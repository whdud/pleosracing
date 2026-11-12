package com.kepler22b.sky

import kotlin.math.cos

/**
 * Horizon coordinates of everything in the sky for one instant and place.
 *
 * Computing this is the expensive part (a few thousand trigonometric conversions), so it is done once
 * per time step; panning and zooming only re-project it through a [Camera].
 */
class SkySnapshot private constructor(
    val epochMillis: Long,
    val observer: Observer,
    val catalog: SkyCatalog,
    /** Altitude and azimuth per star, same order as [SkyCatalog.stars]: [alt0, az0, alt1, az1, ...]. */
    val starAltAz: DoubleArray,
    val constellationLines: List<List<List<Horizontal>>>,
    val constellationLabels: List<Horizontal>,
    val bodies: List<Pair<Body, Horizontal>>,
) {
    val sunAltitude: Double = bodies.first { it.first.kind == BodyKind.SUN }.second.alt

    fun starHorizontal(index: Int) = Horizontal(starAltAz[index * 2], starAltAz[index * 2 + 1])

    companion object {
        fun compute(catalog: SkyCatalog, epochMillis: Long, observer: Observer): SkySnapshot {
            val tf = HorizonTransform(epochMillis, observer)
            val starAltAz = DoubleArray(catalog.stars.size * 2)
            catalog.stars.forEachIndexed { i, star ->
                val h = tf.toHorizontal(star.position)
                starAltAz[i * 2] = h.alt
                starAltAz[i * 2 + 1] = h.az
            }
            val lines = catalog.constellations.map { c -> c.lines.map { line -> line.map(tf::toHorizontal) } }
            val labels = catalog.constellations.map { tf.toHorizontal(it.label) }
            val bodies = SolarSystem.bodies(epochMillis).map { body ->
                var h = if (body.ofDate) tf.toHorizontalOfDate(body.equatorial) else tf.toHorizontal(body.equatorial)
                if (body.kind == BodyKind.MOON) {
                    // Topocentric parallax: the Moon appears up to ~1° lower than its geocentric position.
                    h = h.copy(alt = h.alt - 0.95 * cos(h.alt * DEG))
                }
                body to h
            }
            return SkySnapshot(epochMillis, observer, catalog, starAltAz, lines, labels, bodies)
        }
    }
}
