package md.grin.projector

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.Surface

/**
 * Plays only the PICTURE of a screensaver flight: the video track goes straight to the hardware decoder and
 * onto the screen. No audio track, no system media player, no audio focus — the projector's own player took
 * the sound over when a flight started (even a silent one), and Spotify paused every time a city changed.
 */
class FlightPlayer(private val surface: Surface, private val listener: Listener) {
    interface Listener {
        fun onSize(w: Int, h: Int)
        fun onFirstFrame()
        fun onEnd()
        fun onError()
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false
    private var thread: Thread? = null

    /** True once the decoder is running (the clip opened). */
    @Volatile var ready = false
        private set
    @Volatile var positionMs = 0L
        private set
    @Volatile var durationMs = 0L
        private set

    fun start(path: String?, url: String?, headers: Map<String, String>) {
        thread = Thread({ run(path, url, headers) }, "flight").apply { priority = Thread.NORM_PRIORITY + 1; start() }
    }

    /**
     * Stops and frees the decoder. Waits briefly so the next flight can take over the same screen surface
     * (two decoders can't draw into one surface at once).
     */
    fun release() {
        stopped = true
        thread?.let { t -> try { t.join(800) } catch (_: InterruptedException) {} }
        thread = null
    }

    private fun post(block: () -> Unit) = main.post { if (!stopped) block() }

    private fun run(path: String?, url: String?, headers: Map<String, String>) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (path != null) ex.setDataSource(path) else ex.setDataSource(url!!, headers)
            if (stopped) return
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IllegalStateException("no video track")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            durationMs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1000 else 0
            val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
            val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            post { listener.onSize(w, h) }
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, surface, null, 0)
            c.start()
            ready = true

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var first = true
            var firstPts = -1L
            var startNs = 0L
            while (!stopped) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o < 0) continue
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    c.releaseOutputBuffer(o, false)
                    post { listener.onEnd() }
                    break
                }
                // Show each frame at its own time; a late frame is shown at once to catch up.
                if (firstPts < 0) { firstPts = info.presentationTimeUs; startNs = System.nanoTime() }
                val due = startNs + (info.presentationTimeUs - firstPts) * 1000
                val wait = due - System.nanoTime()
                if (wait > 0) Thread.sleep(minOf(wait / 1_000_000, 200L))
                if (stopped) { c.releaseOutputBuffer(o, false); break }
                c.releaseOutputBuffer(o, true)
                positionMs = (info.presentationTimeUs - firstPts) / 1000
                if (first) { first = false; post { listener.onFirstFrame() } }
            }
        } catch (_: Throwable) {
            post { listener.onError() }
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { ex.release() } catch (_: Throwable) {}
        }
    }
}
