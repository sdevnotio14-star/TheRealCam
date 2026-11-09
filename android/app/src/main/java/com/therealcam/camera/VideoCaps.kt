package com.therealcam.camera

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Size

/** Reads [VideoCapsInfo] from CameraCharacteristics and the device's H.264 encoders. */
object VideoCapsReader {
    fun read(manager: CameraManager, id: String): VideoCapsInfo {
        val ch = manager.getCameraCharacteristics(id)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: error("Camera $id has no stream configuration")
        val rec = map.getOutputSizes(MediaRecorder::class.java)?.map { Dim(it.width, it.height) }.orEmpty()
        val prev = map.getOutputSizes(SurfaceTexture::class.java)?.map { Dim(it.width, it.height) }.orEmpty()
        val recMin = rec.associateWith {
            runCatching { map.getOutputMinFrameDuration(MediaRecorder::class.java, Size(it.width, it.height)) }.getOrDefault(0L)
        }
        val prevMin = prev.associateWith {
            runCatching { map.getOutputMinFrameDuration(SurfaceTexture::class.java, Size(it.width, it.height)) }.getOrDefault(0L)
        }
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.map { it.lower to it.upper }.orEmpty()
        val stab = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
        return VideoCapsInfo(rec, prev, ranges, recMin, prevMin, stab, AvcEncoders::supports)
    }
}

/** H.264 encoders of the device (hardware and software). */
object AvcEncoders {
    private val video: List<MediaCodecInfo.VideoCapabilities> by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } }
                .mapNotNull { runCatching { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    /** An empty encoder list counts as "supported": MediaRecorder reports a real failure anyway. */
    fun supports(d: Dim, fps: Int): Boolean = video.isEmpty() || video.any {
        runCatching { it.areSizeAndRateSupported(d.width, d.height, fps.toDouble()) }.getOrDefault(false)
    }
}
