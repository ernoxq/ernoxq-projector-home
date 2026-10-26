package md.grin.projector

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * iOS-style volume slider: a wide rounded bar that grows out of the left edge of the screen on a
 * soft dark "bulge", fills smoothly with the level, and melts back into the edge a moment later.
 * Replaces the stock volume panel.
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
            v.setLevel(if (muted) 0f else percent / 100f)
            if (!shown) slide(true) else bump(v)
            main.removeCallbacks(hide)
            main.postDelayed(hide, 2600)
        }
    }

    private fun attach(ctx: Context): HudView? {
        if (!available(ctx)) return null
        Ui.init(ctx)
        val v = HudView(ctx)
        val lp = WindowManager.LayoutParams(
            Ui.px(120), Ui.px(420),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = 0
        }
        return try {
            ctx.getSystemService(WindowManager::class.java).addView(v, lp)
            v.pivotX = 0f
            v.pivotY = Ui.pxf(210)
            v.translationX = -Ui.pxf(120)
            v.alpha = 0f
            view = v
            v
        } catch (e: Exception) {
            null
        }
    }

    // Apple-like motion: a long, soft spring on the way in, an unhurried ease on the way out.
    private val springIn = android.view.animation.PathInterpolator(0.16f, 1.12f, 0.3f, 1f)
    private val easeOut = android.view.animation.PathInterpolator(0.55f, 0f, 0.75f, 0.2f)
    private val easeBump = android.view.animation.PathInterpolator(0.3f, 0f, 0.2f, 1f)

    private fun slide(inside: Boolean) {
        val v = view ?: return
        shown = inside
        v.animate().cancel()
        if (inside) {
            v.scaleY = 0.78f
            v.scaleX = 0.9f
            v.animate().translationX(0f).alpha(1f).scaleY(1f).scaleX(1f)
                .setDuration(620).setInterpolator(springIn).withLayer().start()
        } else {
            v.animate().translationX(-Ui.pxf(120)).alpha(0f).scaleY(0.82f).scaleX(0.92f)
                .setDuration(520).setInterpolator(easeOut).withLayer().start()
        }
    }

    /** A tiny breath when the level changes while it's already on screen. */
    private fun bump(v: View) {
        v.animate().cancel()
        v.animate().translationX(0f).alpha(1f).scaleX(1.04f).scaleY(1.02f).setDuration(120).setInterpolator(easeBump)
            .withEndAction { v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(easeBump).start() }
            .start()
    }

    private class HudView(ctx: Context) : View(ctx) {
        private val bulge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2141416.toInt() }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3A3C.toInt() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5F5F7.toInt() }
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = Ui.pxf(1.5f)
            color = 0x26FFFFFF
        }
        private var level = 0f
        private var anim: ValueAnimator? = null
        private val bar = RectF()
        private val path = Path()
        private val clip = Path()

        fun setLevel(target: Float) {
            anim?.cancel()
            anim = ValueAnimator.ofFloat(level, target.coerceIn(0f, 1f)).apply {
                duration = 340
                interpolator = android.view.animation.PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
                addUpdateListener { level = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            // The dark shape that seems to swell out of the screen edge: flat on the left,
            // round on the right, with soft concave shoulders at the top and bottom.
            val bw = Ui.pxf(84)
            val sh = Ui.pxf(46)
            path.reset()
            path.moveTo(0f, 0f)
            path.cubicTo(0f, sh * 0.9f, bw, sh * 0.4f, bw, sh * 1.6f)
            path.lineTo(bw, h - sh * 1.6f)
            path.cubicTo(bw, h - sh * 0.4f, 0f, h - sh * 0.9f, 0f, h)
            path.close()
            canvas.drawPath(path, bulge)

            // The wide slider bar inside it.
            val barW = Ui.pxf(40)
            val left = (bw - barW) / 2 + Ui.pxf(4)
            bar.set(left, sh * 1.9f, left + barW, h - sh * 1.9f)
            val r = barW / 2
            clip.reset()
            clip.addRoundRect(bar, r, r, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawRect(bar, track)
            val top = bar.bottom - bar.height() * level
            canvas.drawRect(bar.left, top, bar.right, bar.bottom, fill)
            canvas.restore()
            canvas.drawRoundRect(bar, r, r, rim)
        }
    }
}
