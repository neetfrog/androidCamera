package com.procamera.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.unit.dp
import com.procamera.app.data.HistogramData
import com.procamera.app.ui.theme.Surface800

/**
 * RGB + Luma histogram overlay, drawn as filled semi-transparent area charts.
 */
@Composable
fun Histogram(
    data: HistogramData,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xCC0A0A0A))
            .padding(4.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawHistogramChannel(data.red,   Color(0x88FF3333))
            drawHistogramChannel(data.green, Color(0x8833FF66))
            drawHistogramChannel(data.blue,  Color(0x884499FF))
            drawHistogramChannel(data.luma,  Color(0x44FFFFFF))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHistogramChannel(
    values: FloatArray,
    color: Color
) {
    if (values.isEmpty()) return
    val n = values.size
    val w = size.width
    val h = size.height
    val stepX = w / n.toFloat()

    val path = Path()
    path.moveTo(0f, h)
    values.forEachIndexed { i, v ->
        val x = i * stepX
        val y = h - v.coerceIn(0f, 1f) * h
        if (i == 0) path.lineTo(x, y) else path.lineTo(x + stepX / 2f, y)
    }
    path.lineTo(w, h)
    path.close()

    drawPath(path = path, color = color, style = Fill)

    // Draw top line slightly brighter
    val linePath = Path()
    values.forEachIndexed { i, v ->
        val x = i * stepX + stepX / 2f
        val y = h - v.coerceIn(0f, 1f) * h
        if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
    }
    drawPath(
        path = linePath,
        color = color.copy(alpha = (color.alpha * 2f).coerceAtMost(1f)),
        style = androidx.compose.ui.graphics.drawscope.Stroke(strokeWidth = 1.2f)
    )
}
