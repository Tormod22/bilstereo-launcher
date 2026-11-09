package no.bil.launcher

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Holder T'OS «i forgrunnen» mens posisjonen brukes, så Android ikke struper GPS-en når
 * Google Maps / Car link dekker hele skjermen. Selve GPS-en styres av [Hub]; tjenesten viser bare
 * et stille varsel («T'OS bruker posisjon»).
 *
 * Android 12+ lar bare en app starte en slik tjeneste mens den vises, derfor [startIfForeground].
 */
class GpsService : Service() {
    companion object {
        private const val CHANNEL = "tos_location"
        private const val ID = 4711
        @Volatile var running = false
            private set

        /** Starter tjenesten hvis T'OS har posisjonstillatelse (og vises nå, Android 12+). Feiler stille ellers. */
        fun startIfForeground(c: Context) {
            if (running) return
            // Dvale (tenning av / skjermen av): ingen posisjon – startes igjen når stereoen våkner og T'OS vises
            if (!Power.plan.gps) return
            if (!c.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) && !c.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) return
            // Android 12+ krever at T'OS vises. Android 10/11 (denne stereoen: ekte Android 10) tillater start fra bakgrunnen –
            // da kommer posisjonen tilbake etter dvale selv om stereoen viser en annen app (CarPlay, Spotify) når den våkner
            if (Build.VERSION.SDK_INT >= 31) {
                val me = ActivityManager.RunningAppProcessInfo().also { runCatching { ActivityManager.getMyMemoryState(it) } }
                if (me.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) return
            }
            // ForegroundServiceStartNotAllowedException (Android 12+) o.l.: prøv igjen neste gang T'OS vises
            runCatching { androidx.core.content.ContextCompat.startForegroundService(c, Intent(c, GpsService::class.java)) }
        }

        /** Tenningen av: stopp tjenesten (varselet forsvinner, Android slipper å holde T'OS i forgrunnen). */
        fun stop(c: Context) {
            if (!running) return
            runCatching { c.stopService(Intent(c, GpsService::class.java)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var note: android.app.Notification? = null
        val ok = runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "T'OS bruker posisjon", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Holder fart, kart og turmåler i gang mens andre apper vises"
                    setShowBadge(false)
                })
            }
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle("T'OS bruker posisjon")
                .setContentText("Fart, kart og turmåler")
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(open)
                .build()
            note = n
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, ID, n, type)
        }.isSuccess
        running = ok
        if (!ok) {
            // Android krever startForeground() etter startForegroundService(), også når vi gir opp – ellers krasjer
            // hele T'OS («did not then call startForeground»). Prøv uten type (bruker typen fra manifestet), og stopp så.
            note?.let { n -> runCatching { startForeground(ID, n) } }
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
        // Drepes tjenesten, startes den igjen neste gang T'OS vises (ikke automatisk fra bakgrunnen)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }
}
