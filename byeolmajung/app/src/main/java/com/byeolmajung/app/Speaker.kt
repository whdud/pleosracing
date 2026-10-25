package com.byeolmajung.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Reads star descriptions and stories aloud.
 *
 * Uses the platform text-to-speech engine. The Pleos TextToSpeech SDK can take its place once it is
 * enabled for the project (it is listed as "coming soon" in Pleos Playground); the rest of the app only
 * sees [speak], [stop] and [onSpeakingChanged].
 */
class Speaker(context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var pending: String? = null

    /** Called on the main thread when speech starts (true) or finishes or is stopped (false). */
    var onSpeakingChanged: ((Boolean) -> Unit)? = null

    var isSpeaking = false
        private set

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (!ready) {
            Log.w("Byeolmajung", "text-to-speech unavailable: $status")
            return@TextToSpeech
        }
        tts.language = Locale.KOREAN
        tts.setSpeechRate(0.95f)
        pending?.let { speak(it) }
        pending = null
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = changed(true)
            override fun onDone(utteranceId: String?) = changed(false)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = changed(false)
        })
    }

    fun speak(text: String) {
        if (!ready) {
            pending = text
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE)
    }

    fun stop() {
        pending = null
        if (ready) tts.stop()
        changed(false)
    }

    fun release() {
        stop()
        tts.shutdown()
    }

    private fun changed(speaking: Boolean) {
        main.post {
            if (isSpeaking == speaking) return@post
            isSpeaking = speaking
            onSpeakingChanged?.invoke(speaking)
        }
    }

    private companion object {
        const val UTTERANCE = "byeolmajung"
    }
}
