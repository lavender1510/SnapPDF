package de.snappdf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import de.snappdf.data.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun EditorScreen(
    session: ScanSession, busy: Boolean, settings: AppSettings,
    onBack: () -> Unit, onDiscard: () -> Unit, onCamera: () -> Unit, onImport: () -> Unit,
    onCrop: (Int) -> Unit, onRetake: (Int) -> Unit, onMode: (Int, ScanMode) -> Unit,
    onContrast: (Int, Float) -> Unit, onRotate: (Int) -> Unit, onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit, onTitle: (String) -> Unit, onSave: (PdfQuality, PaperSize, Boolean) -> Unit
) {
    val largeText = LocalDensity.current.fontScale > 1.3f
    var selected by rememberSaveable(session.id) { mutableIntStateOf(0) }
    val index = selected.coerceIn(0, (session.pages.size - 1).coerceAtLeast(0))
    val page = session.pages.getOrNull(index)
    var saveDialog by remember { mutableStateOf(false) }
    var discardDialog by remember { mutableStateOf(false) }
    var removeDialog by remember { mutableStateOf(false) }
    BackHandler { onBack() }
    Scaffold(topBar = {
        TopAppBar(title = { Column { Text(tr("Scan bearbeiten", "Review scan"), maxLines = 1, overflow = TextOverflow.Ellipsis); if (!largeText) Text("${session.pages.size} " + tr("Seiten · Entwurf gespeichert", "pages · Draft saved"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Zurück", "Back")) } },
            actions = { IconButton(onClick = { discardDialog = true }, enabled = !busy) { Icon(Icons.Rounded.DeleteOutline, tr("Entwurf verwerfen", "Discard draft")) } })
    }, bottomBar = {
        Surface(shadowElevation = 4.dp) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 10.dp))
                if (largeText) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onCamera, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                            Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text(tr("Seite hinzufügen", "Add page"))
                        }
                        Button(onClick = { saveDialog = true }, enabled = !busy && session.pages.isNotEmpty() && session.pages.all { it.processedPath.isNotBlank() }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(16.dp)) {
                            Text(tr("PDF speichern", "Save PDF"))
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onCamera, enabled = !busy, modifier = Modifier.size(54.dp), contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Rounded.Add, tr("Weitere Seite scannen", "Scan another page"))
                        }
                        Button(onClick = { saveDialog = true }, enabled = !busy && session.pages.isNotEmpty() && session.pages.all { it.processedPath.isNotBlank() }, modifier = Modifier.weight(1f).heightIn(min = 54.dp), shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Rounded.PictureAsPdf, null); Spacer(Modifier.width(8.dp)); Text(tr("PDF speichern", "Save PDF"))
                        }
                    }
                }
            }
        }
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val scrollContent = maxHeight < 520.dp || largeText
            Column(if (scrollContent) Modifier.fillMaxSize().verticalScroll(rememberScrollState()) else Modifier.fillMaxSize()) {
            if (page != null) {
                Box((if (scrollContent) Modifier.height(240.dp) else Modifier.weight(1f)).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                    AsyncImage(File(page.processedPath.ifBlank { page.originalPath }), tr("Vorschau der gescannten Seite", "Scanned page preview"), Modifier.fillMaxSize().padding(12.dp), contentScale = ContentScale.Fit)
                    if (page.confidence < 0.75f) Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .95f)) {
                        Text(tr("Ränder bitte prüfen und bei Bedarf zuschneiden.", "Check the edges and adjust the crop if needed."), Modifier.padding(10.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScanMode.entries.forEach { mode -> FilterChip(selected = mode == page.mode, onClick = { onMode(index, mode) }, enabled = !busy, label = { Text(modeLabel(mode)) }) }
                }
                var contrast by remember(page.id, page.contrast) { mutableFloatStateOf(page.contrast) }
                Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Contrast, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(tr("Kontrast", "Contrast"), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium)
                    Slider(value = contrast, onValueChange = { contrast = it }, onValueChangeFinished = { if (contrast != page.contrast) onContrast(index, contrast) }, enabled = !busy, modifier = Modifier.weight(1f).padding(start = 12.dp))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly) {
                    EditorTool(Icons.Rounded.Crop, tr("Zuschnitt", "Crop"), !busy) { onCrop(index) }
                    EditorTool(Icons.Rounded.RotateRight, tr("Drehen", "Rotate"), !busy) { onRotate(index) }
                    EditorTool(Icons.Rounded.Replay, tr("Neu scannen", "Rescan"), !busy) { onRetake(index) }
                    EditorTool(Icons.Rounded.DeleteOutline, tr("Entfernen", "Remove"), !busy) { removeDialog = true }
                }
            } else {
                Column((if (scrollContent) Modifier.heightIn(min = 220.dp) else Modifier.weight(1f)).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Rounded.NoteAdd, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(tr("Füge deine erste Seite hinzu.", "Add your first page."), Modifier.padding(20.dp))
                    OutlinedButton(onImport) { Text(tr("Bilder importieren", "Import images")) }
                }
            }
            if (session.pages.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    itemsIndexed(session.pages, key = { _, p -> p.id }) { position, p ->
                        Card(onClick = { selected = position }, colors = CardDefaults.cardColors(containerColor = if (position == index) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(10.dp)) {
                            Column(Modifier.padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                AsyncImage(File(p.processedPath.ifBlank { p.originalPath }), null, Modifier.size(36.dp, 45.dp), contentScale = ContentScale.Fit)
                                Text("${position + 1}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    item { IconButton(onImport, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) { Icon(Icons.Rounded.AddPhotoAlternate, tr("Importieren", "Import")) } }
                }
                if (session.pages.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onMove(index, -1); selected = (index - 1).coerceAtLeast(0) }, enabled = index > 0 && !busy, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Seite nach vorne", "Move page earlier"), Modifier.size(18.dp)) }
                    Text(tr("Seite ${index + 1} von ${session.pages.size}", "Page ${index + 1} of ${session.pages.size}"), style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = { onMove(index, 1); selected = (index + 1).coerceAtMost(session.pages.lastIndex) }, enabled = index < session.pages.lastIndex && !busy, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, tr("Seite nach hinten", "Move page later"), Modifier.size(18.dp)) }
                }
            }
            }
        }
    }
    if (saveDialog) SaveDialog(session.title, settings, onTitle, { saveDialog = false }) { quality, paper, ocr -> saveDialog = false; onSave(quality, paper, ocr) }
    if (discardDialog) AlertDialog(onDismissRequest = { discardDialog = false }, title = { Text(tr("Entwurf verwerfen?", "Discard draft?")) }, text = { Text(tr("Alle noch nicht als PDF gespeicherten Seiten werden gelöscht.", "All pages that have not been saved as a PDF will be deleted.")) }, confirmButton = { TextButton(onClick = { discardDialog = false; onDiscard() }) { Text(tr("Verwerfen", "Discard")) } }, dismissButton = { TextButton(onClick = { discardDialog = false }) { Text(tr("Behalten", "Keep")) } })
    if (removeDialog) AlertDialog(onDismissRequest = { removeDialog = false }, title = { Text(tr("Seite entfernen?", "Remove page?")) }, text = { Text(tr("Seite ${index + 1} wird aus diesem Entwurf entfernt.", "Page ${index + 1} will be removed from this draft.")) }, confirmButton = { TextButton(onClick = { removeDialog = false; onRemove(index) }) { Text(tr("Entfernen", "Remove")) } }, dismissButton = { TextButton(onClick = { removeDialog = false }) { Text(tr("Abbrechen", "Cancel")) } })
}

@Composable private fun EditorTool(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, enabled: Boolean, action: () -> Unit) {
    TextButton(onClick = action, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null, Modifier.size(22.dp)); Text(title, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 3.dp)) }
    }
}

@Composable fun modeLabel(mode: ScanMode): String = when (mode) {
    ScanMode.STANDARD -> tr("Standard", "Standard")
    ScanMode.HANDWRITING -> tr("Handschrift", "Handwriting")
    ScanMode.HIGH_CONTRAST -> tr("Hoher Kontrast", "High contrast")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun SaveDialog(title: String, settings: AppSettings, onTitle: (String) -> Unit, dismiss: () -> Unit, save: (PdfQuality, PaperSize, Boolean) -> Unit) {
    var quality by remember { mutableStateOf(settings.quality) }
    var paper by remember { mutableStateOf(settings.paperSize) }
    var ocr by remember { mutableStateOf(settings.ocr) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(tr("Als PDF speichern", "Save as PDF")) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            OutlinedTextField(title, onTitle, label = { Text(tr("Dateiname", "File name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(tr("Qualität", "Quality"), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { PdfQuality.entries.forEach { q -> FilterChip(q == quality, { quality = q }, label = { Text(when(q) { PdfQuality.COMPACT -> tr("Kompakt", "Compact"); PdfQuality.STANDARD -> "Standard"; PdfQuality.HIGH -> tr("Hoch", "High") }) }) } }
            Text(tr("Papierformat", "Paper size"), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { PaperSize.entries.forEach { p -> FilterChip(p == paper, { paper = p }, label = { Text(if (p == PaperSize.AUTO) tr("Auto", "Auto") else p.name) }) } }
            Row(verticalAlignment = Alignment.CenterVertically) { Text(tr("Text erkennen (offline)", "Recognize text (offline)"), Modifier.weight(1f)); Switch(ocr, { ocr = it }) }
            Text(tr("OCR erkennt gedruckten deutschen und englischen Text. Handschrift kann unzuverlässig sein.", "OCR recognizes printed German and English text. Handwriting can be unreliable."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = { save(quality, paper, ocr) }, enabled = title.isNotBlank()) { Text(tr("Speichern", "Save")) } }, dismissButton = { TextButton(dismiss) { Text(tr("Abbrechen", "Cancel")) } })
}
