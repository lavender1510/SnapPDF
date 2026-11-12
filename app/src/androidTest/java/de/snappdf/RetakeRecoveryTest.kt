package de.snappdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.snappdf.data.ScanMode
import de.snappdf.data.ScanPage
import de.snappdf.data.ScanSession
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RetakeRecoveryTest {
    @Test fun failedRetakeKeepsPreviouslyScannedPageInMemoryAndOnDisk() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<SnapApplication>()
        val repository = app.repository
        val previousFile = fixture(app.filesDir, "Successful original")
        val retakeFile = fixture(app.filesDir, "Replacement capture")
        val previous = ScanPage(UUID.randomUUID().toString(), previousFile.path, previousFile.path,
            mode = ScanMode.HANDWRITING, contrast = .3f, width = 700, height = 1000)
        val originalSession = ScanSession(UUID.randomUUID().toString(), "Retake safety", listOf(previous))
        repository.saveDraft(originalSession)
        val store = ViewModelStore()
        val viewModel = withContext(Dispatchers.Main) {
            ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[ScanViewModel::class.java]
        }
        val processedDirectory = File(app.filesDir, "sessions/processed")
        val backup = File(app.filesDir, "sessions/processed-backup-${UUID.randomUUID()}")
        var directoryMoved = false
        var obstructionCreated = false
        try {
            withTimeout(20_000) { viewModel.ready.first { it } }
            assertEquals(originalSession, viewModel.session.value)
            if (processedDirectory.exists()) {
                assertTrue("Could not preserve processed directory", processedDirectory.renameTo(backup))
                directoryMoved = true
            }
            // A valid image passes decoding, then saving its optimized result
            // fails because the output directory has become a regular file.
            processedDirectory.writeText("Simulated unavailable result directory")
            obstructionCreated = true
            val operation = withContext(Dispatchers.Main) { viewModel.addCapture(retakeFile, replaceIndex = 0) }
            withTimeout(30_000) { operation.join() }
            assertNotNull("The failed retake must be reported", viewModel.notice.value)
            assertEquals(originalSession, viewModel.session.value)
            assertEquals(originalSession, repository.loadDraft())
            assertTrue(previousFile.isFile)
        } finally {
            if (obstructionCreated) processedDirectory.delete()
            if (directoryMoved) assertTrue("Could not restore processed directory", backup.renameTo(processedDirectory))
            withContext(Dispatchers.Main) { viewModel.discard() }.join()
            withContext(Dispatchers.Main) { store.clear() }
            previousFile.delete()
            retakeFile.delete()
        }
    }

    private fun fixture(filesDir: File, text: String): File {
        val directory = File(filesDir, "sessions/retake-tests").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(700, 1000, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText(text, 60f, 130f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 30f
                })
            }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        return file
    }
}
