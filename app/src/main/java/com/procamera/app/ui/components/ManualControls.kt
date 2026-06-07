package com.procamera.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.procamera.app.data.*
import com.procamera.app.ui.theme.OrangePrimary
import com.procamera.app.viewmodel.CameraViewModel
import kotlin.math.roundToInt

/**
 * Side panel with full manual camera controls.
 *
 * Sliders:
 *  ▸ ISO          (100 – 12 800)
 *  ▸ Shutter      (1/4000 – 4 s)
 *  ▸ Exposure Comp (−3 EV – +3 EV)
 *  ▸ White Balance (2 000 K – 10 000 K)
 *  ▸ Focus Distance (0 = ∞ – max)
 *  ▸ Zoom (1× – maxZoom)
 *
 * Auto/Manual toggles for exposure, WB and focus.
 */
@Composable
fun ManualControls(
    state: CameraUiState,
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier
) {
    val s = state.settings

    Column(
        modifier = modifier
            .width(180.dp)
            .fillMaxHeight()
            .background(Color(0xEE0A0A0A))
            .padding(horizontal = 12.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "MANUAL",
            color = OrangePrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace
        )

        HorizontalDivider(color = Color(0x33FFFFFF))

        // ── Exposure ───────────────────────────────────────────────────────────
        AutoManualToggle(
            label = "EXPOSURE",
            isAuto = s.isAutoExposure,
            onToggle = { viewModel.updateSetting { it.copy(isAutoExposure = !it.isAutoExposure) } }
        )

        if (!s.isAutoExposure) {
            // ISO
            ControlSlider(
                label = "ISO",
                value = isoToSlider(s.iso),
                displayValue = s.iso.toString(),
                onValueChange = { viewModel.updateSetting { st -> st.copy(iso = sliderToIso(it)) } }
            )

            // Shutter speed
            ControlSlider(
                label = "SHUTTER",
                value = shutterToSlider(s.shutterSpeed),
                displayValue = formatShutter(s.shutterSpeed),
                onValueChange = { viewModel.updateSetting { st -> st.copy(shutterSpeed = sliderToShutter(it)) } }
            )
        } else {
            // Exposure compensation (only meaningful in auto mode)
            ControlSlider(
                label = "EV COMP",
                value = (s.exposureCompensation + 9f) / 18f,
                displayValue = formatEv(s.exposureCompensation),
                onValueChange = {
                    val ev = ((it * 18f) - 9f).roundToInt().coerceIn(-9, 9)
                    viewModel.updateSetting { st -> st.copy(exposureCompensation = ev) }
                }
            )
        }

        HorizontalDivider(color = Color(0x22FFFFFF))

        // ── White Balance ──────────────────────────────────────────────────────
        AutoManualToggle(
            label = "WHITE BALANCE",
            isAuto = s.isAutoWhiteBalance,
            onToggle = { viewModel.updateSetting { it.copy(isAutoWhiteBalance = !it.isAutoWhiteBalance) } }
        )

        if (!s.isAutoWhiteBalance) {
            ControlSlider(
                label = "${s.whiteBalanceKelvin} K",
                value = (s.whiteBalanceKelvin - 2000f) / 8000f,
                displayValue = "${s.whiteBalanceKelvin}",
                onValueChange = {
                    val k = ((it * 8000f) + 2000f).roundToInt()
                        .let { raw -> (raw / 50) * 50 }  // round to nearest 50 K
                        .coerceIn(2000, 10000)
                    viewModel.updateSetting { st -> st.copy(whiteBalanceKelvin = k) }
                }
            )
            // WB presets
            WbPresets { k -> viewModel.updateSetting { it.copy(whiteBalanceKelvin = k, isAutoWhiteBalance = false) } }
        }

        HorizontalDivider(color = Color(0x22FFFFFF))

        // ── Focus ──────────────────────────────────────────────────────────────
        AutoManualToggle(
            label = "FOCUS",
            isAuto = s.isAutoFocus,
            onToggle = { viewModel.updateSetting { it.copy(isAutoFocus = !it.isAutoFocus) } }
        )

        if (!s.isAutoFocus) {
            val maxFocus = state.minFocusDistance.coerceAtLeast(0.1f)
            ControlSlider(
                label = "FOCUS DIST",
                value = s.focusDistance / maxFocus,
                displayValue = if (s.focusDistance < 0.05f) "∞" else "${"%.1f".format(s.focusDistance)}",
                onValueChange = {
                    viewModel.updateSetting { st -> st.copy(focusDistance = it * maxFocus) }
                }
            )
        }

        HorizontalDivider(color = Color(0x22FFFFFF))

        // ── Video options ──────────────────────────────────────────────────────
        Text("VIDEO", color = OrangePrimary, fontSize = 10.sp, letterSpacing = 1.5.sp)

        // Frame rate selector
        Text("FRAME RATE", color = Color(0xAAFFFFFF), fontSize = 9.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(24, 30, 60, 120).forEach { fps ->
                val sel = s.frameRate == fps
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 32.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (sel) OrangePrimary else Color(0x33FFFFFF))
                        .clickable { viewModel.updateSetting { it.copy(frameRate = fps) } }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${fps}p",
                        color = if (sel) Color.Black else Color.White,
                        fontSize = 10.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        // Resolution selector
        Text("RESOLUTION", color = Color(0xAAFFFFFF), fontSize = 9.sp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            VideoResolution.entries.forEach { res ->
                val sel = s.videoResolution == res
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (sel) OrangePrimary else Color(0x33FFFFFF))
                        .clickable { viewModel.updateSetting { it.copy(videoResolution = res) } }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        "${res.label}  ${res.width}×${res.height}",
                        color = if (sel) Color.Black else Color.White,
                        fontSize = 10.sp
                    )
                }
            }
        }

        HorizontalDivider(color = Color(0x22FFFFFF))

        // ── Stabilisation ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("STABILISE", color = Color(0xAAFFFFFF), fontSize = 10.sp)
            Switch(
                checked = s.isStabilization,
                onCheckedChange = { viewModel.updateSetting { st -> st.copy(isStabilization = it) } },
                colors = SwitchDefaults.colors(checkedThumbColor = OrangePrimary, checkedTrackColor = OrangePrimary.copy(alpha = 0.4f))
            )
        }

        Spacer(Modifier.height(20.dp))
    }
}

// ─── Reusable sub-components ──────────────────────────────────────────────────

@Composable
private fun AutoManualToggle(label: String, isAuto: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color(0xCCFFFFFF), fontSize = 10.sp, letterSpacing = 0.8.sp)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (!isAuto) OrangePrimary else Color(0x44FFFFFF))
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 3.dp)
        ) {
            Text(
                text = if (isAuto) "AUTO" else "MAN",
                color = if (!isAuto) Color.Black else Color.White,
                fontSize = 9.sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ControlSlider(
    label: String,
    value: Float,
    displayValue: String,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Color(0x88FFFFFF), fontSize = 9.sp)
            Text(
                displayValue,
                color = OrangePrimary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().height(28.dp),
            colors = SliderDefaults.colors(
                thumbColor = OrangePrimary,
                activeTrackColor = OrangePrimary,
                inactiveTrackColor = Color(0x44FFFFFF)
            )
        )
    }
}

@Composable
private fun WbPresets(onSelect: (Int) -> Unit) {
    val presets = listOf(
        "☁" to 6500, "☀" to 5500, "⚡" to 3200, "💡" to 2800, "🌑" to 4000
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        presets.forEach { (icon, k) ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x33FFFFFF))
                    .clickable { onSelect(k) }
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(icon, fontSize = 14.sp)
            }
        }
    }
}

// ─── ISO mapping (logarithmic steps) ─────────────────────────────────────────

private val isoStops = intArrayOf(
    100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
    1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800
)

private fun isoToSlider(iso: Int): Float {
    val idx = isoStops.indexOfFirst { it >= iso }.coerceAtLeast(0)
    return idx.toFloat() / (isoStops.size - 1)
}

private fun sliderToIso(v: Float): Int {
    val idx = (v * (isoStops.size - 1)).roundToInt().coerceIn(0, isoStops.size - 1)
    return isoStops[idx]
}

// ─── Shutter speed mapping ────────────────────────────────────────────────────

private val shutterStops = floatArrayOf(
    4000f, 3200f, 2500f, 2000f, 1600f, 1250f, 1000f, 800f, 640f, 500f,
    400f, 320f, 250f, 200f, 160f, 125f, 100f, 80f, 60f, 50f,
    40f, 30f, 25f, 20f, 15f, 13f, 10f, 8f, 6f, 5f,
    4f, 3f, 2.5f, 2f, 1.6f, 1.3f, 1f, 0.8f, 0.6f, 0.5f,
    0.4f, 0.3f, 0.25f
)

private fun shutterToSlider(ss: Float): Float {
    val idx = shutterStops.indexOfFirst { it <= ss }.coerceAtLeast(0)
    return 1f - idx.toFloat() / (shutterStops.size - 1)  // invert: higher = faster
}

private fun sliderToShutter(v: Float): Float {
    val idx = ((1f - v) * (shutterStops.size - 1)).roundToInt().coerceIn(0, shutterStops.size - 1)
    return shutterStops[idx]
}

private fun formatShutter(ss: Float): String = when {
    ss >= 1f   -> "1/${ss.roundToInt()}"
    else       -> "${(1f / ss).roundToInt()}s"
}

private fun formatEv(ev: Int): String {
    val value = ev / 3f
    val formatted = if (value % 1f == 0f) {
        value.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
    return if (value > 0) "+$formatted" else formatted
}
