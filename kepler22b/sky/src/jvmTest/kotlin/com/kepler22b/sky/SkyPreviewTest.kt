package com.byeolmajung.sky

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.time.ZonedDateTime
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renders the same [SkyFrame] primitives the Android app paints, to PNG files under build/preview,
 * so the sky can be checked visually without an emulator.
 */
class SkyPreviewTest {

    private val catalog: SkyCatalog by lazy {
        val dir = File(System.getProperty("assets.dir"), "sky")
        SkyCatalog.parse(File(dir, "stars.csv").readText(), File(dir, "constellations.txt").readText(), File(dir, "art.txt").readText())
    }

    private val outDir = File(System.getProperty("preview.dir")).apply { mkdirs() }

    private val background: SkyBackground by lazy {
        val img = ImageIO.read(File(System.getProperty("assets.dir"), "sky/milkyway.jpg"))
        SkyBackground(SkyTexture(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width)))
    }

    // Yeongyang Firefly Eco Park, a dark-sky park in Korea.
    private val yeongyang = Observer(36.656, 129.112)

    @Test
    fun catalogLoads() {
        assertTrue(catalog.stars.size > 8000)
        assertTrue(catalog.constellations.size >= 88)
        assertTrue(catalog.constellation("Ori")?.nameKo == "오리온자리")
    }

    private val moonMap: SkyTexture by lazy {
        val img = ImageIO.read(File(System.getProperty("assets.dir"), "sky/moon.jpg"))
        SkyTexture(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
    }

    /** Phases as seen facing south (north up, west to the right), plus the Moon from real frames over a month. */
    @Test
    fun moonPreview() {
        val size = 220
        val out = BufferedImage(size * 6 + 70, size * 2 + 60, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.color = Color(0x0B1020)
        g.fillRect(0, 0, out.width, out.height)
        listOf(0.06, 0.25, 0.5, 0.75, 0.93, 1.0).forEachIndexed { i, phase ->
            val px = MoonSprite.render(size, moonMap, phase, north = -Math.PI / 2, sun = 0.0, west = 0.0)
            val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, size, size, px, 0, size) }
            g.drawImage(img, 10 + i * (size + 10), 10, null)
        }
        // Real evenings: every 4 days from a new Moon, 19:00 in Korea, the view turned toward the Moon.
        val start = ZonedDateTime.parse("2026-11-09T19:00:00+09:00")
        for (i in 0 until 6) {
            val t = start.plusDays(3L + 4 * i)
            val snap = SkySnapshot.compute(catalog, t.toInstant().toEpochMilli(), yeongyang)
            val (moon, h) = snap.bodies.first { it.first.kind == BodyKind.MOON }
            val camera = Camera(ViewMode.WINDSHIELD, h.az, h.alt.coerceAtLeast(5.0), 6.0, size, size)
            val disc = SkyScene.build(snap, camera).bodies.firstOrNull { it.kind == BodyKind.MOON } ?: continue
            val px = MoonSprite.render(size / 2, moonMap, disc.phase, disc.north.toDouble(), disc.sun.toDouble(), disc.west.toDouble())
            val img = BufferedImage(size / 2, size / 2, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, size / 2, size / 2, px, 0, size / 2) }
            g.drawImage(img, 10 + i * (size + 10) + size / 4, size + 40, null)
            g.color = Color(0x8E9BB3)
            g.drawString("${t.toLocalDate()} alt ${h.alt.toInt()}° ${(moon.phase * 100).toInt()}%", 10 + i * (size + 10), size + 30)
        }
        g.dispose()
        ImageIO.write(out, "png", File(outDir, "moon.png"))
    }

    @Test
    fun constellationArtSitsOnItsAnchorStars() {
        assertTrue(catalog.art.size >= 80)
        val snap = SkySnapshot.compute(catalog, ZonedDateTime.parse("2026-01-15T22:00:00+09:00").toInstant().toEpochMilli(), yeongyang)
        val camera = Camera(ViewMode.WINDSHIELD, 180.0, 30.0, 100.0, 1920, 720)
        val frame = SkyScene.build(snap, camera, SceneOptions(highlightConstellations = setOf("Ori")))
        val orion = frame.art.single()
        assertEquals("Ori.png", orion.file)
        val sky = HorizonTransform(snap.epochMillis, snap.observer)
        catalog.art.first { it.abbr == "Ori" }.anchors.forEach { anchor ->
            val star = camera.project(sky.toHorizontal(anchor.star))!!
            val x = orion.a * anchor.u + orion.c * anchor.v + orion.e
            val y = orion.b * anchor.u + orion.d * anchor.v + orion.f
            assertTrue(kotlin.math.abs(x - star.x) < 0.5 && kotlin.math.abs(y - star.y) < 0.5, "anchor off by ${x - star.x}, ${y - star.y}")
        }
        assertTrue(SkyScene.build(snap, camera).art.isEmpty())
    }

    @Test
    fun gleoFindsObjectsBySpokenNames() {
        val snap = SkySnapshot.compute(catalog, ZonedDateTime.parse("2026-08-12T22:00:00+09:00").toInstant().toEpochMilli(), yeongyang)
        val vega = Gleo.find("베가", snap) as SkyObject.StarObject
        assertEquals("Vega", vega.star.properName)
        assertEquals("Vega", (Gleo.find("직녀성", snap) as SkyObject.StarObject).star.properName)
        assertEquals("jupiter", (Gleo.find("목성", snap) as SkyObject.BodyObject).body.id)
        assertEquals("Ori", (Gleo.find("오리온", snap) as SkyObject.ConstellationObject).constellation.abbr)
        assertEquals("Ori", (Gleo.find("오리온자리", snap) as SkyObject.ConstellationObject).constellation.abbr)
        assertEquals("Cyg", (Gleo.find("백조자리", snap) as SkyObject.ConstellationObject).constellation.abbr)
        assertEquals("Cyg", (Gleo.find("고니자리", snap) as SkyObject.ConstellationObject).constellation.abbr)
        assertEquals(null, Gleo.find("없는별", snap))
        assertEquals("견우와 직녀", Gleo.findStory("견우 직녀")?.title)

        val json = Gleo.describe(vega, snap, snap.starHorizontal(vega.index)).toJson()
        assertTrue(json.startsWith("{\"functionName\":\"DESCRIBE_SKY_OBJECT\",\"status\":\"success\""), json)
        // data is a JSON array serialized into a string, so its quotes are escaped.
        assertTrue("\"data\":\"[{\\\"name\\\":\\\"직녀성\\\"" in json, json)
        assertTrue(json.endsWith(",\"mode\":\"voice\"}"), json)
    }

    @Test
    fun kepler22bIsMarkedInCygnus() {
        // A summer evening: Cygnus is high in the east.
        val snap = SkySnapshot.compute(catalog, ZonedDateTime.parse("2026-08-12T22:00:00+09:00").toInstant().toEpochMilli(), yeongyang)
        assertEquals(SkyObject.Kepler22b, Gleo.find("케플러-22b", snap))
        val h = HorizonTransform(snap.epochMillis, snap.observer).toHorizontal(SkyObject.Kepler22b.position)
        assertTrue(h.alt > 50, "altitude ${h.alt}")
        val frame = SkyScene.build(snap, Camera(ViewMode.WINDSHIELD, h.az, h.alt, 60.0, 1920, 720))
        val hit = frame.hitTargets.single { it.target == SkyObject.Kepler22b }
        assertEquals(SkyObject.Kepler22b, frame.pick(hit.x, hit.y, 4f))
        assertTrue(frame.labels.any { it.text == "케플러-22b" })
        assertTrue(Describe.narration(SkyObject.Kepler22b, catalog, h).startsWith("케플러-22b는 백조자리에 있는 외계 행성이에요."))
    }

    @Test
    fun renderPreviews() {
        val winter = ZonedDateTime.parse("2026-12-20T23:00:00+09:00").toInstant().toEpochMilli()
        val summer = ZonedDateTime.parse("2026-08-12T22:00:00+09:00").toInstant().toEpochMilli()
        val tonight = ZonedDateTime.parse("2026-10-23T21:00:00+09:00").toInstant().toEpochMilli()

        val newMoonSummer = ZonedDateTime.parse("2026-08-12T22:30:00+09:00").toInstant().toEpochMilli()
        val seoul = Observer(37.5665, 126.9780)

        render("summer_south_milkyway", SkySnapshot.compute(catalog, newMoonSummer, yeongyang),
            Camera(ViewMode.WINDSHIELD, 175.0, 15.0, 100.0, 1920, 720), SceneOptions())
        render("summer_sunroof", SkySnapshot.compute(catalog, summer, yeongyang),
            Camera(ViewMode.SUNROOF, 0.0, 90.0, 180.0, 1920, 720), SceneOptions(highlightConstellations = setOf("Lyr", "Aql")))
        render("winter_south_windshield", SkySnapshot.compute(catalog, winter, yeongyang),
            Camera(ViewMode.WINDSHIELD, 180.0, 15.0, 100.0, 1920, 720), SceneOptions(highlightConstellations = setOf("Ori")))
        render("tonight_south_lines", SkySnapshot.compute(catalog, tonight, yeongyang),
            Camera(ViewMode.WINDSHIELD, 180.0, 15.0, 100.0, 1920, 720), SceneOptions(showConstellations = true))
        render("winter_moon_west", SkySnapshot.compute(catalog, winter, yeongyang),
            Camera(ViewMode.WINDSHIELD, 250.0, 15.0, 100.0, 1920, 720), SceneOptions())
        render("tonight_seoul_city", SkySnapshot.compute(catalog, tonight, seoul),
            Camera(ViewMode.WINDSHIELD, 180.0, 15.0, 100.0, 1920, 720), SceneOptions(skyQuality = 0.8))
    }

    private fun render(name: String, snapshot: SkySnapshot, camera: Camera, options: SceneOptions) {
        val frame = SkyScene.build(snapshot, camera, options)
        val img = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        g.paint = GradientPaint(0f, 0f, Color(frame.skyTop, true), 0f, frame.height.toFloat(), Color(frame.skyBottom, true))
        g.fillRect(0, 0, frame.width, frame.height)

        val bg = background.render(snapshot, camera, 4, options.skyQuality)
        val bgImg = BufferedImage(bg.width, bg.height, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, bg.width, bg.height, bg.pixels, 0, bg.width) }
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(bgImg, 0, 0, frame.width, frame.height, null)

        frame.lines.forEach {
            g.color = Color(it.color, true)
            g.stroke = BasicStroke(it.width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.drawLine(it.x1.toInt(), it.y1.toInt(), it.x2.toInt(), it.y2.toInt())
        }
        val stops = floatArrayOf(0f, 0.06f, 0.12f, 0.2f, 0.3f, 0.45f, 0.65f, 1f)
        frame.stars.forEach {
            val c = Color(it.color, true)
            if (it.glow > 0) {
                val r = it.radius * StarDot.EXTENT * (2f + 2.5f * it.glow)
                g.paint = java.awt.RadialGradientPaint(it.x, it.y, r, floatArrayOf(0f, 1f),
                    arrayOf(Color(c.red, c.green, c.blue, (40 * it.glow).toInt()), Color(c.red, c.green, c.blue, 0)))
                g.fill(Ellipse2D.Float(it.x - r, it.y - r, r * 2, r * 2))
            }
            val r = it.radius * StarDot.EXTENT
            // Whiter at the center, star color in the wings.
            val colors = stops.map { t ->
                val k = StarDot.profile(t)
                val white = (1 - t / 0.15f).coerceIn(0f, 1f)
                Color(
                    (c.red + (255 - c.red) * white).toInt(),
                    (c.green + (255 - c.green) * white).toInt(),
                    (c.blue + (255 - c.blue) * white).toInt(),
                    (c.alpha * k).toInt().coerceIn(0, 255),
                )
            }.toTypedArray()
            g.paint = java.awt.RadialGradientPaint(it.x, it.y, r, stops, colors)
            g.fill(Ellipse2D.Float(it.x - r, it.y - r, r * 2, r * 2))
        }
        frame.bodies.forEach {
            if (it.kind == BodyKind.MOON || it.kind == BodyKind.SUN) {
                val c = Color(it.color, true)
                val r = it.radius * 7f
                g.paint = java.awt.RadialGradientPaint(it.x, it.y, r, floatArrayOf(0f, 0.25f, 1f),
                    arrayOf(Color(c.red, c.green, c.blue, 120), Color(c.red, c.green, c.blue, 40), Color(c.red, c.green, c.blue, 0)))
                g.fill(Ellipse2D.Float(it.x - r, it.y - r, r * 2, r * 2))
            }
            g.color = Color(it.color, true)
            g.fill(Ellipse2D.Float(it.x - it.radius, it.y - it.radius, it.radius * 2, it.radius * 2))
        }

        g.color = Color(frame.groundColor, true)
        val h = frame.horizon
        val circle = Area(Ellipse2D.Double((h.x - h.radius).toDouble(), (h.y - h.radius).toDouble(), h.radius * 2.0, h.radius * 2.0))
        val groundArea = if (frame.groundOutside) {
            Area(Rectangle2D.Float(0f, 0f, frame.width.toFloat(), frame.height.toFloat())).apply { subtract(circle) }
        } else circle
        g.fill(groundArea)
        g.color = Color(frame.terrainColor, true)
        val terrain = java.awt.geom.Path2D.Float()
        if (frame.terrain.isNotEmpty()) {
            frame.terrain.forEachIndexed { i, p -> if (i == 0) terrain.moveTo(p.x, p.y) else terrain.lineTo(p.x, p.y) }
            terrain.closePath()
            g.fill(terrain)
        }

        frame.rings.forEach {
            g.color = Color(it.color, true)
            g.stroke = BasicStroke(it.width)
            g.draw(Ellipse2D.Float(it.x - it.radius, it.y - it.radius, it.radius * 2, it.radius * 2))
        }
        frame.labels.forEach {
            g.color = Color(it.color, true)
            g.font = Font(Font.SANS_SERIF, if (it.bold) Font.BOLD else Font.PLAIN, it.size.toInt())
            val w = g.fontMetrics.stringWidth(it.text)
            g.drawString(it.text, it.x - w / 2f, it.y)
        }
        g.dispose()
        ImageIO.write(img, "png", File(outDir, "$name.png"))
    }
}
