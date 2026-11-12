package com.byeolmajung.app

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

/**
 * Reads star descriptions and stories aloud.
 *
 * Uses the Pleos TextToSpeech SDK in on-device mode, which speaks through the car's own voice service
 * (no app registration or network needed). Pleos cars and the Pleos emulator have no platform
 * text-to-speech engine, so this is the only voice available there. The rest of the app only sees
 * [speak], [stop] and [onSpeakingChanged].
 */
class Speaker(context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var failed = false
    private var pending: String? = null

    /** Called on the main thread when speech starts (true) or finishes or is stopped (false). */
    var onSpeakingChanged: ((Boolean) -> Unit)? = null

    /** Called on the main thread when something was asked to be read but this car cannot speak. */
    var onUnavailable: (() -> Unit)? = null

    var isSpeaking = false
        private set

    private val tts: TextToSpeech? = runCatching { TextToSpeech(context.applicationContext, Mode.ON_DEVICE) }
        .onFailure { Log.w(TAG, "Pleos text-to-speech unavailable", it) }
        .getOrNull()

    private val events = object : EventListener {
        override fun onStart() = changed(true)
        override fun onReady() {}
        override fun onDone() = changed(false)
        override fun onStop() = changed(false)
        override fun onUpdatedRms(rms: Double) {}

        override fun onError(error: ErrorType) {
            Log.w(TAG, "text-to-speech error: $error")
            changed(false)
        }
    }

    init {
        val engine = tts
        if (engine == null) {
            failed = true
        } else {
            // The SDK creates its engine in initialize(), so listeners can only be added after it.
            runCatching {
                engine.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        main.post { onReady() }
                    }

                    override fun onFailure(error: ErrorType) {
                        main.post { onFailed("initialize: $error") }
                    }
                })
                engine.addEventListener(events)
            }.onFailure { onFailed("initialize: $it") }
        }
    }

    fun speak(text: String) {
        when {
            failed -> main.post { onUnavailable?.invoke() }
            !ready -> pending = text
            else -> runCatching { tts?.speak(text) }.onFailure {
                Log.w(TAG, "speak failed", it)
                changed(false)
            }
        }
    }

    fun stop() {
        pending = null
        if (ready) runCatching { tts?.stop() }
        changed(false)
    }

    fun release() {
        stop()
        runCatching {
            tts?.removeEventListener(events)
            tts?.release()
        }
    }

    private fun onReady() {
        // The on-device engine needs language and country ("ko-KR"); Locale.KOREAN alone is rejected as "ko-".
        runCatching { tts?.setLocale(Locale.KOREA) }.onFailure { Log.w(TAG, "setLocale", it) }
        ready = true
        pending?.let { speak(it) }
        pending = null
    }

    private fun onFailed(reason: String) {
        Log.w(TAG, "text-to-speech unavailable: $reason")
        failed = true
        if (pending != null) {
            pending = null
            onUnavailable?.invoke()
        }
    }

    private fun changed(speaking: Boolean) {
        main.post {
            if (isSpeaking == speaking) return@post
            isSpeaking = speaking
            onSpeakingChanged?.invoke(speaking)
        }
    }

    private companion object {
        const val TAG = "Byeolmajung"
    }
}
