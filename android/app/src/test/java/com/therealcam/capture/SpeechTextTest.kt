package com.therealcam.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextTest {
    @Test
    fun russianPlurals() {
        assertEquals("1 минута", SpeechText.duration(60))
        assertEquals("2 минуты", SpeechText.duration(120))
        assertEquals("5 минут", SpeechText.duration(300))
        assertEquals("21 минута", SpeechText.duration(21 * 60))
        assertEquals("11 минут", SpeechText.duration(11 * 60))
        assertEquals("3 минуты 30 секунд", SpeechText.duration(210))
        assertEquals("30 секунд", SpeechText.duration(30))
        assertEquals("Осталось 1 минута 30 секунд", SpeechText.remaining(90))
        assertEquals("Снимаю звёзды, примерно 4 минуты. Не трогайте телефон.", SpeechText.start(247, astro = true))
    }

    @Test
    fun countdownSpeaksEveryThirtySeconds() {
        var step = SpeechText.tick(247_000, Int.MAX_VALUE).first
        assertEquals(9, step)
        val spoken = mutableListOf<Int>()
        var ms = 247_000L
        while (ms >= 0) {
            val (s, say) = SpeechText.tick(ms, step)
            step = s
            say?.let { spoken += it }
            ms -= 500
        }
        assertEquals(listOf(240, 210, 180, 150, 120, 90, 60, 30), spoken)
    }

    @Test
    fun growingEstimateDoesNotRepeat() {
        val (s1, say1) = SpeechText.tick(170_000, 7)
        assertEquals(6, s1)
        assertEquals(180, say1)
        // The estimate grows back above 3 minutes: no announcement, step moves up.
        val (s2, say2) = SpeechText.tick(185_000, s1)
        assertEquals(7, s2)
        assertEquals(null, say2)
    }
}
