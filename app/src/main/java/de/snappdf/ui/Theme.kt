package de.snappdf.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import de.snappdf.data.*
import java.util.Locale

val LocalEnglish = staticCompositionLocalOf { false }
@Composable fun tr(de: String, en: String): String = if (LocalEnglish.current) en else de

@Composable fun SnapTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val context = LocalContext.current
    val scheme = when {
        settings.dynamicColors && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF7EA6FF), onPrimary = Color(0xFF002A72), primaryContainer = Color(0xFF1D3563), onPrimaryContainer = Color(0xFFD8E5FF), secondary = Color(0xFFB0C4E5), onSecondary = Color(0xFF23344E), secondaryContainer = Color(0xFF344861), onSecondaryContainer = Color(0xFFDBE7FB), surfaceVariant = Color(0xFF2B3340), outline = Color(0xFF6F7F95), inverseSurface = Color(0xFFE4EBF5), inverseOnSurface = Color(0xFF172033), inversePrimary = Color(0xFF2563EB), surfaceTint = Color(0xFF7EA6FF), background = Color(0xFF101318), surface = Color(0xFF1C2128), onSurface = Color(0xFFF8FAFC), onBackground = Color(0xFFF8FAFC), onSurfaceVariant = Color(0xFFADB8C9), surfaceContainerLowest = Color(0xFF101318), surfaceContainerLow = Color(0xFF1C2128), surfaceContainer = Color(0xFF242B35), surfaceContainerHigh = Color(0xFF2B3340), surfaceContainerHighest = Color(0xFF333D4C), outlineVariant = Color(0xFF343D4B))
        else -> lightColorScheme(primary = Color(0xFF2563EB), onPrimary = Color.White, primaryContainer = Color(0xFFE8F0FF), onPrimaryContainer = Color(0xFF173C83), secondary = Color(0xFF51627B), onSecondary = Color.White, secondaryContainer = Color(0xFFE4ECF8), onSecondaryContainer = Color(0xFF23344E), surfaceVariant = Color(0xFFEEF2F7), outline = Color(0xFF91A0B5), inverseSurface = Color(0xFF243247), inverseOnSurface = Color(0xFFF8FAFC), inversePrimary = Color(0xFFADC6FF), surfaceTint = Color(0xFF2563EB), background = Color(0xFFF8FAFC), surface = Color.White, onSurface = Color(0xFF172033), onBackground = Color(0xFF172033), onSurfaceVariant = Color(0xFF64748B), surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8FAFC), surfaceContainer = Color(0xFFF0F4FA), surfaceContainerHigh = Color(0xFFE8EEF6), surfaceContainerHighest = Color(0xFFE1E8F2), outlineVariant = Color(0xFFE2E8F0))
    }
    val english = when (settings.language) { AppLanguage.ENGLISH -> true; AppLanguage.GERMAN -> false; AppLanguage.SYSTEM -> Locale.getDefault().language != "de" }
    CompositionLocalProvider(LocalEnglish provides english) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}
