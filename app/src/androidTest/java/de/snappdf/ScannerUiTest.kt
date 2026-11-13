package de.snappdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.snappdf.data.*
import de.snappdf.ui.EditorScreen
import de.snappdf.ui.HomeScreen
import de.snappdf.ui.LibraryScreen
import de.snappdf.ui.SettingsScreen
import de.snappdf.ui.SnapTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** User-facing actions and constrained-layout regressions, independent of camera hardware. */
@RunWith(AndroidJUnit4::class)
class ScannerUiTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val german = AppSettings(language = AppLanguage.GERMAN)

    @Test fun homeButtonsStartScanImportSettingsAndLibrary() {
        var scans = 0
        var imports = 0
        var settings = 0
        var libraries = 0
        compose.setContent {
            SnapTheme(german) {
                HomeScreen(emptyList(), null, { scans++ }, { imports++ }, { settings++ }, { libraries++ }, {}, {})
            }
        }
        compose.onNodeWithText("Dokument scannen").performClick()
        compose.onNodeWithText("Bild importieren").performClick()
        compose.onNodeWithContentDescription("Einstellungen").performClick()
        compose.onNodeWithText("Bibliothek").performClick()
        compose.runOnIdle {
            assertEquals(1, scans)
            assertEquals(1, imports)
            assertEquals(1, settings)
            assertEquals(1, libraries)
        }
    }

    @Test fun settingsPersistScannerOptionAndInvokeShortcutAction() {
        val store = SettingsStore(context)
        val previous = store.settings.value
        val repository = DocumentRepository(context)
        var shortcutRequests = 0
        store.update { german }
        try {
            compose.setContent {
                SnapTheme(german) {
                    SettingsScreen(store, repository, {}, {}, onPinShortcut = { shortcutRequests++ })
                }
            }
            compose.onNodeWithText("Automatische Aufnahme").performScrollTo().performClick()
            compose.runOnIdle { assertTrue(store.settings.value.autoCapture) }
            // A grouped settings card is taller than the viewport. LazyColumn's
            // bringIntoView can align that card without revealing its first row.
            // Exercise real scrolling until the actual tap target is visible.
            val shortcut = compose.onNodeWithText("Scan-Verknüpfung", useUnmergedTree = true)
            repeat(12) {
                if (runCatching { shortcut.assertIsDisplayed() }.isFailure) {
                    compose.onNode(hasScrollAction()).performTouchInput {
                        swipeUp(startY = height * .8f, endY = height * .5f, durationMillis = 350)
                    }
                    compose.waitForIdle()
                }
            }
            shortcut.assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(1, shortcutRequests) }
            assertTrue(SettingsStore(context).settings.value.autoCapture)
        } finally {
            store.update { previous }
        }
    }

    @Test fun librarySearchFindsOfflineRecognizedTextAndOpensDocument() = runBlocking<Unit> {
        val repository = DocumentRepository(context)
        val id = UUID.randomUUID().toString()
        val image = File(context.cacheDir, "ui-$id.png")
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText("Physics worksheet", 30f, 80f, Paint().apply { color = Color.BLACK; textSize = 23f })
        }
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val marker = "ocr-marker-$id"
        val page = ScanPage(id, image.absolutePath, image.absolutePath, width = 400, height = 600,
            ocrLines = listOf(OcrLine(marker, .1f, .1f, .8f, .15f)))
        val document = repository.savePdf(ScanSession(id, "UI worksheet $id", listOf(page)), german.copy(ocr = true))
        var opened: DocumentEntity? = null
        try {
            compose.setContent {
                SnapTheme(german) { LibraryScreen(repository, {}, { opened = it }, {}, {}) }
            }
            compose.onNode(hasSetTextAction()).performTextInput(marker)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(document.name).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(document.name).performClick()
            compose.runOnIdle { assertEquals(document.id, opened?.id) }
            compose.onNode(hasSetTextAction()).performTextClearance()
            compose.onNode(hasSetTextAction()).performTextInput("no-matching-document-$id")
            compose.onNodeWithText("Keine Treffer").assertIsDisplayed()
        } finally {
            repository.delete(document)
            image.delete()
        }
    }

    @Test fun editorSaveOptionsRemainReachableWithLargeTextAndShortViewport() {
        var session by mutableStateOf(ScanSession("ui-editor", "Arbeitsblatt", listOf(
            ScanPage("page", "/unused-original.png", "/unused-processed.png", confidence = 1f)
        )))
        var saved: Triple<PdfQuality, PaperSize, Boolean>? = null
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.8f)) {
                SnapTheme(german) {
                    Box(Modifier.requiredSize(340.dp, 420.dp)) {
                        EditorScreen(session, false, german, {}, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, { _, _ -> }, {},
                            { session = session.copy(title = it) }, { quality, size, ocr -> saved = Triple(quality, size, ocr) })
                    }
                }
            }
        }
        compose.onNodeWithText("PDF speichern").assertIsDisplayed().performClick()
        compose.onNodeWithText("Hoch").performScrollTo().performClick()
        compose.onNodeWithText("A5").performScrollTo().performClick()
        compose.onNodeWithText("Text erkennen (offline)").performScrollTo()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("Speichern").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(Triple(PdfQuality.HIGH, PaperSize.A5, true), saved) }
    }
}
