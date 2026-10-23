package com.byeolmajung.sky

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** An ARGB image in plate carrée J2000 celestial coordinates: x = 0.5 − RA/360 (RA grows to the left), y = 0.5 − Dec/180. */
class SkyTexture(val width: Int, val height: Int, val pixels: IntArray)

/**
 * The diffuse light of the sky, rendered per pixel at reduced resolution: the Milky Way, the glow of the
 * atmosphere near the horizon (airglow and light pollution) and moonlight scattered across the sky.
 *
 * The result is ARGB with alpha equal to the brightest channel, so drawing it with normal alpha blending
 * over the dark sky gradient adds light the way the real sky does.
 */
class SkyBackground(private val milkyWay: SkyTexture?) {

    private val dir = DoubleArray(3)

    /**
     * @param scaleDown output is the screen size divided by this; the sky light is smooth, so 4 looks the same as full size.
     * @param skyQuality 0 for a dark site, 1 for a city sky.
     */
    fun render(snapshot: SkySnapshot, camera: Camera, scaleDown: Int, skyQuality: Double, nightRed: Boolean): BackgroundImage {
        val w = (camera.width + scaleDown - 1) / scaleDown
        val h = (camera.height + scaleDown - 1) / scaleDown
        val out = IntArray(w * h)

        val sunAlt = snapshot.sunAltitude
        val darkness = ((-sunAlt - 12) / 6).coerceIn(0.0, 1.0)
        val moon = snapshot.bodies.first { it.first.kind == BodyKind.MOON }
        val moonUp = moon.second.alt > 0
        val moonLight = if (moonUp) moon.first.phase * sqrt(sin(moon.second.alt * DEG)) else 0.0
        val moonDir = Camera.direction(moon.second.az, moon.second.alt)
        val milkyWayStrength = darkness * (1 - 0.8 * moonLight) * (1 - 0.85 * skyQuality)
        val glowStrength = darkness * (0.35 + 0.65 * skyQuality)

        val lat = snapshot.observer.latitude * DEG
        val sinLat = sin(lat)
        val cosLat = cos(lat)
        val lst = AstroTime.localSiderealTime(snapshot.epochMillis, snapshot.observer.longitude)

        for (py in 0 until h) {
            for (px in 0 until w) {
                if (!camera.unproject((px + 0.5f) * scaleDown, (py + 0.5f) * scaleDown, dir)) continue
                val up = dir[2]
                if (up < -0.02) continue
                val alt = asin(up.coerceIn(-1.0, 1.0)) * RAD
                var r = 0.0
                var g = 0.0
                var b = 0.0

                // Milky Way, dimmed by the thicker air near the horizon.
                if (milkyWay != null && milkyWayStrength > 0.01) {
                    val ext = smooth(alt / 25.0) * 0.85 + 0.15
                    // Horizon to equatorial of date; precession since J2000 (≈0.4°) is far below this layer's resolution.
                    val sinDec = sinLat * up + cosLat * sqrt(max(0.0, 1 - up * up)) * (dir[1] / max(1e-9, sqrt(dir[0] * dir[0] + dir[1] * dir[1])))
                    val dec = asin(sinDec.coerceIn(-1.0, 1.0))
                    val hourAngle = atan2(-dir[0], up * cosLat - dir[1] * sinLat)
                    val ra = normalizeDeg(lst - hourAngle * RAD)
                    val c = sample(milkyWay, 0.5 - ra / 360.0, 0.5 - dec * RAD / 180.0)
                    val k = milkyWayStrength * ext * 0.75
                    r += ((c shr 16) and 0xFF) * k
                    g += ((c shr 8) and 0xFF) * k
                    b += (c and 0xFF) * k
                }

                // Airglow and light pollution: a band of light along the horizon.
                val band = (1 - alt / 35.0).coerceIn(0.0, 1.0)
                val glow = band * band * glowStrength
                r += glow * (26 + 40 * skyQuality)
                g += glow * (30 + 32 * skyQuality)
                b += glow * (48 + 18 * skyQuality)

                // Moonlight: brightens the whole sky, most strongly around the Moon.
                if (moonLight > 0) {
                    val cosAngle = dir[0] * moonDir[0] + dir[1] * moonDir[1] + dir[2] * moonDir[2]
                    val near = exp(-(1 - cosAngle) * 9)
                    val m = moonLight * darkness.coerceAtLeast(0.3) * (0.35 + 0.65 * near)
                    r += m * 34
                    g += m * 46
                    b += m * 74
                }

                out[py * w + px] = pack(r, g, b, nightRed)
            }
        }
        return BackgroundImage(w, h, out)
    }

    private fun smooth(t: Double): Double {
        val x = t.coerceIn(0.0, 1.0)
        return x * x * (3 - 2 * x)
    }

    private fun sample(tex: SkyTexture, u: Double, v: Double): Int {
        val x = (u - floor(u)) * tex.width - 0.5
        val y = (v.coerceIn(0.0, 1.0) * tex.height - 0.5).coerceIn(0.0, tex.height - 1.0)
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        fun px(ix: Int, iy: Int) = tex.pixels[(iy.coerceIn(0, tex.height - 1)) * tex.width + Math.floorMod(ix, tex.width)]
        val c00 = px(x0, y0)
        val c10 = px(x0 + 1, y0)
        val c01 = px(x0, y0 + 1)
        val c11 = px(x0 + 1, y0 + 1)
        fun ch(shift: Int): Int {
            val top = ((c00 shr shift) and 0xFF) * (1 - fx) + ((c10 shr shift) and 0xFF) * fx
            val bottom = ((c01 shr shift) and 0xFF) * (1 - fx) + ((c11 shr shift) and 0xFF) * fx
            return (top * (1 - fy) + bottom * fy).toInt()
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun pack(r0: Double, g0: Double, b0: Double, nightRed: Boolean): Int {
        var r = r0.coerceIn(0.0, 255.0)
        var g = g0.coerceIn(0.0, 255.0)
        var b = b0.coerceIn(0.0, 255.0)
        if (nightRed) {
            r = 0.3 * r + 0.59 * g + 0.11 * b
            g = r * 0.12
            b = r * 0.08
        }
        val a = max(r, max(g, b))
        if (a < 1) return 0
        val s = 255 / a
        return (a.toInt() shl 24) or ((r * s).toInt().coerceAtMost(255) shl 16) or ((g * s).toInt().coerceAtMost(255) shl 8) or (b * s).toInt().coerceAtMost(255)
    }
}

class BackgroundImage(val width: Int, val height: Int, val pixels: IntArray)
