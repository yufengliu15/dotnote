package dev.dotnote.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Write files before updating the disposable Room index. Reopen repairs a stale index. */
class FileLibraryDao(private val store: Store, private val index: LibraryDao) : LibraryDao {
    override fun folders() = index.folders()

    override fun notes() = index.notes()

    override suspend fun allFolders(): List<Folder> {
        store.ready.await()
        return index.allFolders()
    }

    override suspend fun allNotes(): List<Note> {
        store.ready.await()
        return index.allNotes()
    }

    override suspend fun note(id: String): Note? {
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
        if (index.note(note.id) == note) false
        else {
            store.files.writeNote(note)
            index.put(note)
            true
        }
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
            index.put(updated)
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

    override suspend fun save(id: String, document: String, modified: Long) =
        updateNote(id) {
            if (it.document == document) it else it.copy(document = document, modified = modified)
        }

    override suspend fun clearNotes(): Unit =
        error("Clear is only supported on the disposable index")

    override suspend fun clearFolders(): Unit =
        error("Clear is only supported on the disposable index")
}
