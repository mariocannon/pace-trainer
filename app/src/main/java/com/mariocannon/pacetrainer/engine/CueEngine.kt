package com.mariocannon.pacetrainer.engine

import kotlin.math.roundToLong

enum class Cue { SILENT, NO_GPS, ON_PACE, TOO_SLOW, TOO_FAST }

/** What to play: [cue] repeated every [intervalMs] (0 = nothing). */
data class CueDecision(val cue: Cue, val intervalMs: Long)

/**
 * Decides which audio cue to play from the smoothed pace. Pure Kotlin.
 *
 * - Silent for the first [warmupMs] after start (GPS settling, getting going).
 * - A new cue only takes over after it has been the candidate for [holdMs] (hysteresis).
 * - TOO_SLOW clicks speed up the further off pace you are; TOO_FAST clicks slow down... less.
 */
class CueEngine(
    private val toleranceSec: Double = 8.0,
    private val holdMs: Long = 5_000L,
    private val warmupMs: Long = 20_000L,
    private val maxOffSec: Double = 30.0,
) {
    private var startMs: Long? = null
    private var active = Cue.SILENT
    private var pending: Cue? = null
    private var pendingSinceMs = 0L

    fun reset(nowMs: Long) {
        startMs = nowMs
        active = Cue.SILENT
        pending = null
    }

    fun update(nowMs: Long, reading: PaceReading?, targetSecPerKm: Int): CueDecision {
        val start = startMs ?: nowMs.also { startMs = it }
        if (nowMs - start < warmupMs) return CueDecision(Cue.SILENT, 0)

        val delta = reading?.paceSecPerKm?.let { it - targetSecPerKm }
        val candidate = classify(reading, delta)
        when {
            candidate == active -> pending = null
            candidate != pending -> {
                pending = candidate
                pendingSinceMs = nowMs
            }
            nowMs - pendingSinceMs >= holdMs -> {
                active = candidate
                pending = null
            }
        }
        return CueDecision(active, intervalFor(active, delta))
    }

    private fun classify(reading: PaceReading?, delta: Double?): Cue = when {
        reading == null || reading.status == GpsStatus.NO_GPS -> Cue.NO_GPS
        reading.status == GpsStatus.STOPPED -> Cue.SILENT
        delta == null -> Cue.NO_GPS
        delta > toleranceSec -> Cue.TOO_SLOW
        delta < -toleranceSec -> Cue.TOO_FAST
        else -> Cue.ON_PACE
    }

    private fun intervalFor(cue: Cue, delta: Double?): Long = when (cue) {
        Cue.TOO_SLOW -> lerp(600, 250, frac(delta?.let { it - toleranceSec }))
        Cue.TOO_FAST -> lerp(1500, 900, frac(delta?.let { -it - toleranceSec }))
        Cue.ON_PACE -> 30_000L
        Cue.NO_GPS -> 10_000L
        Cue.SILENT -> 0L
    }

    private fun frac(excessSec: Double?): Double =
        ((excessSec ?: 0.0) / (maxOffSec - toleranceSec)).coerceIn(0.0, 1.0)

    private fun lerp(from: Long, to: Long, f: Double): Long = (from + (to - from) * f).roundToLong()
}
