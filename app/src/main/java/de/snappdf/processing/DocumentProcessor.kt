package de.snappdf.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import de.snappdf.data.DetectionResult
import de.snappdf.data.DocumentQuad
import de.snappdf.data.OcrLine
import de.snappdf.data.ScanMode
import de.snappdf.data.ScanPage
import de.snappdf.data.ScanPoint
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * All processing is local. OpenCV and ML Kit's Latin model are packaged in the APK.
 * Only bounded, upright bitmaps enter the pipeline. No Mat or recognizer is shared
 * between calls, so the CameraX analyzer and editor can use the processor together.
 */
class DocumentProcessor(@Suppress("UNUSED_PARAMETER") context: Context) {
    init {
        check(openCvReady) { "Die lokale Bildverarbeitung konnte nicht gestartet werden." }
    }

    /** Decode with sampling before allocation, then apply every EXIF orientation. */
    fun loadBitmap(file: File, maxDimension: Int = MAX_IMAGE_SIDE): Bitmap {
        require(maxDimension in 64..MAX_IMAGE_SIDE) { "Ungültige Bildgröße." }
        if (!file.isFile) throw IOException("Die Bilddatei ist nicht mehr verfügbar.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Die Bilddatei ist beschädigt oder wird nicht unterstützt.")
        // A malicious header must not trigger an unbounded decoder allocation.
        if (bounds.outWidth.toLong() * bounds.outHeight > 300_000_000L) {
            throw IOException("Dieses Bild ist zu groß. Bitte wähle eine kleinere Bilddatei.")
        }
        var sampling = 1
        // Limit the temporary decoder bitmap too, rather than decoding a full
        // 48MP image only to shrink it immediately afterwards.
        while (max(bounds.outWidth, bounds.outHeight) / sampling > maxDimension * 1.35) sampling *= 2
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sampling
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw IOException("Die Bilddatei konnte nicht geöffnet werden.")
        var result = decoded
        try {
            val orientation = runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                }
            }
            if (!matrix.isIdentity) {
                val upright = Bitmap.createBitmap(result, 0, 0, result.width, result.height, matrix, true)
                if (upright !== result) result.recycle()
                result = upright
            }
            val longest = max(result.width, result.height)
            if (longest > maxDimension) {
                val scale = maxDimension.toDouble() / longest
                val bounded = Bitmap.createScaledBitmap(result, max(1, (result.width * scale).roundToInt()), max(1, (result.height * scale).roundToInt()), true)
                if (bounded !== result) result.recycle()
                result = bounded
            }
            return result
        } catch (error: Throwable) {
            result.recycle()
            throw error
        }
    }

    /** Caller must use a worker thread. Brightness is 0–255; blur is Laplacian variance. */
    fun detect(bitmap: Bitmap): DetectionResult = MatScope().use { mats ->
        val rgba = mats.mat()
        Utils.bitmapToMat(bitmap, rgba)
        val gray = mats.mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val small = mats.mat()
        val scale = min(1.0, 960.0 / max(gray.cols(), gray.rows()))
        Imgproc.resize(gray, small, Size(max(1.0, gray.cols() * scale), max(1.0, gray.rows() * scale)), 0.0, 0.0, Imgproc.INTER_AREA)
        val brightness = Core.mean(small).`val`[0]
        val laplacian = mats.mat()
        val mean = mats.own(MatOfDouble())
        val deviation = mats.own(MatOfDouble())
        Imgproc.Laplacian(small, laplacian, CvType.CV_64F)
        Core.meanStdDev(laplacian, mean, deviation)
        val blurScore = deviation.toArray().firstOrNull()?.let { it * it } ?: 0.0
        val softened = mats.mat()
        Imgproc.GaussianBlur(small, softened, Size(5.0, 5.0), 0.9)
        val edges = mats.mat()
        Imgproc.Canny(softened, edges, 35.0, 110.0)
        val kernel = mats.own(Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
        val threshold = mats.mat()
        Imgproc.threshold(softened, threshold, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        var best: DocumentQuad? = null
        var bestScore = 0f
        for (mask in listOf(edges, threshold)) {
            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = mats.mat()
            try {
                Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                for (contour in contours.sortedByDescending { Imgproc.contourArea(it) }.take(18)) {
                    val contourArea = Imgproc.contourArea(contour) / (small.cols().toDouble() * small.rows())
                    if (contourArea < 0.15 || contourArea > 0.975) continue
                    val curve = MatOfPoint2f(*contour.toArray())
                    val approximate = MatOfPoint2f()
                    try {
                        val length = Imgproc.arcLength(curve, true)
                        for (epsilon in listOf(0.018, 0.027, 0.04)) {
                            Imgproc.approxPolyDP(curve, approximate, length * epsilon, true)
                            val corners = approximate.toArray()
                            if (corners.size != 4) continue
                            val quad = orderedQuad(corners, small.cols(), small.rows())
                            if (!QuadGeometry.isValid(quad)) continue
                            val normalized = quad.points()
                            val angles = normalized.indices.map { i ->
                                val p = normalized[i]
                                val previous = normalized[(i + 3) % 4]
                                val next = normalized[(i + 1) % 4]
                                val ax = (previous.x - p.x) * small.cols()
                                val ay = (previous.y - p.y) * small.rows()
                                val bx = (next.x - p.x) * small.cols()
                                val by = (next.y - p.y) * small.rows()
                                acos(((ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by))).toDouble().coerceIn(-1.0, 1.0)) * 180.0 / Math.PI
                            }
                            if (angles.any { it < 34.0 || it > 146.0 }) continue
                            val edgeMargin = normalized.minOf { min(min(it.x, 1 - it.x), min(it.y, 1 - it.y)) }
                            val angleQuality = 1f - (angles.sumOf { abs(it - 90) } / 260.0).toFloat().coerceIn(0f, 1f)
                            val score = (0.51f + QuadGeometry.area(quad) * 0.30f + angleQuality * 0.17f - if (edgeMargin < 0.008f) 0.14f else 0f).coerceIn(0f, 0.98f)
                            if (score > bestScore) { best = quad; bestScore = score }
                        }
                    } finally {
                        curve.release()
                        approximate.release()
                    }
                }
            } finally {
                contours.forEach { it.release() }
            }
        }
        DetectionResult(best, bestScore, blurScore, brightness)
    }

    /**
     * Rectify before filtering, then normalize illumination using a low-resolution
     * background estimate. Adaptive Gaussian thresholds preserve local contrast;
     * handwriting uses a smaller offset so faint pencil survives the threshold.
     */
    suspend fun process(input: File, output: File, mode: ScanMode, contrast: Float, quad: DocumentQuad? = null, rotation: Int = 0): ScanPage = withContext(Dispatchers.Default) {
        require(input.canonicalPath != output.canonicalPath) { "Original und Ergebnis müssen getrennte Dateien sein." }
        val source = try {
            loadBitmap(input)
        } catch (memory: OutOfMemoryError) {
            throw IOException("Für dieses Bild ist zu wenig Arbeitsspeicher verfügbar. Bitte wähle ein kleineres Bild.", memory)
        }
        try {
            ensureActive()
            val detection = detect(source)
            val selectedQuad = quad ?: detection.quad ?: DocumentQuad.FULL
            require(QuadGeometry.isValid(selectedQuad)) { "Die Dokumentenecken bilden kein gültiges Viereck. Bitte korrigiere die Ecken." }
            MatScope().use { mats ->
                val rgba = mats.mat()
                Utils.bitmapToMat(source, rgba)
                source.recycle()
                val corrected = mats.mat()
                val points = selectedQuad.points().map { Point(it.x * (rgba.cols() - 1.0), it.y * (rgba.rows() - 1.0)) }
                fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
                // Corners refer to pixel centres: the distance from pixel 0 to
                // pixel n - 1 spans n pixels, including both endpoints.
                val width = (max(distance(points[0], points[1]), distance(points[3], points[2])).roundToInt() + 1).coerceIn(64, MAX_IMAGE_SIDE)
                val height = (max(distance(points[0], points[3]), distance(points[1], points[2])).roundToInt() + 1).coerceIn(64, MAX_IMAGE_SIDE)
                val sourcePoints = mats.own(MatOfPoint2f(*points.toTypedArray()))
                val targetPoints = mats.own(MatOfPoint2f(Point(0.0, 0.0), Point(width - 1.0, 0.0), Point(width - 1.0, height - 1.0), Point(0.0, height - 1.0)))
                val transform = mats.own(Imgproc.getPerspectiveTransform(sourcePoints, targetPoints))
                Imgproc.warpPerspective(rgba, corrected, transform, Size(width.toDouble(), height.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
                rgba.release()
                val normalizedRotation = ((rotation % 360) + 360) % 360
                require(normalizedRotation % 90 == 0) { "Es sind nur Drehungen um 90 Grad möglich." }
                if (normalizedRotation != 0) {
                    val rotateCode = when (normalizedRotation) {
                        90 -> Core.ROTATE_90_CLOCKWISE
                        180 -> Core.ROTATE_180
                        else -> Core.ROTATE_90_COUNTERCLOCKWISE
                    }
                    Core.rotate(corrected, corrected, rotateCode)
                }
                val gray = mats.mat()
                Imgproc.cvtColor(corrected, gray, Imgproc.COLOR_RGBA2GRAY)
                corrected.release()
                ensureActive()
                val background = mats.mat()
                val small = mats.mat()
                val backgroundScale = min(1.0, 650.0 / max(gray.cols(), gray.rows()))
                Imgproc.resize(gray, small, Size(max(1.0, gray.cols() * backgroundScale), max(1.0, gray.rows() * backgroundScale)), 0.0, 0.0, Imgproc.INTER_AREA)
                val paperKernel = mats.own(Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(23.0, 23.0)))
                Imgproc.morphologyEx(small, small, Imgproc.MORPH_CLOSE, paperKernel)
                Imgproc.GaussianBlur(small, small, Size(31.0, 31.0), 0.0)
                Imgproc.resize(small, background, gray.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                // Guard almost-black pixels; a camera may be pointed at a desk.
                Core.max(background, Scalar(20.0), background)
                val flattened = mats.mat()
                Core.divide(gray, background, flattened, 255.0)
                gray.release()
                background.release()
                small.release()
                val clean = mats.mat()
                // A low color sigma removes sensor noise without averaging faint
                // pencil into the paper. Never median-filter handwriting strokes.
                Imgproc.bilateralFilter(flattened, clean, 3, if (mode == ScanMode.HANDWRITING) 5.0 else 18.0, 1.2)
                flattened.release()
                val strength = contrast.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
                val offset = when (mode) {
                    ScanMode.STANDARD -> 9.0 - strength * 9.0
                    ScanMode.HANDWRITING -> 3.4 - strength * 3.1
                    ScanMode.HIGH_CONTRAST -> 11.0 - strength * 9.0
                }
                val minSide = min(clean.rows(), clean.cols())
                val window = ((minSide / 35).coerceIn(25, 91) or 1).coerceAtMost(if (minSide % 2 == 0) minSide - 1 else minSide)
                val binary = mats.mat()
                Imgproc.adaptiveThreshold(clean, binary, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, window, offset)
                clean.release()
                ensureActive()
                val result = Bitmap.createBitmap(binary.cols(), binary.rows(), Bitmap.Config.ARGB_8888)
                try {
                    Utils.matToBitmap(binary, result)
                    output.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Der Speicherordner konnte nicht erstellt werden.") }
                    val pending = File(output.parentFile, output.name + ".writing")
                    try {
                        pending.outputStream().use { stream ->
                            if (!result.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw IOException("Das Scanbild konnte nicht gespeichert werden.")
                            stream.fd.sync()
                        }
                        ensureActive()
                        if (!pending.renameTo(output)) throw IOException("Das Scanbild konnte nicht sicher gespeichert werden.")
                    } finally { pending.delete() }
                    ScanPage(UUID.randomUUID().toString(), input.absolutePath, output.absolutePath, mode, strength, selectedQuad, normalizedRotation,
                        result.width, result.height, detection.confidence,
                        safeForAutoSave = quad == null && detection.quad != null && detection.confidence >= 0.85f &&
                            detection.blurScore >= 45.0 && detection.brightness >= 55.0)
                } finally { result.recycle() }
            }
        } catch (memory: OutOfMemoryError) {
            throw IOException("Für dieses Bild ist zu wenig Arbeitsspeicher verfügbar. Bitte wähle ein kleineres Bild.", memory)
        } finally {
            if (!source.isRecycled) source.recycle()
        }
    }

    suspend fun recognize(file: File): List<OcrLine> = withContext(Dispatchers.Default) {
        val bitmap = loadBitmap(file)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            // Keep the bitmap alive until ML Kit completes, including cancellation.
            suspendCancellableCoroutine { continuation ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { text ->
                        val result = text.textBlocks.flatMap { block -> block.lines }.mapNotNull { line ->
                            line.boundingBox?.let { bounds ->
                                OcrLine(line.text,
                                    (bounds.left.toFloat() / bitmap.width).coerceIn(0f, 1f),
                                    (bounds.top.toFloat() / bitmap.height).coerceIn(0f, 1f),
                                    (bounds.right.toFloat() / bitmap.width).coerceIn(0f, 1f),
                                    (bounds.bottom.toFloat() / bitmap.height).coerceIn(0f, 1f))
                            }
                        }
                        if (continuation.isActive) continuation.resume(result)
                    }
                    .addOnFailureListener { failure ->
                        if (continuation.isActive) continuation.resumeWithException(IOException("Die lokale Texterkennung ist fehlgeschlagen.", failure))
                    }
                    .addOnCompleteListener {
                        bitmap.recycle()
                        recognizer.close()
                    }
            }
        } catch (error: Throwable) {
            // If process() threw before a task was started, no listener owns cleanup.
            if (!bitmap.isRecycled && error !is kotlinx.coroutines.CancellationException) bitmap.recycle()
            recognizer.close()
            throw error
        }
    }

    private fun orderedQuad(points: Array<Point>, width: Int, height: Int): DocumentQuad {
        val centerX = points.map { it.x }.average()
        val centerY = points.map { it.y }.average()
        val around = points.sortedBy { kotlin.math.atan2(it.y - centerY, it.x - centerX) }
        val start = around.indices.minBy { around[it].x + around[it].y }
        val ordered = (0..3).map { around[(start + it) % 4] }
        fun normalized(point: Point) = ScanPoint((point.x / (width - 1)).toFloat().coerceIn(0f, 1f), (point.y / (height - 1)).toFloat().coerceIn(0f, 1f))
        return DocumentQuad(normalized(ordered[0]), normalized(ordered[1]), normalized(ordered[2]), normalized(ordered[3]))
    }

    /** Mat ownership is local to one call; finally also covers native exceptions. */
    private class MatScope : AutoCloseable {
        private val allocated = mutableListOf<Mat>()
        fun mat() = own(Mat())
        fun <T : Mat> own(mat: T): T { allocated += mat; return mat }
        override fun close() { allocated.asReversed().forEach { it.release() } }
    }

    companion object {
        const val MAX_IMAGE_SIDE = 3000
        private val openCvReady by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { OpenCVLoader.initLocal() }
    }
}
