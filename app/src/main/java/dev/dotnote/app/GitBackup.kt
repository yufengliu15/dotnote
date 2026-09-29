package dev.dotnote.app

import android.content.Context
import androidx.work.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object BackupScheduler {
    fun schedule(context: Context, id: String, immediate: Boolean = false) {
        val value = VaultCatalog(context).config(id)
        if (
            value.optString("repo").isBlank() ||
                (!immediate && !value.optBoolean("automatic", true))
        )
            return
        if (!immediate && value.optLong("revision") <= value.optLong("backedRevision", -1)) return
        val delay =
            if (immediate) 0L
            else
                backupDelayMillis(
                    value.optLong("editedAt", System.currentTimeMillis()),
                    value.optInt("minutes", 60).coerceIn(15, 360),
                    System.currentTimeMillis(),
                )
        val request =
            OneTimeWorkRequestBuilder<VaultBackupWorker>()
                .setInputData(workDataOf("vault" to id, "manual" to immediate))
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (value.optBoolean("unmetered")) NetworkType.UNMETERED
                            else NetworkType.CONNECTED
                        )
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("vault-backup-$id", ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context, id: String) {
        WorkManager.getInstance(context).cancelUniqueWork("vault-backup-$id")
    }
}

class VaultBackupWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("vault") ?: return Result.failure()
        val catalog = VaultCatalog(applicationContext)
        return try {
            val c = catalog.config(id)
            if (c.optString("repo").isBlank()) return Result.success()
            if (!inputData.getBoolean("manual", false)) {
                if (!c.optBoolean("automatic", true)) return Result.success()
                // Recheck persisted edit time after process death and scheduling races.
                if (
                    backupDelayMillis(
                        c.optLong("editedAt"),
                        c.optInt("minutes", 60),
                        System.currentTimeMillis(),
                    ) > 0
                )
                    return Result.retry()
            }
            GitBackup(applicationContext).backup(id)
            Result.success()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            catalog.update(id) { it.put("status", e.message ?: "Backup failed") }
            if (
                e is RemoteChanged ||
                    e is IllegalArgumentException ||
                    (e is GitHubFailure && e.status in setOf(401, 404, 422))
            )
                Result.failure()
            else if (runAttemptCount < 6) Result.retry() else Result.failure()
        }
    }
}

fun managedVaultPath(path: String): Boolean =
    safeRelative(path) &&
        (path == ".dotnote/vault.json" ||
            path == ".dotnote/settings.json" ||
            (path.startsWith("attachments/") &&
                path.removePrefix("attachments/").matches(Regex("[a-f0-9-]+\\.pdf"))) ||
            (!path.startsWith('.') &&
                !path.startsWith("attachments/") &&
                (path.endsWith(".dotnote") || path.endsWith("/.folder.json"))))

class GitBackup(
    private val context: Context,
    private val apiFactory: (String) -> GitHub = { GitHub(it) },
    private val tokenProvider: () -> String = { GitHubAuth.token(context) },
) {
    private val catalog = VaultCatalog(context)

    suspend fun connect(id: String, name: String) =
        withContext(Dispatchers.IO) {
            VaultLocks.upload(id).withLock {
                val api = apiFactory(tokenProvider())
                val repo = api.repo(repoName(name))
                require(repo.writable) {
                    "This account needs Contents write access to back up to this repository."
                }
                val head = api.head(repo.name, repo.branch)
                val entries = head?.let { api.tree(repo.name, it).second } ?: emptyList()
                require(entries.none { it.path == ".dotnote/vault.json" }) {
                    "This repository already contains a vault. Use Restore from GitHub to open it safely."
                }
                require(entries.all { it.path in setOf("README.md", "LICENSE", ".gitignore") }) {
                    "Choose an empty repository for this vault. Keep application source code in a separate repository."
                }
                catalog.update(id) {
                    it.put("repo", repo.name)
                        .put("branch", repo.branch)
                        .put("base", head ?: "")
                        .put("private", repo.privateRepo)
                        .put("automatic", true)
                        .put("minutes", it.optInt("minutes", 60))
                        .put("status", "Connected; backup pending")
                        .put("editedAt", System.currentTimeMillis())
                        .put("backedRevision", -1)
                    it.remove("pending")
                }
                BackupScheduler.schedule(context, id)
            }
        }

    suspend fun disconnect(id: String) =
        withContext(Dispatchers.IO) {
            BackupScheduler.cancel(context, id)
            VaultLocks.upload(id).withLock {
                catalog.update(id) {
                    it.remove("repo")
                    it.remove("base")
                    it.remove("pending")
                    it.put("status", "Repository disconnected")
                }
            }
        }

    suspend fun backup(id: String) =
        withContext(Dispatchers.IO) {
            VaultLocks.upload(id).withLock {
                val config = catalog.config(id)
                val repo = repoName(config.getString("repo"))
                val branch = config.getString("branch")
                val api = apiFactory(tokenProvider())
                catalog.update(id) { it.put("status", "Backing up…") }
                var head = api.head(repo, branch)
                // A previous request may have succeeded remotely before Android stopped it.
                if (
                    config.optString("pending").isNotBlank() && head == config.optString("pending")
                ) {
                    catalog.update(id) {
                        it.put("base", head)
                            .put("backedRevision", it.optLong("pendingRevision"))
                            .put("lastBackup", System.currentTimeMillis())
                        it.remove("pending")
                    }
                }
                var current = catalog.config(id)
                if ((head ?: "") != current.optString("base")) throw RemoteChanged()
                val root = catalog.root(id)
                val staging = File(context.cacheDir, "upload-${newId()}").apply { mkdirs() }
                try {
                    var revision = 0L
                    val files =
                        VaultLocks.forRoot(root).withLock {
                            val vault = VaultFiles(root)
                            vault.read()
                            revision = catalog.config(id).optLong("revision")
                            vault.snapshot(staging)
                        }
                    files.values.forEach {
                        require(it.length() < 100L * 1024 * 1024) {
                            "${it.name} exceeds GitHub's 100 MiB limit. No notes were omitted or committed."
                        }
                    }
                    // Git data endpoints cannot initialize a completely empty repository.
                    if (head == null) {
                        val content =
                            android.util.Base64.encodeToString(
                                "# Dotnote vault\n".toByteArray(),
                                android.util.Base64.NO_WRAP,
                            )
                        val response =
                            JSONObject(
                                api.request(
                                    "/repos/$repo/contents/README.md",
                                    "PUT",
                                    JSONObject()
                                        .put("message", "Initialize Dotnote vault")
                                        .put("content", content)
                                        .put("branch", branch),
                                )
                            )
                        head = response.getJSONObject("commit").getString("sha")
                        catalog.update(id) { it.put("base", head) }
                    }
                    val (treeSha, remote) = api.tree(repo, head!!)
                    val remoteMap = remote.associateBy { it.path }
                    val changes = JSONArray()
                    files.forEach { (path, file) ->
                        if (digest(file, true) != remoteMap[path]?.sha)
                            changes.put(
                                JSONObject()
                                    .put("path", path)
                                    .put("mode", "100644")
                                    .put("type", "blob")
                                    .put("sha", api.upload(repo, file))
                            )
                    }
                    remote
                        .filter { managedVaultPath(it.path) && it.path !in files }
                        .forEach {
                            changes.put(
                                JSONObject()
                                    .put("path", it.path)
                                    .put("mode", "100644")
                                    .put("type", "blob")
                                    .put("sha", JSONObject.NULL)
                            )
                        }
                    if (changes.length() > 0) {
                        val tree =
                            JSONObject(
                                    api.request(
                                        "/repos/$repo/git/trees",
                                        "POST",
                                        JSONObject().put("base_tree", treeSha).put("tree", changes),
                                    )
                                )
                                .getString("sha")
                        val commit =
                            JSONObject(
                                    api.request(
                                        "/repos/$repo/git/commits",
                                        "POST",
                                        JSONObject()
                                            .put("message", "Back up Dotnote vault")
                                            .put("tree", tree)
                                            .put("parents", JSONArray(listOf(head))),
                                    )
                                )
                                .getString("sha")
                        if (api.head(repo, branch) != head) throw RemoteChanged()
                        catalog.update(id) {
                            it.put("pending", commit).put("pendingRevision", revision)
                        }
                        try {
                            api.request(
                                "/repos/$repo/git/refs/heads/${urlPart(branch)}",
                                "PATCH",
                                JSONObject().put("sha", commit).put("force", false),
                            )
                        } catch (e: GitHubFailure) {
                            if (e.status == 422) throw RemoteChanged() else throw e
                        }
                        head = commit
                    }
                    catalog.update(id) {
                        it.put("base", head)
                            .put("backedRevision", revision)
                            .put("lastBackup", System.currentTimeMillis())
                            .put(
                                "status",
                                if (it.optLong("revision") > revision)
                                    "New edits waiting for backup"
                                else "All changes backed up",
                            )
                        it.remove("pending")
                        it.remove("pendingRevision")
                    }
                } finally {
                    staging.deleteRecursively()
                }
            }
        }

    suspend fun restore(name: String): VaultInfo =
        withContext(Dispatchers.IO) {
            val api = apiFactory(tokenProvider())
            val repo = api.repo(repoName(name))
            val head = api.head(repo.name, repo.branch) ?: error("Repository is empty")
            val entries = api.tree(repo.name, head).second
            require(entries.any { it.path == ".dotnote/vault.json" }) {
                "This repository is not a Dotnote vault"
            }
            require(
                entries.all {
                    managedVaultPath(it.path) ||
                        it.path in setOf("README.md", "LICENSE", ".gitignore")
                }
            ) {
                "Repository contains unexpected files"
            }
            val managed = entries.filter { managedVaultPath(it.path) }
            require(
                managed.all { it.size in 0 until 100L * 1024 * 1024 } &&
                    managed.sumOf { it.size } <= 1024L * 1024 * 1024
            ) {
                "Vault exceeds supported backup size"
            }
            val stage = File(catalog.directory, ".restore-${newId()}").apply { mkdirs() }
            try {
                managed.forEach { api.download(repo.name, it, File(stage, it.path)) }
                val vault = catalog.publish(stage)
                catalog.update(vault.localId) {
                    it.put("repo", repo.name)
                        .put("branch", repo.branch)
                        .put("private", repo.privateRepo)
                        .put("base", head)
                        .put("automatic", repo.writable)
                        .put("minutes", 60)
                        .put("revision", 0)
                        .put("backedRevision", 0)
                        .put("lastBackup", System.currentTimeMillis())
                        .put("status", "Restored from GitHub")
                }
                vault
            } finally {
                stage.deleteRecursively()
            }
        }
}
