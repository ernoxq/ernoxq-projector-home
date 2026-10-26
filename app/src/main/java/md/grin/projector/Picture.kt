package md.grin.projector

import android.content.Context

/**
 * Picture modes applied to the whole screen by Android's display compositor (SurfaceFlinger):
 *  - Bright mode, for watching with the lights on: ~10% brighter mid-tones, lifted darks, 15% richer colour.
 *    The lamp can't physically shine harder from software, so this brightens the picture instead.
 *  - Night Shift: warmer colours with less blue, easier on the eyes in the evening.
 * Both can be on together. Fully reversible, and re-applied after every restart.
 */
object Picture {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun isOn(ctx: Context) = prefs(ctx).getBoolean("bright", false)
    fun isWarm(ctx: Context) = prefs(ctx).getBoolean("warm", false)

    fun set(ctx: Context, on: Boolean): Boolean = apply(ctx, on, isWarm(ctx))
    fun setWarm(ctx: Context, on: Boolean): Boolean = apply(ctx, isOn(ctx), on)

    private fun apply(ctx: Context, bright: Boolean, warm: Boolean): Boolean {
        val ok = if (!bright && !warm) {
            Adb.shell(ctx, "service call SurfaceFlinger 1015 i32 0") &&
                Adb.shell(ctx, "service call SurfaceFlinger 1022 f 1.0")
        } else {
            val g = if (bright) 1.1f else 1f
            val r = g
            val gr = g * (if (warm) 0.90f else 1f)
            val b = g * (if (warm) 0.66f else 1f)
            val lift = if (bright) 0.02f else 0f
            // 4×4 colour matrix, column-major: per-channel gain on the diagonal, lift in the last row.
            Adb.shell(ctx, "service call SurfaceFlinger 1015 i32 1 " +
                "f $r f 0 f 0 f 0 " +
                "f 0 f $gr f 0 f 0 " +
                "f 0 f 0 f $b f 0 " +
                "f $lift f $lift f $lift f 1") &&
                Adb.shell(ctx, "service call SurfaceFlinger 1022 f ${if (bright) 1.15f else 1f}")
        }
        if (ok) prefs(ctx).edit().putBoolean("bright", bright).putBoolean("warm", warm).apply()
        return ok
    }

    /** After a reboot the display is back to normal; re-apply whatever was on. */
    fun restore(ctx: Context) {
        if (isOn(ctx) || isWarm(ctx)) apply(ctx, isOn(ctx), isWarm(ctx))
    }
}
