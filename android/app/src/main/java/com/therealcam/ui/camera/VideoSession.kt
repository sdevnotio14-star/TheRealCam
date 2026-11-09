package com.therealcam.ui.camera

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.therealcam.camera.CameraCaps
import com.therealcam.camera.CameraController
import com.therealcam.camera.VideoCapsReader
import com.therealcam.camera.VideoConfig
import com.therealcam.camera.VideoOptions
import com.therealcam.camera.VideoOutput
import com.therealcam.camera.VideoQuality
import com.therealcam.camera.VideoRecorder
import com.therealcam.camera.VideoSpec
import com.therealcam.data.SettingsRepository
import com.therealcam.data.VideoStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * VIDEO mode of the camera screen: opens the video session (preview + recorder surface), starts and
 * stops recordings and keeps the user's video settings. Owned by [CameraViewModel].
 */
class VideoSession(
    private val app: Application,
    private val camera: CameraController,
    private val repo: SettingsRepository,
    private val scope: CoroutineScope,
    private val deviceOrientation: () -> Int,
    /** Reopens the camera with the current settings (CameraViewModel.openIfReady). */
    private val reopen: () -> Unit,
    private val message: (String) -> Unit,
) {
    data class Prefs(val quality: VideoQuality, val fps: Int, val stabilization: Boolean)

    private val manager = app.getSystemService(CameraManager::class.java)
    private val recorder: VideoRecorder = VideoRecorder(app).also { r ->
        r.onError = { what ->
            message("Не удалось записать видео: ошибка $what")
            scope.launch { stop() }
        }
    }
    private val store = VideoStore(app)
    /** Serializes open / start / stop. */
    private val ops = Mutex()
    private var target: VideoStore.Target? = null
    private var ticker: Job? = null

    private val _recording = MutableStateFlow(false)
    val recording = _recording.asStateFlow()
    private val _elapsedS = MutableStateFlow(0)
    val elapsedS = _elapsedS.asStateFlow()
    private val _options = MutableStateFlow<VideoOptions?>(null)
    val options = _options.asStateFlow()
    /** What the open session records (the settings adjusted to the camera). */
    private val _spec = MutableStateFlow<VideoSpec?>(null)
    val spec = _spec.asStateFlow()
    private val _prefs = MutableStateFlow<Prefs?>(null)
    val prefs = _prefs.asStateFlow()

    /** RECORD_AUDIO was already requested in this app session. */
    var micAsked = false

    suspend fun open(cameraId: String, texture: SurfaceTexture): CameraCaps = ops.withLock {
        val p = _prefs.value ?: repo.current().let { Prefs(VideoQuality.of(it.videoQuality), it.videoFps, it.videoStabilization) }
            .also { _prefs.value = it }
        val info = withContext(Dispatchers.IO) { VideoCapsReader.read(manager, cameraId) }
        val first = VideoConfig.choose(info, p.quality, p.fps, p.stabilization)
            ?: throw IOException("камера не поддерживает запись видео")
        _options.value = VideoConfig.options(info)
        try {
            openWith(cameraId, texture, first)
        } catch (e: Exception) {
            // 4K or 60 fps next to the preview is not a guaranteed stream combination: retry with 1080p30.
            val safe = VideoConfig.choose(info, VideoQuality.FHD, 30, p.stabilization)
            if (safe == null || safe == first) throw e
            openWith(cameraId, texture, safe)
        }
    }

    private suspend fun openWith(cameraId: String, texture: SurfaceTexture, spec: VideoSpec): CameraCaps {
        _spec.value = spec
        // The running session still uses the old surface: close it before replacing the surface.
        if (!recorder.hasSurfaceFor(spec)) camera.close()
        val surface = withContext(Dispatchers.IO) { recorder.prepareSurface(spec) }
        return camera.openVideo(cameraId, texture, VideoOutput(surface, spec))
    }

    fun setQuality(q: VideoQuality) = update { it.copy(quality = q) }
    fun setFps(fps: Int) = update { it.copy(fps = fps) }
    fun setStabilization(on: Boolean) = update { it.copy(stabilization = on) }

    private fun update(change: (Prefs) -> Prefs) {
        if (_recording.value || ops.isLocked) return
        val cur = _prefs.value ?: return
        val next = change(cur)
        if (next == cur) return
        _prefs.value = next
        scope.launch { repo.setVideo(next.quality.key, next.fps, next.stabilization) }
        reopen()
    }

    /** Record button / volume keys. Presses while a start or stop is in progress are ignored. */
    fun toggle() {
        if (ops.isLocked) return
        scope.launch { if (_recording.value) stop() else start() }
    }

    private suspend fun start(): Unit = ops.withLock {
        val caps = camera.caps.value ?: return@withLock
        val spec = _spec.value ?: return@withLock
        val mic = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val o = ((deviceOrientation() + 45) / 90 * 90) % 360
        val orientation = CameraController.jpegOrientation(caps, o)
        val name = "TRC_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        var t: VideoStore.Target? = null
        try {
            val out = withContext(Dispatchers.IO) { store.create(name) }
            t = out
            val withSound = withContext(Dispatchers.IO) { recorder.start(spec, mic, orientation, out.pfd.fileDescriptor) }
            if (!camera.setRecording(true)) throw IOException("камера не в режиме видео")
            target = out
            if (mic && !withSound) message("Микрофон недоступен — видео без звука")
            _elapsedS.value = 0
            _recording.value = true
            val t0 = SystemClock.elapsedRealtime()
            ticker = scope.launch {
                while (isActive) {
                    _elapsedS.value = ((SystemClock.elapsedRealtime() - t0) / 1000).toInt()
                    delay(250)
                }
            }
        } catch (e: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                recorder.abort()
                t?.let(store::discard)
            }
            if (e !is CancellationException) message("Не удалось записать видео: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Stops and saves a running recording; does nothing otherwise. Also used when the screen pauses. */
    suspend fun stop(): Unit = withContext(NonCancellable) {
        ops.withLock {
            if (!_recording.value) return@withLock
            _recording.value = false
            ticker?.cancel()
            val t = target ?: return@withLock
            target = null
            runCatching { camera.setRecording(false) }
            val text = withContext(Dispatchers.IO) {
                val ms = runCatching { recorder.stop() }
                val duration = ms.getOrNull()
                when {
                    duration != null && duration >= MIN_DURATION_MS -> runCatching { store.publish(t) }.fold(
                        { "Видео сохранено: ${t.label}" },
                        { e -> store.discard(t); "Не удалось записать видео: ${e.message}" },
                    )
                    duration != null -> {
                        store.discard(t)
                        "Видео слишком короткое — не сохранено"
                    }
                    else -> {
                        store.discard(t)
                        "Не удалось записать видео: ${ms.exceptionOrNull()?.message ?: "нет кадров"}"
                    }
                }
            }
            message(text)
        }
    }

    /**
     * Tap x on the 16:9 portrait preview → x on the full-sensor preview that [CameraController.focusAt]
     * expects: the video crops the sensor's short side, which is horizontal on screen.
     */
    fun tapU(u: Float): Float {
        val aa = camera.caps.value?.activeArray ?: return u
        val s = _spec.value ?: return u
        val sensor = max(aa.width(), aa.height()).toFloat() / min(aa.width(), aa.height())
        val video = s.size.width.toFloat() / s.size.height
        return if (video > sensor) 0.5f + (u - 0.5f) * sensor / video else u
    }

    /** ViewModel cleared: best-effort save of a recording that is still running. */
    fun release() {
        ticker?.cancel()
        target?.let { t -> runCatching { recorder.stop() }.onSuccess { store.publish(t) }.onFailure { store.discard(t) } }
        target = null
        _recording.value = false
        recorder.release()
    }

    private companion object {
        const val MIN_DURATION_MS = 500L
    }
}
