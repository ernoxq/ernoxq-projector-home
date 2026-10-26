package md.grin.projector

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

/** Everything the phone remote can do, in one place. */
object Control {
    private val keys = mapOf(
        "up" to KeyEvent.KEYCODE_DPAD_UP,
        "down" to KeyEvent.KEYCODE_DPAD_DOWN,
        "left" to KeyEvent.KEYCODE_DPAD_LEFT,
        "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "ok" to KeyEvent.KEYCODE_DPAD_CENTER,
        "back" to KeyEvent.KEYCODE_BACK,
        "home" to KeyEvent.KEYCODE_HOME,
        "menu" to KeyEvent.KEYCODE_MENU,
        "power" to KeyEvent.KEYCODE_POWER,
        "playpause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        "del" to KeyEvent.KEYCODE_DEL,
        "enter" to KeyEvent.KEYCODE_ENTER,
    )

    fun key(ctx: Context, name: String): Boolean {
        if (name == "home") return Apps.goHome(ctx)
        if (name == "power") return Adb.powerOff(ctx, "кнопка на айфоне")
        val code = keys[name] ?: return false
        return Adb.key(ctx, code)
    }

    /**
     * Types text from the phone into whatever field is focused on the projector (browser address bar, search…).
     * Any language works: the text goes through the clipboard and is pasted, since `input text` only knows Latin.
     */
    fun typeText(ctx: Context, text: String): Boolean {
        if (text.isEmpty()) return false
        if (TvKeyboard.insert(text)) return true
        val fg = Apps.foreground(ctx)
        if (fg != null && fg.contains("youtube")) return Apps.searchYoutube(ctx, text)
        if (Adb.chars(ctx, text)) return true
        val latch = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("remote", text))
            } catch (_: Exception) {
            } finally {
                latch.countDown()
            }
        }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        if (Adb.key(ctx, KeyEvent.KEYCODE_PASTE)) return true
        return text.all { it.code < 128 } && Adb.text(ctx, text)
    }

    fun volume(ctx: Context, dir: String): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return false
        val adj = when (dir) {
            "up" -> AudioManager.ADJUST_RAISE
            "down" -> AudioManager.ADJUST_LOWER
            "mute" -> AudioManager.ADJUST_TOGGLE_MUTE
            else -> return false
        }
        val hud = VolumeHud.available(ctx)
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, adj, if (hud) 0 else AudioManager.FLAG_SHOW_UI)
        if (hud) showHud(ctx)
        return true
    }

    fun setVolume(ctx: Context, percent: Int): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return false
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val hud = VolumeHud.available(ctx)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (percent.coerceIn(0, 100) * max + 50) / 100, if (hud) 0 else AudioManager.FLAG_SHOW_UI)
        if (hud) showHud(ctx)
        return true
    }

    @Volatile var lastOwnChange = 0L

    private fun showHud(ctx: Context) {
        lastOwnChange = android.os.SystemClock.elapsedRealtime()
        val (v, muted) = volumeState(ctx)
        VolumeHud.show(ctx, v, muted)
    }

    fun volumeState(ctx: Context): Pair<Int, Boolean> {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return 0 to false
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val v = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return (v * 100 / max) to am.isStreamMute(AudioManager.STREAM_MUSIC)
    }
}
