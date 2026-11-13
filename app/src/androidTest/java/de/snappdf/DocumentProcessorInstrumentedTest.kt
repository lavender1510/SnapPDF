package de.snappdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.snappdf.data.DocumentQuad
import de.snappdf.data.DraftCodec
import de.snappdf.data.ScanMode
import de.snappdf.data.ScanPage
import de.snappdf.data.ScanPoint
import de.snappdf.data.ScanSession
import de.snappdf.processing.DocumentProcessor
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Native OpenCV tests run on an Android device/emulator, including without network. */
@RunWith(AndroidJUnit4::class)
class DocumentProcessorInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var directory: File
    private lateinit var processor: DocumentProcessor

    @Before fun prepare() {
        directory = File(context.cacheDir, "processing-fixtures").apply { mkdirs() }
        processor = DocumentProcessor(context)
    }

    @After fun clean() { directory.deleteRecursively() }

    @Test fun recognizesSkewedWorksheetEdgesInsteadOfTextBoxes() = runBlocking {
        val bitmap = Bitmap.createBitmap(1000, 1400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(45, 53, 64))
        val polygon = Path().apply {
            moveTo(130f, 90f); lineTo(850f, 160f); lineTo(920f, 1240f); lineTo(65f, 1310f); close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawPath(polygon, paint)
        paint.color = Color.BLACK; paint.textSize = 25f
        for (line in 0..24) canvas.drawText("Arbeitsblatt: Gleichungen  2x + 5 = 17", 180f, 240f + line * 32, paint)
        val detection = processor.detect(bitmap)
        val quad = detection.quad
        assertNotNull("A complete white worksheet should be detected", quad)
        assertTrue(detection.confidence >= .8f)
        assertEquals(.13f, quad!!.topLeft.x, .035f)
        assertEquals(.064f, quad.topLeft.y, .035f)
        assertEquals(.92f, quad.bottomRight.x, .035f)
        assertEquals(.886f, quad.bottomRight.y, .035f)
        val input = save(bitmap, "skewed-page.png")
        bitmap.recycle()
        val automatic = processor.process(input, File(directory, "automatic-crop.png"), ScanMode.STANDARD, .5f)
        assertTrue("A sharp, bright, fully recognized sheet can bypass review", automatic.safeForAutoSave)
        val forcedFull = processor.process(input, File(directory, "disabled-crop.png"), ScanMode.STANDARD, .5f, DocumentQuad.FULL)
        assertFalse("Disabled automatic cropping must require review even with a confident detector", forcedFull.safeForAutoSave)
    }

    @Test fun handwritingPreservesPencilAndThinTableLinesWithUnevenLight() = runBlocking {
        val bitmap = Bitmap.createBitmap(1000, 1400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, 1000f, 1400f, Color.rgb(246, 246, 246), Color.rgb(164, 164, 164), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, 1000f, 1400f, paint)
        paint.shader = null
        // Faint pencil has only ~18 intensity levels of local contrast.
        paint.strokeWidth = 2f
        for (x in 140..800) {
            val localPaper = 246 - (82f * (x * 1000f + 300f * 1400f) / (1000f * 1000f + 1400f * 1400f)).toInt()
            val ink = localPaper - 18
            paint.color = Color.rgb(ink, ink, ink)
            canvas.drawLine(x.toFloat(), 300f, x + 1f, 300f, paint)
        }
        paint.color = Color.rgb(171, 171, 171); paint.strokeWidth = 1f
        for (x in listOf(150f, 450f, 750f)) canvas.drawLine(x, 850f, x, 1150f, paint)
        for (y in listOf(850f, 1000f, 1150f)) canvas.drawLine(150f, y, 750f, y, paint)
        val input = save(bitmap, "pencil-shadow.png")
        bitmap.recycle()
        val output = File(directory, "pencil-result.png")
        val page = processor.process(input, output, ScanMode.HANDWRITING, .5f, DocumentQuad.FULL)
        val processed = processor.loadBitmap(output)
        val pencilY = (300f / 1400 * processed.height).toInt()
        val blackPencilPixels = (180..750).count { x ->
            (-2..2).any { dy -> Color.red(processed.getPixel(x, pencilY + dy)) < 128 }
        }
        assertTrue("Weak pencil line must remain visible", blackPencilPixels > 500)
        val whiteBackgroundPixels = (60..700 step 10).count { x -> Color.red(processed.getPixel(x, 600)) > 245 }
        assertTrue("Lighting gradient should be removed", whiteBackgroundPixels >= 60)
        for (x in 0 until processed.width step 25) for (y in 0 until processed.height step 25) {
            val red = Color.red(processed.getPixel(x, y))
            assertTrue("Output must be black or white", red == 0 || red == 255)
        }
        assertEquals(page.width, processed.width)
        assertFalse("A manually supplied crop must require review", page.safeForAutoSave)
        assertTrue(input.exists())
        processed.recycle()
    }

    @Test fun perspectiveCropAndQuarterTurnHaveCorrectOutputDimensions() = runBlocking {
        val bitmap = Bitmap.createBitmap(1000, 1400, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.WHITE)
        val input = save(bitmap, "rotation.png")
        bitmap.recycle()
        val page = processor.process(input, File(directory, "rotated.png"), ScanMode.STANDARD, .5f,
            DocumentQuad(ScanPoint(.1f, .1f), ScanPoint(.9f, .1f), ScanPoint(.9f, .9f), ScanPoint(.1f, .9f)), 90)
        assertTrue("Portrait crop rotated 90 degrees must be landscape", page.width > page.height)
        assertEquals(90, page.rotation)
        assertEquals(1119.0, page.width.toDouble(), 3.0)
        assertEquals(799.0, page.height.toDouble(), 3.0)
    }

    @Test fun exifRotationIsAppliedBeforeCrop() {
        val bitmap = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.WHITE)
        val input = File(directory, "exif.jpg")
        input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        ExifInterface(input).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val upright = processor.loadBitmap(input)
        assertEquals(600, upright.width)
        assertEquals(900, upright.height)
        upright.recycle()
    }

    @Test fun fullImageCropPreservesDimensionsAndAllFourOuterCorners() = runBlocking<Unit> {
        val bitmap = Bitmap.createBitmap(100, 160, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val ink = Paint().apply { color = Color.BLACK }
        canvas.drawRect(0f, 0f, 4f, 4f, ink)
        canvas.drawRect(96f, 0f, 100f, 4f, ink)
        canvas.drawRect(96f, 156f, 100f, 160f, ink)
        canvas.drawRect(0f, 156f, 4f, 160f, ink)
        val source = save(bitmap, "outer-corners.png")
        bitmap.recycle()
        val output = File(directory, "outer-corners-result.png")
        processor.process(source, output, ScanMode.STANDARD, .5f, DocumentQuad.FULL)
        val result = processor.loadBitmap(output)
        try {
            assertEquals(100, result.width)
            assertEquals(160, result.height)
            for ((x, y) in listOf(0 to 0, 99 to 0, 99 to 159, 0 to 159)) {
                assertEquals("The outermost corner pixel must survive cropping", 0, Color.red(result.getPixel(x, y)))
            }
        } finally { result.recycle() }
    }

    @Test fun damagedInputFailsWithoutReplacingOriginal() = runBlocking {
        val input = File(directory, "damaged.png").apply { writeText("not an image") }
        val output = File(directory, "should-not-exist.png")
        try {
            processor.process(input, output, ScanMode.STANDARD, .5f)
            fail("Damaged images must fail")
        } catch (_: java.io.IOException) {
            assertFalse(output.exists())
            assertEquals("not an image", input.readText())
        }
    }

    @Test fun draftRecoveryPreservesSafetyAndOlderDraftsRequireReview() {
        val page = ScanPage("page", "/original.jpg", "/processed.png", safeForAutoSave = true)
        val encoded = DraftCodec.encode(ScanSession("session", "Worksheet", listOf(page)))
        assertTrue(DraftCodec.decode(encoded).pages.single().safeForAutoSave)
        val previousVersion = org.json.JSONObject(encoded).apply {
            getJSONArray("pages").getJSONObject(0).remove("safeForAutoSave")
        }
        assertFalse(DraftCodec.decode(previousVersion.toString()).pages.single().safeForAutoSave)
    }

    private fun save(bitmap: Bitmap, name: String): File = File(directory, name).also { file ->
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
