package md.grin.projector

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/** Shared state about the running home screen. */
object App {
    @Volatile var isForeground = false
}

/**
 * Connects to the projector's own ADB over localhost. That gives the phone remote the same
 * powers as a USB cable: pressing any key in any app, typing text, opening apps, granting
 * this app's permissions. The first connection makes the projector ask "Allow USB debugging?"
 * once — press "Always allow".
 */
object Adb {
    private const val TAG = "Adb"
    private val ports = listOf(5555)
    private var dadb: Dadb? = null
    @Volatile var lastError: String? = null
        private set
    @Volatile var connected = false
        private set

    @Synchronized
    fun shell(ctx: Context, cmd: String): Boolean {
        repeat(2) {
            try {
                val d = dadb ?: connect(ctx) ?: return false
                val r = d.shell(cmd)
                connected = true
                if (r.exitCode != 0) Log.w(TAG, "$cmd -> ${r.exitCode} ${r.allOutput}")
                return r.exitCode == 0
            } catch (e: Throwable) {
                lastError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "shell failed: $lastError")
                try { dadb?.close() } catch (_: Throwable) {}
                dadb = null
                connected = false
            }
        }
        return false
    }

    private var live: dadb.AdbShellStream? = null

    /**
     * Key presses go through one shell that stays open, and we don't wait for each command to finish.
     * That removes the per-press connection setup, so the phone touchpad feels much quicker.
     */
    @Synchronized
    fun key(ctx: Context, code: Int): Boolean {
        try {
            val s = live ?: (dadb ?: connect(ctx))?.openShell()?.also { live = it } ?: return false
            s.write("input keyevent $code\n")
            return true
        } catch (e: Throwable) {
            try { live?.close() } catch (_: Throwable) {}
            live = null
        }
        return shell(ctx, "input keyevent $code")
    }

    /** Silent install (used for updates). The app restarts itself afterwards. */
    @Synchronized
    fun install(ctx: Context, apk: File): Boolean {
        return try {
            val d = dadb ?: connect(ctx) ?: return false
            d.install(apk, "-r")
            true
        } catch (e: Throwable) {
            lastError = e.message ?: e.javaClass.simpleName
            false
        }
    }

    /** `input text` only types plain Latin text, so spaces and shell characters are escaped. */
    fun text(ctx: Context, s: String): Boolean {
        val safe = s.replace("\\", "").replace("'", "").replace(" ", "%s")
        return shell(ctx, "input text '$safe'")
    }

    private fun connect(ctx: Context): Dadb? {
        val priv = File(ctx.filesDir, "adbkey")
        val pub = File(ctx.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        val keys = AdbKeyPair.read(priv, pub)
        for (port in ports) {
            try {
                val d = Dadb.create("127.0.0.1", port, keys)
                d.shell("echo ok")
                dadb = d
                connected = true
                lastError = null
                return d
            } catch (e: Throwable) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        return null
    }

    /**
     * One-time setup the projector's stripped settings menu can't do:
     * lets this app see what's playing, open apps from the phone, be the home screen,
     * and makes system animations shorter so everything feels quicker.
     */
    fun setup(ctx: Context): Boolean {
        val pkg = ctx.packageName
        if (!shell(ctx, "echo ok")) return false
        shell(ctx, "cmd notification allow_listener $pkg/$pkg.MediaListener")
        shell(ctx, "appops set $pkg SYSTEM_ALERT_WINDOW allow")
        shell(ctx, "cmd package set-home-activity $pkg/$pkg.MainActivity")
        shell(ctx, "settings put global window_animation_scale 0.5")
        shell(ctx, "settings put global transition_animation_scale 0.5")
        shell(ctx, "settings put global animator_duration_scale 0.5")
        shell(ctx, "dumpsys deviceidle whitelist +$pkg")
        return true
    }
}
