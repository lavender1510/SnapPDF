package de.snappdf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import de.snappdf.data.DocumentEntity
import de.snappdf.data.ScanSession
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable fun HomeScreen(
    documents: List<DocumentEntity>, session: ScanSession?, onScan: () -> Unit, onImport: () -> Unit,
    onSettings: () -> Unit, onLibrary: () -> Unit, onResume: () -> Unit, onOpen: (DocumentEntity) -> Unit
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.Rounded.Home, null) }, label = { Text(tr("Start", "Home")) })
            NavigationBarItem(selected = false, onClick = onLibrary, icon = { Icon(Icons.Rounded.FolderOpen, null) }, label = { Text(tr("Bibliothek", "Library")) })
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).widthIn(max = 700.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(15.dp)) {
                    Icon(Icons.Rounded.DocumentScanner, null, Modifier.padding(11.dp).size(28.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("SnapPDF", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Scan. Save. Done.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, tr("Einstellungen", "Settings")) }
            }
            Text(tr("Dein Papier.\nEinfach digital.", "Your paper.\nSimply digital."), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(tr("Ein Foto. Eine klare PDF. Alles auf deinem Gerät.", "One photo. One clear PDF. All on your device."), Modifier.padding(top = 12.dp, bottom = 26.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(onClick = onScan, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
                Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DocumentScanner, null, Modifier.size(36.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(tr("Dokument scannen", "Scan document"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(tr("Aufnehmen. Optimieren. Fertig.", "Capture. Enhance. Done."), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    }
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null)
                }
            }
            OutlinedButton(onClick = onImport, Modifier.fillMaxWidth().padding(top = 12.dp).height(58.dp), shape = RoundedCornerShape(18.dp)) {
                Icon(Icons.Rounded.AddPhotoAlternate, null, Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp)); Text(tr("Bild importieren", "Import image"), style = MaterialTheme.typography.titleSmall)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.VerifiedUser, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tr("100 % offline · Ohne Konto", "100% offline · No account"), Modifier.padding(start = 7.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (session != null && session.pages.isNotEmpty()) {
                Card(onClick = onResume, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.padding(bottom = 14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.EditNote, null)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(tr("Scan fortsetzen", "Continue scan"), fontWeight = FontWeight.SemiBold)
                            Text("${session.pages.size} " + tr("Seiten im Entwurf", "pages in your draft"), style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Zuletzt gespeichert", "Recently saved"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = onLibrary) { Text(tr("Alle ansehen", "View all")) }
            }
            if (documents.isEmpty()) {
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(62.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Description, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Text(tr("Platz für deinen ersten Scan", "Room for your first scan"), Modifier.padding(top = 16.dp), fontWeight = FontWeight.SemiBold)
                        Text(tr("Deine PDFs findest du hier wieder.", "Your saved PDFs will appear here."), Modifier.padding(top = 5.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else documents.take(5).forEach { document ->
                Card(onClick = { onOpen(document) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp), modifier = Modifier.padding(bottom = 9.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(File(document.thumbnailPath), tr("Dokumentenvorschau", "Document preview"), Modifier.size(48.dp, 62.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainer))
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(document.name, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                            Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(document.createdAt)) + " · ${document.pageCount} " + tr("Seiten", "pages"), Modifier.padding(top = 5.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
