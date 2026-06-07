package com.procamera.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
//import android.media.DngCreator
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
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
import kotlin.math.log10

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

    fun getCameraCapabilities(cameraId: String): CameraCapabilities {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val hasRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
                && streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR) != null
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        return CameraCapabilities(hasRaw, maxZoom, minFocus)
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
                    val h = computeHistogram(img)
                    img.close()
                    onHistogramUpdate?.invoke(h)
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
        try {
            val req = buildPreviewRequest(settings)
            session.setRepeatingRequest(req, previewCaptureCallback, cameraHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "updateSettings failed", e)
        }
    }

    private fun buildPreviewRequest(settings: CameraSettings): CaptureRequest {
        val device = cameraDevice!!
        val surface = previewSurface!!
        return device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            analysisImageReader?.surface?.let { addTarget(it) }
            applySettings(this, settings, isVideo = false)
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
            if (settings.isAutoExposure) CameraMetadata.NOISE_REDUCTION_MODE_FAST
            else CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
        )
    }

    private fun applyZoom(
        builder: CaptureRequest.Builder,
        zoomRatio: Float,
        chars: CameraCharacteristics
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        } else {
            val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            val zoom = zoomRatio.coerceIn(1f, maxZoom)
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
        val jpeg = jpegImageReader?.surface ?: return

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jpeg)
                if (captureRaw) rawImageReader?.surface?.let { addTarget(it) }
                applySettings(this, settings, isVideo = false)
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, 90) // adjusted for portrait
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            }
            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
                ) {
                    pendingCaptureResult = result
                }
            }, cameraHandler)
        } catch (e: CameraAccessException) {
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
            val file = createOutputFile("IMG", "jpg", android.os.Environment.DIRECTORY_DCIM)
            file.writeBytes(bytes)
            onPhotoSaved?.invoke(file)
        } catch (e: Exception) {
            Log.e(TAG, "saveJpeg failed", e)
        } finally {
            image.close()
        }
    }

    // ─── Save RAW / DNG ───────────────────────────────────────────────────────

    private fun saveRaw(image: Image, result: TotalCaptureResult) {
        try {
            // DNG support not available - skipping RAW save
            Log.d(TAG, "DNG format not available, skipping RAW save")
            image.close()
        } catch (e: Exception) {
            Log.e(TAG, "saveRaw failed", e)
        }
    }

    // ─── Histogram computation (YUV_420_888) ──────────────────────────────────

    private fun computeHistogram(image: Image): HistogramData {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yArr = ByteArray(yPlane.buffer.remaining()).also { yPlane.buffer.get(it) }
        val uArr = ByteArray(uPlane.buffer.remaining()).also { uPlane.buffer.get(it) }
        val vArr = ByteArray(vPlane.buffer.remaining()).also { vPlane.buffer.get(it) }

        val bins = 64
        val rBin = IntArray(bins); val gBin = IntArray(bins)
        val bBin = IntArray(bins); val lBin = IntArray(bins)
        val w = image.width; val h = image.height
        val yStride = yPlane.rowStride
        val uvStride = uPlane.rowStride
        val uvPixel = uPlane.pixelStride

        for (row in 0 until h step 4) {
            for (col in 0 until w step 4) {
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
        val dir = File(context.getExternalFilesDir(envDir), "ProCamera").apply { mkdirs() }
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
    val minFocusDistance: Float
)
