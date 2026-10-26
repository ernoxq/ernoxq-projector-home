package md.grin.projector

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * Runs on the projector as a shell-level process (started once through ADB with app_process,
 * the same trick scrcpy uses). It reads key codes from stdin and injects them straight into Android's
 * input system, so a phone swipe turns into a key press in a few milliseconds instead of starting
 * the slow `input` tool for every press.
 */
object KeyServer {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val imClass = Class.forName("android.hardware.input.InputManager")
            val im = imClass.getMethod("getInstance").invoke(null)
            val inject = imClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            println("keyserver ready")
            System.out.flush()
            val reader = System.`in`.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("t ")) {
                    // Text line: deliver the characters directly to the focused field.
                    val text = java.net.URLDecoder.decode(line.substring(2), "UTF-8")
                    val ev = KeyEvent(SystemClock.uptimeMillis(), text, KeyCharacterMap.VIRTUAL_KEYBOARD, 0)
                    ev.source = InputDevice.SOURCE_KEYBOARD
                    inject.invoke(im, ev, 0)
                    continue
                }
                val code = line.trim().toIntOrNull() ?: continue
                val now = SystemClock.uptimeMillis()
                val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
                val up = KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
                inject.invoke(im, down, 0)
                inject.invoke(im, up, 0)
            }
        } catch (e: Throwable) {
            System.err.println("keyserver failed: $e")
            System.exit(1)
        }
    }
}
