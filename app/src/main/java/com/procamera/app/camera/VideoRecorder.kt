package com.procamera.app.camera

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
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
                stop()
                reset()
            }
            currentFile?.also { onVideoSaved?.invoke(it) }
        } catch (e: RuntimeException) {
            Log.e(TAG, "stop error (recording too short?)", e)
            currentFile?.delete()
            null
        } finally {
            release()
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
        val dir = File(
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES),
            "ProCamera"
        ).apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "VID_$ts.mp4")
    }
}
