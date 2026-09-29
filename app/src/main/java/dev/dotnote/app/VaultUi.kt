package dev.dotnote.app

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
fun VaultManagerDialog(state: AppState, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var github by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val importFolder =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
            it?.let(state::importVault)
        }
    val exportFolder =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
            it?.let(state::exportVault)
        }
    var list by remember { mutableStateOf(emptyList<VaultInfo>()) }
    LaunchedEffect(state.vaultVersion) {
        list = withContext(Dispatchers.IO) { state.catalog.list() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your vaults") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                list.forEach { vault ->
                    OutlinedCard(
                        Modifier.fillMaxWidth().clickable { state.switchVault(vault.localId) }
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (vault.localId == state.store.vaultId) Icons.Outlined.CheckCircle
                                else Icons.Outlined.Folder,
                                null,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(vault.name, Modifier.weight(1f))
                        }
                    }
                }
                HorizontalDivider()
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text("New vault name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        state.createVault(name)
                        name = ""
                    },
                    enabled = name.isNotBlank(),
                ) {
                    Text("Create vault")
                }
                TextButton(onClick = { importFolder.launch(null) }) {
                    Icon(Icons.Outlined.FolderOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Import vault folder")
                }
                TextButton(onClick = { exportFolder.launch(null) }) {
                    Icon(Icons.Outlined.SaveAlt, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Export current vault folder")
                }
                TextButton(onClick = { github = true }) {
                    Icon(Icons.Outlined.CloudUpload, null)
                    Spacer(Modifier.width(8.dp))
                    Text("GitHub backup & restore")
                }
                Text(
                    "Vaults also appear under Dotnote vaults in Android's Files app, where you can copy their files.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
    if (github) GitHubSettings(state) { github = false }
}

@Composable
fun GitHubSettings(state: AppState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf("") }
    var clientId by remember {
        mutableStateOf(
            context
                .getSharedPreferences("github-setup", 0)
                .getString("clientId", DEFAULT_GITHUB_CLIENT_ID) ?: DEFAULT_GITHUB_CLIENT_ID
        )
    }
    var advanced by remember { mutableStateOf(false) }
    var accessToken by remember { mutableStateOf("") }
    var device by remember { mutableStateOf<DeviceLogin?>(null) }
    var authJob by remember { mutableStateOf<Job?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var repos by remember { mutableStateOf<List<GitRepo>>(emptyList()) }
    var repository by remember { mutableStateOf("") }
    var config by remember { mutableStateOf(JSONObject()) }
    var minutes by remember { mutableStateOf("60") }
    var choosing by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf("backup") }
    val id = state.store.vaultId
    suspend fun refresh() {
        val result =
            withContext(Dispatchers.IO) {
                Credentials(context).read().optString("login") to state.catalog.config(id)
            }
        account = result.first
        config = result.second
    }
    fun run(block: suspend () -> Unit) {
        scope.launch {
            working = true
            error = null
            try {
                block()
                refresh()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message ?: "Operation failed"
            } finally {
                working = false
            }
        }
    }
    LaunchedEffect(id) {
        try {
            refresh()
            minutes = config.optInt("minutes", 60).toString()
        } catch (e: Exception) {
            error = e.message
        }
        while (true) {
            delay(1500)
            config = withContext(Dispatchers.IO) { state.catalog.config(id) }
        }
    }
    DisposableEffect(Unit) { onDispose { authJob?.cancel() } }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(20.dp).heightIn(max = 700.dp),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(
                Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "GitHub backup",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, "Close GitHub settings")
                    }
                }
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (account.isBlank()) {
                    Text("Connect GitHub to back up private or public vault repositories.")
                    Button(
                        enabled = clientId.isNotBlank() && !working,
                        onClick = {
                            authJob =
                                scope.launch {
                                    working = true
                                    error = null
                                    try {
                                        context
                                            .getSharedPreferences("github-setup", 0)
                                            .edit()
                                            .putString("clientId", clientId)
                                            .apply()
                                        val response =
                                            withContext(Dispatchers.IO) {
                                                GitHub("")
                                                    .oauth(
                                                        "device/code",
                                                        mapOf(
                                                            "client_id" to clientId,
                                                            "scope" to "repo",
                                                        ),
                                                    )
                                            }
                                        require(response.has("device_code")) {
                                            "GitHub rejected this client ID. Enable device authorization on the app registration."
                                        }
                                        val login =
                                            DeviceLogin(
                                                response.getString("device_code"),
                                                response.getString("user_code"),
                                                response.getString("verification_uri"),
                                                response.optInt("interval", 5),
                                                response.getInt("expires_in"),
                                            )
                                        require(login.uri == "https://github.com/login/device") {
                                            "Unexpected sign-in URL"
                                        }
                                        device = login
                                        val deadline =
                                            System.currentTimeMillis() + login.expires * 1000L
                                        var interval = login.interval.coerceAtLeast(5)
                                        while (System.currentTimeMillis() < deadline) {
                                            delay(interval * 1000L)
                                            val token =
                                                withContext(Dispatchers.IO) {
                                                    GitHub("")
                                                        .oauth(
                                                            "oauth/access_token",
                                                            mapOf(
                                                                "client_id" to clientId,
                                                                "device_code" to login.code,
                                                                "grant_type" to
                                                                    "urn:ietf:params:oauth:grant-type:device_code",
                                                            ),
                                                        )
                                                }
                                            if (token.has("access_token")) {
                                                withContext(Dispatchers.IO) {
                                                    val loginName =
                                                        GitHub(token.getString("access_token"))
                                                            .user()
                                                    val stored =
                                                        JSONObject()
                                                            .put("clientId", clientId)
                                                            .put("login", loginName)
                                                    GitHubAuth.saveResponse(stored, token)
                                                    Credentials(context).save(stored)
                                                }
                                                device = null
                                                refresh()
                                                return@launch
                                            }
                                            when (token.optString("error")) {
                                                "authorization_pending" -> Unit
                                                "slow_down" -> interval += 5
                                                else ->
                                                    error(
                                                        "GitHub authorization expired or was declined. Try connecting again."
                                                    )
                                            }
                                        }
                                        error("GitHub authorization timed out. Try again.")
                                    } catch (e: Exception) {
                                        if (e is CancellationException) throw e
                                        error = e.message
                                    } finally {
                                        working = false
                                        device = null
                                    }
                                }
                        },
                    ) {
                        Text("Connect with GitHub")
                    }
                    device?.let { login ->
                        Text(
                            "Enter this code on GitHub: ${login.userCode}",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        TextButton(
                            onClick = {
                                context
                                    .getSystemService(android.content.ClipboardManager::class.java)
                                    .setPrimaryClip(
                                        android.content.ClipData.newPlainText(
                                            "GitHub code",
                                            login.userCode,
                                        )
                                    )
                            }
                        ) {
                            Text("Copy code")
                        }
                        TextButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(login.uri))
                                )
                            }
                        ) {
                            Text("Open GitHub authorization")
                        }
                        TextButton(onClick = { authJob?.cancel() }) { Text("Cancel sign-in") }
                    }
                    TextButton(onClick = { advanced = !advanced }) {
                        Text(if (advanced) "Hide advanced setup" else "Advanced setup")
                    }
                    if (advanced) {
                        OutlinedTextField(
                            clientId,
                            { clientId = it.trim() },
                            label = { Text("GitHub app client ID") },
                            supportingText = {
                                Text(
                                    "One-time app setup. Device authorization must be enabled; no client secret is needed here."
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        HorizontalDivider()
                        Text(
                            "Or connect with a fine-grained personal access token",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "Grant Contents read/write only for your vault repositories. Read-only access can restore a vault.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            accessToken,
                            { accessToken = it.trim() },
                            label = { Text("GitHub access token") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            enabled = accessToken.isNotBlank() && !working,
                            onClick = {
                                run {
                                    val token = accessToken
                                    val login =
                                        withContext(Dispatchers.IO) {
                                            GitHub(token).user().also {
                                                Credentials(context)
                                                    .save(
                                                        JSONObject()
                                                            .put("token", token)
                                                            .put("login", it)
                                                    )
                                            }
                                        }
                                    account = login
                                    accessToken = ""
                                }
                            },
                        ) {
                            Text("Connect token")
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Connected as $account", Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                run {
                                    withContext(Dispatchers.IO) {
                                        Credentials(context).clear()
                                        state.catalog.list().forEach {
                                            BackupScheduler.cancel(context, it.localId)
                                        }
                                    }
                                    account = ""
                                }
                            }
                        ) {
                            Text("Disconnect")
                        }
                    }
                    Text(
                        "Current vault: ${state.catalog.list().find { it.localId == id }?.name ?: "Vault"}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (config.optString("repo").isNotBlank()) {
                        Text(
                            config.getString("repo") +
                                if (config.optBoolean("private")) " · Private" else " · Public"
                        )
                        Text(config.optString("status", "Ready"))
                        if (config.optLong("lastBackup") > 0)
                            Text(
                                "Last successful backup: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(config.getLong("lastBackup")))}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Automatic backups", Modifier.weight(1f))
                            Switch(
                                config.optBoolean("automatic", true),
                                { enabled ->
                                    run {
                                        withContext(Dispatchers.IO) {
                                            state.catalog.update(id) {
                                                it.put("automatic", enabled)
                                            }
                                            if (enabled) BackupScheduler.schedule(context, id)
                                            else BackupScheduler.cancel(context, id)
                                        }
                                    }
                                },
                            )
                        }
                        OutlinedTextField(
                            minutes,
                            { minutes = it.filter(Char::isDigit).take(3) },
                            label = { Text("Minutes after the last edit") },
                            supportingText = {
                                Text("15–360 minutes (6 hours). Each edit restarts the delay.")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            enabled = minutes.toIntOrNull() in 15..360,
                            onClick = {
                                run {
                                    withContext(Dispatchers.IO) {
                                        state.catalog.update(id) {
                                            it.put("minutes", minutes.toInt())
                                        }
                                        BackupScheduler.schedule(context, id)
                                    }
                                }
                            },
                        ) {
                            Text("Save backup delay")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Unmetered networks only", Modifier.weight(1f))
                            Switch(
                                config.optBoolean("unmetered"),
                                { enabled ->
                                    run {
                                        withContext(Dispatchers.IO) {
                                            state.catalog.update(id) {
                                                it.put("unmetered", enabled)
                                            }
                                            BackupScheduler.schedule(context, id)
                                        }
                                    }
                                },
                            )
                        }
                        Text(
                            "Android may delay background work to save battery. Your notes always save locally first.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            enabled = !working,
                            onClick = {
                                run {
                                    if (state.flush()) {
                                        state.saveWriting()
                                        withContext(Dispatchers.IO) {
                                            state.catalog.update(id) {
                                                it.put("status", "Backup queued")
                                            }
                                            BackupScheduler.schedule(context, id, true)
                                        }
                                    }
                                }
                            },
                        ) {
                            Text("Back up now")
                        }
                        TextButton(onClick = { run { GitBackup(context).disconnect(id) } }) {
                            Text("Disconnect this repository")
                        }
                    }
                    OutlinedButton(
                        enabled = !working,
                        onClick = {
                            action = "backup"
                            choosing = true
                            run {
                                repos =
                                    withContext(Dispatchers.IO) {
                                        GitHub(GitHubAuth.token(context)).repos()
                                    }
                            }
                        },
                    ) {
                        Text("Choose backup repository")
                    }
                    OutlinedButton(
                        enabled = !working,
                        onClick = {
                            action = "restore"
                            choosing = true
                            run {
                                repos =
                                    withContext(Dispatchers.IO) {
                                        GitHub(GitHubAuth.token(context)).repos()
                                    }
                            }
                        },
                    ) {
                        Text("Restore vault from GitHub")
                    }
                    if (choosing) {
                        HorizontalDivider()
                        Text(
                            if (action == "restore") "Restore as a new vault"
                            else "Use an empty repository for this vault",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        OutlinedTextField(
                            repository,
                            { repository = it },
                            label = { Text("owner/repository or GitHub URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            enabled = repository.isNotBlank() && !working,
                            onClick = {
                                if (action == "restore") {
                                    state.restoreRepository(repository)
                                    onDismiss()
                                } else
                                    run {
                                        GitBackup(context).connect(id, repository)
                                        choosing = false
                                    }
                            },
                        ) {
                            Text(if (action == "restore") "Restore vault" else "Connect repository")
                        }
                        repos.forEach { repo ->
                            TextButton(
                                onClick = { repository = repo.name },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    repo.name + if (repo.privateRepo) " · Private" else " · Public"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
