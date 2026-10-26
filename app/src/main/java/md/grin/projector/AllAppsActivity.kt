package md.grin.projector

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import md.grin.projector.Ui.MATCH
import md.grin.projector.Ui.px

/** All installed apps as a grid of white cards in the same style as the home screen. */
class AllAppsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        Sounds.init(this)
        Ui.applyTheme(Ui.isNightNow())
        val apps = Apps.all(this)
        val root = Ui.col(this).apply {
            setBackgroundColor(Ui.GROUND)
            setPadding(px(56), px(40), px(56), 0)
        }
        root.addView(Ui.text(this, "Все приложения", 44, 600, Ui.INK).apply { letterSpacing = -0.03f })
        val grid = GridView(this).apply {
            numColumns = 6
            horizontalSpacing = px(16)
            verticalSpacing = px(16)
            setPadding(px(8), px(28), px(8), px(40))
            clipToPadding = false
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setDrawSelectorOnTop(false)
            adapter = object : BaseAdapter() {
                override fun getCount() = apps.size
                override fun getItem(p: Int) = apps[p]
                override fun getItemId(p: Int) = p.toLong()
                override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
                    val a = apps[p]
                    (convert?.tag as? Holder)?.let { h ->
                        h.icon.setImageDrawable(Apps.icon(this@AllAppsActivity, a))
                        h.label.text = a.label
                        return convert
                    }
                    val card = Ui.col(this@AllAppsActivity).apply {
                        setPadding(px(18), px(18), px(18), px(16))
                        background = Ui.round(Ui.WHITE, 28)
                        layoutParams = android.widget.AbsListView.LayoutParams(MATCH, px(150))
                    }
                    val box = FrameLayout(this@AllAppsActivity).apply { background = Ui.round(Ui.SOFT, 16) }
                    val icon = ImageView(this@AllAppsActivity).apply { setImageDrawable(Apps.icon(this@AllAppsActivity, a)) }
                    box.addView(icon, FrameLayout.LayoutParams(px(40), px(40), Gravity.CENTER))
                    card.addView(box, Ui.lp(px(56), px(56)))
                    card.addView(Ui.flex(this@AllAppsActivity))
                    val label = Ui.text(this@AllAppsActivity, a.label, 17, 600)
                    card.addView(label)
                    card.tag = Holder(icon, label)
                    return card
                }
            }
            isSoundEffectsEnabled = false
            setOnItemClickListener { _, _, p, _ -> Sounds.select(); Apps.launch(this@AllAppsActivity, apps[p]) }
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                var last: View? = null
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, p: Int, id: Long) {
                    last?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(140)?.start()
                    last?.background = Ui.round(Ui.WHITE, 28)
                    view?.background = Ui.round(Ui.WHITE, 28, 4, Ui.INK)
                    view?.animate()?.scaleX(1.05f)?.scaleY(1.05f)?.setDuration(160)?.start()
                    last = view
                    Sounds.tick()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        root.addView(grid, Ui.lp(MATCH, 0, 1f))
        setContentView(root)
    }
}

private class Holder(val icon: ImageView, val label: android.widget.TextView)
