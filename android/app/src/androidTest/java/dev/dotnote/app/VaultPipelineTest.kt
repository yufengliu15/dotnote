package dev.dotnote.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultPipelineTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun filesRebuildIndexAndPreserveMovedNotes() = runBlocking {
        val dbName = "vault-test-${newId()}.db"
        val store = Store(context, dbName)
        try {
            val parent = Folder(name = "Physics")
            val child = Folder(parentId = parent.id, name = "Lectures")
            store.dao.put(parent)
            store.dao.put(child)
            val note =
                Note(
                    folderId = child.id,
                    title = "Lecture",
                    document =
                        DocumentCodec.encode(
                            Document(
                                listOf(
                                    Item(kind = "SQUARE", points = listOf(Pt(0f, 0f), Pt(40f, 40f)))
                                )
                            )
                        ),
                )
            store.dao.put(note)
            assertEquals(
                1,
                store.root.walkTopDown().count { it.isFile && it.extension == "dotnote" },
            )
            store.moveFolder(child, null)
            store.dao.renameNote(note.id, "New title")
            assertEquals(
                1,
                store.root.walkTopDown().count { it.isFile && it.extension == "dotnote" },
            )
            val saved = store.files.read()
            assertNull(saved.first.find { it.id == child.id }!!.parentId)
            assertEquals("New title", saved.second.single().title)
            // Corrupt only the cache. Files must restore the entire library.
            store.db.dao().clearNotes()
            store.db.dao().clearFolders()
            store.reload()
            assertEquals("New title", store.dao.note(note.id)!!.title)
            assertEquals(2, store.dao.allFolders().size)
            val stage = File(context.cacheDir, "snapshot-${newId()}").apply { mkdirs() }
            try {
                store.snapshot(stage)
                assertEquals(1, VaultFiles(stage).read().second.size)
            } finally {
                stage.deleteRecursively()
            }
            store.dao.deleteNote(note.id)
            assertTrue(store.dao.allNotes().isEmpty())
            assertTrue(File(store.root, ".dotnote/trash/${note.id}.dotnote").isFile)
        } finally {
            store.db.close()
            context.deleteDatabase(dbName)
            store.root.deleteRecursively()
        }
    }

    @Test
    fun interruptedFileTransactionReplaysBeforeReading() {
        val root = File(context.cacheDir, "journal-${newId()}").apply { mkdirs() }
        try {
            val files = VaultFiles(root)
            files.create(newId(), "Journal")
            val note = Note(title = "Recovered")
            val json =
                JSONObject()
                    .put("format", "dotnote")
                    .put("version", 1)
                    .put("id", note.id)
                    .put("title", note.title)
                    .put("modified", note.modified)
                    .put("document", JSONObject(note.document))
                    .toString()
            atomicText(
                File(root, ".dotnote/transaction.json"),
                JSONObject()
                    .put("writes", JSONObject().put("Recovered.dotnote", json))
                    .put("deletes", JSONArray())
                    .toString(),
            )
            assertEquals("Recovered", files.read().second.single().title)
            assertFalse(File(root, ".dotnote/transaction.json").exists())
            atomicText(
                File(root, ".dotnote/transaction.json"),
                JSONObject()
                    .put("writes", JSONObject().put("../escape", "bad"))
                    .put("deletes", JSONArray())
                    .toString(),
            )
            assertTrue(runCatching { files.read() }.isFailure)
            assertFalse(File(root.parentFile, "escape").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun androidFilesProviderImportsNestedVaultWithoutChangingSource() = runBlocking {
        val catalog = VaultCatalog(context)
        val vault = catalog.create("Portable vault")
        val files = VaultFiles(catalog.root(vault.localId))
        val folder = Folder(name = "Folder")
        val first = Note(id = "aaaaaaaa-1111", folderId = folder.id, title = "Same title")
        val second = Note(id = "aaaaaaaa-2222", folderId = folder.id, title = "Same title")
        var imported: VaultInfo? = null
        try {
            files.read()
            files.replace(listOf(folder), listOf(first, second))
            assertEquals(2, files.read().second.size)
            val tree =
                android.provider.DocumentsContract.buildTreeDocumentUri(
                    "dev.dotnote.app.vaults",
                    vault.localId,
                )
            imported = VaultTransfer.import(context, tree)
            assertNotEquals(vault.localId, imported!!.localId)
            val copy = VaultFiles(catalog.root(imported!!.localId)).read()
            assertEquals(2, copy.second.size)
            assertEquals(folder.id, copy.first.single().id)
            assertEquals(2, files.read().second.size)
        } finally {
            catalog.root(vault.localId).deleteRecursively()
            imported?.let { catalog.root(it.localId).deleteRecursively() }
        }
    }

    private class FakeGitHub : GitHub("") {
        var currentHead: String? = "initial"
        var uploadCount = 0
        var commitCount = 0
        var loseCommitResponse = false
        var changeHeadBeforePush = false
        val bytes = mutableMapOf<String, ByteArray>()
        val trees = mutableMapOf("initial" to emptyMap<String, GitEntry>())
        val commits = mutableMapOf("initial" to "initial")

        override fun repo(name: String) = GitRepo(name, "main", true, true)

        override fun head(repo: String, branch: String) = currentHead

        override fun tree(repo: String, commit: String): Pair<String, List<GitEntry>> {
            val tree = commits[commit] ?: error("Unknown commit")
            return tree to trees.getValue(tree).values.toList()
        }

        override fun upload(repo: String, file: File): String =
            digest(file, true).also {
                uploadCount++
                bytes[it] = file.readBytes()
            }

        override fun download(repo: String, entry: GitEntry, destination: File) {
            destination.parentFile!!.mkdirs()
            destination.writeBytes(bytes.getValue(entry.sha))
            assertEquals(entry.sha, digest(destination, true))
        }

        override fun request(path: String, method: String, body: JSONObject?): String {
            val data = requireNotNull(body)
            return when {
                path.endsWith("/git/trees") -> {
                    val map = trees.getValue(data.getString("base_tree")).toMutableMap()
                    val a = data.getJSONArray("tree")
                    for (i in 0 until a.length()) {
                        val item = a.getJSONObject(i)
                        val name = item.getString("path")
                        if (item.has("content")) {
                            // GitHub creates the blob from inline UTF-8 content.
                            val data = item.getString("content").toByteArray()
                            val file = File.createTempFile("blob", null)
                            file.writeBytes(data)
                            val sha = digest(file, true)
                            file.delete()
                            bytes[sha] = data
                            map[name] = GitEntry(name, sha, data.size.toLong())
                        } else if (item.isNull("sha")) map.remove(name)
                        else {
                            val sha = item.getString("sha")
                            map[name] = GitEntry(name, sha, bytes.getValue(sha).size.toLong())
                        }
                    }
                    val id = "tree-${newId()}"
                    trees[id] = map
                    JSONObject().put("sha", id).toString()
                }
                path.endsWith("/git/commits") -> {
                    commitCount++
                    val id = "commit-$commitCount"
                    commits[id] = data.getString("tree")
                    if (changeHeadBeforePush) {
                        currentHead = "other-device"
                        changeHeadBeforePush = false
                    }
                    JSONObject().put("sha", id).toString()
                }
                path.contains("/git/refs/heads/") -> {
                    assertFalse(data.getBoolean("force"))
                    currentHead = data.getString("sha")
                    if (loseCommitResponse) {
                        loseCommitResponse = false
                        throw IOException("Connection dropped after commit")
                    }
                    "{}"
                }
                else -> error("Unexpected API call $path")
            }
        }
    }

    @Test
    fun gitBackupRestoreNoChangeAndConflictRecovery() = runBlocking {
        val catalog = VaultCatalog(context)
        val vault = catalog.create("Git protocol test")
        val root = catalog.root(vault.localId)
        val files = VaultFiles(root)
        val api = FakeGitHub()
        val backup = GitBackup(context, { api }, { "test-token" })
        var restored: VaultInfo? = null
        try {
            files.read()
            files.writeNote(Note(title = "First note"))
            catalog.update(vault.localId) {
                it.put("repo", "owner/vault")
                    .put("branch", "main")
                    .put("base", "initial")
                    .put("revision", 1)
                    .put("automatic", false)
            }
            backup.backup(vault.localId)
            assertEquals(1, api.commitCount)
            val count = api.uploadCount
            backup.backup(vault.localId)
            assertEquals(count, api.uploadCount)
            assertEquals(1, api.commitCount)
            restored = backup.restore("owner/vault")
            assertEquals(
                "First note",
                VaultFiles(catalog.root(restored!!.localId)).read().second.single().title,
            )
            files.writeNote(Note(title = "Second note"))
            catalog.update(vault.localId) { it.put("revision", 2) }
            api.loseCommitResponse = true
            assertTrue(runCatching { backup.backup(vault.localId) }.isFailure)
            assertTrue(catalog.config(vault.localId).optString("pending").isNotBlank())
            backup.backup(vault.localId)
            assertEquals(2, api.commitCount)
            assertEquals(2L, catalog.config(vault.localId).getLong("backedRevision"))
            files.writeNote(Note(title = "Third note"))
            catalog.update(vault.localId) { it.put("revision", 3) }
            api.changeHeadBeforePush = true
            assertTrue(
                runCatching { backup.backup(vault.localId) }.exceptionOrNull() is RemoteChanged
            )
            assertEquals("other-device", api.currentHead)
            assertEquals(3, files.read().second.size)
        } finally {
            BackupScheduler.cancel(context, vault.localId)
            root.deleteRecursively()
            restored?.let {
                BackupScheduler.cancel(context, it.localId)
                catalog.root(it.localId).deleteRecursively()
            }
        }
    }
}
