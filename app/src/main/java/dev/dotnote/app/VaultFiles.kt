package dev.dotnote.app

import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

fun atomicText(file: File, text: String) {
    file.parentFile!!.mkdirs()
    val atomic = AtomicFile(file)
    val output = atomic.startWrite()
    try {
        output.write(text.toByteArray(Charsets.UTF_8))
        atomic.finishWrite(output)
    } catch (e: Throwable) {
        atomic.failWrite(output)
        throw e
    }
}

fun digest(file: File, git: Boolean = false): String {
    val hash = MessageDigest.getInstance(if (git) "SHA-1" else "SHA-256")
    if (git) hash.update("blob ${file.length()}\u0000".toByteArray())
    file.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            hash.update(buffer, 0, n)
        }
    }
    return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

fun safeRelative(path: String): Boolean =
    path.isNotBlank() &&
        path.length <= 1000 &&
        !path.startsWith('/') &&
        !path.contains('\\') &&
        path.split('/').all {
            it.isNotEmpty() && it != "." && it != ".." && it.none { c -> c.code < 32 }
        }

fun validId(id: String): Boolean = id.matches(Regex("[a-zA-Z0-9-]{1,80}"))

/** UTF-8 .dotnote files are authoritative; Room is only an index. Call under the vault mutex. */
class VaultFiles(val root: File) {
    val assets = File(root, "attachments").apply { mkdirs() }
    private val journal
        get() = File(root, ".dotnote/transaction.json")

    private var notePaths = mutableMapOf<String, String>()
    private var folderPaths = mutableMapOf<String, String>()
    val manifest
        get() = File(root, ".dotnote/vault.json")

    fun create(id: String, name: String) {
        require(validId(id))
        if (!manifest.exists())
            atomicText(
                manifest,
                JSONObject()
                    .put("format", "dotnote-vault")
                    .put("version", 1)
                    .put("id", id)
                    .put("name", name)
                    .toString(2),
            )
    }

    private fun target(path: String): File {
        require(safeRelative(path)) { "Invalid vault path" }
        val file = File(root, path)
        require(file.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            "Vault path escapes its folder"
        }
        return file
    }

    fun recover() {
        val atomic = AtomicFile(journal)
        val text =
            runCatching { atomic.openRead().use { it.readBytes().toString(Charsets.UTF_8) } }
                .getOrElse {
                    if (!journal.exists() && !File(journal.path + ".bak").exists()) return
                    throw it
                }
        applyTransaction(JSONObject(text))
        atomic.delete()
    }

    private fun applyTransaction(transaction: JSONObject) {
        val writes = transaction.getJSONObject("writes")
        writes.keys().forEach { path -> atomicText(target(path), writes.getString(path)) }
        val deletes = transaction.getJSONArray("deletes")
        for (i in 0 until deletes.length()) {
            val f = target(deletes.getString(i))
            require(!f.exists() || f.delete()) { "Could not finish moving a note" }
        }
        root
            .walkBottomUp()
            .filter {
                it.isDirectory && it != root && it.name != "attachments" && it.name != ".dotnote"
            }
            .forEach { if (it.list()?.isEmpty() == true) it.delete() }
    }

    private fun transaction(writes: Map<String, String>, deletes: List<String>) {
        val data =
            JSONObject()
                .put("writes", JSONObject(writes))
                .put("deletes", JSONArray(deletes.filterNot { it in writes }))
        atomicText(journal, data.toString())
        applyTransaction(data)
        AtomicFile(journal).delete()
    }

    fun read(): Pair<List<Folder>, List<Note>> {
        recover()
        require(File(root, ".dotnote").canonicalFile == File(root.canonicalFile, ".dotnote")) {
            "Linked metadata is not supported"
        }
        require(manifest.length() <= 65536) { "Vault metadata too large" }
        val settingsFile = File(root, ".dotnote/settings.json")
        if (settingsFile.exists()) {
            require(settingsFile.length() <= 65536) { "Writing settings too large" }
            val settings = JSONObject(settingsFile.readText())
            require(
                settings.getInt("version") == 1 && settings.getJSONArray("palette").length() == 6
            ) {
                "Unsupported writing settings"
            }
            repeat(6) { settings.getJSONArray("palette").getInt(it) }
            require(
                settings.getDouble("width").isFinite() && settings.getDouble("width") in 1.0..12.0
            )
            val textSize = settings.optDouble("textSize", 24.0)
            require(textSize.isFinite() && textSize in 8.0..144.0) { "Invalid text size setting" }
            settings.getInt("color")
            settings.getInt("rows")
            settings.getInt("cols")
            settings.getBoolean("finger")
            settings.getString("dock")
        }
        val meta = JSONObject(manifest.readText())
        require(
            meta.getString("format") == "dotnote-vault" &&
                meta.getInt("version") == 1 &&
                validId(meta.getString("id"))
        ) {
            "Unsupported vault format"
        }
        notePaths.clear()
        folderPaths.clear()
        val folders = mutableListOf<Folder>()
        val notes = mutableListOf<Note>()
        var count = 0
        var total = 0L
        fun walk(dir: File, parent: String?, depth: Int = 0) {
            require(depth < 64) { "Folder tree is too deep" }
            require(dir.canonicalFile == File(root.canonicalFile, dir.relativeTo(root).path)) {
                "Linked folders are not supported"
            }
            dir.listFiles()
                ?.sortedBy { it.name }
                ?.forEach { file ->
                    if (
                        file.name == ".dotnote" ||
                            (dir == root && file.name == "attachments") ||
                            file.name == ".folder.json"
                    )
                        return@forEach
                    require(++count <= 10000) { "Vault has too many files" }
                    require(
                        file.canonicalFile == File(root.canonicalFile, file.relativeTo(root).path)
                    ) {
                        "Linked files are not supported"
                    }
                    if (file.isDirectory) {
                        val marker = File(file, ".folder.json")
                        require(marker.length() <= 65536) { "Folder metadata too large" }
                        val value =
                            if (marker.isFile) JSONObject(marker.readText())
                            else JSONObject().put("id", newId()).put("name", file.name)
                        val id = value.getString("id")
                        require(validId(id) && !folderPaths.containsKey(id)) {
                            "Duplicate or invalid folder ID"
                        }
                        if (!marker.exists()) atomicText(marker, value.toString())
                        folderPaths[id] = file.relativeTo(root).invariantSeparatorsPath
                        folders.add(Folder(id, parent, value.optString("name", file.name)))
                        walk(file, id, depth + 1)
                    } else if (file.extension == "dotnote") {
                        require(file.length() <= 64L * 1024 * 1024) { "Note exceeds 64 MB" }
                        total += file.length()
                        require(total <= 256L * 1024 * 1024) { "Vault note data exceeds 256 MB" }
                        val o =
                            JSONObject(
                                AtomicFile(file).openRead().use {
                                    it.readBytes().toString(Charsets.UTF_8)
                                }
                            )
                        require(o.getString("format") == "dotnote" && o.getInt("version") == 1) {
                            "Unsupported note format"
                        }
                        val id = o.getString("id")
                        require(validId(id) && !notePaths.containsKey(id)) {
                            "Duplicate or invalid note ID"
                        }
                        val doc = o.getJSONObject("document").toString()
                        DocumentCodec.decode(doc).items.forEach { item ->
                            item.asset?.let {
                                require(
                                    File(assets, it).isFile &&
                                        File(assets, it).canonicalFile ==
                                            File(root.canonicalFile, "attachments/$it")
                                ) {
                                    "Missing PDF attachment: $it"
                                }
                            }
                        }
                        notePaths[id] = file.relativeTo(root).invariantSeparatorsPath
                        notes.add(
                            Note(
                                id,
                                parent,
                                o.getString("title"),
                                o.getLong("modified"),
                                doc,
                                o.optBoolean("template", false),
                            )
                        )
                    } else
                        require(
                            file.name in setOf("README.md", "LICENSE", ".gitignore", ".DS_Store")
                        ) {
                            "Unexpected vault file: ${file.name}"
                        }
                }
        }
        walk(root, null)
        return folders to notes
    }

    private fun component(name: String, id: String): String {
        val safe =
            name
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
                .trim()
                .trim('.')
                .take(90)
                .ifEmpty { "Untitled" }
        val bounded =
            safe.toList().let { chars ->
                var result = chars.joinToString("")
                while (result.toByteArray(Charsets.UTF_8).size > 120) result = result.dropLast(1)
                result
            }
        return "$bounded [$id]"
    }

    private fun notePath(note: Note): String =
        (note.folderId?.let { folderPaths.getValue(it) + "/" } ?: "") +
            component(note.title, note.id) +
            ".dotnote"

    private fun encode(note: Note) =
        JSONObject()
            .put("format", "dotnote")
            .put("version", 1)
            .put("id", note.id)
            .put("title", note.title)
            .put("modified", note.modified)
            .put("document", JSONObject(note.document))
            .put("template", note.isTemplate)
            .toString()

    fun writeNote(note: Note) {
        require(validId(note.id))
        DocumentCodec.decode(note.document)
        val path = notePath(note)
        val old = notePaths[note.id]
        transaction(mapOf(path to encode(note)), listOfNotNull(old).filter { it != path })
        notePaths[note.id] = path
    }

    fun deleteNote(id: String) {
        notePaths[id]?.let { path ->
            // Retain an on-device recovery copy; Git history separately retains remote deletions.
            val source = target(path)
            transaction(mapOf(".dotnote/trash/$id.dotnote" to source.readText()), listOf(path))
            notePaths.remove(id)
        }
    }

    fun replace(folders: List<Folder>, notes: List<Note>) {
        val old = notePaths.values.toList() + folderPaths.values.map { "$it/.folder.json" }
        val parents = folders.associate { it.id to it.parentId }
        require(
            parents.size == folders.size &&
                folders.all { validId(it.id) && canMoveFolder(it.id, it.parentId, parents) }
        ) {
            "Invalid folder tree"
        }
        val byId = folders.associateBy { it.id }
        fun path(f: Folder): String =
            (f.parentId?.let { path(byId.getValue(it)) + "/" } ?: "") + component(f.name, f.id)
        folderPaths = folders.associate { it.id to path(it) }.toMutableMap()
        val writes =
            folders
                .associate {
                    folderPaths.getValue(it.id) + "/.folder.json" to
                        JSONObject().put("id", it.id).put("name", it.name).toString()
                }
                .toMutableMap()
        notes.forEach { writes[notePath(it)] = encode(it) }
        transaction(writes, old)
        notePaths = notes.associate { it.id to notePath(it) }.toMutableMap()
    }

    fun snapshot(destination: File): Map<String, File> {
        recover()
        val result = linkedMapOf<String, File>()
        val paths =
            listOf(".dotnote/vault.json", ".dotnote/settings.json") +
                folderPaths.values.map { "$it/.folder.json" } +
                notePaths.values
        val assetsNeeded =
            notePaths.values
                .flatMap { path ->
                    DocumentCodec.decode(
                            JSONObject(target(path).readText()).getJSONObject("document").toString()
                        )
                        .items
                        .mapNotNull(Item::asset)
                }
                .toSet()
        (paths + assetsNeeded.map { "attachments/$it" }).forEach { path ->
            val input = target(path)
            if (!input.exists() && path == ".dotnote/settings.json") return@forEach
            require(input.isFile) { "Missing vault file: $path" }
            val output = File(destination, path)
            output.parentFile!!.mkdirs()
            input.copyTo(output, overwrite = true)
            result[path] = output
        }
        return result
    }
}
