package md.grin.projector

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import md.grin.projector.Ui.MATCH
import md.grin.projector.Ui.WRAP
import md.grin.projector.Ui.px

/**
 * Settings in the spirit of iPhone and Mac, in the same glass: sections on the left, the chosen section on the right.
 * With the remote, moving through the list opens each section; Right steps into it.
 */
class SettingsActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var detail: LinearLayout
    private lateinit var scroll: ScrollView
    private val navRows = HashMap<String, View>()
    private var current = ""
    private var eatBackUp = false

    private data class Section(val key: String, val label: String, val icon: Int, val color: Int)

    private val sections = listOf(
        Section("wifi", "Wi‑Fi", R.drawable.ic_wifi, 0xFF0A84FF.toInt()),
        Section("bt", "Bluetooth", R.drawable.ic_bluetooth, 0xFF0A84FF.toInt()),
        Section("sound", "Звук", R.drawable.ic_volume, 0xFFFF375F.toInt()),
        Section("screen", "Экран и яркость", R.drawable.ic_sun, 0xFF0A84FF.toInt()),
        Section("saver", "Заставка", R.drawable.ic_image, 0xFF30B0C7.toInt()),
        Section("sleep", "Сон", R.drawable.ic_moon, 0xFF5E5CE6.toInt()),
        Section("remote", "Пульт с телефона", R.drawable.ic_phone, 0xFF34C759.toInt()),
        Section("about", "Об устройстве", R.drawable.ic_info, 0xFF8E8E93.toInt()),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        Sounds.init(this)
        Ui.applyTheme(true)
        setContentView(build())
        window.setBackgroundDrawable(null)
        show(intent.getStringExtra("section") ?: "wifi")
        navRows[current]?.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        // Back from a system screen (Wi‑Fi list, Bluetooth pairing): show fresh state.
        if (current.isNotEmpty()) refresh()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        SleepTimer.touch()
        val f = currentFocus
        // Clear, predictable moves with the remote (Android's own guess jumped between the two columns):
        //  - in the list: Up/Down go through sections, Right enters the section at its FIRST item;
        //  - in a section: Up/Down go item by item inside it, never back into the list.
        if (event.action == KeyEvent.ACTION_DOWN) {
            val navIdx = sections.indexOfFirst { navRows[it.key] === f }
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (navIdx >= 0) {
                    firstInDetail()?.let { scroll.scrollTo(0, 0); it.requestFocus() }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val d = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                    if (navIdx >= 0) {
                        navRows[sections[(navIdx + d).coerceIn(0, sections.size - 1)].key]?.requestFocus()
                        return true
                    }
                    if (inDetail(f)) {
                        val items = detail.getFocusables(View.FOCUS_DOWN)
                        val next = items.getOrNull(items.indexOf(f) + d)
                        if (next != null) {
                            next.requestFocus()
                            if (items.indexOf(next) == 0) scroll.smoothScrollTo(0, 0)
                        } else if (d < 0) scroll.smoothScrollTo(0, 0)
                        return true
                    }
                }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && inDetail(f)) {
            // Leaving a section with Left or Back lands on that section in the list, not on a neighbour.
            val leave = when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> true
                KeyEvent.KEYCODE_DPAD_LEFT -> f !is GlassSlider && !inDetail(f?.focusSearch(View.FOCUS_LEFT))
                else -> false
            }
            if (leave) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) eatBackUp = true
                navRows[current]?.requestFocus()
                return true
            }
        }
        // The Back press that only stepped out of a section must not also close Settings when it's released.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && eatBackUp) { eatBackUp = false; return true }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) Sounds.select()
        return super.dispatchKeyEvent(event)
    }

    private fun build(): View {
        val root = FrameLayout(this).apply { background = RoomDrawable() }
        val win = Ui.row(this, Gravity.TOP).apply {
            background = Glass.panel(40)
            clipToOutline = true
        }
        // Left: the list of sections.
        val side = Ui.col(this).apply {
            setBackgroundColor(0x24000000)
            setPadding(px(16), px(22), px(16), px(22))
            clipChildren = false
            clipToPadding = false
        }
        val head = Ui.row(this).apply { setPadding(px(4), 0, 0, 0) }
        val back = Ui.circleButton(this, R.drawable.ic_back, 40, 0x24FFFFFF, Color.WHITE, iconSize = 20).apply {
            contentDescription = "Назад на главный экран"
            setOnClickListener { finish() }
        }
        head.addView(back, Ui.lp(px(40), px(40)))
        head.addView(Ui.text(this, "Настройки", 28, 700, Color.WHITE).apply { letterSpacing = -0.02f }, Ui.lp(WRAP, WRAP).apply { leftMargin = px(12) })
        side.addView(head, Ui.lp(MATCH, WRAP).apply { bottomMargin = px(18) })
        for (s in sections) {
            val row = Ui.row(this).apply {
                setPadding(px(12), 0, px(12), 0)
                contentDescription = s.label
                isFocusable = true
                isClickable = true
                isSoundEffectsEnabled = false
                background = Ui.round(Color.TRANSPARENT, 14)
                // tvOS-style: landing on a section opens it.
                setOnFocusChangeListener { _, has -> if (has) { Sounds.tick(); show(s.key) }; paintNav() }
                setOnClickListener { show(s.key); firstInDetail()?.requestFocus() }
            }
            val badge = FrameLayout(this).apply { background = Ui.round(s.color, 8) }
            badge.addView(Ui.icon(this, s.icon, 17, Color.WHITE), FrameLayout.LayoutParams(px(17), px(17), Gravity.CENTER))
            row.addView(badge, Ui.lp(px(30), px(30)))
            row.addView(Ui.text(this, s.label, 16, 500, Color.WHITE), Ui.lp(0, WRAP, 1f).apply { leftMargin = px(12) })
            side.addView(row, Ui.lp(MATCH, px(46)).apply { topMargin = px(2) })
            navRows[s.key] = row
        }
        win.addView(side, Ui.lp(px(330), MATCH))

        // Right: the chosen section.
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = false
            clipToPadding = false
            setPadding(px(44), px(26), px(44), px(30))
        }
        detail = Ui.col(this).apply { clipChildren = false; clipToPadding = false }
        scroll.addView(detail, FrameLayout.LayoutParams(MATCH, WRAP))
        win.addView(scroll, Ui.lp(0, MATCH, 1f))
        root.addView(win, FrameLayout.LayoutParams(px(1120), px(640)).apply { leftMargin = px(80); topMargin = px(40) })
        return root
    }

    private fun paintNav() {
        for ((k, v) in navRows) {
            v.background = Ui.round(if (v.isFocused) 0x47FFFFFF else if (k == current) 0x29FFFFFF else Color.TRANSPARENT, 14)
        }
    }

    private fun firstInDetail(): View? = detail.getFocusables(View.FOCUS_DOWN).firstOrNull()

    private fun inDetail(v: View?): Boolean {
        var p = v?.parent
        while (p != null) { if (p === detail) return true; p = p.parent }
        return false
    }

    private fun show(key: String) {
        if (key == current) return
        current = key
        refresh()
        paintNav()
    }

    private fun refresh() {
        detail.removeAllViews()
        when (current) {
            "wifi" -> wifi()
            "bt" -> bluetooth()
            "sound" -> sound()
            "screen" -> screen()
            "saver" -> saver()
            "sleep" -> sleep()
            "remote" -> remote()
            "about" -> about()
        }
        scroll.scrollTo(0, 0)
    }

    // ---------- building blocks ----------

    private fun hero(icon: Int, color: Int, title: String, text: String) {
        val c = Ui.col(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val badge = FrameLayout(this).apply { background = Ui.round(color, 18) }
        badge.addView(Ui.icon(this, icon, 34, Color.WHITE), FrameLayout.LayoutParams(px(34), px(34), Gravity.CENTER))
        c.addView(badge, Ui.lp(px(64), px(64)))
        c.addView(Ui.text(this, title, 24, 700, Color.WHITE), Ui.lp(WRAP, WRAP).apply { topMargin = px(10) })
        if (text.isNotEmpty()) c.addView(Ui.text(this, text, 14, 500, Glass.TEXT2).apply {
            maxLines = 3; ellipsize = null; gravity = Gravity.CENTER; setLineSpacing(0f, 1.3f)
        }, Ui.lp(px(480), WRAP).apply { topMargin = px(6) })
        detail.addView(c, Ui.lp(MATCH, WRAP).apply { bottomMargin = px(8) })
    }

    /** An inset group of rows, like on iPhone. */
    private fun group(title: String? = null, foot: String? = null, rows: LinearLayout.() -> Unit) {
        val wrap = Ui.col(this)
        if (title != null) wrap.addView(Ui.text(this, title, 13, 600, Glass.TEXT3).apply { letterSpacing = 0.04f },
            Ui.lp(WRAP, WRAP).apply { leftMargin = px(18); bottomMargin = px(8) })
        val card = Ui.col(this).apply {
            background = Glass.inner(22, 0x17FFFFFF, 0x1AFFFFFF)
            clipToOutline = true
        }
        card.rows()
        // Hairlines between rows.
        val n = card.childCount
        for (i in n - 1 downTo 1) {
            card.addView(View(this).apply { setBackgroundColor(0x1AFFFFFF) }, i, Ui.lp(MATCH, maxOf(1, px(1))).apply { leftMargin = px(18) })
        }
        wrap.addView(card, Ui.lp(MATCH, WRAP))
        if (foot != null) wrap.addView(Ui.text(this, foot, 13, 500, Glass.TEXT3).apply { maxLines = 3; ellipsize = null; setLineSpacing(0f, 1.3f) },
            Ui.lp(MATCH, WRAP).apply { leftMargin = px(18); rightMargin = px(18); topMargin = px(8) })
        detail.addView(wrap, Ui.lp(MATCH, WRAP).apply { topMargin = px(22) })
    }

    private fun LinearLayout.row(
        label: String,
        sub: String? = null,
        value: String? = null,
        icon: Int = 0,
        color: Int = 0xFF3A3A3C.toInt(),
        chevron: Boolean = false,
        onClick: (() -> Unit)? = null,
    ): LinearLayout {
        val r = Ui.row(this@SettingsActivity).apply {
            setPadding(px(18), px(10), px(18), px(10))
            minimumHeight = px(56)
        }
        if (icon != 0) {
            val b = FrameLayout(this@SettingsActivity).apply { background = Ui.round(color, 9) }
            b.addView(Ui.icon(this@SettingsActivity, icon, 18, Color.WHITE), FrameLayout.LayoutParams(px(18), px(18), Gravity.CENTER))
            r.addView(b, Ui.lp(px(32), px(32)).apply { rightMargin = px(14) })
        }
        val t = Ui.col(this@SettingsActivity)
        t.addView(Ui.text(this@SettingsActivity, label, 16, 500, Color.WHITE))
        if (sub != null) t.addView(Ui.text(this@SettingsActivity, sub, 13, 500, Glass.TEXT3), Ui.lp(WRAP, WRAP).apply { topMargin = px(2) })
        r.addView(t, Ui.lp(0, WRAP, 1f))
        if (value != null) r.addView(Ui.text(this@SettingsActivity, value, 15, 500, Glass.TEXT2), Ui.lp(WRAP, WRAP).apply { leftMargin = px(12) })
        if (chevron) r.addView(Ui.icon(this@SettingsActivity, R.drawable.ic_chevron, 16, 0x66FFFFFF), Ui.lp(px(16), px(16)).apply { leftMargin = px(8) })
        if (onClick != null) { Glass.row(r); r.setOnClickListener { onClick() } }
        addView(r, Ui.lp(MATCH, WRAP))
        return r
    }

    private fun LinearLayout.toggle(label: String, sub: String? = null, on: Boolean, change: (Boolean) -> Unit) {
        val sw = GlassSwitch(this@SettingsActivity).apply { set(on, false) }
        val r = row(label, sub, onClick = {
            sw.set(!sw.on)
            change(sw.on)
        })
        r.addView(sw, Ui.lp(px(50), px(30)).apply { leftMargin = px(12) })
    }

    private fun LinearLayout.slider(value: Int, min: Int = 0, left: Int, right: Int, change: (Int) -> Unit) {
        val r = Ui.row(this@SettingsActivity).apply { setPadding(px(18), 0, px(18), 0) }
        r.addView(Ui.icon(this@SettingsActivity, left, 18, Glass.TEXT2))
        val s = GlassSlider(this@SettingsActivity, value, min, change)
        r.addView(s, Ui.lp(0, px(64), 1f).apply { leftMargin = px(16); rightMargin = px(16) })
        r.addView(Ui.icon(this@SettingsActivity, right, 24, Color.WHITE))
        addView(r, Ui.lp(MATCH, px(64)))
    }

    private fun open(vararg actions: String) {
        for (a in actions) {
            try { startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        }
        Apps.openSettings(this)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- sections ----------

    private fun wifi() {
        hero(R.drawable.ic_wifi, 0xFF0A84FF.toInt(), "Wi‑Fi", "Через эту сеть работают пульт на телефоне и AirPlay.")
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        val on = wm?.isWifiEnabled == true
        @Suppress("DEPRECATION")
        val ssid = wm?.connectionInfo?.ssid?.trim('"')?.takeIf { on && it.isNotBlank() && it != "<unknown ssid>" }
        val ip = RemoteServer.address()
        group {
            row("Wi‑Fi", value = if (on) "Вкл." else "Выкл.")
            row(ssid ?: if (ip != null) "Подключено" else "Нет подключения", sub = ip?.let { "IP-адрес $it" }, icon = R.drawable.ic_check,
                color = if (ip != null) 0xFF34C759.toInt() else 0xFF8E8E93.toInt())
        }
        group(foot = "Откроется список сетей проектора: выбери сеть и введи пароль с пульта на телефоне.") {
            row("Выбрать другую сеть", chevron = true) { open(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS) }
        }
    }

    private fun bluetooth() {
        hero(R.drawable.ic_bluetooth, 0xFF0A84FF.toInt(), "Bluetooth", "Колонки, наушники и геймпады. Звук сразу идёт на подключённое устройство.")
        @Suppress("DEPRECATION")
        val bt = BluetoothAdapter.getDefaultAdapter()
        if (bt == null) {
            group { row("Bluetooth на этом проекторе недоступен") }
            return
        }
        val on = try { bt.isEnabled } catch (_: Exception) { false }
        group {
            toggle("Bluetooth", on = on) { want ->
                try { if (want) bt.enable() else bt.disable() } catch (_: Exception) {}
                main.postDelayed({ if (current == "bt") refresh() }, 1500)
            }
        }
        if (!on) return
        val out = VolumeHud.outputName(this)
        val devices = try { bt.bondedDevices?.toList().orEmpty() } catch (_: Exception) { emptyList() }
        if (devices.isNotEmpty()) group("МОИ УСТРОЙСТВА") {
            for (d in devices) {
                val name = try { d.name } catch (_: Exception) { null } ?: d.address
                val playing = name.equals(out, true)
                row(name, sub = if (playing) "Подключено · звук идёт сюда" else "Сопряжено", icon = R.drawable.ic_speaker, chevron = true) {
                    open(Settings.ACTION_BLUETOOTH_SETTINGS)
                }
            }
        }
        group(foot = "Включи на колонке или наушниках режим сопряжения, и они появятся в списке.") {
            row("Добавить устройство", chevron = true) { open(Settings.ACTION_BLUETOOTH_SETTINGS) }
        }
    }

    private fun sound() {
        hero(R.drawable.ic_volume, 0xFFFF375F.toInt(), "Звук", "Громкость и куда выводится звук.")
        val (vol, _) = Control.volumeState(this)
        group("ГРОМКОСТЬ") {
            slider(vol, 0, R.drawable.ic_volume_off, R.drawable.ic_volume) { Control.setVolume(this@SettingsActivity, it) }
        }
        group("ВЫВОД ЗВУКА", "Чтобы звук шёл на колонку, подключи её в разделе Bluetooth.") {
            row(VolumeHud.outputName(this@SettingsActivity), icon = R.drawable.ic_speaker, value = "Сейчас")
            row("Bluetooth-устройства", chevron = true) { show("bt"); firstInDetail()?.requestFocus() }
        }
        group {
            toggle("Звуки навигации", "Тихий щелчок при движении по экрану", Sounds.isOn(this@SettingsActivity)) { Sounds.setOn(this@SettingsActivity, it) }
        }
    }

    private fun screen() {
        hero(R.drawable.ic_sun, 0xFF0A84FF.toInt(), "Экран и яркость", "Яркость картинки и тёплый свет вечером, как на iPhone.")
        group("ЯРКОСТЬ") {
            slider(Picture.brightness(this@SettingsActivity), 5, R.drawable.ic_sun, R.drawable.ic_sun) { Picture.setBrightness(this@SettingsActivity, it) }
        }
        group("NIGHT SHIFT") {
            toggle("Night Shift", "Тёплые цвета, мягче для глаз вечером", Picture.isWarm(this@SettingsActivity)) { Picture.setWarm(this@SettingsActivity, it) }
            slider(Picture.warmth(this@SettingsActivity), 0, R.drawable.ic_sun, R.drawable.ic_moon) { Picture.setWarmth(this@SettingsActivity, it) }
        }
    }

    private fun saver() {
        hero(R.drawable.ic_image, 0xFF30B0C7.toInt(), "Заставка", "Полёты над городами мира, когда проектор отдыхает.")
        group {
            toggle("Заставка", "Сама через 3 минуты, если ничего не играет", Aerials.isOn(this@SettingsActivity)) { Aerials.setOn(this@SettingsActivity, it) }
            toggle("Песня на заставке", "Карточка в правом верхнем углу", Aerials.cardOn(this@SettingsActivity)) { Aerials.setCard(this@SettingsActivity, it) }
        }
        group {
            row("Показать сейчас", chevron = true) { Aerials.show(this@SettingsActivity) }
        }
    }

    private fun sleep() {
        hero(R.drawable.ic_moon, 0xFF5E5CE6.toInt(), "Сон", "С 23:00 до 6:00, если 30 минут никто не трогает пульт, проектор выключится сам. За минуту до этого появится предупреждение.")
        group {
            toggle("Сон после 23:00", on = SleepTimer.isOn(this@SettingsActivity)) { SleepTimer.setOn(this@SettingsActivity, it) }
        }
        group {
            row("Проверить: выключить через 40 с", chevron = true) { SleepTimer.test(this@SettingsActivity, 40); finish() }
        }
    }

    private fun remote() {
        hero(R.drawable.ic_phone, 0xFF34C759.toInt(), "Пульт с телефона", "Открой адрес на iPhone в Safari и добавь на экран «Домой».")
        group {
            row("Адрес пульта", value = RemoteServer.url())
            row("Кнопки", value = if (Adb.connected) "Подключены" else "Не подключены")
            row("Плеер", value = if (Media.hasAccess(this@SettingsActivity)) "Есть доступ" else "Нет доступа")
            row("Клавиатура", value = if (TvKeyboard.isDefault(this@SettingsActivity)) "Наша (Apple TV)" else "Системная", chevron = true) {
                try {
                    Thread { Adb.useOurKeyboard(applicationContext) }.start()
                    getSystemService(android.view.inputmethod.InputMethodManager::class.java).showInputMethodPicker()
                } catch (_: Exception) { open(Settings.ACTION_INPUT_METHOD_SETTINGS) }
            }
        }
        group(foot = "Даёт пульту право нажимать кнопки, видеть музыку и открывать приложения. При первом разе на проекторе спросит «Allow USB debugging?» — выбери «Always allow».") {
            row("Настроить автоматически", chevron = true) {
                toast("Настраиваю…")
                Thread {
                    val ok = Adb.setup(applicationContext)
                    main.post {
                        Media.attach(applicationContext)
                        Toast.makeText(this@SettingsActivity, if (ok) "Готово" else "Не вышло: ${Adb.lastError}", Toast.LENGTH_LONG).show()
                        if (current == "remote") refresh()
                    }
                }.start()
            }
        }
    }

    private fun about() {
        hero(R.drawable.ic_info, 0xFF8E8E93.toInt(), "Об устройстве", "")
        val prefs = getSharedPreferences("home", MODE_PRIVATE)
        group {
            row("Модель", value = android.os.Build.MODEL)
            row("Projector Home", value = "Версия 1.0.${Updater.currentBuild(this@SettingsActivity)}")
            row("Проверить обновления", "Проверяются сами каждые 6 часов", icon = R.drawable.ic_refresh, color = 0xFF0A84FF.toInt(), chevron = true) {
                toast("Проверяю обновления…")
                Thread {
                    Updater.check(applicationContext)
                    main.post { Toast.makeText(this@SettingsActivity, Updater.status, Toast.LENGTH_LONG).show() }
                }.start()
            }
        }
        val notes = listOfNotNull(
            prefs.getString("last_off", null)?.let { "Последнее выключение" to it },
            prefs.getString("last_crash", null)?.let { "Последняя ошибка" to it },
            prefs.getStringSet("quieted", null)?.takeIf { it.isNotEmpty() }?.let { "Отключены всплывающие окна" to it.joinToString() },
        )
        if (notes.isNotEmpty()) group("ЖУРНАЛ") {
            for ((k, v) in notes) row(k, sub = v)
        }
        group {
            row("Системные настройки проектора", chevron = true) { Apps.openSettings(this@SettingsActivity) }
        }
    }
}
