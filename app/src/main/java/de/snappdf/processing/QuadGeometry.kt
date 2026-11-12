package de.snappdf.processing

import de.snappdf.data.DocumentQuad
import de.snappdf.data.ScanPoint
import kotlin.math.abs
import kotlin.math.hypot

/** Geometry uses normalized coordinates in the EXIF-corrected original image. */
object QuadGeometry {
    fun area(quad: DocumentQuad): Float {
        val points = quad.points()
        return abs(points.indices.sumOf { i ->
            val p = points[i]
            val q = points[(i + 1) % 4]
            (p.x * q.y - q.x * p.y).toDouble()
        }.toFloat()) / 2f
    }

    fun isValid(quad: DocumentQuad): Boolean {
        val points = quad.points()
        if (points.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f }) return false
        if (area(quad) < 0.015f) return false
        val crosses = points.indices.map { i ->
            val a = points[i]
            val b = points[(i + 1) % 4]
            val c = points[(i + 2) % 4]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        return crosses.all { it > 0.0001f } && points.indices.all { i ->
            val a = points[i]
            val b = points[(i + 1) % 4]
            hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) >= 0.035
        }
    }
}
