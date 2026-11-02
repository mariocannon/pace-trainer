package com.mariocannon.pacetrainer.engine

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One GPS reading. [timeMs] must be monotonic (Android: elapsedRealtimeNanos / 1e6). */
data class GpsFix(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,
    val speedMps: Float?,
)

enum class GpsStatus { NO_GPS, STOPPED, MOVING }

data class PaceReading(
    val status: GpsStatus,
    val speedMps: Double?,
    val paceSecPerKm: Double?,
    val accuracyM: Float?,
)

/**
 * Turns noisy GPS fixes into a smoothed running pace. Pure Kotlin, no Android imports.
 *
 * - Fixes worse than [maxAccuracyM] are dropped.
 * - NO_GPS until [fixesToLock] consecutive good fixes; lock is lost after [lockTimeoutMs] with no good fix.
 * - Speed = average over a rolling [windowMs] window (Doppler speed if every fix has it, else haversine
 *   distance / time), then an EMA on top.
 * - Below [stoppedBelowMps] the status is STOPPED.
 */
class PaceEngine(
    private val maxAccuracyM: Float = 20f,
    private val windowMs: Long = 15_000L,
    private val emaAlpha: Double = 0.3,
    private val fixesToLock: Int = 3,
    private val stoppedBelowMps: Double = 0.8,
    private val lockTimeoutMs: Long = 5_000L,
) {
    private val window = ArrayDeque<GpsFix>()
    private var goodStreak = 0
    private var locked = false
    private var lastGoodMs: Long? = null
    private var smoothed: Double? = null
    private var lastAccuracy: Float? = null

    fun reset() {
        window.clear()
        goodStreak = 0
        locked = false
        lastGoodMs = null
        smoothed = null
        lastAccuracy = null
    }

    fun onFix(fix: GpsFix): PaceReading {
        lastAccuracy = fix.accuracyM
        val acc = fix.accuracyM
        if (acc == null || acc > maxAccuracyM) {
            if (!locked) goodStreak = 0
            return checkTimeout(fix.timeMs)
        }

        val last = window.lastOrNull()
        if (last != null && fix.timeMs <= last.timeMs) return current()

        lastGoodMs = fix.timeMs
        window.addLast(fix)
        while (window.isNotEmpty() && fix.timeMs - window.first().timeMs > windowMs) {
            window.removeFirst()
        }

        if (!locked) {
            goodStreak++
            if (goodStreak >= fixesToLock) locked = true
        }
        if (locked) {
            rawSpeed()?.let { raw ->
                val prev = smoothed
                smoothed = if (prev == null) raw else prev + emaAlpha * (raw - prev)
            }
        }
        return current()
    }

    /** Call about once a second so a lost GPS signal is noticed even when no fixes arrive. */
    fun onTick(nowMs: Long): PaceReading = checkTimeout(nowMs)

    private fun checkTimeout(nowMs: Long): PaceReading {
        val lastGood = lastGoodMs
        if (locked && lastGood != null && nowMs - lastGood > lockTimeoutMs) {
            val acc = lastAccuracy
            reset()
            lastAccuracy = acc
        }
        return current()
    }

    private fun rawSpeed(): Double? {
        if (window.isEmpty()) return null
        if (window.all { it.speedMps != null }) {
            return window.map { it.speedMps!!.toDouble() }.average()
        }
        if (window.size < 2) return null
        val spanMs = window.last().timeMs - window.first().timeMs
        if (spanMs < 2_000) return null
        var distance = 0.0
        for (i in 1 until window.size) distance += haversineM(window[i - 1], window[i])
        return distance / (spanMs / 1000.0)
    }

    private fun current(): PaceReading {
        val s = smoothed
        if (!locked || s == null) return PaceReading(GpsStatus.NO_GPS, null, null, lastAccuracy)
        if (s < stoppedBelowMps) return PaceReading(GpsStatus.STOPPED, s, null, lastAccuracy)
        return PaceReading(GpsStatus.MOVING, s, 1000.0 / s, lastAccuracy)
    }
}

internal fun haversineM(a: GpsFix, b: GpsFix): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
}
