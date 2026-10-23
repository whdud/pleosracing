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
        button("now") { timeOffset = 0.0; fastForward = false; text("play", "▶ 빠르게"); animateTo(0.0, DEFAULT_ALTITUDE); refreshSky() }
        button("minus") { timeOffset -= 3_600_000.0; refreshSky() }
        button("plus") { timeOffset += 3_600_000.0; refreshSky() }
        button("play") { fastForward = !fastForward; text("play", if (fastForward) "■ 멈춤" else "▶ 빠르게") }
        button("mode") { toggleMode() }
        button("night") { toggleLights() }
        button("lines") {
            options = options.copy(showConstellations = !options.showConstellations)
            text("lines", if (options.showConstellations) "별자리 선 끄기" else "별자리 선")
            redraw()
        }
        button("stories") { showStoryList() }
        button("place") { choosePlace() }
        button("tonight") { jumpToTonight() }
        button("drive") { setRestricted(true) }
        button("park") { setRestricted(false) }
    }

    private fun toggleMode() {
        mode = if (mode == ViewMode.WINDSHIELD) ViewMode.SUNROOF else ViewMode.WINDSHIELD
        fov = if (mode == ViewMode.SUNROOF) 180.0 else DEFAULT_FOV
        text("mode", if (mode == ViewMode.SUNROOF) "앞유리 보기" else "선루프 보기")
        if (mode == ViewMode.SUNROOF) toast("선루프 가림막을 열고 머리 위 하늘을 보여 드려요 (실제 차에서는 가림막이 자동으로 열려요)")
        updateStatus()
        redraw()
    }

    /** Stargazing mode: turn every light in the car off so eyes adapt to the dark. The web demo can only announce it. */
    private fun toggleLights() {
        lightsOff = !lightsOff
        text("night", if (lightsOff) "관측 모드 끄기" else "관측 모드")
        toast(
            if (lightsOff) "실내등, 독서등, 무드등을 모두 껐어요. 눈이 어둠에 익으면 별이 더 많이 보여요. (웹 데모에서는 안내만 해요)"
            else "조명을 원래대로 켰어요",
        )
    }

    private fun setRestricted(r: Boolean) {
        restricted = r
        el<HTMLElement>("driving").hidden = !r
        if (r) {
            fastForward = false
            text("play", "▶ 빠르게")
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
        val clock = "${if (hour < 12) "오전" else "오후"} ${if (hour % 12 == 0) 12 else hour % 12}:${d.getUTCMinutes().toString().padStart(2, '0')}"
        val facing = if (mode == ViewMode.SUNROOF) "머리 위 하늘" else "${Describe.compass(viewAzimuth)}쪽 하늘"
        text("status", "${place.name}  ·  $day $clock  ·  $facing" + if (timeOffset != 0.0) "  (시간 이동 중)" else "")
    }

    private fun localDate(millis: Double) = Date(millis + utcOffsetMinutes * 60_000.0)
    private fun dayNumber(millis: Double) = kotlin.math.floor((millis + utcOffsetMinutes * 60_000.0) / DAY).toInt()

    // --- Selection, panel and stories ---

    private fun onSelect(obj: SkyObject?) {
        val cat = catalog ?: return
        options = options.copy(selected = obj, highlightConstellations = activeStory?.constellations?.toSet().orEmpty())
        redraw()
        if (obj == null) return closePanel()
        val d = Describe.of(obj, cat, positionOf(obj))
        activeStory = null
        showPanel(d.title, d.subtitle, d.body)
        d.story?.let { story -> addPanelButton("'${story.title}' 이야기 듣기") { startStory(story) } }
        addPanelButton("닫기", secondary = true) { onSelect(null) }
    }

    private fun positionOf(obj: SkyObject): Horizontal? = snapshot?.let { snap ->
        when (obj) {
            is SkyObject.StarObject -> snap.starHorizontal(obj.index)
            is SkyObject.BodyObject -> snap.bodies.firstOrNull { it.first.id == obj.body.id }?.second
            is SkyObject.ConstellationObject -> HorizonTransform(snap.epochMillis, snap.observer).toHorizontal(obj.constellation.label)
        }
    }

    private fun showStoryList() {
        showPanel("별자리 이야기", "이야기를 고르면 하늘에서 그 별자리를 찾아 드려요", "")
        Stories.all.forEach { story -> addPanelButton("${story.title}  ·  ${story.season}") { startStory(story) } }
        addPanelButton("닫기", secondary = true) { closePanel() }
    }

    private fun startStory(story: Story) {
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
        redraw()
    }

    private fun showStoryPage() {
        val story = activeStory ?: return
        showPanel(story.title, "${story.season} 별자리 이야기  ·  ${storyPage + 1} / ${story.pages.size}", story.pages[storyPage], storyText = true)
        val row = document.createElement("div") as HTMLElement
        row.className = "row"
        el<HTMLElement>("panel-actions").appendChild(row)
        if (storyPage > 0) addPanelButton("이전", into = row) { storyPage--; showStoryPage() }
        if (storyPage < story.pages.lastIndex) {
            addPanelButton("다음", into = row) { storyPage++; showStoryPage() }
        } else {
            addPanelButton("다른 이야기", into = row) { showStoryList() }
        }
        addPanelButton("닫기", secondary = true) { closePanel() }
    }

    private fun choosePlace() {
        showPanel("장소 선택", "웹 데모에서는 차 위치 대신 장소를 골라요. 장소마다 하늘의 밝기(도시 불빛)도 달라요.", "")
        places.forEach { p ->
            addPanelButton(p.name) {
                place = p
                options = options.copy(skyQuality = p.skyQuality)
                closePanel()
                refreshSky()
            }
        }
        addPanelButton("닫기", secondary = true) { closePanel() }
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
        el<HTMLElement>("panel").hidden = false
    }

    private fun closePanel() {
        el<HTMLElement>("panel").hidden = true
        activeStory = null
        options = options.copy(selected = null, highlightConstellations = emptySet())
        redraw()
    }

    private fun addPanelButton(label: String, secondary: Boolean = false, into: HTMLElement? = null, onClick: () -> Unit) {
        val b = document.createElement("button") as HTMLButtonElement
        b.textContent = label
        b.className = if (secondary) "btn secondary" else "btn"
        b.onclick = { onClick() }
        (into ?: el("panel-actions")).appendChild(b)
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
    }
}
