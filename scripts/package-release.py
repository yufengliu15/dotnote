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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--allow-dirty", action="store_true")
    parser.add_argument("--check", action="store_true", help="Validate only; do not package")
    args = parser.parse_args()
    config = (ROOT / "app/build.gradle.kts").read_text()
    version = re.search(r'versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"', config)[1]
    code = int(re.search(r'versionCode\s*=\s*(\d+)', config)[1])
    apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "")
    builds = [p for p in (sdk / "build-tools").glob("*") if (p / "aapt").is_file()]
    if not builds:
        raise SystemExit("Set ANDROID_HOME to the SDK containing aapt and apksigner.")
    build_tools = max(builds, key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.name)))
    metadata = run(str(build_tools / "aapt"), "dump", "badging", str(apk))
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", metadata)
    if not package or package.groups() != ("dev.dotnote.app", str(code), version):
        raise SystemExit("APK metadata does not match Gradle. Rebuild before packaging.")
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
        return re.search(r"Signer #1 certificate SHA-256 digest: (\w+)", report)[1]

    cert = certificate(apk)
    dist = ROOT / "dist"
    previous = sorted((p for p in dist.glob("dotnote-*.apk")
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
    dist.mkdir(exist_ok=True)
    files = run("git", "ls-files", "--cached", "--others", "--exclude-standard", "-z").split("\0")
    with zipfile.ZipFile(source_output, "w", zipfile.ZIP_DEFLATED) as archive:
        for name in sorted(set(files)):
            path = ROOT / name
            allowed = name.startswith(("app/src/", "app/schemas/", "docs/", "scripts/", "gradle/")) or name in {
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
    }, indent=2) + "\n")
    checksums.write_text("".join(f"{sha(p)}  {p.name}\n" for p in (apk_output, source_output, manifest)))
    for source, name in ((apk_output, "dotnote-debug.apk"), (source_output, "dotnote-source.zip")):
        shutil.copyfile(source, dist / name)
    (dist / "SHA256SUMS").write_text("".join(
        f"{sha(dist / name)}  {name}\n" for name in ("dotnote-debug.apk", "dotnote-source.zip")
    ))
    print(f"Packaged {apk_output.relative_to(ROOT)} ({'development snapshot' if dirty else 'clean tree'})")


if __name__ == "__main__":
    main()
