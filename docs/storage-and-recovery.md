# Storage, transfer, and recovery

[Documentation index](README.md) · Related: [format](data-model-and-format.md), [GitHub backup](vaults-and-github-backups.md)

## Authority and normal writes

The vault directory is the source of truth. [`Store`](../app/src/main/java/dev/dotnote/app/Store.kt) maintains a Room index for reactive lists and document queries. Its `dao` is a [`FileLibraryDao`](../app/src/main/java/dev/dotnote/app/FileLibraryDao.kt) wrapper, not the raw database DAO.

Room schema 2 (with non-destructive 1→2 migration) contains `folders(id PRIMARY KEY, parentId?, name)` and `notes(id PRIMARY KEY, folderId?, title, modified, document, isTemplate DEFAULT 0)`, with an index on `notes.folderId`. There are no SQL foreign-key constraints; application validation protects folder relationships. Folder flow sorts names case-insensitively. Note-summary flow selects metadata only and sorts by modification time descending.

Since 0.10.0 the index stores **metadata only**: the `document` column of every row is empty (no schema change). `FileLibraryDao.note(id)` reads the document from the note's file under the vault lock. Previously the index held every document, and Android cannot read a row larger than its 2 MB `CursorWindow`: a note of roughly 2,000 handwritten strokes could be saved once but then neither re-saved nor reopened. `allNotes()` returns metadata with an empty `document`; a note passed back to a write with an empty document keeps its stored content. Existing indexes are rebuilt this way on the first start.

`RoomLibraryDao.note()` and `allNotes()` read small metadata projections, then reconstruct document JSON with 65,536-code-point SQLite `substr` queries inside `@Transaction`. No cursor row contains an entire large document. SQLite `length` and `substr` share code-point offsets, preserving supplementary Unicode characters at chunk boundaries. This also covers the old database during migration, backups and file mutations that re-read notes. The schema and portable file format remain unchanged; a complete opened document still occupies memory proportional to its content.

```mermaid
sequenceDiagram
    participant UI as AppState
    participant DAO as FileLibraryDao
    participant F as VaultFiles
    participant DB as Room index
    participant C as VaultCatalog
    UI->>DAO: saveDocument(latest scene of this note)
    DAO->>DAO: encode (reusing unchanged items' JSON)
    DAO->>DAO: await ready; acquire root mutex
    DAO->>F: replace the note file atomically (journal only if the path changes)
    DAO->>DB: update modified time
    DAO->>C: increment backup revision and schedule
    DAO-->>UI: success / failure
```

The editor's save queue writes only the newest scene of each note: saves queued while a large note was being written collapse into one write. A scene that is the same immutable `Document` instance as the last one written is not written again, so identical saves keep `modified` unchanged and do not schedule a backup. `DocumentCodec.encode` writes exactly the bytes Android's org.json produced (`CodecCompatibilityTest`) and caches each immutable item's JSON on the item, so a save after one stroke formats only that stroke. Editor saves stream the cached item text straight into the atomic file (`VaultFiles.writeScene`) instead of building the whole document string. Documents the editor encoded are written without re-parsing; other writes (`put`, `save`, `replace`) still validate the document. `updateNote` re-reads the current metadata inside the lock before applying a title/folder change. This prevents an older save request from restoring an old title or folder. Rename/move updates `modified`; renaming/moving a folder rewrites paths without individually changing every note's timestamp.

A single in-place note write is already crash-safe through AtomicFile and skips the journal and folder walk (pending journals are recovered first). New paths, renames/moves and deletes retain the journal.

The file operation precedes the index mutation. They are not a single distributed transaction. If a crash occurs between them, reopening/reloading rebuilds the index from files. If a write succeeds but updating backup configuration fails, the UI can report failure even though the file exists; inspect all layers before assuming the document is absent.

## File transaction journal

[`VaultFiles`](../app/src/main/java/dev/dotnote/app/VaultFiles.kt) uses `AtomicFile` for text writes and `.dotnote/transaction.json` for multi-file operations:

```json
{
  "writes": {
    "Example [note-1].dotnote": "serialized note JSON goes here"
  },
  "deletes": ["Old name [note-1].dotnote"]
}
```

This illustrates the journal structure; each write value is a string containing a complete file body, not a nested note object.

1. Construct all writes and deletes. A path being written is removed from the delete list.
A single in-place note replacement is already atomic through `AtomicFile` and skips the journal. Operations that write one path and delete another (rename, move, delete, folder changes) use it:

2. Atomically persist the journal.
3. Atomically write each target, then delete old files.
4. If anything was deleted, prune empty directories, preserving the root and directories named `attachments` / `.dotnote`.
5. Delete the journal with `AtomicFile.delete()`.

`read()` and `snapshot()` recover an existing journal first. Recovery replays the same operations and only removes the journal after success. It also recognizes the journal's AtomicFile backup. Paths are checked for safe relative syntax and canonical containment before application. This completes an interrupted operation; it is not a rollback to the old library or an undo history.

A folder rename/move calls `replace`: validate parent relationships/cycles, calculate every folder path, write folder markers, rewrite notes whose path or metadata changed, delete obsolete managed paths. Notes outside the moved subtree are not rewritten. In-memory path maps are maintained by `VaultFiles`; use an initialized instance and the root lock. After a failed transaction, reopen/reload and replay rather than continuing arbitrary external edits against stale maps.

## Deletion and retained data

Deleting a note writes its current serialized note into `.dotnote/trash/<note-id>.dotnote` and deletes the active file in the same journal operation. The index and recent-note entry are then removed. There is no trash browser, retention policy, or restore button. Recovery copies do not retain the original parent path because membership is directory-derived.

Folder deletion is allowed only when it has neither child folders nor notes. A move cannot create a cycle or target an unknown parent. Duplicate display names are supported through full IDs in paths.

Attachments are not garbage-collected after deletion. Local trash and unreferenced PDFs are excluded from managed snapshots. Git history can retain prior files after a successful remote commit, but the app has no historical-commit restore picker.

## Initialization and legacy migration

For production, each vault index is named `vault-<local-id>.db`. `Store.ready` runs initialization on IO under the root mutex and rebuilds Room (metadata only) using batch upserts in one `withTransaction` after reading the files. The scanner validates each note with a streaming parser, without building an object tree or renderable items. All format, coordinate, ID, attachment and file-containment checks still apply. Debug builds log note counts and scan/index timing under `DotnoteStartup`, without note names/content or credentials. `Store.reload()` explicitly repeats the file scan/index rebuild. Merely having a newer database is never permission to overwrite canonical note files.

**Scan cache.** A validated note's metadata (ID, title, modified time, template flag, attachments) is remembered in app-private `no_backup/vault-scan/<local-id>.json`, outside the vault, keyed by the file's inode, size and nanosecond modification time. A note whose stamp is unchanged is not read again on startup or before a backup; any rewrite (including atomic replacement, external copies and restores) produces a new stamp and a full validation. Attachments named by cached notes are still checked for existence and containment on every scan, and the directory walk, folder markers, settings and manifest are always read. The cache also remembers Git blob SHA-1s by the same stamp. Losing or corrupting it only costs a full scan; it can never supply metadata for different content. `GitBackup` uses the same cache under the same root lock.

The first default vault may be marked `legacyTarget` in catalog preferences. If it lacks `.dotnote/migrated`, initialization:

1. Opens the original `dotnote.db` with schema 1 and reads folders/notes transactionally.
2. Copies each referenced original PDF from `files/pdfs/` into the vault's attachments directory.
3. Writes the portable folder/note layout and validates it by reading it back.
4. Atomically writes the migration marker containing `1`.
5. Closes the old database but leaves it and the original PDF files intact.

Missing source PDFs fail migration rather than silently dropping them. A missing marker permits replay after interruption. Do not delete the legacy marker on a populated migrated vault: the migration path can reapply the old library. Old copies are for recovery, not an ongoing second source of truth.

Test Stores constructed with a custom database name use a derived `test-...` local vault and skip production dirty scheduling. Tests should not depend on production SharedPreferences unless they intentionally exercise lifecycle behavior.

## Transfer choices

| Mechanism | What it contains | Restore behavior |
| --- | --- | --- |
| Vault folder export | Manifest, optional writing settings, folder markers, notes, referenced PDFs | Folder import validates/stages/publishes a new local vault |
| GitHub snapshot | Same managed portable content, plus existing allowed repository root documents | Explicit restore downloads a new local vault and establishes a local repository binding |
| Legacy ZIP | `library.json` and `pdfs/<asset>.pdf`; no per-vault writing settings or Git binding | Merge into the current vault with fresh folder/note/asset IDs |
| Android Files provider | Read-only access to current vault files, including some files not in a managed snapshot | External copying only; use app import to adopt a copied vault |
| Android automatic backup | Disabled by manifest and data-extraction rules | Not a supported recovery path |

All exported note/PDF contents are unencrypted. Credentials never belong in these transfers. Uninstall/clear-data removes local vaults, indexes and preferences; the Keystore credential cannot be treated as portable backup data.

### Managed snapshots

Snapshots hard-link each managed file into the staging directory when the filesystem allows it and copy otherwise. Every vault write replaces files by rename, so a link keeps the snapshot's content while costing no copy. Referenced attachments come from validated metadata instead of decoding every note.

`Store.snapshot(destination)` waits for readiness and holds the root mutex while `VaultFiles.snapshot` replays any journal and links or copies known paths to a staging directory. It includes only assets referenced by active documents. Network or external-provider I/O then consumes the staged files after releasing the lock. This gives a coherent managed snapshot without blocking writing throughout an upload.

Copying files one by one directly through the documents provider has no whole-vault snapshot lock. For a consistent transfer while edits might occur, prefer the app's export action. The provider can expose unreferenced attachments, whereas managed snapshots exclude them.

### Storage Access Framework folder import/export

[`VaultTransfer`](../app/src/main/java/dev/dotnote/app/VaultTransfer.kt) uses user-selected tree URIs. It does not keep a continuously linked external working directory or retain a sync relationship.

Import recursively streams allowed files to `files/vaults/.import-<uuid>`. It rejects unsafe names/paths and unexpected files, skips local trash and selected nonportable/root housekeeping files, then calls `VaultCatalog.publish()`. Publish validates with `VaultFiles.read()` and renames staging to a fresh local ID on the same filesystem. The source remains untouched. The selected vault changes only through the subsequent AppState action.

Export first stages a managed snapshot under cache, then creates a new `<vault-name>-<epoch-millis>` folder beneath the selected destination. It creates child directories/files with MIME types for PDF, JSON, or generic `.dotnote` bytes. Staging is cleaned in `finally`. A failed provider write may leave a partial destination folder; there is no transactional rollback across an arbitrary external provider.

Import recognizes managed paths and allows/skips root `README.md`, `LICENSE`, `.gitignore`, `.DS_Store`, and the migration marker. It skips the `.dotnote/trash` subtree. A raw copy containing an active transaction journal or temporary files is not a clean portable export and can be rejected. Include hidden `.dotnote` and `.folder.json` files when transferring.

### Android Files integration

[`VaultDocumentsProvider`](../app/src/main/java/dev/dotnote/app/VaultDocumentsProvider.kt) exposes authority `dev.dotnote.app.vaults`, root title “Dotnote vaults,” and read-only file descriptors. The manifest uses `MANAGE_DOCUMENTS` with URI grants; the app does not request broad storage permission. Modes other than `r` are rejected. There is no create/edit/delete API.

Document IDs are root-relative paths; `root` denotes the vault collection. Every resolution checks relative syntax, existence and canonical containment. Top-level vault folders display their manifest names. Child listings hide `transaction.json`, `migrated`, `trash`, `.bak` and `.new` files. Listing filters are UI behavior, not a claim that hidden files cannot ever be addressed through a granted document ID.

### Legacy ZIP behavior

`Store.backup` captures folders/notes under the mutex and writes `library.json` plus referenced PDFs. JSON has `format: "dotnote"`, `version: 1`, `folders` records `{id,parent?,name}`, and `notes` records `{id,folder?,title,modified,document}`. Nullable parent/folder strings are omitted by the encoder. Attachment filenames are relative to `pdfs/`.

`Store.restore` streams into cache staging, rejects duplicate/unexpected ZIP entry names, validates the manifest/document/folder graph, and remaps every folder/note to a new UUID. Each referenced PDF is copied under a fresh UUID filename, not content-addressed/deduplicated by this legacy path. Existing content is combined with restored content through `replaceLibrary`; original note modification times are retained. On failure it removes staged files and copied assets tracked by that attempt. The normal UI serializes restore as an action; this is not a cross-process import transaction.

## Concrete limits

| Operation | Limit / nuance |
| --- | --- |
| Vault scan metadata | 64 KiB each for manifest, writing settings and folder marker |
| Vault scan notes | 64 MiB per note, 256 MiB aggregate note JSON |
| Vault scan tree | Depth must be less than 64; at most 10,000 counted entries; skipped metadata/attachment trees are not a comprehensive total-byte limit |
| Folder import | Depth less than 64; at most 10,000 encountered entries; 512 MiB per streamed file; 1 GiB total streamed bytes, followed by stricter vault validation |
| Legacy ZIP restore | At most 10,000 unique entries; 1 GiB total expanded bytes; `library.json` at most 64 MiB |
| PDF import | Strictly below 512 MiB |
| Git backup/restore | Each file strictly below 100 MiB; restore at most 1 GiB and 10,000 tree files |

These checks live at different boundaries. Local drawing does not preflight every scanner limit before each save. A sufficiently huge locally created note could save and later fail the bounded reader. Do not turn documentation limits into a false guarantee of proactive UI enforcement.

## Recovery runbook for an agent

1. Establish whether the issue is unsaved in-memory work, malformed canonical files, a pending journal, stale Room data, or a remote conflict. Capture the exact error; preserve a copy of the full local vault before repair, including hidden recovery files.
2. For a stale index or interrupted file transaction, use the ordinary reopen/`Store.reload()` path first. Keep the journal until replay succeeds. Deleting Room does not repair bad JSON or missing PDFs.
3. For missing active content, inspect local trash and retained attachments on a copy. Reintroduce a recovered note into a valid folder with a noncolliding ID/path, then validate and rebuild. This is a developer/manual recovery procedure, not an existing user UI.
4. For corrupt/missing PDFs, recover the referenced original asset from an export, retained legacy `files/pdfs`, or repository history. A document's asset filename is part of its reference; do not rename an attachment alone.
5. For Git conflicts, preserve local data and restore the remote as another vault. Do not clear `base` or force the branch to make the warning disappear.
6. For uninstallation/clear-data, only separately exported/backed-up copies are recoverable by this app. Re-authenticate on the new install and import/restore explicitly.

Use a debug device's `run-as dev.dotnote.app` only with appropriate access and care. Never log or include credential preferences in a support bundle. Production/non-debug builds may not permit `run-as`.
