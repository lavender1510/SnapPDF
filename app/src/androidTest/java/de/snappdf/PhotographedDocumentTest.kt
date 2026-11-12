package de.snappdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.snappdf.data.DocumentQuad
import de.snappdf.data.ScanMode
import de.snappdf.processing.DocumentProcessor
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real photographed paper from OpenCV's test data, not a generated document. */
@RunWith(AndroidJUnit4::class)
class PhotographedDocumentTest {
    @Test fun photographedShadowedGridKeepsLinesInEveryScanMode() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "sessions/photo-tests-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(directory, "sudoku.png")
        instrumentation.context.assets.open("fixtures/opencv-sudoku.png").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        val processor = DocumentProcessor(context)
        try {
            for (mode in ScanMode.entries) {
                val output = File(directory, "${mode.name}.png")
                // This fixture has newspaper content beyond the grid. Keep the
                // full image: a detected table is not necessarily a paper edge.
                processor.process(source, output, mode, .5f, DocumentQuad.FULL)
                val bitmap = BitmapFactory.decodeFile(output.path)
                try {
                    assertEquals(558, bitmap.width)
                    assertEquals(563, bitmap.height)
                    var black = 0
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    for (pixel in pixels) {
                        val value = Color.red(pixel)
                        assertTrue("$mode must output binary pixels", value == 0 || value == 255)
                        assertEquals(value, Color.green(pixel))
                        assertEquals(value, Color.blue(pixel))
                        if (value == 0) black++
                    }
                    val inkFraction = black.toFloat() / pixels.size
                    assertTrue("$mode should retain content without a black background: $inkFraction", inkFraction in .025f.. .45f)
                    val edges = listOf(
                        floatArrayOf(74f, 86f, 491f, 70f),
                        floatArrayOf(491f, 70f, 520f, 519f),
                        floatArrayOf(520f, 519f, 35f, 516f),
                        floatArrayOf(35f, 516f, 74f, 86f)
                    )
                    var retainedSamples = 0
                    for (edge in edges) for (step in 1..9) {
                        val t = step / 10f
                        val x = (edge[0] + (edge[2] - edge[0]) * t).toInt()
                        val y = (edge[1] + (edge[3] - edge[1]) * t).toInt()
                        if (darkNear(bitmap, x, y)) retainedSamples++
                    }
                    assertTrue("$mode lost photographed table borders: $retainedSamples / 36", retainedSamples >= 28)
                } finally { bitmap.recycle() }
            }
            assertTrue("Original photo must be retained", source.isFile)
        } finally { directory.deleteRecursively() }
    }

    private fun darkNear(bitmap: Bitmap, x: Int, y: Int): Boolean {
        for (dy in -4..4) for (dx in -4..4) {
            val sampleX = (x + dx).coerceIn(0, bitmap.width - 1)
            val sampleY = (y + dy).coerceIn(0, bitmap.height - 1)
            if (Color.red(bitmap.getPixel(sampleX, sampleY)) == 0) return true
        }
        return false
    }
}
