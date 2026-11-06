package md.grin.projector

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock

/** Soft navigation sounds in the spirit of Apple TV: a quiet tick on every move, a fuller tap on OK. */
object Sounds {
    private var pool: SoundPool? = null
    private var tick = 0
    private var select = 0
    private var lastTick = 0L
    /** Navigation sounds can be switched off in Settings › Звук. */
    @Volatile var enabled = true

    fun isOn(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("ui_sounds", true)
    fun setOn(ctx: Context, on: Boolean) {
        enabled = on
        ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putBoolean("ui_sounds", on).apply()
    }

    fun init(ctx: Context) {
        enabled = isOn(ctx)
        if (pool != null) return
        val p = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        tick = p.load(ctx.applicationContext, R.raw.tick, 1)
        select = p.load(ctx.applicationContext, R.raw.select, 1)
        pool = p
    }

    fun tick() {
        if (!enabled) return
        val now = SystemClock.uptimeMillis()
        if (now - lastTick < 35) return // holding a direction key shouldn't turn into a buzz
        lastTick = now
        pool?.play(tick, 0.55f, 0.55f, 1, 0, 1f)
    }

    fun select() {
        if (!enabled) return
        pool?.play(select, 0.6f, 0.6f, 1, 0, 1f)
    }
}
