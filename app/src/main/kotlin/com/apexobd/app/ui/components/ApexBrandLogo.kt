package com.apexobd.app.ui.components

import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared classic motorsport sticker wordmark ("Wordmark B": Apex cut + blue OBD badge).
 * Both Jetpack Compose headers and exported PNG cards invoke the same vector Canvas routine.
 */
object ApexBrandLogo {

    private const val SHEAR = 0.28f
    private const val ASPECT_W_OVER_H = 7.85f

    @Composable
    fun ComposeWordmark(
        modifier: Modifier = Modifier,
        height: Dp = 18.dp
    ) {
        ComposeCanvas(modifier = modifier.size(width = height * ASPECT_W_OVER_H, height = height)) {
            drawIntoCanvas { canvas ->
                drawOnAndroidCanvas(
                    canvas = canvas.nativeCanvas,
                    left = 0f,
                    top = size.height * 0.10f,
                    height = size.height * 0.80f
                )
            }
        }
    }

    /**
     * Draws Wordmark B scaled by [height] at ([left], [top]) on an Android Canvas.
     * The parallelogram badge bleeds vertically by ~12% above and below glyph bounds.
     */
    fun drawOnAndroidCanvas(
        canvas: Canvas,
        left: Float,
        top: Float,
        height: Float
    ) {
        val h = height
        fun pt(ux: Float, uy: Float, offX: Float): Pair<Float, Float> {
            val px = left + offX + (ux + (1f - uy) * SHEAR) * h
            val py = top + uy * h
            return px to py
        }

        fun buildPolyPath(offX: Float, vararg pts: Pair<Float, Float>): Path = Path().apply {
            pts.forEachIndexed { idx, (ux, uy) ->
                val (px, py) = pt(ux, uy, offX)
                if (idx == 0) moveTo(px, py) else lineTo(px, py)
            }
            close()
        }

        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            style = Paint.Style.FILL
        }
        val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(0, 168, 255)
            style = Paint.Style.FILL
        }
        val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(5, 8, 13)
            style = Paint.Style.FILL
        }

        var cursor = 0f

        // 'A' (with signature cyan apex slash crossbar)
        val aOuter = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            val outer = arrayOf(0.41f to 0f, 0.62f to 0f, 0.98f to 1f, 0.74f to 1f, 0.66f to 0.74f, 0.28f to 0.74f, 0.21f to 1f, 0f to 1f)
            outer.forEachIndexed { i, (u, v) ->
                val (x, y) = pt(u, v, cursor)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
            val hole = arrayOf(0.50f to 0.24f, 0.35f to 0.56f, 0.61f to 0.56f)
            hole.forEachIndexed { i, (u, v) ->
                val (x, y) = pt(u, v, cursor)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        canvas.drawPath(aOuter, whitePaint)
        canvas.drawPath(buildPolyPath(cursor, 0.20f to 0.57f, 0.78f to 0.57f, 0.83f to 0.73f, 0.15f to 0.73f), bluePaint)
        cursor += 1.07f * h

        // 'P'
        val pPath = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            val outer = arrayOf(
                0f to 0f, 0.74f to 0f, 0.90f to 0.14f, 0.90f to 0.45f, 0.74f to 0.59f,
                0.23f to 0.59f, 0.23f to 1f, 0f to 1f
            )
            outer.forEachIndexed { i, (u, v) ->
                val (x, y) = pt(u, v, cursor)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
            val hole = arrayOf(0.23f to 0.19f, 0.66f to 0.19f, 0.66f to 0.40f, 0.23f to 0.40f)
            hole.forEachIndexed { i, (u, v) ->
                val (x, y) = pt(u, v, cursor)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        canvas.drawPath(pPath, whitePaint)
        cursor += 0.98f * h

        // 'E'
        val ePath = buildPolyPath(
            cursor,
            0f to 0f, 0.86f to 0f, 0.86f to 0.19f, 0.23f to 0.19f,
            0.23f to 0.40f, 0.74f to 0.40f, 0.74f to 0.59f, 0.23f to 0.59f,
            0.23f to 0.81f, 0.86f to 0.81f, 0.86f to 1f, 0f to 1f
        )
        canvas.drawPath(ePath, whitePaint)
        cursor += 0.95f * h

        // 'X'
        canvas.drawPath(buildPolyPath(cursor, 0.02f to 0f, 0.27f to 0f, 0.95f to 1f, 0.70f to 1f), whitePaint)
        canvas.drawPath(buildPolyPath(cursor, 0.72f to 0f, 0.96f to 0f, 0.25f to 1f, 0.01f to 1f), whitePaint)
        cursor += 1.12f * h

        // Electric-blue slanted OBD badge
        val badgePadX = 0.22f
        val badgeTop = -0.14f
        val badgeBot = 1.14f
        val badgeTotalUnits = badgePadX * 2f + 0.88f + 0.11f + 0.88f + 0.11f + 0.88f
        canvas.drawPath(
            buildPolyPath(
                cursor,
                0f to badgeTop,
                badgeTotalUnits to badgeTop,
                badgeTotalUnits to badgeBot,
                0f to badgeBot
            ),
            bluePaint
        )

        var bCursor = cursor + badgePadX * h

        // 'O' inside badge
        val oPath = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            val outer = arrayOf(
                0.18f to 0f, 0.70f to 0f, 0.88f to 0.18f, 0.88f to 0.82f,
                0.70f to 1f, 0.18f to 1f, 0f to 0.82f, 0f to 0.18f
            )
            outer.forEachIndexed { i, (u, v) ->
                val (x, y) = pt(u, v, bCursor)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
            val hole = arrayOf(
                0.24f to 0.20f, 0.64f to 0.20f, 0.64f to 0.80f, 0.24f to 0.80f
            )
            hole.forEachIndexed { i, (u, v) ->
                val (x, y) = pt(u, v, bCursor)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        canvas.drawPath(oPath, darkPaint)
        bCursor += 0.99f * h

        // 'B' inside badge
        val bPath = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            val outer = arrayOf(
                0f to 0f, 0.70f to 0f, 0.86f to 0.15f, 0.86f to 0.40f,
                0.74f to 0.49f, 0.88f to 0.59f, 0.88f to 0.85f, 0.72f to 1f, 0f to 1f
            )
            outer.forEachIndexed { i, (u, v) ->
                val (x, y) = pt(u, v, bCursor)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
            val h1 = arrayOf(0.23f to 0.18f, 0.62f to 0.18f, 0.62f to 0.39f, 0.23f to 0.39f)
            h1.forEachIndexed { i, (u, v) ->
                val (x, y) = pt(u, v, bCursor)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
            val h2 = arrayOf(0.23f to 0.58f, 0.65f to 0.58f, 0.65f to 0.82f, 0.23f to 0.82f)
            h2.forEachIndexed { i, (u, v) ->
                val (x, y) = pt(u, v, bCursor)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        canvas.drawPath(bPath, darkPaint)
        bCursor += 0.99f * h

        // 'D' inside badge
        val dPath = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            val outer = arrayOf(0f to 0f, 0.68f to 0f, 0.88f to 0.20f, 0.88f to 0.80f, 0.68f to 1f, 0f to 1f)
            outer.forEachIndexed { i, (u, v) ->
                val (x, y) = pt(u, v, bCursor)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
            val hole = arrayOf(0.23f to 0.20f, 0.64f to 0.20f, 0.64f to 0.80f, 0.23f to 0.80f)
            hole.forEachIndexed { i, (u, v) ->
                val (x, y) = pt(u, v, bCursor)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        canvas.drawPath(dPath, darkPaint)
    }
}
