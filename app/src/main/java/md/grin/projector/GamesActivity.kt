package md.grin.projector

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.TextView
import md.grin.projector.Ui.MATCH
import md.grin.projector.Ui.WRAP
import md.grin.projector.Ui.px

/**
 * Game mode on the projector: every game in /sdcard/Games as a card. Played with the phone gamepad
 * (or any Bluetooth gamepad): ✕ / OK starts a game, ○ / Back goes back.
 */
class GamesActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var games: List<Game> = emptyList()
    private val builtins = MiniGame.all()
    /** Built-in games first (always there, nothing to install), then the uploaded ones. */
    private val items: List<Any> get() = builtins + games
    private lateinit var grid: GridView
    private lateinit var empty: TextView
    private lateinit var status: TextView
    private lateinit var action: TextView
    private var lastCount = -1

    private val tick = object : Runnable {
        override fun run() {
            renderStatus()
            // New uploads from the phone show up by themselves.
            Thread {
                val fresh = Games.list()
                main.post { if (fresh.size != lastCount || fresh.map { it.file } != games.map { it.file }) show(fresh) }
            }.start()
            main.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        Sounds.init(this)
        Ui.applyTheme(true)
        val root = Ui.col(this).apply {
            background = RoomDrawable()
            setPadding(px(56), px(40), px(56), 0)
        }
        val head = Ui.row(this).apply { clipChildren = false; clipToPadding = false }
        val titles = Ui.col(this)
        titles.addView(Ui.text(this, "Игры", 40, 700, Color.WHITE).apply { letterSpacing = -0.02f })
        status = Ui.text(this, "", 16, 500, Glass.TEXT2)
        titles.addView(status, Ui.lp(MATCH, WRAP).apply { topMargin = px(6) })
        head.addView(titles, Ui.lp(0, WRAP, 1f))
        action = Ui.pill(this, "RetroArch", 0x33FFFFFF, Color.WHITE, h = 52, padH = 26, size = 17, weight = 600).apply {
            setOnClickListener { Sounds.select(); onAction() }
        }
        head.addView(action)
        root.addView(head, Ui.lp(MATCH, WRAP))

        val body = FrameLayout(this)
        grid = GridView(this).apply {
            numColumns = 4
            horizontalSpacing = px(16)
            verticalSpacing = px(16)
            setPadding(px(8), px(28), px(8), px(40))
            clipToPadding = false
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setDrawSelectorOnTop(false)
            isSoundEffectsEnabled = false
            adapter = Adapter()
            setOnItemClickListener { _, _, p, _ -> open(items.getOrNull(p)) }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                var last: View? = null
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, p: Int, id: Long) {
                    last?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(140)?.start()
                    last?.background = Glass.inner(28)
                    view?.background = Ui.round(0x47FFFFFF, 28, 2, 0xA6FFFFFF.toInt())
                    view?.animate()?.scaleX(1.05f)?.scaleY(1.05f)?.setDuration(160)?.start()
                    last = view
                    Sounds.tick()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        body.addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))
        empty = TextView(this).apply {
            setTextColor(Glass.TEXT2)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.pxf(20))
            typeface = Ui.font(this@GamesActivity, 500)
            setLineSpacing(Ui.pxf(8), 1f)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        body.addView(empty, FrameLayout.LayoutParams(px(820), WRAP, Gravity.CENTER))
        root.addView(body, Ui.lp(MATCH, 0, 1f))
        setContentView(root)
        window.setBackgroundDrawable(null)
        Thread { Games.prepare(this) }.start()
    }

    override fun onResume() {
        super.onResume()
        lastCount = -1
        main.post(tick)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tick)
    }

    private fun show(list: List<Game>) {
        val focusedGrid = grid.hasFocus()
        games = list
        lastCount = list.size
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
        val none = false
        grid.visibility = View.VISIBLE
        empty.visibility = View.GONE
        empty.text = if (Games.retroArch(this) == null)
            "Для игр нужен эмулятор RetroArch (бесплатный).\nНажми «Установить RetroArch» вверху — он скачается и поставится сам."
        else
            "Игр пока нет.\nНа телефоне: пульт → 🎮 → «Загрузить игру» и выбери файл.\n\n" +
            "Один раз скачай эмуляторы: «RetroArch» вверху → Online Updater → Core Downloader.\n" +
            "Бесплатные игры там же: Online Updater → Content Downloader."
        if (!none && (focusedGrid || currentFocus == null)) grid.requestFocus()
        if (none) action.requestFocus()
        renderStatus()
    }

    private fun renderStatus() {
        val ra = Games.retroArch(this)
        val s = when {
            Games.busy -> Games.status
            else -> "${items.size} ${plural(items.size)} · ✕ — играть, ○ — назад"
        }
        if (status.text.toString() != s) status.text = s
        val a = if (ra == null) "Установить RetroArch" else "RetroArch"
        if (action.text.toString() != a) action.text = a
    }

    private fun plural(n: Int) = when {
        n % 10 == 1 && n % 100 != 11 -> "игра"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "игры"
        else -> "игр"
    }

    private fun onAction() {
        if (Games.retroArch(this) == null) {
            if (!Games.busy) Thread { Games.installRetroArch(applicationContext); main.post { show(Games.list()) } }.start()
            renderStatus()
        } else {
            Games.openRetroArch(this)
        }
    }

    private fun open(item: Any?) {
        when (item) {
            is MiniGame -> { Sounds.select(); Games.playBuiltin(this, item.key) }
            is Game -> start(item)
        }
    }

    private fun start(g: Game?) {
        g ?: return
        Sounds.select()
        if (Games.retroArch(this) == null) { onAction(); return }
        val app = applicationContext
        Thread { Games.launch(app, g) }.start()
    }

    /** Gamepad buttons and the keyboard fallback (✕ = Z, ○ = X) work like OK and Back here. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_ENTER -> {
                val f = currentFocus
                if (f === grid) open(items.getOrNull(grid.selectedItemPosition)) else f?.performClick()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_X -> { finish(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val item = items[p]
            val card = (convert as? ViewGroup) ?: Ui.col(this@GamesActivity).apply {
                setPadding(px(20), px(18), px(20), px(18))
                background = Glass.inner(28)
                layoutParams = android.widget.AbsListView.LayoutParams(MATCH, px(150))
                val badge = Ui.text(this@GamesActivity, "", 13, 700, Color.WHITE).apply {
                    gravity = Gravity.CENTER
                    setPadding(px(12), 0, px(12), 0)
                }
                addView(badge, Ui.lp(WRAP, px(28)))
                addView(Ui.flex(this@GamesActivity))
                addView(Ui.text(this@GamesActivity, "", 20, 650, Color.WHITE).apply { maxLines = 2 }, Ui.lp(MATCH, WRAP))
            }
            val badge = card.getChildAt(0) as TextView
            if (item is MiniGame) {
                badge.text = "Встроенная"
                badge.background = Ui.round(item.color, 14)
                (card.getChildAt(2) as TextView).text = item.title
            } else if (item is Game) {
                badge.text = item.system.title
                badge.background = Ui.round(item.system.color, 14)
                (card.getChildAt(2) as TextView).text = Games.displayTitle(item)
            }
            return card
        }
    }
}
