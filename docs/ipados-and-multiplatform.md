# iPadOS and Kotlin Multiplatform

[Documentation index](README.md)

## Repository boundaries

- `android/app/`: existing Android application, Compose interface, AndroidX Ink, Room index, Android file providers, widgets, and GitHub backup adapters. The Gradle project remains `:app` so existing task names continue working.
- `ios/`: native iPadOS 17+ SwiftUI application, UIKit Pencil/touch input, Core Graphics scene rendering, PDFKit attachments, Foundation file storage, Xcode project and native tests.
- `shared/`: Kotlin Multiplatform document model, streaming codec, geometry, hit tests, spatial index, visible-resource cache, history, and editor operations. Android consumes its JVM artifact; the iPad app links the static `DotnoteCore` framework. There is no second Swift document model.

Keep OS services at the edges. Rendering is native to preserve input and platform integration; document and geometry changes belong in `commonMain` and must pass tests on JVM and Kotlin/Native. The shared package retains `dev.dotnote.app` to minimize Android migration churn. Android's JSONObject overloads are thin compatibility adapters.

The structure follows [Kotlin's native UI/shared logic guidance](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html) and [direct Xcode integration](https://kotlinlang.org/docs/multiplatform-direct-integration.html). No CocoaPods, experimental Swift export, or duplicated checked-in framework binaries are required.

## Build and test

Open `ios/Dotnote.xcodeproj`, choose the shared **Dotnote** scheme, and select an iPad simulator. Install JDK 17 and make it available to Xcode's build script through `JAVA_HOME`. The build phase runs `:shared:embedAndSignAppleFrameworkForXcode` from the repository root. Gradle uses its pinned wrapper and Kotlin plugin versions. Xcode user-script sandboxing is disabled only for the target that invokes Gradle, as required by direct integration.

```sh
./gradlew :shared:jvmTest :shared:iosSimulatorArm64Test
scripts/test-ios.sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

`DOTNOTE_SIMULATOR_ID` optionally selects the iPad used by `scripts/test-ios.sh`. The script otherwise uses an installed available iPad. Apple device builds use `iosArm64`; Apple Silicon simulators use `iosSimulatorArm64`, and Intel simulators use `iosX64`.

To install on a physical iPad, set your Apple development team in Xcode's Signing & Capabilities and run on the connected device. No developer team, certificate, provisioning profile or credential is committed. GitHub Releases distribute the Android APK and monorepo source; they do not install iPad apps. TestFlight/App Store publication requires an Apple Developer account and a separately configured distribution pipeline.

The iPad CI workflow tests common Kotlin and native file operations, then compiles an unsigned device app. Android publication depends on that job and the existing Android release gates. No signing secrets are exposed to pull requests.

## Portable files and input

Both apps read version-1 `.dotnote` files. Existing AndroidX Ink bytes remain opaque and are retained when edited on iPad; the iPad renderer displays their coordinate centerlines. This preserves editability but does not reproduce Android's pressure brush silhouette exactly.

New iPad pen strokes omit `ink` and store their real coalesced coordinates with optional `pressures`, one finite number in `0..1` per point. Missing pressures default to full-width rendering. Both renderers use pressure for portable pens, and both use constant-width markers. Pressure arrays must be absent/empty or match the point count. Older Android builds do not correctly display these portable polylines; update Android before exchanging newly drawn iPad notes. No existing document needs migration, and single-page PDFs never acquire image provenance automatically.

One completed touch gesture is one shared history operation. Cancellation discards live points. Predicted input is not persisted. Pencil owns the input stream while fingers navigate; finger drawing is an explicit toggle. The canvas uses the same shared spatial index as Android, and holds at most four open PDF documents. Imports retain PDF bytes and page rectangles rather than allocating a raster for every page. Image imports downsample to at most 4,096 pixels per dimension before creating a PDF attachment.

## File safety

The iPad app owns copies under `Documents/Vaults/<local-id>/`. `VaultStore` runs on one serial queue. Saves capture the immutable document JSON and note destination; atomic Foundation writes finish before switching notes or vaults. A failed save keeps the note open and offers retry. Backgrounding requests a save with an iOS background-task allowance; the OS can still terminate a process, so force-killing before a save finishes is not a durability guarantee.

Vault import uses a private staging folder, rejects symbolic links and pending Android recovery journals, validates notes and referenced PDF pages, and publishes a new local directory only after success. It preserves portable IDs while generating an independent local vault ID. The source is never modified. Note-only import creates a new note ID and rejects missing external attachments; use the full vault folder for notes with PDFs/images. Note deletion moves the file to local `.dotnote/trash`. Export copies the vault behind the same serial queue, omitting trash.

## Initial iPad feature boundary

The native port supports local vaults and nested-folder destinations, notes, Pencil/finger writing, pressure, constant-opacity highlighter, object eraser, shapes, text creation, lasso/tap selection, moving/scaling/recoloring/deleting selections, undo/redo, pan/pinch/fit, PDF/image import, and note/PDF/vault export.

Android remains the fuller implementation: GitHub sign-in and scheduled backup, PowerPoint conversion, bucket fill, template creation, text reflow editing, folder/note rename and move controls, widgets, default-notes integration, momentum navigation, and raster scene tiles are not yet exposed on iPad. Imported template flags and all supported document objects are retained. Use vault export for iPad backups. These are explicit parity gaps, not features supplied by Kotlin Multiplatform automatically.

Physical Apple Pencil latency, palm rejection, double-tap behavior, very large document performance, Files provider behavior, and background termination require acceptance on a real iPad. Simulator tests do not certify them.
