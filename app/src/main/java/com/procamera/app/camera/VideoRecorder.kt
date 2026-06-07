package com.procamera.app.camera

import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
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
    private var currentVideoUri: Uri? = null
    private var currentVideoPfd: ParcelFileDescriptor? = null
    private var currentDisplayName: String? = null

    data class VideoSaveResult(
        val uri: Uri?,
        val file: File?,
        val displayName: String
    )

    var onVideoSaved: ((VideoSaveResult) -> Unit)? = null

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

        currentDisplayName = createVideoDisplayName()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            currentVideoUri = createVideoUri(currentDisplayName!!)
            currentVideoPfd = context.contentResolver.openFileDescriptor(currentVideoUri!!, "w")
                ?: throw IllegalStateException("Unable to open video output descriptor")
        } else {
            currentFile = createVideoFile()
        }

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setOutputFile(currentVideoPfd!!.fileDescriptor)
            } else {
                setOutputFile(currentFile!!.absolutePath)
            }
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

    fun stop(): VideoSaveResult? {
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

            // Give the system a brief moment to finalize the file
            Thread.sleep(100)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                currentVideoUri?.let { uri ->
                    currentVideoPfd?.close()
                    currentVideoPfd = null
                    finalizeVideoUri(uri)
                    val name = currentDisplayName ?: uri.lastPathSegment.orEmpty()
                    Log.d(TAG, "Video saved to MediaStore: $uri")
                    val result = VideoSaveResult(uri, null, name)
                    onVideoSaved?.invoke(result)
                    result
                }
            } else {
                currentFile?.let { file ->
                    if (file.exists() && file.length() > 1000) {
                        Log.d(TAG, "Video file created: ${file.absolutePath} (${file.length()} bytes)")
                        scanVideoFile(file)
                        val result = VideoSaveResult(null, file, file.name)
                        onVideoSaved?.invoke(result)
                        result
                    } else {
                        Log.e(TAG, "Video file not created or empty: ${file.absolutePath} (size: ${file.length()})")
                        null
                    }
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

    private fun createVideoUri(displayName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ProCamera")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Unable to create MediaStore entry for video")
    }

    private fun finalizeVideoUri(videoUri: Uri) {
        val updateValues = ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
        }
        context.contentResolver.update(videoUri, updateValues, null, null)
    }

    private fun scanVideoFile(videoFile: File) {
        MediaScannerConnection.scanFile(
            context,
            arrayOf(videoFile.absolutePath),
            arrayOf("video/mp4"),
            null
        )
    }

    private fun createVideoFile(): File {
        val publicMovies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val dir = File(publicMovies, "ProCamera").apply {
            if (!exists() && !mkdirs()) {
                Log.w(TAG, "Failed to create directory: ${absolutePath}")
            }
        }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "VID_$ts.mp4")
        Log.d(TAG, "Video file path: ${file.absolutePath}")
        return file
    }

    private fun createVideoDisplayName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "VID_$ts.mp4"
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
}
