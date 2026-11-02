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
    /** Debug: window-average Doppler speed the phone reported, if any. */
    val dopplerMps: Double? = null,
    /** Debug: window speed from GPS positions (path distance / time). */
    val distanceMps: Double? = null,
    /** Debug: true once Doppler speed was judged broken and positions are used instead. */
    val usingDistance: Boolean = false,
)

/**
 * Turns noisy GPS fixes into a smoothed running pace. Pure Kotlin, no Android imports.
 *
 * - Fixes worse than [maxAccuracyM] are dropped.
 * - NO_GPS until [fixesToLock] consecutive good fixes; lock is lost after [lockTimeoutMs] with no good fix.
 * - Speed = average over a rolling [windowMs] window, then an EMA on top. Doppler speed is preferred, but
 *   some phones report it stuck at ~0 while moving: if Doppler says stopped while the positions show
 *   steady movement for 10+ s, Doppler is ignored for the rest of the run and position distance is used.
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
    private var dopplerUntrusted = false
    private var lastDoppler: Double? = null
    private var lastDistance: Double? = null

    fun reset() {
        window.clear()
        goodStreak = 0
        locked = false
        lastGoodMs = null
        smoothed = null
        lastAccuracy = null
        dopplerUntrusted = false
        lastDoppler = null
        lastDistance = null
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
        val doppler = dopplerSpeed()
        val distance = distanceSpeed()
        lastDoppler = doppler
        lastDistance = distance

        if (doppler != null && !dopplerUntrusted && doppler < stoppedBelowMps) {
            val spanMs = window.last().timeMs - window.first().timeMs
            val displacement = haversineM(window.first(), window.last())
            if (spanMs >= 10_000 && displacement / (spanMs / 1000.0) > 1.0) dopplerUntrusted = true
        }
        return if (doppler != null && !dopplerUntrusted) doppler else distance
    }

    /** Average Doppler speed, only if every fix in the window has one. */
    private fun dopplerSpeed(): Double? {
        if (!window.all { it.speedMps != null }) return null
        return window.map { it.speedMps!!.toDouble() }.average()
    }

    /** Path distance over the window divided by elapsed time; needs at least 2 s of fixes. */
    private fun distanceSpeed(): Double? {
        if (window.size < 2) return null
        val spanMs = window.last().timeMs - window.first().timeMs
        if (spanMs < 2_000) return null
        var distance = 0.0
        for (i in 1 until window.size) distance += haversineM(window[i - 1], window[i])
        return distance / (spanMs / 1000.0)
    }

    private fun current(): PaceReading {
        val s = smoothed
        val reading = when {
            !locked || s == null -> PaceReading(GpsStatus.NO_GPS, null, null, lastAccuracy)
            s < stoppedBelowMps -> PaceReading(GpsStatus.STOPPED, s, null, lastAccuracy)
            else -> PaceReading(GpsStatus.MOVING, s, 1000.0 / s, lastAccuracy)
        }
        return reading.copy(dopplerMps = lastDoppler, distanceMps = lastDistance, usingDistance = dopplerUntrusted)
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
