package com.byeolmajung.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.byeolmajung.sky.BackgroundImage
import com.byeolmajung.sky.Camera
import com.byeolmajung.sky.Horizontal
import com.byeolmajung.sky.SceneOptions
import com.byeolmajung.sky.SkyBackground
import com.byeolmajung.sky.SkyFrame
import com.byeolmajung.sky.SkyObject
import com.byeolmajung.sky.SkyScene
import com.byeolmajung.sky.SkySnapshot
import com.byeolmajung.sky.ViewMode

/**
 * The interactive sky. The view direction follows the car heading; the user can drag to look around
 * (an offset from the heading), pinch to zoom and tap to select.
 */
class SkyView(context: Context) : View(context) {

    var snapshot: SkySnapshot? = null
        set(value) {
            field = value
            invalidate()
        }

    var mode = ViewMode.WINDSHIELD
        set(value) {
            field = value
            fieldOfView = if (value == ViewMode.SUNROOF) 180.0 else DEFAULT_FOV
            invalidate()
        }

    /** Car heading in degrees from north, from the vehicle location service. */
    var carHeading = 180.0
        set(value) {
            field = value
            invalidate()
        }

    var options = SceneOptions()
        set(value) {
            field = value
            invalidate()
        }

    /** Milky Way, airglow and moonlight layer; null until the texture is loaded. */
    var background: SkyBackground? = null
        set(value) {
            field = value
            invalidate()
        }

    var onSelect: ((SkyObject?) -> Unit)? = null
    var onViewChanged: (() -> Unit)? = null

    private var headingOffset = 0.0
    private var altitude = DEFAULT_ALTITUDE
    private var fieldOfView = DEFAULT_FOV
    private var backgroundImage: BackgroundImage? = null
    private var backgroundKey: List<Any>? = null
    private var frame: SkyFrame? = null
    private var animator: ValueAnimator? = null
    private val density = resources.displayMetrics.density
    private val renderer = SkyCanvasRenderer()

    /** The azimuth currently in the middle of the view (windshield) or at the top (sunroof). */
    val viewAzimuth: Double get() = ((carHeading + headingOffset) % 360 + 360) % 360

    val isFollowingCar: Boolean get() = headingOffset == 0.0

    fun followCar() = animateTo(0.0, DEFAULT_ALTITUDE)

    /** Turns the view toward a point in the sky (windshield mode). */
    fun lookAt(target: Horizontal) {
        if (mode == ViewMode.SUNROOF) return
        var offset = target.az - carHeading
        offset = ((offset + 540) % 360) - 180
        animateTo(offset, (target.alt - 10).coerceIn(DEFAULT_ALTITUDE, 70.0))
    }

    private fun animateTo(offset: Double, alt: Double) {
        animator?.cancel()
        val startOffset = headingOffset
        val startAlt = altitude
        // Take the short way round.
        val delta = ((offset - startOffset + 540) % 360) - 180
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                headingOffset = if (t >= 1f) offset else startOffset + delta * t
                altitude = startAlt + (alt - startAlt) * t
                onViewChanged?.invoke()
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val snap = snapshot ?: return
        if (width == 0 || height == 0) return
        val camera = Camera(mode, viewAzimuth, altitude, fieldOfView, width, height)
        val f = SkyScene.build(snap, camera, options.copy(density = density))
        frame = f
        renderer.draw(canvas, f, backgroundFor(snap, camera))
    }

    /** The background layer is the most expensive part; recompute it only when the view or the sky changes. */
    private fun backgroundFor(snap: SkySnapshot, camera: Camera): BackgroundImage? {
        val bg = background ?: return null
        val key = listOf(snap.epochMillis, snap.observer, mode, viewAzimuth, altitude, fieldOfView, width, height, options.skyQuality)
        if (key != backgroundKey) {
            backgroundImage = bg.render(snap, camera, scaleDown = 5, skyQuality = options.skyQuality)
            backgroundKey = key
        }
        return backgroundImage
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            animator?.cancel()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val ppd = Camera(mode, viewAzimuth, altitude, fieldOfView, width, height).pixelsPerDegree
            if (mode == ViewMode.SUNROOF) {
                headingOffset += dx / ppd * 0.5
            } else {
                headingOffset += dx / ppd
                altitude = (altitude - dy / ppd).coerceIn(5.0, 80.0)
            }
            headingOffset = ((headingOffset + 540) % 360) - 180
            onViewChanged?.invoke()
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onSelect?.invoke(frame?.pick(e.x, e.y, 24f * density))
            return true
        }
    })

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val range = if (mode == ViewMode.SUNROOF) 90.0..200.0 else 30.0..150.0
            fieldOfView = (fieldOfView / detector.scaleFactor).coerceIn(range)
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        if (!scale.isInProgress) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    companion object {
        /** Horizontal field of view through the windshield, close to Stellarium's default framing on a wide screen. */
        const val DEFAULT_FOV = 100.0

        /** Keeps the horizon near the bottom of the screen, as when looking out of the windshield. */
        const val DEFAULT_ALTITUDE = 15.0
    }
}
