package com.mariocannon.pacetrainer.service

import com.mariocannon.pacetrainer.engine.PaceReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class PaceUiState(
    val running: Boolean = false,
    val targetSecPerKm: Int = 300,
    val reading: PaceReading? = null,
)

/** Shared state between the foreground service and the UI. */
object PaceStateHolder {
    const val MIN_TARGET = 150 // 2:30 /km
    const val MAX_TARGET = 720 // 12:00 /km

    private val _state = MutableStateFlow(PaceUiState())
    val state: StateFlow<PaceUiState> = _state

    fun setTarget(secPerKm: Int) =
        _state.update { it.copy(targetSecPerKm = secPerKm.coerceIn(MIN_TARGET, MAX_TARGET)) }

    fun setRunning(running: Boolean) =
        _state.update { it.copy(running = running, reading = if (running) it.reading else null) }

    fun setReading(reading: PaceReading) = _state.update { it.copy(reading = reading) }
}
