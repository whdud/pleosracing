package com.byeolmajung.web

import com.byeolmajung.sky.ArtPlacement
import com.byeolmajung.sky.BackgroundImage
import com.byeolmajung.sky.BodyDisc
import com.byeolmajung.sky.BodyKind
import com.byeolmajung.sky.SkyFrame
import com.byeolmajung.sky.StarDot
import kotlinx.browser.document
import org.khronos.webgl.Uint8ClampedArray
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.ImageData
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Paints [SkyFrame] primitives on an HTML canvas; the web twin of the Android SkyCanvasRenderer.
 * [onArtLoaded] asks for a repaint once a constellation drawing has downloaded.
 */
class WebRenderer(private val onArtLoaded: () -> Unit = {}) {

    private val sprites = HashMap<Int, HTMLCanvasElement>()
    private var bgCanvas: HTMLCanvasElement? = null
    private val artImages = HashMap<String, HTMLImageElement>()

    fun draw(ctx: CanvasRenderingContext2D, frame: SkyFrame, background: BackgroundImage?) {
        val w = frame.width.toDouble()
        val h = frame.height.toDouble()
        ctx.globalAlpha = 1.0
        val gradient = ctx.createLinearGradient(0.0, 0.0, 0.0, h)
        gradient.addColorStop(0.0, css(frame.skyTop))
        gradient.addColorStop(1.0, css(frame.skyBottom))
        ctx.fillStyle = gradient
        ctx.fillRect(0.0, 0.0, w, h)

        background?.let { drawBackground(ctx, frame, it) }
        frame.art.forEach { drawArt(ctx, it) }

        ctx.asDynamic().lineCap = "round"
        frame.lines.forEach {
            ctx.strokeStyle = css(it.color)
            ctx.lineWidth = it.width.toDouble()
            ctx.beginPath()
            ctx.moveTo(it.x1.toDouble(), it.y1.toDouble())
            ctx.lineTo(it.x2.toDouble(), it.y2.toDouble())
            ctx.stroke()
        }
        frame.stars.forEach { drawStar(ctx, it) }
        ctx.globalAlpha = 1.0
        frame.bodies.forEach { drawBody(ctx, it) }
        drawGround(ctx, frame)

        frame.rings.forEach {
            ctx.strokeStyle = css(it.color)
            ctx.lineWidth = it.width.toDouble()
            ctx.beginPath()
            ctx.arc(it.x.toDouble(), it.y.toDouble(), it.radius.toDouble(), 0.0, 2 * PI)
            ctx.stroke()
        }
        ctx.asDynamic().textAlign = "center"
        frame.labels.forEach {
            ctx.fillStyle = css(it.color)
            ctx.font = "${if (it.bold) "700" else "400"} ${it.size}px 'IBM Plex Sans KR', 'Apple SD Gothic Neo', 'Malgun Gothic', sans-serif"
            ctx.fillText(it.text, it.x.toDouble(), it.y.toDouble())
        }
    }

    private fun drawArt(ctx: CanvasRenderingContext2D, art: ArtPlacement) {
        val img = artImages.getOrPut(art.file) {
            (document.createElement("img") as HTMLImageElement).apply {
                onload = { onArtLoaded() }
                src = "sky/art/${art.file}"
            }
        }
        if (!img.complete || img.naturalWidth == 0) return
        val w = img.naturalWidth.toDouble()
        val h = img.naturalHeight.toDouble()
        ctx.save()
        ctx.globalAlpha = art.alpha.toDouble()
        ctx.setTransform(art.a / w, art.b / w, art.c / h, art.d / h, art.e.toDouble(), art.f.toDouble())
        ctx.drawImage(img, 0.0, 0.0)
        ctx.restore()
    }

    private fun drawBackground(ctx: CanvasRenderingContext2D, frame: SkyFrame, bg: BackgroundImage) {
        val canvas = bgCanvas?.takeIf { it.width == bg.width && it.height == bg.height }
            ?: (document.createElement("canvas") as HTMLCanvasElement).also {
                it.width = bg.width
                it.height = bg.height
                bgCanvas = it
            }
        val c = canvas.getContext("2d") as CanvasRenderingContext2D
        val data = Uint8ClampedArray(bg.width * bg.height * 4)
        // Write through dynamic: Kotlin's set(Byte) is signed and would clamp values above 127 to 0.
        val d = data.asDynamic()
        for (i in bg.pixels.indices) {
            val p = bg.pixels[i]
            d[i * 4] = (p shr 16) and 0xFF
            d[i * 4 + 1] = (p shr 8) and 0xFF
            d[i * 4 + 2] = p and 0xFF
            d[i * 4 + 3] = p ushr 24
        }
        c.putImageData(ImageData(data, bg.width, bg.height), 0.0, 0.0)
        ctx.imageSmoothingEnabled = true
        ctx.drawImage(canvas, 0.0, 0.0, frame.width.toDouble(), frame.height.toDouble())
    }

    /** A soft point sprite per (quantized) star color, alpha following [StarDot.profile]. */
    private fun sprite(rgb: Int): HTMLCanvasElement = sprites.getOrPut(rgb) {
        val size = 48
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.width = size
        canvas.height = size
        val c = canvas.getContext("2d") as CanvasRenderingContext2D
        val data = Uint8ClampedArray(size * size * 4)
        val d = data.asDynamic()
        val center = (size - 1) / 2f
        for (y in 0 until size) for (x in 0 until size) {
            val r = hypot(x - center, y - center) / center
            val i = (y * size + x) * 4
            // White core, star color in the wings.
            val white = (1 - r / 0.15f).coerceIn(0f, 1f)
            d[i] = (((rgb shr 16) and 0xFF) + (255 - ((rgb shr 16) and 0xFF)) * white).toInt()
            d[i + 1] = (((rgb shr 8) and 0xFF) + (255 - ((rgb shr 8) and 0xFF)) * white).toInt()
            d[i + 2] = ((rgb and 0xFF) + (255 - (rgb and 0xFF)) * white).toInt()
            d[i + 3] = (StarDot.profile(r) * 255).toInt()
        }
        c.putImageData(ImageData(data, size, size), 0.0, 0.0)
        canvas
    }

    private fun drawStar(ctx: CanvasRenderingContext2D, star: StarDot) {
        val rgb = star.color and 0xFFFFFF
        val alpha = (star.color ushr 24) / 255.0
        val x = star.x.toDouble()
        val y = star.y.toDouble()
        if (star.glow > 0f) {
            val r = star.radius * StarDot.EXTENT * (2f + 2.5f * star.glow)
            val g = ctx.createRadialGradient(x, y, 0.0, x, y, r.toDouble())
            g.addColorStop(0.0, css(rgb, 0.16 * star.glow))
            g.addColorStop(1.0, css(rgb, 0.0))
            ctx.globalAlpha = 1.0
            ctx.fillStyle = g
            ctx.beginPath()
            ctx.arc(x, y, r.toDouble(), 0.0, 2 * PI)
            ctx.fill()
        }
        val e = (star.radius * StarDot.EXTENT).toDouble()
        ctx.globalAlpha = alpha
        ctx.drawImage(sprite(rgb and 0xF0F0F0), x - e, y - e, e * 2, e * 2)
    }

    private fun drawGround(ctx: CanvasRenderingContext2D, frame: SkyFrame) {
        val h = frame.horizon
        ctx.fillStyle = css(frame.groundColor)
        ctx.beginPath()
        if (frame.groundOutside) ctx.rect(0.0, 0.0, frame.width.toDouble(), frame.height.toDouble())
        ctx.arc(h.x.toDouble(), h.y.toDouble(), h.radius.toDouble(), 0.0, 2 * PI)
        ctx.asDynamic().fill("evenodd")

        if (frame.terrain.isNotEmpty()) {
            ctx.fillStyle = css(frame.terrainColor)
            ctx.beginPath()
            frame.terrain.forEachIndexed { i, p -> if (i == 0) ctx.moveTo(p.x.toDouble(), p.y.toDouble()) else ctx.lineTo(p.x.toDouble(), p.y.toDouble()) }
            ctx.closePath()
            ctx.fill()
        }
    }

    private fun drawBody(ctx: CanvasRenderingContext2D, b: BodyDisc) {
        val x = b.x.toDouble()
        val y = b.y.toDouble()
        val r = b.radius.toDouble()
        val rgb = b.color and 0xFFFFFF
        val halo = ctx.createRadialGradient(x, y, 0.0, x, y, r * 7)
        halo.addColorStop(0.0, css(rgb, 0.47))
        halo.addColorStop(0.25, css(rgb, 0.16))
        halo.addColorStop(1.0, css(rgb, 0.0))
        ctx.fillStyle = halo
        ctx.beginPath()
        ctx.arc(x, y, r * 7, 0.0, 2 * PI)
        ctx.fill()
        if (b.kind != BodyKind.MOON) {
            ctx.fillStyle = css(b.color)
            ctx.beginPath()
            ctx.arc(x, y, r, 0.0, 2 * PI)
            ctx.fill()
            return
        }
        // Dark disc, then the lit half plus or minus a half ellipse (waxing lit on the right).
        ctx.fillStyle = "#2a2c33"
        ctx.beginPath()
        ctx.arc(x, y, r, 0.0, 2 * PI)
        ctx.fill()
        val k = b.phase
        val terminator = abs(1 - 2 * k) * r
        val start = if (b.waxing) -PI / 2 else PI / 2
        ctx.fillStyle = css(b.color)
        ctx.beginPath()
        ctx.arc(x, y, r, start, start + PI)
        ctx.ellipse(x, y, terminator, r, 0.0, start + PI, start, anticlockwise = k <= 0.5)
        ctx.closePath()
        ctx.fill()
    }

    companion object {
        fun css(argb: Int): String = css(argb and 0xFFFFFF, (argb ushr 24) / 255.0)

        fun css(rgb: Int, alpha: Double): String =
            "rgba(${(rgb shr 16) and 0xFF},${(rgb shr 8) and 0xFF},${rgb and 0xFF},${(alpha * 1000).toInt() / 1000.0})"
    }
}
