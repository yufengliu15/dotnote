# 0.11.1 release automation validation

App version **0.11.1 / code 20**, October 1, 2026. This change advances the delivered 0.11.0 snapshot without altering updater or notebook behavior. The setup uses separate clean commits for earlier storage/preview work and the updater; unrelated TODO edits and transfer artifacts are excluded.

- Local Python release-history tests, Python syntax and workflow YAML checks pass. The existing 0.11.0 runtime evidence below covers unchanged app behavior; hosted build/test/lint/package results will be recorded by the GitHub Actions run for this exact commit.
- Protected `release` environment secrets preserve the installed signing identity and are available only to main. Workflow dispatch defaults to validation only; publishing requires the explicit Publish release checkbox.
- The first hosted attempt failed during SDK setup because the action default requested the removed `tools` package. The workflow now explicitly installs platform-tools, Android 36 and build-tools 36.0.0; no APK was built or published by that failed attempt.
- The second hosted attempt passed build, unit tests, lint and all 11 emulator tests, then failed parsing signing-tool output during packaging. Packaging now pins SDK build-tools 36.0.0 and tests both numbered and SDK-range signer output while rejecting absent/multiple identities. No release was published.
- Hosted validation is pending for this commit. No 0.11.1 APK, tag or GitHub release is claimed before that run succeeds. Hosted logs/artifacts are the canonical additional evidence and should not be retroactively inserted into the source commit they validate.

---

# 0.11.0 in-app update validation

Validated October 1, 2026 using the cached JDK 20 / Android SDK 36 toolchain and disposable Android 16 ARM64 tablet emulator. App version **0.11.0 / code 19**.

- Debug app and instrumentation assembly, explicitly signed non-debuggable release assembly, all **44 JVM tests**, and debug/release lint passed. Both lint reports have **0 errors / 49 warnings**. Four Python release-history tests, Python syntax checks, workflow YAML parsing, and `git diff --check` passed.
- **11 focused Android tests passed**: AppUpdateTest (2), NativePipelineTest (2), VaultPipelineTest (4), VaultLifecycleTest (3), in separate instrumentation processes. AppUpdateTest passed again against the non-debuggable release APK after an in-place install over the debug build.
- Updated the emulator from 0.10.1/code 18 to 0.11.0/code 19 without uninstalling or clearing data. All **175 private files** in the app's files directory matched byte-for-byte immediately after the update. Existing native/PDF/vault/migration regressions passed afterward.
- Updater regressions cover numeric versions, preview/draft filtering, malformed manifest and asset locations, restricted HTTPS hosts, bounded/canceled streaming, wrong package/version/signing identity, private-file exclusion from the update FileProvider, corrupt downloads, and same-version APK rejection. Release-history tests reject reused versions, lower codes and duplicate drafts.
- Visually inspected the Version & updates dialog at 1280×800. A manual network check entered its progress state, but the session was interrupted to install the release build; live check completion, permission-settings return, canceled/successful system-installer UI and physical-tablet Play Protect behavior remain unverified. There is no newer published release with which to complete an end-to-end self-update.
- APK metadata is 0.11.0/code 19, package dev.dotnote.app, and non-debuggable. Its signing certificate matches the historical installed-app identity: SHA-256 `825739c9e1b77f0276094938143f07b6a51e636344c076fa90f6caad869d9744`. This intentionally retains the old debug certificate as a compatibility bridge; it is not Play Store signing.
- Read-only GitHub preflight accepted 0.11.0/code 19 before local packaging. The hosted GitHub Actions workflow has not run. Activation requires reviewed commits/push, the protected release signing secrets, and an explicit workflow dispatch. Local snapshot manifests reserve their version and code for the next publication; do not reuse 0.11.0 for a changed clean build.

Package using `scripts/package-release.py --allow-dirty --apk app/build/outputs/apk/release/app-release.apk`: this is a **development snapshot** on `codex/in-app-updates-0.11.0`. Preexisting 0.9.2–0.10.1 source changes, TODO edits and `.perf-transfer.bundle` are preserved. No commit, tag, push, signing-key upload or GitHub publication is claimed. Artifacts: `dist/dotnote-0.11.0.apk`, source ZIP, `dist/release-0.11.0.json`, and `dist/SHA256SUMS-0.11.0`; current alias is `dist/dotnote.apk`. The manifest records the actual dirty source state and source/APK hashes.

Evidence: `/private/tmp/dotnote-0.11.0-build.log`, `dotnote-0.11.0-release-build.log`, `dotnote-0.11.0-android-tests.log`, `dotnote-0.11.0-release-tests.log`, `dotnote-0.11.0-package.log`; updater screenshot `/private/tmp/dotnote-update-dialog.png`. Source code and tests do not modify the note format, drawing, backup credentials or source PDFs.

---

# 0.10.1 long-note preview validation

Validated October 1, 2026 with cached JDK 20 / Android SDK 36 and the existing disposable Android 16 ARM64 emulator. App version **0.10.1 / code 18**.

- Debug app and instrumentation assembly, all **38 JVM tests**, lint and `git diff --check` passed. Lint reports 0 errors and 40 warnings.
- All **3 NotePreviewTest Android tests passed**. The 40-page fixture now checks a readable first-page width, visible annotations and paper outside the page. Every later PDF asset is deliberately missing, proving the preview never opens them. Existing centering/camera-preservation, blank note, cache reuse, saved-edit invalidation and vault-isolation checks also passed.
- Scenes taller than three times their width with a non-image PDF fit the first PDF page in scene order. Only that page and intersecting non-PDF annotations render, clipped to its bounds. Ordinary scenes retain whole-note previews. No document format, saved camera, editor behavior or source files change. Physical-tablet acceptance remains unverified.

Packaged using `scripts/package-release.py --allow-dirty` as a **development snapshot** on `codex/note-previews-0.10.0`, preserving all preexisting work. No commit, tag or push. Artifacts: `dist/dotnote-0.10.1.apk`, `dist/dotnote-0.10.1-source.zip`, `dist/release-0.10.1.json`, `dist/SHA256SUMS-0.10.1`. The package manifest records source state and hashes; packaging verifies the increased version code and previous signing identity.

Evidence: `/private/tmp/dotnote-0.10.1-build.log`, `/private/tmp/dotnote-0.10.1-previews.log`, `/private/tmp/dotnote-0.10.1-package.log`. Emulator installation used updates without clearing app data.

---

# 0.10.0 note-preview validation

Validated October 1, 2026 with cached JDK 20 / Android SDK 36 and the disposable Android 16 ARM64 tablet emulator (1280×800, skiagl). App version **0.10.0 / code 17**.

- Debug app/instrumentation assembly, all **38 JVM tests**, and lint passed (**0 errors, 40 warnings**, including one optional KTX suggestion for snapshot bitmap creation). `git diff --check` passed.
- **9 focused Android tests passed**: NotePreviewTest (3), NativePipelineTest (2), FlingNavigationTest (3), and TextTemplateTest.textRendersAfterTransformAndInPdfExport (1). Pixel checks cover off-origin centering, unchanged saved camera, blank notes and a 40-page deck below the editor minimum zoom. Cache checks cover reuse, saved-edit invalidation, vault isolation and unchanged stored content. Existing native ink/PDF/backup, editor fit/navigation and text export checks passed.
- Visually inspected the actual library grid: centered ink, shapes, text and imported-image content appear in the cards, with quiet empty cards for blank notes. Screenshot: `/private/tmp/dotnote-0.10.0-grid.png`. Physical-tablet performance remains unverified.
- Initial Android test compilation used the wrong PDF close helper; corrected to the existing `useDocument`. The initial cache fixture incorrectly changed content without changing its revision; corrected to use `dao.save` with a new modification time. Final test builds and runs passed. No application data was cleared.

Source branch: `codex/note-previews-0.10.0`. Preexisting 0.9.2 storage work, TODO edits and `.perf-transfer.bundle` are preserved. Package with `scripts/package-release.py --allow-dirty` as an explicit **development snapshot**, not a clean tagged release. The manifest records the base commit, dirty paths, source/APK hashes and signing certificate. No commit, tag or push is claimed. Artifacts: `dist/dotnote-0.10.0.apk`, `dist/dotnote-0.10.0-source.zip`, `dist/release-0.10.0.json` and `dist/SHA256SUMS-0.10.0`. The package script verifies increasing metadata and signing compatibility with the previous APK.

Evidence: `/private/tmp/dotnote-0.10.0-build.log`, `dotnote-0.10.0-test-build.log`, `dotnote-0.10.0-preview-native.log`, `dotnote-0.10.0-fit.log`, `dotnote-0.10.0-text.log`, and `dotnote-0.10.0-package.log` in the same directory. Earlier failed fixture evidence is in `dotnote-0.10.0-previews.log`.

---

# 0.9.2 large-note storage validation

Validated October 1, 2026 with the cached JDK 20 / Android SDK 36 toolchain and the existing disposable Android 16 / API 36 ARM64 tablet emulator (1280×800, skiagl). App version **0.9.2 / code 16**.

- Debug app and instrumentation APK assembly, compilation, all **38 JVM tests**, and lint passed. Lint: **0 errors, 39 warnings**. `git diff --check` passed. Room schema 2 and its identity hash are unchanged; no migration or app-data clearing was required.
- **28 focused Android tests passed**: LargeNoteStorageTest (2), VaultPipelineTest (4), VaultLifecycleTest (3), NativePipelineTest (2), StartupLoadingTest (2), GesturePipelineTest (2), EditorUpdateTest (3), WidgetPipelineTest (4), and TextTemplateTest (6, isolated method invocations).
- A valid Unicode document over **4 MiB** reproduces `SQLiteBlobTooBigException` using the old full-row SELECT. The same existing note passes chunked single/all-note reads, save/no-op timestamp retention, note/folder rename and move, snapshot, ZIP backup/merge restore, file-authoritative reopening and delete-to-trash. A second preexisting small note survives unchanged. Boundary tests retain supplementary Unicode at the 65,536-code-point boundary and handle empty/exact-length documents and missing IDs.
- Save/read operations reconstruct content from bounded SQLite substrings in one Room transaction, so metadata and chunks share a consistent database snapshot. The fix also applies to legacy migration and backup reads. Native ink, selection/undo, rendering/PDF export, templates, widgets, migration, vault switching and journal/Git snapshot regressions passed. Documents still require memory proportional to their content; this does not remove the existing 64 MiB vault-file read limit or certify arbitrary large-note performance.
- The initial combined TextTemplateTest run hit a dialog accessibility failure and the documented emulator `libhwui.so` RenderThread crash. Isolated method runs passed. A retained fixture with the same template title made the backup test ambiguous; that test now uses a temporary isolated vault, restores the prior selection and deletes only its own fixture. Its final run passed. These initial failures are retained in the evidence logs; no all-in-one connected-suite pass is claimed.

Release source: focused branch `codex/large-note-cursorwindow-0.9.2`. Existing `TODO.md` edits and `.perf-transfer.bundle` remain untouched. Packaging uses `scripts/package-release.py --allow-dirty`: an explicit **development snapshot**, with the base commit, dirty paths and exact source/APK hashes in `dist/release-0.9.2.json`. No tag or push is claimed. Artifacts: `dist/dotnote-0.9.2.apk`, `dist/dotnote-0.9.2-source.zip`, `dist/SHA256SUMS-0.9.2` and the manifest. The packaging script verifies APK metadata and certificate compatibility against 0.9.1: SHA-256 `825739c9e1b77f0276094938143f07b6a51e636344c076fa90f6caad869d9744`.

Evidence: `/private/tmp/dotnote-0.9.2-build.log`, `/private/tmp/dotnote-0.9.2-test-fixture-final-build.log`, `/private/tmp/dotnote-0.9.2-storage-tests.log`, class-named `/private/tmp/dotnote-0.9.2-*.log` results, isolated `/private/tmp/dotnote-0.9.2-text-*.log` results (including `text-template-backup-final`), and `/private/tmp/dotnote-0.9.2-package.log`.

Physical-tablet acceptance remains unverified. Install as an update without uninstalling or clearing data, then reopen the affected note and make a small edit to confirm saving.

---

# 0.9.1 text reflow and local startup validation

Validated October 1, 2026 with the cached JDK 20 / Android SDK 36 toolchain and the existing disposable Android 16 / API 36 ARM64 tablet emulator (1280×800, skiagl). App version **0.9.1 / code 15**.

- Debug app and instrumentation APK assembly, compilation, all **38 JVM tests**, and lint passed. Lint: **0 errors, 39 warnings**. `git diff --check` passed.
- **46 focused Android tests passed** on the final APK in separate instrumentation invocations: StartupLoadingTest (2), TextTemplateTest (6), NativePipelineTest (2), VaultPipelineTest (4), VaultLifecycleTest (3), GesturePipelineTest (2), EditorUpdateTest (3), FlingNavigationTest (3), HighlighterPerformanceTest (2), VectorPerformanceTest (2), InkStartupTest (1), WidgetPipelineTest (4), NotesRoleTest (5), DocumentImportPipelineTest (5), GitHubSignInLifecycleTest (2). The two calendar UI tests were not rerun; all calendar JVM rules passed.
- The text UI regression widens soft-wrapped “like this” from two lines to one without changing font size or existing visual scale, fits the rectangle height to the resulting lines, retains wrapping width through editing/font changes, saves/reopens, and checks undo/redo. Scale grows and shrinks proportionally while preserving text and wrapping; explicit newlines remain paragraph breaks. Existing text rendering/PDF, template, attachment, backup, migration, drawing and image/deck tests also pass. No document-format migration or app-data clearing was needed.
- Startup waits for local file validation and Room index rebuilding, not a repository network exchange. A held local-vault mutex test verifies “Opening local vault…” and no empty library flashing behind the overlay; the library appears after initialization. Backup scheduling now runs off the UI thread after local readiness. Files remain authoritative, attachment/format validation remains complete, and stale Room entries are repaired from files.
- A generated **160-note / 32,000-object** fixture measured Store reopening at **1,226.324 ms** on the installed 0.9.0 baseline. The first updated run measured **844.522 ms**; the final 0.9.1 run measured **863.281 ms** (about **30% less time**). Final startup phases were **825.297 ms scan / 36.787 ms index**. These are individual paired emulator measurements, not physical-tablet latency guarantees. The scanner avoids a second JSON parse and allocating every renderable scene; the derived index uses batch inserts within one transaction. Debug timing logs contain counts/timings, not note content or credentials.

Release source: focused branch `codex/text-reflow-startup-0.9.1`. Preexisting edits in `TODO.md` are preserved and excluded from the implementation commits. Packaging uses `scripts/package-release.py --allow-dirty`: this is explicitly a **development snapshot**, with its exact source commit, dirty paths and source/APK hashes recorded in `dist/release-0.9.1.json`, not a clean tagged release. No tag or push is claimed. Artifacts are `dist/dotnote-0.9.1.apk`, `dist/dotnote-0.9.1-source.zip`, `dist/SHA256SUMS-0.9.1` and the manifest. The signing certificate is checked against the previous distributable: SHA-256 `825739c9e1b77f0276094938143f07b6a51e636344c076fa90f6caad869d9744`.

Evidence: `/private/tmp/dotnote-0.9.1-final-build.log`, `/private/tmp/dotnote-0.9.1-final-StartupLoadingTest.log`, `/private/tmp/dotnote-0.9.1-final-TextTemplateTest.log`, class-named `/private/tmp/dotnote-0.9.1-final-*.log` regression results, and `DotnoteStartupTest` / `DotnoteStartup` emulator logcat timings. Baseline: `/private/tmp/dotnote-0.9.1-startup-baseline.log`. Visually inspected captures: `dist/preview/text-resize-0.9.1.png` and `dist/preview/text-scale-0.9.1.png`.

Physical-tablet loading, OEM stylus behavior and live GitHub networking remain unverified. Existing notes, source files, signing identity and historical artifacts are retained. Install as an update without uninstalling or clearing data.

---

# 0.9.0 text and template validation

Validated September 30, 2026 with the cached JDK 20 / Android SDK 36 toolchain and the existing disposable Android 16 / API 36 ARM64 tablet emulator (1280×800, skiagl). App version **0.9.0 / code 14**.

- Debug app and instrumentation APK assembly, compilation, all **37 JVM tests**, and lint passed. Lint: **0 errors, 39 warnings** (dependency/update, XML/layout, preferences and canvas accessibility suggestions); no warning originates in the new text/dialog implementation. `git diff --check` passed.
- **41 focused Android tests passed** in separate instrumentation invocations: TextTemplateTest (5), NativePipelineTest (2), VaultPipelineTest (4), VaultLifecycleTest (3), GesturePipelineTest (2), EditorUpdateTest (3), FlingNavigationTest (3), HighlighterPerformanceTest (2), VectorPerformanceTest (2), InkStartupTest (1), WidgetPipelineTest (4), NotesRoleTest (5), DocumentImportPipelineTest (5). Calendar and GitHub sign-in lifecycle tests were not rerun.
- New coverage exercises real stylus/finger text taps at 200% zoom, cancellation, rotation, accessible dialog entry/save, editing with a stable object ID, selection/movement/resizing/recolor/deletion, undo/redo/reopen, Unicode multiline screen and PDF rendering, template creation/picker UI, source/copy independence with fresh IDs, shared PDF attachments, grid/camera retention, file-to-index rebuild, ZIP backup/restore metadata, and non-destructive Room schema 1→2 migration. Existing notes default to non-templates; no app data was cleared.
- Existing drawing, large-deck import, selectable-image, native-ink, vault/Git snapshot, widget and quick-note regressions passed. The initial new UI test raced the dialog accessibility window; it now waits for an editable field. The legacy-vault fixture now registers the same migration as production. Both corrected tests passed; these were test setup failures, not destructive migration workarounds.

Release source: focused branch `codex/text-templates-0.9.0`, starting from a clean working tree. The validated changes are committed locally before packaging; no tag or push is claimed. `scripts/package-release.py` verifies app metadata, documentation, increasing versionCode and certificate compatibility, then packages `dist/dotnote-0.9.0.apk`, `dist/dotnote-0.9.0-source.zip`, `dist/SHA256SUMS-0.9.0` and `dist/release-0.9.0.json`. The manifest identifies the exact source commit, clean source state and artifact hashes. Debug certificate SHA-256: `825739c9e1b77f0276094938143f07b6a51e636344c076fa90f6caad869d9744`, matching the previous distributable.

Evidence: `/private/tmp/dotnote-0.9.0-final-build.log`, `/private/tmp/dotnote-0.9.0-text-template-tests.log`, and the class-named `/private/tmp/dotnote-0.9.0-*.log` regression results. UI captures: `dist/preview/text-dialog-0.9.0.png` and `dist/preview/template-dialog-0.9.0.png`.

Physical tablet/keyboard/OEM stylus behavior remains unverified. Text uses the device's installed fonts and a maximum line width of 4,096 world units; older builds cannot read notes containing the new TEXT kind. Templates are reusable editable notes within a vault; selecting a different vault offers that vault's templates. Existing source notes/files and historical artifacts are retained.

---

# 0.8.1 import and image-selection fixes

Validated September 30, 2026 with JDK 20 (bytecode target 17), Android SDK 36 and the disposable Android 16 / API 36 ARM64 emulator. Current application metadata is **0.8.1 / code 13**. The import feature baseline is 0.8.0; the initial metadata mistake is preserved in the historical record below and explained in CHANGELOG.md.

- Debug app and instrumentation APK builds passed; all **35 JVM tests** passed; lint passed with **0 errors, 39 warnings**.
- **17 focused Android tests passed**, in separate invocations where appropriate: DocumentImportPipelineTest (5), NativePipelineTest (2), GesturePipelineTest (2), EditorUpdateTest (3), HighlighterPerformanceTest (2), VectorPerformanceTest (2), InkStartupTest (1).
- The image-heavy regression imports 24 slides containing 2048×1152 imagery (56.6 million decoded pixels in total), exceeding the old 32 Mi-pixel limit. It verifies every page and first/last-page colors. Slides now rasterize and stream to PDF one at a time; shared package parts count once toward expanded-content limits.
- Image tests verify tap selection, drag/move, corner resize, deletion, undo/redo, saved transforms after reopening, eraser protection, backup persistence, and moved/resized placement in exported PDF. Ordinary PDFs and converted PowerPoint slides remain locked. Older images need reimport because the old build stored no origin flag.
- APK metadata/signing and versioned packaging are checked by `scripts/package-release.py`. Delivery: `dist/dotnote-0.8.1.apk`, `dist/dotnote-0.8.1-source.zip`, `dist/SHA256SUMS-0.8.1` and `dist/release-0.8.1.json`; current aliases are copied from the same outputs.

Source control: branch `codex/import-fixes-0.8.1`. This workspace already contained uncommitted calendar/widget and import work; those changes are preserved. The packaged APK is an explicitly recorded **development snapshot**, not a clean tagged release. Its manifest records the base commit, dirty paths, certificate and artifact/source hashes. No tag or push is claimed.

Evidence: `/private/tmp/dotnote-0.8.1-final-build.log`, `/private/tmp/dotnote-0.8.1-import-tests.log`, `/private/tmp/dotnote-0.8.1-final-import-tests.log`, `/private/tmp/dotnote-0.8.1-gesture-tests.log` and the four class-named drawing-test logs. Physical-tablet behavior and the user's actual deck remain unverified; the reported failure is covered by a generated deck that exceeds the former limit. Converted slides are lossless raster pages capped at 2048 pixels on their longest edge; device fonts and the existing limited PowerPoint feature coverage still apply.

---

# Image and offline PowerPoint import validation (0.8.0 feature baseline)

Validated September 30, 2026 using the cached JDK 20 / Android SDK 36 toolchain and the disposable Android 16 / API 36 ARM64 emulator. This is a development build retaining the current 0.7.1 / code 11 version; existing release artifacts were not replaced.

- `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and all 34 JVM tests passed.
- `:app:lintDebug` passed with 0 errors and 38 warnings.
- `DocumentImportPipelineTest` passed all 4 tests: image pixels/aspect ratio and backup, JPEG EXIF rotation and bounded decoding, PPTX presentation order/slide dimensions/layout-inherited text/embedded images/basic shapes, and unsupported-content reports/XML document-type rejection/corrupt-image cleanup.
- Both existing `NativePipelineTest` tests passed, covering native ink and PDF import/export/backup/restore. Combined focused Android run: **6 tests, 0 failures**.

Build output: `app/build/outputs/apk/debug/app-debug.apk`. Build log: `/private/tmp/dotnote-import-build.log`; Android results: `/private/tmp/dotnote-import-final-tests.log`. Source paths: `DocumentImport.kt`, `PptxPdf.kt`, the editor import flow, and new JVM/Android import tests. Conversion uses generated PresentationML fixtures; arbitrary real-world decks, third-party document providers, and physical-tablet behavior still need acceptance checks. Offline rendering intentionally supports a subset of PowerPoint; legacy `.ppt`, charts, tables, SmartArt and exact font/effect reproduction are outside that subset. See [PDFs and export](docs/pdf-and-export.md).

---

# 0.7.1 compact calendar update

Built September 29, 2026 using the cached JDK 20 / Android SDK 36 toolchain. Calendar defaults to 4×2 launcher cells, with minimum and minimum resize dimensions reduced to 250×110 dp. Shorter than 180 dp, the widget uses compact headers, padding and date text sized for the available week rows. Responsive variants now use both width and height, including legacy orientation alternatives.

Manually resized the existing calendar on the Android 16 / API 36 tablet emulator from 4×3 (672×344 dp) to 4×2 (672×224 dp); the current and next month remain complete. Screenshot: `dist/preview/calendar-widget-4x2.png`. Debug APK built successfully, version 0.7.1 / code 11. No tests or lint rerun, as requested. Smaller phone-launcher dimensions and physical OEM behavior remain unverified.

---

# 0.7.0 calendar widget validation

Validated September 29, 2026 using JDK 20 (bytecode target 17), Android SDK 36, and the existing disposable Android 16 / API 36 ARM64 tablet emulator.

- Build, all **32 JVM tests**, lint, and Android test APK assembly pass. Lint has no errors; existing project warnings remain.
- **6 Android widget tests pass**: two calendar tests plus the four existing widget regressions. The calendar tests exercise real RemoteViews at 280×200, 360×220 and 500×260 dp, whole six-week months, today's highlight, December/January rollover, launcher-preview inflation and the root PendingIntent opening Dotnote without requesting note creation.
- Date rules cover leap years, Monday alignment, all month dates, and Toronto's 23/25-hour daylight-saving days.
- Rendered widget inspected visually against the supplied reference: rounded translucent panel, two side-by-side months, Monday-first weekday headers, blue Saturdays, pink Sundays and a dark rounded today highlight. The Android launcher lists all three Dotnote widgets. Added Calendar on the actual home screen, confirmed live September/October dates, tapped it to open MainActivity, and verified the non-waking alarm for September 30 at 00:00 with a ten-minute window.
- APK version **0.7.0 / code 10**; signature verified and certificate SHA-256 matches 0.6.0: `825739c9e1b77f0276094938143f07b6a51e636344c076fa90f6caad869d9744`. Debug-signed personal-test build.

Physical-device widget sizing and date refresh under OEM battery saving remain unverified. Midnight uses a non-waking inexact alarm with an hourly fallback; Android may delay it while asleep.

Spacing follow-up: reduced horizontal padding from 16 to 8 dp and increased vertical padding to 20 dp for five-row months, tightening row spacing. Six-row months retain 14 dp vertical padding so all dates fit at minimum height. Rebuilt and visually inspected the installed widget; tests were not rerun at the user’s request. Updated screenshot: `dist/preview/calendar-widget-spacing.png`.

---

# Verification · 0.6.0 (build 9)

Validated September 29, 2026 using JDK 20 (bytecode target 17), Android SDK 36, the cached Gradle toolchain, and the existing disposable Android 16 / API 36 ARM64 emulator with `skiagl`. Physical Lenovo selection and pen-shortcut acceptance remain pending.

- Debug and instrumentation APKs built successfully; **29 JVM tests passed**. Lint: **0 errors, 35 warnings**.
- **22 focused Android tests passed** in separate instrumentation processes: NotesRoleTest (5), VaultPipelineTest (4), VaultLifecycleTest (3), GitHubSignInLifecycleTest (2), EditorUpdateTest (3), WidgetPipelineTest (4), InkStartupTest (1). Other historical drawing/performance suites were not rerun for this integration.
- Notes tests verify the package-scoped implicit `CREATE_NOTE` resolver, exported activity, required `showWhenLocked`/`turnScreenOn` flags, separate document tasks, unavailable-role guarding, ignoring historical note/vault extras, fresh blank documents, rotation/save retention, independent launches, and saving the previous note before a reused activity opens another blank scene.
- Manual secure lock-screen launch: the quick-note activity appeared with keyguard still showing and input restricted (`mKeyguardOccluded=true`); visible controls exposed only the new note and writing tools. No library, historical title, vault label, or account controls appeared. The temporary emulator PIN was removed after the check.
- Startup regression: **37 ms input dispatch / 281 ms screenshot-observed visibility** on the emulator. This is not a physical-tablet latency measurement.
- APK v2 signature verifies; certificate SHA-256 `825739c9e1b77f0276094938143f07b6a51e636344c076fa90f6caad869d9744` matches `dist/dotnote-0.5.2.apk`. Package `dev.dotnote.app`, version **0.6.0 / build 9**.

The emulator reports `ROLE_NOTES` unavailable, so it cannot verify chooser membership or role assignment. Dotnote checks role availability and opens the public default-app settings when supported; AOSP marks NOTES non-requestable. Lenovo's actual default-app chooser, pen shortcut and floating-window behavior must be confirmed on the tablet. [Implementation and acceptance guide](docs/default-notes-app.md).

An initial reused-activity test passed its save/fresh-scene assertions but failed teardown because its synthetic `onNewIntent` replaced the original explicit intent with a bare action, preventing ActivityScenario from recognizing later lifecycle events. It now copies the actual launch intent, and all five tests pass. The widget UI test also now waits for the accessibility title field before editing it; its previous window-readiness race failed twice before that correction. Neither correction changes production behavior.

Versioned APK, source archive and SHA-256 checksums are in `dist/`. Build/test logs are under `/private/tmp/dotnote-toolchain/notes-role-*`; the secure lock-screen screenshot is `notes-role-locked.png` in that directory. Install over the previous build to retain local vaults.

---

# Historical verification · 0.5.2 (build 8)

Validated September 29, 2026 using installed JDK 20 (Java/Kotlin bytecode target 17), Android SDK 36 and the cached Gradle toolchain. Android tests ran on the existing disposable Android 16 / API 36 ARM64 emulator. Physical Lenovo confirmation is pending.

- Debug and instrumentation APKs built successfully; **29 JVM tests passed** (19 existing + 10 GitHub sign-in regressions).
- **9 focused Android tests passed**: GitHubSignInLifecycleTest (2), VaultPipelineTest (4), VaultLifecycleTest (3). Drawing/widget instrumentation was not rerun for this auth update; prior results are recorded below.
- Lint: **0 errors, 35 warnings**. Final versioned APK build and lint passed after changing only version code/name; behavioral tests ran before that version-only packaging step.
- APK v2 signature verifies; signing certificate matches `dist/dotnote-0.5.1.apk`. Package `dev.dotnote.app`, version **0.5.2 / build 8**. Install over the prior build to preserve local vaults.

The reported error occurs after browser authorization. Previously, polling continued in the background and any temporary hostname-resolution failure ended the attempt and discarded its device code. Sign-in now gates each new network attempt on a resumed activity, retries temporary DNS/socket/connection failures with increased intervals until expiry, and retains an issued token in memory while retrying `/user` for up to 60 seconds. Initial code acquisition retries for up to 60 seconds. Terminal auth/HTTP/TLS failures still stop; closing settings and cancellation stop attempts. OAuth connections close even when writing the POST body fails. No network routing, DNS provider or TLS verification changes were made.

JVM regressions exercise waiting for browser return, retrying the same device code after DNS failure, retaining an approved token through account lookup failure, timeout backoff, `slow_down`, expiry while waiting/backgrounded, denial, cancellation, initial-code retries and TLS/auth classification. Android tests use a real LifecycleRegistry to verify background gating and cancellation. Git/vault tests still use deterministic injected API responses, not a live GitHub account.

Tablet acceptance: install `dist/dotnote-0.5.2.apk`, connect with GitHub, approve in the browser, and return to Dotnote. Confirm account/repository selection, then test private-vault backup/restore. If DNS still fails while Dotnote stays open, device/network diagnostics are required; this build does not establish that the tablet's underlying DNS issue is resolved.

Build and test logs: `/private/tmp/dotnote-toolchain/github-fix-build.log`, `github-fix-package.log`, and `github-fix-android-tests.log`. Versioned APK, source archive and checksums are in `dist/`.

---

# Historical verification · 0.5.1 (build 7)

Validated September 29, 2026 on the existing Android 16 / API 36 ARM64 emulator with `skiagl`. Version code 7 / 0.5.1; this is a debug-signed personal-test build.

- Debug APK, instrumentation APK, Kotlin compilation, and lint completed successfully using installed JDK 20 (bytecode target 17), SDK 36, and the cached Gradle toolchain. Lint: **0 errors, 35 warnings**.
- JVM tests: **19 passed**, including five new scene-resource regressions. Spatial queries match linear culling and document order over 10,001 mixed objects and extreme coordinates. A pan in a 20,000-object note reads fewer than 20 nearby items; repeated layer queries read zero additional items. Edit, append, deletion, undo and viewport changes invalidate correctly. Visible resources above the old 400-entry limit survive redraws and pan transitions; offscreen retention remains bounded.
- Android tests: **14 passed** in separate instrumentation invocations: VectorPerformanceTest (2), NativePipelineTest (2), GesturePipelineTest (1), EditorUpdateTest (3), InkStartupTest (1), HighlighterPerformanceTest (2), FlingNavigationTest (3). Storage, Git and widget suites were not rerun for this rendering change.
- Dense vector workload: **900 strokes / 36,000 input points**. Initial software render: **770.975 ms**; five cached redraws: **18.161 ms median**. Mesh construction remained at exactly 900 across redraws, reordered views, zoom and object movement. Recoloring and replacing encoded inputs rebuilt only the affected stroke. Cached redraw pixels matched the initial render. These are cold/warm measurements of this implementation, not a measured old/new speedup or hardware frame-latency claim.
- Native handoff visibility works without replacing the scene list; cached grid geometry refreshes after transforms. Existing pressure ink persistence, zoomed input, selection/undo, highlighter opacity, PDF export, first-stroke visibility and fling navigation regressions pass.
- Highlighter workload: **4.701 ms median / 5.815 ms p95** software frames. First-stroke input dispatch: **38 ms**; screenshot-observed visibility: **297 ms**.

Visible geometry stays resident with up to 400 offscreen resources per cache. This avoids cache thrashing at the cost of memory proportional to visible geometry. The spatial index rebuilds on edits, and cold native mesh construction remains synchronous. Very dense views, initial opening and physical-tablet input/GPU performance still need device profiling. Saved note format and vector export semantics are unchanged.

Packaging follow-up: rebuilt the APK with version **0.5.1 / build 7**, verified APK metadata and signature, and confirmed the signing certificate matches `dist/dotnote-0.5.0.apk`. Version-only packaging did not rerun the behavioral suites above. Versioned APK, source archive and SHA-256 checksums are in `dist/`.

Build logs and individual instrumentation results are under `/private/tmp/dotnote-toolchain/vector-*`; generated APK: `app/build/outputs/apk/debug/app-debug.apk`.

---

# Historical verification · 0.5.0 (build 6)

Validated September 29, 2026 on the existing Android 16 / API 36 ARM64 tablet emulator (1280 × 800), using the documented `skiagl` backend. Physical tablet scrolling feel and large-document frame rates remain unverified.

- Debug APK and instrumentation APK: built successfully. Build used the installed JDK 20 with Java/Kotlin bytecode target 17, Android SDK 36, and the existing Gradle wrapper.
- JVM tests: **14 passed**, 0 failures.
- Focused Android tests: **12 passed** in separate instrumentation processes: FlingNavigationTest (3), GesturePipelineTest (1), NativePipelineTest (2), EditorUpdateTest (3), InkStartupTest (1), HighlighterPerformanceTest (2). Storage, Git and widget suites were not rerun for this navigation update.
- Android lint: **0 errors, 35 warnings**, including dependency-update notices, optional KTX suggestions, widget API-level attributes and the existing custom-canvas accessibility warning.
- Fling coverage: imported 40-page PDF; post-release movement in both vertical directions; faster flick travels farther; stable horizontal position and zoom at 1×/2×; natural deceleration; final camera survives closing/reopening. Tests exercise an offset far from the canvas origin and nonzero pointer IDs.
- Interruption coverage: new touch, stylus drawing, page jump, fit and view release stop momentum. Slow drag, cancelled touch/up, pinch followed by pointer lift, and finger drawing do not fling. Hand mode supports flicking with finger drawing enabled.
- Existing drawing/export regressions pass. First-stroke emulator measurement: **32 ms dispatch / 204 ms screenshot-observed visibility**. Highlighter synthetic software frames: median **4.635 ms**, p95 **6.092 ms**. These are emulator checks, not physical-device performance claims.
- APK signature verifies and certificate matches `dist/dotnote-0.4.1.apk`. Package remains `dev.dotnote.app`; version code **6**, version name **0.5.0**. Install over the prior build without uninstalling or clearing data.

Momentum uses Android's built-in `VelocityTracker`, `ViewConfiguration` thresholds and `OverScroller` deceleration; no new library dependency. Frame updates apply relative screen-pixel movement to the camera in dp. The existing infinite canvas remains unbounded. Pinch gestures deliberately do not coast on release. Window focus loss, detach, and the editor's existing stop/save lifecycle also cancel momentum.

Tablet acceptance: open a large PDF, compare gentle and fast one-finger flicks, touch to stop, pinch and write, then reopen the note to check position. With finger drawing enabled, use Hand for flick navigation.

Commands and test inventory: [docs/build-test-release.md](docs/build-test-release.md).

---

# Historical verification · 0.4.1 (build 5)

Validated September 29, 2026 on the existing Android 16 / API 36 ARM64 tablet emulator (1280 × 800). Physical Galaxy Tab S6 Lite performance is **not yet verified**.

- Kotlin compilation, debug APK, and instrumentation APK: passed.
- JVM tests: **14 passed**, 0 failures.
- Focused Android tests: **9 passed** in separate instrumentation processes: InkStartupTest (1), HighlighterPerformanceTest (2), NativePipelineTest (2), EditorUpdateTest (3), GesturePipelineTest (1). Storage, Git, and widget suites were not rerun for this drawing update.
- Android lint: **0 errors, 25 warnings**; includes optional KTX suggestions and the existing custom-canvas accessibility warning.
- First stroke: **35 ms dispatch / 215 ms screenshot-observed visibility**, while the pen was still down; 500 ms visibility budget. No warmup strokes entered the document. The earlier candidate measured 136 / 475 ms, illustrating emulator/timing variation.
- Highlighter: **2,400 live points over 60 existing 100-point strokes**, 200 software bitmap frames: median **4.735 ms**, p95 **17.574 ms**. The earlier candidate measured 4.546 / 5.344 ms. These are synthetic frame costs, not a before/after comparison or physical input-to-display latency.
- Verified live overlap and chunk interiors remain one-third opaque; pen stays above highlights; saved/reopened markers render; cancelled previews leave no ink. Existing exported-PDF opacity, pressure ink, 200% zoom, undo/redo, and save/reopen tests pass.
- Library dropdown → Dotnote version was opened and visually inspected: **Dotnote 0.4.1 / Build 5**. The same dialog is available in the editor dropdown.
- APK signatures verify, and the signing certificate matches `dist/dotnote-0.4.0.apk`. Package remains `dev.dotnote.app`; version code increases from 4 to 5. Install over the prior build, without uninstalling or clearing app data.

The highlighter hot path no longer copies the full stroke, recalculates its Item bounds, unions geometry on each frame, or redraws PDFs/finished pen ink on each move. Completed highlights use cached outlines; PDF export retains normalized unions. Native initialization starts at activity launch and an offscreen stroke warms each new authoring surface. Real drawing is never gated on warmup completion.

The S6 Lite first-writing delay may include device-specific input or graphics behavior. The new warmup and stronger test do not prove that report is resolved. Check cold and warm note opens, long highlights, large PDFs, and the first versus subsequent strokes on the actual tablet. Widget discovery and the previously deferred GitHub issue are unchanged.

Build/test commands are in [docs/build-test-release.md](docs/build-test-release.md). Final emulator run used the documented `skiagl` setting; production does not force a renderer. Startup cleanup synchronizes Ink after measurement, not during production input.

---

# Historical verification · 0.4.0

Validated on Android 16 / API 36 ARM64 tablet emulator, 1280 × 800.

- Kotlin compilation, debug APK and instrumentation APK packaging: passed.
- JVM tests: **14 passed**, 0 failures.
- Android instrumentation: **18 passed**, 0 failures, with UI test classes run in separate app processes (see graphics caveat below).
- Android lint: **0 errors**, 23 warnings, including optional KTX suggestions, pinned versions and the existing custom-canvas accessibility suggestion.
- APK v2 signature verification: passed. Package `dev.dotnote.app`, version code 4, version name 0.4.0. The debug signing certificate matches 0.2.0, allowing installation over that build.
- GitHub's live device-code endpoint accepted the configured public Client ID and confirmed device flow is enabled. No client secret is embedded.

## Coverage

The 14 JVM tests cover document/camera/geometry/history round trips, folder cycles, bad document data, path restrictions, repository URL validation, edit-based backup deadlines at 15 minutes and 6 hours, adaptive widget capacity and full folder paths with duplicate folder names.

The 18 Android tests cover:

- Four vault pipeline tests: file-authoritative index rebuild, nested folder moves and note rename/delete; interrupted transaction replay and path rejection; Git backup/restore, no-change commits, lost final responses and remote-head conflicts; Android Files nested-folder import and duplicate note titles with matching ID prefixes.
- Three vault lifecycle tests: a new edit replaces the pending WorkManager job, 15/360-minute delays and unmetered constraints; legacy Room/PDF migration with source files retained; switching vaults preserves separate notes and palettes.
- Three editor tests: long-press palette editing and selection recoloring with undo; constant highlight opacity after reopening; constant highlight opacity in exported PDFs.
- Two native pipeline tests: pressure ink serialization/reopening/rendering; PDF import/export and legacy ZIP merge restore, including malformed input rejection.
- Four widget tests: vault rename preserves IDs, notes and repository settings while marking a backup revision; persistent opening order, rename/move/delete updates without treating opens as edits; real RemoteViews rendering at different widget sizes and distinct click targets; a widget launch opens the New note dialog, selects another vault and nested folder through accessibility UI, creates the note there, and subsequently reopens it across vaults.
- One first-stroke startup test: starts drawing as soon as the new editor is laid out, measures input dispatch, and checks visible ink in a screenshot before one second. Final emulator run: **48 ms input dispatch, 236 ms to visible ink**. This measures emulator behavior, not physical Lenovo latency.
- One synthetic stylus test: coordinates at 200% zoom, pen/highlighter drawing, undo/redo and save/reopen.

The Git tests use a deterministic injectable API fixture that models blobs, trees, commits and branch refs; they do not authenticate against a real private repository. WorkManager tests inspect the actual enqueued jobs; they do not wait six hours or simulate all Android battery policies.

The Android launcher displayed both new widget types. The recent-notes widget was added to the actual home screen and its New note button opened the creation dialog. The new dialog and both widget previews were visually inspected. Earlier library, vault chooser and GitHub sign-in screens were visually inspected. The normal sign-in screen has no Client ID field; alternative credentials are under Advanced setup. Earlier navigation-bar swipe-reveal and merged-header checks remain applicable.

## Reproduce

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class dev.dotnote.app.VaultPipelineTest,dev.dotnote.app.VaultLifecycleTest,dev.dotnote.app.NativePipelineTest dev.dotnote.app.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class dev.dotnote.app.WidgetPipelineTest dev.dotnote.app.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class dev.dotnote.app.InkStartupTest dev.dotnote.app.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class dev.dotnote.app.EditorUpdateTest dev.dotnote.app.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class dev.dotnote.app.GesturePipelineTest dev.dotnote.app.test/androidx.test.runner.AndroidJUnitRunner
```

## Limits of validation

A headless SwiftShader run crashed inside Android `libhwui.so` when running UI classes sequentially in one process. The emulator uses `debug.hwui.renderer=skiagl`; separate-process UI runs pass. The startup screenshot test initially hit this crash during teardown after recording its timing. Test cleanup now drains native rendering with the Ink test synchronization API before destroying the view; this is after measurement and is not included in production code. Dotnote does not force a graphics backend on the tablet. This emulator-specific failure is recorded rather than counted as a passing all-in-one suite.

The user reports that GitHub sign-in still fails on the Lenovo with a hostname-resolution error; it remains explicitly deferred in TODO.md. GitHub account consent, live private-repository backup/restore, token revocation and real network interruptions still require a working connection and signed-in account. No network-authentication fix is claimed in this version. External Android storage providers vary; the built-in DocumentsProvider import path is tested, but arbitrary vendor/cloud folder providers are not certified. Vault export is a snapshot, not a live folder link.

Physical Lenovo acceptance remains necessary for stylus pressure/latency, palm rejection/buttons, large-note performance and Android 17 behavior. Android background work may be delayed by battery restrictions, Doze or connectivity.
