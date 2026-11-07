package md.grin.projector

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import java.io.FileDescriptor
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs on the projector as a shell-level process (started once through ADB with app_process,
 * the same trick scrcpy uses). It reads key codes from stdin and injects them straight into Android's
 * input system, so a phone swipe turns into a key press in a few milliseconds instead of starting
 * the slow `input` tool for every press.
 *
 * Lines it understands:
 *  - `<code>`            press and release one key
 *  - `t <urlencoded>`    type text into the focused field
 *  - `p <bits> <x> <y>`  the phone gamepad's full state (see [Pad])
 */
object KeyServer {
    private lateinit var im: Any
    private lateinit var inject: Method

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val imClass = Class.forName("android.hardware.input.InputManager")
            im = imClass.getMethod("getInstance").invoke(null)!!
            inject = imClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
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
                if (line.startsWith("p ")) {
                    val parts = line.split(' ')
                    Pad.set(
                        parts.getOrNull(1)?.toIntOrNull() ?: 0,
                        parts.getOrNull(2)?.toIntOrNull() ?: 0,
                        parts.getOrNull(3)?.toIntOrNull() ?: 0,
                    )
                    continue
                }
                val code = line.trim().toIntOrNull() ?: continue
                key(KeyEvent.ACTION_DOWN, code)
                key(KeyEvent.ACTION_UP, code)
            }
        } catch (e: Throwable) {
            System.err.println("keyserver failed: $e")
            System.exit(1)
        }
    }

    internal fun key(action: Int, code: Int) {
        val now = SystemClock.uptimeMillis()
        val ev = KeyEvent(now, now, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        inject.invoke(im, ev, 0)
    }

    /**
     * The phone gamepad. It shows up on the projector as a real USB-style gamepad ("Projector Pad") made with
     * Linux UHID, so emulators and Android games see an ordinary controller, with buttons held as long as the
     * finger stays on them. If this firmware has no UHID, the same buttons are sent as keyboard keys instead
     * (RetroArch's default keyboard layout).
     *
     * State bits, in the order of the HID report:
     *  0 ✕ (south)  1 ○ (east)  2 —  3 □ (west)  4 △ (north)  5 —  6 L1  7 R1  8 L2  9 R2
     *  10 Select  11 Start  12 Mode  13 Up  14 Down  15 Right  16 Left
     */
    object Pad {
        private const val UHID_DESTROY = 1
        private const val UHID_CREATE2 = 11
        private const val UHID_INPUT2 = 12
        private const val BUS_VIRTUAL = 0x06

        private val DESCRIPTOR = byteArrayOf(
            0x05, 0x01,                   // Usage Page (Generic Desktop)
            0x09, 0x05,                   // Usage (Game Pad)
            0xA1.toByte(), 0x01,          // Collection (Application)
            0x05, 0x09,                   //   Usage Page (Button)
            0x19, 0x01, 0x29, 0x0D,       //   Usage 1..13 → BTN_A … BTN_MODE
            0x15, 0x00, 0x25, 0x01,       //   0..1
            0x75, 0x01, 0x95.toByte(), 0x0D,
            0x81.toByte(), 0x02,          //   Input (Data, Var, Abs)
            0x05, 0x01,                   //   Usage Page (Generic Desktop)
            0x09, 0x90.toByte(), 0x09, 0x91.toByte(), 0x09, 0x92.toByte(), 0x09, 0x93.toByte(), // D-pad Up, Down, Right, Left
            0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95.toByte(), 0x04,
            0x81.toByte(), 0x02,
            0x75, 0x01, 0x95.toByte(), 0x07,
            0x81.toByte(), 0x03,          //   7 bits of padding
            0x09, 0x30, 0x09, 0x31,       //   X, Y (left stick)
            0x15, 0x81.toByte(), 0x25, 0x7F, // -127..127
            0x75, 0x08, 0x95.toByte(), 0x02,
            0x81.toByte(), 0x02,
            0xC0.toByte(),                // End Collection
        )

        private var fd: FileDescriptor? = null
        private var noUhid = false
        private var lastBits = 0

        // Keyboard fallback: RetroArch's default keys (✕=Z, ○=X, □=A, △=S, L/R=Q/W, L2/R2=E/R, Start=Enter, Select=Right Shift).
        private val KEYS = mapOf(
            0 to KeyEvent.KEYCODE_Z, 1 to KeyEvent.KEYCODE_X, 3 to KeyEvent.KEYCODE_A, 4 to KeyEvent.KEYCODE_S,
            6 to KeyEvent.KEYCODE_Q, 7 to KeyEvent.KEYCODE_W, 8 to KeyEvent.KEYCODE_E, 9 to KeyEvent.KEYCODE_R,
            10 to KeyEvent.KEYCODE_SHIFT_RIGHT, 11 to KeyEvent.KEYCODE_ENTER, 12 to KeyEvent.KEYCODE_F1,
            13 to KeyEvent.KEYCODE_DPAD_UP, 14 to KeyEvent.KEYCODE_DPAD_DOWN, 15 to KeyEvent.KEYCODE_DPAD_RIGHT, 16 to KeyEvent.KEYCODE_DPAD_LEFT,
        )

        fun set(bits: Int, x: Int, y: Int) {
            if (open()) {
                try {
                    val b = ByteBuffer.allocate(4 + 2 + 5).order(ByteOrder.LITTLE_ENDIAN)
                    b.putInt(UHID_INPUT2)
                    b.putShort(5)
                    b.put((bits and 0xFF).toByte())
                    b.put(((bits shr 8) and 0xFF).toByte())
                    b.put(((bits shr 16) and 0x01).toByte())
                    b.put(x.coerceIn(-127, 127).toByte())
                    b.put(y.coerceIn(-127, 127).toByte())
                    write(b.array())
                    lastBits = bits
                    return
                } catch (e: Throwable) {
                    System.err.println("uhid input failed: $e")
                    close()
                    noUhid = true
                }
            }
            keys(bits)
        }

        private fun keys(bits: Int) {
            val changed = bits xor lastBits
            if (changed == 0) return
            for ((bit, code) in KEYS) {
                if (changed and (1 shl bit) == 0) continue
                KeyServer.key(if (bits and (1 shl bit) != 0) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code)
            }
            lastBits = bits
        }

        private fun open(): Boolean {
            if (fd != null) return true
            if (noUhid) return false
            return try {
                val f = Os.open("/dev/uhid", OsConstants.O_RDWR, 0)
                val name = "Projector Pad".toByteArray()
                val b = ByteBuffer.allocate(280 + DESCRIPTOR.size).order(ByteOrder.LITTLE_ENDIAN)
                b.putInt(UHID_CREATE2)
                b.put(name.copyOf(128))
                b.put(ByteArray(64))          // phys
                b.put(ByteArray(64))          // uniq
                b.putShort(DESCRIPTOR.size.toShort())
                b.putShort(BUS_VIRTUAL.toShort())
                b.putInt(0x1209)              // vendor: pid.codes (open-source hardware)
                b.putInt(0x0001)              // product
                b.putInt(1)                   // version
                b.putInt(0)                   // country
                b.put(DESCRIPTOR)
                fd = f
                write(b.array())
                // Android needs a moment to register the new controller before its first report.
                Thread.sleep(150)
                true
            } catch (e: Throwable) {
                System.err.println("uhid unavailable: $e")
                close()
                noUhid = true
                false
            }
        }

        private fun write(bytes: ByteArray) {
            var off = 0
            while (off < bytes.size) off += Os.write(fd!!, bytes, off, bytes.size - off)
        }

        private fun close() {
            val f = fd ?: return
            try {
                val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(UHID_DESTROY)
                Os.write(f, b.array(), 0, 4)
            } catch (_: Throwable) {}
            try { Os.close(f) } catch (_: Throwable) {}
            fd = null
        }
    }
}
