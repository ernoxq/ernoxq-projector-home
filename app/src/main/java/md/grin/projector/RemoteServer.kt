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
    private val pool = Executors.newFixedThreadPool(4)
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
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
            }
            val target = request.split(" ").getOrNull(1) ?: "/"
            val uri = Uri.parse("http://p$target")
            val out = sock.getOutputStream()
            try {
                route(uri, out)
            } catch (e: Exception) {
                send(out, 500, "application/json", json("ok" to false, "error" to (e.message ?: "error")).toByteArray())
            }
        }
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
        when (uri.path ?: "/") {
            "/", "/index.html" -> {
                val html = ctx.assets.open("remote.html").use { it.readBytes() }
                send(out, 200, "text/html; charset=utf-8", html)
            }
            "/api/state" -> ok(out, state())
            "/api/key" -> ok(out, json("ok" to Control.key(ctx, q("k"))))
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
            "/api/text" -> ok(out, json("ok" to Control.typeText(ctx, q("t"))))
            // Live typing: replaces the focused field's text as the phone keyboard types
            "/api/settext" -> ok(out, json("ok" to (TvKeyboard.setText(q("t")) || Control.typeText(ctx, q("t")))))
            "/api/submit" -> ok(out, json("ok" to (TvKeyboard.submit() || Control.key(ctx, "enter"))))
            "/api/setup" -> ok(out, json("ok" to Adb.setup(ctx)))
            "/api/bright" -> ok(out, json("ok" to Picture.set(ctx, q("on") == "1"), "on" to Picture.isOn(ctx)))
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
            .put("bright", Picture.isOn(ctx))
            .put("warm", Picture.isWarm(ctx))
        if (np != null) {
            o.put("media", JSONObject()
                .put("title", np.title)
                .put("artist", np.artist)
                .put("source", np.source)
                .put("playing", np.playing)
                .put("position", np.position)
                .put("duration", np.duration)
                .put("art", np.art != null))
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
