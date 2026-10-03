# Dotnote engineering rules

Read `docs/README.md` and the relevant implementation guide before changing behavior.

## Version control and releases

- After completing a deliverable app build, commit the scoped changes, push them to `main`, and run the **Release Dotnote** GitHub Actions workflow (`release.yml`) with `publish=true`. This is standing user authorization for that release push and publication. Do not stop at a local APK or validation-only run unless the user explicitly requests it. Keep the default prerelease channel unless stable publication is requested. Follow the run through completion, fix failures, and return the release link. Never bypass checks or include unrelated work.
- Follow the release workflow in `docs/build-test-release.md`. `android/app/build.gradle.kts` is the single source of truth for the app's `versionName` and increasing Android `versionCode`.
- Advance the version for the change being delivered. Never freeze a version number, reuse an old version for changed APKs, or change historical release records to pretend they were built differently. The import feature baseline is 0.8.0; its fixes are 0.8.1. Future work must choose the next appropriate version.
- Use focused branches/commits. Inspect the working tree first and preserve unrelated uncommitted work. Do not silently stage other work, rewrite history, tag an unverified release, or push without authorization.
- README.md is a permanent, version-free description of the app. Never add per-release sections, version numbers or APK filenames to it; release notes go in CHANGELOG.md only. Edit the README only when a user-facing feature is added, removed or materially changed, and keep its tone and structure.
- Keep CHANGELOG, validation record, APK metadata and versioned artifacts consistent. Historical entries retain their historical versions. The in-app version comes from BuildConfig.
- Run appropriate regression tests, compilation and lint. Package with `scripts/package-release.py`, which checks APK metadata, documentation, signing compatibility and filenames. Never relabel an older binary as a new release.
- A release commit/tag must reproduce its artifact from a clean tree. If preexisting local work prevents that, identify the artifact as a development snapshot and record its source snapshot and dirty state; do not claim it is a clean tagged release.

## Import and drawing changes

- Preserve source files and existing notes. Do not clear app data to test an update.
- Imports must stay bounded in memory as deck length grows. Keep rendering, selection, undo, reopen, export and backup behavior covered by relevant tests.
- Document format changes need backward-compatible defaults or an explicit migration. Never infer that an old single-page PDF is an image without provenance.
