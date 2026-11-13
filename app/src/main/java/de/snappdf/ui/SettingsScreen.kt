package de.snappdf.ui

import android.os.Build
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.snappdf.BuildConfig
import de.snappdf.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    store: SettingsStore,
    repository: DocumentRepository,
    onBack: () -> Unit,
    onChooseFolder: () -> Unit,
    onManageDocuments: () -> Unit = onBack,
    onPinShortcut: () -> Unit = {}
) {
    val settings by store.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var clearing by remember { mutableStateOf(false) }
    var privacyInfo by remember { mutableStateOf(false) }
    val cleanupMessage = tr("Temporäre Daten gelöscht. Dokumente und laufende Scans bleiben erhalten.", "Temporary data cleared. Documents and scans in progress are kept.")
    val cleanupFailure = tr("Temporäre Daten konnten nicht gelöscht werden.", "Could not clear temporary data.")
    Scaffold(
        topBar = { TopAppBar(title = { Text(tr("Einstellungen", "Settings")) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("Zurück", "Back")) } }) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                SettingsSection(title = tr("Allgemein", "General"), icon = Icons.Outlined.Palette) {
                    SettingsChoice(
                        title = tr("Darstellung", "Appearance"), value = settings.theme,
                        options = listOf(ThemeMode.SYSTEM to tr("Systemstandard", "System default"), ThemeMode.LIGHT to tr("Hell", "Light"), ThemeMode.DARK to tr("Dunkel", "Dark")),
                        onSelected = { theme -> store.update { it.copy(theme = theme) } }
                    )
                    SettingsDivider()
                    SettingsChoice(
                        title = tr("Sprache", "Language"), value = settings.language,
                        options = listOf(AppLanguage.SYSTEM to tr("Systemstandard", "System default"), AppLanguage.GERMAN to "Deutsch", AppLanguage.ENGLISH to "English"),
                        onSelected = { language -> store.update { it.copy(language = language) } }
                    )
                    SettingsDivider()
                    SettingsToggle(title = tr("Dynamische Farben", "Dynamic colors"),
                        description = if (Build.VERSION.SDK_INT >= 31) tr("Akzentfarbe deines Android-Hintergrunds verwenden", "Use your Android wallpaper accent color") else tr("Ab Android 12 verfügbar", "Available on Android 12 and later"),
                        checked = settings.dynamicColors, enabled = Build.VERSION.SDK_INT >= 31,
                        onChecked = { value -> store.update { it.copy(dynamicColors = value) } })
                    SettingsDivider()
                    SettingsToggle(title = tr("Animationen reduzieren", "Reduce motion"), checked = settings.reduceMotion,
                        description = tr("Ruhige Übergänge und weniger Bewegung", "Calmer transitions with less movement"),
                        onChecked = { value -> store.update { it.copy(reduceMotion = value) } })
                }
            }
            item {
                SettingsSection(title = tr("Scanner", "Scanner"), icon = Icons.Outlined.DocumentScanner) {
                    SettingsChoice(title = tr("Standard-Scanmodus", "Default scan mode"), value = settings.defaultMode,
                        options = listOf(ScanMode.STANDARD to tr("Standard", "Standard"), ScanMode.HANDWRITING to tr("Handschrift", "Handwriting"), ScanMode.HIGH_CONTRAST to tr("Hoher Kontrast", "High contrast")),
                        onSelected = { value -> store.update { it.copy(defaultMode = value) } })
                    SettingsDivider()
                    SettingsToggle(title = tr("Dokumente erkennen", "Detect documents"), checked = settings.detectDocument,
                        description = tr("Papierkanten in der Kamera automatisch markieren", "Automatically mark paper edges in the camera"),
                        onChecked = { value -> store.update { it.copy(detectDocument = value, autoCapture = it.autoCapture && value, skipPreview = it.skipPreview && value) } })
                    SettingsDivider()
                    SettingsToggle(title = tr("Perspektive korrigieren", "Correct perspective"), checked = settings.correctPerspective,
                        description = tr("Erkannte Dokumente zuschneiden und entzerren", "Crop and straighten detected documents"),
                        onChecked = { value -> store.update { it.copy(correctPerspective = value) } })
                    SettingsDivider()
                    SettingsToggle(title = tr("Automatische Aufnahme", "Automatic capture"), checked = settings.autoCapture, enabled = settings.detectDocument,
                        description = tr("Aufnehmen, sobald das Dokument vollständig und ruhig erkannt wird", "Capture once the full document is detected and held steady"),
                        onChecked = { value -> store.update { it.copy(autoCapture = value) } })
                    SettingsDivider()
                    SettingsChoice(title = tr("Blitz beim Start", "Initial flash mode"), value = settings.flash,
                        options = listOf(FlashMode.OFF to tr("Aus", "Off"), FlashMode.AUTO to tr("Automatisch", "Automatic"), FlashMode.ON to tr("Ein", "On")),
                        onSelected = { value -> store.update { it.copy(flash = value) } })
                }
            }
            item {
                SettingsSection(title = tr("Schnellscan", "Quick scan"), icon = Icons.Outlined.Bolt) {
                    SettingsAction(title = tr("Scan-Verknüpfung", "Scan shortcut"),
                        description = tr("Kamera direkt vom Startbildschirm öffnen", "Open the camera directly from your home screen"),
                        icon = Icons.Outlined.AddToHomeScreen, onClick = onPinShortcut)
                    SettingsDivider()
                    SettingsToggle(title = tr("Direkt mit Kamera starten", "Open camera on launch"), checked = settings.openCameraOnStart,
                        description = tr("Beim Öffnen sofort bereit zum Scannen", "Be ready to scan as soon as you open SnapPDF"),
                        onChecked = { value -> store.update { it.copy(openCameraOnStart = value) } })
                    SettingsDivider()
                    SettingsToggle(title = tr("Scanmodus merken", "Remember scan mode"), checked = settings.rememberMode,
                        description = tr("Zuletzt gewählten Modus beim nächsten Scan verwenden", "Use the last selected mode for the next scan"),
                        onChecked = { value -> store.update { it.copy(rememberMode = value) } })
                    SettingsDivider()
                    SettingsToggle(title = tr("Vorschau überspringen", "Skip preview"), checked = settings.skipPreview, enabled = settings.detectDocument && settings.autoSave,
                        description = tr("Bei automatischer Speicherung direkt zur PDF. Nur sicher erkannte, scharfe Dokumente überspringen die Vorschau.", "Go straight to PDF with automatic saving. Only confidently detected, sharp documents skip preview."),
                        onChecked = { value -> store.update { it.copy(skipPreview = value) } })
                    SettingsDivider()
                    SettingsToggle(title = tr("Automatisch speichern", "Save automatically"), checked = settings.autoSave,
                        description = tr("Nach Abschluss mit automatisch erzeugtem Namen speichern. Die Vorschau bleibt währenddessen sichtbar.", "Save on completion with an automatically generated name. The preview stays visible while saving."),
                        onChecked = { value -> store.update { it.copy(autoSave = value, autoName = it.autoName || value, skipPreview = it.skipPreview && value) } })
                }
            }
            item {
                SettingsSection(title = tr("PDF und Speicherung", "PDF and storage"), icon = Icons.Outlined.PictureAsPdf) {
                    SettingsChoice(title = tr("PDF-Qualität", "PDF quality"), value = settings.quality,
                        description = tr("Standard hält Schrift lesbar und Dateien überschaubar.", "Standard keeps text readable and file sizes moderate."),
                        options = listOf(PdfQuality.COMPACT to tr("Kompakt", "Compact"), PdfQuality.STANDARD to tr("Standard", "Standard"), PdfQuality.HIGH to tr("Hoch", "High")),
                        onSelected = { value -> store.update { it.copy(quality = value) } })
                    SettingsDivider()
                    SettingsChoice(title = tr("Papiergröße", "Paper size"), value = settings.paperSize,
                        options = listOf(PaperSize.AUTO to tr("Automatisch", "Automatic"), PaperSize.A4 to "A4", PaperSize.A5 to "A5"),
                        onSelected = { value -> store.update { it.copy(paperSize = value) } })
                    SettingsDivider()
                    SettingsToggle(title = tr("Dateinamen automatisch erzeugen", "Generate file names"), checked = settings.autoName,
                        description = tr("Dokumente mit Datum und Uhrzeit benennen", "Name documents using the date and time"),
                        onChecked = { value -> store.update { it.copy(autoName = value, autoSave = it.autoSave && value, skipPreview = it.skipPreview && value) } })
                    SettingsDivider()
                    Row(Modifier.fillMaxWidth().clickable(onClick = onChooseFolder).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("Zusätzlicher Speicherort", "Additional save location"), style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(4.dp))
                            val folderLabel = if (settings.exportTreeUri.isBlank()) tr("Nur in SnapPDF • Ordner wählen", "Only in SnapPDF • Choose folder") else Uri.decode(Uri.parse(settings.exportTreeUri).lastPathSegment ?: settings.exportTreeUri)
                            Text(folderLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (settings.exportTreeUri.isNotBlank()) {
                                Text(tr("Neue PDFs werden zusätzlich hierhin exportiert.", "New PDFs are also exported here."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    if (settings.exportTreeUri.isNotBlank()) {
                        TextButton(onClick = { store.update { it.copy(exportTreeUri = "") } }, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)) {
                            Text(tr("Zusätzlichen Speicherort entfernen", "Remove additional save location"))
                        }
                    }
                }
            }
            item {
                SettingsSection(title = tr("Texterkennung", "Text recognition"), icon = Icons.AutoMirrored.Outlined.TextSnippet) {
                    SettingsToggle(title = tr("Offline-OCR aktivieren", "Enable offline OCR"), checked = settings.ocr,
                        description = tr("Gedruckten deutschen und englischen Text erkennen. PDFs werden durchsuchbar; erkannter Text lässt sich kopieren.", "Recognize printed German and English text. PDFs become searchable and recognized text can be copied."),
                        onChecked = { value -> store.update { it.copy(ocr = value) } })
                    Text(tr("Das Sprachmodell ist bereits enthalten. Die Erkennung erfolgt lokal beim Speichern und kann etwas länger dauern. Handschrift wird nicht zuverlässig erkannt.", "The language model is included. Recognition runs locally when saving and may take a little longer. Handwriting recognition is not reliable."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
                }
            }
            item {
                SettingsSection(title = tr("Datenschutz", "Privacy"), icon = Icons.Outlined.Shield) {
                    SettingsAction(title = tr("Temporäre Daten löschen", "Clear temporary data"),
                        description = tr("Gespeicherte PDFs und laufende Scans bleiben erhalten", "Saved PDFs and scans in progress are kept"),
                        icon = Icons.Outlined.CleaningServices, enabled = !clearing,
                        onClick = {
                            clearing = true
                            scope.launch {
                                try {
                                    repository.clearTemporaryFiles()
                                    snackbar.showSnackbar(cleanupMessage)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    snackbar.showSnackbar(error.localizedMessage ?: cleanupFailure)
                                } finally {
                                    clearing = false
                                }
                            }
                        })
                    if (clearing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    SettingsDivider()
                    SettingsAction(title = tr("Lokale Dokumente verwalten", "Manage local documents"),
                        description = tr("Über die Dokumentenbibliothek öffnen, exportieren oder löschen", "Open, export or delete files in your document library"),
                        icon = Icons.Outlined.FolderOpen, onClick = onManageDocuments)
                    SettingsDivider()
                    SettingsAction(title = tr("Alles bleibt auf deinem Gerät", "Everything stays on your device"),
                        description = tr("Informationen zur Offline-Verarbeitung", "About offline processing"),
                        icon = Icons.Outlined.PrivacyTip, onClick = { privacyInfo = true })
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("SnapPDF", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Scan. Save. Done. · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(tr("Ohne Konto. Ohne Werbung. Ohne Cloud.", "No account. No ads. No cloud."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (privacyInfo) {
        AlertDialog(onDismissRequest = { privacyInfo = false }, icon = { Icon(Icons.Outlined.Shield, null) },
            title = { Text(tr("Privat von Anfang an", "Private from the start")) },
            text = { Text(tr("Kamera, Kantenerkennung, Bildoptimierung, PDF-Erstellung und OCR funktionieren vollständig auf deinem Gerät. SnapPDF hat keine Internetberechtigung, verwendet keine Analyse- oder Werbedienste und benötigt kein Konto.\n\nFotos und PDFs liegen im privaten App-Speicher. Eine Deinstallation löscht diese lokalen Daten. Exportiere wichtige PDFs vorher über die System-Dateiauswahl. Nur du entscheidest, wann du Dateien über Android teilst oder exportierst.", "Camera, edge detection, image processing, PDF creation and OCR run entirely on your device. SnapPDF has no internet permission, uses no analytics or advertising services and requires no account.\n\nPhotos and PDFs are stored in private app storage. Uninstalling the app deletes this local data. Export important PDFs using the system file picker first. You decide when to share or export files through Android.")) },
            confirmButton = { TextButton(onClick = { privacyInfo = false }) { Text(tr("Verstanden", "Got it")) } })
    }
}

@Composable
private fun SettingsSection(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Row(Modifier.padding(start = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
}

@Composable
private fun SettingsToggle(title: String, checked: Boolean, onChecked: (Boolean) -> Unit, description: String? = null, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChecked).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f))
            if (description != null) {
                Spacer(Modifier.height(4.dp))
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.45f))
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun SettingsAction(title: String, description: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun <T> SettingsChoice(title: String, value: T, options: List<Pair<T, String>>, onSelected: (T) -> Unit, description: String? = null) {
    var showOptions by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { showOptions = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text(options.firstOrNull { it.first == value }?.second.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            if (description != null) {
                Spacer(Modifier.height(4.dp))
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.Outlined.UnfoldMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (showOptions) {
        AlertDialog(onDismissRequest = { showOptions = false }, title = { Text(title) },
            text = {
                Column {
                    options.forEach { (option, label) ->
                        Row(Modifier.fillMaxWidth().selectable(selected = option == value, role = Role.RadioButton, onClick = { onSelected(option); showOptions = false }).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = option == value, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { showOptions = false }) { Text(tr("Abbrechen", "Cancel")) } })
    }
}
