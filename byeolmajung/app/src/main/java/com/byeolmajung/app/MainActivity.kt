package com.byeolmajung.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.byeolmajung.sky.Describe
import com.byeolmajung.sky.HorizonTransform
import com.byeolmajung.sky.Observer
import com.byeolmajung.sky.SkyCatalog
import com.byeolmajung.sky.SkyObject
import com.byeolmajung.sky.SkySnapshot
import com.byeolmajung.sky.Stories
import com.byeolmajung.sky.Story
import com.byeolmajung.sky.ViewMode
import com.byeolmajung.sky.Visibility
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private data class Place(val name: String, val observer: Observer, val heading: Double)

    /** Used when the car's own position is not available (emulator without location, or SDK not yet enabled). */
    private val presets = listOf(
        Place("서울", Observer(37.5665, 126.9780), 180.0),
        Place("영양 국제밤하늘보호공원", Observer(36.656, 129.112), 180.0),
        Place("화천 조경철천문대", Observer(38.153, 127.531), 180.0),
        Place("제주", Observer(33.450, 126.570), 180.0),
    )

    private val zone: ZoneId = ZoneId.systemDefault()
    private val handler = Handler(Looper.getMainLooper())

    private var catalog: SkyCatalog? = null
    private var place = presets[1]
    private var carPosition: Observer? = null
    private var timeOffsetMillis = 0L
    private var fastForward = false
    private var nightMode = false
    private var restricted = false
    private var selected: SkyObject? = null
    private var activeStory: Story? = null
    private var storyPage = 0

    private lateinit var sky: SkyView
    private lateinit var status: TextView
    private lateinit var daylightBanner: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var panelTitle: TextView
    private lateinit var panelSubtitle: TextView
    private lateinit var panelBody: TextView
    private lateinit var panelActions: LinearLayout
    private lateinit var drivingOverlay: TextView
    private lateinit var modeButton: Button
    private lateinit var nightButton: Button
    private lateinit var playButton: Button
    private val buttons = mutableListOf<Button>()

    private lateinit var location: CarLocation
    private lateinit var features: CarFeatures
    private lateinit var drivingGuard: DrivingGuard

    private val observer: Observer get() = carPosition ?: place.observer
    private val displayTime: Long get() = System.currentTimeMillis() + timeOffsetMillis

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())

        location = CarLocation(this) { obs, heading ->
            runOnUiThread {
                carPosition = obs
                heading?.let { sky.carHeading = it }
                refreshSky()
            }
        }
        features = CarFeatures(this) { open -> runOnUiThread { onSunroofBlind(open) } }
        drivingGuard = DrivingGuard(this) { r -> runOnUiThread { setRestricted(r) } }

        thread(name = "catalog") {
            val loaded = SkyCatalog.parse(
                assets.open("sky/stars.csv").bufferedReader(),
                assets.open("sky/constellations.txt").bufferedReader(),
            )
            runOnUiThread {
                catalog = loaded
                refreshSky()
            }
        }
        sky.carHeading = place.heading
    }

    override fun onStart() {
        super.onStart()
        location.start()
        features.start()
        drivingGuard.start()
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        if (nightMode) features.restoreLights()
        location.stop()
        features.stop()
        drivingGuard.stop()
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            panel.visibility == View.VISIBLE -> closePanel()
            // Pleos CCP policy: Back at the top level hides the app instead of finishing it.
            else -> moveTaskToBack(true)
        }
    }

    // --- Sky and time ---

    private val ticker = object : Runnable {
        override fun run() {
            if (fastForward) timeOffsetMillis += 10 * 60_000L / 30 // 10 minutes of sky per second
            refreshSky()
            handler.postDelayed(this, if (fastForward) 33L else 10_000L)
        }
    }

    private fun refreshSky() {
        val cat = catalog ?: return
        val snapshot = SkySnapshot.compute(cat, displayTime, observer)
        sky.snapshot = snapshot
        daylightBanner.visibility = if (snapshot.sunAltitude > -6 && !restricted) View.VISIBLE else View.GONE
        updateStatus()
    }

    private fun updateStatus() {
        val time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(displayTime), zone)
        val now = ZonedDateTime.now(zone)
        val day = when (time.toLocalDate()) {
            now.toLocalDate() -> "오늘"
            now.toLocalDate().plusDays(1) -> "내일"
            now.toLocalDate().minusDays(1) -> "어제"
            else -> time.format(DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN))
        }
        val where = if (carPosition != null) "내 차 위치" else place.name
        val facing = if (sky.mode == ViewMode.SUNROOF) "머리 위 하늘" else "${Describe.compass(sky.viewAzimuth)}쪽 하늘"
        val clock = time.format(DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN))
        status.text = "$where  ·  $day $clock  ·  $facing" + if (timeOffsetMillis != 0L) "  (시간 이동 중)" else ""
    }

    private fun shiftTime(hours: Int) {
        timeOffsetMillis += hours * 3_600_000L
        refreshSky()
    }

    private fun jumpToTonight() {
        val tonight = ZonedDateTime.now(zone).toLocalDate().atTime(21, 0).atZone(zone)
        timeOffsetMillis = tonight.toInstant().toEpochMilli() - System.currentTimeMillis()
        refreshSky()
    }

    // --- Modes ---

    private fun toggleMode() {
        if (sky.mode == ViewMode.WINDSHIELD) {
            sky.mode = ViewMode.SUNROOF
            if (features.hasSunroofBlind) {
                features.openSunroofBlind { ok -> if (ok) runOnUiThread { toast("선루프 가림막을 열고 있어요") } }
            }
        } else {
            sky.mode = ViewMode.WINDSHIELD
        }
        modeButton.text = if (sky.mode == ViewMode.SUNROOF) "앞유리 보기" else "선루프 보기"
        updateStatus()
    }

    private fun onSunroofBlind(open: Boolean) {
        if (open && sky.mode != ViewMode.SUNROOF) {
            toggleMode()
            toast("선루프가 열려 있어서 머리 위 하늘을 보여드려요")
        }
    }

    private fun toggleNightMode() {
        nightMode = !nightMode
        sky.options = sky.options.copy(nightRed = nightMode)
        if (nightMode) {
            features.dimLights { ok -> runOnUiThread { toast(if (ok) "실내 조명을 어둡게 했어요. 눈이 어둠에 익으면 별이 더 많이 보여요." else "관측 모드: 화면을 붉게 바꿨어요") } }
        } else {
            features.restoreLights()
        }
        nightButton.text = if (nightMode) "관측 모드 끄기" else "관측 모드"
        restyle()
    }

    private fun setRestricted(r: Boolean) {
        restricted = r
        drivingOverlay.visibility = if (r) View.VISIBLE else View.GONE
        if (r) fastForward = false
        refreshSky()
    }

    // --- Selection, info and stories ---

    private fun onSelect(obj: SkyObject?) {
        val cat = catalog ?: return
        selected = obj
        sky.options = sky.options.copy(selected = obj, highlightConstellations = activeStory?.constellations?.toSet().orEmpty())
        if (obj == null) return closePanel()
        val position = positionOf(obj)
        val d = Describe.of(obj, cat, position)
        activeStory = null
        showPanel(d.title, d.subtitle, d.body)
        d.story?.let { story -> panelActions.addView(button("'${story.title}' 이야기 듣기") { startStory(story) }) }
        panelActions.addView(button("닫기") { onSelect(null) })
    }

    private fun positionOf(obj: SkyObject) = sky.snapshot?.let { snap ->
        when (obj) {
            is SkyObject.StarObject -> snap.starHorizontal(obj.index)
            is SkyObject.BodyObject -> snap.bodies.firstOrNull { it.first.id == obj.body.id }?.second
            is SkyObject.ConstellationObject -> HorizonTransform(snap.epochMillis, snap.observer).toHorizontal(obj.constellation.label)
        }
    }

    private fun showStoryList() {
        showPanel("별자리 이야기", "이야기를 고르면 하늘에서 그 별자리를 찾아 드려요", "")
        panelBody.visibility = View.GONE
        Stories.all.forEach { story -> panelActions.addView(button("${story.title}  (${story.season})") { startStory(story) }) }
        panelActions.addView(button("닫기") { closePanel() })
    }

    private fun startStory(story: Story) {
        activeStory = story
        storyPage = 0
        selected = null
        sky.options = sky.options.copy(selected = null, highlightConstellations = story.constellations.toSet())
        val h = HorizonTransform(displayTime, observer).toHorizontal(story.focus)
        if (h.alt > 10) {
            sky.lookAt(h)
        } else {
            val when_ = Visibility.nextGoodTime(story.focus, observer, Instant.ofEpochMilli(displayTime), zone)
            if (when_ != null) {
                timeOffsetMillis = when_.toEpochMilli() - System.currentTimeMillis()
                refreshSky()
                sky.lookAt(HorizonTransform(displayTime, observer).toHorizontal(story.focus))
                val t = ZonedDateTime.ofInstant(when_, zone).format(DateTimeFormatter.ofPattern("M월 d일 a h시", Locale.KOREAN))
                toast("지금은 잘 안 보여서, 잘 보이는 $t 하늘로 이동했어요")
            }
        }
        showStoryPage()
    }

    private fun showStoryPage() {
        val story = activeStory ?: return
        showPanel(story.title, "${story.season} 별자리 이야기  ·  ${storyPage + 1} / ${story.pages.size}", story.pages[storyPage])
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (storyPage > 0) row.addView(button("이전") { storyPage--; showStoryPage() }, weight())
        if (storyPage < story.pages.lastIndex) {
            row.addView(button("다음") { storyPage++; showStoryPage() }, weight())
        } else {
            row.addView(button("다른 이야기") { showStoryList() }, weight())
        }
        panelActions.addView(row)
        panelActions.addView(button("닫기") { closePanel() })
    }

    private fun showPanel(title: String, subtitle: String, body: String) {
        panelTitle.text = title
        panelSubtitle.text = subtitle
        panelSubtitle.visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
        panelBody.text = body
        panelBody.visibility = View.VISIBLE
        panelActions.removeAllViews()
        panel.visibility = View.VISIBLE
        restyle()
    }

    private fun closePanel() {
        panel.visibility = View.GONE
        activeStory = null
        selected = null
        sky.options = sky.options.copy(selected = null, highlightConstellations = emptySet())
    }

    private fun choosePlace() {
        showPanel("장소 선택", if (location.isAvailable) "차 위치를 받고 있어요" else "차 위치를 받을 수 없어서 장소를 골라 주세요", "")
        panelBody.visibility = View.GONE
        presets.forEach { p ->
            panelActions.addView(button(p.name) {
                place = p
                carPosition = null
                sky.carHeading = p.heading
                closePanel()
                refreshSky()
            })
        }
        panelActions.addView(button("닫기") { closePanel() })
    }

    // --- Layout ---

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        sky = SkyView(this).apply {
            onSelect = ::onSelect
            onViewChanged = ::updateStatus
        }
        root.addView(sky, FrameLayout.LayoutParams(-1, -1))

        status = TextView(this).apply {
            textSize = 18f
            setTextColor(TEXT)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = rounded(0xAA0B1020.toInt())
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(20), dp(16), 0, 0) })

        daylightBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xCC14213D.toInt())
            setPadding(dp(20), dp(8), dp(8), dp(8))
            addView(TextView(context).apply {
                text = "지금은 하늘이 밝아서 별이 잘 안 보여요"
                textSize = 17f
                setTextColor(TEXT)
            })
            addView(button("오늘 밤 9시 하늘 보기") { jumpToTonight() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(16) })
            visibility = View.GONE
        }
        root.addView(daylightBanner, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(80) })

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xCC0B1020.toInt())
        }
        bar.addView(button("지금") { timeOffsetMillis = 0; fastForward = false; playButton.text = "▶ 빠르게"; sky.followCar(); refreshSky() })
        bar.addView(button("−1시간") { shiftTime(-1) })
        bar.addView(button("+1시간") { shiftTime(1) })
        playButton = button("▶ 빠르게") {
            fastForward = !fastForward
            playButton.text = if (fastForward) "■ 멈춤" else "▶ 빠르게"
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        }
        bar.addView(playButton)
        modeButton = button("선루프 보기") { toggleMode() }
        bar.addView(modeButton)
        nightButton = button("관측 모드") { toggleNightMode() }
        bar.addView(nightButton)
        bar.addView(button("별자리 이야기") { showStoryList() })
        bar.addView(button("장소") { choosePlace() })
        root.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(16) })

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(20))
            background = rounded(0xEE0D1426.toInt())
            visibility = View.GONE
        }
        panelTitle = TextView(this).apply {
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        panelSubtitle = TextView(this).apply {
            textSize = 17f
            setTextColor(ACCENT)
            setPadding(0, dp(6), 0, dp(12))
        }
        panelBody = TextView(this).apply {
            textSize = 21f
            setTextColor(TEXT)
            setLineSpacing(0f, 1.35f)
        }
        panelActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        panel.addView(panelTitle)
        panel.addView(panelSubtitle)
        panel.addView(ScrollView(this).apply { addView(panelBody) }, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(panelActions)
        root.addView(panel, FrameLayout.LayoutParams(dp(560), -1, Gravity.END).apply { setMargins(0, dp(16), dp(16), dp(110)) })

        drivingOverlay = TextView(this).apply {
            text = "운전 중에는 별을 볼 수 없어요.\n안전한 곳에 차를 세우면 보던 하늘을 이어서 보여 드릴게요."
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0xF2000000.toInt())
            visibility = View.GONE
            isClickable = true
        }
        root.addView(drivingOverlay, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 18f
        isAllCaps = false
        minHeight = dp(72)
        minWidth = dp(120)
        setPadding(dp(20), 0, dp(20), 0)
        setTextColor(TEXT)
        background = rounded(BUTTON)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(6), dp(4), dp(6), dp(4)) }
        buttons += this
        restyle(this)
    }

    private fun weight() = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(6), dp(4), dp(6), dp(4)) }

    /** Night mode turns all UI text red so it does not spoil dark adaptation. */
    private fun restyle(only: Button? = null) {
        val color = if (nightMode) NIGHT_TEXT else TEXT
        (only?.let { listOf(it) } ?: buttons).forEach { it.setTextColor(color) }
        if (only != null || !::panelBody.isInitialized) return
        status.setTextColor(color)
        panelBody.setTextColor(color)
        panelTitle.setTextColor(if (nightMode) NIGHT_TEXT else Color.WHITE)
        panelSubtitle.setTextColor(if (nightMode) NIGHT_TEXT else ACCENT)
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(18).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private val TEXT = 0xFFE4ECF7.toInt()
        private val ACCENT = 0xFF8FC2FF.toInt()
        private val BUTTON = 0xFF1B2640.toInt()
        private val NIGHT_TEXT = 0xFFD0453A.toInt()
    }
}
