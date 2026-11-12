package de.snappdf.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import de.snappdf.data.DocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PdfReaderScreen(document: DocumentEntity, onBack: () -> Unit, onShare: () -> Unit, onExport: () -> Unit) {
    var showText by remember { mutableStateOf(false) }
    var zoom by remember(document.id) { mutableFloatStateOf(1f) }
    val clipboard = LocalClipboardManager.current
    val copyLabel = tr("Text kopieren", "Copy text")
    Scaffold(topBar = {
        TopAppBar(title = { Text(document.name, maxLines = 1) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Zurück", "Back")) } }, actions = {
            if (document.ocrText.isNotBlank()) IconButton({ showText = true }) { Icon(Icons.Rounded.TextSnippet, tr("Erkannten Text anzeigen", "Show recognized text")) }
            IconButton(onExport) { Icon(Icons.Rounded.SaveAlt, tr("PDF exportieren", "Export PDF")) }
            IconButton(onShare) { Icon(Icons.Rounded.Share, tr("PDF teilen", "Share PDF")) }
        })
    }, bottomBar = {
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { zoom = (zoom - .25f).coerceAtLeast(1f) }, enabled = zoom > 1f) {
                    Icon(Icons.Rounded.ZoomOut, tr("Verkleinern", "Zoom out"))
                }
                TextButton(onClick = { zoom = 1f }) {
                    Text("${(zoom * 100).roundToInt()} %", Modifier.widthIn(min = 64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                IconButton(onClick = { zoom = (zoom + .25f).coerceAtMost(2f) }, enabled = zoom < 2f) {
                    Icon(Icons.Rounded.ZoomIn, tr("Vergrößern", "Zoom in"))
                }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surfaceContainer).testTag("pdf-page-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items((0 until document.pageCount).toList(), key = { it }) { index ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PdfPage(document.pdfPath, index, zoom)
                    Text("${index + 1} / ${document.pageCount}", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (showText) AlertDialog(onDismissRequest = { showText = false }, title = { Text(tr("Erkannter Text", "Recognized text")) }, text = {
        LazyColumn(Modifier.heightIn(max = 400.dp)) { item { androidx.compose.foundation.text.selection.SelectionContainer { Text(document.ocrText) } } }
    }, confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(document.ocrText)); showText = false }) { Text(copyLabel) } }, dismissButton = { TextButton(onClick = { showText = false }) { Text(tr("Schließen", "Close")) } })
}

private data class RenderedPage(val bitmap: Bitmap? = null, val error: Boolean = false)

@Composable private fun PdfPage(path: String, index: Int, zoom: Float) {
    var rendered by remember(path, index) { mutableStateOf(RenderedPage()) }
    LaunchedEffect(path, index) {
        rendered = withContext(Dispatchers.IO) {
            try {
                val bitmap = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        renderer.openPage(index).use { page ->
                            val factor = 1800f / max(page.width, page.height)
                            Bitmap.createBitmap(max(1, (page.width * factor).roundToInt()), max(1, (page.height * factor).roundToInt()), Bitmap.Config.ARGB_8888).also {
                                it.eraseColor(Color.WHITE)
                                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
                RenderedPage(bitmap)
            } catch (_: Exception) { RenderedPage(error = true) }
        }
    }
    // LazyColumn disposes offscreen pages; pixel storage is released with their state.
    val bitmap = rendered.bitmap
    if (bitmap != null) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val pageWidth = maxWidth * zoom
            val pageHeight = pageWidth * bitmap.height.toFloat() / bitmap.width
            val horizontalScroll = rememberScrollState()
            LaunchedEffect(zoom) { if (zoom == 1f) horizontalScroll.scrollTo(0) }
            // The page takes its actual scaled height in the list. Vertical swipes
            // scroll naturally; at larger sizes, a horizontal swipe pans the page.
            // No all-direction gesture detector intercepts normal document scrolling.
            Row(Modifier.fillMaxWidth().then(if (zoom > 1f) Modifier.horizontalScroll(horizontalScroll) else Modifier)) {
                Image(bitmap.asImageBitmap(), tr("PDF-Seite ${index + 1}", "PDF page ${index + 1}"),
                    Modifier.width(pageWidth).height(pageHeight).clip(RoundedCornerShape(3.dp)), contentScale = ContentScale.Fit)
            }
        }
    } else Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
        if (rendered.error) Text(tr("Diese Seite konnte nicht geöffnet werden.", "This page could not be opened.")) else CircularProgressIndicator()
    }
}

@Composable fun SavedScreen(document: DocumentEntity, onHome: () -> Unit, onOpen: () -> Unit, onShare: () -> Unit, onExport: () -> Unit, onScan: () -> Unit) {
    Scaffold { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
        val viewportHeight = maxHeight
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewportHeight).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp)) {
                Icon(Icons.Rounded.Check, null, Modifier.padding(18.dp).size(42.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(tr("Gescannt. Gespeichert.", "Scanned. Saved."), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(tr("Deine PDF ist bereit.", "Your PDF is ready."), Modifier.padding(top = 6.dp, bottom = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            AsyncImage(File(document.thumbnailPath), null, Modifier.height(170.dp).fillMaxWidth(), contentScale = ContentScale.Fit)
            Text(document.name, Modifier.padding(top = 18.dp), style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Text("${document.pageCount} " + tr("Seiten", "pages") + " · " + android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, document.byteSize), Modifier.padding(top = 6.dp, bottom = 22.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onShare, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(8.dp)); Text(tr("PDF teilen", "Share PDF")) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onOpen) { Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Öffnen", "Open")) }
                TextButton(onExport) { Icon(Icons.Rounded.SaveAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Exportieren", "Export")) }
            }
            OutlinedButton(onScan, Modifier.fillMaxWidth().padding(top = 12.dp).height(50.dp)) { Text(tr("Nächster Scan", "Next scan")) }
            TextButton(onHome, Modifier.padding(top = 6.dp)) { Text(tr("Zur Startseite", "Back to home")) }
        }
        }
    }
}
