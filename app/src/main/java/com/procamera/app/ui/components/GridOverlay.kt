package com.procamera.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.procamera.app.data.GridMode

/**
 * Transparent grid overlay drawn over the viewfinder.
 */
@Composable
fun GridOverlay(
    gridMode: GridMode,
    modifier: Modifier = Modifier
) {
    if (gridMode == GridMode.NONE) return

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val color = Color(0x55FFFFFF)
        val thickColor = Color(0x88FFFFFF)
        val sw = 1f

        when (gridMode) {

            GridMode.THIRDS -> {
                // Vertical lines at 1/3 and 2/3
                drawLine(color, Offset(w / 3f, 0f), Offset(w / 3f, h), sw)
                drawLine(color, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), sw)
                // Horizontal lines at 1/3 and 2/3
                drawLine(color, Offset(0f, h / 3f), Offset(w, h / 3f), sw)
                drawLine(color, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), sw)
                // Corner marks at intersections
                val points = listOf(
                    w / 3f to h / 3f,  2f * w / 3f to h / 3f,
                    w / 3f to 2f * h / 3f, 2f * w / 3f to 2f * h / 3f
                )
                val cs = 12f
                for ((px, py) in points) {
                    drawLine(thickColor, Offset(px - cs, py), Offset(px + cs, py), sw * 2)
                    drawLine(thickColor, Offset(px, py - cs), Offset(px, py + cs), sw * 2)
                }
            }

            GridMode.SQUARE -> {
                // Show 1:1 crop boundary (portrait, centred)
                val cropSize = w
                val top = (h - cropSize) / 2f
                val bottom = top + cropSize
                if (top > 0) {
                    drawLine(thickColor, Offset(0f, top), Offset(w, top), sw * 2)
                    drawLine(thickColor, Offset(0f, bottom), Offset(w, bottom), sw * 2)
                }
                // Rule of thirds inside the square
                drawLine(color, Offset(w / 3f, top), Offset(w / 3f, bottom), sw)
                drawLine(color, Offset(2f * w / 3f, top), Offset(2f * w / 3f, bottom), sw)
                val th = cropSize / 3f
                drawLine(color, Offset(0f, top + th), Offset(w, top + th), sw)
                drawLine(color, Offset(0f, top + 2f * th), Offset(w, top + 2f * th), sw)
            }

            GridMode.GOLDEN -> {
                // Golden ratio ≈ 0.382 / 0.618
                val phi = 0.6180f
                val vx1 = w * (1f - phi)
                val vx2 = w * phi
                val hy1 = h * (1f - phi)
                val hy2 = h * phi
                drawLine(color, Offset(vx1, 0f), Offset(vx1, h), sw)
                drawLine(color, Offset(vx2, 0f), Offset(vx2, h), sw)
                drawLine(color, Offset(0f, hy1), Offset(w, hy1), sw)
                drawLine(color, Offset(0f, hy2), Offset(w, hy2), sw)
            }

            GridMode.NONE -> { /* nothing */ }
        }

        // Centre crosshair (always)
        val cx = w / 2f; val cy = h / 2f; val chs = 20f
        drawLine(Color(0x55FFFFFF), Offset(cx - chs, cy), Offset(cx + chs, cy), sw)
        drawLine(Color(0x55FFFFFF), Offset(cx, cy - chs), Offset(cx, cy + chs), sw)
    }
}
