package com.therealcam.ui.camera

import android.app.Application
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.media.MediaActionSound
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.therealcam.ai.AstroPlanner
import com.therealcam.ai.CapturePlan
import com.therealcam.ai.CapturePlanner
import com.therealcam.ai.SceneFeatures
import com.therealcam.ai.ScenePreview
import com.therealcam.ai.engine.ModelOutput
import com.therealcam.ai.engine.TrcmModel
import com.therealcam.appGraph
import com.therealcam.camera.CameraCaps
import com.therealcam.camera.CameraController
import com.therealcam.camera.CameraMode
import com.therealcam.camera.ExposureScale
import com.therealcam.camera.FocusMode
import com.therealcam.camera.Lens
import com.therealcam.camera.LensKind
import com.therealcam.camera.LensProbe
import com.therealcam.camera.LensUse
import com.therealcam.camera.ShotSettings
import com.therealcam.camera.ZoomMath
import com.therealcam.capture.PresetPlanner
import com.therealcam.capture.ScenePreset
import com.therealcam.capture.ShotRunner
import com.therealcam.capture.SpeechText
import com.therealcam.capture.VoiceAnnouncer
import com.therealcam.core.RootCamera
import com.therealcam.data.AppSettings
import com.therealcam.data.Profile
import com.therealcam.data.SceneKeys
import com.therealcam.data.StackMode
import com.therealcam.data.UserPreset
import com.therealcam.data.db.CaptureEntity
import com.therealcam.data.db.CategoryEntity
import com.therealcam.dataset.AiInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TimeZone
import kotlin.math.ln

/** AI suggestions for the current scene. */
data class AiState(
    val probs: FloatArray,
    val ranking: List<Int>,
    val chosen: Int,
    val userOverride: Boolean,
    val plan: CapturePlan?,
)

class CameraViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.appGraph
    val camera = CameraController(app)
    private val runner = ShotRunner(g, camera)
    private val shutterSound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }

    val settings: StateFlow<AppSettings> = g.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val categories: StateFlow<List<CategoryEntity>> = g.categories.all.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val latest: StateFlow<CaptureEntity?> = g.captures.latest.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val datasetCount: StateFlow<Int> = g.captures.datasetCount.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val model: StateFlow<TrcmModel?> = g.models.active
    val modelError: StateFlow<String?> = g.models.activeError
    val motion = g.sensors.state
    val location = g.location.location

    private val _mode = MutableStateFlow(CameraMode.AUTO)
    val mode = _mode.asStateFlow()
    private val _shot = MutableStateFlow(ShotSettings())
    val shot = _shot.asStateFlow()
    private val _autoEv = MutableStateFlow(0f)
    val autoEv = _autoEv.asStateFlow()
    private val _torch = MutableStateFlow(false)
    val torch = _torch.asStateFlow()
    private val _categoryHint = MutableStateFlow<String?>(null)
    val categoryHint = _categoryHint.asStateFlow()
    private val _front = MutableStateFlow(false)
    val front = _front.asStateFlow()
    private val _busy = MutableStateFlow<ShotRunner.Progress?>(null)
    val busy = _busy.asStateFlow()
    private val _countdown = MutableStateFlow<Int?>(null)
    val countdown = _countdown.asStateFlow()
    private val _ratingFor = MutableStateFlow<String?>(null)
    val ratingFor = _ratingFor.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _ai = MutableStateFlow<AiState?>(null)
    val ai = _ai.asStateFlow()
    /** Exposure meter of the last analysed preview, EV relative to mid-grey. */
    private val _meterEv = MutableStateFlow<Double?>(null)
    val meterEv = _meterEv.asStateFlow()
    /** Live histogram in manual mode when pro mode is on (see [Histogram]). */
    private val _histogram = MutableStateFlow<FloatArray?>(null)
    val histogram = _histogram.asStateFlow()

    private var lensSpecs: LensProbe.Specs? = null
    /** Lenses of the current side (back or front), sorted by zoom, macro last. */
    private val _lenses = MutableStateFlow<List<Lens>>(emptyList())
    val lenses = _lenses.asStateFlow()
    /** Highlighted lens; its camera is the one that is (or will be) open. */
    private val _lens = MutableStateFlow<Lens?>(null)
    val lens = _lens.asStateFlow()
    /** Field of view relative to the main lens of this side: 0.6×, 1×, 2.5×… */
    private val _zoom = MutableStateFlow(1f)
    val zoom = _zoom.asStateFlow()
    private var pinchZoom: Float? = null

    /** Astro mode plan (frames × exposure · ISO), refreshed from the preview meter. */
    private val _astro = MutableStateFlow<AstroPlanner.Plan?>(null)
    val astro = _astro.asStateFlow()
    /** Scene of the СЦЕНЫ mode and its plan, refreshed from the preview meter. */
    private val _preset = MutableStateFlow(ScenePreset.NIGHT)
    val preset = _preset.asStateFlow()
    private val _presetPlan = MutableStateFlow<PresetPlanner.Plan?>(null)
    val presetPlan = _presetPlan.asStateFlow()
    /** Seconds left until a long series finishes capturing. */
    private val _remainingS = MutableStateFlow<Int?>(null)
    val remainingS = _remainingS.asStateFlow()
    private var lastMeanLin: Double? = null
    @Volatile private var etaRt: Long? = null
    private var voice: VoiceAnnouncer? = null

    /** Provided by the UI while the preview view exists (main thread only). */
    var sceneGrabber: (() -> ScenePreview?)? = null

    private var texture: SurfaceTexture? = null
    private var resumed = false
    private var analysisJob: Job? = null
    private var shotJob: Job? = null
    private var smoothed: FloatArray? = null
    private var smoothedFor: TrcmModel? = null

    init {
        viewModelScope.launch {
            val s = g.settings.settings.first()
            _mode.value = CameraMode.of(s.lastMode)
            _categoryHint.value = s.lastCategoryHint
            _preset.value = ScenePreset.of(s.lastPreset).takeIf { it != ScenePreset.STARS } ?: ScenePreset.NIGHT
            applyPreviewSettings()
        }
        viewModelScope.launch {
            val manager = app.getSystemService(CameraManager::class.java)
            val s = g.settings.current()
            lensSpecs = withContext(Dispatchers.IO) {
                // With root, unlock the vendor-hidden modules before the camera list is read.
                if (s.proMode && s.rootCamera) runCatching { RootCamera.enable(app.packageName) }
                runCatching { LensProbe.read(manager) }.getOrNull()
            }
            refreshLenses()
        }
        viewModelScope.launch {
            settings.collect { s ->
                camera.pro = if (s.proMode) CameraController.ProOptions(s.noiseReduction, s.edgeMode, s.oisMode)
                else CameraController.ProOptions()
            }
        }
    }

    // ------------------------------------------------------------ lifecycle

    fun onSurface(t: SurfaceTexture?) {
        texture = t
        if (t == null) viewModelScope.launch { camera.close() } else openIfReady()
    }

    fun onResume() {
        if (resumed) return
        resumed = true
        g.sensors.acquire()
        if (settings.value.geotag) g.location.start()
        openIfReady()
        startAnalysis()
        // Root may have been switched on in settings meanwhile: modules can appear or disappear.
        if (lensSpecs != null) viewModelScope.launch {
            val manager = getApplication<Application>().getSystemService(CameraManager::class.java)
            withContext(Dispatchers.IO) { runCatching { LensProbe.read(manager) }.getOrNull() }?.let {
                lensSpecs = it
                if (_busy.value == null) refreshLenses()
            }
        }
    }

    fun onPause() {
        if (!resumed) return
        resumed = false
        analysisJob?.cancel()
        g.sensors.release()
        g.location.stop()
        viewModelScope.launch { camera.close() }
    }

    fun observeCapture(id: String) = g.captures.observe(id)

    fun startLocation() {
        if (resumed && settings.value.geotag) g.location.start()
    }

    private fun openIfReady() {
        val t = texture ?: return
        if (!resumed) return
        viewModelScope.launch {
            try {
                val (back, frontId) = camera.cameraIds()
                val fallback = (if (_front.value) frontId else back) ?: back ?: frontId ?: run {
                    _message.value = "Камера не найдена"
                    return@launch
                }
                val l = _lens.value
                camera.setZoomRatio(l?.ratioFor(_zoom.value) ?: 1f, applyNow = false)
                camera.open(l?.cameraId ?: fallback, t)
                applyPreviewSettings()
            } catch (e: Exception) {
                val l = _lens.value
                if (l != null && l.kind != LensKind.MAIN && l.kind != LensKind.FRONT && l.cameraId !in blockedIds) {
                    // The vendor lists the module but does not let third-party apps open it: back to the main lens.
                    blockedIds += l.cameraId
                    _lens.value = null
                    refreshLenses(reopen = false)
                    _message.value = "${l.kind.title}: производитель не даёт открыть этот объектив сторонним приложениям"
                    openIfReady()
                } else {
                    _message.value = "Не удалось открыть камеру: ${e.message}"
                }
            }
        }
    }

    /** Camera ids that failed to open; their lenses are no longer offered. */
    private val blockedIds = mutableSetOf<String>()

    /** Rebuilds the lens list for the current side and falls back to its main lens if needed. */
    private fun refreshLenses(reopen: Boolean = true) {
        val list = lensSpecs?.lenses(_front.value).orEmpty().filter { it.cameraId !in blockedIds }
        _lenses.value = list
        val cur = _lens.value
        if (cur == null || cur !in list) {
            val main = list.firstOrNull { it.kind == LensKind.MAIN || it.kind == LensKind.FRONT } ?: list.firstOrNull()
            _lens.value = main
            _zoom.value = main?.zoom ?: 1f
            // The camera may already be open on the default id: only reopen if the main lens differs.
            if (reopen && main != null && camera.caps.value != null && camera.caps.value?.id != main.cameraId) openIfReady()
        }
    }

    /** Lens chip: switch lens (reopening the camera only when it is a different camera id). */
    fun selectLens(l: Lens) {
        if (_busy.value != null || _countdown.value != null) return
        pinchZoom = null
        val reopen = camera.caps.value?.id != l.cameraId
        _lens.value = l
        _zoom.value = l.zoom
        camera.setZoomRatio(l.baseRatio, applyNow = !reopen)
        if (reopen) openIfReady()
    }

    /** Digital zoom button ("2"): the best lens for [target], reopening only for another camera id. */
    fun setZoom(target: Float) {
        if (_busy.value != null || _countdown.value != null) return
        pinchZoom = null
        val list = _lenses.value
        val range = ZoomMath.range(list)
        val t = target.coerceIn(range.start, range.endInclusive)
        val best = ZoomMath.lensFor(list, t) ?: return
        val reopen = camera.caps.value?.id != best.cameraId
        _lens.value = best
        _zoom.value = t.coerceIn(best.minZoom, best.maxZoom)
        camera.setZoomRatio(best.ratioFor(_zoom.value), applyNow = !reopen)
        if (reopen) openIfReady()
    }

    /** Pinch on the preview: zoom on the open camera, switching lens when the gesture ends. */
    fun onPinch(scale: Float) {
        if (_busy.value != null || _countdown.value != null) return
        val list = _lenses.value
        val cur = _lens.value ?: return
        val camId = camera.caps.value?.id ?: return
        val range = ZoomMath.range(list)
        val target = ((pinchZoom ?: _zoom.value) * scale).coerceIn(range.start, range.endInclusive)
        pinchZoom = target
        val lens = if (cur.kind == LensKind.MACRO) cur else {
            ZoomMath.lensFor(list, target)?.takeIf { it.cameraId == camId }
                ?: ZoomMath.lensFor(list.filter { it.cameraId == camId }, target)
                ?: cur
        }
        val shown = target.coerceIn(lens.minZoom, lens.maxZoom)
        _lens.value = lens
        _zoom.value = shown
        camera.setZoomRatio(lens.ratioFor(shown))
    }

    fun onPinchEnd() {
        val target = pinchZoom ?: return
        pinchZoom = null
        if (_lens.value?.kind == LensKind.MACRO) return
        val best = ZoomMath.lensFor(_lenses.value, target) ?: return
        if (best.cameraId == camera.caps.value?.id) return
        // The requested view is better served by another camera id: switch to it now.
        _lens.value = best
        _zoom.value = target.coerceIn(best.minZoom, best.maxZoom)
        camera.setZoomRatio(best.ratioFor(_zoom.value), applyNow = false)
        openIfReady()
    }

    override fun onCleared() {
        camera.release()
        shutterSound.release()
        voice?.shutdown()
    }

    // ------------------------------------------------------------ controls

    fun setMode(m: CameraMode) {
        if (_mode.value == m) return
        _mode.value = m
        _ai.value = null
        smoothed = null
        viewModelScope.launch { g.settings.setLastMode(m.key) }
        if (m == CameraMode.ASTRO) camera.caps.value?.let { _astro.value = astroPlan(it) }
        if (m == CameraMode.PRESET) camera.caps.value?.let { _presetPlan.value = presetPlan(it) }
        applyPreviewSettings()
    }

    fun setAstroDuration(s: Int) {
        camera.caps.value?.let { _astro.value = astroPlan(it, s) }
        viewModelScope.launch { g.settings.setAstroDurationS(s) }
    }

    fun setVoice(on: Boolean) = viewModelScope.launch { g.settings.setVoice(on) }

    /** Scene button; "Звёзды" opens the АСТРО mode, which has its own controls. */
    fun setPreset(p: ScenePreset) {
        if (_busy.value != null) return
        if (p == ScenePreset.STARS) {
            setMode(CameraMode.ASTRO)
            return
        }
        _userPreset.value = null
        _preset.value = p
        viewModelScope.launch { g.settings.setLastPreset(p.key) }
        camera.caps.value?.let { _presetPlan.value = presetPlan(it) }
        applyPreviewSettings()
    }

    /** Selected user preset id in the СЦЕНЫ mode; null = a built-in scene. */
    private val _userPreset = MutableStateFlow<String?>(null)
    val userPreset = _userPreset.asStateFlow()

    fun setUserPreset(id: String) {
        if (_busy.value != null) return
        _userPreset.value = id
        camera.caps.value?.let { _presetPlan.value = presetPlan(it) }
        applyPreviewSettings()
    }

    /** Manual mode: keep the current settings as a preset of the СЦЕНЫ mode. */
    fun saveManualAsPreset(name: String, icon: String) = viewModelScope.launch {
        val id = "u" + System.currentTimeMillis().toString(36)
        g.settings.saveUserPreset(UserPreset.from(id, name, icon, _shot.value.copy(torch = false), hintProfile()))
        _message.value = "Пресет «$name» сохранён — он в режиме СЦЕНЫ"
    }

    fun deleteUserPreset(id: String) = viewModelScope.launch {
        g.settings.deleteUserPreset(id)
        if (_userPreset.value == id) {
            _userPreset.value = null
            camera.caps.value?.let { _presetPlan.value = presetPlan(it) }
            applyPreviewSettings()
        }
    }

    private fun selectedUserPreset(): UserPreset? = _userPreset.value?.let { id -> settings.value.userPresets.firstOrNull { it.id == id } }

    private fun presetPlan(caps: CameraCaps): PresetPlanner.Plan {
        val p = camera.preview.value
        val input = PresetPlanner.Input(
            manualSensor = caps.manualSensor,
            minExpNs = caps.exposureRange?.lower,
            maxExpNs = caps.exposureRange?.upper,
            minIso = caps.isoRange?.lower,
            maxIso = caps.isoRange?.upper,
            canFocusInfinity = caps.canManualFocus,
            aperture = p?.aperture ?: caps.apertures.firstOrNull(),
            previewExpNs = p?.exposureNs,
            previewIso = p?.iso,
            previewMeanLin = lastMeanLin,
            steady = motion.value.steady,
        )
        return selectedUserPreset()?.let { PresetPlanner.planUser(it, input) } ?: PresetPlanner.plan(_preset.value, input)
    }

    private fun astroPlan(caps: CameraCaps, durationS: Int = settings.value.astroDurationS): AstroPlanner.Plan {
        val p = camera.preview.value
        val l = _lens.value
        return AstroPlanner.plan(
            AstroPlanner.Input(
                durationS = durationS,
                manualSensor = caps.manualSensor,
                focalMm = p?.focalLengthMm ?: caps.focalLengths.firstOrNull(),
                aperture = p?.aperture ?: caps.apertures.firstOrNull(),
                pixelPitchUm = caps.physicalSize?.let { it.width * 1000f / caps.jpegSize.width },
                equivMm = l?.equivMm,
                digitalZoom = l?.let { _zoom.value / it.zoom } ?: 1f,
                minExpNs = caps.exposureRange?.lower,
                maxExpNs = caps.exposureRange?.upper,
                minIso = caps.isoRange?.lower,
                maxIso = caps.isoRange?.upper,
                canFocusInfinity = caps.canManualFocus,
                previewExpNs = p?.exposureNs,
                previewIso = p?.iso,
                previewMeanLin = lastMeanLin,
            ),
        )
    }

    fun switchCamera() {
        if (_busy.value != null) return
        _front.value = !_front.value
        _shot.value = ShotSettings(frames = _shot.value.frames, stackMode = _shot.value.stackMode)
        pinchZoom = null
        _lens.value = null
        refreshLenses(reopen = false)
        openIfReady()
    }

    fun setAutoEv(ev: Float) {
        _autoEv.value = ev
        applyPreviewSettings()
    }

    fun toggleTorch() {
        _torch.value = !_torch.value
        applyPreviewSettings()
    }

    fun setCategoryHint(id: String?) {
        _categoryHint.value = if (_categoryHint.value == id) null else id
        viewModelScope.launch { g.settings.setLastCategoryHint(_categoryHint.value) }
        val cat = categories.value.firstOrNull { it.id == _categoryHint.value }
        // A hint with a multi-frame profile proposes that profile's merge mode for manual series.
        if (cat != null && _shot.value.frames > 1) {
            val p = SceneKeys.profileOf(cat.icon)
            if (p.stackMode != StackMode.NONE) updateShot { it.copy(stackMode = p.stackMode) }
        }
    }

    fun updateShot(change: (ShotSettings) -> ShotSettings) {
        _shot.value = change(_shot.value)
        applyPreviewSettings()
    }

    /** Manual ISO; picking one while shutter is auto fixes the shutter at the current metered value. */
    fun setIso(iso: Int?) = updateShot { s ->
        if (iso == null) s.copy(iso = null, exposureNs = null)
        else s.copy(iso = iso, exposureNs = s.exposureNs ?: meteredShutter())
    }

    fun setShutter(ns: Long?) = updateShot { s ->
        if (ns == null) s.copy(iso = null, exposureNs = null)
        else s.copy(exposureNs = ns, iso = s.iso ?: meteredIso())
    }

    private fun meteredShutter(): Long {
        val caps = camera.caps.value
        val p = camera.preview.value?.exposureNs ?: 16_666_666L
        return ExposureScale.nearest(ExposureScale.shutterValuesNs(caps), p)
    }

    private fun meteredIso(): Int {
        val caps = camera.caps.value
        val p = camera.preview.value?.iso ?: 100
        return ExposureScale.nearestIso(ExposureScale.isoValues(caps), p)
    }

    fun setFrames(n: Int) = updateShot { s ->
        val mode = when {
            n <= 1 -> StackMode.NONE
            s.stackMode != StackMode.NONE -> s.stackMode
            else -> hintProfile().stackMode.takeIf { it != StackMode.NONE } ?: StackMode.MEAN
        }
        s.copy(frames = n.coerceIn(1, 64), stackMode = mode)
    }

    private fun hintProfile(): Profile =
        categories.value.firstOrNull { it.id == _categoryHint.value }?.let { SceneKeys.profileOf(it.icon) } ?: Profile.DEFAULT

    fun focusAt(u: Float, v: Float) {
        if (_mode.value == CameraMode.MANUAL && _shot.value.focus != FocusMode.AUTO) return
        camera.focusAt(u, v)
    }

    fun pickAiClass(k: Int) {
        val st = _ai.value ?: return
        val m = model.value ?: return
        val caps = camera.caps.value ?: return
        val override = !(st.userOverride && st.chosen == k)
        _ai.value = st.copy(chosen = k, userOverride = override, plan = CapturePlanner.plan(m, lastOutput ?: return, k, caps))
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun dismissRating() {
        _ratingFor.value = null
    }

    fun rate(id: String, change: (CaptureEntity) -> CaptureEntity) {
        viewModelScope.launch { g.captures.rate(id, change) }
    }

    fun createCategory(name: String, icon: String, assignTo: String?) {
        viewModelScope.launch {
            val c = g.categories.create(name, icon)
            if (assignTo != null) g.captures.rate(assignTo) { it.copy(categoryId = c.id) }
        }
    }

    private fun previewSettings(): ShotSettings = when (_mode.value) {
        CameraMode.AUTO -> ShotSettings(ev = _autoEv.value, torch = _torch.value)
        CameraMode.MANUAL -> _shot.value.copy(torch = _torch.value)
        CameraMode.ASTRO -> ShotSettings(focus = FocusMode.INFINITY, torch = _torch.value)
        CameraMode.PRESET -> {
            val caps = camera.caps.value
            when {
                // User presets and the moon are previewed with their own manual exposure.
                caps != null && (selectedUserPreset() != null || _preset.value == ScenePreset.MOON) ->
                    presetPlan(caps).settings.copy(frames = 1, torch = _torch.value)
                _preset.value in INFINITY_PRESETS && caps?.canManualFocus == true -> ShotSettings(focus = FocusMode.INFINITY, torch = _torch.value)
                else -> ShotSettings(torch = _torch.value)
            }
        }
        CameraMode.AI -> ShotSettings(torch = _torch.value)
    }

    private fun applyPreviewSettings() = camera.setShot(previewSettings())

    // ------------------------------------------------------------ analysis (meter + AI)

    private var lastOutput: ModelOutput? = null

    private fun featureContext(): SceneFeatures.Context {
        val p = camera.preview.value
        val m = motion.value
        val loc = if (settings.value.geotag) location.value else null
        val now = System.currentTimeMillis()
        return SceneFeatures.Context(
            previewExposureNs = p?.exposureNs, previewIso = p?.iso, aperture = p?.aperture, lux = m.lux,
            pitchDeg = m.pitchDeg, rollDeg = m.rollDeg, gyroRms = m.gyroRms, timeMs = now,
            tzOffsetMs = TimeZone.getDefault().getOffset(now), lat = loc?.latitude, lon = loc?.longitude,
            focusDiopters = p?.focusDiopters, zoom = _zoom.value, front = camera.caps.value?.isFront == true,
        )
    }

    private fun startAnalysis() {
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            while (isActive) {
                delay(if (_mode.value == CameraMode.AI) 450L else 800L)
                if (_busy.value != null || _mode.value == CameraMode.AUTO) continue
                val caps = camera.caps.value ?: continue
                val scene = sceneGrabber?.invoke() ?: continue
                val ctx = featureContext()
                val m = model.value
                val result = withContext(Dispatchers.Default) {
                    val stats = SceneFeatures.imageStats(scene.px, scene.w, scene.h)
                    val out = if (_mode.value == CameraMode.AI && m != null) {
                        runCatching { m.run(scene.thumb, SceneFeatures.compute(scene.px, scene.w, scene.h, ctx)) }.getOrNull()
                    } else null
                    stats.meanLin to out
                }
                lastMeanLin = result.first
                _meterEv.value = ln(maxOf(result.first, 1e-5) / 0.18) / ln(2.0)
                _histogram.value = if (_mode.value == CameraMode.MANUAL && settings.value.proMode) {
                    withContext(Dispatchers.Default) { Histogram.of(scene.px) }
                } else null
                if (_mode.value == CameraMode.ASTRO) {
                    _astro.value = astroPlan(caps)
                    continue
                }
                if (_mode.value == CameraMode.PRESET) {
                    _presetPlan.value = presetPlan(caps)
                    continue
                }
                val out = result.second
                if (out == null || m == null || _mode.value != CameraMode.AI) continue
                lastOutput = out
                val prev = smoothed?.takeIf { smoothedFor === m && it.size == out.probs.size }
                val sm = if (prev == null) out.probs.copyOf() else FloatArray(out.probs.size) { 0.6f * prev[it] + 0.4f * out.probs[it] }
                smoothed = sm
                smoothedFor = m
                val ranking = sm.indices.sortedByDescending { sm[it] }
                val st = _ai.value
                val chosen = if (st != null && st.userOverride && st.chosen in sm.indices) st.chosen else ranking.first()
                _ai.value = AiState(sm, ranking, chosen, st?.userOverride == true && st.chosen == chosen, CapturePlanner.plan(m, out, chosen, caps))
            }
        }
    }

    // ------------------------------------------------------------ shutter

    fun onShutter() {
        if (_busy.value != null || _countdown.value != null) return
        val caps = camera.caps.value ?: return
        _ratingFor.value = null
        shotJob = viewModelScope.launch {
            val app = settings.value
            // Astro and tripod scenes: a short delay so pressing the shutter does not shake the first frame.
            val tripodScene = _mode.value == CameraMode.PRESET && _presetPlan.value?.needsTripod == true && motion.value.steady
            val timer = when {
                _mode.value == CameraMode.ASTRO -> maxOf(app.timerSeconds, 3)
                tripodScene -> maxOf(app.timerSeconds, 2)
                else -> app.timerSeconds
            }
            for (s in timer downTo 1) {
                _countdown.value = s
                delay(1000)
            }
            _countdown.value = null
            val scene = sceneGrabber?.invoke()
            val ctx = featureContext()
            val features = scene?.let { s -> withContext(Dispatchers.Default) { SceneFeatures.compute(s.px, s.w, s.h, ctx) } }
                ?: SceneFeatures.NAMES.associateWith { null }
            val plan = buildPlan(app).copy(timerS = timer)
            val expectedMs = plan.settings.frames.coerceAtLeast(1) * ShotRunner.plannedFrameMs(plan.settings)
            val speak = app.voice && expectedMs >= SPEAK_MIN_MS
            if (speak) speaker().say(SpeechText.start((expectedMs / 1000).toInt(), plan.mode == CameraMode.ASTRO))
            etaRt = null
            _busy.value = ShotRunner.Progress.Capturing(0, plan.settings.frames, plan.settings.exposureNs)
            val ticker = if (expectedMs >= COUNTDOWN_MIN_MS) launch { countdown(expectedMs, speak) } else null
            try {
                val env = ShotRunner.Env(caps, camera.preview.value, scene, features, motion.value, location.value, app)
                stopRequested = false
                shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                val e = withContext(Dispatchers.Default) {
                    runner.run(plan, env, stop = { stopRequested }) { p ->
                        if (p is ShotRunner.Progress.Capturing) etaRt = p.endEstimateRt
                        _busy.value = p
                    }
                }
                if (speak) speaker().say("Готово")
                if (app.datasetMode && app.askRating) _ratingFor.value = e.id
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) {
                    _message.value = "Не удалось снять: ${t.message}"
                    if (speak) speaker().say("Не удалось снять")
                }
            } finally {
                ticker?.cancel()
                _remainingS.value = null
                etaRt = null
                _busy.value = null
                applyPreviewSettings()
            }
        }
    }

    private fun speaker(): VoiceAnnouncer = voice ?: VoiceAnnouncer(getApplication()).also { voice = it }

    /** Time left on screen and, if [speak], aloud on every 30-second mark ("Осталось 3 минуты"). */
    private suspend fun countdown(expectedMs: Long, speak: Boolean) {
        var step = SpeechText.tick(expectedMs, Int.MAX_VALUE).first
        var mergingSaid = false
        while (true) {
            val b = _busy.value
            val eta = etaRt
            if (b is ShotRunner.Progress.Capturing && eta != null) {
                val rem = eta - SystemClock.elapsedRealtime()
                _remainingS.value = (rem / 1000).toInt().coerceAtLeast(0)
                if (speak) {
                    val (s, say) = SpeechText.tick(rem, step)
                    step = s
                    say?.let { speaker().say(SpeechText.remaining(it)) }
                }
            } else if (b is ShotRunner.Progress.Merging) {
                _remainingS.value = null
                if (speak && !mergingSaid) {
                    mergingSaid = true
                    speaker().say("Съёмка закончена, свожу кадры")
                }
            }
            delay(500)
        }
    }

    @Volatile private var stopRequested = false

    /** Second press during a series: stop after the current frame and merge what was taken. */
    fun requestStop() {
        val b = _busy.value
        if (b is ShotRunner.Progress.Capturing && b.total > 1 && !stopRequested) {
            stopRequested = true
            _message.value = "Останавливаю серию после текущего кадра…"
        }
    }

    fun cancelShot() {
        if (_countdown.value != null) {
            shotJob?.cancel()
            _countdown.value = null
        }
    }

    private fun buildPlan(app: AppSettings): ShotRunner.Plan = basePlan(app).copy(lens = lensUse())

    private fun lensUse(): LensUse? {
        val l = _lens.value ?: return null
        val z = _zoom.value
        // Every lens's field of view is expressed relative to the main lens, so this holds for all of them.
        val mainEq = _lenses.value.firstOrNull { it.kind == LensKind.MAIN || it.kind == LensKind.FRONT }?.equivMm
        return LensUse(l.kind, z, mainEq?.times(z))
    }

    private fun basePlan(app: AppSettings): ShotRunner.Plan {
        val hint = _categoryHint.value?.takeIf { id -> categories.value.any { it.id == id } }
        return when (_mode.value) {
            CameraMode.AUTO -> ShotRunner.Plan(
                CameraMode.AUTO, ShotSettings(ev = _autoEv.value, torch = _torch.value), hintProfile(), hint, null, app.timerSeconds,
            )
            CameraMode.MANUAL -> ShotRunner.Plan(
                CameraMode.MANUAL, _shot.value.copy(torch = _torch.value), hintProfile(), hint, null, app.timerSeconds,
            )
            CameraMode.ASTRO -> {
                val settings = camera.caps.value?.let { astroPlan(it).settings } ?: _astro.value?.settings
                    ?: ShotSettings(focus = FocusMode.INFINITY, frames = 15, stackMode = StackMode.SIGMA_CLIP)
                // Astro shots go to the user's astro category if there is one; it can be changed when rating.
                val cat = hint ?: categories.value.firstOrNull { SceneKeys.profileOf(it.icon) == Profile.ASTRO }?.id
                ShotRunner.Plan(CameraMode.ASTRO, settings, Profile.ASTRO, cat, null, app.timerSeconds)
            }
            CameraMode.PRESET -> {
                val p = _preset.value
                val user = selectedUserPreset()
                val profile = user?.let { Profile.of(it.profile) } ?: p.profile
                val settings = (camera.caps.value?.let { presetPlan(it).settings } ?: _presetPlan.value?.settings ?: ShotSettings())
                    .copy(torch = _torch.value)
                val cat = hint ?: categories.value.firstOrNull { SceneKeys.profileOf(it.icon) == profile }?.id
                ShotRunner.Plan(CameraMode.PRESET, settings, profile, cat, null, app.timerSeconds, preset = if (user != null) "user" else p.key)
            }
            CameraMode.AI -> {
                val m = model.value
                val st = _ai.value
                val plan = st?.plan
                if (m == null || st == null || plan == null) {
                    ShotRunner.Plan(CameraMode.AI, ShotSettings(torch = _torch.value), Profile.DEFAULT, hint, null, app.timerSeconds)
                } else {
                    val userCategory = categories.value.firstOrNull { it.id == plan.cls.id }?.id
                    val info = AiInfo(
                        modelName = m.name, modelSha256 = m.sha256,
                        probs = m.classes.indices.associate { m.classes[it].id to st.probs[it].toDouble() },
                        chosenClass = plan.cls.id, userOverride = st.userOverride, params = plan.rawParams,
                    )
                    ShotRunner.Plan(CameraMode.AI, plan.settings.copy(torch = _torch.value), plan.profile, userCategory ?: hint, info, app.timerSeconds)
                }
            }
        }
    }

    fun setTimer(seconds: Int) = viewModelScope.launch { g.settings.setTimerSeconds(seconds) }
    fun setGrid(on: Boolean) = viewModelScope.launch { g.settings.setGrid(on) }

    private companion object {
        /** Series at least this long show the time left; from [SPEAK_MIN_MS] it is also spoken. */
        const val COUNTDOWN_MIN_MS = 10_000L
        const val SPEAK_MIN_MS = 30_000L
        val INFINITY_PRESETS = setOf(ScenePreset.CLOUDS, ScenePreset.SUNSET, ScenePreset.MOON, ScenePreset.TRAILS)
    }
}
