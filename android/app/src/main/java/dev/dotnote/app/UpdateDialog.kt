package dev.dotnote.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun UpdateDialog(beforeInstall: suspend () -> Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val updater = remember { AppUpdates(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Check for updates directly from Dotnote.") }
    var previews by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    var ready by remember { mutableStateOf<File?>(null) }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        status = if (context.packageManager.canRequestPackageInstalls())
            "Ready. Tap Install update to continue." else "Installation permission was not granted. You can try again."
    }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        status = "If you canceled installation, tap Install update to try again."
    }
    fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = e.message ?: "Update failed. Please try again." }
            finally { busy = false }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Dotnote version & updates") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Dotnote ${BuildConfig.VERSION_NAME} · Build ${BuildConfig.VERSION_CODE}")
                Text(status)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = previews, enabled = !busy, onCheckedChange = {
                        previews = it
                        update = null
                        ready = null
                        status = "Tap Check for updates."
                    })
                    Text("Include preview releases")
                }
                Text("Android will ask you to confirm installation. Your notes stay on this device.")
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                work {
                    val candidate = update
                    if (candidate == null) {
                        status = "Checking for updates…"
                        update = updater.check(previews)
                        status = update?.let { "Dotnote ${it.version} is available." }
                            ?: "No newer ${if (previews) "" else "stable "}release is available."
                    } else if (ready == null) {
                        status = "Downloading and verifying Dotnote ${candidate.version}…"
                        ready = updater.fetch(candidate)
                        status = "Update verified. Tap Install update to continue."
                    } else {
                        val apk = ready!!
                        updater.verify(apk, candidate)
                        check(beforeInstall()) { "Your note could not be saved. Please retry saving before installing." }
                        if (!context.packageManager.canRequestPackageInstalls()) {
                            status = "Allow updates from Dotnote in Android settings, then return here."
                            settings.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                        } else {
                            installer.launch(updater.installer(apk))
                        }
                    }
                }
            }) { Text(if (update == null) "Check for updates" else if (ready == null) "Download update" else "Install update") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (busy) "Cancel" else "Close") } },
    )
}
