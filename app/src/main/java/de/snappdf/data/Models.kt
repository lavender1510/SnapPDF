package de.snappdf.data

enum class ScanMode { STANDARD, HANDWRITING, HIGH_CONTRAST }
enum class PdfQuality { COMPACT, STANDARD, HIGH }
enum class PaperSize { AUTO, A4, A5 }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class FlashMode { OFF, AUTO, ON }
enum class AppLanguage { SYSTEM, GERMAN, ENGLISH }

data class ScanPoint(val x: Float, val y: Float)
data class DocumentQuad(
    val topLeft: ScanPoint, val topRight: ScanPoint,
    val bottomRight: ScanPoint, val bottomLeft: ScanPoint
) {
    fun points() = listOf(topLeft, topRight, bottomRight, bottomLeft)
    companion object {
        val FULL = DocumentQuad(ScanPoint(0f, 0f), ScanPoint(1f, 0f), ScanPoint(1f, 1f), ScanPoint(0f, 1f))
    }
}
data class DetectionResult(val quad: DocumentQuad?, val confidence: Float, val blurScore: Double, val brightness: Double)
data class OcrLine(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)
data class ScanPage(
    val id: String,
    val originalPath: String,
    val processedPath: String,
    val mode: ScanMode = ScanMode.STANDARD,
    val contrast: Float = 0.5f,
    val quad: DocumentQuad = DocumentQuad.FULL,
    val rotation: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val confidence: Float = 0f,
    val ocrLines: List<OcrLine> = emptyList(),
    val safeForAutoSave: Boolean = false
)
data class ScanSession(val id: String, val title: String, val pages: List<ScanPage> = emptyList())
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val dynamicColors: Boolean = false,
    val reduceMotion: Boolean = false,
    val defaultMode: ScanMode = ScanMode.STANDARD,
    val autoCapture: Boolean = false,
    val detectDocument: Boolean = true,
    val correctPerspective: Boolean = true,
    val flash: FlashMode = FlashMode.OFF,
    val openCameraOnStart: Boolean = false,
    val rememberMode: Boolean = true,
    val autoSave: Boolean = false,
    val skipPreview: Boolean = false,
    val quality: PdfQuality = PdfQuality.STANDARD,
    val paperSize: PaperSize = PaperSize.AUTO,
    val autoName: Boolean = true,
    val exportTreeUri: String = "",
    val ocr: Boolean = false
)
