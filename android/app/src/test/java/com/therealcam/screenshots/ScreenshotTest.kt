package com.therealcam.screenshots

import android.graphics.Rect
import android.hardware.camera2.CameraMetadata
import android.util.Range
import android.util.Rational
import android.util.Size
import android.util.SizeF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import com.therealcam.ai.AstroPlanner
import com.therealcam.camera.CameraCaps
import com.therealcam.camera.CameraMode
import com.therealcam.camera.Dim
import com.therealcam.camera.Lens
import com.therealcam.camera.LensCatalog
import com.therealcam.camera.LensKind
import com.therealcam.camera.LensSpec
import com.therealcam.camera.ShotSettings
import com.therealcam.camera.VideoOptions
import com.therealcam.camera.VideoQuality
import com.therealcam.camera.VideoSpec
import com.therealcam.capture.PresetPlanner
import com.therealcam.capture.ScenePreset
import com.therealcam.data.UserPreset
import com.therealcam.ui.camera.AstroControls
import com.therealcam.ui.camera.Histogram
import com.therealcam.ui.camera.HistogramOverlay
import com.therealcam.ui.camera.ManualControls
import com.therealcam.ui.camera.ModeSwitcher
import com.therealcam.ui.camera.PresetControls
import com.therealcam.ui.camera.RecordButton
import com.therealcam.ui.camera.RecordingBadge
import com.therealcam.ui.camera.SavePresetButton
import com.therealcam.ui.camera.VideoOptionsRow
import com.therealcam.ui.camera.ZoomBar
import com.therealcam.ui.camera.controlsArea
import com.therealcam.ui.camera.previewArea
import com.therealcam.ui.common.GuideScreen
import com.therealcam.ui.onboarding.OnboardingScreen
import com.therealcam.ui.theme.RealColors
import com.therealcam.ui.theme.RealTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders key screens to app/build/screenshots (run with -Pscreenshots). Camera preview is a gradient here. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h851dp-xxhdpi")
class ScreenshotTest {
    private fun shot(name: String, content: @Composable () -> Unit) =
        captureRoboImage("build/screenshots/$name.png") { RealTheme { content() } }

    @Test
    fun onboardingWelcome() = shot("onboarding_1_welcome") {
        OnboardingScreen(importedModel = null, importError = null, importBusy = false, onImportModel = {}, onFinish = {})
    }

    @Test
    fun onboardingChoice() = shot("onboarding_2_choice") {
        OnboardingScreen(
            importedModel = "«Звёзды и луна» · категорий: 5", importError = null, importBusy = false,
            onImportModel = {}, onFinish = {}, startPage = 1, datasetInitially = true,
        )
    }

    private fun spec(
        id: String, focal: Float, w: Float, h: Float, mp: Pair<Int, Int>, logical: Boolean = false,
        physical: List<String> = emptyList(), zr: ClosedFloatingPointRange<Float>? = null, fixedFocus: Boolean = false,
    ) = LensSpec(id, false, listOf(focal), w, h, mp.first, mp.second, true, logical, physical, zr, 8f, fixedFocus)

    private val samsungLike = LensCatalog.build(
        listOf(
            spec("0", 5.4f, 7.2f, 5.4f, 4000 to 3000, zr = 1f..8f),
            spec("2", 2.2f, 5.6f, 4.2f, 4000 to 3000, zr = 1f..4f, fixedFocus = true),
            spec("3", 6.3f, 4.0f, 3.0f, 3648 to 2736, zr = 1f..4f),
        ),
        emptyMap(), front = false,
    )
    private val pixelLike = LensCatalog.build(
        listOf(spec("0", 6.9f, 9.8f, 7.35f, 8160 to 6144, logical = true, physical = listOf("2", "3", "4"), zr = 0.5f..20f)),
        mapOf(
            "2" to spec("2", 6.9f, 9.8f, 7.35f, 8160 to 6144),
            "3" to spec("3", 2.0f, 6.17f, 4.55f, 4000 to 3000, fixedFocus = true),
            "4" to spec("4", 19.7f, 5.6f, 4.2f, 8000 to 6000),
        ),
        front = false,
    )
    private val budgetLike = LensCatalog.build(
        listOf(
            spec("0", 4.7f, 6.4f, 4.8f, 8160 to 6144),
            spec("2", 1.8f, 2.24f, 1.68f, 1600 to 1200, fixedFocus = true),
        ),
        emptyMap(), front = false,
    )

    @Composable
    private fun FakePreview(title: String, lenses: List<Lens>, selected: Lens?, zoom: Float) {
        Column(Modifier.background(Color.Black).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(6.dp))
            Box(
                Modifier.fillMaxWidth().aspectRatio(3f / 4f)
                    .background(Brush.verticalGradient(listOf(Color(0xFF6E8FB5), Color(0xFFD9C9A8), Color(0xFF3B3A2E)))),
            ) {
                ZoomBar(lenses, selected, zoom, 0f, onLens = {}, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
            }
        }
    }

    @Test
    fun zoomSeparateCameras() = shot("zoom_1_separate_ids") {
        FakePreview("Отдельные камеры (как у Samsung): выбрана основная", samsungLike, samsungLike.first { it.kind == LensKind.MAIN }, 1f)
    }

    @Test
    fun zoomLogicalCamera() = shot("zoom_2_logical") {
        FakePreview("Логическая камера (как у Pixel): жест до 1.4×", pixelLike, pixelLike.first { it.kind == LensKind.MAIN }, 1.4f)
    }

    @Test
    fun zoomBudgetMacro() = shot("zoom_3_budget_macro") {
        FakePreview("Бюджетный: основная + макро, выбрано макро", budgetLike, budgetLike.first { it.kind == LensKind.MACRO }, 1.09f)
    }

    private fun astroPlan(maxExpS: Double) = AstroPlanner.plan(
        AstroPlanner.Input(
            durationS = 240, manualSensor = true, focalMm = 5.4f, aperture = 1.8f, pixelPitchUm = 1.8f, equivMm = 26f,
            digitalZoom = 1f, minExpNs = 100_000L, maxExpNs = (maxExpS * 1e9).toLong(), minIso = 50, maxIso = 6400,
            canFocusInfinity = true, previewExpNs = 100_000_000L, previewIso = 3200, previewMeanLin = 0.004,
        ),
    )

    @Composable
    private fun AstroPanel(title: String, plan: AstroPlanner.Plan, steady: Boolean, voice: Boolean) {
        Column(Modifier.background(Color.Black).fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = RealColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            AstroControls(plan, 240, voice, steady, onDuration = {}, onVoice = {})
            ModeSwitcher(CameraMode.ASTRO, enabled = true, onMode = {})
        }
    }

    @Test
    fun astroControls() = shot("astro_controls") {
        Column(Modifier.background(Color.Black).fillMaxWidth()) {
            AstroPanel("Штатив, сенсор до 30 с", astroPlan(30.0), steady = true, voice = true)
            AstroPanel("В руках, голос выключен", astroPlan(30.0), steady = false, voice = false)
            AstroPanel("Бюджетный сенсор: выдержка до 0,5 с", astroPlan(0.5), steady = true, voice = true)
        }
    }

    @Test
    fun presetControls() = shot("preset_controls") {
        Column(Modifier.background(Color.Black).fillMaxWidth().padding(vertical = 10.dp)) {
            PresetControls(ScenePreset.NIGHT, PresetPlanner.plan(ScenePreset.NIGHT, presetInput(steady = false)), steady = false, orientation = 0f, onPreset = {})
            ModeSwitcher(CameraMode.PRESET, enabled = true, onMode = {})
        }
    }

    private fun presetInput(steady: Boolean) = PresetPlanner.Input(
        manualSensor = true, minExpNs = 100_000L, maxExpNs = 30_000_000_000L, minIso = 50, maxIso = 6400,
        canFocusInfinity = true, aperture = 1.8f, previewExpNs = 100_000_000L, previewIso = 3200,
        previewMeanLin = 0.02, steady = steady,
    )

    @Test
    fun userPresets() = shot("preset_user") {
        val mine = listOf(
            UserPreset("u1", "Мост ночью", "city_night", iso = 400, exposureNs = 2_000_000_000L, frames = 4),
            UserPreset("u2", "Кот дома", "pet", iso = 1600, exposureNs = 8_000_000L),
        )
        Column(Modifier.background(Color.Black).fillMaxWidth().padding(vertical = 10.dp)) {
            PresetControls(
                ScenePreset.NIGHT, PresetPlanner.planUser(mine[0], presetInput(steady = true)), steady = true, orientation = 0f,
                onPreset = {}, userPresets = mine, selectedUser = "u1",
            )
            ModeSwitcher(CameraMode.PRESET, enabled = true, onMode = {})
        }
    }

    @Test
    fun guide() = shot("guide") { GuideScreen(onBack = {}) }

    // ---------------------------------------------------------------- manual mode (pro mode): histogram

    private fun gray(v: Double): Int {
        val g = (v.coerceIn(0.0, 1.0) * 255).toInt()
        return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }

    private fun histogram(clipped: Double): FloatArray {
        val r = java.util.Random(7)
        val n = 20_000
        return Histogram.of(IntArray(n) { i -> if (i < n * clipped) gray(1.0) else gray(0.35 + 0.15 * r.nextGaussian()) })
    }

    private val manualCaps = CameraCaps(
        id = "0", facing = CameraMetadata.LENS_FACING_BACK, hardwareLevel = CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
        manualSensor = true, sensorOrientation = 90, isoRange = Range(50, 6400), exposureRange = Range(100_000L, 30_000_000_000L),
        maxAnalogIso = 800, minFocusDistance = 10f, focalLengths = floatArrayOf(5.4f), apertures = floatArrayOf(1.8f),
        physicalSize = SizeF(7.2f, 5.4f), pixelArray = Size(4000, 3000), activeArray = Rect(0, 0, 4000, 3000),
        aeCompRange = Range(-12, 12), aeCompStep = Rational(1, 6), flash = true,
        awbModes = intArrayOf(
            CameraMetadata.CONTROL_AWB_MODE_AUTO, CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
        ),
        afModes = intArrayOf(CameraMetadata.CONTROL_AF_MODE_OFF, CameraMetadata.CONTROL_AF_MODE_AUTO, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE),
        maxAfRegions = 1, maxAeRegions = 1, jpegSize = Size(4000, 3000), previewSize = Size(1440, 1080),
        jpegMinFrameNs = 33_333_333L, timestampRealtime = true, noiseReductionModes = intArrayOf(0, 1, 2),
        zoomRatioRange = Range(1f, 8f), maxDigitalZoom = 8f,
    )

    @Test
    fun manualHistogram() = shot("manual_histogram") {
        Column(Modifier.background(Color.Black).fillMaxWidth()) {
            Box(
                Modifier.fillMaxWidth().height(240.dp)
                    .background(Brush.verticalGradient(listOf(Color(0xFFF7F4EC), Color(0xFFD9C9A8), Color(0xFF3B3A2E)))),
            ) {
                HistogramOverlay(histogram(0.04), Modifier.align(Alignment.TopStart).padding(10.dp))
                HistogramOverlay(histogram(0.0), Modifier.align(Alignment.TopEnd).padding(10.dp))
                Text(
                    "слева: 4 % пересвета · справа: без пересвета", color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
                )
            }
            ManualControls(manualCaps, ShotSettings(iso = 400, exposureNs = 250_000_000L), 0.3, {}, {}, {}, {})
            SavePresetButton { _, _ -> }
            ModeSwitcher(CameraMode.MANUAL, enabled = true, onMode = {})
        }
    }

    // ---------------------------------------------------------------- VIDEO

    private val flagshipVideo = VideoOptions(VideoQuality.entries, setOf(VideoQuality.FHD, VideoQuality.UHD), stabilization = true)
    private val budgetVideo = VideoOptions(listOf(VideoQuality.HD, VideoQuality.FHD), emptySet(), stabilization = false)

    private fun videoSpec(q: VideoQuality, fps: Int, stab: Boolean) =
        VideoSpec(q, Dim(q.width, q.height), fps, bitRate = 0, aeFps = fps to fps, preview = Dim(1920, 1080), stabilization = stab)

    /** Same structure as CameraScreen in VIDEO: the 9:16 preview shrinks to the height the controls leave. */
    @Composable
    private fun FakeVideoScreen(recording: Boolean, options: VideoOptions, spec: VideoSpec) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Spacer(Modifier.fillMaxWidth().height(48.dp))
            Box(previewArea(video = true)) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF6E8FB5), Color(0xFFD9C9A8), Color(0xFF3B3A2E)))))
                ZoomBar(
                    samsungLike, samsungLike.first { it.kind == LensKind.MAIN }, 1f, 0f, onLens = {},
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                )
                if (recording) RecordingBadge(83, Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
            }
            Column(controlsArea(video = true), verticalArrangement = Arrangement.SpaceEvenly) {
                VideoOptionsRow(options, spec, recording, mic = true, rot = 0f, onQuality = {}, onFps = {}, onStabilization = {})
                ModeSwitcher(CameraMode.VIDEO, enabled = !recording, onMode = {})
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(52.dp).clip(CircleShape).background(RealColors.SurfaceHigh))
                    RecordButton(recording, onClick = {})
                    Box(Modifier.size(52.dp).clip(CircleShape).background(RealColors.SurfaceHigh))
                }
            }
        }
    }

    @Test
    fun videoIdle() = shot("video_1_idle") {
        FakeVideoScreen(recording = false, flagshipVideo, videoSpec(VideoQuality.UHD, 60, stab = true))
    }

    @Test
    fun videoRecording() = shot("video_2_recording") {
        FakeVideoScreen(recording = true, flagshipVideo, videoSpec(VideoQuality.FHD, 30, stab = true))
    }

    @Composable
    private fun VideoPanel(title: String, options: VideoOptions, spec: VideoSpec, mic: Boolean, width: Dp = 393.dp, fontScale: Float = 1f) {
        Column(Modifier.background(Color.Black).width(width).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = RealColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                VideoOptionsRow(options, spec, recording = false, mic, rot = 0f, onQuality = {}, onFps = {}, onStabilization = {})
            }
        }
    }

    @Test
    fun videoVariants() = shot("video_3_variants") {
        Column(Modifier.background(Color.Black).fillMaxWidth()) {
            VideoPanel("Бюджетный: без 60 к/с и стабилизации, микрофон запрещён", budgetVideo, videoSpec(VideoQuality.FHD, 30, false), mic = false)
            VideoPanel("Флагман, узкий экран 360 dp", flagshipVideo, videoSpec(VideoQuality.UHD, 60, true), mic = true, width = 360.dp)
            VideoPanel("Флагман, крупный шрифт ×1.3", flagshipVideo, videoSpec(VideoQuality.UHD, 60, true), mic = true, fontScale = 1.3f)
        }
    }
}
