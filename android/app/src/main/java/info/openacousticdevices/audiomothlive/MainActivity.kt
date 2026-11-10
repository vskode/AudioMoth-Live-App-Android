package info.openacousticdevices.audiomothlive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.roundToLong

class MainActivity : Activity(), UsbMicrophone.Listener, PlotView.Listener {

    companion object {
        const val TAG = "AudioMothLive"

        /* index.js */
        val DISPLAY_WIDTH_VALUES = intArrayOf(1, 5, 10, 20, 60)
        const val DEFAULT_DISPLAY_WIDTH_INDEX = 2
        const val DEFAULT_SAMPLE_RATE = 48000

        /* frequencyDisplay.js */
        val FREQUENCY_SCALING_VALUES = intArrayOf(100, 200, 500, 1000, 2000, 5000, 10000)
        const val KHZ = " kHz"

        const val MENU_DIAGNOSTICS = 100
        const val MENU_LOW_AMP = 101
        const val MENU_COLOUR_BASE = 200
        val COLOUR_MAP_NAMES = listOf("Default", "Monochrome", "Inverse monochrome")
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var usbMicrophone: UsbMicrophone

    /* UI */
    private lateinit var inputSpan: TextView
    private lateinit var clockSpan: TextView
    private lateinit var selectionSpan: TextView
    private lateinit var waveformView: PlotView
    private lateinit var spectrogramView: PlotView
    private lateinit var displayWidthControl: SegmentedControl
    private lateinit var sampleRateControl: SegmentedControl
    private lateinit var pausePlayButton: ImageButton

    /* State (index.js) */
    private var currentSampleRate = DEFAULT_SAMPLE_RATE
    private var deviceSampleRate = 0
    private var displayWidth = DISPLAY_WIDTH_VALUES[DEFAULT_DISPLAY_WIDTH_INDEX]
    private var paused = false
    private var redrawPending = true
    private var colourMapIndex = Plotter.COLOUR_MAP_DEFAULT
    private var lowAmpColourScaleEnabled = false

    /* Time display state (timeDisplay.js) */
    private var currentTime = 0L

    private val frame = Engine.Frame()
    private var engineReady = false
    private var frameLoopRunning = false
    private var lastFrameNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!frameLoopRunning) return
            /* TARGET_FRAME_RATE = 60 in index.js */
            if (frameTimeNanos - lastFrameNanos >= 15_000_000L) {
                lastFrameNanos = frameTimeNanos
                updateDisplay()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        val savedRate = prefs.getInt("sampleRate", DEFAULT_SAMPLE_RATE)
        currentSampleRate = if (savedRate in Engine.SAMPLE_RATES) savedRate else DEFAULT_SAMPLE_RATE
        val savedWidthIndex = prefs.getInt("displayWidthIndex", DEFAULT_DISPLAY_WIDTH_INDEX).coerceIn(0, DISPLAY_WIDTH_VALUES.size - 1)
        displayWidth = DISPLAY_WIDTH_VALUES[savedWidthIndex]
        colourMapIndex = prefs.getInt("colourMap", Plotter.COLOUR_MAP_DEFAULT).coerceIn(0, 2)
        lowAmpColourScaleEnabled = prefs.getBoolean("lowAmp", false)

        buildLayout(savedWidthIndex)

        engineReady = Engine.initialise()

        if (!engineReady) {
            inputSpan.text = "Could not allocate audio buffers"
            return
        }

        Engine.nativeChangeSampleRate(currentSampleRate)

        usbMicrophone = UsbMicrophone(this, this)
        usbMicrophone.register()
        usbMicrophone.connect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (engineReady && intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) usbMicrophone.connect()
    }

    override fun onResume() {
        super.onResume()
        if (!engineReady) return
        if (!usbMicrophone.isStreaming) usbMicrophone.connect()
        redrawPending = true
        frameLoopRunning = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onPause() {
        frameLoopRunning = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onPause()
    }

    override fun onDestroy() {
        if (engineReady) usbMicrophone.unregister()
        super.onDestroy()
    }

    /* Layout: mirrors index.html */

    private fun buildLayout(displayWidthIndex: Int) {

        val pad = dp(10f)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BACKGROUND)
        }

        /* Top row: input device (left), selection / clock (right), options */

        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

        inputSpan = bodyText("​")
        clockSpan = bodyText("").apply { gravity = Gravity.END }
        selectionSpan = bodyText("").apply { gravity = Gravity.END; setTextColor(Theme.SELECTION); visibility = TextView.GONE }

        val menuButton = bodyText("⋮").apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(dp(12f), 0, dp(4f), 0)
            setOnClickListener { showOptionsMenu(this) }
        }

        topRow.addView(inputSpan, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        topRow.addView(selectionSpan, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        topRow.addView(clockSpan, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        topRow.addView(menuButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        /* Plots: waveform 30%, spectrogram 70% */

        waveformView = PlotView(this, isWaveform = true).also { it.listener = this }
        spectrogramView = PlotView(this, isWaveform = false).also { it.listener = this }
        waveformView.baselineColour = if (colourMapIndex == Plotter.COLOUR_MAP_DEFAULT) Color.BLACK else Color.GRAY

        val plotHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        plotHolder.addView(waveformView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 30f))
        plotHolder.addView(spectrogramView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 70f).apply { topMargin = pad })

        /* Controls */

        displayWidthControl = SegmentedControl(this, DISPLAY_WIDTH_VALUES.map { "${it}s" }) { i ->
            displayWidth = DISPLAY_WIDTH_VALUES[i]
            prefs.edit().putInt("displayWidthIndex", i).apply()
            redrawPending = true
            Log.i(TAG, "Display width changed to: $displayWidth seconds")
        }.apply { select(displayWidthIndex) }

        pausePlayButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_pause)
            contentDescription = "Pause/play"
            val shape = GradientDrawable().apply { cornerRadius = dp(6f).toFloat(); setColor(Theme.DANGER) }
            background = RippleDrawable(ColorStateList.valueOf(0x33000000), shape, null)
            setOnClickListener { setPause(!paused) }
        }

        val clearButton = bootstrapButton("Clear", Theme.SECONDARY).apply { setOnClickListener { clear() } }

        val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttonRow.addView(pausePlayButton, LinearLayout.LayoutParams(0, dp(40f), 1f).apply { marginEnd = pad })
        buttonRow.addView(clearButton, LinearLayout.LayoutParams(0, dp(40f), 1f))

        val sampleRateLabel = bodyText("Sample rate (kHz):")

        sampleRateControl = SegmentedControl(this, Engine.SAMPLE_RATES.map { (it / 1000).toString() }) { i ->
            val requestedSampleRate = Engine.SAMPLE_RATES[i]
            prefs.edit().putInt("sampleRate", requestedSampleRate).apply()
            Engine.nativeChangeSampleRate(requestedSampleRate)
            Log.i(TAG, "Sample rate changed to: $requestedSampleRate")
        }
        updateSampleRateButtons()

        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controls.addView(displayWidthControl, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad })
        controls.addView(buttonRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad })
        controls.addView(sampleRateLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad })
        controls.addView(sampleRateControl, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4f) })

        root.addView(topRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(4f) })
        root.addView(plotHolder, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)

        /* White background: use dark status/navigation bar icons */
        window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )

        /* Android 15+ forces edge-to-edge: keep content clear of system bars and cutouts */
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(pad + bars.left, pad / 2 + bars.top, pad + bars.right, pad + bars.bottom)
            WindowInsets.CONSUMED
        }
    }

    private fun bodyText(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Theme.BODY_TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Theme.TEXT_SP)
        typeface = Typeface.DEFAULT
        fontFeatureSettings = "tnum"   /* .mono-text { font-variant-numeric: tabular-nums } */
    }

    private fun showOptionsMenu(anchor: android.view.View) {
        val menu = PopupMenu(this, anchor)
        val colours = menu.menu.addSubMenu("Colour map")
        COLOUR_MAP_NAMES.forEachIndexed { i, name ->
            colours.add(1, MENU_COLOUR_BASE + i, i, name).isChecked = i == colourMapIndex
        }
        colours.setGroupCheckable(1, true, true)
        menu.menu.add(0, MENU_LOW_AMP, 1, "Low amplitude colour scale").apply { isCheckable = true; isChecked = lowAmpColourScaleEnabled }
        menu.menu.add(0, MENU_DIAGNOSTICS, 2, "USB diagnostics…")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_LOW_AMP -> {
                    lowAmpColourScaleEnabled = !lowAmpColourScaleEnabled
                    prefs.edit().putBoolean("lowAmp", lowAmpColourScaleEnabled).apply()
                    redrawPending = true
                }
                MENU_DIAGNOSTICS -> {
                    usbMicrophone.disconnect()
                    startActivity(Intent(this, PocActivity::class.java))
                }
                in MENU_COLOUR_BASE until MENU_COLOUR_BASE + 3 -> {
                    colourMapIndex = item.itemId - MENU_COLOUR_BASE
                    prefs.edit().putInt("colourMap", colourMapIndex).apply()
                    waveformView.baselineColour = if (colourMapIndex == Plotter.COLOUR_MAP_DEFAULT) Color.BLACK else Color.GRAY
                    redrawPending = true
                }
            }
            true
        }
        menu.show()
    }

    /* index.js: updateSampleRateButtons() */

    private fun updateSampleRateButtons() {
        Engine.SAMPLE_RATES.forEachIndexed { i, rate ->
            sampleRateControl.setItemEnabled(i, deviceSampleRate == 0 || rate <= deviceSampleRate)
            if (rate == currentSampleRate) sampleRateControl.select(i)
        }
    }

    /* index.js: setPause() / clear */

    private fun setPause(enable: Boolean) {
        paused = enable
        pausePlayButton.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        if (!paused) redrawPending = true
    }

    private fun clear() {
        Engine.nativeClear()
        if (paused) setPause(false)
    }

    /* index.js: updateDisplay() */

    private fun updateDisplay() {

        Engine.getFrame(frame)

        var redraw = false

        if (frame.redrawRequired || frame.currentSampleRate != currentSampleRate) {
            currentSampleRate = frame.currentSampleRate
            updateSampleRateButtons()
            updateSelectionText()
            redraw = true
        }

        if (redrawPending) {
            redrawPending = false
            redraw = true
        }

        if (paused) return

        val wb = waveformView.bitmap ?: return
        val sb = spectrogramView.bitmap ?: return

        val changed = Plotter.nativeUpdate(
            Engine.audioBuffer, Engine.stftBuffer, wb, sb,
            redraw, frame.audioIndex, frame.audioCount, displayWidth.toLong() * currentSampleRate,
            false, lowAmpColourScaleEnabled, colourMapIndex
        )

        if (changed) {
            waveformView.invalidate()
            spectrogramView.invalidate()
        }

        if (frame.state == Engine.STATE_RUNNING && frame.audioCount > 0) {
            currentTime = frame.audioTimeLocal
            clockSpan.text = formatTime(currentTime, false, false)
            if (waveformView.selectionStart != null) updateSelectionText()
        }
    }

    /* UsbMicrophone.Listener */

    override fun onDeviceChanged(deviceName: String?, deviceSampleRate: Int) {
        runOnUiThread {
            this.deviceSampleRate = deviceSampleRate
            inputSpan.text = deviceName ?: "No AudioMoth USB Microphone connected"
            inputSpan.setTextColor(if (deviceName == null) Theme.SECONDARY else Theme.BODY_TEXT)
            if (deviceName == null) clockSpan.text = ""
            updateSampleRateButtons()
            redrawPending = true
        }
    }

    override fun onError(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    /* PlotView.Listener */

    override fun onPlotResized() {
        redrawPending = true
    }

    override fun onSelectionChanged(view: PlotView) {
        /* Only one selection at a time, as with the desktop's single readout span */
        if (view === waveformView) spectrogramView.clearSelection() else waveformView.clearSelection()
        updateSelectionText()
    }

    private fun updateSelectionText() {
        val text = when {
            spectrogramView.selectionStart != null -> frequencyText()
            waveformView.selectionStart != null -> timeText()
            else -> null
        }
        if (text == null) {
            selectionSpan.visibility = TextView.GONE
            clockSpan.visibility = TextView.VISIBLE
        } else {
            selectionSpan.text = text
            selectionSpan.visibility = TextView.VISIBLE
            clockSpan.visibility = TextView.GONE
        }
    }

    /* frequencyDisplay.js */

    private fun scaleFrequency(y: Double, h: Double, nyquist: Double): Double {
        val frequencyPerPixel = nyquist / h
        val frequency = y * frequencyPerPixel
        for (scalingValue in FREQUENCY_SCALING_VALUES) {
            if (frequencyPerPixel < scalingValue) return nyquist - scalingValue * Math.round(frequency / scalingValue)
        }
        return frequency
    }

    private fun frequencyText(): String? {
        val s = spectrogramView.selectionStart ?: return null
        val e = spectrogramView.selectionEnd ?: s
        val h = spectrogramView.axisLength.toDouble()
        val nyquist = currentSampleRate / 2.0
        return if (s == e) {
            "%.1f".format(scaleFrequency(s.toDouble(), h, nyquist) / 1000) + KHZ
        } else {
            val minimumFreq = scaleFrequency(maxOf(s, e).toDouble(), h, nyquist)
            val maximumFreq = scaleFrequency(minOf(s, e).toDouble(), h, nyquist)
            "%.1f - %.1f".format(minimumFreq / 1000, maximumFreq / 1000) + KHZ
        }
    }

    /* timeDisplay.js */

    private fun roundAccordingToDisplayWidth(n: Long): Long {
        val precision = when (displayWidth) { 1 -> 1; 5 -> 5; 10 -> 10; 20 -> 20; 60 -> 50; else -> 1 }
        return (ceil(n.toDouble() / precision) * precision).toLong()
    }

    /** t is already offset to local time, so read it as UTC (as the desktop does) */
    private fun formatTime(t: Long, showMilliseconds: Boolean, roundMilliseconds: Boolean): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = t }
        var hours = c.get(Calendar.HOUR_OF_DAY)
        var mins = c.get(Calendar.MINUTE)
        var secs = c.get(Calendar.SECOND)
        var ms = c.get(Calendar.MILLISECOND).toLong()
        if (roundMilliseconds) ms = roundAccordingToDisplayWidth(ms)
        if (ms >= 1000) { ms = 0; secs++ }
        if (secs == 60) { secs = 0; mins++ }
        if (mins == 60) { mins = 0; hours = (hours + 1) % 24 }
        val str = "%02d:%02d:%02d".format(hours, mins, secs)
        return if (showMilliseconds) str + "." + "%03d".format(ms) else str
    }

    private fun timeText(): String? {
        val s = waveformView.selectionStart ?: return null
        val e = waveformView.selectionEnd ?: s
        val w = waveformView.axisLength.toDouble()
        val displayWidthMs = displayWidth * 1000.0
        val precision = displayWidthMs / 1000.0

        fun position(x: Float, isEnd: Boolean): Pair<Long, Boolean> {
            var time = x / w * displayWidthMs
            var round = true
            if (!isEnd && time <= precision) { round = false; time = 0.0 }
            if (displayWidthMs - time <= precision) { round = false; time = displayWidthMs }
            return Pair((currentTime - displayWidthMs + time).roundToLong(), round)
        }

        return if (s == e) {
            val (t, r) = position(s, false)
            formatTime(t, true, r)
        } else {
            val (t0, r0) = position(minOf(s, e), false)
            val (t1, r1) = position(maxOf(s, e), true)
            formatTime(t0, true, r0) + " - " + formatTime(t1, true, r1)
        }
    }
}
