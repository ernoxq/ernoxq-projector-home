package md.grin.projector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder

/** Keeps the phone-remote server alive in the background, even while another app is on screen. */
class RemoteService : Service() {
    private var server: RemoteServer? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("remote", "Пульт", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "remote")
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle("Пульт с телефона")
            .setContentText(RemoteServer.url())
            .setOngoing(true)
            .build()
        startForeground(1, n)
        server = RemoteServer(applicationContext).also { it.start() }
        Media.attach(applicationContext)
        updates.postDelayed(updateCheck, 60_000)
        Thread { Picture.restore(applicationContext) }.start()
        SleepTimer.start(applicationContext)
        updates.postDelayed({ Thread { Adb.airplayReady(applicationContext) }.start() }, 25_000)
        registerReceiver(volumeChanged, android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
    }

    private val updates = android.os.Handler(android.os.Looper.getMainLooper())

    /** Checks GitHub for a newer build a minute after start, then every 6 hours. */
    private val updateCheck = object : Runnable {
        override fun run() {
            val quiet = App.isForeground && SleepTimer.idleMs() > 5 * 60_000L && Media.current(applicationContext)?.playing != true
            if (quiet) {
                Thread { Updater.check(applicationContext) }.start()
                updates.postDelayed(this, 6 * 60 * 60_000L)
            } else {
                updates.postDelayed(this, 10 * 60_000L) // busy: try again in 10 minutes
            }
        }
    }

    private var lastScan = 0L

    /** Volume changed by the projector's own remote: show our slider and note who drew the stock bar. */
    private val volumeChanged = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - Control.lastOwnChange < 800) return
            if (VolumeHud.available(c)) {
                val (v, muted) = Control.volumeState(c)
                VolumeHud.show(c, v, muted)
            }
            if (now - lastScan < 10_000) return
            lastScan = now
            Thread {
                Thread.sleep(250)
                val out = Adb.run(c, "dumpsys window windows | grep -iE -A 8 'Window #.*vol'") ?: return@Thread
                val titles = Regex("Window\\{[0-9a-f]+ u0 ([^ }]+)").findAll(out).map { it.groupValues[1] }
                val pkgs = Regex("(?:pkg|package|mOwnerPackage)=([A-Za-z0-9_.]+)").findAll(out).map { it.groupValues[1] }
                val owners = (titles + pkgs).distinct().toList()
                if (owners.isNotEmpty()) c.getSharedPreferences("home", Context.MODE_PRIVATE).edit()
                    .putString("volume_ui", owners.joinToString(", ")).apply()
            }.start()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        try { unregisterReceiver(volumeChanged) } catch (_: Exception) {}
        server?.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(ctx: Context) {
            try { ctx.startForegroundService(Intent(ctx, RemoteService::class.java)) } catch (_: Exception) {}
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RemoteService.start(context)
    }
}
