package de.snappdf

import android.graphics.Bitmap
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
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
import org.junit.runner.RunWith

/** Screenshots of the installed app, with a real photo only in its scan draft. */
@RunWith(AndroidJUnit4::class)
class PreviewScreenshotsTest {
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

    private fun awaitNode(label: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            findNode(instrumentation.uiAutomation.rootInActiveWindow, label)?.let { return it }
            SystemClock.sleep(100)
        }
        error("The installed app did not display: $label")
    }

    private fun findNode(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString()?.contains(label) == true || node.contentDescription?.toString()?.contains(label) == true) return node
        for (index in 0 until node.childCount) {
            findNode(node.getChild(index), label)?.let { return it }
        }
        return null
    }

    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = awaitNode(label)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("No clickable action for $label", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        // Wait for accessibility changes and Coil's local image decode to settle.
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
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
            if (Build.VERSION.SDK_INT >= 31) {
                // Stream the bytes into the shell-owned file: recent Android
                // versions prevent shell cp from reading another app's external
                // data directory through scoped-storage FUSE rules.
                val pipes = instrumentation.uiAutomation.executeShellCommandRw(
                    "mkdir -p /data/local/tmp/snappdf-qa && cat > $destination && test -s $destination && echo SNAPPDF_SCREENSHOT_SAVED"
                )
                ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
                val result = ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader().use { it.readText() }
                assertTrue("Screenshot evidence must survive APK cleanup", "SNAPPDF_SCREENSHOT_SAVED" in result)
            } else {
                val command = "mkdir -p /data/local/tmp/snappdf-qa && cp ${File(directory, name).absolutePath} $destination"
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
            }
        } finally { bitmap.recycle() }
    }
}
