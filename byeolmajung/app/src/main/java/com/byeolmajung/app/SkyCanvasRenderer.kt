package com.byeolmajung.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.byeolmajung.sky.BackgroundImage
import com.byeolmajung.sky.BodyDisc
import com.byeolmajung.sky.BodyKind
import com.byeolmajung.sky.SkyFrame
import com.byeolmajung.sky.StarDot
import kotlin.math.abs

/** Paints [SkyFrame] primitives on an Android canvas. Mirrors the JVM preview renderer in the sky module's tests. */
class SkyCanvasRenderer {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val ground = Path()
    private val terrain = Path()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()
    private var backgroundBitmap: Bitmap? = null
    private val moon = Path()
    private val oval = RectF()
    private var skyShaderKey = 0L
    private var skyShader: Shader? = null

    /** @param background the Milky Way / airglow / moonlight layer, drawn scaled up over the sky gradient. */
    fun draw(canvas: Canvas, frame: SkyFrame, background: BackgroundImage?) {
        drawSky(canvas, frame)
        background?.let { drawBackground(canvas, frame, it) }

        frame.lines.forEach {
            stroke.color = it.color
            stroke.strokeWidth = it.width
            canvas.drawLine(it.x1, it.y1, it.x2, it.y2, stroke)
        }
        frame.stars.forEach { drawStar(canvas, it) }
        frame.bodies.forEach { drawBody(canvas, it) }

        drawGround(canvas, frame)

        frame.rings.forEach {
            stroke.color = it.color
            stroke.strokeWidth = it.width
            canvas.drawCircle(it.x, it.y, it.radius, stroke)
        }
        frame.labels.forEach {
            text.color = it.color
            text.textSize = it.size
            text.typeface = if (it.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            canvas.drawText(it.text, it.x, it.y, text)
        }
    }

    private fun drawSky(canvas: Canvas, frame: SkyFrame) {
        val key = (frame.skyTop.toLong() shl 32) xor frame.skyBottom.toLong() xor (frame.height.toLong() shl 16)
        if (key != skyShaderKey || skyShader == null) {
            skyShader = LinearGradient(0f, 0f, 0f, frame.height.toFloat(), frame.skyTop, frame.skyBottom, Shader.TileMode.CLAMP)
            skyShaderKey = key
        }
        fill.shader = skyShader
        canvas.drawRect(0f, 0f, frame.width.toFloat(), frame.height.toFloat(), fill)
        fill.shader = null
    }

    /** White soft-point sprite (alpha follows [StarDot.profile]), tinted per star with a cached color filter. */
    private val starSprite: Bitmap = Bitmap.createBitmap(SPRITE, SPRITE, Bitmap.Config.ARGB_8888).apply {
        val c = (SPRITE - 1) / 2f
        val px = IntArray(SPRITE * SPRITE) { i ->
            val r = kotlin.math.hypot(i % SPRITE - c, i / SPRITE - c) / c
            ((StarDot.profile(r) * 255).toInt() shl 24) or 0xFFFFFF
        }
        setPixels(px, 0, SPRITE, 0, 0, SPRITE, SPRITE)
    }
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tintFilters = HashMap<Int, PorterDuffColorFilter>()
    private val spriteDst = RectF()

    private fun drawStar(canvas: Canvas, star: StarDot) {
        val rgb = star.color and 0x00FFFFFF
        val alpha = star.color ushr 24
        if (star.glow > 0f) {
            val r = star.radius * StarDot.EXTENT * (2f + 2.5f * star.glow)
            fill.shader = RadialGradient(star.x, star.y, r, rgb or ((40 * star.glow).toInt() shl 24), rgb, Shader.TileMode.CLAMP)
            canvas.drawCircle(star.x, star.y, r, fill)
            fill.shader = null
        }
        // Quantize the tint so a handful of filters cover all star colors.
        val key = rgb and 0xF0F0F0
        spritePaint.colorFilter = tintFilters.getOrPut(key) { PorterDuffColorFilter(key or 0xFF080808.toInt(), PorterDuff.Mode.SRC_IN) }
        spritePaint.alpha = alpha
        val e = star.radius * StarDot.EXTENT
        spriteDst.set(star.x - e, star.y - e, star.x + e, star.y + e)
        canvas.drawBitmap(starSprite, null, spriteDst, spritePaint)
        // A small white core: star centers look white to the eye whatever their color.
        if (star.radius > 1.4f) {
            spritePaint.colorFilter = null
            spritePaint.alpha = (alpha * 0.8f).toInt()
            val c = e * 0.35f
            spriteDst.set(star.x - c, star.y - c, star.x + c, star.y + c)
            canvas.drawBitmap(starSprite, null, spriteDst, spritePaint)
        }
    }

    private fun drawBackground(canvas: Canvas, frame: SkyFrame, bg: BackgroundImage) {
        var bmp = backgroundBitmap
        if (bmp == null || bmp.width != bg.width || bmp.height != bg.height) {
            bmp = Bitmap.createBitmap(bg.width, bg.height, Bitmap.Config.ARGB_8888)
            backgroundBitmap = bmp
        }
        bmp.setPixels(bg.pixels, 0, bg.width, 0, 0, bg.width, bg.height)
        dst.set(0, 0, frame.width, frame.height)
        canvas.drawBitmap(bmp, null, dst, bitmapPaint)
    }

    private fun drawGround(canvas: Canvas, frame: SkyFrame) {
        val h = frame.horizon
        ground.rewind()
        ground.addCircle(h.x, h.y, h.radius, Path.Direction.CW)
        ground.fillType = if (frame.groundOutside) Path.FillType.INVERSE_WINDING else Path.FillType.WINDING
        fill.color = frame.groundColor
        canvas.drawPath(ground, fill)

        terrain.rewind()
        frame.terrain.forEachIndexed { i, p -> if (i == 0) terrain.moveTo(p.x, p.y) else terrain.lineTo(p.x, p.y) }
        terrain.close()
        fill.color = frame.terrainColor
        canvas.drawPath(terrain, fill)

        if (h.width > 0f) {
            stroke.color = h.color
            stroke.strokeWidth = h.width
            canvas.drawCircle(h.x, h.y, h.radius, stroke)
        }
    }

    private fun drawBody(canvas: Canvas, b: BodyDisc) {
        if (b.kind == BodyKind.MOON || b.kind == BodyKind.SUN) {
            val r = b.radius * 7f
            val rgb = b.color and 0x00FFFFFF
            fill.shader = RadialGradient(b.x, b.y, r, intArrayOf(rgb or (120 shl 24), rgb or (40 shl 24), rgb), floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(b.x, b.y, r, fill)
            fill.shader = null
        }
        if (b.kind != BodyKind.MOON) {
            fill.color = b.color
            canvas.drawCircle(b.x, b.y, b.radius, fill)
            return
        }
        // Dark disc, then the lit part: a half disc on the sunward side plus or minus a half ellipse.
        fill.color = 0xFF2A2C33.toInt()
        canvas.drawCircle(b.x, b.y, b.radius, fill)
        val r = b.radius
        val k = b.phase.toFloat()
        val terminator = abs(1 - 2 * k) * r
        moon.rewind()
        oval.set(b.x - r, b.y - r, b.x + r, b.y + r)
        // Waxing Moon is lit on the right as seen from the northern hemisphere.
        val litStart = if (b.waxing) -90f else 90f
        moon.arcTo(oval, litStart, 180f)
        oval.set(b.x - terminator, b.y - r, b.x + terminator, b.y + r)
        val gibbous = k > 0.5f
        moon.arcTo(oval, litStart + 180f, if (gibbous) 180f else -180f)
        moon.close()
        fill.color = b.color
        canvas.drawPath(moon, fill)
    }

    companion object {
        private const val SPRITE = 48
    }
}
