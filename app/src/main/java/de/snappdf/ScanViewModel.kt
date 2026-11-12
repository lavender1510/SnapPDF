package de.snappdf

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.snappdf.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class Notice(val de: String, val en: String)

class ScanViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SnapApplication
    val repository = app.repository
    val settings = app.settings
    val processor = app.processor
    private val lock = Mutex()
    private val _session = MutableStateFlow<ScanSession?>(null)
    val session: StateFlow<ScanSession?> = _session
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready
    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice
    private val _saved = MutableStateFlow<DocumentEntity?>(null)
    val saved: StateFlow<DocumentEntity?> = _saved
    private var titleJob: Job? = null
    private val pendingOperations = AtomicInteger(0)

    init {
        operation {
            _session.value = repository.loadDraft()
            // A crash after the camera wrote an original must not lose that page.
            _session.value?.pages?.filter { it.processedPath.isEmpty() }?.forEach { pending ->
                runCatching { processPending(pending) }.onFailure {
                    _notice.value = Notice("Eine Seite konnte nicht wiederhergestellt werden. Bitte erneut scannen oder entfernen.", "A page could not be restored. Please rescan or remove it.")
                }
            }
        }.invokeOnCompletion { _ready.value = true }
    }

    fun clearNotice() { _notice.value = null }
    fun report(de: String, en: String) { _notice.value = Notice(de, en) }
    fun clearSaved() { _saved.value = null }

    private fun newSession(): ScanSession {
        val title = "Scan_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date())
        return ScanSession(UUID.randomUUID().toString(), if (settings.settings.value.autoName) title else "")
    }

    fun addCapture(file: File, replaceIndex: Int? = null) = operation { appendOriginal(file, replaceIndex) }

    fun importImages(uris: List<Uri>) = operation {
        for (uri in uris) {
            try {
                val incoming = File(app.filesDir, "sessions/incoming").apply { mkdirs() }
                val target = File(incoming, "import-${UUID.randomUUID()}.img")
                try {
                    withContext(Dispatchers.IO) {
                        app.contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(32 * 1024)
                                var total = 0L
                                while (true) {
                                    val length = input.read(buffer)
                                    if (length < 0) break
                                    total += length
                                    if (total > 80L * 1024 * 1024) throw IOException("Bild überschreitet 80 MB.")
                                    output.write(buffer, 0, length)
                                }
                            }
                        } ?: throw IOException("Bild ist nicht verfügbar.")
                    }
                    appendOriginal(target, null)
                } catch (e: Throwable) {
                    if (_session.value?.pages?.none { it.originalPath == target.absolutePath } != false) target.delete()
                    throw e
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _notice.value = Notice("Ein Bild konnte nicht importiert werden. Bereits importierte Seiten bleiben erhalten. ${e.message.orEmpty()}", "An image could not be imported. Previously imported pages have been kept.")
            }
        }
    }

    private suspend fun appendOriginal(file: File, replaceIndex: Int?) {
        val current = _session.value ?: newSession()
        val config = settings.settings.value
        // Validate before registering an unreadable import as a draft page.
        withContext(Dispatchers.Default) { processor.loadBitmap(file, 128).recycle() }
        val previous = replaceIndex?.let { current.pages.getOrNull(it) }
        val pending = ScanPage(UUID.randomUUID().toString(), file.absolutePath, "",
            previous?.mode ?: config.defaultMode, previous?.contrast ?: 0.5f)
        if (previous != null) {
            // A failed retake must leave the previously successful page in both
            // the live session and its persisted draft. Commit only the result.
            val replacement = processOriginal(pending).copy(id = previous.id)
            replacePage(previous.id, replacement)
            return
        }
        val pages = current.pages + pending
        val updated = current.copy(pages = pages)
        repository.saveDraft(updated)
        _session.value = updated
        processPending(pending)
    }

    private suspend fun processPending(pending: ScanPage) {
        replacePage(pending.id, processOriginal(pending).copy(id = pending.id))
    }

    private suspend fun processOriginal(pending: ScanPage): ScanPage {
        val config = settings.settings.value
        val output = newOutput()
        return processor.process(File(pending.originalPath), output, pending.mode, pending.contrast,
            if (!config.detectDocument || !config.correctPerspective) DocumentQuad.FULL else null, pending.rotation)
    }

    private fun newOutput(): File = File(app.filesDir, "sessions/processed").apply { mkdirs() }
        .let { File(it, "${UUID.randomUUID()}.png") }

    fun changePage(index: Int, mode: ScanMode? = null, contrast: Float? = null, quad: DocumentQuad? = null, rotate: Boolean = false) = operation {
        val page = _session.value?.pages?.getOrNull(index) ?: return@operation
        val nextMode = mode ?: page.mode
        val output = newOutput()
        val result = processor.process(File(page.originalPath), output, nextMode, contrast ?: page.contrast,
            quad ?: page.quad, if (rotate) (page.rotation + 90) % 360 else page.rotation)
        replacePage(page.id, result.copy(id = page.id, confidence = page.confidence, safeForAutoSave = false))
        if (mode != null && settings.settings.value.rememberMode) settings.update { it.copy(defaultMode = mode) }
    }

    private suspend fun replacePage(id: String, replacement: ScanPage) {
        val current = _session.value ?: return
        val previous = current.pages.firstOrNull { it.id == id }
        val updated = current.copy(pages = current.pages.map { if (it.id == id) replacement else it })
        repository.saveDraft(updated)
        _session.value = updated
        previous?.let { cleanUnreferenced(listOf(it.originalPath, it.processedPath), updated) }
    }

    fun movePage(index: Int, offset: Int) = operation {
        val current = _session.value ?: return@operation
        val destination = index + offset
        if (index !in current.pages.indices || destination !in current.pages.indices) return@operation
        val pages = current.pages.toMutableList()
        pages.add(destination, pages.removeAt(index))
        val updated = current.copy(pages = pages)
        repository.saveDraft(updated)
        _session.value = updated
    }

    fun removePage(index: Int) = operation {
        val current = _session.value ?: return@operation
        val removed = current.pages.getOrNull(index) ?: return@operation
        val updated = current.copy(pages = current.pages.filterIndexed { position, _ -> position != index })
        repository.saveDraft(updated)
        _session.value = updated
        cleanUnreferenced(listOf(removed.originalPath, removed.processedPath), updated)
    }

    private suspend fun cleanUnreferenced(paths: List<String>, active: ScanSession) = withContext(Dispatchers.IO) {
        val referenced = active.pages.flatMap { listOf(it.originalPath, it.processedPath) }.toSet()
        val directory = File(app.filesDir, "sessions").canonicalPath + File.separator
        paths.filter { it.isNotBlank() && it !in referenced }.forEach { path ->
            runCatching { File(path).let { if (it.canonicalPath.startsWith(directory)) it.delete() } }
        }
    }

    fun setTitle(title: String) {
        _session.value = _session.value?.copy(title = title.take(120))
        titleJob?.cancel()
        titleJob = viewModelScope.launch {
            delay(300)
            lock.withLock {
                try { _session.value?.let { repository.saveDraft(it) } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { report("Der Dateiname konnte noch nicht im Entwurf gesichert werden. Bitte den Speicherplatz prüfen.", "The file name could not be saved in the draft. Please check free storage.") }
            }
        }
    }

    fun discard() = operation {
        titleJob?.cancel()
        repository.clearDraft()
        _session.value = null
    }

    fun save() = operation {
        val current = _session.value ?: return@operation
        require(current.pages.isNotEmpty()) { "Bitte zuerst eine Seite scannen." }
        require(current.pages.all { it.processedPath.isNotEmpty() }) { "Eine Seite ist noch nicht verarbeitet. Bitte erneut scannen oder entfernen." }
        val config = settings.settings.value
        var final = current
        if (config.ocr) {
            val pages = current.pages.map { page ->
                page.copy(ocrLines = processor.recognize(File(page.processedPath)))
            }
            final = current.copy(pages = pages)
            _session.value = final
            repository.saveDraft(final)
        }
        val document = repository.savePdf(final, config)
        _saved.value = document
        _session.value = null
        titleJob?.cancel()
        runCatching { repository.clearDraft() }
        if (config.exportTreeUri.isNotBlank()) {
            try { repository.exportToTree(document, config.exportTreeUri) }
            catch (e: Exception) {
                _notice.value = Notice("Die PDF ist in SnapPDF gespeichert. Die Kopie im gewählten Ordner ist fehlgeschlagen. Bitte erneut exportieren.", "The PDF is saved in SnapPDF. Copying to your selected folder failed. Please export it again.")
            }
        }
    }

    private fun operation(block: suspend () -> Unit): Job {
        pendingOperations.incrementAndGet()
        _busy.value = true
        return viewModelScope.launch {
        lock.withLock {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: OutOfMemoryError) {
                _notice.value = Notice("Nicht genügend Arbeitsspeicher. Bitte eine kleinere Aufnahme verwenden. Der Entwurf bleibt erhalten.", "Not enough memory. Please use a smaller image. Your draft has been kept.")
            }
            catch (e: Exception) {
                _notice.value = Notice("${e.message ?: "Der Vorgang ist fehlgeschlagen."} Der Entwurf bleibt erhalten. Prüfe auch den freien Speicherplatz.", "The operation failed. Your draft has been kept. Check free storage and try again.")
            }
            finally { _busy.value = pendingOperations.decrementAndGet() > 0 }
        }
        }
    }
}
