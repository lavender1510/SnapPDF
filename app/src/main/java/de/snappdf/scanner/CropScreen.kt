package de.snappdf.scanner

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import de.snappdf.data.DocumentQuad
import de.snappdf.data.ScanPage
import de.snappdf.data.ScanPoint
import de.snappdf.processing.DocumentProcessor
import de.snappdf.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/** Corners always refer to the upright, unwarped original, independent of preview scaling. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(page: ScanPage, onApply: (DocumentQuad) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(page.originalPath) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(page.originalPath) { mutableStateOf(false) }
    val corners = remember(page.id) { mutableStateListOf<ScanPoint>().apply { addAll(page.quad.points()) } }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var dragging by remember { mutableIntStateOf(-1) }
    val touchRadius = with(LocalDensity.current) { 56.dp.toPx() }
    val imageRect = remember(bitmap, viewSize) {
        val source = bitmap
        if (source == null || viewSize.width == 0 || viewSize.height == 0) Rect.Zero else {
            val scale = min(viewSize.width.toFloat() / source.width, viewSize.height.toFloat() / source.height)
            val w = source.width * scale
            val h = source.height * scale
            Rect((viewSize.width - w) / 2, (viewSize.height - h) / 2, (viewSize.width + w) / 2, (viewSize.height + h) / 2)
        }
    }
    LaunchedEffect(page.originalPath) {
        try {
            bitmap = withContext(Dispatchers.IO) { DocumentProcessor(context).loadBitmap(File(page.originalPath), 1800) }
        } catch (_: Exception) { failed = true }
    }
    DisposableEffect(bitmap) {
        val held = bitmap
        onDispose { held?.recycle() }
    }
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Dokument zuschneiden", "Crop document")) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Zurück", "Back")) } }
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(tr("Ziehe die vier Punkte an die Papierecken.", "Drag the four points to the paper corners."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { corners.clear(); corners.addAll(DocumentQuad.FULL.points()) },
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp), enabled = bitmap != null
                    ) { Icon(Icons.Rounded.CropFree, null); Spacer(Modifier.width(8.dp)); Text(tr("Ganzes Bild", "Full image")) }
                    Button(
                        onClick = { onApply(DocumentQuad(corners[0], corners[1], corners[2], corners[3])) },
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp), enabled = bitmap != null && validCorners(corners)
                    ) { Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(8.dp)); Text(tr("Übernehmen", "Apply")) }
                }
            }
        }
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).background(Color(0xFF101318)).onSizeChanged { viewSize = it },
            contentAlignment = Alignment.Center
        ) {
            val image = bitmap
            if (image != null) {
                Image(image.asImageBitmap(), tr("Originaldokument", "Original document"), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                Canvas(
                    Modifier.fillMaxSize().pointerInput(imageRect, page.id) {
                        detectDragGestures(
                            onDragStart = { position ->
                                dragging = corners.indices.minByOrNull { index ->
                                    val p = corners[index]
                                    (Offset(imageRect.left + p.x * imageRect.width, imageRect.top + p.y * imageRect.height) - position).getDistance()
                                }?.takeIf { index ->
                                    val p = corners[index]
                                    (Offset(imageRect.left + p.x * imageRect.width, imageRect.top + p.y * imageRect.height) - position).getDistance() <= touchRadius
                                } ?: -1
                            },
                            onDragEnd = { dragging = -1 },
                            onDragCancel = { dragging = -1 },
                            onDrag = { change, amount ->
                                if (dragging >= 0 && imageRect.width > 0 && imageRect.height > 0) {
                                    change.consume()
                                    val old = corners[dragging]
                                    val next = ScanPoint((old.x + amount.x / imageRect.width).coerceIn(0f, 1f), (old.y + amount.y / imageRect.height).coerceIn(0f, 1f))
                                    val candidate = corners.toMutableList().also { it[dragging] = next }
                                    if (validCorners(candidate)) corners[dragging] = next
                                }
                            }
                        )
                    }
                ) {
                    if (imageRect.width > 0) {
                        val positions = corners.map { Offset(imageRect.left + it.x * imageRect.width, imageRect.top + it.y * imageRect.height) }
                        val polygon = Path().apply {
                            moveTo(positions[0].x, positions[0].y)
                            positions.drop(1).forEach { lineTo(it.x, it.y) }
                            close()
                        }
                        val outside = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRect(Rect(0f, 0f, size.width, size.height))
                            addPath(polygon)
                        }
                        drawPath(outside, Color.Black.copy(alpha = .56f))
                        drawPath(polygon, Color(0xFF7EA6FF), style = Stroke(2.dp.toPx()))
                        positions.forEachIndexed { i, position ->
                            drawCircle(Color(0xFF7EA6FF).copy(alpha = if (dragging == i) .35f else .16f), if (dragging == i) 25.dp.toPx() else 20.dp.toPx(), position)
                            drawCircle(Color.White, 10.dp.toPx(), position)
                            drawCircle(Color(0xFF2563EB), 10.dp.toPx(), position, style = Stroke(3.dp.toPx()))
                        }
                    }
                }
            } else if (failed) {
                Text(tr("Das Originalbild konnte nicht geöffnet werden.", "Could not open the original image."), color = Color.White, modifier = Modifier.padding(24.dp), textAlign = TextAlign.Center)
            } else CircularProgressIndicator(color = Color(0xFF7EA6FF))
        }
    }
}

private fun validCorners(points: List<ScanPoint>): Boolean {
    if (points.size != 4) return false
    var area = 0f
    for (i in points.indices) {
        val a = points[i]
        val b = points[(i + 1) % 4]
        val c = points[(i + 2) % 4]
        if (a.x !in 0f..1f || a.y !in 0f..1f) return false
        if ((b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x) <= .001f) return false
        if (abs(a.x - b.x) + abs(a.y - b.y) < .03f) return false
        area += a.x * b.y - b.x * a.y
    }
    return area > .04f
}
