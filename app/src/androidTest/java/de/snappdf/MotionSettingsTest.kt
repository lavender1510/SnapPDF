package de.snappdf

import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        val expectedSystemScale = Settings.Global.getFloat(
            app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        )
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
    }

    private suspend fun awaitScale(scale: MotionDurationScale, expected: Float) {
        withTimeout(5_000) {
            while (kotlin.math.abs(scale.scaleFactor - expected) > .0001f) delay(20)
        }
    }
}
