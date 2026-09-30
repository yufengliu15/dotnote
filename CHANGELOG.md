# Changelog

## 0.8.1 — 2026-09-30

- Fixed image-heavy PowerPoint import by rendering and writing one slide at a time; removed the cumulative decoded-image limit. Reused archive parts no longer count repeatedly toward the expanded-size limit.
- Imported images support Select, move, resize, delete and undo/redo, including after reopening or restoring a backup. Eraser strokes leave images intact. Reimport images created before 0.8.1 to add the missing image provenance flag.
- Standardized versioning and packaging checks. Current Android versionCode: 13. Older release records remain unchanged.

## 0.8.0 — 2026-09-30

- Added image import and offline `.pptx` conversion to PDF, with reports for unsupported slide content.
- Release bookkeeping correction: the initial feature build was incorrectly delivered with 0.7.1 / code 11 metadata. It is the 0.8.0 feature baseline; no correctly versioned 0.8.0 APK is claimed here. Code 12 is reserved for that baseline; 0.8.1 advances to code 13.

## 0.7.1 — 2026-09-29

- Compact, resizable two-month calendar widget. See VALIDATION.md for historical build evidence and earlier releases.
