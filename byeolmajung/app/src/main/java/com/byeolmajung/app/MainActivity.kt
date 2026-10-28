package com.byeolmajung.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
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

    /** True while the detail sheet is sliding out. */
    private var closing = false

    /** True while a story is read aloud page after page. */
    private var readingStory = false

    /** A Gleo command that arrived before the star catalog finished loading. */
    private var pendingGleo: GleoBridge.Request? = null

    private lateinit var sky: SkyView
    private lateinit var clock: TextView
    private lateinit var dateLine: TextView
    private lateinit var coords: TextView
    private lateinit var placeButton: Button
    private lateinit var backToNow: Button
    private lateinit var daylightBanner: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var panelTitle: TextView
    private lateinit var panelSubtitle: TextView
    private lateinit var panelBody: TextView
    private lateinit var bodyScroll: ScrollView
    private lateinit var panelActions: LinearLayout
    private lateinit var drivingOverlay: TextView
    private lateinit var windshieldButton: StripButton
    private lateinit var sunroofButton: StripButton
    private lateinit var linesButton: StripButton
    private lateinit var artButton: StripButton
    private lateinit var nightButton: StripButton
    private lateinit var playButton: StripButton
    private var listenButton: PaperButton? = null

    private lateinit var location: CarLocation
    private var locationStarted = false

    /** The permission dialog is shown once per launch; after a denial the preset places are used. */
    private var askedLocation = false
    private lateinit var features: CarFeatures
    private lateinit var drivingGuard: DrivingGuard
    private lateinit var speaker: Speaker

    private val observer: Observer get() = carPosition ?: place.observer
    private val displayTime: Long get() = System.currentTimeMillis() + timeOffsetMillis

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())

        speaker = Speaker(this).apply {
            onSpeakingChanged = ::onSpeakingChanged
            onUnavailable = { readingStory = false; toast("이 차에서는 소리로 읽어 줄 수 없어요") }
        }
        location = CarLocation(this) { obs, heading ->
            runOnUiThread {
                val first = carPosition == null
                carPosition = obs
                heading?.let { sky.carHeading = it }
                refreshSky()
                // A story started before the car's position arrived (e.g. by Gleo at launch) was aimed for a preset place.
                if (first) activeStory?.let { moveToStoryTime(it); lookAtStoryPage() }
            }
        }
        features = CarFeatures(this) { open -> runOnUiThread { onSunroofBlind(open) } }
        drivingGuard = DrivingGuard(this) { r -> runOnUiThread { setRestricted(r) } }

        thread(name = "catalog") {
            val loaded = SkyCatalog.parse(
                assets.open("sky/stars.csv").bufferedReader().readText(),
                assets.open("sky/constellations.txt").bufferedReader().readText(),
                assets.open("sky/art.txt").bufferedReader().readText(),
            )
            fun texture(name: String) = android.graphics.BitmapFactory.decodeStream(assets.open(name)).let { bmp ->
                val pixels = IntArray(bmp.width * bmp.height)
                bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                SkyTexture(bmp.width, bmp.height, pixels).also { bmp.recycle() }
            }
            val texture = texture("sky/milkyway.jpg")
            val moonMap = texture("sky/moon.jpg")
            runOnUiThread {
                catalog = loaded
                sky.background = SkyBackground(texture)
                sky.moonMap = moonMap
                refreshSky()
                sky.animate().alpha(1f).setDuration(1600).setInterpolator(DecelerateInterpolator()).start()
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
        startLocation()
        features.start()
        drivingGuard.start()
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        stopSpeaking()
        if (nightMode) features.restoreLights()
        if (locationStarted) {
            locationStarted = false
            location.stop()
        }
        features.stop()
        drivingGuard.stop()
        super.onStop()
    }

    /** Starts car location, asking for the FUSED_LOCATION permission once if it is not granted yet. */
    private fun startLocation() {
        if (locationStarted) return
        if (checkSelfPermission(LOCATION_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            if (!askedLocation) {
                askedLocation = true
                requestPermissions(arrayOf(LOCATION_PERMISSION), REQUEST_LOCATION)
            }
            return
        }
        locationStarted = true
        location.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_LOCATION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startLocation()
        else Log.i("Byeolmajung", "FUSED_LOCATION denied; using a preset place")
    }

    override fun onDestroy() {
        speaker.release()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            panel.visibility == View.VISIBLE && !closing -> closePanel()
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
        dateLine.text = "${time.format(DateTimeFormatter.ofPattern("M월 d일 E요일", Locale.KOREAN))} · $day"
        coords.text = String.format(Locale.US, "%.1f°N  %.1f°E", observer.latitude, observer.longitude)
        placeButton.text = if (carPosition != null) "내 차 위치" else place.name
        val shifted = timeOffsetMillis != 0L
        if (shifted != (backToNow.visibility == View.VISIBLE)) {
            backToNow.visibility = if (shifted) View.VISIBLE else View.GONE
            if (shifted) rise(backToNow)
        }
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
        playButton.text = if (on) "멈춤" else "빠르게"
        playButton.on = on
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
        windshieldButton.on = mode == ViewMode.WINDSHIELD
        sunroofButton.on = mode == ViewMode.SUNROOF
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
        linesButton.on = sky.options.showConstellations
    }

    private fun toggleArt() {
        sky.options = sky.options.copy(showArt = !sky.options.showArt)
        artButton.on = sky.options.showArt
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
        nightButton.on = nightMode
    }

    private fun setRestricted(r: Boolean) {
        restricted = r
        drivingOverlay.visibility = if (r) View.VISIBLE else View.GONE
        if (r) {
            drivingOverlay.alpha = 0f
            drivingOverlay.animate().alpha(1f).setDuration(400).start()
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
        listenButton = paperButton(LISTEN) {
            if (speaker.isSpeaking) stopSpeaking() else speaker.speak(Describe.narration(obj, cat, position))
        }.also { panelActions.addView(it, fullWidth()) }
        d.story?.let { story -> panelActions.addView(outlineButton("'${story.title}' 이야기 보기") { startStory(story) }, fullWidth()) }
    }

    private fun positionOf(obj: SkyObject) = sky.snapshot?.let { snap ->
        when (obj) {
            is SkyObject.StarObject -> snap.starHorizontal(obj.index)
            is SkyObject.BodyObject -> snap.bodies.firstOrNull { it.first.id == obj.body.id }?.second
            is SkyObject.ConstellationObject -> HorizonTransform(snap.epochMillis, snap.observer).toHorizontal(obj.constellation.label)
            SkyObject.Kepler22b -> HorizonTransform(snap.epochMillis, snap.observer).toHorizontal(SkyObject.Kepler22b.position)
        }
    }

    private fun showStoryList() {
        stopSpeaking()
        showPanel("별자리 이야기", "이야기를 고르면 하늘에서 그 별자리를 찾아 드려요", "")
        panelBody.visibility = View.GONE
        Stories.all.forEach { story -> addItem(story.title, "${story.season} 별자리") { startStory(story) } }
    }

    private fun startStory(story: Story, readAloud: Boolean = false) {
        stopSpeaking()
        activeStory = story
        storyPage = 0
        selected = null
        sky.options = sky.options.copy(selected = null, highlightConstellations = story.constellations.toSet())
        moveToStoryTime(story)
        showStoryPage()
        if (readAloud) readStory()
    }

    /** If the story's part of the sky is below the horizon now, moves the clock to when it is well placed. */
    private fun moveToStoryTime(story: Story) {
        if (HorizonTransform(displayTime, observer).toHorizontal(story.focus).alt > 10) return
        val offsetMinutes = zone.rules.getOffset(Instant.ofEpochMilli(displayTime)).totalSeconds / 60
        val when_ = Visibility.nextGoodTime(story.focus, observer, displayTime, offsetMinutes)?.let(Instant::ofEpochMilli) ?: return
        timeOffsetMillis = when_.toEpochMilli() - System.currentTimeMillis()
        refreshSky()
        val t = ZonedDateTime.ofInstant(when_, zone).format(DateTimeFormatter.ofPattern("M월 d일 a h시", Locale.KOREAN))
        toast("지금은 잘 안 보여서, 잘 보이는 $t 하늘로 이동했어요")
    }

    /** Turns the view to the part of the sky the current story page talks about, if it is above the horizon. */
    private fun lookAtStoryPage() {
        val story = activeStory ?: return
        val h = HorizonTransform(displayTime, observer).toHorizontal(story.focusFor(storyPage))
        if (h.alt > 10) sky.lookAt(h)
    }

    private fun showStoryPage() {
        val story = activeStory ?: return
        lookAtStoryPage()
        showPanel(story.title, "${story.season} 별자리 이야기  ·  ${storyPage + 1} / ${story.pages.size}", story.pages[storyPage], storyText = true)
        listenButton = paperButton(LISTEN_STORY) {
            if (readingStory || speaker.isSpeaking) stopSpeaking() else readStory()
        }.also { panelActions.addView(it, fullWidth()) }
        if (readingStory) setListen(STOP)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (storyPage > 0) row.addView(outlineButton("이전") { turnPage(-1) }, weight())
        if (storyPage < story.pages.lastIndex) {
            row.addView(outlineButton("다음") { turnPage(1) }, weight())
        } else {
            row.addView(outlineButton("다른 이야기") { showStoryList() }, weight())
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
        setListen(STOP)
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
        setListen(
            when {
                speaking || readingStory -> STOP
                activeStory != null -> LISTEN_STORY
                else -> LISTEN
            },
        )
    }

    private fun setListen(label: String) {
        val b = listenButton ?: return
        b.text = label
        b.speaking = label == STOP
    }

    private fun stopSpeaking() {
        readingStory = false
        if (::speaker.isInitialized) speaker.stop()
    }

    private fun showPanel(title: String, subtitle: String, body: String, storyText: Boolean = false) {
        panelTitle.text = title
        panelSubtitle.text = subtitle
        panelSubtitle.visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
        panelBody.text = body
        panelBody.visibility = View.VISIBLE
        // Lists (stories, places) have no body; let them sit right under the title.
        bodyScroll.visibility = if (body.isEmpty()) View.GONE else View.VISIBLE
        panelBody.typeface = if (storyText) serif else Typeface.DEFAULT
        panelBody.textSize = if (storyText) 22f else 19f
        panelActions.removeAllViews()
        listenButton = null
        if (panel.visibility == View.VISIBLE && !closing) {
            // Already open: turn the page instead of sliding the sheet in again.
            panelBody.alpha = 0f
            panelBody.translationX = dp(14).toFloat()
            panelBody.animate().alpha(1f).translationX(0f).setDuration(450).setInterpolator(DecelerateInterpolator(2f)).start()
            return
        }
        panel.animate().cancel()
        closing = false
        panel.visibility = View.VISIBLE
        panel.alpha = 0f
        panel.translationX = dp(48).toFloat()
        panel.animate().alpha(1f).translationX(0f).setDuration(480).setInterpolator(DecelerateInterpolator(2f)).start()
        for (i in 0 until panel.childCount) rise(panel.getChildAt(i), delay = 60L * i)
    }

    private fun closePanel() {
        stopSpeaking()
        if (panel.visibility == View.VISIBLE && !closing) {
            closing = true
            panel.animate().alpha(0f).translationX(dp(32).toFloat()).setDuration(220)
                .withEndAction { closing = false; panel.visibility = View.GONE }.start()
        }
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
            addItem(p.name, darkness) {
                place = p
                carPosition = null
                sky.carHeading = p.heading
                sky.options = sky.options.copy(skyQuality = p.skyQuality)
                closePanel()
                refreshSky()
            }
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

    // --- Layout: a star atlas laid over the windshield ---
    // Type sits on the sky like chart labels; one ruled control strip runs along the bottom;
    // details open as a ruled sheet on the right.

    private val serif: Typeface by lazy { resources.getFont(R.font.hahmlet_light) }
    private val serifThin: Typeface by lazy { resources.getFont(R.font.hahmlet_extralight) }

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(SKY) }

        sky = SkyView(this).apply {
            onSelect = ::onSelect
            onViewChanged = ::updateStatus
            alpha = 0f // fades in once the catalog is drawn
        }
        root.addView(sky, FrameLayout.LayoutParams(-1, -1))

        // Shade behind the corner text and the strip so they read over the Milky Way.
        root.addView(View(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                colors = intArrayOf(0xC004060C.toInt(), 0x0004060C)
                gradientRadius = dp(400).toFloat()
                setGradientCenter(0f, 0f)
            }
        }, FrameLayout.LayoutParams(dp(820), dp(460)))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xEB04060C.toInt(), 0x0004060C))
        }, FrameLayout.LayoutParams(-1, dp(190), Gravity.BOTTOM))

        // Top left: time and place, set like a chart's title block.
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clock = TextView(this).apply {
            textSize = 64f
            typeface = serifThin
            setTextColor(PAPER)
            isSingleLine = true
        }
        dateLine = TextView(this).apply {
            textSize = 16f
            setTextColor(PAPER_DIM)
        }
        coords = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.06f
            setTextColor(PAPER_DIM)
        }
        placeButton = Button(this).apply {
            textSize = 17f
            isAllCaps = false
            setTextColor(PAPER)
            background = rule(bottom = true)
            stateListAnimator = null
            minHeight = dp(48)
            minimumHeight = dp(48)
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { choosePlace() }
        }
        backToNow = Button(this).apply {
            text = "시간 이동 중 · 지금으로"
            textSize = 15f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ON_PAPER)
            setBackgroundColor(PAPER)
            stateListAnimator = null
            minHeight = dp(44)
            minimumHeight = dp(44)
            setPadding(dp(16), 0, dp(16), 0)
            visibility = View.GONE
            setOnClickListener { backToNow() }
        }
        title.addView(clock, LinearLayout.LayoutParams(-1, -2))
        title.addView(dateLine, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        title.addView(coords)
        title.addView(placeButton, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        title.addView(backToNow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        root.addView(title, FrameLayout.LayoutParams(dp(460), -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(36), dp(28), 0, 0) })

        daylightBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(SHEET); setStroke(dp(1), PAPER_FAINT) }
            setPadding(dp(24), dp(8), dp(8), dp(8))
            addView(TextView(context).apply {
                text = "지금은 하늘이 밝아서 별이 잘 안 보여요"
                textSize = 17f
                setTextColor(PAPER)
            })
            addView(paperButton("오늘 밤 9시 하늘") { jumpToTonight() }.apply {
                textSize = 16f
                minHeight = dp(52)
                setPadding(dp(20), 0, dp(20), 0)
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(20) })
            visibility = View.GONE
        }
        root.addView(daylightBanner, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(32) })

        // Bottom: the control strip.
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rule(bottom = false)
            setPadding(dp(24), 0, dp(24), 0)
        }
        strip.addView(stripButton("별자리 이야기") { showStoryList() }.apply {
            typeface = serif
            textSize = 22f
            setTextColor(PAPER)
        })
        strip.addView(divider())
        strip.addView(stripButton("−1시간") { shiftTime(-1) })
        strip.addView(stripButton("+1시간") { shiftTime(1) })
        playButton = stripButton("빠르게") { setFastForward(!fastForward) }
        strip.addView(playButton)
        strip.addView(divider())
        windshieldButton = stripButton("앞유리") { setMode(ViewMode.WINDSHIELD) }.apply { on = true }
        sunroofButton = stripButton("선루프") { setMode(ViewMode.SUNROOF) }
        linesButton = stripButton("별자리 선") { toggleLines() }
        artButton = stripButton("별자리 그림") { toggleArt() }
        nightButton = stripButton("관측 모드") { toggleNightMode() }
        listOf(windshieldButton, sunroofButton, linesButton, artButton, nightButton).forEach(strip::addView)
        root.addView(strip, FrameLayout.LayoutParams(-1, dp(STRIP_HEIGHT), Gravity.BOTTOM))

        // Right: the detail sheet.
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(32), dp(28), dp(28))
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                GradientDrawable().apply { setColor(SHEET) },
                GradientDrawable().apply { setStroke(dp(1), PAPER_FAINT) },
            )).apply { setLayerInset(1, 0, -dp(2), -dp(2), -dp(2)) }
            visibility = View.GONE
            isClickable = true
        }
        panelSubtitle = TextView(this).apply {
            textSize = 15f
            letterSpacing = 0.02f
            setTextColor(PAPER_DIM)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        panelTitle = TextView(this).apply {
            textSize = 38f
            typeface = serif
            setTextColor(PAPER)
        }
        header.addView(panelTitle, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(4) })
        header.addView(Button(this).apply {
            text = "×"
            textSize = 30f
            setTextColor(PAPER_DIM)
            background = null
            stateListAnimator = null
            setPadding(0, 0, 0, 0)
            contentDescription = "닫기"
            setOnClickListener { closePanel() }
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        panelBody = TextView(this).apply {
            setTextColor(PAPER)
            setLineSpacing(0f, 1.6f)
        }
        panelActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        panel.addView(panelSubtitle)
        panel.addView(header, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        panel.addView(View(this).apply { setBackgroundColor(PAPER_FAINT) }, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(16), 0, dp(16)) })
        bodyScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(panelBody) }
        panel.addView(bodyScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(panelActions)
        root.addView(panel, FrameLayout.LayoutParams(dp(560), -1, Gravity.END).apply { bottomMargin = dp(STRIP_HEIGHT) })

        drivingOverlay = TextView(this).apply {
            text = "운전 중에는 별을 볼 수 없어요.\n차를 세우면 보던 하늘을 이어서 보여 드릴게요."
            textSize = 32f
            typeface = serif
            gravity = Gravity.CENTER
            setTextColor(PAPER)
            setLineSpacing(0f, 1.45f)
            setBackgroundColor(0xF504060C.toInt())
            visibility = View.GONE
            isClickable = true
        }
        root.addView(drivingOverlay, FrameLayout.LayoutParams(-1, -1))

        // Opening sequence: the title block and the strip rise in while the sky fades up.
        rise(title, delay = 300)
        rise(strip, delay = 450)
        return root
    }

    private fun stripButton(label: String, onClick: () -> Unit) = StripButton(this).apply {
        text = label
        textSize = 18f
        minHeight = dp(STRIP_HEIGHT)
        minimumHeight = dp(STRIP_HEIGHT)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(22), 0, dp(22), 0)
        setOnClickListener { onClick() }
    }

    private fun divider() = View(this).apply { setBackgroundColor(PAPER_FAINT) }.also {
        it.layoutParams = LinearLayout.LayoutParams(dp(1), dp(32)).apply { setMargins(dp(14), 0, dp(14), 0) }
    }

    private fun paperButton(label: String, onClick: () -> Unit) = PaperButton(this).apply {
        text = label
        textSize = 19f
        setTypeface(typeface, Typeface.BOLD)
        minHeight = dp(68)
        setOnClickListener { onClick() }
    }

    private fun outlineButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 17f
        isAllCaps = false
        setTextColor(PAPER)
        background = GradientDrawable().apply { setStroke(dp(1), PAPER_FAINT) }
        minHeight = dp(60)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    /** A two-line choice on the sheet, such as a story or a place, separated from the next by a rule. */
    private fun addItem(title: String, detail: String, onClick: () -> Unit) {
        panelActions.addView(Button(this).apply {
            text = android.text.SpannableStringBuilder(title).apply {
                setSpan(android.text.style.TypefaceSpan(serif), 0, length, 0)
                setSpan(android.text.style.RelativeSizeSpan(1.25f), 0, length, 0)
                append("\n")
                val start = length
                append(detail)
                setSpan(android.text.style.ForegroundColorSpan(PAPER_DIM), start, length, 0)
                setSpan(android.text.style.RelativeSizeSpan(0.75f), start, length, 0)
            }
            textSize = 20f
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(PAPER)
            background = null
            stateListAnimator = null
            minHeight = dp(88)
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(-1, -2))
        panelActions.addView(View(this).apply { setBackgroundColor(PAPER_FAINT) }, LinearLayout.LayoutParams(-1, dp(1)))
    }

    /** A hairline along one edge: the bottom (an underline) or the top (the strip's upper rule). */
    private fun rule(bottom: Boolean) = android.graphics.drawable.LayerDrawable(arrayOf(
        GradientDrawable().apply { setStroke(dp(1), PAPER_FAINT) },
    )).apply {
        if (bottom) setLayerInset(0, -dp(2), -dp(2), -dp(2), 0) else setLayerInset(0, -dp(2), 0, -dp(2), -dp(2))
    }

    /** Fades [view] up from slightly below. */
    private fun rise(view: View, delay: Long = 0) {
        view.alpha = 0f
        view.translationY = dp(10).toFloat()
        view.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(600)
            .setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun fullWidth() = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(5), 0, dp(5)) }

    private fun weight() = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private val SKY = 0xFF04060C.toInt()
        private const val STRIP_HEIGHT = 76

        private const val LISTEN = "설명 듣기"
        private const val LISTEN_STORY = "이야기 듣기"
        private const val STOP = "그만 듣기"

        private const val LOCATION_PERMISSION = "pleos.car.permission.FUSED_LOCATION"
        private const val REQUEST_LOCATION = 1
    }
}
