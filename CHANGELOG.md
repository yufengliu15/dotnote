# Changelog

## 0.15.0 — 2026-10-06

- The library home (vault root, no search) shows a Recent row with up to five notes of the active vault, most recently opened first. Recency stays device-local and is the same list the Recent notes widget uses; viewing a note still does not change its backup revision.
- Notes with two or more PDF pages show a page scrubber in the editor's top-right corner. A slow drag moves one page per 40 dp; faster drags skip more pages, and a fast drag the height of the canvas spans the whole document. Tap its upper or lower half for the previous or next page. Jumps frame the page like the page navigator.
- Added `PageScrubRulesTest` and `RecentHomeRulesTest`. No file format change. Android versionCode: 26.

## 0.14.0

- Organize the repository into `android/`, `ios/`, and `shared/` with a Kotlin Multiplatform document engine, codec, geometry, spatial index, resource cache, history and editor operations.
- Add a native iPadOS app for local handwriting, pressure, shapes, typed text, selection, PDF/image import and portable vault import/export. See the iPadOS guide for initial platform differences and device-signing setup.
- Preserve existing Android notes and opaque AndroidX Ink data. Both apps render portable pressure polylines created on iPad; update Android before exchanging those new strokes.
- Gate Android releases on shared Kotlin and iPad simulator checks, retain existing Android regression gates, and package both platform source trees.

## 0.13.0 — 2026-10-02

- Rendering overhaul for dense notes: finished ink and highlights are drawn once into 512 px tiles on a background thread, and frames only composite tiles, so pan, zoom, pen-up, eraser and selection costs no longer grow with the number of visible strokes. Edits repaint only the changed pixels of affected tiles; a moved selection is translated as one cached image; a whole-note overview fills areas revealed while zooming out; the paper and dots are one cached image; marker tiles are stored pre-faded, so no translucent layer is needed while idle. Stroke meshes for a tile are built on several cores.
- Faster opening: a streaming JSON parser replaces org.json for notes; the parsed scene and stroke meshes of recently used notes are reused when reopening a note or drawing its library preview while its file is unchanged. Library previews are kept on disk, so dense notes are not rendered again on every launch. Eraser, tap and lasso selection query nearby items only; PDF export visits only the items on each page. Editor chrome no longer recomposes on every pan frame.
- Faster saving: the Room index stores metadata only (no note content; replaces 0.9.2's chunked reads), each object's JSON is cached and streamed into the file byte-identically, queued saves of a note collapse into one write, and the three-second autosave reuses one timer instead of starting a coroutine per edit.
- Faster startup and sync: validated note files are remembered by inode/size/nanosecond mtime in app-private storage, so unchanged notes are not re-read on startup, before backups or after import/restore. GitHub backups send changed text inside the tree request, reuse the last tree listing and blob hashes, need one request when nothing changed, hard-link snapshots, and restores download up to six files at a time. Folder renames/moves rewrite only notes whose paths change.
- Added `PerformanceBenchmarkTest`, `TileRenderingTest` and `CodecCompatibilityTest`. No file format change. Android versionCode: 24.

## 0.12.1 — 2026-10-02

- Deliver the colour-to-Pen, held folder dragging, Pen/Highlighter bucket fill and faster three-second autosaving changes from the 0.12.0 development snapshot through the signed GitHub Actions release workflow. Android versionCode: 23.
- Require publishing through Release Dotnote after every completed deliverable app build; local APK delivery alone is insufficient unless explicitly requested.

## 0.12.0 — 2026-10-01

- Tapping a palette colour activates Pen. Long-press and drag notes/folders into folder cards or parent breadcrumbs; the item stays held until release. Invalid folder cycles are rejected.
- Added bucket Fill with side-by-side Pen fill and Highlighter base options using the selected colour. Closed areas must be fully visible; fills support selection, transforms, undo/reopen, backups and PDF export.
- Edits and camera changes save after three seconds of inactivity; close, switch, export and background requests bypass the delay. Superseded queued autosaves are skipped. Content saves avoid loading the old large document, rebuilding drawing objects, duplicating the note into a journal, and walking the vault.
- Android versionCode: 22. Portable fill coverage adds an optional `fill` field, default false for existing notes. Older builds do not render new fills correctly.

## 0.11.2 — 2026-10-01

- Release workflow runs now publish the signed APK and create the GitHub Release by default after validation passes. Validation-only runs remain an explicit opt-out. Android versionCode: 21.

## 0.11.1 — 2026-10-01

- Activate the GitHub release pipeline with a validation-only default, explicit publishing option, protected signing secrets and downloadable validation artifacts. Preserve earlier snapshot version records and use a clean committed source tree. Android versionCode: 20.

## 0.11.0 — 2026-10-01

- Added manual in-app update checks, optional preview releases, bounded APK downloads, hash/package/version/signing verification, and Android installer handoff after saving notes. No extra updater app or account is required.
- Added an explicitly signed non-debuggable distribution build and a manually triggered GitHub Actions release workflow with clean-source, signing and version checks. Existing signing identity is retained for compatible updates. Android versionCode: 19.

## 0.10.1 — 2026-10-01

- Long notes containing PDFs now preview only the first page of the first PDF, including annotations within that page. Other pages are not rendered. Ordinary notes keep full-scene previews. Android versionCode: 18.

## 0.10.0 — 2026-10-01

- Added centered note snapshots to the library grid using the editor’s shared fit calculation and renderer. Visible cards load serially off the UI thread, cache up to 8 MiB of images, and refresh by vault, note and modification time. PDF pages render and recycle one at a time. Android versionCode: 17.

## 0.9.2 — 2026-10-01

- Fixed Android CursorWindow overflow when a note's serialized document outgrows a database row read. Save, open, rename/move/delete, folder operations, ZIP backup/restore and legacy migration now read document content in bounded chunks within a consistent Room transaction.
- Preserved note files, Room schema 2, no-op save timestamps and Unicode across chunk boundaries. Added oversized-note and boundary regressions. Android versionCode: 16.

## 0.9.1 — 2026-10-01

- Added Resize and Scale choices for selected text. Resize reflows words at the existing font size and fits the height to the lines; Scale preserves wrapping and scales text selections proportionally. Editing retains the chosen box width. No document migration is needed.
- Replaced vague startup wording with actual local-loading phases and removed the empty-library flash. Startup scans now share document validation without constructing scenes or reparsing document JSON; index inserts are batched. Backup job reconciliation runs on IO independently of the startup overlay and never waits for repository networking.
- Added reflow/scale, validation-equivalence and local startup/performance regressions. Android versionCode: 15.

## 0.9.0 — 2026-09-30

- Replaced the Pan toolbar button with Text: add/edit multiline Unicode text, adjust font size, and select/move/resize/recolor/delete it with undo/redo. Text persists through reopening and backups and renders in PDF exports. Finger pan/fling and pinch remain available.
- Added template notes: create a template or mark an existing note with Use as template, then start independent notes from it in any folder of the selected vault. Copies retain attachments, layout, camera and grid settings with fresh note/object IDs.
- Added backward-compatible template metadata and Room schema 1→2 migration; existing notes default to ordinary notes. Text objects require app 0.9.0 or later. Android versionCode: 14.

## 0.8.1 — 2026-09-30

- Fixed image-heavy PowerPoint import by rendering and writing one slide at a time; removed the cumulative decoded-image limit. Reused archive parts no longer count repeatedly toward the expanded-size limit.
- Imported images support Select, move, resize, delete and undo/redo, including after reopening or restoring a backup. Eraser strokes leave images intact. Reimport images created before 0.8.1 to add the missing image provenance flag.
- Standardized versioning and packaging checks. Current Android versionCode: 13. Older release records remain unchanged.

## 0.8.0 — 2026-09-30

- Added image import and offline `.pptx` conversion to PDF, with reports for unsupported slide content.
- Release bookkeeping correction: the initial feature build was incorrectly delivered with 0.7.1 / code 11 metadata. It is the 0.8.0 feature baseline; no correctly versioned 0.8.0 APK is claimed here. Code 12 is reserved for that baseline; 0.8.1 advances to code 13.

## 0.7.1 — 2026-09-29

- Compact, resizable two-month calendar widget. See VALIDATION.md for historical build evidence and earlier releases.
