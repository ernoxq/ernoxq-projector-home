package md.grin.projector

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import md.grin.projector.Ui.INK
import md.grin.projector.Ui.MATCH
import md.grin.projector.Ui.WRAP
import md.grin.projector.Ui.px
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var greet: TextView
    private lateinit var weatherIcon: ImageView
    private lateinit var weatherText: TextView
    private lateinit var cover: ImageView
    private lateinit var coverIcon: ImageView
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var trackSource: TextView
    private lateinit var trackTime: TextView
    private lateinit var progress: View
    private lateinit var progressTrack: FrameLayout
    private lateinit var playIcon: ImageView
    private lateinit var tilesRow: LinearLayout
    private var firstTile: View? = null

    private val mediaChanged: () -> Unit = { renderMedia() }
    private val tick = object : Runnable {
        override fun run() {
            renderClock()
            renderMedia()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        setContentView(build())
        RemoteService.start(this)
        Thread { Adb.setup(applicationContext) }.start()
    }

    override fun onResume() {
        super.onResume()
        App.isForeground = true
        Apps.invalidate()
        renderTiles()
        Media.attach(applicationContext)
        Media.listen(mediaChanged)
        main.post(tick)
        loadWeather()
        firstTile?.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        App.isForeground = false
        Media.unlisten(mediaChanged)
        main.removeCallbacks(tick)
    }

    @Deprecated("Home screen: Back does nothing")
    override fun onBackPressed() {}

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { showMenu(); return true }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- layout (mockup pixels, scaled to the screen) ----------

    private fun build(): View {
        val root = Ui.col(this).apply {
            setBackgroundColor(Ui.GROUND)
            setPadding(px(56), px(34), px(56), px(30))
            clipChildren = false
            clipToPadding = false
        }
        root.addView(header(), Ui.lp(MATCH, px(56)))
        root.addView(Ui.flex(this))
        root.addView(topRow(), Ui.lp(MATCH, px(200)))
        root.addView(Ui.flex(this))
        tilesRow = Ui.row(this).apply { clipChildren = false; clipToPadding = false }
        root.addView(tilesRow, Ui.lp(MATCH, px(196)))
        root.addView(Ui.flex(this))
        root.addView(nav(), LinearLayout.LayoutParams(WRAP, px(64)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        return root
    }

    private fun header(): View {
        val h = Ui.row(this)
        h.addView(Ui.circleButton(this, R.drawable.ic_menu, 52, Ui.GLASS).apply { setOnClickListener { showMenu() } }, Ui.lp(px(52), px(52)))
        h.addView(Ui.flex(this))
        val pill = Ui.row(this).apply {
            background = Ui.round(Ui.GLASS, 30)
            setPadding(px(22), 0, px(8), 0)
        }
        clock = Ui.text(this, "", 26, 600).apply { letterSpacing = -0.03f }
        pill.addView(clock)
        pill.addView(View(this).apply { setBackgroundColor(0x2E121212) }, Ui.lp(px(1), px(22)).apply { leftMargin = px(16); rightMargin = px(16) })
        date = Ui.text(this, "", 17, 500)
        pill.addView(date)
        val w = Ui.row(this).apply {
            background = Ui.round(Ui.WHITE, 21)
            setPadding(px(12), 0, px(16), 0)
        }
        weatherIcon = Ui.icon(this, R.drawable.ic_cloud, 22)
        w.addView(weatherIcon)
        weatherText = Ui.text(this, Weather.city(this), 17, 600)
        w.addView(weatherText, Ui.lp(WRAP, WRAP).apply { leftMargin = px(8) })
        pill.addView(w, Ui.lp(WRAP, px(42)).apply { leftMargin = px(18) })
        h.addView(pill, Ui.lp(WRAP, px(56)))
        h.addView(Ui.flex(this))
        h.addView(Ui.circleButton(this, R.drawable.ic_bell, 52, Ui.GLASS).apply { setOnClickListener { showMenu() } }, Ui.lp(px(52), px(52)))
        return h
    }

    private fun topRow(): View {
        val r = Ui.row(this, Gravity.TOP).apply { clipChildren = false }
        val left = Ui.col(this).apply { clipChildren = false }
        greet = Ui.text(this, "", 48, 600).apply {
            maxLines = 2
            letterSpacing = -0.033f
            setLineSpacing(0f, 1.02f)
        }
        left.addView(greet, Ui.lp(MATCH, WRAP).apply { topMargin = px(6) })
        left.addView(Ui.flex(this))
        val chips = Ui.row(this).apply { clipChildren = false }
        val chipDefs = listOf("Все" to "apps", "Видео" to "youtube", "Музыка" to "spotify", "Трансляция" to "cast")
        chipDefs.forEachIndexed { i, (label, key) ->
            val dark = i == 0
            val c = Ui.pill(this, label, if (dark) INK else Ui.GLASS, if (dark) Color.WHITE else INK)
            c.setOnClickListener { Apps.launchKey(this, key) }
            chips.addView(c, Ui.lp(WRAP, px(48)).apply { rightMargin = px(10) })
        }
        left.addView(chips, Ui.lp(MATCH, px(48)))
        r.addView(left, Ui.lp(0, MATCH, 1f).apply { rightMargin = px(32) })
        r.addView(player(), Ui.lp(px(540), MATCH))
        return r
    }

    private fun player(): View {
        val card = Ui.row(this).apply {
            background = Ui.round(Ui.WHITE, 36)
            setPadding(px(14), px(14), px(20), px(14))
        }
        val art = FrameLayout(this).apply {
            background = Ui.round(Ui.NIGHT, 26)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, Ui.pxf(26)) }
            }
        }
        art.addView(View(this).apply { background = Ui.round(Ui.NIGHT2, 75) }, FrameLayout.LayoutParams(px(150), px(150)).apply { leftMargin = px(62); topMargin = px(-50) })
        art.addView(View(this).apply { background = Ui.round(Ui.ACCENT, 55) }, FrameLayout.LayoutParams(px(110), px(110)).apply { leftMargin = px(-20); topMargin = px(102) })
        coverIcon = ImageView(this).apply { setImageResource(R.drawable.ic_music); imageTintList = android.content.res.ColorStateList.valueOf(Ui.GROUND) }
        art.addView(coverIcon, FrameLayout.LayoutParams(px(40), px(40), Gravity.CENTER))
        cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        art.addView(cover, FrameLayout.LayoutParams(MATCH, MATCH))
        card.addView(art, Ui.lp(px(172), px(172)))

        val info = Ui.col(this)
        trackSource = Ui.text(this, "Сейчас играет", 13, 500, 0xFF3F4552.toInt()).apply {
            background = Ui.round(Color.WHITE, 13, 1, Ui.LINE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(12), 0, px(12), 0)
        }
        info.addView(trackSource, Ui.lp(WRAP, px(26)))
        trackTitle = Ui.text(this, "Ничего не играет", 24, 600).apply { letterSpacing = -0.02f }
        info.addView(trackTitle, Ui.lp(MATCH, WRAP).apply { topMargin = px(12) })
        trackArtist = Ui.text(this, "Включи музыку в Spotify", 15, 400, Ui.MUTED)
        info.addView(trackArtist, Ui.lp(MATCH, WRAP).apply { topMargin = px(6) })
        info.addView(Ui.flex(this))
        progressTrack = FrameLayout(this).apply { background = Ui.round(0xFFE6EAF2.toInt(), 3) }
        progress = View(this).apply { background = Ui.round(INK, 3) }
        progressTrack.addView(progress, FrameLayout.LayoutParams(0, MATCH))
        info.addView(progressTrack, Ui.lp(MATCH, px(5)))
        val ctrl = Ui.row(this)
        trackTime = Ui.text(this, "", 13, 500, Ui.MUTED)
        ctrl.addView(trackTime)
        ctrl.addView(Ui.flex(this))
        ctrl.addView(Ui.circleButton(this, R.drawable.ic_prev, 44, Ui.SOFT, iconSize = 16).apply { setOnClickListener { Media.command("prev") } }, Ui.lp(px(44), px(44)))
        val play = Ui.circleButton(this, R.drawable.ic_pause, 52, Ui.ACCENT, iconSize = 18).apply {
            setOnClickListener { if (!Media.command("play")) Apps.launchKey(this@MainActivity, "spotify") }
        }
        playIcon = play.getChildAt(0) as ImageView
        ctrl.addView(play, Ui.lp(px(52), px(52)).apply { leftMargin = px(8); rightMargin = px(8) })
        ctrl.addView(Ui.circleButton(this, R.drawable.ic_next, 44, Ui.SOFT, iconSize = 16).apply { setOnClickListener { Media.command("next") } }, Ui.lp(px(44), px(44)))
        info.addView(ctrl, Ui.lp(MATCH, px(52)).apply { topMargin = px(10) })
        card.addView(info, Ui.lp(0, MATCH, 1f).apply { leftMargin = px(18) })
        return card
    }

    private fun renderTiles() {
        tilesRow.removeAllViews()
        firstTile = null
        Apps.tiles.forEachIndexed { i, spec ->
            val entry = Apps.resolve(this, spec)
            val cardBg = if (spec.dark) INK else Ui.WHITE
            val tile = Ui.col(this).apply {
                setPadding(px(20), px(20), px(20), px(20))
                Ui.focusable(this, cardBg, 34, if (spec.dark) Ui.ACCENT else INK)
                setOnClickListener { if (entry != null) Apps.launch(this@MainActivity, entry) else Apps.launchKey(this@MainActivity, spec.key) }
                setOnLongClickListener { pickApp(spec); true }
            }
            val iconBox = FrameLayout(this).apply { background = Ui.round(if (spec.dark) Ui.ACCENT else Ui.SOFT, 18) }
            val appIcon = entry?.let { Apps.icon(this, it) }
            if (appIcon != null && !spec.dark) {
                iconBox.addView(ImageView(this).apply { setImageDrawable(appIcon) }, FrameLayout.LayoutParams(px(44), px(44), Gravity.CENTER))
            } else {
                iconBox.addView(Ui.icon(this, spec.fallbackIcon, 28), FrameLayout.LayoutParams(px(28), px(28), Gravity.CENTER))
            }
            tile.addView(iconBox, Ui.lp(px(60), px(60)))
            tile.addView(Ui.flex(this))
            tile.addView(Ui.text(this, spec.label, 19, 600, if (spec.dark) Color.WHITE else INK).apply { letterSpacing = -0.015f })
            val hint = if (entry == null && spec.candidates.isNotEmpty()) "Не установлено" else spec.hint
            tile.addView(Ui.text(this, hint, 14, 400, if (spec.dark) 0xFFB8BDC9.toInt() else Ui.MUTED), Ui.lp(WRAP, WRAP).apply { topMargin = px(4) })
            tilesRow.addView(tile, Ui.lp(0, MATCH, 1f).apply { if (i > 0) leftMargin = px(16) })
            if (i == 0) firstTile = tile
        }
    }

    private fun nav(): View {
        val n = Ui.row(this).apply {
            background = Ui.round(Ui.WHITE, 34)
            setPadding(px(8), px(8), px(8), px(8))
        }
        val home = Ui.row(this).apply {
            setPadding(px(14), 0, px(22), 0)
            Ui.focusable(this, Ui.SOFT, 24)
            addView(Ui.icon(this@MainActivity, R.drawable.ic_home, 22))
            addView(Ui.text(this@MainActivity, "Главная", 17, 600), Ui.lp(WRAP, WRAP).apply { leftMargin = px(10) })
            setOnClickListener { firstTile?.requestFocus() }
        }
        n.addView(home, Ui.lp(WRAP, px(48)))
        val items = listOf(R.drawable.ic_grid to "apps", R.drawable.ic_hdmi to "hdmi", R.drawable.ic_cast to "cast", R.drawable.ic_gear to "settings")
        for ((res, key) in items) {
            val b = FrameLayout(this).apply {
                Ui.focusable(this, Color.WHITE, 24)
                addView(Ui.icon(this@MainActivity, res, 22, 0xFF6B7280.toInt()), FrameLayout.LayoutParams(px(22), px(22), Gravity.CENTER))
                setOnClickListener { Apps.launchKey(this@MainActivity, key) }
            }
            n.addView(b, Ui.lp(px(56), px(48)).apply { leftMargin = px(6) })
        }
        return n
    }

    // ---------- live data ----------

    private fun renderClock() {
        val now = Date()
        clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val d = SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(now)
        date.text = d.replaceFirstChar { it.uppercase() }
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val hello = when (h) { in 5..11 -> "Доброе утро"; in 12..17 -> "Добрый день"; in 18..22 -> "Добрый вечер"; else -> "Доброй ночи" }
        val name = getSharedPreferences("home", MODE_PRIVATE).getString("name", "Эрнест")
        val text = "$hello, $name,\nчто включаем?"
        if (greet.text.toString() != text) {
            val sp = android.text.SpannableString(text)
            val start = text.indexOf(name!!)
            sp.setSpan(ItalicSpan(Ui.font(this, 600, true)), start, start + name.length + 1, 0)
            greet.text = sp
        }
    }

    private fun renderMedia() {
        val np = Media.current(this)
        if (np == null) {
            trackSource.text = if (Media.hasAccess(this)) "Сейчас играет" else "Плеер ждёт доступа"
            trackTitle.text = "Ничего не играет"
            trackArtist.text = "Включи музыку в Spotify"
            trackTime.text = ""
            cover.visibility = View.GONE
            playIcon.setImageResource(R.drawable.ic_play)
            setProgress(0f)
            return
        }
        trackSource.text = "Сейчас играет · ${np.source}"
        trackTitle.text = np.title
        trackArtist.text = np.artist
        if (np.art != null) { cover.setImageBitmap(np.art); cover.visibility = View.VISIBLE } else cover.visibility = View.GONE
        playIcon.setImageResource(if (np.playing) R.drawable.ic_pause else R.drawable.ic_play)
        trackTime.text = if (np.duration > 0) "${fmt(np.position)} / ${fmt(np.duration)}" else ""
        setProgress(if (np.duration > 0) np.position.toFloat() / np.duration else 0f)
    }

    private fun setProgress(f: Float) {
        val w = (progressTrack.width * f.coerceIn(0f, 1f)).toInt()
        val lp = progress.layoutParams
        if (lp.width != w) { lp.width = w; progress.layoutParams = lp }
    }

    private fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    private fun loadWeather() {
        Weather.peek()?.let { showWeather(it) }
        Thread {
            val w = Weather.load(applicationContext) ?: return@Thread
            main.post { showWeather(w) }
        }.start()
    }

    private fun showWeather(w: WeatherNow) {
        weatherIcon.setImageResource(w.icon)
        weatherText.text = "${w.temp}° · ${w.city}"
    }

    // ---------- dialogs ----------

    private fun pickApp(spec: TileSpec) {
        val apps = Apps.all(this)
        AlertDialog.Builder(this, R.style.Dialog)
            .setTitle("Что открывать в плитке «${spec.label}»?")
            .setItems(apps.map { it.label }.toTypedArray()) { _, i ->
                Apps.choose(this, spec, apps[i].pkg)
                renderTiles()
            }
            .show()
    }

    private fun showMenu() {
        val adb = if (Adb.connected) "подключено" else "не подключено (${Adb.lastError ?: "ждёт разрешения"})"
        val player = if (Media.hasAccess(this)) "есть доступ" else "нет доступа"
        AlertDialog.Builder(this, R.style.Dialog)
            .setTitle("Пульт с телефона")
            .setMessage("Версия 1.0.${Updater.currentBuild(this)} · обновляется сама\n\nОткрой на айфоне в Safari:\n\n${RemoteServer.url()}\n\nТелефон и проектор должны быть в одной Wi-Fi-сети.\n\nУправление кнопками: $adb\nПлеер: $player")
            .setPositiveButton("Настроить автоматически") { _, _ ->
                Thread {
                    val ok = Adb.setup(applicationContext)
                    main.post {
                        Media.attach(applicationContext)
                        android.widget.Toast.makeText(this, if (ok) "Готово" else "Не вышло: ${Adb.lastError}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            .setNeutralButton("Обновить") { _, _ ->
                android.widget.Toast.makeText(this, "Проверяю обновления…", android.widget.Toast.LENGTH_SHORT).show()
                Thread {
                    Updater.check(applicationContext)
                    main.post { android.widget.Toast.makeText(this, Updater.status, android.widget.Toast.LENGTH_LONG).show() }
                }.start()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }
}

/** Draws part of a text in another typeface (the italic name in the greeting). */
class ItalicSpan(private val tf: android.graphics.Typeface) : android.text.style.MetricAffectingSpan() {
    override fun updateDrawState(tp: android.text.TextPaint) { tp.typeface = tf }
    override fun updateMeasureState(tp: android.text.TextPaint) { tp.typeface = tf }
}
