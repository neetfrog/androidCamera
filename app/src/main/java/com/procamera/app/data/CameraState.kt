package com.procamera.app.data

// ─── Enumerations ────────────────────────────────────────────────────────────

enum class FlashMode { OFF, ON, AUTO, TORCH }

enum class CaptureMode(val label: String) {
    PHOTO("PHOTO"),
    RAW("RAW"),
    VIDEO("VIDEO")
}

enum class GridMode { NONE, THIRDS, SQUARE, GOLDEN }

enum class VideoResolution(val width: Int, val height: Int, val label: String) {
    HD_720P(1280, 720, "720p"),
    FHD_1080P(1920, 1080, "1080p"),
    UHD_4K(3840, 2160, "4K")
}

// ─── Settings (immutable data class) ─────────────────────────────────────────

data class CameraSettings(
    // Exposure
    val isAutoExposure: Boolean = true,
    val iso: Int = 200,
    val shutterSpeed: Float = 60f,           // displayed as "1/shutterSpeed"
    val exposureCompensation: Int = 0,        // in 1/3-EV steps, -9..+9

    // White Balance
    val isAutoWhiteBalance: Boolean = true,
    val whiteBalanceKelvin: Int = 5500,       // 2000..10000 K

    // Focus
    val isAutoFocus: Boolean = true,
    val focusDistance: Float = 0f,            // 0 = infinity, max = macro

    // Color / Tone
    val isLogColorSpace: Boolean = false,

    // Stabilization
    val isStabilization: Boolean = true,

    // Flash
    val flashMode: FlashMode = FlashMode.OFF,

    // Zoom
    val zoomRatio: Float = 1f,

    // Video
    val frameRate: Int = 30,
    val videoResolution: VideoResolution = VideoResolution.FHD_1080P
)

// ─── Histogram data ───────────────────────────────────────────────────────────

data class HistogramData(
    val red: FloatArray = FloatArray(64),
    val green: FloatArray = FloatArray(64),
    val blue: FloatArray = FloatArray(64),
    val luma: FloatArray = FloatArray(64)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HistogramData) return false
        return red.contentEquals(other.red)
    }
    override fun hashCode(): Int = red.contentHashCode()
}

// ─── UI State ─────────────────────────────────────────────────────────────────

data class CameraUiState(
    // Mode
    val captureMode: CaptureMode = CaptureMode.PHOTO,

    // Recording
    val isRecording: Boolean = false,
    val recordingDurationSec: Int = 0,

    // Camera
    val isFrontCamera: Boolean = false,
    val isCameraReady: Boolean = false,
    val currentCameraId: String = "",
    val hasRawSupport: Boolean = false,
    val maxZoomRatio: Float = 8f,
    val minFocusDistance: Float = 0f,

    // UI panels
    val showManualControls: Boolean = false,
    val showHistogram: Boolean = true,
    val showLevelIndicator: Boolean = true,
    val showFocusPeaking: Boolean = false,
    val showZebra: Boolean = false,
    val gridMode: GridMode = GridMode.THIRDS,

    // Capture state
    val isCapturing: Boolean = false,
    val timerSeconds: Int = 0,           // 0 = off, 3, 10
    val timerCountdown: Int = 0,

    // Messages
    val errorMessage: String? = null,
    val savedMessage: String? = null,

    // Sensor data
    val pitch: Float = 0f,
    val roll: Float = 0f,

    // Analysis
    val histogramData: HistogramData = HistogramData(),
    val audioLevel: Float = 0f,

    // Settings
    val settings: CameraSettings = CameraSettings()
)
