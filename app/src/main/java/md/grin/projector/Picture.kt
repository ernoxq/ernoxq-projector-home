package md.grin.projector

import android.content.Context

/**
 * "Bright mode" for watching with the lights on. The lamp itself can't shine harder from software,
 * so this lifts the picture instead: about 10% brighter mid-tones, a small lift of the darks and
 * 15% richer colour, applied to the whole screen by Android's display compositor (SurfaceFlinger).
 * Fully reversible, and re-applied after every restart while it's switched on.
 */
object Picture {
    private const val GAIN = "1.1"
    private const val LIFT = "0.02"
    private const val SATURATION = "1.15"

    fun isOn(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("bright", false)

    fun set(ctx: Context, on: Boolean): Boolean {
        val ok = if (on) {
            // 4×4 colour matrix, column-major: RGB gain on the diagonal, lift in the last row.
            Adb.shell(ctx, "service call SurfaceFlinger 1015 i32 1 " +
                "f $GAIN f 0 f 0 f 0 " +
                "f 0 f $GAIN f 0 f 0 " +
                "f 0 f 0 f $GAIN f 0 " +
                "f $LIFT f $LIFT f $LIFT f 1") &&
                Adb.shell(ctx, "service call SurfaceFlinger 1022 f $SATURATION")
        } else {
            Adb.shell(ctx, "service call SurfaceFlinger 1015 i32 0") &&
                Adb.shell(ctx, "service call SurfaceFlinger 1022 f 1.0")
        }
        if (ok) ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putBoolean("bright", on).apply()
        return ok
    }

    /** After a reboot the display is back to normal; switch bright mode on again if it was on. */
    fun restore(ctx: Context) {
        if (isOn(ctx)) set(ctx, true)
    }
}
