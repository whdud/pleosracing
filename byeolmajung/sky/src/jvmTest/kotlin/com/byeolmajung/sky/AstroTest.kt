package com.byeolmajung.sky

import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AstroTest {

    private fun millis(iso: String) = ZonedDateTime.parse(iso).toInstant().toEpochMilli()

    private val seoul = Observer(37.5665, 126.9780)

    @Test
    fun julianDayAtJ2000() {
        assertEquals(2451545.0, AstroTime.julianDay(millis("2000-01-01T12:00:00Z")), 1e-9)
    }

    @Test
    fun gmstMatchesMeeusExample() {
        // Meeus example 12.a: 1987-04-10 0h UT, GMST = 13h10m46.3668s
        val expected = (13 + 10 / 60.0 + 46.3668 / 3600.0) * 15
        assertEquals(expected, AstroTime.gmst(millis("1987-04-10T00:00:00Z")), 1e-3)
    }

    @Test
    fun polarisAltitudeEqualsLatitude() {
        val polaris = Equatorial(37.95456, 89.26411)
        val h = HorizonTransform(millis("2026-10-23T13:00:00Z"), seoul).toHorizontal(polaris)
        assertEquals(seoul.latitude, h.alt, 1.0)
        assertTrue(h.az < 2 || h.az > 358, "Polaris should be due north, was ${h.az}")
    }

    @Test
    fun objectOnMeridianIsDueSouth() {
        val t = millis("2026-10-23T13:00:00Z")
        val lst = AstroTime.localSiderealTime(t, seoul.longitude)
        val h = HorizonTransform(t, seoul).toHorizontalOfDate(Equatorial(lst, 0.0))
        assertEquals(90 - seoul.latitude, h.alt, 1e-6)
        assertEquals(180.0, h.az, 1e-6)
    }

    @Test
    fun sunDeclinationAtSolsticeAndEquinox() {
        fun sunDec(iso: String) = SolarSystem.bodies(millis(iso)).first { it.kind == BodyKind.SUN }.equatorial.dec
        assertEquals(23.44, sunDec("2026-06-21T08:24:00Z"), 0.1)
        assertEquals(0.0, sunDec("2026-03-20T14:46:00Z"), 0.1)
    }

    @Test
    fun moonIsFullAndNewOnKnownDates() {
        fun moon(iso: String) = SolarSystem.bodies(millis(iso)).first { it.kind == BodyKind.MOON }
        assertTrue(moon("2026-03-03T11:38:00Z").phase > 0.98, "full moon 2026-03-03")
        assertTrue(moon("2026-03-19T01:23:00Z").phase < 0.02, "new moon 2026-03-19")
    }

    @Test
    fun brightStarSeparationsMatchCatalog() {
        // Vega to Altair is about 34.2°, a quick check of the separation helper and coordinate units.
        val vega = Equatorial(279.23473, 38.78369)
        val altair = Equatorial(297.69583, 8.86832)
        assertEquals(34.2, separation(vega, altair), 0.2)
    }

    @Test
    fun windshieldCameraPutsViewCenterInScreenCenter() {
        val cam = Camera(ViewMode.WINDSHIELD, azimuth = 180.0, altitude = 20.0, fieldOfView = 90.0, width = 1600, height = 900)
        val p = assertNotNull(cam.project(20.0, 180.0))
        assertEquals(800f, p.x, 0.5f)
        assertEquals(450f, p.y, 0.5f)
        // East of south is to the left when facing south; higher is up.
        val left = assertNotNull(cam.project(20.0, 150.0))
        assertTrue(left.x < 800f)
        val up = assertNotNull(cam.project(40.0, 180.0))
        assertTrue(up.y < 450f)
        assertNull(cam.project(-20.0, 0.0), "the point straight behind should not project")
    }

    @Test
    fun sunroofCameraIsAnUnmirroredViewFromBelow() {
        // Car facing north: front (north) at the top, and like a star chart held overhead, east on the left.
        val cam = Camera(ViewMode.SUNROOF, azimuth = 0.0, altitude = 90.0, fieldOfView = 180.0, width = 1000, height = 1000)
        val zenith = assertNotNull(cam.project(90.0, 0.0))
        assertEquals(500f, zenith.x, 0.5f)
        assertEquals(500f, zenith.y, 0.5f)
        val north = assertNotNull(cam.project(0.0, 0.0))
        assertEquals(500f, north.x, 0.5f)
        assertEquals(0f, north.y, 0.5f)
        val east = assertNotNull(cam.project(0.0, 90.0))
        assertEquals(0f, east.x, 0.5f)
    }

    @Test
    fun planetsAreNearTheEcliptic() {
        SolarSystem.bodies(millis("2026-10-23T13:00:00Z")).filter { it.kind == BodyKind.PLANET }.forEach { body ->
            // Ecliptic latitude of naked-eye planets stays within ~8°; check via distance from the ecliptic plane.
            val eq = body.equatorial
            val sinBeta = kotlin.math.sin(eq.dec * DEG) * kotlin.math.cos(23.439 * DEG) -
                kotlin.math.cos(eq.dec * DEG) * kotlin.math.sin(23.439 * DEG) * kotlin.math.sin(eq.ra * DEG)
            assertTrue(abs(kotlin.math.asin(sinBeta) * RAD) < 8.5, "${body.id} far from ecliptic")
        }
    }
}

class StoryTest {
    private val seoul = Observer(37.5665, 126.9780)
    private val zone = java.time.ZoneId.of("Asia/Seoul")

    @Test
    fun orionIsBestInWinterEvenings() {
        val from = java.time.ZonedDateTime.parse("2026-08-01T12:00:00+09:00").toInstant()
        val orion = Stories.all.first { it.id == "orion_scorpion" }.focus
        val t = assertNotNull(Visibility.nextGoodTime(orion, seoul, from.toEpochMilli(), utcOffsetMinutes = 540))
        val local = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(t), zone)
        // Orion climbs 35° high before dawn from late summer/autumn; it must be found well before next summer.
        assertTrue(local.isBefore(java.time.ZonedDateTime.parse("2026-12-31T00:00:00+09:00")), "found $local")
        assertTrue(local.hour in 0..4 || local.hour >= 20, "should be night, was $local")
    }

    @Test
    fun numberFormattingIsPlatformIndependent() {
        assertEquals("1.5", Describe.oneDecimal(1.46))
        assertEquals("-0.7", Describe.oneDecimal(-0.72))
        assertEquals("0.0", Describe.oneDecimal(-0.01))
        assertEquals("1,300", Describe.thousands(1300))
        assertEquals("25", Describe.thousands(25))
    }

    @Test
    fun everyStoryPointsAtKnownConstellations() {
        val abbrs = setOf("Lyr", "Aql", "UMa", "Ori", "Sco", "Per", "And", "Cas", "Cep")
        Stories.all.forEach { story -> story.constellations.forEach { assertTrue(it in abbrs) } }
    }
}

class CameraTest {
    @Test
    fun unprojectInvertsProject() {
        val cams = listOf(
            Camera(ViewMode.WINDSHIELD, 135.0, 30.0, 70.0, 1920, 720),
            Camera(ViewMode.SUNROOF, 200.0, 90.0, 180.0, 1000, 800),
        )
        val out = DoubleArray(3)
        for (cam in cams) for ((alt, az) in listOf(40.0 to 140.0, 10.0 to 100.0, 75.0 to 250.0)) {
            val p = cam.project(alt, az) ?: continue
            assertTrue(cam.unproject(p.x, p.y, out))
            val expected = Camera.direction(az, alt)
            for (i in 0..2) assertEquals(expected[i], out[i], 1e-4)
        }
    }
}
