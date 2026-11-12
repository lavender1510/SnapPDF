package de.snappdf

import de.snappdf.data.DocumentQuad
import de.snappdf.data.ScanPoint
import de.snappdf.processing.QuadGeometry
import org.junit.Assert.*
import org.junit.Test

class QuadGeometryTest {
    @Test fun fullPageHasUnitAreaAndIsValid() {
        assertEquals(1f, QuadGeometry.area(DocumentQuad.FULL), 0.0001f)
        assertTrue(QuadGeometry.isValid(DocumentQuad.FULL))
    }

    @Test fun skewedWorksheetIsValid() {
        val quad = DocumentQuad(ScanPoint(.14f, .07f), ScanPoint(.88f, .17f), ScanPoint(.97f, .90f), ScanPoint(.03f, .98f))
        assertTrue(QuadGeometry.isValid(quad))
        assertTrue(QuadGeometry.area(quad) > .6f)
    }

    @Test fun crossedCornersAreRejected() {
        assertFalse(QuadGeometry.isValid(DocumentQuad(ScanPoint(.1f, .1f), ScanPoint(.9f, .9f), ScanPoint(.9f, .1f), ScanPoint(.1f, .9f))))
    }

    @Test fun degenerateAndOutOfBoundsAndNanAreRejected() {
        val point = ScanPoint(.5f, .5f)
        assertFalse(QuadGeometry.isValid(DocumentQuad(point, point, point, point)))
        assertFalse(QuadGeometry.isValid(DocumentQuad.FULL.copy(topLeft = ScanPoint(-.1f, 0f))))
        assertFalse(QuadGeometry.isValid(DocumentQuad.FULL.copy(topLeft = ScanPoint(Float.NaN, 0f))))
    }
}
