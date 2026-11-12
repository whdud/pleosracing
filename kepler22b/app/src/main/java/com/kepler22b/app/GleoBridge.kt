package com.kepler22b.app

import android.content.Context
import android.content.Intent
import android.util.Log
import com.kepler22b.sky.Gleo
import org.json.JSONObject

/**
 * Android side of the Gleo Interface.
 *
 * Gleo launches the app with an Intent whose action is the function name (see assets/schema.json and the
 * intent filters in the manifest) and the parameters as JSON in the `ACTION_PARAM` extra. The app answers
 * with the `APP_ACTION_RESPONSE` broadcast within 7 seconds.
 */
object GleoBridge {

    private const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"

    data class Request(val action: Gleo.Action, val params: JSONObject) {
        fun string(name: String): String = params.optString(name, "").trim()
    }

    /** The Gleo request carried by [intent], or null for a normal launch. */
    fun parse(intent: Intent?): Request? {
        val action = intent?.action?.let(Gleo.Action::of) ?: return null
        val raw = intent.getStringExtra("ACTION_PARAM").orEmpty()
        return Request(action, parseParams(raw))
    }

    /** The Pleos sample sends the JSON wrapped in an extra pair of quotes; accept both forms. */
    internal fun parseParams(raw: String): JSONObject {
        var text = raw.trim()
        if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length - 1).replace("\\\"", "\"")
        }
        return runCatching { JSONObject(text.ifEmpty { "{}" }) }.getOrElse {
            Log.w("Kepler22b", "bad Gleo ACTION_PARAM: $raw")
            JSONObject()
        }
    }

    fun respond(context: Context, response: Gleo.Response) {
        runCatching {
            context.sendBroadcast(Intent(RESPONSE_ACTION).apply {
                putExtra("packageName", context.packageName)
                putExtra("response", response.toJson())
            })
            Log.d("Kepler22b", "Gleo response: ${response.toJson()}")
        }.onFailure { Log.w("Kepler22b", "Gleo response failed", it) }
    }
}
