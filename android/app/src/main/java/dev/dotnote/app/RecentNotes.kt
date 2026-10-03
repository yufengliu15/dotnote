package dev.dotnote.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class RecentNote(
    val vaultId: String,
    val noteId: String,
    val title: String,
    val folder: String,
)

fun folderPath(id: String?, folders: List<Folder>): String {
    val byId = folders.associateBy { it.id }
    val parts = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    var current = id
    while (current != null && seen.add(current)) {
        val folder = byId[current] ?: break
        parts.add(0, folder.name)
        current = folder.parentId
    }
    return parts.joinToString(" / ").ifEmpty { "Vault root" }
}

/** Device-local opening order; viewing a note must not change its backup revision. */
class RecentNotes(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("recent-notes", 0)

    fun list(): List<RecentNote> =
        synchronized(lock) {
            val array =
                runCatching { JSONArray(prefs.getString("items", "[]")) }.getOrDefault(JSONArray())
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val vault = o.optString("vault")
                    val note = o.optString("note")
                    if (validId(vault) && validId(note))
                        add(RecentNote(vault, note, o.optString("title"), o.optString("folder")))
                }
            }
        }

    private fun write(items: List<RecentNote>) {
        val array =
            JSONArray(
                items.take(100).map {
                    JSONObject()
                        .put("vault", it.vaultId)
                        .put("note", it.noteId)
                        .put("title", it.title)
                        .put("folder", it.folder)
                }
            )
        check(prefs.edit().putString("items", array.toString()).commit()) {
            "Could not save recent notes"
        }
        NoteWidgets.refresh(app)
    }

    fun opened(vault: String, note: Note, folders: List<Folder>) =
        synchronized(lock) {
            val recent = RecentNote(vault, note.id, note.title, folderPath(note.folderId, folders))
            write(listOf(recent) + list().filterNot { it.vaultId == vault && it.noteId == note.id })
        }

    fun changed(vault: String, note: Note, folders: List<Folder>) =
        synchronized(lock) {
            val old = list()
            val updated =
                old.map {
                    if (it.vaultId == vault && it.noteId == note.id)
                        it.copy(title = note.title, folder = folderPath(note.folderId, folders))
                    else it
                }
            if (updated != old) write(updated)
        }

    fun refreshFolders(vault: String, notes: List<Note>, folders: List<Folder>) =
        synchronized(lock) {
            val byId = notes.associateBy { it.id }
            val old = list()
            val updated =
                old.mapNotNull {
                    if (it.vaultId != vault) it
                    else
                        byId[it.noteId]?.let { note ->
                            it.copy(title = note.title, folder = folderPath(note.folderId, folders))
                        }
                }
            if (updated != old) write(updated)
        }

    fun remove(vault: String, note: String) =
        synchronized(lock) {
            val old = list()
            val updated = old.filterNot { it.vaultId == vault && it.noteId == note }
            if (updated != old) write(updated)
        }

    companion object {
        private val lock = Any()
    }
}
