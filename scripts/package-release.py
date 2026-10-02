#!/usr/bin/env python3
"""Validate and package the exact built APK and workspace source snapshot.

Use --allow-dirty only for an explicitly labeled development snapshot. No Git mutation.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def run(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def certificate_digest(report):
    digests = set(re.findall(
        r"^Signer (?:#\d+|\([^()\r\n]+\)) certificate SHA-256 digest: ([0-9a-fA-F]{64})[ \t]*$",
        report, re.M,
    ))
    if len(digests) != 1:
        raise ValueError("Expected exactly one signing identity in apksigner output:\n" + report)
    return digests.pop().lower()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--allow-dirty", action="store_true")
    parser.add_argument("--check", action="store_true", help="Validate only; do not package")
    parser.add_argument("--apk", type=Path, help="Explicit signed APK (defaults to local debug build)")
    parser.add_argument("--output-dir", type=Path, help="Artifact directory (CI uses a directory outside the checkout)")
    parser.add_argument("--require-release", action="store_true", help="Reject debuggable APKs and dirty sources")
    args = parser.parse_args()
    if args.require_release and args.allow_dirty:
        raise SystemExit("Published releases cannot allow dirty sources.")
    config = (ROOT / "app/build.gradle.kts").read_text()
    version = re.search(r'versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"', config)[1]
    code = int(re.search(r'versionCode\s*=\s*(\d+)', config)[1])
    apk = (args.apk or ROOT / "app/build/outputs/apk/debug/app-debug.apk").resolve()
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "")
    build_tools = sdk / "build-tools" / "36.0.0"
    if not all((build_tools / tool).is_file() for tool in ("aapt", "apksigner")):
        raise SystemExit("Set ANDROID_HOME and install Android SDK build-tools;36.0.0.")
    metadata = run(str(build_tools / "aapt"), "dump", "badging", str(apk))
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", metadata)
    if not package or package.groups() != ("dev.dotnote.app", str(code), version):
        raise SystemExit("APK metadata does not match Gradle. Rebuild before packaging.")
    if args.require_release and "application-debuggable" in metadata:
        raise SystemExit("Published APK must not be debuggable.")
    build_inputs = list((ROOT / "app/src/main").rglob("*")) + [
        ROOT / p for p in ("app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts", "gradle.properties")
    ]
    if any(p.is_file() and p.stat().st_mtime_ns > apk.stat().st_mtime_ns for p in build_inputs):
        raise SystemExit("Production source changed after the APK was built. Rebuild before packaging.")
    readme = (ROOT / "README.md").read_text()
    if f"Install `dist/dotnote-{version}.apk`" not in readme:
        raise SystemExit("README install link does not match the current version.")
    if re.search(r"^## (\d+\.\d+\.\d+)", (ROOT / "CHANGELOG.md").read_text(), re.M)[1] != version:
        raise SystemExit("The first CHANGELOG entry must match the current version.")
    if not (ROOT / "VALIDATION.md").read_text().startswith(f"# {version} "):
        raise SystemExit("The current validation record must match the release version.")

    def certificate(path):
        report = run(str(build_tools / "apksigner"), "verify", "--print-certs", str(path))
        return certificate_digest(report)

    cert = certificate(apk)
    policy = json.loads((ROOT / "scripts/release-policy.json").read_text())
    if cert != policy["certificateSha256"]:
        raise SystemExit("Signing identity does not match the pinned installed-app certificate.")
    if code <= policy["minimumPreviousVersionCode"]:
        raise SystemExit("versionCode must exceed the delivered baseline.")
    dist = (args.output_dir or ROOT / "dist").resolve()
    previous = sorted((p for p in (ROOT / "dist").glob("dotnote-*.apk")
                       if re.fullmatch(r"dotnote-\d+\.\d+\.\d+\.apk", p.name)),
                      key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.name)))
    if previous:
        old = previous[-1]
        old_meta = run(str(build_tools / "aapt"), "dump", "badging", str(old))
        old_code = int(re.search(r"versionCode='(\d+)'", old_meta)[1])
        if code <= old_code and old.name != f"dotnote-{version}.apk":
            raise SystemExit("versionCode must increase over the previous packaged APK.")
        if certificate(old) != cert:
            raise SystemExit("Signing certificate differs from the previous APK; update would fail.")
    dirty = run("git", "status", "--porcelain")
    if dirty and not args.allow_dirty:
        raise SystemExit("Working tree is dirty. Commit the release or use --allow-dirty for a development snapshot.")
    print(f"Verified Dotnote {version} (code {code}), certificate {cert}")
    if args.check:
        return
    apk_output = dist / f"dotnote-{version}.apk"
    source_output = dist / f"dotnote-{version}-source.zip"
    manifest = dist / f"release-{version}.json"
    checksums = dist / f"SHA256SUMS-{version}"
    if any(p.exists() for p in (apk_output, source_output, manifest, checksums)):
        raise SystemExit("Versioned artifacts already exist. Do not overwrite a delivered version.")
    dist.mkdir(parents=True, exist_ok=True)
    files = run("git", "ls-files", "--cached", "--others", "--exclude-standard", "-z").split("\0")
    with zipfile.ZipFile(source_output, "w", zipfile.ZIP_DEFLATED) as archive:
        for name in sorted(set(files)):
            path = ROOT / name
            allowed = name.startswith(("app/src/", "app/schemas/", "docs/", "scripts/", "gradle/", ".github/workflows/")) or name in {
                "AGENTS.md", "README.md", "CHANGELOG.md", "VALIDATION.md", "TODO.md", ".gitignore",
                "app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
            }
            if name and allowed and path.is_file():
                archive.write(path, "dotnote/" + name)
    shutil.copyfile(apk, apk_output)
    manifest.write_text(json.dumps({
        "versionName": version, "versionCode": code,
        "artifactType": "development snapshot" if dirty else "clean working tree build",
        "gitCommit": run("git", "rev-parse", "HEAD"),
        "gitBranch": run("git", "branch", "--show-current"),
        "dirtyState": dirty.splitlines(),
        "certificateSha256": cert,
        "apkSha256": sha(apk_output), "sourceSha256": sha(source_output),
        "debuggable": "application-debuggable" in metadata,
    }, indent=2) + "\n")
    checksums.write_text("".join(f"{sha(p)}  {p.name}\n" for p in (apk_output, source_output, manifest)))
    apk_alias = "dotnote-debug.apk" if "application-debuggable" in metadata else "dotnote.apk"
    for source, name in ((apk_output, apk_alias), (source_output, "dotnote-source.zip")):
        shutil.copyfile(source, dist / name)
    (dist / "SHA256SUMS").write_text("".join(
        f"{sha(dist / name)}  {name}\n" for name in (apk_alias, "dotnote-source.zip")
    ))
    print(f"Packaged {apk_output} ({'development snapshot' if dirty else 'clean tree'})")


if __name__ == "__main__":
    main()
