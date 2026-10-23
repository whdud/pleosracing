package com.byeolmajung.app

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.byeolmajung.sky.BodyDisc
import com.byeolmajung.sky.BodyKind
import com.byeolmajung.sky.SkyFrame
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
    private val moon = Path()
    private val oval = RectF()
    private var skyShaderKey = 0L
    private var skyShader: Shader? = null

    fun draw(canvas: Canvas, frame: SkyFrame) {
        drawSky(canvas, frame)

        frame.lines.forEach {
            stroke.color = it.color
            stroke.strokeWidth = it.width
            canvas.drawLine(it.x1, it.y1, it.x2, it.y2, stroke)
        }
        frame.stars.forEach {
            if (it.glow > 0f) {
                fill.color = (it.color and 0x00FFFFFF) or (0x33 shl 24)
                canvas.drawCircle(it.x, it.y, it.radius * 2.6f, fill)
            }
            fill.color = it.color
            canvas.drawCircle(it.x, it.y, it.radius, fill)
        }
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

    private fun drawGround(canvas: Canvas, frame: SkyFrame) {
        val h = frame.horizon
        ground.rewind()
        ground.addCircle(h.x, h.y, h.radius, Path.Direction.CW)
        ground.fillType = if (frame.groundOutside) Path.FillType.INVERSE_WINDING else Path.FillType.WINDING
        fill.color = frame.groundColor
        canvas.drawPath(ground, fill)
        stroke.color = h.color
        stroke.strokeWidth = h.width
        canvas.drawCircle(h.x, h.y, h.radius, stroke)
    }

    private fun drawBody(canvas: Canvas, b: BodyDisc) {
        if (b.kind != BodyKind.MOON) {
            if (b.kind == BodyKind.SUN) {
                fill.color = (b.color and 0x00FFFFFF) or (0x40 shl 24)
                canvas.drawCircle(b.x, b.y, b.radius * 2.2f, fill)
            }
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
}
