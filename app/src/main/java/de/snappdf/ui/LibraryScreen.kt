package de.snappdf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.MergeType
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import de.snappdf.data.DocumentEntity
import de.snappdf.data.DocumentRepository
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class LibrarySort { NEWEST, OLDEST, NAME }

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    repository: DocumentRepository,
    onBack: () -> Unit,
    onOpen: (DocumentEntity) -> Unit,
    onShare: (DocumentEntity) -> Unit,
    onExport: (DocumentEntity) -> Unit
) {
    val documents by repository.documents.collectAsState(initial = emptyList())
    val folders by repository.folders.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(LibrarySort.NEWEST) }
    var sortMenu by remember { mutableStateOf(false) }
    var folderFilter by remember { mutableStateOf("all") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var renameDocument by remember { mutableStateOf<DocumentEntity?>(null) }
    var deleteDocuments by remember { mutableStateOf<List<DocumentEntity>?>(null) }
    var moveDocuments by remember { mutableStateOf<List<DocumentEntity>?>(null) }
    var createFolder by remember { mutableStateOf(false) }
    var mergeDocuments by remember { mutableStateOf<List<DocumentEntity>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val failure = tr("Das hat nicht geklappt. Bitte versuche es erneut.", "That did not work. Please try again.")
    val done = tr("Erledigt", "Done")
    fun runAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                action()
                selected = emptySet()
                busy = false
                snackbar.showSnackbar(done)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                snackbar.showSnackbar(error.localizedMessage?.takeIf { it.isNotBlank() } ?: failure)
            } finally {
                busy = false
            }
        }
    }
    val visible = remember(documents, query, folderFilter, sort) {
        val filtered = documents.filter { document ->
            (folderFilter == "all" || (folderFilter == "unfiled" && document.folderId == null) || document.folderId == folderFilter) &&
                (document.name.contains(query.trim(), ignoreCase = true) || document.ocrText.contains(query.trim(), ignoreCase = true))
        }
        when (sort) {
            LibrarySort.NEWEST -> filtered.sortedByDescending { it.createdAt }
            LibrarySort.OLDEST -> filtered.sortedBy { it.createdAt }
            LibrarySort.NAME -> filtered.sortedBy { it.name.lowercase(Locale.getDefault()) }
        }
    }
    LaunchedEffect(documents) {
        selected = selected.intersect(documents.map { it.id }.toSet())
    }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    val english = LocalEnglish.current
    val selectedDocuments = selected.mapNotNull { id -> documents.firstOrNull { it.id == id } }
    fun toggleSelection(document: DocumentEntity) {
        selected = if (document.id in selected) selected - document.id else selected + document.id
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (selected.isEmpty()) tr("Dokumente", "Documents") else tr("${selected.size} ausgewählt", "${selected.size} selected"))
                },
                navigationIcon = {
                    IconButton(onClick = { if (selected.isNotEmpty()) selected = emptySet() else onBack() }) {
                        Icon(if (selected.isEmpty()) Icons.AutoMirrored.Outlined.ArrowBack else Icons.Outlined.Close, tr("Zurück", "Back"))
                    }
                },
                actions = {
                    if (selected.isEmpty()) {
                        IconButton(onClick = { createFolder = true }, enabled = !busy) {
                            Icon(Icons.Outlined.CreateNewFolder, tr("Ordner erstellen", "Create folder"))
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, tr("Sortieren", "Sort")) }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                listOf(
                                    LibrarySort.NEWEST to tr("Neueste zuerst", "Newest first"),
                                    LibrarySort.OLDEST to tr("Älteste zuerst", "Oldest first"),
                                    LibrarySort.NAME to tr("Name A–Z", "Name A–Z")
                                ).forEach { (value, title) ->
                                    DropdownMenuItem(text = { Text(title) }, onClick = { sort = value; sortMenu = false },
                                        trailingIcon = { if (sort == value) Icon(Icons.Outlined.Check, null) })
                                }
                            }
                        }
                    } else {
                        IconButton(onClick = { selected = visible.map { it.id }.toSet() }, enabled = !busy) {
                            Icon(Icons.Outlined.SelectAll, tr("Alle auswählen", "Select all"))
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (selectedDocuments.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { mergeDocuments = selectedDocuments }, enabled = !busy && selectedDocuments.size >= 2) {
                            Icon(Icons.AutoMirrored.Outlined.MergeType, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Verbinden", "Merge"))
                        }
                        TextButton(onClick = { moveDocuments = selectedDocuments }, enabled = !busy) {
                            Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Verschieben", "Move"))
                        }
                        IconButton(onClick = { deleteDocuments = selectedDocuments }, enabled = !busy) {
                            Icon(Icons.Outlined.DeleteOutline, tr("Auswahl löschen", "Delete selection"), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                placeholder = { Text(tr("Dokumente und Text durchsuchen", "Search documents and text")) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, tr("Suche leeren", "Clear search")) } },
                shape = RoundedCornerShape(18.dp), singleLine = true
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = folderFilter == "all", onClick = { folderFilter = "all" }, label = { Text(tr("Alle", "All")) }) }
                item { FilterChip(selected = folderFilter == "unfiled", onClick = { folderFilter = "unfiled" }, label = { Text(tr("Ohne Ordner", "Unfiled")) }) }
                items(folders, key = { it.id }) { folder ->
                    FilterChip(selected = folderFilter == folder.id, onClick = { folderFilter = folder.id },
                        label = { Text(folder.name) }, leadingIcon = { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp)) })
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            if (visible.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(Icons.Outlined.Description, null, Modifier.padding(24.dp).size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.height(22.dp))
                    Text(if (query.isNotBlank()) tr("Keine Treffer", "No results") else tr("Platz für deine Dokumente", "A place for your documents"), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(if (query.isNotBlank()) tr("Versuche einen anderen Suchbegriff.", "Try a different search term.") else tr("Gespeicherte Scans erscheinen hier. Alles bleibt auf deinem Gerät.", "Saved scans appear here. Everything stays on your device."),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(tr("${visible.size} Dokumente", "${visible.size} documents"), modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(visible, key = { it.id }) { document ->
                        var menu by remember(document.id) { mutableStateOf(false) }
                        val isSelected = document.id in selected
                        val pageLabel = if (document.pageCount == 1) tr("1 Seite", "1 page") else tr("${document.pageCount} Seiten", "${document.pageCount} pages")
                        val date = remember(document.createdAt, english) { DateFormat.getDateInstance(DateFormat.MEDIUM, if (english) Locale.ENGLISH else Locale.GERMAN).format(Date(document.createdAt)) }
                        Surface(
                            shape = RoundedCornerShape(20.dp), tonalElevation = if (isSelected) 4.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).combinedClickable(
                                enabled = !busy,
                                onClick = { if (selected.isNotEmpty()) toggleSelection(document) else onOpen(document) },
                                onLongClick = { toggleSelection(document) },
                                onLongClickLabel = tr("Dokument auswählen", "Select document")
                            )
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(model = File(document.thumbnailPath), contentDescription = null, contentScale = ContentScale.Fit,
                                    modifier = Modifier.size(width = 52.dp, height = 70.dp).clip(RoundedCornerShape(6.dp)).background(androidx.compose.ui.graphics.Color.White))
                                Column(Modifier.weight(1f).padding(start = 14.dp, end = 4.dp)) {
                                    Text(document.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(6.dp))
                                    Text("$date · $pageLabel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(formatDocumentSize(document.byteSize), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (selected.isNotEmpty()) {
                                    Checkbox(checked = isSelected, onCheckedChange = { toggleSelection(document) }, enabled = !busy)
                                } else {
                                    Box {
                                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, tr("Dokumentaktionen", "Document actions")) }
                                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                            DropdownMenuItem(text = { Text(tr("Öffnen", "Open")) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) }, onClick = { menu = false; onOpen(document) })
                                            DropdownMenuItem(text = { Text(tr("Teilen", "Share")) }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = { menu = false; onShare(document) })
                                            DropdownMenuItem(text = { Text(tr("Speichern unter …", "Save as…")) }, leadingIcon = { Icon(Icons.Outlined.SaveAlt, null) }, onClick = { menu = false; onExport(document) })
                                            DropdownMenuItem(text = { Text(tr("Umbenennen", "Rename")) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; renameDocument = document })
                                            DropdownMenuItem(text = { Text(tr("In Ordner verschieben", "Move to folder")) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) }, onClick = { menu = false; moveDocuments = listOf(document) })
                                            DropdownMenuItem(text = { Text(tr("Auswählen", "Select")) }, leadingIcon = { Icon(Icons.Outlined.CheckCircleOutline, null) }, onClick = { menu = false; toggleSelection(document) })
                                            HorizontalDivider()
                                            DropdownMenuItem(text = { Text(tr("Löschen", "Delete"), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }, onClick = { menu = false; deleteDocuments = listOf(document) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    renameDocument?.let { document ->
        LibraryNameDialog(title = tr("Dokument umbenennen", "Rename document"), initial = document.name.removeSuffix(".pdf"),
            onDismiss = { renameDocument = null }, onConfirm = { name -> renameDocument = null; runAction { repository.rename(document, name) } })
    }
    if (createFolder) {
        LibraryNameDialog(title = tr("Neuer Ordner", "New folder"), initial = "", onDismiss = { createFolder = false },
            onConfirm = { name -> createFolder = false; runAction { repository.createFolder(name) } })
    }
    mergeDocuments?.let { selectedDocs ->
        LibraryNameDialog(title = tr("PDFs verbinden", "Merge PDFs"), initial = tr("Zusammengeführtes Dokument", "Merged document"),
            detail = tr("${selectedDocs.size} PDFs werden in der Reihenfolge deiner Auswahl verbunden. Die Originale bleiben erhalten.", "${selectedDocs.size} PDFs will be merged in selection order. The originals are kept."),
            onDismiss = { mergeDocuments = null }, onConfirm = { name -> mergeDocuments = null; runAction { repository.merge(selectedDocs, name) } })
    }
    deleteDocuments?.let { toDelete ->
        AlertDialog(onDismissRequest = { deleteDocuments = null }, icon = { Icon(Icons.Outlined.DeleteOutline, null) },
            title = { Text(if (toDelete.size == 1) tr("Dokument löschen?", "Delete document?") else tr("${toDelete.size} Dokumente löschen?", "Delete ${toDelete.size} documents?")) },
            text = { Text(tr("Die PDFs werden dauerhaft aus SnapPDF entfernt. Bereits exportierte Kopien bleiben erhalten.", "The PDFs will be permanently removed from SnapPDF. Previously exported copies are kept.")) },
            confirmButton = { TextButton(onClick = { deleteDocuments = null; runAction { toDelete.forEach { repository.delete(it) } } }) { Text(tr("Löschen", "Delete"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteDocuments = null }) { Text(tr("Abbrechen", "Cancel")) } })
    }
    moveDocuments?.let { toMove ->
        AlertDialog(onDismissRequest = { moveDocuments = null }, title = { Text(tr("In Ordner verschieben", "Move to folder")) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    item {
                        TextButton(onClick = { moveDocuments = null; runAction { toMove.forEach { repository.move(it, null) } } }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.FolderOff, null); Spacer(Modifier.width(10.dp)); Text(tr("Ohne Ordner", "Unfiled"))
                        }
                    }
                    items(folders, key = { it.id }) { folder ->
                        TextButton(onClick = { moveDocuments = null; runAction { toMove.forEach { repository.move(it, folder.id) } } }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.FolderOpen, null); Spacer(Modifier.width(10.dp)); Text(folder.name)
                        }
                    }
                    if (folders.isEmpty()) item { Text(tr("Erstelle zuerst einen Ordner über das Ordnersymbol oben.", "Create a folder using the folder icon above."), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp)) }
                }
            }, confirmButton = { TextButton(onClick = { moveDocuments = null }) { Text(tr("Schließen", "Close")) } })
    }
}

@Composable
private fun LibraryNameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit, detail: String? = null) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (detail != null) Text(detail)
            OutlinedTextField(value = value, onValueChange = { value = it.take(120) }, singleLine = true,
                label = { Text(tr("Name", "Name")) }, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = { onConfirm(value.trim()) }, enabled = value.trim().isNotEmpty()) { Text(tr("Speichern", "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen", "Cancel")) } })
}

internal fun formatDocumentSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> "${(bytes + 1023) / 1024} KB"
    else -> "$bytes B"
}
