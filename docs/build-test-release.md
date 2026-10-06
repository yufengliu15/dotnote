# Build, test, and release

[Documentation index](README.md) · Canonical release evidence: [VALIDATION.md](../VALIDATION.md)

## Toolchain and identity

Use a full JDK 17 and an Android SDK with platform 36 plus compatible build tools/platform tools. Set `ANDROID_HOME`, or use an untracked `local.properties` containing `sdk.dir=/absolute/path/to/sdk`. Android Studio can import the root Gradle project. Initial dependency/tool downloads require network access.

| Setting | Pinned value at baseline |
| --- | --- |
| Gradle wrapper | 8.13 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin / Compose compiler plugin | 2.2.21 |
| KSP | 2.2.21-2.0.4 |
| Java/Kotlin bytecode | 17 |
| Package / namespace | `dev.dotnote.app` |
| App version | Read `versionCode` / `versionName` in `android/app/build.gradle.kts`; current release documented in CHANGELOG.md |
| Android support | minimum API 29 (Android 10), compile/target API 36 (Android 16) |
| Compose BOM | 2025.12.00 |
| Activity Compose | 1.12.1 |
| Lifecycle ViewModel/runtime Compose | 2.9.4 |
| Room runtime/ktx/compiler | 2.8.4 |
| WorkManager runtime-ktx | 2.11.2 |
| AndroidX Ink authoring/brush/strokes/geometry/rendering/storage | 1.0.0 |
| Input motion prediction | 1.0.0 |
| JUnit / JVM org.json | 4.13.2 / 20250517 |
| Android test ext JUnit / runner | 1.3.0 / 1.7.0 |

Version sources: [root build](../build.gradle.kts), [app build](../android/app/build.gradle.kts), [wrapper properties](../gradle/wrapper/gradle-wrapper.properties). Compose UI/Material versions come from the BOM. [Gradle properties](../gradle.properties) enables AndroidX, nontransitive R classes and a 3 GiB Gradle heap. Dependencies resolve through repositories in [settings](../settings.gradle.kts). Room schema export is configured into [`android/app/schemas/`](../android/app/schemas/).

Android 16 is directly targeted and was used for emulator validation. The minSdk allows installation on newer Android releases, but that alone does not certify Android 17 or every OEM tablet. Do not change targetSdk merely to address an unmeasured stylus problem.

## iPadOS and shared code

See [the iPadOS guide](ipados-and-multiplatform.md). Both platform source trees ship in source archives. `shared:jvmTest` and iPad CI are release prerequisites; iPad signing/distribution is configured separately from the Android APK pipeline.

## Build and local installation

From the repository root:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebugAndroidTest
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Outputs include:

- `android/app/build/outputs/apk/debug/app-debug.apk`
- `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- `android/app/build/test-results/testDebugUnitTest/` and `android/app/build/reports/tests/testDebugUnitTest/`
- `android/app/build/reports/lint-results-debug.html` / XML

Use `adb devices` to confirm the intended target; pass `-s <serial>` when more than one device is attached. Installing instrumentation tests does not run them. Never uninstall/clear production app data just to solve a build or signing mismatch; that deletes local vaults. Export first if a destructive device reset is actually necessary.

Local snapshots remain debug-signed personal-test builds. An explicitly signed non-debuggable release build and manually triggered GitHub Actions workflow are now configured; the release environment restricts the configured signing secrets to main, and workflow dispatch defaults to publishing the verified APK and GitHub Release after all checks pass. There is no Play Store pipeline. See [app updates and free distribution](app-updates.md).

## Test inventory: 47 JVM tests

Tests are in [`android/app/src/test/java/dev/dotnote/app/`](../android/app/src/test/java/dev/dotnote/app/).

| Class | Count | Contracts |
| --- | --- | --- |
| `FillRulesTest` | 2 | Closed-region flood coverage, holes/open edges; fill codec/defaults, invalid combinations and transformed hit tests |
| `DocumentTest` | 14 | Scan/decode validation equivalence; Unicode text/size codec roundtrip, rectangular selection/transforms and malformed text rejection; camera zoom anchor; valid/invalid/corrupt folder cycles; codec round trip with attachments/transforms; gesture history and redo invalidation; geometric hit tests including locked PDFs and selectable images; concave polygon selection; transform composition; attachment traversal rejection; unsupported version rejection |
| `DocumentImportTest` | 2 | File/MIME classification and bounded input copying |
| `GitHubSignInTest` | 10 | Browser-return gate, same-code DNS retry, approved-token retention, slowdown/timeout backoff, code expiry, denial, cancellation, initial-code retries, TLS/auth classification |
| `VaultRulesTest` | 2 | Edit-based backup deadlines and clamping; local/remote path and repository-name restrictions |
| `SceneResourcesTest` | 5 | Visible-cache retention above 400 objects; bounded offscreen LRU; spatial lookup equivalence/order and extreme coordinates; 20,000-object pan query work; edit/undo/viewport invalidation |
| `WidgetRulesTest` | 2 | Adaptive widget capacity; full folder paths with duplicate display names |
| `PageScrubRulesTest` | 5 | Page-scrubber speed gain (slow step, full-height fast span, monotonic, short-document floor), page order and nearest page |
| `RecentHomeRulesTest` | 1 | Home Recent row: five-note cap, active vault, opening order, deleted/duplicate entries skipped |
| `CalendarRulesTest` | 3 | Monday-first complete months, leap years, year rollover, local midnight across daylight saving |

`UpdateRulesTest` adds 6 tests for release filtering/versioning, manifest and asset validation, HTTPS redirect restrictions, bounded/canceled downloads, and APK identity/signature checks. `AppUpdateTest` adds 2 Android tests for installer/provider confinement and invalid/same-version APK rejection. Four Python release-history tests run with `python3 -m unittest discover -s scripts -p 'test_*.py'`.

These are ordinary JUnit tests with a JVM `org.json` dependency. Native Android Ink rendering, actual Room/WorkManager, Views and PDF APIs require instrumentation; a passing JVM suite cannot establish those behaviors.

## Test inventory: 69 Android tests

Tests are in [`android/app/src/androidTest/java/dev/dotnote/app/`](../android/app/src/androidTest/java/dev/dotnote/app/).

| Class | Count | Test methods and coverage |
| --- | --- | --- |
| `TextTemplateTest` | 6 | Resize vs proportional Scale, wrapping-width retention and explicit paragraphs; text dialog/taps, rotation, edit/history, selection/move/resize/recolor/delete/reopen; screen/PDF text pixels; independent templates with attachment/backup/rebuild; template creation/picker UI; non-destructive Room 1→2 migration |
| `LargeNoteStorageTest` | 2 | Oversized existing documents keep only metadata in the index row (no CursorWindow overflow); save/no-op/reopen, folder/note rename/move/delete, snapshot and ZIP merge restore; Unicode and exact/empty chunk boundaries |
| `StartupLoadingTest` | 2 | Unbound-vault local-loading wording/no empty-list flash; 160-note/32,000-object scan/index/reopen measurement and stale-index recovery |
| `NativePipelineTest` | 2 | `nativeInkSurvivesSerializationAndRenders`; `pdfImportExportBackupRestoreAndFailedRestoreAreConsistent` |
| `FlingNavigationTest` | 3 | 40-page PDF fling, speed/direction/zoom, decay and saved camera; touch/stylus/page/fit/release interruption; slow/cancelled/pinch/drawing exclusion |
| `GesturePipelineTest` | 2 | Synthetic stylus at 200% zoom, pen/highlighter, save/reopen and history; imported-image tap/move/resize/delete/undo/redo/reopen and eraser protection |
| `EditorUpdateTest` | 3 | `repeatedHighlightsKeepOpacityAfterReopen`; `pdfHighlightsStayTranslucentAndDoNotAccumulate`; `paletteLongPressPersistsAndSelectionPickerSupportsUndo` |
| `NotesRoleTest` | 5 | CREATE_NOTE resolver/qualification flags; role availability guard; fresh-note privacy and ignored external IDs; rotation/save retention; reused-task save and new scene; independent launches |
| `GitHubSignInLifecycleTest` | 2 | Real Android lifecycle pauses exchange while the browser is open; cancellation prevents exchange on return |
| `VaultPipelineTest` | 4 | `filesRebuildIndexAndPreserveMovedNotes`; `interruptedFileTransactionReplaysBeforeReading`; `androidFilesProviderImportsNestedVaultWithoutChangingSource`; `gitBackupRestoreNoChangeAndConflictRecovery` |
| `VaultLifecycleTest` | 3 | `lastEditReplacesScheduledWorkAndKeepsNetworkPreference`; `migrationCopiesLegacyNotesAndPdfsWithoutDeletingOriginals`; `vaultSwitchFlushesNotesAndKeepsSeparatePalettes` |
| `WidgetPipelineTest` | 4 | `vaultRenamePreservesIdentityAndMarksBackupPending`; `openingHistoryPersistsAndRenameMoveDeleteRefreshIt`; `resizedRemoteViewsFitMoreNotesAndKeepDistinctLaunchTargets`; `widgetLaunchCreatesInSelectedFolderAndOpensAcrossVaults` |
| `HighlighterPerformanceTest` | 2 | Live overlap/chunk opacity, pen layering, reopen/cancel rendering; 2,400-point live-stroke software frame budget |
| `VectorPerformanceTest` | 2 | 900 native strokes reuse meshes and preserve pixels across redraws; zoom/transforms reuse geometry; recolor/input edits invalidate; handoff visibility and shape transforms |
| `InkStartupTest` | 1 | `firstPenStrokeIsVisibleBeforePenUp`: first-stroke dispatch and screenshot visibility before ACTION_UP, plus no phantom warmup item |
| `TileRenderingTest` | 1 | Tiled frames equal direct vector rendering for the first (fallback) frame, finished tiles, whole-pixel pans, appends, erasing (in-place repaint), marker re-stacking, drag exclusion/drop and a settled zoom level |
| `CodecCompatibilityTest` | 1 | The direct encoder writes byte-identical text to Android org.json (escapes, floats, negative zero, Unicode); decoding of org.json and pretty-printed files |
| `PerformanceBenchmarkTest` | 9 | Before/after workloads (compile against 0.12.1 too): dense-note pan, pinch, pen-up commit/save/burst, eraser, lasso, selection drag, opening 1,200/3,000-stroke notes, cold read and first library preview of a 3,000-stroke note, vault startup cold/warm, GitHub backup/restore with 80 ms simulated latency. Logs `BENCH` lines under `DotnoteBench` |

`QualityOfLifeTest` adds two Android tests for a three-second resettable edit debounce, immediate large-note flush, no delayed duplicate, file-authoritative reopen, bucket pixels, marker opacity and exported PDF coverage. `QualityOfLifeUiTest` adds a real-pointer test for held note/folder drops in both directions, palette-to-Pen activation and horizontal Fill choices, a real finger fill tap, undo/redo and close.

`NotePreviewTest` adds three Android tests for snapshot pixels/centering, a 40-page PDF using only its first page with annotations (later assets deliberately unavailable) and blank notes, and cache reuse/edit invalidation/vault isolation.

`CalendarWidgetTest` adds two Android tests for complete month grids at three sizes, today and year rollover, static preview inflation, and the actual calendar PendingIntent opening the app without requesting note creation.

`DocumentImportPipelineTest` adds five tests for image rendering/backup, JPEG orientation and decode bounds, PPTX layout/text/image conversion, a 24-slide image-heavy regression exceeding the old pixel budget, and conversion warnings/malformed-input cleanup.

Git tests use an injectable deterministic API that models blobs, trees, commits and branch refs. It covers lost final responses and remote conflicts, but no real account consent or private repository. WorkManager tests inspect real queued jobs; they do not simulate six hours of Doze/OEM battery policies. Android Files tests use the app's provider, not every external provider. Tests that create real app UI/preferences should run on a disposable emulator/test profile, not the user's only notebook installation.

## Reproduce the validated instrumentation arrangement

On the validated Android 16/API 36 ARM64 tablet emulator (1280×800), UI classes were run in separate instrumentation invocations:

```sh
adb shell am instrument -w -e class dev.dotnote.app.VaultPipelineTest,dev.dotnote.app.VaultLifecycleTest,dev.dotnote.app.NativePipelineTest dev.dotnote.app.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class dev.dotnote.app.NotesRoleTest dev.dotnote.app.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class dev.dotnote.app.WidgetPipelineTest dev.dotnote.app.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class dev.dotnote.app.InkStartupTest dev.dotnote.app.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class dev.dotnote.app.EditorUpdateTest dev.dotnote.app.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class dev.dotnote.app.GesturePipelineTest dev.dotnote.app.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class dev.dotnote.app.FlingNavigationTest dev.dotnote.app.test/androidx.test.runner.AndroidJUnitRunner
```

The headless SwiftShader environment crashed in Android `libhwui.so` when several UI classes ran sequentially in one process. The validated emulator used:

```sh
adb shell setprop debug.hwui.renderer skiagl
```

Apply that only when reproducing the documented emulator setup, before launching the test app. Dotnote production code does not force that backend. Separate-process passing results must not be represented as a passing all-in-one default `connectedDebugAndroidTest` run. Startup test cleanup drains native rendering after measurement to avoid the known teardown crash.

The historical 0.4.0 release result was 14/14 JVM and 18/18 Android. For 0.4.1, run the focused drawing checks below; see `VALIDATION.md` for the actual run results rather than assuming every historical suite was rerun. Warnings include optional KTX suggestions, pinned-version notices and the custom-canvas accessibility suggestion. See the root validation record for interpretation, not just totals.

## Choosing checks for a change

| Change | Minimum relevant verification |
| --- | --- |
| Model/codec/geometry | JVM `DocumentTest`; native roundtrip if ink fields change |
| Input/brush/rendering | Native + gesture + editor + startup + highlighter/vector performance tests; physical stylus acceptance |
| PDF import/export | Native pipeline and exported-highlight test; real multi-page document/provider |
| File layout/transactions/transfer | Vault pipeline + migration/switch lifecycle; malformed/interrupted cases |
| Git/auth/scheduling | Sign-in JVM/lifecycle tests + vault rules + pipeline + lifecycle; live account/network checks only when authorized/available |
| Widgets/new-note/rename | Widget rules + widget pipeline; calendar date rules + RemoteViews and tap tests; launcher resize/cold/warm opening |
| System notes/default app | Notes role tests + vault/lifecycle + widget/editor/startup regressions; real locked launch and Lenovo default-app/pen entry point |
| Documentation only | Relative links, examples, source consistency and `git diff --check`; no APK required |

Run the relevant checks plus compilation/lint for source changes. Broaden testing when shared invariants change. Do not rerun expensive device suites merely to validate prose edits.

## Manual tablet acceptance

1. Open an empty note and draw immediately; compare first/subsequent strokes, pressure loops and fast diagonals. Rest palm, use button/eraser tip, toggle finger drawing, pan/pinch then write.
2. Draw every shape, lasso/tap, move/resize/recolor/delete, undo/redo. Repeat marker coverage and compare after reopen and PDF export.
3. Import several PDFs, navigate distant pages, zoom, rotate and export visible/entire note. Confirm failure feedback for damaged/oversize files.
4. Create nested folders and same-name notes, move/rename, reject cycles/nonempty deletion. Background and reopen after “Saved.” Export/import complete vaults and merge legacy ZIP without replacing originals.
5. Add all three widgets, resize/orient, confirm calendar dates/today and app-only tap, create in another vault's nested folder, test recent order and renamed/deleted targets. Rename a vault and verify widget/file-provider labels.
6. Authorize GitHub in the browser and return to Dotnote to complete sign-in; then back up/restore a private test vault, revoke credentials, interrupt network/ref acknowledgement, and create a remote conflict. Confirm local notes remain usable throughout.
7. Select Dotnote in Lenovo's default notes-app settings, launch using the system pen shortcut while unlocked and locked, resize/rotate the quick-note window, and check the saved notes after unlocking. See [default notes app](default-notes-app.md).

## Required delivery: GitHub Actions release

After completing a deliverable app build, the work is not complete until **Release Dotnote** (`.github/workflows/release.yml`) has run with publishing enabled. The user has given standing authorization to commit the scoped changes, push them to `main`, and publish through this workflow. A local APK or a validation-only run is insufficient unless the user explicitly asks for that exception. Documentation-only changes need no application release.

1. Preserve unrelated edits; commit only the intended app, tests and release documentation. Use a clean release source and advance the version past all delivered snapshots.
2. Integrate onto current `main` without rewriting history and push. The workflow deliberately only runs on `main` to protect signing secrets.
3. Dispatch `gh workflow run release.yml --ref main -f publish=true -f stable=false`. Use `stable=true` only when the user requests a stable release.
4. Follow the run to completion. Investigate and fix failures without bypassing checks; verify the published tag, APK, manifest and source commit. Return the GitHub release link to the user.

## Versioning and source control

`android/app/build.gradle.kts` is the only authority for `versionName` and `versionCode`. BuildConfig supplies the in-app version. **Versions advance with releases; consistency means every current artifact and reference agrees, not that the number stays fixed.** Use major/minor/patch semantics: incompatible changes advance major, new feature releases advance minor, and compatible bug fixes advance patch. During 0.x development, document any breaking changes explicitly. Increment Android versionCode monotonically for every delivered update, independently of the display version.

The import feature baseline is **0.8.0**; these import fixes are **0.8.1 / code 13**. Its initial 0.8.0 delivery mistakenly retained 0.7.1/code 11 metadata; CHANGELOG and the historical validation record disclose that mistake. Code 12 is reserved for the 0.8.0 baseline. Do not rewrite old APKs, checksums or evidence to disguise the mismatch.

1. Inspect Git status, use a focused branch, and preserve unrelated uncommitted work. Keep commits scoped to one logical change. Review the diff before committing; never silently bundle other work or rewrite published history.
2. Choose the next version for the actual change. Update Gradle, CHANGELOG and validation record together. Do not add version sections or install links to README.md; it is version-free. Historical headings and artifact names keep their original version.
3. Build and run relevant tests/lint. Record actual results, limitations, signing identity and source state. A release commit and its `vX.Y.Z` tag must describe exactly what was tested and packaged; only tag after the checks pass. Push/publish only when authorized.
4. Run `python3 scripts/package-release.py` with `ANDROID_HOME` and a compatible `JAVA_HOME`. It verifies the built APK's app ID/version, current documentation, increasing Android code and signing certificate, then derives versioned APK/source/checksum names from Gradle. Existing versioned outputs are immutable. `--check` only validates.
5. If preexisting uncommitted work is present, `--allow-dirty` explicitly packages a **development snapshot**, not a clean tagged release. The manifest records the base commit, branch, dirty paths, certificate and source/APK hashes; its source ZIP includes that workspace's source. Do not claim a dirty APK came solely from a clean release commit. Current-name aliases and their checksums are refreshed from the same verified APK/source archive.

Never just rename a stale APK, reuse a version for changed delivered binaries, or leave the previous version in the installation instructions. Versioned manifests/checksums identify each delivered artifact. An unrelated documentation edit does not require a new application release.

## Packaging and signing

Keep application ID stable. Increment version code/name deliberately in `android/app/build.gradle.kts`; record verification honestly in `VALIDATION.md`. Before handing out an update, inspect APK metadata and signing certificate with the SDK's `apkanalyzer`/`apksigner`. Example with build-tools on PATH:

```sh
apksigner verify --verbose --print-certs android/app/build/outputs/apk/debug/app-debug.apk
```

An update requires the same signing certificate as the installed app. A freshly generated debug key on another machine is not equivalent. The 0.4.0 release record confirmed its certificate matched earlier 0.2.0 builds; preserve that key privately if distributing compatible personal updates. Never commit a private signing key.

Copy verified APKs/source archives to `dist/` using versioned names only when making a release. Those artifacts are Git-ignored. A source archive should contain source, resources, schemas, wrapper, documentation and tests, not `local.properties`, build caches, credentials, SDKs or machine-specific toolchain directories. A documentation update alone does not require rebuilding or repackaging an APK.
