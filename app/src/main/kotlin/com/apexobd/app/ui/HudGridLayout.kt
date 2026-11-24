package com.apexobd.app.ui

import com.apexobd.app.data.db.HudWidgetEntity

data class GridPlacement(
    val widget: HudWidgetEntity,
    val col: Int,
    val row: Int,
    val spanCols: Int,
    val spanRows: Int
)

enum class WidgetResizeEdge {
    LEFT,
    RIGHT,
    TOP,
    BOTTOM
}

data class GridBounds(
    val col: Int,
    val row: Int,
    val spanCols: Int,
    val spanRows: Int
)

/**
 * Computes clamped grid origin and span when dragging one of the four edge resize handles.
 */
fun computeResizedGridBounds(
    startCol: Int,
    startRow: Int,
    startSpanCols: Int,
    startSpanRows: Int,
    edge: WidgetResizeEdge,
    deltaCells: Int,
    totalCols: Int,
    totalRows: Int
): GridBounds {
    val safeCols = totalCols.coerceAtLeast(1)
    val safeRows = totalRows.coerceAtLeast(1)
    val initSpanCols = startSpanCols.coerceIn(1, safeCols)
    val initSpanRows = startSpanRows.coerceIn(1, safeRows)
    val initCol = startCol.coerceIn(0, (safeCols - initSpanCols).coerceAtLeast(0))
    val initRow = startRow.coerceIn(0, (safeRows - initSpanRows).coerceAtLeast(0))

    return when (edge) {
        WidgetResizeEdge.RIGHT -> {
            val maxSpan = (safeCols - initCol).coerceAtLeast(1)
            GridBounds(
                col = initCol,
                row = initRow,
                spanCols = (initSpanCols + deltaCells).coerceIn(1, maxSpan),
                spanRows = initSpanRows
            )
        }
        WidgetResizeEdge.LEFT -> {
            val rightEdge = initCol + initSpanCols
            val nextCol = (initCol + deltaCells).coerceIn(0, (rightEdge - 1).coerceAtLeast(0))
            GridBounds(
                col = nextCol,
                row = initRow,
                spanCols = (rightEdge - nextCol).coerceIn(1, safeCols),
                spanRows = initSpanRows
            )
        }
        WidgetResizeEdge.BOTTOM -> {
            val maxSpan = (safeRows - initRow).coerceAtLeast(1)
            GridBounds(
                col = initCol,
                row = initRow,
                spanCols = initSpanCols,
                spanRows = (initSpanRows + deltaCells).coerceIn(1, maxSpan)
            )
        }
        WidgetResizeEdge.TOP -> {
            val bottomEdge = initRow + initSpanRows
            val nextRow = (initRow + deltaCells).coerceIn(0, (bottomEdge - 1).coerceAtLeast(0))
            GridBounds(
                col = initCol,
                row = nextRow,
                spanCols = initSpanCols,
                spanRows = (bottomEdge - nextRow).coerceIn(1, safeRows)
            )
        }
    }
}

/**
 * Places widgets on the HUD grid while honoring explicit dropped coordinates (`gridCol`, `gridRow`)
 * instead of compacting everything left-to-right. Unplaced legacy widgets (`-1`) fill open slots.
 */
fun resolveGridPlacements(
    widgets: List<HudWidgetEntity>,
    cols: Int,
    rows: Int
): List<GridPlacement> {
    if (cols <= 0 || rows <= 0) return emptyList()
    val maxNeededRows = (rows * 3).coerceAtLeast(widgets.sumOf { it.spanRows.coerceIn(1, rows) } + rows)
    val occupied = Array(maxNeededRows) { BooleanArray(cols) }
    val resolved = mutableMapOf<Long, GridPlacement>()

    // Pass 1: Lock widgets with explicit tile coordinates exactly where the user placed them
    for (w in widgets) {
        if (w.gridCol < 0 || w.gridRow < 0) continue
        val sw = w.spanCols.coerceIn(1, cols)
        val sh = w.spanRows.coerceIn(1, rows)
        val col = w.gridCol.coerceIn(0, (cols - sw).coerceAtLeast(0))
        val row = w.gridRow.coerceIn(0, (rows - sh).coerceAtLeast(0))
        for (dr in 0 until sh) {
            val rr = row + dr
            if (rr in occupied.indices) {
                for (dc in 0 until sw) {
                    occupied[rr][col + dc] = true
                }
            }
        }
        resolved[w.id] = GridPlacement(w, col, row, sw, sh)
    }

    // Pass 2: Place any unpositioned widgets into the first open region without moving pinned tiles
    for (w in widgets) {
        if (w.id in resolved) continue
        val sw = w.spanCols.coerceIn(1, cols)
        val sh = w.spanRows.coerceIn(1, rows)
        val (c, r) = findFirstOpenRegion(occupied, cols, rows, sw, sh)
        for (dr in 0 until sh) {
            val rr = r + dr
            if (rr in occupied.indices) {
                for (dc in 0 until sw) {
                    occupied[rr][c + dc] = true
                }
            }
        }
        resolved[w.id] = GridPlacement(w, c, r, sw, sh)
    }

    return widgets.mapNotNull { resolved[it.id] }
}

fun findFirstFreeGridSlot(
    existing: List<HudWidgetEntity>,
    cols: Int,
    rows: Int,
    spanCols: Int,
    spanRows: Int
): Pair<Int, Int> {
    val sw = spanCols.coerceIn(1, cols)
    val sh = spanRows.coerceIn(1, rows)
    val occupied = Array((rows * 3).coerceAtLeast(rows + sh)) { BooleanArray(cols) }
    for (placement in resolveGridPlacements(existing, cols, rows)) {
        for (dr in 0 until placement.spanRows) {
            val rr = placement.row + dr
            if (rr in occupied.indices) {
                for (dc in 0 until placement.spanCols) {
                    val cc = placement.col + dc
                    if (cc in 0 until cols) occupied[rr][cc] = true
                }
            }
        }
    }
    return findFirstOpenRegion(occupied, cols, rows, sw, sh)
}

private fun findFirstOpenRegion(
    occupied: Array<BooleanArray>,
    cols: Int,
    rows: Int,
    sw: Int,
    sh: Int
): Pair<Int, Int> {
    for (r in 0..(rows - sh).coerceAtLeast(0)) {
        for (c in 0..(cols - sw).coerceAtLeast(0)) {
            if (regionIsFree(occupied, r, c, sw, sh)) return c to r
        }
    }
    return 0 to 0
}

private fun regionIsFree(
    occupied: Array<BooleanArray>,
    row: Int,
    col: Int,
    sw: Int,
    sh: Int
): Boolean {
    for (dr in 0 until sh) {
        val rr = row + dr
        if (rr !in occupied.indices) return false
        for (dc in 0 until sw) {
            if (occupied[rr][col + dc]) return false
        }
    }
    return true
}
