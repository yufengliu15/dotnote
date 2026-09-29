package dev.dotnote.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun NewNoteDialog(state: AppState, onDismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var vault by rememberSaveable { mutableStateOf(state.store.vaultId) }
    var folder by rememberSaveable { mutableStateOf(state.folderId) }
    var vaultMenu by remember { mutableStateOf(false) }
    var vaults by remember { mutableStateOf(emptyList<VaultInfo>()) }
    var folders by remember { mutableStateOf(emptyList<Folder>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(vault) {
        loading = true
        error = null
        try {
            vaults = withContext(Dispatchers.IO) { state.catalog.list() }
            folders = state.vaultFolders(vault)
            if (folder != null && folders.none { it.id == folder }) folder = null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = e.message ?: "Could not open vault"
        } finally {
            loading = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    title,
                    { title = it },
                    label = { Text("Note title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box {
                    OutlinedButton(
                        onClick = { vaultMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Inventory2, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            vaults.find { it.localId == vault }?.name ?: "Vault",
                            Modifier.weight(1f),
                        )
                        Icon(Icons.Outlined.ExpandMore, "Choose vault")
                    }
                    DropdownMenu(vaultMenu, { vaultMenu = false }) {
                        vaults.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.name) },
                                onClick = {
                                    vault = item.localId
                                    folder = null
                                    vaultMenu = false
                                },
                            )
                        }
                    }
                }
                Text(
                    "Folder: ${folderPath(folder, folders)}",
                    style = MaterialTheme.typography.labelLarge,
                )
                if (folder != null) {
                    Row {
                        TextButton(onClick = { folder = null }) { Text("Vault root") }
                        TextButton(
                            onClick = { folder = folders.find { it.id == folder }?.parentId }
                        ) {
                            Text("↑ Parent folder")
                        }
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    items(folders.filter { it.parentId == folder }, key = { it.id }) { child ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { folder = child.id }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Folder, null)
                            Spacer(Modifier.width(12.dp))
                            Text(child.name, Modifier.weight(1f))
                            Icon(Icons.Outlined.ChevronRight, null)
                        }
                    }
                }
                Text(
                    "The note will be created in the folder shown above.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && !loading && error == null && !state.busy,
                onClick = {
                    state.createNote(title, folder, vault)
                    onDismiss()
                },
            ) {
                Text("Create note")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
