package info.openacousticdevices.audiomothlive

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Bootstrap 5 colours used by the desktop app (bootstrap.min.css + css/ui.css),
 * and the night mode overrides from css/uiNight.css.
 */
object Theme {
    var night = false

    val BODY_TEXT get() = if (night) Color.WHITE else Color.parseColor("#212529")
    val BACKGROUND get() = if (night) Color.BLACK else Color.WHITE
    val PRIMARY = Color.parseColor("#0d6efd")
    val SECONDARY = Color.parseColor("#6c757d")
    val DANGER = Color.parseColor("#dc3545")
    val SUCCESS = Color.parseColor("#198754")
    val BORDER = Color.parseColor("#dee2e6")
    val SELECTION get() = if (night) Color.WHITE else Color.parseColor("#d11e35")
    val GREY_TEXT = Color.parseColor("#D3D3D3")   /* .grey */
    val ACTIVE_DISABLED_BG = Color.GRAY

    /* .pagination items */
    val PAGE_BG get() = if (night) Color.BLACK else Color.WHITE
    val PAGE_TEXT get() = if (night) Color.WHITE else PRIMARY
    val PAGE_DISABLED_TEXT get() = if (night) Color.LTGRAY else Color.parseColor("#6c757d")
    val PAGE_ACTIVE_BORDER get() = if (night) Color.WHITE else PRIMARY

    /** body { font-size: 10pt } scaled up slightly for a phone */
    const val TEXT_SP = 14f
}

fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

/** Bootstrap .btn */
fun Context.bootstrapButton(text: String, colour: Int): Button = Button(this).apply {
    this.text = text
    isAllCaps = false
    setTextColor(Color.WHITE)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, Theme.TEXT_SP)
    minHeight = dp(40f); minimumHeight = dp(40f)
    setPadding(dp(8f), 0, dp(8f), 0)
    stateListAnimator = null
    setButtonColour(this, colour)
}

/** Bootstrap button background; disabled buttons are drawn at 65% opacity like .btn:disabled */
fun Context.setButtonColour(view: android.view.View, colour: Int) {
    val shape = GradientDrawable().apply { cornerRadius = dp(6f).toFloat(); setColor(colour) }
    view.background = RippleDrawable(ColorStateList.valueOf(0x33000000), shape, null)
}

fun android.view.View.setBootstrapEnabled(enabled: Boolean) {
    isEnabled = enabled
    alpha = if (enabled) 1f else 0.65f
}

/**
 * Bootstrap .pagination as used by the desktop app for sample rate,
 * display width, autosave and monitor selection.
 */
class SegmentedControl(context: Context, labels: List<String>, weights: List<Float>? = null, private val onSelected: (Int) -> Unit) : LinearLayout(context) {

    private val items = mutableListOf<TextView>()
    private val enabledFlags = BooleanArray(labels.size) { true }

    var selectedIndex = -1
        private set

    init {
        orientation = HORIZONTAL
        val radius = context.dp(6f).toFloat()
        labels.forEachIndexed { i, label ->
            val tv = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Theme.TEXT_SP)
                typeface = Typeface.DEFAULT
                minHeight = context.dp(38f)
                setPadding(context.dp(2f), 0, context.dp(2f), 0)
                maxLines = 1
                outlineProvider = null   /* no elevation shadow */
                setOnClickListener { if (enabledFlags[i] && i != selectedIndex) { select(i); onSelected(i) } }
            }
            tv.tag = floatArrayOf(
                if (i == 0) radius else 0f, if (i == 0) radius else 0f,
                if (i == labels.size - 1) radius else 0f, if (i == labels.size - 1) radius else 0f,
                if (i == labels.size - 1) radius else 0f, if (i == labels.size - 1) radius else 0f,
                if (i == 0) radius else 0f, if (i == 0) radius else 0f
            )
            val lp = LayoutParams(0, LayoutParams.WRAP_CONTENT, weights?.get(i) ?: 1f)
            if (i > 0) lp.marginStart = -context.dp(1f)   /* collapse shared borders */
            addView(tv, lp)
            items += tv
        }
        refresh()
    }

    fun select(index: Int) { selectedIndex = index; refresh() }

    fun setItemEnabled(index: Int, enabled: Boolean) { enabledFlags[index] = enabled; refresh() }

    fun setAllEnabled(enabled: Boolean) { enabledFlags.fill(enabled); refresh() }

    fun isItemEnabled(index: Int) = enabledFlags[index]

    private fun refresh() {
        items.forEachIndexed { i, tv ->
            val active = i == selectedIndex
            val enabled = enabledFlags[i]
            val (bg, border, fg) = when {
                active && !enabled -> Triple(Theme.ACTIVE_DISABLED_BG, Theme.ACTIVE_DISABLED_BG, Color.WHITE)
                active -> Triple(Theme.PRIMARY, Theme.PAGE_ACTIVE_BORDER, Color.WHITE)
                !enabled -> Triple(Theme.PAGE_BG, Theme.BORDER, Theme.PAGE_DISABLED_TEXT)
                else -> Triple(Theme.PAGE_BG, Theme.BORDER, Theme.PAGE_TEXT)
            }
            tv.background = GradientDrawable().apply {
                cornerRadii = tv.tag as FloatArray
                setColor(bg)
                setStroke(context.dp(1f), border)
            }
            tv.setTextColor(fg)
            /* Draw the active item's border above its neighbours (bringToFront would reorder a LinearLayout) */
            tv.translationZ = if (active) 1f else 0f
        }
    }
}
