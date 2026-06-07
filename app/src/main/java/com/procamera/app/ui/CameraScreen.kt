package com.procamera.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.procamera.app.data.*
import com.procamera.app.ui.components.*
import com.procamera.app.ui.theme.OrangePrimary
import com.procamera.app.ui.theme.RecordRed
import com.procamera.app.viewmodel.CameraViewModel

/**
 * Main camera screen — full-screen viewfinder with all overlays and controls.
 */
@Composable
fun CameraScreen(viewModel: CameraViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ── Full-screen preview ────────────────────────────────────────────────
        ViewfinderSurface(
            modifier = Modifier.fillMaxSize(),
            viewModel = viewModel
        )

        // ── Grid overlay ───────────────────────────────────────────────────────
        GridOverlay(
            gridMode = state.gridMode,
            modifier = Modifier.fillMaxSize()
        )

        // ── Level indicator ────────────────────────────────────────────────────
        if (state.showLevelIndicator) {
            LevelIndicator(
                pitch = state.pitch,
                roll  = state.roll,
                modifier = Modifier
                    .size(180.dp)
                    .align(Alignment.Center)
            )
        }

        // ── Timer countdown overlay ────────────────────────────────────────────
        if (state.timerCountdown > 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x44000000)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = state.timerCountdown.toString(),
                    color = OrangePrimary,
                    fontSize = 120.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // ── Top controls ───────────────────────────────────────────────────────
        TopControls(
            state = state,
            viewModel = viewModel,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
        )

        // ── Recording indicator ────────────────────────────────────────────────
        if (state.isRecording) {
            RecordingIndicator(
                durationSec = state.recordingDurationSec,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(end = 12.dp, top = 48.dp)
            )
        }

        // ── Manual controls side panel ─────────────────────────────────────────
        AnimatedVisibility(
            visible = state.showManualControls,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(250)
            ) + fadeIn(tween(250)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(200)
            ) + fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            ManualControls(
                state = state,
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.statusBars)
            )
        }

        // ── Bottom section (histogram + audio meter + controls) ────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            // Histogram
            AnimatedVisibility(
                visible = state.showHistogram,
                enter = expandVertically() + fadeIn(),
                exit  = shrinkVertically() + fadeOut()
            ) {
                Histogram(
                    data = state.histogramData,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // Audio level meter (during video recording)
            AnimatedVisibility(visible = state.isRecording) {
                AudioLevelMeter(
                    level = state.audioLevel,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Camera controls
            BottomControls(
                state = state,
                viewModel = viewModel
            )
        }

        // ── Camera parameters HUD ──────────────────────────────────────────────
        if (!state.showManualControls) {
            CameraParamsHud(
                state = state,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
            )
        }

        // ── Snackbar messages ──────────────────────────────────────────────────
        state.savedMessage?.let { msg ->
            SnackMessage(
                message = msg,
                color = Color(0xFF4CAF50),
                modifier = Modifier.align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 60.dp)
            )
        }
        state.errorMessage?.let { msg ->
            SnackMessage(
                message = msg,
                color = RecordRed,
                modifier = Modifier.align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 60.dp)
            )
        }
    }
}

// ── HUD showing key exposure values ───────────────────────────────────────────

@Composable
private fun CameraParamsHud(
    state: CameraUiState,
    modifier: Modifier = Modifier
) {
    val s = state.settings
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        HudValue(label = "ISO",  value = if (s.isAutoExposure) "AUTO" else s.iso.toString())
        HudValue(label = "SS",   value = if (s.isAutoExposure) "AUTO" else formatShutterHud(s.shutterSpeed))
        HudValue(label = "WB",   value = if (s.isAutoWhiteBalance) "AWB" else "${s.whiteBalanceKelvin}K")
        HudValue(label = "AF",   value = if (s.isAutoFocus) "AF" else "MF")
        HudValue(label = "ZOOM", value = "${"%.1f".format(s.zoomRatio)}×")
        if (s.isLogColorSpace) {
            HudValue(label = "LOG", value = "ON", highlight = true)
        }
    }
}

@Composable
private fun HudValue(label: String, value: String, highlight: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Color(0x66FFFFFF), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text(
            value,
            color = if (highlight) OrangePrimary else Color(0xCCFFFFFF),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

// ── Recording indicator (REC + timer) ────────────────────────────────────────

@Composable
private fun RecordingIndicator(durationSec: Int, modifier: Modifier = Modifier) {
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "blink")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(700),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "blink"
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(RecordRed.copy(alpha = alpha))
        )
        Text(
            text = formatDuration(durationSec),
            color = Color.White,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ── Toast-style message ────────────────────────────────────────────────────────

@Composable
private fun SnackMessage(message: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.9f))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(message, color = Color.White, fontSize = 12.sp)
    }
}

// ─── Formatters ───────────────────────────────────────────────────────────────

private fun formatShutterHud(ss: Float): String = when {
    ss >= 1f -> "1/${ss.toInt()}"
    else     -> "${(1f / ss).toInt()}s"
}

private fun formatDuration(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%02d:%02d:%02d".format(h, m, s)
    else "%02d:%02d".format(m, s)
}
