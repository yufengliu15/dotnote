package dev.dotnote.app

import android.database.sqlite.SQLiteBlobTooBigException
import android.net.Uri
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LargeNoteStorageTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun oversizedExistingNoteSavesReopensMovesAndBacksUp(): Unit = runBlocking {
        val databaseName = "large-note-${newId()}.db"
        var store = Store(context, databaseName)
        val stage = File(context.cacheDir, "large-snapshot-${newId()}")
        val backup = File(context.cacheDir, "large-backup-${newId()}.zip")
        val content = "漢字🙂".repeat(1400)
        val document =
            Document(
                List(400) { index ->
                    Item(
                        kind = "TEXT",
                        text = content,
                        points = listOf(Pt(0f, index * 100f), Pt(200f, index * 100f + 80f)),
                    )
                }
            )
        val note = Note(title = "Large existing note", document = DocumentCodec.encode(document))
        val small = Note(title = "Keep this note")
        try {
            assertTrue(note.document.toByteArray().size > 4 * 1024 * 1024)
            store.dao.put(note)
            store.dao.put(small)
            // Reproduce the reported platform failure with the old full-row query.
            assertThrows(SQLiteBlobTooBigException::class.java) {
                store.db
                    .query(SimpleSQLiteQuery("SELECT * FROM notes WHERE id = ?", arrayOf(note.id)))
                    .use {
                        it.moveToFirst()
                        it.getString(it.getColumnIndexOrThrow("document"))
                    }
            }
            assertEquals(2, store.dao.notes().first().size)
            assertEquals(note, store.dao.note(note.id))
            assertEquals(setOf(note, small), store.dao.allNotes().toSet())
            assertNull(store.dao.note("missing"))

            val edited =
                DocumentCodec.encode(document.copy(camera = Camera(20f, 30f, 2f), dots = false))
            store.dao.save(note.id, edited, 123456L)
            store.dao.save(note.id, edited, 999999L)
            assertEquals(123456L, store.dao.note(note.id)!!.modified)
            val folder = Folder(name = "Before rename")
            store.dao.put(folder)
            store.dao.renameNote(note.id, "Large renamed note")
            store.dao.moveNote(note.id, folder.id)
            store.dao.put(folder.copy(name = "After rename"))
            val saved = store.dao.note(note.id)!!
            assertEquals(edited, saved.document)
            assertEquals(folder.id, saved.folderId)
            assertEquals("Large renamed note", saved.title)
            assertEquals(small, store.dao.note(small.id))

            store.snapshot(stage)
            assertEquals(
                edited,
                VaultFiles(stage).read().second.single { it.id == note.id }.document,
            )
            store.backup(Uri.fromFile(backup))
            assertEquals(2, store.restore(Uri.fromFile(backup)))
            assertEquals(2, store.dao.allNotes().count { it.document == edited })
            assertEquals(small, store.dao.note(small.id))
            store.db.close()
            store = Store(context, databaseName)
            store.ready.await()
            assertEquals(saved, store.dao.note(note.id))
            assertEquals(400, DocumentCodec.decode(store.dao.note(note.id)!!.document).items.size)
            store.dao.deleteNote(note.id)
            assertNull(store.dao.note(note.id))
            assertTrue(File(store.root, ".dotnote/trash/${note.id}.dotnote").isFile)
            assertEquals(small, store.dao.note(small.id))
        } finally {
            store.db.close()
            context.deleteDatabase(databaseName)
            store.root.deleteRecursively()
            stage.deleteRecursively()
            backup.delete()
        }
    }

    @Test
    fun chunksKeepUnicodeAtBoundariesAndHandleExactLengths(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val documents =
                listOf("", "a".repeat(65536), "a".repeat(65535) + "🙂漢字" + "b".repeat(65536))
            val notes =
                documents.mapIndexed { index, document ->
                    Note(title = "Boundary $index", document = document)
                }
            db.dao().putNotes(notes)
            notes.forEach { assertEquals(it, db.dao().note(it.id)) }
            assertEquals(notes.toSet(), db.dao().allNotes().toSet())
            assertNull(db.dao().note("missing"))
        } finally {
            db.close()
        }
    }
}
