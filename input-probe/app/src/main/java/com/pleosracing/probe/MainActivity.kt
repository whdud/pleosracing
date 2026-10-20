package com.pleosracing.probe

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * One screen that shows, live, every vehicle signal a racing game could use as input,
 * plus a log of keys and supported properties. Everything is also written to logcat (tag: PleosProbe).
 */
class MainActivity : Activity(), ProbeBoard.Observer {

    private val board = ProbeBoard()
    private lateinit var pleosProbe: PleosVehicleProbe
    private lateinit var carProbe: CarPropertyProbe
    private lateinit var keyProbe: KeyProbe

    private lateinit var rowsContainer: LinearLayout
    private lateinit var logView: TextView
    private lateinit var sessionButton: Button
    private val sectionViews = HashMap<String, LinearLayout>()
    private val rowViews = HashMap<String, TextView>()
    private val logLines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        board.observer = this

        board.log("device=${Build.DEVICE} model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
        pleosProbe = PleosVehicleProbe(this, board)
        carProbe = CarPropertyProbe(this, board)
        keyProbe = KeyProbe(this, board)

        runCatching { pleosProbe.start() }.onFailure { board.log("Pleos probe failed to start: $it") }
        runCatching { carProbe.start() }.onFailure { board.log("Car probe failed to start: $it") }
        requestRuntimePermissions()
    }

    override fun onDestroy() {
        board.observer = null
        runCatching { pleosProbe.stop() }
        runCatching { carProbe.stop() }
        keyProbe.release()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        keyProbe.onActivityKey(event)
        // Pleos CCP policy: Back at the top level hides the app instead of finishing it.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) moveTaskToBack(true)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun requestRuntimePermissions() {
        val missing = RUNTIME_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return
        board.log("requesting runtime permissions: $missing")
        requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode != REQUEST_PERMISSIONS) return
        permissions.forEachIndexed { i, p ->
            val granted = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            board.log("permission $p granted=$granted")
        }
        runCatching { carProbe.restart() }.onFailure { board.log("Car probe restart failed: $it") }
    }

    override fun onRowChanged(row: ProbeBoard.Row) {
        val view = rowViews.getOrPut(row.key) {
            TextView(this).apply {
                textSize = 18f
                setTextColor(Color.WHITE)
                setPadding(dp(16), dp(6), dp(16), dp(6))
                sectionFor(row.section).addView(this)
            }
        }
        val rate = if (row.updates > 0) "  ·  ${"%.0f".format(row.hz)}Hz  ·  ${row.updates}회" else ""
        val detail = if (row.detail.isNotEmpty()) "\n    ${row.detail}" else ""
        view.text = "${row.label}:  ${row.value}$rate$detail"
    }

    override fun onLog(line: String) {
        logLines.addLast(line)
        while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        logView.text = logLines.joinToString("\n")
    }

    private fun sectionFor(section: String) = sectionViews.getOrPut(section) {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(TextView(this@MainActivity).apply {
                text = section
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ACCENT)
                setPadding(dp(16), dp(8), dp(16), dp(4))
            })
            rowsContainer.addView(this)
        }
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 18, 22))
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "Pleos Input Probe"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "핸들·브레이크·기어·버튼 입력이 앱까지 들어오는지 확인합니다. 로그: adb logcat -s PleosProbe"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(4), 0, dp(12))
        })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sessionButton = button("미디어 키 수신 켜기") {
            keyProbe.setSessionActive(!keyProbe.isSessionActive)
            sessionButton.text = if (keyProbe.isSessionActive) "미디어 키 수신 끄기" else "미디어 키 수신 켜기"
        }
        buttons.addView(sessionButton)
        buttons.addView(button("로그 지우기") {
            logLines.clear()
            logView.text = ""
        })
        buttons.addView(button("현재 값 로그로 저장") {
            board.snapshot().forEach { board.log("[snapshot] ${it.label} = ${it.value} (${it.hz}Hz) ${it.detail}") }
        })
        root.addView(buttons)

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(ScrollView(this).apply { addView(rowsContainer) }, LinearLayout.LayoutParams(0, -1, 3f))

        logView = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(170, 220, 170))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val logScroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(8, 9, 11))
            addView(logView)
        }
        logView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> logScroll.fullScroll(View.FOCUS_DOWN) }
        body.addView(logScroll, LinearLayout.LayoutParams(0, -1, 2f))

        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 16f
        gravity = Gravity.CENTER
        minHeight = dp(64)
        setPadding(dp(20), 0, dp(20), 0)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_PERMISSIONS = 1
        private const val MAX_LOG_LINES = 400
        private val ACCENT = Color.rgb(90, 170, 255)

        // Dangerous permissions; the normal ones in the manifest are granted at install time.
        private val RUNTIME_PERMISSIONS = listOf(
            "android.car.permission.READ_CAR_STEERING",
            "android.car.permission.CAR_ENGINE_DETAILED",
            CarPropertyProbe.PERMISSION_READ_CAR_PEDALS,
        )
    }
}
