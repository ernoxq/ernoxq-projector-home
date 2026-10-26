package md.grin.projector

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Catches the projector remote's volume keys so our Apple TV-style volume capsule shows
 * instead of the stock panel. It asks for no other events, so apps don't do extra work for it.
 */
class FocusWatcher : AccessibilityService() {
    override fun onKeyEvent(event: KeyEvent): Boolean {
        SleepTimer.touch()
        val dir = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> "up"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "down"
            KeyEvent.KEYCODE_VOLUME_MUTE -> "mute"
            else -> return false
        }
        if (!VolumeHud.available(this)) return false
        if (event.action == KeyEvent.ACTION_DOWN) Control.volume(this, dir)
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
