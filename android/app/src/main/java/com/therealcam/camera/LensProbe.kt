package com.therealcam.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build

/** Reads [LensSpec]s of the listed cameras, of hidden modules and of the physical cameras behind logical ones. */
object LensProbe {
    class Specs(val listed: List<LensSpec>, val hidden: List<LensSpec>, val physical: Map<String, LensSpec>) {
        fun lenses(front: Boolean) = LensCatalog.build(listed + hidden, physical, front)
    }

    /**
     * Ids tried besides getCameraIdList(). Xiaomi/Redmi/POCO and some other vendors list only the first back
     * and front camera to third-party apps, while the other modules (usually ids 2–5) still answer by id.
     */
    private val PROBE_IDS = (0..15).map { "$it" } + (20..25).map { "$it" } + (60..63).map { "$it" } + (100..102).map { "$it" }

    fun read(manager: CameraManager): Specs {
        val listedIds = manager.cameraIdList.toList()
        val listed = listedIds.mapNotNull { id ->
            runCatching { spec(manager.getCameraCharacteristics(id), id) }.getOrNull()
        }
        val physical = HashMap<String, LensSpec>()
        for (l in listed) for (pid in l.physicalIds) {
            if (pid in physical) continue
            // Physical ids of a logical camera are queryable even when they are not listed (API 28+).
            runCatching { spec(manager.getCameraCharacteristics(pid), pid) }.getOrNull()?.let { physical[pid] = it }
        }
        val hidden = PROBE_IDS.filter { it !in listedIds && it !in physical }.mapNotNull { id ->
            runCatching { spec(manager.getCameraCharacteristics(id), id).copy(hidden = true) }.getOrNull()
        }
        return Specs(listed, hidden, physical)
    }

    private fun spec(c: CameraCharacteristics, id: String): LensSpec {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val fw = if (pixels != null && active != null && pixels.width > 0) active.width().toFloat() / pixels.width else 1f
        val fh = if (pixels != null && active != null && pixels.height > 0) active.height().toFloat() / pixels.height else 1f
        val logical = Build.VERSION.SDK_INT >= 28 &&
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps
        val mono = Build.VERSION.SDK_INT >= 28 && CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME in caps
        val cfa = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
        val monoCfa = Build.VERSION.SDK_INT >= 29 &&
            (cfa == CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO || cfa == CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR)
        val widthPx = active?.width() ?: pixels?.width ?: 0
        val heightPx = active?.height() ?: pixels?.height ?: 0
        // Small depth modules often also claim colour output; they are never offered as a lens.
        val depth = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT in caps && widthPx.toLong() * heightPx < 5_000_000L
        val physicalIds = if (logical && Build.VERSION.SDK_INT >= 28) c.physicalCameraIds.toList() else emptyList()
        val zr = if (Build.VERSION.SDK_INT >= 30) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        return LensSpec(
            id = id,
            front = c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT,
            focalsMm = (c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: FloatArray(0)).sorted(),
            activeWidthMm = (size?.width ?: 0f) * fw,
            activeHeightMm = (size?.height ?: 0f) * fh,
            activeWidthPx = widthPx,
            activeHeightPx = heightPx,
            usable = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE in caps && !mono && !monoCfa && !depth,
            logical = logical,
            physicalIds = physicalIds,
            zoomRatioRange = zr?.let { it.lower..it.upper },
            maxDigitalZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f,
            fixedFocus = (c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) <= 0f,
        )
    }
}
