package de.snappdf

import android.provider.Settings
import android.os.ParcelFileDescriptor
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.MotionDurationScale
import de.snappdf.ui.setSnapContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MotionSettingsTest {
    @Test fun reduceMotionUpdatesCompositionAndSurvivesActivityStopResume() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<SnapApplication>()
        val previous = app.settings.settings.value
        val observed = CompletableDeferred<MotionDurationScale>()
        val originalSystemScale = Settings.Global.getString(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        try {
            // CI disables device animations. Set a nonzero baseline via the
            // test-only shell identity so this proves 1 -> 0 -> 1 behavior.
            shell("settings put global animator_duration_scale 1.0")
            val expectedSystemScale = 1f
            assertEquals(expectedSystemScale, Settings.Global.getFloat(
                app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f), .0001f)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    scenario.onActivity { activity ->
                        app.settings.update { it.copy(reduceMotion = false) }
                        activity.setSnapContent(app.settings) {
                            LaunchedEffect(Unit) {
                                observed.complete(checkNotNull(coroutineContext[MotionDurationScale]))
                            }
                            Text("Motion settings integration test")
                        }
                    }
                    val scale = withTimeout(10_000) { observed.await() }
                    awaitScale(scale, expectedSystemScale)
                    scenario.onActivity { app.settings.update { it.copy(reduceMotion = true) } }
                    awaitScale(scale, 0f)
                    scenario.moveToState(Lifecycle.State.CREATED)
                    app.settings.update { it.copy(reduceMotion = false) }
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    awaitScale(scale, expectedSystemScale)
                    assertEquals(expectedSystemScale, scale.scaleFactor, .0001f)
                } finally {
                    scenario.onActivity { app.settings.update { previous } }
                }
            }
        } finally {
            val original = originalSystemScale?.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f }
            if (original == null) shell("settings delete global animator_duration_scale")
            else shell("settings put global animator_duration_scale $original")
        }
    }

    private suspend fun awaitScale(scale: MotionDurationScale, expected: Float) {
        withTimeout(5_000) {
            while (kotlin.math.abs(scale.scaleFactor - expected) > .0001f) delay(20)
        }
    }

    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
}
