package dev.dotnote.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Forest = Color(0xff255a4e)
private val Paper = Color(0xfff7f7f2)
private val Ink = Color(0xff25342e)

@Composable
internal fun DotnoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            lightColorScheme(
                primary = Forest,
                onPrimary = Color.White,
                primaryContainer = Color(0xffdfebdf),
                background = Paper,
                surface = Paper,
                onSurface = Ink,
                secondary = Forest,
                secondaryContainer = Color(0xffe7edde),
                onSecondaryContainer = Forest,
                surfaceContainer = Color(0xffefefe8),
                surfaceContainerHigh = Color(0xffefefe8),
                surfaceContainerHighest = Color(0xffe7eae1),
                onSurfaceVariant = Color(0xff61705f),
            ),
        content = content,
    )
}

class MainActivity : ComponentActivity() {
    private var widgetRequest by mutableStateOf<WidgetAction?>(null)

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Stroke meshes are rebuilt on demand; give them back when the system is short of memory.
        if (
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        )
            StrokeCache.shared.clear()
    }

    override fun onStart() {
        super.onStart()
        CalendarWidgets.updateAll(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        widgetRequest = WidgetAction.from(intent)
    }

    private fun hideNavigationBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideNavigationBar()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        InkWarmup.start()
        if (savedInstanceState == null) widgetRequest = WidgetAction.from(intent)
        enableEdgeToEdge()
        hideNavigationBar()
        setContent {
            DotnoteTheme {
                val state: AppState = viewModel()
                LaunchedEffect(widgetRequest) {
                    widgetRequest?.let { state.widgetAction(it) }
                    widgetRequest = null
                }
                Dotnote(state)
            }
        }
    }
}

@Composable
private fun Dotnote(state: AppState) {
    val snackbar = remember { SnackbarHostState() }
    val localLoading by state.store.loadingStatus.collectAsStateWithLifecycle()
    LaunchedEffect(state.message) {
        state.message?.let { text ->
            snackbar.showSnackbar(text)
            if (state.message == text) state.message = null
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { inset ->
        Box(Modifier.fillMaxSize().padding(inset)) {
            // Avoid flashing an empty/stale library beneath the startup overlay.
            if (!state.switching || !state.busy) {
                if (state.note == null) Library(state) else key(state.note!!.id) { Editor(state) }
            }
            if (state.busy)
                Box(
                    Modifier.fillMaxSize()
                        .background(Color.White.copy(alpha = .6f))
                        .clickable(enabled = true, onClick = {}),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(shape = RoundedCornerShape(18.dp), shadowElevation = 4.dp) {
                        Row(
                            Modifier.padding(24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Text(if (state.switching) localLoading else "Updating notes…")
                        }
                    }
                }
        }
    }
    if (state.newNoteRequested) NewNoteDialog(state) { state.newNoteRequested = false }
}

@Composable
private fun Library(state: AppState) {
    val folders by state.folders.collectAsStateWithLifecycle()
    val notes by state.notes.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var dialog by remember { mutableStateOf<String?>(null) }
    var renameFolder by remember { mutableStateOf<Folder?>(null) }
    var renameNote by remember { mutableStateOf<NoteSummary?>(null) }
    var moveFolder by remember { mutableStateOf<Folder?>(null) }
    var moveNote by remember { mutableStateOf<NoteSummary?>(null) }
    var deleteFolder by remember { mutableStateOf<Folder?>(null) }
    var deleteNote by remember { mutableStateOf<NoteSummary?>(null) }
    var menu by remember { mutableStateOf(false) }
    var vaults by remember { mutableStateOf(false) }
    var githubSettings by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf(false) }
    val backup =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) {
            it?.let(state::backup)
        }
    val restore =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(state::restore)
        }
    val drag = remember { LibraryDragState() }
    var libraryOrigin by remember { mutableStateOf(Offset.Zero) }
    drag.allowed = { target ->
        drag.item?.let { item ->
            if (item.folder)
                folders
                    .find { it.id == item.id }
                    ?.let { source ->
                        target != source.parentId &&
                            canMoveFolder(
                                item.id,
                                target,
                                folders.associate { it.id to it.parentId },
                            )
                    } == true
            else notes.find { it.id == item.id }?.let { it.folderId != target } == true
        } == true
    }
    val dropItem: (LibraryDragItem, String?) -> Unit = { item, target ->
        state.runAction {
            if (item.folder)
                state.store.dao
                    .allFolders()
                    .find { it.id == item.id }
                    ?.let { state.store.moveFolder(it, target) }
            else state.store.dao.moveNote(item.id, target)
        }
    }
    val current = folders.find { it.id == state.folderId }
    BackHandler(state.folderId != null) { state.folderId = current?.parentId }
    val breadcrumbs =
        remember(folders, state.folderId) {
            val result = mutableListOf<Folder>()
            var cursor = current
            val seen = mutableSetOf<String>()
            while (cursor != null && seen.add(cursor.id)) {
                result.add(0, cursor)
                cursor = folders.find { it.id == cursor!!.parentId }
            }
            result
        }
    Box(Modifier.fillMaxSize().onGloballyPositioned { libraryOrigin = it.boundsInRoot().topLeft }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = Forest,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.size(42.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("d.", color = Paper, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Dotnote", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "A little room to think.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xff6f776d),
                    )
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Outlined.MoreVert, "Library options")
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Vaults") },
                            onClick = {
                                menu = false
                                vaults = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("GitHub backup & restore") },
                            onClick = {
                                menu = false
                                githubSettings = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Back up library") },
                            onClick = {
                                menu = false
                                backup.launch(
                                    "Dotnote-${SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())}.zip"
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Restore backup (merge)") },
                            onClick = {
                                menu = false
                                restore.launch(
                                    arrayOf("application/zip", "application/octet-stream")
                                )
                            },
                        )
                        DefaultNotesMenuItem { menu = false }
                        DropdownMenuItem(
                            text = { Text("Version & updates") },
                            onClick = {
                                menu = false
                                version = true
                            },
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { vaults = true }) {
                    Icon(Icons.Outlined.UnfoldMore, "Switch vault")
                    state.vaultVersion
                    Text(
                        state.catalog.list().find { it.localId == state.store.vaultId }?.name
                            ?: "My notes"
                    )
                }
                if (state.folderId != null || drag.item != null)
                    TextButton(
                        onClick = { state.folderId = null },
                        modifier = Modifier.libraryDropTarget(drag, null),
                    ) {
                        Text("All notes")
                    }
                breadcrumbs.forEach { f ->
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp))
                    TextButton(
                        onClick = { state.folderId = f.id },
                        modifier = Modifier.libraryDropTarget(drag, f.id),
                    ) {
                        Text(f.name)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    query,
                    { query = it },
                    Modifier.weight(1f),
                    placeholder = { Text("Find a note or folder") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
                FilledTonalIconButton(
                    onClick = { dialog = "folder" },
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(Icons.Outlined.CreateNewFolder, "New folder")
                }
                Button(
                    onClick = { state.newNoteRequested = true },
                    contentPadding = PaddingValues(16.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Outlined.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("New note")
                }
            }
            val visibleFolders =
                folders.filter {
                    if (query.isBlank()) it.parentId == state.folderId
                    else it.name.contains(query, true)
                }
            val visibleNotes =
                notes.filter {
                    if (query.isBlank()) it.folderId == state.folderId
                    else it.title.contains(query, true)
                }
            val recentNotes =
                remember(notes, state.store.vaultId, state.recentVersion) {
                    recentNotesFor(state.store.vaultId, RecentNotes(state.getApplication<android.app.Application>()).list(), notes)
                }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(220.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.weight(1f),
            ) {
                if (query.isBlank() && state.folderId == null && recentNotes.isNotEmpty())
                    item(key = "recent", span = { GridItemSpan(maxLineSpan) }) {
                        RecentNotesRow(state, recentNotes)
                    }
                if (visibleFolders.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "FOLDERS",
                            fontSize = 11.sp,
                            letterSpacing = 1.5.sp,
                            color = Color(0xff6f776d),
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(visibleFolders, key = { it.id }) { f ->
                        Surface(
                            onClick = {
                                state.folderId = f.id
                                query = ""
                            },
                            modifier =
                                Modifier.libraryDropTarget(drag, f.id)
                                    .libraryDragSource(
                                        drag,
                                        LibraryDragItem(f.id, f.name, true),
                                        dropItem,
                                    ),
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xffeaeee5),
                        ) {
                            Row(
                                Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.Folder, null, tint = Forest)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    f.name,
                                    Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                ObjectMenu(
                                    onRename = { renameFolder = f },
                                    onMove = { moveFolder = f },
                                    onDelete = { deleteFolder = f },
                                )
                            }
                        }
                    }
                }
                if (visibleNotes.isNotEmpty())
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.padding(top = 16.dp, bottom = 4.dp)) {
                            Text(
                                if (query.isBlank()) "NOTES" else "MATCHING NOTES",
                                fontSize = 11.sp,
                                letterSpacing = 1.5.sp,
                                color = Color(0xff6f776d),
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${visibleNotes.size} · Recently edited",
                                fontSize = 11.sp,
                                color = Color(0xff6f776d),
                            )
                        }
                    }
                items(visibleNotes, key = { it.id }) { note ->
                    Surface(
                        onClick = { state.open(note.id) },
                        modifier =
                            Modifier.libraryDragSource(
                                drag,
                                LibraryDragItem(note.id, note.title, false),
                                dropItem,
                            ),
                        shape = RoundedCornerShape(18.dp),
                        color = Color.White,
                        border = BorderStroke(1.dp, Color(0xffe0e4da)),
                    ) {
                        Column {
                            NotePreview(state.store, note, Modifier.fillMaxWidth().height(112.dp))
                            Row(
                                Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        note.title,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        (if (note.isTemplate) "Template · " else "") +
                                            SimpleDateFormat("MMM d · h:mm a", Locale.getDefault())
                                                .format(Date(note.modified)),
                                        fontSize = 11.sp,
                                        color = Color(0xff737a70),
                                    )
                                }
                                ObjectMenu(
                                    onRename = { renameNote = note },
                                    onMove = { moveNote = note },
                                    onDelete = { deleteNote = note },
                                )
                            }
                        }
                    }
                }
                if (visibleNotes.isEmpty() && visibleFolders.isEmpty())
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 60.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                Modifier.size(128.dp)
                                    .clip(RoundedCornerShape(28.dp))
                                    .background(Color(0xffe7edde)),
                                contentAlignment = Alignment.Center,
                            ) {
                                DotPattern(Modifier.fillMaxSize())
                                Icon(Icons.Outlined.Draw, null, Modifier.size(48.dp), tint = Forest)
                            }
                            Text(
                                if (query.isBlank()) "Space for your next idea."
                                else "No matches yet.",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                if (query.isBlank())
                                    "An endless canvas, a pen, and no distractions."
                                else "Try another title.",
                                color = Color(0xff6f776d),
                            )
                            if (query.isBlank())
                                Button(onClick = { state.newNoteRequested = true }) {
                                    Text("Create a note")
                                }
                        }
                    }
            }
        }
        LibraryDragPreview(drag, libraryOrigin)
    }
    if (vaults) VaultManagerDialog(state) { vaults = false }
    if (version)
        UpdateDialog(
            beforeInstall = {
                state.saveWriting()
                state.flush()
            }
        ) {
            version = false
        }
    if (githubSettings) GitHubSettings(state) { githubSettings = false }
    if (dialog != null)
        NameDialog("New folder", "") { value ->
            dialog = null
            if (value != null)
                state.runAction {
                    state.store.dao.put(Folder(parentId = state.folderId, name = value))
                }
        }
    renameFolder?.let { f ->
        NameDialog("Rename folder", f.name) { value ->
            renameFolder = null
            if (value != null) state.runAction { state.store.dao.put(f.copy(name = value)) }
        }
    }
    renameNote?.let { n ->
        NameDialog("Rename note", n.title) { value ->
            renameNote = null
            if (value != null) state.runAction { state.store.dao.renameNote(n.id, value) }
        }
    }
    if (moveFolder != null || moveNote != null)
        MoveDialog(
            folders,
            moveFolder?.id,
            onDismiss = {
                moveFolder = null
                moveNote = null
            },
        ) { target ->
            val f = moveFolder
            val n = moveNote
            moveFolder = null
            moveNote = null
            state.runAction {
                if (f != null) state.store.moveFolder(f, target)
                else if (n != null) state.store.dao.moveNote(n.id, target)
            }
        }
    deleteFolder?.let { f ->
        ConfirmDialog(
            "Delete ${f.name}?",
            "Only empty folders can be deleted.",
            { deleteFolder = null },
        ) {
            deleteFolder = null
            state.runAction { state.store.deleteFolder(f) }
        }
    }
    deleteNote?.let { n ->
        ConfirmDialog(
            "Delete ${n.title}?",
            "This removes the note permanently. Export a backup first if you want to keep it.",
            { deleteNote = null },
        ) {
            deleteNote = null
            state.runAction { state.store.dao.deleteNote(n.id) }
        }
    }
}

/** Up to [HOME_RECENT_LIMIT] most recently opened notes of the active vault, newest first. */
@Composable
private fun RecentNotesRow(state: AppState, recent: List<NoteSummary>) {
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "Recent notes" }) {
        Text(
            "RECENT",
            fontSize = 11.sp,
            letterSpacing = 1.5.sp,
            color = Color(0xff6f776d),
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            recent.forEach { note ->
                Surface(
                    onClick = { state.open(note.id) },
                    modifier = Modifier.width(168.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, Color(0xffe0e4da)),
                ) {
                    Column {
                        NotePreview(state.store, note, Modifier.fillMaxWidth().height(84.dp))
                        Text(
                            note.title,
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DotPattern(modifier: Modifier) {
    Canvas(modifier) {
        val step = 16.dp.toPx()
        var x = step
        while (x < size.width) {
            var y = step
            while (y < size.height) {
                drawCircle(Color(0xffc6d0bc), .8.dp.toPx(), Offset(x, y))
                y += step
            }
            x += step
        }
    }
}

@Composable
private fun ObjectMenu(onRename: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
    var show by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { show = true }) {
            Icon(Icons.Outlined.MoreVert, "Rename, move or delete")
        }
        DropdownMenu(show, { show = false }) {
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = {
                    show = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text("Move") },
                onClick = {
                    show = false
                    onMove()
                },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = {
                    show = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text,
                { text = it.take(150) },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onDone(text.trim()) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MoveDialog(
    folders: List<Folder>,
    movingId: String?,
    onDismiss: () -> Unit,
    onMove: (String?) -> Unit,
) {
    var current by remember { mutableStateOf<String?>(null) }
    val parents = folders.associate { it.id to it.parentId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to folder") },
        text = {
            Column {
                Text(
                    folders.find { it.id == current }?.name ?: "My notes",
                    fontWeight = FontWeight.Bold,
                )
                if (current != null)
                    TextButton(onClick = { current = parents[current] }) { Text("↑ Parent folder") }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(
                        folders.filter {
                            it.parentId == current &&
                                (movingId == null || canMoveFolder(movingId, it.id, parents))
                        }
                    ) { f ->
                        ListItem(
                            headlineContent = { Text(f.name) },
                            leadingContent = { Icon(Icons.Outlined.Folder, null) },
                            modifier = Modifier.clickable { current = f.id },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onMove(current) }) { Text("Move here") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun Editor(state: AppState, quickNote: Boolean = false, onClose: (() -> Unit)? = null) {
    state.importReport?.let { report ->
        AlertDialog(
            onDismissRequest = { state.importReport = null },
            title = { Text("PowerPoint converted to PDF") },
            text = { Text(report, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { state.importReport = null }) { Text("Done") } },
        )
    }
    state.textEdit?.let { TextEditorDialog(state, it) }
    var canvas by remember { mutableStateOf<NotebookView?>(null) }
    var settings by remember { mutableStateOf(false) }
    var shapes by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var pages by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf(false) }
    var colorSlot by remember { mutableStateOf<Int?>(null) }
    var selectionColor by remember { mutableStateOf(false) }
    var exportRegion by remember { mutableStateOf<Bounds?>(null) }
    val importDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(state::importDocument)
        }
    val exportPdf =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/pdf")
        ) {
            it?.let { uri -> state.export(uri, exportRegion) }
        }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, canvas) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                canvas?.settle()
                state.save()
                state.runAction { state.saveWriting() }
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    BackHandler {
        canvas?.settle()
        if (onClose != null) onClose() else state.closeNote()
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    canvas?.settle()
                    if (onClose != null) onClose() else state.closeNote()
                }
            ) {
                Icon(
                    if (quickNote) Icons.Outlined.Close else Icons.AutoMirrored.Outlined.ArrowBack,
                    if (quickNote) "Close quick note" else "Back to notes",
                )
            }
            Column(
                (if (!quickNote && state.dock == "Top") Modifier.width(140.dp)
                    else Modifier.weight(1f))
                    .clickable { rename = true }
                    .padding(horizontal = 8.dp)
            ) {
                Text(
                    state.note?.title ?: "",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (state.note?.isTemplate == true) "TEMPLATE" else "INFINITE CANVAS",
                    fontSize = 9.sp,
                    letterSpacing = 1.5.sp,
                    color = Color(0xff75806f),
                )
            }
            if (!quickNote && state.dock == "Top")
                Box(Modifier.weight(1f)) {
                    ToolStrip(
                        state,
                        true,
                        { settings = true },
                        { shapes = true },
                        { canvas?.settle() },
                        { colorSlot = it },
                    )
                }
            TextButton(
                onClick = {
                    canvas?.settle()
                    state.save()
                }
            ) {
                Icon(
                    if (state.saved) Icons.Outlined.CheckCircle else Icons.Outlined.Save,
                    "Save status",
                    Modifier.size(16.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(if (state.saved) "Saved" else "Unsaved", fontSize = 12.sp)
            }
            UndoRedoButtons(state) { canvas?.settle() }
            if (!quickNote)
                Box {
                    IconButton(onClick = { overflow = true }) {
                        Icon(Icons.Outlined.MoreVert, "Note options")
                    }
                    DropdownMenu(overflow, { overflow = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (state.note?.isTemplate == true) "Remove from templates"
                                    else "Use as template"
                                )
                            },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                            onClick = {
                                overflow = false
                                canvas?.settle()
                                state.setTemplate(state.note?.isTemplate != true)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Import PDF, image or PowerPoint") },
                            leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, null) },
                            onClick = {
                                overflow = false
                                canvas?.settle()
                                importDocument.launch(DocumentImport.mimeTypes)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("PDF page navigator") },
                            onClick = {
                                overflow = false
                                pages = true
                            },
                            enabled = state.document.items.any { it.locked },
                        )
                        DropdownMenuItem(
                            text = { Text("Export entire note as PDF") },
                            onClick = {
                                overflow = false
                                canvas?.settle()
                                exportRegion = null
                                exportPdf.launch("${state.note?.title}.pdf")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Export visible area as PDF") },
                            onClick = {
                                overflow = false
                                canvas?.settle()
                                exportRegion = canvas?.viewport()
                                exportPdf.launch("${state.note?.title}-selection.pdf")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Writing settings") },
                            onClick = {
                                overflow = false
                                settings = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("How to use Dotnote") },
                            onClick = {
                                overflow = false
                                help = true
                            },
                        )
                        DefaultNotesMenuItem { overflow = false }
                        DropdownMenuItem(
                            text = { Text("Version & updates") },
                            onClick = {
                                overflow = false
                                version = true
                            },
                        )
                    }
                }
        }
        HorizontalDivider(color = Color(0xffe0e4da))
        Row(Modifier.weight(1f)) {
            if (!quickNote && state.dock == "Left")
                ToolStrip(
                    state,
                    false,
                    onSettings = { settings = true },
                    onShapes = { shapes = true },
                    beforeAction = { canvas?.settle() },
                    onEditColor = { colorSlot = it },
                )
            Box(Modifier.weight(1f).fillMaxHeight()) {
                AndroidView(
                    factory = { context -> NotebookView(context, state).also { canvas = it } },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { it.release() },
                    update = {
                        state.revision
                        state.document.camera
                        state.selection
                        it.refresh()
                    },
                )
                CanvasOverlays(state, { canvas }, { selectionColor = true })
                PageScrubber(state) { canvas }
            }
            if (!quickNote && state.dock == "Right")
                ToolStrip(
                    state,
                    false,
                    onSettings = { settings = true },
                    onShapes = { shapes = true },
                    beforeAction = { canvas?.settle() },
                    onEditColor = { colorSlot = it },
                )
        }
        if (quickNote) {
            HorizontalDivider(color = Color(0xffe0e4da))
            ToolStrip(
                state,
                true,
                { settings = true },
                { shapes = true },
                { canvas?.settle() },
                { colorSlot = it },
            )
        }
    }
    colorSlot?.let { slot ->
        ColorPickerDialog("Color slot ${slot + 1}", state.palette[slot], { colorSlot = null }) {
            state.updatePalette(slot, it)
            colorSlot = null
        }
    }
    if (selectionColor)
        ColorPickerDialog(
            "Selection color",
            state.document.items.firstOrNull { it.id in state.selection }?.color ?: state.color,
            { selectionColor = false },
        ) {
            state.recolorSelection(it)
            selectionColor = false
        }
    if (rename)
        NameDialog("Rename note", state.note?.title ?: "") {
            rename = false
            if (it != null) state.renameNote(it)
        }
    if (settings) WritingSettings(state) { settings = false }
    if (shapes)
        AlertDialog(
            onDismissRequest = { shapes = false },
            title = { Text("Shapes") },
            text = {
                Column {
                    listOf(
                            Tool.LINE,
                            Tool.ARROW,
                            Tool.RECTANGLE,
                            Tool.SQUARE,
                            Tool.ELLIPSE,
                            Tool.CIRCLE,
                            Tool.GRID,
                        )
                        .chunked(2)
                        .forEach { pair ->
                            Row {
                                pair.forEach { tool ->
                                    TextButton(
                                        onClick = {
                                            canvas?.settle()
                                            state.tool = tool
                                            shapes = false
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(toolIcon(tool), null, Modifier.size(20.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(tool.label)
                                    }
                                }
                            }
                        }
                    HorizontalDivider()
                    Text("Grid size", Modifier.padding(top = 12.dp), fontWeight = FontWeight.Medium)
                    Stepper("Rows", state.rows) { state.rows = it }
                    Stepper("Columns", state.cols) { state.cols = it }
                }
            },
            confirmButton = { TextButton(onClick = { shapes = false }) { Text("Done") } },
        )
    if (pages)
        AlertDialog(
            onDismissRequest = { pages = false },
            title = { Text("PDF pages") },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(state.document.items.filter { it.locked }) { item ->
                        TextButton(
                            onClick = {
                                canvas?.page(item)
                                pages = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Page ${item.page+1} · ${item.asset?.take(6)}")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pages = false }) { Text("Close") } },
        )
    if (version)
        UpdateDialog(
            beforeInstall = {
                canvas?.settle()
                state.saveWriting()
                state.flush()
            }
        ) {
            version = false
        }
    if (help)
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text("Make yourself at home") },
            text = {
                Text(
                    "Write with your stylus; drag or flick with one finger to pan, and pinch to zoom. Faster flicks coast farther; touch the canvas to stop. Enable finger drawing in settings if needed, use two fingers to pan while finger drawing is enabled.\n\nChoose Text and tap the canvas to type. Tap existing text with Text to edit it. Use Select to move, resize, recolor or delete text.\n\nIn Note options, choose Use as template. New note can start from any template in the selected vault; each copy is independent.\n\nSelect a shape and drag to draw it. The grid's rows and columns are adjustable.\n\nUse Select to circle objects or tap one. Drag the selection to move it; drag its bottom-right handle to resize.\n\nPDF pages stay locked underneath your writing. Use the page navigator to jump through a document, or drag the page scrubber in the top-right corner: slowly for one page at a time, faster to skip ahead. Tap its top or bottom half for the previous or next page.\n\nNotes save automatically. Back up your library from the home screen menu. Uninstalling removes local notes."
                )
            },
            confirmButton = { TextButton(onClick = { help = false }) { Text("Got it") } },
        )
}

private fun toolIcon(tool: Tool): ImageVector =
    when (tool) {
        Tool.FILL -> Icons.Outlined.FormatColorFill
        Tool.PEN -> Icons.Outlined.Edit
        Tool.HIGHLIGHTER -> Icons.Outlined.BorderColor
        Tool.ERASER -> Icons.Outlined.AutoFixNormal
        Tool.LASSO -> Icons.Outlined.Gesture
        Tool.TEXT -> Icons.Outlined.TextFields
        Tool.LINE -> Icons.Outlined.HorizontalRule
        Tool.ARROW -> Icons.AutoMirrored.Outlined.ArrowForward
        Tool.RECTANGLE -> Icons.Outlined.CropLandscape
        Tool.SQUARE -> Icons.Outlined.CropSquare
        Tool.CIRCLE,
        Tool.ELLIPSE -> Icons.Outlined.Circle
        Tool.GRID -> Icons.Outlined.GridOn
    }

@Composable
private fun ToolStrip(
    state: AppState,
    horizontal: Boolean,
    onSettings: () -> Unit,
    onShapes: () -> Unit,
    beforeAction: () -> Unit,
    onEditColor: (Int) -> Unit,
) {
    var fillMenu by remember { mutableStateOf(false) }
    val shapeTool = state.tool.ordinal >= Tool.LINE.ordinal
    val tools = listOf(Tool.PEN, Tool.HIGHLIGHTER, Tool.ERASER, Tool.LASSO, Tool.TEXT, Tool.FILL)
    val content: @Composable () -> Unit = {
        tools.forEach { tool ->
            ToolButton(tool.label, toolIcon(tool), state.tool == tool) {
                beforeAction()
                state.tool = tool
                if (tool == Tool.FILL) fillMenu = true
                state.selection = emptySet()
            }
        }
        ToolButton(
            if (shapeTool) state.tool.label else "Shapes",
            if (shapeTool) toolIcon(state.tool) else Icons.Outlined.Category,
            shapeTool,
        ) {
            beforeAction()
            onShapes()
        }
        Box {
            DropdownMenu(expanded = fillMenu, onDismissRequest = { fillMenu = false }) {
                Row(
                    Modifier.padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = !state.fillHighlighter,
                        onClick = {
                            state.fillHighlighter = false
                            fillMenu = false
                        },
                        label = { Text("Pen fill") },
                    )
                    FilterChip(
                        selected = state.fillHighlighter,
                        onClick = {
                            state.fillHighlighter = true
                            fillMenu = false
                        },
                        label = { Text("Highlighter base") },
                    )
                }
            }
        }
        if (horizontal) VerticalDivider(Modifier.height(32.dp).padding(horizontal = 5.dp))
        else HorizontalDivider(Modifier.width(40.dp).padding(vertical = 5.dp))
        state.palette.forEachIndexed { index, color ->
            Box(
                Modifier.size(48.dp)
                    .semantics { contentDescription = "Color slot ${index + 1}" }
                    .combinedClickable(
                        onClickLabel = "Use color ${Integer.toHexString(color)}",
                        onLongClickLabel = "Edit color slot ${index + 1}",
                        onClick = {
                            beforeAction()
                            state.color = color
                            state.tool = Tool.PEN
                            state.selection = emptySet()
                        },
                        onLongClick = {
                            beforeAction()
                            onEditColor(index)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(if (state.color == color) 34.dp else 26.dp)
                        .border(
                            if (state.color == color) 2.dp else 0.dp,
                            if (state.color == color) Forest else Color.Transparent,
                            CircleShape,
                        )
                        .padding(4.dp)
                        .background(Color(color), CircleShape)
                )
            }
        }
        ToolButton("Settings", Icons.Outlined.Tune, false, onSettings)
    }
    Surface(color = Paper) {
        if (horizontal)
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                content()
            }
        else
            Column(
                Modifier.width(68.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                content()
            }
    }
}

@Composable
private fun ToolButton(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(58.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0xffdfe9d8) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(top = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, label, Modifier.size(22.dp), tint = if (selected) Forest else Ink)
        Text(label, fontSize = 9.sp, color = if (selected) Forest else Ink, maxLines = 1)
    }
}

@Composable
private fun WritingSettings(state: AppState, onDismiss: () -> Unit) {
    var hex by remember { mutableStateOf(String.format("%06X", state.color and 0xffffff)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your writing space") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Pen width · ${"%.1f".format(state.strokeWidth)}",
                    fontWeight = FontWeight.Medium,
                )
                Slider(
                    value = state.strokeWidth,
                    onValueChange = { state.strokeWidth = it },
                    valueRange = 1f..12f,
                )
                Text("Highlighter uses 5× the pen width.", fontSize = 11.sp, color = Color.Gray)
                Spacer(Modifier.height(16.dp))
                Text("Toolbar position", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Left", "Top", "Right").forEach { position ->
                        FilterChip(
                            selected = state.dock == position,
                            onClick = { state.dock = position },
                            label = { Text(position) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Draw with a finger", Modifier.weight(1f))
                    Switch(state.fingerDrawing, { state.fingerDrawing = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Dot grid", Modifier.weight(1f))
                    Switch(state.document.dots, { state.dots() })
                }
                OutlinedTextField(
                    hex,
                    { value ->
                        hex =
                            value.filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }.take(6)
                        if (hex.length == 6) state.color = (0xff000000L or hex.toLong(16)).toInt()
                    },
                    label = { Text("Custom color · hex") },
                    prefix = { Text("#") },
                    singleLine = true,
                )
                Spacer(Modifier.height(16.dp))
                Text("Grid shape", fontWeight = FontWeight.Medium)
                Stepper("Rows", state.rows) { state.rows = it }
                Stepper("Columns", state.cols) { state.cols = it }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        IconButton(enabled = value > 1, onClick = { onChange(value - 1) }) {
            Icon(Icons.Outlined.Remove, "Fewer $label")
        }
        Text("$value")
        IconButton(enabled = value < 30, onClick = { onChange(value + 1) }) {
            Icon(Icons.Outlined.Add, "More $label")
        }
    }
}

@Composable
private fun UndoRedoButtons(state: AppState, settle: () -> Unit) {
    // History is plain state; every edit, undo and redo advances revision. Camera moves do not.
    state.revision
    IconButton(
        enabled = state.history.canUndo,
        onClick = {
            settle()
            state.undo()
        },
    ) {
        Icon(Icons.AutoMirrored.Outlined.Undo, "Undo")
    }
    IconButton(
        enabled = state.history.canRedo,
        onClick = {
            settle()
            state.redo()
        },
    ) {
        Icon(Icons.AutoMirrored.Outlined.Redo, "Redo")
    }
}

/** Canvas overlays read the scene in their own scope, so panning does not recompose the editor. */
@Composable
private fun BoxScope.CanvasOverlays(
    state: AppState,
    canvas: () -> NotebookView?,
    onSelectionColor: () -> Unit,
) {
    val empty by remember(state) { derivedStateOf { state.document.items.isEmpty() } }
    val zoom by remember(state) { derivedStateOf { (state.document.camera.zoom * 100).toInt() } }
    // Selection details scan the scene only when something is selected and only recompose when
    // the answers change, not on every pan frame.
    val selectionHasText by remember(state) {
        derivedStateOf {
            val selected = state.selection
            selected.isNotEmpty() && state.document.items.any { it.id in selected && it.kind == "TEXT" }
        }
    }
    val selectionRecolorable by remember(state) {
        derivedStateOf {
            val selected = state.selection
            selected.isNotEmpty() &&
                state.document.items.any { it.id in selected && !it.locked && !it.image }
        }
    }
                if (empty)
                    Surface(
                        Modifier.align(Alignment.TopCenter).padding(24.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xffeef1e7),
                    ) {
                        Text(
                            "Write with your pen. Drag or flick with a finger. Pinch to zoom.",
                            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            fontSize = 12.sp,
                            color = Forest,
                        )
                    }
                Surface(
                    Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = Paper,
                    shadowElevation = 2.dp,
                    border = BorderStroke(1.dp, Color(0xffe0e4da)),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "$zoom%",
                            Modifier.padding(start = 16.dp, end = 4.dp),
                            fontSize = 12.sp,
                        )
                        IconButton(onClick = { canvas()?.fit() }) {
                            Icon(Icons.Outlined.CenterFocusStrong, "Fit all content")
                        }
                    }
                }
                if (state.selection.isNotEmpty())
                    Surface(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = Forest,
                        contentColor = Color.White,
                        shadowElevation = 3.dp,
                    ) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${state.selection.size} selected",
                                Modifier.padding(start = 16.dp),
                                fontSize = 12.sp,
                            )
                            if (selectionHasText) {
                                SelectionSizing.entries.forEach { mode ->
                                    TextButton(
                                        onClick = {
                                            canvas()?.settle()
                                            state.selectionSizing = mode
                                        },
                                        colors =
                                            ButtonDefaults.textButtonColors(
                                                contentColor =
                                                    if (state.selectionSizing == mode) Color.White
                                                    else Color.White.copy(alpha = .65f),
                                                containerColor =
                                                    if (state.selectionSizing == mode)
                                                        Color.White.copy(alpha = .18f)
                                                    else Color.Transparent,
                                            ),
                                    ) {
                                        Text(
                                            if (mode == SelectionSizing.RESIZE) "Resize"
                                            else "Scale"
                                        )
                                    }
                                }
                            }
                            IconButton(
                                enabled = selectionRecolorable,
                                onClick = {
                                    canvas()?.settle()
                                    onSelectionColor()
                                },
                            ) {
                                Icon(Icons.Outlined.Palette, "Change selection color")
                            }
                            IconButton(onClick = { state.deleteSelection() }) {
                                Icon(Icons.Outlined.DeleteOutline, "Delete selection")
                            }
                            IconButton(onClick = { state.selection = emptySet() }) {
                                Icon(Icons.Outlined.Close, "Clear selection")
                            }
                        }
                    }
}
