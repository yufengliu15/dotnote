# Interface and user flows

[Documentation index](README.md) · Main sources: [MainActivity.kt](../app/src/main/java/dev/dotnote/app/MainActivity.kt), [NewNoteDialog.kt](../app/src/main/java/dev/dotnote/app/NewNoteDialog.kt), [VaultUi.kt](../app/src/main/java/dev/dotnote/app/VaultUi.kt)

## Activity shell

Dotnote uses a light Material 3 palette with warm paper surfaces and dark green controls. A Scaffold hosts snackbars. High-level operations show a full-window “Working…” overlay while `AppState.busy` is true. Errors normally become a snackbar; GitHub dialog operations have their own working indicator and error text.

The activity hides **navigation bars** with `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` on creation and whenever it regains focus. Swiping from the bottom temporarily reveals them. The status bar remains visible. OEM taskbar behavior depends on the platform honoring these window-insets requests; no privileged system setting is changed.

## Library

The library contains the app title/tagline, vault chooser, folder breadcrumbs, search, New folder, New note, and a responsive grid with minimum card width 220 dp.

- With an empty query, it shows direct child folders and notes of the current folder. The “All notes” breadcrumb returns to **vault root**; it is not a recursively flattened all-notes view.
- A nonblank query searches folder names and note titles across the active vault, case-insensitively. It does not search handwriting, PDF text, or other vaults.
- Folder lists sort alphabetically through Room; notes sort by modification time. Entering a folder clears search.
- Back in a nested folder moves to its parent. Opening a note loads its saved camera and scene.
- Object menus support rename, move, delete. A move dialog navigates existing destination folders; folder moves reject self/descendant destinations. Delete requires confirmation. Nonempty folders cannot be deleted.
- New folder creates a child of the current folder. New note opens the shared creation dialog rather than immediately creating an empty file.

Library overflow actions:

| Label | Action |
| --- | --- |
| Vaults | Choose/create/rename/import/export vaults |
| GitHub backup & restore | Open account and current-vault repository settings |
| Back up library | Create the legacy ZIP snapshot through Android's save picker |
| Restore backup (merge) | Open ZIP/octet-stream picker and merge into the current vault |

## Shared new-note dialog

The app and both widgets use `NewNoteDialog`. It remembers a title, selected vault and folder while the dialog survives configuration recreation. Initial destination is the current vault/current folder.

Changing vault loads its folders asynchronously through `state.vaultFolders(id)` without selecting that vault yet. The browser supports vault root, parent navigation and direct child folders. The full destination path disambiguates duplicate folder names. If a previously selected folder no longer exists, destination falls back to root.

Create is enabled only with a nonblank title, successfully loaded destination and no app busy state. It invokes `state.createNote(title, folderId, vaultId)` and dismisses the dialog. The action flushes existing work, switches vault if necessary, verifies the destination still exists, writes the new note and opens it. Failures report a message rather than silently creating in another folder. Cancel creates no note and does not select another vault, although browsing may initialize that vault's local index.

## Editor

The 64 dp header contains Back, tappable title, toolbar when docked Top, saved indicator, Undo, Redo, and overflow. Top tools share this existing header; the strip scrolls when needed. Left/Right docking moves the tool strip beside the canvas. The title is truncated visually but its stored value is unchanged.

Tool buttons select Pen, Highlighter, Eraser, Select, Pan, or the Shapes picker. Shapes offers line, arrow, rectangle, square, ellipse, circle and grid. Grid settings use 1–30 rows/columns. The canvas does not snap to the background dots.

The bottom-right zoom indicator shows saved camera zoom and a “Fit all content” action. Selecting drawing items shows a bottom action bar with count, color picker, delete and clear selection. An empty note displays brief gesture guidance.

| User action | Result |
| --- | --- |
| Tap title | Rename note dialog |
| Tap a palette slot | Choose future ink color |
| Hold a palette slot | Edit that slot using wheel/brightness/hex; Apply saves slot and selects its color |
| Select items → palette icon | Open picker initialized from the first selected item; Apply recolors selection as one undoable change |
| Tap Saved/Unsaved | Settle finished native display handoff and enqueue another local save, including retry after failure |
| Undo/Redo | Restore item-list state; buttons disabled when unavailable |
| Back | Settle finished ink, flush latest document, then return to library only if successful |

The save label is local status. It does not indicate successful remote backup. Palette editing affects the current vault's settings and is not part of drawing undo.

Editor overflow exposes Import PDF, PDF page navigator, Export entire note as PDF, Export visible area as PDF, Writing settings, How to use Dotnote, and Dotnote version. Both library and editor version buttons open a dialog showing the installed build version and code. The navigator lists imported page items, using page number and an asset prefix; tapping frames that page. PDF page operations and export semantics are described in [PDFs and export](pdf-and-export.md).

## Writing settings and color picker

Writing settings adjusts pen width 1–12, dock position, finger drawing, current note's dot visibility, custom hex color, and grid subdivisions. Highlighter width is five times pen width. Settings other than dots persist per vault after a short debounce; the current tool itself is transient.

[`ColorPickerDialog`](../app/src/main/java/dev/dotnote/app/ColorPicker.kt) has a 220 dp hue/saturation wheel, brightness slider, color preview and six-character hex field. Wheel angle controls hue, distance from center controls saturation, slider controls HSV value. Hex input filters to six hexadecimal characters; Apply is enabled only at six characters. Output is opaque ARGB. Cancel leaves the target unchanged.

## Vault chooser and rename

The chooser lists vaults by name, identifies the active vault, and offers:

- Select another vault, after flushing current note/settings.
- Create a named vault and switch to it.
- Rename using the pencil beside a vault name.
- Import a complete vault folder through Android's tree picker.
- Export the current vault folder beneath a chosen external folder.
- Open GitHub settings.

Rename updates only `name` in `.dotnote/vault.json`, marks a backup edit and refreshes widget labels. Local ID, portable ID, note/folder IDs, root path and repository configuration remain unchanged. Names are trimmed/capped at 120 characters; a blank rename is rejected; an identical name is a no-op. There is no vault-delete UI.

Imported/exported folders are copies/snapshots, not live external vault bindings. For exact contents and error behavior, see [storage and recovery](storage-and-recovery.md).

## GitHub UI

The normal sign-in screen uses the built-in public client ID and displays a device code with Copy code/Open GitHub authorization controls. Advanced setup supports an alternate client ID or a fine-grained token. Closing the settings dialog cancels its ongoing device authorization polling.

After sign-in, the current vault can choose a dedicated repository, set automatic backup on/off, choose a whole-minute inactivity delay from 15 to 360, require unmetered networking, queue “Back up now,” disconnect its repository, or restore a repository as a new vault. Repository names may be selected from the list or entered as owner/repository or a GitHub HTTPS URL. The app neither creates repositories nor changes their privacy; users manage that on GitHub. Status is polled from local configuration every 1.5 seconds while the dialog is open.

Account Disconnect clears local credentials and cancels existing work for all vaults, but does not revoke tokens on GitHub or erase every repository binding. Repository disconnect affects only that vault's binding. See [the backup guide](vaults-and-github-backups.md) before altering these flows.

## User-visible limits

No drawing text tool, OCR/handwriting recognition, audio recording, PDF text search, collaborative editing, automatic device-to-device merge, or attachment gallery is implemented. The UI has no trash recovery or Git historical-version picker. Folder nesting has implementation limits rather than mathematically unlimited depth. Drawing is stylus-first; canvas accessibility is currently a description and click hook, not semantic navigation of every handwritten object.

Do not add network/account requirements to ordinary note creation or editing. Widgets and vault rename work independently of the deferred Lenovo GitHub issue.
