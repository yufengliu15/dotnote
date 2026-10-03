package dev.dotnote.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as DC
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object VaultTransfer {
    private fun children(
        context: Context,
        tree: Uri,
        id: String,
    ): List<Triple<String, String, String>> {
        val uri = DC.buildChildDocumentsUriUsingTree(tree, id)
        return context.contentResolver
            .query(
                uri,
                arrayOf(
                    DC.Document.COLUMN_DOCUMENT_ID,
                    DC.Document.COLUMN_DISPLAY_NAME,
                    DC.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )
            ?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(
                        Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))
                    )
                }
            } ?: error("Could not read selected folder")
    }

    suspend fun import(context: Context, tree: Uri): VaultInfo =
        withContext(Dispatchers.IO) {
            val catalog = VaultCatalog(context)
            val stage = File(catalog.directory, ".import-${newId()}").apply { mkdirs() }
            var count = 0
            var total = 0L
            try {
                fun copy(id: String, parent: String, depth: Int) {
                    require(depth < 64) { "Folder tree is too deep" }
                    children(context, tree, id).forEach { (child, name, mime) ->
                        require(name.isNotBlank() && !name.contains('/') && !name.contains('\\')) {
                            "Invalid folder entry"
                        }
                        val path = if (parent.isEmpty()) name else "$parent/$name"
                        require(safeRelative(path) && ++count <= 10000) { "Invalid vault folder" }
                        if (path == ".dotnote/trash") return@forEach
                        if (mime == DC.Document.MIME_TYPE_DIR) copy(child, path, depth + 1)
                        else {
                            if (
                                path in
                                    setOf(
                                        "README.md",
                                        "LICENSE",
                                        ".gitignore",
                                        ".DS_Store",
                                        ".dotnote/migrated",
                                    )
                            )
                                return@forEach
                            require(managedVaultPath(path)) {
                                "This folder contains unexpected files: $path"
                            }
                            val target = File(stage, path)
                            target.parentFile!!.mkdirs()
                            val source =
                                context.contentResolver.openInputStream(
                                    DC.buildDocumentUriUsingTree(tree, child)
                                ) ?: error("Cannot read $name")
                            source.use { input ->
                                target.outputStream().use { output ->
                                    val buffer = ByteArray(65536)
                                    var size = 0L
                                    while (true) {
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        size += n
                                        total += n
                                        require(
                                            size <= 512L * 1024 * 1024 &&
                                                total <= 1024L * 1024 * 1024
                                        ) {
                                            "Vault exceeds import size limit"
                                        }
                                        output.write(buffer, 0, n)
                                    }
                                }
                            }
                        }
                    }
                }
                copy(DC.getTreeDocumentId(tree), "", 0)
                catalog.publish(stage)
            } finally {
                stage.deleteRecursively()
            }
        }

    suspend fun export(context: Context, store: Store, tree: Uri) =
        withContext(Dispatchers.IO) {
            val stage = File(context.cacheDir, "folder-export-${newId()}").apply { mkdirs() }
            try {
                val files = store.snapshot(stage)
                val parent = DC.buildDocumentUriUsingTree(tree, DC.getTreeDocumentId(tree))
                val name =
                    store.catalog.list().find { it.localId == store.vaultId }?.name ?: "Dotnote"
                val root =
                    DC.createDocument(
                        context.contentResolver,
                        parent,
                        DC.Document.MIME_TYPE_DIR,
                        "$name-${System.currentTimeMillis()}",
                    ) ?: error("Cannot create destination folder")
                val dirs = mutableMapOf("" to root)
                fun directory(path: String): Uri =
                    dirs.getOrPut(path) {
                        val parentPath = path.substringBeforeLast('/', "")
                        DC.createDocument(
                            context.contentResolver,
                            directory(parentPath),
                            DC.Document.MIME_TYPE_DIR,
                            path.substringAfterLast('/'),
                        ) ?: error("Cannot create folder")
                    }
                files.forEach { (path, input) ->
                    val dir = directory(path.substringBeforeLast('/', ""))
                    val uri =
                        DC.createDocument(
                            context.contentResolver,
                            dir,
                            when {
                                path.endsWith(".pdf") -> "application/pdf"
                                path.endsWith(".dotnote") -> "application/octet-stream"
                                else -> "application/json"
                            },
                            path.substringAfterLast('/'),
                        ) ?: error("Cannot create $path")
                    val output =
                        context.contentResolver.openOutputStream(uri, "wt")
                            ?: error("Cannot write $path")
                    output.use { out -> input.inputStream().use { it.copyTo(out) } }
                }
            } finally {
                stage.deleteRecursively()
            }
        }
}
