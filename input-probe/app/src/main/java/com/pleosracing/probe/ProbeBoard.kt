package com.pleosracing.probe

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Thread-safe sink for probe results. Every source reports into here; the UI observes it.
 *
 * Each value row tracks how often it updates (Hz) so we can judge whether a signal is
 * usable as a real-time game input.
 */
class ProbeBoard {

    data class Row(
        val key: String,
        val section: String,
        val label: String,
        var value: String = "-",
        var detail: String = "",
        var updates: Int = 0,
        var hz: Double = 0.0,
        var lastUpdateMs: Long = 0,
    )

    interface Observer {
        fun onRowChanged(row: Row)
        fun onLog(line: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val rows = LinkedHashMap<String, Row>()
    private val recentUpdates = HashMap<String, ArrayDeque<Long>>()
    var observer: Observer? = null

    fun define(key: String, section: String, label: String) = onMain {
        rows.getOrPut(key) { Row(key, section, label) }.also { observer?.onRowChanged(it) }
    }

    /** A live value from a signal (counts toward update rate). */
    fun value(key: String, value: String) = onMain {
        val row = rows[key] ?: return@onMain
        val now = SystemClock.elapsedRealtime()
        val window = recentUpdates.getOrPut(key) { ArrayDeque() }
        window.addLast(now)
        while (window.isNotEmpty() && now - window.first() > 1000) window.removeFirst()
        row.value = value
        row.updates++
        row.hz = window.size.toDouble()
        row.lastUpdateMs = now
        observer?.onRowChanged(row)
    }

    /** Static information about a signal: support, range, errors. */
    fun detail(key: String, detail: String) = onMain {
        val row = rows[key] ?: return@onMain
        row.detail = detail
        observer?.onRowChanged(row)
    }

    fun log(line: String) {
        Log.i(TAG, line)
        onMain { observer?.onLog(line) }
    }

    fun snapshot(): List<Row> = rows.values.map { it.copy() }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    companion object {
        const val TAG = "PleosProbe"
    }
}
