package md.grin.projector

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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

/** A flight. [hevc] is Apple's lighter H.265 copy of the same Full HD clip (about a third smaller), [mb] its size. */
data class Clip(val place: String, val country: String, val tod: String, val url: String, val hevc: String? = null, val mb: Int = 300)

/**
 * Apple TV-style screensaver: flights over cities and countries with the place written in the corner.
 * The list lives in assets/aerials.json. Clips are streamed the first time and saved in the background,
 * so the projector soon plays them from its own storage, without Wi-Fi hiccups.
 */
object Aerials {
    /** The screensaver comes on by itself after 15 minutes without anyone touching a remote. */
    const val IDLE_MS = 15 * 60_000L
    // Kept modest: a nearly full flash drive makes the whole projector slow, not just the screensaver.
    // The projector has only 4.7 GB of storage with ~1.6 GB free: the 12 lightest flights (1.3 GB) are kept,
    // leaving about half a gigabyte free for Android.
    private const val CACHE_LIMIT = 1400L * 1024 * 1024
    private const val MIN_FREE = 450L * 1024 * 1024
    private const val UA = "Mozilla/5.0 (Linux; Android 11) ProjectorHome"
    val headers = mapOf("User-Agent" to UA)

    private val active = AtomicInteger(0)
    val showing get() = active.get() > 0
    @Volatile var playingFile: File? = null
    private var clips: List<Clip>? = null
    private val recent = ArrayDeque<String>()
    private var lastPlace = ""
    @Volatile private var downloading = false
    /** What the downloader is doing, for the screen while no flight is showing yet. */
    @Volatile var stored = 0
        private set
    @Volatile var total = 0
        private set
    @Volatile var currentPercent = -1
        private set

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
            val hevc = o.optString("hevc").ifEmpty { null }
            Clip(o.getString("place"), o.optString("country"), o.optString("tod"), o.getString("url"), hevc,
                o.optInt(if (hevc != null) "mb" else "mb264", 0).takeIf { it > 0 } ?: 300)
        }.also { clips = it }
    }

    /** In the evening and at night only darker flights, so the room isn't lit up by a bright sky. */
    private fun pool(ctx: Context): List<Clip> {
        val list = all(ctx)
        return if (Ui.isNightNow()) list.filter { it.tod != "day" }.ifEmpty { list } else list
    }

    private fun dir(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "aerials").apply { mkdirs() }
    private fun fileFor(ctx: Context, url: String) = File(dir(ctx), Integer.toHexString(url.hashCode()) + ".video")

    /** Can this projector decode Full HD H.265 in hardware? Then the lighter copies are used. */
    private val hevcOk: Boolean by lazy {
        try {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", true) } &&
                    !info.name.startsWith("c2.android", true) && !info.name.startsWith("OMX.google", true) &&
                    try { info.getCapabilitiesForType("video/hevc").videoCapabilities.isSizeSupported(1920, 1080) } catch (_: Exception) { false }
            }
        } catch (_: Exception) { false }
    }

    /** Where this flight is fetched from: the H.265 copy when the projector can play it. */
    fun source(c: Clip): String = if (hevcOk && c.hevc != null) c.hevc else c.url

    /** The stored copy to play (either kind, so flights saved before keep playing), or where to save one. */
    fun file(ctx: Context, c: Clip): File {
        val preferred = fileFor(ctx, source(c))
        if (preferred.exists()) return preferred
        val other = fileFor(ctx, c.url)
        return if (other.exists()) other else preferred
    }

    /**
     * Music is playing (Spotify, AirPlay…). The projector's Wi‑Fi is weak and AirPlay sound has almost no
     * buffer: a 2K flight streamed from the internet, plus the next one being saved, starved the music and
     * made everything lag. So while music plays, the screensaver uses only flights already stored here.
     */
    @Suppress("UNUSED_PARAMETER")
    fun musicOn(ctx: Context) = Media.isPlaying()

    /**
     * The next flight: somewhere else than the last one, and from storage if possible.
     * With [storedOnly] (music playing) it never streams: null when nothing is stored yet.
     */
    @Synchronized
    fun next(ctx: Context, storedOnly: Boolean = false): Clip? {
        val pool = pool(ctx)
        val stored = pool.filter { file(ctx, it).exists() }
        if (storedOnly && stored.isEmpty()) return null
        val base = if (storedOnly) stored else pool
        val fresh = base.filter { it.url !in recent && it.place != lastPlace }.ifEmpty { base.filter { it.place != lastPlace }.ifEmpty { base } }
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
        val app = ctx.applicationContext
        Thread({
            try {
                // Flights that are no longer in the library (the list is now 20 light ones) are deleted.
                val keep = all(app).flatMap { listOf(fileFor(app, it.url).name, it.hevc?.let { h -> fileFor(app, h).name }) }.filterNotNull().toSet()
                dir(app).listFiles()?.forEach { f ->
                    val name = f.name.removeSuffix(".part")
                    if (name !in keep && f != playingFile) f.delete()
                }
                makeRoom(app)
            } catch (_: Exception) {}
        }, "aerial-trim").apply { priority = Thread.MIN_PRIORITY }.start()
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
                    val missing = all(ctx).filter { !file(ctx, it).exists() }
                    val storedCount = all(ctx).size - missing.size
                    stored = storedCount
                    total = all(ctx).size
                    // With music on, only the first few are saved (slowly); the rest waits for silence.
                    if (musicOn(ctx)) { SystemClock.sleep(5_000); continue }
                    // Lightest first: a picture shows up soon, and as many flights as possible fit the small drive.
                    val todo = missing.minByOrNull { it.mb } ?: break
                    if (!makeRoom(ctx)) break
                    try { download(ctx, todo, file(ctx, todo)); fails = 0 } catch (_: Exception) { fails++; SystemClock.sleep(5_000) }
                }
            } catch (_: Exception) {
            } finally { downloading = false }
        }, "aerial-download").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** Keeps at least 450 MB free and the collection under 1.4 GB (the library of 12), dropping what was played longest ago. */
    private fun makeRoom(ctx: Context): Boolean {
        val d = dir(ctx)
        while (true) {
            val files = d.listFiles()?.filter { it.name.endsWith(".video") } ?: return false
            if (files.sumOf { it.length() } < CACHE_LIMIT && d.usableSpace > MIN_FREE) return true
            val old = files.filter { it != playingFile }.minByOrNull { it.lastModified() } ?: return false
            old.delete()
        }
    }

    private fun download(ctx: Context, c: Clip, to: File) {
        val part = File(to.path + ".part")
        var u = URL(source(c))
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
        currentPercent = 0
        var done = false
        try {
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (showing) {
                        val n = input.read(buf)
                        if (n < 0) { done = true; break }
                        out.write(buf, 0, n)
                        // While music plays, save at about 6 Mbit/s so the music keeps its share of the Wi‑Fi.
                        if (musicOn(ctx)) SystemClock.sleep(80)
                        if (total > 0) currentPercent = ((part.length() * 100) / total.coerceAtLeast(1)).toInt().coerceIn(0, 99)
                    }
                }
            }
        } finally { conn.disconnect() }
        // Left unfinished: the part stays and continues next time.
        if (done && (total <= 0 || part.length() == total)) { part.renameTo(to); stored++ }
        currentPercent = -1
    }
}

/** The picture itself: video filling the screen, the place at the bottom left, the time at the bottom right. */
/**
 * The video sits on its own hardware plane (SurfaceView): the projector shows it directly, without the GPU
 * copying every frame into the app's picture as a TextureView does. Fades are a black curtain above it.
 */
class AerialView(ctx: Context) : FrameLayout(ctx), android.view.SurfaceHolder.Callback {
    private val main = Handler(Looper.getMainLooper())
    private val video = android.view.SurfaceView(ctx)
    private val curtain = View(ctx).apply { setBackgroundColor(Color.BLACK) }
    private val labels = Ui.col(ctx)
    private val country: TextView
    private val place: TextView
    private val clock: TextView
    private val nowPlaying: NowPlayingCard
    private val status: TextView
    private val mediaChanged: () -> Unit = { refreshCard() }

    /** Shows the song card, unless it was switched off on the phone (in music mode the big view shows the song). */
    fun refreshCard() {
        if (!running) return
        if (musicMode) { nowPlaying.update(null); music.update(Media.current(context)); return }
        nowPlaying.update(if (Aerials.cardOn(context)) Media.current(context) else null)
    }

    // ---------- music mode ----------
    // While music plays there is no flight at all: no video decoder, no downloads, nothing that could take
    // memory or sound away from Spotify. Instead, an Apple TV-style "now playing" screen.

    private var musicMode = false
    private var silentSince = 0L
    private val music: MusicScreen

    private fun enterMusic() {
        if (musicMode) return
        musicMode = true
        releasePlayer()
        main.removeCallbacks(nextClip)
        curtain.animate().cancel(); curtain.alpha = 1f
        labels.animate().cancel(); labels.alpha = 0f
        labelsOn = false
        revealed = false
        music.show()
        refreshCard()
    }

    private fun leaveMusic() {
        if (!musicMode) return
        musicMode = false
        music.hide()
        playNext()
    }
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = Clock.zone }

    private var surface: Surface? = null
    private var player: FlightPlayer? = null
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
        video.holder.addCallback(this)
        addView(video, LayoutParams(Ui.MATCH, Ui.MATCH, Gravity.CENTER))
        addView(curtain, LayoutParams(Ui.MATCH, Ui.MATCH))
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
        music = MusicScreen(ctx)
        addView(music, LayoutParams(Ui.MATCH, Ui.MATCH))
        // Shown only while no flight is on screen yet: tells that the library is being prepared.
        status = caption(15, 600).apply {
            gravity = Gravity.CENTER
            background = Glass.panel(22)
            setPadding(Ui.px(20), 0, Ui.px(20), 0)
            setShadowLayer(0f, 0f, 0f, 0)
            alpha = 0f
        }
        addView(status, LayoutParams(Ui.WRAP, Ui.px(44), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Ui.px(36) })
        nowPlaying = NowPlayingCard(ctx)
        addView(nowPlaying, LayoutParams(Ui.px(290), Ui.WRAP, Gravity.TOP or Gravity.END).apply {
            topMargin = Ui.px(32); rightMargin = Ui.px(32)
        })
        // The time in a small glass pill, like the rest of the system.
        clock = caption(20, 600).apply {
            gravity = Gravity.CENTER
            background = Glass.panel(22)
            setPadding(Ui.px(18), 0, Ui.px(18), 0)
            setShadowLayer(0f, 0f, 0f, 0)
        }
        addView(clock, LayoutParams(Ui.WRAP, Ui.px(44), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = Ui.px(48); bottomMargin = Ui.px(36)
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
        musicMode = false
        music.hide()
        running = false
        Aerials.leave()
        if (Aerials.view === this) Aerials.view = null
        Media.unlisten(mediaChanged)
        nowPlaying.hide()
        main.removeCallbacksAndMessages(null)
        releasePlayer()
        Aerials.playingFile = null
    }

    /** The flight being stopped: the next one waits for its decoder to be freed. */
    private var previous: FlightPlayer? = null

    private fun releasePlayer() {
        val p = player ?: return
        player = null
        ready = false
        try { p.release() } catch (_: Exception) {}
        previous = p
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
        if (!running || surface == null || musicMode) return
        val idx = histPos + dir
        val target = history.getOrNull(idx)
        labelsUntil = maxOf(labelsUntil, SystemClock.elapsedRealtime() + 8_000)
        main.removeCallbacks(nextClip)
        revealed = false
        syncLabels()
        curtain.animate().cancel()
        curtain.animate().alpha(1f).setStartDelay(0).setDuration(350).withEndAction {
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
        curtain.animate().cancel(); curtain.alpha = 1f
        labels.animate().cancel(); labels.alpha = 0f
        labelsOn = false
        revealed = false
        fading = false
        lastPos = -1
        stuckSince = 0
        startedAt = SystemClock.elapsedRealtime()
        if (Aerials.musicOn(context)) { enterMusic(); return }
        // Nothing reachable (no internet): only the clock, and another try in a minute.
        if (fails >= 6) { fails = 0; main.postDelayed(nextClip, 60_000); return }
        val music = Aerials.musicOn(context)
        if (target != null && music && !Aerials.file(context, target).exists()) { playNext(); return }
        val c = if (target != null) { histPos = idx; target } else {
            val n = try { Aerials.next(context, storedOnly = music) } catch (_: Exception) { return }
            if (n == null) {
                // Music and nothing stored yet: just the clock and the song, no video. Look again in a while.
                main.postDelayed(nextClip, 30_000)
                return
            }
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
        // Picture only, through our own decoder: the system player took the sound over and paused the music.
        var me: FlightPlayer? = null
        val p = FlightPlayer(s, object : FlightPlayer.Listener {
            override fun onSize(w: Int, h: Int) { if (player === me) crop(w, h) }
            override fun onFirstFrame() { if (player === me) { ready = true; reveal(c) } }
            override fun onEnd() { if (player === me) playNext() }
            override fun onError() { if (player === me) failed() }
        })
        me = p
        player = p
        p.start(f?.path, if (f == null) Aerials.source(c) else null, Aerials.headers, previous)
        previous = null
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

    /** "Готовлю заставку · сохранено 3 из 20 · 45 %" while there is no picture yet. */
    private fun showStatus() {
        val noPicture = !revealed
        val want = noPicture && player == null && !musicMode
        if (want) {
            val pct = Aerials.currentPercent
            val t = "Готовлю заставку · сохранено ${Aerials.stored} из ${Aerials.total.takeIf { it > 0 } ?: 20}" + (if (pct >= 0) " · следующий город $pct %" else "")
            if (status.text.toString() != t) status.text = t
        }
        val a = if (want) 1f else 0f
        if (status.alpha != a && status.animate() != null) status.animate().alpha(a).setDuration(500).start()
    }

    private fun reveal(c: Clip) {
        fails = 0
        place.text = c.place
        country.text = c.country.uppercase(Locale("ru"))
        country.visibility = if (c.country.isEmpty()) View.GONE else View.VISIBLE
        labels.requestLayout()
        revealed = true
        curtain.animate().cancel()
        curtain.animate().alpha(0f).setStartDelay(0).setDuration(1200).start()
        syncLabels()
    }

    /** Fills the screen without stretching, if a clip isn't exactly 16:9. */
    private fun crop(w: Int, h: Int) {
        val sw = width
        val sh = height
        if (w <= 0 || h <= 0 || sw <= 0 || sh <= 0) return
        // Fill the screen without stretching: the video plane is made larger than the screen and centred.
        val r = (w.toFloat() / h) / (sw.toFloat() / sh)
        val lw = if (r > 1) (sw * r).toInt() else sw
        val lh = if (r > 1) sh else (sh / r).toInt()
        val lp = video.layoutParams as LayoutParams
        if (lp.width != lw || lp.height != lh) { lp.width = lw; lp.height = lh; video.layoutParams = lp }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val t = timeFmt.format(Clock.now())
            // Setting the same text again would still re-measure the screen over the playing video.
            if (clock.text.toString() != t) clock.text = t
            showStatus()
            refreshCard()
            syncLabels()
            // Music started: the flight stops at once and the music screen takes over.
            // Music stopped for 20 seconds: back to the flights.
            val musicNow = Aerials.musicOn(context)
            if (!musicMode && musicNow) { enterMusic(); main.postDelayed(this, 1000); return }
            if (musicMode) {
                if (musicNow) silentSince = 0L
                else if (silentSince == 0L) silentSince = SystemClock.elapsedRealtime()
                else if (SystemClock.elapsedRealtime() - silentSince > 20_000) { silentSince = 0L; leaveMusic() }
                main.postDelayed(this, 1000)
                return
            }
            val p = player
            val now = SystemClock.elapsedRealtime()
            try {
                if (p != null && !ready) {
                    if (now - startedAt > 30_000) failed()
                } else if (p != null) {
                    val pos = p.positionMs.toInt()
                    val dur = p.durationMs.toInt()
                    val left = dur - pos
                    if (!fading && dur > 4000 && left in 1..1600) {
                        fading = true
                        curtain.animate().alpha(1f).setDuration(1200).start()
                    }
                    // Stuck on a slow connection for 20 seconds: move on.
                    if (pos != lastPos) { lastPos = pos; stuckSince = now }
                    else if (now - stuckSince > 20_000) failed()
                }
            } catch (_: Exception) { failed() }
            main.postDelayed(this, 1000)
        }
    }

    override fun surfaceCreated(holder: android.view.SurfaceHolder) {
        surface = holder.surface
        if (running) playNext()
    }

    override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
        releasePlayer()
        // The decoder must let go of the surface before it disappears.
        previous?.awaitEnd(600)
        surface = null
    }

    override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, w: Int, h: Int) {}
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
