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
import kotlin.test.assertTrue

/**
 * Renders the same [SkyFrame] primitives the Android app paints, to PNG files under build/preview,
 * so the sky can be checked visually without an emulator.
 */
class SkyPreviewTest {

    private val catalog: SkyCatalog by lazy {
        val dir = File(System.getProperty("assets.dir"), "sky")
        SkyCatalog.parse(File(dir, "stars.csv").bufferedReader(), File(dir, "constellations.txt").bufferedReader())
    }

    private val outDir = File(System.getProperty("preview.dir")).apply { mkdirs() }

    // Yeongyang Firefly Eco Park, a dark-sky park in Korea.
    private val yeongyang = Observer(36.656, 129.112)

    @Test
    fun catalogLoads() {
        assertTrue(catalog.stars.size > 8000)
        assertTrue(catalog.constellations.size >= 88)
        assertTrue(catalog.constellation("Ori")?.nameKo == "오리온자리")
    }

    @Test
    fun renderPreviews() {
        val winter = ZonedDateTime.parse("2026-12-20T23:00:00+09:00").toInstant().toEpochMilli()
        val summer = ZonedDateTime.parse("2026-08-12T22:00:00+09:00").toInstant().toEpochMilli()
        val tonight = ZonedDateTime.parse("2026-10-23T21:00:00+09:00").toInstant().toEpochMilli()

        render("winter_south_windshield", SkySnapshot.compute(catalog, winter, yeongyang),
            Camera(ViewMode.WINDSHIELD, 180.0, 25.0, 100.0, 1920, 720), SceneOptions(highlightConstellations = setOf("Ori")))
        render("summer_sunroof", SkySnapshot.compute(catalog, summer, yeongyang),
            Camera(ViewMode.SUNROOF, 0.0, 90.0, 180.0, 1920, 720), SceneOptions(highlightConstellations = setOf("Lyr")))
        render("tonight_south_nightred", SkySnapshot.compute(catalog, tonight, yeongyang),
            Camera(ViewMode.WINDSHIELD, 180.0, 25.0, 100.0, 1920, 720), SceneOptions(nightRed = true))
        render("tonight_north_bigdipper", SkySnapshot.compute(catalog, tonight, yeongyang),
            Camera(ViewMode.WINDSHIELD, 0.0, 25.0, 100.0, 1920, 720), SceneOptions(highlightConstellations = setOf("UMa")))
    }

    private fun render(name: String, snapshot: SkySnapshot, camera: Camera, options: SceneOptions) {
        val frame = SkyScene.build(snapshot, camera, options)
        val img = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        g.paint = GradientPaint(0f, 0f, Color(frame.skyTop, true), 0f, frame.height.toFloat(), Color(frame.skyBottom, true))
        g.fillRect(0, 0, frame.width, frame.height)

        frame.lines.forEach {
            g.color = Color(it.color, true)
            g.stroke = BasicStroke(it.width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.drawLine(it.x1.toInt(), it.y1.toInt(), it.x2.toInt(), it.y2.toInt())
        }
        frame.stars.forEach {
            if (it.glow > 0) {
                val c = Color(it.color, true)
                g.color = Color(c.red, c.green, c.blue, 50)
                g.fill(Ellipse2D.Float(it.x - it.radius * 2.6f, it.y - it.radius * 2.6f, it.radius * 5.2f, it.radius * 5.2f))
            }
            g.color = Color(it.color, true)
            g.fill(Ellipse2D.Float(it.x - it.radius, it.y - it.radius, it.radius * 2, it.radius * 2))
        }
        frame.bodies.forEach {
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
        g.color = Color(h.color, true)
        g.stroke = BasicStroke(h.width)
        g.draw(Ellipse2D.Double((h.x - h.radius).toDouble(), (h.y - h.radius).toDouble(), h.radius * 2.0, h.radius * 2.0))

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
