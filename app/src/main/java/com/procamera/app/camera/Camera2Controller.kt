package com.procamera.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContentValues
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.hardware.camera2.DngCreator
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.graphics.SurfaceTexture
import android.util.Log
import android.util.Size
import android.view.Surface
import com.procamera.app.data.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.*

class Camera2Controller(private val context: Context) {

    companion object {
        private const val TAG = "Camera2Controller"
        private const val MAX_IMAGES = 3
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val cameraThread = HandlerThread("CameraHandlerThread").apply { start() }
    val cameraHandler = Handler(cameraThread.looper)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    @Volatile
    internal var previewSurface: Surface? = null
        private set

    @Volatile
    private var videoRecordingSurface: Surface? = null
    @Volatile
    private var isVideoCaptureSession: Boolean = false

    private var currentCameraId = ""
    private var cameraCharacteristics: CameraCharacteristics? = null

    private var jpegImageReader: ImageReader? = null
    private var rawImageReader: ImageReader? = null
    private var analysisImageReader: ImageReader? = null

    @Volatile
    private var pendingCaptureResult: TotalCaptureResult? = null

    val isCameraOpen: Boolean get() = cameraDevice != null

    // ─── Callbacks ────────────────────────────────────────────────────────────

    var onPhotoSaved: ((File) -> Unit)? = null
    var onRawSaved: ((File) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onHistogramUpdate: ((HistogramData) -> Unit)? = null

    // ─── Camera discovery ─────────────────────────────────────────────────────

    fun getBackCameraId(): String? = cameraManager.cameraIdList.firstOrNull { id ->
        cameraManager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    }

    fun getFrontCameraId(): String? = cameraManager.cameraIdList.firstOrNull { id ->
        cameraManager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
    }

    fun getAvailableCameraOptions(): List<CameraOption> = cameraManager.cameraIdList.mapNotNull { id ->
        try {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            val lensLabel = when (facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                CameraCharacteristics.LENS_FACING_BACK -> {
                    val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        ?.firstOrNull() ?: 0f
                    when {
                        focal > 0f && focal < 3.5f -> "Back (Ultra Wide)"
                        focal > 0f && focal < 5.2f -> "Back (Wide)"
                        focal > 0f -> "Back (Main)"
                        else -> "Back"
                    }
                }
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
                else -> "Camera"
            }
            CameraOption(id, "$lensLabel ($id)")
        } catch (e: Exception) {
            null
        }
    }

    fun getCameraCapabilities(cameraId: String): CameraCapabilities {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val hasRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
                && streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR) != null
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val minZoom = calculateMinZoomRatio(chars)
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        return CameraCapabilities(hasRaw, maxZoom, minZoom, minFocus)
    }

    private fun calculateMinZoomRatio(chars: CameraCharacteristics): Float {
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        if (focalLengths == null || focalLengths.size <= 1) return 1f
        val minFocal = focalLengths.minOrNull() ?: return 1f
        val maxFocal = focalLengths.maxOrNull() ?: return 1f
        return (minFocal / maxFocal).coerceAtMost(1f)
    }

    fun getCurrentSensorOrientation(): Int? = cameraCharacteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION)

    fun getCurrentLensFacing(): Int? = cameraCharacteristics?.get(CameraCharacteristics.LENS_FACING)

    fun choosePreviewSize(targetWidth: Int, targetHeight: Int): Size {
        val chars = cameraCharacteristics ?: return Size(max(targetWidth, 1920), max(targetHeight, 1080))
        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(max(targetWidth, 1920), max(targetHeight, 1080))

        val sizes = streamMap.getOutputSizes(SurfaceTexture::class.java)?.toList()
            ?: return Size(max(targetWidth, 1920), max(targetHeight, 1080))

        if (sizes.isEmpty()) {
            return Size(max(targetWidth, 1920), max(targetHeight, 1080))
        }

        val normalizedTargetRatio = if (targetWidth > 0 && targetHeight > 0)
            max(targetWidth, targetHeight).toFloat() / min(targetWidth, targetHeight)
        else
            4f / 3f

        return sizes.minByOrNull { size ->
            val ratio = max(size.width, size.height).toFloat() / min(size.width, size.height)
            abs(ratio - normalizedTargetRatio)
        } ?: sizes.first()
    }

    // ─── Open camera ──────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    suspend fun openCamera(cameraId: String): Unit = suspendCancellableCoroutine { cont ->
        currentCameraId = cameraId
        cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)

        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                if (cont.isActive) cont.resume(Unit)
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close(); cameraDevice = null
            }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close(); cameraDevice = null
                if (cont.isActive) cont.resumeWithException(
                    RuntimeException("Camera open error: $error")
                )
            }
        }
        try {
            cameraManager.openCamera(cameraId, callback, cameraHandler)
        } catch (e: CameraAccessException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
        cont.invokeOnCancellation { cameraDevice?.close() }
    }

    // ─── Preview session ──────────────────────────────────────────────────────

    suspend fun createPreviewSession(
        surface: Surface,
        settings: CameraSettings
    ): Unit = suspendCancellableCoroutine { cont ->
        val device = cameraDevice
            ?: run { cont.resumeWithException(IllegalStateException("Camera not open")); return@suspendCancellableCoroutine }
        val chars = cameraCharacteristics
            ?: run { cont.resumeWithException(IllegalStateException("No characteristics")); return@suspendCancellableCoroutine }
        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: run { cont.resumeWithException(IllegalStateException("No stream map")); return@suspendCancellableCoroutine }

        previewSurface = surface
        isVideoCaptureSession = false
        videoRecordingSurface = null
        val surfaces = mutableListOf(surface)

        // JPEG ImageReader (max resolution)
        val jpegSizes = streamMap.getOutputSizes(ImageFormat.JPEG)
        val jpegSize = jpegSizes?.maxByOrNull { it.width.toLong() * it.height } ?: Size(1920, 1080)
        jpegImageReader?.close()
        jpegImageReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, MAX_IMAGES).also {
            it.setOnImageAvailableListener({ reader ->
                reader.acquireLatestImage()?.let { img -> saveJpeg(img) }
            }, cameraHandler)
            surfaces.add(it.surface)
        }

        // RAW ImageReader (if supported)
        val rawSizes = streamMap.getOutputSizes(ImageFormat.RAW_SENSOR)
        if (!rawSizes.isNullOrEmpty()) {
            val rawSize = rawSizes.maxByOrNull { it.width.toLong() * it.height }!!
            rawImageReader?.close()
            rawImageReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, MAX_IMAGES).also {
                it.setOnImageAvailableListener({ reader ->
                    reader.acquireLatestImage()?.let { img ->
                        val result = pendingCaptureResult
                        if (result != null) saveRaw(img, result) else img.close()
                    }
                }, cameraHandler)
                surfaces.add(it.surface)
            }
        }

        // Analysis ImageReader (small YUV for histogram)
        analysisImageReader?.close()
        analysisImageReader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2).also {
            it.setOnImageAvailableListener({ reader ->
                reader.acquireLatestImage()?.let { img ->
                    try {
                        val yPlane = img.planes[0]
                        val uPlane = img.planes[1]
                        val vPlane = img.planes[2]
                        val yArr = ByteArray(yPlane.buffer.remaining()).also { yPlane.buffer.get(it) }
                        val uArr = ByteArray(uPlane.buffer.remaining()).also { uPlane.buffer.get(it) }
                        val vArr = ByteArray(vPlane.buffer.remaining()).also { vPlane.buffer.get(it) }
                        val yStride = yPlane.rowStride
                        val uvStride = uPlane.rowStride
                        val uvPixel = uPlane.pixelStride

                        val h = computeHistogram(img.width, img.height, yArr, uArr, vArr, yStride, uvStride, uvPixel)
                        onHistogramUpdate?.invoke(h)
                    } catch (_: Exception) {
                    } finally {
                        img.close()
                    }
                }
            }, cameraHandler)
            surfaces.add(it.surface)
        }

        val sessionCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                try {
                    val req = buildPreviewRequest(settings)
                    session.setRepeatingRequest(req, previewCaptureCallback, cameraHandler)
                    if (cont.isActive) cont.resume(Unit)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                if (cont.isActive) cont.resumeWithException(RuntimeException("Session config failed"))
            }
        }
        createSession(device, surfaces, sessionCallback)
    }

    // ─── Video session ────────────────────────────────────────────────────────

    suspend fun createVideoSession(
        previewSurf: Surface,
        recordingSurf: Surface,
        settings: CameraSettings
    ): Unit = suspendCancellableCoroutine { cont ->
        val device = cameraDevice
            ?: run { cont.resumeWithException(IllegalStateException("Camera not open")); return@suspendCancellableCoroutine }

        captureSession?.close()
        captureSession = null

        val surfaces = mutableListOf(previewSurf, recordingSurf)
        analysisImageReader?.surface?.let { surfaces.add(it) }

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                videoRecordingSurface = recordingSurf
                isVideoCaptureSession = true
                try {
                    val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(previewSurf)
                        addTarget(recordingSurf)
                        analysisImageReader?.surface?.let { addTarget(it) }
                        applySettings(this, settings, isVideo = true)
                    }.build()
                    session.setRepeatingRequest(req, previewCaptureCallback, cameraHandler)
                    if (cont.isActive) cont.resume(Unit)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                if (cont.isActive) cont.resumeWithException(RuntimeException("Video session failed"))
            }
        }
        createSession(device, surfaces, callback)
    }

    suspend fun restartPreviewSession(surface: Surface, settings: CameraSettings) {
        captureSession?.close()
        captureSession = null
        createPreviewSession(surface, settings)
    }

    private fun createSession(
        device: CameraDevice,
        surfaces: List<Surface>,
        callback: CameraCaptureSession.StateCallback
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val configs = surfaces.map { OutputConfiguration(it) }
                val config = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    configs,
                    { r -> cameraHandler.post(r) },
                    callback
                )
                device.createCaptureSession(config)
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(surfaces, callback, cameraHandler)
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "createSession failed", e)
        }
    }

    // ─── Capture callbacks ────────────────────────────────────────────────────

    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            pendingCaptureResult = result
        }
    }

    // ─── Settings application ─────────────────────────────────────────────────

    fun updateSettings(settings: CameraSettings) {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        try {
            val req = if (isVideoCaptureSession && previewSurface != null && videoRecordingSurface != null) {
                device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(previewSurface!!)
                    addTarget(videoRecordingSurface!!)
                    analysisImageReader?.surface?.let { addTarget(it) }
                    applySettings(this, settings, isVideo = true)
                }.build()
            } else {
                buildPreviewRequest(settings)
            }
            session.setRepeatingRequest(req, previewCaptureCallback, cameraHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "updateSettings failed", e)
        }
    }

    private fun buildPreviewRequest(settings: CameraSettings): CaptureRequest {
        val device = cameraDevice!!
        val surface = previewSurface!!
        val template = if (settings.isFlatVideoMode) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        return device.createCaptureRequest(template).apply {
            addTarget(surface)
            analysisImageReader?.surface?.let { addTarget(it) }
            applySettings(this, settings, isVideo = settings.isFlatVideoMode)
        }.build()
    }

    private fun applySettings(
        builder: CaptureRequest.Builder,
        settings: CameraSettings,
        isVideo: Boolean
    ) {
        val chars = cameraCharacteristics ?: return

        // ── Exposure ──────────────────────────────────────────────────────────
        if (settings.isAutoExposure) {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, settings.exposureCompensation)
        } else {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            // Clamp ISO to camera range
            val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val iso = if (isoRange != null)
                settings.iso.coerceIn(isoRange.lower, isoRange.upper)
            else settings.iso
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            // Clamp exposure time to camera range
            val exposureNanos = (1_000_000_000.0 / settings.shutterSpeed).toLong()
            val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val clampedExp = if (expRange != null)
                exposureNanos.coerceIn(expRange.lower, expRange.upper)
            else exposureNanos
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, clampedExp)
        }

        // ── White Balance ─────────────────────────────────────────────────────
        if (settings.isAutoWhiteBalance) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            val (r, g, b) = kelvinToGains(settings.whiteBalanceKelvin)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                android.hardware.camera2.params.RggbChannelVector(r, g, g, b)
            )
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        }

        // ── Focus ─────────────────────────────────────────────────────────────
        if (settings.isAutoFocus) {
            val afMode = if (isVideo)
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            else
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, settings.focusDistance)
        }

        // ── Tonemap / LOG profile ─────────────────────────────────────────────
        if (settings.isLogColorSpace) {
            try {
                // TONEMAP_MODE_CURVE = 2
                builder.set(CaptureRequest.TONEMAP_MODE, 2)
                builder.set(CaptureRequest.TONEMAP_CURVE, buildLogTonemapCurve())
            } catch (e: Exception) {
                Log.w(TAG, "Tonemap curve not supported: ${e.message}")
                builder.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_FAST)
            }
        } else {
            builder.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_FAST)
        }

        // ── Flat video / low processing mode ────────────────────────────────────
        if (isVideo && settings.isFlatVideoMode) {
            builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
            builder.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_OFF)
        }

        // ── Zoom ──────────────────────────────────────────────────────────────
        applyZoom(builder, settings.zoomRatio, chars)

        // ── Flash ─────────────────────────────────────────────────────────────
        applyFlash(builder, settings)

        // ── Video stabilization ───────────────────────────────────────────────
        if (isVideo) {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (settings.isStabilization) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
        }

        // ── Noise reduction ───────────────────────────────────────────────────
        builder.set(
            CaptureRequest.NOISE_REDUCTION_MODE,
            if (isVideo && settings.isFlatVideoMode) CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
            else if (settings.isAutoExposure) CameraMetadata.NOISE_REDUCTION_MODE_FAST
            else CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
        )
    }

    fun focusAtPoint(
        x: Float,
        y: Float,
        viewWidth: Int,
        viewHeight: Int,
        settings: CameraSettings
    ) {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val chars = cameraCharacteristics ?: return
        val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        val focusX = ((x / viewWidth) * sensorRect.width()).toInt().coerceIn(sensorRect.left, sensorRect.right)
        val focusY = ((y / viewHeight) * sensorRect.height()).toInt().coerceIn(sensorRect.top, sensorRect.bottom)
        val areaSize = (min(sensorRect.width(), sensorRect.height()) / 10).coerceAtLeast(50)
        val left = (focusX - areaSize / 2).coerceIn(sensorRect.left, sensorRect.right - areaSize)
        val top = (focusY - areaSize / 2).coerceIn(sensorRect.top, sensorRect.bottom - areaSize)
        val focusArea = MeteringRectangle(Rect(left, top, left + areaSize, top + areaSize), MeteringRectangle.METERING_WEIGHT_MAX)

        try {
            val req = device.createCaptureRequest(
                if (isVideoCaptureSession) CameraDevice.TEMPLATE_RECORD
                else CameraDevice.TEMPLATE_PREVIEW
            ).apply {
                previewSurface?.let { addTarget(it) }
                analysisImageReader?.surface?.let { addTarget(it) }

                if (settings.isAutoFocus) {
                    applySettings(this, settings, isVideo = isVideoCaptureSession)
                    set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusArea))
                    set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(focusArea))
                    set(CaptureRequest.CONTROL_AF_MODE,
                        if (isVideoCaptureSession) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                        else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    )
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                } else {
                    applySettings(this, settings, isVideo = isVideoCaptureSession)
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, settings.focusDistance)
                }
            }.build()

            session.capture(req, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    updateSettings(settings)
                }
            }, cameraHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "focusAtPoint failed", e)
        }
    }

    private fun applyZoom(
        builder: CaptureRequest.Builder,
        zoomRatio: Float,
        chars: CameraCharacteristics
    ) {
        val minZoom = calculateMinZoomRatio(chars)
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val zoom = zoomRatio.coerceIn(minZoom, maxZoom)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        } else {
            val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            if (zoom < 1f) {
                // Zoom values below 1× are not supported via crop region on older APIs.
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                return
            }
            val cropW = (sensor.width() / zoom).toInt()
            val cropH = (sensor.height() / zoom).toInt()
            val left = (sensor.width() - cropW) / 2
            val top = (sensor.height() - cropH) / 2
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + cropW, top + cropH))
        }
    }

    private fun applyFlash(builder: CaptureRequest.Builder, settings: CameraSettings) {
        when (settings.flashMode) {
            FlashMode.OFF -> builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            FlashMode.ON -> if (settings.isAutoExposure) builder.set(
                CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            )
            FlashMode.AUTO -> if (settings.isAutoExposure) builder.set(
                CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
            )
            FlashMode.TORCH -> builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
        }
    }

    // ─── Photo capture ────────────────────────────────────────────────────────

    fun capturePhoto(settings: CameraSettings, captureRaw: Boolean) {
        val session = captureSession ?: return
        val device = cameraDevice ?: return

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                // Always include preview surface to maintain camera session stability
                previewSurface?.let { addTarget(it) }
                
                // Mutually exclusive: RAW OR JPEG, not both
                if (captureRaw) {
                    if (rawImageReader?.surface != null) {
                        Log.d(TAG, "RAW mode: Adding RAW surface")
                        addTarget(rawImageReader!!.surface)
                    } else {
                        Log.w(TAG, "RAW mode selected but RAW surface not available")
                        return@apply
                    }
                } else {
                    val jpeg = jpegImageReader?.surface ?: return
                    Log.d(TAG, "JPEG mode: Adding JPEG surface")
                    addTarget(jpeg)
                    set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                    set(CaptureRequest.JPEG_ORIENTATION, 90)
                }
                
                applySettings(this, settings, isVideo = false)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            }
            
            Log.d(TAG, "capturePhoto: captureRaw=$captureRaw, pending capture result=${pendingCaptureResult != null}")
            
            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
                ) {
                    Log.d(TAG, "onCaptureCompleted: captureRaw=$captureRaw")
                    pendingCaptureResult = result
                }
            }, cameraHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Capture failed: ${e.message}", e)
            onError?.invoke("Capture failed: ${e.message}")
        }
    }

    // ─── LOG tonemap curve (S-Log3 inspired) ──────────────────────────────────

    private fun buildLogTonemapCurve(): TonemapCurve {
        val n = 64
        val ch = FloatArray(n * 2)
        for (i in 0 until n) {
            val x = i.toFloat() / (n - 1)
            val y = when {
                x < 0.014f -> 0.09f + x * 1.5f
                else -> (log10(x * 9.0 + 1.0) * 0.7 + 0.09).toFloat()
            }.coerceIn(0f, 1f)
            ch[i * 2] = x
            ch[i * 2 + 1] = y
        }
        return TonemapCurve(ch, ch, ch)
    }

    // ─── Color temperature ────────────────────────────────────────────────────

    private fun kelvinToGains(kelvin: Int): Triple<Float, Float, Float> {
        val t = kelvin / 100f
        val r = if (t <= 66f) 1.0f
        else ((329.7f * Math.pow((t - 60.0), -0.133)) / 255f).toFloat()
        val g = if (t <= 66f) ((99.47f * Math.log(t.toDouble()) - 161.12f) / 255f).toFloat()
        else ((288.12f * Math.pow((t - 60.0), -0.0755)) / 255f).toFloat()
        val b = when {
            t >= 66f -> 1.0f
            t <= 19f -> 0.1f
            else -> ((138.52f * Math.log((t - 10.0)) - 305.04f) / 255f).toFloat()
        }
        return Triple(r.coerceIn(0.1f, 2.5f), g.coerceIn(0.1f, 2.5f), b.coerceIn(0.1f, 2.5f))
    }

    // ─── Save JPEG ────────────────────────────────────────────────────────────

    private fun saveJpeg(image: Image) {
        try {
            val buf = image.planes[0].buffer
            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(Date())
            val fileName = "IMG_$timeStamp.jpg"

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/RawSnap")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: throw Exception("Failed to create MediaStore entry")

            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(bytes)
                stream.flush()
            } ?: throw Exception("Could not open output stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, updateValues, null, null)
            }

            Log.d(TAG, "Photo saved: $fileName")
            onPhotoSaved?.invoke(File(fileName))
        } catch (e: Exception) {
            Log.e(TAG, "saveJpeg failed: ${e.message}", e)
            onError?.invoke("Failed to save photo: ${e.message}")
        } finally {
            image.close()
        }
    }

    // ─── Save RAW / DNG ───────────────────────────────────────────────────────

    private fun saveRaw(image: Image, result: TotalCaptureResult) {
        try {
            if (image.format != ImageFormat.RAW_SENSOR) {
                throw Exception("Image format is ${image.format}, not RAW_SENSOR")
            }

            val chars = cameraCharacteristics ?: throw Exception("No camera characteristics")
            Log.d(TAG, "Saving RAW: ${image.width}x${image.height}, format=${image.format}")

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "RAW_$timestamp.dng"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/RawSnap")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                ) ?: throw Exception("Failed to create MediaStore entry")

                context.contentResolver.openOutputStream(uri)?.use { output ->
                    DngCreator(chars, result).use { dngCreator ->
                        dngCreator.writeImage(output, image)
                    }
                } ?: throw Exception("Could not open output stream")

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)

                onRawSaved?.invoke(File(fileName))
                Log.d(TAG, "DNG saved to MediaStore: $uri")
            } else {
                val publicPictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val folder = File(publicPictures, "RawSnap")
                if (!folder.exists() && !folder.mkdirs()) {
                    throw Exception("Failed to create directory: ${folder.absolutePath}")
                }
                val outputFile = File(folder, fileName)
                outputFile.outputStream().use { output ->
                    DngCreator(chars, result).use { dngCreator ->
                        dngCreator.writeImage(output, image)
                    }
                }
                onRawSaved?.invoke(outputFile)
                Log.d(TAG, "DNG saved to file: ${outputFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveRaw failed: ${e.message}", e)
            onError?.invoke("Failed to save RAW: ${e.message}")
        } finally {
            image.close()
        }
    }

    // ─── Histogram computation (YUV_420_888) ──────────────────────────────────

    private fun computeHistogram(
        width: Int,
        height: Int,
        yArr: ByteArray,
        uArr: ByteArray,
        vArr: ByteArray,
        yStride: Int,
        uvStride: Int,
        uvPixel: Int
    ): HistogramData {
        val bins = 64
        val rBin = IntArray(bins); val gBin = IntArray(bins)
        val bBin = IntArray(bins); val lBin = IntArray(bins)

        for (row in 0 until height step 4) {
            for (col in 0 until width step 4) {
                val yIdx = row * yStride + col
                if (yIdx >= yArr.size) continue
                val yv = yArr[yIdx].toInt() and 0xFF
                val uvIdx = (row / 2) * uvStride + (col / 2) * uvPixel
                val uv = if (uvIdx < uArr.size) (uArr[uvIdx].toInt() and 0xFF) - 128 else 0
                val vv = if (uvIdx < vArr.size) (vArr[uvIdx].toInt() and 0xFF) - 128 else 0
                val r = (yv + 1.402 * vv).toInt().coerceIn(0, 255)
                val g = (yv - 0.344 * uv - 0.714 * vv).toInt().coerceIn(0, 255)
                val b = (yv + 1.772 * uv).toInt().coerceIn(0, 255)
                rBin[r * bins / 256]++
                gBin[g * bins / 256]++
                bBin[b * bins / 256]++
                lBin[yv * bins / 256]++
            }
        }

        fun normalize(arr: IntArray): FloatArray {
            val max = (arr.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
            return FloatArray(arr.size) { arr[it] / max }
        }
        return HistogramData(normalize(rBin), normalize(gBin), normalize(bBin), normalize(lBin))
    }

    // ─── File creation ────────────────────────────────────────────────────────

    private fun createOutputFile(prefix: String, ext: String, envDir: String): File {
        val dir = File(context.getExternalFilesDir(envDir), "RawSnap").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(Date())
        return File(dir, "${prefix}_$ts.$ext")
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    fun closeCamera() {
        try {
            captureSession?.close(); captureSession = null
            cameraDevice?.close(); cameraDevice = null
            jpegImageReader?.close(); jpegImageReader = null
            rawImageReader?.close(); rawImageReader = null
            analysisImageReader?.close(); analysisImageReader = null
            previewSurface = null
            videoRecordingSurface = null
            isVideoCaptureSession = false
        } catch (e: Exception) {
            Log.e(TAG, "closeCamera error", e)
        }
    }

    fun release() {
        closeCamera()
        try { cameraThread.quitSafely() } catch (_: Exception) {}
    }
}

data class CameraCapabilities(
    val hasRawSupport: Boolean,
    val maxZoomRatio: Float,
    val minZoomRatio: Float,
    val minFocusDistance: Float
)
