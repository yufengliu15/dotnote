package dev.dotnote.app

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/**
 * A separate editor/task: a system launch must never reveal MainActivity's existing note/library.
 */
class CreateNoteActivity : ComponentActivity() {
    private val state by viewModels<AppState>()

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action != Intent.ACTION_CREATE_NOTE) return
        setIntent(intent)
        // A system bubble can reuse its task. Hide the old scene immediately, settle its live
        // stroke, then save it before opening another blank note in this activity.
        window.decorView.visibility = View.INVISIBLE
        settleCanvas(window.decorView)
        state.startSystemNote(quickNoteTitle(), fresh = true)
    }

    private fun settleCanvas(view: View) {
        if (view is NotebookView) view.settle()
        else if (view is ViewGroup)
            for (i in 0 until view.childCount) settleCanvas(view.getChildAt(i))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action != Intent.ACTION_CREATE_NOTE) {
            finish()
            return
        }
        enableEdgeToEdge()
        InkWarmup.start()
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        // A retained ViewModel preserves the current quick note on rotation. After process death,
        // saved note IDs may only be reopened while unlocked; locked launches always start fresh.
        state.startSystemNote(
            quickNoteTitle(),
            if (locked) null else savedInstanceState?.getString("quick-vault"),
            if (locked) null else savedInstanceState?.getString("quick-note"),
        )
        setContent {
            DotnoteTheme {
                // Restore visibility only after the composition has replaced the previous scene.
                SideEffect { window.decorView.visibility = View.VISIBLE }
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(state.message) {
                    state.message?.let {
                        snackbar.showSnackbar(it)
                        state.message = null
                    }
                }
                BackHandler { closeSavedNote() }
                Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { inset ->
                    Box(Modifier.fillMaxSize().padding(inset)) {
                        if (state.note != null && !state.systemNoteOpening) {
                            key(state.note!!.id) {
                                Editor(state, quickNote = true, onClose = ::closeSavedNote)
                            }
                        } else {
                            Column(
                                Modifier.align(Alignment.Center).padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                if (state.busy) {
                                    CircularProgressIndicator()
                                    Text("Opening a new note…", Modifier.padding(top = 16.dp))
                                } else {
                                    Text("The quick note could not be opened.")
                                    TextButton(
                                        onClick = {
                                            state.startSystemNote(
                                                quickNoteTitle(),
                                                fresh = state.note != null,
                                            )
                                        }
                                    ) {
                                        Text("Retry")
                                    }
                                    TextButton(onClick = { finish() }) { Text("Close") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        state.note?.let {
            outState.putString("quick-vault", state.store.vaultId)
            outState.putString("quick-note", it.id)
        }
        super.onSaveInstanceState(outState)
    }

    private fun closeSavedNote() {
        state.runAction { if (state.flush()) finish() }
    }

    private fun quickNoteTitle(): String =
        "Quick note · " +
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date())
}
