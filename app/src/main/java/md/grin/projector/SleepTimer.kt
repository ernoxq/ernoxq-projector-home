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
    @Volatile private var warnedAt = 0L
    private var notice: View? = null
    private var appCtx: Context? = null

    fun isOn(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("sleep", true)

    fun setOn(ctx: Context, on: Boolean): Boolean {
        ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putBoolean("sleep", on).apply()
        touch()
        return true
    }

    /** Someone used a remote: restart the 30-minute countdown and cancel any warning. */
    fun touch() {
        lastActivity = SystemClock.elapsedRealtime()
        if (warnedAt != 0L) {
            warnedAt = 0L
            main.post { hideNotice() }
        }
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
            if (!isOn(ctx) || !lateNight()) {
                touch()
            } else if (warnedAt == 0L && now - lastActivity >= IDLE_MS - WARN_MS) {
                warnedAt = now
                showNotice(ctx)
            } else if (warnedAt != 0L && now - warnedAt >= WARN_MS) {
                warnedAt = 0L
                hideNotice()
                lastActivity = now
                Thread { Adb.key(ctx, android.view.KeyEvent.KEYCODE_POWER) }.start()
            }
            main.postDelayed(this, if (warnedAt != 0L) 5_000 else 30_000)
        }
    }

    private fun showNotice(ctx: Context) {
        if (notice != null || !Settings.canDrawOverlays(ctx)) return
        Ui.init(ctx)
        val v = TextView(ctx).apply {
            text = "Проектор выключится через минуту — нажми любую кнопку, чтобы продолжить"
            setTextColor(0xFFF5F5F7.toInt())
            textSize = 0f
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
        val v = notice ?: return
        notice = null
        val ctx = appCtx ?: return
        v.animate().alpha(0f).translationY(-Ui.pxf(40)).setDuration(400).withEndAction {
            try { ctx.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Exception) {}
        }.start()
    }
}
