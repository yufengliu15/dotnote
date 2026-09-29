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
| App version | code 5, name `0.4.1` |
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

Version sources: [root build](../build.gradle.kts), [app build](../app/build.gradle.kts), [wrapper properties](../gradle/wrapper/gradle-wrapper.properties). Compose UI/Material versions come from the BOM. [Gradle properties](../gradle.properties) enables AndroidX, nontransitive R classes and a 3 GiB Gradle heap. Dependencies resolve through repositories in [settings](../settings.gradle.kts). Room schema export is configured into [`app/schemas/`](../app/schemas/).

Android 16 is directly targeted and was used for emulator validation. The minSdk allows installation on newer Android releases, but that alone does not certify Android 17 or every OEM tablet. Do not change targetSdk merely to address an unmeasured stylus problem.

## Build and local installation

From the repository root:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Outputs include:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- `app/build/test-results/testDebugUnitTest/` and `app/build/reports/tests/testDebugUnitTest/`
- `app/build/reports/lint-results-debug.html` / XML

Use `adb devices` to confirm the intended target; pass `-s <serial>` when more than one device is attached. Installing instrumentation tests does not run them. Never uninstall/clear production app data just to solve a build or signing mismatch; that deletes local vaults. Export first if a destructive device reset is actually necessary.

The current distributable is a debug-signed personal-test build. There is no checked-in production signing setup, Play Store pipeline or CI release workflow. `assembleRelease` alone is not a configured production distribution process.

## Test inventory: 14 JVM tests

Tests are in [`app/src/test/java/dev/dotnote/app/`](../app/src/test/java/dev/dotnote/app/).

| Class | Count | Contracts |
| --- | --- | --- |
| `DocumentTest` | 10 | Camera zoom anchor; valid/invalid/corrupt folder cycles; codec round trip with attachments/transforms; gesture history and redo invalidation; geometric hit tests including locked PDFs; concave polygon selection; transform composition; attachment traversal rejection; unsupported version rejection |
| `VaultRulesTest` | 2 | Edit-based backup deadlines and clamping; local/remote path and repository-name restrictions |
| `WidgetRulesTest` | 2 | Adaptive widget capacity; full folder paths with duplicate display names |

These are ordinary JUnit tests with a JVM `org.json` dependency. Native Android Ink rendering, actual Room/WorkManager, Views and PDF APIs require instrumentation; a passing JVM suite cannot establish those behaviors.

## Test inventory: 20 Android tests

Tests are in [`app/src/androidTest/java/dev/dotnote/app/`](../app/src/androidTest/java/dev/dotnote/app/).

| Class | Count | Test methods and coverage |
| --- | --- | --- |
| `NativePipelineTest` | 2 | `nativeInkSurvivesSerializationAndRenders`; `pdfImportExportBackupRestoreAndFailedRestoreAreConsistent` |
| `GesturePipelineTest` | 1 | `stylusCoordinatesUndoAndReopenSurviveZoom`: synthetic stylus at 200% zoom, pen/highlighter, save/reopen and history |
| `EditorUpdateTest` | 3 | `repeatedHighlightsKeepOpacityAfterReopen`; `pdfHighlightsStayTranslucentAndDoNotAccumulate`; `paletteLongPressPersistsAndSelectionPickerSupportsUndo` |
| `VaultPipelineTest` | 4 | `filesRebuildIndexAndPreserveMovedNotes`; `interruptedFileTransactionReplaysBeforeReading`; `androidFilesProviderImportsNestedVaultWithoutChangingSource`; `gitBackupRestoreNoChangeAndConflictRecovery` |
| `VaultLifecycleTest` | 3 | `lastEditReplacesScheduledWorkAndKeepsNetworkPreference`; `migrationCopiesLegacyNotesAndPdfsWithoutDeletingOriginals`; `vaultSwitchFlushesNotesAndKeepsSeparatePalettes` |
| `WidgetPipelineTest` | 4 | `vaultRenamePreservesIdentityAndMarksBackupPending`; `openingHistoryPersistsAndRenameMoveDeleteRefreshIt`; `resizedRemoteViewsFitMoreNotesAndKeepDistinctLaunchTargets`; `widgetLaunchCreatesInSelectedFolderAndOpensAcrossVaults` |
| `HighlighterPerformanceTest` | 2 | Live overlap/chunk opacity, pen layering, reopen/cancel rendering; 2,400-point live-stroke software frame budget |
| `InkStartupTest` | 1 | `firstPenStrokeIsVisibleBeforePenUp`: first-stroke dispatch and screenshot visibility before ACTION_UP, plus no phantom warmup item |

Git tests use an injectable deterministic API that models blobs, trees, commits and branch refs. It covers lost final responses and remote conflicts, but no real account consent or private repository. WorkManager tests inspect real queued jobs; they do not simulate six hours of Doze/OEM battery policies. Android Files tests use the app's provider, not every external provider. Tests that create real app UI/preferences should run on a disposable emulator/test profile, not the user's only notebook installation.

## Reproduce the validated instrumentation arrangement

On the validated Android 16/API 36 ARM64 tablet emulator (1280×800), UI classes were run in separate instrumentation invocations:

```sh
adb shell am instrument -w -e class dev.dotnote.app.VaultPipelineTest,dev.dotnote.app.VaultLifecycleTest,dev.dotnote.app.NativePipelineTest dev.dotnote.app.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class dev.dotnote.app.WidgetPipelineTest dev.dotnote.app.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class dev.dotnote.app.InkStartupTest dev.dotnote.app.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class dev.dotnote.app.EditorUpdateTest dev.dotnote.app.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class dev.dotnote.app.GesturePipelineTest dev.dotnote.app.test/androidx.test.runner.AndroidJUnitRunner
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
| Input/brush/rendering | Native + gesture + editor + startup + highlighter performance tests; physical stylus acceptance |
| PDF import/export | Native pipeline and exported-highlight test; real multi-page document/provider |
| File layout/transactions/transfer | Vault pipeline + migration/switch lifecycle; malformed/interrupted cases |
| Git/auth/scheduling | Vault rules + pipeline + lifecycle; live account/network checks only when authorized/available |
| Widgets/new-note/rename | Widget rules + widget pipeline; launcher resize/cold/warm opening |
| Documentation only | Relative links, examples, source consistency and `git diff --check`; no APK required |

Run the relevant checks plus compilation/lint for source changes. Broaden testing when shared invariants change. Do not rerun expensive device suites merely to validate prose edits.

## Manual tablet acceptance

1. Open an empty note and draw immediately; compare first/subsequent strokes, pressure loops and fast diagonals. Rest palm, use button/eraser tip, toggle finger drawing, pan/pinch then write.
2. Draw every shape, lasso/tap, move/resize/recolor/delete, undo/redo. Repeat marker coverage and compare after reopen and PDF export.
3. Import several PDFs, navigate distant pages, zoom, rotate and export visible/entire note. Confirm failure feedback for damaged/oversize files.
4. Create nested folders and same-name notes, move/rename, reject cycles/nonempty deletion. Background and reopen after “Saved.” Export/import complete vaults and merge legacy ZIP without replacing originals.
5. Add both widgets, resize/orient, create in another vault's nested folder, test recent order and renamed/deleted targets. Rename a vault and verify widget/file-provider labels.
6. When the deferred connection issue is resumed, authorize GitHub, back up/restore a private test vault, revoke credentials, interrupt network/ref acknowledgement, and create a remote conflict. Confirm local notes remain usable throughout.

## Packaging and signing

Keep application ID stable. Increment version code/name deliberately in `app/build.gradle.kts`; record verification honestly in `VALIDATION.md`. Before handing out an update, inspect APK metadata and signing certificate with the SDK's `apkanalyzer`/`apksigner`. Example with build-tools on PATH:

```sh
apksigner verify --verbose --print-certs app/build/outputs/apk/debug/app-debug.apk
```

An update requires the same signing certificate as the installed app. A freshly generated debug key on another machine is not equivalent. The 0.4.0 release record confirmed its certificate matched earlier 0.2.0 builds; preserve that key privately if distributing compatible personal updates. Never commit a private signing key.

Copy verified APKs/source archives to `dist/` using versioned names only when making a release. Those artifacts are Git-ignored. A source archive should contain source, resources, schemas, wrapper, documentation and tests, not `local.properties`, build caches, credentials, SDKs or machine-specific toolchain directories. A documentation update alone does not require rebuilding or repackaging an APK.
