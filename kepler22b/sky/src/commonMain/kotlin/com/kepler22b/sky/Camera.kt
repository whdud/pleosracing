package com.kepler22b.sky

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** A point on the screen in pixels. */
data class ScreenPoint(val x: Float, val y: Float)

enum class ViewMode {
    /** Looking out of the windshield: centered on the car heading, slightly above the horizon. */
    WINDSHIELD,

    /** Looking up through the sunroof: whole sky, top of the screen toward the car's front. */
    SUNROOF,
}

/**
 * Stereographic projection of the sky onto the screen.
 *
 * Stereographic keeps constellation shapes undistorted and can show the whole hemisphere
 * (field of view 180°) in sunroof mode, the same idea as a planisphere.
 *
 * Both modes are "real" views as seen by an observer, never mirrored: in sunroof mode with the car
 * facing north, east appears on the left, like a star chart held over your head.
 */
class Camera(
    val mode: ViewMode,
    /** Direction the view is centered on (windshield) or the car front direction (sunroof), degrees from north. */
    val azimuth: Double,
    /** Altitude of the view center. Ignored in sunroof mode. */
    val altitude: Double,
    /**
     * Field of view in degrees: across the screen width in windshield mode (wide car displays stay
     * natural, like Stellarium's horizontal view), across the shorter side in sunroof mode (so the whole sky fits).
     */
    val fieldOfView: Double,
    val width: Int,
    val height: Int,
) {
    // Forward, up and right unit vectors in the local (east, north, up) frame.
    private val fx: Double
    private val fy: Double
    private val fz: Double
    private val ux: Double
    private val uy: Double
    private val uz: Double
    private val rx: Double
    private val ry: Double
    private val rz: Double
    private val scale: Double =
        ((if (mode == ViewMode.SUNROOF) minOf(width, height) else width) / 2.0) / (2 * tan(fieldOfView * DEG / 4))

    init {
        val (f, u) = when (mode) {
            ViewMode.WINDSHIELD -> direction(azimuth, altitude) to direction(azimuth, altitude + 90)
            ViewMode.SUNROOF -> doubleArrayOf(0.0, 0.0, 1.0) to direction(azimuth, 0.0)
        }
        fx = f[0]; fy = f[1]; fz = f[2]
        ux = u[0]; uy = u[1]; uz = u[2]
        // right = forward × up
        rx = fy * uz - fz * uy
        ry = fz * ux - fx * uz
        rz = fx * uy - fy * ux
    }

    /** Pixels per degree near the center of the view, for converting drag gestures. */
    val pixelsPerDegree: Double get() = scale * DEG

    fun project(h: Horizontal): ScreenPoint? = project(h.alt, h.az)

    fun project(alt: Double, az: Double): ScreenPoint? {
        val v = direction(az, alt)
        val z = v[0] * fx + v[1] * fy + v[2] * fz
        if (z < -0.95) return null // too close to the point behind the viewer
        val k = 2 / (1 + z)
        val x = (v[0] * rx + v[1] * ry + v[2] * rz) * k
        val y = (v[0] * ux + v[1] * uy + v[2] * uz) * k
        return ScreenPoint((width / 2.0 + scale * x).toFloat(), (height / 2.0 - scale * y).toFloat())
    }

    /**
     * Inverse projection: the (east, north, up) unit direction seen at a screen pixel, written into [out].
     * Returns false for pixels outside the projectable area.
     */
    fun unproject(sx: Float, sy: Float, out: DoubleArray): Boolean {
        val px = (sx - width / 2.0) / scale
        val py = (height / 2.0 - sy) / scale
        val rho2 = px * px + py * py
        val z = (4 - rho2) / (4 + rho2)
        if (z < -0.95) return false
        val k = (1 + z) / 2
        val x = px * k
        val y = py * k
        out[0] = x * rx + y * ux + z * fx
        out[1] = x * ry + y * uy + z * fy
        out[2] = x * rz + y * uz + z * fz
        return true
    }

    fun isOnScreen(p: ScreenPoint, margin: Float = 0f) =
        p.x >= -margin && p.y >= -margin && p.x <= width + margin && p.y <= height + margin

    companion object {
        /** Unit vector (east, north, up) for an azimuth/altitude in degrees. */
        fun direction(az: Double, alt: Double): DoubleArray {
            val a = az * DEG
            val e = alt * DEG
            return doubleArrayOf(cos(e) * sin(a), cos(e) * cos(a), sin(e))
        }
    }
}
