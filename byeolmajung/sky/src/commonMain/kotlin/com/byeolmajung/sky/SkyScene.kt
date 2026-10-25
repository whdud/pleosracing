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

/**
 * A star drawn as a soft point: bright at the center and fading smoothly to nothing at [StarDot.EXTENT] × [radius],
 * like the blur of starlight in the eye or a camera, never as a hard-edged disc.
 * [glow] (0..1) adds a wider faint halo for the brightest stars.
 */
data class StarDot(val x: Float, val y: Float, val radius: Float, val color: Int, val glow: Float) {
    companion object {
        const val EXTENT = 3f

        /**
         * Brightness profile at distance [r] from the center, in units of the drawn extent (0..1).
         * A sharp core plus a softer wing, shared by every renderer so they look the same.
         */
        fun profile(r: Float): Float {
            val core = kotlin.math.exp(-(r * r) / (2 * 0.17f * 0.17f))
            val wing = 0.45f * kotlin.math.exp(-(r * r) / (2 * 0.36f * 0.36f))
            return (core + wing).coerceAtMost(1f) * (1 - r).coerceIn(0f, 1f)
        }
    }
}

data class LineSeg(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val color: Int, val width: Float)
/**
 * A disc for the Sun or the Moon. For the Moon, [north], [sun] and [west] are screen directions (radians, y down)
 * toward celestial north, toward the Sun and toward celestial west, used by [MoonSprite] to light and orient it.
 */
data class BodyDisc(
    val x: Float, val y: Float, val radius: Float, val color: Int, val kind: BodyKind, val phase: Double, val waxing: Boolean,
    val north: Float = 0f, val sun: Float = 0f, val west: Float = 0f,
)
data class Label(val text: String, val x: Float, val y: Float, val size: Float, val color: Int, val bold: Boolean = false)
data class Ring(val x: Float, val y: Float, val radius: Float, val color: Int, val width: Float)
/**
 * A constellation drawing placed on screen. The image's own coordinates (u, v from 0 to 1) map to screen pixels by
 * x = a·u + c·v + e and y = b·u + d·v + f, the affine map that puts its three anchor points on their stars.
 */
data class ArtPlacement(
    val file: String,
    val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float,
    val alpha: Float,
)

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
    /** Distant hills and trees standing on the horizon: a closed polygon painted in [terrainColor]. */
    val terrain: List<ScreenPoint>,
    val terrainColor: Int,
    /** Constellation drawings, painted over the Milky Way and under the lines and stars. */
    val art: List<ArtPlacement>,
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
    /** Constellation lines and names. Off by default for a natural sky; stories still highlight theirs. */
    val showConstellations: Boolean = false,
    /** Constellation drawings for every constellation. Highlighted and selected ones show theirs regardless. */
    val showArt: Boolean = false,
    /** 0 for a dark site, 1 for a city sky: fewer stars and a brighter horizon. */
    val skyQuality: Double = 0.0,
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
    private const val GROUND = 0xFF06080B.toInt()
    private const val TERRAIN = GROUND
    private const val HORIZON_LINE = 0xFF2C3A52.toInt()

    private val CARDINALS = listOf("북" to 0.0, "북동" to 45.0, "동" to 90.0, "남동" to 135.0, "남" to 180.0, "남서" to 225.0, "서" to 270.0, "북서" to 315.0)

    fun build(snapshot: SkySnapshot, camera: Camera, options: SceneOptions = SceneOptions()): SkyFrame {
        val d = options.density
        val sunAlt = snapshot.sunAltitude
        val limitingMagnitude = minOf(limitingMagnitude(sunAlt), 6.5 - 2.5 * options.skyQuality)
        val (skyTop, skyBottom) = skyColors(sunAlt)

        val lines = mutableListOf<LineSeg>()
        val stars = mutableListOf<StarDot>()
        val bodies = mutableListOf<BodyDisc>()
        val rings = mutableListOf<Ring>()
        val labels = mutableListOf<Label>()
        val hits = mutableListOf<HitTarget>()
        val art = constellationArt(snapshot, camera, options)

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
                        lines += LineSeg(p.x, p.y, q.x, q.y, color, width)
                    }
                }
                val lh = snapshot.constellationLabels[ci]
                if (lh.alt > 0) {
                    camera.project(lh)?.takeIf { camera.isOnScreen(it) }?.let { p ->
                        labels += Label(c.nameKo, p.x, p.y, (if (highlighted) 22f else 15f) * d, if (highlighted) LABEL_HIGHLIGHT else LABEL, highlighted)
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
            val glow = ((2.2 - star.magnitude) / 3.5).coerceIn(0.0, 1.0).toFloat()
            stars += StarDot(p.x, p.y, radius, withAlpha(bvToRgb(star.colorIndex), alpha), glow)
            if (star.magnitude < 4.5) {
                hits += HitTarget(p.x, p.y, radius + 6f * d, SkyObject.StarObject(i, star), priority = if (star.magnitude < 2) 1 else 2)
            }
            val name = star.nameKo
            if (name != null && star.magnitude < (if (options.showConstellations) 2.1 else 1.3)) {
                labels += Label(name, p.x + radius + 8f * d, p.y - radius - 6f * d, 13f * d, STAR_LABEL)
            }
            if (options.selected is SkyObject.StarObject && options.selected.index == i) {
                rings += Ring(p.x, p.y, radius + 14f * d, SELECTION, 2.5f * d)
            }
        }

        // Sun, Moon and planets.
        snapshot.bodies.forEach { (body, h) ->
            if (h.alt < -1) return@forEach
            val p = camera.project(h)?.takeIf { camera.isOnScreen(it, 30f) } ?: return@forEach
            val (radius, color) = when (body.kind) {
                BodyKind.SUN -> 18f * d to 0xFFFFE8A0.toInt()
                BodyKind.MOON -> 26f * d to 0xFFEDEBE0.toInt()
                BodyKind.PLANET -> starRadius(body.magnitude) * d to PLANET_COLORS.getValue(body.id)
            }
            if (body.kind == BodyKind.PLANET) {
                // To the naked eye a planet is a bright, steady point, not a disc.
                stars += StarDot(p.x, p.y, radius, color, glow = ((1.5 - body.magnitude) / 4).coerceIn(0.2, 1.0).toFloat())
            } else {
                val disc = BodyDisc(p.x, p.y, radius, color, body.kind, body.phase, body.waxing)
                bodies += if (body.kind == BodyKind.MOON) moonDirections(snapshot, camera, body, disc) else disc
            }
            labels += Label(body.nameKo, p.x + radius + 6f * d, p.y + radius + 14f * d, 15f * d, color, bold = true)
            hits += HitTarget(p.x, p.y, radius + 8f * d, SkyObject.BodyObject(body), priority = 0)
            if (options.selected is SkyObject.BodyObject && options.selected.body.id == body.id) {
                rings += Ring(p.x, p.y, radius + 14f * d, SELECTION, 2.5f * d)
            }
        }

        // Horizon, terrain, ground and compass directions.
        val horizon = horizonCircle(camera, HORIZON_LINE, 0f)
        val terrain = terrainPolygon(camera)
        val cardinalAlt = if (camera.mode == ViewMode.SUNROOF) 4.0 else 0.0
        val cardinalOffset = if (camera.mode == ViewMode.SUNROOF) 0f else 30f * d
        CARDINALS.forEach { (text, az) ->
            camera.project(cardinalAlt, az)?.takeIf { camera.isOnScreen(it) }?.let { p ->
                labels += Label(text, p.x, p.y + cardinalOffset, (if (text.length == 1) 22f else 16f) * d, CARDINAL, bold = text.length == 1)
            }
        }

        return SkyFrame(
            width = camera.width,
            height = camera.height,
            skyTop = skyTop,
            skyBottom = skyBottom,
            groundColor = GROUND,
            horizon = horizon,
            groundOutside = camera.mode == ViewMode.SUNROOF || camera.altitude > 0,
            terrain = terrain,
            terrainColor = TERRAIN,
            art = art,
            lines = lines,
            stars = stars,
            bodies = bodies,
            rings = rings,
            labels = declutter(labels),
            hitTargets = hits,
            isDaylight = sunAlt > -6,
        )
    }

    /** Screen directions toward celestial north, the Sun and celestial west, measured half a degree from the Moon. */
    private fun moonDirections(snapshot: SkySnapshot, camera: Camera, moon: Body, disc: BodyDisc): BodyDisc {
        val tf = HorizonTransform(snapshot.epochMillis, snapshot.observer)
        val m = moon.equatorial
        val c = camera.project(tf.toHorizontalOfDate(m)) ?: return disc
        fun angleTo(eq: Equatorial): Float? = camera.project(tf.toHorizontalOfDate(eq))?.let { p ->
            kotlin.math.atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble()).toFloat()
        }
        val north = angleTo(Equatorial(m.ra, (m.dec + 0.5).coerceAtMost(89.9))) ?: return disc
        val west = angleTo(Equatorial(m.ra - 0.5 / kotlin.math.cos(m.dec * DEG).coerceAtLeast(0.05), m.dec)) ?: return disc
        // Half a degree along the great circle from the Moon toward the Sun.
        val s = snapshot.bodies.first { it.first.kind == BodyKind.SUN }.first.equatorial
        fun unit(e: Equatorial) = doubleArrayOf(
            kotlin.math.cos(e.dec * DEG) * kotlin.math.cos(e.ra * DEG),
            kotlin.math.cos(e.dec * DEG) * kotlin.math.sin(e.ra * DEG),
            kotlin.math.sin(e.dec * DEG),
        )
        val mu = unit(m)
        val su = unit(s)
        val k = mu[0] * su[0] + mu[1] * su[1] + mu[2] * su[2]
        val t = DoubleArray(3) { su[it] - k * mu[it] }
        val tl = kotlin.math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2])
        val sun = if (tl < 1e-6) north else {
            val step = 0.5 * DEG
            val q = DoubleArray(3) { mu[it] * kotlin.math.cos(step) + t[it] / tl * kotlin.math.sin(step) }
            val ra = kotlin.math.atan2(q[1], q[0]) * RAD
            val dec = kotlin.math.asin(q[2].coerceIn(-1.0, 1.0)) * RAD
            angleTo(Equatorial((ra + 360) % 360, dec)) ?: north
        }
        return disc.copy(north = north, sun = sun, west = west)
    }

    private fun constellationArt(snapshot: SkySnapshot, camera: Camera, options: SceneOptions): List<ArtPlacement> {
        val selected = (options.selected as? SkyObject.ConstellationObject)?.constellation?.abbr
        if (!options.showArt && options.highlightConstellations.isEmpty() && selected == null) return emptyList()
        val sky = HorizonTransform(snapshot.epochMillis, snapshot.observer)
        val screen = hypot(camera.width.toFloat(), camera.height.toFloat())
        return snapshot.catalog.art.mapNotNull { art ->
            val featured = art.abbr in options.highlightConstellations || art.abbr == selected
            if (!options.showArt && !featured) return@mapNotNull null
            val points = art.anchors.map { anchor ->
                val h = sky.toHorizontal(anchor.star)
                if (h.alt < -10) return@mapNotNull null
                camera.project(h) ?: return@mapNotNull null
            }
            if (points.none { camera.isOnScreen(it, screen / 3) }) return@mapNotNull null
            val (a0, a1, a2) = art.anchors
            val x0 = points[0].x.toDouble(); val y0 = points[0].y.toDouble()
            val x1 = points[1].x - x0; val y1 = points[1].y - y0
            val x2 = points[2].x - x0; val y2 = points[2].y - y0
            val du1 = a1.u - a0.u; val dv1 = a1.v - a0.v
            val du2 = a2.u - a0.u; val dv2 = a2.v - a0.v
            val det = du1 * dv2 - du2 * dv1
            if (kotlin.math.abs(det) < 1e-6) return@mapNotNull null
            val a = (x1 * dv2 - x2 * dv1) / det
            val c = (x2 * du1 - x1 * du2) / det
            val b = (y1 * dv2 - y2 * dv1) / det
            val d = (y2 * du1 - y1 * du2) / det
            // Near the edge of a very wide view the drawing can stretch past usefulness; leave it out.
            if (hypot(a, b) > 3.0 * screen || hypot(c, d) > 3.0 * screen) return@mapNotNull null
            ArtPlacement(
                art.file,
                a.toFloat(), b.toFloat(), c.toFloat(), d.toFloat(),
                (x0 - a * a0.u - c * a0.v).toFloat(), (y0 - b * a0.u - d * a0.v).toFloat(),
                alpha = if (featured) 0.5f else 0.2f,
            )
        }
    }

    private val PLANET_COLORS = mapOf(
        "mercury" to 0xFFD9CBB0.toInt(),
        "venus" to 0xFFFFF4D6.toInt(),
        "mars" to 0xFFFF8A5C.toInt(),
        "jupiter" to 0xFFF5DDB5.toInt(),
        "saturn" to 0xFFEFD99A.toInt(),
    )

    /**
     * A band from just below the horizon up to the skyline: rolling hills with clumps of trees.
     * Only the part in front is built (points behind the viewer cannot be projected).
     */
    private fun terrainPolygon(camera: Camera): List<ScreenPoint> {
        // Looking up through the sunroof the skyline is barely visible and only makes the edge look ragged.
        if (camera.mode == ViewMode.SUNROOF) return emptyList()
        val (from, to) = camera.azimuth - 150 to camera.azimuth + 150
        val top = mutableListOf<ScreenPoint>()
        val bottom = mutableListOf<ScreenPoint>()
        var az = from
        while (az <= to + 1e-9) {
            camera.project(Landscape.skyline(az), az)?.let(top::add)
            camera.project(-3.0, az)?.let(bottom::add)
            az += 0.5
        }
        return top + bottom.asReversed()
    }

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

    internal fun starRadius(mag: Double): Float = (1.0 + (6.5 - mag).coerceAtLeast(0.0).pow(1.45) * 0.24).toFloat().coerceAtMost(6.5f)

    private fun starAlpha(mag: Double, limit: Double): Float = ((limit - mag + 0.5) / 2.0).coerceIn(0.2, 1.0).toFloat()

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

    private fun lerpColor(a: Int, b: Int, t: Double): Int {
        val k = t.coerceIn(0.0, 1.0)
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * k).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/** A procedural skyline: deterministic, so the same place always looks the same. */
object Landscape {
    /** Altitude of the skyline in degrees at an azimuth. */
    fun skyline(az: Double): Double {
        val a = az * DEG
        val hills = 0.55 + 0.45 * kotlin.math.sin(a * 2 + 0.7) + 0.3 * kotlin.math.sin(a * 5 + 2.1) + 0.15 * kotlin.math.sin(a * 11 + 0.4)
        return hills.coerceAtLeast(0.1) + trees(az)
    }

    private fun trees(az: Double): Double {
        // Clumps of trees where a slow noise is high; each tree a rounded bump about 1.5° wide.
        val clump = kotlin.math.sin(az * DEG * 7 + 1.3) + 0.6 * kotlin.math.sin(az * DEG * 17 + 4.0)
        if (clump < 0.55) return 0.0
        val cell = kotlin.math.floor(az / 1.6)
        val t = (az / 1.6 - cell) * 2 - 1
        val height = 0.5 + 1.0 * hash(cell) * (clump - 0.55)
        return height * kotlin.math.sqrt((1 - t * t).coerceAtLeast(0.0))
    }

    private fun hash(n: Double): Double {
        val x = kotlin.math.sin(n * 127.1 + 311.7) * 43758.5453
        return x - kotlin.math.floor(x)
    }
}
