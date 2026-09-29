# Drawing and input pipeline

[Documentation index](README.md) · Sources: [NotebookView.kt](../app/src/main/java/dev/dotnote/app/NotebookView.kt), [Rendering.kt](../app/src/main/java/dev/dotnote/app/Rendering.kt), [Document.kt](../app/src/main/java/dev/dotnote/app/Document.kt)

## Two rendering paths, one saved model

`NotebookView` is a `FrameLayout` containing a normal content View and an AndroidX Ink `InProgressStrokesView` above it. Completed content is rendered by `ObjectRenderer`. The native overlay gives pen strokes low-latency live display; completed strokes become ordinary immutable `Item`s in `AppState.document`.

Pen and marker brush families are lazily cached stock AndroidX families (`pressurePen`, `highlighter`). Brush epsilon is `0.1`. The pen brush is prepared during view construction and refreshed when color/width changes; `ink.eagerInit()` runs on attachment. See [startup details](widgets-and-ink-startup.md).

## Pointer arbitration

| Input | Behavior |
| --- | --- |
| Stylus/eraser down with no owned pointer | Owns the gesture and suppresses palm/finger effects until the gesture ends |
| Eraser tool type or primary stylus button at begin | Overrides selected tool with whole-stroke eraser |
| Stylus with Hand selected | Enters navigation instead of drawing |
| One finger, default settings | Pans |
| One finger with finger drawing enabled and non-Hand tool | Begins that drawing/editing tool |
| Two or more fingers without stylus ownership | Cancels an owned finger drawing gesture and enters pan/pinch navigation |
| Extra finger while stylus owns gesture | Does not draw or move camera |
| `ACTION_CANCEL` or canceled owned pointer-up | Cancels live ink and restores pre-gesture item list |

The view intercepts its touch stream and requests the parent not intercept it. Tool choice is captured at gesture start. Button support depends on Android reporting `BUTTON_STYLUS_PRIMARY`; there is no Lenovo-specific SDK, Bluetooth pen command handling or handwriting service. Palm rejection is ownership-based software arbitration, supplemented by whatever the device reports.

## Pen lifecycle and persistence handoff

1. At begin, settle already-finished native strokes, capture the pre-gesture item list, world start point and active tool, request unbuffered dispatch, and record the real down event.
2. Build an inverse world-to-screen matrix and start native live authoring with the prepared brush.
3. On movement, send the real MotionEvent plus optional `MotionEventPredictor` prediction to the native overlay. Recycle predicted events after use. Independently append real historical/current samples to a `MutableStrokeInputBatch`.
4. On owned pointer-up, create a `Stroke` from the real recorded batch, convert it to an `Item`, register native-stroke-ID → item-ID handoff, and **commit/save immediately**. Then tell the native authoring view to finish.
5. Until native rendering announces completion, hide that item from completed scene rendering. The live layer still displays it, avoiding a double-dark stroke.
6. The finished-strokes listener removes the handoff mapping, invalidates the completed scene and removes finished native strokes. `settle()` also drains already-finished strokes before relevant UI actions.

Real samples include historical coordinates, elapsed event time and pressure clamped to `0..1`. Coordinates are converted through display density and camera. Times must strictly increase; equal/older samples are skipped. The input tool is TOUCH or STYLUS. Predictions are not recorded in the portable stroke.

`strokeItem` Base64-encodes the Ink input batch, records a parallel point list for geometry, records brush width, and stores opaque ARGB. Completed pen rendering decodes inputs and reconstructs the stock brush. It applies item transform and the world-to-screen matrix consistently; this is why zoomed strokes remain aligned after reopening.

The native stroke cache holds 400 entries keyed by item ID, color and width. Transforms do not require re-decoding. This assumes a given item ID's underlying input bytes do not change; code adding destructive input editing must invalidate/change that cache identity.

## Constant-opacity highlighter

Highlighter uses five times the current pen width. It does **not** paint repeated translucent native strokes onto the canvas. Its live preview is a regular highlighter Item built from real recorded points; at pointer-up it creates the saved input-backed Item through the same serialization helper.

`ObjectRenderer.drawScene`:

1. Collects highlighter items and creates filled outlines from their point polylines using round cap/join stroke geometry, with a circle for a single point.
2. Applies each item's transform and unions outlines per color using `Path.Op.UNION` (falling back to `addPath` if union fails).
3. Combines the live outline with the appropriate color group without mutating the cached finished group.
4. Paints each group as opaque color inside **one** `saveLayerAlpha(..., 85)` layer.
5. Restores that layer once, giving approximately one-third opacity regardless of repeat coverage.
6. Draws pen and shape ink above the marker layer.

Same-color overlaps do not accumulate darkness, including interior antialiased seams addressed by the union operation. Different colors replace one another in that shared layer rather than mixing extra alpha. Groups are ordered by each color's last occurrence in the current item list, so the most recently occurring color wins at overlap; this is color-group ordering, not per-stroke chronological compositing. A live marker's group moves to the top.

Marker geometry uses constant-width point outlines, not the native pressure brush mesh, even though its saved inputs retain pressure. Finished outline cache capacity is 400, keyed by ID with item identity checks; grouped outlines are rebuilt when the highlight item list changes. The same renderer is used for PDF exports. Do not replace it with independent `draw(item)` calls for each marker: that would reintroduce accumulation.

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

## Camera, grid and draw order

Navigation uses average pointer focus and the first two pointers' span. It zooms around the previous focus, then translates by focus delta. Pointer-count changes reset span/focus to avoid jumps. Camera updates save after a 350 ms debounce.

`fit()` frames document bounds with margin, using zoom `0.08..2`; an empty scene uses an 800×700 fallback. PDF page navigation frames that page with zoom `0.08..4`. Manual pinch permits `0.08..8`.

The canvas background is `#FAFAF6`. Dots are spaced 24 world units; spacing doubles until at least 12 screen-dp apart. Dot radius is `1 / zoom`, keeping a stable apparent size. The grid does not snap strokes or shapes.

Paint order is paper → dots → visible PDF pages → highlighter layer → pen/shapes → temporary shape/lasso/selection affordances, with live native pen ink in the overlay. PDFs and highlights occupy semantic layers regardless of when they were added. Items are culled by bounds against the visible world rectangle; there is no spatial index, so scanning still scales with total item count.

## Undo and failure boundaries

`History` stores up to 80 prior immutable item lists. New commits clear redo. Undo/redo replaces items, clears selection and saves; it does not undo camera, dot visibility, palette changes, note rename, folders, or vault configuration. Reopening a note resets history. Item lists share immutable objects rather than deep-copying every encoded stroke.

`ACTION_CANCEL` restores the captured scene without committing partial edits. A completed pen save does not wait for asynchronous display handoff. However, editor shutdown or process death during a still-active, unfinished gesture is not a guaranteed recovery of that gesture. Save queues and lifecycle callbacks do not make unsaved RAM durable.

Relevant tests are `DocumentTest`, `GesturePipelineTest`, `NativePipelineTest`, `EditorUpdateTest`, and `InkStartupTest`; see the [test guide](build-test-release.md) for their precise scope and hardware limitations.
