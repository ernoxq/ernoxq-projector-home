package md.grin.projector

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService

/** Android only shows other apps' playing music to a notification listener, so this service exists just for that. */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() {
        Media.attach(this)
    }
}

data class NowPlaying(
    val title: String,
    val artist: String,
    val source: String,
    val art: Bitmap?,
    val playing: Boolean,
    val position: Long,
    val duration: Long,
)

/** What's playing right now in any app (Spotify, YouTube Music, AirPlay…), plus its controls. */
object Media {
    private val main = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var sessions: List<MediaController> = emptyList()
    private var attached = false
    private val listeners = LinkedHashSet<() -> Unit>()
    private val labels = HashMap<String, String>()

    fun hasAccess(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(ctx.packageName)
    }

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }

    private fun changed() {
        main.post { listeners.toList().forEach { it() } }
    }

    /**
     * Watches EVERY open player, not just the chosen one: when another app starts playing,
     * we switch to it, so the card shows the song you actually hear and the buttons control that player.
     */
    private val watch = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = repick()
        override fun onMetadataChanged(metadata: MediaMetadata?) = repick()
        override fun onSessionDestroyed() = repick()
    }

    fun attach(ctx: Context) {
        if (attached || !hasAccess(ctx)) return
        appCtx = ctx.applicationContext
        val msm = ctx.getSystemService(MediaSessionManager::class.java) ?: return
        val cn = ComponentName(ctx, MediaListener::class.java)
        try {
            msm.addOnActiveSessionsChangedListener({ list -> pick(list) }, cn, main)
            pick(msm.getActiveSessions(cn))
            attached = true
        } catch (e: SecurityException) {
            attached = false
        }
    }

    private fun pick(list: List<MediaController>?) {
        sessions.forEach { try { it.unregisterCallback(watch) } catch (_: Exception) {} }
        sessions = list.orEmpty()
        sessions.forEach { it.registerCallback(watch, main) }
        repick()
    }

    private fun repick() {
        val playing = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val keep = controller?.let { cur -> sessions.firstOrNull { it.sessionToken == cur.sessionToken } }
        controller = playing ?: keep ?: sessions.firstOrNull()
        changed()
    }

    // ---------- covers ----------

    @Volatile private var appCtx: Context? = null
    private val covers = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>?) = size > 8
    }
    private val loading = HashSet<String>()

    /**
     * The cover of the current song. Players hand it over in different ways: a picture, a link to one,
     * or the picture a moment later. Whatever we get is kept for that song, so it doesn't blink away
     * when the next update comes without it; a link is downloaded in the background.
     */
    private fun cover(pkg: String, title: String, artist: String, md: MediaMetadata): Bitmap? {
        val key = "$pkg|$title|$artist"
        val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        synchronized(covers) {
            if (bmp != null) { covers[key] = bmp; return bmp }
            covers[key]?.let { return it }
        }
        val uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI) ?: return null
        val ctx = appCtx ?: return null
        synchronized(loading) { if (!loading.add(key)) return null }
        Thread({
            try {
                load(ctx, uri)?.let { b -> synchronized(covers) { covers[key] = b }; changed() }
            } catch (_: Throwable) {
            } finally { synchronized(loading) { loading.remove(key) } }
        }, "cover").start()
        return null
    }

    private fun load(ctx: Context, uri: String): Bitmap? {
        val bytes = if (uri.startsWith("http")) {
            var u = java.net.URL(uri)
            var c: java.net.HttpURLConnection
            var hops = 0
            while (true) {
                c = u.openConnection() as java.net.HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 8000
                c.readTimeout = 8000
                val code = c.responseCode
                if (code in 300..399 && hops < 4) { u = java.net.URL(u, c.getHeaderField("Location")); c.disconnect(); hops++; continue }
                break
            }
            c.inputStream.use { it.readBytes() }.also { c.disconnect() }
        } else {
            ctx.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { it.readBytes() } ?: return null
        }
        // Small is enough for a card: decode at about 512 px so big covers don't eat memory.
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 512) sample *= 2
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun current(ctx: Context): NowPlaying? {
        val c = controller ?: return null
        val md = c.metadata ?: return null
        val st = c.playbackState
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        if (appCtx == null) appCtx = ctx.applicationContext
        val art = cover(c.packageName, title, artist, md)
        val source = labels.getOrPut(c.packageName) {
            try {
                val pm = ctx.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
            } catch (e: Exception) {
                c.packageName
            }
        }
        val playing = st?.state == PlaybackState.STATE_PLAYING
        var pos = st?.position ?: 0L
        if (playing && st != null && st.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        return NowPlaying(title, artist, source, art, playing, pos.coerceAtLeast(0), dur)
    }

    /** When someone last pressed play/pause/next on a remote (to tell a user's pause from an unwanted one). */
    @Volatile var lastCommandAt = 0L

    fun command(cmd: String): Boolean {
        lastCommandAt = SystemClock.elapsedRealtime()
        val t = controller?.transportControls ?: return false
        when (cmd) {
            "play" -> if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            "next" -> t.skipToNext()
            "prev" -> t.skipToPrevious()
            else -> return false
        }
        return true
    }

    /** Starts the player again without counting as someone's press. */
    fun resume(): Boolean {
        val t = controller?.transportControls ?: return false
        t.play()
        return true
    }
}
