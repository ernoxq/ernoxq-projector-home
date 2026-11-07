package md.grin.projector

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import md.grin.projector.Ui.MATCH
import md.grin.projector.Ui.WRAP
import md.grin.projector.Ui.px
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The home screen, visionOS-style: a glass window floating in a softly lit room, a small "Главная" ornament on top,
 * a column of shortcuts on the left. Inside: greeting, weather and time, three app tiles, a big player,
 * and the Settings and Apps cards.
 */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var greet: TextView
    private lateinit var weatherIcon: ImageView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherText: TextView
    private lateinit var cover: ImageView
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var trackSource: TextView
    private lateinit var trackElapsed: TextView
    private lateinit var trackLeft: TextView
    private lateinit var progress: RoundBar
    private lateinit var playIcon: ImageView
    private lateinit var tilesBox: LinearLayout
    private var firstTile: View? = null

    private val mediaChanged: () -> Unit = { renderMedia() }
    private val tick = object : Runnable {
        override fun run() {
            renderClock()
            updateProgress()
            main.postDelayed(this, 1000)
        }
    }

    /** Tiles are rebuilt only when an app is installed, removed or updated. */
    private var tilesDirty = true
    private val packagesChanged = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) {
            Apps.invalidate()
            tilesDirty = true
            if (App.isForeground) { renderTiles(); tilesDirty = false }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        Sounds.init(this)
        Ui.applyTheme(true)
        setContentView(build())
        // The root view paints the room itself; drop the window's copy to avoid drawing the screen twice.
        window.setBackgroundDrawable(null)
        RemoteService.start(this)
        registerReceiver(packagesChanged, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        // System setup through ADB runs once per installed version, not on every start.
        val prefs = getSharedPreferences("home", MODE_PRIVATE)
        val build = Updater.currentBuild(this)
        if (prefs.getLong("setup_build", -1) != build) {
            Thread { if (Adb.setup(applicationContext)) prefs.edit().putLong("setup_build", build).apply() }.start()
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(packagesChanged) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        App.isForeground = true
        if (tilesDirty) { renderTiles(); tilesDirty = false }
        if (!TvKeyboard.isDefault(this)) Thread { Adb.useOurKeyboard(applicationContext) }.start()
        // (No more "am kill-all": it closed Spotify whenever it was paused or in the background,
        //  so songs vanished and wouldn't come back. Android manages memory well enough on its own.)
        Media.attach(applicationContext)
        Media.listen(mediaChanged)
        lastMinute = ""
        renderMedia()
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        SleepTimer.touch()
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) Sounds.select()
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { openSettings(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // ---------- layout (mockup pixels, 1280×720, scaled to the screen) ----------

    private fun build(): View {
        val root = FrameLayout(this).apply {
            background = RoomDrawable()
            clipChildren = false
            clipToPadding = false
        }

        // "Главная" ornament on top.
        val top = Ui.row(this).apply {
            background = Glass.panel(28)
            setPadding(px(6), px(6), px(6), px(6))
        }
        val chip = Ui.row(this).apply {
            background = Ui.round(0x33FFFFFF, 22)
            setPadding(px(18), 0, px(20), 0)
            addView(Ui.icon(this@MainActivity, R.drawable.ic_home, 18, Color.WHITE))
            addView(Ui.text(this@MainActivity, "Главная", 15, 600, Color.WHITE), Ui.lp(WRAP, WRAP).apply { leftMargin = px(8) })
        }
        top.addView(chip, Ui.lp(WRAP, px(44)))
        root.addView(top, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = px(22) })

        root.addView(sideNav(), FrameLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = px(30); topMargin = px(236) })

        // The main glass window.
        val win = Ui.col(this).apply {
            background = Glass.panel(40)
            setPadding(px(34), px(32), px(34), px(32))
            clipChildren = false
            clipToPadding = false
        }
        win.addView(header(), Ui.lp(MATCH, px(72)))
        win.addView(Ui.flex(this))
        val mid = Ui.row(this, Gravity.TOP).apply { clipChildren = false; clipToPadding = false }
        tilesBox = Ui.row(this, Gravity.TOP).apply { clipChildren = false; clipToPadding = false }
        mid.addView(tilesBox, Ui.lp(0, MATCH, 2f))
        mid.addView(player(), Ui.lp(0, MATCH, 3f).apply { leftMargin = px(14) })
        win.addView(mid, Ui.lp(MATCH, px(236)))
        win.addView(Ui.flex(this))
        win.addView(bottomRow(), Ui.lp(MATCH, px(104)))
        // Larger and wider: from just right of the shortcut column almost to the right edge.
        root.addView(win, FrameLayout.LayoutParams(px(1124), px(560)).apply { leftMargin = px(122); topMargin = px(96) })
        return root
    }

    private fun sideNav(): View {
        val col = Ui.col(this).apply {
            background = Glass.panel(32)
            setPadding(px(8), px(8), px(8), px(8))
            clipChildren = false
            clipToPadding = false
        }
        val items = listOf(
            Triple(R.drawable.ic_home, "home", "Главная"),
            Triple(R.drawable.ic_grid, "apps", "Все приложения"),
            Triple(R.drawable.ic_hdmi, "hdmi", "HDMI"),
            Triple(R.drawable.ic_cast, "cast", "Трансляция"),
            Triple(R.drawable.ic_gamepad, "games", "Игры"),
            Triple(R.drawable.ic_gear, "settings", "Настройки"),
        )
        items.forEachIndexed { i, (res, key, label) ->
            val selected = key == "home"
            val b = FrameLayout(this).apply {
                contentDescription = label
                Glass.focusable(this, 24, if (selected) 0x38FFFFFF else 0, 0, 1.1f)
                addView(Ui.icon(this@MainActivity, res, 22, if (selected) Color.WHITE else Glass.TEXT2), FrameLayout.LayoutParams(px(22), px(22), Gravity.CENTER))
                setOnClickListener {
                    when (key) {
                        "home" -> firstTile?.requestFocus()
                        "settings" -> openSettings()
                        else -> Apps.launchKey(this@MainActivity, key)
                    }
                }
            }
            col.addView(b, Ui.lp(px(48), px(48)).apply { if (i > 0) topMargin = px(6) })
        }
        return col
    }

    private fun header(): View {
        val h = Ui.row(this)
        val left = Ui.col(this)
        greet = Ui.text(this, "", 32, 700, Color.WHITE).apply { letterSpacing = -0.02f }
        left.addView(greet, Ui.lp(MATCH, WRAP))
        left.addView(Ui.text(this, "Что включаем?", 17, 500, Glass.TEXT2), Ui.lp(WRAP, WRAP).apply { topMargin = px(4) })
        h.addView(left, Ui.lp(0, WRAP, 1f))

        // Weather: a large pill.
        val w = Ui.row(this).apply {
            background = Glass.inner(36, 0x21FFFFFF, 0x24FFFFFF)
            setPadding(px(12), 0, px(24), 0)
        }
        val badge = FrameLayout(this).apply { background = Glass.circle(0xE6FFFFFF.toInt()) }
        weatherIcon = Ui.icon(this, R.drawable.ic_cloud, 30, 0xFF4A90E2.toInt())
        badge.addView(weatherIcon, FrameLayout.LayoutParams(px(30), px(30), Gravity.CENTER))
        w.addView(badge, Ui.lp(px(48), px(48)))
        val wt = Ui.col(this)
        weatherTemp = Ui.text(this, "—°", 32, 700, Color.WHITE).apply { letterSpacing = -0.02f }
        weatherText = Ui.text(this, Weather.city(this), 14, 500, Glass.TEXT2)
        wt.addView(weatherTemp)
        wt.addView(weatherText, Ui.lp(WRAP, WRAP).apply { topMargin = px(2) })
        w.addView(wt, Ui.lp(WRAP, WRAP).apply { leftMargin = px(14) })
        h.addView(w, Ui.lp(WRAP, px(72)))

        // Time, larger.
        val t = Ui.col(this).apply { gravity = Gravity.END }
        clock = Ui.text(this, "", 44, 700, Color.WHITE).apply { letterSpacing = -0.03f; gravity = Gravity.END }
        date = Ui.text(this, "", 15, 500, Glass.TEXT2).apply { gravity = Gravity.END }
        t.addView(clock, Ui.lp(px(170), WRAP))
        t.addView(date, Ui.lp(px(200), WRAP).apply { topMargin = px(6) })
        h.addView(t, Ui.lp(WRAP, WRAP).apply { leftMargin = px(22) })
        return h
    }

    private fun player(): View {
        val card = Ui.row(this).apply {
            background = Glass.inner(28)
            setPadding(px(16), px(16), px(18), px(16))
            clipChildren = false
            clipToPadding = false
        }
        val art = FrameLayout(this).apply {
            background = Glass.gradient(20, 0xFF8E6CFF.toInt(), 0xFFFF6FB1.toInt())
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, Ui.pxf(20)) }
            }
        }
        art.addView(Ui.icon(this, R.drawable.ic_music, 64, 0xE6FFFFFF.toInt()), FrameLayout.LayoutParams(px(64), px(64), Gravity.CENTER))
        cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        art.addView(cover, FrameLayout.LayoutParams(MATCH, MATCH))
        card.addView(art, Ui.lp(px(204), px(204)))

        val info = Ui.col(this).apply { clipChildren = false; clipToPadding = false }
        trackSource = Ui.text(this, "СЕЙЧАС ИГРАЕТ", 12, 600, Glass.TEXT3).apply { letterSpacing = 0.08f }
        info.addView(trackSource, Ui.lp(MATCH, WRAP).apply { topMargin = px(2) })
        trackTitle = Ui.text(this, "Ничего не играет", 24, 700, Color.WHITE).apply { letterSpacing = -0.01f }
        info.addView(trackTitle, Ui.lp(MATCH, WRAP).apply { topMargin = px(6) })
        trackArtist = Ui.text(this, "Включи музыку в Spotify", 15, 500, Glass.TEXT2)
        info.addView(trackArtist, Ui.lp(MATCH, WRAP).apply { topMargin = px(4) })
        info.addView(Ui.flex(this))
        progress = RoundBar(this, 0x38FFFFFF, Color.WHITE)
        info.addView(progress, Ui.lp(MATCH, px(5)))
        val times = Ui.row(this)
        trackElapsed = Ui.text(this, "", 12, 500, Glass.TEXT3)
        trackLeft = Ui.text(this, "", 12, 500, Glass.TEXT3).apply { gravity = Gravity.END }
        // Fixed widths: the times tick every second without re-measuring the screen.
        times.addView(trackElapsed, Ui.lp(px(70), WRAP))
        times.addView(Ui.flex(this))
        times.addView(trackLeft, Ui.lp(px(70), WRAP))
        info.addView(times, Ui.lp(MATCH, WRAP).apply { topMargin = px(6) })
        val ctrl = Ui.row(this, Gravity.CENTER).apply { clipChildren = false }
        ctrl.addView(Ui.circleButton(this, R.drawable.ic_prev, 48, 0x1FFFFFFF, Color.WHITE, iconSize = 18).apply {
            contentDescription = "Предыдущий"
            setOnClickListener { Media.command("prev") }
        }, Ui.lp(px(48), px(48)))
        val play = Ui.circleButton(this, R.drawable.ic_pause, 58, 0xF2FFFFFF.toInt(), 0xFF1C1C1E.toInt(), iconSize = 20).apply {
            contentDescription = "Пауза"
            setOnClickListener { if (!Media.command("play")) Apps.launchKey(this@MainActivity, "spotify") }
        }
        playIcon = play.getChildAt(0) as ImageView
        ctrl.addView(play, Ui.lp(px(58), px(58)).apply { leftMargin = px(18); rightMargin = px(18) })
        ctrl.addView(Ui.circleButton(this, R.drawable.ic_next, 48, 0x1FFFFFFF, Color.WHITE, iconSize = 18).apply {
            contentDescription = "Следующий"
            setOnClickListener { Media.command("next") }
        }, Ui.lp(px(48), px(48)))
        info.addView(ctrl, Ui.lp(MATCH, px(58)).apply { topMargin = px(6) })
        card.addView(info, Ui.lp(0, MATCH, 1f).apply { leftMargin = px(20) })
        return card
    }

    private fun bottomRow(): View {
        val row = Ui.row(this).apply { clipChildren = false; clipToPadding = false }
        val settingsIcon = FrameLayout(this).apply {
            background = Ui.round(0xFF8E8E93.toInt(), 16)
            addView(Ui.icon(this@MainActivity, R.drawable.ic_gear, 30, Color.WHITE), FrameLayout.LayoutParams(px(30), px(30), Gravity.CENTER))
        }
        row.addView(bigCard(settingsIcon, "Настройки", "Wi‑Fi, Bluetooth, звук, экран") { openSettings() }, Ui.lp(0, MATCH, 1f))
        row.addView(bigCard(AppsGlyph(this), "Приложения", "Все программы на проекторе") { Apps.launchKey(this, "apps") },
            Ui.lp(0, MATCH, 1f).apply { leftMargin = px(14) })
        return row
    }

    private fun bigCard(icon: View, title: String, sub: String, onClick: () -> Unit): View =
        Ui.row(this).apply {
            setPadding(px(18), 0, px(22), 0)
            Glass.focusable(this, 26, lift = 1.03f)
            contentDescription = title
            addView(icon, Ui.lp(px(58), px(58)))
            val t = Ui.col(this@MainActivity)
            t.addView(Ui.text(this@MainActivity, title, 20, 600, Color.WHITE))
            t.addView(Ui.text(this@MainActivity, sub, 14, 500, Glass.TEXT2), Ui.lp(WRAP, WRAP).apply { topMargin = px(3) })
            addView(t, Ui.lp(0, WRAP, 1f).apply { leftMargin = px(16) })
            addView(Ui.icon(this@MainActivity, R.drawable.ic_chevron, 22, 0x80FFFFFF.toInt()))
            setOnClickListener { onClick() }
        }

    // Netflix is left out on purpose: only YouTube and Spotify on the home screen, the player gets the room.
    private val tileColors = mapOf("youtube" to 0xFFFF3B30.toInt(), "spotify" to 0xFF1FB257.toInt())

    private fun renderTiles() {
        tilesBox.removeAllViews()
        firstTile = null
        Apps.tiles.filter { it.key in tileColors }.forEachIndexed { i, spec ->
            val entry = Apps.resolve(this, spec)
            val appIcon = if (entry != null) Apps.hiResIcon(this, entry) else null
            val tile = Ui.col(this).apply {
                setPadding(px(18), px(18), px(18), px(18))
                Glass.focusable(this, 28)
                contentDescription = spec.label
                setOnClickListener {
                    // The app list is refreshed by the install/remove broadcast; re-reading it on every press slowed launches.
                    if (Apps.resolve(this@MainActivity, spec) == null) Apps.invalidate()
                    if (Apps.resolve(this@MainActivity, spec) == null) pickApp(spec)
                    else Apps.launchKey(this@MainActivity, spec.key)
                }
                setOnLongClickListener { pickApp(spec); true }
            }
            if (appIcon != null) {
                // The app's own icon, crisp, with rounded corners like on a phone home screen.
                val img = ImageView(this).apply {
                    setImageDrawable(appIcon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, Ui.pxf(15)) }
                    }
                }
                tile.addView(img, Ui.lp(px(52), px(52)))
            } else {
                val box = FrameLayout(this).apply { background = Ui.round(tileColors[spec.key] ?: 0xFF8E8E93.toInt(), 15) }
                box.addView(Ui.icon(this, spec.fallbackIcon, 26, Color.WHITE), FrameLayout.LayoutParams(px(26), px(26), Gravity.CENTER))
                tile.addView(box, Ui.lp(px(52), px(52)))
            }
            tile.addView(Ui.flex(this))
            tile.addView(Ui.text(this, spec.label, 17, 600, Color.WHITE))
            val hint = if (entry == null && spec.candidates.isNotEmpty()) "Нажми, чтобы выбрать" else spec.hint
            tile.addView(Ui.text(this, hint, 13, 500, Glass.TEXT2), Ui.lp(WRAP, WRAP).apply { topMargin = px(3) })
            tilesBox.addView(tile, Ui.lp(0, MATCH, 1f).apply { if (i > 0) leftMargin = px(14) })
            if (i == 0) firstTile = tile
        }
    }

    // ---------- live data ----------

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = Clock.zone }
    private val dateFmt = SimpleDateFormat("EEEE, d MMMM", Locale("ru")).apply { timeZone = Clock.zone }
    private var lastMinute = ""

    /** Redraws the header only when the minute changes. */
    private fun renderClock() {
        val now = Clock.now()
        val t = timeFmt.format(now)
        if (t == lastMinute) return
        lastMinute = t
        clock.text = t
        date.text = dateFmt.format(now).replaceFirstChar { it.uppercase() }
        val h = Calendar.getInstance(Clock.zone).apply { time = now }.get(Calendar.HOUR_OF_DAY)
        val hello = when (h) { in 5..11 -> "Доброе утро"; in 12..17 -> "Добрый день"; in 18..22 -> "Добрый вечер"; else -> "Доброй ночи" }
        val name = getSharedPreferences("home", MODE_PRIVATE).getString("name", "Эрнест")
        val text = "$hello, $name"
        if (greet.text.toString() != text) greet.text = text
    }

    private var mediaKey: Any? = Unit
    private var shownArt: android.graphics.Bitmap? = null

    /** Updates only the progress bar and time each second; the rest of the card waits for real changes. */
    private fun updateProgress() {
        val np = Media.current(this)
        val key = np?.let { listOf(it.title, it.artist, it.playing, it.art) }
        if (key != mediaKey) { renderMedia(); return }
        if (np == null || !np.playing || np.duration <= 0) return
        setTimes(np.position, np.duration)
    }

    private fun renderMedia() {
        val np = Media.current(this)
        mediaKey = np?.let { listOf(it.title, it.artist, it.playing, it.art) }
        if (np == null) {
            setText(trackSource, if (Media.hasAccess(this)) "СЕЙЧАС ИГРАЕТ" else "ПЛЕЕР ЖДЁТ ДОСТУПА")
            setText(trackTitle, "Ничего не играет")
            setText(trackArtist, "Включи музыку в Spotify")
            setTimes(0, 0)
            cover.visibility = View.GONE
            shownArt = null
            playIcon.setImageResource(R.drawable.ic_play)
            return
        }
        setText(trackSource, "СЕЙЧАС ИГРАЕТ · ${np.source.uppercase(Locale("ru"))}")
        setText(trackTitle, np.title)
        setText(trackArtist, np.artist)
        if (np.art != null) {
            if (np.art !== shownArt) cover.setImageBitmap(np.art)
            cover.visibility = View.VISIBLE
        } else cover.visibility = View.GONE
        shownArt = np.art
        playIcon.setImageResource(if (np.playing) R.drawable.ic_pause else R.drawable.ic_play)
        setTimes(np.position, np.duration)
    }

    private fun setTimes(pos: Long, dur: Long) {
        if (dur <= 0) {
            setText(trackElapsed, ""); setText(trackLeft, ""); progress.fraction = 0f
            return
        }
        val p = pos.coerceIn(0, dur)
        setText(trackElapsed, fmt(p))
        setText(trackLeft, "−" + fmt(dur - p))
        progress.fraction = p.toFloat() / dur
    }

    private fun setText(v: TextView, s: String) { if (v.text.toString() != s) v.text = s }

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
        setText(weatherTemp, "${w.temp}°")
        val sky = when (w.code) {
            0, 1 -> "Ясно"
            45, 48 -> "Туман"
            in 51..67, in 80..82 -> "Дождь"
            in 95..99 -> "Гроза"
            in 71..77, 85, 86 -> "Снег"
            else -> "Облачно"
        }
        setText(weatherText, "$sky · ${w.city}")
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
}
