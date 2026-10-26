package md.grin.projector

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager

/**
 * Picture modes.
 *  - Night Shift: a soft amber layer over the whole screen (like a screen filter), so colours look warm
 *    and there's less blue in the evening. Works on any firmware.
 *  - Bright mode: maximum lamp brightness, plus a colour/contrast boost through Android's display
 *    compositor where the firmware allows it.
 * Both are remembered and come back after a restart.
 */
object Picture {
    private val main = Handler(Looper.getMainLooper())
    private var warmView: View? = null
    private const val WARM = 0x3DFF8A1E  // amber at ~24%

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun isOn(ctx: Context) = prefs(ctx).getBoolean("bright", false)
    fun isWarm(ctx: Context) = prefs(ctx).getBoolean("warm", false)

    // ---------- Night Shift ----------

    fun setWarm(ctx: Context, on: Boolean): Boolean {
        if (!Settings.canDrawOverlays(ctx)) return false
        prefs(ctx).edit().putBoolean("warm", on).apply()
        main.post { if (on) showWarm(ctx.applicationContext) else hideWarm(ctx.applicationContext) }
        return true
    }

    private fun showWarm(ctx: Context) {
        val v = warmView ?: View(ctx).apply { setBackgroundColor(WARM); alpha = 0f }.also { v ->
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            try {
                ctx.getSystemService(WindowManager::class.java).addView(v, lp)
                warmView = v
            } catch (_: Exception) {
                return
            }
        }
        v.animate().alpha(1f).setDuration(900).start()
    }

    private fun hideWarm(ctx: Context) {
        val v = warmView ?: return
        v.animate().alpha(0f).setDuration(600).withEndAction {
            try { ctx.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Exception) {}
            if (warmView === v) warmView = null
        }.start()
    }

    // ---------- Bright mode ----------

    fun set(ctx: Context, on: Boolean): Boolean {
        val ok = if (on) {
            Adb.shell(ctx, "settings put system screen_brightness_mode 0")
            Adb.shell(ctx, "settings put system screen_brightness 255")
            // Colour boost through the compositor; quietly ignored by firmwares that don't support it.
            Adb.shell(ctx, "service call SurfaceFlinger 1022 f 1.2")
            Adb.shell(ctx, "service call SurfaceFlinger 1015 i32 1 f 1.08 f 0 f 0 f 0 f 0 f 1.08 f 0 f 0 f 0 f 0 f 1.08 f 0 f 0.015 f 0.015 f 0.015 f 1")
        } else {
            Adb.shell(ctx, "service call SurfaceFlinger 1022 f 1.0")
            Adb.shell(ctx, "service call SurfaceFlinger 1015 i32 0")
        }
        if (ok) prefs(ctx).edit().putBoolean("bright", on).apply()
        return ok
    }

    /** After a restart, bring back whatever was on. */
    fun restore(ctx: Context) {
        if (isWarm(ctx)) setWarm(ctx, true)
        if (isOn(ctx)) set(ctx, true)
    }
}
