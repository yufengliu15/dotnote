# Maintenance, diagnostics, and known issues

[Documentation index](README.md)

## Before changing the app

Read the relevant guides and actual implementation. Preserve uncommitted user changes. Identify whether the change affects canonical files, transient editor state, local preferences, derived Room data, native renderer state, or remote repository state; those layers have different failure/recovery behavior.

Keep scope anchored to the requested feature. The app's intended baseline is Kotlin, offline local editing, Lenovo stylus support, file-based vaults, and optional backup. Do not introduce account requirements, web-canvas replacements, automatic two-way sync, or destructive migration as incidental refactoring.

## Route a task to the right files

| Task | Read/edit first | Additional boundary to inspect |
| --- | --- | --- |
| Add a drawing tool/item kind | `Document.kt`, `NotebookView.kt`, `Rendering.kt`, `MainActivity.kt` | Codec version/backward compatibility; PDF output; hit testing/history |
| Adjust first-stroke/pressure behavior | `NotebookView.kt`, `Rendering.kt` | Native handoff, real vs predicted inputs, startup test and physical hardware |
| Change marker opacity | `ObjectRenderer.drawScene` in `Rendering.kt` | Live preview and exported PDF use same layer; color grouping semantics |
| Change selection/eraser | `NotebookView.kt`, geometry in `Document.kt` | PDF lock, cancellation, one gesture/one undo, transformed points |
| Add portable note metadata | `Document.kt` or `VaultFiles.kt` envelope | Old readers, re-encoding loss of unknown fields, migration/test fixtures |
| Change folders/note moves | `FileLibraryDao.kt`, `VaultFiles.kt`, `Store.kt` | File journal, cycle checks, index rebuild, widget labels |
| Add per-vault settings | `AppState.kt`, `VaultFiles.read`, settings UI | Captured store in debounce, defaults, snapshot inclusion |
| Change vault identities/rename | `VaultCatalog.kt`, `VaultUi.kt` | Local vs portable IDs, widget intents, repo binding, migration target |
| Add import/export support | `VaultTransfer.kt`, `VaultDocumentsProvider.kt`, `Store.kt` | SAF provider errors, staging, size/path limits, unchanged source |
| Modify Git transport/auth | `GitHub.kt`, `VaultUi.kt` | No secrets in logs/files, token permissions, DNS vs auth failures |
| Modify Git backup | `GitBackup.kt`, `VaultCatalog.kt` | Root/upload locks, expected head, pending commit, no-force update, managed paths |
| Change widgets/recent ordering | `NoteWidgets.kt`, `RecentNotes.kt`, widget resources | PendingIntent identity, no edits from viewing, asynchronous metadata races |
| Change creation/navigation | `NewNoteDialog.kt`, `AppState.kt`, `MainActivity.kt` | Flush failure, cross-vault destinations, saved instance recreation |

Full clickable source map is in [architecture](architecture.md).

## Change invariants checklist

- A normal storage mutation writes authoritative files before Room. Raw Room clearing is reserved for index rebuild; `FileLibraryDao.clearNotes/clearFolders` intentionally error.
- Root mutex covers file/index mutation and snapshot capture. Upload mutex coordinates bind/unbind/upload. Do not invert lock order or call a nonreentrant root-locking operation while already holding that mutex.
- Saves retain the original store/note destination and order. A stale completion must not mark a newer document saved.
- Preview does not write a partial eraser/transform gesture. Canceled gestures restore their original scene. Pen-up commits before asynchronous native handoff.
- Durable native samples exclude predicted input. Density, camera and item transforms must agree between live, reopened and exported strokes.
- Same-color highlight overlap stays at one layer opacity. Do not regress to per-stroke alpha accumulation.
- PDFs are copied locally and references remain valid through import, move, backup and restore. Rejected backup files are not silently skipped.
- Local/portable vault identities remain distinct; display-name changes do not change identity. Widget targets include both local vault and note IDs.
- Viewing recency is local metadata, not a note modification. Rename/move should update labels without changing opening order.
- A remote unexpected head stops upload. Keep pending ref acknowledgement recovery; do not bypass it by resetting base or force-pushing.
- Credentials are excluded from every export and from Android backup. Public OAuth client ID is safe in source; access tokens and signing keys are not.

## Confirmed unresolved work

These are recorded in [TODO.md](../TODO.md), not newly claimed fixes:

1. **Lenovo GitHub DNS failure.** The user still sees `Unable to resolve host "github.com": No address associated with hostname`. Required network permissions are already present; previous suggested checks did not resolve it. The user asked to defer it. Resume only as a scoped investigation; do not call live private-repository backup verified.
2. **Physical first-stroke verification.** Renderer/brush pre-initialization and IO document decoding are implemented, with emulator timing tests passing. Physical Lenovo digitizer latency still needs testing. Emulator screenshot timing is not an end-to-end hardware latency measurement.

## Current functional limits

- No typed text, handwriting recognition, collaboration, continuous remote synchronization, tags, drawing thumbnails, PDF text selection/search, vault deletion UI, trash UI, Git commit history picker or Git LFS.
- Native canvas accessibility is limited; it does not expose every stroke as an accessible semantic element.
- Imported PDFs are locked scene items and raster previews have bounded resolution. Export rasterizes imported PDF pages, omits the dot grid and can tile through page boundaries.
- Folder search is by names/titles in one vault; an empty-query root view is not recursive “all notes.”
- Whole-item erasing and sample-point lasso selection are approximations, not vector Boolean editing. Selection resize can distort constrained shapes.
- Exported folders are snapshots, imported folders become copies, and the Files provider is read-only. There is no supported external live writer or filesystem watcher.
- Background backup timing is best effort after inactivity. Continuous edits reset the timer. Manual backup still depends on Android/network constraints.

## Implementation caveats to account for

These are visible design gaps or risks inferred from code; they are not all reproduced user bugs.

| Area | Current limitation / implication |
| --- | --- |
| Process lifetime | `ON_STOP` queues saves; Android can terminate before completion. “Saved” matters. Unfinished gestures are not crash-recoverable input logs. |
| Memory/resources | Unbounded save/open queues, retained Store/database instances, full document JSON encoding and whole-library folder rewrites can grow expensive. There is no complete spatial index or very-large-vault performance certification. |
| Size enforcement | Import/read boundaries have limits that are not proactively applied to every local editing operation. Large local notes may later exceed scanner limits. |
| Validation | Document JSON checks do not fully validate native input payloads, shape point counts or PDF page ranges. Some faults surface during rendering. |
| Atomicity | Journaling makes file operations replayable; file/index/preferences/remote operations are not one global transaction. A reported error can follow a partially successful earlier layer. |
| File exposure | Direct copying through the Files provider is not a coherent snapshot during edits. SAF export failure may leave partial destination output. |
| Multi-process/external writes | Locks are process-local; external editing while the app is open can leave stale path/index state. No automatic reload watcher exists. |
| Recovery retention | Trash and old/orphan PDFs are retained without UI cleanup or retention bounds. They are excluded from managed backups. |
| Git bootstrap | Pending commit reconciliation covers final ref updates, not every response-loss case during initial README repository setup. |
| Auth disconnect | Clearing account credentials cancels existing work but leaves bindings; later edits/startup can schedule work that needs sign-in again. |
| Format evolution | Unknown fields are dropped on re-encode; a new tool/metadata version needs intentional compatibility handling. Native stroke cache assumes immutable input bytes for an item ID. |
| Device/OEM differences | Only documented emulator/provider/launcher cases are verified. Android 17, physical stylus/palm behavior and arbitrary external providers require acceptance. |

Do not silently “fix” these as part of an unrelated request. Use them to choose meaningful regression tests and to describe actual limitations.

## Diagnostic starting points

| Symptom | First checks |
| --- | --- |
| Save remains Unsaved | Snackbar error, storage availability, queued save destination/sequence, `FileLibraryDao` failure, journal existence; preserve vault before repair |
| Note appears in files but not library | Store readiness/index rebuild and active vault/folder/search filters; avoid inserting only into Room |
| Duplicate or missing content after a move | Journal replay, full IDs in generated paths, parent graph and canonical containment; inspect a copied vault |
| PDF says Page unavailable | Referenced file existence, page index, actual renderer error, failed-key cache tied to current view |
| Stroke under wrong location | Pixel→dp→world input conversion, camera matrix, item transform, zoomed reopen/export tests |
| First stroke slow | Separate document-load time, view layout/attachment, eager native setup, main-thread work and actual digitizer delay |
| Widget opens wrong note | PendingIntent action/data identity, not just extras; local vault/note IDs; stale recency records |
| Widget label stale | Recency metadata refresh queue, vault-name lookup, swallowed update failure, launcher options/update event |
| Backup never starts | Binding, automatic flag, revision vs backedRevision, editedAt deadline resets, WorkManager state, network constraint, OS restrictions |
| Backup stops with newer remote | Expected `base` vs actual head, pending commit reconciliation; restore another vault instead of forcing |
| GitHub cannot resolve host | DNS/network failure precedes OAuth/token permissions; collect device/network diagnostics when that work resumes |
| Credential read failure | Keystore/prefs mismatch or decryption failure; reconnect rather than copying encrypted prefs between devices |

Useful non-destructive developer checks on a test device include `adb logcat`, WorkManager inspection and `adb shell run-as dev.dotnote.app` where debug access is available. Do not print credentials, user drawings or whole preferences into logs. Debug screenshots/timing should be labeled with device/build/setup and measured interval.

## Keeping this documentation current

For a source change, update the matching guide and source-map entry; change examples/schema text when formats change. Update test inventory when methods are added/removed. Keep [VALIDATION.md](../VALIDATION.md) as the release evidence record and distinguish new verification from previous results. Preserve the two original guide URLs (`vaults-and-github-backups.md`, `widgets-and-ink-startup.md`) so existing root README links remain usable.

Validate relative Markdown links and JSON code blocks, run `git diff --check`, and compare claims against actual behavior. Documentation should describe where guarantees stop, not imply that every path has physical-device or live-account coverage.
