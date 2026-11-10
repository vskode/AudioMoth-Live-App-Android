package info.openacousticdevices.audiomothlive

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * One plot (waveform or spectrogram): a bitmap filled by plotter.c, plus
 * the overlays the desktop app draws with SVG: the waveform baseline, the
 * red time/frequency selection, and the 1px border.
 */
class PlotView(context: Context, private val isWaveform: Boolean) : View(context) {

    interface Listener {
        fun onPlotResized()
        fun onSelectionChanged(view: PlotView)
    }

    var listener: Listener? = null

    var bitmap: Bitmap? = null
        private set

    /* Selection in pixels along the time (x) or frequency (y) axis. Null = none. */
    var selectionStart: Float? = null
        private set
    var selectionEnd: Float? = null
        private set

    var baselineColour = Color.BLACK

    private val bitmapPaint = Paint().apply { isFilterBitmap = false }
    private val borderPaint = Paint().apply { style = Paint.Style.STROKE; color = Theme.BODY_TEXT; strokeWidth = 1f }
    private val linePaint = Paint().apply { style = Paint.Style.STROKE; color = Color.RED; strokeWidth = 1f }
    private val fillPaint = Paint().apply { style = Paint.Style.FILL; color = Color.RED; alpha = (0.3f * 255).toInt() }

    /** Length of the selection axis in pixels */
    val axisLength get() = if (isWaveform) width.toFloat() else height.toFloat()

    fun clearSelection() {
        if (selectionStart == null) return
        selectionStart = null
        selectionEnd = null
        invalidate()
        listener?.onSelectionChanged(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        bitmap?.recycle()
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        listener?.onPlotResized()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pos = (if (isWaveform) event.x else event.y).coerceIn(0f, axisLength)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { selectionStart = pos; selectionEnd = pos; parent.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> selectionEnd = pos
            MotionEvent.ACTION_CANCEL -> { selectionStart = null; selectionEnd = null }
            else -> return true
        }
        invalidate()
        listener?.onSelectionChanged(this)
        return true
    }

    override fun onDraw(canvas: Canvas) {

        canvas.drawColor(Theme.BACKGROUND)

        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }

        val w = width.toFloat()
        val h = height.toFloat()

        /* drawWaveformBaseline() in index.js */
        if (isWaveform) {
            linePaint.color = baselineColour
            val y = Math.floor(h / 2.0).toFloat() + 0.5f
            canvas.drawLine(0f, y, w, y, linePaint)
        }

        /* Selection (timeDisplay.js / frequencyDisplay.js) */
        val s = selectionStart
        val e = selectionEnd
        if (s != null && e != null) {
            linePaint.color = Color.RED
            val a = Math.floor(minOf(s, e).toDouble()).toFloat() + 0.5f
            val b = Math.floor(maxOf(s, e).toDouble()).toFloat() + 0.5f
            if (isWaveform) {
                if (b - a >= 1f) canvas.drawRect(a, 0f, b, h, fillPaint)
                canvas.drawLine(a, 0f, a, h, linePaint)
                if (b - a >= 1f) canvas.drawLine(b, 0f, b, h, linePaint)
            } else {
                if (b - a >= 1f) canvas.drawRect(0f, a, w, b, fillPaint)
                canvas.drawLine(0f, a, w, a, linePaint)
                if (b - a >= 1f) canvas.drawLine(0f, b, w, b, linePaint)
            }
        }

        /* border-style: solid; border-width: 1px */
        canvas.drawRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, borderPaint)
    }
}
