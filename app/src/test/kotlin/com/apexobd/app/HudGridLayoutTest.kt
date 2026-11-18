package com.apexobd.app

import com.apexobd.app.data.db.HudWidgetEntity
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
}
