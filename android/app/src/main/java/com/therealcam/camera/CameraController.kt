package com.therealcam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Thin Camera2 wrapper: preview on a SurfaceTexture plus sequential JPEG stills with per-frame
 * manual settings. All camera callbacks run on a dedicated handler thread.
 */
class CameraController(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camera").also { it.start() }
    private val handler = Handler(thread.looper)
    private val lock = Mutex()

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private val jpegs = Channel<Pair<Long, ByteArray>>(capacity = 8)

    @Volatile private var shot = ShotSettings()
    @Volatile private var region: MeteringRectangle? = null
    /** CONTROL_ZOOM_RATIO on API 30+ cameras that support it, otherwise a centred crop factor. */
    @Volatile private var zoomRatio = 1f
    /** Set while the session is the VIDEO one (preview + recorder surface, no JPEG reader). */
    @Volatile private var video: VideoOutput? = null

    /** Pro-mode overrides of the camera's processing; -1 keeps the camera default. */
    data class ProOptions(val noiseReduction: Int = -1, val edge: Int = -1, val ois: Int = -1)

    @Volatile var pro = ProOptions()

    private val _caps = MutableStateFlow<CameraCaps?>(null)
    /** Non-null while a session is running. */
    val caps: StateFlow<CameraCaps?> = _caps.asStateFlow()

    private val _preview = MutableStateFlow<PreviewSnapshot?>(null)
    val preview: StateFlow<PreviewSnapshot?> = _preview.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun cameraIds(): Pair<String?, String?> = CameraCaps.primaryIds(manager)

    fun readCaps(id: String): CameraCaps = CameraCaps.read(manager, id)

    suspend fun open(cameraId: String, texture: SurfaceTexture): CameraCaps = lock.withLock {
        closeLocked()
        _error.value = null
        val c = CameraCaps.read(manager, cameraId)
        texture.setDefaultBufferSize(c.previewSize.width, c.previewSize.height)
        val surface = Surface(texture)
        val r = ImageReader.newInstance(c.jpegSize.width, c.jpegSize.height, ImageFormat.JPEG, 4)
        r.setOnImageAvailableListener({ rd ->
            val img = runCatching { rd.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                jpegs.trySend(img.timestamp to bytes)
            } finally {
                img.close()
            }
        }, handler)
        try {
            val d = openDevice(cameraId)
            device = d
            reader = r
            previewSurface = surface
            session = createSession(d, listOf(surface, r.surface))
            shot = shot.copy(focus = if (c.canManualFocus) shot.focus else FocusMode.AUTO)
            region = null
            _caps.value = c
            startPreviewLocked(c)
            c
        } catch (t: Throwable) {
            _error.value = t.message ?: t.javaClass.simpleName
            closeLocked()
            r.close()
            surface.release()
            throw t
        }
    }

    /** VIDEO session: preview with the video aspect plus the recorder's persistent surface. */
    suspend fun openVideo(cameraId: String, texture: SurfaceTexture, out: VideoOutput): CameraCaps = lock.withLock {
        closeLocked()
        _error.value = null
        val c = CameraCaps.read(manager, cameraId)
        texture.setDefaultBufferSize(out.spec.preview.width, out.spec.preview.height)
        val surface = Surface(texture)
        try {
            val d = openDevice(cameraId)
            device = d
            previewSurface = surface
            video = out
            session = createSession(d, listOf(surface, out.recorder))
            shot = shot.copy(focus = if (c.canManualFocus) shot.focus else FocusMode.AUTO)
            region = null
            _caps.value = c
            startPreviewLocked(c)
            c
        } catch (t: Throwable) {
            _error.value = t.message ?: t.javaClass.simpleName
            closeLocked()
            surface.release()
            throw t
        }
    }

    /** VIDEO: adds or removes the recorder surface in the repeating request; false without a video session. */
    suspend fun setRecording(on: Boolean): Boolean = lock.withLock {
        val v = video ?: return@withLock false
        val c = _caps.value ?: return@withLock false
        v.recording = on
        startPreviewLocked(c)
        true
    }

    suspend fun close() = lock.withLock { closeLocked() }

    private fun closeLocked() {
        _caps.value = null
        video = null
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        runCatching { previewSurface?.release() }
        session = null
        device = null
        reader = null
        previewSurface = null
        while (jpegs.tryReceive().isSuccess) Unit
    }

    fun release() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        thread.quitSafely()
    }

    /** Apply new settings to the running preview. */
    fun setShot(s: ShotSettings) {
        shot = s
        val c = _caps.value ?: return
        runCatching { startPreviewLocked(c) }.onFailure { Log.w(TAG, "preview update failed", it) }
    }

    /**
     * Zoom ratio for the preview and the following stills; also used by the next [open].
     * [applyNow] = false only stores it, for a camera that is about to be reopened.
     */
    fun setZoomRatio(r: Float, applyNow: Boolean = true) {
        if (abs(r - zoomRatio) < 1e-3f) return
        zoomRatio = r
        // A tap region refers to the old field of view; go back to continuous focus.
        region = null
        if (!applyNow) return
        val c = _caps.value ?: return
        runCatching { startPreviewLocked(c) }.onFailure { Log.w(TAG, "zoom update failed", it) }
    }

    /** Tap to focus/meter at normalized view coordinates of the (portrait, un-cropped) preview. */
    fun focusAt(u: Float, v: Float) {
        val c = _caps.value ?: return
        val aa = c.activeArray ?: return
        val s = session ?: return
        if (c.maxAfRegions == 0 && c.maxAeRegions == 0) return
        val (sx, sy) = viewToSensor(u, v, c.sensorOrientation, c.isFront)
        // With CONTROL_ZOOM_RATIO regions are relative to the zoomed view; with a crop they are not.
        val crop = legacyCrop(c)
        val f = crop?.let { it.width().toFloat() / aa.width() } ?: 1f
        val half = (min(aa.width(), aa.height()) * 0.07f * f).roundToInt().coerceAtLeast(1)
        val cx = (crop?.left ?: 0) + (sx * aa.width() * f).roundToInt()
        val cy = (crop?.top ?: 0) + (sy * aa.height() * f).roundToInt()
        val rect = Rect(
            (cx - half).coerceIn(0, aa.width() - 1), (cy - half).coerceIn(0, aa.height() - 1),
            (cx + half).coerceIn(1, aa.width()), (cy + half).coerceIn(1, aa.height()),
        )
        region = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)
        runCatching {
            startPreviewLocked(c)
            if (shot.focus == FocusMode.AUTO && CameraMetadata.CONTROL_AF_MODE_AUTO in c.afModes) {
                val b = previewBuilder(c)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                s.capture(b.build(), null, handler)
            }
        }.onFailure { Log.w(TAG, "focusAt failed", it) }
    }

    fun resetFocus() {
        region = null
        _caps.value?.let { c -> runCatching { startPreviewLocked(c) } }
    }

    val hasFocusRegion get() = region != null

    suspend fun capture(req: StillRequest): CapturedFrame = lock.withLock {
        val c = _caps.value ?: throw IOException("Камера не готова")
        val s = session ?: throw IOException("Камера не готова")
        val d = device ?: throw IOException("Камера не готова")
        val r = reader ?: throw IOException("Камера не готова")
        while (jpegs.tryReceive().isSuccess) Unit

        val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        b.addTarget(r.surface)
        apply3A(b, req.settings, c, preview = false)
        b.set(CaptureRequest.JPEG_ORIENTATION, req.jpegOrientation)
        b.set(CaptureRequest.JPEG_QUALITY, req.jpegQuality.coerceIn(1, 100).toByte())
        req.location?.let { b.set(CaptureRequest.JPEG_GPS_LOCATION, it) }

        val done = CompletableDeferred<TotalCaptureResult>()
        var startRt = 0L
        val cb = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                startRt = if (c.timestampRealtime) timestamp
                else timestamp + (SystemClock.elapsedRealtimeNanos() - System.nanoTime())
            }

            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                done.complete(result)
            }

            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                done.completeExceptionally(IOException("Сбой съёмки (код ${failure.reason})"))
            }
        }
        s.capture(b.build(), cb, handler)
        val expectedNs = req.settings.exposureNs ?: 0L
        val timeout = expectedNs / 1_000_000L + 15_000L
        val result = withTimeout(timeout) { done.await() }
        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP)
        val (_, bytes) = withTimeout(10_000L) {
            var got = jpegs.receive()
            // Prefer the image that matches this result if a stale one slipped in.
            if (ts != null && got.first != ts) jpegs.tryReceive().getOrNull()?.let { got = it }
            got
        }
        val meta = readMetadata(result)
        if (startRt == 0L) startRt = SystemClock.elapsedRealtimeNanos() - (meta.exposureNs ?: 0L)
        CapturedFrame(bytes, meta, startRt, startRt + (meta.exposureNs ?: expectedNs))
    }

    // ---------------------------------------------------------------- internals

    private fun previewBuilder(c: CameraCaps): CaptureRequest.Builder {
        val d = device ?: throw IOException("no device")
        val v = video
        val b = d.createCaptureRequest(if (v != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(previewSurface ?: throw IOException("no surface"))
        apply3A(b, shot, c, preview = true)
        v?.applyTo(b, c)
        return b
    }

    private fun startPreviewLocked(c: CameraCaps) {
        val s = session ?: return
        s.setRepeatingRequest(previewBuilder(c).build(), previewCallback, handler)
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            _preview.value = PreviewSnapshot(
                exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                aperture = result.get(CaptureResult.LENS_APERTURE),
                focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                aeState = result.get(CaptureResult.CONTROL_AE_STATE),
                afState = result.get(CaptureResult.CONTROL_AF_STATE),
                timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L,
                zoomRatio = if (Build.VERSION.SDK_INT >= 30) result.get(CaptureResult.CONTROL_ZOOM_RATIO) else null,
                focalLengthMm = result.get(CaptureResult.LENS_FOCAL_LENGTH),
            )
        }
    }

    private fun apply3A(b: CaptureRequest.Builder, s: ShotSettings, c: CameraCaps, preview: Boolean) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        val iso = s.iso
        val exp = s.exposureNs
        if (iso != null && exp != null && c.manualSensor) {
            var e: Long = exp
            var i = iso.toDouble()
            if (preview && e > PREVIEW_MAX_EXPOSURE_NS) {
                // Keep the preview fluid: shorter exposure, same brightness through gain.
                i *= e.toDouble() / PREVIEW_MAX_EXPOSURE_NS
                e = PREVIEW_MAX_EXPOSURE_NS
            }
            c.exposureRange?.let { e = e.coerceIn(it.lower, it.upper) }
            var isoInt = i.roundToInt()
            c.isoRange?.let { isoInt = isoInt.coerceIn(it.lower, it.upper) }
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, e)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, isoInt)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(e, if (preview) 0L else c.jpegMinFrameNs))
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evSteps(s.ev, c))
            region?.let { if (c.maxAeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it)) }
        }
        b.set(CaptureRequest.FLASH_MODE, if (s.torch && c.flash) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)

        val af = c.afModes
        when {
            s.focus == FocusMode.INFINITY && c.canManualFocus -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
            }
            s.focus == FocusMode.MANUAL && c.canManualFocus -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, s.focusDiopters.coerceIn(0f, c.minFocusDistance))
            }
            region != null && CameraMetadata.CONTROL_AF_MODE_AUTO in af && c.maxAfRegions > 0 -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            }
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in af ->
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            CameraMetadata.CONTROL_AF_MODE_AUTO in af ->
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            else -> b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        }
        val awb = if (s.wb.awbMode in c.awbModes) s.wb.awbMode else CameraMetadata.CONTROL_AWB_MODE_AUTO
        b.set(CaptureRequest.CONTROL_AWB_MODE, awb)
        applyZoom(b, c)
        val p = pro
        if (!preview) {
            if (p.noiseReduction >= 0 && p.noiseReduction in c.noiseReductionModes) b.set(CaptureRequest.NOISE_REDUCTION_MODE, p.noiseReduction)
            if (p.edge >= 0 && p.edge in c.edgeModes) b.set(CaptureRequest.EDGE_MODE, p.edge)
        }
        if (p.ois >= 0 && p.ois in c.oisModes) b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, p.ois)
    }

    private fun applyZoom(b: CaptureRequest.Builder, c: CameraCaps) {
        val zr = c.zoomRatioRange
        if (Build.VERSION.SDK_INT >= 30 && zr != null) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio.coerceIn(zr.lower, zr.upper))
        } else {
            legacyCrop(c)?.let { b.set(CaptureRequest.SCALER_CROP_REGION, it) }
        }
    }

    /** Centred crop of the active array for digital zoom without CONTROL_ZOOM_RATIO; null at 1×. */
    private fun legacyCrop(c: CameraCaps): Rect? {
        if (Build.VERSION.SDK_INT >= 30 && c.zoomRatioRange != null) return null
        val aa = c.activeArray ?: return null
        val f = zoomRatio.coerceIn(1f, max(1f, c.maxDigitalZoom))
        if (f <= 1.001f) return null
        val w = (aa.width() / f).roundToInt()
        val h = (aa.height() / f).roundToInt()
        val left = (aa.width() - w) / 2
        val top = (aa.height() - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    private fun evSteps(ev: Float, c: CameraCaps): Int {
        val step = c.aeCompStep.toFloat().takeIf { it > 0f } ?: return 0
        return (ev / step).roundToInt().coerceIn(c.aeCompRange.lower, c.aeCompRange.upper)
    }

    private fun readMetadata(r: TotalCaptureResult): FrameMetadata {
        val gains = r.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val c = _caps.value
        val evSteps = r.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
        return FrameMetadata(
            exposureNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME),
            iso = r.get(CaptureResult.SENSOR_SENSITIVITY),
            aperture = r.get(CaptureResult.LENS_APERTURE),
            focalLengthMm = r.get(CaptureResult.LENS_FOCAL_LENGTH),
            focusDiopters = r.get(CaptureResult.LENS_FOCUS_DISTANCE),
            aeMode = aeModeName(r.get(CaptureResult.CONTROL_AE_MODE)),
            afMode = afModeName(r.get(CaptureResult.CONTROL_AF_MODE)),
            awbMode = awbModeName(r.get(CaptureResult.CONTROL_AWB_MODE)),
            wbGains = gains?.let { listOf(it.red, it.greenEven, it.greenOdd, it.blue) },
            evCompensation = if (evSteps != null && c != null) evSteps * c.aeCompStep.toFloat() else null,
            flash = when (r.get(CaptureResult.FLASH_STATE)) {
                CaptureResult.FLASH_STATE_FIRED -> "fired"
                CaptureResult.FLASH_STATE_PARTIAL -> "partial"
                CaptureResult.FLASH_STATE_UNAVAILABLE -> "unavailable"
                null -> null
                else -> if (r.get(CaptureResult.FLASH_MODE) == CameraMetadata.FLASH_MODE_TORCH) "torch" else "off"
            },
            ois = when (r.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)) {
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON -> "on"
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF -> "off"
                else -> null
            },
            zoomRatio = if (Build.VERSION.SDK_INT >= 30) r.get(CaptureResult.CONTROL_ZOOM_RATIO) else null,
            frameDurationNs = r.get(CaptureResult.SENSOR_FRAME_DURATION),
            noiseReduction = nrModeName(r.get(CaptureResult.NOISE_REDUCTION_MODE)),
            sensorTimestampNs = r.get(CaptureResult.SENSOR_TIMESTAMP),
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun openDevice(id: String): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (cont.isActive) cont.resume(camera) else camera.close()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (cont.isActive) cont.resumeWithException(IOException("Камера отключена"))
                else _error.value = "Камера занята другим приложением"
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                val msg = "Ошибка камеры ($error)"
                if (cont.isActive) cont.resumeWithException(IOException(msg)) else _error.value = msg
            }
        }, handler)
    }

    @Suppress("DEPRECATION")
    private suspend fun createSession(d: CameraDevice, targets: List<Surface>): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            d.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cont.isActive) cont.resume(session) else session.close()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (cont.isActive) cont.resumeWithException(IOException("Не удалось настроить камеру"))
                }
            }, handler)
        }

    companion object {
        private const val TAG = "CameraController"

        /** Longest exposure used for the live preview; longer manual exposures are simulated with gain. */
        const val PREVIEW_MAX_EXPOSURE_NS = 100_000_000L

        /**
         * Maps a point of the upright portrait preview (u right, v down, 0..1) to normalized
         * sensor coordinates. Front previews are mirrored by the framework.
         */
        fun viewToSensor(u: Float, v: Float, sensorOrientation: Int, mirrored: Boolean): Pair<Float, Float> {
            val uu = if (mirrored) 1f - u else u
            return when (sensorOrientation) {
                0 -> uu to v
                180 -> (1f - uu) to (1f - v)
                270 -> (1f - v) to uu
                else -> v to (1f - uu)
            }
        }

        /** JPEG orientation from the device orientation (OrientationEventListener convention). */
        fun jpegOrientation(c: CameraCaps, deviceOrientation: Int): Int {
            val d = if (c.isFront) -deviceOrientation else deviceOrientation
            return (c.sensorOrientation + d + 360) % 360
        }
    }
}
