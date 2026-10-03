#!/usr/bin/env python3
"""Check remote history, or publish already verified artifacts. Requires authenticated gh.

No publishing without --publish. Never replaces a tag or an existing release.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
REPO = "yufengliu15/dotnote"


def run(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()


def metadata():
    config = (ROOT / "android/app/build.gradle.kts").read_text()
    return re.search(r'versionName\s*=\s*"([0-9.]+)"', config)[1], int(re.search(r'versionCode\s*=\s*(\d+)', config)[1])


def validate_history(releases, version, code, previous_codes):
    current = tuple(map(int, version.split(".")))
    for release in releases:
        tag = release["tag_name"]
        if re.fullmatch(r"v\d+\.\d+\.\d+", tag):
            if tuple(map(int, tag[1:].split("."))) >= current:
                raise ValueError(f"Version must exceed existing release {tag}; never overwrite it.")
    if code <= max(previous_codes):
        raise ValueError("versionCode must exceed every known delivered build.")


def publish_artifacts(version, commit, files, notes, stable=False):
    with tempfile.TemporaryDirectory() as temp:
        body = Path(temp) / "notes.md"
        body.write_text(notes)
        # Draft first: the updater cannot see a release until every asset is attached.
        run("gh", "release", "create", f"v{version}", "--repo", REPO, "--target", commit,
            "--draft", f"--prerelease={str(not stable).lower()}", "--title", f"Dotnote {version}", "--notes-file", str(body), *map(str, files))
        run("gh", "release", "edit", f"v{version}", "--repo", REPO, "--draft=false",
            f"--prerelease={str(not stable).lower()}", f"--latest={str(stable).lower()}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--publish", type=Path, metavar="ARTIFACT_DIRECTORY")
    parser.add_argument("--stable", action="store_true", help="Explicitly publish a stable release; default is prerelease")
    args = parser.parse_args()
    version, code = metadata()
    # --slurp preserves page boundaries while requesting all release history.
    pages = json.loads(run("gh", "api", "--paginate", "--slurp", f"repos/{REPO}/releases?per_page=100"))
    releases = [release for page in pages for release in page]
    previous_codes = [json.loads((ROOT / "scripts/release-policy.json").read_text())["minimumPreviousVersionCode"]]
    # Delivered local snapshots also consume their version codes, even before upload.
    local_releases = []
    for path in (ROOT / "dist").glob("release-*.json"):
        delivered = json.loads(path.read_text())
        previous_codes.append(int(delivered["versionCode"]))
        local_releases.append({"tag_name": f"v{delivered['versionName']}"})
    for release in releases:
        tag = release["tag_name"]
        if not re.fullmatch(r"v\d+\.\d+\.\d+", tag):
            continue
        for asset in release.get("assets", []):
            if asset["name"] == f"release-{tag[1:]}.json":
                manifest = json.loads(run("gh", "api", "-H", "Accept: application/octet-stream",
                                         f"repos/{REPO}/releases/assets/{int(asset['id'])}"))
                previous_codes.append(int(manifest["versionCode"]))
    validate_history(releases + local_releases, version, code, previous_codes)
    tags = json.loads(run("gh", "api", "--paginate", "--slurp", f"repos/{REPO}/git/matching-refs/tags/v{version}"))
    if any(ref["ref"] == f"refs/tags/v{version}" for page in tags for ref in page):
        raise SystemExit("Tag already exists. Inspect the earlier attempt; do not overwrite it.")
    print(f"Remote history permits {version} / {code}.")
    if args.publish is None:
        return
    directory = args.publish.resolve()
    if run("git", "status", "--porcelain"):
        raise SystemExit("Publishing requires a clean checkout.")
    manifest = json.loads((directory / f"release-{version}.json").read_text())
    expected = (version, code, run("git", "rev-parse", "HEAD"), [], False)
    actual = tuple(manifest[k] for k in ("versionName", "versionCode", "gitCommit", "dirtyState", "debuggable"))
    if actual != expected:
        raise SystemExit("Artifacts must be a non-debuggable clean build of this exact commit.")
    files = [directory / f"dotnote-{version}.apk", directory / f"dotnote-{version}-source.zip"]
    for path, field in zip(files, ("apkSha256", "sourceSha256")):
        if hashlib.sha256(path.read_bytes()).hexdigest() != manifest[field]:
            raise SystemExit(f"Artifact hash mismatch: {path.name}")
    # The package checker revalidates metadata, pinned certificate, sources and docs.
    run("python3", "scripts/package-release.py", "--check", "--require-release", "--apk", str(files[0]))
    files.extend([directory / f"release-{version}.json", directory / f"SHA256SUMS-{version}"])
    changelog = (ROOT / "CHANGELOG.md").read_text().split(f"## {version}", 1)[1].split("\n## ", 1)[0]
    notes = f"Dotnote {version} (Android build {code})\n\n" + changelog.split("\n", 1)[1].strip()
    notes += f"\n\nBuilt from {manifest['gitCommit']}. Install the APK as an update; keep your existing app data.\n"
    publish_artifacts(version, manifest["gitCommit"], files, notes, stable=args.stable)
    print(f"Published https://github.com/{REPO}/releases/tag/v{version}")


if __name__ == "__main__":
    main()
