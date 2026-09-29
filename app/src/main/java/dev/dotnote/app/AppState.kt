package dev.dotnote.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

val defaultPalette =
    listOf(0xff25342e, 0xff3267ad, 0xffc65a48, 0xff8a65ab, 0xffd8a728, 0xff398b6a).map {
        it.toInt()
    }

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class AppState(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("writing", 0)
    val catalog = VaultCatalog(application)
    private val activeStore = MutableStateFlow(Store(application))
    private val openedStores = mutableMapOf(activeStore.value.vaultId to activeStore.value)
    val store
        get() = activeStore.value

    var vaultVersion by mutableIntStateOf(0)
    var switching by mutableStateOf(true)
        private set

    val folders =
        activeStore
            .flatMapLatest { it.dao.folders() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val notes =
        activeStore
            .flatMapLatest { it.dao.notes() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val actions = Mutex()
    var folderId by mutableStateOf<String?>(null)
    var note by mutableStateOf<Note?>(null)
        private set

    var document by mutableStateOf(Document())
        private set

    var revision by mutableIntStateOf(0)
        private set

    var saved by mutableStateOf(true)
        private set

    var busy by mutableStateOf(true)
        private set

    var message by mutableStateOf<String?>(null)
    var tool by mutableStateOf(Tool.PEN)
    var color by mutableIntStateOf(preferences.getInt("color", 0xff25342e.toInt()))
    var palette by
        mutableStateOf(
            List(6) { index -> preferences.getInt("palette_$index", defaultPalette[index]) }
        )
        private set

    fun updatePalette(index: Int, value: Int) {
        require(index in palette.indices)
        val opaque = value or 0xff000000.toInt()
        palette = palette.toMutableList().also { it[index] = opaque }
        color = opaque
        preferences.edit().putInt("palette_$index", opaque).putInt("color", opaque).apply()
    }

    var strokeWidth by mutableFloatStateOf(preferences.getFloat("width", 3f))
    var rows by mutableIntStateOf(preferences.getInt("rows", 3))
    var cols by mutableIntStateOf(preferences.getInt("cols", 3))
    var fingerDrawing by mutableStateOf(preferences.getBoolean("finger", false))
    var dock by mutableStateOf(preferences.getString("dock", "Top") ?: "Top")
    var selection by mutableStateOf<Set<String>>(emptySet())
    var history = History()
        private set

    private var saveSequence = 0L
    private var cameraJob: Job? = null

    private data class Save(
        val storage: Store,
        val id: String,
        val document: Document,
        val sequence: Long,
        val done: CompletableDeferred<Boolean>? = null,
    )

    private val queue = Channel<Save>(Channel.UNLIMITED)

    init {
        runAction {
            loadWriting()
            switching = false
            catalog.list().forEach { BackupScheduler.schedule(application, it.localId) }
        }
        viewModelScope.launch {
            snapshotFlow {
                    vaultVersion
                    if (switching) null else store to writingJson().toString()
                }
                .debounce(400)
                .filterNotNull()
                .collect { (storage, json) ->
                    try {
                        persistWriting(storage, json)
                    } catch (e: Exception) {
                        message = "Settings could not be saved: ${e.message}"
                    }
                    preferences
                        .edit()
                        .putInt("color", color)
                        .putFloat("width", strokeWidth)
                        .putInt("rows", rows)
                        .putInt("cols", cols)
                        .putBoolean("finger", fingerDrawing)
                        .putString("dock", dock)
                        .apply()
                }
        }
        viewModelScope.launch {
            for (save in queue) {
                val ok =
                    try {
                        withContext(Dispatchers.IO) {
                            save.storage.dao.save(
                                save.id,
                                DocumentCodec.encode(save.document),
                                System.currentTimeMillis(),
                            )
                        }
                        if (
                            store === save.storage &&
                                note?.id == save.id &&
                                save.sequence == saveSequence
                        )
                            saved = true
                        true
                    } catch (e: Exception) {
                        message = "Save failed: ${e.message}. Tap the save indicator to retry."
                        false
                    }
                save.done?.complete(ok)
            }
        }
    }

    fun runAction(action: suspend () -> Unit) {
        viewModelScope.launch {
            actions.withLock {
                busy = true
                try {
                    store.ready.await()
                    action()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    message = e.message ?: "Operation failed"
                } finally {
                    busy = false
                }
            }
        }
    }

    private fun writingJson() =
        JSONObject()
            .put("version", 1)
            .put("palette", JSONArray(palette))
            .put("color", color)
            .put("width", strokeWidth)
            .put("rows", rows)
            .put("cols", cols)
            .put("finger", fingerDrawing)
            .put("dock", dock)

    private suspend fun persistWriting(storage: Store, text: String) =
        withContext(Dispatchers.IO) {
            storage.ready.await()
            storage.mutex.withLock {
                val file = File(storage.root, ".dotnote/settings.json")
                if (!file.exists() || file.readText() != text) {
                    atomicText(file, text)
                    storage.changed()
                }
            }
        }

    suspend fun saveWriting() {
        persistWriting(store, writingJson().toString())
    }

    private suspend fun loadWriting() {
        val text =
            withContext(Dispatchers.IO) {
                File(store.root, ".dotnote/settings.json").takeIf { it.isFile }?.readText()
            }
        if (text != null) {
            val o = JSONObject(text)
            require(o.getInt("version") == 1) { "Unsupported writing settings" }
            val a = o.getJSONArray("palette")
            require(a.length() == 6)
            palette = List(6) { a.getInt(it) or 0xff000000.toInt() }
            color = o.getInt("color")
            strokeWidth = o.getDouble("width").toFloat().coerceIn(1f, 12f)
            rows = o.getInt("rows").coerceIn(1, 30)
            cols = o.getInt("cols").coerceIn(1, 30)
            fingerDrawing = o.getBoolean("finger")
            dock = o.getString("dock").takeIf { it in listOf("Top", "Left", "Right") } ?: "Top"
        }
    }

    suspend fun switchVaultNow(id: String) {
        if (id == store.vaultId) return
        if (!flush()) return
        saveWriting()
        switching = true
        try {
            val next = openedStores.getOrPut(id) { Store(getApplication(), requestedVault = id) }
            next.ready.await()
            activeStore.value = next
            catalog.select(id)
            note = null
            folderId = null
            selection = emptySet()
            history = History()
            revision++
            vaultVersion++
            loadWriting()
        } finally {
            switching = false
        }
    }

    fun switchVault(id: String) = runAction { switchVaultNow(id) }

    fun createVault(name: String) = runAction {
        val vault = withContext(Dispatchers.IO) { catalog.create(name) }
        switchVaultNow(vault.localId)
    }

    fun importVault(uri: Uri) = runAction {
        val vault = VaultTransfer.import(getApplication(), uri)
        switchVaultNow(vault.localId)
        message = "Vault imported"
    }

    fun exportVault(uri: Uri) = runAction {
        if (flush()) {
            saveWriting()
            VaultTransfer.export(getApplication(), store, uri)
            message = "Vault folder exported"
        }
    }

    fun restoreRepository(repo: String) = runAction {
        val vault = GitBackup(getApplication()).restore(repo)
        switchVaultNow(vault.localId)
        message = "Vault restored from GitHub"
    }

    fun createNote(title: String) = runAction {
        val created = Note(title = title.trim().ifEmpty { "Untitled" }, folderId = folderId)
        store.dao.put(created)
        openNow(created)
    }

    fun open(id: String) = runAction { store.dao.note(id)?.let(::openNow) }

    private fun openNow(value: Note) {
        val decoded = DocumentCodec.decode(value.document)
        note = value
        document = decoded
        history = History()
        selection = emptySet()
        revision++
        saved = true
    }

    fun save() {
        val id = note?.id ?: return
        saved = false
        saveSequence++
        queue.trySend(Save(store, id, document, saveSequence))
    }

    suspend fun flush(): Boolean {
        cameraJob?.cancel()
        val id = note?.id ?: return true
        saved = false
        saveSequence++
        val done = CompletableDeferred<Boolean>()
        queue.send(Save(store, id, document, saveSequence, done))
        return done.await()
    }

    fun closeNote() = runAction {
        if (flush()) {
            note = null
            selection = emptySet()
        }
    }

    fun commit(items: List<Item>, before: List<Item> = document.items) {
        if (items == before) return
        history.push(before)
        document = document.copy(items = items)
        revision++
        save()
    }

    fun preview(items: List<Item>) {
        document = document.copy(items = items)
        revision++
    }

    fun undo() {
        history.undo(document.items)?.let {
            document = document.copy(items = it)
            selection = emptySet()
            revision++
            save()
        }
    }

    fun redo() {
        history.redo(document.items)?.let {
            document = document.copy(items = it)
            selection = emptySet()
            revision++
            save()
        }
    }

    fun deleteSelection() {
        commit(document.items.filterNot { it.id in selection })
        selection = emptySet()
    }

    fun recolorSelection(value: Int = color) {
        commit(
            document.items.map {
                if (it.id in selection && !it.locked) it.copy(color = value) else it
            }
        )
    }

    fun camera(camera: Camera) {
        document = document.copy(camera = camera)
        cameraJob?.cancel()
        cameraJob =
            viewModelScope.launch {
                delay(350)
                save()
            }
    }

    fun dots() {
        document = document.copy(dots = !document.dots)
        revision++
        save()
    }

    fun renameNote(title: String) = runAction {
        note?.let {
            store.dao.renameNote(it.id, title.trim())
            note = it.copy(title = title.trim())
        }
    }

    fun importPdf(uri: Uri) = runAction {
        val id = note?.id ?: return@runAction
        val imported =
            withContext(Dispatchers.IO) {
                PdfFiles.import(store, uri, document.bounds?.bottom?.plus(48f) ?: 0f)
            }
        if (note?.id == id) {
            commit(document.items + imported)
            message = "Imported ${imported.size} PDF pages"
        }
    }

    fun backup(uri: Uri) = runAction {
        if (flush()) {
            store.backup(uri)
            message = "Backup saved"
        }
    }

    fun restore(uri: Uri) = runAction {
        val count = store.restore(uri)
        message = "Restored $count notes. Existing notes were kept."
    }

    fun export(uri: Uri, viewport: Bounds?) = runAction {
        if (flush()) {
            val snapshot = document
            withContext(Dispatchers.IO) { PdfFiles.export(store, snapshot, uri, viewport) }
            message = "PDF exported"
        }
    }
}
