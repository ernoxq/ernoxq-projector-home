package md.grin.projector

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager

/**
 * Plays one built-in game full screen. Controls: the phone gamepad (straight from RemoteServer, no delay),
 * the projector's own remote, or any Bluetooth gamepad. START / Menu = pause, Back = pause or leave.
 */
class PlayActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var game: MiniGame
    private val input = Input()
    @Volatile private var keys = 0
    @Volatile private var running = false
    private var loop: Thread? = null
    private var paused = false
    private var best = 0
    private var savedBest = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        game = MiniGame.byKey(intent.getStringExtra("game")) ?: run { finish(); return }
        game.reset()
        best = getSharedPreferences("games", MODE_PRIVATE).getInt("best_${game.key}", 0)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val view = SurfaceView(this)
        view.holder.addCallback(this)
        setContentView(view)
    }

    override fun onResume() {
        super.onResume()
        GamePad.phone = 0
        GamePad.menuRequest = false
        GamePad.exitRequest = false
        GamePad.active = true
        Games.playingBuiltin = game.title
    }

    override fun onPause() {
        super.onPause()
        GamePad.active = false
        Games.playingBuiltin = null
        if (!game.over) paused = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        loop = Thread({ run(holder) }, "game").also { it.priority = Thread.MAX_PRIORITY; it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        try { loop?.join(500) } catch (_: InterruptedException) {}
        loop = null
    }

    private fun run(holder: SurfaceHolder) {
        val W = MiniGame.W; val H = MiniGame.H
        val frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val fc = Canvas(frame)
        val gp = Paint()
        val pixel = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Ui.font(this@PlayActivity, 700) }
        val dim = Paint().apply { color = 0xB3000000.toInt() }
        val src = Rect(0, 0, W, H)
        var last = SystemClock.uptimeMillis()
        while (running) {
            val now = SystemClock.uptimeMillis()
            val dt = ((now - last) / 1000f).coerceIn(0f, 0.05f)
            last = now
            input.prev = input.cur
            input.cur = GamePad.phone or keys
            step(dt)
            game.draw(fc, gp)
            val c: Canvas = try { holder.lockHardwareCanvas() } catch (_: Throwable) { holder.lockCanvas() } ?: continue
            try {
                val cw = c.width.toFloat(); val ch = c.height.toFloat()
                val s = minOf(cw / W, ch / H)
                val dst = RectF((cw - W * s) / 2, (ch - H * s) / 2, (cw + W * s) / 2, (ch + H * s) / 2)
                c.drawColor(Color.BLACK)
                c.drawBitmap(frame, src, dst, pixel)
                // HUD at full resolution: crisp text on the projector.
                val u = ch / 720f
                text.textSize = 26 * u
                text.textAlign = Paint.Align.LEFT
                text.alpha = 200
                c.drawText(game.title, dst.left + 24 * u, dst.top + 40 * u, text)
                text.textAlign = Paint.Align.RIGHT
                val right = listOfNotNull(game.extra, "Счёт ${game.score}", "Рекорд ${maxOf(best, game.score)}").joinToString("   ")
                c.drawText(right, dst.right - 24 * u, dst.top + 40 * u, text)
                text.alpha = 255
                text.textAlign = Paint.Align.CENTER
                val msg = game.message
                if (msg != null && !paused && !game.over) { text.textSize = 34 * u; c.drawText(msg, cw / 2, ch * 0.62f, text) }
                if (paused || game.over) {
                    c.drawRect(0f, 0f, cw, ch, dim)
                    text.textSize = 64 * u
                    c.drawText(if (game.over) "Игра окончена" else "Пауза", cw / 2, ch * 0.42f, text)
                    text.textSize = 30 * u
                    text.alpha = 210
                    val sub = if (game.over) "Счёт ${game.score}" + (if (game.score >= best && game.score > 0) " — новый рекорд!" else "   ·   рекорд $best") else game.hint
                    c.drawText(sub, cw / 2, ch * 0.52f, text)
                    text.alpha = 255
                    text.textSize = 28 * u
                    c.drawText(if (game.over) "✕ — ещё раз      ○ — выйти" else "✕ — продолжить      ○ — выйти", cw / 2, ch * 0.66f, text)
                }
            } finally {
                holder.unlockCanvasAndPost(c)
            }
            val spent = SystemClock.uptimeMillis() - now
            if (spent < 16) SystemClock.sleep(16 - spent)
        }
        frame.recycle()
    }

    private fun step(dt: Float) {
        if (GamePad.exitRequest) { GamePad.exitRequest = false; runOnUiThread { finish() }; return }
        if (GamePad.menuRequest) { GamePad.menuRequest = false; if (!game.over) paused = !paused }
        if (game.over) {
            if (!savedBest) {
                savedBest = true
                if (game.score > best) {
                    best = game.score
                    getSharedPreferences("games", MODE_PRIVATE).edit().putInt("best_${game.key}", best).apply()
                }
            }
            if (input.pressed(GamePad.X) || input.pressed(GamePad.START)) { game.reset(); savedBest = false }
            else if (input.pressed(GamePad.O)) runOnUiThread { finish() }
            return
        }
        if (paused) {
            if (input.pressed(GamePad.X) || input.pressed(GamePad.START)) paused = false
            else if (input.pressed(GamePad.O)) runOnUiThread { finish() }
            return
        }
        if (input.pressed(GamePad.START)) { paused = true; return }
        game.update(dt, input)
    }

    // The projector's remote and Bluetooth gamepads.
    private fun bit(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> GamePad.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> GamePad.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> GamePad.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> GamePad.RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> GamePad.X
        KeyEvent.KEYCODE_BUTTON_B -> GamePad.O
        KeyEvent.KEYCODE_BUTTON_X -> GamePad.SQ
        KeyEvent.KEYCODE_BUTTON_Y -> GamePad.TR
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> GamePad.START
        else -> -1
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (paused || game.over) finish() else paused = true
            return true
        }
        val b = bit(keyCode)
        if (b < 0) return super.onKeyDown(keyCode, event)
        keys = keys or (1 shl b)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val b = bit(keyCode)
        if (b < 0) return super.onKeyUp(keyCode, event)
        keys = keys and (1 shl b).inv()
        return true
    }
}
