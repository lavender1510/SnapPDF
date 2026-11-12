package de.snappdf.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import de.snappdf.data.DetectionResult
import de.snappdf.data.DocumentQuad
import de.snappdf.data.FlashMode
import de.snappdf.processing.DocumentProcessor
import de.snappdf.ui.tr
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val ScannerBlue = Color(0xFF7EA6FF)

/** Camera stays bound for the entire multipage session. All detection runs off the UI thread. */
@Composable
fun CameraScreen(
    pagesCount: Int,
    autoCapture: Boolean,
    detectionEnabled: Boolean,
    flashDefault: FlashMode,
    onCapture: (File) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    imageProcessor: DocumentProcessor
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    var permission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = it
        denied = !it
    }
    val currentCapture by rememberUpdatedState(onCapture)
    val currentDetectionEnabled by rememberUpdatedState(detectionEnabled)
    var camera by remember { mutableStateOf<Camera?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(flashDefault) }
    var error by remember { mutableStateOf<String?>(null) }
    var detection by remember { mutableStateOf<DetectionResult?>(null) }
    var frameAspect by remember { mutableFloatStateOf(3f / 4f) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var stableProgress by remember { mutableFloatStateOf(0f) }
    var bindAttempt by remember { mutableIntStateOf(0) }
    val stability = remember { CaptureStability() }
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val permissionError = tr("Die Kamera benötigt deine Erlaubnis.", "Camera access needs your permission.")
    val unavailableError = tr("Die Kamera ist nicht verfügbar. Schließe andere Kamera-Apps und versuche es erneut.", "Camera unavailable. Close other camera apps and try again.")
    val captureError = tr("Die Aufnahme konnte nicht gespeichert werden. Prüfe den freien Speicherplatz.", "Could not save the photo. Check the available storage space.")
    val latestUnavailable by rememberUpdatedState(unavailableError)
    val latestCaptureError by rememberUpdatedState(captureError)
    val takePhoto: () -> Unit = {
        val imageCapture = capture
        if (!capturing && imageCapture != null) {
            capturing = true
            stability.didCapture(detection?.quad)
            stableProgress = 0f
            val incoming = File(context.filesDir, "sessions/incoming")
            if (!incoming.isDirectory && !incoming.mkdirs()) {
                capturing = false
                error = latestCaptureError
            } else {
                val file = File(incoming, "capture-${UUID.randomUUID()}.jpg")
                try {
                    imageCapture.takePicture(
                        ImageCapture.OutputFileOptions.Builder(file).build(), mainExecutor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                capturing = false
                                error = null
                                currentCapture(file)
                            }
                            override fun onError(exception: ImageCaptureException) {
                                capturing = false
                                file.delete()
                                error = latestCaptureError
                            }
                        }
                    )
                } catch (_: Exception) {
                    capturing = false
                    file.delete()
                    error = latestCaptureError
                }
            }
        }
    }
    val currentTakePhoto by rememberUpdatedState(takePhoto)
    val currentAutoCapture by rememberUpdatedState(autoCapture)

    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { if (!permission) permissionRequest.launch(Manifest.permission.CAMERA) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) { onDispose { analyzerExecutor.shutdown() } }
    LaunchedEffect(flash, capture) {
        capture?.flashMode = when (flash) {
            FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
        }
    }
    DisposableEffect(permission, previewSize, lifecycleOwner, bindAttempt) {
        val active = AtomicBoolean(true)
        var provider: ProcessCameraProvider? = null
        var boundCases: List<androidx.camera.core.UseCase> = emptyList()
        if (permission && previewSize.width > 0 && previewSize.height > 0) {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                if (active.get()) {
                    try {
                        provider = providerFuture.get()
                        if (provider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) != true) {
                            error = latestUnavailable
                        } else {
                            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
                            val preview = Preview.Builder().setTargetRotation(rotation).build()
                                .also { it.surfaceProvider = previewView.surfaceProvider }
                            val photo = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                .setTargetRotation(rotation).build()
                            val analysis = ImageAnalysis.Builder()
                                .setTargetResolution(android.util.Size(960, 1280))
                                .setTargetRotation(rotation)
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                            var lastAnalysis = 0L
                            analysis.setAnalyzer(analyzerExecutor) { image ->
                                try {
                                    val now = SystemClock.elapsedRealtime()
                                    if (active.get() && currentDetectionEnabled && now - lastAnalysis >= 220) {
                                        lastAnalysis = now
                                        val bitmap = analysisBitmap(image)
                                        try {
                                            val result = imageProcessor.detect(bitmap)
                                            val aspect = bitmap.width.toFloat() / bitmap.height
                                            mainExecutor.execute {
                                                if (active.get()) {
                                                    frameAspect = aspect
                                                    detection = result
                                                    val progress = stability.update(result, SystemClock.elapsedRealtime())
                                                    stableProgress = if (currentAutoCapture) progress else 0f
                                                    if (currentAutoCapture && progress >= 1f) currentTakePhoto()
                                                }
                                            }
                                        } finally { bitmap.recycle() }
                                    }
                                } catch (_: Exception) {
                                    // Detection is advisory; manual capture always remains available.
                                } finally { image.close() }
                            }
                            val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(photo).addUseCase(analysis)
                            previewView.viewPort?.let { group.setViewPort(it) }
                            boundCases = listOf(preview, photo, analysis)
                            camera = provider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group.build())
                            capture = photo
                            error = null
                        }
                    } catch (_: Exception) { error = latestUnavailable }
                }
            }, mainExecutor)
        }
        onDispose {
            active.set(false)
            capture = null
            camera = null
            provider?.unbind(*boundCases.toTypedArray())
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF101318)).systemBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Zurück", "Back"), tint = Color.White) }
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(tr("Dokument scannen", "Scan document"), color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(tr("Scan. Save. Done.", "Scan. Save. Done."), color = Color(0xFF98A6BA), style = MaterialTheme.typography.labelMedium)
                }
                IconButton(
                    enabled = camera?.cameraInfo?.hasFlashUnit() == true,
                    onClick = { flash = FlashMode.entries[(flash.ordinal + 1) % FlashMode.entries.size] }
                ) {
                    val icon = when (flash) { FlashMode.OFF -> Icons.Rounded.FlashOff; FlashMode.AUTO -> Icons.Rounded.FlashAuto; FlashMode.ON -> Icons.Rounded.FlashOn }
                    Icon(icon, tr("Blitz: ", "Flash: ") + flash.name, tint = if (camera?.cameraInfo?.hasFlashUnit() == true) Color.White else Color.Gray)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.Black)) {
                if (permission) {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier.fillMaxSize().onSizeChanged { previewSize = it }
                            .pointerInput(camera) {
                                detectTapGestures { offset ->
                                    camera?.cameraControl?.startFocusAndMetering(
                                        FocusMeteringAction.Builder(
                                            previewView.meteringPointFactory.createPoint(offset.x, offset.y),
                                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                                        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                                    )
                                }
                            }
                    )
                    if (detectionEnabled) DetectionOverlay(detection?.quad, frameAspect, stableProgress)
                    if (capture == null && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = ScannerBlue)
                    if (capture == null && error != null) {
                        Button(onClick = { error = null; bindAttempt++ }, modifier = Modifier.align(Alignment.Center)) {
                            Text(tr("Erneut versuchen", "Try again"))
                        }
                    }
                } else {
                    Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Rounded.PhotoCamera, null, tint = ScannerBlue, modifier = Modifier.size(56.dp))
                        Text(permissionError, color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Button(onClick = { permissionRequest.launch(Manifest.permission.CAMERA) }) { Text(tr("Kamera erlauben", "Allow camera")) }
                        if (denied) TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        }) { Text(tr("App-Einstellungen öffnen", "Open app settings"), color = ScannerBlue) }
                    }
                }
                if (permission) {
                    val result = detection
                    val hint = when {
                        error != null -> error!!
                        capturing -> tr("Aufnahme wird gespeichert …", "Saving photo …")
                        !detectionEnabled -> tr("Dokument ausrichten und aufnehmen", "Align the document and capture")
                        result != null && result.brightness < 55 -> tr("Mehr Licht verbessert deinen Scan", "More light will improve your scan")
                        result != null && result.blurScore < 45 -> tr("Halte die Kamera ruhig · Tippen zum Fokussieren", "Hold still · Tap to focus")
                        result?.quad == null -> tr("Alle vier Dokumentenecken ins Bild bringen", "Keep all four document corners in view")
                        autoCapture && stableProgress > 0 -> tr("Ruhig halten …", "Hold still …")
                        autoCapture -> tr("Automatische Aufnahme · Dokument ausrichten", "Automatic capture · Align the document")
                        else -> tr("Dokument erkannt · Bereit zum Scannen", "Document detected · Ready to scan")
                    }
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                        color = Color(0xDB172033), shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(hint, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.width(90.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Layers, null, tint = ScannerBlue)
                    Text("$pagesCount " + tr(if (pagesCount == 1) "Seite" else "Seiten", if (pagesCount == 1) "page" else "pages"), color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
                val shutterDescription = tr("Dokument fotografieren", "Capture document")
                Box(
                    Modifier.size(80.dp).clip(CircleShape).background(Color.White.copy(alpha = if (capture != null && !capturing) 1f else .3f))
                        .clickable(enabled = capture != null && !capturing, onClick = takePhoto)
                        .semantics { contentDescription = shutterDescription }, contentAlignment = Alignment.Center
                ) {
                    if (capturing) CircularProgressIndicator(Modifier.size(40.dp), color = Color(0xFF2563EB))
                    else Icon(Icons.Rounded.DocumentScanner, null, tint = Color(0xFF2563EB), modifier = Modifier.size(38.dp))
                }
                TextButton(onClick = onDone, enabled = pagesCount > 0 && !capturing, modifier = Modifier.width(90.dp)) {
                    Text(tr("Fertig", "Done"), color = if (pagesCount > 0 && !capturing) ScannerBlue else Color.Gray, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DetectionOverlay(quad: DocumentQuad?, aspect: Float, progress: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val imageWidth = min(size.width, size.height * aspect)
        val imageHeight = imageWidth / aspect
        val left = (size.width - imageWidth) / 2f
        val top = (size.height - imageHeight) / 2f
        if (quad != null) {
            val points = quad.points().map { Offset(left + it.x * imageWidth, top + it.y * imageHeight) }
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            val color = if (progress > .1f) Color(0xFF6FE1B8) else ScannerBlue
            drawPath(path, color.copy(alpha = .12f))
            drawPath(path, color, style = Stroke(2.dp.toPx()))
            points.forEach { drawCircle(color, 5.dp.toPx(), it) }
        } else {
            val marginX = imageWidth * .12f
            val marginY = imageHeight * .12f
            val corners = listOf(
                Offset(left + marginX, top + marginY), Offset(left + imageWidth - marginX, top + marginY),
                Offset(left + imageWidth - marginX, top + imageHeight - marginY), Offset(left + marginX, top + imageHeight - marginY)
            )
            val segment = 26.dp.toPx()
            corners.forEachIndexed { i, p ->
                drawLine(Color.White.copy(alpha = .6f), p, p + Offset(if (i == 0 || i == 3) segment else -segment, 0f), 3.dp.toPx())
                drawLine(Color.White.copy(alpha = .6f), p, p + Offset(0f, if (i < 2) segment else -segment), 3.dp.toPx())
            }
        }
        if (progress > 0f) drawArc(Color(0xFF6FE1B8), -90f, progress * 360f, false, Offset(size.width / 2 - 24.dp.toPx(), 20.dp.toPx()), androidx.compose.ui.geometry.Size(48.dp.toPx(), 48.dp.toPx()), style = Stroke(4.dp.toPx()))
    }
}

/** Crop first, then rotate: detector and preview share the CameraX ViewPort. */
private fun analysisBitmap(image: ImageProxy): Bitmap {
    val crop = image.cropRect
    val plane = image.planes[0]
    val buffer = plane.buffer
    val stride = plane.rowStride
    val pixelStride = plane.pixelStride
    val pixels = IntArray(crop.width() * crop.height())
    for (y in 0 until crop.height()) {
        val row = (crop.top + y) * stride
        for (x in 0 until crop.width()) {
            val value = buffer.get(row + (crop.left + x) * pixelStride).toInt() and 0xff
            pixels[y * crop.width() + x] = (0xff shl 24) or (value shl 16) or (value shl 8) or value
        }
    }
    val raw = Bitmap.createBitmap(pixels, crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
    val rotation = image.imageInfo.rotationDegrees
    if (rotation == 0) return raw
    val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    if (upright !== raw) raw.recycle()
    return upright
}

private class CaptureStability {
    private var previous: DocumentQuad? = null
    private var anchor: DocumentQuad? = null
    private var captured: DocumentQuad? = null
    private var stableSince = 0L
    private var capturedAt = 0L

    fun didCapture(quad: DocumentQuad?) {
        captured = quad
        capturedAt = SystemClock.elapsedRealtime()
        stableSince = 0L
        anchor = null
    }

    fun update(result: DetectionResult, now: Long): Float {
        val quad = result.quad
        if (quad == null) {
            captured = null
            previous = null
            stableSince = 0L
            anchor = null
            return 0f
        }
        if (captured != null && distance(captured!!, quad) > .075f) captured = null
        val safe = result.confidence >= .8f && result.blurScore >= 45 && result.brightness >= 55 &&
            quad.points().all { it.x in .02f.. .98f && it.y in .02f.. .98f }
        val moved = previous == null || distance(previous!!, quad) > .015f || (anchor != null && distance(anchor!!, quad) > .02f)
        previous = quad
        if (!safe || moved || captured != null || now - capturedAt < 1800) {
            stableSince = 0L
            anchor = null
            return 0f
        }
        if (stableSince == 0L) {
            stableSince = now
            anchor = quad
        }
        return ((now - stableSince) / 1100f).coerceIn(0f, 1f)
    }

    private fun distance(a: DocumentQuad, b: DocumentQuad): Float = a.points().zip(b.points()).maxOf { (p, q) -> max(abs(p.x - q.x), abs(p.y - q.y)) }
}
