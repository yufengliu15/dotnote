package dev.dotnote.app

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetPipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context
        get() = instrumentation.targetContext

    private fun await(check: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < until) {
            var done = false
            instrumentation.runOnMainSync { done = check() }
            if (done) return
            SystemClock.sleep(20)
        }
        fail("Timed out")
    }

    private fun node(label: String): android.view.accessibility.AccessibilityNodeInfo? {
        fun find(
            n: android.view.accessibility.AccessibilityNodeInfo
        ): android.view.accessibility.AccessibilityNodeInfo? {
            if (n.text?.toString() == label || n.contentDescription?.toString() == label) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { child ->
                find(child)?.let {
                    return it
                }
            }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }

    private fun click(label: String) {
        await { node(label) != null }
        var target = node(label)
        while (target != null && !target.isClickable) target = target.parent
        assertTrue(
            "Cannot click $label",
            target?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) ==
                true,
        )
    }

    private fun enterTitle(value: String) {
        fun find(
            n: android.view.accessibility.AccessibilityNodeInfo
        ): android.view.accessibility.AccessibilityNodeInfo? {
            if (n.isEditable) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { child ->
                find(child)?.let {
                    return it
                }
            }
            return null
        }
        val field = find(requireNotNull(instrumentation.uiAutomation.rootInActiveWindow))
        assertTrue(
            field?.performAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(
                        android.view.accessibility.AccessibilityNodeInfo
                            .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        value,
                    )
                },
            ) == true
        )
    }

    @Test
    fun vaultRenamePreservesIdentityAndMarksBackupPending() = runBlocking {
        val catalog = VaultCatalog(context)
        val vault = catalog.create("Before rename")
        val files = VaultFiles(catalog.root(vault.localId))
        val id = JSONObject(files.manifest.readText()).getString("id")
        try {
            files.read()
            val note = Note(title = "Preserved")
            files.writeNote(note)
            catalog.update(vault.localId) {
                it.put("repo", "owner/notes").put("automatic", false).put("base", "commit")
            }
            catalog.rename(vault.localId, "After rename")
            assertEquals(id, JSONObject(files.manifest.readText()).getString("id"))
            assertEquals(note.id, files.read().second.single().id)
            assertEquals("After rename", catalog.list().first { it.localId == vault.localId }.name)
            assertEquals("owner/notes", catalog.config(vault.localId).getString("repo"))
            assertEquals("commit", catalog.config(vault.localId).getString("base"))
            assertEquals(1, catalog.config(vault.localId).getInt("revision"))
            catalog.rename(vault.localId, "After rename")
            assertEquals(1, catalog.config(vault.localId).getInt("revision"))
        } finally {
            catalog.root(vault.localId).deleteRecursively()
        }
    }

    @Test
    fun openingHistoryPersistsAndRenameMoveDeleteRefreshIt() = runBlocking {
        val vault = VaultCatalog(context).create("History test")
        val store = Store(context, requestedVault = vault.localId)
        val recent = RecentNotes(context)
        val folder = Folder(name = "Original folder")
        val one = Note(title = "First")
        val two = Note(title = "Second")
        try {
            store.dao.put(folder)
            store.dao.put(one)
            store.dao.put(two)
            val revision = store.catalog.config(vault.localId).optLong("revision")
            recent.opened(vault.localId, one, listOf(folder))
            recent.opened(vault.localId, two, listOf(folder))
            recent.opened(vault.localId, one, listOf(folder))
            assertEquals(
                listOf(one.id, two.id),
                RecentNotes(context).list().filter { it.vaultId == vault.localId }.map { it.noteId },
            )
            assertEquals(revision, store.catalog.config(vault.localId).optLong("revision"))
            store.dao.renameNote(one.id, "Renamed")
            store.dao.moveNote(one.id, folder.id)
            store.dao.put(folder.copy(name = "Renamed folder"))
            val row = recent.list().first { it.noteId == one.id }
            assertEquals("Renamed", row.title)
            assertEquals("Renamed folder", row.folder)
            store.dao.deleteNote(one.id)
            assertFalse(recent.list().any { it.noteId == one.id })
        } finally {
            listOf(one, two).forEach { recent.remove(vault.localId, it.id) }
            store.db.close()
            store.root.deleteRecursively()
            context.deleteDatabase("vault-${vault.localId}.db")
        }
    }

    @Test
    fun resizedRemoteViewsFitMoreNotesAndKeepDistinctLaunchTargets() {
        val entries = List(12) { RecentNote("vault", "note-$it", "Note $it", "Folder") }
        instrumentation.runOnMainSync {
            val parent = FrameLayout(context)
            fun rows(width: Float, height: Float): LinearLayout {
                val view =
                    NoteWidgets.render(
                            context,
                            width,
                            height,
                            entries,
                            mapOf("vault" to "University"),
                        )
                        .apply(context, parent)
                return view.findViewById(R.id.widget_rows)
            }
            assertEquals(1, rows(250f, 160f).childCount)
            val larger = rows(500f, 272f)
            assertEquals(3, larger.childCount)
            assertEquals(
                "Note 0",
                larger.getChildAt(0).findViewById<TextView>(R.id.widget_first_title).text,
            )
            assertEquals(
                "Note 1",
                larger.getChildAt(0).findViewById<TextView>(R.id.widget_second_title).text,
            )
            assertEquals(
                View.VISIBLE,
                larger.getChildAt(2).findViewById<View>(R.id.widget_second).visibility,
            )
            assertNotEquals(
                NoteWidgets.launchIntent(context, entries[0]).data,
                NoteWidgets.launchIntent(context, entries[1]).data,
            )
            val quick =
                NoteWidgets.layouts(context, Bundle(), true, entries, emptyMap())
                    .apply(context, parent)
            assertNotNull(quick.findViewById<View>(R.id.widget_quick))
        }
    }

    @Test
    fun widgetLaunchCreatesInSelectedFolderAndOpensAcrossVaults() {
        val catalog = VaultCatalog(context)
        val original = catalog.selected()
        val a = catalog.create("Widget A")
        val b = catalog.create("Widget B")
        catalog.select(a.localId)
        val bStore = Store(context, requestedVault = b.localId)
        val parent = Folder(name = "University")
        val child = Folder(parentId = parent.id, name = "Lectures")
        runBlocking {
            bStore.dao.put(parent)
            bStore.dao.put(child)
        }
        var id: String? = null
        try {
            ActivityScenario.launch<MainActivity>(NoteWidgets.launchIntent(context)).use { scenario
                ->
                lateinit var state: AppState
                scenario.onActivity { state = ViewModelProvider(it)[AppState::class.java] }
                await { state.newNoteRequested && !state.busy }
                enterTitle("From widget")
                click("Choose vault")
                click("Widget B")
                click("University")
                click("Lectures")
                await { node("Folder: University / Lectures") != null }
                click("Create note")
                await { state.note?.title == "From widget" && !state.busy }
                id = state.note!!.id
                assertEquals(b.localId, state.store.vaultId)
                assertEquals(child.id, state.note!!.folderId)
                scenario.onActivity { state.switchVault(a.localId) }
                await { state.store.vaultId == a.localId && !state.busy }
                val row = RecentNote(b.localId, id!!, "From widget", "University / Lectures")
                context.startActivity(NoteWidgets.launchIntent(context, row))
                await { state.note?.id == id && !state.busy }
                assertEquals(b.localId, state.store.vaultId)
                context.startActivity(NoteWidgets.launchIntent(context))
                await { state.newNoteRequested && !state.busy }
                assertEquals(id, state.note!!.id)
                scenario.onActivity {
                    state.newNoteRequested = false
                    state.closeNote()
                }
                await { state.note == null && !state.busy }
                runBlocking { state.store.dao.deleteNote(id!!) }
            }
        } finally {
            id?.let { RecentNotes(context).remove(b.localId, it) }
            bStore.db.close()
            catalog.select(original)
            listOf(a, b).forEach { catalog.root(it.localId).deleteRecursively() }
        }
    }
}
