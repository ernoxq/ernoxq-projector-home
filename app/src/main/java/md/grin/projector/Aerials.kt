package md.grin.projector

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

data class Clip(val place: String, val country: String, val tod: String, val url: String)

/**
 * Apple TV-style screensaver: flights over cities and countries with the place written in the corner.
 * The list lives in assets/aerials.json. Clips are streamed the first time and saved in the background,
 * so the projector soon plays them from its own storage, without Wi-Fi hiccups.
 */
object Aerials {
    const val IDLE_MS = 3 * 60_000L
    private const val CACHE_LIMIT = 4L * 1024 * 1024 * 1024
    private const val MIN_FREE = 1536L * 1024 * 1024
    private const val UA = "Mozilla/5.0 (Linux; Android 11) ProjectorHome"
    val headers = mapOf("User-Agent" to UA)

    private val active = AtomicInteger(0)
    val showing get() = active.get() > 0
    @Volatile var playingFile: File? = null
    private var clips: List<Clip>? = null
    private val recent = ArrayDeque<String>()
    private var lastPlace = ""
    @Volatile private var downloading = false

    fun isOn(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("aerials", true)
    fun setOn(ctx: Context, on: Boolean) { ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putBoolean("aerials", on).apply() }

    /** Opens the screensaver full screen. */
    fun show(ctx: Context): Boolean = try {
        ctx.startActivity(Intent(ctx, AerialActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) { false }

    private fun all(ctx: Context): List<Clip> = clips ?: run {
        val arr = JSONArray(ctx.assets.open("aerials.json").bufferedReader().use { it.readText() })
        List(arr.length()) {
            val o = arr.getJSONObject(it)
            Clip(o.getString("place"), o.optString("country"), o.optString("tod"), o.getString("url"))
        }.also { clips = it }
    }

    /** In the evening and at night only darker flights, so the room isn't lit up by a bright sky. */
    private fun pool(ctx: Context): List<Clip> {
        val list = all(ctx)
        return if (Ui.isNightNow()) list.filter { it.tod != "day" }.ifEmpty { list } else list
    }

    private fun dir(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "aerials").apply { mkdirs() }
    fun file(ctx: Context, c: Clip) = File(dir(ctx), Integer.toHexString(c.url.hashCode()) + ".video")

    /** The next flight: somewhere else than the last one, and from storage if possible. */
    @Synchronized
    fun next(ctx: Context): Clip {
        val pool = pool(ctx)
        val fresh = pool.filter { it.url !in recent && it.place != lastPlace }.ifEmpty { pool.filter { it.place != lastPlace }.ifEmpty { pool } }
        val c = fresh.filter { file(ctx, it).exists() }.randomOrNull() ?: fresh.random()
        recent.addLast(c.url)
        while (recent.size > pool.size / 2) recent.removeFirst()
        lastPlace = c.place
        return c
    }

    fun enter(ctx: Context) {
        active.incrementAndGet()
        startDownloads(ctx.applicationContext)
    }

    fun leave() { active.decrementAndGet() }

    /** While the screensaver is on, saves flights one by one. It stops as soon as someone comes back. */
    private fun startDownloads(ctx: Context) {
        synchronized(this) { if (downloading) return; downloading = true }
        Thread({
            try {
                var fails = 0
                while (showing && fails < 3) {
                    val todo = pool(ctx).filter { !file(ctx, it).exists() }.randomOrNull() ?: break
                    if (!makeRoom(ctx)) break
                    try { download(todo, file(ctx, todo)); fails = 0 } catch (_: Exception) { fails++; SystemClock.sleep(5_000) }
                }
            } catch (_: Exception) {
            } finally { downloading = false }
        }, "aerial-download").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** Keeps at least 1.5 GB free and the collection under 4 GB, dropping what was played longest ago. */
    private fun makeRoom(ctx: Context): Boolean {
        val d = dir(ctx)
        while (true) {
            val files = d.listFiles()?.filter { it.name.endsWith(".video") } ?: return false
            if (files.sumOf { it.length() } < CACHE_LIMIT && d.usableSpace > MIN_FREE) return true
            val old = files.filter { it != playingFile }.minByOrNull { it.lastModified() } ?: return false
            old.delete()
        }
    }

    private fun download(c: Clip, to: File) {
        val part = File(to.path + ".part")
        var u = URL(c.url)
        var hops = 0
        var conn: HttpURLConnection
        while (true) {
            conn = u.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", UA)
            if (part.exists() && part.length() > 0) conn.setRequestProperty("Range", "bytes=${part.length()}-")
            val code = conn.responseCode
            if (code in 300..399 && hops < 5) {
                u = URL(u, conn.getHeaderField("Location")); conn.disconnect(); hops++; continue
            }
            if (code == 416) { part.delete(); conn.disconnect(); throw java.io.IOException("range") }
            if (code !in 200..299) { conn.disconnect(); throw java.io.IOException("HTTP $code") }
            break
        }
        val append = conn.responseCode == 206
        val total = conn.contentLengthLong + if (append) part.length() else 0
        var done = false
        try {
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (showing) {
                        val n = input.read(buf)
                        if (n < 0) { done = true; break }
                        out.write(buf, 0, n)
                    }
                }
            }
        } finally { conn.disconnect() }
        // Left unfinished: the part stays and continues next time.
        if (done && (total <= 0 || part.length() == total)) part.renameTo(to)
    }
}

/** The picture itself: video filling the screen, the place at the bottom left, the time at the bottom right. */
class AerialView(ctx: Context) : FrameLayout(ctx), TextureView.SurfaceTextureListener {
    private val main = Handler(Looper.getMainLooper())
    private val video = TextureView(ctx)
    private val labels = Ui.col(ctx)
    private val country: TextView
    private val place: TextView
    private val clock: TextView
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = Clock.zone }

    private var surface: Surface? = null
    private var player: MediaPlayer? = null
    private var clip: Clip? = null
    private var source: File? = null
    private var running = false
    private var fails = 0
    private var ready = false
    private var startedAt = 0L
    private var lastPos = -1
    private var stuckSince = 0L
    private var fading = false

    init {
        Ui.init(ctx)
        setBackgroundColor(Color.BLACK)
        video.surfaceTextureListener = this
        video.alpha = 0f
        addView(video, LayoutParams(Ui.MATCH, Ui.MATCH))
        addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0x8C000000.toInt(), 0))
        }, LayoutParams(Ui.MATCH, Ui.px(240), Gravity.BOTTOM))
        country = caption(22, 600).apply { letterSpacing = 0.14f }
        place = caption(54, 600).apply { letterSpacing = -0.02f }
        labels.addView(country, Ui.lp(Ui.WRAP, Ui.WRAP).apply { bottomMargin = Ui.px(6) })
        labels.addView(place)
        labels.alpha = 0f
        addView(labels, LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = Ui.px(56); bottomMargin = Ui.px(44)
        })
        clock = caption(36, 500).apply { alpha = 0.9f }
        addView(clock, LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = Ui.px(56); bottomMargin = Ui.px(46)
        })
    }

    private fun caption(size: Int, weight: Int) = Ui.text(context, "", size, weight, Color.WHITE).apply {
        setShadowLayer(Ui.pxf(14), 0f, Ui.pxf(2), 0x99000000.toInt())
    }

    fun start() {
        if (running) return
        running = true
        Aerials.enter(context)
        fails = 0
        main.post(tick)
        if (surface != null) playNext()
    }

    fun stop() {
        if (!running) return
        running = false
        Aerials.leave()
        main.removeCallbacksAndMessages(null)
        releasePlayer()
        Aerials.playingFile = null
    }

    private fun releasePlayer() {
        val p = player ?: return
        player = null
        ready = false
        try { p.setSurface(null); p.release() } catch (_: Exception) {}
    }

    private val nextClip = Runnable { playNext() }

    private fun playNext() {
        if (!running) return
        val s = surface ?: return
        main.removeCallbacks(nextClip)
        releasePlayer()
        video.animate().cancel(); video.alpha = 0f
        labels.animate().cancel(); labels.alpha = 0f
        fading = false
        lastPos = -1
        stuckSince = 0
        startedAt = SystemClock.elapsedRealtime()
        // Nothing reachable (no internet): only the clock, and another try in a minute.
        if (fails >= 6) { fails = 0; main.postDelayed(nextClip, 60_000); return }
        val c = try { Aerials.next(context) } catch (_: Exception) { return }
        clip = c
        val f = Aerials.file(context, c).takeIf { it.exists() }
        source = f
        Aerials.playingFile = f
        f?.setLastModified(System.currentTimeMillis())
        val p = MediaPlayer()
        player = p
        try {
            p.setSurface(s)
            p.setVolume(0f, 0f)
            p.setOnPreparedListener { if (it === player) { ready = true; it.start() } }
            p.setOnInfoListener { mp, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && mp === player) reveal(c)
                false
            }
            p.setOnVideoSizeChangedListener { mp, w, h -> if (mp === player) crop(w, h) }
            p.setOnCompletionListener { if (it === player) playNext() }
            p.setOnErrorListener { mp, _, _ -> if (mp === player) failed(); true }
            if (f != null) p.setDataSource(f.path) else p.setDataSource(context, Uri.parse(c.url), Aerials.headers)
            p.prepareAsync()
        } catch (_: Exception) {
            failed()
        }
    }

    /** A clip that won't play is skipped; if it was a saved file, it's thrown away and fetched again later. */
    private fun failed() {
        fails++
        source?.delete()
        source = null
        Aerials.playingFile = null
        releasePlayer()
        main.removeCallbacks(nextClip)
        main.postDelayed(nextClip, 400)
    }

    private fun reveal(c: Clip) {
        fails = 0
        place.text = c.place
        country.text = c.country.uppercase(Locale("ru"))
        country.visibility = if (c.country.isEmpty()) View.GONE else View.VISIBLE
        video.animate().alpha(1f).setDuration(1400).start()
        labels.animate().alpha(1f).setStartDelay(1200).setDuration(1400).start()
    }

    /** Fills the screen without stretching, if a clip isn't exactly 16:9. */
    private fun crop(w: Int, h: Int) {
        val vw = video.width.toFloat()
        val vh = video.height.toFloat()
        if (w <= 0 || h <= 0 || vw <= 0 || vh <= 0) return
        val r = (w.toFloat() / h) / (vw / vh)
        val m = Matrix()
        if (r > 1) m.setScale(r, 1f, vw / 2, vh / 2) else m.setScale(1f, 1 / r, vw / 2, vh / 2)
        video.setTransform(m)
        video.invalidate()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            clock.text = timeFmt.format(Clock.now())
            val p = player
            val now = SystemClock.elapsedRealtime()
            try {
                if (p != null && !ready) {
                    if (now - startedAt > 30_000) failed()
                } else if (p != null) {
                    val pos = p.currentPosition
                    val left = p.duration - pos
                    if (!fading && p.duration > 4000 && left in 1..1600) {
                        fading = true
                        video.animate().alpha(0f).setDuration(1200).start()
                        labels.animate().alpha(0f).setStartDelay(0).setDuration(800).start()
                    }
                    // Stuck on a slow connection for 20 seconds: move on.
                    if (pos != lastPos) { lastPos = pos; stuckSince = now }
                    else if (now - stuckSince > 20_000) failed()
                }
            } catch (_: Exception) { failed() }
            main.postDelayed(this, 1000)
        }
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        surface = Surface(st)
        if (running) playNext()
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        releasePlayer()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}

/** Our own launch of the screensaver from the home screen. Any button brings the home screen back. */
class AerialActivity : Activity() {
    private lateinit var view: AerialView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = AerialView(this)
        setContentView(view)
    }

    override fun onResume() { super.onResume(); view.start() }

    override fun onPause() {
        super.onPause()
        view.stop()
        SleepTimer.touch()
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) finish()
        return true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) finish()
        return true
    }
}

/** The same picture as the system screensaver, replacing the stock photo one in every app. */
class AerialDream : DreamService() {
    private var view: AerialView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        window?.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        view = AerialView(this).also { setContentView(it) }
    }

    override fun onDreamingStarted() { super.onDreamingStarted(); view?.start() }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        view?.stop()
        SleepTimer.touch()
    }

    override fun onDetachedFromWindow() {
        view?.stop()
        super.onDetachedFromWindow()
    }
}
