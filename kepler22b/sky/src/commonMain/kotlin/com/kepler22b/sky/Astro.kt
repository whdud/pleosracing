package com.byeolmajung.sky

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Right ascension / declination in degrees. */
data class Equatorial(val ra: Double, val dec: Double)

/** Altitude above the horizon and azimuth measured from north through east, in degrees. */
data class Horizontal(val alt: Double, val az: Double)

/** Observer on the Earth's surface. Longitude is east-positive. */
data class Observer(val latitude: Double, val longitude: Double)

internal const val DEG = PI / 180.0
internal const val RAD = 180.0 / PI

internal fun normalizeDeg(a: Double): Double {
    val r = a % 360.0
    return if (r < 0) r + 360.0 else r
}

object AstroTime {
    const val J2000 = 2451545.0

    fun julianDay(epochMillis: Long): Double = epochMillis / 86_400_000.0 + 2440587.5

    /** Days since J2000.0. */
    fun daysSinceJ2000(epochMillis: Long): Double = julianDay(epochMillis) - J2000

    /** Greenwich mean sidereal time in degrees (Meeus 12.4). */
    fun gmst(epochMillis: Long): Double {
        val d = daysSinceJ2000(epochMillis)
        val t = d / 36525.0
        return normalizeDeg(280.46061837 + 360.98564736629 * d + 0.000387933 * t * t - t * t * t / 38710000.0)
    }

    fun localSiderealTime(epochMillis: Long, longitude: Double): Double = normalizeDeg(gmst(epochMillis) + longitude)
}

/**
 * Converts J2000 equatorial coordinates to the local horizon for one instant.
 *
 * Precession to the date is applied (Meeus 21.2) so that stars sit where they really are tonight.
 * Atmospheric refraction is ignored; it lifts objects by about half a degree only near the horizon.
 */
class HorizonTransform(epochMillis: Long, observer: Observer) {
    private val zeta: Double
    private val z: Double
    private val sinTheta: Double
    private val cosTheta: Double
    private val lst: Double
    private val sinLat = sin(observer.latitude * DEG)
    private val cosLat = cos(observer.latitude * DEG)

    init {
        val t = AstroTime.daysSinceJ2000(epochMillis) / 36525.0
        zeta = (2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t) / 3600.0
        z = (2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t) / 3600.0
        val theta = (2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t) / 3600.0
        sinTheta = sin(theta * DEG)
        cosTheta = cos(theta * DEG)
        lst = AstroTime.localSiderealTime(epochMillis, observer.longitude)
    }

    fun precess(j2000: Equatorial): Equatorial {
        val dec0 = j2000.dec * DEG
        val raZeta = (j2000.ra + zeta) * DEG
        val a = cos(dec0) * sin(raZeta)
        val b = cosTheta * cos(dec0) * cos(raZeta) - sinTheta * sin(dec0)
        val c = sinTheta * cos(dec0) * cos(raZeta) + cosTheta * sin(dec0)
        return Equatorial(normalizeDeg(atan2(a, b) * RAD + z), asin(c.coerceIn(-1.0, 1.0)) * RAD)
    }

    /** Equatorial coordinates of date (already precessed) to horizon. */
    fun toHorizontalOfDate(eq: Equatorial): Horizontal {
        val h = (lst - eq.ra) * DEG
        val dec = eq.dec * DEG
        val sinAlt = sinLat * sin(dec) + cosLat * cos(dec) * cos(h)
        val alt = asin(sinAlt.coerceIn(-1.0, 1.0))
        val az = atan2(-sin(h) * cos(dec), sin(dec) * cosLat - cos(dec) * cos(h) * sinLat)
        return Horizontal(alt * RAD, normalizeDeg(az * RAD))
    }

    fun toHorizontal(j2000: Equatorial): Horizontal = toHorizontalOfDate(precess(j2000))
}

/** Angular separation between two directions in degrees. */
fun separation(a: Equatorial, b: Equatorial): Double {
    val d1 = a.dec * DEG
    val d2 = b.dec * DEG
    val c = sin(d1) * sin(d2) + cos(d1) * cos(d2) * cos((a.ra - b.ra) * DEG)
    return kotlin.math.acos(c.coerceIn(-1.0, 1.0)) * RAD
}

internal fun eclipticToEquatorial(lambda: Double, beta: Double, epsilon: Double): Equatorial {
    val l = lambda * DEG
    val b = beta * DEG
    val e = epsilon * DEG
    val ra = atan2(sin(l) * cos(e) - kotlin.math.tan(b) * sin(e), cos(l))
    val dec = asin((sin(b) * cos(e) + cos(b) * sin(e) * sin(l)).coerceIn(-1.0, 1.0))
    return Equatorial(normalizeDeg(ra * RAD), dec * RAD)
}

internal fun vectorToEquatorial(x: Double, y: Double, zc: Double): Equatorial {
    val r = sqrt(x * x + y * y + zc * zc)
    return Equatorial(normalizeDeg(atan2(y, x) * RAD), asin((zc / r).coerceIn(-1.0, 1.0)) * RAD)
}
