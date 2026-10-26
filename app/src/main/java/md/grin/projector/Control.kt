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
        val code = keys[name] ?: return false
        return Adb.key(ctx, code)
    }

    fun volume(ctx: Context, dir: String): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return false
        val adj = when (dir) {
            "up" -> AudioManager.ADJUST_RAISE
            "down" -> AudioManager.ADJUST_LOWER
            "mute" -> AudioManager.ADJUST_TOGGLE_MUTE
            else -> return false
        }
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, adj, AudioManager.FLAG_SHOW_UI)
        return true
    }

    fun setVolume(ctx: Context, percent: Int): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return false
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (percent.coerceIn(0, 100) * max + 50) / 100, AudioManager.FLAG_SHOW_UI)
        return true
    }

    fun volumeState(ctx: Context): Pair<Int, Boolean> {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return 0 to false
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val v = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return (v * 100 / max) to am.isStreamMute(AudioManager.STREAM_MUSIC)
    }
}
