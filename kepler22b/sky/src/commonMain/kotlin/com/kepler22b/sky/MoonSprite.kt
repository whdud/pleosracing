package com.kepler22b.sky

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Paints the Moon as a lit sphere wrapped in a photographic map of its surface (NASA LRO, equirectangular,
 * longitude 0 at the center, north up), seen from Earth: the near side faces the viewer, the map is turned
 * so lunar north points to celestial north on screen, and sunlight comes from the Sun's real direction.
 *
 * The result is an emissive layer (color with matching alpha) so the dark side shows the sky behind it,
 * as it does in reality, with only a trace of earthshine.
 */
object MoonSprite {

    /**
     * @param size sprite width and height in pixels; the disc fills it.
     * @param phase illuminated fraction 0..1.
     * @param north, sun screen directions (radians, atan2 of y-down screen vectors) toward celestial north and
     *   toward the Sun, as seen at the Moon's position.
     * @param west screen direction toward celestial west, which is lunar east (+longitude) on the near side.
     *   Passing it separately keeps a mirrored view (looking up through the sunroof) correct.
     */
    fun render(size: Int, map: SkyTexture, phase: Double, north: Double, sun: Double, west: Double): IntArray {
        val out = IntArray(size * size)
        val r = size / 2.0
        val cosA = (2 * phase - 1).coerceIn(-1.0, 1.0)
        val sinA = sqrt(1 - cosA * cosA)
        val sx = sinA * cos(sun)
        val sy = sinA * sin(sun)
        val sz = cosA
        val nx = cos(north)
        val ny = sin(north)
        // Lunar +x: toward celestial west, made exactly perpendicular to north.
        var xx = cos(west)
        var xy = sin(west)
        val dot = xx * nx + xy * ny
        xx -= dot * nx
        xy -= dot * ny
        val len = sqrt(xx * xx + xy * xy).takeIf { it > 1e-6 } ?: 1.0
        xx /= len
        xy /= len

        for (py in 0 until size) {
            for (px in 0 until size) {
                val dx = (px + 0.5 - r) / r
                val dy = (py + 0.5 - r) / r
                val d2 = dx * dx + dy * dy
                if (d2 >= 1.0) continue
                val nz = sqrt(1 - d2)
                // Antialiased rim, about one pixel wide.
                val coverage = ((1 - sqrt(d2)) * r).coerceIn(0.0, 1.0)

                val lat = asin((dx * nx + dy * ny).coerceIn(-1.0, 1.0))
                val lon = atan2(dx * xx + dy * xy, nz)
                val albedo = sample(map, 0.5 + lon / (2 * PI), 0.5 - lat / PI)

                val light = dx * sx + dy * sy + nz * sz
                // A soft terminator, and the Moon's nearly flat brightness across the lit face.
                val lit = smoothstep(-0.03, 0.16, light) * (0.66 + 0.34 * sqrt(light.coerceAtLeast(0.0)))
                val earthshine = 0.045 * (1 - phase)
                // The map's maria are darker than the eye sees them; lift them toward the highlands.
                fun tone(c: Int) = 0.1 + 0.9 * (c / 255.0)
                var red = tone(albedo shr 16 and 0xFF) * (lit * 1.4 + earthshine * 0.8)
                var green = tone(albedo shr 8 and 0xFF) * (lit * 1.37 + earthshine * 0.9)
                var blue = tone(albedo and 0xFF) * (lit * 1.27 + earthshine * 1.1)
                // Emissive: alpha follows brightness so the dark side lets the sky through.
                // Over black this composites back to exactly (red, green, blue).
                val opacity = (maxOf(red, green, blue) * 1.6).coerceIn(1e-6, 1.0)
                val alpha = coverage * opacity
                red /= opacity
                green /= opacity
                blue /= opacity
                out[py * size + px] = ((alpha * 255).toInt() shl 24) or
                    ((red * 255).toInt().coerceIn(0, 255) shl 16) or
                    ((green * 255).toInt().coerceIn(0, 255) shl 8) or
                    (blue * 255).toInt().coerceIn(0, 255)
            }
        }
        return out
    }

    private fun smoothstep(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /** Bilinear sample; u wraps around in longitude. */
    private fun sample(map: SkyTexture, u: Double, v: Double): Int {
        val fx = (u - floor(u)) * map.width - 0.5
        val fy = (v.coerceIn(0.0, 1.0) * map.height - 0.5).coerceIn(0.0, map.height - 1.0)
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        fun px(x: Int, y: Int) = map.pixels[y.coerceIn(0, map.height - 1) * map.width + Math2.floorMod(x, map.width)]
        val a = px(x0, y0); val b = px(x0 + 1, y0); val c = px(x0, y0 + 1); val d = px(x0 + 1, y0 + 1)
        fun ch(p: Int, s: Int) = (p shr s and 0xFF).toDouble()
        fun mix(s: Int) = ((ch(a, s) * (1 - tx) + ch(b, s) * tx) * (1 - ty) + (ch(c, s) * (1 - tx) + ch(d, s) * tx) * ty).toInt()
        return (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}
