package com.mariocannon.pacetrainer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.mariocannon.pacetrainer.service.PaceService
import com.mariocannon.pacetrainer.service.PaceStateHolder
import com.mariocannon.pacetrainer.ui.PaceScreen
import com.mariocannon.pacetrainer.ui.PaceTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PaceTheme {
                val state by PaceStateHolder.state.collectAsState()
                var hasLocation by remember { mutableStateOf(hasLocationPermission()) }
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { hasLocation = hasLocationPermission() }

                PaceScreen(
                    state = state,
                    hasLocationPermission = hasLocation,
                    onRequestPermission = { launcher.launch(requiredPermissions()) },
                    onTargetChange = { PaceStateHolder.setTarget(it) },
                    onStart = { startRun() },
                    onStop = { stopRun() },
                )
            }
        }
    }

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startRun() {
        if (!hasLocationPermission()) return
        ContextCompat.startForegroundService(
            this,
            Intent(this, PaceService::class.java).setAction(PaceService.ACTION_START),
        )
    }

    private fun stopRun() {
        stopService(Intent(this, PaceService::class.java))
    }
}
