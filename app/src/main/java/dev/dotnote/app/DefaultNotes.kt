package dev.dotnote.app

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal class DefaultNotes(private val context: Context) {
    val available: Boolean
        get() =
            Build.VERSION.SDK_INT >= 34 &&
                context
                    .getSystemService(RoleManager::class.java)
                    .isRoleAvailable(RoleManager.ROLE_NOTES)

    val held: Boolean
        get() =
            available &&
                context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_NOTES)

    fun request(): Intent? =
        if (available)
        // AOSP marks NOTES as non-requestable. Use the public default-app settings screen,
        // where the user can select Dotnote even on OEMs that reject a role-request dialog.
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        else null
}

@Composable
internal fun DefaultNotesMenuItem(onDismissMenu: () -> Unit) {
    val context = LocalContext.current
    val role = remember(context) { DefaultNotes(context) }
    var held by remember { mutableStateOf(role.held) }
    var explanation by remember { mutableStateOf<String?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            held = role.held
        }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) held = role.held
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DropdownMenuItem(
        text = { Text(if (held) "Default notes app: Dotnote" else "Set as default notes app") },
        onClick = {
            // The item leaves composition when its dropdown closes, so the role request must be
            // launched before dismissing the menu. Unsupported-device explanations keep it open.
            val request = role.request()
            if (request == null) {
                explanation =
                    "This device doesn't offer Android's default notes app setting. Dotnote still works normally."
            } else if (held) {
                onDismissMenu()
            } else {
                launcher.launch(request)
                onDismissMenu()
            }
        },
    )
    explanation?.let { text ->
        AlertDialog(
            onDismissRequest = {
                explanation = null
                onDismissMenu()
            },
            title = { Text("Default notes app") },
            text = { Text(text) },
            confirmButton = {
                TextButton(
                    onClick = {
                        explanation = null
                        onDismissMenu()
                    }
                ) {
                    Text("Done")
                }
            },
        )
    }
}
