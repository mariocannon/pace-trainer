package com.mariocannon.pacetrainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mariocannon.pacetrainer.engine.GpsStatus
import com.mariocannon.pacetrainer.engine.NO_PACE
import com.mariocannon.pacetrainer.engine.PaceReading
import com.mariocannon.pacetrainer.engine.formatPace
import com.mariocannon.pacetrainer.service.PaceUiState
import kotlin.math.roundToInt

@Composable
fun PaceScreen(
    state: PaceUiState,
    hasLocationPermission: Boolean,
    onRequestPermission: () -> Unit,
    onTargetChange: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Pace Trainer", style = MaterialTheme.typography.titleLarge)

            TargetPicker(state.targetSecPerKm, onTargetChange)

            LivePace(state)

            val buttonModifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
            when {
                !hasLocationPermission -> Button(onClick = onRequestPermission, modifier = buttonModifier) {
                    Text("Allow location", fontSize = 20.sp)
                }
                state.running -> Button(
                    onClick = onStop,
                    modifier = buttonModifier,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Stop", fontSize = 20.sp) }
                else -> Button(onClick = onStart, modifier = buttonModifier) {
                    Text("Start", fontSize = 20.sp)
                }
            }
        }
    }
}

@Composable
private fun TargetPicker(targetSecPerKm: Int, onTargetChange: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("TARGET", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { onTargetChange(targetSecPerKm - 5) }) { Text("−5s") }
            Text(
                text = "${formatPace(targetSecPerKm.toDouble())} /km",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            FilledTonalButton(onClick = { onTargetChange(targetSecPerKm + 5) }) { Text("+5s") }
        }
    }
}

@Composable
private fun LivePace(state: PaceUiState) {
    val reading = state.reading
    val pace = reading?.paceSecPerKm
    val moving = state.running && reading?.status == GpsStatus.MOVING && pace != null
    val accuracy = reading?.accuracyM?.let { "GPS ±${it.roundToInt()} m" }

    val status = when {
        !state.running -> "Ready"
        reading == null || reading.status == GpsStatus.NO_GPS ->
            "Waiting for GPS…" + (accuracy?.let { " ($it)" } ?: "")
        reading.status == GpsStatus.STOPPED -> "Stopped" + (accuracy?.let { " · $it" } ?: "")
        else -> accuracy ?: ""
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (moving) formatPace(pace!!) else NO_PACE,
            fontSize = 96.sp,
            fontWeight = FontWeight.Bold,
            color = if (moving) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("/km", style = MaterialTheme.typography.titleMedium)
        Text(status, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
        if (state.running && reading != null) {
            Text(
                text = debugLine(reading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun debugLine(r: PaceReading): String {
    fun f(v: Double?) = v?.let { ((it * 10).roundToInt() / 10.0).toString() } ?: "–"
    val source = if (r.usingDistance || r.dopplerMps == null) "using position" else "using doppler"
    return "doppler ${f(r.dopplerMps)} · position ${f(r.distanceMps)} m/s · $source"
}
