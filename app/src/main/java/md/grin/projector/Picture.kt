package md.grin.projector

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager

/**
 * Picture controls, drawn as layers over the whole screen so they work on any firmware.
 *  - Brightness: 100% is the lamp's full light; lower values gently darken the picture (down to a dim
 *    night level). Software can't push the lamp past its maximum, so 100% is the top.
 *  - Night Shift, iPhone-style: a soft peach warmth with an adjustable strength, not a yellow wash.
 * Settings are remembered and come back after a restart.
 */
object Picture {
    private val main = Handler(Looper.getMainLooper())
    private var dimView: View? = null
    private var warmView: View? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun brightness(ctx: Context) = prefs(ctx).getInt("brightness", 100)
    fun isWarm(ctx: Context) = prefs(ctx).getBoolean("warm", false)
    fun warmth(ctx: Context) = prefs(ctx).getInt("warmth", 50)

    // ---------- Brightness ----------

    fun setBrightness(ctx: Context, percent: Int): Boolean {
        if (!Settings.canDrawOverlays(ctx)) return false
        val p = percent.coerceIn(5, 100)
        prefs(ctx).edit().putInt("brightness", p).apply()
        // Up to 85% darker at the lowest setting.
        val alpha = (100 - p) / 95f * 0.85f
        main.post { dimView = layer(ctx.applicationContext, dimView, Color.BLACK, alpha, 250) }
        return true
    }

    // ---------- Night Shift ----------

    fun setWarm(ctx: Context, on: Boolean): Boolean {
        if (!Settings.canDrawOverlays(ctx)) return false
        prefs(ctx).edit().putBoolean("warm", on).apply()
        applyWarm(ctx, 900)
        return true
    }

    fun setWarmth(ctx: Context, percent: Int): Boolean {
        prefs(ctx).edit().putInt("warmth", percent.coerceIn(0, 100)).apply()
        if (isWarm(ctx)) applyWarm(ctx, 200)
        return true
    }

    private fun applyWarm(ctx: Context, ms: Long) {
        val on = isWarm(ctx)
        val t = warmth(ctx) / 100f
        // From a light peach to a deeper amber, like moving the iPhone's "Less warm — More warm" slider.
        val color = blend(0xFFFFC898.toInt(), 0xFFFF9A52.toInt(), t)
        val alpha = if (on) 0.08f + 0.14f * t else 0f
        main.post { warmView = layer(ctx.applicationContext, warmView, color, alpha, ms) }
    }

    // ---------- shared ----------

    /** One full-screen, see-through, untouchable layer. Removed when fully transparent. */
    private fun layer(ctx: Context, existing: View?, color: Int, alpha: Float, ms: Long): View? {
        val wm = ctx.getSystemService(WindowManager::class.java)
        if (alpha <= 0.001f) {
            existing?.animate()?.alpha(0f)?.setDuration(ms)?.withEndAction {
                try { wm.removeView(existing) } catch (_: Exception) {}
            }?.start()
            return null
        }
        val v = existing ?: View(ctx).also { nv ->
            nv.alpha = 0f
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            try { wm.addView(nv, lp) } catch (_: Exception) { return existing }
        }
        v.setBackgroundColor(color)
        v.animate().cancel()
        v.animate().alpha(alpha).setDuration(ms).start()
        return v
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t).toInt()
        return Color.rgb(ch(16), ch(8), ch(0))
    }

    /** After a restart, bring back the brightness and Night Shift that were set. */
    fun restore(ctx: Context) {
        if (brightness(ctx) < 100) setBrightness(ctx, brightness(ctx))
        if (isWarm(ctx)) applyWarm(ctx, 900)
    }
}
