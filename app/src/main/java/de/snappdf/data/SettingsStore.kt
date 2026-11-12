package de.snappdf.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings
    @Synchronized fun update(transform: (AppSettings) -> AppSettings) {
        val value = transform(_settings.value)
        val json = JSONObject().apply {
            put("theme", value.theme.name); put("language", value.language.name)
            put("dynamicColors", value.dynamicColors); put("reduceMotion", value.reduceMotion)
            put("defaultMode", value.defaultMode.name); put("autoCapture", value.autoCapture)
            put("detectDocument", value.detectDocument); put("correctPerspective", value.correctPerspective)
            put("flash", value.flash.name); put("openCameraOnStart", value.openCameraOnStart)
            put("rememberMode", value.rememberMode); put("autoSave", value.autoSave)
            put("skipPreview", value.skipPreview); put("quality", value.quality.name)
            put("paperSize", value.paperSize.name); put("autoName", value.autoName)
            put("exportTreeUri", value.exportTreeUri); put("ocr", value.ocr)
        }
        prefs.edit().putString("json", json.toString()).apply()
        _settings.value = value
    }
    private inline fun <reified T : Enum<T>> JSONObject.enum(key: String, fallback: T): T =
        runCatching { enumValueOf<T>(optString(key)) }.getOrDefault(fallback)
    private fun read(): AppSettings = runCatching {
        val j = JSONObject(prefs.getString("json", "{}") ?: "{}")
        AppSettings(
            theme = j.enum("theme", ThemeMode.SYSTEM), language = j.enum("language", AppLanguage.SYSTEM),
            dynamicColors = j.optBoolean("dynamicColors"), reduceMotion = j.optBoolean("reduceMotion"),
            defaultMode = j.enum("defaultMode", ScanMode.STANDARD), autoCapture = j.optBoolean("autoCapture"),
            detectDocument = j.optBoolean("detectDocument", true), correctPerspective = j.optBoolean("correctPerspective", true),
            flash = j.enum("flash", FlashMode.OFF), openCameraOnStart = j.optBoolean("openCameraOnStart"),
            rememberMode = j.optBoolean("rememberMode", true), autoSave = j.optBoolean("autoSave"),
            skipPreview = j.optBoolean("skipPreview"), quality = j.enum("quality", PdfQuality.STANDARD),
            paperSize = j.enum("paperSize", PaperSize.AUTO), autoName = j.optBoolean("autoName", true),
            exportTreeUri = j.optString("exportTreeUri"), ocr = j.optBoolean("ocr")
        )
    }.getOrDefault(AppSettings())
}
