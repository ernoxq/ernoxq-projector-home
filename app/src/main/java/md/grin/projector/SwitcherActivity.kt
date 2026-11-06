package md.grin.projector

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import md.grin.projector.Ui.MATCH
import md.grin.projector.Ui.WRAP
import md.grin.projector.Ui.px

/**
 * Open apps, like the app switcher on iPhone and Apple TV: a row of glass cards, most recent first.
 * OK opens an app, Up closes it (the card flies up), "Закрыть все" at the end closes them all.
 * Closing frees the projector's small memory for what you're using.
 */
class SwitcherActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var row: LinearLayout
    private lateinit var empty: TextView
    private val open = ArrayList<AppEntry>()

    companion object {
        fun show(ctx: Context): Boolean = try {
            ctx.startActivity(Intent(ctx, SwitcherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            true
        } catch (_: Exception) { false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        Sounds.init(this)
        Ui.applyTheme(true)
        val root = FrameLayout(this).apply { background = RoomDrawable() }
        root.addView(View(this).apply { setBackgroundColor(0x66000000) }, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(Ui.text(this, "Открытые приложения", 30, 700, Color.WHITE).apply { letterSpacing = -0.02f },
            FrameLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = px(72); topMargin = px(64) })
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
            setPadding(px(72), px(40), px(72), px(40))
        }
        row = Ui.row(this).apply { clipChildren = false; clipToPadding = false }
        scroll.addView(row, FrameLayout.LayoutParams(WRAP, WRAP))
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_VERTICAL))
        empty = Ui.text(this, "Сейчас ничего не открыто", 22, 600, Glass.TEXT2).apply { visibility = View.GONE }
        root.addView(empty, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        root.addView(Ui.text(this, "OK — открыть   ·   ↑ — закрыть   ·   Назад — выйти", 16, 600, Glass.TEXT3),
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(48) })
        setContentView(root)
        window.setBackgroundDrawable(null)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        SleepTimer.touch()
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) Sounds.select()
        return super.dispatchKeyEvent(event)
    }

    /** Which apps are running right now (one look at the process list), most recently used first. */
    private fun load() {
        Thread {
            val ctx = applicationContext
            val names = Adb.run(ctx, "ps -A -o NAME")?.lines()?.map { it.trim().substringBefore(':') }?.toSet().orEmpty()
            val recent = Apps.lastUsed(ctx)
            val skip = setOf(packageName, Apps.AIRPLAY_OWN, Apps.AIRPLAY_STOCK)
            val list = Apps.all(ctx).filter { it.pkg in names && it.pkg !in skip }
                .distinctBy { it.pkg }
                .sortedByDescending { recent[it.pkg] ?: 0L }
            main.post { show(list) }
        }.start()
    }

    private fun show(list: List<AppEntry>) {
        open.clear(); open += list
        row.removeAllViews()
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        list.forEachIndexed { i, a -> row.addView(card(a), Ui.lp(px(250), px(320)).apply { if (i > 0) leftMargin = px(28) }) }
        if (list.size > 1) {
            val all = Ui.col(this).apply {
                gravity = Gravity.CENTER
                Glass.focusable(this, 30)
                contentDescription = "Закрыть все"
                addView(Ui.text(this@SwitcherActivity, "Закрыть все", 20, 600, Color.WHITE))
                setOnClickListener { closeAll() }
            }
            row.addView(all, Ui.lp(px(200), px(320)).apply { leftMargin = px(28) })
        }
        row.getChildAt(0)?.requestFocus()
    }

    private fun card(a: AppEntry): View {
        val c = Ui.col(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(24), px(40), px(24), px(28))
            Glass.focusable(this, 30)
            contentDescription = a.label
        }
        val icon = ImageView(this).apply {
            setImageDrawable(Apps.hiResIcon(this@SwitcherActivity, a))
            scaleType = ImageView.ScaleType.FIT_CENTER
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, Ui.pxf(28)) }
            }
        }
        c.addView(icon, Ui.lp(px(132), px(132)))
        c.addView(Ui.flex(this))
        c.addView(Ui.text(this, a.label, 20, 600, Color.WHITE).apply { gravity = Gravity.CENTER }, Ui.lp(MATCH, WRAP))
        c.setOnClickListener { Apps.launch(this, a) }
        c.setOnKeyListener { v, code, e ->
            if (code == KeyEvent.KEYCODE_DPAD_UP && e.action == KeyEvent.ACTION_DOWN) { close(v, a); true } else false
        }
        return c
    }

    /** The card flies up and fades, like swiping an app away on iPhone; the app is closed for real. */
    private fun close(v: View, a: AppEntry) {
        Sounds.select()
        val idx = row.indexOfChild(v)
        v.setOnKeyListener(null)
        v.animate().translationY(-px(160).toFloat()).alpha(0f).setDuration(260).withLayer().withEndAction {
            row.removeView(v)
            open.remove(a)
            (row.getChildAt(idx.coerceAtMost(row.childCount - 1)) ?: row.getChildAt(0))?.requestFocus()
            if (open.isEmpty()) { row.removeAllViews(); empty.visibility = View.VISIBLE }
        }.start()
        val app = applicationContext
        Thread { Adb.shell(app, "am force-stop ${a.pkg}") }.start()
    }

    private fun closeAll() {
        val app = applicationContext
        val pkgs = open.map { it.pkg }
        Thread { pkgs.forEach { Adb.shell(app, "am force-stop $it") } }.start()
        for (i in 0 until row.childCount) {
            row.getChildAt(i).animate().translationY(-px(160).toFloat()).alpha(0f).setStartDelay(i * 40L).setDuration(260).start()
        }
        main.postDelayed({ finish() }, 450)
    }
}
