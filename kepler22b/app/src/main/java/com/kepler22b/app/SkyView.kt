package com.kepler22b.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.kepler22b.sky.BackgroundImage
import com.kepler22b.sky.Camera
import com.kepler22b.sky.Horizontal
import com.kepler22b.sky.SceneOptions
import com.kepler22b.sky.SkyBackground
import com.kepler22b.sky.SkyFrame
import com.kepler22b.sky.SkyObject
import com.kepler22b.sky.SkyScene
import com.kepler22b.sky.SkySnapshot
import com.kepler22b.sky.ViewMode

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

    /**
     * Sunroof mode is the same view tilted up toward the sky overhead, as if looking up through the roof;
     * the windshield view brings it back down to the horizon.
     */
    var mode = ViewMode.WINDSHIELD
        set(value) {
            if (field == value) return
            field = value
            animateTo(headingOffset, if (value == ViewMode.SUNROOF) SUNROOF_ALTITUDE else DEFAULT_ALTITUDE)
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
    /** Photographic Moon map; see [com.kepler22b.sky.MoonSprite]. */
    var moonMap: com.kepler22b.sky.SkyTexture?
        get() = renderer.moonMap
        set(value) {
            renderer.moonMap = value
            invalidate()
        }

    private val renderer = SkyCanvasRenderer { file ->
        runCatching { context.assets.open("sky/art/$file").use(android.graphics.BitmapFactory::decodeStream) }.getOrNull()
    }

    /** The azimuth currently in the middle of the view. */
    val viewAzimuth: Double get() = ((carHeading + headingOffset) % 360 + 360) % 360

    val isFollowingCar: Boolean get() = headingOffset == 0.0

    fun followCar() = animateTo(0.0, if (mode == ViewMode.SUNROOF) SUNROOF_ALTITUDE else DEFAULT_ALTITUDE)

    /** Turns the view toward a point in the sky. */
    fun lookAt(target: Horizontal) {
        var offset = target.az - carHeading
        offset = ((offset + 540) % 360) - 180
        animateTo(offset, (target.alt - 10).coerceIn(DEFAULT_ALTITUDE, MAX_ALTITUDE))
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
        val camera = camera()
        val f = SkyScene.build(snap, camera, options.copy(density = density))
        frame = f
        renderer.draw(canvas, f, backgroundFor(snap, camera))
    }

    private fun camera() = Camera(ViewMode.WINDSHIELD, viewAzimuth, altitude, fieldOfView, width, height)

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
            val ppd = camera().pixelsPerDegree
            headingOffset += dx / ppd
            altitude = (altitude - dy / ppd).coerceIn(5.0, MAX_ALTITUDE)
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
            fieldOfView = (fieldOfView / detector.scaleFactor).coerceIn(30.0, 150.0)
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

        /** Looking up through the sunroof: the sky overhead fills the screen, the horizon out of sight. */
        const val SUNROOF_ALTITUDE = 70.0

        const val MAX_ALTITUDE = 85.0
    }
}
