package md.grin.projector

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator

/**
 * Apple TV–style volume indicator: a dark glass capsule that glides down in the top-right corner,
 * shows where the sound is playing (e.g. "Redmi Soundbar" over Bluetooth, or "Проектор"),
 * a thin level bar under the name, and glides back up a moment later.
 * Replaces the stock volume panel.
 */
object VolumeHud {
    private val main = Handler(Looper.getMainLooper())
    private var view: HudView? = null
    private var shown = false
    private val hide = Runnable { slide(false) }

    // Apple-like motion: a long, soft spring on the way in, an unhurried ease on the way out.
    private val springIn = PathInterpolator(0.16f, 1.12f, 0.3f, 1f)
    private val easeOut = PathInterpolator(0.55f, 0f, 0.75f, 0.2f)
    private val easeBump = PathInterpolator(0.3f, 0f, 0.2f, 1f)

    fun available(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun show(ctx: Context, percent: Int, muted: Boolean) {
        val name = outputName(ctx)
        main.post {
            val v = view ?: attach(ctx.applicationContext) ?: return@post
            v.update(name, if (muted) 0f else percent / 100f, muted)
            if (!shown) slide(true) else bump(v)
            main.removeCallbacks(hide)
            main.postDelayed(hide, 2600)
        }
    }

    /** Where the sound goes right now: a Bluetooth speaker's own name, an external output, or the projector. */
    private fun outputName(ctx: Context): String {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return "Проектор"
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        fun nameOf(d: AudioDeviceInfo, fallback: String) =
            d.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() && !it.equals(android.os.Build.MODEL, true) } ?: fallback
        outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }?.let { return nameOf(it, "Bluetooth") }
        val external = setOf(
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE,
        )
        outs.firstOrNull { it.type in external }?.let { return nameOf(it, "Внешний звук") }
        return "Проектор"
    }

    private fun attach(ctx: Context): HudView? {
        if (!available(ctx)) return null
        Ui.init(ctx)
        val v = HudView(ctx)
        val lp = WindowManager.LayoutParams(
            Ui.px(380), Ui.px(96),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = Ui.px(40)
            y = Ui.px(32)
        }
        return try {
            ctx.getSystemService(WindowManager::class.java).addView(v, lp)
            v.pivotX = Ui.pxf(380)
            v.pivotY = 0f
            v.translationY = -Ui.pxf(150)
            v.alpha = 0f
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
        if (inside) {
            v.scaleX = 0.9f
            v.scaleY = 0.9f
            v.animate().translationY(0f).alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(620).setInterpolator(springIn).withLayer().start()
        } else {
            v.animate().translationY(-Ui.pxf(150)).alpha(0f).scaleX(0.92f).scaleY(0.92f)
                .setDuration(520).setInterpolator(easeOut).withLayer().start()
        }
    }

    /** A tiny breath when the level changes while it's already on screen. */
    private fun bump(v: View) {
        v.animate().cancel()
        v.animate().translationY(0f).alpha(1f).scaleX(1.03f).scaleY(1.03f).setDuration(120).setInterpolator(easeBump)
            .withEndAction { v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(easeBump).start() }
            .start()
    }

    private class HudView(private val ctx: Context) : View(ctx) {
        private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEB1E1E21.toInt() }
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = Ui.pxf(1.2f)
            color = 0x1FFFFFFF
        }
        private val iconBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24FFFFFF }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5F5F7.toInt() }
        private val title = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF5F5F7.toInt()
            textSize = Ui.pxf(21)
            typeface = Ui.font(ctx, 600)
        }
        private val icon: Drawable? = ctx.getDrawable(R.drawable.ic_volume)
        private val iconMuted: Drawable? = ctx.getDrawable(R.drawable.ic_volume_off)

        private var name = "Проектор"
        private var level = 0f
        private var muted = false
        private var anim: ValueAnimator? = null
        private val box = RectF()
        private val bar = RectF()
        private val clip = Path()

        fun update(newName: String, target: Float, isMuted: Boolean) {
            name = newName
            muted = isMuted
            anim?.cancel()
            anim = ValueAnimator.ofFloat(level, target.coerceIn(0f, 1f)).apply {
                duration = 340
                interpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
                addUpdateListener { level = it.animatedValue as Float; invalidate() }
                start()
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val rad = h / 2
            box.set(0f, 0f, w, h)
            canvas.drawRoundRect(box, rad, rad, glass)
            canvas.drawRoundRect(box, rad, rad, rim)

            // Speaker in a soft round badge on the left.
            val pad = Ui.pxf(16)
            val badge = h - pad * 2
            val cx = pad + badge / 2
            val cy = h / 2
            canvas.drawCircle(cx, cy, badge / 2, iconBg)
            (if (muted || level == 0f) iconMuted else icon)?.let { d ->
                val s = Ui.px(30)
                d.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
                d.setTint(0xFFF5F5F7.toInt())
                d.draw(canvas)
            }

            // Device name, then the thin level bar under it.
            val left = pad + badge + Ui.pxf(16)
            val right = w - Ui.pxf(28)
            val label = TextUtils.ellipsize(name, title, right - left, TextUtils.TruncateAt.END).toString()
            canvas.drawText(label, left, cy - Ui.pxf(6), title)

            val bh = Ui.pxf(7)
            bar.set(left, cy + Ui.pxf(10), right, cy + Ui.pxf(10) + bh)
            clip.reset()
            clip.addRoundRect(bar, bh / 2, bh / 2, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawRect(bar, track)
            canvas.drawRect(bar.left, bar.top, bar.left + bar.width() * level, bar.bottom, fill)
            canvas.restore()
        }
    }
}
