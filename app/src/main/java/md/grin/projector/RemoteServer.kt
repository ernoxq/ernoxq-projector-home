package md.grin.projector

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * A tiny web server on the projector. The phone opens http://<projector-ip>:8765 and gets the remote page;
 * the buttons on it call the /api/… endpoints below. Nothing leaves the home Wi-Fi.
 */
class RemoteServer(private val ctx: Context) : Thread("remote-server") {
    private val pool = Executors.newFixedThreadPool(6)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var socket: ServerSocket? = null

    override fun run() {
        try {
            val ss = ServerSocket(PORT)
            ss.reuseAddress = true
            socket = ss
            while (!isInterrupted) {
                val s = ss.accept()
                pool.execute { handle(s) }
            }
        } catch (_: Exception) {
        }
    }

    fun close() {
        interrupt()
        try { socket?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun handle(s: Socket) {
        s.use { sock ->
            sock.soTimeout = 10_000
            val input = BufferedInputStream(sock.getInputStream())
            val request = readLine(input) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
                val i = h.indexOf(':')
                if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
            }
            val target = request.split(" ").getOrNull(1) ?: "/"
            val uri = Uri.parse("http://p$target")
            val out = sock.getOutputStream()
            try {
                when {
                    uri.path == "/pad/ws" && headers["upgrade"].equals("websocket", true) -> padSocket(sock, input, out, headers["sec-websocket-key"] ?: return)
                    request.startsWith("POST ") && uri.path == "/api/upload" -> upload(uri, input, out, headers["content-length"]?.toLongOrNull() ?: -1)
                    else -> route(uri, out)
                }
            } catch (e: Exception) {
                send(out, 500, "application/json", json("ok" to false, "error" to (e.message ?: "error")).toByteArray())
            }
        }
    }

    // ---------- phone gamepad: one WebSocket, each message is the pad's whole state ----------

    private fun padSocket(sock: Socket, input: BufferedInputStream, out: OutputStream, key: String) {
        val accept = android.util.Base64.encodeToString(
            java.security.MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC11B92").toByteArray()),
            android.util.Base64.NO_WRAP,
        )
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
        out.flush()
        sock.soTimeout = 0
        sock.tcpNoDelay = true
        var touched = 0L
        try {
            while (true) {
                val b0 = input.read()
                val b1 = input.read()
                if (b0 < 0 || b1 < 0) break
                val op = b0 and 0x0F
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = ((input.read() shl 8) or input.read()).toLong()
                else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or input.read().toLong() } }
                if (len > 4096) break
                val mask = if (b1 and 0x80 != 0) ByteArray(4) { input.read().toByte() } else null
                val data = ByteArray(len.toInt())
                var got = 0
                while (got < data.size) { val n = input.read(data, got, data.size - got); if (n < 0) break; got += n }
                if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (op) {
                    0x8 -> break
                    0x9 -> { out.write(byteArrayOf(0x8A.toByte(), data.size.toByte()) + data); out.flush() }
                    0x1 -> {
                        val msg = String(data, Charsets.UTF_8)
                        if (msg.startsWith("p ")) {
                            val p = msg.split(' ')
                            padInput(p.getOrNull(1)?.toIntOrNull() ?: 0, p.getOrNull(2)?.toIntOrNull() ?: 0, p.getOrNull(3)?.toIntOrNull() ?: 0)
                        }
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - touched > 5000) { touched = now; SleepTimer.touch() }
                    }
                }
            }
        } finally {
            // Phone locked or page closed: let go of every button so nothing stays pressed.
            padInput(0, 0, 0)
        }
    }

    @Volatile private var padBits = 0
    @Volatile private var padLive = false

    /**
     * Inside a game the phone is a real gamepad. Everywhere else (home screen, game list) its D-pad, ✕ and ○
     * are plain remote keys: no virtual controller is plugged into Android until a game is on screen.
     */
    @Synchronized
    private fun padInput(bits: Int, x: Int, y: Int) {
        // Our own built-in game: the buttons go straight in, same process.
        if (GamePad.active) {
            GamePad.phone = bits
            padBits = bits
            return
        }
        val inGame = Games.isEmulator(foreground())
        if (inGame) {
            padLive = true
            Adb.pad(ctx, bits, x, y)
        } else {
            if (padLive) { padLive = false; Adb.pad(ctx, 0) }
            val pressed = bits and padBits.inv()
            for ((bit, k) in NAV) if (pressed and (1 shl bit) != 0) Control.key(ctx, k)
        }
        padBits = bits
    }

    private val NAV = listOf(13 to "up", 14 to "down", 16 to "left", 15 to "right", 0 to "ok", 11 to "ok", 1 to "back")

    // ---------- games uploaded from the phone: the raw file is the request body ----------

    private fun upload(uri: Uri, input: BufferedInputStream, out: OutputStream, length: Long) {
        val name = uri.getQueryParameter("name") ?: ""
        val sys = Games.system(uri.getQueryParameter("sys") ?: "") ?: Games.guess(name)
        if (sys == null || sys.key == "free") return send(out, 400, "application/json", json("ok" to false, "error" to "Не понял, для какой это приставки").toByteArray())
        if (length <= 0) return send(out, 411, "application/json", json("ok" to false, "error" to "Нет размера файла").toByteArray())
        if (!Games.canWriteStorage()) Games.prepare(ctx)
        if (!Games.canWriteStorage()) return send(out, 403, "application/json", json("ok" to false, "error" to "Нет доступа к памяти проектора: нажми «Настроить» в пункте управления").toByteArray())
        val file = Games.target(sys, name)
        val free = Games.root.usableSpace
        if (free in 1 until length + 50_000_000) return send(out, 507, "application/json", json("ok" to false, "error" to "Мало места: свободно ${free / 1_000_000} МБ").toByteArray())
        val part = java.io.File(file.parentFile, ".${file.name}.part")
        try {
            part.outputStream().buffered(256 * 1024).use { o ->
                val buf = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw java.io.IOException("связь прервалась")
                    o.write(buf, 0, n)
                    left -= n
                    SleepTimer.touch()
                }
            }
            file.delete()
            if (!part.renameTo(file)) throw java.io.IOException("не удалось сохранить")
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        ok(out, json("ok" to true, "sys" to sys.key, "file" to file.name))
    }

    private fun gamesJson(): String {
        val arr = JSONArray()
        for (g in Games.list()) arr.put(JSONObject()
            .put("path", Games.rel(g)).put("title", Games.displayTitle(g))
            .put("sys", g.system.key).put("system", g.system.title)
            .put("mb", g.file.length() / 1_000_000))
        val systems = JSONArray()
        for (s in Games.SYSTEMS) systems.put(JSONObject().put("key", s.key).put("title", s.title).put("exts", JSONArray(s.exts.toList())))
        return JSONObject().put("games", arr).put("systems", systems)
            .put("retroarch", Games.retroArch(ctx) != null).put("busy", Games.busy).put("status", Games.status)
            .put("storage", Games.canWriteStorage()).put("playing", Games.playingBuiltin ?: Games.current ?: "")
            .put("builtin", JSONArray().also { a -> MiniGame.all().forEach { g -> a.put(JSONObject().put("key", g.key).put("title", g.title).put("hint", g.hint)) } })
            .put("free", Games.root.usableSpace.takeIf { it > 0 } ?: android.os.Environment.getExternalStorageDirectory().usableSpace)
            .toString()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            buf.write(b)
            if (buf.size() > 8192) return buf.toString("UTF-8")
        }
    }

    private fun route(uri: Uri, out: OutputStream) {
        val q = { k: String -> uri.getQueryParameter(k) ?: "" }
        val path = uri.path ?: "/"
        if (path.startsWith("/api/") && path != "/api/state" && path != "/api/art") SleepTimer.touch()
        when (uri.path ?: "/") {
            "/", "/index.html" -> {
                val html = ctx.assets.open("remote.html").use { it.readBytes() }
                send(out, 200, "text/html; charset=utf-8", html)
            }
            "/icon.png", "/icon-512.png", "/apple-touch-icon.png" -> {
                val name = if (uri.path == "/icon-512.png") "icon-512.png" else "icon.png"
                send(out, 200, "image/png", ctx.assets.open(name).use { it.readBytes() })
            }
            "/pad", "/pad.html" -> send(out, 200, "text/html; charset=utf-8", ctx.assets.open("pad.html").use { it.readBytes() })
            "/api/games" -> ok(out, gamesJson())
            // Game mode from the phone's 🎮 button: the game list on the projector.
            "/api/games/open" -> {
                var r = false
                onMain { r = Games.openList(ctx) }
                ok(out, json("ok" to r))
            }
            "/api/games/play" -> {
                if (q("game").isNotEmpty()) return ok(out, json("ok" to Games.playBuiltin(ctx, q("game"))))
                val g = Games.find(q("path"))
                ok(out, json("ok" to (g != null && Games.launch(ctx, g))))
            }
            "/api/games/cmd" -> {
                val c = q("c")
                val r = when {
                    GamePad.active && c == "quit" -> { GamePad.exitRequest = true; true }
                    GamePad.active && c == "MENU_TOGGLE" -> { GamePad.menuRequest = true; true }
                    else -> false
                } || when (c) {
                    "quit" -> { Thread { Games.quit(ctx) }.start(); true }
                    "MENU_TOGGLE", "SAVE_STATE", "LOAD_STATE", "RESET", "PAUSE_TOGGLE" -> Games.command(c)
                    else -> false
                }
                ok(out, json("ok" to r))
            }
            "/api/games/delete" -> {
                val g = Games.find(q("path"))
                ok(out, json("ok" to (g != null && g.file.delete())))
            }
            "/api/games/install" -> {
                if (!Games.busy) Thread { Games.installRetroArch(ctx) }.start()
                ok(out, json("ok" to true))
            }
            "/api/games/free" -> {
                if (!Games.busy) Thread { Games.downloadFree(ctx) }.start()
                ok(out, json("ok" to true))
            }
            "/api/games/retroarch" -> {
                var r = false
                onMain { r = Games.openRetroArch(ctx) }
                ok(out, json("ok" to r))
            }
            // HTTP fallback for the gamepad when WebSockets are blocked.
            "/api/pad" -> { padInput(q("b").toIntOrNull() ?: 0, 0, 0); ok(out, json("ok" to true)) }
            "/api/state" -> ok(out, state())
            "/api/key" -> ok(out, json("ok" to (Aerials.remote(q("k")) || Control.key(ctx, q("k")))))
            // A finger on the phone's touchpad: brings the screensaver's place name back.
            "/api/touch" -> ok(out, json("ok" to Aerials.remote("touch")))
            "/api/vol" -> {
                val r = if (q("set").isNotEmpty()) Control.setVolume(ctx, q("set").toIntOrNull() ?: 0) else Control.volume(ctx, q("d"))
                ok(out, json("ok" to r))
            }
            "/api/media" -> {
                val r = Media.command(q("c")) || (q("c") == "play" && Control.key(ctx, "playpause"))
                ok(out, json("ok" to r))
            }
            "/api/launch" -> {
                var r = false
                onMain { r = Apps.launchKey(ctx, q("app")) }
                ok(out, json("ok" to r))
            }
            "/api/ytsearch" -> {
                var r = false
                onMain { r = Apps.searchYoutube(ctx, q("q")) }
                ok(out, json("ok" to r))
            }
            "/api/text" -> ok(out, json("ok" to Control.typeText(ctx, q("t"))))
            // Live typing: replaces the focused field's text as the phone keyboard types
            "/api/settext" -> ok(out, json("ok" to (TvKeyboard.setText(q("t")) || Control.typeText(ctx, q("t")))))
            "/api/submit" -> ok(out, json("ok" to (TvKeyboard.submit() || Control.key(ctx, "enter"))))
            "/api/setup" -> ok(out, json("ok" to Adb.setup(ctx)))
            "/api/brightness" -> ok(out, json("ok" to Picture.setBrightness(ctx, q("v").toIntOrNull() ?: 100)))
            "/api/sleep" -> ok(out, json("ok" to SleepTimer.setOn(ctx, q("on") == "1")))
            "/api/sleeptest" -> ok(out, json("ok" to SleepTimer.test(ctx, 40)))
            "/api/aerial" -> ok(out, json("ok" to Aerials.show(ctx)))
            "/api/switcher" -> {
                var r = false
                onMain { r = SwitcherActivity.show(ctx) }
                ok(out, json("ok" to r))
            }
            // Open in Safari: what takes the projector's memory and processor.
            "/api/diag" -> send(out, 200, "text/plain; charset=utf-8", Adb.diagnostics(ctx).toByteArray())
            "/api/npcard" -> ok(out, json("ok" to Aerials.setCard(ctx, q("on") == "1")))
            "/api/warmth" -> ok(out, json("ok" to Picture.setWarmth(ctx, q("v").toIntOrNull() ?: 50)))
            "/api/warm" -> ok(out, json("ok" to Picture.setWarm(ctx, q("on") == "1"), "on" to Picture.isWarm(ctx)))
            "/api/update" -> {
                Thread { Updater.check(ctx) }.start()
                ok(out, json("ok" to true, "version" to "1.0.${Updater.currentBuild(ctx)}"))
            }
            "/api/apps" -> {
                val arr = JSONArray()
                Apps.all(ctx).forEach { arr.put(JSONObject().put("label", it.label).put("pkg", it.pkg)) }
                ok(out, JSONObject().put("apps", arr).toString())
            }
            "/api/art" -> {
                val art = Media.current(ctx)?.art
                if (art == null) send(out, 404, "text/plain", ByteArray(0))
                else {
                    val bos = ByteArrayOutputStream()
                    Bitmap.createScaledBitmap(art, 300, 300 * art.height / art.width.coerceAtLeast(1), true)
                        .compress(Bitmap.CompressFormat.JPEG, 85, bos)
                    send(out, 200, "image/jpeg", bos.toByteArray())
                }
            }
            else -> send(out, 404, "text/plain", "not found".toByteArray())
        }
    }

    @Volatile private var fgPkg = ""
    @Volatile private var fgAt = 0L
    @Volatile private var fgBusy = false

    private fun foreground(): String {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!fgBusy && now - fgAt > 2500) {
            fgBusy = true
            Thread {
                try { fgPkg = if (App.isForeground) ctx.packageName else Apps.foreground(ctx) ?: "" } finally {
                    fgAt = android.os.SystemClock.elapsedRealtime(); fgBusy = false
                }
            }.start()
        }
        return fgPkg
    }

    private fun state(): String {
        val (vol, muted) = Control.volumeState(ctx)
        val np = Media.current(ctx)
        val o = JSONObject()
            .put("volume", vol)
            .put("muted", muted)
            .put("adb", Adb.connected)
            .put("mediaAccess", Media.hasAccess(ctx))
            .put("typing", TvKeyboard.active)
            .put("typingAt", TvKeyboard.changedAt)
            .put("text", TvKeyboard.text)
            .put("brightness", Picture.brightness(ctx))
            .put("warmth", Picture.warmth(ctx))
            .put("sleep", SleepTimer.isOn(ctx))
            .put("fg", foreground())
            .put("warm", Picture.isWarm(ctx))
            .put("npCard", Aerials.cardOn(ctx))
            .put("aerial", Aerials.showing)
            .put("version", "1.0.${Updater.currentBuild(ctx)}")
            .put("updateStatus", Updater.status)
        if (np != null) {
            o.put("media", JSONObject()
                .put("title", np.title)
                .put("artist", np.artist)
                .put("source", np.source)
                .put("playing", np.playing)
                .put("position", np.position)
                .put("duration", np.duration)
                .put("art", np.art != null)
                .put("artId", np.art?.let { System.identityHashCode(it) } ?: 0))
        }
        return o.toString()
    }

    private fun onMain(block: () -> Unit) {
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post {
            try { block() } finally { latch.countDown() }
        }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun ok(out: OutputStream, body: String) = send(out, 200, "application/json; charset=utf-8", body.toByteArray())

    private fun json(vararg pairs: Pair<String, Any>): String {
        val o = JSONObject()
        pairs.forEach { o.put(it.first, it.second) }
        return o.toString()
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val status = when (code) { 200 -> "OK"; 404 -> "Not Found"; else -> "Error" }
        val head = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    companion object {
        const val PORT = 8765

        /** The projector's address on the home Wi-Fi, for showing the remote link. */
        fun address(): String? {
            try {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (a in ni.inetAddresses) {
                        if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
                    }
                }
            } catch (_: Exception) {}
            return null
        }

        fun url(): String = address()?.let { "http://$it:$PORT" } ?: "нет Wi-Fi"
    }
}
