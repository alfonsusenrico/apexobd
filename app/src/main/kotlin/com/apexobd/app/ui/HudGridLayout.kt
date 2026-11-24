package com.apexobd.app.ui

import com.apexobd.app.data.db.HudWidgetEntity
import kotlin.math.roundToInt

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
 * Computes clamped grid origin and span when dragging one of the four edge resize handles,
 * enforcing readable minimum component spans (`minSpanCols`, `minSpanRows`).
 */
fun computeResizedGridBounds(
    startCol: Int,
    startRow: Int,
    startSpanCols: Int,
    startSpanRows: Int,
    edge: WidgetResizeEdge,
    deltaCells: Int,
    totalCols: Int,
    totalRows: Int,
    minSpanCols: Int = 1,
    minSpanRows: Int = 1
): GridBounds {
    val safeCols = totalCols.coerceAtLeast(1)
    val safeRows = totalRows.coerceAtLeast(1)
    val safeMinCols = minSpanCols.coerceIn(1, safeCols)
    val safeMinRows = minSpanRows.coerceIn(1, safeRows)
    val initSpanCols = startSpanCols.coerceIn(safeMinCols, safeCols)
    val initSpanRows = startSpanRows.coerceIn(safeMinRows, safeRows)
    val initCol = startCol.coerceIn(0, (safeCols - initSpanCols).coerceAtLeast(0))
    val initRow = startRow.coerceIn(0, (safeRows - initSpanRows).coerceAtLeast(0))

    return when (edge) {
        WidgetResizeEdge.RIGHT -> {
            val maxSpan = (safeCols - initCol).coerceAtLeast(safeMinCols)
            GridBounds(
                col = initCol,
                row = initRow,
                spanCols = (initSpanCols + deltaCells).coerceIn(safeMinCols, maxSpan),
                spanRows = initSpanRows
            )
        }
        WidgetResizeEdge.LEFT -> {
            val rightEdge = initCol + initSpanCols
            val maxLeftCol = (rightEdge - safeMinCols).coerceAtLeast(0)
            val nextCol = (initCol + deltaCells).coerceIn(0, maxLeftCol)
            GridBounds(
                col = nextCol,
                row = initRow,
                spanCols = (rightEdge - nextCol).coerceIn(safeMinCols, safeCols),
                spanRows = initSpanRows
            )
        }
        WidgetResizeEdge.BOTTOM -> {
            val maxSpan = (safeRows - initRow).coerceAtLeast(safeMinRows)
            GridBounds(
                col = initCol,
                row = initRow,
                spanCols = initSpanCols,
                spanRows = (initSpanRows + deltaCells).coerceIn(safeMinRows, maxSpan)
            )
        }
        WidgetResizeEdge.TOP -> {
            val bottomEdge = initRow + initSpanRows
            val maxTopRow = (bottomEdge - safeMinRows).coerceAtLeast(0)
            val nextRow = (initRow + deltaCells).coerceIn(0, maxTopRow)
            GridBounds(
                col = initCol,
                row = nextRow,
                spanCols = initSpanCols,
                spanRows = (bottomEdge - nextRow).coerceIn(safeMinRows, safeRows)
            )
        }
    }
}

/**
 * Proportionally rescales a widget's grid position and span when switching HUD grid density
 * so every existing component keeps its real physical size on screen instead of shrinking.
 */
fun rescaleWidgetBoundsForGridChange(
    widget: HudWidgetEntity,
    fromCols: Int,
    fromRows: Int,
    toCols: Int,
    toRows: Int,
    minSpanCols: Int,
    minSpanRows: Int
): HudWidgetEntity {
    if (fromCols <= 0 || fromRows <= 0 || toCols <= 0 || toRows <= 0) return widget
    if (fromCols == toCols && fromRows == toRows) return widget
    val scaleX = toCols.toFloat() / fromCols.toFloat()
    val scaleY = toRows.toFloat() / fromRows.toFloat()
    val safeMinCols = minSpanCols.coerceIn(1, toCols)
    val safeMinRows = minSpanRows.coerceIn(1, toRows)
    val nextSpanCols = (widget.spanCols * scaleX).roundToInt().coerceIn(safeMinCols, toCols)
    val nextSpanRows = (widget.spanRows * scaleY).roundToInt().coerceIn(safeMinRows, toRows)
    val nextCol = if (widget.gridCol >= 0) {
        (widget.gridCol * scaleX).roundToInt().coerceIn(0, (toCols - nextSpanCols).coerceAtLeast(0))
    } else {
        widget.gridCol
    }
    val nextRow = if (widget.gridRow >= 0) {
        (widget.gridRow * scaleY).roundToInt().coerceIn(0, (toRows - nextSpanRows).coerceAtLeast(0))
    } else {
        widget.gridRow
    }
    return widget.copy(
        spanCols = nextSpanCols,
        spanRows = nextSpanRows,
        gridCol = nextCol,
        gridRow = nextRow,
        freeformXFraction = if (nextCol >= 0) (nextCol.toFloat() / toCols.toFloat()).coerceIn(0f, 0.95f) else widget.freeformXFraction,
        freeformYFraction = if (nextRow >= 0) (nextRow.toFloat() / toRows.toFloat()).coerceIn(0f, 0.95f) else widget.freeformYFraction
    )
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
