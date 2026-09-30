# Data model and portable format

[Documentation index](README.md) · Sources: [Document.kt](../app/src/main/java/dev/dotnote/app/Document.kt), [VaultFiles.kt](../app/src/main/java/dev/dotnote/app/VaultFiles.kt), [AppState.kt](../app/src/main/java/dev/dotnote/app/AppState.kt)

## Identities and directory layout

There are four distinct identities: a device-local vault directory ID; a portable vault ID in its manifest; folder/note IDs; and drawing item IDs. New IDs are UUID strings. Import/restore gives a vault a new **local** ID while keeping the portable ID and note/folder IDs. Legacy ZIP restore instead creates new note/folder IDs to merge into an existing vault.

```text
files/vaults/<local-vault-id>/
  .dotnote/
    vault.json
    settings.json                  optional until written
    transaction.json               temporary local recovery journal
    migrated                       local legacy migration marker
    trash/<note-id>.dotnote         local deleted-note recovery copy
  attachments/<asset-name>.pdf
  Lecture [<note-id>].dotnote
  Course [<folder-id>]/
    .folder.json
    Week 1 [<folder-id>]/
      .folder.json
      Notes [<note-id>].dotnote
```

Physical paths are derived from sanitized display names plus the **full ID**, not an ID prefix. Folder membership comes from directory nesting. Renaming a filename manually does not change the title stored in JSON; later app writes can regenerate the path from that title.

The name component replaces filesystem punctuation/control characters with `_`, trims whitespace and edge dots, takes at most 90 characters, falls back to `Untitled`, then truncates to 120 UTF-8 bytes before appending ` [id]`. Metadata preserves the logical title/name. Full relative paths must still satisfy path limits.

## Vault manifest and folder marker

`.dotnote/vault.json`:

```json
{
  "format": "dotnote-vault",
  "version": 1,
  "id": "7fdb08de-5b12-4d24-b849-000000000001",
  "name": "University"
}
```

A folder's `.folder.json`:

```json
{"id":"7fdb08de-5b12-4d24-b849-000000000002","name":"Signal processing"}
```

There is no parent ID in the marker; the scanner derives it from the containing folder. If a directory lacks a marker, reading the vault generates an ID and writes a marker. Reading is therefore not always a read-only operation. The root itself has no folder entity. Root folder references in Room are Kotlin/SQL `null`.

Vault creation/rename trims the display name and caps it at 120 characters. Renaming does not change either vault ID or its physical root directory.

## Complete `.dotnote` example

This is a valid minimal note containing a line shape; no native Ink binary payload is needed for this example.

```json
{
  "format": "dotnote",
  "version": 1,
  "id": "7fdb08de-5b12-4d24-b849-000000000003",
  "title": "Example",
  "modified": 1790640000000,
  "document": {
    "version": 1,
    "dots": true,
    "camera": [40.0, 40.0, 1.0],
    "items": [
      {
        "id": "7fdb08de-5b12-4d24-b849-000000000004",
        "kind": "LINE",
        "color": -14339026,
        "width": 3.0,
        "points": [[0.0, 0.0], [100.0, 50.0]],
        "transform": [1.0, 1.0, 0.0, 0.0],
        "rows": 3,
        "cols": 3,
        "page": 0
      }
    ]
  }
}
```

`modified` is milliseconds since Unix epoch. The outer note version, inner document version, vault version and writing-settings version are separately checked and all currently equal 1. They are independent of the application's release version and Room's schema version.

### Document and item fields

| Field | Meaning / serialization |
| --- | --- |
| Document `version` | Must be 1 |
| `dots` | Required boolean; grid visibility, saved per note |
| `camera` | Required `[x, y, zoom]`; x/y are screen-space translation in density-independent units |
| `items` | Ordered array; layer rules can override ordinary chronological paint order |
| Item `id` | String, unique within a document; generated UUID in normal use |
| `kind` | `TEXT`, `PEN`, `HIGHLIGHTER`, `LINE`, `ARROW`, `RECTANGLE`, `SQUARE`, `ELLIPSE`, `CIRCLE`, `GRID`, or `PDF` in normal saved scenes |
| `color` | Signed Kotlin 32-bit ARGB integer; ordinary chosen colors are opaque |
| `width` | Brush/shape width in local world units; highlighter records the already multiplied width |
| `points` | Local-space `[x,y]` coordinates; freehand samples or two shape/PDF corners |
| `ink` | Optional Base64-encoded AndroidX Ink `StrokeInputBatch`, including timing and pressure; not a PNG, SVG or full serialized mesh |
| `transform` | `[sx, sy, tx, ty]`; positive scale plus translation, no rotation/shear |
| `rows`, `cols` | Grid subdivisions; serialized on every item, ignored by other shapes |
| `asset` | Optional PDF filename relative to vault `attachments/`; never an absolute path/URI |
| `page` | Zero-based PDF page index; serialized as 0 on ordinary ink/shapes |
| `text` | Required nonblank Unicode string for TEXT; at most 10,000 characters |
| `fontSize` | TEXT size in world units, 8–144; defaults to 24 when omitted |
| `image` | Optional boolean (default false), emitted only for imported images; requires PDF kind and an attachment. Enables image selection without unlocking normal PDF pages. |

The encoder emits all scalar/list fields and omits `ink` and `asset` when absent. **Omit absent optional strings instead of writing JSON `null`**: decoding checks key presence then reads a string. Do not rely on explicit-null coercion.

For pen strokes, the points support bounds/hit tests while `ink` reconstructs pressure-sensitive rendering. `strokeItem` stores opaque color even for highlights; marker transparency is applied by the renderer. An edited color rebuilds the native brush rather than rewriting input bytes. Transforms leave both the input batch and original points intact.

PDF items use `kind: "PDF"`, an `asset`, a `page`, and two bounding corners. Ordinary PDFs and PowerPoint slides are locked (`Item.locked`). Imported images additionally store `image: true`; Select hits their rectangular interior and can move, resize and delete them. All PDF-backed items stay on the attachment layer underneath ink. Eraser and recoloring exclude images, so erasing annotations cannot delete an image. Original PDF bytes are shared by all pages that reference the same asset.

This is an additive version-1 field with default false. Older files remain readable, and older builds display images as locked PDF pages (and may drop the new flag when saving). The 0.8.0 converter did not retain image provenance, so old images cannot safely be distinguished from genuine single-page PDFs. Reimport them to enable selection; do not guess from page count or dimensions.

## Text and templates (0.9.0)

TEXT items use two local points defining the measured top-left/bottom-right rectangle, plus `text` and `fontSize`. Newlines and Unicode are preserved. Transform scaling changes visual size without rewriting text. Version-1 legacy documents remain readable; optional text size defaults to 24. Older app versions reject the new TEXT kind, so notes containing typed text require 0.9.0 or later.

The outer `.dotnote` object adds `template: true/false`, defaulting to false for old files. It is Note metadata rather than a drawing object or document setting. Vault snapshots, Git backups and legacy ZIP manifests preserve it. Templates are ordinary editable notes. A new instance copies document items, camera and dots, regenerates all item IDs and the note ID, and defaults to `template: false`; immutable attachment references stay shared in the same vault. Room schema 2 adds an `isTemplate INTEGER NOT NULL DEFAULT 0` column through the registered 1→2 migration, preserving existing indexed and legacy notes.

## Coordinate system and geometry

For local point `(u,v)`:

```text
worldX = sx * u + tx
worldY = sy * v + ty
screenDpX = camera.x + camera.zoom * worldX
screenDpY = camera.y + camera.zoom * worldY
screenPixels = screenDp * displayDensity
```

Pointer input reverses density and camera transforms. `Camera.zoomAt` preserves the world point under the chosen screen focus and clamps zoom to `0.08..8`. Default camera is `(40,40,1)`. Canvas coordinates are `Float`; “infinite” means no page boundary, not infinite numerical precision.

Bounds use transformed point bounds plus a width-based outset for ordinary ink/shapes; PDFs receive no width outset. Bounds are conservative geometry for culling and selection, not exact native pressure outlines. Resize composes scale/translation around a world-space anchor. Item IDs and encoded points stay unchanged.

## Portable writing settings

`.dotnote/settings.json`:

```json
{
  "version": 1,
  "palette": [-14339026, -13473875, -3777976, -7707221, -2578648, -13005974],
  "color": -14339026,
  "width": 3.0,
  "textSize": 24.0,
  "rows": 3,
  "cols": 3,
  "finger": false,
  "dock": "Top"
}
```

Six defaults in hex are `#25342E`, `#3267AD`, `#C65A48`, `#8A65AB`, `#D8A728`, `#398B6A`. The chosen color need not equal a palette slot. Width is `1..12`; grid rows/columns `1..30`; dock is `Top`, `Left`, or `Right`. Optional `textSize` records the last text size (default 24, range 8–144). Dot visibility belongs to each document; active tool, selection and history are not persisted here.

The reader verifies version, six integer colors, finite width in range, and the other required field types. `AppState.loadWriting` additionally forces palette alpha opaque, clamps grid values and defaults unknown dock values to `Top`. A vault without settings inherits the current in-memory/legacy defaults until its own file is written; it does not necessarily start with factory defaults.

## Validation boundaries

| Layer | Implemented checks |
| --- | --- |
| `validId` | Local vault/note/folder IDs match `[a-zA-Z0-9-]{1,80}` |
| `safeRelative` | Nonblank, at most 1,000 characters, no leading `/`, backslash, empty/`.`/`..` path segment or character below U+0020 |
| `DocumentCodec.decode` | Version 1; recognized kind; finite coordinates/transforms; positive scale; width `0.1..100`; nonnegative page; unique item IDs; asset regex `[a-f0-9-]+\.pdf` |
| Document normalization | Camera zoom clamped to `0.08..8`; grid rows/columns clamped to `1..30` |
| Vault scanner | Canonical containment, no linked folders/files in scanned content, valid unique note/folder IDs, valid document JSON, existing referenced attachment, bounded metadata/note sizes |

The codec recognizes every `Tool` enum name, including `ERASER` and `LASSO`, plus the legacy `HAND` name, although normal UI never saves those as drawable items. Item IDs are checked for uniqueness, not with `validId`. The codec does not validate the native `ink` payload, impose an item/sample count limit, require two shape points, check PDF page count, or require an asset specifically for every PDF item. Native decoding/PDF rendering can fail later. Do not describe the format reader as complete validation of arbitrary malicious input.

Unknown extra JSON fields are generally ignored and are not preserved by re-encoding. Future required formats need an explicit version/migration plan. Changing Ink storage/brush versions also requires old-stroke rendering checks even if the JSON version remains unchanged.

## Local-only state

| Location | Contents |
| --- | --- |
| `databases/vault-<local-id>.db` | Room folders/notes index; rebuildable |
| `shared_prefs/vaults.xml` | `selected`, `legacyTarget`, and JSON-string `config_<local-id>` entries for Git backup state |
| `shared_prefs/writing.xml` | Legacy/fallback `color`, `palette_0..5`, `width`, `rows`, `cols`, `finger`, `dock` |
| `shared_prefs/recent-notes.xml` | `items`: JSON array of `{vault,note,title,folder}`, newest first, up to 100 |
| `shared_prefs/github-setup.xml` | Optional `clientId` override |
| `shared_prefs/github-credentials.xml` | `encrypted`: JSON containing Base64 `iv` and `data`; decrypted credentials never belong in a vault |
| Android Keystore | AES key alias `dotnote-github` |
| WorkManager database | Pending background work, constraints and retries; managed by AndroidX |
| `cache/` | Temporary upload/export/restore staging; not authoritative |

Repository state and credentials are not part of portable settings. See the [GitHub guide](vaults-and-github-backups.md) for every backup configuration field.
