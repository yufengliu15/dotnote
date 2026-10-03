package dev.dotnote.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotesRoleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context
        get() = instrumentation.targetContext

    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false
            instrumentation.runOnMainSync { done = check() }
            if (done) return
            SystemClock.sleep(20)
        }
        fail("Quick note did not become ready")
    }

    private fun canvas(view: View): NotebookView? {
        if (view is NotebookView) return view
        if (view is ViewGroup)
            for (i in 0 until view.childCount) {
                canvas(view.getChildAt(i))?.let {
                    return it
                }
            }
        return null
    }

    private fun visibleLabels(): List<String> {
        val labels = mutableListOf<String>()
        fun visit(node: android.view.accessibility.AccessibilityNodeInfo) {
            node.text?.toString()?.let(labels::add)
            node.contentDescription?.toString()?.let(labels::add)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return labels
    }

    private fun launchIntent() =
        Intent(context, CreateNoteActivity::class.java)
            .setAction(Intent.ACTION_CREATE_NOTE)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            )

    private fun isolatedVault(block: (VaultCatalog, VaultInfo) -> Unit) {
        val catalog = VaultCatalog(context)
        val original = catalog.selected()
        val vault = catalog.create("Private vault for notes-role test")
        catalog.select(vault.localId)
        try {
            block(catalog, vault)
        } finally {
            catalog.select(original)
            VaultFiles(catalog.root(vault.localId)).read().second.forEach {
                RecentNotes(context).remove(vault.localId, it.id)
            }
            catalog.root(vault.localId).deleteRecursively()
        }
    }

    @Test
    fun systemNotesIntentResolvesToSeparateExportedActivity() {
        val info =
            context.packageManager
                .resolveActivity(
                    Intent(Intent.ACTION_CREATE_NOTE).setPackage(context.packageName),
                    PackageManager.MATCH_DEFAULT_ONLY,
                )!!
                .activityInfo
        assertEquals(CreateNoteActivity::class.java.name, info.name)
        assertTrue(info.exported)
        // AOSP Notes role requires SHOW_WHEN_LOCKED | TURN_SCREEN_ON activity flags.
        assertEquals(0x1800000, info.flags and 0x1800000)
        assertEquals(ActivityInfo.DOCUMENT_LAUNCH_ALWAYS, info.documentLaunchMode)
        assertTrue(info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        assertNotEquals(MainActivity::class.java.name, info.name)
    }

    @Test
    fun roleRequestIsGuardedByDeviceAvailability() {
        val role = DefaultNotes(context)
        if (role.available) {
            assertNotNull(role.request())
        } else {
            assertNull(role.request())
            assertFalse(role.held)
        }
    }

    @Test
    fun freshQuickNoteIgnoresExistingNoteExtrasAndSurvivesRotationAndSaving() =
        isolatedVault { catalog, vault ->
            val privateNote = Note(title = "PRIVATE HISTORICAL NOTE")
            VaultFiles(catalog.root(vault.localId)).writeNote(privateNote)
            val intent =
                launchIntent().putExtra("note", privateNote.id).putExtra("vault", vault.localId)
            ActivityScenario.launch<CreateNoteActivity>(intent).use { scenario ->
                lateinit var state: AppState
                var view: NotebookView? = null
                scenario.onActivity { state = ViewModelProvider(it)[AppState::class.java] }
                await {
                    scenario.onActivity { view = canvas(it.window.decorView) }
                    state.note != null && !state.busy && (view?.width ?: 0) > 0
                }
                val id = state.note!!.id
                assertNotEquals(privateNote.id, id)
                assertNull(state.note!!.folderId)
                assertTrue(state.document.items.isEmpty())
                await { visibleLabels().contains("Close quick note") }
                val labels = visibleLabels()
                assertTrue(labels.contains("Close quick note"))
                listOf(
                        "PRIVATE HISTORICAL NOTE",
                        vault.name,
                        "Library options",
                        "Note options",
                        "Back to notes",
                    )
                    .forEach { assertFalse("Quick note exposed $it", labels.contains(it)) }
                scenario.onActivity {
                    state.commit(
                        listOf(Item(kind = "LINE", points = listOf(Pt(10f, 20f), Pt(50f, 60f))))
                    )
                }
                runBlocking { assertTrue(state.flush()) }
                scenario.recreate()
                scenario.onActivity { state = ViewModelProvider(it)[AppState::class.java] }
                await { state.note?.id == id && !state.busy }
                assertEquals(1, state.document.items.size)
                runBlocking {
                    assertEquals(2, state.store.dao.allNotes().size)
                    assertEquals(
                        1,
                        DocumentCodec.decode(state.store.dao.note(id)!!.document).items.size,
                    )
                }
                scenario.onActivity { state.closeNote() }
                await { state.note == null && !state.busy }
            }
        }

    @Test
    fun reusedSystemTaskSavesPreviousNoteAndCreatesFreshScene() = isolatedVault { _, _ ->
        ActivityScenario.launch<CreateNoteActivity>(launchIntent()).use { scenario ->
            lateinit var state: AppState
            scenario.onActivity { state = ViewModelProvider(it)[AppState::class.java] }
            await { state.note != null && !state.busy }
            val oldId = state.note!!.id
            scenario.onActivity {
                state.commit(listOf(Item(kind = "LINE", points = listOf(Pt(1f, 2f), Pt(3f, 4f)))))
                it.onNewIntent(Intent(it.intent))
                assertTrue(state.systemNoteOpening)
            }
            await { state.note?.id != oldId && !state.busy && !state.systemNoteOpening }
            assertTrue(state.document.items.isEmpty())
            runBlocking {
                assertEquals(
                    1,
                    DocumentCodec.decode(state.store.dao.note(oldId)!!.document).items.size,
                )
                assertEquals(2, state.store.dao.allNotes().size)
            }
            scenario.onActivity { state.closeNote() }
            await { state.note == null && !state.busy }
        }
    }

    @Test
    fun separateSystemLaunchesCreateIndependentNotes() = isolatedVault { _, _ ->
        val ids = mutableSetOf<String>()
        repeat(2) {
            ActivityScenario.launch<CreateNoteActivity>(launchIntent()).use { scenario ->
                lateinit var state: AppState
                scenario.onActivity { state = ViewModelProvider(it)[AppState::class.java] }
                await { state.note != null && !state.busy }
                assertTrue(ids.add(state.note!!.id))
                assertTrue(state.document.items.isEmpty())
                scenario.onActivity { state.closeNote() }
                await { state.note == null && !state.busy }
            }
        }
        assertEquals(2, ids.size)
    }
}
