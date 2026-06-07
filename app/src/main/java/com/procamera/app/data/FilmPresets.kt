package com.procamera.app.data

import androidx.compose.ui.graphics.Color

/**
 * Film preset color adjustments for real film stock emulation.
 * RGB values are applied as color filters to the preview.
 */
data class FilmPresetConfig(
    val preset: FilmPreset,
    val description: String,
    // Color channels: R, G, B multipliers (1.0 = no change)
    val colorShift: Color,
    // Saturation boost (1.0 = normal)
    val saturation: Float,
    // Contrast boost (1.0 = normal)
    val contrast: Float,
    // Highlights tint
    val highlightsTint: Color,
    // Shadows tint
    val shadowsTint: Color
)

object FilmPresetsLibrary {
    fun getPreset(preset: FilmPreset): FilmPresetConfig? = when (preset) {
        FilmPreset.NONE -> null

        // Kodak Portra 400 - warm, saturated, flattering for skin tones
        FilmPreset.PORTRA_400 -> FilmPresetConfig(
            preset = FilmPreset.PORTRA_400,
            description = "Warm, saturated. Great for portraits.",
            colorShift = Color(1.05f, 1.0f, 0.95f),    // warm (red-yellow boost)
            saturation = 1.2f,
            contrast = 0.95f,
            highlightsTint = Color(1.1f, 1.05f, 0.95f), // warm highlights
            shadowsTint = Color(1.0f, 1.0f, 1.08f)      // cool shadows
        )

        // Kodak Portra 800 - even warmer, more forgiving
        FilmPreset.PORTRA_800 -> FilmPresetConfig(
            preset = FilmPreset.PORTRA_800,
            description = "Warmer Portra. Soft and forgiving.",
            colorShift = Color(1.08f, 1.0f, 0.92f),
            saturation = 1.25f,
            contrast = 0.9f,
            highlightsTint = Color(1.15f, 1.08f, 0.9f),
            shadowsTint = Color(1.0f, 1.0f, 1.12f)
        )

        // Kodak Ektar 100 - super saturated, warm
        FilmPreset.EKTAR_100 -> FilmPresetConfig(
            preset = FilmPreset.EKTAR_100,
            description = "Super saturated, vibrant colors.",
            colorShift = Color(1.1f, 1.05f, 0.88f),
            saturation = 1.4f,
            contrast = 1.1f,
            highlightsTint = Color(1.15f, 1.1f, 0.85f),
            shadowsTint = Color(0.98f, 0.98f, 1.1f)
        )

        // Kodak T-Max 100 - high contrast B&W simulation
        FilmPreset.TMAX_100 -> FilmPresetConfig(
            preset = FilmPreset.TMAX_100,
            description = "High contrast B&W look. Crisp and sharp.",
            colorShift = Color(1.0f, 1.0f, 1.0f),
            saturation = 0.0f,  // fully desaturated
            contrast = 1.3f,
            highlightsTint = Color(1.0f, 1.0f, 1.0f),
            shadowsTint = Color(1.0f, 1.0f, 1.0f)
        )

        // Kodak Tri-X - classic B&W with grain feel
        FilmPreset.TRIX_400 -> FilmPresetConfig(
            preset = FilmPreset.TRIX_400,
            description = "Classic B&W. Warm blacks, rich tones.",
            colorShift = Color(1.02f, 1.0f, 0.98f),  // very slight warmth
            saturation = 0.0f,
            contrast = 1.25f,
            highlightsTint = Color(1.0f, 1.0f, 1.0f),
            shadowsTint = Color(1.05f, 1.02f, 0.98f)
        )

        // Fuji Astia 100 - cool, contrasty
        FilmPreset.FUJI_ASTIA -> FilmPresetConfig(
            preset = FilmPreset.FUJI_ASTIA,
            description = "Cool tones, punchy contrast.",
            colorShift = Color(0.95f, 1.02f, 1.08f),  // cool (blue boost)
            saturation = 1.15f,
            contrast = 1.15f,
            highlightsTint = Color(0.95f, 1.0f, 1.15f),
            shadowsTint = Color(1.05f, 1.02f, 1.0f)
        )

        // Fuji Velvia 50 - mega-saturated, cool
        FilmPreset.VELVIA -> FilmPresetConfig(
            preset = FilmPreset.VELVIA,
            description = "Hyper-saturated, vivid. For landscapes.",
            colorShift = Color(0.92f, 1.08f, 1.1f),   // cool, cyan-magenta boost
            saturation = 1.5f,
            contrast = 1.2f,
            highlightsTint = Color(0.9f, 1.05f, 1.2f),
            shadowsTint = Color(1.08f, 1.0f, 0.95f)
        )
    }

    fun applyFilmFilter(baseColor: Color, preset: FilmPresetConfig?): Color {
        if (preset == null) return baseColor

        var r = baseColor.red
        var g = baseColor.green
        var b = baseColor.blue

        // Apply color shift
        r *= preset.colorShift.red
        g *= preset.colorShift.green
        b *= preset.colorShift.blue

        // Apply saturation
        if (preset.saturation != 1.0f) {
            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            r = luma + (r - luma) * preset.saturation
            g = luma + (g - luma) * preset.saturation
            b = luma + (b - luma) * preset.saturation
        }

        // Apply contrast
        if (preset.contrast != 1.0f) {
            val mid = 0.5f
            r = mid + (r - mid) * preset.contrast
            g = mid + (g - mid) * preset.contrast
            b = mid + (b - mid) * preset.contrast
        }

        // Clamp to valid range
        r = r.coerceIn(0f, 1f)
        g = g.coerceIn(0f, 1f)
        b = b.coerceIn(0f, 1f)

        return Color(r, g, b, baseColor.alpha)
    }
}
