# PDFs and document export

[Documentation index](README.md) · Sources: [Rendering.kt](../android/app/src/main/java/dev/dotnote/app/Rendering.kt), [NotebookView.kt](../android/app/src/main/java/dev/dotnote/app/NotebookView.kt), [AppState.kt](../android/app/src/main/java/dev/dotnote/app/AppState.kt)

## Import

The editor's “Import PDF, image or PowerPoint” action uses Android `OpenDocument` with PDF, `image/*`, and PowerPoint `.pptx` MIME types. `AppState.importDocument` captures the destination store, note and placement before IO; `DocumentImport` dispatches by filename/MIME type and normalizes images and slides to PDF. All returned pages commit as one undoable item-list change. It places the new document below the current scene's bottom bound plus 48 world units, or at y=0 for an empty scene.

Images use Android ImageDecoder (including EXIF orientation), software bitmaps capped at 4096 pixels on the longest edge, and a white PDF background. Animated images import as a still image. Supported formats depend on the device decoder (PNG/JPEG/WebP and others); unsupported or corrupt input reports an error. The resulting PDF-backed item stores `image: true` and supports Select (tap/lasso), moving, resizing and deleting with undo/redo. Eraser strokes and recoloring leave it intact. Normal PDFs and slides remain locked. Images imported before 0.8.1 must be reimported to add provenance; old single-page PDFs cannot safely be classified as images.

`PptxPdf` converts `.pptx` entirely offline using an Android bitmap canvas and `SlidePdfWriter`. Each slide is rendered to at most 2048 pixels on its longest edge, losslessly compressed as RGB rows into the PDF, then recycled before the next slide. PDF page dimensions and order are preserved; memory does not grow with the sum of decoded pixels across slides. Converted text is rasterized, so extreme zoom can look soft. The writer implements only generated image XObjects, page trees, indirect stream lengths and classic cross-reference offsets ([PDF specification](https://developer.adobe.com/document-services/docs/assets/35e4369068f86065372c18787171a17e/PDF_ISO_32000-1.pdf)); it does not parse or merge arbitrary PDFs.

The converter follows presentation relationships for slide order and dimensions, resolves layout/master placeholder positions and basic text defaults, and renders embedded raster images, groups, rectangles, rounded rectangles, ellipses, lines and styled text. It uses device fonts. Charts, tables, SmartArt, linked images, media, complex backgrounds and other unsupported content may be omitted or simplified; a dismissible conversion report lists detected limitations. For exact appearance, export PDF in PowerPoint before importing. Legacy binary `.ppt` is not supported. Source image/PowerPoint files are not retained in the vault; the converted PDF is retained and included in normal exports/backups.

Image/PowerPoint source copies are capped at 128 MiB and removed from cache on success/failure. PowerPoint archives are read without extraction, reject external/path-escaping relationships and XML document types, and have bounded entry counts, part sizes, XML nesting and unique expanded content (256 MiB). Repeated reads of shared parts count once. Conversion permits up to 500 slides, caps embedded raster images at 2048 pixels, and rejects oversized slide/object dimensions. The 0.8.0 cumulative decoded-image cap was removed; images draw onto the current slide and are recycled immediately, and completed slides stream to disk. Generated PDFs go through the same validation, hashing and storage path below. Failed conversion does not publish partial pages or attachments.

1. Stream the selected URI to a temporary UUID-named PDF in the active vault's `attachments/`, rejecting size at or above 512 MiB. Sync the output file descriptor.
2. Open it with Android `PdfRenderer`. Encrypted or damaged files can fail here; the app reports the import error and removes the temporary file.
3. Read each page's dimensions. Add a locked PDF item at x=0 with width 800 world units, proportional height, and 32 units of vertical space before the next page. Every page references the same copied asset and its own zero-based page index.
4. Compute SHA-256 of the original bytes. Rename to `<sha256>.pdf`, or verify/reuse an existing file of that name. Replace temporary references with that name.
5. Append the items and save normally. The source URI is no longer needed for viewing; no long-term external URI grant is required for the copied asset.

The whole PDF is retained; there is no page-selection import dialog. PDF items cannot be erased, selected or moved with drawing tools. Undo can remove the import's item-list change, but its copied asset remains locally until a future explicit cleanup feature. A successful import larger than GitHub's file limit still works locally and will later block that vault's Git backup; it is not silently omitted.

## On-screen rendering and caching

`PdfPageSource` wraps Android `PdfRenderer`, keeping an access-ordered cache of at most three open source documents. Its caller must serialize all access. A `NotebookView` has a single executor and one source instance bound to that view's vault assets directory.

The content View culls page items outside the viewport. For a visible page, it computes a desired pixel width from world width × camera zoom × density, then selects a 512, 1024 or 2048 pixel bucket. Cache keys are `asset:page:targetWidth`; the bitmap LRU is measured in allocation kilobytes and capped at `48 * 1024` KiB (approximately 48 MiB).

A missing bitmap draws white paper and a loading label, enqueues at most one request per key, and invalidates when the worker posts a result. Failed keys are remembered for that view and display “Page unavailable” rather than retrying on every frame. Recreating the view clears those sets. Disposed views ignore late results; PDF sources close behind outstanding work on the same executor.

Rendering constrains width to 64–2048 pixels and height to at most 4096. The source page is rasterized on white with display rendering mode. Very tall pages and extreme zoom are therefore bounded-resolution previews. PDF text is neither selectable nor indexed/searchable by Dotnote. There is no PDF text extraction or original object editing.

## Page scrubber

`PageScrubber` (in [PageScrubber.kt](../android/app/src/main/java/dev/dotnote/app/PageScrubber.kt)) appears at the editor canvas's top-right when a note has at least two locked PDF pages. Pages are ordered by top edge, then left edge. The label shows the page whose vertical center is nearest the viewport center. The scrubber fades in on any camera change (pan, zoom, fling, page jump, including opening the note) and fades out `PageScrub.HIDE_AFTER_MS` (2 s) after the last one; it stays while a finger is on it. The camera is observed with `snapshotFlow`, so panning does not recompose the overlay; edits that leave the camera unchanged do not show it. While hidden it receives no touches.

Dragging past touch slop settles ink and accumulates a fractional page position. Each move adds `dy × PageScrub.gain(speed)` pages, where speed comes from a Compose `VelocityTracker`: below 250 dp/s the gain is one page per 40 dp; at or above 1500 dp/s a drag the height of the canvas spans first to last page; speeds between blend linearly. Short documents never drop below the one-page-per-40-dp rate. Whenever the rounded position changes, the camera frames that page with the same fit as the page navigator. A tap without dragging moves one page back (upper half) or forward (lower half). Camera moves do not add undo history.

## PDF export

The editor offers “Export entire note as PDF” and “Export visible area as PDF.” Both use `CreateDocument(application/pdf)`. Before export, AppState flushes the current note and captures the immutable document. Export uses its own renderer/source on IO, not the screen's cache.

| Choice | Region and paging |
| --- | --- |
| Visible area | Captures current viewport bounds when the export action is chosen; one PDF page |
| Entire note | Document bounds plus 24 world units; tiles of 800×1100 world units, row-major |
| Empty entire note | Uses a default 800×1100 world-unit region |

Output pages are 612×842 PDF units. Content fits within 564×794 using `min(564 / tileWidth, 794 / tileHeight)`, translated with a 24-unit top/left margin and clipped to the tile. This scales without distorting aspect ratio; it does not preserve original PDF page dimensions. Entire-note tiling can split annotations or imported pages across output sheets. A long or sparse scene can produce many blank/mostly blank tiles. More than 500 output pages is rejected with a suggestion to export the visible area.

Each output page draws:

1. White background; the editor's dot grid is not exported.
2. Intersecting imported PDF items rasterized at target width 1600 (height still bounded by the source renderer).
3. Intersecting highlighter and pen/shape items through the shared `ObjectRenderer`, retaining its constant-opacity marker composition.

The export is a flattened visual PDF: imported PDF content becomes raster images and annotations are drawn through Android's PDF canvas. It is not a portable editable `.dotnote` file, a preservation of original PDF objects, or a backup of folder/vault metadata. Selection handles and live previews are not exported.

Opening the output stream and writing can fail after pages are built; an external destination may remain incomplete. There is no cross-provider transactional replacement. Large exports can consume substantial memory/time even within the 500-page bound; imported-page rendering is synchronous within the export loop and its bitmaps are recycled after drawing.

## Regression checks

`NativePipelineTest.pdfImportExportBackupRestoreAndFailedRestoreAreConsistent` exercises import, render/export, attachment-preserving ZIP restore and rejection paths. `EditorUpdateTest.pdfHighlightsStayTranslucentAndDoNotAccumulate` renders exported pages to verify marker opacity. On a physical tablet also test large PDFs, distant-page navigation, zooming, rotation, and repeated exports to the actual destination provider.

`DocumentImportTest` checks file classification and stream limits. `DocumentImportPipelineTest` checks image pixels/aspect ratio, JPEG orientation, decode bounds, backup/reopen, PPTX slide order, inherited text placement, shapes and embedded images, conversion warnings, XML document-type rejection and failed-import cleanup.

Any change to coordinate transforms, item bounds, highlighter drawing or brush reconstruction must be checked on both screen and PDF output. A screen-only fix can change clipping, scaling or opacity during export.
