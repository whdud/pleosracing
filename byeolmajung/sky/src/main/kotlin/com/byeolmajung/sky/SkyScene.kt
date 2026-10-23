package com.byeolmajung.sky

import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow

/** Something the user can tap on. */
sealed interface SkyObject {
    data class StarObject(val index: Int, val star: Star) : SkyObject
    data class BodyObject(val body: Body) : SkyObject
    data class ConstellationObject(val constellation: Constellation) : SkyObject
}

/** Draw primitives in screen pixels. Colors are ARGB. Platform renderers (Android Canvas, AWT preview) paint these. */
data class LineSeg(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val color: Int, val width: Float)
data class StarDot(val x: Float, val y: Float, val radius: Float, val color: Int, val glow: Float)
data class BodyDisc(val x: Float, val y: Float, val radius: Float, val color: Int, val kind: BodyKind, val phase: Double, val waxing: Boolean)
data class Label(val text: String, val x: Float, val y: Float, val size: Float, val color: Int, val bold: Boolean = false)
data class Ring(val x: Float, val y: Float, val radius: Float, val color: Int, val width: Float)
data class HitTarget(val x: Float, val y: Float, val radius: Float, val target: SkyObject, val priority: Int)

data class SkyFrame(
    val width: Int,
    val height: Int,
    val skyTop: Int,
    val skyBottom: Int,
    val groundColor: Int,
    /**
     * The horizon. Stereographic projection maps it to an exact circle; the ground is the region outside it
     * when [groundOutside] (view center above the horizon, always true in sunroof mode), otherwise inside.
     */
    val horizon: Ring,
    val groundOutside: Boolean,
    val lines: List<LineSeg>,
    val stars: List<StarDot>,
    val bodies: List<BodyDisc>,
    val rings: List<Ring>,
    val labels: List<Label>,
    val hitTargets: List<HitTarget>,
    /** True when the Sun is up or twilight is bright enough to hide most stars. */
    val isDaylight: Boolean,
) {
    /** The object nearest to a tap, preferring planets and bright stars over constellation labels. */
    fun pick(x: Float, y: Float, tolerance: Float): SkyObject? = hitTargets
        .map { it to hypot(it.x - x, it.y - y) }
        .filter { (t, d) -> d <= t.radius + tolerance }
        .minWithOrNull(compareBy<Pair<HitTarget, Float>>({ it.first.priority }, { it.second }))
        ?.first?.target
}

data class SceneOptions(
    val nightRed: Boolean = false,
    val showConstellations: Boolean = true,
    val highlightConstellations: Set<String> = emptySet(),
    val selected: SkyObject? = null,
    /** Multiplier for star and label sizes, e.g. display density. */
    val density: Float = 1f,
)

/** Turns a [SkySnapshot] into draw primitives through a [Camera]. */
object SkyScene {

    private const val LINE = 0xFF4E6FA8.toInt()
    private const val LINE_HIGHLIGHT = 0xFFFFD36B.toInt()
    private const val LABEL = 0xFF8FA6CC.toInt()
    private const val LABEL_HIGHLIGHT = 0xFFFFE29A.toInt()
    private const val STAR_LABEL = 0xFFDCE6F5.toInt()
    private const val CARDINAL = 0xFFB9C7DE.toInt()
    private const val SELECTION = 0xFF7FE0FF.toInt()
    private const val GROUND = 0xFF0A0D12.toInt()
    private const val HORIZON_LINE = 0xFF2C3A52.toInt()

    private val CARDINALS = listOf("북" to 0.0, "북동" to 45.0, "동" to 90.0, "남동" to 135.0, "남" to 180.0, "남서" to 225.0, "서" to 270.0, "북서" to 315.0)

    fun build(snapshot: SkySnapshot, camera: Camera, options: SceneOptions = SceneOptions()): SkyFrame {
        val d = options.density
        val tint: (Int) -> Int = if (options.nightRed) ::toNightRed else { c -> c }
        val sunAlt = snapshot.sunAltitude
        val limitingMagnitude = limitingMagnitude(sunAlt)
        val (skyTop, skyBottom) = skyColors(sunAlt)

        val lines = mutableListOf<LineSeg>()
        val stars = mutableListOf<StarDot>()
        val bodies = mutableListOf<BodyDisc>()
        val rings = mutableListOf<Ring>()
        val labels = mutableListOf<Label>()
        val hits = mutableListOf<HitTarget>()

        // Constellation lines and names.
        if (options.showConstellations || options.highlightConstellations.isNotEmpty()) {
            snapshot.catalog.constellations.forEachIndexed { ci, c ->
                val highlighted = c.abbr in options.highlightConstellations
                if (!options.showConstellations && !highlighted) return@forEachIndexed
                val color = if (highlighted) LINE_HIGHLIGHT else LINE
                val width = if (highlighted) 3.2f * d else 1.3f * d
                snapshot.constellationLines[ci].forEach { polyline ->
                    polyline.zipWithNext { a, b ->
                        if (a.alt < -2 && b.alt < -2) return@zipWithNext
                        val p = camera.project(a) ?: return@zipWithNext
                        val q = camera.project(b) ?: return@zipWithNext
                        if (!camera.isOnScreen(p, 200f) && !camera.isOnScreen(q, 200f)) return@zipWithNext
                        lines += LineSeg(p.x, p.y, q.x, q.y, tint(color), width)
                    }
                }
                val lh = snapshot.constellationLabels[ci]
                if (lh.alt > 0) {
                    camera.project(lh)?.takeIf { camera.isOnScreen(it) }?.let { p ->
                        labels += Label(c.nameKo, p.x, p.y, (if (highlighted) 22f else 15f) * d, tint(if (highlighted) LABEL_HIGHLIGHT else LABEL), highlighted)
                        hits += HitTarget(p.x, p.y, 28f * d, SkyObject.ConstellationObject(c), priority = 3)
                    }
                }
            }
        }

        // Stars; the catalog is ordered faintest first so bright ones are painted on top.
        for (i in snapshot.catalog.stars.indices) {
            val star = snapshot.catalog.stars[i]
            if (star.magnitude > limitingMagnitude) continue
            val h = snapshot.starHorizontal(i)
            if (h.alt < -1) continue
            val p = camera.project(h) ?: continue
            if (!camera.isOnScreen(p, 10f)) continue
            val radius = starRadius(star.magnitude) * d
            val alpha = starAlpha(star.magnitude, limitingMagnitude)
            stars += StarDot(p.x, p.y, radius, tint(withAlpha(bvToRgb(star.colorIndex), alpha)), glow = if (star.magnitude < 1.5) 1f else 0f)
            if (star.magnitude < 4.5) {
                hits += HitTarget(p.x, p.y, radius + 6f * d, SkyObject.StarObject(i, star), priority = if (star.magnitude < 2) 1 else 2)
            }
            val name = star.nameKo
            if (name != null && star.magnitude < 2.1) {
                labels += Label(name, p.x + radius + 6f * d, p.y - radius - 4f * d, 14f * d, tint(STAR_LABEL))
            }
            if (options.selected is SkyObject.StarObject && options.selected.index == i) {
                rings += Ring(p.x, p.y, radius + 14f * d, tint(SELECTION), 2.5f * d)
            }
        }

        // Sun, Moon and planets.
        snapshot.bodies.forEach { (body, h) ->
            if (h.alt < -1) return@forEach
            val p = camera.project(h)?.takeIf { camera.isOnScreen(it, 30f) } ?: return@forEach
            val (radius, color) = when (body.kind) {
                BodyKind.SUN -> 18f * d to 0xFFFFE8A0.toInt()
                BodyKind.MOON -> 16f * d to 0xFFEDEBE0.toInt()
                BodyKind.PLANET -> (starRadius(body.magnitude) + 1f) * d to PLANET_COLORS.getValue(body.id)
            }
            bodies += BodyDisc(p.x, p.y, radius, tint(color), body.kind, body.phase, body.waxing)
            labels += Label(body.nameKo, p.x + radius + 6f * d, p.y + radius + 14f * d, 15f * d, tint(color), bold = true)
            hits += HitTarget(p.x, p.y, radius + 8f * d, SkyObject.BodyObject(body), priority = 0)
            if (options.selected is SkyObject.BodyObject && options.selected.body.id == body.id) {
                rings += Ring(p.x, p.y, radius + 14f * d, tint(SELECTION), 2.5f * d)
            }
        }

        // Horizon, ground and compass directions.
        val horizon = horizonCircle(camera, tint(HORIZON_LINE), 2f * d)
        val cardinalAlt = if (camera.mode == ViewMode.SUNROOF) 4.0 else 0.0
        val cardinalOffset = if (camera.mode == ViewMode.SUNROOF) 0f else 30f * d
        CARDINALS.forEach { (text, az) ->
            camera.project(cardinalAlt, az)?.takeIf { camera.isOnScreen(it) }?.let { p ->
                labels += Label(text, p.x, p.y + cardinalOffset, (if (text.length == 1) 22f else 16f) * d, tint(CARDINAL), bold = text.length == 1)
            }
        }

        return SkyFrame(
            width = camera.width,
            height = camera.height,
            skyTop = tint(skyTop),
            skyBottom = tint(skyBottom),
            groundColor = tint(GROUND),
            horizon = horizon,
            groundOutside = camera.mode == ViewMode.SUNROOF || camera.altitude > 0,
            lines = lines,
            stars = stars,
            bodies = bodies,
            rings = rings,
            labels = declutter(labels),
            hitTargets = hits,
            isDaylight = sunAlt > -6,
        )
    }

    private val PLANET_COLORS = mapOf(
        "mercury" to 0xFFD9CBB0.toInt(),
        "venus" to 0xFFFFF4D6.toInt(),
        "mars" to 0xFFFF8A5C.toInt(),
        "jupiter" to 0xFFF5DDB5.toInt(),
        "saturn" to 0xFFEFD99A.toInt(),
    )

    /** Circle through three projected horizon points (stereographic projection maps circles to circles). */
    private fun horizonCircle(camera: Camera, color: Int, width: Float): Ring {
        val a = camera.project(0.0, camera.azimuth - 60)!!
        val b = camera.project(0.0, camera.azimuth)!!
        val c = camera.project(0.0, camera.azimuth + 60)!!
        val ax = a.x.toDouble(); val ay = a.y.toDouble()
        val bx = b.x.toDouble(); val by = b.y.toDouble()
        val cx = c.x.toDouble(); val cy = c.y.toDouble()
        val det = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
        val a2 = ax * ax + ay * ay
        val b2 = bx * bx + by * by
        val c2 = cx * cx + cy * cy
        val ox = (a2 * (by - cy) + b2 * (cy - ay) + c2 * (ay - by)) / det
        val oy = (a2 * (cx - bx) + b2 * (ax - cx) + c2 * (bx - ax)) / det
        return Ring(ox.toFloat(), oy.toFloat(), hypot(ax - ox, ay - oy).toFloat(), color, width)
    }

    /**
     * Drops labels that would overlap one already placed. Labels are added in priority order
     * (constellations, stars, planets, compass), so place from the end: compass and planets win.
     */
    private fun declutter(labels: List<Label>): List<Label> {
        val placed = mutableListOf<Label>()
        val boxes = mutableListOf<FloatArray>()
        for (label in labels.asReversed().sortedByDescending { it.bold }) {
            val w = textWidth(label)
            val box = floatArrayOf(label.x - w / 2, label.y - label.size, label.x + w / 2, label.y + label.size * 0.25f)
            if (boxes.none { it[0] < box[2] && box[0] < it[2] && it[1] < box[3] && box[1] < it[3] }) {
                boxes += box
                placed += label
            }
        }
        return placed
    }

    private fun textWidth(label: Label): Float =
        label.text.sumOf { ch -> if (ch.code in 0xAC00..0xD7A3) 1.0 else 0.58 }.toFloat() * label.size

    /** Faintest magnitude visible for the Sun's altitude (dark sky ≈ 6.5). */
    internal fun limitingMagnitude(sunAlt: Double): Double = when {
        sunAlt <= -18 -> 6.5
        sunAlt >= 0 -> -1.5
        else -> -1.5 + (6.5 + 1.5) * (-sunAlt / 18.0)
    }

    private fun skyColors(sunAlt: Double): Pair<Int, Int> = when {
        sunAlt <= -18 -> 0xFF03050C.toInt() to 0xFF0B1426.toInt()
        sunAlt <= -6 -> lerpColor(0xFF03050C.toInt(), 0xFF0E1B3D.toInt(), (sunAlt + 18) / 12) to
            lerpColor(0xFF0B1426.toInt(), 0xFF2B3A66.toInt(), (sunAlt + 18) / 12)
        sunAlt <= 0 -> lerpColor(0xFF0E1B3D.toInt(), 0xFF3C6EB4.toInt(), (sunAlt + 6) / 6) to
            lerpColor(0xFF2B3A66.toInt(), 0xFFE7A26A.toInt(), (sunAlt + 6) / 6)
        else -> 0xFF3E7CC9.toInt() to 0xFFA8CBEB.toInt()
    }

    internal fun starRadius(mag: Double): Float = (1.0 + (6.5 - mag).coerceAtLeast(0.0).pow(1.35) * 0.42).toFloat().coerceAtMost(9f)

    private fun starAlpha(mag: Double, limit: Double): Float = ((limit - mag + 0.6) / 1.6).coerceIn(0.25, 1.0).toFloat()

    /** Approximate star color from the B−V color index (Ballesteros temperature, then a blackbody fit). */
    internal fun bvToRgb(bv: Double): Int {
        val b = bv.coerceIn(-0.4, 2.0)
        val t = 4600 * (1 / (0.92 * b + 1.7) + 1 / (0.92 * b + 0.62)) / 100.0
        val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
        val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
        val bl = when {
            t >= 66 -> 255.0
            t <= 19 -> 0.0
            else -> 138.5177312231 * ln(t - 10) - 305.0447927307
        }
        // Blend toward white: real stars look much less saturated to the eye than blackbody colors.
        fun c(v: Double) = (v.coerceIn(0.0, 255.0) * 0.55 + 255 * 0.45).toInt()
        return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(bl)
    }

    private fun withAlpha(color: Int, alpha: Float): Int = ((alpha * 255).toInt() shl 24) or (color and 0xFFFFFF)

    /** Night-vision palette: keep brightness, drop everything but red. */
    internal fun toNightRed(color: Int): Int {
        val a = color ushr 24
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val lum = (0.3 * r + 0.59 * g + 0.11 * b).toInt().coerceIn(0, 255)
        return (a shl 24) or (lum shl 16) or ((lum * 0.12).toInt() shl 8) or (lum * 0.08).toInt()
    }

    private fun lerpColor(a: Int, b: Int, t: Double): Int {
        val k = t.coerceIn(0.0, 1.0)
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * k).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
