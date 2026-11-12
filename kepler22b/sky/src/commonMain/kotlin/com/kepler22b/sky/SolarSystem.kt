package com.kepler22b.sky

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class BodyKind { SUN, MOON, PLANET }

/**
 * A solar-system body for one instant.
 *
 * @property equatorial position; [ofDate] tells whether it is already referred to the equinox of date
 *   (Sun, Moon) or to J2000 (planets).
 * @property phase illuminated fraction 0..1 (Moon only, 1 otherwise).
 * @property waxing whether the Moon is growing (Moon only).
 */
data class Body(
    val id: String,
    val nameKo: String,
    val kind: BodyKind,
    val equatorial: Equatorial,
    val ofDate: Boolean,
    val magnitude: Double,
    val phase: Double = 1.0,
    val waxing: Boolean = true,
)

/**
 * Low-precision positions, good to a fraction of a degree, which is plenty for naked-eye viewing.
 *
 * - Sun and Moon: Astronomical Almanac low-precision formulae.
 * - Planets: JPL "Keplerian Elements for Approximate Positions of the Major Planets" (Standish), valid 1800–2050.
 */
object SolarSystem {

    private const val OBLIQUITY_J2000 = 23.43928

    fun bodies(epochMillis: Long): List<Body> {
        val d = AstroTime.daysSinceJ2000(epochMillis)
        val sunLongitude = sunEclipticLongitude(d)
        val sun = sun(d, sunLongitude)
        val earth = PLANETS.getValue("earth").heliocentric(d / 36525.0)
        val planets = PLANETS.filterKeys { it != "earth" }.map { (id, el) -> planet(id, el, d / 36525.0, earth) }
        return listOf(sun, moon(d, sunLongitude)) + planets
    }

    private fun sunEclipticLongitude(d: Double): Double {
        val l = normalizeDeg(280.460 + 0.9856474 * d)
        val g = normalizeDeg(357.528 + 0.9856003 * d) * DEG
        return normalizeDeg(l + 1.915 * sin(g) + 0.020 * sin(2 * g))
    }

    private fun obliquity(d: Double) = 23.439 - 0.0000004 * d

    private fun sun(d: Double, lambda: Double) = Body(
        id = "sun",
        nameKo = "태양",
        kind = BodyKind.SUN,
        equatorial = eclipticToEquatorial(lambda, 0.0, obliquity(d)),
        ofDate = true,
        magnitude = -26.7,
    )

    private fun moon(d: Double, sunLambda: Double): Body {
        val t = d / 36525.0
        fun s(a: Double, b: Double) = sin(normalizeDeg(a + b * t) * DEG)
        val lambda = normalizeDeg(
            218.32 + 481267.881 * t +
                6.29 * s(135.0, 477198.87) - 1.27 * s(259.3, -413335.36) +
                0.66 * s(235.7, 890534.22) + 0.21 * s(269.9, 954397.74) -
                0.19 * s(357.5, 35999.05) - 0.11 * s(186.5, 966404.03),
        )
        val beta = 5.13 * s(93.3, 483202.02) + 0.28 * s(228.2, 960400.89) -
            0.28 * s(318.3, 6003.15) - 0.17 * s(217.6, -407332.21)

        val elongation = normalizeDeg(lambda - sunLambda)
        // Geocentric elongation is close enough to the phase angle for display purposes.
        val phase = (1 - cos(elongation * DEG)) / 2
        return Body(
            id = "moon",
            nameKo = "달",
            kind = BodyKind.MOON,
            equatorial = eclipticToEquatorial(lambda, beta, obliquity(d)),
            ofDate = true,
            magnitude = -12.7 * phase.coerceAtLeast(0.05),
            phase = phase,
            waxing = elongation < 180.0,
        )
    }

    private fun planet(id: String, el: Elements, t: Double, earth: Vec3): Body {
        val p = el.heliocentric(t)
        val gx = p.x - earth.x
        val gy = p.y - earth.y
        val gz = p.z - earth.z
        val e = OBLIQUITY_J2000 * DEG
        val eq = vectorToEquatorial(gx, gy * cos(e) - gz * sin(e), gy * sin(e) + gz * cos(e))
        return Body(id, el.nameKo, BodyKind.PLANET, eq, ofDate = false, magnitude = el.typicalMagnitude)
    }

    internal data class Vec3(val x: Double, val y: Double, val z: Double)

    /** Orbital elements at J2000 and their rates per Julian century. Angles in degrees, a in AU. */
    internal class Elements(
        val nameKo: String,
        val typicalMagnitude: Double,
        private val a0: Double, private val aRate: Double,
        private val e0: Double, private val eRate: Double,
        private val i0: Double, private val iRate: Double,
        private val l0: Double, private val lRate: Double,
        private val peri0: Double, private val periRate: Double,
        private val node0: Double, private val nodeRate: Double,
    ) {
        /** Heliocentric ecliptic (J2000) position in AU. */
        fun heliocentric(t: Double): Vec3 {
            val a = a0 + aRate * t
            val e = e0 + eRate * t
            val i = (i0 + iRate * t) * DEG
            val l = l0 + lRate * t
            val peri = peri0 + periRate * t
            val node = node0 + nodeRate * t
            val w = (peri - node) * DEG
            val o = node * DEG
            val m = normalizeDeg(l - peri) * DEG
            val ea = solveKepler(m, e)
            val xp = a * (cos(ea) - e)
            val yp = a * sqrt(1 - e * e) * sin(ea)
            return Vec3(
                (cos(w) * cos(o) - sin(w) * sin(o) * cos(i)) * xp + (-sin(w) * cos(o) - cos(w) * sin(o) * cos(i)) * yp,
                (cos(w) * sin(o) + sin(w) * cos(o) * cos(i)) * xp + (-sin(w) * sin(o) + cos(w) * cos(o) * cos(i)) * yp,
                (sin(w) * sin(i)) * xp + (cos(w) * sin(i)) * yp,
            )
        }

        private fun solveKepler(m: Double, e: Double): Double {
            var ea = m + e * sin(m)
            repeat(10) {
                val delta = (ea - e * sin(ea) - m) / (1 - e * cos(ea))
                ea -= delta
                if (abs(delta) < 1e-10) return ea
            }
            return ea
        }
    }

    internal val PLANETS = linkedMapOf(
        "mercury" to Elements("수성", 0.0, 0.38709927, 0.00000037, 0.20563593, 0.00001906, 7.00497902, -0.00594749, 252.25032350, 149472.67411175, 77.45779628, 0.16047689, 48.33076593, -0.12534081),
        "venus" to Elements("금성", -4.2, 0.72333566, 0.00000390, 0.00677672, -0.00004107, 3.39467605, -0.00078890, 181.97909950, 58517.81538729, 131.60246718, 0.00268329, 76.67984255, -0.27769418),
        "earth" to Elements("지구", 0.0, 1.00000261, 0.00000562, 0.01671123, -0.00004392, -0.00001531, -0.01294668, 100.46457166, 35999.37244981, 102.93768193, 0.32327364, 0.0, 0.0),
        "mars" to Elements("화성", 0.7, 1.52371034, 0.00001847, 0.09339410, 0.00007882, 1.84969142, -0.00813131, -4.55343205, 19140.30268499, -23.94362959, 0.44441088, 49.55953891, -0.29257343),
        "jupiter" to Elements("목성", -2.3, 5.20288700, -0.00011607, 0.04838624, -0.00013253, 1.30439695, -0.00183714, 34.39644051, 3034.74612775, 14.72847983, 0.21252668, 100.47390909, 0.20469106),
        "saturn" to Elements("토성", 0.6, 9.53667594, -0.00125060, 0.05386179, -0.00050991, 2.48599187, 0.00193609, 49.95424423, 1222.49362201, 92.59887831, -0.41897216, 113.66242448, -0.28867794),
    )
}
