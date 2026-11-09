package com.therealcam.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import com.therealcam.ai.AstroPlanner
import com.therealcam.camera.CameraMode
import com.therealcam.camera.Lens
import com.therealcam.camera.LensCatalog
import com.therealcam.camera.LensKind
import com.therealcam.camera.LensSpec
import com.therealcam.ui.camera.AstroControls
import com.therealcam.ui.camera.ModeSwitcher
import com.therealcam.ui.camera.ZoomBar
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
}
