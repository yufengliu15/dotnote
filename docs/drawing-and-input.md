# Drawing and input pipeline

[Documentation index](README.md) · Sources: [NotebookView.kt](../app/src/main/java/dev/dotnote/app/NotebookView.kt), [SceneTiles.kt](../app/src/main/java/dev/dotnote/app/SceneTiles.kt), [Rendering.kt](../app/src/main/java/dev/dotnote/app/Rendering.kt), [Document.kt](../app/src/main/java/dev/dotnote/app/Document.kt), [SceneResources.kt](../app/src/main/java/dev/dotnote/app/SceneResources.kt)

## Two rendering paths, one saved model

`NotebookView` is a `FrameLayout` containing separate background, highlighter, and foreground Views with an AndroidX Ink `InProgressStrokesView` above them. Completed content is rendered by `ObjectRenderer` into raster tiles (`SceneTiles`, below); frames composite those tiles. The native overlay gives pen strokes low-latency live display; completed strokes become ordinary immutable `Item`s in `AppState.document`.

## Tiled scene rendering (0.10.0)

Completed pen, shape, text and marker content is drawn once into 512×512 px bitmap tiles at the current zoom and then blitted, so a pan, zoom or redraw costs a few bitmap draws regardless of how many strokes are visible. `SceneTiles` keeps two bitmaps per tile (marker fills and ink); empty layers have no bitmap.

- **Rendering:** a single background thread (`Dotnote-Tiles`) renders visible tiles nearest the center first, then a one-tile ring around the viewport. It queries its own `SceneIndex` over an immutable snapshot of the item list. Native Ink strokes are shared between threads through `StrokeCache.shared` (one process-wide LRU of 3,000 tessellated strokes; Ink strokes are immutable), so a stroke is tessellated once and survives closing and reopening the note. Before drawing a tile with eight or more untessellated strokes, the tile thread and up to three background-priority `Dotnote-Ink` helpers (CPU cores minus one) build those meshes in parallel. The cache is cleared on `TRIM_MEMORY_BACKGROUND` or critical memory pressure.
- **Placement:** at rest, tiles are drawn at whole-pixel offsets of the camera (no resampling, no seams). Item bounds are tested with two device pixels of antialiasing margin.
- **Zoom:** during a pinch (scale changed within the last 140 ms and within 0.5×–2× of the tile level) the current tiles are drawn scaled. When the zoom settles, tiles are rendered for the exact new scale; the previous level fills gaps until they arrive.
- **Gaps:** a tile that is not ready is covered by the previous level if it has every overlapping tile, otherwise by direct vector drawing of just the missing area (one clipped pass). While a gap is already being rendered, that fallback skips strokes whose native geometry is not built yet, and an area holding more than 300 objects is left as paper; both appear with their tile instead of stalling the frame (for example, the first frames of a freshly opened dense note). Failed tile renders, and areas revealed during a zoom gesture, are drawn completely by the fallback. A zoom gesture stays "moving" until its settle callback runs, so every layer of one frame uses the same tile level. Hardware-accelerated frames draw the paper as a repeating one-period shader tile (rebuilt only when the period changes, a few kilobytes), so neither the UI thread nor the GPU rasterizes thousands of dots per frame. Software canvases use the same tile while a pinch changes the period and otherwise blit one cached full-view image, rebuilt once the period is stable.
- **Edits:** the tile scene is the document without hidden hand-off strokes and without a selection being dragged. A pure append (pen-up, new shape/text) draws the new items onto the ready tiles they touch immediately; it is topmost. Other edits (erase, delete, undo, recolor, drop) find the changed items by identity and repaint only the dirty pixel rectangles of the ready tiles they touch: each rectangle is cleared and every item touching it is redrawn in order, clipped to it, which is pixel-identical to a full render. A tile whose dirty area exceeds half its size (or more than 48 rectangles) is re-rendered in the background instead. Tiles whose render started before an edit replay later appends or are discarded and re-rendered if a later edit touched them. A change in marker color stacking redraws tiles containing markers.
- **Dragging a selection:** while a move/resize gesture is active the selected items are excluded from tiles. A move rasterizes the selection once (sprite, at most twice the view's area) and translates it each frame; a resize, or a larger selection, draws the items live. On release they rejoin in document order and the affected tile regions are repainted.
- **Markers without a layer:** when every visible marker tile is finished and nothing live or dragged belongs to the marker group, marker tiles are drawn with 85/255 paint alpha directly. Tiles never overlap, so this equals the translucent layer without allocating it. Otherwise the layer path is used.
- **Memory:** up to 96 MB of tile bitmaps; least recently needed tiles outside the latest frame are evicted first, previous zoom levels before current ones. Released bitmaps are reused after 300 ms (they may still be referenced by the displayed frame).

`TileRenderingTest` compares tiled frames against direct vector rendering after appends, erasing, marker reordering, drags, pans and zoom changes.

Pen and marker brush families are lazily cached stock AndroidX families (`pressurePen`, `highlighter`). Brush epsilon is `0.1`. Both brushes are prepared during view construction and refreshed when color/width changes; `ink.eagerInit()` runs on attachment. Native stroke preparation starts asynchronously at activity launch, and an offscreen stroke exercises the authoring surface after its first layout. See [startup details](widgets-and-ink-startup.md).

## Pointer arbitration

| Input | Behavior |
| --- | --- |
| Stylus/eraser down with no owned pointer | Owns the gesture and suppresses palm/finger effects until the gesture ends |
| Eraser tool type or primary stylus button at begin | Overrides selected tool with whole-stroke eraser |
| Stylus with Text selected | Tap adds/edits text at the world coordinate; drag or cancel creates nothing |
| One finger, default settings | Pans, then coasts after a flick |
| One finger with finger drawing enabled and non-Text tool | Begins that drawing/editing tool |
| One finger with Text selected | Tap adds/edits text; drag/fling pans, including with finger drawing enabled |
| Two or more fingers without stylus ownership | Cancels an owned finger drawing gesture and enters pan/pinch navigation |
| Extra finger while stylus owns gesture | Does not draw or move camera |
| `ACTION_CANCEL` or canceled owned pointer-up | Cancels live ink and restores pre-gesture item list |

The view intercepts its touch stream and requests the parent not intercept it. Tool choice is captured at gesture start. Button support depends on Android reporting `BUTTON_STYLUS_PRIMARY`; there is no Lenovo-specific SDK, Bluetooth pen command handling or handwriting service. Palm rejection is ownership-based software arbitration, supplemented by whatever the device reports.

## Pen lifecycle and persistence handoff

1. At begin, settle already-finished native strokes, capture the pre-gesture item list, world start point and active tool, request unbuffered dispatch, and record the real down event.
2. Build an inverse world-to-screen matrix and start native live authoring with the prepared brush.
3. On movement, send the real MotionEvent plus optional `MotionEventPredictor` prediction to the native overlay. Recycle predicted events after use. Independently append real historical/current samples to a `MutableStrokeInputBatch`.
4. On owned pointer-up, encode the real recorded batch directly into an `Item` (without constructing another native mesh), register native-stroke-ID → item-ID handoff, and **commit immediately and schedule autosave**. Then tell the native authoring view to finish.
5. Until native rendering announces completion, hide that item from completed scene rendering. The live layer still displays it, avoiding a double-dark stroke.
6. The finished-strokes listener removes the handoff mapping, invalidates the completed scene and removes finished native strokes. `settle()` also drains already-finished strokes before relevant UI actions.

Real samples include historical coordinates, elapsed event time and pressure clamped to `0..1`. Coordinates are converted through display density and camera. Times must strictly increase; equal/older samples are skipped. The input tool is TOUCH or STYLUS. Predictions are not recorded in the portable stroke.

`strokeItem` Base64-encodes the Ink input batch, records a parallel point list for geometry, records brush width, and stores opaque ARGB. Completed pen rendering decodes inputs and reconstructs the stock brush. It applies item transform and the world-to-screen matrix consistently; this is why zoomed strokes remain aligned after reopening.

The editor's tile and fallback renderers and library previews share one native stroke cache (`StrokeCache.shared`, 3,000 entries). Standalone `ObjectRenderer`s (PDF export, tests) retain every visible stroke plus up to 400 offscreen entries. Entries validate color, width and encoded inputs; transforms reuse native geometry. Shape/grid line coordinates are cached by immutable item identity and drawn in one batch per object. Visible geometry memory therefore scales with the visible working set, not a fixed 400-object cap.

## Constant-opacity highlighter

Highlighter uses five times the current pen width. Its live preview is a mutable `LiveHighlight`: real input points append to paths capped at 128 segments each. No full point-list copy, Item bounds calculation, filled-outline construction, or boolean path union runs on every move. Pointer-up encodes the recorded inputs directly; it does not tessellate a native highlighter stroke just to save it.

The interactive renderer caches finished outlines and groups them by color. Marker tiles hold these opaque fills; the highlighter View draws the marker tiles and the live paths inside **one** `saveLayerAlpha(..., 85)` layer, producing approximately one-third opacity even over repeated highlights. Pen and shapes remain above that layer. Color groups follow scene-wide last-occurrence ordering (the most recently used color on top); while a live stroke is drawn, existing strokes of its color keep their place until pen-up.

Only the highlighter View invalidates on live moves, once per animation frame. Android can reuse the background/PDF and finished pen/shape display lists. Document, camera, selection, and tool changes refresh the relevant editor layers normally.

PDF export retains normalized per-color `Path.Op.UNION` outlines to avoid vector antialiasing seams; expensive unions are kept out of interactive drawing. Antialiased edge coverage can differ slightly between the screen's opaque paths and the normalized PDF outlines, but overlapping interiors remain one-third opaque. Do not give each highlight its own translucent layer: that would accumulate opacity.

Marker geometry uses constant-width point outlines, not a pressure-dependent mesh, while saved input data still retains pressure. Finished outlines retain the visible working set plus 400 offscreen entries, with item identity checks. Group caches are rebuilt when the visible highlighter list changes. Unchanged scenes reuse their layer lists during live input; very large documents still require physical-device profiling.

## Shapes and hit testing

Shape preview contains two points: start and current end. Release commits it only when drag length exceeds `2 / zoom` world units. Square/circle use the larger absolute axis delta to constrain the drawn bounds. Available kinds:

- Line: one segment.
- Arrow: shaft plus two heads at ±0.5 radians, head length `max(12, width * 4)`.
- Rectangle/square: border segments.
- Grid: rectangle with `rows + 1` horizontal and `cols + 1` vertical lines; UI limits subdivisions to `1..30`.
- Ellipse/circle: native oval drawing; hit-test geometry approximates it with 64 segments.

Eraser, tap and lasso selection first query the spatial index for items near the gesture, so they do not scan the whole note. An eraser move that hits nothing leaves the scene untouched. `hitItem` first excludes locked PDFs and rejects points outside expanded bounds. It then tests distance to stroke samples or shape segments with tolerance `radius + width * maxScale / 2`. It tests line geometry, not the empty interior of a rectangle. Pen hit testing uses sample polylines rather than the exact pressure mesh.

The eraser samples the swept segment between successive events with radius `14 / zoom`, at roughly half-radius spacing, capped at 1,000 steps. Any hit removes the **whole item**, not a portion of a stroke. Preview changes are committed as one undo operation at release. PDFs remain intact.

## Selection and transforms

Lasso tool starts one of three gestures:

- Within `20 / zoom` of the bottom-right selection handle: apply the selected Resize/Scale mode.
- Otherwise within selected bounds expanded by `10 / zoom`: move selection.
- Else clear selection and collect a new lasso path.

A short gesture (`< 8 / zoom` displacement and fewer than five lasso samples) is tap selection: choose the last item in document order that hits within `12 / zoom`. A longer lasso selects unlocked items with **any stored point** inside the polygon after applying the item transform. It does not require full enclosure or compute exact curve/polygon intersections; a shape crossing the lasso with all stored corners outside can be missed.

Movement applies delta to the captured original transforms. Non-text resizing uses the selection's top-left anchor, independent x/y factors clamped to `0.05..20` for that gesture. Text Resize changes wrapping width in local coordinates and remeasures the height at the existing font/scale. Text-only Scale uses the axis with the larger change from 1 uniformly and preserves the layout; mixed Scale uses the existing group transform. Aspect ratio is not preserved during selection resize, even for a previously constrained square/circle. There is no rotation handle. Preview always derives from the original list to avoid cumulative drift. Release commits one history entry.

Selection actions recolor unlocked items, delete selected items, or clear selection. The color action opens the same picker used by palette slots. Recolor is undoable; choosing a palette color alone changes future ink, not selected objects.

## Typed text

Text items store their content, font size and two local rectangle corners. `TextTool.kt` uses Android StaticLayout to measure and render multiline Unicode text with a maximum line width of 4,096 world units. The shared ObjectRenderer caches layouts for the visible working set plus bounded offscreen entries and applies the same object transform for the canvas and PDF export. Text sits above the highlighter layer with pen/shapes. Font metrics depend on device fonts; a PDF captures the exporting device's rendering.

Text hit tests use the full transformed rectangle; lasso uses the same rectangular overlap behavior as imported images. Resize preserves the font/transform and reflows soft wraps; Scale changes the transform while preserving text/points. The two local corners store the chosen wrapping width and measured height without new JSON fields. Recoloring, deletion and whole-object eraser behavior apply, with one history entry per change. The dialog draft does not preview-mutably replace the scene. Saving/deleting text commits through AppState, and reopening uses the stored dimensions for culling/selection.

## Camera, grid and draw order

Navigation uses average pointer focus and the first two pointers' span. It zooms around the previous focus, then translates by focus delta. Pointer-count changes reset span/focus to avoid jumps. Camera updates save after a 350 ms debounce.

Single-finger pans use Android `VelocityTracker` and `OverScroller` for platform fling physics. A gesture must exceed touch slop and the system minimum fling speed; velocity is capped at the system maximum. Relative pixel offsets are converted to camera dp each frame, so motion is independent of zoom and large world coordinates. The infinite canvas has no artificial PDF-edge boundary. Two-finger gestures disable fling until a fresh down; stylus navigation, finger drawing, taps and cancelled gestures never launch momentum. A fresh touch, `settle()`, page jump, fit, window focus loss, detach or release stops motion at its current position. Existing editor lifecycle handling calls `settle()` before saving on stop.

`fit()` frames document bounds with margin, using zoom `0.08..2`; an empty scene uses an 800×700 fallback. PDF page navigation frames that page with zoom `0.08..4`. Manual pinch permits `0.08..8`.

The canvas background is `#FAFAF6`. Dots are spaced 24 world units; spacing doubles until at least 12 screen-dp apart. Dot radius is `1 / zoom`, keeping a stable apparent size; all dots are submitted in one `drawPoints` call. The grid does not snap strokes or shapes.

Paint order is paper → dots → visible PDF pages → highlighter layer (marker tiles, dragged markers, live marker) → pen/shapes/text tiles → dragged selection → temporary shape/lasso/selection affordances, with live native pen ink in the overlay. PDFs and highlights occupy semantic layers regardless of when they were added. A world-space spatial grid (512-unit cells) answers rectangle queries for tiles, gaps, the eraser and selection. Appends and in-place replacements (moving or recoloring a selection) update only the changed items; other edits rebuild it. Candidates are deduplicated with a generation-marked array and sorted back into document order. Items spanning more than 64 cells use an overflow list; queries spanning more than 4,096 cells fall back to linear culling. Hit testing and selection retain their existing geometry rules.

## Undo and failure boundaries

`History` stores up to 80 prior immutable item lists. New commits clear redo. Undo/redo replaces items, clears selection and saves; it does not undo camera, dot visibility, palette changes, note rename, folders, or vault configuration. Reopening a note resets history. Item lists share immutable objects rather than deep-copying every encoded stroke.

`ACTION_CANCEL` restores the captured scene without committing partial edits. A completed pen save does not wait for asynchronous display handoff. However, editor shutdown or process death during a still-active, unfinished gesture is not a guaranteed recovery of that gesture. Save queues and lifecycle callbacks do not make unsaved RAM durable.

Relevant tests are `DocumentTest`, `GesturePipelineTest`, `NativePipelineTest`, `EditorUpdateTest`, `HighlighterPerformanceTest`, `VectorPerformanceTest`, `SceneResourcesTest`, and `InkStartupTest`; see the [test guide](build-test-release.md) for their precise scope and hardware limitations.

## Bucket fill (0.12.0)

Fill opens a horizontal pair of Pen fill / Highlighter base buttons; both retain the selected palette colour. A stylus or finger tap floods the same-colour connected region of the visible scene, including visible PDF/image content. Finger drags still pan. Processing runs off the UI thread on a raster capped at 1024×1024; PDF pages are rendered/recycled individually, not across the whole deck. Regions reaching the viewport edge are rejected; zoom out until the closed boundary is fully visible. Very thin boundaries at low zoom can leak and be rejected.

The resulting item stores vector scanline rectangles with `fill: true` and kind PEN or HIGHLIGHTER. Rendering normalizes shared edges and uses the existing constant-opacity highlighter layer. Fills are single undoable/selectable/recolourable/erasable objects; transforms, reopen, backups and PDF export preserve coverage. Palette taps settle input, select the colour, clear selection and activate Pen.
