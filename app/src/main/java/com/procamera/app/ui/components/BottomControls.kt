package com.procamera.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.procamera.app.data.*
import com.procamera.app.ui.theme.*
import com.procamera.app.viewmodel.CameraViewModel

/**
 * Bottom controls:
 *  - Mode selector (scrollable pill row)
 *  - Zoom indicator + slider
 *  - Camera flip  |  Capture/Record button  |  Manual toggle
 */
@Composable
fun BottomControls(
    state: CameraUiState,
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC000000))
            .padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Mode selector ──────────────────────────────────────────────────────
        if (!state.isRecording) {
            ModeSelector(
                current = state.captureMode,
                onSelect = viewModel::setCaptureMode,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        // ── Zoom slider ────────────────────────────────────────────────────────
        ZoomControl(
            zoom = state.settings.zoomRatio,
            minZoom = state.minZoomRatio,
            maxZoom = state.maxZoomRatio,
            onZoomChange = viewModel::setZoom,
            modifier = Modifier.padding(horizontal = 40.dp)
        )

        Spacer(Modifier.height(8.dp))

        // ── Action row ─────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Camera flip
            IconButton(
                onClick = { if (!state.isRecording) viewModel.toggleCamera() },
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color(0x33FFFFFF))
            ) {
                Icon(
                    imageVector = Icons.Default.FlipCameraAndroid,
                    contentDescription = "Flip camera",
                    tint = if (state.isRecording) Color(0x55FFFFFF) else Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }

            // Main capture / record button
            CaptureButton(
                isVideo = state.captureMode == CaptureMode.VIDEO,
                isRecording = state.isRecording,
                isCapturing = state.isCapturing,
                timerCountdown = state.timerCountdown,
                onClick = {
                    when {
                        state.isRecording -> viewModel.stopVideoRecording()
                        state.captureMode == CaptureMode.VIDEO -> viewModel.startVideoRecording()
                        else -> viewModel.capturePhoto()
                    }
                }
            )

            // Manual controls toggle
            IconButton(
                onClick = { viewModel.toggleManualControls() },
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(
                        if (state.showManualControls) OrangePrimary.copy(alpha = 0.3f)
                        else Color(0x33FFFFFF)
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "Manual controls",
                    tint = if (state.showManualControls) OrangePrimary else Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

// ── Mode selector ─────────────────────────────────────────────────────────────

@Composable
fun ModeSelector(
    current: CaptureMode,
    onSelect: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CaptureMode.entries.forEach { mode ->
            val selected = mode == current
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (selected) OrangePrimary else Color(0x33FFFFFF)
                    )
                    .clickable { onSelect(mode) }
                    .padding(horizontal = 14.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = mode.label,
                    color = if (selected) Color.Black else Color.White,
                    fontSize = 11.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    letterSpacing = 1.2.sp
                )
            }
        }
    }
}

// ── Capture button ────────────────────────────────────────────────────────────

@Composable
fun CaptureButton(
    isVideo: Boolean,
    isRecording: Boolean,
    isCapturing: Boolean,
    timerCountdown: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = if (isRecording) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = modifier
            .size((76 * pulse).dp)
            .clip(CircleShape)
            .background(
                when {
                    isRecording -> RecordRed
                    isVideo     -> Color(0xDDEF4444)
                    else        -> Color(0xDDFFFFFF)
                }
            )
            .border(3.dp,
                when {
                    isRecording -> RecordRed.copy(alpha = 0.5f)
                    isVideo     -> RecordRed.copy(alpha = 0.4f)
                    else        -> Color(0x55FFFFFF)
                },
                CircleShape
            )
            .clickable(enabled = !isCapturing, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        when {
            timerCountdown > 0 -> Text(
                text = timerCountdown.toString(),
                color = Color.Black,
                fontSize = 28.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            isRecording -> Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White)
            )
            isVideo -> Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(RecordRed)
            )
            else -> { /* White circle = photo shutter */ }
        }
    }
}

// ── Zoom control ──────────────────────────────────────────────────────────────

@Composable
fun ZoomControl(
    zoom: Float,
    minZoom: Float,
    maxZoom: Float,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Quick zoom preset buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            listOf(minZoom, 1f, 2f, 4f, 8f).forEach { preset ->
                if (preset in minZoom..maxZoom) {
                    val label = when {
                        preset == minZoom && preset != 1f -> "${"%.1f".format(preset)}×"
                        preset < 2f -> "1×"
                        else -> "${preset.toInt()}×"
                    }
                    val sel = kotlin.math.abs(zoom - preset) < 0.15f
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (sel) OrangePrimary else Color(0x33FFFFFF)
                            )
                            .clickable { onZoomChange(preset) }
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (sel) Color.Black else Color.White,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
        // Continuous zoom slider
        Slider(
            value = zoom,
            onValueChange = onZoomChange,
            valueRange = minZoom..maxZoom.coerceAtLeast(minZoom + 0.1f),
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = OrangePrimary,
                activeTrackColor = OrangePrimary,
                inactiveTrackColor = Color(0x44FFFFFF)
            )
        )
    }
}
