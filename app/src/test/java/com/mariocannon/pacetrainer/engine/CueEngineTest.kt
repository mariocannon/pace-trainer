package com.mariocannon.pacetrainer.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class CueEngineTest {

    private val target = 300 // 5:00 /km

    private fun moving(pace: Double) = PaceReading(GpsStatus.MOVING, 1000.0 / pace, pace, 5f)
    private val stopped = PaceReading(GpsStatus.STOPPED, 0.2, null, 5f)
    private val noGps = PaceReading(GpsStatus.NO_GPS, null, null, 40f)

    /** Feed one reading per second from [fromSec] to [toSec] inclusive, return the last decision. */
    private fun CueEngine.feed(fromSec: Int, toSec: Int, reading: PaceReading?): CueDecision {
        var d = CueDecision(Cue.SILENT, 0)
        for (t in fromSec..toSec) d = update(t * 1000L, reading, target)
        return d
    }

    private fun started() = CueEngine().also { it.reset(0) }

    @Test
    fun silentDuringWarmup() {
        assertEquals(Cue.SILENT, started().feed(0, 19, moving(400.0)).cue)
    }

    @Test
    fun onPaceAfterHold() {
        val e = started()
        assertEquals(Cue.SILENT, e.feed(0, 23, moving(303.0)).cue)
        val d = e.feed(24, 26, moving(303.0))
        assertEquals(Cue.ON_PACE, d.cue)
        assertEquals(30_000L, d.intervalMs)
    }

    @Test
    fun tooSlowClicksGetFasterTheFurtherOff() {
        val e = started()
        val justOff = e.feed(0, 30, moving(309.0))
        assertEquals(Cue.TOO_SLOW, justOff.cue)
        assertEquals(600.0, justOff.intervalMs.toDouble(), 20.0)

        val wayOff = e.feed(31, 32, moving(345.0))
        assertEquals(Cue.TOO_SLOW, wayOff.cue)
        assertEquals(250L, wayOff.intervalMs)
    }

    @Test
    fun tooFastClicksAreSlow() {
        val d = started().feed(0, 30, moving(285.0))
        assertEquals(Cue.TOO_FAST, d.cue)
        assertEquals(true, d.intervalMs in 900..1500)
    }

    @Test
    fun briefBlipDoesNotSwitchCue() {
        val e = started()
        e.feed(0, 30, moving(300.0))
        e.feed(31, 33, moving(330.0)) // 3 s slow surge, under the 5 s hold
        assertEquals(Cue.ON_PACE, e.feed(34, 40, moving(300.0)).cue)
    }

    @Test
    fun stoppedIsSilentAndNoGpsBeeps() {
        val e = started()
        assertEquals(Cue.SILENT, e.feed(0, 30, stopped).cue)
        val d = e.feed(31, 40, noGps)
        assertEquals(Cue.NO_GPS, d.cue)
        assertEquals(10_000L, d.intervalMs)
    }
}
