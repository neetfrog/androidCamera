package com.procamera.app.ui.components

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
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
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0x88000000))
            .heightIn(min = 106.dp)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Camera selector
            if (state.cameraOptions.isNotEmpty()) {
                CameraSelector(
                    options = state.cameraOptions,
                    currentCameraId = state.currentCameraId,
                    onSelect = viewModel::selectCamera
                )
            }

            // Gallery/Folder
            TopIconBtn(
                icon = Icons.Default.PhotoLibrary,
                label = "FOLDER",
                active = false,
                onClick = { viewModel.openSavedFilesFolder(context) }
            )

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
        }

        Spacer(modifier = Modifier.height(8.dp))

        ExposureInfoRow(
            state = state,
            viewModel = viewModel
        )
    }
}

@Composable
fun ExposureInfoRow(
    state: CameraUiState,
    viewModel: com.procamera.app.viewmodel.CameraViewModel
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ExposureChip(
            icon = Icons.Default.Bolt,
            text = if (state.settings.isAutoExposure) "AUTO" else state.settings.iso.toString(),
            onClick = { viewModel.toggleAutoExposure() }
        )
        ExposureChip(
            icon = Icons.Default.Timer,
            text = if (state.settings.isAutoExposure) "AUTO" else formatShutterHud(state.settings.shutterSpeed),
            onClick = { viewModel.toggleAutoExposure() }
        )
        ExposureChip(
            icon = Icons.Default.WbAuto,
            text = if (state.settings.isAutoWhiteBalance) "AWB" else "${state.settings.whiteBalanceKelvin}K",
            onClick = { viewModel.toggleAutoWhiteBalance() }
        )
        ExposureChip(
            icon = Icons.Default.TextFields,
            text = if (state.settings.isAutoFocus) "AF" else "MF",
            onClick = { viewModel.toggleAutoFocus() }
        )
    }
}

@Composable
fun ExposureChip(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x22FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(
            text = text,
            color = Color.White,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatShutterHud(ss: Float): String = when {
    ss >= 1f -> "1/${ss.toInt()}"
    else     -> "${(1f / ss).toInt()}s"
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
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) OrangePrimary else Color(0x99FFFFFF),
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = label,
            color = if (active) OrangePrimary else Color(0x88FFFFFF),
            fontSize = 9.sp
        )
    }
}

@Composable
fun CameraSelector(
    options: List<CameraOption>,
    currentCameraId: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.id == currentCameraId }?.label ?: "Camera"

    Box {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = currentLabel,
                tint = Color(0x99FFFFFF),
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = currentLabel,
                color = Color(0x88FFFFFF),
                fontSize = 9.sp
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onSelect(option.id)
                    }
                )
            }
        }
    }
}

