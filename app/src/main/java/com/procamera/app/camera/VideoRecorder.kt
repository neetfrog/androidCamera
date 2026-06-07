package com.procamera.app.camera

import android.content.Context
import android.content.ContentValues
import android.media.MediaRecorder
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import com.procamera.app.data.VideoResolution
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class VideoRecorder(private val context: Context) {

    companion object {
        private const val TAG = "VideoRecorder"
    }

    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null

    var onVideoSaved: ((File) -> Unit)? = null

    /**
     * Prepare the MediaRecorder and return its recording Surface.
     * Must be called before [start].
     */
    fun prepare(
        resolution: VideoResolution = VideoResolution.FHD_1080P,
        frameRate: Int = 30,
        audioEnabled: Boolean = true
    ): Surface {
        release()

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        currentFile = createVideoFile()

        recorder.apply {
            if (audioEnabled) {
                setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

            if (audioEnabled) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(320_000)
                setAudioSamplingRate(48_000)
                setAudioChannels(2)
            }

            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            val bitrate = when {
                resolution.width >= 3840 -> 80_000_000   // 80 Mbps for 4K
                resolution.width >= 1920 -> 30_000_000   // 30 Mbps for 1080p
                else                     -> 10_000_000   // 10 Mbps for 720p
            }
            setVideoEncodingBitRate(bitrate)
            setVideoFrameRate(frameRate)
            setVideoSize(resolution.width, resolution.height)
            setOutputFile(currentFile!!.absolutePath)
            prepare()
        }

        mediaRecorder = recorder
        return recorder.surface
    }

    fun start() {
        try {
            mediaRecorder?.start()
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
        }
    }

    fun stop(): File? {
        return try {
            mediaRecorder?.apply {
                try {
                    stop()
                } catch (e: Exception) {
                    Log.e(TAG, "MediaRecorder.stop() failed: ${e.message}", e)
                    throw e
                }
                reset()
            }
            
            currentFile?.also { file ->
                // Give the system a brief moment to finalize the file
                Thread.sleep(100)
                
                // Verify file exists and has content
                if (file.exists() && file.length() > 1000) {  // At least 1KB
                    Log.d(TAG, "Video file created: ${file.absolutePath} (${file.length()} bytes)")
                    // Register with MediaStore so it appears in Gallery
                    registerVideoWithMediaStore(file)
                    onVideoSaved?.invoke(file)
                    file
                } else {
                    Log.e(TAG, "Video file not created or empty: ${file.absolutePath} (size: ${file.length()})")
                    null
                }
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "stop error (recording too short?)", e)
            currentFile?.delete()
            null
        } finally {
            release()
        }
    }

    private fun registerVideoWithMediaStore(videoFile: File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.TITLE, videoFile.nameWithoutExtension)
                put(MediaStore.Video.Media.DISPLAY_NAME, videoFile.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ProCamera")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                } else {
                    put(MediaStore.Video.Media.DATA, videoFile.absolutePath)
                }
            }

            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, updateValues, null, null)
            }
            Log.d(TAG, "Video registered: ${videoFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register video: ${e.message}")
        }
    }

    /**
     * Returns amplitude (0..32768) for VU meter calculation.
     */
    fun getMaxAmplitude(): Int = try {
        mediaRecorder?.maxAmplitude ?: 0
    } catch (_: Exception) { 0 }

    fun release() {
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
    }

    private fun createVideoFile(): File {
        val baseDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
            ?: throw IllegalStateException("Cannot access external movies directory")
        val dir = File(baseDir, "ProCamera").apply { 
            if (!exists()) {
                if (!mkdirs()) {
                    Log.w(TAG, "Failed to create directory: ${absolutePath}")
                }
            }
        }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "VID_$ts.mp4")
        Log.d(TAG, "Video file path: ${file.absolutePath}")
        return file
    }
}
