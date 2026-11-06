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
    // Kept modest: a nearly full flash drive makes the whole projector slow, not just the screensaver.
    private const val CACHE_LIMIT = 1536L * 1024 * 1024
    private const val MIN_FREE = 3L * 1024 * 1024 * 1024
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

    /** The song card in the top corner of the screensaver, switched on and off from the phone. */
    @Volatile private var card: Boolean? = null
    fun cardOn(ctx: Context): Boolean = card ?: ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("np_card", true).also { card = it }
    fun setCard(ctx: Context, on: Boolean): Boolean {
        card = on
        ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putBoolean("np_card", on).apply()
        view?.let { v -> ui.post { v.refreshCard() } }
        return true
    }

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

    /** Frees space taken by older, bigger collections. Runs once in the background at start. */
    fun trim(ctx: Context) {
        Thread({ try { makeRoom(ctx.applicationContext) } catch (_: Exception) {} }, "aerial-trim").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    fun leave() { active.decrementAndGet() }

    private val ui = Handler(Looper.getMainLooper())
    @Volatile var view: AerialView? = null
    private var lastSwipe = 0L

    /**
     * The phone remote while the screensaver is on: a swipe left or right changes the flight,
     * any other touch brings the place name back. OK, Back and Home still close the screensaver.
     */
    fun remote(key: String): Boolean {
        val v = view ?: return false
        when (key) {
            "left", "right" -> {
                val now = SystemClock.elapsedRealtime()
                // One swipe sends several steps as the finger moves: count it once.
                if (now - lastSwipe > 800) { lastSwipe = now; ui.post { v.skip(if (key == "right") 1 else -1) } }
            }
            "up", "down", "touch" -> ui.post { v.wake() }
            else -> return false
        }
        return true
    }

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

    /** Keeps at least 3 GB free and the collection under 1.5 GB, dropping what was played longest ago. */
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
    private val nowPlaying: NowPlayingCard
    private val mediaChanged: () -> Unit = { refreshCard() }

    /** Shows the song card, unless it was switched off on the phone. */
    fun refreshCard() {
        if (!running) return
        nowPlaying.update(if (Aerials.cardOn(context)) Media.current(context) else null)
    }
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
    private var revealed = false
    private var labelsOn = false
    private var labelsUntil = 0L
    private val history = ArrayList<Clip>()
    private var histPos = -1

    init {
        Ui.init(ctx)
        setBackgroundColor(Color.BLACK)
        video.surfaceTextureListener = this
        video.alpha = 0f
        addView(video, LayoutParams(Ui.MATCH, Ui.MATCH))
        addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0x66000000, 0))
        }, LayoutParams(Ui.MATCH, Ui.px(170), Gravity.BOTTOM))
        country = caption(13, 600).apply { letterSpacing = 0.18f; alpha = 0.85f }
        // The place may be long ("Рисовые террасы Лунцзи"): it wraps onto a second line instead of being cut to "Р…".
        place = caption(28, 500).apply {
            maxLines = 2
            ellipsize = null
            setLineSpacing(0f, 1.08f)
        }
        labels.addView(country, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = Ui.px(6) })
        labels.addView(place, Ui.lp(Ui.MATCH, Ui.WRAP))
        labels.alpha = 0f
        // A fixed width, so the text is never measured against an older, shorter name.
        addView(labels, LayoutParams(Ui.px(560), Ui.WRAP, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = Ui.px(48); bottomMargin = Ui.px(38)
        })
        nowPlaying = NowPlayingCard(ctx)
        addView(nowPlaying, LayoutParams(Ui.px(290), Ui.WRAP, Gravity.TOP or Gravity.END).apply {
            topMargin = Ui.px(32); rightMargin = Ui.px(32)
        })
        clock = caption(22, 500).apply { alpha = 0.85f }
        addView(clock, LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = Ui.px(48); bottomMargin = Ui.px(38)
        })
    }

    private fun caption(size: Int, weight: Int) = Ui.text(context, "", size, weight, Color.WHITE).apply {
        setShadowLayer(Ui.pxf(10), 0f, Ui.pxf(1), 0x80000000.toInt())
    }

    fun start() {
        if (running) return
        running = true
        Aerials.enter(context)
        Aerials.view = this
        // The place name is shown for the first minute, then fades away.
        labelsUntil = SystemClock.elapsedRealtime() + 60_000
        Media.attach(context.applicationContext)
        Media.listen(mediaChanged)
        refreshCard()
        fails = 0
        main.post(tick)
        if (surface != null) playNext()
    }

    fun stop() {
        if (!running) return
        running = false
        Aerials.leave()
        if (Aerials.view === this) Aerials.view = null
        Media.unlisten(mediaChanged)
        nowPlaying.hide()
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

    /** Brings the place name back for a few seconds (a touch on the phone remote). */
    fun wake(ms: Long = 10_000) {
        if (!running) return
        labelsUntil = maxOf(labelsUntil, SystemClock.elapsedRealtime() + ms)
        syncLabels()
    }

    /** Swipe on the phone: right is the next flight, left goes back to the previous one. */
    fun skip(dir: Int) {
        if (!running || surface == null) return
        val idx = histPos + dir
        val target = history.getOrNull(idx)
        labelsUntil = maxOf(labelsUntil, SystemClock.elapsedRealtime() + 8_000)
        main.removeCallbacks(nextClip)
        revealed = false
        syncLabels()
        video.animate().cancel()
        video.animate().alpha(0f).setStartDelay(0).setDuration(350).withEndAction {
            if (running) playNext(target, idx)
        }.start()
    }

    private fun syncLabels() {
        val want = revealed && !fading && SystemClock.elapsedRealtime() < labelsUntil
        if (want == labelsOn) return
        labelsOn = want
        labels.animate().cancel()
        labels.animate().alpha(if (want) 1f else 0f)
            .setStartDelay(if (want) 700 else 0)
            .setDuration(if (want) 1000 else 2000)
            .start()
    }

    private fun playNext(target: Clip? = null, idx: Int = -1) {
        if (!running) return
        val s = surface ?: return
        main.removeCallbacks(nextClip)
        releasePlayer()
        video.animate().cancel(); video.alpha = 0f
        labels.animate().cancel(); labels.alpha = 0f
        labelsOn = false
        revealed = false
        fading = false
        lastPos = -1
        stuckSince = 0
        startedAt = SystemClock.elapsedRealtime()
        // Nothing reachable (no internet): only the clock, and another try in a minute.
        if (fails >= 6) { fails = 0; main.postDelayed(nextClip, 60_000); return }
        val c = if (target != null) { histPos = idx; target } else {
            val n = try { Aerials.next(context) } catch (_: Exception) { return }
            history.add(n)
            if (history.size > 30) history.removeAt(0)
            histPos = history.size - 1
            n
        }
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
        labels.requestLayout()
        revealed = true
        video.animate().cancel()
        video.animate().alpha(1f).setStartDelay(0).setDuration(1200).start()
        syncLabels()
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
            val t = timeFmt.format(Clock.now())
            // Setting the same text again would still re-measure the screen over the playing video.
            if (clock.text.toString() != t) clock.text = t
            refreshCard()
            syncLabels()
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
