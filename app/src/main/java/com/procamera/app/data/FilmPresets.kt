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
            colorShift = Color(1.15f, 1.0f, 0.85f),    // strong warm (red-yellow)
            saturation = 1.3f,
            contrast = 0.95f,
            highlightsTint = Color(1.2f, 1.1f, 0.9f), // warm highlights
            shadowsTint = Color(1.0f, 1.0f, 1.1f)      // cool shadows
        )

        // Kodak Portra 800 - even warmer, more forgiving
        FilmPreset.PORTRA_800 -> FilmPresetConfig(
            preset = FilmPreset.PORTRA_800,
            description = "Warmer Portra. Soft and forgiving.",
            colorShift = Color(1.2f, 1.0f, 0.8f),
            saturation = 1.35f,
            contrast = 0.9f,
            highlightsTint = Color(1.25f, 1.15f, 0.85f),
            shadowsTint = Color(1.0f, 1.0f, 1.15f)
        )

        // Kodak Ektar 100 - super saturated, warm
        FilmPreset.EKTAR_100 -> FilmPresetConfig(
            preset = FilmPreset.EKTAR_100,
            description = "Super saturated, vibrant colors.",
            colorShift = Color(1.25f, 1.1f, 0.75f),
            saturation = 1.6f,
            contrast = 1.2f,
            highlightsTint = Color(1.3f, 1.2f, 0.8f),
            shadowsTint = Color(0.95f, 0.95f, 1.05f)
        )

        // Kodak T-Max 100 - high contrast B&W simulation
        FilmPreset.TMAX_100 -> FilmPresetConfig(
            preset = FilmPreset.TMAX_100,
            description = "High contrast B&W look. Crisp and sharp.",
            colorShift = Color(0.5f, 0.5f, 0.5f),  // strong grayscale tint
            saturation = 0.0f,  // fully desaturated
            contrast = 1.4f,
            highlightsTint = Color(0.5f, 0.5f, 0.5f),
            shadowsTint = Color(0.5f, 0.5f, 0.5f)
        )

        // Kodak Tri-X - classic B&W with grain feel
        FilmPreset.TRIX_400 -> FilmPresetConfig(
            preset = FilmPreset.TRIX_400,
            description = "Classic B&W. Warm blacks, rich tones.",
            colorShift = Color(0.55f, 0.55f, 0.55f),  // grayscale with slight warmth
            saturation = 0.0f,
            contrast = 1.3f,
            highlightsTint = Color(0.55f, 0.55f, 0.55f),
            shadowsTint = Color(0.6f, 0.55f, 0.5f)
        )

        // Fuji Astia 100 - cool, contrasty
        FilmPreset.FUJI_ASTIA -> FilmPresetConfig(
            preset = FilmPreset.FUJI_ASTIA,
            description = "Cool tones, punchy contrast.",
            colorShift = Color(0.9f, 1.05f, 1.2f),  // strong cool (blue/cyan boost)
            saturation = 1.25f,
            contrast = 1.2f,
            highlightsTint = Color(0.9f, 1.0f, 1.25f),
            shadowsTint = Color(1.1f, 1.05f, 1.0f)
        )

        // Fuji Velvia 50 - mega-saturated, cool
        FilmPreset.VELVIA -> FilmPresetConfig(
            preset = FilmPreset.VELVIA,
            description = "Hyper-saturated, vivid. For landscapes.",
            colorShift = Color(0.85f, 1.15f, 1.25f),   // strong cool cyan-magenta
            saturation = 1.7f,
            contrast = 1.3f,
            highlightsTint = Color(0.8f, 1.1f, 1.3f),
            shadowsTint = Color(1.1f, 1.0f, 0.9f)
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
