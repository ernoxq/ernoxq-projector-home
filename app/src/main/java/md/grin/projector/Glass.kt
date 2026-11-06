package md.grin.projector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * The visionOS-style "liquid glass" look: see-through light panels over a softly lit, out-of-focus room.
 * Real background blur is too heavy for the projector's GPU, so glass is a translucent fill with a fine bright rim;
 * on the dark room it reads the same and costs nothing per frame.
 */
object Glass {
    const val WHITE = Color.WHITE
    const val TEXT2 = 0xB3FFFFFF.toInt()   // secondary text
    const val TEXT3 = 0x8CFFFFFF.toInt()   // captions
    private val EASE = DecelerateInterpolator(1.6f)

    /** A floating glass window or ornament. */
    fun panel(radius: Number): GradientDrawable = GradientDrawable().apply {
        setColor(0x24FFFFFF)
        cornerRadius = Ui.pxf(radius)
        setStroke(maxOf(1, Ui.px(1)), 0x30FFFFFF)
    }

    /** A card inside a glass window. */
    fun inner(radius: Number, fill: Int = 0x14FFFFFF, stroke: Int = 0x1AFFFFFF): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = Ui.pxf(radius)
        if (stroke != 0) setStroke(maxOf(1, Ui.px(1)), stroke)
    }

    /**
     * Reachable with the remote: when chosen, the card lights up, gets a bright rim and lifts a little,
     * like on Apple TV. One permanent GPU layer per card keeps the lift cheap.
     */
    fun focusable(v: View, radius: Number, fill: Int = 0x14FFFFFF, stroke: Int = 0x1AFFFFFF, lift: Float = 1.06f) {
        v.isFocusable = true
        v.isClickable = true
        v.isSoundEffectsEnabled = false
        val normal = inner(radius, fill, stroke)
        val focused = GradientDrawable().apply {
            setColor(0x47FFFFFF)
            cornerRadius = Ui.pxf(radius)
            setStroke(maxOf(2, Ui.px(2)), 0xA6FFFFFF.toInt())
        }
        v.background = normal
        v.setOnFocusChangeListener { view, has ->
            view.background = if (has) focused else normal
            if (has) Sounds.tick()
            view.animate().cancel()
            // A GPU layer only for the 140 ms of the lift, not kept for every card all the time.
            view.animate().scaleX(if (has) lift else 1f).scaleY(if (has) lift else 1f)
                .setDuration(140).setInterpolator(EASE).withLayer().start()
        }
    }

    /** A list row inside a settings group: it only lights up, it doesn't move. */
    fun row(v: View) {
        v.isFocusable = true
        v.isClickable = true
        v.isSoundEffectsEnabled = false
        v.background = null
        v.setOnFocusChangeListener { view, has ->
            view.setBackgroundColor(if (has) 0x33FFFFFF else Color.TRANSPARENT)
            if (has) Sounds.tick()
        }
    }

    fun circle(color: Int): GradientDrawable = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    fun gradient(radius: Number, vararg colors: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = Ui.pxf(radius) }
}

/**
 * The room behind the glass: warm walls, a lit window on the right, a lamp glow on the left, a sofa and a table,
 * all out of focus. Painted once into a half-size bitmap (it's blurry anyway), then just copied to the screen.
 */
class RoomDrawable : Drawable() {
    private var cache: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()

    override fun onBoundsChange(bounds: Rect) { cache = null }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val bmp = cache ?: render(maxOf(1, b.width() / 2), maxOf(1, b.height() / 2)).also { cache = it }
        dst.set(b)
        canvas.drawBitmap(bmp, null, dst, paint)
    }

    private fun render(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        val fw = w.toFloat()
        val fh = h.toFloat()
        p.shader = LinearGradient(0f, 0f, 0f, fh,
            intArrayOf(0xFF3A342E.toInt(), 0xFF2E2924.toInt(), 0xFF26211D.toInt(), 0xFF1D1916.toInt()),
            floatArrayOf(0f, 0.6f, 0.64f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, fw, fh, p)
        fun glow(cx: Float, cy: Float, r: Float, color: Int, squash: Float = 1f) {
            val g = RadialGradient(0f, 0f, r, intArrayOf(color, color and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            val m = Matrix()
            m.setScale(1f, squash)
            m.postTranslate(cx, cy)
            g.setLocalMatrix(m)
            p.shader = g
            c.drawRect(0f, 0f, fw, fh, p)
        }
        // Window light on the right, lamp on the left, sofa and table in the middle.
        glow(fw * 0.81f, fh * 0.33f, fw * 0.25f, 0x5CFFECD2, 1.25f)
        glow(fw * 0.14f, fh * 0.29f, fw * 0.17f, 0x2EFFD6AA, 1.2f)
        glow(fw * 0.5f, fh * 0.76f, fw * 0.4f, 0x994A4540.toInt(), 0.22f)
        glow(fw * 0.5f, fh * 0.94f, fw * 0.22f, 0x995B4A3B.toInt(), 0.16f)
        return bmp
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/** The "Приложения" icon: a glass tile holding four small coloured app squares, like an app folder on iPhone. */
class AppsGlyph(ctx: Context) : View(ctx) {
    private val tile = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sq = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val colors = listOf(
        0xFF5AC8FA.toInt() to 0xFF0A84FF.toInt(),
        0xFFFFB340.toInt() to 0xFFFF7A1A.toInt(),
        0xFF5EE07A.toInt() to 0xFF22B14C.toInt(),
        0xFFFF7AB6.toInt() to 0xFFE6336E.toInt(),
    )

    override fun onDraw(c: Canvas) {
        val s = width.toFloat()
        val rad = s * 0.28f
        r.set(0f, 0f, s, s)
        tile.color = 0x38FFFFFF
        c.drawRoundRect(r, rad, rad, tile)
        rim.strokeWidth = maxOf(1f, s / 58f)
        rim.color = 0x5AFFFFFF
        r.inset(rim.strokeWidth / 2, rim.strokeWidth / 2)
        c.drawRoundRect(r, rad, rad, rim)
        val pad = s * 0.155f
        val gap = s * 0.086f
        val cell = (s - pad * 2 - gap) / 2
        for (i in 0 until 4) {
            val x = pad + (i % 2) * (cell + gap)
            val y = pad + (i / 2) * (cell + gap)
            sq.shader = LinearGradient(x, y, x + cell * 0.4f, y + cell, colors[i].first, colors[i].second, Shader.TileMode.CLAMP)
            r.set(x, y, x + cell, y + cell)
            c.drawRoundRect(r, cell * 0.3f, cell * 0.3f, sq)
        }
    }
}

/** An iOS-style switch. */
class GlassSwitch(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var pos = 0f
    private var anim: android.animation.ValueAnimator? = null
    var on = false
        private set

    fun set(v: Boolean, animated: Boolean = true) {
        on = v
        anim?.cancel()
        val to = if (v) 1f else 0f
        if (!animated) { pos = to; invalidate(); return }
        anim = android.animation.ValueAnimator.ofFloat(pos, to).apply {
            duration = 180
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        r.set(0f, 0f, w, h)
        p.color = blend(0x33FFFFFF, 0xFF34C759.toInt(), pos)
        c.drawRoundRect(r, h / 2, h / 2, p)
        val k = h - Ui.pxf(4)
        val x = Ui.pxf(2) + (w - k - Ui.pxf(4)) * pos
        p.color = Color.WHITE
        c.drawCircle(x + k / 2, h / 2, k / 2, p)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a ushr s) and 0xFF) + ((((b ushr s) and 0xFF) - ((a ushr s) and 0xFF)) * t)).toInt()
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/**
 * A wide slider for the remote: Left and Right move it by 5%, hold to glide. The value is sent a moment
 * after the last press, so the projector isn't flooded while the key is held.
 */
class GlassSlider(ctx: Context, initial: Int, private val min: Int = 0, private val onChange: (Int) -> Unit) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    var value = initial.coerceIn(min, 100)
        private set
    private val send = Runnable { onChange(value) }

    init {
        isFocusable = true
        isSoundEffectsEnabled = false
        setOnFocusChangeListener { _, has -> if (has) Sounds.tick(); invalidate() }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val d = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -5
            KeyEvent.KEYCODE_DPAD_RIGHT -> 5
            else -> return super.onKeyDown(keyCode, event)
        }
        val n = (value + d).coerceIn(min, 100)
        if (n != value) {
            value = n
            Sounds.tick()
            invalidate()
            removeCallbacks(send)
            postDelayed(send, 120)
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val th = Ui.pxf(if (isFocused) 12 else 8)
        val top = (h - th) / 2
        r.set(0f, top, w, top + th)
        p.color = 0x2EFFFFFF
        c.drawRoundRect(r, th / 2, th / 2, p)
        val x = w * value / 100f
        r.set(0f, top, x, top + th)
        p.color = Color.WHITE
        c.drawRoundRect(r, th / 2, th / 2, p)
        val k = Ui.pxf(if (isFocused) 15 else 12)
        p.color = 0x55000000
        c.drawCircle(x.coerceIn(k, w - k), h / 2 + Ui.pxf(1.5f), k, p)
        p.color = Color.WHITE
        c.drawCircle(x.coerceIn(k, w - k), h / 2, k, p)
    }
}
