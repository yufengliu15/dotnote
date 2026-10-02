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

/** Identity of a file version: a new inode, size or nanosecond mtime means new content. */
internal data class FileStamp(val inode: Long, val size: Long, val mtime: Long) {
    companion object {
        fun of(file: File): FileStamp? =
            try {
                val st = android.system.Os.stat(file.path)
                FileStamp(st.st_ino, st.st_size, st.st_mtim.tv_sec * 1_000_000_000L + st.st_mtim.tv_nsec)
            } catch (_: Exception) {
                null
            }
    }
}

/** Metadata of a validated note file, reusable while the file's stamp is unchanged. */
internal data class NoteScan(
    val stamp: FileStamp,
    val id: String,
    val title: String,
    val modified: Long,
    val template: Boolean,
    val assets: List<String>,
)

/**
 * UTF-8 .dotnote files are authoritative; Room is only an index. Call under the vault mutex.
 *
 * [cacheFile] (app-private, outside the vault) remembers which note files were already validated.
 * Unchanged files are not parsed again on startup or before a backup. Every entry is keyed by the
 * file's inode, size and nanosecond modification time, so a stale or lost cache only costs a
 * re-read; it can never return metadata for different content.
 */
class VaultFiles(val root: File, private val cacheFile: File? = null) {
    val assets = File(root, "attachments").apply { mkdirs() }
    private val journal
        get() = File(root, ".dotnote/transaction.json")

    private var notePaths = mutableMapOf<String, String>()
    private var folderPaths = mutableMapOf<String, String>()
    private val noteScans = HashMap<String, NoteScan>()
    private var scanCache: MutableMap<String, NoteScan>? = null
    private var blobCache: MutableMap<String, Pair<FileStamp, String>>? = null
    private var cacheDirty = false
    private var cacheSavedAt = 0L
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
        if (deletes.length() == 0) return
        // Only deletions can leave empty folders behind.
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

    private fun loadCache(): MutableMap<String, NoteScan> {
        scanCache?.let {
            return it
        }
        val notes = HashMap<String, NoteScan>()
        val blobs = HashMap<String, Pair<FileStamp, String>>()
        cacheFile
            ?.takeIf { it.isFile }
            ?.let { file ->
                runCatching {
                    val o = JSONObject(file.readText())
                    // Entries are keyed by relative path and inode, so a vault validated in
                    // staging and then renamed into place keeps its records.
                    if (o.optInt("version") != 1) return@runCatching
                    fun stamp(a: JSONArray) = FileStamp(a.getLong(0), a.getLong(1), a.getLong(2))
                    o.optJSONObject("notes")?.let { n ->
                        n.keys().forEach { path ->
                            val e = n.getJSONObject(path)
                            val assets = e.getJSONArray("assets")
                            notes[path] =
                                NoteScan(
                                    stamp(e.getJSONArray("stamp")),
                                    e.getString("id"),
                                    e.getString("title"),
                                    e.getLong("modified"),
                                    e.getBoolean("template"),
                                    List(assets.length()) { assets.getString(it) },
                                )
                        }
                    }
                    o.optJSONObject("blobs")?.let { b ->
                        b.keys().forEach { path ->
                            val e = b.getJSONObject(path)
                            blobs[path] = stamp(e.getJSONArray("stamp")) to e.getString("sha")
                        }
                    }
                }
            }
        scanCache = notes
        blobCache = blobs
        return notes
    }

    /** Persist validated-file metadata; throttled unless [force]. Failure only costs speed. */
    fun saveCache(force: Boolean = true) {
        val file = cacheFile ?: return
        val notes = scanCache ?: return
        if (!cacheDirty) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - cacheSavedAt < 10_000) return
        runCatching {
            fun stamp(s: FileStamp) = JSONArray().put(s.inode).put(s.size).put(s.mtime)
            val n = JSONObject()
            notes.forEach { (path, e) ->
                n.put(
                    path,
                    JSONObject()
                        .put("stamp", stamp(e.stamp))
                        .put("id", e.id)
                        .put("title", e.title)
                        .put("modified", e.modified)
                        .put("template", e.template)
                        .put("assets", JSONArray(e.assets)),
                )
            }
            val b = JSONObject()
            blobCache?.forEach { (path, e) ->
                b.put(path, JSONObject().put("stamp", stamp(e.first)).put("sha", e.second))
            }
            atomicText(
                file,
                JSONObject()
                    .put("version", 1)
                    .put("root", root.absolutePath)
                    .put("notes", n)
                    .put("blobs", b)
                    .toString(),
            )
            cacheDirty = false
            cacheSavedAt = now
        }
    }

    private fun remember(path: String, scan: NoteScan) {
        noteScans[scan.id] = scan
        if (cacheFile == null) return
        loadCache()[path] = scan
        cacheDirty = true
    }

    private fun forget(path: String) {
        if (cacheFile == null) return
        if (loadCache().remove(path) != null) cacheDirty = true
    }

    /** Git blob SHA-1, reused while the file's inode, size and mtime are unchanged. */
    fun gitSha(path: String, file: File): String {
        val stamp = FileStamp.of(file)
        if (cacheFile != null && stamp != null) {
            loadCache()
            blobCache?.get(path)?.let { (cached, sha) -> if (cached == stamp) return sha }
        }
        val sha = digest(file, true)
        if (cacheFile != null && stamp != null && FileStamp.of(file) == stamp) {
            blobCache?.set(path, stamp to sha)
            cacheDirty = true
        }
        return sha
    }

    /** Validates the vault and returns its folders and complete notes (documents included). */
    fun read(): Pair<List<Folder>, List<Note>> {
        val (folders, notes) = scan()
        return folders to notes.map { it.copy(document = readDocument(it.id)) }
    }

    /**
     * Validates the vault like [read] but returns notes without documents (empty strings), for
     * building the index. Unchanged notes are not read at all when a scan cache is configured.
     */
    fun scan(): Pair<List<Folder>, List<Note>> {
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
        noteScans.clear()
        val cached = if (cacheFile != null) loadCache() else null
        val seen = HashSet<String>()
        val folders = mutableListOf<Folder>()
        val notes = mutableListOf<Note>()
        var count = 0
        var total = 0L
        val rootCanonical = root.canonicalFile
        val checkedAssets = HashSet<String>()
        fun walk(dir: File, parent: String?, depth: Int = 0) {
            require(depth < 64) { "Folder tree is too deep" }
            require(dir.canonicalFile == File(rootCanonical, dir.relativeTo(root).path)) {
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
                    require(file.canonicalFile == File(rootCanonical, file.relativeTo(root).path)) {
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
                        val path = file.relativeTo(root).invariantSeparatorsPath
                        val stamp = FileStamp.of(file)
                        val length = stamp?.size ?: file.length()
                        require(length <= 64L * 1024 * 1024) { "Note exceeds 64 MB" }
                        total += length
                        require(total <= 256L * 1024 * 1024) { "Vault note data exceeds 256 MB" }
                        val reuse = cached?.get(path)?.takeIf { stamp != null && it.stamp == stamp }
                        val scan = reuse ?: scanNote(file, stamp)
                        require(validId(scan.id) && !notePaths.containsKey(scan.id)) {
                            "Duplicate or invalid note ID"
                        }
                        scan.assets.forEach { asset ->
                            if (checkedAssets.add(asset))
                                require(
                                    File(assets, asset).isFile &&
                                        File(assets, asset).canonicalFile ==
                                            File(rootCanonical, "attachments/$asset")
                                ) {
                                    "Missing PDF attachment: $asset"
                                }
                        }
                        notePaths[scan.id] = path
                        seen.add(path)
                        if (reuse == null && stamp != null) remember(path, scan)
                        else noteScans[scan.id] = scan
                        notes.add(Note(scan.id, parent, scan.title, scan.modified, "", scan.template))
                    } else
                        require(
                            file.name in setOf("README.md", "LICENSE", ".gitignore", ".DS_Store")
                        ) {
                            "Unexpected vault file: ${file.name}"
                        }
                }
        }
        walk(root, null)
        cached?.let { map ->
            if (map.keys.retainAll(seen)) cacheDirty = true
            saveCache()
        }
        return folders to notes
    }

    /** Full validation of one note file, without building its drawable scene. */
    private fun scanNote(file: File, stamp: FileStamp?): NoteScan {
        val text = AtomicFile(file).openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        val json = JsonCursor(text)
        var format: String? = null
        var version: Int? = null
        var id: String? = null
        var title: String? = null
        var modified: Long? = null
        var template = false
        var document: String? = null
        json.beginObject()
        var first = true
        while (true) {
            val key = json.nextKey(first) ?: break
            first = false
            when (key) {
                "format" -> format = json.string()
                "version" -> version = json.int()
                "id" -> id = json.string()
                "title" -> title = json.string()
                "modified" -> modified = json.number().toLong()
                "template" -> template = if (json.isNull()) false else json.boolean()
                "document" -> {
                    if (json.peek() != '{') json.fail("Invalid note document")
                    document = json.raw()
                }
                else -> json.skip()
            }
        }
        json.end()
        require(format == "dotnote" && version == 1) { "Unsupported note format" }
        val assets = LinkedHashSet<String>()
        DocumentCodec.validate(requireNotNull(document) { "Missing note document" }) {
            assets.add(it)
        }
        return NoteScan(
            stamp ?: FileStamp(-1, -1, -1),
            requireNotNull(id) { "Missing note ID" },
            requireNotNull(title) { "Missing note title" },
            requireNotNull(modified) { "Missing modified time" },
            template,
            assets.toList(),
        )
    }

    /** Exact document JSON stored in a note file. */
    fun readDocument(id: String): String {
        val path = requireNotNull(notePaths[id]) { "Note file is missing" }
        return documentText(target(path))
    }

    internal fun documentText(file: File): String {
        val text = AtomicFile(file).openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        // Files written by Dotnote (and earlier org.json versions) keep a fixed key order:
        // ...,"document":{...},"template":<bool>}. Strings escape quotes, so these markers cannot
        // appear inside the title; the document is then taken without scanning it. Anything
        // else uses the full parser. The caller parses and validates the document either way.
        if (text.startsWith("{\"format\":\"dotnote\",\"version\":1,\"id\":")) {
            val start = text.indexOf(",\"document\":{")
            val end = text.lastIndexOf(",\"template\":")
            if (
                start > 0 &&
                    end > start &&
                    (text.endsWith(",\"template\":false}") || text.endsWith(",\"template\":true}")) &&
                    text.lastIndexOf(",\"document\":{") == start
            )
                return text.substring(start + 12, end)
        }
        val json = JsonCursor(text)
        json.beginObject()
        var first = true
        while (true) {
            val key = json.nextKey(first) ?: break
            first = false
            if (key == "document") return json.raw()
            json.skip()
        }
        error("Missing note document")
    }

    /** Attachments referenced by notes, from validated metadata. */
    fun noteAssets(): Set<String> = noteScans.values.flatMapTo(LinkedHashSet()) { it.assets }

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

    private fun header(note: Note): String {
        val out = StringBuilder(160)
        out.append("{\"format\":\"dotnote\",\"version\":1,\"id\":")
        JsonText.string(out, note.id)
        out.append(",\"title\":")
        JsonText.string(out, note.title)
        out.append(",\"modified\":").append(note.modified).append(",\"document\":")
        return out.toString()
    }

    private fun footer(note: Note) = ",\"template\":${note.isTemplate}}"

    /** The same text org.json produced: metadata around the verbatim document JSON. */
    private fun encode(note: Note, document: String) = header(note) + document + footer(note)

    private fun writeFile(file: File, note: Note, document: String) =
        writeFile(file, note) { it.write(document) }

    private fun writeFile(file: File, note: Note, document: (java.io.Writer) -> Unit) {
        file.parentFile!!.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(output, Charsets.UTF_8), 65536)
            writer.write(header(note))
            document(writer)
            writer.write(footer(note))
            writer.flush()
            atomic.finishWrite(output)
        } catch (e: Throwable) {
            atomic.failWrite(output)
            throw e
        }
    }

    /**
     * Writes one note. An empty [Note.document] keeps the stored document (renames and moves).
     * [trustedAssets] is supplied only for documents this app just encoded from a valid scene.
     */
    fun writeNote(note: Note, trustedAssets: Collection<String>? = null) {
        require(validId(note.id))
        val old = notePaths[note.id]
        val document =
            note.document.ifEmpty { documentText(target(requireNotNull(old) { "Note file is missing" })) }
        val assets =
            if (trustedAssets != null && note.document.isNotEmpty()) trustedAssets.distinct()
            else LinkedHashSet<String>().also { found -> DocumentCodec.validate(document, found::add) }.toList()
        val path = notePath(note)
        if (old == null || old == path) {
            // A single replaced file is already atomic; no journal or folder walk is needed.
            // Finish any interrupted multi-file operation first.
            recover()
            writeFile(target(path), note, document)
        } else {
            transaction(mapOf(path to encode(note, document)), listOf(old))
            forget(old)
        }
        notePaths[note.id] = path
        val stamp = FileStamp.of(File(root, path))
        val scan = NoteScan(stamp ?: FileStamp(-1, -1, -1), note.id, note.title, note.modified, note.isTemplate, assets)
        if (stamp != null) remember(path, scan) else noteScans[note.id] = scan
        saveCache(force = false)
    }

    /**
     * Saves an editor scene in place, streaming each item's cached JSON straight to the file.
     * [note] supplies metadata; its document field is ignored. Falls back to [writeNote] when the
     * note's path changes.
     */
    fun writeScene(note: Note, scene: Document, assets: Collection<String>) {
        require(validId(note.id))
        val old = notePaths[note.id]
        val path = notePath(note)
        if (old != null && old != path) {
            writeNote(note.copy(document = DocumentCodec.encode(scene)), assets)
            return
        }
        recover()
        writeFile(target(path), note) { DocumentCodec.encodeTo(scene, it) }
        notePaths[note.id] = path
        val stamp = FileStamp.of(File(root, path))
        val scan =
            NoteScan(
                stamp ?: FileStamp(-1, -1, -1),
                note.id,
                note.title,
                note.modified,
                note.isTemplate,
                assets.distinct(),
            )
        if (stamp != null) remember(path, scan) else noteScans[note.id] = scan
        saveCache(force = false)
    }

    fun deleteNote(id: String) {
        notePaths[id]?.let { path ->
            // Retain an on-device recovery copy; Git history separately retains remote deletions.
            val source = target(path)
            transaction(mapOf(".dotnote/trash/$id.dotnote" to source.readText()), listOf(path))
            notePaths.remove(id)
            noteScans.remove(id)
            forget(path)
            saveCache(force = false)
        }
    }

    /**
     * Rewrites folder markers and moves notes whose path changed. Notes with an empty document keep
     * their stored content; unchanged notes at unchanged paths are not rewritten.
     */
    fun replace(folders: List<Folder>, notes: List<Note>) {
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
        val oldNotes = HashMap(notePaths)
        val oldMarkers = folderPaths.values.map { "$it/.folder.json" }
        folderPaths = folders.associate { it.id to path(it) }.toMutableMap()
        val writes =
            folders
                .associate {
                    folderPaths.getValue(it.id) + "/.folder.json" to
                        JSONObject().put("id", it.id).put("name", it.name).toString()
                }
                .toMutableMap()
        val unchanged = HashSet<String>()
        val scans = HashMap<String, NoteScan>()
        notes.forEach { note ->
            val path = notePath(note)
            val previous = oldNotes[note.id]
            val known = noteScans[note.id]
            if (
                note.document.isEmpty() &&
                    previous == path &&
                    known != null &&
                    known.title == note.title &&
                    known.modified == note.modified &&
                    known.template == note.isTemplate
            ) {
                unchanged.add(path)
                return@forEach
            }
            val assets: List<String>
            val document =
                if (note.document.isEmpty() && known != null) {
                    assets = known.assets
                    documentText(target(requireNotNull(previous) { "Note file is missing" }))
                } else {
                    val text =
                        note.document.ifEmpty {
                            documentText(target(requireNotNull(previous) { "Note file is missing" }))
                        }
                    val found = LinkedHashSet<String>()
                    DocumentCodec.validate(text, found::add)
                    assets = found.toList()
                    text
                }
            writes[path] = encode(note, document)
            scans[note.id] =
                NoteScan(FileStamp(-1, -1, -1), note.id, note.title, note.modified, note.isTemplate, assets)
        }
        val keep = writes.keys + unchanged
        val deletes = (oldNotes.values + oldMarkers).filterNot { it in keep }
        if (writes.isNotEmpty() || deletes.isNotEmpty()) transaction(writes, deletes)
        notePaths = notes.associate { it.id to notePath(it) }.toMutableMap()
        oldNotes.forEach { (id, path) -> if (notePaths[id] != path) forget(path) }
        (oldNotes.keys - notePaths.keys).forEach { noteScans.remove(it) }
        scans.forEach { (id, scan) ->
            val path = notePaths.getValue(id)
            val stamp = FileStamp.of(File(root, path))
            if (stamp != null) remember(path, scan.copy(stamp = stamp)) else noteScans[id] = scan
        }
        saveCache()
    }

    /**
     * Point-in-time copy of every managed file. Files are hard-linked when possible: every vault
     * write replaces files by rename, so a link keeps the snapshot's content without copying it.
     */
    fun snapshot(destination: File): Map<String, File> {
        recover()
        val result = linkedMapOf<String, File>()
        val paths =
            listOf(".dotnote/vault.json", ".dotnote/settings.json") +
                folderPaths.values.map { "$it/.folder.json" } +
                notePaths.values
        val assetsNeeded =
            notePaths.keys
                .flatMap { id ->
                    noteScans[id]?.assets
                        ?: LinkedHashSet<String>().also { found ->
                            DocumentCodec.validate(readDocument(id), found::add)
                        }
                }
                .toSet()
        (paths + assetsNeeded.map { "attachments/$it" }).forEach { path ->
            val input = target(path)
            if (!input.exists() && path == ".dotnote/settings.json") return@forEach
            require(input.isFile) { "Missing vault file: $path" }
            val output = File(destination, path)
            output.parentFile!!.mkdirs()
            output.delete()
            val linked =
                runCatching { android.system.Os.link(input.path, output.path) }.isSuccess
            if (!linked) input.copyTo(output, overwrite = true)
            result[path] = output
        }
        return result
    }
}
