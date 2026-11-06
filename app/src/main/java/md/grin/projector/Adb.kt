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
    @Volatile private var dadb: Dadb? = null
    @Volatile var lastError: String? = null
        private set
    @Volatile var connected = false
        private set

    /**
     * One shared connection. Only connecting is locked: commands run side by side over it,
     * so a slow command never holds up a key press from the phone.
     */
    private fun conn(ctx: Context): Dadb? = dadb ?: synchronized(this) { dadb ?: connect(ctx) }

    private fun drop(d: Dadb?) {
        synchronized(this) {
            if (d != null && dadb !== d) return
            try { dadb?.close() } catch (_: Throwable) {}
            dadb = null
            connected = false
        }
    }

    fun shell(ctx: Context, cmd: String): Boolean {
        repeat(2) {
            var d: Dadb? = null
            try {
                d = conn(ctx) ?: return false
                val r = d.shell(cmd)
                connected = true
                if (r.exitCode != 0) Log.w(TAG, "$cmd -> ${r.exitCode} ${r.allOutput}")
                return r.exitCode == 0
            } catch (e: Throwable) {
                lastError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "shell failed: $lastError")
                drop(d)
            }
        }
        return false
    }

    /** Runs a command and returns what it printed (null if ADB isn't available). */
    fun run(ctx: Context, cmd: String): String? = try {
        conn(ctx)?.shell(cmd)?.allOutput
    } catch (e: Throwable) {
        lastError = e.message
        null
    }

    private var live: dadb.AdbShellStream? = null

    /**
     * Key presses go through one shell that stays open, and we don't wait for each command to finish.
     * That removes the per-press connection setup, so the phone touchpad feels much quicker.
     */
    fun key(ctx: Context, code: Int): Boolean =
        writeLive(ctx, "$code") || shell(ctx, "input keyevent $code")

    /** Types text straight into the focused field (any language), through the live injector. */
    fun chars(ctx: Context, text: String): Boolean =
        writeLive(ctx, "t " + java.net.URLEncoder.encode(text, "UTF-8"))

    private val liveLock = Any()

    private fun writeLive(ctx: Context, line: String): Boolean {
        synchronized(liveLock) {
        try {
            val s = live ?: conn(ctx)?.openShell()?.also {
                live = it
                // Start the fast injector from this app's own APK. If it can't start on this firmware,
                // fall back to a loop that reads the same key codes and uses the standard `input` tool.
                val apk = ctx.applicationInfo.sourceDir
                it.write("CLASSPATH=$apk app_process / ${KeyServer::class.java.name} || while read k; do input keyevent \$k; done\n")
            } ?: return false
            s.write("$line\n")
            return true
        } catch (e: Throwable) {
            try { live?.close() } catch (_: Throwable) {}
            live = null
        }
        return false
        }
    }

    /**
     * Switches the projector off for real. Pressing the power key only opens the firmware's
     * "Power off / Restart" menu, so ask Android to shut down directly instead.
     */
    fun powerOff(ctx: Context, reason: String = "кнопка"): Boolean {
        val stamp = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale("ru")).apply { timeZone = Clock.zone }.format(Clock.now())
        ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putString("last_off", "$stamp · $reason").commit()
        if (shell(ctx, "svc power shutdown")) return true
        if (shell(ctx, "am start -a com.android.internal.intent.action.REQUEST_SHUTDOWN --ez android.intent.extra.KEY_CONFIRM false --activity-clear-task")) return true
        return shell(ctx, "am broadcast -a android.intent.action.ACTION_REQUEST_SHUTDOWN --ez android.intent.extra.KEY_CONFIRM false")
    }

    /**
     * No more system pop-ups:
     *  - "Use a different app" (which home screen?): Android forgets our choice whenever this app is updated,
     *    so the choice is set again at every start.
     *  - The firmware's "NfxAccessibilityService has detected a new version update" nag: its package is
     *    switched off (not removed). `pm enable <package>` brings it back; the names are kept in "quieted".
     */
    fun quietSystem(ctx: Context) {
        val pkg = ctx.packageName
        if (!shell(ctx, "cmd package set-home-activity $pkg/$pkg.MainActivity")) return
        val pm = ctx.packageManager
        val nags = ArrayList<String>()
        for (ai in pm.getInstalledApplications(0)) {
            if (ai.packageName == pkg || !ai.enabled) continue
            val label = try { pm.getApplicationLabel(ai).toString() } catch (_: Exception) { "" }
            var hit = ai.packageName.contains("nfx", true) || label.contains("nfx", true)
            if (!hit) {
                val services = try { pm.getPackageInfo(ai.packageName, android.content.pm.PackageManager.GET_SERVICES).services } catch (_: Exception) { null }
                hit = services?.any { it.name.contains("NfxAccessibility", true) } == true
            }
            if (hit) nags += ai.packageName
        }
        val prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)
        val done = prefs.getStringSet("quieted", emptySet())!!.toMutableSet()
        for (p in nags) {
            shell(ctx, "am force-stop $p")
            if (shell(ctx, "pm disable-user --user 0 $p")) done += p
        }
        prefs.edit().putStringSet("quieted", done).apply()
    }

    /** Makes our Apple TV-style keyboard the one Android uses. */
    fun useOurKeyboard(ctx: Context): Boolean {
        val id = "${ctx.packageName}/.TvKeyboard"
        shell(ctx, "ime enable $id")
        shell(ctx, "ime set $id")
        if (!TvKeyboard.isDefault(ctx)) shell(ctx, "settings put secure default_input_method $id")
        return TvKeyboard.isDefault(ctx)
    }

    /** Replaces the stock photo screensaver with our flights over cities. */
    fun useOurScreensaver(ctx: Context) {
        val id = "${ctx.packageName}/.AerialDream"
        shell(ctx, "settings put secure screensaver_enabled 1")
        shell(ctx, "settings put secure screensaver_components $id")
        shell(ctx, "settings put secure screensaver_default_component $id")
        shell(ctx, "settings put secure screensaver_activate_on_sleep 1")
    }

    /** Silent install (used for updates). The app restarts itself afterwards. */
    fun install(ctx: Context, apk: File): Boolean {
        return try {
            val d = conn(ctx) ?: return false
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
        // Lets the remote know which app is on screen without the heavy `dumpsys`.
        shell(ctx, "appops set $pkg GET_USAGE_STATS allow")
        quietSystem(ctx)
        shell(ctx, "settings put global window_animation_scale 0.5")
        shell(ctx, "settings put global transition_animation_scale 0.5")
        shell(ctx, "settings put global animator_duration_scale 0.5")
        shell(ctx, "dumpsys deviceidle whitelist +$pkg")
        // Lets the phone remote see when a text field is selected on the projector
        shell(ctx, "settings put secure enabled_accessibility_services $pkg/$pkg.FocusWatcher")
        shell(ctx, "settings put secure accessibility_enabled 1")
        // Apple TV-style keyboard instead of the stock one
        useOurKeyboard(ctx)
        // Correct clock: network time on, Chișinău time zone
        shell(ctx, "settings put global auto_time 1")
        shell(ctx, "settings put global auto_time_zone 0")
        shell(ctx, "service call alarm 3 s16 Europe/Chisinau")
        shell(ctx, "setprop persist.sys.timezone Europe/Chisinau")
        useOurScreensaver(ctx)
        nameAirPlay(ctx)
        airplayReady(ctx)
        return true
    }

    /**
     * Makes AirPlay instant: the receiver may jump onto the screen by itself when an iPhone or Mac
     * connects (Android 11 needs the "draw over other apps" permission for that, which the projector's
     * settings menu doesn't offer), keeps running in the background, and is started if it isn't running.
     */
    fun airplayReady(ctx: Context) {
        if (!Apps.isInstalled(ctx, Apps.AIRPLAY)) return
        val air = Apps.AIRPLAY
        if (!shell(ctx, "appops set $air SYSTEM_ALERT_WINDOW allow")) return
        shell(ctx, "dumpsys deviceidle whitelist +$air")
        for ((k, v) in listOf("auto_start" to "true", "boot_auto_start" to "true", "run_in_background" to "true", "auto_fullscreen" to "true")) {
            shell(ctx, "am broadcast -a $air.SET_SETTING -n $air/.SettingsReceiver --es key $k --es value $v")
        }
        val running = run(ctx, "pidof $air")?.trim().orEmpty().isNotEmpty()
        if (!running) {
            // Opening the receiver once starts its background service; then come straight back home.
            shell(ctx, "am start -n $air/.MainActivity")
            Thread.sleep(2500)
            shell(ctx, "input keyevent 3")
        }
    }

    /**
     * Gives the AirPlay receiver a clean name ("AirPlay") in the iPhone/Mac Screen Mirroring list.
     * Done once; the receiver is restarted so the new name shows up right away.
     */
    fun nameAirPlay(ctx: Context, name: String = "AirPlay") {
        val prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)
        if (prefs.getString("airplay_name", null) == name) return
        if (!Apps.isInstalled(ctx, Apps.AIRPLAY)) return
        val air = Apps.AIRPLAY
        val ok = shell(ctx, "am broadcast -a $air.SET_SETTING -n $air/.SettingsReceiver --es key server_name --es value '$name'")
        if (!ok) return
        shell(ctx, "am force-stop $air")
        shell(ctx, "am start -n $air/.MainActivity")
        Thread.sleep(2500)
        shell(ctx, "input keyevent 3")
        prefs.edit().putString("airplay_name", name).apply()
    }
}
