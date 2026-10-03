package dev.dotnote.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class DocumentImportTest {
    @Test
    fun providerMimeFallbackAndLegacyFormatAreHandled() {
        assertEquals(
            DocumentImport.Kind.POWERPOINT,
            DocumentImport.kind("Lecture.PPTX", "application/zip"),
        )
        assertEquals(
            DocumentImport.Kind.POWERPOINT,
            DocumentImport.kind("Lecture", DocumentImport.PPTX),
        )
        assertEquals(
            DocumentImport.Kind.IMAGE,
            DocumentImport.kind("photo.HEIC", "application/octet-stream"),
        )
        assertEquals(DocumentImport.Kind.IMAGE, DocumentImport.kind("photo", "image/jpeg"))
        assertEquals(DocumentImport.Kind.PDF, DocumentImport.kind("notes.PDF", null))
        assertTrue(
            runCatching { DocumentImport.kind("old.ppt", "application/vnd.ms-powerpoint") }
                .exceptionOrNull()!!
                .message!!
                .contains("Save as .pptx")
        )
        assertTrue(runCatching { DocumentImport.kind("file.zip", "application/zip") }.isFailure)
    }

    @Test
    fun copyLimitStopsBeforeWritingOversizedInput() {
        val output = ByteArrayOutputStream()
        DocumentImport.copyLimited(ByteArrayInputStream(ByteArray(4)), output, 4)
        assertEquals(4, output.size())
        assertTrue(
            runCatching {
                    DocumentImport.copyLimited(
                        ByteArrayInputStream(ByteArray(5)),
                        ByteArrayOutputStream(),
                        4,
                    )
                }
                .isFailure
        )
    }
}
