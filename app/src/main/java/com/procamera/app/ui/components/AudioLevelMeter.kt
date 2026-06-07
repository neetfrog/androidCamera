package com.procamera.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.procamera.app.ui.theme.GreenLevel
import com.procamera.app.ui.theme.RecordRed
import com.procamera.app.ui.theme.YellowWarn

/**
 * Stereo PPM-style audio level meter for video recording.
 * [level] is 0.0f (silence) to 1.0f (full scale).
 */
@Composable
fun AudioLevelMeter(
    level: Float,
    modifier: Modifier = Modifier
) {
    val animated by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 80),
        label = "audioLevel"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "MIC",
            color = Color(0xFFB0BEC5),
            fontSize = 9.sp,
            modifier = Modifier.width(24.dp)
        )
        MeterBar(level = animated, Modifier.weight(1f).height(6.dp))
        MeterBar(level = animated * 0.93f, Modifier.weight(1f).height(6.dp)) // slight L/R difference
        val db = if (animated > 0.001f)
            (20 * Math.log10(animated.toDouble())).toInt().coerceIn(-60, 0)
        else -60
        Text(
            text = "${db}dB",
            color = Color(0xFFB0BEC5),
            fontSize = 9.sp,
            modifier = Modifier.width(36.dp)
        )
    }
}

@Composable
private fun MeterBar(level: Float, modifier: Modifier = Modifier) {
    val segments = 20
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0xFF1E1E1E)),
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        repeat(segments) { i ->
            val threshold = (i + 1f) / segments
            val lit = level >= threshold
            val color = when {
                threshold > 0.90f -> RecordRed
                threshold > 0.75f -> YellowWarn
                else              -> GreenLevel
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (lit) color else color.copy(alpha = 0.15f))
            )
        }
    }
}
