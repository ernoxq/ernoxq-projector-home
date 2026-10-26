package md.grin.projector

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Palette and sizing shared by every screen. Sizes are written in mockup pixels (1280×720) and scaled to the real screen. */
object Ui {
    const val GROUND = 0xFFC9D3EA.toInt()
    const val INK = 0xFF121212.toInt()
    const val WHITE = Color.WHITE
    const val SOFT = 0xFFEEF1F7.toInt()
    const val GLASS = 0x8CFFFFFF.toInt()
    const val MUTED = 0xFF555B66.toInt()
    const val LINE = 0xFFDDE2EC.toInt()
    const val ACCENT = 0xFFFBE38E.toInt()
    const val NIGHT = 0xFF2B3247.toInt()
    const val NIGHT2 = 0xFF3A4360.toInt()

    var scale = 1f
    private val FOCUS_EASE = DecelerateInterpolator(1.6f)

    fun init(ctx: Context) {
        val dm = ctx.resources.displayMetrics
        scale = maxOf(dm.widthPixels, dm.heightPixels) / 1280f
    }

    fun px(v: Number): Int = (v.toFloat() * scale).roundToInt()
    fun pxf(v: Number): Float = v.toFloat() * scale

    private val fontCache = HashMap<String, Typeface>()

    /** Inter Tight (variable font shipped in assets); falls back to the system sans if it is missing. */
    fun font(ctx: Context, weight: Int, italic: Boolean = false): Typeface {
        val key = "$weight-$italic"
        fontCache[key]?.let { return it }
        val tf = try {
            val file = if (italic) "fonts/InterTight-Italic.ttf" else "fonts/InterTight.ttf"
            Typeface.Builder(ctx.assets, file)
                .setFontVariationSettings("'wght' $weight")
                .build()
        } catch (e: Throwable) {
            null
        } ?: Typeface.create(Typeface.SANS_SERIF, if (italic) Typeface.BOLD_ITALIC else if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        fontCache[key] = tf
        return tf
    }

    fun round(color: Int, radius: Number, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = pxf(radius)
            if (stroke > 0) setStroke(px(stroke), strokeColor)
        }

    fun text(ctx: Context, s: CharSequence, size: Number, weight: Int = 500, color: Int = INK, italic: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, pxf(size))
            typeface = font(ctx, weight, italic)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

    fun icon(ctx: Context, res: Int, size: Number, tint: Int = INK): ImageView =
        ImageView(ctx).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(tint)
            layoutParams = LinearLayout.LayoutParams(px(size), px(size))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

    /** A round icon button like the ones in the mockup header. */
    fun circleButton(ctx: Context, res: Int, size: Number, bg: Int, tint: Int = INK, iconSize: Number = 22): FrameLayout =
        FrameLayout(ctx).apply {
            background = round(bg, size.toFloat() / 2)
            val ic = icon(ctx, res, iconSize, tint)
            addView(ic, FrameLayout.LayoutParams(px(iconSize), px(iconSize), Gravity.CENTER))
            focusable(this, bg, size.toFloat() / 2)
        }

    fun pill(ctx: Context, label: String, bg: Int, fg: Int, h: Number = 48, padH: Number = 24, size: Number = 17, weight: Int = 500): TextView =
        text(ctx, label, size, weight, fg).apply {
            gravity = Gravity.CENTER
            setPadding(px(padH), 0, px(padH), 0)
            layoutParams = LinearLayout.LayoutParams(WRAP, px(h))
            focusable(this, bg, h.toFloat() / 2)
        }

    /**
     * Makes a view reachable with the remote's D-pad. Focus gets a soft grow and a ring,
     * done with the view's own render properties so it stays cheap on the projector's weak GPU.
     */
    fun focusable(v: View, bg: Int, radius: Number, ring: Int = INK) {
        v.isFocusable = true
        v.isClickable = true
        // Our own tick replaces the stock system click.
        v.isSoundEffectsEnabled = false
        val normal = round(bg, radius)
        val focused = round(bg, radius, 4, ring)
        v.background = normal
        // A permanent GPU layer: the card is drawn once, and growing it on focus is just moving a texture.
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        v.setOnFocusChangeListener { view, has ->
            view.background = if (has) focused else normal
            if (has) Sounds.tick()
            view.animate().cancel()
            view.animate()
                .scaleX(if (has) 1.05f else 1f)
                .scaleY(if (has) 1.05f else 1f)
                .setDuration(120)
                .setInterpolator(FOCUS_EASE)
                .start()
        }
    }

    fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight)
    fun gap(ctx: Context, w: Number = 0, h: Number = 0): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(px(w), px(h)) }
    fun flex(ctx: Context): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) }

    fun row(ctx: Context, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout =
        LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; this.gravity = gravity }

    fun col(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
}
