package com.byeolmajung.web

import com.byeolmajung.sky.BackgroundImage
import com.byeolmajung.sky.Camera
import com.byeolmajung.sky.Describe
import com.byeolmajung.sky.HorizonTransform
import com.byeolmajung.sky.Horizontal
import com.byeolmajung.sky.Observer
import com.byeolmajung.sky.SceneOptions
import com.byeolmajung.sky.SkyBackground
import com.byeolmajung.sky.SkyCatalog
import com.byeolmajung.sky.SkyFrame
import com.byeolmajung.sky.SkyObject
import com.byeolmajung.sky.SkyScene
import com.byeolmajung.sky.SkySnapshot
import com.byeolmajung.sky.SkyTexture
import com.byeolmajung.sky.Stories
import com.byeolmajung.sky.Story
import com.byeolmajung.sky.ViewMode
import com.byeolmajung.sky.Visibility
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.events.WheelEvent
import org.w3c.dom.pointerevents.PointerEvent
import kotlin.js.Date
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Web demo of the 별마중 car app. Same sky engine as the Android app; the screen, buttons and story flow
 * mirror MainActivity. Car-only features (car position and heading, cabin lights, sunroof blind) are
 * simulated: a preset place and a fixed heading; turning the lights off is only announced.
 */
fun main() {
    // Start as soon as the page structure exists; don't wait for web fonts or images.
    if (document.readyState.toString() == "loading") {
        document.addEventListener("DOMContentLoaded", { WebApp().start() })
    } else {
        WebApp().start()
    }
}

private class Place(val name: String, val observer: Observer, val heading: Double, val skyQuality: Double)

private class WebApp {
    private val places = listOf(
        Place("서울", Observer(37.5665, 126.9780), 180.0, 0.8),
        Place("영양 국제밤하늘보호공원", Observer(36.656, 129.112), 180.0, 0.0),
        Place("화천 조경철천문대", Observer(38.153, 127.531), 180.0, 0.1),
        Place("제주", Observer(33.450, 126.570), 180.0, 0.4),
    )

    // Korea Standard Time: the places are in Korea, whatever the viewer's own time zone.
    private val utcOffsetMinutes = 540

    private val canvas = el<HTMLCanvasElement>("sky")
    private val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
    private val renderer = WebRenderer()

    private var catalog: SkyCatalog? = null
    private var background: SkyBackground? = null
    private var place = places[1]
    private var timeOffset = 0.0
    private var fastForward = false
    private var restricted = false
    private var options = SceneOptions(skyQuality = place.skyQuality)
    private var lightsOff = false
    private var selected: SkyObject? = null
    private var activeStory: Story? = null
    private var storyPage = 0

    // View state, as in the Android SkyView.
    private var mode = ViewMode.WINDSHIELD
    private var headingOffset = 0.0
    private var altitude = DEFAULT_ALTITUDE
    private var fov = DEFAULT_FOV
    private var snapshot: SkySnapshot? = null
    private var frame: SkyFrame? = null
    private var bgImage: BackgroundImage? = null
    private var bgKey = ""
    private var redrawQueued = false
    private var animation: ((Double) -> Boolean)? = null

    private val now: Double get() = Date.now() + timeOffset
    private val viewAzimuth: Double get() = ((place.heading + headingOffset) % 360 + 360) % 360
    private val density: Double get() = window.devicePixelRatio

    fun start() {
        wireControls()
        wireGestures()
        window.onresize = { redraw() }
        load()
        window.setInterval({
            if (fastForward) timeOffset += 10 * 60_000.0 / 30 // 10 minutes of sky per second
            if (fastForward || Date.now().toLong() % 10_000 < 40) refreshSky()
        }, 33)
    }

    // --- Data ---

    private fun load() {
        window.fetch("sky/stars.csv").then { r -> r.text() }.then { stars ->
            window.fetch("sky/constellations.txt").then { r -> r.text() }.then { cons ->
                catalog = SkyCatalog.parse(stars, cons)
                el<HTMLElement>("loading").hidden = true
                refreshSky()
                // Fade the sky in once the first frame is drawn.
                window.requestAnimationFrame { canvas.classList.add("ready") }
            }
        }
        val img = document.createElement("img") as HTMLImageElement
        img.onload = {
            val c = document.createElement("canvas") as HTMLCanvasElement
            c.width = img.naturalWidth
            c.height = img.naturalHeight
            val g = c.getContext("2d") as CanvasRenderingContext2D
            g.drawImage(img, 0.0, 0.0)
            val data = g.getImageData(0.0, 0.0, c.width.toDouble(), c.height.toDouble()).data
            val px = IntArray(c.width * c.height) { i ->
                val d = data.asDynamic()
                (0xFF shl 24) or ((d[i * 4] as Int) shl 16) or ((d[i * 4 + 1] as Int) shl 8) or (d[i * 4 + 2] as Int)
            }
            background = SkyBackground(SkyTexture(c.width, c.height, px))
            bgKey = ""
            redraw()
        }
        img.src = "sky/milkyway.jpg"
    }

    private fun refreshSky() {
        val cat = catalog ?: return
        snapshot = SkySnapshot.compute(cat, now.toLong(), place.observer)
        el<HTMLElement>("daylight").hidden = !(snapshot!!.sunAltitude > -6 && !restricted)
        updateStatus()
        redraw()
    }

    // --- Drawing ---

    private fun redraw() {
        if (redrawQueued) return
        redrawQueued = true
        window.requestAnimationFrame { t ->
            redrawQueued = false
            animation?.let { if (!it(t)) animation = null else redraw() }
            paint()
        }
    }

    private fun camera(): Camera {
        val w = (canvas.clientWidth * density).roundToInt().coerceAtLeast(1)
        val h = (canvas.clientHeight * density).roundToInt().coerceAtLeast(1)
        if (canvas.width != w || canvas.height != h) {
            canvas.width = w
            canvas.height = h
        }
        return Camera(mode, viewAzimuth, altitude, fov, w, h)
    }

    private fun paint() {
        val snap = snapshot ?: return
        val cam = camera()
        val f = SkyScene.build(snap, cam, options.copy(density = density.toFloat()))
        frame = f
        val bg = background?.let { sky ->
            val key = "${snap.epochMillis}|$mode|$viewAzimuth|$altitude|$fov|${cam.width}x${cam.height}|${options.skyQuality}"
            if (key != bgKey) {
                bgImage = sky.render(snap, cam, scaleDown = 5, skyQuality = options.skyQuality)
                bgKey = key
            }
            bgImage
        }
        renderer.draw(ctx, f, bg)
    }

    private fun lookAt(target: Horizontal) {
        if (mode == ViewMode.SUNROOF) return
        val offset = ((target.az - place.heading + 540) % 360) - 180
        animateTo(offset, (target.alt - 10).coerceIn(DEFAULT_ALTITUDE, 70.0))
    }

    private fun animateTo(offset: Double, alt: Double) {
        val startOffset = headingOffset
        val startAlt = altitude
        val delta = ((offset - startOffset + 540) % 360) - 180
        var t0 = -1.0
        animation = { t ->
            if (t0 < 0) t0 = t
            val k = ((t - t0) / 700).coerceIn(0.0, 1.0)
            val e = 1 - (1 - k) * (1 - k)
            headingOffset = if (k >= 1) offset else startOffset + delta * e
            altitude = startAlt + (alt - startAlt) * e
            updateStatus()
            k < 1
        }
        redraw()
    }

    // --- Gestures: drag to look around, wheel or pinch to zoom, tap to select ---

    private fun wireGestures() {
        val pointers = HashMap<Int, Pair<Double, Double>>()
        var downAt: Pair<Double, Double>? = null
        var moved = false
        var pinchStart = 0.0
        var fovStart = fov

        canvas.addEventListener("pointerdown", { e ->
            e as PointerEvent
            canvas.setPointerCapture(e.pointerId)
            pointers[e.pointerId] = e.clientX.toDouble() to e.clientY.toDouble()
            animation = null
            if (pointers.size == 1) {
                downAt = e.clientX.toDouble() to e.clientY.toDouble()
                moved = false
            } else if (pointers.size == 2) {
                val (a, b) = pointers.values.toList()
                pinchStart = hypot(a.first - b.first, a.second - b.second)
                fovStart = fov
                moved = true
            }
        })
        canvas.addEventListener("pointermove", { e ->
            e as PointerEvent
            val prev = pointers[e.pointerId] ?: return@addEventListener
            val cur = e.clientX.toDouble() to e.clientY.toDouble()
            pointers[e.pointerId] = cur
            if (pointers.size >= 2) {
                val (a, b) = pointers.values.toList()
                val d = hypot(a.first - b.first, a.second - b.second)
                if (pinchStart > 0) zoomTo(fovStart * pinchStart / d)
                return@addEventListener
            }
            val start = downAt ?: return@addEventListener
            if (hypot(cur.first - start.first, cur.second - start.second) > 6) moved = true
            if (!moved) return@addEventListener
            val ppd = camera().pixelsPerDegree / density
            val dx = cur.first - prev.first
            val dy = cur.second - prev.second
            if (mode == ViewMode.SUNROOF) {
                headingOffset -= dx / ppd * 0.5
            } else {
                headingOffset -= dx / ppd
                altitude = (altitude + dy / ppd).coerceIn(5.0, 80.0)
            }
            headingOffset = ((headingOffset + 540) % 360) - 180
            updateStatus()
            redraw()
        })
        val end = { e: org.w3c.dom.events.Event ->
            e as PointerEvent
            pointers.remove(e.pointerId)
            if (pointers.isEmpty()) {
                if (!moved) {
                    val rect = canvas.getBoundingClientRect()
                    val x = ((e.clientX - rect.left) * density).toFloat()
                    val y = ((e.clientY - rect.top) * density).toFloat()
                    onSelect(frame?.pick(x, y, (24 * density).toFloat()))
                }
                downAt = null
            }
        }
        canvas.addEventListener("pointerup", end)
        canvas.addEventListener("pointercancel", end)
        canvas.addEventListener("wheel", { e ->
            e as WheelEvent
            e.preventDefault()
            zoomTo(fov * (1 + e.deltaY / 800))
        })
    }

    private fun zoomTo(value: Double) {
        fov = value.coerceIn(if (mode == ViewMode.SUNROOF) 90.0..200.0 else 30.0..150.0)
        redraw()
    }

    // --- Controls ---

    private fun wireControls() {
        button("back-now") { timeOffset = 0.0; setFastForward(false); animateTo(0.0, DEFAULT_ALTITUDE); refreshSky() }
        button("minus") { timeOffset -= HOUR; refreshSky() }
        button("plus") { timeOffset += HOUR; refreshSky() }
        button("play") { setFastForward(!fastForward) }
        button("seg-windshield") { setMode(ViewMode.WINDSHIELD) }
        button("seg-sunroof") { setMode(ViewMode.SUNROOF) }
        button("night") { toggleLights() }
        button("lines") {
            options = options.copy(showConstellations = !options.showConstellations)
            setOn("lines", options.showConstellations)
            redraw()
        }
        button("stories") { showStoryList() }
        button("place") { choosePlace() }
        button("tonight") { jumpToTonight() }
        button("drive") { setRestricted(true) }
        button("park") { setRestricted(false) }
        button("panel-close") { closePanel() }
    }

    private fun setFastForward(on: Boolean) {
        fastForward = on
        text("play", if (on) "멈춤" else "빠르게")
        el<HTMLElement>("play").classList.toggle("on", on)
    }

    private fun setOn(id: String, on: Boolean) {
        el<HTMLElement>(id).classList.toggle("on", on)
        el<HTMLElement>(id).setAttribute("aria-pressed", on.toString())
    }

    private fun setMode(m: ViewMode) {
        if (mode == m) return
        mode = m
        fov = if (mode == ViewMode.SUNROOF) 180.0 else DEFAULT_FOV
        setOn("seg-windshield", mode == ViewMode.WINDSHIELD)
        setOn("seg-sunroof", mode == ViewMode.SUNROOF)
        if (mode == ViewMode.SUNROOF) toast("선루프 가림막을 열고 머리 위 하늘을 보여 드려요 (실제 차에서는 가림막이 자동으로 열려요)")
        updateStatus()
        redraw()
    }

    /** Stargazing mode: turn every light in the car off so eyes adapt to the dark. The web demo can only announce it. */
    private fun toggleLights() {
        lightsOff = !lightsOff
        setOn("night", lightsOff)
        toast(
            if (lightsOff) "실내등, 독서등, 무드등을 모두 껐어요. 눈이 어둠에 익으면 별이 더 많이 보여요. (웹 데모에서는 안내만 해요)"
            else "조명을 원래대로 켰어요",
        )
    }

    private fun setRestricted(r: Boolean) {
        restricted = r
        el<HTMLElement>("driving").hidden = !r
        if (r) {
            setFastForward(false)
            stopSpeech()
        }
        refreshSky()
    }

    private fun jumpToTonight() {
        val local = Date.now() + utcOffsetMinutes * 60_000.0
        val midnight = kotlin.math.floor(local / DAY) * DAY - utcOffsetMinutes * 60_000.0
        timeOffset = midnight + 21 * HOUR - Date.now()
        refreshSky()
    }

    private fun updateStatus() {
        val t = now
        val today = dayNumber(Date.now())
        val day = when (dayNumber(t)) {
            today -> "오늘"
            today + 1 -> "내일"
            today - 1 -> "어제"
            else -> localDate(t).let { "${it.getUTCMonth() + 1}월 ${it.getUTCDate()}일" }
        }
        val d = localDate(t)
        val hour = d.getUTCHours()
        text("clock", "${if (hour < 12) "오전" else "오후"} ${if (hour % 12 == 0) 12 else hour % 12}:${d.getUTCMinutes().toString().padStart(2, '0')}")
        val o = place.observer
        text("date", "${d.getUTCMonth() + 1}월 ${d.getUTCDate()}일 ${"일월화수목금토"[d.getUTCDay()]}요일 · $day")
        text("coords", "${Describe.oneDecimal(o.latitude)}°N  ${Describe.oneDecimal(o.longitude)}°E")
        text("place", place.name)
        el<HTMLElement>("back-now").hidden = timeOffset == 0.0
    }

    private fun localDate(millis: Double) = Date(millis + utcOffsetMinutes * 60_000.0)
    private fun dayNumber(millis: Double) = kotlin.math.floor((millis + utcOffsetMinutes * 60_000.0) / DAY).toInt()

    // --- Selection, panel and stories ---

    private fun onSelect(obj: SkyObject?) {
        val cat = catalog ?: return
        stopSpeech()
        selected = obj
        activeStory = null
        options = options.copy(selected = obj, highlightConstellations = emptySet())
        redraw()
        if (obj == null) return closePanel()
        val position = positionOf(obj)
        val d = Describe.of(obj, cat, position)
        showPanel(d.title, d.subtitle, d.body)
        listenButton = addPanelButton(LISTEN, kind = "primary") {
            if (speaking) stopSpeech() else speak(Describe.narration(obj, cat, position))
        }
        d.story?.let { story -> addPanelButton("'${story.title}' 이야기 보기", kind = "ghost") { startStory(story) } }
    }

    private fun positionOf(obj: SkyObject): Horizontal? = snapshot?.let { snap ->
        when (obj) {
            is SkyObject.StarObject -> snap.starHorizontal(obj.index)
            is SkyObject.BodyObject -> snap.bodies.firstOrNull { it.first.id == obj.body.id }?.second
            is SkyObject.ConstellationObject -> HorizonTransform(snap.epochMillis, snap.observer).toHorizontal(obj.constellation.label)
        }
    }

    private fun showStoryList() {
        stopSpeech()
        showPanel("별자리 이야기", "이야기를 고르면 하늘에서 그 별자리를 찾아 드려요", "")
        Stories.all.forEach { story -> addItem(story.title, "${story.season} 별자리") { startStory(story) } }
    }

    private fun startStory(story: Story, readAloud: Boolean = false) {
        stopSpeech()
        selected = null
        activeStory = story
        storyPage = 0
        options = options.copy(selected = null, highlightConstellations = story.constellations.toSet())
        val h = HorizonTransform(now.toLong(), place.observer).toHorizontal(story.focus)
        if (h.alt > 10) {
            lookAt(h)
        } else {
            Visibility.nextGoodTime(story.focus, place.observer, now.toLong(), utcOffsetMinutes)?.let { t ->
                timeOffset = t - Date.now()
                refreshSky()
                lookAt(HorizonTransform(t, place.observer).toHorizontal(story.focus))
                val d = localDate(t.toDouble())
                val hr = d.getUTCHours()
                toast("지금은 잘 안 보여서, 잘 보이는 ${d.getUTCMonth() + 1}월 ${d.getUTCDate()}일 ${if (hr < 12) "오전" else "오후"} ${if (hr % 12 == 0) 12 else hr % 12}시 하늘로 이동했어요")
            }
        }
        showStoryPage()
        if (readAloud) readStory()
        redraw()
    }

    private fun showStoryPage() {
        val story = activeStory ?: return
        showPanel(story.title, "${story.season} 별자리 이야기  ·  ${storyPage + 1} / ${story.pages.size}", story.pages[storyPage], storyText = true)
        listenButton = addPanelButton(LISTEN_STORY, kind = "primary") {
            if (readingStory || speaking) stopSpeech() else readStory()
        }
        if (readingStory) setListen(STOP)
        val row = document.createElement("div") as HTMLElement
        row.className = "row"
        el<HTMLElement>("panel-actions").appendChild(row)
        if (storyPage > 0) addPanelButton("이전", kind = "ghost", into = row) { turnPage(-1) }
        if (storyPage < story.pages.lastIndex) {
            addPanelButton("다음", kind = "ghost", into = row) { turnPage(1) }
        } else {
            addPanelButton("다른 이야기", kind = "ghost", into = row) { showStoryList() }
        }
    }

    private fun turnPage(delta: Int) {
        stopSpeech()
        storyPage += delta
        showStoryPage()
    }

    /** Reads the story aloud from the current page, turning pages as it goes. */
    private fun readStory() {
        val story = activeStory ?: return
        readingStory = true
        setListen(STOP)
        speak(story.pages[storyPage]) {
            if (readingStory && activeStory == story && storyPage < story.pages.lastIndex) {
                storyPage++
                showStoryPage()
                readStory()
            } else {
                readingStory = false
                setListen(LISTEN_STORY)
            }
        }
    }

    private fun choosePlace() {
        stopSpeech()
        showPanel("어디서 볼까요?", "웹 데모에서는 차 위치 대신 장소를 골라요. 장소마다 하늘의 밝기(도시 불빛)도 달라요.", "")
        places.forEach { p ->
            val darkness = when {
                p.skyQuality < 0.2 -> "아주 어두운 하늘"
                p.skyQuality < 0.5 -> "꽤 어두운 하늘"
                else -> "도시의 밝은 하늘"
            }
            addItem(p.name, darkness) {
                place = p
                options = options.copy(skyQuality = p.skyQuality)
                closePanel()
                refreshSky()
            }
        }
    }

    private fun showPanel(title: String, subtitle: String, body: String, storyText: Boolean = false) {
        text("panel-title", title)
        text("panel-subtitle", subtitle)
        el<HTMLElement>("panel-subtitle").hidden = subtitle.isEmpty()
        val bodyEl = el<HTMLElement>("panel-body")
        bodyEl.textContent = body
        bodyEl.hidden = body.isEmpty()
        bodyEl.classList.toggle("story", storyText)
        el<HTMLElement>("panel-actions").innerHTML = ""
        listenButton = null
        val panel = el<HTMLElement>("panel")
        if (panel.hidden) {
            panel.hidden = false // the sheet slides in by CSS
        } else {
            // Already open: turn the page instead of sliding the whole sheet again.
            bodyEl.classList.remove("turn")
            bodyEl.asDynamic().offsetWidth
            bodyEl.classList.add("turn")
        }
    }

    private fun setListen(label: String) {
        val b = listenButton ?: return
        b.textContent = label
        b.classList.toggle("speaking", label == STOP)
    }

    private fun closePanel() {
        stopSpeech()
        el<HTMLElement>("panel").hidden = true
        activeStory = null
        selected = null
        listenButton = null
        options = options.copy(selected = null, highlightConstellations = emptySet())
        redraw()
    }

    private fun addPanelButton(label: String, kind: String, into: HTMLElement? = null, onClick: () -> Unit): HTMLButtonElement {
        val b = document.createElement("button") as HTMLButtonElement
        b.type = "button"
        b.textContent = label
        b.className = kind
        b.onclick = { onClick() }
        (into ?: el("panel-actions")).appendChild(b)
        return b
    }

    /** A two-line choice in the panel, such as a story or a place. */
    private fun addItem(title: String, detail: String, onClick: () -> Unit) {
        val b = document.createElement("button") as HTMLButtonElement
        b.type = "button"
        b.className = "item"
        b.appendChild((document.createElement("b") as HTMLElement).apply { textContent = title })
        b.appendChild((document.createElement("span") as HTMLElement).apply { textContent = detail })
        b.onclick = { onClick() }
        el<HTMLElement>("panel-actions").appendChild(b)
    }

    // --- 설명 듣기: the browser's speech synthesis stands in for the car's text-to-speech ---

    private val synth: dynamic get() = window.asDynamic().speechSynthesis
    private var speaking = false
    private var readingStory = false
    private var speechToken = 0
    private var listenButton: HTMLButtonElement? = null

    /**
     * Speaks [text] in Korean and calls [onEnd] when it finishes (not when stopped). Long text goes sentence by
     * sentence, since some browsers cut a single long utterance short.
     */
    private fun speak(text: String, onEnd: () -> Unit = {}) {
        val s = synth
        if (s == null || s == undefined) {
            toast("이 브라우저에서는 소리 내어 읽기를 할 수 없어요")
            return
        }
        s.cancel()
        val token = ++speechToken
        val voices = (s.getVoices() as Array<dynamic>)
        val korean = voices.firstOrNull { (it.lang as String).startsWith("ko") }
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        speaking = true
        setListen(STOP)
        sentences.forEachIndexed { i, sentence ->
            val u: dynamic = js("new SpeechSynthesisUtterance()")
            u.text = sentence
            u.lang = "ko-KR"
            u.rate = 0.95
            if (korean != null) u.voice = korean
            if (i == sentences.lastIndex) {
                u.onend = {
                    if (token == speechToken) {
                        speaking = false
                        if (!readingStory) setListen(if (activeStory != null) LISTEN_STORY else LISTEN)
                        onEnd()
                    }
                }
            }
            s.speak(u)
        }
    }

    private fun stopSpeech() {
        speechToken++
        readingStory = false
        if (speaking) {
            speaking = false
            synth?.cancel()
        }
        setListen(if (activeStory != null) LISTEN_STORY else LISTEN)
    }

    private var toastTimer = 0

    private fun toast(message: String) {
        val t = el<HTMLElement>("toast")
        t.textContent = message
        t.hidden = false
        window.clearTimeout(toastTimer)
        toastTimer = window.setTimeout({ t.hidden = true }, 4500)
    }

    // --- DOM helpers ---

    @Suppress("UNCHECKED_CAST")
    private fun <T : HTMLElement> el(id: String): T = document.getElementById(id) as T

    private fun text(id: String, value: String) {
        el<HTMLElement>(id).textContent = value
    }

    private fun button(id: String, onClick: () -> Unit) {
        el<HTMLButtonElement>(id).onclick = { onClick() }
    }

    companion object {
        const val DEFAULT_FOV = 100.0
        const val DEFAULT_ALTITUDE = 15.0
        const val HOUR = 3_600_000.0
        const val DAY = 24 * HOUR

        const val LISTEN = "설명 듣기"
        const val LISTEN_STORY = "이야기 듣기"
        const val STOP = "그만 듣기"
    }
}
