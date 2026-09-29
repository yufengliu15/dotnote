# Verification · 0.5.2 (build 8)

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
