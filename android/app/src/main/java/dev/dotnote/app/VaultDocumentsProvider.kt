package dev.dotnote.app

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/** Makes portable vault files available in Android's Files app without broad storage permission. */
class VaultDocumentsProvider : DocumentsProvider() {
    private val base
        get() = VaultCatalog(requireNotNull(context)).directory

    override fun onCreate() = true

    private fun file(id: String): File {
        if (id == "root") return base
        if (!safeRelative(id)) throw FileNotFoundException()
        return File(base, id).also {
            if (!it.canonicalPath.startsWith(base.canonicalPath + "/") || !it.exists())
                throw FileNotFoundException()
        }
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result =
            MatrixCursor(
                projection
                    ?: arrayOf(
                        Root.COLUMN_ROOT_ID,
                        Root.COLUMN_DOCUMENT_ID,
                        Root.COLUMN_TITLE,
                        Root.COLUMN_FLAGS,
                        Root.COLUMN_MIME_TYPES,
                    )
            )
        result
            .newRow()
            .add(Root.COLUMN_ROOT_ID, "dotnote")
            .add(Root.COLUMN_DOCUMENT_ID, "root")
            .add(Root.COLUMN_TITLE, "Dotnote vaults")
            .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
            .add(Root.COLUMN_MIME_TYPES, "*/*")
        return result
    }

    private fun cursor(projection: Array<out String>?) =
        MatrixCursor(
            projection
                ?: arrayOf(
                    Document.COLUMN_DOCUMENT_ID,
                    Document.COLUMN_DISPLAY_NAME,
                    Document.COLUMN_MIME_TYPE,
                    Document.COLUMN_FLAGS,
                    Document.COLUMN_SIZE,
                    Document.COLUMN_LAST_MODIFIED,
                )
        )

    private fun add(cursor: MatrixCursor, f: File) {
        val id = if (f == base) "root" else f.relativeTo(base).invariantSeparatorsPath
        cursor
            .newRow()
            .add(Document.COLUMN_DOCUMENT_ID, id)
            .add(
                Document.COLUMN_DISPLAY_NAME,
                if (f == base) "Dotnote vaults"
                else if (f.parentFile == base)
                    runCatching {
                            org.json
                                .JSONObject(File(f, ".dotnote/vault.json").readText())
                                .getString("name")
                        }
                        .getOrDefault(f.name)
                else f.name,
            )
            .add(
                Document.COLUMN_MIME_TYPE,
                if (f.isDirectory) Document.MIME_TYPE_DIR
                else if (f.extension == "pdf") "application/pdf"
                else if (f.extension == "dotnote") "application/octet-stream"
                else "application/json",
            )
            .add(Document.COLUMN_FLAGS, 0)
            .add(Document.COLUMN_SIZE, f.length())
            .add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        cursor(projection).also { add(it, file(documentId)) }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor =
        cursor(projection).also { result ->
            file(parentDocumentId)
                .listFiles()
                ?.filter { f ->
                    if (parentDocumentId == "root")
                        validId(f.name) && File(f, ".dotnote/vault.json").isFile
                    else
                        f.name !in setOf("transaction.json", "migrated", "trash") &&
                            !f.name.endsWith(".bak") &&
                            !f.name.endsWith(".new")
                }
                ?.sortedBy { it.name }
                ?.forEach { add(result, it) }
        }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = file(parentDocumentId).canonicalFile
        val child = file(documentId).canonicalFile
        return child.path.startsWith(parent.path + File.separator)
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (mode != "r")
            throw FileNotFoundException(
                "Use Dotnote to edit the vault; files can be copied from here."
            )
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
