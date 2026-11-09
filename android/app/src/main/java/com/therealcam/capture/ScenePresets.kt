package com.therealcam.capture

import com.therealcam.camera.ExposureScale
import com.therealcam.camera.FocusMode
import com.therealcam.camera.ShotSettings
import com.therealcam.data.Profile
import com.therealcam.data.UserPreset
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** One-tap scenes of the СЦЕНЫ mode: a series, its merge method and the exposure rule. */
enum class ScenePreset(
    val key: String,
    val title: String,
    /** Scene icon key (ui/common/SceneIcons.kt). */
    val icon: String,
    val profile: Profile,
) {
    NIGHT("night", "Ночь", "city_night", Profile.NIGHT),
    CLOUDS("clouds", "Облака", "clouds", Profile.SKY),
    SUNSET("sunset", "Закат", "sunset", Profile.SKY),
    MOON("moon", "Луна", "moon", Profile.LUNAR),
    /** Opens the АСТРО mode. */
    STARS("stars", "Звёзды", "stars", Profile.ASTRO),
    TRAILS("trails", "Следы света", "light_trails", Profile.TRAILS),
    WATER("water", "Вода", "waterfall", Profile.LONG),
    ACTION("action", "Движение", "sport", Profile.ACTION),
    DOCUMENT("document", "Документ", "document", Profile.DOCUMENT);

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: NIGHT
    }
}

/** Turns a preset and the preview meter into settings; exposure is split between time and ISO per scene. */
object PresetPlanner {
    /** Linear mean the auto exposure aims at (mid grey). */
    private const val TARGET_LIN = 0.18
    /** Moon disc brightness for the "Looney 11" rule, a bit brighter for small phone sensors (EV at ISO 100). */
    private const val MOON_EV100 = 14.0

    data class Input(
        val manualSensor: Boolean,
        val minExpNs: Long?,
        val maxExpNs: Long?,
        val minIso: Int?,
        val maxIso: Int?,
        val canFocusInfinity: Boolean,
        val aperture: Float?,
        val previewExpNs: Long?,
        val previewIso: Int?,
        val previewMeanLin: Double?,
        val steady: Boolean,
    )

    data class Plan(val settings: ShotSettings, val label: String, val hint: String, val needsTripod: Boolean)

    private class Rule(
        val frames: Int,
        val biasEv: Double,
        /** Longest exposure per frame, seconds. */
        val maxFrameS: Double,
        val isoCap: Int,
        val infinity: Boolean,
        val hint: String,
        val needsTripod: Boolean = false,
    )

    private fun rule(p: ScenePreset, steady: Boolean): Rule = when (p) {
        ScenePreset.NIGHT -> if (steady) {
            Rule(6, -0.5, 1.0, 3200, false, "Телефон неподвижен: кадры длиннее, шум уйдёт при сведении", needsTripod = true)
        } else {
            Rule(8, -0.5, 1.0 / 10, 3200, false, "С рук: 8 коротких кадров, при сведении уходят шум и случайные блики")
        }
        ScenePreset.CLOUDS -> Rule(6, -0.7, if (steady) 1.0 / 30 else 1.0 / 60, 800, true,
            "Светлые облака без пересвета: −0,7 EV, 6 кадров усредняются")
        ScenePreset.SUNSET -> Rule(4, -1.0, if (steady) 1.0 / 30 else 1.0 / 60, 800, true,
            "Небо и солнце без пересвета: −1 EV, 4 кадра")
        ScenePreset.MOON -> Rule(24, 0.0, 1.0 / 60, 400, true,
            "Приблизьте луну (кнопка 2 или телевик). Много коротких кадров, остаются самые резкие")
        ScenePreset.STARS -> Rule(1, 0.0, 1.0, 3200, true, "Режим АСТРО")
        ScenePreset.TRAILS -> Rule(20, 0.0, if (steady) 2.0 else 1.0 / 4, 800, true,
            if (steady) "Огни машин сложатся в линии" else "Нужен штатив или опора", needsTripod = true)
        ScenePreset.WATER -> Rule(if (steady) 24 else 16, 0.0, if (steady) 0.5 else 1.0 / 15, 400, false,
            if (steady) "Вода станет гладкой" else "С рук вода сгладится слабее — лучше опора", needsTripod = steady)
        ScenePreset.ACTION -> Rule(5, 0.0, 1.0 / 500, 3200, false, "Короткая выдержка, 5 кадров — остаётся самый резкий")
        ScenePreset.DOCUMENT -> Rule(3, 0.3, 1.0 / 30, 800, false, "3 кадра — остаётся самый резкий")
    }

    fun plan(preset: ScenePreset, i: Input): Plan {
        val r = rule(preset, i.steady)
        val focus = if (r.infinity && i.canFocusInfinity) FocusMode.INFINITY else FocusMode.AUTO
        val stack = if (r.frames > 1) preset.profile.stackMode else com.therealcam.data.StackMode.NONE
        val inf = if (focus == FocusMode.INFINITY) " · ∞" else ""
        val auto = ShotSettings(focus = focus, ev = r.biasEv.toFloat(), frames = r.frames, stackMode = stack)
        if (!i.manualSensor || i.minIso == null || i.maxIso == null || i.maxExpNs == null) {
            val ev = if (r.biasEv != 0.0) " · EV%+.1f".format(r.biasEv) else ""
            return Plan(auto, "${r.frames} кадров · авто$ev$inf", r.hint, r.needsTripod)
        }
        val minExpS = (i.minExpNs ?: 0L) / 1e9
        val minIso = i.minIso
        val isoCap = min(i.maxIso, r.isoCap).coerceAtLeast(minIso)
        // Exposure × ISO that gives the target brightness, in seconds·ISO.
        val need: Double = if (preset == ScenePreset.MOON) {
            val n = (i.aperture ?: 1.8f).toDouble()
            n * n / 2.0.pow(MOON_EV100) * 100.0
        } else {
            val e = i.previewExpNs ?: return Plan(auto, "${r.frames} кадров · авто$inf", r.hint, r.needsTripod)
            val s = i.previewIso ?: return Plan(auto, "${r.frames} кадров · авто$inf", r.hint, r.needsTripod)
            // AE may not reach its target in the dark; the preview mean tells how far off it is.
            val k = i.previewMeanLin?.takeIf { it > 0.0 }?.let { (TARGET_LIN / it).coerceIn(1.0 / 8, 16.0) } ?: 1.0
            e / 1e9 * s * k * 2.0.pow(r.biasEv)
        }
        val maxS = min(r.maxFrameS, i.maxExpNs / 1e9)
        val expS = (need / minIso).coerceIn(max(minExpS, 1e-6), max(maxS, minExpS))
        val iso = (need / expS).roundToInt().coerceIn(minIso, isoCap)
        val expNs = (expS * 1e9).roundToLong()
        val settings = ShotSettings(iso = iso, exposureNs = expNs, focus = focus, frames = r.frames, stackMode = stack)
        val frames = if (r.frames > 1) "${r.frames} × " else ""
        return Plan(settings, "$frames${ExposureScale.formatShutter(expNs)} · ISO$iso$inf", r.hint, r.needsTripod)
    }

    /** A user preset as saved, limited to what this camera can do. */
    fun planUser(u: UserPreset, i: Input): Plan {
        var s = u.settings()
        val iso = s.iso
        val exp = s.exposureNs
        s = if (!i.manualSensor || iso == null || exp == null || i.minIso == null || i.maxIso == null || i.maxExpNs == null) {
            s.copy(iso = null, exposureNs = null)
        } else {
            s.copy(iso = iso.coerceIn(i.minIso, i.maxIso), exposureNs = exp.coerceIn(i.minExpNs ?: 0L, i.maxExpNs))
        }
        if (s.focus != FocusMode.AUTO && !i.canFocusInfinity) s = s.copy(focus = FocusMode.AUTO)
        val expS = (s.exposureNs ?: 0L) / 1e9
        val tripod = expS >= 1.0 / 15 || (s.frames > 1 && expS * s.frames >= 1.0)
        val label = buildString {
            if (s.frames > 1) append("${s.frames} × ")
            append(s.exposureNs?.let { ExposureScale.formatShutter(it) } ?: "авто")
            s.iso?.let { append(" · ISO$it") }
            if (s.focus == FocusMode.INFINITY) append(" · ∞")
            if (s.exposureNs == null && s.ev != 0f) append(" · EV%+.1f".format(s.ev))
        }
        return Plan(s, label, "Ваш пресет «${u.name}». Удалить — долгое нажатие на значок", tripod)
    }
}
