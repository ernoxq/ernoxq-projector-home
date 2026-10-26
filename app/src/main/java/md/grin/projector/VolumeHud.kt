package md.grin.projector

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator

/**
 * iPhone-style volume indicator: a tall glass capsule that slides in from the right edge,
 * fills smoothly with the level, and slides away a moment later. Replaces the stock Android panel.
 */
object VolumeHud {
    private val main = Handler(Looper.getMainLooper())
    private var view: HudView? = null
    private var shown = false
    private val hide = Runnable { slide(false) }

    fun available(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun show(ctx: Context, percent: Int, muted: Boolean) {
        main.post {
            val v = view ?: attach(ctx.applicationContext) ?: return@post
            v.setLevel(if (muted) 0f else percent / 100f, muted)
            if (!shown) slide(true)
            main.removeCallbacks(hide)
            main.postDelayed(hide, 1600)
        }
    }

    private fun attach(ctx: Context): HudView? {
        if (!available(ctx)) return null
        Ui.init(ctx)
        val v = HudView(ctx)
        val lp = WindowManager.LayoutParams(
            Ui.px(64) + Ui.px(40), Ui.px(250),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        return try {
            ctx.getSystemService(WindowManager::class.java).addView(v, lp)
            v.translationX = Ui.pxf(140)
            view = v
            v
        } catch (e: Exception) {
            null
        }
    }

    private fun slide(inside: Boolean) {
        val v = view ?: return
        shown = inside
        v.animate().cancel()
        v.animate()
            .translationX(if (inside) 0f else Ui.pxf(140))
            .alpha(if (inside) 1f else 0f)
            .setDuration(if (inside) 320 else 260)
            .setInterpolator(if (inside) PathInterpolator(0.2f, 0.9f, 0.25f, 1f) else DecelerateInterpolator())
            .start()
    }

    private class HudView(ctx: Context) : View(ctx) {
        private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE62A2A2D.toInt() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5F5F7.toInt() }
        private val icon: Drawable? = ctx.getDrawable(R.drawable.ic_volume)
        private val iconMuted: Drawable? = ctx.getDrawable(R.drawable.ic_volume_off)
        private var level = 0f
        private var muted = false
        private var anim: ValueAnimator? = null
        private val r = RectF()

        fun setLevel(target: Float, isMuted: Boolean) {
            muted = isMuted
            anim?.cancel()
            anim = ValueAnimator.ofFloat(level, target.coerceIn(0f, 1f)).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                addUpdateListener { level = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = Ui.pxf(64)
            val left = width - w - Ui.pxf(40)
            r.set(left, 0f, left + w, height.toFloat())
            val rad = w / 2
            canvas.save()
            val clip = android.graphics.Path().apply { addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW) }
            canvas.clipPath(clip)
            canvas.drawRect(r, glass)
            val top = r.bottom - r.height() * level
            canvas.drawRect(r.left, top, r.right, r.bottom, fill)
            canvas.restore()
            // Speaker icon near the bottom: dark on the white fill, light on the glass.
            val d = (if (muted || level == 0f) iconMuted else icon) ?: return
            val s = Ui.px(26)
            val cx = r.centerX().toInt()
            val cy = (r.bottom - Ui.pxf(34)).toInt()
            d.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
            d.setTint(if (top < cy) 0xFF1C1C1E.toInt() else 0xFFF5F5F7.toInt())
            d.draw(canvas)
        }
    }
}
