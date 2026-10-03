package dev.dotnote.app

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

object VaultLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun forRoot(root: File) = locks.getOrPut(root.absolutePath) { Mutex() }

    private val uploads = ConcurrentHashMap<String, Mutex>()

    fun upload(id: String) = uploads.getOrPut(id) { Mutex() }
}

data class VaultInfo(val localId: String, val name: String)

class VaultCatalog(val context: Context) {
    val directory = File(context.filesDir, "vaults").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("vaults", 0)

    fun root(id: String): File {
        require(validId(id))
        return File(directory, id)
    }

    fun list(): List<VaultInfo> =
        directory
            .listFiles()
            ?.filter { File(it, ".dotnote/vault.json").isFile && validId(it.name) }
            ?.mapNotNull {
                runCatching {
                        VaultInfo(
                            it.name,
                            JSONObject(File(it, ".dotnote/vault.json").readText()).getString("name"),
                        )
                    }
                    .getOrNull()
            }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun selected(): String =
        synchronized(lock) {
            prefs.getString("selected", null)?.takeIf {
                File(root(it), ".dotnote/vault.json").isFile
            }
                ?: run {
                    val id =
                        list().firstOrNull()?.localId
                            ?: create("My notes").localId.also {
                                prefs.edit().putString("legacyTarget", it).commit()
                            }
                    select(id)
                    id
                }
        }

    fun select(id: String) {
        require(File(root(id), ".dotnote/vault.json").isFile)
        prefs.edit().putString("selected", id).commit()
    }

    fun create(name: String): VaultInfo {
        val id = newId()
        val clean = name.trim().ifEmpty { "Untitled vault" }.take(120)
        VaultFiles(root(id)).create(newId(), clean)
        return VaultInfo(id, clean)
    }

    suspend fun rename(id: String, name: String) =
        withContext(Dispatchers.IO) {
            val clean = name.trim().take(120)
            require(clean.isNotEmpty()) { "Enter a vault name" }
            VaultLocks.forRoot(root(id)).withLock {
                val manifest = VaultFiles(root(id)).manifest
                val data = JSONObject(manifest.readText())
                if (data.getString("name") != clean) {
                    data.put("name", clean)
                    atomicText(manifest, data.toString(2))
                    edited(id)
                    NoteWidgets.refresh(context)
                }
            }
        }

    fun isLegacyTarget(id: String) = prefs.getString("legacyTarget", null) == id

    fun config(id: String): JSONObject =
        synchronized(lock) { JSONObject(prefs.getString("config_$id", "{}")!!) }

    fun update(id: String, change: (JSONObject) -> Unit): JSONObject =
        synchronized(lock) {
            val value = config(id)
            change(value)
            check(prefs.edit().putString("config_$id", value.toString()).commit()) {
                "Could not save vault settings"
            }
            value
        }

    fun edited(id: String) {
        update(id) {
            it.put("editedAt", System.currentTimeMillis())
                .put("revision", it.optLong("revision") + 1)
                .put("status", "Changes waiting for backup")
        }
        BackupScheduler.schedule(context, id)
    }

    fun publish(staging: File, name: String? = null): VaultInfo {
        val id = newId()
        // Validation records survive the rename, so the first open skips re-validating.
        val files = VaultFiles(staging, scanCache(context, root(id)))
        files.scan()
        val meta = JSONObject(files.manifest.readText())
        if (name != null) {
            meta.put("name", name.take(120))
            atomicText(files.manifest, meta.toString(2))
        }
        require(staging.renameTo(root(id))) { "Could not finish importing vault" }
        return VaultInfo(id, meta.getString("name"))
    }

    companion object {
        private val lock = Any()
    }
}

fun backupDelayMillis(editedAt: Long, minutes: Int, now: Long): Long {
    require(minutes in 15..360)
    return (editedAt + minutes * 60_000L - now).coerceAtLeast(0)
}
