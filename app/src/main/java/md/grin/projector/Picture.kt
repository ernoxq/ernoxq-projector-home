package md.grin.projector

import android.animation.ValueAnimator
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
    /** Brightness and Night Shift share ONE full-screen layer: half the work for the weak GPU on every frame. */
    private var layerView: View? = null
    // What the layer shows now: opacity and colour already multiplied by it (0..255).
    private var curA = 0f
    private var curR = 0f
    private var curG = 0f
    private var curB = 0f
    private var anim: ValueAnimator? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun brightness(ctx: Context) = prefs(ctx).getInt("brightness", 100)
    fun isWarm(ctx: Context) = prefs(ctx).getBoolean("warm", false)
    fun warmth(ctx: Context) = prefs(ctx).getInt("warmth", 50)

    // ---------- Brightness ----------

    fun setBrightness(ctx: Context, percent: Int): Boolean {
        if (!Settings.canDrawOverlays(ctx)) return false
        val p = percent.coerceIn(5, 100)
        prefs(ctx).edit().putInt("brightness", p).apply()
        apply(ctx, 250)
        return true
    }

    // ---------- Night Shift ----------

    fun setWarm(ctx: Context, on: Boolean): Boolean {
        if (!Settings.canDrawOverlays(ctx)) return false
        prefs(ctx).edit().putBoolean("warm", on).apply()
        apply(ctx, 900)
        return true
    }

    fun setWarmth(ctx: Context, percent: Int): Boolean {
        prefs(ctx).edit().putInt("warmth", percent.coerceIn(0, 100)).apply()
        if (isWarm(ctx)) apply(ctx, 200)
        return true
    }

    // ---------- shared ----------

    /**
     * The same picture as a black dimming layer with a peach layer over it, folded into one layer:
     * opacity 1-(1-dim)(1-warm), colour = peach × warm / opacity. Exactly equal on screen.
     */
    private fun target(ctx: Context): FloatArray {
        // Up to 85% darker at the lowest setting.
        val dim = (100 - brightness(ctx)) / 95f * 0.85f
        val t = warmth(ctx) / 100f
        // From a light peach to a deeper amber, like moving the iPhone's "Less warm — More warm" slider.
        val warmColor = blend(0xFFFFC898.toInt(), 0xFFFF9A52.toInt(), t)
        val warm = if (isWarm(ctx)) 0.08f + 0.14f * t else 0f
        val a = 1f - (1f - dim) * (1f - warm)
        return floatArrayOf(
            a,
            Color.red(warmColor) * warm,
            Color.green(warmColor) * warm,
            Color.blue(warmColor) * warm,
        )
    }

    private fun apply(ctx: Context, ms: Long) {
        val app = ctx.applicationContext
        val to = target(app)
        main.post {
            anim?.cancel()
            if (to[0] > 0.001f && layerView == null) layerView = attach(app) ?: return@post
            val from = floatArrayOf(curA, curR, curG, curB)
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ms
                addUpdateListener {
                    val f = it.animatedFraction
                    curA = from[0] + (to[0] - from[0]) * f
                    curR = from[1] + (to[1] - from[1]) * f
                    curG = from[2] + (to[2] - from[2]) * f
                    curB = from[3] + (to[3] - from[3]) * f
                    paint()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        if (anim === a && curA <= 0.001f) detach(app)
                    }
                })
                start()
            }
        }
    }

    /** Colour with its opacity built in, on a plain view: no extra offscreen pass for a see-through view. */
    private fun paint() {
        val v = layerView ?: return
        val a = curA.coerceIn(0f, 1f)
        if (a <= 0.001f) { v.setBackgroundColor(Color.TRANSPARENT); return }
        fun ch(p: Float) = (p / a).toInt().coerceIn(0, 255)
        v.setBackgroundColor(Color.argb((a * 255).toInt(), ch(curR), ch(curG), ch(curB)))
    }

    private fun attach(ctx: Context): View? {
        val wm = ctx.getSystemService(WindowManager::class.java)
        val v = View(ctx)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        return try { wm.addView(v, lp); v } catch (_: Exception) { null }
    }

    private fun detach(ctx: Context) {
        val v = layerView ?: return
        layerView = null
        try { ctx.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Exception) {}
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t).toInt()
        return Color.rgb(ch(16), ch(8), ch(0))
    }

    /** After a restart, bring back the brightness and Night Shift that were set. */
    fun restore(ctx: Context) {
        if (brightness(ctx) < 100 || isWarm(ctx)) apply(ctx, 900)
    }
}
