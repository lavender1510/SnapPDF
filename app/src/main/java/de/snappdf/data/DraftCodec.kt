package de.snappdf.data

import org.json.JSONArray
import org.json.JSONObject

/** Explicit, versioned disk format. Unknown additions can safely be ignored by older releases. */
internal object DraftCodec {
    fun encode(session: ScanSession): String = JSONObject().apply {
        put("version", 1)
        put("id", session.id)
        put("title", session.title)
        put("pages", JSONArray().apply {
            session.pages.forEach { page -> put(JSONObject().apply {
                put("id", page.id)
                put("originalPath", page.originalPath)
                put("processedPath", page.processedPath)
                put("mode", page.mode.name)
                put("contrast", page.contrast.toDouble())
                put("rotation", page.rotation)
                put("width", page.width)
                put("height", page.height)
                put("confidence", page.confidence.toDouble())
                put("safeForAutoSave", page.safeForAutoSave)
                put("quad", JSONArray().apply {
                    page.quad.points().forEach { p -> put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble())) }
                })
                put("ocrLines", JSONArray().apply {
                    page.ocrLines.forEach { line -> put(JSONObject().apply {
                        put("text", line.text)
                        put("left", line.left.toDouble())
                        put("top", line.top.toDouble())
                        put("right", line.right.toDouble())
                        put("bottom", line.bottom.toDouble())
                    }) }
                })
            }) }
        })
    }.toString()

    fun decode(value: String): ScanSession {
        val json = JSONObject(value)
        require(json.optInt("version", 1) == 1) { "Unbekanntes Entwurfsformat" }
        val pages = json.getJSONArray("pages")
        return ScanSession(json.getString("id"), json.optString("title", "Scan"),
            (0 until pages.length()).map { index ->
                val page = pages.getJSONObject(index)
                val corners = page.getJSONArray("quad")
                fun corner(i: Int): ScanPoint = corners.getJSONArray(i).let {
                    ScanPoint(it.getDouble(0).toFloat().coerceIn(0f, 1f), it.getDouble(1).toFloat().coerceIn(0f, 1f))
                }
                val lines = page.optJSONArray("ocrLines") ?: JSONArray()
                ScanPage(
                    id = page.getString("id"),
                    originalPath = page.getString("originalPath"),
                    processedPath = page.getString("processedPath"),
                    mode = runCatching { ScanMode.valueOf(page.getString("mode")) }.getOrDefault(ScanMode.STANDARD),
                    contrast = page.optDouble("contrast", 0.5).toFloat().coerceIn(0f, 1f),
                    quad = DocumentQuad(corner(0), corner(1), corner(2), corner(3)),
                    rotation = page.optInt("rotation", 0),
                    width = page.optInt("width", 0),
                    height = page.optInt("height", 0),
                    confidence = page.optDouble("confidence", 0.0).toFloat(),
                    ocrLines = (0 until lines.length()).map { n -> lines.getJSONObject(n).let {
                        OcrLine(it.getString("text"), it.getDouble("left").toFloat(), it.getDouble("top").toFloat(),
                            it.getDouble("right").toFloat(), it.getDouble("bottom").toFloat())
                    } },
                    safeForAutoSave = page.optBoolean("safeForAutoSave", false)
                )
            })
    }
}
