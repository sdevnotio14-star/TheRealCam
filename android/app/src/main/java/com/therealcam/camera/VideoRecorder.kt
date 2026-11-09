package com.therealcam.camera

import android.content.Context
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Range
import android.view.Surface
import java.io.File
import java.io.FileDescriptor
import java.io.IOException

/** Outputs of the VIDEO capture session; the recorder surface joins the repeating request while [recording]. */
class VideoOutput(val recorder: Surface, val spec: VideoSpec) {
    @Volatile var recording = false

    /** TEMPLATE_RECORD extras on top of the regular 3A and zoom of [CameraController]. */
    fun applyTo(b: CaptureRequest.Builder, c: CameraCaps) {
        if (recording) b.addTarget(recorder)
        spec.aeFps?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(it.first, it.second)) }
        b.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (spec.stabilization) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        )
        if (b.get(CaptureRequest.CONTROL_AF_MODE) == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE &&
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in c.afModes
        ) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
    }
}

/**
 * MediaRecorder fed through a persistent input surface (as in Google's Camera2Video sample): the
 * surface is part of the VIDEO session from the start, so recording begins without a new session.
 * Not thread-safe; the caller serializes calls.
 */
class VideoRecorder(private val context: Context) {
    var surface: Surface? = null
        private set
    private var surfaceKey: Pair<Dim, Int>? = null
    private var recorder: MediaRecorder? = null
    private var startedRt = 0L

    /** MediaRecorder error during recording (delivered on the main looper). */
    var onError: ((String) -> Unit)? = null

    fun hasSurfaceFor(spec: VideoSpec) = surface != null && surfaceKey == (spec.size to spec.fps)

    /** Persistent surface for [spec]; a dummy prepare() makes the encoder size its buffers before the session uses it. */
    fun prepareSurface(spec: VideoSpec): Surface {
        surface?.let { if (hasSurfaceFor(spec)) return it }
        releaseSurface()
        val s = MediaCodec.createPersistentInputSurface()
        val probe = File(context.cacheDir, "video_probe.mp4")
        try {
            val r = newRecorder()
            try {
                r.setUp(spec, s, audioChannels = 0, orientation = 0) { setOutputFile(probe.absolutePath) }
                r.prepare()
            } finally {
                r.release()
            }
        } catch (t: Throwable) {
            s.release()
            throw t
        } finally {
            probe.delete()
        }
        surface = s
        surfaceKey = spec.size to spec.fps
        return s
    }

    /** Starts recording into [fd]; returns whether sound is recorded (stereo, mono, then video only). */
    fun start(spec: VideoSpec, audio: Boolean, orientation: Int, fd: FileDescriptor): Boolean {
        check(recorder == null) { "already recording" }
        val input = surface ?: throw IOException("видеопоток не готов")
        var last: Throwable? = null
        for (channels in if (audio) listOf(2, 1, 0) else listOf(0)) {
            val r = newRecorder()
            try {
                // A failed attempt may already have written a header.
                runCatching { Os.ftruncate(fd, 0) }
                runCatching { Os.lseek(fd, 0, OsConstants.SEEK_SET) }
                r.setUp(spec, input, channels, orientation) { setOutputFile(fd) }
                r.setOnErrorListener { _, what, extra -> onError?.invoke("$what/$extra") }
                r.prepare()
                r.start()
                recorder = r
                startedRt = SystemClock.elapsedRealtime()
                return channels > 0
            } catch (t: Throwable) {
                last = t
                runCatching { r.reset() }
                runCatching { r.release() }
            }
        }
        throw last ?: IOException("MediaRecorder")
    }

    /** Stops recording and returns its duration in ms; throws when MediaRecorder got no usable data. */
    fun stop(): Long {
        val r = recorder ?: throw IOException("запись не идёт")
        recorder = null
        val ms = SystemClock.elapsedRealtime() - startedRt
        try {
            r.stop()
        } finally {
            runCatching { r.reset() }
            runCatching { r.release() }
        }
        return ms
    }

    /** Drops a recording that could not be completed. */
    fun abort() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        runCatching { r.reset() }
        runCatching { r.release() }
    }

    fun releaseSurface() {
        runCatching { surface?.release() }
        surface = null
        surfaceKey = null
    }

    fun release() {
        abort()
        releaseSurface()
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()

    private fun MediaRecorder.setUp(
        spec: VideoSpec,
        input: Surface,
        audioChannels: Int,
        orientation: Int,
        output: MediaRecorder.() -> Unit,
    ) {
        if (audioChannels > 0) setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        output()
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        setVideoEncodingBitRate(spec.bitRate)
        setVideoFrameRate(spec.fps)
        setVideoSize(spec.size.width, spec.size.height)
        if (audioChannels > 0) {
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(48_000)
            setAudioEncodingBitRate(128_000)
            setAudioChannels(audioChannels)
        }
        setInputSurface(input)
        setOrientationHint(orientation)
    }
}
