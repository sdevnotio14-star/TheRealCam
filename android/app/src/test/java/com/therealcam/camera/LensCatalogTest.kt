package com.therealcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensCatalogTest {
    private fun spec(
        id: String, focal: Float, wMm: Float, hMm: Float, wPx: Int, hPx: Int,
        front: Boolean = false, usable: Boolean = true, logical: Boolean = false,
        physical: List<String> = emptyList(), zr: ClosedFloatingPointRange<Float>? = null,
        maxDigital: Float = 8f, fixedFocus: Boolean = false, focals: List<Float> = listOf(focal),
    ) = LensSpec(id, front, focals, wMm, hMm, wPx, hPx, usable, logical, physical, zr, maxDigital, fixedFocus)

    private val front = spec("1", 2.7f, 4.8f, 3.6f, 3648, 2736, front = true)

    // Pixel-like: one logical back camera, lenses switched by CONTROL_ZOOM_RATIO.
    private val pixelMain = spec("2", 6.9f, 9.8f, 7.35f, 8160, 6144)
    private val pixelUw = spec("3", 2.0f, 6.17f, 4.55f, 4000, 3000, fixedFocus = true)
    private val pixelTele = spec("4", 19.7f, 5.6f, 4.2f, 8000, 6000)
    private val pixelLogical = spec(
        "0", 2.0f, 9.8f, 7.35f, 8160, 6144, logical = true, physical = listOf("2", "3", "4"),
        zr = 0.5f..20f, focals = listOf(2.0f, 6.9f, 19.7f),
    )

    @Test
    fun logicalCameraExposesItsPhysicalLensesThroughZoomRatio() {
        val lenses = LensCatalog.build(
            listOf(pixelLogical, front), mapOf("2" to pixelMain, "3" to pixelUw, "4" to pixelTele), front = false,
        )
        assertEquals(listOf("0.5", "1", "5"), lenses.map { it.label })
        assertEquals(listOf(LensKind.ULTRAWIDE, LensKind.MAIN, LensKind.TELE), lenses.map { it.kind })
        assertTrue("all on the logical camera", lenses.all { it.cameraId == "0" })
        assertEquals(0.5f, lenses[0].baseRatio, 1e-4f)
        assertEquals(1f, lenses[0].zoomPerRatio, 1e-6f)
        assertEquals(24.4f, lenses[1].equivMm!!, 0.2f)
        assertEquals(0.5f..20f, ZoomMath.range(lenses))
    }

    @Test
    fun frontSideHasItsOwnMainLens() {
        val lenses = LensCatalog.build(listOf(pixelLogical, front), emptyMap(), front = true)
        assertEquals(1, lenses.size)
        assertEquals(LensKind.FRONT, lenses[0].kind)
        assertEquals("1", lenses[0].cameraId)
    }

    // Samsung-like: separate camera ids, no zoom-out on the main camera.
    private val samMain = spec("0", 5.4f, 7.2f, 5.4f, 4000, 3000, zr = 1f..8f)
    private val samUw = spec("2", 2.2f, 5.6f, 4.2f, 4000, 3000, zr = 1f..4f, fixedFocus = true)
    private val samTele = spec("3", 6.3f, 4.0f, 3.0f, 3648, 2736, zr = 1f..4f)

    @Test
    fun separateCameraIdsBecomeLensesWithNamesAndZoom() {
        val lenses = LensCatalog.build(listOf(samMain, front, samUw, samTele), emptyMap(), front = false)
        assertEquals(listOf("0.5", "1", "2"), lenses.map { it.label })
        assertEquals(listOf("2", "0", "3"), lenses.map { it.cameraId })
        assertEquals(listOf("Ультраширик", "Основная", "Телевик"), lenses.map { it.kind.title })
        val uw = lenses[0]
        assertEquals(1f, uw.baseRatio, 1e-6f)
        assertEquals(uw.zoom, uw.zoomPerRatio, 1e-6f)
        // Digital zoom on the ultra-wide: 0.8× of the main view is ratio 0.8 / 0.52.
        assertEquals(0.8f / uw.zoom, uw.ratioFor(0.8f), 1e-4f)
        assertTrue(uw.description.startsWith("0.5× Ультраширик"))
    }

    @Test
    fun lensForPicksTheLongestLensThatStillCoversTheView() {
        val lenses = LensCatalog.build(listOf(samMain, samUw, samTele), emptyMap(), front = false)
        assertEquals(LensKind.ULTRAWIDE, ZoomMath.lensFor(lenses, 0.7f)!!.kind)
        assertEquals(LensKind.ULTRAWIDE, ZoomMath.lensFor(lenses, 0.99f)!!.kind)
        assertEquals(LensKind.MAIN, ZoomMath.lensFor(lenses, 1f)!!.kind)
        assertEquals(LensKind.MAIN, ZoomMath.lensFor(lenses, 1.9f)!!.kind)
        assertEquals(LensKind.TELE, ZoomMath.lensFor(lenses, 2.5f)!!.kind)
        assertEquals(LensKind.ULTRAWIDE, ZoomMath.lensFor(lenses, 0.2f)!!.kind)
    }

    // Budget phone: 50 MP main, 2 MP macro, 2 MP mono depth sensor, no CONTROL_ZOOM_RATIO.
    @Test
    fun macroIsRecognisedAndDepthSensorIgnored() {
        val main = spec("0", 4.7f, 6.4f, 4.8f, 8160, 6144, maxDigital = 10f)
        val macro = spec("2", 1.8f, 2.24f, 1.68f, 1600, 1200, fixedFocus = true)
        val depth = spec("3", 1.8f, 2.24f, 1.68f, 1600, 1200, fixedFocus = true, usable = false)
        val lenses = LensCatalog.build(listOf(main, front, macro, depth), emptyMap(), front = false)
        assertEquals(listOf(LensKind.MAIN, LensKind.MACRO), lenses.map { it.kind })
        assertEquals("M", lenses[1].label)
        assertEquals(1f..10f, ZoomMath.range(lenses))
        assertEquals(LensKind.MAIN, ZoomMath.lensFor(lenses, 1f)!!.kind)
    }

    @Test
    fun hiddenPhysicalLensesStillGiveZoomOut() {
        val logical = spec("0", 4.7f, 6.4f, 4.8f, 8160, 6144, logical = true, zr = 0.6f..10f)
        val lenses = LensCatalog.build(listOf(logical), emptyMap(), front = false)
        assertEquals(listOf("0.6", "1"), lenses.map { it.label })
        assertEquals(LensKind.ULTRAWIDE, lenses[0].kind)
        assertEquals("0", lenses[0].cameraId)
        assertEquals(0.6f, lenses[0].baseRatio, 1e-6f)
    }

    @Test
    fun standaloneDuplicateOfALogicalLensIsSkipped() {
        val logical = spec(
            "0", 2.2f, 7.2f, 5.4f, 4000, 3000, logical = true, physical = listOf("20", "21"),
            zr = 0.6f..10f, focals = listOf(2.2f, 5.4f),
        )
        val wide = spec("20", 5.4f, 7.2f, 5.4f, 4000, 3000)
        val uw = spec("21", 2.2f, 5.6f, 4.2f, 4000, 3000, fixedFocus = true)
        val uwListed = uw.copy(id = "2")
        val lenses = LensCatalog.build(listOf(logical, uwListed), mapOf("20" to wide, "21" to uw), front = false)
        assertEquals(listOf("0.6", "1"), lenses.map { it.label })
        assertTrue(lenses.all { it.cameraId == "0" })
    }

    // Redmi 9T-like: only the main and front cameras are listed; ultra-wide, macro and depth answer by id.
    @Test
    fun hiddenXiaomiModulesBecomeLenses() {
        val main = spec("0", 4.71f, 6.4f, 4.8f, 8000, 6000, maxDigital = 10f)
        val uw = spec("2", 1.6f, 3.6f, 2.7f, 3264, 2448, fixedFocus = true).copy(hidden = true)
        val depth = spec("3", 1.8f, 2.24f, 1.68f, 1600, 1200, fixedFocus = true, usable = false).copy(hidden = true)
        val macro = spec("4", 1.8f, 2.24f, 1.68f, 1600, 1200, fixedFocus = true).copy(hidden = true)
        val lenses = LensCatalog.build(listOf(main, front, uw, depth, macro), emptyMap(), front = false)
        assertEquals(listOf("0.6", "1", "M"), lenses.map { it.label })
        assertEquals(listOf("2", "0", "4"), lenses.map { it.cameraId })
        assertEquals(listOf(true, false, true), lenses.map { it.hidden })
        // No telephoto: a digital 2× button is offered next to the lenses.
        assertEquals(listOf(2f), ZoomMath.digitalStops(lenses))
        assertTrue(ZoomMath.digitalStops(LensCatalog.build(listOf(samMain, samUw, samTele), emptyMap(), front = false)).isEmpty())
    }

    @Test
    fun singleOldCameraHasOnlyDigitalZoom() {
        val main = spec("0", 3.9f, 4.6f, 3.5f, 4160, 3120, maxDigital = 4f)
        val lenses = LensCatalog.build(listOf(main, front), emptyMap(), front = false)
        assertEquals(1, lenses.size)
        assertEquals(1f..4f, ZoomMath.range(lenses))
        assertEquals(3f, lenses[0].ratioFor(3f), 1e-6f)
        assertEquals(4f, lenses[0].ratioFor(9f), 1e-6f)
    }

    @Test
    fun noUsableCamera() {
        assertTrue(LensCatalog.build(emptyList(), emptyMap(), front = false).isEmpty())
        assertNull(ZoomMath.lensFor(emptyList(), 1f))
    }

    @Test
    fun labels() {
        assertEquals("0.5", ZoomMath.label(0.5f))
        assertEquals("0.6", ZoomMath.label(0.55f))
        assertEquals("1", ZoomMath.label(0.999f))
        assertEquals("1", ZoomMath.label(1f))
        assertEquals("2", ZoomMath.label(2.05f))
        assertEquals("2.5", ZoomMath.label(2.5f))
        assertEquals("10", ZoomMath.label(10f))
        assertEquals("1×", ZoomMath.display(1f))
        assertEquals("1.4×", ZoomMath.display(1.43f))
        assertEquals("0.6×", ZoomMath.display(0.6f))
        assertEquals("12×", ZoomMath.display(12.3f))
    }
}
