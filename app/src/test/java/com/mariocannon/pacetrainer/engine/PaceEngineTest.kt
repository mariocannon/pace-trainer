package com.mariocannon.pacetrainer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaceEngineTest {

    private val startLat = -36.59
    private val lon = 174.69
    private val metresPerDegLat = 6_371_000.0 * Math.PI / 180.0

    private fun fix(
        tSec: Int,
        speed: Float? = PACE_5_00,
        acc: Float? = 5f,
        lat: Double = startLat,
    ) = GpsFix(tSec * 1000L, lat, lon, acc, speed)

    @Test
    fun noGpsUntilThreeGoodFixes() {
        val e = PaceEngine()
        assertEquals(GpsStatus.NO_GPS, e.onFix(fix(0)).status)
        assertEquals(GpsStatus.NO_GPS, e.onFix(fix(1)).status)
        assertEquals(GpsStatus.MOVING, e.onFix(fix(2)).status)
    }

    @Test
    fun poorAccuracyNeverLocks() {
        val e = PaceEngine()
        repeat(30) { assertEquals(GpsStatus.NO_GPS, e.onFix(fix(it, acc = 45f)).status) }
    }

    @Test
    fun badFixBeforeLockResetsStreak() {
        val e = PaceEngine()
        e.onFix(fix(0))
        e.onFix(fix(1))
        e.onFix(fix(2, acc = 50f))
        assertEquals(GpsStatus.NO_GPS, e.onFix(fix(3)).status)
        assertEquals(GpsStatus.NO_GPS, e.onFix(fix(4)).status)
        assertEquals(GpsStatus.MOVING, e.onFix(fix(5)).status)
    }

    @Test
    fun steadyDopplerSpeedGivesTargetPace() {
        val e = PaceEngine()
        var r: PaceReading? = null
        for (t in 0..20) r = e.onFix(fix(t))
        assertEquals(300.0, r!!.paceSecPerKm!!, 1.0)
    }

    @Test
    fun distanceFallbackWhenNoDopplerSpeed() {
        val e = PaceEngine()
        val degPerSec = PACE_5_00 / metresPerDegLat
        var r: PaceReading? = null
        for (t in 0..25) r = e.onFix(fix(t, speed = null, lat = startLat + t * degPerSec))
        assertEquals(GpsStatus.MOVING, r!!.status)
        assertEquals(300.0, r.paceSecPerKm!!, 5.0)
    }

    @Test
    fun slowShuffleIsStopped() {
        val e = PaceEngine()
        var r: PaceReading? = null
        for (t in 0..10) r = e.onFix(fix(t, speed = 0.3f))
        assertEquals(GpsStatus.STOPPED, r!!.status)
        assertNull(r.paceSecPerKm)
    }

    @Test
    fun lockLostWhenFixesStop() {
        val e = PaceEngine()
        for (t in 0..5) e.onFix(fix(t))
        assertEquals(GpsStatus.MOVING, e.onTick(8_000).status)
        assertEquals(GpsStatus.NO_GPS, e.onTick(11_000).status)
    }

    @Test
    fun noisyGpsStaysNearTruePace() {
        val e = PaceEngine()
        var r: PaceReading? = null
        for (t in 0..30) {
            r = if (t % 7 == 6) {
                e.onFix(fix(t, speed = 10f, acc = 40f)) // garbage fix, must be ignored
            } else {
                e.onFix(fix(t, speed = if (t % 2 == 0) 3.0f else 3.667f))
            }
        }
        assertEquals(300.0, r!!.paceSecPerKm!!, 10.0)
    }

    @Test
    fun followsAPaceChange() {
        val e = PaceEngine()
        for (t in 0..30) e.onFix(fix(t))
        var r: PaceReading? = null
        for (t in 31..60) r = e.onFix(fix(t, speed = PACE_4_00))
        assertEquals(240.0, r!!.paceSecPerKm!!, 3.0)
    }

    @Test
    fun formatsPace() {
        assertEquals("5:00", formatPace(300.0))
        assertEquals("5:05", formatPace(305.4))
        assertEquals("1:00", formatPace(59.6))
        assertEquals(NO_PACE, formatPace(Double.POSITIVE_INFINITY))
        assertEquals(NO_PACE, formatPace(0.0))
    }

    companion object {
        const val PACE_5_00 = 1000f / 300f
        const val PACE_4_00 = 1000f / 240f
    }
}
