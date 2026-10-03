package dev.dotnote.app

import android.app.Application
import android.graphics.pdf.PdfDocument
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultLifecycleTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun lastEditReplacesScheduledWorkAndKeepsNetworkPreference() {
        val catalog = VaultCatalog(context)
        val vault = catalog.create("Scheduling test")
        val id = vault.localId
        val manager = WorkManager.getInstance(context)
        fun pending(): WorkInfo {
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline) {
                val values =
                    manager.getWorkInfosForUniqueWork("vault-backup-$id").get(5, TimeUnit.SECONDS)
                values
                    .firstOrNull { it.state == WorkInfo.State.ENQUEUED }
                    ?.let {
                        return it
                    }
                Thread.sleep(20)
            }
            error("Backup was not scheduled")
        }
        try {
            catalog.update(id) {
                it.put("repo", "test/vault")
                    .put("minutes", 15)
                    .put("automatic", true)
                    .put("unmetered", true)
                    .put("editedAt", System.currentTimeMillis() - 300000)
            }
            BackupScheduler.schedule(context, id)
            val first = pending()
            assertTrue(first.initialDelayMillis in 590000..600000)
            catalog.edited(id)
            val deadline = System.currentTimeMillis() + 5000
            var next = pending()
            while (next.id == first.id && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                next = pending()
            }
            assertNotEquals(first.id, next.id)
            assertTrue(next.initialDelayMillis in 890000..900000)
            assertEquals(NetworkType.UNMETERED, next.constraints.requiredNetworkType)
            catalog.update(id) { it.put("minutes", 360) }
            catalog.edited(id)
            var last = pending()
            while (last.id == next.id && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                last = pending()
            }
            assertTrue(last.initialDelayMillis in 21590000..21600000)
        } finally {
            manager.cancelUniqueWork("vault-backup-$id").result.get(5, TimeUnit.SECONDS)
            catalog.root(id).deleteRecursively()
        }
    }

    @Test
    fun migrationCopiesLegacyNotesAndPdfsWithoutDeletingOriginals() = runBlocking {
        val catalog = VaultCatalog(context)
        val prefs = context.getSharedPreferences("vaults", 0)
        val oldTarget = prefs.getString("legacyTarget", null)
        val vault = catalog.create("Migration test")
        val legacy =
            Room.databaseBuilder(context, LibraryDatabase::class.java, "dotnote.db")
                .addMigrations(LibraryDatabase.MIGRATION_1_2)
                .build()
        val folder = Folder(name = "Legacy lectures")
        val pdf = File(context.filesDir, "pdfs/${newId()}.pdf").apply { parentFile!!.mkdirs() }
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(200, 200, 1).create())
            doc.finishPage(page)
            pdf.outputStream().use(doc::writeTo)
        } finally {
            doc.close()
        }
        val note =
            Note(
                folderId = folder.id,
                title = "Legacy PDF",
                document =
                    DocumentCodec.encode(
                        Document(
                            listOf(
                                Item(
                                    kind = "PDF",
                                    asset = pdf.name,
                                    points = listOf(Pt(0f, 0f), Pt(200f, 200f)),
                                )
                            )
                        )
                    ),
            )
        var store: Store? = null
        try {
            legacy.dao().put(folder)
            legacy.dao().put(note)
            prefs.edit().putString("legacyTarget", vault.localId).commit()
            store = Store(context, requestedVault = vault.localId)
            store.ready.await()
            assertEquals(note.document, store.dao.note(note.id)!!.document)
            assertEquals(digest(pdf), digest(File(store.assets, pdf.name)))
            assertNotNull(legacy.dao().note(note.id))
            assertTrue(pdf.isFile)
            assertTrue(File(store.root, ".dotnote/migrated").isFile)
        } finally {
            store?.db?.close()
            legacy.dao().deleteNote(note.id)
            legacy.dao().deleteFolder(folder.id)
            legacy.close()
            pdf.delete()
            prefs.edit().putString("legacyTarget", oldTarget).commit()
            catalog.root(vault.localId).deleteRecursively()
            context.deleteDatabase("vault-${vault.localId}.db")
        }
    }

    @Test
    fun vaultSwitchFlushesNotesAndKeepsSeparatePalettes() = runBlocking {
        val catalog = VaultCatalog(context)
        val original = catalog.selected()
        val a = catalog.create("Vault A")
        val b = catalog.create("Vault B")
        val models = ViewModelStore()
        lateinit var state: AppState
        try {
            catalog.select(a.localId)
            withContext(Dispatchers.Main) {
                state = AppState(context.applicationContext as Application)
                models.put("test", state)
            }
            withTimeout(10000) { while (withContext(Dispatchers.Main) { state.busy }) delay(20) }
            withContext(Dispatchers.Main) {
                state.updatePalette(0, 0xff123456.toInt())
                state.saveWriting()
                state.createNote("Only in A")
            }
            withTimeout(10000) {
                while (withContext(Dispatchers.Main) { state.note == null || state.busy }) delay(20)
            }
            withContext(Dispatchers.Main) {
                state.switchVaultNow(b.localId)
                assertTrue(state.store.dao.allNotes().isEmpty())
                state.updatePalette(0, 0xffabcdef.toInt())
                state.saveWriting()
                state.switchVaultNow(a.localId)
                assertEquals(0xff123456.toInt(), state.palette[0])
                assertEquals("Only in A", state.store.dao.allNotes().single().title)
                state.switchVaultNow(b.localId)
                assertEquals(0xffabcdef.toInt(), state.palette[0])
            }
        } finally {
            withContext(Dispatchers.Main) { models.clear() }
            catalog.select(original)
            listOf(a, b).forEach {
                BackupScheduler.cancel(context, it.localId)
                catalog.root(it.localId).deleteRecursively()
            }
        }
    }
}
