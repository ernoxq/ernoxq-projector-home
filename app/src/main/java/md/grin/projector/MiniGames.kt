package md.grin.projector

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The phone gamepad as seen by our own built-in games. RemoteServer writes the phone's buttons here directly
 * (same process: no ADB, no virtual controller, no delay) while a built-in game is on screen.
 */
object GamePad {
    const val X = 0; const val O = 1; const val SQ = 3; const val TR = 4
    const val L1 = 6; const val R1 = 7; const val SELECT = 10; const val START = 11
    const val UP = 13; const val DOWN = 14; const val RIGHT = 15; const val LEFT = 16

    @Volatile var phone = 0
    @Volatile var active = false
    @Volatile var menuRequest = false
    @Volatile var exitRequest = false
}

class Input {
    var cur = 0
    var prev = 0
    fun down(b: Int) = cur and (1 shl b) != 0
    fun pressed(b: Int) = down(b) && prev and (1 shl b) == 0
}

/** A built-in game drawn on a small 320×180 pixel screen, scaled up to the projector. */
abstract class MiniGame(val key: String, val title: String, val hint: String, val color: Int) {
    var score = 0
    var over = false
    /** Text in the middle of the screen (e.g. "Нажми ✕"), drawn crisp at full resolution. */
    open val message: String? get() = null
    open val extra: String? get() = null
    abstract fun reset()
    abstract fun update(dt: Float, i: Input)
    abstract fun draw(c: Canvas, p: Paint)

    protected fun rect(c: Canvas, p: Paint, x: Float, y: Float, w: Float, h: Float, color: Int) {
        p.color = color
        c.drawRect(x, y, x + w, y + h, p)
    }

    companion object {
        const val W = 320
        const val H = 180
        const val BG = 0xFF0E1018.toInt()

        fun all(): List<MiniGame> = listOf(Snake(), Blocks(), Bricks(), Space(), Runner())
        fun byKey(k: String?): MiniGame? = all().firstOrNull { it.key == k }
    }
}

// ---------------------------------------------------------------- Змейка

class Snake : MiniGame("snake", "Змейка", "Ешь яблоки и не врезайся", 0xFF2FA36B.toInt()) {
    private val cols = 32; private val rows = 17; private val cell = 10f; private val top = 10f
    private val body = ArrayDeque<Int>()
    private var dir = 1; private var next = 1; private var t = 0f; private var step = 0.13f; private var food = 0
    private var started = false

    override val message get() = if (!started) "Стрелки — повернуть" else null

    override fun reset() {
        body.clear()
        for (i in 0 until 4) body.addFirst(8 * cols + 5 + i)
        dir = 1; next = 1; t = 0f; step = 0.13f; score = 0; over = false; started = false
        placeFood()
    }

    private fun placeFood() {
        do { food = Random.nextInt(cols * rows) } while (food in body)
    }

    override fun update(dt: Float, i: Input) {
        if (i.pressed(GamePad.UP) && dir != 2) next = 0
        if (i.pressed(GamePad.RIGHT) && dir != 3) next = 1
        if (i.pressed(GamePad.DOWN) && dir != 0) next = 2
        if (i.pressed(GamePad.LEFT) && dir != 1) next = 3
        if (i.cur != 0) started = true
        if (!started) return
        t += dt
        while (t >= step && !over) {
            t -= step
            dir = next
            val h = body.first()
            var x = h % cols; var y = h / cols
            when (dir) { 0 -> y--; 1 -> x++; 2 -> y++; else -> x-- }
            val n = y * cols + x
            val eating = n == food
            if (x !in 0 until cols || y !in 0 until rows || (n in body && !(n == body.last() && !eating))) { over = true; return }
            body.addFirst(n)
            if (eating) { score += 10; step = max(0.06f, step * 0.97f); placeFood() } else body.removeLast()
        }
    }

    override fun draw(c: Canvas, p: Paint) {
        c.drawColor(BG)
        rect(c, p, 0f, top, W.toFloat(), rows * cell, 0xFF151A26.toInt())
        rect(c, p, (food % cols) * cell + 1, top + (food / cols) * cell + 1, cell - 2, cell - 2, 0xFFFF5A5A.toInt())
        body.forEachIndexed { k, v ->
            rect(c, p, (v % cols) * cell + 1, top + (v / cols) * cell + 1, cell - 2, cell - 2,
                if (k == 0) 0xFFB8F5A0.toInt() else 0xFF3CC46A.toInt())
        }
    }
}

// ---------------------------------------------------------------- Блоки (falling-block puzzle)

class Blocks : MiniGame("blocks", "Блоки", "Собирай полные линии", 0xFF7D5BD6.toInt()) {
    private val bw = 10; private val bh = 20; private val cell = 8f
    private val ox = 120f; private val oy = 10f
    private val board = IntArray(bw * bh)
    private val shapes = listOf(
        4 to listOf(0 to 1, 1 to 1, 2 to 1, 3 to 1),
        2 to listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1),
        3 to listOf(1 to 0, 0 to 1, 1 to 1, 2 to 1),
        3 to listOf(1 to 0, 2 to 0, 0 to 1, 1 to 1),
        3 to listOf(0 to 0, 1 to 0, 1 to 1, 2 to 1),
        3 to listOf(0 to 0, 0 to 1, 1 to 1, 2 to 1),
        3 to listOf(2 to 0, 0 to 1, 1 to 1, 2 to 1),
    )
    private val colors = intArrayOf(0xFF4FD1E8.toInt(), 0xFFF5D547.toInt(), 0xFFB57BF2.toInt(), 0xFF5EDB7A.toInt(),
        0xFFF2626B.toInt(), 0xFF5B8DEF.toInt(), 0xFFF59D47.toInt())
    private var bag = ArrayList<Int>()
    private var type = 0; private var nextType = 0; private var n = 3
    private var cells = listOf<Pair<Int, Int>>()
    private var px = 0; private var py = 0
    private var fall = 0f; private var lines = 0; private var dasT = 0f; private var dasDir = 0; private var soft = 0f

    override val extra get() = "Линии $lines"

    private fun draw7(): Int {
        if (bag.isEmpty()) bag = ArrayList((0 until 7).shuffled())
        return bag.removeAt(bag.size - 1)
    }

    override fun reset() {
        board.fill(0); bag.clear(); score = 0; lines = 0; over = false; fall = 0f
        nextType = draw7(); spawn()
    }

    private fun spawn() {
        type = nextType; nextType = draw7()
        n = shapes[type].first; cells = shapes[type].second
        px = if (n == 2) 4 else 3; py = 0
        if (!fits(cells, px, py)) over = true
    }

    private fun fits(cs: List<Pair<Int, Int>>, x: Int, y: Int): Boolean = cs.all { (cx, cy) ->
        val bx = x + cx; val by = y + cy
        bx in 0 until bw && by < bh && (by < 0 || board[by * bw + bx] == 0)
    }

    private fun rotated(cw: Boolean) = cells.map { (x, y) -> if (cw) (n - 1 - y) to x else y to (n - 1 - x) }

    private fun rotate(cw: Boolean) {
        if (n == 2) return
        val r = rotated(cw)
        for (k in intArrayOf(0, -1, 1, -2, 2)) if (fits(r, px + k, py)) { cells = r; px += k; return }
    }

    private fun lock() {
        for ((cx, cy) in cells) { val by = py + cy; if (by < 0) { over = true; return }; board[by * bw + px + cx] = type + 1 }
        var cleared = 0
        var y = bh - 1
        while (y >= 0) {
            if ((0 until bw).all { board[y * bw + it] != 0 }) {
                for (yy in y downTo 1) for (x in 0 until bw) board[yy * bw + x] = board[(yy - 1) * bw + x]
                for (x in 0 until bw) board[x] = 0
                cleared++
            } else y--
        }
        lines += cleared
        score += intArrayOf(0, 100, 300, 500, 800)[cleared] * level()
        spawn()
    }

    private fun level() = 1 + lines / 10

    override fun update(dt: Float, i: Input) {
        if (i.pressed(GamePad.X) || i.pressed(GamePad.UP)) rotate(true)
        if (i.pressed(GamePad.O) || i.pressed(GamePad.SQ)) rotate(false)
        if (i.pressed(GamePad.TR)) {
            while (fits(cells, px, py + 1)) { py++; score += 2 }
            lock(); return
        }
        val dirNow = if (i.down(GamePad.LEFT)) -1 else if (i.down(GamePad.RIGHT)) 1 else 0
        if (dirNow != 0 && (dirNow != dasDir)) { dasDir = dirNow; dasT = 0f; if (fits(cells, px + dirNow, py)) px += dirNow }
        else if (dirNow != 0) {
            dasT += dt
            if (dasT > 0.17f) { dasT -= 0.05f; if (fits(cells, px + dirNow, py)) px += dirNow }
        } else dasDir = 0
        if (i.down(GamePad.DOWN)) {
            soft += dt
            while (soft > 0.04f) { soft -= 0.04f; if (fits(cells, px, py + 1)) { py++; score += 1 } else { lock(); return } }
        } else soft = 0f
        fall += dt
        val g = max(0.08f, 0.8f - (level() - 1) * 0.07f)
        while (fall >= g) {
            fall -= g
            if (fits(cells, px, py + 1)) py++ else { lock(); return }
        }
    }

    override fun draw(c: Canvas, p: Paint) {
        c.drawColor(BG)
        rect(c, p, ox - 2, oy - 2, bw * cell + 4, bh * cell + 4, 0xFF2A3042.toInt())
        rect(c, p, ox, oy, bw * cell, bh * cell, 0xFF141824.toInt())
        for (y in 0 until bh) for (x in 0 until bw) {
            val v = board[y * bw + x]
            if (v != 0) block(c, p, ox + x * cell, oy + y * cell, colors[v - 1])
        }
        var gy = py
        while (fits(cells, px, gy + 1)) gy++
        for ((cx, cy) in cells) if (gy + cy >= 0) rect(c, p, ox + (px + cx) * cell + 1, oy + (gy + cy) * cell + 1, cell - 2, cell - 2, 0x33FFFFFF)
        for ((cx, cy) in cells) if (py + cy >= 0) block(c, p, ox + (px + cx) * cell, oy + (py + cy) * cell, colors[type])
        // Next piece
        rect(c, p, 222f, 20f, 44f, 36f, 0xFF141824.toInt())
        val (nn, nc) = shapes[nextType]
        val off = if (nn == 4) 228f else if (nn == 2) 236f else 232f
        for ((cx, cy) in nc) block(c, p, off + cx * cell, 28f + cy * cell, colors[nextType])
    }

    private fun block(c: Canvas, p: Paint, x: Float, y: Float, color: Int) {
        rect(c, p, x, y, cell - 1, cell - 1, color)
        rect(c, p, x, y, cell - 1, 1f, 0x55FFFFFF)
    }
}

// ---------------------------------------------------------------- Кирпичи (bat and ball)

class Bricks : MiniGame("bricks", "Кирпичи", "Отбивай мяч и ломай стену", 0xFFE08A1E.toInt()) {
    private val cols = 10; private val rows = 6
    private val bricks = BooleanArray(cols * rows)
    private var padX = 140f; private val padW = 40f; private val padY = 166f
    private var bx = 0f; private var by = 0f; private var vx = 0f; private var vy = 0f
    private var stuck = true; private var lives = 3; private var speed = 140f; private var level = 1
    private val rowColors = intArrayOf(0xFFF2626B.toInt(), 0xFFF59D47.toInt(), 0xFFF5D547.toInt(), 0xFF5EDB7A.toInt(), 0xFF4FD1E8.toInt(), 0xFFB57BF2.toInt())

    override val message get() = if (stuck) "✕ — запустить мяч" else null
    override val extra get() = "Жизни $lives"

    override fun reset() { bricks.fill(true); lives = 3; score = 0; over = false; level = 1; speed = 140f; stuck = true; padX = 140f }

    override fun update(dt: Float, i: Input) {
        if (i.down(GamePad.LEFT)) padX -= 230f * dt
        if (i.down(GamePad.RIGHT)) padX += 230f * dt
        padX = padX.coerceIn(0f, W - padW)
        if (stuck) {
            bx = padX + padW / 2; by = padY - 4
            if (i.pressed(GamePad.X) || i.pressed(GamePad.UP)) {
                stuck = false; vx = speed * 0.5f; vy = -speed * 0.87f
            }
            return
        }
        val steps = 4
        repeat(steps) {
            val sx = bx; val sy = by
            bx += vx * dt / steps; by += vy * dt / steps
            if (bx < 2) { bx = 2f; vx = abs(vx) }
            if (bx > W - 2) { bx = W - 2f; vx = -abs(vx) }
            if (by < 12) { by = 12f; vy = abs(vy) }
            if (vy > 0 && by + 2 >= padY && by - 2 <= padY + 4 && bx >= padX - 2 && bx <= padX + padW + 2) {
                val hit = ((bx - padX) / padW - 0.5f).coerceIn(-0.5f, 0.5f)
                vx = speed * hit * 1.6f
                vy = -kotlin.math.sqrt(max(speed * speed - vx * vx, speed * speed * 0.2f))
            }
            val c = ((bx - 1) / 32).toInt(); val r = ((by - 24) / 10).toInt()
            if (by >= 24 && r in 0 until rows && c in 0 until cols && bricks[r * cols + c]) {
                bricks[r * cols + c] = false
                score += 10 * (rows - r)
                val pc = ((sx - 1) / 32).toInt()
                if (pc != c) vx = -vx else vy = -vy
                speed += 1.5f
            }
            if (by > H + 4) {
                lives--
                if (lives <= 0) { over = true; return }
                stuck = true
                return
            }
        }
        if (bricks.none { it }) { level++; bricks.fill(true); speed = 140f + level * 15; stuck = true }
    }

    override fun draw(c: Canvas, p: Paint) {
        c.drawColor(BG)
        for (r in 0 until rows) for (k in 0 until cols) if (bricks[r * cols + k]) {
            rect(c, p, 1f + k * 32 + 1, 24f + r * 10, 30f, 8f, rowColors[r])
            rect(c, p, 1f + k * 32 + 1, 24f + r * 10, 30f, 1f, 0x55FFFFFF)
        }
        rect(c, p, padX, padY, padW, 4f, 0xFFE8ECF5.toInt())
        rect(c, p, bx - 2, by - 2, 4f, 4f, 0xFFFFFFFF.toInt())
    }
}

// ---------------------------------------------------------------- Космос (shooter)

class Space : MiniGame("space", "Космос", "Стреляй ✕, уворачивайся", 0xFF3A6FD8.toInt()) {
    private class E(var x: Float, var y: Float, val ph: Float, val vy: Float, val kind: Int)
    private val enemies = ArrayList<E>()
    private val shots = ArrayList<FloatArray>()
    private val stars = Array(40) { floatArrayOf(Random.nextFloat() * W, Random.nextFloat() * H, 10f + Random.nextFloat() * 40f) }
    private var sx = 160f; private var sy = 160f; private var cool = 0f; private var spawnT = 0f; private var time = 0f
    private var lives = 3; private var hurt = 0f

    override val extra get() = "Жизни $lives"

    override fun reset() { enemies.clear(); shots.clear(); sx = 160f; sy = 160f; lives = 3; score = 0; over = false; time = 0f; hurt = 0f }

    override fun update(dt: Float, i: Input) {
        time += dt
        for (s in stars) { s[1] += s[2] * dt; if (s[1] > H) { s[1] = 0f; s[0] = Random.nextFloat() * W } }
        if (i.down(GamePad.LEFT)) sx -= 150f * dt
        if (i.down(GamePad.RIGHT)) sx += 150f * dt
        if (i.down(GamePad.UP)) sy -= 110f * dt
        if (i.down(GamePad.DOWN)) sy += 110f * dt
        sx = sx.coerceIn(8f, W - 8f); sy = sy.coerceIn(110f, H - 10f)
        cool -= dt; hurt -= dt
        if ((i.down(GamePad.X) || i.down(GamePad.SQ)) && cool <= 0) { cool = 0.2f; shots += floatArrayOf(sx, sy - 8) }
        shots.forEach { it[1] -= 260f * dt }
        shots.removeAll { it[1] < 8 }
        spawnT -= dt
        val lvl = 1 + (time / 20).toInt()
        if (spawnT <= 0) {
            spawnT = max(0.35f, 1.3f - lvl * 0.12f)
            enemies += E(16 + Random.nextFloat() * (W - 32), 8f, Random.nextFloat() * 6f, 22f + lvl * 4 + Random.nextFloat() * 10, Random.nextInt(3))
        }
        val iter = enemies.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            e.y += e.vy * dt
            e.x += sin(time * 2 + e.ph) * 30f * dt
            val hit = shots.firstOrNull { abs(it[0] - e.x) < 7 && abs(it[1] - e.y) < 6 }
            if (hit != null) { shots.remove(hit); iter.remove(); score += 10; continue }
            if (abs(e.x - sx) < 10 && abs(e.y - sy) < 8 && hurt <= 0) { iter.remove(); damage(); continue }
            if (e.y > H + 6) { iter.remove(); damage() }
        }
    }

    private fun damage() { lives--; hurt = 1.2f; if (lives <= 0) over = true }

    override fun draw(c: Canvas, p: Paint) {
        c.drawColor(0xFF05060C.toInt())
        for (s in stars) rect(c, p, s[0], s[1], 1f, 1f, if (s[2] > 35) 0xFFFFFFFF.toInt() else 0xFF6B7390.toInt())
        for (s in shots) rect(c, p, s[0] - 1, s[1] - 3, 2f, 6f, 0xFFF5D547.toInt())
        for (e in enemies) {
            val col = intArrayOf(0xFFF2626B.toInt(), 0xFFB57BF2.toInt(), 0xFF5EDB7A.toInt())[e.kind]
            rect(c, p, e.x - 6, e.y - 3, 12f, 6f, col)
            rect(c, p, e.x - 3, e.y - 6, 6f, 3f, col)
            rect(c, p, e.x - 6, e.y + 3, 2f, 3f, col); rect(c, p, e.x + 4, e.y + 3, 2f, 3f, col)
        }
        if (hurt <= 0 || (hurt * 10).toInt() % 2 == 0) {
            rect(c, p, sx - 7, sy, 14f, 5f, 0xFF7FA8FF.toInt())
            rect(c, p, sx - 2, sy - 7, 4f, 7f, 0xFFE8ECF5.toInt())
            rect(c, p, sx - 1, sy + 5, 2f, 2f, 0xFFF59D47.toInt())
        }
    }
}

// ---------------------------------------------------------------- Прыгун (endless runner)

class Runner : MiniGame("runner", "Прыгун", "✕ — прыжок через препятствия", 0xFFF2626B.toInt()) {
    private val ground = 150f
    private var y = 0f; private var vy = 0f; private var dist = 0f; private var speed = 120f
    private val obs = ArrayList<FloatArray>()  // x, w, h
    private var gap = 0f; private var started = false

    override val message get() = if (!started) "✕ — прыжок" else null

    override fun reset() { y = 0f; vy = 0f; dist = 0f; speed = 120f; obs.clear(); gap = 200f; score = 0; over = false; started = false }

    override fun update(dt: Float, i: Input) {
        val jump = i.pressed(GamePad.X) || i.pressed(GamePad.UP)
        if (!started) { if (jump) started = true else return }
        if (jump && y == 0f) vy = 250f
        if (y > 0f || vy > 0f) {
            // Holding the button makes the jump a little higher.
            vy -= (if (i.down(GamePad.X) || i.down(GamePad.UP)) 560f else 900f) * dt
            y = max(0f, y + vy * dt)
            if (y == 0f) vy = 0f
        }
        speed = min(260f, speed + 4f * dt)
        dist += speed * dt
        score = (dist / 10).toInt()
        for (o in obs) o[0] -= speed * dt
        obs.removeAll { it[0] + it[1] < 0 }
        gap -= speed * dt
        if (gap <= 0) {
            val w = 8f + Random.nextInt(3) * 6f
            val h = 10f + Random.nextInt(4) * 5f
            obs += floatArrayOf(W.toFloat(), w, h)
            gap = 110f + Random.nextFloat() * 120f + speed * 0.3f
        }
        val px = 50f; val pw = 10f
        for (o in obs) if (px + pw > o[0] + 1 && px < o[0] + o[1] - 1 && y < o[2] - 1) over = true
    }

    override fun draw(c: Canvas, p: Paint) {
        c.drawColor(0xFF141A2B.toInt())
        rect(c, p, 0f, ground, W.toFloat(), H - ground, 0xFF2A3350.toInt())
        rect(c, p, 0f, ground, W.toFloat(), 1f, 0xFF6B7390.toInt())
        val off = (dist % 40)
        var gx = -off
        while (gx < W) { rect(c, p, gx, ground + 8, 12f, 1f, 0xFF3C4668.toInt()); gx += 40f }
        for (o in obs) {
            rect(c, p, o[0], ground - o[2], o[1], o[2], 0xFF5EDB7A.toInt())
            rect(c, p, o[0], ground - o[2], o[1], 2f, 0xFFA8F0B8.toInt())
        }
        val top = ground - 14 - y
        rect(c, p, 50f, top, 10f, 14f, 0xFFF2626B.toInt())
        rect(c, p, 55f, top + 3, 3f, 3f, 0xFFFFFFFF.toInt())
    }
}
