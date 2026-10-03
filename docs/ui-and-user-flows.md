# Interface and user flows

[Documentation index](README.md) · Main sources: [MainActivity.kt](../android/app/src/main/java/dev/dotnote/app/MainActivity.kt), [NewNoteDialog.kt](../android/app/src/main/java/dev/dotnote/app/NewNoteDialog.kt), [VaultUi.kt](../android/app/src/main/java/dev/dotnote/app/VaultUi.kt)

## Activity shell

Dotnote uses a light Material 3 palette with warm paper surfaces and dark green controls. A Scaffold hosts snackbars. High-level operations show a full-window overlay while `AppState.busy` is true. Initial local loading uses Store loading-status messages: Opening local vault, Migrating local notes when needed, Reading local notes, and Updating note list. The library is hidden during loading so a cached/empty list does not flash underneath the overlay; failures still expose the library and error snackbar. Ordinary actions use Updating notes. Startup never waits for a GitHub upload or download. Errors normally become a snackbar; GitHub dialog operations have their own working indicator and error text.

The activity hides **navigation bars** with `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` on creation and whenever it regains focus. Swiping from the bottom temporarily reveals them. The status bar remains visible. OEM taskbar behavior depends on the platform honoring these window-insets requests; no privileged system setting is changed.

## Library

The library contains the app title/tagline, vault chooser, folder breadcrumbs, search, New folder, New note, and a responsive grid with minimum card width 220 dp.

- With an empty query, it shows direct child folders and notes of the current folder. The “All notes” breadcrumb returns to **vault root**; it is not a recursively flattened all-notes view.
- A nonblank query searches folder names and note titles across the active vault, case-insensitively. It does not search handwriting, PDF text, or other vaults.
- Folder lists sort alphabetically through Room; notes sort by modification time. Entering a folder clears search.
- Back in a nested folder moves to its parent. Opening a note loads its saved camera and scene.
- Long-press a card and drag it to a folder or a parent breadcrumb (All notes means root). The lifted label follows the pointer until release; valid targets highlight, cancellation/outside drops do nothing, and self/descendant/same-parent drops are excluded.
- Object menus support rename, move, delete. A move dialog navigates existing destination folders; folder moves reject self/descendant destinations. Delete requires confirmation. Nonempty folders cannot be deleted.
- New folder creates a child of the current folder. New note opens the shared creation dialog rather than immediately creating an empty file.

Note cards show small centered snapshots using the shared editor fit calculation, with a lower zoom floor so scenes fit. For a scene taller than three times its width containing a non-image PDF, the preview fits only the first PDF page in scene order and clips intersecting annotations to that page; it never opens later pages. Only composed cards request previews; decoding/rendering is serialized on IO and an 8 MiB in-memory LRU caches results by vault, note, modification time and size. Rendered previews are also written as PNGs to `cacheDir/note-previews` (at most 600 files; older previews of the same note are deleted), so they survive restarts. A preview drawn right after closing a note reuses its parsed scene and stroke meshes. Imported pages render and recycle one at a time. Blank/loading/unavailable previews use a quiet paper background. Previews never change saved cameras, content or recency.

Library overflow actions:

| Label | Action |
| --- | --- |
| Vaults | Choose/create/rename/import/export vaults |
| Set as default notes app | Open Android default-app settings when the Notes role is supported; otherwise explain device unavailability |
| GitHub backup & restore | Open account and current-vault repository settings |
| Back up library | Create the legacy ZIP snapshot through Android's save picker |
| Restore backup (merge) | Open ZIP/octet-stream picker and merge into the current vault |

## Shared new-note dialog

The app and both widgets use `NewNoteDialog`. It remembers a title, selected vault, folder, template and template-creation flag while the dialog survives configuration recreation. Initial destination is the current vault/current folder.

Changing vault loads its folders and template summaries asynchronously through `state.vaultFolders(id)` without selecting that vault yet. The browser supports vault root, parent navigation and direct child folders. The full destination path disambiguates duplicate folder names. If a previously selected folder no longer exists, destination falls back to root.

Create is enabled only with a nonblank title, successfully loaded destination and no app busy state. It invokes `state.createNote(title, folderId, vaultId, templateId, isTemplate)` and dismisses the dialog. The action flushes existing work, switches vault if necessary, verifies the destination still exists, writes the new note and opens it. Failures report a message rather than silently creating in another folder. Cancel creates no note and does not select another vault, although browsing may initialize that vault's local index.

The template picker defaults to Blank note and lists templates from every folder of the destination vault, including their folder paths. Create as template makes the new note reusable. Template notes stay visible/editable in the library with a Template label. Note options → Use as template marks an existing note; Remove from templates turns it back into an ordinary note. Creation rechecks the source template, preserves its scene/camera/dots and attachment references, and generates fresh note and item IDs. Editing the copy never changes the template. Attachments are immutable and shared within the vault. System quick notes always start blank and do not expose templates.

## Editor

The 64 dp header contains Back, tappable title, toolbar when docked Top, saved indicator, Undo, Redo, and overflow. Top tools share this existing header; the strip scrolls when needed. Left/Right docking moves the tool strip beside the canvas. The title is truncated visually but its stored value is unchanged.

Tool buttons select Pen, Highlighter, Eraser, Select, Text, or the Shapes picker. Shapes offers line, arrow, rectangle, square, ellipse, circle and grid. Grid settings use 1–30 rows/columns. The canvas does not snap to the background dots.

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

Editor overflow exposes Use as template / Remove from templates, Import PDF, image or PowerPoint, PDF page navigator, Export entire note as PDF, Export visible area as PDF, Writing settings, How to use Dotnote, and Version & updates. Image imports can be tapped/lassoed with Select, dragged, resized and deleted with undo/redo; the color control is disabled for image-only selections. `.pptx` imports convert offline and show a dismissible report describing conversion limitations. PDF/slide pages stay locked and appear in the page navigator. Both library and editor Version & updates buttons show the installed build and offer manual update checking, downloading and installation; see [app updates](app-updates.md). The navigator lists locked pages using page number and an asset prefix; tapping frames that page. PDF page operations and export semantics are described in [PDFs and export](pdf-and-export.md).

## Typed text

Text replaces the former Pan button. A stylus or finger tap opens Add text, or Edit text when it hits an existing text object's rectangle. The dialog accepts up to 10,000 characters with multiple lines and a font-size slider (8–144 world units); Save text commits one history entry. Cancel leaves the scene intact; Delete text removes the object as one history entry. Empty/whitespace-only drafts cannot be saved. Drafts survive activity rotation in the current editor but are not stored as content before Save text.

New text uses the selected palette color and the most recent text size for that vault (default 24). Existing text retains its color, anchor and transform when edited. Text selections show Resize and Scale choices above the canvas. Resize is the default: dragging the bottom-right corner changes the local wrapping width while retaining font size and prior visual scale; the height fits the resulting lines. Scale retains the text/box layout and scales a text-only selection uniformly about its top-left corner. With mixed selections, non-text items retain their normal geometric resizing. The mode is captured at gesture start. Reflow preserves explicit paragraph breaks, Unicode, IDs, colors and anchors; only soft wrapping changes. Text editing preserves the chosen box width. Move/recolor/delete use the existing selection controls, and both sizing modes are undoable and persist through reopen/backup/PDF export. Finger drag/fling with Text still pans even when finger drawing is enabled; pinches never open the text dialog. Stylus text drags and canceled gestures create nothing. Text also works in the isolated quick-note editor.

## Writing settings and color picker

Writing settings adjusts pen width 1–12, dock position, finger drawing, current note's dot visibility, custom hex color, and grid subdivisions. Highlighter width is five times pen width. Settings other than dots persist per vault after a short debounce; the current tool itself is transient.

[`ColorPickerDialog`](../android/app/src/main/java/dev/dotnote/app/ColorPicker.kt) has a 220 dp hue/saturation wheel, brightness slider, color preview and six-character hex field. Wheel angle controls hue, distance from center controls saturation, slider controls HSV value. Hex input filters to six hexadecimal characters; Apply is enabled only at six characters. Output is opaque ARGB. Cancel leaves the target unchanged.

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

The normal sign-in screen uses the built-in public client ID and displays a device code with Copy code/Open GitHub authorization controls. Advanced setup supports an alternate client ID or a fine-grained token. After approval, return to Dotnote to finish connecting. New polls wait for the foreground activity; temporary network failures display a retry message and retain the same authorization code. Closing the settings dialog cancels its ongoing device authorization polling.

After sign-in, the current vault can choose a dedicated repository, set automatic backup on/off, choose a whole-minute inactivity delay from 15 to 360, require unmetered networking, queue “Back up now,” disconnect its repository, or restore a repository as a new vault. Repository names may be selected from the list or entered as owner/repository or a GitHub HTTPS URL. The app neither creates repositories nor changes their privacy; users manage that on GitHub. Status is polled from local configuration every 1.5 seconds while the dialog is open.

Account Disconnect clears local credentials and cancels existing work for all vaults, but does not revoke tokens on GitHub or erase every repository binding. Repository disconnect affects only that vault's binding. See [the backup guide](vaults-and-github-backups.md) before altering these flows.

## User-visible limits

No OCR/handwriting recognition, audio recording, PDF text search, collaborative editing, automatic device-to-device merge, or attachment gallery is implemented. The UI has no trash recovery or Git historical-version picker. Folder nesting has implementation limits rather than mathematically unlimited depth. Drawing is stylus-first; canvas accessibility is currently a description and click hook, not semantic navigation of every handwritten object.

Do not add network/account requirements to ordinary note creation or editing. Widgets and vault rename work independently of the Lenovo GitHub tablet verification.

## System notes and lock-screen entry

See [default notes app integration](default-notes-app.md). Android CREATE_NOTE launches a separate quick editor rather than the library, using a fresh note in the current vault root. Close saves and finishes the system editor. Its bottom toolbar scrolls in narrow windows; the library, import/export and account menus are absent. Normal launcher/widget flows retain the existing folder-picker and library navigation.

Fill opens horizontal Pen fill and Highlighter base choices and retains the selected colour. Tap a closed area fully visible on the canvas; finger drags pan. Tapping a palette colour returns to Pen. Edits save after three seconds of inactivity; leaving/backgrounding requests an immediate save.
