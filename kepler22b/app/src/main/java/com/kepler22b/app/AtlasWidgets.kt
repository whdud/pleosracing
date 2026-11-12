package com.byeolmajung.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.animation.DecelerateInterpolator
import android.widget.Button

/**
 * A text control in the bottom strip. When [on], it brightens and a short rule grows across the top edge
 * of the strip above it, like the selected tab on an instrument panel.
 */
class StripButton(context: Context) : Button(context) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var mark = 0f
    private var animator: ValueAnimator? = null

    var on = false
        set(value) {
            if (field == value) return
            field = value
            setTextColor(if (value) PAPER else PAPER_DIM)
            animator?.cancel()
            animator = ValueAnimator.ofFloat(mark, if (value) 1f else 0f).apply {
                duration = 350
                interpolator = DecelerateInterpolator(2f)
                addUpdateListener { mark = it.animatedValue as Float; invalidate() }
                start()
            }
        }

    init {
        background = null
        isAllCaps = false
        stateListAnimator = null
        setTextColor(PAPER_DIM)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mark <= 0f) return
        val inset = 18 * density
        val w = (width - 2 * inset) * mark
        paint.color = PAPER
        canvas.drawRect(width / 2f - w / 2, 0f, width / 2f + w / 2, 2 * density, paint)
    }
}

/** The one filled control on a sheet. While [speaking], three bars move beside the label. */
class PaperButton(context: Context) : Button(context) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    var speaking = false
        set(value) {
            if (field == value) return
            field = value
            if (value) pulse.start() else pulse.cancel()
            invalidate()
        }

    init {
        isAllCaps = false
        stateListAnimator = null
        setTextColor(ON_PAPER)
        setBackgroundColor(PAPER)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!speaking) return
        val textWidth = getPaint().measureText(text.toString())
        val bar = 4 * density
        val gap = 4 * density
        val full = 18 * density
        val left = (width - textWidth) / 2 - 3 * bar - 2 * gap - 14 * density
        val bottom = height / 2f + full / 2
        paint.color = ON_PAPER
        for (i in 0 until 3) {
            val t = (phase + i / 3f) % 1f
            val h = full * (0.3f + 0.7f * kotlin.math.abs(kotlin.math.sin(t * Math.PI)).toFloat())
            val x = left + i * (bar + gap)
            canvas.drawRect(x, bottom - h, x + bar, bottom, paint)
        }
    }

    override fun onDetachedFromWindow() {
        pulse.cancel()
        super.onDetachedFromWindow()
    }
}

/** Chart-paper ivory on the night sky: text, rules and the one filled control. */
internal val PAPER = 0xFFECE5D3.toInt()
internal val PAPER_DIM = 0xFFA49E90.toInt()
internal val PAPER_FAINT = 0x38ECE5D3
internal val ON_PAPER = 0xFF0B0F1A.toInt()
internal val SHEET = 0xE6050810.toInt()
