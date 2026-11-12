package de.snappdf

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import de.snappdf.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Complete real-raster import → native OpenCV → bundled OCR → searchable PDF → native rendering. */
@RunWith(AndroidJUnit4::class)
class E2eScanTest {
    @Test fun importsTwoPhotosAndSavesReadableSearchablePdfWithoutInternetPermission() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<SnapApplication>()
        @Suppress("DEPRECATION")
        val permissions = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse("The installed merged APK must not have internet permission", Manifest.permission.INTERNET in permissions)

        val repository = app.repository
        val previousSettings = app.settings.settings.value
        val previousDraft = repository.loadDraft()
        val id = UUID.randomUUID().toString()
        val sources = listOf(
            worksheetPhoto(app.filesDir, id, "Energy worksheet", "School and science"),
            worksheetPhoto(app.filesDir, id, "Maths practice", "Schule und Studium")
        )
        val store = ViewModelStore()
        var viewModel: ScanViewModel? = null
        var document: DocumentEntity? = null
        try {
            app.settings.update { AppSettings(ocr = true, quality = PdfQuality.STANDARD, paperSize = PaperSize.A4) }
            // Keep any unrelated draft originals intact, and restore its metadata
            // afterwards. This test's own sources are managed independently.
            repository.saveDraft(ScanSession(id, "Offline lesson", emptyList()))
            val model = withContext(Dispatchers.Main) {
                ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[ScanViewModel::class.java]
            }
            viewModel = model
            withTimeout(30_000) { model.ready.first { it } }
            val uris = sources.map { FileProvider.getUriForFile(app, "${app.packageName}.files", it) }
            val importJob = withContext(Dispatchers.Main) { model.importImages(uris) }
            withTimeout(120_000) { importJob.join() }
            assertNull("Import error: ${model.notice.value}", model.notice.value)
            val draft = model.session.value
            assertNotNull(draft)
            assertEquals(2, draft!!.pages.size)
            assertEquals(draft, repository.loadDraft())
            draft.pages.forEach { page ->
                assertTrue(File(page.originalPath).isFile)
                assertTrue(File(page.processedPath).length() > 0)
                assertNotEquals(page.originalPath, page.processedPath)
                assertTrue(page.width in 64..3000 && page.height in 64..3000)
            }

            withContext(Dispatchers.Main) { model.setTitle("Offline lesson $id") }
            val saveJob = withContext(Dispatchers.Main) { model.save() }
            withTimeout(120_000) { saveJob.join() }
            assertNull("Save error: ${model.notice.value}", model.notice.value)
            document = model.saved.value
            assertNotNull("The real scan workflow must produce a saved document", document)
            val saved = document!!
            assertEquals(2, saved.pageCount)
            assertTrue(saved.name.endsWith(".pdf"))
            assertTrue(saved.byteSize > 1000)
            assertTrue(repository.documents.first().any { it.id == saved.id && it.pageCount == 2 })
            assertNull("A successful commit clears the active draft", repository.loadDraft())
            assertNull(model.session.value)
            assertTrue("Import must never delete the selected source photos", sources.all { it.isFile })
            assertTrue("Bundled OCR must recognize first-page printed text: ${saved.ocrText}", saved.ocrText.contains("Energy", ignoreCase = true))
            assertTrue("Bundled OCR must recognize second-page printed text: ${saved.ocrText}", saved.ocrText.contains("Maths", ignoreCase = true))

            PDDocument.load(File(saved.pdfPath)).use { pdf ->
                assertEquals(2, pdf.numberOfPages)
                assertEquals(595.27f, pdf.getPage(0).mediaBox.width, 1f)
                val extracted = PDFTextStripper().getText(pdf)
                assertTrue("Recognized text must be embedded in the PDF", extracted.contains("Energy", ignoreCase = true))
                assertTrue(extracted.contains("Maths", ignoreCase = true))
            }
            ParcelFileDescriptor.open(File(saved.pdfPath), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertEquals(2, renderer.pageCount)
                    renderer.openPage(0).use { page ->
                        val rendered = Bitmap.createBitmap(300, 424, Bitmap.Config.ARGB_8888)
                        try {
                            rendered.eraseColor(Color.WHITE)
                            page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            var darkPixels = 0
                            for (y in 0 until rendered.height) for (x in 0 until rendered.width) {
                                if (Color.red(rendered.getPixel(x, y)) < 100) darkPixels++
                            }
                            assertTrue("The native Android PDF reader must render actual page content", darkPixels > 400)
                        } finally { rendered.recycle() }
                    }
                }
            }
        } finally {
            val model = viewModel
            if (model != null) withContext(Dispatchers.Main) { model.discard() }.join()
            withContext(Dispatchers.Main) { store.clear() }
            document?.let { repository.delete(it) }
            sources.forEach { it.delete() }
            if (previousDraft != null) repository.saveDraft(previousDraft)
            app.settings.update { previousSettings }
        }
    }

    private fun worksheetPhoto(filesDir: File, id: String, heading: String, subtitle: String): File {
        val directory = File(filesDir, "documents").apply { mkdirs() }
        val file = File(directory, "e2e-source-$id-${UUID.randomUUID()}.jpg")
        val bitmap = Bitmap.createBitmap(800, 1120, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(66, 73, 84))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.rgb(244, 244, 241)
            canvas.drawRect(48f, 50f, 752f, 1070f, paint)
            paint.color = Color.BLACK
            paint.textSize = 36f
            paint.isFakeBoldText = true
            canvas.drawText(heading, 100f, 150f, paint)
            paint.isFakeBoldText = false
            paint.textSize = 28f
            canvas.drawText(subtitle, 100f, 205f, paint)
            canvas.drawText("Name: Anna Example", 100f, 280f, paint)
            canvas.drawText("Task 1: 2x + 5 = 17", 100f, 355f, paint)
            canvas.drawText("Show your work below.", 100f, 410f, paint)
            paint.strokeWidth = 2f
            for (y in 490..900 step 70) canvas.drawLine(100f, y.toFloat(), 700f, y.toFloat(), paint)
            canvas.drawLine(100f, 490f, 100f, 900f, paint)
            canvas.drawLine(400f, 490f, 400f, 900f, paint)
            canvas.drawLine(700f, 490f, 700f, 900f, paint)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 93, it)) }
        } finally { bitmap.recycle() }
        return file
    }
}
