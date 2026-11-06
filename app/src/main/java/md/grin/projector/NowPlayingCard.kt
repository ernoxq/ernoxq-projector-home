package md.grin.projector

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.sin

/**
 * The iOS-style "Now Playing" card for the screensaver corner: rounded cover, title, artist,
 * a thin progress bar with elapsed and remaining time, and the little dancing bars while music plays.
 */
class NowPlayingCard(ctx: Context) : LinearLayout(ctx) {
    private val art = ImageView(ctx)
    private val artBox = FrameLayout(ctx)
    private val placeholder = Ui.icon(ctx, R.drawable.ic_music, 28, 0xB3FFFFFF.toInt())
    private val title: TextView
    private val artist: TextView
    private val elapsed: TextView
    private val remaining: TextView
    private val bar = Progress(ctx)
    private val eq = Equalizer(ctx)
    private var lastArt: Bitmap? = null
    var shown = false
        private set

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(0x8C1C1C1E.toInt())
            cornerRadius = Ui.pxf(26)
            setStroke(Ui.px(1), 0x24FFFFFF)
        }
        setPadding(Ui.px(18), Ui.px(18), Ui.px(18), Ui.px(16))
        alpha = 0f
        visibility = View.GONE

        // Cover with rounded corners, like the iOS lock screen.
        artBox.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF4A4F5A.toInt(), 0xFF2A2D35.toInt())).apply {
            cornerRadius = Ui.pxf(12)
        }
        artBox.clipToOutline = true
        art.scaleType = ImageView.ScaleType.CENTER_CROP
        artBox.addView(placeholder, FrameLayout.LayoutParams(Ui.px(28), Ui.px(28), Gravity.CENTER))
        artBox.addView(art, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        title = Ui.text(ctx, "", 19, 600, Color.WHITE)
        artist = Ui.text(ctx, "", 16, 500, 0xB3FFFFFF.toInt())
        val texts = Ui.col(ctx).apply {
            addView(title)
            addView(artist, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.px(4) })
        }

        val top = Ui.row(ctx).apply {
            addView(artBox, Ui.lp(Ui.px(64), Ui.px(64)))
            addView(texts, Ui.lp(0, Ui.WRAP, 1f).apply { leftMargin = Ui.px(14); rightMargin = Ui.px(10) })
            addView(eq, Ui.lp(Ui.px(22), Ui.px(20)))
        }
        addView(top, Ui.lp(Ui.MATCH, Ui.WRAP))

        addView(bar, Ui.lp(Ui.MATCH, Ui.px(5)).apply { topMargin = Ui.px(16) })

        elapsed = Ui.text(ctx, "0:00", 13, 600, 0x99FFFFFF.toInt())
        remaining = Ui.text(ctx, "", 13, 600, 0x99FFFFFF.toInt())
        val times = Ui.row(ctx).apply {
            addView(elapsed)
            addView(Ui.flex(ctx))
            addView(remaining)
        }
        addView(times, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.px(6) })
    }

    /** Shows the card for what's playing, hides it when nothing is. */
    fun update(np: NowPlaying?) {
        if (np == null || !np.playing) { hide(); return }
        title.text = np.title
        artist.text = np.artist.ifEmpty { np.source }
        if (np.art !== lastArt) {
            lastArt = np.art
            art.setImageBitmap(np.art)
            art.visibility = if (np.art != null) View.VISIBLE else View.GONE
        }
        if (np.duration > 0) {
            val pos = np.position.coerceAtMost(np.duration)
            bar.visibility = View.VISIBLE
            bar.fraction = pos.toFloat() / np.duration
            elapsed.text = mmss(pos)
            remaining.text = "−" + mmss(np.duration - pos)
            (elapsed.parent as View).visibility = View.VISIBLE
        } else {
            // Radio and live streams have no length.
            bar.visibility = View.GONE
            (elapsed.parent as View).visibility = View.GONE
        }
        eq.running = true
        show()
    }

    private fun show() {
        if (shown) return
        shown = true
        visibility = View.VISIBLE
        translationY = -Ui.pxf(16)
        animate().cancel()
        animate().alpha(1f).translationY(0f).setDuration(600).start()
    }

    fun hide() {
        eq.running = false
        if (!shown) return
        shown = false
        animate().cancel()
        animate().alpha(0f).translationY(-Ui.pxf(16)).setDuration(500).withEndAction { if (!shown) visibility = View.GONE }.start()
    }

    private fun mmss(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** Thin rounded track with a white fill. */
    private class Progress(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        var fraction = 0f
            set(v) { field = v.coerceIn(0f, 1f); invalidate() }

        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            r.set(0f, 0f, width.toFloat(), h)
            paint.color = 0x40FFFFFF
            c.drawRoundRect(r, h / 2, h / 2, paint)
            r.right = width * fraction
            if (r.right > 0) {
                paint.color = Color.WHITE
                c.drawRoundRect(r, h / 2, h / 2, paint)
            }
        }
    }

    /** The four little bars that bounce while music plays. */
    private class Equalizer(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val r = RectF()
        private var t = 0f
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { t += 0.016f; invalidate() }
        }
        var running = false
            set(v) {
                if (field == v) return
                field = v
                if (v) anim.start() else { anim.cancel(); invalidate() }
            }

        override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

        override fun onDraw(c: Canvas) {
            val n = 4
            val gap = width * 0.12f
            val w = (width - gap * (n - 1)) / n
            val h = height.toFloat()
            for (i in 0 until n) {
                val level = if (running) 0.3f + 0.7f * ((sin(t * 6f * (1f + i * 0.37f) + i * 1.7f) + 1f) / 2f) else 0.25f
                val left = i * (w + gap)
                r.set(left, h - h * level, left + w, h)
                c.drawRoundRect(r, w / 2, w / 2, paint)
            }
        }
    }
}
