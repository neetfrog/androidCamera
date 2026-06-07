package com.procamera.app.viewmodel

import android.app.Application
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.procamera.app.camera.Camera2Controller
import com.procamera.app.camera.OrientationSensor
import com.procamera.app.camera.VideoRecorder
import com.procamera.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val camera2 = Camera2Controller(application)
    private val videoRecorder = VideoRecorder(application)
    private val orientationSensor = OrientationSensor(application)

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    // Track whether surface arrived before camera was ready
    private var pendingSurface: SurfaceTexture? = null

    private var recordingDurationJob: Job? = null
    private var timerJob: Job? = null
    private var recordingSurface: Surface? = null

    // ─── Init ─────────────────────────────────────────────────────────────────

    init {
        camera2.onPhotoSaved = { file ->
            _uiState.update { it.copy(isCapturing = false, savedMessage = "Saved: ${file.name}") }
            viewModelScope.launch { delay(2500); _uiState.update { it.copy(savedMessage = null) } }
        }
        camera2.onRawSaved = { file ->
            _uiState.update { it.copy(savedMessage = "RAW: ${file.name}") }
            viewModelScope.launch { delay(2500); _uiState.update { it.copy(savedMessage = null) } }
        }
        camera2.onError = { msg ->
            _uiState.update { it.copy(errorMessage = msg, isCapturing = false, isRecording = false) }
            viewModelScope.launch { delay(3000); _uiState.update { it.copy(errorMessage = null) } }
        }
        camera2.onHistogramUpdate = { data ->
            _uiState.update { it.copy(histogramData = data) }
        }
        videoRecorder.onVideoSaved = { file ->
            _uiState.update { it.copy(savedMessage = "Video: ${file.name}") }
            viewModelScope.launch { delay(3000); _uiState.update { it.copy(savedMessage = null) } }
        }
        orientationSensor.onOrientationChanged = { pitch, roll ->
            _uiState.update { it.copy(pitch = pitch, roll = roll) }
        }
    }

    // ─── Camera lifecycle ─────────────────────────────────────────────────────

    fun initCamera(isFront: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cameraId = if (isFront) camera2.getFrontCameraId() else camera2.getBackCameraId()
                    ?: throw IllegalStateException("No ${if (isFront) "front" else "back"} camera found")

                camera2.closeCamera()
                camera2.openCamera(cameraId)

                val caps = camera2.getCameraCapabilities(cameraId)
                _uiState.update {
                    it.copy(
                        currentCameraId = cameraId,
                        isFrontCamera = isFront,
                        hasRawSupport = caps.hasRawSupport,
                        maxZoomRatio = caps.maxZoomRatio,
                        minFocusDistance = caps.minFocusDistance
                    )
                }
                // If surface arrived before camera was ready, start preview now
                pendingSurface?.let { st ->
                    val surf = Surface(st)
                    camera2.createPreviewSession(surf, _uiState.value.settings)
                    _uiState.update { it.copy(isCameraReady = true) }
                    orientationSensor.start()
                    pendingSurface = null
                }
            } catch (e: Exception) {
                postError("Failed to open camera: ${e.message}")
            }
        }
    }

    /** Called when the TextureView surface is ready. */
    fun startPreview(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        if (!camera2.isCameraOpen) {
            pendingSurface = surfaceTexture
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val surf = Surface(surfaceTexture)
                camera2.createPreviewSession(surf, _uiState.value.settings)
                _uiState.update { it.copy(isCameraReady = true) }
                orientationSensor.start()
            } catch (e: Exception) {
                postError("Preview failed: ${e.message}")
            }
        }
    }

    // ─── Photo capture ────────────────────────────────────────────────────────

    fun capturePhoto() {
        val state = _uiState.value
        if (state.isCapturing || state.isRecording) return
        if (state.timerSeconds > 0) {
            startCountdownAndCapture(state.timerSeconds)
        } else {
            executeCapture()
        }
    }

    private fun startCountdownAndCapture(seconds: Int) {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            for (i in seconds downTo 1) {
                _uiState.update { it.copy(timerCountdown = i) }
                delay(1000)
            }
            _uiState.update { it.copy(timerCountdown = 0) }
            executeCapture()
        }
    }

    private fun executeCapture() {
        val state = _uiState.value
        _uiState.update { it.copy(isCapturing = true) }
        val captureRaw = state.captureMode == CaptureMode.RAW
        camera2.capturePhoto(state.settings, captureRaw)
    }

    // ─── Video recording ──────────────────────────────────────────────────────

    fun startVideoRecording() {
        if (_uiState.value.isRecording) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = _uiState.value
                val isLog = state.captureMode == CaptureMode.LOG_VIDEO
                val isSlow = state.captureMode == CaptureMode.SLOW_MO
                val fps = when {
                    isSlow -> 120
                    else   -> state.settings.frameRate
                }
                val recSurf = videoRecorder.prepare(
                    resolution = state.settings.videoResolution,
                    frameRate = fps,
                    audioEnabled = true
                )
                recordingSurface = recSurf

                val preview = camera2.previewSurface
                    ?: throw IllegalStateException("No preview surface")

                val videoSettings = if (isLog) state.settings.copy(isLogColorSpace = true)
                else state.settings

                camera2.createVideoSession(preview, recSurf, videoSettings)
                videoRecorder.start()

                _uiState.update { it.copy(isRecording = true, recordingDurationSec = 0) }
                startDurationTimer()
            } catch (e: Exception) {
                postError("Recording failed: ${e.message}")
            }
        }
    }

    fun stopVideoRecording() {
        recordingDurationJob?.cancel()
        recordingDurationJob = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                videoRecorder.stop()
                val preview = camera2.previewSurface ?: return@launch
                camera2.restartPreviewSession(preview, _uiState.value.settings)
            } catch (_: Exception) {
            } finally {
                _uiState.update { it.copy(isRecording = false, recordingDurationSec = 0) }
                recordingSurface?.release()
                recordingSurface = null
            }
        }
    }

    private fun startDurationTimer() {
        recordingDurationJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val amplitude = videoRecorder.getMaxAmplitude()
                val level = (amplitude / 32768f).coerceIn(0f, 1f)
                _uiState.update {
                    it.copy(
                        recordingDurationSec = it.recordingDurationSec + 1,
                        audioLevel = level
                    )
                }
            }
        }
    }

    // ─── Camera switching ─────────────────────────────────────────────────────

    fun toggleCamera() {
        val isFront = _uiState.value.isFrontCamera
        orientationSensor.stop()
        camera2.closeCamera()
        _uiState.update { it.copy(isCameraReady = false) }
        initCamera(!isFront)
    }

    // ─── Settings mutations ───────────────────────────────────────────────────

    fun updateSetting(update: (CameraSettings) -> CameraSettings) {
        val newSettings = update(_uiState.value.settings)
        _uiState.update { it.copy(settings = newSettings) }
        camera2.updateSettings(newSettings)
    }

    fun setZoom(ratio: Float) {
        val max = _uiState.value.maxZoomRatio
        updateSetting { it.copy(zoomRatio = ratio.coerceIn(1f, max)) }
    }

    fun setCaptureMode(mode: CaptureMode) {
        val isLog = mode == CaptureMode.LOG_VIDEO
        _uiState.update { it.copy(captureMode = mode) }
        updateSetting { it.copy(isLogColorSpace = isLog) }
    }

    fun cycleFlash() {
        val next = when (_uiState.value.settings.flashMode) {
            FlashMode.OFF   -> FlashMode.AUTO
            FlashMode.AUTO  -> FlashMode.ON
            FlashMode.ON    -> FlashMode.TORCH
            FlashMode.TORCH -> FlashMode.OFF
        }
        updateSetting { it.copy(flashMode = next) }
    }

    fun cycleGrid() {
        val next = when (_uiState.value.gridMode) {
            GridMode.NONE   -> GridMode.THIRDS
            GridMode.THIRDS -> GridMode.SQUARE
            GridMode.SQUARE -> GridMode.GOLDEN
            GridMode.GOLDEN -> GridMode.NONE
        }
        _uiState.update { it.copy(gridMode = next) }
    }

    fun cycleTimer() {
        val next = when (_uiState.value.timerSeconds) {
            0  -> 3
            3  -> 10
            else -> 0
        }
        _uiState.update { it.copy(timerSeconds = next) }
    }

    fun toggleManualControls() = _uiState.update { it.copy(showManualControls = !it.showManualControls) }
    fun toggleHistogram()      = _uiState.update { it.copy(showHistogram = !it.showHistogram) }
    fun toggleLevelIndicator() = _uiState.update { it.copy(showLevelIndicator = !it.showLevelIndicator) }
    fun toggleFocusPeaking()   = _uiState.update { it.copy(showFocusPeaking = !it.showFocusPeaking) }
    fun toggleZebra()          = _uiState.update { it.copy(showZebra = !it.showZebra) }

    // ─── Stop camera ──────────────────────────────────────────────────────────

    fun stopCamera() {
        orientationSensor.stop()
        camera2.release()
        videoRecorder.release()
        _uiState.update { it.copy(isCameraReady = false, isRecording = false) }
    }

    override fun onCleared() {
        super.onCleared()
        stopCamera()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun postError(msg: String) {
        _uiState.update { it.copy(errorMessage = msg) }
        viewModelScope.launch { delay(3000); _uiState.update { it.copy(errorMessage = null) } }
    }
}
