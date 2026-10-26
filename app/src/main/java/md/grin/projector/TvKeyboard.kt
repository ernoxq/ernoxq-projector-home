package md.grin.projector

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest

/**
 * Our own on-screen keyboard in the spirit of Apple TV: a dark glass panel, soft white letters,
 * and a white highlight that glides between keys. Driven by the remote's arrows, and it also
 * accepts live text from the phone remote.
 */
class TvKeyboard : InputMethodService() {
    private lateinit var board: BoardView
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
        Ui.init(this)
        Sounds.init(this)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        board = BoardView(this) { press(it) }
        return board
    }

    // Keep the app visible above the keyboard instead of a full-screen text box.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        active = true
        changedAt = System.currentTimeMillis()
        text = currentText()
        if (::board.isInitialized) board.reset()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        active = false
        super.onFinishInputView(finishingInput)
    }

    fun currentText(): String =
        currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString() ?: ""

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isInputViewShown || !::board.isInitialized) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> board.move(0, -1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> board.move(0, 1)
            KeyEvent.KEYCODE_DPAD_UP -> board.move(-1, 0)
            KeyEvent.KEYCODE_DPAD_DOWN -> board.move(1, 0)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) board.pressFocused()
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isInputViewShown && keyCode in setOf(
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            )) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun press(k: BoardView.Key) {
        val ic = currentInputConnection ?: return
        Sounds.select()
        when (k.action) {
            BoardView.Action.CHAR -> ic.commitText(k.label, 1)
            BoardView.Action.SPACE -> ic.commitText(" ", 1)
            BoardView.Action.DELETE -> ic.deleteSurroundingText(1, 0)
            BoardView.Action.DONE -> done()
            BoardView.Action.LANG -> if (::board.isInitialized) board.toggleLang()
            BoardView.Action.MODE -> if (::board.isInitialized) board.toggleMode()
        }
        text = currentText()
    }

    private fun done() {
        val ic = currentInputConnection ?: return
        val action = (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    /** Replaces the field's text with what's typed on the phone. */
    private fun replaceAll(value: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText(value, 1)
        ic.endBatchEdit()
        text = value
    }

    companion object {
        @Volatile var instance: TvKeyboard? = null
        @Volatile var active = false
        @Volatile var text = ""
        @Volatile var changedAt = 0L

        fun setText(value: String): Boolean {
            val kb = instance ?: return false
            if (!active) return false
            kb.main.post { kb.replaceAll(value) }
            return true
        }

        fun insert(value: String): Boolean {
            val kb = instance ?: return false
            if (!active) return false
            kb.main.post { kb.currentInputConnection?.commitText(value, 1); text = kb.currentText() }
            return true
        }

        fun isDefault(ctx: android.content.Context): Boolean {
            val cur = android.provider.Settings.Secure.getString(ctx.contentResolver, "default_input_method") ?: return false
            return cur.startsWith(ctx.packageName + "/")
        }

        fun submit(): Boolean {
            val kb = instance ?: return false
            if (!active) return false
            kb.main.post { kb.done() }
            return true
        }
    }
}

/** Draws the whole keyboard on one canvas: cheap to render on the projector's weak GPU. */
class BoardView(ctx: Context, private val onPress: (Key) -> Unit) : View(ctx) {
    enum class Action { CHAR, SPACE, DELETE, DONE, LANG, MODE }
    data class Key(val label: String, val action: Action = Action.CHAR, val weight: Float = 1f)

    private var ru = true
    private var numbers = false
    private var rows: List<List<Key>> = emptyList()
    private var r = 0
    private var c = 0

    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF01C1C1E.toInt() }
    private val keyText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE5E5EA.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Ui.font(ctx, 500)
    }
    private val focusText = Paint(keyText).apply { color = 0xFF121212.toInt(); typeface = Ui.font(ctx, 600) }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val special = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }

    private val hl = RectF()
    private val from = RectF()
    private val to = RectF()
    private var anim: ValueAnimator? = null

    init {
        isFocusable = false
        build()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Ui.px(300))
    }

    private fun build() {
        val bottom = listOf(
            Key(if (numbers) "АБВ" else "123", Action.MODE, 1.6f),
            Key(if (ru) "EN" else "РУС", Action.LANG, 1.6f),
            Key("Пробел", Action.SPACE, 5f),
            Key("⌫", Action.DELETE, 1.6f),
            Key("Готово", Action.DONE, 2.2f),
        )
        val letters = when {
            numbers -> listOf("1234567890", "@#&_-+()/=", ".,?!:;'\"*%")
            ru -> listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю.")
            else -> listOf("qwertyuiop", "asdfghjkl", "zxcvbnm.-")
        }
        rows = letters.map { line -> line.map { Key(it.toString()) } } + listOf(bottom)
        r = r.coerceIn(0, rows.size - 1)
        c = c.coerceIn(0, rows[r].size - 1)
    }

    fun reset() {
        r = 0; c = 0
        build()
        hl.setEmpty()
        invalidate()
    }

    fun toggleLang() { ru = !ru; numbers = false; build(); snap(); invalidate() }
    fun toggleMode() { numbers = !numbers; build(); snap(); invalidate() }

    fun move(dr: Int, dc: Int) {
        if (rows.isEmpty()) return
        if (dr != 0) {
            val nr = (r + dr).coerceIn(0, rows.size - 1)
            if (nr == r) return
            // Land on the key whose centre is closest to the one we're leaving.
            val x = keyRect(r, c).centerX()
            r = nr
            c = rows[r].indices.minByOrNull { kotlin.math.abs(keyRect(r, it).centerX() - x) } ?: 0
        } else {
            val nc = (c + dc).coerceIn(0, rows[r].size - 1)
            if (nc == c) return
            c = nc
        }
        Sounds.tick()
        glide()
    }

    fun pressFocused() {
        val k = rows.getOrNull(r)?.getOrNull(c) ?: return
        onPress(k)
    }

    private fun padX() = Ui.pxf(120)
    private fun rowTop(i: Int) = Ui.pxf(28) + i * Ui.pxf(64)
    private fun keyH() = Ui.pxf(52)

    private fun keyRect(ri: Int, ci: Int): RectF {
        val row = rows[ri]
        val total = row.sumOf { it.weight.toDouble() }.toFloat()
        val gap = Ui.pxf(8)
        val avail = width - 2 * padX()
        // Letter rows use a fixed key size centred on screen; the bottom row stretches.
        val unit = if (ri < rows.size - 1) minOf(Ui.pxf(78), (avail - gap * (row.size - 1)) / row.size) else (avail - gap * (row.size - 1)) / total
        val rowW = row.sumOf { (it.weight * unit).toDouble() }.toFloat() + gap * (row.size - 1)
        var x = (width - rowW) / 2
        for (i in 0 until ci) x += row[i].weight * unit + gap
        val top = rowTop(ri)
        return RectF(x, top, x + row[ci].weight * unit, top + keyH())
    }

    private fun snap() {
        if (width == 0) return
        hl.set(keyRect(r, c))
    }

    private fun glide() {
        to.set(keyRect(r, c))
        if (hl.isEmpty) { hl.set(to); invalidate(); return }
        from.set(hl)
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 110
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                val t = it.animatedValue as Float
                hl.set(
                    from.left + (to.left - from.left) * t, from.top + (to.top - from.top) * t,
                    from.right + (to.right - from.right) * t, from.bottom + (to.bottom - from.bottom) * t,
                )
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val rad = Ui.pxf(28)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height + rad, rad, rad, panel)
        if (hl.isEmpty) snap()
        keyText.textSize = Ui.pxf(26)
        focusText.textSize = Ui.pxf(26)
        rows.forEachIndexed { ri, row ->
            row.forEachIndexed { ci, k ->
                val rect = keyRect(ri, ci)
                if (k.action != Action.CHAR) canvas.drawRoundRect(rect, Ui.pxf(14), Ui.pxf(14), special)
            }
        }
        canvas.drawRoundRect(hl, Ui.pxf(14), Ui.pxf(14), highlight)
        rows.forEachIndexed { ri, row ->
            row.forEachIndexed { ci, k ->
                val rect = keyRect(ri, ci)
                val p = if (ri == r && ci == c) focusText else keyText
                p.textSize = if (k.action == Action.CHAR) Ui.pxf(26) else Ui.pxf(19)
                val y = rect.centerY() - (p.descent() + p.ascent()) / 2
                canvas.drawText(k.label, rect.centerX(), y, p)
            }
        }
    }
}
