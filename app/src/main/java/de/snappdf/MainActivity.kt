package de.snappdf

import android.content.ClipData
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon as AndroidIcon
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.snappdf.data.*
import de.snappdf.scanner.CameraScreen
import de.snappdf.scanner.CropScreen
import de.snappdf.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private val directScan = mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        directScan.value = intent.action == "de.snappdf.SCAN"
        setSnapContent((application as SnapApplication).settings) { SnapApp(directScan.value, { directScan.value = false }) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); if (intent.action == "de.snappdf.SCAN") directScan.value = true }
}

@Composable private fun SnapApp(directScan: Boolean, consumeDirectScan: () -> Unit, model: ScanViewModel = viewModel()) {
    val settings by model.settings.settings.collectAsStateWithLifecycle()
    SnapTheme(settings) {
        val context = LocalContext.current
        val activity = context as ComponentActivity
        val ready by model.ready.collectAsStateWithLifecycle()
        val session by model.session.collectAsStateWithLifecycle()
        val busy by model.busy.collectAsStateWithLifecycle()
        val saved by model.saved.collectAsStateWithLifecycle()
        val notice by model.notice.collectAsStateWithLifecycle()
        val documents by model.repository.documents.collectAsStateWithLifecycle(emptyList())
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        var route by rememberSaveable { mutableStateOf("home") }
        var readerId by rememberSaveable { mutableStateOf("") }
        var cropIndex by rememberSaveable { mutableIntStateOf(0) }
        var retakeIndex by rememberSaveable { mutableIntStateOf(-1) }
        var bootHandled by rememberSaveable { mutableStateOf(false) }
        var autoFinish by rememberSaveable { mutableStateOf(false) }
        var photoImport by remember { mutableStateOf(false) }
        var exportId by rememberSaveable { mutableStateOf("") }

        val lightStatus = MaterialTheme.colorScheme.background.luminance() > .5f
        SideEffect {
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = route != "camera" && lightStatus
                isAppearanceLightNavigationBars = route != "camera" && lightStatus
            }
        }

        val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            val requestedId = exportId
            if (uri != null && requestedId.isNotBlank()) scope.launch {
                try {
                    val document = model.repository.documents.first().firstOrNull { it.id == requestedId }
                        ?: error("Dokument nicht mehr verfügbar")
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                            File(document.pdfPath).inputStream().use { it.copyTo(output) }
                        } ?: error("Exportziel nicht verfügbar")
                    }
                    model.report("PDF exportiert.", "PDF exported.")
                } catch (_: Exception) { model.report("Export fehlgeschlagen. Die PDF bleibt in SnapPDF gespeichert.", "Export failed. The PDF remains saved in SnapPDF.") }
            }
            exportId = ""
        }
        val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    model.settings.update { it.copy(exportTreeUri = uri.toString()) }
                } catch (_: Exception) { model.report("Für diesen Ordner konnte kein dauerhafter Zugriff gespeichert werden.", "Persistent access could not be saved for this folder.") }
            }
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
            if (uris.isNotEmpty()) {
                photoImport = true
                route = "editor"
                model.importImages(uris).invokeOnCompletion { photoImport = false }
            }
        }
        val launchImport: () -> Unit = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        val open: (DocumentEntity) -> Unit = { readerId = it.id; route = "reader" }
        val export: (DocumentEntity) -> Unit = { exportId = it.id; exporter.launch(it.name) }
        val share: (DocumentEntity) -> Unit = { document ->
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(document.pdfPath), document.name)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_TITLE, document.name)
                    clipData = ClipData.newRawUri(document.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, document.name))
            } catch (_: Exception) { model.report("Die PDF konnte nicht geteilt werden. Bitte prüfe, ob die Datei noch existiert.", "The PDF could not be shared. Please check that the file still exists.") }
        }
        val startCamera: () -> Unit = { retakeIndex = -1; model.clearSaved(); route = "camera" }
        val pinShortcut: () -> Unit = {
            val manager = context.getSystemService(ShortcutManager::class.java)
            if (manager.isRequestPinShortcutSupported) {
                val info = ShortcutInfo.Builder(context, "scan").setShortLabel("SnapPDF Scan")
                    .setIcon(AndroidIcon.createWithResource(context, R.drawable.ic_scan))
                    .setIntent(Intent(context, MainActivity::class.java).setAction("de.snappdf.SCAN")).build()
                manager.requestPinShortcut(info, null)
            } else model.report("Dein Launcher unterstützt keine angehefteten Verknüpfungen. Halte das App-Symbol gedrückt, um den Scanner zu öffnen.", "Your launcher does not support pinned shortcuts. Long-press the app icon to open the scanner.")
        }
        LaunchedEffect(ready, directScan) {
            if (ready && (directScan || !bootHandled)) {
                if (directScan || settings.openCameraOnStart) { retakeIndex = -1; route = "camera" }
                bootHandled = true
                if (directScan) consumeDirectScan()
            }
        }
        LaunchedEffect(saved?.id) { if (saved != null) { route = "saved"; autoFinish = false } }
        LaunchedEffect(busy, route, saved) {
            if (route == "saving" && !busy && saved == null) route = if (session != null) "editor" else "home"
        }
        LaunchedEffect(notice) {
            notice?.let { snackbar.showSnackbar(if (settings.language == AppLanguage.GERMAN || settings.language == AppLanguage.SYSTEM && java.util.Locale.getDefault().language == "de") it.de else it.en); model.clearNotice() }
        }
        // Completion waits for every queued image operation. Reliable automatic
        // scans may skip review; every other page is shown for confirmation.
        LaunchedEffect(autoFinish, busy, session?.pages) {
            if (autoFinish && !busy && !photoImport && session?.pages?.isNotEmpty() == true) {
                autoFinish = false
                val pages = session!!.pages
                if (settings.autoSave && settings.autoName && (!settings.skipPreview || pages.all { it.safeForAutoSave })) {
                    route = if (settings.skipPreview) "saving" else "editor"
                    model.save()
                } else route = "editor"
            }
        }
        BackHandler(enabled = route != "home" && route != "camera" && route != "crop" && route != "editor" && route != "library") {
            if (!busy) { route = "home"; model.clearSaved() }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                if (!ready) LoadingScreen()
                else when (route) {
                    "camera" -> CameraScreen(session?.pages?.size ?: 0, settings.autoCapture, settings.detectDocument, settings.flash,
                        onCapture = { file ->
                            val replace = retakeIndex.takeIf { it >= 0 }
                            model.addCapture(file, replace)
                            if (replace != null) { retakeIndex = -1; route = "editor" }
                        }, onDone = { retakeIndex = -1; autoFinish = true; route = "editor" }, onBack = { retakeIndex = -1; route = if (session?.pages?.isNotEmpty() == true) "editor" else "home" }, imageProcessor = model.processor)
                    "editor" -> {
                        val active = session
                        if (active == null) {
                            if (busy || photoImport) LoadingScreen() else LaunchedEffect(Unit) { route = "home" }
                        } else EditorScreen(active, busy, settings,
                            onBack = { route = "home" }, onDiscard = { model.discard(); route = "home" }, onCamera = startCamera,
                            onImport = launchImport, onCrop = { cropIndex = it; route = "crop" },
                            onRetake = { retakeIndex = it; route = "camera" },
                            onMode = { index, mode -> model.changePage(index, mode = mode) },
                            onContrast = { index, contrast -> model.changePage(index, contrast = contrast) },
                            onRotate = { model.changePage(it, rotate = true) }, onMove = { index, offset -> model.movePage(index, offset) },
                            onRemove = { model.removePage(it) }, onTitle = model::setTitle,
                            onSave = { quality, paper, ocr -> model.settings.update { it.copy(quality = quality, paperSize = paper, ocr = ocr) }; model.save() })
                    }
                    "crop" -> {
                        val page = session?.pages?.getOrNull(cropIndex)
                        if (page != null) CropScreen(page, onApply = { model.changePage(cropIndex, quad = it); route = "editor" }, onBack = { route = "editor" })
                        else LaunchedEffect(Unit) { route = "editor" }
                    }
                    "library" -> LibraryScreen(model.repository, onBack = { route = "home" }, onOpen = open, onShare = share, onExport = export)
                    "settings" -> SettingsScreen(model.settings, model.repository, onBack = { route = "home" }, onChooseFolder = { directoryPicker.launch(null) }, onManageDocuments = { route = "library" }, onPinShortcut = pinShortcut)
                    "reader" -> {
                        val document = documents.find { it.id == readerId } ?: saved?.takeIf { it.id == readerId }
                        if (document != null) PdfReaderScreen(document, { route = "library" }, { share(document) }, { export(document) })
                        else LoadingScreen()
                    }
                    "saved" -> saved?.let { document -> SavedScreen(document, { model.clearSaved(); route = "home" }, { open(document) }, { share(document) }, { export(document) }, startCamera) }
                        ?: LaunchedEffect(Unit) { route = "home" }
                    "saving" -> LoadingScreen()
                    else -> HomeScreen(documents, session, startCamera, launchImport, { route = "settings" }, { route = "library" }, { route = "editor" }, open)
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
            }
        }
    }
}

@Composable private fun LoadingScreen() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(); Text(tr("Einen Moment …", "One moment …"), Modifier.padding(top = 16.dp))
    }
}
