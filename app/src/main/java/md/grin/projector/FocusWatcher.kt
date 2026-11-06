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

    /**
     * The firmware's "Version update — NfxAccessibilityService has detected a new version update" nag:
     * its package is switched off, and should it (or a look-alike from another app) still pop up,
     * it's closed right away with its own Cancel button, and that app is switched off too.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return
        val hint = e.text.joinToString(" ") + " " + (e.className ?: "")
        if (!hint.contains("update", true) && !hint.contains("Dialog", true) && !hint.contains("обновлен", true)) return
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        val nag = listOf("detected a new version", "Version update").any { root.findAccessibilityNodeInfosByText(it).isNotEmpty() }
        if (!nag) return
        val cancel = listOf("Cancel", "Отмена", "Later", "Позже").firstNotNullOfOrNull { label ->
            root.findAccessibilityNodeInfosByText(label).firstNotNullOfOrNull { n ->
                var c: android.view.accessibility.AccessibilityNodeInfo? = n
                while (c != null && !c.isClickable) c = c.parent
                c
            }
        }
        if (cancel == null || !cancel.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        if (pkg !in KEEP) {
            val prefs = getSharedPreferences("home", MODE_PRIVATE)
            val set = prefs.getStringSet("quieted", emptySet())!!.toMutableSet()
            if (set.add(pkg)) {
                prefs.edit().putStringSet("quieted", set).apply()
                val app = applicationContext
                Thread { Adb.shell(app, "pm disable-user --user 0 $pkg") }.start()
            }
        }
    }

    private companion object {
        /** Never switched off, whatever they show. */
        val KEEP = setOf("android", "com.android.systemui", "com.android.settings", "com.android.tv.settings", "com.android.packageinstaller", "com.google.android.packageinstaller")
    }
    override fun onInterrupt() {}
}
