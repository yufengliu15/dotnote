package dev.dotnote.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Write files before updating the disposable Room index. Reopen repairs a stale index.
 *
 * The index holds note metadata only (its document column is empty), so no row approaches
 * Android's 2 MB CursorWindow and startup/saves never copy note content into SQLite. Documents
 * are read from the authoritative files: [note] and [allNotes] return complete notes, and a note
 * passed to a write with an empty document keeps its stored content.
 */
class FileLibraryDao(private val store: Store, private val index: RoomLibraryDao) : LibraryDao {
    override fun folders() = index.folders()

    override fun notes() = index.notes()

    override suspend fun templates(): List<NoteSummary> {
        store.ready.await()
        return index.templates()
    }

    override suspend fun allFolders(): List<Folder> {
        store.ready.await()
        return index.allFolders()
    }

    override suspend fun allNotes(): List<Note> {
        store.ready.await()
        return withContext(Dispatchers.IO) {
            store.mutex.withLock {
                index.allNotes().map { it.copy(document = store.files.readDocument(it.id)) }
            }
        }
    }

    override suspend fun note(id: String): Note? {
        store.ready.await()
        return withContext(Dispatchers.IO) {
            store.mutex.withLock {
                index.note(id)?.let { it.copy(document = store.files.readDocument(id)) }
            }
        }
    }

    /** Index metadata without reading the note's content. */
    suspend fun summary(id: String): Note? {
        store.ready.await()
        return index.note(id)
    }

    override suspend fun childFolders(id: String): Int {
        store.ready.await()
        return index.childFolders(id)
    }

    override suspend fun childNotes(id: String): Int {
        store.ready.await()
        return index.childNotes(id)
    }

    private suspend fun mutate(block: suspend () -> Boolean) {
        store.ready.await()
        withContext(Dispatchers.IO) { store.mutex.withLock { if (block()) store.changed() } }
    }

    override suspend fun put(note: Note) = mutate {
        val current = index.note(note.id)
        if (
            current != null &&
                current == note.copy(document = "") &&
                (note.document.isEmpty() || note.document == store.files.readDocument(note.id))
        )
            false
        else {
            store.files.writeNote(note)
            index.put(note.copy(document = ""))
            true
        }
    }

    // Normal bulk writes still use the files-first path; index rebuilds call the raw DAO.
    override suspend fun putNotes(notes: List<Note>) {
        notes.forEach { put(it) }
    }

    override suspend fun putFolders(folders: List<Folder>) {
        folders.forEach { put(it) }
    }

    override suspend fun put(folder: Folder) = mutate {
        val folders = index.allFolders()
        if (folders.find { it.id == folder.id } == folder) false
        else {
            val all = folders.filterNot { it.id == folder.id } + folder
            store.files.replace(all, index.allNotes())
            index.put(folder)
            runCatching {
                RecentNotes(store.context).refreshFolders(store.vaultId, index.allNotes(), all)
            }
            true
        }
    }

    override suspend fun deleteNote(id: String) = mutate {
        if (index.note(id) == null) false
        else {
            store.files.deleteNote(id)
            index.deleteNote(id)
            runCatching { RecentNotes(store.context).remove(store.vaultId, id) }
            true
        }
    }

    override suspend fun deleteFolder(id: String) = mutate {
        require(index.childFolders(id) == 0 && index.childNotes(id) == 0) {
            "Move or delete the folder's contents first."
        }
        store.files.replace(index.allFolders().filterNot { it.id == id }, index.allNotes())
        index.deleteFolder(id)
        true
    }

    private suspend fun updateNote(id: String, change: (Note) -> Note) = mutate {
        val original = index.note(id) ?: return@mutate false
        val updated = change(original)
        if (updated == original) false
        else {
            store.files.writeNote(updated)
            index.put(updated.copy(document = ""))
            if (original.title != updated.title || original.folderId != updated.folderId)
                runCatching {
                    RecentNotes(store.context).changed(store.vaultId, updated, index.allFolders())
                }
            true
        }
    }

    override suspend fun renameNote(id: String, title: String) =
        updateNote(id) {
            it.copy(
                title = title.trim().ifEmpty { "Untitled" },
                modified = System.currentTimeMillis(),
            )
        }

    override suspend fun moveNote(id: String, folder: String?) =
        updateNote(id) { it.copy(folderId = folder, modified = System.currentTimeMillis()) }

    override suspend fun save(id: String, document: String, modified: Long) = mutate {
        // Unchanged content keeps its modified time and schedules no backup.
        if (index.noteSummary(id) == null || store.files.readDocument(id) == document) false
        else {
            val current = index.noteSummary(id) ?: return@mutate false
            val updated =
                Note(current.id, current.folderId, current.title, modified, document, current.isTemplate)
            store.files.writeNote(updated)
            index.put(updated.copy(document = ""))
            true
        }
    }

    /**
     * Saves an editor scene. Encoding reuses each unchanged item's JSON; the file is replaced
     * atomically in place and only the index's modified time changes.
     */
    suspend fun saveDocument(id: String, document: Document, modified: Long): Boolean {
        store.ready.await()
        return withContext(Dispatchers.IO) {
            // Format new items before taking the vault lock; the write then only streams text.
            document.items.forEach { DocumentCodec.itemText(it) }
            val assets = document.items.mapNotNullTo(LinkedHashSet()) { it.asset }
            var written = false
            store.mutex.withLock {
                val summary = index.note(id)
                if (summary != null) {
                    store.files.writeScene(summary.copy(modified = modified), document, assets)
                    index.save(id, "", modified)
                    store.changed()
                    written = true
                }
            }
            written
        }
    }

    override suspend fun clearNotes(): Unit =
        error("Clear is only supported on the disposable index")

    override suspend fun clearFolders(): Unit =
        error("Clear is only supported on the disposable index")
}
