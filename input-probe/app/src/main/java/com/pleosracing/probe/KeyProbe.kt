package com.pleosracing.probe

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent

/**
 * Records every hardware key that reaches the app: steering wheel buttons, CCP keys, etc.
 *
 * Two paths are watched because AAOS routes keys differently:
 * - Activity key dispatch: keys delivered to the focused window.
 * - MediaSession: media keys (next/previous/play) are routed to the active media session instead.
 *   The session is opt-in because it can take media-button routing away from the radio/music app.
 */
class KeyProbe(context: Context, private val board: ProbeBoard) {

    private val session = MediaSession(context, "PleosInputProbe").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                @Suppress("DEPRECATION")
                val event: KeyEvent? = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                event?.let { record("MediaSession", it) }
                return true
            }
        })
    }

    init {
        board.define(LAST_KEY, SECTION, "마지막 키 (Activity)")
        board.define(LAST_MEDIA_KEY, SECTION, "마지막 키 (MediaSession)")
        board.define(SESSION, SECTION, "미디어 세션")
        board.value(SESSION, "꺼짐")
    }

    val isSessionActive get() = session.isActive

    fun setSessionActive(active: Boolean) {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(if (active) PlaybackState.STATE_PLAYING else PlaybackState.STATE_STOPPED, 0, 1f)
                .setActions(PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_PLAY_PAUSE)
                .build(),
        )
        session.isActive = active
        board.value(SESSION, if (active) "켜짐 (미디어 키 수신 중)" else "꺼짐")
        board.log("MediaSession active=$active")
    }

    /** Call from Activity.dispatchKeyEvent. */
    fun onActivityKey(event: KeyEvent) = record("Activity", event)

    fun release() = session.release()

    private fun record(path: String, event: KeyEvent) {
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount > 0) "REPEAT" else "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            else -> "${event.action}"
        }
        val text = "${KeyEvent.keyCodeToString(event.keyCode)} $action"
        board.value(if (path == "Activity") LAST_KEY else LAST_MEDIA_KEY, text)
        board.log(
            "[key/$path] $text code=${event.keyCode} scan=${event.scanCode} " +
                "device=${event.deviceId} source=0x%x".format(event.source),
        )
    }

    companion object {
        private const val SECTION = "버튼 입력"
        private const val LAST_KEY = "key.activity"
        private const val LAST_MEDIA_KEY = "key.media"
        private const val SESSION = "key.session"
    }
}
