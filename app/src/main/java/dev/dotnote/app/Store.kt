package dev.dotnote.app

import android.content.Context
import android.net.Uri
import androidx.room.*
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "folders")
data class Folder(
    @PrimaryKey val id: String = newId(),
    val parentId: String? = null,
    val name: String,
)

@Entity(tableName = "notes", indices = [Index("folderId")])
data class Note(
    @PrimaryKey val id: String = newId(),
    val folderId: String? = null,
    val title: String,
    val modified: Long = System.currentTimeMillis(),
    val document: String = DocumentCodec.encode(Document()),
    @ColumnInfo(defaultValue = "0") val isTemplate: Boolean = false,
)

data class NoteSummary(
    val id: String,
    val folderId: String?,
    val title: String,
    val modified: Long,
    val isTemplate: Boolean = false,
)

interface LibraryDao {
    fun folders(): Flow<List<Folder>>

    fun notes(): Flow<List<NoteSummary>>

    suspend fun templates(): List<NoteSummary>

    suspend fun allFolders(): List<Folder>

    suspend fun allNotes(): List<Note>

    suspend fun note(id: String): Note?

    suspend fun clearNotes()

    suspend fun clearFolders()

    suspend fun put(note: Note)

    suspend fun put(folder: Folder)

    suspend fun putNotes(notes: List<Note>)

    suspend fun putFolders(folders: List<Folder>)

    suspend fun deleteNote(id: String)

    suspend fun deleteFolder(id: String)

    suspend fun childFolders(id: String): Int

    suspend fun childNotes(id: String): Int

    suspend fun renameNote(id: String, title: String)

    suspend fun moveNote(id: String, folder: String?)

    suspend fun save(id: String, document: String, modified: Long)
}

/** Metadata stays small even when the document exceeds Android's CursorWindow capacity. */
data class NoteRecord(@Embedded val summary: NoteSummary, val documentLength: Int)

@Dao
abstract class RoomLibraryDao : LibraryDao {
    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
    abstract override fun folders(): Flow<List<Folder>>

    @Query("SELECT id, folderId, title, modified, isTemplate FROM notes ORDER BY modified DESC")
    abstract override fun notes(): Flow<List<NoteSummary>>

    @Query(
        "SELECT id, folderId, title, modified, isTemplate FROM notes WHERE isTemplate = 1 ORDER BY title COLLATE NOCASE"
    )
    abstract override suspend fun templates(): List<NoteSummary>

    @Query("SELECT * FROM folders") abstract override suspend fun allFolders(): List<Folder>

    @Query(
        "SELECT id, folderId, title, modified, isTemplate, length(document) AS documentLength FROM notes"
    )
    protected abstract suspend fun noteRecords(): List<NoteRecord>

    @Query(
        "SELECT id, folderId, title, modified, isTemplate, length(document) AS documentLength FROM notes WHERE id=:id"
    )
    protected abstract suspend fun noteRecord(id: String): NoteRecord?

    @Query("SELECT id, folderId, title, modified, isTemplate FROM notes WHERE id=:id")
    abstract suspend fun noteSummary(id: String): NoteSummary?

    @Query("SELECT document = :document FROM notes WHERE id=:id")
    abstract suspend fun documentMatches(id: String, document: String): Boolean?

    @Query("SELECT substr(document, :offset, :count) FROM notes WHERE id=:id")
    protected abstract suspend fun documentChunk(id: String, offset: Int, count: Int): String

    @Transaction override suspend fun allNotes(): List<Note> = noteRecords().map { readNote(it) }

    @Transaction override suspend fun note(id: String): Note? = noteRecord(id)?.let { readNote(it) }

    private suspend fun readNote(record: NoteRecord): Note {
        // SQLite length/substr both count Unicode code points. Never advance by Kotlin's
        // UTF-16 String.length, which would skip content after supplementary characters.
        val document = StringBuilder(record.documentLength)
        var offset = 1
        val chunkSize = 64 * 1024
        while (offset <= record.documentLength) {
            document.append(documentChunk(record.summary.id, offset, chunkSize))
            offset += chunkSize
        }
        val summary = record.summary
        return Note(
            summary.id,
            summary.folderId,
            summary.title,
            summary.modified,
            document.toString(),
            summary.isTemplate,
        )
    }

    @Query("DELETE FROM notes") abstract override suspend fun clearNotes()

    @Query("DELETE FROM folders") abstract override suspend fun clearFolders()

    @Upsert abstract override suspend fun put(note: Note)

    @Upsert abstract override suspend fun put(folder: Folder)

    @Upsert abstract override suspend fun putNotes(notes: List<Note>)

    @Upsert abstract override suspend fun putFolders(folders: List<Folder>)

    @Query("DELETE FROM notes WHERE id=:id") abstract override suspend fun deleteNote(id: String)

    @Query("DELETE FROM folders WHERE id=:id")
    abstract override suspend fun deleteFolder(id: String)

    @Query("SELECT COUNT(*) FROM folders WHERE parentId=:id")
    abstract override suspend fun childFolders(id: String): Int

    @Query("SELECT COUNT(*) FROM notes WHERE folderId=:id")
    abstract override suspend fun childNotes(id: String): Int

    @Query("UPDATE notes SET title=:title WHERE id=:id")
    abstract override suspend fun renameNote(id: String, title: String)

    @Query("UPDATE notes SET folderId=:folder WHERE id=:id")
    abstract override suspend fun moveNote(id: String, folder: String?)

    @Query("UPDATE notes SET document=:document, modified=:modified WHERE id=:id")
    abstract override suspend fun save(id: String, document: String, modified: Long)
}

@Database(entities = [Folder::class, Note::class], version = 2, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): RoomLibraryDao

    companion object {
        val MIGRATION_1_2 =
            object : androidx.room.migration.Migration(1, 2) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE notes ADD COLUMN isTemplate INTEGER NOT NULL DEFAULT 0")
                }
            }
    }
}

class Store(
    val context: Context,
    databaseName: String = "dotnote.db",
    requestedVault: String? = null,
) {
    val catalog = VaultCatalog(context)
    val vaultId =
        if (databaseName == "dotnote.db") requestedVault ?: catalog.selected()
        else "test-" + databaseName.replace(Regex("[^a-zA-Z0-9-]"), "-")
    val root = catalog.root(vaultId)
    val files = VaultFiles(root)
    val mutex = VaultLocks.forRoot(root)
    private val isAppVault = databaseName == "dotnote.db"
    val db =
        Room.databaseBuilder(
                context,
                LibraryDatabase::class.java,
                if (isAppVault) "vault-$vaultId.db" else databaseName,
            )
            .addMigrations(LibraryDatabase.MIGRATION_1_2)
            .build()
    private val index = db.dao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loading = MutableStateFlow("Opening local vault…")
    val loadingStatus = loading.asStateFlow()
    internal var initializationTimings: Pair<Double, Double>? = null
        private set

    val ready =
        scope.async(start = CoroutineStart.LAZY) {
            mutex.withLock {
                files.create(newId(), if (isAppVault) "My notes" else "Test vault")
                val migrated = File(root, ".dotnote/migrated")
                if (isAppVault && catalog.isLegacyTarget(vaultId) && !migrated.exists()) {
                    loading.value = "Migrating local notes…"
                    // Leave the old DB and PDFs intact. Replay safely if migration was interrupted.
                    val legacy =
                        Room.databaseBuilder(context, LibraryDatabase::class.java, "dotnote.db")
                            .addMigrations(LibraryDatabase.MIGRATION_1_2)
                            .build()
                    try {
                        val old =
                            legacy.withTransaction {
                                legacy.dao().allFolders() to legacy.dao().allNotes()
                            }
                        old.second
                            .flatMap {
                                DocumentCodec.decode(it.document).items.mapNotNull(Item::asset)
                            }
                            .toSet()
                            .forEach { name ->
                                val source = File(context.filesDir, "pdfs/$name")
                                require(source.isFile) { "Migration needs a missing PDF: $name" }
                                source.copyTo(File(files.assets, name), overwrite = true)
                            }
                        files.replace(old.first, old.second)
                        files.read()
                        atomicText(migrated, "1")
                    } finally {
                        legacy.close()
                    }
                }
                loading.value = "Reading local notes…"
                val scanStart = android.os.SystemClock.elapsedRealtimeNanos()
                val (folders, notes) = files.read()
                val indexStart = android.os.SystemClock.elapsedRealtimeNanos()
                loading.value = "Updating note list…"
                rebuildIndex(folders, notes)
                val finished = android.os.SystemClock.elapsedRealtimeNanos()
                initializationTimings =
                    (indexStart - scanStart) / 1e6 to (finished - indexStart) / 1e6
                if (BuildConfig.DEBUG)
                    android.util.Log.i(
                        "DotnoteStartup",
                        "Local vault: ${notes.size} notes, scan=${initializationTimings!!.first} ms, index=${initializationTimings!!.second} ms",
                    )
                loading.value = "Local notes ready"
            }
        }

    private suspend fun rebuildIndex(folders: List<Folder>, notes: List<Note>) =
        db.withTransaction {
            index.clearNotes()
            index.clearFolders()
            index.putFolders(folders)
            index.putNotes(notes)
        }

    val dao: LibraryDao = FileLibraryDao(this, index)
    val assets
        get() = files.assets

    init {
        ready.start()
    }

    fun changed() {
        if (isAppVault) catalog.edited(vaultId)
    }

    suspend fun reload() =
        withContext(Dispatchers.IO) {
            ready.await()
            mutex.withLock {
                val (folders, notes) = files.read()
                rebuildIndex(folders, notes)
            }
        }

    suspend fun replaceLibrary(folders: List<Folder>, notes: List<Note>) =
        withContext(Dispatchers.IO) {
            ready.await()
            mutex.withLock {
                files.replace(folders, notes)
                rebuildIndex(folders, notes)
                changed()
            }
        }

    suspend fun snapshot(destination: File): Map<String, File> =
        withContext(Dispatchers.IO) {
            ready.await()
            mutex.withLock { files.snapshot(destination) }
        }

    suspend fun moveFolder(folder: Folder, target: String?) {
        dao.put(folder.copy(parentId = target))
    }

    suspend fun deleteFolder(folder: Folder) {
        dao.deleteFolder(folder.id)
    }

    suspend fun backup(uri: Uri) =
        withContext(Dispatchers.IO) {
            ready.await()
            val (folders, notes) = mutex.withLock { index.allFolders() to index.allNotes() }
            val manifest =
                JSONObject()
                    .put("format", "dotnote")
                    .put("version", 1)
                    .put(
                        "folders",
                        JSONArray(
                            folders.map {
                                JSONObject()
                                    .put("id", it.id)
                                    .put("parent", it.parentId)
                                    .put("name", it.name)
                            }
                        ),
                    )
                    .put(
                        "notes",
                        JSONArray(
                            notes.map {
                                JSONObject()
                                    .put("id", it.id)
                                    .put("folder", it.folderId)
                                    .put("title", it.title)
                                    .put("modified", it.modified)
                                    .put("document", JSONObject(it.document))
                                    .put("template", it.isTemplate)
                            }
                        ),
                    )
            val attachments =
                notes
                    .flatMap { DocumentCodec.decode(it.document).items.mapNotNull(Item::asset) }
                    .toSet()
            val output =
                context.contentResolver.openOutputStream(uri, "wt")
                    ?: error("Cannot open backup destination")
            ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                zip.putNextEntry(ZipEntry("library.json"))
                zip.write(manifest.toString().toByteArray())
                zip.closeEntry()
                attachments.forEach { name ->
                    zip.putNextEntry(ZipEntry("pdfs/$name"))
                    File(assets, name).inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }

    /** Validate first, merge in one transaction. Existing notes are never overwritten. */
    suspend fun restore(uri: Uri): Int =
        withContext(Dispatchers.IO) {
            val staging = File(context.cacheDir, "restore-${newId()}").apply { mkdirs() }
            val copied = mutableListOf<File>()
            try {
                val input =
                    context.contentResolver.openInputStream(uri) ?: error("Cannot open backup")
                var total = 0L
                val seen = mutableSetOf<String>()
                ZipInputStream(BufferedInputStream(input)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(seen.add(entry.name) && seen.size <= 10000) {
                            "Invalid backup entries"
                        }
                        require(
                            entry.name == "library.json" ||
                                entry.name.matches(Regex("pdfs/[a-f0-9-]+\\.pdf"))
                        ) {
                            "Unexpected backup file"
                        }
                        val dest = File(staging, entry.name)
                        dest.parentFile!!.mkdirs()
                        dest.outputStream().use { out ->
                            val buffer = ByteArray(65536)
                            var fileSize = 0L
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                total += n
                                fileSize += n
                                require(total <= 1024L * 1024 * 1024) { "Backup exceeds 1 GB" }
                                require(
                                    entry.name != "library.json" || fileSize <= 64L * 1024 * 1024
                                ) {
                                    "Library metadata too large"
                                }
                                out.write(buffer, 0, n)
                            }
                        }
                        zip.closeEntry()
                    }
                }
                val manifest = JSONObject(File(staging, "library.json").readText())
                require(
                    manifest.getString("format") == "dotnote" && manifest.getInt("version") == 1
                ) {
                    "Unsupported backup format"
                }
                val f = manifest.getJSONArray("folders")
                val n = manifest.getJSONArray("notes")
                val folders =
                    (0 until f.length()).map {
                        val o = f.getJSONObject(it)
                        Folder(
                            o.getString("id"),
                            o.optString("parent").takeIf(String::isNotEmpty),
                            o.getString("name"),
                        )
                    }
                val folderIds = folders.associate { it.id to newId() }
                require(folderIds.size == folders.size) { "Duplicate folders" }
                val parents = folders.associate { it.id to it.parentId }
                folders.forEach {
                    require(canMoveFolder(it.id, it.parentId, parents)) { "Invalid folder tree" }
                }
                val assetsMap = mutableMapOf<String, String>()
                val notes =
                    (0 until n.length()).map {
                        val o = n.getJSONObject(it)
                        val doc = DocumentCodec.decode(o.getJSONObject("document").toString())
                        val folder = o.optString("folder").takeIf(String::isNotEmpty)
                        require(folder == null || folderIds.containsKey(folder)) {
                            "Missing parent folder"
                        }
                        val items =
                            doc.items.map { item ->
                                if (item.asset == null) item
                                else {
                                    val newName =
                                        assetsMap.getOrPut(item.asset) {
                                            val source = File(staging, "pdfs/${item.asset}")
                                            require(source.isFile) { "Missing PDF attachment" }
                                            val name = "${newId()}.pdf"
                                            val dest = File(assets, name)
                                            source.copyTo(dest)
                                            copied.add(dest)
                                            name
                                        }
                                    item.copy(asset = newName)
                                }
                            }
                        Note(
                            folderId = folder?.let(folderIds::getValue),
                            title = o.getString("title"),
                            modified = o.getLong("modified"),
                            document = DocumentCodec.encode(doc.copy(items = items)),
                            isTemplate = o.optBoolean("template", false),
                        )
                    }
                val restoredFolders =
                    folders.map {
                        it.copy(
                            id = folderIds.getValue(it.id),
                            parentId = it.parentId?.let(folderIds::getValue),
                        )
                    }
                replaceLibrary(dao.allFolders() + restoredFolders, dao.allNotes() + notes)
                copied.clear()
                notes.size
            } finally {
                staging.deleteRecursively()
                copied.forEach(File::delete)
            }
        }
}
