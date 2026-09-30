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
    var newNoteRequested by mutableStateOf(false)
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
    var importReport by mutableStateOf<String?>(null)
    var tool by mutableStateOf(Tool.PEN)
    var textEdit by mutableStateOf<TextEditRequest?>(null)
        private set

    var textSize by mutableFloatStateOf(24f)

    fun requestText(position: Pt) {
        val current = note ?: return
        if (busy) return
        val existing =
            document.items.lastOrNull {
                it.kind == "TEXT" && hitItem(it, position, 4f / document.camera.zoom)
            }
        textEdit = TextEditRequest(current.id, existing, position, color, textSize)
    }

    fun dismissText() {
        textEdit = null
    }

    fun applyText(content: String, size: Float) {
        val request = textEdit ?: return
        if (note?.id != request.noteId) {
            dismissText()
            return
        }
        require(content.isNotBlank() && content.length <= 10000)
        require(size.isFinite() && size in 8f..144f)
        val item = textItem(content, size, request.color, request.position, request.item)
        commit(
            if (request.item == null) document.items + item
            else document.items.map { if (it.id == item.id) item else it }
        )
        textSize = size
        dismissText()
    }

    fun deleteText() {
        val request = textEdit ?: return
        if (note?.id == request.noteId && request.item != null)
            commit(document.items.filterNot { it.id == request.item.id })
        dismissText()
    }

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
    private val openings = Channel<Pair<Store, String>>(Channel.UNLIMITED)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for ((storage, id) in openings) {
                runCatching {
                    storage.mutex.withLock {
                        storage.dao.note(id)?.let { latest ->
                            RecentNotes(getApplication())
                                .opened(storage.vaultId, latest, storage.dao.allFolders())
                        }
                    }
                }
            }
        }
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
            .put("textSize", textSize)
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
            textSize = o.optDouble("textSize", 24.0).toFloat().coerceIn(8f, 144f)
            rows = o.getInt("rows").coerceIn(1, 30)
            cols = o.getInt("cols").coerceIn(1, 30)
            fingerDrawing = o.getBoolean("finger")
            dock = o.getString("dock").takeIf { it in listOf("Top", "Left", "Right") } ?: "Top"
        }
    }

    suspend fun switchVaultNow(id: String): Boolean {
        if (id == store.vaultId) return true
        if (!flush()) return false
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
        return true
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

    suspend fun vaultFolders(id: String): List<Folder> {
        require(catalog.list().any { it.localId == id }) { "Vault is no longer available" }
        val target = openedStores.getOrPut(id) { Store(getApplication(), requestedVault = id) }
        return target.dao.allFolders()
    }

    fun renameVault(id: String, name: String) = runAction {
        catalog.rename(id, name)
        vaultVersion++
    }

    fun widgetAction(action: WidgetAction) = runAction {
        if (action.note == null) {
            newNoteRequested = true
        } else {
            val target = requireNotNull(action.vault)
            if (catalog.list().none { it.localId == target }) {
                message = "This vault is no longer available"
                NoteWidgets.refresh(getApplication())
                return@runAction
            }
            if (!flush() || !switchVaultNow(target)) return@runAction
            val found = store.dao.note(action.note)
            if (found == null) {
                withContext(Dispatchers.IO) {
                    RecentNotes(getApplication()).remove(target, action.note)
                }
                message = "This note was deleted or moved to another vault"
            } else {
                newNoteRequested = false
                openNow(found)
            }
        }
    }

    suspend fun vaultTemplates(id: String): List<NoteSummary> {
        vaultFolders(id)
        return openedStores.getValue(id).dao.templates()
    }

    fun setTemplate(enabled: Boolean) = runAction {
        if (!flush()) return@runAction
        val current = note ?: return@runAction
        val latest = store.dao.note(current.id) ?: return@runAction
        val updated = latest.copy(isTemplate = enabled, modified = System.currentTimeMillis())
        store.dao.put(updated)
        note = updated
        message = if (enabled) "Template available in New note" else "Note removed from templates"
    }

    fun createNote(
        title: String,
        destination: String? = folderId,
        vault: String = store.vaultId,
        templateId: String? = null,
        isTemplate: Boolean = false,
    ) = runAction {
        if (!flush() || !switchVaultNow(vault)) return@runAction
        require(destination == null || store.dao.allFolders().any { it.id == destination }) {
            "The selected folder no longer exists"
        }
        val source =
            templateId?.let { id ->
                requireNotNull(store.dao.note(id)?.takeIf { it.isTemplate }) {
                    "The selected template no longer exists"
                }
            }
        val content =
            withContext(Dispatchers.IO) {
                val copied =
                    source?.let {
                        val decoded = DocumentCodec.decode(it.document)
                        decoded.copy(items = decoded.items.map { item -> item.copy(id = newId()) })
                    } ?: Document()
                DocumentCodec.encode(copied)
            }
        val created =
            Note(
                title = title.trim().ifEmpty { "Untitled" },
                folderId = destination,
                document = content,
                isTemplate = isTemplate,
            )
        store.dao.put(created)
        openNow(created)
    }

    private var systemNoteStarted = false
    private var systemNoteRequest = 0L
    internal var systemNoteOpening by mutableStateOf(false)
        private set

    /** Only the dedicated system-note activity calls this; its UI never renders the library. */
    internal fun startSystemNote(
        title: String,
        restoredVault: String? = null,
        restoredNote: String? = null,
        fresh: Boolean = false,
    ) {
        // Activity recreation can happen before the queued creation finishes.
        if (systemNoteStarted && !fresh) return
        systemNoteStarted = true
        val request = ++systemNoteRequest
        systemNoteOpening = true
        runAction {
            try {
                if (fresh) {
                    check(flush()) {
                        "The previous quick note could not be saved. Retry to continue."
                    }
                    note = null
                }
                if (
                    restoredVault != null &&
                        restoredNote != null &&
                        catalog.list().any { it.localId == restoredVault }
                ) {
                    check(switchVaultNow(restoredVault)) { "Could not reopen the quick note" }
                    store.dao.note(restoredNote)?.let {
                        openNow(it)
                        if (request == systemNoteRequest) systemNoteOpening = false
                        return@runAction
                    }
                }
                val created = Note(title = title, folderId = null)
                store.dao.put(created)
                openNow(created)
                tool = Tool.PEN
                if (request == systemNoteRequest) systemNoteOpening = false
            } finally {
                if (request == systemNoteRequest && systemNoteOpening) systemNoteStarted = false
            }
        }
    }

    fun open(id: String) = runAction { if (flush()) store.dao.note(id)?.let { openNow(it) } }

    private suspend fun openNow(value: Note) {
        val decoded = withContext(Dispatchers.IO) { DocumentCodec.decode(value.document) }
        note = value
        textEdit = null
        folderId = value.folderId
        document = decoded
        history = History()
        selection = emptySet()
        revision++
        saved = true
        // Process opening history in order, without putting disk I/O on the first pen event.
        openings.trySend(store to value.id)
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
            textEdit = null
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
                if (it.id in selection && !it.locked && !it.image) it.copy(color = value) else it
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

    fun importDocument(uri: Uri) = runAction {
        val id = note?.id ?: return@runAction
        val destination = store
        val top = document.bounds?.bottom?.plus(48f) ?: 0f
        val imported = withContext(Dispatchers.IO) { DocumentImport.import(destination, uri, top) }
        if (note?.id == id && store === destination) {
            commit(document.items + imported.items)
            message = imported.message
            importReport = imported.conversionReport
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
