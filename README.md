# Dotnote

An offline, native Kotlin notebook for Android tablets. Built with Jetpack Compose, AndroidX Ink 1.0, Room, and Android's PDF renderer. Works offline without an account. Optional GitHub backups; no subscriptions or analytics.

## 0.11.0 in-app updates

Check for updates, download a verified APK, and open Android’s installer from **Version & updates**. No extra updater app or account is required. Release automation builds and verifies updates on GitHub; publishing is an explicit checkbox on the release workflow.

## 0.10.1 note previews

The notes grid shows small, centered snapshots of saved content. Visible cards load in the background and reuse a bounded image cache. Long notes containing PDFs preview the first page of the first PDF with its annotations, instead of shrinking the whole deck.

## 0.9.2 large-note saving

Fixed “Row too big to fit into CursorWindow” when saving or reopening a large note. Document reads now use small chunks, including folder operations, backups and legacy migration. Existing files and the database schema are retained; install as an update without clearing app data.

Current app version: **0.11.1 / code 20**. See [VALIDATION.md](VALIDATION.md) for test results.

## 0.9.1 text resizing and local startup

Select text and choose **Resize** to change its wrapping width at the same font size. Drag the corner handle horizontally; the box height fits the resulting lines. Choose **Scale** to enlarge or shrink the existing layout proportionally. Editing text retains its box width. Explicit paragraph breaks remain intact.

Startup loads local notes rather than syncing a repository. Its messages now identify opening the local vault, reading local notes and updating the note list. File validation avoids redundant document parsing and drawing-object construction, index writes are batched, and backup scheduling runs off the UI thread. The library appears after local loading, avoiding an empty-list flash.

The text/startup release was **0.9.1 / code 15**. See [VALIDATION.md](VALIDATION.md) for measured emulator timings and test results.

## 0.9.0 text and templates

Choose **Text** in the toolbar, then tap the canvas to add typed text. Tap existing text with Text to edit it; choose a font size in the dialog. Select supports moving, resizing, recoloring and deleting text, with undo/redo. Text is saved with the note and appears in PDF exports. Finger dragging still pans; pinching zooms.

To reuse a layout, open **Note options → Use as template**, or enable **Create as template** in New note. The New note dialog offers **Blank note** and templates from the selected vault. Copies retain the layout, dot grid, camera and attachments, with independent content and new IDs. Templates remain editable notes in the library and travel with vault/ZIP backups.

Text and templates were introduced in **0.9.0 / code 14**. See [CHANGELOG.md](CHANGELOG.md) and [VALIDATION.md](VALIDATION.md). Existing notes and the local index migrate without clearing app data. Notes containing the new text objects require 0.9.0 or later; older app versions do not recognize them.

## 0.8.1 import fixes

Image-heavy PowerPoint decks now convert one slide at a time, avoiding the previous cumulative image limit. Imported images work with **Select**: tap or lasso an image, drag to move it, resize with its corner handle, or use the selection trash button. Undo/redo and reopening preserve these edits. Images imported before 0.8.1 need to be reimported because the old build did not record their image origin.

The import-fix release was **0.8.1 / code 13**. The previous import feature is the **0.8.0** baseline. See [CHANGELOG.md](CHANGELOG.md) and the [versioning and release workflow](docs/build-test-release.md#versioning-and-source-control). Future releases advance the version; historical release numbers remain unchanged.

## 0.7.1 calendar widget

Add **Widgets → Dotnote → Calendar** to your home screen. It defaults to 4×2 cells and can be resized vertically; shorter layouts compact the text and headers so both months remain complete. The rounded, translucent widget shows the current month and the full next month side by side, highlights today, and colors Saturdays blue and Sundays pink. Weeks start on Monday. Tap anywhere to open Dotnote. Month names follow your device language, and the calendar follows your local date and time zone.

## 0.6.0 default notes app

Dotnote can now be selected as the default notes app on compatible Android devices, including supported Lenovo tablets. Open the library or note menu → **Set as default notes app**, then select the notes category and Dotnote in Android settings.

System note launches open a fresh drawing note in the current vault. The separate quick-note screen works over the lock screen, keeps existing notes and the library hidden, and uses a scrollable bottom toolbar for narrow floating windows. Notes save normally and appear in the library after unlocking. Tablet chooser and pen-button behavior still need Lenovo confirmation.

## 0.5.2 GitHub sign-in recovery

Sign-in waits for your return from the GitHub browser page and retries temporary DNS/connection failures using the same device code. The approved token is retained in memory while retrying the account lookup. Install the update, approve GitHub, and return to Dotnote to finish connecting. Tablet confirmation remains pending.

## 0.5.1 vector performance

- Visible vector geometry stays cached above 400 strokes, avoiding repeated mesh reconstruction.
- A shared spatial grid finds visible drawings; shape and grid geometry is reused between frames.
- Verified with 19 JVM tests and 14 focused Android tests, including a 900-stroke rendering workload. Physical-tablet performance still needs checking.

## 0.5.0 momentum scrolling

Flick with one finger to coast through PDFs and across the canvas. Faster flicks travel farther and slow down naturally. Touch the canvas again to stop immediately. Pinch zoom and drawing stay precise. The 0.5.0 release included a Hand tool for finger scrolling; in 0.9.0, choose Text for one-finger pan or use two fingers while drawing.

## Previous drawing update

Highlighter drawing now appends live geometry in bounded chunks, avoids interactive path unions, and redraws separately from PDFs and finished pen strokes. Saving a stroke no longer builds a duplicate native mesh. Native Ink warms before writing; actual first-stroke latency still needs confirmation on the Galaxy Tab S6 Lite.

Open either the library or note dropdown and tap **Version & updates** to see the installed version and build number.

## Engineering documentation

Start with [docs/README.md](docs/README.md) for the implementation guide: architecture, exact file formats, storage/recovery, drawing and PDF pipelines, user flows, widgets, GitHub backup protocol, build/test instructions, and known limitations. It includes a reading order and source map for agents continuing development.

## Install

Install `dist/dotnote-0.11.1.apk` on your tablet. Android may ask you to allow installs from the app you opened it with. The 0.11.1 build is non-debuggable and retains the existing signing certificate for update compatibility. It is not a Play Store build. Install it over the current app; do not uninstall or clear data.

From 0.11.0 onward, use **Version & updates → Check for updates** to download verified updates directly inside Dotnote. Stable releases are checked by default; preview releases are optional. Android still confirms installation and may show Play Protect prompts. First installs use the [GitHub releases page](https://github.com/yufengliu15/dotnote/releases). See [app updates and release automation](docs/app-updates.md).

Install this APK over the previous Dotnote build to keep your library.

Create a note from the library. Use a stylus to write, one finger to drag or flick, and two fingers to zoom. If your pen is treated as a finger by the device, turn on **Writing settings → Draw with a finger**. Return to all your content with the focus button at the bottom right.

## Features

- Default Android notes-app registration and private lock-screen quick notes on supported devices.
- Hidden Android navigation/taskbar, temporarily revealed by swiping up from the bottom; the status bar stays visible.
- Infinite dot-grid canvas with momentum pan/zoom, return-to-content, and saved camera position.
- Native Ink pressure pen and a constant-opacity highlighter (repeated strokes do not darken). Whole-stroke eraser; stylus eraser tip/primary button support when reported by Android.
- Tools share the note header, with optional left/right docking. Long-press any of six color slots for a hue/saturation wheel, brightness slider, or hex entry; slots persist across sessions. Adjustable widths and persistent preferences.
- Lines, arrows, rectangles, squares, ellipses, circles, and grids with 1–30 rows/columns.
- Lasso or tap selection; move, resize using the bottom-right handle, open the selection palette to choose a new color, and delete. Gesture-based undo/redo (80 operations per open note).
- PDFs imported as locked pages on the canvas with space around them for writing. Original files are copied into private storage. Page navigator and asynchronous page rendering with a bounded bitmap cache.
- Import images and PowerPoint `.pptx` from the editor's **Import PDF, image or PowerPoint** menu. Images are selectable, movable and deletable; their stored PDF attachments remain portable. PowerPoint slides convert to PDF entirely on-device, one slide at a time, with a 2048-pixel raster limit per slide. Text, embedded images, basic shapes and slide layout positioning are supported. Conversion reports flag omitted/simplified content; complex formatting and fonts may differ. Older `.ppt` files must first be saved as `.pptx` or PDF.
- Nested folder trees. Breadcrumbs, search by title/name, rename, move, and delete. Folders must be empty before deletion; folder cycles are rejected.
- Automatic transactional local saves, a visible save indicator, and retry on save failure. Rotation keeps the editor and history alive.
- Three Android home-screen widgets: a compact New note button, a resizable recent-notes list, and a two-month calendar. New-note creation includes a vault and nested-folder picker.
- Rename vaults from the vault chooser; names update in widgets and portable metadata while IDs and repository connections stay intact.
- The native ink renderer and pen brush initialize when an editor opens, before the first pen-down event.
- Multiple file-based vaults, each with independent folders, writing preferences and optional GitHub repository. Import/export a complete vault folder; copy files through Android Files → Dotnote vaults.
- GitHub device sign-in, repository selection, manual backup and restore as a new vault. Automatic backups run after 15–360 minutes without an edit; each edit resets the timer. Optional unmetered network constraint.
- Legacy ZIP backup/merge restore remains available, including original PDFs.
- PDF export of either the viewport or all content tiled across pages, including annotations.

## Home-screen widgets and vault names

Long-press an empty area of the Android home screen, choose **Widgets → Dotnote**, and add **New note**, **Recent notes**, or **Calendar**. The recent-notes widget also includes New note. Resize it to show more entries; wide layouts use two columns. Entries are ordered by last opening, across all local vaults, and show the vault/folder below the title. Each entry opens its original vault. Opening history is local to the device, persists between sessions, and does not count as editing a note or restart GitHub backup timers. It tracks up to 100 openings with duplicate entries removed; a widget displays as many as fit, up to 32.

The New note dialog lets you choose the vault, browse into any existing folder, and create the note in the displayed location. The same dialog is used inside the app and from the two note widgets. Cancelling does not create a note. To rename a vault, open the vault chooser and tap the pencil beside its name.

## GitHub tablet verification

The reported hostname-resolution failure happens after returning from GitHub authorization. Version 0.5.2 fixes sign-in abandoning authorization on a single network failure and avoids starting polls while Dotnote is in the background. Regression tests pass; actual Lenovo sign-in and live private-repository backup still need confirmation. See [TODO.md](TODO.md).

## Build

Requires JDK 17, Android SDK platform 36, and Android build tools. Open this directory in Android Studio or run:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Set `ANDROID_HOME` to your SDK location, or add `sdk.dir=/your/sdk/path` to a local `local.properties`. The Gradle wrapper is included. Dependencies are pinned in the two Gradle build files.

Minimum Android: 10 / API 29. Compile/target SDK: 36; the APK can run on Android 17. Physical Lenovo stylus performance requires device verification.

## Data and reliability

Notes are UTF-8 JSON `.dotnote` files inside ordinary vault directories. PDFs live in `attachments/`, folder IDs in `.folder.json`, and writing preferences in `.dotnote/settings.json`. Room is a rebuildable index. A local transaction journal completes interrupted multi-file moves on reopening. Existing 0.2.0 notes/PDFs migrate automatically; the old database and PDF directory are retained.

Open **Vaults → GitHub backup & restore → Connect with GitHub**, copy the displayed code, and open GitHub authorization. The supplied public OAuth Client ID is built in; no secret or server is required. Create/select a dedicated notes repository, such as `dotnote-notes`. Keep the application source repository separate. Private repositories stay private. Advanced setup also accepts a fine-grained token restricted to selected repositories (Contents read/write; read-only is sufficient for restore).

Each vault can connect to its own repository. Set any whole-minute delay from **15 to 360 minutes after the last edit**. New edits restart the delay; Android may postpone execution for battery/network conditions. **Back up now** queues immediate work subject to the chosen network constraint. Unchanged files produce no extra commit. GitHub credentials are encrypted using Android Keystore and excluded from vault copies and backups.

This is backup and explicit restore, not automatic two-way sync. If another device changes the remote branch, uploads stop with an explanation instead of overwriting it. Restore the repository as another vault to inspect the newer copy; both local copies remain. Each new device signs in independently.

The active vaults live in app-private `files/vaults/<local-id>/`. Android Files exposes them for reading/copying. **Folder import makes a local copy; exported folders are snapshots, not continuously linked working folders.** Export the whole vault, including hidden `.dotnote` and `.folder.json` metadata and `attachments`, for transfer. The legacy ZIP format includes notes/PDFs but not writing preferences; use a vault export or GitHub for complete portability.

Backups are unencrypted. GitHub private repositories restrict access but are not end-to-end encrypted. GitHub backup rejects files at or above 100 MiB and does not silently omit attachments. No Git LFS integration is included.

There is no Android cloud backup. **Uninstalling or clearing app data deletes local vaults and credentials. Export or successfully back up first.** Undo history is session-only. Deleted notes leave local recovery files under `.dotnote/trash/` (no recovery UI yet); successful Git backups retain earlier versions in Git history. Attachment files are retained locally after deletion.

## Project map

- `Document.kt`: immutable scene model, geometry, history, versioned JSON format.
- `VaultFiles.kt`, `Store.kt`, `FileLibraryDao.kt`: authoritative files, migration, index, transactional operations and legacy ZIP support.
- `VaultCatalog.kt`, `VaultTransfer.kt`, `VaultDocumentsProvider.kt`: vault selection, folder import/export and Android Files integration.
- `GitHub.kt`, `GitHubSignIn.kt`, `GitBackup.kt`, `VaultUi.kt`: encrypted credentials, device authorization, Git commits/restore, scheduling and settings.
- `AppState.kt`: lifecycle-aware editor state and ordered saving.
- `NotebookView.kt`: native Ink authoring, gesture arbitration, hit testing, viewport rendering.
- `Rendering.kt`: completed ink, shapes, PDF import/render/export.
- `CreateNoteActivity.kt`, `DefaultNotes.kt`: system note launches, lock-screen quick editor and default-app settings.
- `MainActivity.kt`: Compose library, merged editor header, settings and file picker flows.
- `ColorPicker.kt`: hue/saturation wheel, brightness and hex editing.

## Current limits

This is a personal-use build. No typed text, handwriting recognition, cloud sync, or collaboration. Imported PDF text is not selectable/searchable, encrypted PDFs are not supported, and PDFs exported by this app flatten the imported page image plus vector annotations rather than preserving editable PDF objects. Page previews are capped at 2048 pixels wide, so extreme zoom can look soft.

Highlighter colors share one translucent layer below pen/shape ink. Different marker colors replace rather than darken one another where they overlap; the most recently used color is on top. Existing saved highlights use this rendering too.

The canvas uses a shared spatial grid to find visible objects, retains visible stroke geometry plus a bounded offscreen cache, and caches PDF pages. Index updates after edits and very dense visible scenes still scale with object count. Very large notebooks need further profiling on the tablet. “Infinite” means no imposed page boundary, with the practical limits of memory and floating-point coordinates. PDF export is capped at 500 pages; backup restore at 1 GiB; individual local PDF imports at 512 MiB. Vault reading permits 10,000 entries, fewer than 64 directory levels, 64 MiB per note and 256 MiB total note JSON. Filesystem path/name limits also apply.

## Verification

See the release validation record for build, test and signature results. See [VALIDATION.md](VALIDATION.md) for test scope and the emulator graphics-backend caveat.

## Manual tablet acceptance check

1. Write fast loops with the Lenovo stylus, rest your palm on the display, and check pressure variation. Pan and pinch before writing again; strokes should remain under the pen.
2. Draw each shape and a grid. Lasso, move, resize, recolor, erase, undo, and redo. Test finger drawing with two-finger navigation.
3. Import a multi-page PDF, annotate distant pages, zoom, and jump through the page navigator. Export the viewport and entire note.
4. Create nested folders, move notes, try moving a parent into a child, and test title search. Rotate, background, force-stop after “Saved,” and reopen.
5. Back up the library, restore the ZIP into the existing library, and confirm the restored notes have their PDFs and annotations without replacing the originals.

## Vault implementation details

[Vault format and GitHub backups](docs/vaults-and-github-backups.md) describes the implemented file layout, authentication, scheduling and recovery behavior.

APK and source ZIP artifacts are available locally in `dist/` and excluded from Git history. The project includes source, tests, schemas, Gradle wrapper and build documentation.
