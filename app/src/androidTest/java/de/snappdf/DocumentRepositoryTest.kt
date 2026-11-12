package de.snappdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import de.snappdf.data.AppSettings
import de.snappdf.data.DocumentRepository
import de.snappdf.data.DocumentQuad
import de.snappdf.data.OcrLine
import de.snappdf.data.PaperSize
import de.snappdf.data.ScanPage
import de.snappdf.data.ScanPoint
import de.snappdf.data.ScanSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DocumentRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = DocumentRepository(context)

    @Test fun draftSurvivesReloadAndTemporaryCleanup() = runBlocking {
        val page = fixture("Arbeitsblatt")
        val session = ScanSession(UUID.randomUUID().toString(), "Schule", listOf(page))
        repository.saveDraft(session)
        repository.clearTemporaryFiles()
        assertEquals(session, repository.loadDraft())
        assertTrue(File(page.originalPath).isFile)
        repository.clearDraft()
        assertNull(repository.loadDraft())
        assertFalse(File(page.originalPath).exists())
    }

    @Test fun interruptedProcessingKeepsOriginalAndPendingMarker() = runBlocking {
        val page = fixture("Pending original").copy(processedPath = "", ocrLines = emptyList())
        val session = ScanSession(UUID.randomUUID().toString(), "Wiederherstellung", listOf(page))
        try {
            repository.saveDraft(session)
            repository.clearTemporaryFiles()
            val recovered = repository.loadDraft()!!.pages.single()
            assertEquals(page.originalPath, recovered.originalPath)
            assertEquals("", recovered.processedPath)
            assertTrue(File(recovered.originalPath).isFile)
        } finally { repository.clearDraft() }
    }

    @Test fun missingProcessedImageRequestsReprocessingRatherThanRawExport() = runBlocking {
        val page = fixture("Original survives").copy(processedPath = File(context.filesDir, "missing-result.png").absolutePath)
        try {
            repository.saveDraft(ScanSession(UUID.randomUUID().toString(), "Missing result", listOf(page)))
            val recovered = repository.loadDraft()!!.pages.single()
            assertEquals("", recovered.processedPath)
            assertTrue(File(recovered.originalPath).isFile)
        } finally { repository.clearDraft() }
    }

    @Test fun searchablePdfPreservesTextPagesAndSources() = runBlocking {
        val page = fixture("Grüße aus der Schule")
        val second = fixture("English worksheet")
        val session = ScanSession(UUID.randomUUID().toString(), "Arbeitsblatt / Physik", listOf(page, second))
        var saved: de.snappdf.data.DocumentEntity? = null
        try {
            saved = repository.savePdf(session, AppSettings(ocr = true, paperSize = PaperSize.A4))
            assertEquals("Arbeitsblatt _ Physik.pdf", saved.name)
            assertEquals(2, saved.pageCount)
            assertTrue(File(saved.thumbnailPath).length() > 0)
            PDDocument.load(File(saved.pdfPath)).use { pdf ->
                assertEquals(2, pdf.numberOfPages)
                assertEquals(595.27f, pdf.getPage(0).mediaBox.width, 1f)
                val text = PDFTextStripper().getText(pdf)
                assertTrue(text, text.contains("Grüße aus der Schule"))
                assertTrue(text, text.contains("English worksheet"))
            }
            assertTrue(File(page.originalPath).exists())
            assertTrue(File(second.processedPath).exists())
        } finally {
            saved?.let { repository.delete(it) }
            File(page.originalPath).delete()
            File(second.originalPath).delete()
        }
    }

    @Test fun failedPdfDoesNotDeleteScannedPageOrCommitDocument() = runBlocking {
        val page = fixture("Original remains")
        val missing = page.copy(id = UUID.randomUUID().toString(), processedPath = "/does-not-exist.png")
        val count = repository.documents.first().size
        try {
            val failure = runCatching {
                repository.savePdf(ScanSession("failed", "Failure", listOf(page, missing)), AppSettings())
            }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals(count, repository.documents.first().size)
            assertTrue(File(page.originalPath).isFile)
        } finally { File(page.originalPath).delete() }
    }

    @Test fun mergingPreservesBothSourcePdfsAndSearchableText() = runBlocking {
        val firstPage = fixture("First source")
        val secondPage = fixture("Second source")
        val documents = mutableListOf<de.snappdf.data.DocumentEntity>()
        try {
            for (page in listOf(firstPage, secondPage)) {
                documents += repository.savePdf(ScanSession(page.id, page.id, listOf(page)), AppSettings(ocr = true))
            }
            val merged = repository.merge(documents.toList(), "Zusammengeführt")
            documents += merged
            assertEquals(2, merged.pageCount)
            PDDocument.load(File(merged.pdfPath)).use { pdf ->
                assertEquals(2, pdf.numberOfPages)
                val text = PDFTextStripper().getText(pdf)
                assertTrue(text, text.contains("First source"))
                assertTrue(text, text.contains("Second source"))
            }
            assertTrue(documents.all { File(it.pdfPath).isFile })
        } finally {
            documents.forEach { repository.delete(it) }
            File(firstPage.originalPath).delete()
            File(secondPage.originalPath).delete()
        }
    }

    private fun fixture(text: String): ScanPage {
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "sessions/tests").apply { mkdirs() }
        val file = File(directory, "$id.png")
        val bitmap = Bitmap.createBitmap(800, 1100, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText(text, 80f, 160f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 35f })
            drawRect(80f, 220f, 720f, 224f, Paint().apply { color = Color.BLACK })
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return ScanPage(id, file.absolutePath, file.absolutePath, width = 800, height = 1100,
            quad = DocumentQuad(ScanPoint(0.01f, 0.01f), ScanPoint(.99f, .01f), ScanPoint(.99f, .99f), ScanPoint(.01f, .99f)),
            ocrLines = listOf(OcrLine(text, .1f, .11f, .85f, .15f)))
    }
}
