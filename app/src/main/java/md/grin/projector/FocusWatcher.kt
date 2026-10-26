package md.grin.projector

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Notices when a text field gets focus in any app on the projector (YouTube search, a browser
 * address bar…), so the phone remote can pop up its keyboard bar. It can also put text straight
 * into that field, letter by letter, in any language.
 */
class FocusWatcher : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val src = e.source
                typing = src?.isEditable == true
                text = if (typing) src?.text?.toString() ?: "" else ""
                if (typing) changedAt = System.currentTimeMillis()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // A new screen: re-check what has focus there.
                val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                typing = f?.isEditable == true
                if (!typing) text = ""
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: FocusWatcher? = null
        @Volatile var typing = false
        @Volatile var text = ""
        @Volatile var changedAt = 0L

        val enabled: Boolean get() = instance != null

        /** Replaces the focused field's text. Returns false if there's no editable field in focus. */
        fun setText(value: String): Boolean {
            val svc = instance ?: return false
            val node = svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            if (!node.isEditable) return false
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok) text = value
            return ok
        }
    }
}
