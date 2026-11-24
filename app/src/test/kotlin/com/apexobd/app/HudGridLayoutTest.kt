package com.apexobd.app

import com.apexobd.app.data.db.HudWidgetEntity
import com.apexobd.app.data.prefs.HudGridScale
import com.apexobd.app.ui.GridBounds
import com.apexobd.app.ui.WidgetResizeEdge
import com.apexobd.app.ui.computeResizedGridBounds
import com.apexobd.app.ui.findFirstFreeGridSlot
import com.apexobd.app.ui.resolveGridPlacements
import org.junit.Assert.assertEquals
import org.junit.Test

class HudGridLayoutTest {

    @Test
    fun `resolveGridPlacements keeps dropped tile coordinates without compacting left to right`() {
        val rightHalfMap = HudWidgetEntity(
            id = 1L,
            pageId = 1L,
            sensorKey = "minimap",
            displayType = "MINI_MAP",
            spanCols = 4,
            spanRows = 4,
            sortOrder = 0,
            gridCol = 4,
            gridRow = 0
        )
        val bottomLeftGauge = HudWidgetEntity(
            id = 2L,
            pageId = 1L,
            sensorKey = "rpm",
            displayType = "DIGITAL",
            spanCols = 2,
            spanRows = 1,
            sortOrder = 1,
            gridCol = 1,
            gridRow = 3
        )

        val placements = resolveGridPlacements(listOf(rightHalfMap, bottomLeftGauge), cols = 8, rows = 4)
        assertEquals(2, placements.size)
        assertEquals(4, placements[0].col)
        assertEquals(0, placements[0].row)
        assertEquals(1, placements[1].col)
        assertEquals(3, placements[1].row)
    }

    @Test
    fun `findFirstFreeGridSlot places new component in open tiles without disturbing pinned layout`() {
        val rightHalfMap = HudWidgetEntity(
            id = 1L,
            pageId = 1L,
            sensorKey = "minimap",
            displayType = "MINI_MAP",
            spanCols = 4,
            spanRows = 4,
            sortOrder = 0,
            gridCol = 4,
            gridRow = 0
        )

        val (col, row) = findFirstFreeGridSlot(listOf(rightHalfMap), cols = 8, rows = 4, spanCols = 2, spanRows = 2)
        assertEquals(0, col)
        assertEquals(0, row)

        val occupiedLeftTop = HudWidgetEntity(
            id = 2L,
            pageId = 1L,
            sensorKey = "rpm",
            displayType = "ANALOG_DIAL",
            spanCols = 4,
            spanRows = 2,
            sortOrder = 1,
            gridCol = 0,
            gridRow = 0
        )
        val (nextCol, nextRow) = findFirstFreeGridSlot(listOf(rightHalfMap, occupiedLeftTop), cols = 8, rows = 4, spanCols = 2, spanRows = 2)
        assertEquals(0, nextCol)
        assertEquals(2, nextRow)
    }

    @Test
    fun `resolveGridPlacements clamps oversized pinned positions when switching to smaller grid scale`() {
        val oversizedTile = HudWidgetEntity(
            id = 3L,
            pageId = 1L,
            sensorKey = "coolant",
            displayType = "BAR",
            spanCols = 3,
            spanRows = 2,
            sortOrder = 0,
            gridCol = 7,
            gridRow = 3
        )
        val placements = resolveGridPlacements(listOf(oversizedTile), cols = 6, rows = 3)
        assertEquals(3, placements[0].col)
        assertEquals(1, placements[0].row)
    }

    @Test
    fun `computeResizedGridBounds updates all four edges cleanly on 24x12 ultra grid`() {
        assertEquals(24, HudGridScale.ULTRA_24X12.cols)
        assertEquals(12, HudGridScale.ULTRA_24X12.rows)
        assertEquals(16, HudGridScale.FINE_16X8.cols)
        assertEquals(8, HudGridScale.FINE_16X8.rows)

        // Expand right edge by 3 cells
        assertEquals(
            GridBounds(col = 4, row = 2, spanCols = 7, spanRows = 3),
            computeResizedGridBounds(4, 2, 4, 3, WidgetResizeEdge.RIGHT, deltaCells = 3, totalCols = 24, totalRows = 12)
        )
        // Drag left edge left by 2 cells (origin moves left, span grows)
        assertEquals(
            GridBounds(col = 2, row = 2, spanCols = 6, spanRows = 3),
            computeResizedGridBounds(4, 2, 4, 3, WidgetResizeEdge.LEFT, deltaCells = -2, totalCols = 24, totalRows = 12)
        )
        // Drag left edge past right edge clamps to minimum 1-cell width at rightEdge - 1
        assertEquals(
            GridBounds(col = 7, row = 2, spanCols = 1, spanRows = 3),
            computeResizedGridBounds(4, 2, 4, 3, WidgetResizeEdge.LEFT, deltaCells = 10, totalCols = 24, totalRows = 12)
        )
        // Drag top edge up by 2 cells
        assertEquals(
            GridBounds(col = 4, row = 0, spanCols = 4, spanRows = 5),
            computeResizedGridBounds(4, 2, 4, 3, WidgetResizeEdge.TOP, deltaCells = -4, totalCols = 24, totalRows = 12)
        )
        // Drag bottom edge down past canvas bottom clamps to totalRows - row
        assertEquals(
            GridBounds(col = 4, row = 2, spanCols = 4, spanRows = 10),
            computeResizedGridBounds(4, 2, 4, 3, WidgetResizeEdge.BOTTOM, deltaCells = 20, totalCols = 24, totalRows = 12)
        )
    }
}
