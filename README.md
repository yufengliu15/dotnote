# Dotnote

An offline, native Kotlin notebook for Android tablets. Built with Jetpack Compose, AndroidX Ink 1.0, Room, and Android's PDF renderer. Works offline without an account. Optional GitHub backups; no subscriptions or analytics.

## Install

Install `dist/dotnote-0.4.0.apk` on your tablet. Android may ask you to allow installs from the app you opened it with. This is a development build signed with a debug key, suitable for personal testing, not a Play Store release.

Install this APK over the previous Dotnote build to keep your library.

Create a note from the library. Use a stylus to write, one finger to pan, and two fingers to zoom. If your pen is treated as a finger by the device, turn on **Writing settings → Draw with a finger**. Return to all your content with the focus button at the bottom right.

## Features

- Hidden Android navigation/taskbar, temporarily revealed by swiping up from the bottom; the status bar stays visible.
- Infinite dot-grid canvas with pan/zoom, return-to-content, and saved camera position.
- Native Ink pressure pen and a constant-opacity highlighter (repeated strokes do not darken). Whole-stroke eraser; stylus eraser tip/primary button support when reported by Android.
- Tools share the note header, with optional left/right docking. Long-press any of six color slots for a hue/saturation wheel, brightness slider, or hex entry; slots persist across sessions. Adjustable widths and persistent preferences.
- Lines, arrows, rectangles, squares, ellipses, circles, and grids with 1–30 rows/columns.
- Lasso or tap selection; move, resize using the bottom-right handle, open the selection palette to choose a new color, and delete. Gesture-based undo/redo (80 operations per open note).
- PDFs imported as locked pages on the canvas with space around them for writing. Original files are copied into private storage. Page navigator and asynchronous page rendering with a bounded bitmap cache.
- Nested folder trees. Breadcrumbs, search by title/name, rename, move, and delete. Folders must be empty before deletion; folder cycles are rejected.
- Automatic transactional local saves, a visible save indicator, and retry on save failure. Rotation keeps the editor and history alive.
- Two Android home-screen widgets: a compact New note button and a resizable recent-notes list. New-note creation includes a vault and nested-folder picker.
- Rename vaults from the vault chooser; names update in widgets and portable metadata while IDs and repository connections stay intact.
- The native ink renderer and pen brush initialize when an editor opens, before the first pen-down event.
- Multiple file-based vaults, each with independent folders, writing preferences and optional GitHub repository. Import/export a complete vault folder; copy files through Android Files → Dotnote vaults.
- GitHub device sign-in, repository selection, manual backup and restore as a new vault. Automatic backups run after 15–360 minutes without an edit; each edit resets the timer. Optional unmetered network constraint.
- Legacy ZIP backup/merge restore remains available, including original PDFs.
- PDF export of either the viewport or all content tiled across pages, including annotations.

## Home-screen widgets and vault names

Long-press an empty area of the Android home screen, choose **Widgets → Dotnote**, and add **New note** or **Recent notes**. The recent-notes widget also includes New note. Resize it to show more entries; wide layouts use two columns. Entries are ordered by last opening, across all local vaults, and show the vault/folder below the title. Each entry opens its original vault. Opening history is local to the device, persists between sessions, and does not count as editing a note or restart GitHub backup timers. It tracks up to 100 openings with duplicate entries removed; a widget displays as many as fit, up to 32.

The New note dialog lets you choose the vault, browse into any existing folder, and create the note in the displayed location. The same dialog is used inside the app and from both widgets. Cancelling does not create a note. To rename a vault, open the vault chooser and tap the pencil beside its name.

## Known issue deferred

GitHub connection still fails on the user's Lenovo tablet with a `github.com` hostname-resolution error. This remains on [TODO.md](TODO.md) at the user's request. The new widgets, vault renaming and local drawing work without GitHub. No GitHub/network workaround is included in 0.4.0.

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
- `GitHub.kt`, `GitBackup.kt`, `VaultUi.kt`: encrypted credentials, device authorization, Git commits/restore, scheduling and settings.
- `AppState.kt`: lifecycle-aware editor state and ordered saving.
- `NotebookView.kt`: native Ink authoring, gesture arbitration, hit testing, viewport rendering.
- `Rendering.kt`: completed ink, shapes, PDF import/render/export.
- `MainActivity.kt`: Compose library, merged editor header, settings and file picker flows.
- `ColorPicker.kt`: hue/saturation wheel, brightness and hex editing.

## Current limits

This is a personal-use build. No typed text, handwriting recognition, cloud sync, or collaboration. Imported PDF text is not selectable/searchable, encrypted PDFs are not supported, and PDFs exported by this app flatten the imported page image plus vector annotations rather than preserving editable PDF objects. Page previews are capped at 2048 pixels wide, so extreme zoom can look soft.

Highlighter colors share one translucent layer below pen/shape ink. Different marker colors replace rather than darken one another where they overlap; the most recently used color is on top. Existing saved highlights use this rendering too.

The canvas redraws visible objects and caches strokes and PDF pages, but it does not yet use a full spatial index. Very large notebooks need further profiling on the tablet. “Infinite” means no imposed page boundary, with the practical limits of memory and floating-point coordinates. PDF export is capped at 500 pages; backup restore at 1 GiB; individual local PDF imports at 512 MiB. Vault reading permits 10,000 entries, fewer than 64 directory levels, 64 MiB per note and 256 MiB total note JSON. Filesystem path/name limits also apply.

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
