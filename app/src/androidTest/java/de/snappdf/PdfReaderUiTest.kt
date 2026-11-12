package de.snappdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.snappdf.data.AppLanguage
import de.snappdf.data.AppSettings
import de.snappdf.data.DocumentEntity
import de.snappdf.ui.PdfReaderScreen
import de.snappdf.ui.SavedScreen
import de.snappdf.ui.SnapTheme
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfReaderUiTest {
    @get:Rule val compose = createComposeRule()
    private val german = AppSettings(language = AppLanguage.GERMAN)

    @Test fun swipeOnRenderedPdfScrollsDocumentAndZoomCanReset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "reader-${UUID.randomUUID()}.pdf")
        val pdf = PdfDocument()
        try {
            repeat(3) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(400, 600, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText("Page ${index + 1}", 40f, 80f, Paint().apply { textSize = 32f; color = Color.BLACK })
                pdf.finishPage(page)
            }
            file.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        try {
            val document = DocumentEntity("reader", "Worksheet", 0, 3, file.path, "")
            compose.setContent {
                SnapTheme(german) { Box(Modifier.requiredSize(340.dp, 520.dp)) { PdfReaderScreen(document, {}, {}, {}) } }
            }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithContentDescription("PDF-Seite 1").fetchSemanticsNodes().isNotEmpty()
            }
            val list = compose.onNodeWithTag("pdf-page-list")
            val initialOffset = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            // Start over the image, where the former transform detector consumed
            // a one-finger swipe without moving either the page or the list.
            list.performTouchInput { swipeUp(durationMillis = 500) }
            compose.waitForIdle()
            val finalOffset = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("A swipe over the PDF must scroll the list", finalOffset > initialOffset)
            compose.onNodeWithContentDescription("Vergrößern").performClick()
            compose.onNodeWithText("125 %").assertIsDisplayed().performClick()
            compose.onNodeWithText("100 %").assertIsDisplayed()
        } finally { file.delete() }
    }

    @Test fun savedActionsRemainReachableInLandscapeWithLargeText() {
        var nextScans = 0
        var homeVisits = 0
        val document = DocumentEntity("saved", "Ein längeres Arbeitsblatt", 0, 4, "/unused.pdf", "", byteSize = 16000)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.8f)) {
                SnapTheme(german) {
                    Box(Modifier.requiredSize(480.dp, 320.dp)) {
                        SavedScreen(document, { homeVisits++ }, {}, {}, {}, { nextScans++ })
                    }
                }
            }
        }
        compose.onNodeWithText("Nächster Scan").performScrollTo().performClick()
        compose.onNodeWithText("Zur Startseite").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, nextScans)
            assertEquals(1, homeVisits)
        }
    }
}
