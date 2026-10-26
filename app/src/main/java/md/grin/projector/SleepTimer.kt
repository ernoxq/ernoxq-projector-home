package md.grin.projector

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Bedtime switch-off: from 23:00 until 06:00, if nobody touches the projector remote or the phone
 * remote for 30 minutes, the projector turns itself off. A minute before, a notice appears on screen;
 * any button press cancels it.
 */
object SleepTimer {
    private const val IDLE_MS = 30 * 60_000L
    private const val WARN_MS = 60_000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastActivity = SystemClock.elapsedRealtime()
    /** When the projector will switch off (elapsedRealtime), or 0 if nothing is scheduled. */
    @Volatile private var offAt = 0L
    private var notice: TextView? = null
    private var appCtx: Context? = null

    fun isOn(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("sleep", true)

    fun setOn(ctx: Context, on: Boolean): Boolean {
        ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putBoolean("sleep", on).apply()
        touch()
        return true
    }

    /** Someone used a remote: restart the 30-minute countdown and cancel a pending switch-off. */
    fun touch() {
        lastActivity = SystemClock.elapsedRealtime()
        if (offAt != 0L) {
            offAt = 0L
            main.post { hideNotice() }
        }
    }

    /** Try it out: switch off in [seconds] with the same on-screen countdown. */
    fun test(ctx: Context, seconds: Int = 40): Boolean {
        appCtx = ctx.applicationContext
        main.post {
            offAt = SystemClock.elapsedRealtime() + seconds * 1000L
            showNotice(ctx.applicationContext)
            main.removeCallbacks(check)
            main.post(check)
        }
        return true
    }

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        main.removeCallbacks(check)
        main.postDelayed(check, 30_000)
    }

    private fun lateNight(): Boolean {
        val h = java.util.Calendar.getInstance(Clock.zone).apply { time = Clock.now() }.get(java.util.Calendar.HOUR_OF_DAY)
        return h >= 23 || h < 6
    }

    private val check = object : Runnable {
        override fun run() {
            val ctx = appCtx ?: return
            val now = SystemClock.elapsedRealtime()
            if (offAt != 0L) {
                val left = ((offAt - now + 999) / 1000).toInt()
                if (left <= 0) {
                    offAt = 0L
                    hideNotice()
                    lastActivity = now
                    Thread { Adb.powerOff(ctx) }.start()
                } else {
                    notice?.text = message(left)
                }
            } else if (isOn(ctx) && lateNight() && now - lastActivity >= IDLE_MS - WARN_MS) {
                offAt = now + WARN_MS
                showNotice(ctx)
            }
            main.postDelayed(this, if (offAt != 0L) 1_000 else 30_000)
        }
    }

    private fun message(seconds: Int) = "Проектор выключится через $seconds с — нажми любую кнопку, чтобы продолжить"

    private fun showNotice(ctx: Context) {
        if (notice != null || !Settings.canDrawOverlays(ctx)) return
        Ui.init(ctx)
        val v = TextView(ctx).apply {
            text = message(((offAt - SystemClock.elapsedRealtime() + 999) / 1000).toInt().coerceAtLeast(1))
            setTextColor(0xFFF5F5F7.toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.pxf(22))
            typeface = Ui.font(ctx, 600)
            gravity = Gravity.CENTER
            setPadding(Ui.px(32), Ui.px(20), Ui.px(32), Ui.px(20))
            background = Ui.round(0xEB1E1E21.toInt(), 30)
            alpha = 0f
            translationY = -Ui.pxf(40)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = Ui.px(40)
        }
        try {
            ctx.getSystemService(WindowManager::class.java).addView(v, lp)
            notice = v
            v.animate().alpha(1f).translationY(0f).setDuration(500)
                .setInterpolator(android.view.animation.PathInterpolator(0.16f, 1.1f, 0.3f, 1f)).start()
        } catch (_: Exception) {}
    }

    private fun hideNotice() {
        val v: View = notice ?: return
        notice = null
        val ctx = appCtx ?: return
        v.animate().alpha(0f).translationY(-Ui.pxf(40)).setDuration(400).withEndAction {
            try { ctx.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Exception) {}
        }.start()
    }
}
