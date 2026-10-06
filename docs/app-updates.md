# App updates and free distribution

[Documentation index](README.md)

## User flow

Install the first APK from the [Dotnote releases page](https://github.com/yufengliu15/dotnote/releases). In the library or editor, choose **Version & updates → Check for updates → Download update → Install update**. After the check, and before anything downloads, the dialog shows **What's new**: the release notes of every release newer than the installed build up to the offered one, newest first (same draft/preview filtering as the check). Close skips the update. No GitHub account, Google Drive, or separate updater app is required. Existing installations before 0.11.0 need one manual APK update to acquire this feature.

Checks are manual: no network request delays notebook startup. Stable releases are the default; **Include preview releases** opts into GitHub prereleases for this dialog session. The checker examines the most recent 100 releases, ignores drafts, compares numerical three-part versions, and requires the release's manifest and APK. Older releases without a manifest cannot be installed through this updater. Release notes are parsed from each release body as plain bullets: the "Dotnote x (Android build n)" title and the "Built from" trailer are dropped, wrapped lines are joined and Markdown code/emphasis marks removed; at most 12 releases and 12 items per release are shown. A missing/broken release fails visibly rather than falling back to an unverified APK.

Downloading and verifying happens off the UI thread. APKs are streamed with a 150 MiB limit, JSON responses with a 2 MiB limit, HTTPS-only GitHub redirects and network timeouts. Canceling or rotating the dialog cancels the operation and removes a partial download; reopen the dialog and check again. A successful download replaces old cache files. The version/code, SHA-256, package ID and current signing certificates must match. Before installation, verification runs again and the active note/settings are saved. A save failure prevents installer launch.

Android may require enabling **Allow from this source** for Dotnote. After returning, tap Install update again. The system installer requires user confirmation; a canceled install can be retried. The app never disables Play Protect or promises to avoid its warnings. Only the `cache/updates/` directory is shared through the nonexported FileProvider with a temporary read grant; vaults and credentials are not exposed.

## Signing compatibility

The initial distribution channel retains the existing personal debug certificate to update existing installations without deleting data. New distribution APKs use the non-debuggable release build type, explicitly configured with the existing key. This is a compatibility bridge, not a claim of production/Play signing. Never replace the key or regenerate a CI debug keystore for distribution. `scripts/release-policy.json` pins the known certificate and the highest pre-automation delivered code (18). Any future key migration needs explicit planning and device validation; the updater currently rejects changed signers, including rotation.

The original key must be backed up privately. Configure these GitHub **release** environment secrets once, from the existing matching keystore:

- `DOTNOTE_KEYSTORE_BASE64`: base64-encoded keystore (encoding is not encryption; use the secret field).
- `DOTNOTE_STORE_PASSWORD`, `DOTNOTE_KEY_ALIAS`, `DOTNOTE_KEY_PASSWORD`.

Do not paste secrets into issues, logs, source, or chat. Use protected environment access and trusted workflow changes. PR workflows must never receive this signing key.

## Publishing

The manual **Release Dotnote** GitHub Actions workflow runs only against `main`. Use **Actions → Release Dotnote → Run workflow** on `main`. **Publish release** is checked by default: a successful run creates the versioned GitHub Release and uploads the signed APK, source archive, checksums and update manifest automatically. Uncheck it only when you specifically want a validation-only run. Releases are **prereleases by default**. Select **stable** only when a stable release is explicitly requested; only stable releases are marked Latest. To see prereleases in Dotnote, enable **Include preview releases** in the update dialog. Ordinary commits do not publish APKs. Validation artifacts are retained for seven days. The release environment permits only `main`; signing credentials are configured as environment secrets.

Before running it, update `android/app/build.gradle.kts` with a fresh display version and strictly increasing code, update CHANGELOG, README, documentation index and VALIDATION with actual local checks. Include all intended source changes in reviewed commits. A failed delivery must not be disguised by replacing a historical APK.

The workflow checks all remote release manifests and the pinned local baseline for increasing codes, rejects existing versions/tags (including draft attempts), builds/tests/lints, runs isolated update/native/vault suites on a disposable emulator, and calls `scripts/package-release.py --require-release` on the signed release APK. Packaging checks the certificate even when no previous local APK exists. Generated artifacts go outside the checkout, leaving its source state clean. Reports are retained in Actions; release assets contain the APK, source archive, versioned checksums and manifest with exact commit and hashes. Publishing creates a draft with all assets before making it visible to the updater. If publication fails halfway, inspect the draft/tag; the workflow deliberately refuses to overwrite it.

The source archive includes workflow and release scripts but no keys. One release job runs at a time. Release tags refer to the tested clean commit; no dirty snapshots can be published by this workflow. Delivered local snapshot manifests also reserve their version codes: commit those records, and advance the version/code for the next published build. Local development snapshots remain possible using `package-release.py --allow-dirty`; they must be labeled honestly and must use their own new version.

For local non-debuggable builds, set `DOTNOTE_KEYSTORE`, `DOTNOTE_STORE_PASSWORD`, `DOTNOTE_KEY_ALIAS`, and `DOTNOTE_KEY_PASSWORD` in the environment, then run `:app:assembleRelease :app:lintRelease`. Do not embed credentials in committed commands. `publish-release.py` without arguments checks remote history only; `--publish <artifact-directory>` publishes a prerelease. Add `--stable` only for an explicitly requested stable release. Both are external publishing actions.

## Acceptance

- Unit tests: numeric version ordering, preview/draft filtering, malformed/missing assets, host restrictions, download bounds/cancellation, APK identity/signer mismatch, remote version reuse.
- Android tests: update-cache read grant and private-file exclusion, corrupt digest and same-version APK rejection.
- Device acceptance: install over an earlier APK without clearing data; verify notes, PDFs and widgets; check offline/rate-limit failure; exercise denied/granted unknown-source permission, installer cancellation, and successful update.
- Hosted Actions and physical-tablet acceptance require separate evidence. A local build is not proof the hosted workflow or Play Protect prompt behavior has been verified.
