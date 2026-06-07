package com.procamera.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.procamera.app.data.*
import com.procamera.app.ui.theme.*
import com.procamera.app.viewmodel.CameraViewModel

/**
 * Top control bar: flash, timer, grid, histogram, level, focus peaking, zebra.
 */
@Composable
fun TopControls(
    state: CameraUiState,
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0x88000000))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Flash
        TopIconBtn(
            icon = when (state.settings.flashMode) {
                FlashMode.OFF   -> Icons.Default.FlashOff
                FlashMode.ON    -> Icons.Default.FlashOn
                FlashMode.AUTO  -> Icons.Default.FlashAuto
                FlashMode.TORCH -> Icons.Default.Highlight
            },
            label = state.settings.flashMode.name,
            active = state.settings.flashMode != FlashMode.OFF,
            onClick = { viewModel.cycleFlash() }
        )

        // Timer
        TopIconBtn(
            icon = when (state.timerSeconds) {
                3  -> Icons.Default.Timer3Select
                10 -> Icons.Default.Timer10Select
                else -> Icons.Default.TimerOff
            },
            label = when (state.timerSeconds) {
                0 -> "OFF"; 3 -> "3s"; else -> "10s"
            },
            active = state.timerSeconds > 0,
            onClick = { viewModel.cycleTimer() }
        )

        // Grid
        TopIconBtn(
            icon = when (state.gridMode) {
                GridMode.NONE -> Icons.Default.GridOff
                else          -> Icons.Default.GridOn
            },
            label = when (state.gridMode) {
                GridMode.NONE   -> "OFF"
                GridMode.THIRDS -> "3rds"
                GridMode.SQUARE -> "1:1"
                GridMode.GOLDEN -> "φ"
            },
            active = state.gridMode != GridMode.NONE,
            onClick = { viewModel.cycleGrid() }
        )

        // Histogram
        TopIconBtn(
            icon = Icons.Default.BarChart,
            label = "HIST",
            active = state.showHistogram,
            onClick = { viewModel.toggleHistogram() }
        )

        // Level meter
        TopIconBtn(
            icon = Icons.Default.Straighten,
            label = "LEVEL",
            active = state.showLevelIndicator,
            onClick = { viewModel.toggleLevelIndicator() }
        )

        // Focus peaking
        TopIconBtn(
            icon = Icons.Default.CenterFocusStrong,
            label = "PEAK",
            active = state.showFocusPeaking,
            onClick = { viewModel.toggleFocusPeaking() }
        )

        // Zebra (overexposure highlight)
        TopIconBtn(
            icon = Icons.Default.Tonality,
            label = "ZEBRA",
            active = state.showZebra,
            onClick = { viewModel.toggleZebra() }
        )
    }
}

@Composable
fun TopIconBtn(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) OrangePrimary else Color(0x99FFFFFF),
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            color = if (active) OrangePrimary else Color(0x88FFFFFF),
            fontSize = 8.sp
        )
    }
}
