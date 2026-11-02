package com.mariocannon.pacetrainer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.mariocannon.pacetrainer.MainActivity
import com.mariocannon.pacetrainer.R
import com.mariocannon.pacetrainer.audio.ClickPlayer
import com.mariocannon.pacetrainer.engine.Cue
import com.mariocannon.pacetrainer.engine.CueEngine
import com.mariocannon.pacetrainer.engine.GpsFix
import com.mariocannon.pacetrainer.engine.GpsStatus
import com.mariocannon.pacetrainer.engine.PaceEngine
import com.mariocannon.pacetrainer.engine.PaceReading
import com.mariocannon.pacetrainer.engine.formatPace

/**
 * Foreground service that owns a run: GPS → PaceEngine → notification + UI state.
 * Runs on its own HandlerThread with a partial wake lock so it keeps going screen-off.
 */
class PaceService : Service(), LocationListener {

    companion object {
        const val ACTION_START = "com.mariocannon.pacetrainer.START"
        const val ACTION_STOP = "com.mariocannon.pacetrainer.STOP"
        private const val CHANNEL_ID = "pace_run"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 1_000L
        private const val MAX_RUN_MS = 4 * 60 * 60 * 1000L
    }

    private lateinit var locationManager: LocationManager
    private lateinit var notificationManager: NotificationManager
    private val engine = PaceEngine()
    private val cueEngine = CueEngine()
    private var clickPlayer: ClickPlayer? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = false
    private var lastNotificationText: String? = null

    private val tick = object : Runnable {
        override fun run() {
            publish(engine.onTick(SystemClock.elapsedRealtime()))
            handler?.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Pace training", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) startRun()
        return START_NOT_STICKY
    }

    private fun startRun() {
        val notification = buildNotification(notificationText(null, Cue.SILENT))
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            stopSelf()
            return
        }

        running = true
        engine.reset()
        cueEngine.reset(SystemClock.elapsedRealtime())
        clickPlayer = ClickPlayer(this)
        PaceStateHolder.setRunning(true)

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PaceTrainer:run")
            .apply {
                setReferenceCounted(false)
                acquire(MAX_RUN_MS)
            }

        val t = HandlerThread("pace-gps").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h

        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this, t.looper)
        } catch (e: SecurityException) {
            stopSelf()
            return
        }
        h.postDelayed(tick, TICK_MS)
    }

    override fun onLocationChanged(location: Location) {
        val fix = GpsFix(
            timeMs = location.elapsedRealtimeNanos / 1_000_000L,
            lat = location.latitude,
            lon = location.longitude,
            accuracyM = if (location.hasAccuracy()) location.accuracy else null,
            speedMps = if (location.hasSpeed()) location.speed else null,
        )
        publish(engine.onFix(fix))
    }

    // Overridden explicitly: these are only default methods from API 30, older phones need them implemented.
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    private fun publish(reading: PaceReading) {
        if (!running) return
        val target = PaceStateHolder.state.value.targetSecPerKm
        val decision = cueEngine.update(SystemClock.elapsedRealtime(), reading, target)
        clickPlayer?.setCue(decision.cue, decision.intervalMs)
        PaceStateHolder.setReading(reading, decision.cue)
        val text = notificationText(reading, decision.cue)
        if (text != lastNotificationText) {
            lastNotificationText = text
            notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun notificationText(reading: PaceReading?, cue: Cue): String {
        val target = formatPace(PaceStateHolder.state.value.targetSecPerKm.toDouble())
        val pace = reading?.paceSecPerKm
        return when {
            reading == null || reading.status == GpsStatus.NO_GPS -> "Waiting for GPS… · target $target"
            reading.status == GpsStatus.STOPPED || pace == null -> "Stopped · target $target"
            else -> "${formatPace(pace)} /km · target $target" + when (cue) {
                Cue.TOO_SLOW -> " · speed up"
                Cue.TOO_FAST -> " · ease off"
                Cue.ON_PACE -> " · on pace"
                else -> ""
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PaceService::class.java).setAction(ACTION_STOP),
            flags,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Pace Trainer")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    override fun onDestroy() {
        running = false
        clickPlayer?.release()
        clickPlayer = null
        handler?.removeCallbacksAndMessages(null)
        locationManager.removeUpdates(this)
        thread?.quitSafely()
        thread = null
        handler = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        PaceStateHolder.setRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
