# Drawing and input pipeline

[Documentation index](README.md) · Sources: [NotebookView.kt](../app/src/main/java/dev/dotnote/app/NotebookView.kt), [Rendering.kt](../app/src/main/java/dev/dotnote/app/Rendering.kt), [Document.kt](../app/src/main/java/dev/dotnote/app/Document.kt), [SceneResources.kt](../app/src/main/java/dev/dotnote/app/SceneResources.kt)

## Two rendering paths, one saved model

`NotebookView` is a `FrameLayout` containing separate background, highlighter, and foreground Views with an AndroidX Ink `InProgressStrokesView` above them. Completed content is rendered by `ObjectRenderer`. The native overlay gives pen strokes low-latency live display; completed strokes become ordinary immutable `Item`s in `AppState.document`.

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
4. On owned pointer-up, encode the real recorded batch directly into an `Item` (without constructing another native mesh), register native-stroke-ID → item-ID handoff, and **commit/save immediately**. Then tell the native authoring view to finish.
5. Until native rendering announces completion, hide that item from completed scene rendering. The live layer still displays it, avoiding a double-dark stroke.
6. The finished-strokes listener removes the handoff mapping, invalidates the completed scene and removes finished native strokes. `settle()` also drains already-finished strokes before relevant UI actions.

Real samples include historical coordinates, elapsed event time and pressure clamped to `0..1`. Coordinates are converted through display density and camera. Times must strictly increase; equal/older samples are skipped. The input tool is TOUCH or STYLUS. Predictions are not recorded in the portable stroke.

`strokeItem` Base64-encodes the Ink input batch, records a parallel point list for geometry, records brush width, and stores opaque ARGB. Completed pen rendering decodes inputs and reconstructs the stock brush. It applies item transform and the world-to-screen matrix consistently; this is why zoomed strokes remain aligned after reopening.

The native stroke cache retains every visible stroke plus up to 400 offscreen entries. This prevents sequential redraws of more than 400 visible strokes from evicting and rebuilding every mesh on each frame. Entries validate color, width and encoded inputs; transforms reuse native geometry. Shape/grid line coordinates are cached by immutable item identity and drawn in one batch per object. Visible geometry memory therefore scales with the visible working set, not a fixed 400-object cap.

## Constant-opacity highlighter

Highlighter uses five times the current pen width. Its live preview is a mutable `LiveHighlight`: real input points append to paths capped at 128 segments each. No full point-list copy, Item bounds calculation, filled-outline construction, or boolean path union runs on every move. Pointer-up encodes the recorded inputs directly; it does not tessellate a native highlighter stroke just to save it.

The interactive `ObjectRenderer(vectorHighlights = false)` caches finished outlines and groups them by color. It draws these opaque outlines and the live paths inside **one** `saveLayerAlpha(..., 85)` layer, producing approximately one-third opacity even over repeated highlights. Pen and shapes remain above that layer. Color groups retain last-occurrence ordering, with the live color group on top.

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

`hitItem` first excludes locked PDFs and rejects points outside expanded bounds. It then tests distance to stroke samples or shape segments with tolerance `radius + width * maxScale / 2`. It tests line geometry, not the empty interior of a rectangle. Pen hit testing uses sample polylines rather than the exact pressure mesh.

The eraser samples the swept segment between successive events with radius `14 / zoom`, at roughly half-radius spacing, capped at 1,000 steps. Any hit removes the **whole item**, not a portion of a stroke. Preview changes are committed as one undo operation at release. PDFs remain intact.

## Selection and transforms

Lasso tool starts one of three gestures:

- Within `20 / zoom` of the bottom-right selection handle: resize.
- Otherwise within selected bounds expanded by `10 / zoom`: move selection.
- Else clear selection and collect a new lasso path.

A short gesture (`< 8 / zoom` displacement and fewer than five lasso samples) is tap selection: choose the last item in document order that hits within `12 / zoom`. A longer lasso selects unlocked items with **any stored point** inside the polygon after applying the item transform. It does not require full enclosure or compute exact curve/polygon intersections; a shape crossing the lasso with all stored corners outside can be missed.

Movement applies delta to the captured original transforms. Resize uses the selection's top-left anchor, independent x/y factors clamped to `0.05..20` for that gesture. Aspect ratio is not preserved during selection resize, even for a previously constrained square/circle. There is no rotation handle. Preview always derives from the original list to avoid cumulative drift. Release commits one history entry.

Selection actions recolor unlocked items, delete selected items, or clear selection. The color action opens the same picker used by palette slots. Recolor is undoable; choosing a palette color alone changes future ink, not selected objects.

## Typed text

Text items store their content, font size and two local rectangle corners. `TextTool.kt` uses Android StaticLayout to measure and render multiline Unicode text with a maximum line width of 4,096 world units. The shared ObjectRenderer caches layouts for the visible working set plus bounded offscreen entries and applies the same object transform for the canvas and PDF export. Text sits above the highlighter layer with pen/shapes. Font metrics depend on device fonts; a PDF captures the exporting device's rendering.

Text hit tests use the full transformed rectangle; lasso uses the same rectangular overlap behavior as imported images. Existing selection transforms, recoloring, deletion and whole-object eraser behavior apply, with one history entry per change. The dialog draft does not preview-mutably replace the scene. Saving/deleting text commits through AppState, and reopening uses the stored dimensions for culling/selection.

## Camera, grid and draw order

Navigation uses average pointer focus and the first two pointers' span. It zooms around the previous focus, then translates by focus delta. Pointer-count changes reset span/focus to avoid jumps. Camera updates save after a 350 ms debounce.

Single-finger pans use Android `VelocityTracker` and `OverScroller` for platform fling physics. A gesture must exceed touch slop and the system minimum fling speed; velocity is capped at the system maximum. Relative pixel offsets are converted to camera dp each frame, so motion is independent of zoom and large world coordinates. The infinite canvas has no artificial PDF-edge boundary. Two-finger gestures disable fling until a fresh down; stylus navigation, finger drawing, taps and cancelled gestures never launch momentum. A fresh touch, `settle()`, page jump, fit, window focus loss, detach or release stops motion at its current position. Existing editor lifecycle handling calls `settle()` before saving on stop.

`fit()` frames document bounds with margin, using zoom `0.08..2`; an empty scene uses an 800×700 fallback. PDF page navigation frames that page with zoom `0.08..4`. Manual pinch permits `0.08..8`.

The canvas background is `#FAFAF6`. Dots are spaced 24 world units; spacing doubles until at least 12 screen-dp apart. Dot radius is `1 / zoom`, keeping a stable apparent size. The grid does not snap strokes or shapes.

Paint order is paper → dots → visible PDF pages → highlighter layer → pen/shapes → temporary shape/lasso/selection affordances, with live native pen ink in the overlay. PDFs and highlights occupy semantic layers regardless of when they were added. A shared world-space spatial grid (512-unit cells) queries visible bounds once for all three completed-content layers. The index rebuilds when the immutable item list changes; pan/zoom queries reuse it, and unchanged viewports reuse the split scene lists. Candidates are deduplicated and sorted back into document order. Items spanning more than 64 cells use an overflow list; viewports spanning more than 4,096 cells fall back to linear culling to bound query work. Editing still rebuilds the index in proportion to note size, and extremely zoomed-out or overlapping scenes still require drawing all visible objects. Hit testing and selection retain their existing geometry rules.

## Undo and failure boundaries

`History` stores up to 80 prior immutable item lists. New commits clear redo. Undo/redo replaces items, clears selection and saves; it does not undo camera, dot visibility, palette changes, note rename, folders, or vault configuration. Reopening a note resets history. Item lists share immutable objects rather than deep-copying every encoded stroke.

`ACTION_CANCEL` restores the captured scene without committing partial edits. A completed pen save does not wait for asynchronous display handoff. However, editor shutdown or process death during a still-active, unfinished gesture is not a guaranteed recovery of that gesture. Save queues and lifecycle callbacks do not make unsaved RAM durable.

Relevant tests are `DocumentTest`, `GesturePipelineTest`, `NativePipelineTest`, `EditorUpdateTest`, `HighlighterPerformanceTest`, `VectorPerformanceTest`, `SceneResourcesTest`, and `InkStartupTest`; see the [test guide](build-test-release.md) for their precise scope and hardware limitations.
