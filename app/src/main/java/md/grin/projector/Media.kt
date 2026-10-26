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

/** What's playing right now in any app (Spotify, YouTube Music…), plus its controls. */
object Media {
    private val main = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var attached = false
    private val listeners = LinkedHashSet<() -> Unit>()

    fun hasAccess(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(ctx.packageName)
    }

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }

    private fun changed() {
        main.post { listeners.toList().forEach { it() } }
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = changed()
        override fun onMetadataChanged(metadata: MediaMetadata?) = changed()
        override fun onSessionDestroyed() {
            controller = null
            changed()
        }
    }

    fun attach(ctx: Context) {
        if (attached || !hasAccess(ctx)) return
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
        val c = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        if (c?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = c
            c?.registerCallback(callback, main)
        }
        changed()
    }

    fun current(ctx: Context): NowPlaying? {
        val c = controller ?: return null
        val md = c.metadata ?: return null
        val st = c.playbackState
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val source = try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
        } catch (e: Exception) {
            c.packageName
        }
        val playing = st?.state == PlaybackState.STATE_PLAYING
        var pos = st?.position ?: 0L
        if (playing && st != null && st.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        return NowPlaying(title, artist, source, art, playing, pos.coerceAtLeast(0), dur)
    }

    fun command(cmd: String): Boolean {
        val t = controller?.transportControls ?: return false
        when (cmd) {
            "play" -> if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            "next" -> t.skipToNext()
            "prev" -> t.skipToPrevious()
            else -> return false
        }
        return true
    }
}
