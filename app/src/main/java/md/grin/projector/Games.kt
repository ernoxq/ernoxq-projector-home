package md.grin.projector

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** One console the projector can run, and the RetroArch core that runs it. */
data class GameSystem(val key: String, val title: String, val core: String, val exts: Set<String>, val color: Int)

data class Game(val system: GameSystem, val file: File) {
    /** "Crash Bandicoot (USA) [!]" → "Crash Bandicoot" */
    val title: String
        get() = file.nameWithoutExtension
            .replace(Regex("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]"), "")
            .replace('_', ' ')
            .trim()
            .ifEmpty { file.nameWithoutExtension }
}

/**
 * Games on the projector. The games live in /sdcard/Games/<console>/ (put there from the phone, see
 * RemoteServer /api/upload) and run in RetroArch, the open-source emulator, which this app installs and sets up.
 *
 * Chosen for the HY300 Pro (4 weak ARM cores, 1 GB): the light, fast cores only. PS2, GameCube or Switch
 * are far beyond this hardware.
 */
object Games {
    val SYSTEMS = listOf(
        GameSystem("ps1", "PlayStation", "pcsx_rearmed", setOf("cue", "chd", "pbp", "m3u", "iso", "img", "bin"), 0xFF3A6FD8.toInt()),
        GameSystem("nes", "NES · Денди", "fceumm", setOf("nes", "fds", "unf", "unif", "zip"), 0xFFE0453A.toInt()),
        GameSystem("snes", "Super Nintendo", "snes9x2010", setOf("sfc", "smc", "zip"), 0xFF7D5BD6.toInt()),
        GameSystem("md", "Sega Mega Drive", "genesis_plus_gx", setOf("md", "gen", "smd", "sms", "gg", "bin", "zip"), 0xFF2C2C2E.toInt()),
        GameSystem("gba", "Game Boy Advance", "gpsp", setOf("gba", "zip"), 0xFF5B4BD6.toInt()),
        GameSystem("gb", "Game Boy · Color", "gambatte", setOf("gb", "gbc", "dmg", "zip"), 0xFF6E8B3D.toInt()),
        GameSystem("arcade", "Аркады", "fbneo", setOf("zip", "7z"), 0xFFE08A1E.toInt()),
        GameSystem("dos", "DOS", "dosbox_pure", setOf("zip", "dosz", "exe", "com", "bat"), 0xFF4A4F5A.toInt()),
    )

    /** Free games from RetroArch's own Content Downloader land in Games/free; found by their file. */
    val FREE = GameSystem("free", "Бесплатные", "", emptySet(), 0xFF2FA36B.toInt())
    private val FREE_CORES = mapOf("doukutsu.exe" to "nxengine", "wad" to "prboom", "game" to "dinothawr", "pak" to "tyrquake")

    val root: File get() = File(Environment.getExternalStorageDirectory(), "Games")

    fun system(key: String): GameSystem? = SYSTEMS.firstOrNull { it.key == key } ?: FREE.takeIf { key == "free" }

    /** Which console a file belongs to, from its extension. null when the extension fits several (zip, bin…). */
    fun guess(name: String): GameSystem? {
        val ext = name.substringAfterLast('.', "").lowercase()
        val fits = SYSTEMS.filter { ext in it.exts }
        return fits.singleOrNull()
    }

    fun canWriteStorage(): Boolean = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    fun list(): List<Game> {
        val out = ArrayList<Game>()
        for (sys in SYSTEMS) {
            val files = File(root, sys.key).listFiles()?.filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase() in sys.exts } ?: continue
            // A PlayStation game is its .cue (or .m3u); the .bin tracks behind it aren't separate games.
            val cueNames = files.filter { it.extension.equals("cue", true) }.map { it.nameWithoutExtension.lowercase() }.toSet()
            val m3u = files.any { it.extension.equals("m3u", true) }
            for (f in files) {
                val ext = f.extension.lowercase()
                if (sys.key == "ps1" && ext == "bin" && cueNames.any { f.nameWithoutExtension.lowercase().startsWith(it) }) continue
                if (sys.key == "ps1" && m3u && ext in setOf("cue", "chd", "pbp") && Regex("(?i)\\(disc ?\\d\\)").containsMatchIn(f.name)) continue
                out += Game(sys, f)
            }
        }
        File(root, "free").walkTopDown().maxDepth(4).filter { it.isFile }.forEach { f ->
            val n = f.name.lowercase()
            if (n == "doukutsu.exe" || f.extension.lowercase() in setOf("wad", "game") || n == "pak0.pak") {
                out += Game(FREE, f)
            }
        }
        return out.sortedWith(compareBy({ SYSTEMS.indexOfFirst { s -> s.key == it.system.key }.let { i -> if (i < 0) 99 else i } }, { it.title.lowercase() }))
    }

    private fun coreOf(g: Game): String {
        if (g.system.key != "free") return g.system.core
        val n = g.file.name.lowercase()
        return FREE_CORES[n] ?: FREE_CORES[g.file.extension.lowercase()] ?: "nxengine"
    }

    fun displayTitle(g: Game): String = if (g.system.key != "free") g.title else when {
        g.file.name.equals("doukutsu.exe", true) -> "Cave Story"
        g.file.name.equals("pak0.pak", true) -> "Quake"
        else -> g.file.parentFile?.name ?: g.title
    }

    // ---------- RetroArch ----------

    /** All three RetroArch builds share the same activity names. 64-bit first: it's a little faster. */
    val PACKAGES = listOf("com.retroarch.aarch64", "com.retroarch", "com.retroarch.ra32")
    private const val GAME_ACTIVITY = "com.retroarch.browser.retroactivity.RetroActivityFuture"

    fun isEmulator(pkg: String?): Boolean = pkg != null && pkg in PACKAGES

    fun retroArch(ctx: Context): String? = PACKAGES.firstOrNull { Apps.installedVersion(ctx, it) >= 0 }

    @Volatile var status = ""
        private set
    @Volatile var busy = false
        private set

    /**
     * Downloads the official RetroArch from libretro's buildbot and installs it silently. Blocking.
     * The buildbot is often slow and drops connections: the download resumes where it stopped (and survives a
     * second press of the button), and is installed straight from the file, without a second copy.
     */
    fun installRetroArch(ctx: Context): Boolean {
        if (busy) return false
        busy = true
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        try {
            status = "Ищу последнюю версию RetroArch…"
            val is64 = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
            val names = if (is64) listOf("RetroArch_aarch64.apk", "RetroArch.apk") else listOf("RetroArch_ra32.apk", "RetroArch.apk")
            val dir = ctx.getExternalFilesDir(null) ?: ctx.cacheDir
            val apk = File(dir, "retroarch.apk")
            val prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)
            // The same address as last time, so a half-downloaded file can be continued.
            var url = prefs.getString("ra_url", null)?.takeIf { apk.exists() && apk.length() > 0 }
            if (url == null) {
                apk.delete()
                loop@ for (v in stableVersions().take(3)) for (n in names) {
                    val u = "https://buildbot.libretro.com/stable/$v/android/$n"
                    if (exists(u)) { url = u; break@loop }
                }
            }
            if (url == null) { status = "Не нашёл RetroArch на buildbot.libretro.com — проверь интернет"; return false }
            prefs.edit().putString("ra_url", url).apply()
            if (!download(url, apk)) return false
            status = "Устанавливаю RetroArch… (около минуты)"
            // pm reads the file itself: no copy through ADB, which needs the free space twice.
            val ok = Adb.shell(ctx, "pm install -r -g '${apk.absolutePath}'") || Adb.install(ctx, apk)
            if (!ok) { status = "Не удалось установить: ${Adb.lastError ?: "нет ADB — нажми «Настроить» на пульте"}"; return false }
            apk.delete()
            prefs.edit().remove("ra_url").apply()
            prepare(ctx)
            status = "RetroArch установлен"
            return true
        } catch (e: Exception) {
            status = "Ошибка: ${e.message}"
            return false
        } finally {
            busy = false
        }
    }

    /**
     * The free, freely distributable games libretro hosts for RetroArch's Content Downloader (Cave Story, Doom
     * shareware, Dinothawr…), fetched straight into Games/free and unpacked. Small files; skips anything huge.
     */
    fun downloadFree(ctx: Context): Boolean {
        if (busy) return false
        busy = true
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        try {
            prepare(ctx)
            if (!canWriteStorage()) { status = "Нет доступа к памяти: нажми «Настроить» в пункте управления"; return false }
            val base = "https://buildbot.libretro.com/assets/cores/"
            status = "Смотрю, какие бесплатные игры есть…"
            val dirs = text(base + ".index-dirs")?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: run { status = "Сервер libretro не отвечает, попробуй ещё раз"; return false }
            var got = 0
            for (d in dirs) {
                val files = text(base + enc(d) + "/.index")?.lines()?.map { it.trim() }?.filter { it.endsWith(".zip", true) } ?: continue
                for (f in files) {
                    val dest = File(root, "free/$d")
                    if (dest.exists() && (dest.list()?.isNotEmpty() == true)) { got++; continue }
                    status = "Скачиваю «$d»… (${got + 1})"
                    val zip = File(root, "free/.$f.part")
                    zip.parentFile?.mkdirs()
                    if (!fetchSmall(base + enc(d) + "/" + enc(f), zip, 60_000_000)) { zip.delete(); continue }
                    try { unzip(zip, dest); got++ } catch (_: Exception) { dest.deleteRecursively() }
                    zip.delete()
                }
            }
            val playable = list().count { it.system.key == "free" }
            status = if (playable > 0) "Готово: бесплатных игр — $playable" else "Скачал $got, но для них пока нет эмулятора в списке"
            return playable > 0
        } catch (e: Exception) {
            status = "Ошибка: ${e.message}"
            return false
        } finally {
            busy = false
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun text(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
    } catch (_: Exception) {
        null
    }

    private fun fetchSmall(url: String, to: File, max: Long): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        if (c.responseCode != 200 || c.contentLengthLong > max) false
        else { c.inputStream.use { i -> to.outputStream().use { o -> i.copyTo(o) } }; to.length() > 0 }
    } catch (_: Exception) {
        false
    }

    private fun unzip(zip: File, dest: File) {
        dest.mkdirs()
        val top = dest.canonicalPath + File.separator
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val out = File(dest, e.name)
                if (!out.canonicalPath.startsWith(top)) continue   // never outside the folder
                if (e.isDirectory) out.mkdirs()
                else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
            }
        }
    }

    private fun exists(url: String): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"
        c.connectTimeout = 15_000; c.readTimeout = 15_000
        val ok = c.responseCode == 200
        c.disconnect()
        ok
    } catch (_: Exception) {
        false
    }

    private fun stableVersions(): List<String> = try {
        val c = URL("https://buildbot.libretro.com/stable/").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 15_000
        val html = c.inputStream.bufferedReader().use { it.readText() }
        Regex("""(\d+)\.(\d+)\.(\d+)/""").findAll(html).map { it.groupValues }.distinctBy { it[0] }
            .sortedWith(compareByDescending<List<String>> { it[1].toInt() }.thenByDescending { it[2].toInt() }.thenByDescending { it[3].toInt() })
            .map { "${it[1]}.${it[2]}.${it[3]}" }.toList()
    } catch (_: Exception) {
        emptyList()
    }.ifEmpty { listOf("1.21.0", "1.20.0", "1.19.1") }

    /** Resumable download: after a stall or a dropped connection it carries on from the bytes it already has. */
    private fun download(url: String, to: File): Boolean {
        var total = -1L
        var fails = 0
        val t0 = android.os.SystemClock.elapsedRealtime()
        val start = to.length()
        while (fails < 30) {
            val have = to.length()
            if (total > 0 && have >= total) return true
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 20_000          // no data for 20 s = stalled: reconnect and continue
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                val code = c.responseCode
                val append = code == 206
                if (code != 200 && code != 206) { c.disconnect(); fails++; Thread.sleep(3000); continue }
                total = if (append) have + c.contentLengthLong else c.contentLengthLong
                val free = to.parentFile?.usableSpace ?: 0
                if (total > 0 && free in 1 until (total - have) + 300_000_000) {
                    status = "Мало места на проекторе: нужно ~${(total + 300_000_000) / 1_000_000} МБ, свободно ${free / 1_000_000} МБ"
                    c.disconnect()
                    return false
                }
                java.io.FileOutputStream(to, append).use { out ->
                    c.inputStream.use { inp ->
                        val buf = ByteArray(64 * 1024)
                        var done = if (append) have else 0L
                        var shownAt = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - shownAt > 700) {
                                shownAt = now
                                val secs = ((now - t0) / 1000.0).coerceAtLeast(1.0)
                                val speed = (done - start) / secs / 1_000_000
                                status = if (total > 0) "Скачиваю RetroArch… ${done * 100 / total}% · ${done / 1_000_000} из ${total / 1_000_000} МБ · %.1f МБ/с".format(speed)
                                         else "Скачиваю RetroArch… ${done / 1_000_000} МБ"
                            }
                        }
                    }
                }
                c.disconnect()
                if (total <= 0 || to.length() >= total) return to.length() > 1_000_000
            } catch (e: Exception) {
                fails++
                status = "Связь с сервером прервалась, продолжаю… (${to.length() / 1_000_000} МБ уже есть)"
                Thread.sleep(3000)
            }
        }
        status = "Сервер libretro не отвечает. Нажми «Установить» ещё раз — продолжу с ${to.length() / 1_000_000} МБ"
        return false
    }

    /**
     * Permissions the projector's settings can't give: our app may write the games folder, RetroArch may read it.
     * Done through ADB; safe to repeat.
     */
    fun prepare(ctx: Context) {
        val me = ctx.packageName
        Adb.shell(ctx, "appops set $me MANAGE_EXTERNAL_STORAGE allow")
        Adb.shell(ctx, "pm grant $me android.permission.READ_EXTERNAL_STORAGE")
        Adb.shell(ctx, "pm grant $me android.permission.WRITE_EXTERNAL_STORAGE")
        val ra = retroArch(ctx)
        if (ra != null) {
            Adb.shell(ctx, "pm grant $ra android.permission.READ_EXTERNAL_STORAGE")
            Adb.shell(ctx, "pm grant $ra android.permission.WRITE_EXTERNAL_STORAGE")
            Adb.shell(ctx, "appops set $ra MANAGE_EXTERNAL_STORAGE allow")
            Adb.shell(ctx, "dumpsys deviceidle whitelist +$ra")
        }
        if (canWriteStorage()) {
            for (s in SYSTEMS) File(root, s.key).mkdirs()
            File(root, "free").mkdirs()
        }
    }

    private val cfgFile: File get() = File(root, ".retroarch.cfg")

    /**
     * Settings forced on every start, on top of whatever RetroArch saved itself: the phone gamepad's buttons,
     * no on-screen touch overlay, the light menu, network commands (for Menu / Save / Quit from the phone),
     * and saves every 10 seconds so nothing is lost when a game is closed from the phone.
     */
    private fun forced(ra: String): Map<String, String> = linkedMapOf(
        // Phone gamepad ("Projector Pad"): Android keycodes. RetroPad B = ✕ (south), A = ○, Y = □, X = △.
        "input_player1_b_btn" to "96", "input_player1_a_btn" to "97",
        "input_player1_y_btn" to "99", "input_player1_x_btn" to "100",
        "input_player1_l_btn" to "102", "input_player1_r_btn" to "103",
        "input_player1_l2_btn" to "104", "input_player1_r2_btn" to "105",
        "input_player1_select_btn" to "109", "input_player1_start_btn" to "108",
        "input_player1_up_btn" to "19", "input_player1_down_btn" to "20",
        "input_player1_left_btn" to "21", "input_player1_right_btn" to "22",
        "input_menu_toggle_btn" to "110",
        "input_menu_toggle_gamepad_combo" to "4",           // Start + Select also opens the menu
        // Same buttons as keys, for firmware without UHID.
        "input_player1_b" to "z", "input_player1_a" to "x", "input_player1_y" to "a", "input_player1_x" to "s",
        "input_player1_l" to "q", "input_player1_r" to "w", "input_player1_l2" to "e", "input_player1_r2" to "r",
        "input_player1_start" to "enter", "input_player1_select" to "rshift",
        "input_player1_up" to "up", "input_player1_down" to "down", "input_player1_left" to "left", "input_player1_right" to "right",
        "input_menu_toggle" to "f1",
        "input_overlay_enable" to "false",
        "input_autodetect_enable" to "true",
        "menu_driver" to "ozone",
        "video_threaded" to "true",
        "video_smooth" to "false",
        "network_cmd_enable" to "true",
        "network_cmd_port" to "55355",
        "autosave_interval" to "10",
        "savestate_auto_save" to "true",
        "savestate_auto_load" to "true",
        "quit_press_twice" to "false",
        "confirm_quit" to "false",
        "pause_nonactive" to "true",
        "config_save_on_exit" to "true",
        "core_assets_directory" to File(root, "free").absolutePath,
        "rgui_browser_directory" to root.absolutePath,
        "libretro_directory" to "/data/data/$ra/cores",
    )

    private fun writeConfig(ra: String) {
        val lines = if (cfgFile.exists()) cfgFile.readLines().toMutableList() else ArrayList<String>()
        val want = forced(ra)
        val seen = HashSet<String>()
        for (i in lines.indices) {
            val k = lines[i].substringBefore('=').trim()
            want[k]?.let { lines[i] = "$k = \"$it\""; seen += k }
        }
        for ((k, v) in want) if (k !in seen) lines += "$k = \"$v\""
        cfgFile.writeText(lines.joinToString("\n") + "\n")
    }

    /** Opens RetroArch's own menu (to download the emulator cores or the free games once). */
    fun openRetroArch(ctx: Context): Boolean {
        val ra = retroArch(ctx) ?: return false
        val i = ctx.packageManager.getLaunchIntentForPackage(ra) ?: return false
        return try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
    }

    @Volatile var current: String? = null
        private set

    /** Starts a game straight away in RetroArch with the right core. Call from a background thread. */
    fun launch(ctx: Context, g: Game): Boolean {
        val ra = retroArch(ctx) ?: return false
        prepare(ctx)
        try { writeConfig(ra) } catch (_: Exception) {}
        // One game at a time: a RetroArch that's already running would ignore the new game.
        Adb.shell(ctx, "am force-stop $ra")
        val info = try { ctx.packageManager.getApplicationInfo(ra, 0) } catch (_: Exception) { return false }
        val sd = Environment.getExternalStorageDirectory().absolutePath
        val ime = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val i = Intent().setComponent(ComponentName(ra, GAME_ACTIVITY))
            .putExtra("ROM", g.file.absolutePath)
            .putExtra("LIBRETRO", "/data/data/$ra/cores/${coreOf(g)}_libretro_android.so")
            .putExtra("CONFIGFILE", cfgFile.absolutePath)
            .putExtra("IME", ime)
            .putExtra("DATADIR", info.dataDir)
            .putExtra("APK", info.sourceDir)
            .putExtra("SDCARD", sd)
            .putExtra("EXTERNAL", "$sd/Android/data/$ra/files")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return try {
            ctx.startActivity(i)
            current = displayTitle(g)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Sends one of RetroArch's network commands (MENU_TOGGLE, SAVE_STATE, LOAD_STATE, RESET, QUIT…). */
    fun command(cmd: String): Boolean = try {
        java.net.DatagramSocket().use { s ->
            val b = cmd.toByteArray()
            s.send(java.net.DatagramPacket(b, b.size, java.net.InetAddress.getLoopbackAddress(), 55355))
        }
        true
    } catch (_: Exception) {
        false
    }

    /** Leaves the game: RetroArch saves and quits by itself; if it doesn't, it's closed. Back to the game list. */
    fun quit(ctx: Context) {
        val ra = retroArch(ctx)
        command("QUIT")
        Thread.sleep(2500)
        if (ra != null && Apps.foreground(ctx) == ra) Adb.shell(ctx, "am force-stop $ra")
        current = null
        openList(ctx)
    }

    fun openList(ctx: Context): Boolean = try {
        ctx.startActivity(Intent(ctx, GamesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        true
    } catch (_: Exception) {
        false
    }

    /** "ps1/Crash.chd" → the game, only inside the games folder. */
    fun find(path: String): Game? = list().firstOrNull { rel(it) == path }

    fun rel(g: Game): String = g.file.absolutePath.removePrefix(root.absolutePath + "/")

    /** Where an uploaded file goes. The name is cleaned so it can't leave the folder. */
    fun target(sys: GameSystem, name: String): File {
        val clean = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").trim().trimStart('.').ifEmpty { "game" }
        val dir = File(root, sys.key).apply { mkdirs() }
        return File(dir, clean)
    }
}
