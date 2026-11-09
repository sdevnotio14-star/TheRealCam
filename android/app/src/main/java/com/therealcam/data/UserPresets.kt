package com.therealcam.data

import com.therealcam.camera.FocusMode
import com.therealcam.camera.ShotSettings
import com.therealcam.camera.WbPreset
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A manual setup saved by the user; shown in the СЦЕНЫ mode next to the built-in scenes. */
@Serializable
data class UserPreset(
    val id: String,
    val name: String,
    val icon: String,
    val iso: Int? = null,
    val exposureNs: Long? = null,
    val focus: String = "auto",
    val focusDiopters: Float = 0f,
    val wb: String = "auto",
    val ev: Float = 0f,
    val frames: Int = 1,
    val stackMode: String = "none",
    /** Merge profile (alignment, outlier rejection) taken from the category chosen when saving. */
    val profile: String = "default",
) {
    fun settings() = ShotSettings(
        iso = iso, exposureNs = exposureNs,
        focus = FocusMode.entries.firstOrNull { it.key == focus } ?: FocusMode.AUTO,
        focusDiopters = focusDiopters,
        wb = WbPreset.entries.firstOrNull { it.key == wb } ?: WbPreset.AUTO,
        ev = ev, frames = frames.coerceAtLeast(1), stackMode = StackMode.of(stackMode),
    )

    companion object {
        fun from(id: String, name: String, icon: String, s: ShotSettings, profile: Profile) = UserPreset(
            id = id, name = name, icon = icon, iso = s.iso, exposureNs = s.exposureNs, focus = s.focus.key,
            focusDiopters = s.focusDiopters, wb = s.wb.key, ev = s.ev, frames = s.frames, stackMode = s.stackMode.key,
            profile = profile.key,
        )
    }
}

object UserPresets {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(s: String?): List<UserPreset> =
        if (s.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString<List<UserPreset>>(s) }.getOrDefault(emptyList())

    fun encode(list: List<UserPreset>): String = json.encodeToString(list)
}
