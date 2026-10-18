package app.beaguardian

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Runs while a guardian is on duty. It keeps sharing their location with the server and
 * checks for new alerts, so the phone can sound a full-screen alarm even when the app is closed.
 */
class DutyService : Service() {
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private var poll: ScheduledFuture<*>? = null
    private var lm: LocationManager? = null
    private var wake: PowerManager.WakeLock? = null
    private val seen = HashSet<String>()
    @Volatile private var lastPost = 0L

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            val now = System.currentTimeMillis()
            if (now - lastPost < 15000) return
            lastPost = now
            val token = Prefs.token(this@DutyService)
            if (token.isEmpty()) return
            val body = JSONObject().put("lat", l.latitude).put("lng", l.longitude).put("acc", l.accuracy.toDouble()).toString()
            exec.execute {
                try {
                    val (code, _) = Net.request("POST", "/api/location", token, body)
                    if (code == 401) stopForSignedOut()
                } catch (e: Exception) { /* try again on the next update */ }
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        makeChannels()
        val n = dutyNotification()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID_DUTY, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(ID_DUTY, n)
        } catch (e: Exception) {
            stopSelf()   // for example, location permission was removed
            return
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "beaguardian:duty").apply { acquire(12 * 60 * 60 * 1000L) }

        startLocation()
        poll = exec.scheduleWithFixedDelay({ checkAlerts() }, 2, 8, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        poll?.cancel(false)
        try { lm?.removeUpdates(listener) } catch (e: Exception) { /* ignore */ }
        try { if (wake?.isHeld == true) wake?.release() } catch (e: Exception) { /* ignore */ }
        exec.shutdownNow()
        super.onDestroy()
    }

    private fun startLocation() {
        try {
            val m = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm = m
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (m.isProviderEnabled(p)) m.requestLocationUpdates(p, 20000L, 0f, listener)
            }
        } catch (e: SecurityException) {
            // Location permission was not given. The website will explain this to the guardian.
        }
    }

    private fun checkAlerts() {
        val token = Prefs.token(this)
        if (token.isEmpty()) { stopForSignedOut(); return }
        try {
            val (code, text) = Net.request("GET", "/api/state", token)
            if (code == 401) { stopForSignedOut(); return }
            if (code != 200) return
            val j = JSONObject(text)
            val me = j.optJSONObject("me")
            if (me != null && !me.optBoolean("onDuty", false)) { stopSelf(); return }
            val arr = j.optJSONArray("alerts") ?: return
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                val id = a.optString("id")
                if (id.isEmpty() || !seen.add(id)) continue
                val my = if (a.isNull("my")) "" else a.optString("my")
                if (my.isNotEmpty()) continue            // already answered
                if (MainActivity.visible) continue       // the open app shows its own alarm
                showAlarm(a.optString("name", "Someone"))
            }
        } catch (e: Exception) { /* try again in a few seconds */ }
    }

    private fun stopForSignedOut() {
        Prefs.clear(this)
        stopSelf()
    }

    private fun openApp(requestCode: Int): PendingIntent {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun dutyNotification(): Notification =
        NotificationCompat.Builder(this, CH_DUTY)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("On duty")
            .setContentText("You will be alerted if someone nearby needs help.")
            .setOngoing(true)
            .setContentIntent(openApp(0))
            .build()

    private fun showAlarm(name: String) {
        val pi = openApp(1)
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("$name needs help nearby")
            .setContentText("Open the app to see where. Call 112 first.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID_ALERT, n)
    }

    private fun makeChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_DUTY, "On duty", NotificationManager.IMPORTANCE_LOW))
        val alert = NotificationChannel(CH_ALERT, "Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Someone nearby needs help"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 150, 400, 150, 800)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(alert)
    }

    companion object {
        const val CH_DUTY = "duty"
        const val CH_ALERT = "alerts"
        const val ID_DUTY = 1
        const val ID_ALERT = 2
    }
}
