package com.byeolmajung.app

import android.app.Activity
import android.content.Intent
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
import com.byeolmajung.sky.Gleo
import com.byeolmajung.sky.HorizonTransform
import com.byeolmajung.sky.Observer
import com.byeolmajung.sky.SkyBackground
import com.byeolmajung.sky.SkyCatalog
import com.byeolmajung.sky.SkyObject
import com.byeolmajung.sky.SkySnapshot
import com.byeolmajung.sky.SkyTexture
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
import kotlin.math.roundToInt

class MainActivity : Activity() {

    /** @property skyQuality 0 for a dark site, 1 for a city sky. */
    private data class Place(val name: String, val observer: Observer, val heading: Double, val skyQuality: Double)

    /** Used when the car's own position is not available (emulator without location, or SDK not yet enabled). */
    private val presets = listOf(
        Place("서울", Observer(37.5665, 126.9780), 180.0, 0.8),
        Place("영양 국제밤하늘보호공원", Observer(36.656, 129.112), 180.0, 0.0),
        Place("화천 조경철천문대", Observer(38.153, 127.531), 180.0, 0.1),
        Place("제주", Observer(33.450, 126.570), 180.0, 0.4),
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

    /** True while a story is read aloud page after page. */
    private var readingStory = false

    /** A Gleo command that arrived before the star catalog finished loading. */
    private var pendingGleo: GleoBridge.Request? = null

    private lateinit var sky: SkyView
    private lateinit var clock: TextView
    private lateinit var dateLine: TextView
    private lateinit var placeButton: Button
    private lateinit var backToNow: Button
    private lateinit var daylightBanner: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var panelTitle: TextView
    private lateinit var panelSubtitle: TextView
    private lateinit var panelBody: TextView
    private lateinit var panelActions: LinearLayout
    private lateinit var drivingOverlay: TextView
    private lateinit var windshieldButton: Button
    private lateinit var sunroofButton: Button
    private lateinit var linesButton: Button
    private lateinit var nightButton: Button
    private lateinit var playButton: Button
    private var listenButton: Button? = null

    private lateinit var location: CarLocation
    private lateinit var features: CarFeatures
    private lateinit var drivingGuard: DrivingGuard
    private lateinit var speaker: Speaker

    private val observer: Observer get() = carPosition ?: place.observer
    private val displayTime: Long get() = System.currentTimeMillis() + timeOffsetMillis

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())

        speaker = Speaker(this).apply { onSpeakingChanged = ::onSpeakingChanged }
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
                assets.open("sky/stars.csv").bufferedReader().readText(),
                assets.open("sky/constellations.txt").bufferedReader().readText(),
            )
            val texture = android.graphics.BitmapFactory.decodeStream(assets.open("sky/milkyway.jpg")).let { bmp ->
                val pixels = IntArray(bmp.width * bmp.height)
                bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                SkyTexture(bmp.width, bmp.height, pixels).also { bmp.recycle() }
            }
            runOnUiThread {
                catalog = loaded
                sky.background = SkyBackground(texture)
                refreshSky()
                pendingGleo?.let { pendingGleo = null; handleGleo(it) }
            }
        }
        sky.carHeading = place.heading
        sky.options = sky.options.copy(skyQuality = place.skyQuality)
        GleoBridge.parse(intent)?.let(::handleGleo)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        GleoBridge.parse(intent)?.let(::handleGleo)
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
        stopSpeaking()
        if (nightMode) features.restoreLights()
        location.stop()
        features.stop()
        drivingGuard.stop()
        super.onStop()
    }

    override fun onDestroy() {
        speaker.release()
        super.onDestroy()
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
        clock.text = time.format(DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN))
        dateLine.text = "$day · ${time.format(DateTimeFormatter.ofPattern("E요일", Locale.KOREAN))}"
        placeButton.text = (if (carPosition != null) "내 차 위치" else place.name) + "  ▾"
        backToNow.visibility = if (timeOffsetMillis != 0L) View.VISIBLE else View.GONE
    }

    private fun shiftTime(hours: Int) {
        timeOffsetMillis += hours * 3_600_000L
        refreshSky()
    }

    private fun backToNow() {
        timeOffsetMillis = 0
        setFastForward(false)
        sky.followCar()
        refreshSky()
    }

    private fun jumpToTonight() {
        val tonight = ZonedDateTime.now(zone).toLocalDate().atTime(21, 0).atZone(zone)
        timeOffsetMillis = tonight.toInstant().toEpochMilli() - System.currentTimeMillis()
        refreshSky()
    }

    private fun setFastForward(on: Boolean) {
        fastForward = on
        playButton.text = if (on) "❚❚  멈춤" else "▶  빠르게"
        style(playButton, on)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    // --- Modes ---

    private fun setMode(mode: ViewMode) {
        if (sky.mode == mode) return
        sky.mode = mode
        if (mode == ViewMode.SUNROOF && features.hasSunroofBlind) {
            features.openSunroofBlind { ok -> if (ok) runOnUiThread { toast("선루프 가림막을 열고 있어요") } }
        }
        style(windshieldButton, mode == ViewMode.WINDSHIELD, segment = true)
        style(sunroofButton, mode == ViewMode.SUNROOF, segment = true)
        updateStatus()
    }

    private fun onSunroofBlind(open: Boolean) {
        if (open && sky.mode != ViewMode.SUNROOF) {
            setMode(ViewMode.SUNROOF)
            toast("선루프가 열려 있어서 머리 위 하늘을 보여 드려요")
        }
    }

    private fun toggleLines() {
        sky.options = sky.options.copy(showConstellations = !sky.options.showConstellations)
        style(linesButton, sky.options.showConstellations)
    }

    /** Stargazing mode: turn every light in the car off so eyes adapt to the dark; turning it off restores them. */
    private fun toggleNightMode() {
        nightMode = !nightMode
        if (nightMode) {
            features.turnOffAllLights { ok ->
                runOnUiThread { toast(if (ok) "실내등, 독서등, 무드등을 모두 껐어요. 눈이 어둠에 익으면 별이 더 많이 보여요." else "이 차에서는 조명을 끌 수 없어요") }
            }
        } else {
            features.restoreLights()
        }
        style(nightButton, nightMode)
    }

    private fun setRestricted(r: Boolean) {
        restricted = r
        drivingOverlay.visibility = if (r) View.VISIBLE else View.GONE
        if (r) {
            setFastForward(false)
            stopSpeaking()
        }
        refreshSky()
    }

    // --- Selection, info and stories ---

    private fun onSelect(obj: SkyObject?) {
        val cat = catalog ?: return
        stopSpeaking()
        selected = obj
        activeStory = null
        sky.options = sky.options.copy(selected = obj, highlightConstellations = emptySet())
        if (obj == null) return closePanel()
        val position = positionOf(obj)
        val d = Describe.of(obj, cat, position)
        showPanel(d.title, d.subtitle, d.body)
        listenButton = primaryButton(LISTEN) {
            if (speaker.isSpeaking) stopSpeaking() else speaker.speak(Describe.narration(obj, cat, position))
        }.also { panelActions.addView(it, fullWidth()) }
        d.story?.let { story -> panelActions.addView(secondaryButton("'${story.title}' 이야기 보기") { startStory(story) }, fullWidth()) }
    }

    private fun positionOf(obj: SkyObject) = sky.snapshot?.let { snap ->
        when (obj) {
            is SkyObject.StarObject -> snap.starHorizontal(obj.index)
            is SkyObject.BodyObject -> snap.bodies.firstOrNull { it.first.id == obj.body.id }?.second
            is SkyObject.ConstellationObject -> HorizonTransform(snap.epochMillis, snap.observer).toHorizontal(obj.constellation.label)
        }
    }

    private fun showStoryList() {
        stopSpeaking()
        showPanel("별자리 이야기", "이야기를 고르면 하늘에서 그 별자리를 찾아 드려요", "")
        panelBody.visibility = View.GONE
        Stories.all.forEach { story ->
            panelActions.addView(listItem(story.title, "${story.season} 별자리") { startStory(story) }, fullWidth())
        }
    }

    private fun startStory(story: Story, readAloud: Boolean = false) {
        stopSpeaking()
        activeStory = story
        storyPage = 0
        selected = null
        sky.options = sky.options.copy(selected = null, highlightConstellations = story.constellations.toSet())
        val h = HorizonTransform(displayTime, observer).toHorizontal(story.focus)
        if (h.alt > 10) {
            sky.lookAt(h)
        } else {
            val offsetMinutes = zone.rules.getOffset(Instant.ofEpochMilli(displayTime)).totalSeconds / 60
            val when_ = Visibility.nextGoodTime(story.focus, observer, displayTime, offsetMinutes)?.let(Instant::ofEpochMilli)
            if (when_ != null) {
                timeOffsetMillis = when_.toEpochMilli() - System.currentTimeMillis()
                refreshSky()
                sky.lookAt(HorizonTransform(displayTime, observer).toHorizontal(story.focus))
                val t = ZonedDateTime.ofInstant(when_, zone).format(DateTimeFormatter.ofPattern("M월 d일 a h시", Locale.KOREAN))
                toast("지금은 잘 안 보여서, 잘 보이는 $t 하늘로 이동했어요")
            }
        }
        showStoryPage()
        if (readAloud) readStory()
    }

    private fun showStoryPage() {
        val story = activeStory ?: return
        showPanel(story.title, "${story.season} 별자리 이야기  ·  ${storyPage + 1} / ${story.pages.size}", story.pages[storyPage])
        listenButton = primaryButton(if (readingStory) STOP else LISTEN_STORY) {
            if (readingStory || speaker.isSpeaking) stopSpeaking() else readStory()
        }.also { panelActions.addView(it, fullWidth()) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (storyPage > 0) row.addView(secondaryButton("이전") { turnPage(-1) }, weight())
        if (storyPage < story.pages.lastIndex) {
            row.addView(secondaryButton("다음") { turnPage(1) }, weight())
        } else {
            row.addView(secondaryButton("다른 이야기") { showStoryList() }, weight())
        }
        panelActions.addView(row, fullWidth())
    }

    private fun turnPage(delta: Int) {
        stopSpeaking()
        storyPage += delta
        showStoryPage()
    }

    /** Reads the current page aloud and turns to the next page when it is done. */
    private fun readStory() {
        val story = activeStory ?: return
        readingStory = true
        listenButton?.text = STOP
        speaker.speak(story.pages[storyPage])
    }

    private fun onSpeakingChanged(speaking: Boolean) {
        val story = activeStory
        if (!speaking && readingStory && story != null && storyPage < story.pages.lastIndex && panel.visibility == View.VISIBLE) {
            storyPage++
            showStoryPage()
            speaker.speak(story.pages[storyPage])
            return
        }
        if (!speaking) readingStory = false
        listenButton?.text = when {
            speaking || readingStory -> STOP
            activeStory != null -> LISTEN_STORY
            else -> LISTEN
        }
    }

    private fun stopSpeaking() {
        readingStory = false
        if (::speaker.isInitialized) speaker.stop()
    }

    private fun showPanel(title: String, subtitle: String, body: String) {
        panelTitle.text = title
        panelSubtitle.text = subtitle
        panelSubtitle.visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
        panelBody.text = body
        panelBody.visibility = View.VISIBLE
        panelActions.removeAllViews()
        listenButton = null
        panel.visibility = View.VISIBLE
    }

    private fun closePanel() {
        stopSpeaking()
        panel.visibility = View.GONE
        activeStory = null
        selected = null
        listenButton = null
        sky.options = sky.options.copy(selected = null, highlightConstellations = emptySet())
    }

    private fun choosePlace() {
        stopSpeaking()
        showPanel("어디서 볼까요?", if (location.isAvailable) "차 위치를 받고 있어요" else "차 위치를 받을 수 없어서 장소를 골라 주세요", "")
        panelBody.visibility = View.GONE
        presets.forEach { p ->
            val darkness = when {
                p.skyQuality < 0.2 -> "아주 어두운 하늘"
                p.skyQuality < 0.5 -> "꽤 어두운 하늘"
                else -> "도시의 밝은 하늘"
            }
            panelActions.addView(listItem(p.name, darkness) {
                place = p
                carPosition = null
                sky.carHeading = p.heading
                sky.options = sky.options.copy(skyQuality = p.skyQuality)
                closePanel()
                refreshSky()
            }, fullWidth())
        }
    }

    // --- Gleo AI ---

    private fun handleGleo(request: GleoBridge.Request) {
        val cat = catalog
        if (cat == null || sky.snapshot == null) {
            pendingGleo = request
            return
        }
        refreshSky()
        val snapshot = sky.snapshot ?: return
        val response = when (request.action) {
            Gleo.Action.DESCRIBE_SKY_OBJECT -> {
                val name = request.string("name")
                val obj = if (name.isEmpty()) selected else Gleo.find(name, snapshot)
                obj?.let(::showObject)
                Gleo.describe(obj, snapshot, obj?.let(::positionOf))
            }
            Gleo.Action.FIND_SKY_OBJECT -> {
                val name = request.string("name")
                val obj = Gleo.find(name, snapshot)
                obj?.let(::showObject)
                Gleo.found(obj, name, snapshot, obj?.let(::positionOf))
            }
            Gleo.Action.TELL_CONSTELLATION_STORY -> {
                val name = request.string("story")
                val story = Gleo.findStory(name)
                story?.let { startStory(it, readAloud = true) }
                Gleo.storyStarted(story, name)
            }
        }
        GleoBridge.respond(this, response)
    }

    /** Selects [obj] and turns the view toward it if it is above the horizon. */
    private fun showObject(obj: SkyObject) {
        onSelect(obj)
        positionOf(obj)?.takeIf { it.alt > 0 }?.let { sky.lookAt(it) }
    }

    // --- Layout ---

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        sky = SkyView(this).apply {
            onSelect = ::onSelect
            onViewChanged = ::updateStatus
        }
        root.addView(sky, FrameLayout.LayoutParams(-1, -1))

        // Top left: time and place.
        val timeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            background = glass()
        }
        clock = TextView(this).apply {
            textSize = 40f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(INK)
        }
        dateLine = TextView(this).apply {
            textSize = 17f
            setTextColor(INK_DIM)
        }
        placeButton = Button(this).apply {
            textSize = 19f
            isAllCaps = false
            setTextColor(GOLD)
            background = null
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(0, 0, 0, 0)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { choosePlace() }
        }
        backToNow = Button(this).apply {
            text = "시간 이동 중 · 지금으로"
            textSize = 16f
            isAllCaps = false
            setTextColor(BG)
            background = pill(GOLD)
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(18), 0, dp(18), 0)
            visibility = View.GONE
            setOnClickListener { backToNow() }
        }
        timeCard.addView(clock)
        timeCard.addView(dateLine)
        timeCard.addView(placeButton, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        timeCard.addView(backToNow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        root.addView(timeCard, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(24), dp(20), 0, 0) })

        // Top right: view mode and toggles.
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val segment = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = glass(radius = 40)
        }
        windshieldButton = chip("앞유리") { setMode(ViewMode.WINDSHIELD) }
        sunroofButton = chip("선루프") { setMode(ViewMode.SUNROOF) }
        segment.addView(windshieldButton)
        segment.addView(sunroofButton)
        style(windshieldButton, true, segment = true)
        style(sunroofButton, false, segment = true)
        controls.addView(segment)
        linesButton = chip("별자리 선") { toggleLines() }
        nightButton = chip("관측 모드") { toggleNightMode() }
        style(linesButton, false)
        style(nightButton, false)
        controls.addView(linesButton, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        controls.addView(nightButton, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        root.addView(controls, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(20), dp(24), 0) })

        daylightBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass(radius = 40)
            setPadding(dp(28), dp(8), dp(8), dp(8))
            addView(TextView(context).apply {
                text = "지금은 하늘이 밝아서 별이 잘 안 보여요"
                textSize = 18f
                setTextColor(INK)
            })
            addView(Button(context).apply {
                text = "오늘 밤 9시 하늘 보기"
                textSize = 17f
                isAllCaps = false
                setTextColor(BG)
                background = pill(GOLD)
                minHeight = dp(56)
                setPadding(dp(22), 0, dp(22), 0)
                setOnClickListener { jumpToTonight() }
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(20) })
            visibility = View.GONE
        }
        root.addView(daylightBanner, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(110) })

        // Bottom left: stories.
        val storiesButton = Button(this).apply {
            text = "✦  별자리 이야기"
            textSize = 20f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(BG)
            background = pill(GOLD)
            minHeight = dp(72)
            setPadding(dp(32), 0, dp(32), 0)
            setOnClickListener { showStoryList() }
        }
        root.addView(storiesButton, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(24), 0, 0, dp(24)) })

        // Bottom centre: time dock.
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = glass(radius = 44)
        }
        dock.addView(chip("−1시간") { shiftTime(-1) }.also { style(it, false, segment = true) })
        dock.addView(chip("+1시간") { shiftTime(1) }.also { style(it, false, segment = true) })
        playButton = chip("▶  빠르게") { setFastForward(!fastForward) }
        style(playButton, false, segment = true)
        dock.addView(playButton, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        root.addView(dock, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) })

        // Right: detail panel.
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(24), dp(24), dp(24))
            background = glass(fill = PANEL, radius = 28)
            visibility = View.GONE
            isClickable = true
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        panelTitle = TextView(this).apply {
            textSize = 30f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            setTextColor(INK)
        }
        header.addView(panelTitle, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(8) })
        header.addView(Button(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(INK_DIM)
            background = pill(0x14FFFFFF)
            minWidth = dp(56)
            minimumWidth = dp(56)
            minHeight = dp(56)
            minimumHeight = dp(56)
            setPadding(0, 0, 0, 0)
            contentDescription = "닫기"
            setOnClickListener { closePanel() }
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        panelSubtitle = TextView(this).apply {
            textSize = 16f
            setTextColor(GOLD)
            setPadding(0, dp(6), 0, dp(16))
        }
        panelBody = TextView(this).apply {
            textSize = 20f
            setTextColor(INK)
            setLineSpacing(0f, 1.45f)
        }
        panelActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        panel.addView(header)
        panel.addView(panelSubtitle)
        panel.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(panelBody) }, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(panelActions)
        root.addView(panel, FrameLayout.LayoutParams(dp(540), -1, Gravity.END).apply { setMargins(0, dp(100), dp(24), dp(24)) })

        drivingOverlay = TextView(this).apply {
            text = "운전 중에는 별을 볼 수 없어요.\n안전한 곳에 차를 세우면 보던 하늘을 이어서 보여 드릴게요."
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(INK)
            setLineSpacing(0f, 1.4f)
            setBackgroundColor(0xF2050810.toInt())
            visibility = View.GONE
            isClickable = true
        }
        root.addView(drivingOverlay, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    /** A rounded control for the top bar and time dock; [style] sets how it looks when on or off. */
    private fun chip(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 18f
        isAllCaps = false
        minHeight = dp(64)
        minimumHeight = dp(64)
        minWidth = dp(112)
        minimumWidth = dp(112)
        setPadding(dp(22), 0, dp(22), 0)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    /**
     * Toggles are glass pills that turn gold when on; segment items sit inside a glass group and are
     * transparent when off.
     */
    private fun style(button: Button, on: Boolean, segment: Boolean = false) {
        button.setTextColor(if (on) BG else INK)
        button.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        button.background = when {
            on -> pill(GOLD)
            segment -> pill(Color.TRANSPARENT)
            else -> glass(radius = 40)
        }
    }

    private fun primaryButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 20f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(BG)
        background = pill(GOLD)
        minHeight = dp(72)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun secondaryButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 18f
        isAllCaps = false
        setTextColor(INK)
        background = GradientDrawable().apply {
            cornerRadius = dp(36).toFloat()
            setStroke(dp(1), 0x55FFFFFF)
        }
        minHeight = dp(64)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    /** A two-line choice in the panel, such as a story or a place. */
    private fun listItem(title: String, detail: String, onClick: () -> Unit) = Button(this).apply {
        text = android.text.SpannableStringBuilder(title).apply {
            append("\n")
            val start = length
            append(detail)
            setSpan(android.text.style.ForegroundColorSpan(INK_DIM), start, length, 0)
            setSpan(android.text.style.RelativeSizeSpan(0.8f), start, length, 0)
        }
        textSize = 20f
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setTextColor(INK)
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(0x0FFFFFFF)
            setStroke(dp(1), 0x1FFFFFFF)
        }
        minHeight = dp(80)
        setPadding(dp(24), dp(12), dp(24), dp(12))
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun fullWidth() = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(6)) }

    private fun weight() = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }

    private fun glass(fill: Int = GLASS, radius: Int = 24) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), 0x1FFFFFFF)
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(40).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private val GOLD = 0xFFF2C46D.toInt()
        private val INK = 0xFFE6ECF5.toInt()
        private val INK_DIM = 0xFF8E9BB3.toInt()
        private val BG = 0xFF0A0E1A.toInt()
        private val GLASS = 0xB30C1222.toInt()
        private val PANEL = 0xEB0C1222.toInt()

        private const val LISTEN = "▶  설명 듣기"
        private const val LISTEN_STORY = "▶  이야기 듣기"
        private const val STOP = "■  그만 듣기"
    }
}
