# Changelog

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
