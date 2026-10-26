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

    fun init(ctx: Context) {
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
        val now = SystemClock.uptimeMillis()
        if (now - lastTick < 35) return // holding a direction key shouldn't turn into a buzz
        lastTick = now
        pool?.play(tick, 0.55f, 0.55f, 1, 0, 1f)
    }

    fun select() {
        pool?.play(select, 0.6f, 0.6f, 1, 0, 1f)
    }
}
