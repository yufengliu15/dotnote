# Verification · 0.4.0

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
