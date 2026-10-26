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
    }

    private val updates = android.os.Handler(android.os.Looper.getMainLooper())

    /** Checks GitHub for a newer build a minute after start, then every 6 hours. */
    private val updateCheck = object : Runnable {
        override fun run() {
            Thread { Updater.check(applicationContext) }.start()
            updates.postDelayed(this, 6 * 60 * 60_000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
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
