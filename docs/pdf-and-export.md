# PDFs and document export

[Documentation index](README.md) · Sources: [Rendering.kt](../app/src/main/java/dev/dotnote/app/Rendering.kt), [NotebookView.kt](../app/src/main/java/dev/dotnote/app/NotebookView.kt), [AppState.kt](../app/src/main/java/dev/dotnote/app/AppState.kt)

## Import

The editor's “Import PDF” action uses Android `OpenDocument` with `application/pdf`. `AppState.importPdf` runs `PdfFiles.import` on IO and commits all returned pages as one item-list change. It places the new document below the current scene's bottom bound plus 48 world units, or at y=0 for an empty scene.

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

Any change to coordinate transforms, item bounds, highlighter drawing or brush reconstruction must be checked on both screen and PDF output. A screen-only fix can change clipping, scaling or opacity during export.
