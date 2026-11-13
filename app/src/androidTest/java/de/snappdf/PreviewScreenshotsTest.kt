package de.snappdf

import android.graphics.Bitmap
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.snappdf.data.AppLanguage
import de.snappdf.data.DocumentQuad
import de.snappdf.data.ScanMode
import de.snappdf.data.ScanSession
import de.snappdf.data.ThemeMode
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

/** Screenshots of the installed app, with a real photo only in its scan draft. */
@RunWith(AndroidJUnit4::class)
class PreviewScreenshotsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<SnapApplication>()

    @Test fun captureActualHomeSettingsAndPhotographedDraftInBothThemes() = runBlocking {
        val originalSettings = app.settings.settings.value
        val originalDraft = app.repository.loadDraft()
        val fixtureDirectory = File(app.filesDir, "sessions/screenshot-test-${UUID.randomUUID()}").apply { mkdirs() }
        var fixtureDraftInstalled = false
        try {
            app.settings.update {
                it.copy(language = AppLanguage.GERMAN, theme = ThemeMode.LIGHT,
                    dynamicColors = false, reduceMotion = true, openCameraOnStart = false,
                    autoSave = false, skipPreview = false)
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitNode("Dokument scannen")
                capture("home-light.png")
                click("Einstellungen")
                awaitNode("Allgemein")
                capture("settings-light.png")
                scenario.onActivity { app.settings.update { it.copy(theme = ThemeMode.DARK) } }
                capture("settings-dark.png")
                click("Zurück")
                awaitNode("Dokument scannen")
                capture("home-dark.png")
            }

            val source = File(fixtureDirectory, "opencv-photo.png")
            instrumentation.context.assets.open("fixtures/opencv-sudoku.png").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val page = app.processor.process(source, File(fixtureDirectory, "processed.png"),
                ScanMode.STANDARD, .5f, DocumentQuad.FULL)
            app.repository.saveDraft(ScanSession(UUID.randomUUID().toString(), "OpenCV · Testaufnahme", listOf(page)))
            fixtureDraftInstalled = true
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                click("Scan fortsetzen")
                awaitNode("Scan bearbeiten")
                capture("editor-real-photo-dark.png")
                scenario.onActivity { app.settings.update { it.copy(theme = ThemeMode.LIGHT) } }
                capture("editor-real-photo-light.png")
            }
        } finally {
            if (fixtureDraftInstalled) {
                app.repository.clearDraft()
                if (originalDraft != null) app.repository.saveDraft(originalDraft)
            }
            fixtureDirectory.deleteRecursively()
            app.settings.update { originalSettings }
        }
    }

    private fun awaitNode(label: String): SemanticsNodeInteraction {
        val matcher = hasText(label, substring = true) or hasContentDescription(label, substring = true)
        // Synchronize with the actual Compose window instead of a system
        // accessibility cache that can still reference a previous test Activity.
        compose.waitUntil(20_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNode(matcher)
    }

    private fun click(label: String) {
        awaitNode(label).performClick()
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        compose.waitForIdle()
        // Local Coil decoding is asynchronous and does not hold a Compose idler.
        SystemClock.sleep(250)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Screenshot unavailable" }
        try {
            val directory = File(checkNotNull(app.getExternalFilesDir(null)), "qa").apply { mkdirs() }
            File(directory, name).outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            // Gradle may uninstall the target APK after the suite, removing its
            // external app directory. Keep test evidence in shell-owned scratch
            // storage so CI can retrieve it after that cleanup.
            val destination = "/data/local/tmp/snappdf-qa/$name"
            // UiAutomation executes a command directly, without an implicit sh.
            // Individual commands avoid interpreting shell operators as mkdir
            // arguments and create evidence outside APK-managed storage.
            shell("mkdir -p /data/local/tmp/snappdf-qa")
            val captureResult = shell("screencap -p $destination")
            val size = shell("stat -c %s $destination").trim().toLongOrNull()
            assertTrue("Screenshot evidence must survive APK cleanup: $captureResult", size != null && size > 1000L)
        } finally { bitmap.recycle() }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
}
