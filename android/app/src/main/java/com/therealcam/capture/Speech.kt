package com.therealcam.capture

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.math.ceil

/** Russian phrases for the voice countdown of long series. */
object SpeechText {
    /** Russian plural form: 1 минута, 2 минуты, 5 минут, 21 минута, 11 минут. */
    fun plural(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10
        val m100 = n % 100
        return when {
            m10 == 1 && m100 != 11 -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
    }

    /** "3 минуты 30 секунд", "1 минута", "30 секунд". */
    fun duration(totalS: Int): String {
        val m = totalS / 60
        val s = totalS % 60
        val parts = buildList {
            if (m > 0) add("$m ${plural(m, "минута", "минуты", "минут")}")
            if (s > 0 || m == 0) add("$s ${plural(s, "секунда", "секунды", "секунд")}")
        }
        return parts.joinToString(" ")
    }

    fun remaining(totalS: Int) = "Осталось ${duration(totalS)}"

    fun start(totalS: Int, astro: Boolean) =
        (if (astro) "Снимаю звёзды" else "Снимаю серию") + ", примерно ${duration(roundTo30(totalS))}. Не трогайте телефон."

    private fun roundTo30(s: Int) = if (s < 60) s else ((s + 15) / 30) * 30

    /**
     * Countdown in 30-second steps. Returns the new step and the seconds to announce when [remainingMs]
     * crosses a 30-second mark below [lastStep]; an estimate that grows only moves the step up.
     */
    fun tick(remainingMs: Long, lastStep: Int): Pair<Int, Int?> {
        val step = ceil(remainingMs.coerceAtLeast(0L) / 30_000.0).toInt()
        return if (step in 1 until lastStep) step to step * 30 else step to null
    }
}

/** Text-to-speech in Russian; silent when no Russian voice is installed. */
class VoiceAnnouncer(context: Context) {
    private var ready = false
    private var pending: String? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> onInit(status) }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val r = tts.setLanguage(Locale.forLanguageTag("ru-RU"))
        ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        if (ready) pending?.let { speakNow(it) }
        pending = null
    }

    fun say(text: String) {
        if (ready) speakNow(text) else pending = text
    }

    private fun speakNow(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "therealcam")
    }

    fun shutdown() {
        runCatching { tts.shutdown() }
    }
}
