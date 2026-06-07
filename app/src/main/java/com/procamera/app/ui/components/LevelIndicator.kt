package com.procamera.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.procamera.app.ui.theme.GreenLevel
import com.procamera.app.ui.theme.OrangePrimary
import com.procamera.app.ui.theme.RecordRed
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Artificial horizon level indicator.
 *
 * Shows:
 *  - Horizontal bar (roll) — rotates with device tilt
 *  - Center reticle
 *  - Roll arc tick marks
 *  - Pitch offset dot
 *
 * Colour:
 *  - Green when within ±1° of level
 *  - Orange within ±5°
 *  - Red otherwise
 */
@Composable
fun LevelIndicator(
    pitch: Float,
    roll: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val barLen = size.width * 0.30f
        val rollRad = Math.toRadians(roll.toDouble())

        val levelColor = when {
            abs(roll) <= 1f && abs(pitch) <= 1f -> GreenLevel
            abs(roll) <= 5f                      -> OrangePrimary
            else                                 -> RecordRed
        }

        val alpha = 0.85f
        val stroke = Stroke(width = 3f, cap = StrokeCap.Round)

        // ── Outer ring ─────────────────────────────────────────────────────────
        val ringR = barLen * 0.55f
        drawCircle(
            color = levelColor.copy(alpha = 0.25f),
            radius = ringR,
            center = Offset(cx, cy),
            style = Stroke(width = 1f)
        )

        // ── Roll tick marks (every 10°) ────────────────────────────────────────
        for (deg in listOf(-30, -20, -10, 0, 10, 20, 30)) {
            val tickRad = Math.toRadians(deg.toDouble())
            val tickLen = if (deg == 0) 18f else 10f
            val x1 = (cx + ringR * sin(tickRad)).toFloat()
            val y1 = (cy - ringR * cos(tickRad)).toFloat()
            val x2 = (cx + (ringR + tickLen) * sin(tickRad)).toFloat()
            val y2 = (cy - (ringR + tickLen) * cos(tickRad)).toFloat()
            drawLine(
                color = levelColor.copy(alpha = 0.5f),
                start = Offset(x1, y1),
                end   = Offset(x2, y2),
                strokeWidth = if (deg == 0) 2f else 1f
            )
        }

        // ── Horizon bar (rotates with roll) ────────────────────────────────────
        val cosR = cos(rollRad).toFloat()
        val sinR = sin(rollRad).toFloat()
        val dx = barLen * cosR
        val dy = barLen * sinR
        val barCenter = Offset(cx, cy)
        drawLine(
            color = levelColor.copy(alpha = alpha),
            start = Offset(barCenter.x - dx, barCenter.y - dy),
            end   = Offset(barCenter.x + dx, barCenter.y + dy),
            strokeWidth = 4f,
            cap = StrokeCap.Round
        )

        // ── Centre reticle ─────────────────────────────────────────────────────
        val cs = 12f
        drawLine(levelColor.copy(alpha = alpha),
            start = Offset(cx - cs, cy), end = Offset(cx + cs, cy), strokeWidth = 3f)
        drawLine(levelColor.copy(alpha = alpha),
            start = Offset(cx, cy - cs), end = Offset(cx, cy + cs), strokeWidth = 3f)
        drawCircle(
            color = levelColor.copy(alpha = 0.4f),
            radius = 5f,
            center = Offset(cx, cy),
            style = Stroke(width = 2f)
        )
    }
}
