package com.mariocannon.pacetrainer.engine

import kotlin.math.roundToInt

const val NO_PACE = "–:––"

/** 305.4 → "5:05". Returns [NO_PACE] for nonsense values. */
fun formatPace(secPerKm: Double): String {
    if (!secPerKm.isFinite() || secPerKm <= 0.0 || secPerKm >= 3600.0) return NO_PACE
    val total = secPerKm.roundToInt()
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
