package de.snappdf.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.snappdf.data.SettingsStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keep Compose's window/lifecycle scheduling and replace only its duration scale.
 * A snapshot-backed value is essential: infinite animations suspend at zero and
 * need a snapshot change to wake up when reduced motion is disabled again.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun ComponentActivity.setSnapContent(
    settings: SettingsStore,
    content: @Composable () -> Unit
) {
    val appContext = applicationContext
    val motion = SettingsDurationScale(
        if (settings.settings.value.reduceMotion) 0f else readSystemAnimationScale(appContext)
    )
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            combine(settings.settings, systemAnimationScales(appContext)) { config, systemScale ->
                if (config.reduceMotion) 0f else systemScale
            }.collect { motion.scaleFactor = it }
        }
    }
    // This public Compose factory supplies the normal Android UI dispatcher,
    // frame clock, lifecycle pause/resume and automatic cancellation on destroy.
    val recomposer = window.decorView.createLifecycleAwareWindowRecomposer(
        coroutineContext = motion,
        lifecycle = lifecycle
    )
    setContent(parent = recomposer, content = content)
}

private class SettingsDurationScale(initial: Float) : MotionDurationScale {
    override var scaleFactor by mutableFloatStateOf(initial)
}

private fun readSystemAnimationScale(context: Context): Float = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
}.getOrDefault(1f).let { if (it.isFinite() && it >= 0f) it else 1f }

/** Read-only observer; active only while this Activity is visible. */
private fun systemAnimationScales(context: Context) = callbackFlow {
    val resolver = context.contentResolver
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            trySend(readSystemAnimationScale(context))
        }
    }
    resolver.registerContentObserver(
        Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer
    )
    trySend(readSystemAnimationScale(context))
    awaitClose { resolver.unregisterContentObserver(observer) }
}
