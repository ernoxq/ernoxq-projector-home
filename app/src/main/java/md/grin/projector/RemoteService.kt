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
        Aerials.trim(applicationContext)
        SleepTimer.start(applicationContext)
        Apps.detectAirplay(applicationContext)
        updates.postDelayed({ Thread { Adb.airplayReady(applicationContext) }.start() }, 25_000)
        // Our own AirPlay receiver: put in place (or updated) soon after start, not only with the 6-hour check.
        updates.postDelayed({ Thread { Updater.checkReceiver(applicationContext) }.start() }, 45_000)
        // Right after a start or an update: our home screen stays the default, the firmware's update nag stays off.
        updates.postDelayed({ Thread { Adb.quietSystem(applicationContext) }.start() }, 3_000)
        updates.postDelayed(airplayWatch, 30_000)
        updates.postDelayed(aerialWatch, 30_000)
        registerReceiver(volumeChanged, android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
    }

    private val updates = android.os.Handler(android.os.Looper.getMainLooper())

    /** Checks GitHub for a newer build a minute after start, then every 6 hours. */
    private val updateCheck = object : Runnable {
        override fun run() {
            val quiet = (App.isForeground || Aerials.showing) && SleepTimer.idleMs() > 5 * 60_000L && Media.current(applicationContext)?.playing != true
            if (quiet) {
                Thread { Updater.check(applicationContext) }.start()
                updates.postDelayed(this, 6 * 60 * 60_000L)
            } else {
                updates.postDelayed(this, 10 * 60_000L) // busy: try again in 10 minutes
            }
        }
    }

    /**
     * Back to the home screen when AirPlay ends: while another app is on screen, look every few seconds
     * for an open AirPlay session (a connection on the receiver's port 7000). When a session that was
     * open closes and the receiver is still on screen, go home.
     */
    @Volatile private var airplaySession = false
    @Volatile private var airplayBusy = false
    private val airplayWatch = object : Runnable {
        override fun run() {
            if (!App.isForeground && !Aerials.showing && !airplayBusy && Apps.isInstalled(applicationContext, Apps.AIRPLAY)) {
                airplayBusy = true
                Thread {
                    try {
                        val ctx = applicationContext
                        // Only while the receiver itself is on screen: watching YouTube no longer starts
                        // shell commands on the projector every 4 seconds.
                        if (Apps.foreground(ctx) != Apps.AIRPLAY) { airplaySession = false; return@Thread }
                        val n = Adb.run(ctx, "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | awk 'toupper(\$2) ~ /:1B58\$/ && \$4==\"01\"' | wc -l")
                            ?.trim()?.toIntOrNull() ?: return@Thread
                        if (n > 0) {
                            airplaySession = true
                        } else if (airplaySession) {
                            airplaySession = false
                            updates.post { Apps.goHome(ctx) }
                        }
                    } finally { airplayBusy = false }
                }.start()
            } else if (App.isForeground) {
                airplaySession = false
            }
            updates.postDelayed(this, 4_000)
        }
    }

    /**
     * Home screen left alone for 3 minutes with nothing playing: the screensaver comes on by itself.
     * While music plays it doesn't (it may still be started by hand, and then shows stored flights only).
     */
    private val aerialWatch = object : Runnable {
        override fun run() {
            val ctx = applicationContext
            if (App.isForeground && !Aerials.showing && Aerials.isOn(ctx) && SleepTimer.idleMs() >= Aerials.IDLE_MS &&
                !Aerials.musicOn(ctx)) Aerials.show(ctx)
            updates.postDelayed(this, 15_000)
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
            // Who draws the stock volume bar is only a note for the menu: look it up once, not on every press.
            if (c.getSharedPreferences("home", Context.MODE_PRIVATE).contains("volume_ui")) return
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
