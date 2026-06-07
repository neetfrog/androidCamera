package com.procamera.app.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.view.Surface
import android.view.WindowManager
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
    private var pendingSurfaceSize: Pair<Int, Int>? = null
    private var currentSurfaceTexture: SurfaceTexture? = null
    private var currentSurfaceSize: Pair<Int, Int>? = null

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
            _uiState.update { it.copy(isCapturing = false, savedMessage = "RAW: ${file.name}") }
            viewModelScope.launch { delay(2500); _uiState.update { it.copy(savedMessage = null) } }
        }
        camera2.onError = { msg ->
            _uiState.update { it.copy(errorMessage = msg, isCapturing = false, isRecording = false) }
            viewModelScope.launch { delay(3000); _uiState.update { it.copy(errorMessage = null) } }
        }
        camera2.onHistogramUpdate = { data ->
            _uiState.update { it.copy(histogramData = data) }
        }
        videoRecorder.onVideoSaved = { result ->
            _uiState.update { it.copy(savedMessage = "Video: ${result.displayName}") }
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
                val cameraOptions = camera2.getAvailableCameraOptions()
                if (cameraOptions.isEmpty()) throw IllegalStateException("No cameras found")

                _uiState.update { it.copy(cameraOptions = cameraOptions) }

                val cameraId = (if (isFront) camera2.getFrontCameraId() else camera2.getBackCameraId())
                    ?: cameraOptions.first().id

                openCameraInternal(cameraId)
            } catch (e: Exception) {
                postError("Failed to open camera: ${e.message}")
            }
        }
    }

    private suspend fun openCameraInternal(cameraId: String) {
        camera2.closeCamera()
        camera2.openCamera(cameraId)

        val caps = camera2.getCameraCapabilities(cameraId)
        val isFront = camera2.getCurrentLensFacing() == CameraCharacteristics.LENS_FACING_FRONT

        _uiState.update {
            it.copy(
                currentCameraId = cameraId,
                isFrontCamera = isFront,
                hasRawSupport = caps.hasRawSupport,
                maxZoomRatio = caps.maxZoomRatio,
                minZoomRatio = caps.minZoomRatio,
                minFocusDistance = caps.minFocusDistance
            )
        }

        val surfaceTexture = currentSurfaceTexture ?: pendingSurface
        val surfaceSize = currentSurfaceSize ?: pendingSurfaceSize
        if (surfaceTexture != null && surfaceSize != null) {
            withContext(Dispatchers.Main) {
                startPreview(surfaceTexture, surfaceSize.first, surfaceSize.second)
            }
            pendingSurface = null
            pendingSurfaceSize = null
        }
    }

    /** Called when the TextureView surface is ready. */
    fun startPreview(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        currentSurfaceTexture = surfaceTexture
        currentSurfaceSize = Pair(width, height)

        if (!camera2.isCameraOpen) {
            pendingSurface = surfaceTexture
            pendingSurfaceSize = Pair(width, height)
            return
        }

        val previewSize = camera2.choosePreviewSize(width, height)
        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val aspectRatio = if (previewSize.width >= previewSize.height)
            previewSize.height.toFloat() / previewSize.width
        else
            previewSize.width.toFloat() / previewSize.height

        _uiState.update { it.copy(previewAspectRatio = aspectRatio) }

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

    fun executeCapture() {
        val state = _uiState.value
        _uiState.update { it.copy(isCapturing = true) }
        val captureRaw = state.captureMode == CaptureMode.RAW
        camera2.capturePhoto(state.settings, captureRaw)
    }

    // ─── Video recording ──────────────────────────────────────────────────────

    private fun getDisplayRotationDegrees(): Int {
        val wm = getApplication<Application>().getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return when (wm.defaultDisplay.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun getVideoOrientationHint(): Int {
        val sensorOrientation = camera2.getCurrentSensorOrientation() ?: 90
        val lensFacing = camera2.getCurrentLensFacing() ?: CameraCharacteristics.LENS_FACING_BACK
        val rotation = getDisplayRotationDegrees()

        return if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + rotation) % 360
        } else {
            (sensorOrientation - rotation + 360) % 360
        }
    }

    fun startVideoRecording() {
        if (_uiState.value.isRecording) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = _uiState.value
                val recSurf = videoRecorder.prepare(
                    resolution = state.settings.videoResolution,
                    frameRate = state.settings.frameRate,
                    audioEnabled = true,
                    orientationHint = getVideoOrientationHint()
                )
                recordingSurface = recSurf

                val preview = camera2.previewSurface
                    ?: throw IllegalStateException("No preview surface")

                camera2.createVideoSession(preview, recSurf, state.settings)
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
        val options = _uiState.value.cameraOptions
        if (options.isEmpty()) return

        val currentId = _uiState.value.currentCameraId
        val nextIndex = options.indexOfFirst { it.id == currentId }.let {
            if (it < 0 || it >= options.lastIndex) 0 else it + 1
        }
        selectCamera(options[nextIndex].id)
    }

    fun selectCamera(cameraId: String) {
        if (_uiState.value.isRecording) return
        orientationSensor.stop()
        _uiState.update { it.copy(isCameraReady = false) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                openCameraInternal(cameraId)
            } catch (e: Exception) {
                postError("Failed to switch camera: ${e.message}")
            }
        }
    }

    // ─── Settings mutations ───────────────────────────────────────────────────

    fun updateSetting(update: (CameraSettings) -> CameraSettings) {
        val newSettings = update(_uiState.value.settings)
        _uiState.update { it.copy(settings = newSettings) }
        camera2.updateSettings(newSettings)
    }

    fun setZoom(ratio: Float) {
        val state = _uiState.value
        val zoom = ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        updateSetting { it.copy(zoomRatio = zoom) }
    }

    fun focusAtPoint(x: Float, y: Float, width: Int, height: Int) {
        val state = _uiState.value
        val settings = state.settings
        if (!camera2.isCameraOpen || settings.isAutoFocus) return

        val maxFocus = state.minFocusDistance.coerceAtLeast(0.1f)
        val normalized = (y / height).coerceIn(0f, 1f)
        val focusDistance = (normalized * maxFocus).coerceIn(0f, maxFocus)
        val newSettings = settings.copy(focusDistance = focusDistance)

        _uiState.update { it.copy(settings = newSettings) }
        camera2.updateSettings(newSettings)

        viewModelScope.launch(Dispatchers.IO) {
            camera2.focusAtPoint(x, y, width, height, newSettings)
        }
    }

    fun setCaptureMode(mode: CaptureMode) {
        _uiState.update { it.copy(captureMode = mode) }
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

    fun toggleAutoExposure() {
        updateSetting { it.copy(isAutoExposure = !it.isAutoExposure) }
    }

    fun toggleAutoWhiteBalance() {
        updateSetting { it.copy(isAutoWhiteBalance = !it.isAutoWhiteBalance) }
    }

    fun toggleAutoFocus() {
        updateSetting { it.copy(isAutoFocus = !it.isAutoFocus) }
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

    fun openSavedFilesFolder(context: android.content.Context) {
        viewModelScope.launch {
            try {
                // Open the saved videos in the gallery/MediaStore
                val mediaIntent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                ).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(mediaIntent)
            } catch (e: Exception) {
                postError("Could not open gallery: ${e.message}")
            }
        }
    }

    fun toggleManualControls() = _uiState.update { it.copy(showManualControls = !it.showManualControls) }
    fun toggleHistogram()      = _uiState.update { it.copy(showHistogram = !it.showHistogram) }
    fun toggleLevelIndicator() = _uiState.update { it.copy(showLevelIndicator = !it.showLevelIndicator) }
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
