package dev.dotnote.app

import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupLoadingTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private fun hasText(root: AccessibilityNodeInfo?, text: String): Boolean {
        root ?: return false
        if (root.text?.toString() == text) return true
        return (0 until root.childCount).any { hasText(root.getChild(it), text) }
    }

    @Test
    fun startupNamesLocalWorkAndDoesNotFlashAnEmptyLibrary(): Unit = runBlocking {
        val context = instrumentation.targetContext
        val catalog = VaultCatalog(context)
        val previous = catalog.selected()
        val vault = catalog.create("Startup UI fixture")
        catalog.select(vault.localId)
        val mutex = VaultLocks.forRoot(catalog.root(vault.localId))
        mutex.lock()
        var locked = true
        var scenario: ActivityScenario<MainActivity>? = null
        var state: AppState? = null
        try {
            assertTrue(catalog.config(vault.localId).optString("repo").isBlank())
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { state = ViewModelProvider(it)[AppState::class.java] }
            val deadline = SystemClock.uptimeMillis() + 10000
            while (
                !hasText(instrumentation.uiAutomation.rootInActiveWindow, "Opening local vault…") &&
                    SystemClock.uptimeMillis() < deadline
            ) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(50)
            }
            assertTrue(
                hasText(instrumentation.uiAutomation.rootInActiveWindow, "Opening local vault…")
            )
            assertFalse(hasText(instrumentation.uiAutomation.rootInActiveWindow, "Working…"))
            assertFalse(hasText(instrumentation.uiAutomation.rootInActiveWindow, "Syncing…"))
            assertFalse(hasText(instrumentation.uiAutomation.rootInActiveWindow, "New note"))
            mutex.unlock()
            locked = false
            val readyDeadline = SystemClock.uptimeMillis() + 10000
            while (SystemClock.uptimeMillis() < readyDeadline) {
                var ready = false
                instrumentation.runOnMainSync {
                    ready = state?.busy == false && state?.switching == false
                }
                if (ready && hasText(instrumentation.uiAutomation.rootInActiveWindow, "New note"))
                    break
                instrumentation.waitForIdleSync()
                SystemClock.sleep(50)
            }
            assertTrue(hasText(instrumentation.uiAutomation.rootInActiveWindow, "New note"))
            assertFalse(state!!.busy)
        } finally {
            if (locked) mutex.unlock()
            scenario?.close()
            state?.store?.db?.close()
            catalog.select(previous)
            context.deleteDatabase("vault-${vault.localId}.db")
            catalog.root(vault.localId).deleteRecursively()
        }
    }

    @Test
    fun populatedVaultLoadsFromLocalFilesAndRepairsStaleIndex(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "startup-${newId()}.db"
        var store = Store(context, databaseName)
        val folder = Folder(name = "Local load fixture")
        val document =
            DocumentCodec.encode(
                Document(
                    List(200) { index ->
                        Item(
                            kind = "LINE",
                            points = listOf(Pt(index.toFloat(), 0f), Pt(index.toFloat(), 100f)),
                        )
                    }
                )
            )
        val notes =
            List(160) { Note(folderId = folder.id, title = "Local note $it", document = document) }
        try {
            store.replaceLibrary(listOf(folder), notes)
            val scanStart = SystemClock.elapsedRealtimeNanos()
            val scanned = store.files.read()
            val scanMs = (SystemClock.elapsedRealtimeNanos() - scanStart) / 1e6
            val indexStart = SystemClock.elapsedRealtimeNanos()
            store.db.withTransaction {
                store.db.dao().clearNotes()
                store.db.dao().clearFolders()
                scanned.first.forEach { store.db.dao().put(it) }
                scanned.second.forEach { store.db.dao().put(it) }
            }
            val indexMs = (SystemClock.elapsedRealtimeNanos() - indexStart) / 1e6
            // Canonical files must repair an index that does not reflect a complete file write.
            store.db.dao().deleteNote(notes.first().id)
            store.db.close()
            val openStart = SystemClock.elapsedRealtimeNanos()
            store = Store(context, databaseName)
            store.ready.await()
            val openMs = (SystemClock.elapsedRealtimeNanos() - openStart) / 1e6
            assertEquals(160, store.dao.allNotes().size)
            assertEquals(document, store.dao.note(notes.first().id)!!.document)
            assertEquals(folder, store.dao.allFolders().single())
            Log.i(
                "DotnoteStartupTest",
                "160 notes / 32000 objects: scan=$scanMs ms, index=$indexMs ms, reopen=$openMs ms",
            )
        } finally {
            store.db.close()
            context.deleteDatabase(databaseName)
            store.root.deleteRecursively()
        }
    }
}
