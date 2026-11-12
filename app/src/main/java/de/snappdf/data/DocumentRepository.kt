package de.snappdf.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** Local-only document storage. Originals stay on disk until the caller clears its saved draft. */
class DocumentRepository(context: Context) {
    private val app = context.applicationContext
    private val database = Room.databaseBuilder(app, SnapDatabase::class.java, "snappdf.db").build()
    private val dao = database.documents()
    private val pdfDirectory = File(app.filesDir, "documents").apply { mkdirs() }
    private val thumbnailDirectory = File(app.filesDir, "thumbnails").apply { mkdirs() }
    private val sessionDirectory = File(app.filesDir, "sessions").apply { mkdirs() }
    private val draft = AtomicFile(File(app.filesDir, "active-session.json"))
    private val writes = Mutex()

    val documents: Flow<List<DocumentEntity>> = dao.observeDocuments()
    val folders: Flow<List<FolderEntity>> = dao.observeFolders()

    init { PDFBoxResourceLoader.init(app) }

    suspend fun savePdf(session: ScanSession, settings: AppSettings): DocumentEntity = writes.withLock {
        withContext(Dispatchers.IO) {
            require(session.pages.isNotEmpty()) { "Bitte zuerst eine Seite scannen." }
            val id = UUID.randomUUID().toString()
            val destination = File(pdfDirectory, "$id.pdf")
            val temporary = File(pdfDirectory, "$id.pdf.part")
            val thumbnail = File(thumbnailDirectory, "$id.png")
            try {
                PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(app.cacheDir)).use { pdf ->
                    pdf.documentInformation.title = displayName(session.title).removeSuffix(".pdf")
                    pdf.documentInformation.creator = "SnapPDF"
                    pdf.documentInformation.creationDate = java.util.Calendar.getInstance()
                    for (scan in session.pages) appendPage(pdf, scan, settings)
                    pdf.save(temporary)
                }
                sync(temporary)
                if (!temporary.renameTo(destination)) throw IOException("Die PDF konnte nicht sicher gespeichert werden.")
                createThumbnail(destination, thumbnail)
                val document = DocumentEntity(
                    id = id,
                    name = displayName(session.title),
                    createdAt = System.currentTimeMillis(),
                    pageCount = session.pages.size,
                    pdfPath = destination.absolutePath,
                    thumbnailPath = thumbnail.absolutePath,
                    byteSize = destination.length(),
                    ocrText = if (settings.ocr) session.pages.joinToString("\n\n") { page -> page.ocrLines.joinToString("\n") { it.text } } else ""
                )
                dao.insertDocument(document)
                document
            } catch (error: Throwable) {
                temporary.delete()
                destination.delete()
                thumbnail.delete()
                throw error
            }
        }
    }

    private fun appendPage(pdf: PDDocument, scan: ScanPage, settings: AppSettings) {
        val imageFile = File(scan.processedPath)
        require(imageFile.isFile && imageFile.length() > 0L) { "Eine gescannte Seite fehlt. Bitte die Seite erneut aufnehmen." }
        val bitmap = decodeBounded(imageFile, when (settings.quality) {
            PdfQuality.COMPACT -> 1700
            PdfQuality.STANDARD -> 2600
            PdfQuality.HIGH -> 3600
        })
        try {
            val pageSize = pageSize(bitmap.width, bitmap.height, settings.paperSize)
            val page = PDPage(pageSize)
            pdf.addPage(page)
            val image = LosslessFactory.createFromImage(pdf, bitmap)
            val scale = min(pageSize.width / bitmap.width, pageSize.height / bitmap.height)
            val imageWidth = bitmap.width * scale
            val imageHeight = bitmap.height * scale
            val x = (pageSize.width - imageWidth) / 2f
            val y = (pageSize.height - imageHeight) / 2f
            PDPageContentStream(pdf, page).use { content ->
                content.setNonStrokingColor(255, 255, 255)
                content.addRect(0f, 0f, pageSize.width, pageSize.height)
                content.fill()
                content.drawImage(image, x, y, imageWidth, imageHeight)
                if (settings.ocr) addSearchableText(content, scan.ocrLines, x, y, imageWidth, imageHeight)
            }
        } finally { bitmap.recycle() }
    }

    /** The Latin OCR model's German/English characters fit WinAnsi; unsupported glyphs become spaces. */
    private fun addSearchableText(content: PDPageContentStream, lines: List<OcrLine>, x: Float, y: Float, width: Float, height: Float) {
        val font = PDType1Font.HELVETICA
        for (line in lines) {
            val text = line.text.map { character ->
                if (character.isISOControl()) ' ' else runCatching { font.encode(character.toString()); character }.getOrDefault(' ')
            }.joinToString("").trim()
            if (text.isEmpty()) continue
            val lineHeight = ((line.bottom - line.top).coerceAtLeast(0.001f) * height)
            val lineWidth = ((line.right - line.left).coerceAtLeast(0.001f) * width)
            val fontSize = (lineHeight * 0.82f).coerceIn(1f, 120f)
            val textWidth = max(0.01f, font.getStringWidth(text) / 1000f * fontSize)
            content.beginText()
            content.setFont(font, fontSize)
            content.setRenderingMode(RenderingMode.NEITHER)
            content.setHorizontalScaling((lineWidth / textWidth * 100f).coerceIn(1f, 1000f))
            content.newLineAtOffset(x + line.left.coerceIn(0f, 1f) * width, y + (1f - line.bottom.coerceIn(0f, 1f)) * height + lineHeight * 0.1f)
            content.showText(text)
            content.endText()
        }
    }

    suspend fun rename(document: DocumentEntity, name: String) = withContext(Dispatchers.IO) {
        dao.renameDocument(document.id, displayName(name))
    }

    suspend fun delete(document: DocumentEntity) = writes.withLock {
        withContext(Dispatchers.IO) {
            dao.deleteDocument(document.id)
            safeDelete(File(document.pdfPath), pdfDirectory)
            safeDelete(File(document.thumbnailPath), thumbnailDirectory)
        }
    }

    suspend fun createFolder(name: String) = withContext(Dispatchers.IO) {
        val clean = name.trim().take(100)
        require(clean.isNotBlank()) { "Bitte einen Ordnernamen eingeben." }
        dao.insertFolder(FolderEntity(UUID.randomUUID().toString(), clean))
    }

    suspend fun move(document: DocumentEntity, folderId: String?) = withContext(Dispatchers.IO) {
        require(folderId == null || dao.folderExists(folderId)) { "Dieser Ordner ist nicht mehr vorhanden." }
        dao.moveDocument(document.id, folderId)
    }

    suspend fun merge(documents: List<DocumentEntity>, name: String): DocumentEntity = writes.withLock {
        withContext(Dispatchers.IO) {
            require(documents.size >= 2) { "Bitte mindestens zwei PDFs auswählen." }
            require(documents.all { File(it.pdfPath).isFile }) { "Eine der ausgewählten PDFs fehlt." }
            val id = UUID.randomUUID().toString()
            val temporary = File(pdfDirectory, "$id.pdf.part")
            val destination = File(pdfDirectory, "$id.pdf")
            val thumbnail = File(thumbnailDirectory, "$id.png")
            try {
                PDFMergerUtility().apply {
                    documents.forEach { addSource(File(it.pdfPath)) }
                    destinationFileName = temporary.absolutePath
                    mergeDocuments(MemoryUsageSetting.setupTempFileOnly().setTempDir(app.cacheDir))
                }
                sync(temporary)
                if (!temporary.renameTo(destination)) throw IOException("Die zusammengeführte PDF konnte nicht gespeichert werden.")
                createThumbnail(destination, thumbnail)
                val merged = DocumentEntity(id, displayName(name), System.currentTimeMillis(),
                    documents.sumOf { it.pageCount }, destination.absolutePath, thumbnail.absolutePath,
                    byteSize = destination.length(), ocrText = documents.joinToString("\n\n") { it.ocrText })
                dao.insertDocument(merged)
                merged
            } catch (error: Throwable) {
                temporary.delete(); destination.delete(); thumbnail.delete()
                throw error
            }
        }
    }

    suspend fun saveDraft(session: ScanSession) = writes.withLock {
        withContext(Dispatchers.IO) {
            var stream: FileOutputStream? = null
            try {
                stream = draft.startWrite()
                stream.write(DraftCodec.encode(session).toByteArray(Charsets.UTF_8))
                draft.finishWrite(stream)
            } catch (error: Throwable) {
                stream?.let { draft.failWrite(it) }
                throw error
            }
        }
    }

    suspend fun loadDraft(): ScanSession? = writes.withLock { withContext(Dispatchers.IO) { readDraft() } }

    private fun readDraft(): ScanSession? {
        if (!draft.baseFile.exists() && !File(draft.baseFile.path + ".bak").exists()) return null
        val session = draft.openRead().bufferedReader().use { DraftCodec.decode(it.readText()) }
        return session.copy(pages = session.pages.mapNotNull { page ->
            val original = File(page.originalPath)
            val processed = File(page.processedPath)
            when {
                original.isFile && processed.isFile -> page
                // An empty result path is the persisted "awaiting processing"
                // marker used by startup recovery. Never silently export the
                // raw original when a crash interrupted image optimization.
                original.isFile -> page.copy(processedPath = "", ocrLines = emptyList())
                processed.isFile -> page.copy(originalPath = processed.absolutePath)
                else -> null
            }
        })
    }

    /** Only call after successful save or explicit user discard; this also deletes this draft's images. */
    suspend fun clearDraft() = writes.withLock {
        withContext(Dispatchers.IO) {
            val session = runCatching { readDraft() }.getOrNull()
            draft.delete()
            session?.pages?.forEach { page ->
                safeDelete(File(page.originalPath), sessionDirectory)
                safeDelete(File(page.processedPath), sessionDirectory)
            }
        }
    }

    /** Creates a unique SAF document; local PDF remains intact if the provider fails or access expires. */
    suspend fun exportToTree(document: DocumentEntity, treeUri: String) = withContext(Dispatchers.IO) {
        val source = File(document.pdfPath)
        require(source.isFile) { "Die lokale PDF wurde nicht gefunden." }
        val directory = DocumentFile.fromTreeUri(app, Uri.parse(treeUri))
            ?: throw IOException("Der Speicherordner ist nicht verfügbar. Bitte in den Einstellungen neu auswählen.")
        require(directory.canWrite()) { "Kein Schreibzugriff auf den Speicherordner. Bitte den Ordner erneut auswählen." }
        val base = displayName(document.name).removeSuffix(".pdf")
        var candidate = "$base.pdf"
        var suffix = 1
        while (directory.findFile(candidate) != null) {
            candidate = "$base (${suffix++}).pdf"
            require(suffix < 10_000) { "Zu viele gleichnamige Dateien im Speicherordner." }
        }
        val target = directory.createFile("application/pdf", candidate) ?: throw IOException("Die Datei konnte im Speicherordner nicht angelegt werden.")
        try {
            val output = app.contentResolver.openOutputStream(target.uri, "w") ?: throw IOException("Der Speicherordner ist nicht beschreibbar.")
            output.use { out -> source.inputStream().use { input -> input.copyTo(out) } }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    /** Never removes a live draft. Recent incoming captures are protected while processing is in flight. */
    suspend fun clearTemporaryFiles() = writes.withLock {
        withContext(Dispatchers.IO) {
            val active = readDraft()?.pages.orEmpty().flatMap { listOf(File(it.originalPath).canonicalPath, File(it.processedPath).canonicalPath) }.toSet()
            val before = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            sessionDirectory.walkBottomUp().filter { it.isFile }.forEach { file ->
                if (file.canonicalPath !in active && file.lastModified() < before) file.delete()
            }
            pdfDirectory.listFiles()?.filter { it.name.endsWith(".part") && it.lastModified() < before }?.forEach { it.delete() }
            File(app.cacheDir, "shared").listFiles()?.filter { it.lastModified() < before }?.forEach { it.delete() }
        }
    }

    private fun safeDelete(file: File, directory: File) {
        if (file.canonicalPath.startsWith(directory.canonicalPath + File.separator)) file.delete()
    }

    private fun sync(file: File) { FileOutputStream(file, true).use { it.fd.sync() } }

    private fun createThumbnail(pdf: File, destination: File) {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    val scale = 360f / max(page.width, page.height)
                    val bitmap = Bitmap.createBitmap(max(1, (page.width * scale).toInt()), max(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        FileOutputStream(destination).use { stream ->
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Vorschau konnte nicht gespeichert werden." }
                            stream.fd.sync()
                        }
                    } finally { bitmap.recycle() }
                }
            }
        }
    }

    private fun decodeBounded(file: File, limit: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Eine Bilddatei ist beschädigt oder wird nicht unterstützt." }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= limit) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Das gescannte Bild konnte nicht gelesen werden.")
        if (max(bitmap.width, bitmap.height) <= limit) return bitmap
        val factor = limit.toFloat() / max(bitmap.width, bitmap.height)
        return Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * factor).toInt()), max(1, (bitmap.height * factor).toInt()), true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }

    internal fun pageSize(width: Int, height: Int, paper: PaperSize): PDRectangle {
        require(width > 0 && height > 0)
        if (paper == PaperSize.AUTO) {
            val scale = 842f / max(width, height)
            return PDRectangle(width * scale, height * scale)
        }
        val standard = if (paper == PaperSize.A4) PDRectangle.A4 else PDRectangle.A5
        return if (width > height) PDRectangle(standard.height, standard.width) else standard
    }

    internal fun displayName(value: String): String {
        val fallback = "Scan_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date())
        val base = value.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .removeSuffix(".pdf").removeSuffix(".PDF").trim().take(160).ifBlank { fallback }
        return "$base.pdf"
    }
}
