package md.grin.projector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
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
    private val placeholder = Ui.icon(ctx, R.drawable.ic_music, 20, 0xB3FFFFFF.toInt())
    private val title: TextView
    private val artist: TextView
    private val elapsed: TextView
    private val remaining: TextView
    private val bar = RoundBar(ctx, 0x40FFFFFF, Color.WHITE)
    private val eq = Equalizer(ctx)
    private var lastArt: Bitmap? = null
    var shown = false
        private set

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(0x8C1C1C1E.toInt())
            cornerRadius = Ui.pxf(18)
            setStroke(Ui.px(1), 0x24FFFFFF)
        }
        setPadding(Ui.px(12), Ui.px(12), Ui.px(12), Ui.px(10))
        alpha = 0f
        visibility = View.GONE

        // Cover with rounded corners, like the iOS lock screen.
        artBox.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF4A4F5A.toInt(), 0xFF2A2D35.toInt())).apply {
            cornerRadius = Ui.pxf(8)
        }
        artBox.clipToOutline = true
        art.scaleType = ImageView.ScaleType.CENTER_CROP
        artBox.addView(placeholder, FrameLayout.LayoutParams(Ui.px(20), Ui.px(20), Gravity.CENTER))
        artBox.addView(art, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        title = Ui.text(ctx, "", 14, 600, Color.WHITE)
        artist = Ui.text(ctx, "", 12, 500, 0xB3FFFFFF.toInt())
        val texts = Ui.col(ctx).apply {
            addView(title)
            addView(artist, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.px(2) })
        }

        val top = Ui.row(ctx).apply {
            addView(artBox, Ui.lp(Ui.px(42), Ui.px(42)))
            addView(texts, Ui.lp(0, Ui.WRAP, 1f).apply { leftMargin = Ui.px(10); rightMargin = Ui.px(8) })
            addView(eq, Ui.lp(Ui.px(15), Ui.px(13)))
        }
        addView(top, Ui.lp(Ui.MATCH, Ui.WRAP))

        addView(bar, Ui.lp(Ui.MATCH, Ui.px(3)).apply { topMargin = Ui.px(10) })

        elapsed = Ui.text(ctx, "0:00", 10, 600, 0x99FFFFFF.toInt())
        remaining = Ui.text(ctx, "", 10, 600, 0x99FFFFFF.toInt()).apply { gravity = Gravity.END }
        // Fixed widths: the times tick every second without re-measuring the card.
        val times = Ui.row(ctx).apply {
            addView(elapsed, Ui.lp(Ui.px(60), Ui.WRAP))
            addView(Ui.flex(ctx))
            addView(remaining, Ui.lp(Ui.px(60), Ui.WRAP))
        }
        addView(times, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.px(4) })
    }

    /** Shows the card for what's playing, hides it when nothing is. */
    fun update(np: NowPlaying?) {
        if (np == null || !np.playing) { hide(); return }
        // Text is only touched when it really changes: every change re-measures the card over the video.
        set(title, np.title)
        set(artist, np.artist.ifEmpty { np.source })
        if (np.art !== lastArt) {
            lastArt = np.art
            art.setImageBitmap(np.art)
            art.visibility = if (np.art != null) View.VISIBLE else View.GONE
        }
        if (np.duration > 0) {
            val pos = np.position.coerceAtMost(np.duration)
            bar.visibility = View.VISIBLE
            bar.fraction = pos.toFloat() / np.duration
            set(elapsed, mmss(pos))
            set(remaining, "−" + mmss(np.duration - pos))
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
        translationY = -Ui.pxf(10)
        animate().cancel()
        animate().alpha(1f).translationY(0f).setDuration(600).start()
    }

    fun hide() {
        eq.running = false
        if (!shown) return
        shown = false
        animate().cancel()
        animate().alpha(0f).translationY(-Ui.pxf(10)).setDuration(500).withEndAction { if (!shown) visibility = View.GONE }.start()
    }

    private fun set(v: TextView, s: String) { if (v.text.toString() != s) v.text = s }

    private fun mmss(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }


    /** The four little bars that bounce while music plays. */
    private class Equalizer(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val r = RectF()
        private var t = 0f
        // About 20 frames a second is plenty for four little bars, and costs a third of a full-rate animation.
        private val frame = object : Runnable {
            override fun run() {
                if (!running) return
                t += 0.1f
                invalidate()
                postDelayed(this, 100)
            }
        }
        var running = false
            set(v) {
                if (field == v) return
                field = v
                removeCallbacks(frame)
                if (v) post(frame) else invalidate()
            }

        override fun onDetachedFromWindow() { removeCallbacks(frame); super.onDetachedFromWindow() }

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

/**
 * A thin rounded progress bar drawn in one go. Moving it only repaints this strip;
 * resizing a child view instead would re-measure the whole screen every second.
 */
class RoundBar(ctx: Context, private val trackColor: Int, private val fillColor: Int) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    var fraction = 0f
        set(v) {
            val n = v.coerceIn(0f, 1f)
            if (kotlin.math.abs(n - field) * width < 0.5f && n != 0f) { field = n; return }
            field = n
            invalidate()
        }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        r.set(0f, 0f, width.toFloat(), h)
        paint.color = trackColor
        c.drawRoundRect(r, h / 2, h / 2, paint)
        r.right = width * fraction
        if (r.right > 0) {
            paint.color = fillColor
            c.drawRoundRect(r, h / 2, h / 2, paint)
        }
    }
}

/**
 * The screensaver while music plays, Apple TV style: the room, a large cover, the song in big letters and its
 * progress. No video at all, so nothing competes with the music for memory or sound.
 */
class MusicScreen(ctx: Context) : FrameLayout(ctx) {
    private val cover = ImageView(ctx)
    private val note = Ui.icon(ctx, R.drawable.ic_music, 96, 0xE6FFFFFF.toInt())
    private val source = Ui.text(ctx, "", 15, 600, Glass.TEXT3).apply { letterSpacing = 0.1f }
    private val title = Ui.text(ctx, "", 46, 700, Color.WHITE).apply { letterSpacing = -0.02f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val artist = Ui.text(ctx, "", 24, 500, Glass.TEXT2)
    private val bar = RoundBar(ctx, 0x38FFFFFF, Color.WHITE)
    private val elapsed = Ui.text(ctx, "", 15, 600, Glass.TEXT3)
    private val left = Ui.text(ctx, "", 15, 600, Glass.TEXT3).apply { gravity = Gravity.END }
    private var shownArt: Bitmap? = null

    init {
        background = RoomDrawable()
        visibility = View.GONE
        alpha = 0f
        val row = Ui.row(ctx)
        val art = FrameLayout(ctx).apply {
            background = Glass.gradient(30, 0xFF8E6CFF.toInt(), 0xFFFF6FB1.toInt())
            clipToOutline = true
        }
        art.addView(note, FrameLayout.LayoutParams(Ui.px(96), Ui.px(96), Gravity.CENTER))
        cover.scaleType = ImageView.ScaleType.CENTER_CROP
        art.addView(cover, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        row.addView(art, Ui.lp(Ui.px(360), Ui.px(360)))
        val col = Ui.col(ctx)
        col.addView(source)
        col.addView(title, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.px(12) })
        col.addView(artist, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.px(8) })
        col.addView(bar, Ui.lp(Ui.MATCH, Ui.px(6)).apply { topMargin = Ui.px(36) })
        val times = Ui.row(ctx)
        times.addView(elapsed, Ui.lp(Ui.px(90), Ui.WRAP))
        times.addView(Ui.flex(ctx))
        times.addView(left, Ui.lp(Ui.px(90), Ui.WRAP))
        col.addView(times, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.px(10) })
        row.addView(col, Ui.lp(Ui.px(560), Ui.WRAP).apply { leftMargin = Ui.px(56) })
        addView(row, LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER))
    }

    fun show() {
        visibility = View.VISIBLE
        animate().cancel()
        animate().alpha(1f).setDuration(900).withLayer().start()
    }

    fun hide() {
        animate().cancel()
        if (visibility != View.VISIBLE) return
        animate().alpha(0f).setDuration(600).withLayer().withEndAction { visibility = View.GONE }.start()
    }

    fun update(np: NowPlaying?) {
        if (visibility != View.VISIBLE) return
        set(source, if (np != null) "СЕЙЧАС ИГРАЕТ · ${np.source.uppercase()}" else "СЕЙЧАС ИГРАЕТ")
        set(title, np?.title ?: "")
        set(artist, np?.artist ?: "")
        val art = np?.art
        if (art !== shownArt) {
            shownArt = art
            cover.setImageBitmap(art)
            cover.visibility = if (art != null) View.VISIBLE else View.GONE
        }
        val dur = np?.duration ?: 0
        if (np != null && dur > 0) {
            val pos = np.position.coerceIn(0, dur)
            bar.fraction = pos.toFloat() / dur
            set(elapsed, mmss(pos))
            set(left, "−" + mmss(dur - pos))
        } else {
            bar.fraction = 0f; set(elapsed, ""); set(left, "")
        }
    }

    private fun set(v: TextView, s: String) { if (v.text.toString() != s) v.text = s }
    private fun mmss(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
}
