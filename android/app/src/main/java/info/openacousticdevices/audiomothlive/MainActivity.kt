package info.openacousticdevices.audiomothlive

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Main window: Android port of index.html / js/index.js on top of the
 * ported backstage engine (Backstage.kt) and plotter (Plotter.kt).
 */
class MainActivity : Activity(), UsbMicrophone.Listener, PlotView.Listener {

    companion object {
        const val TAG = "AudioMothLive"
        const val REQUEST_RECORD_AUDIO = 1
        const val REQUEST_FILE_DESTINATION = 2

        /* index.js pagination values */
        val SAMPLE_RATE_VALUES = intArrayOf(8000, 16000, 32000, 48000, 96000, 192000, 250000, 384000)
        val DISPLAY_WIDTH_VALUES = intArrayOf(1, 5, 10, 20, 60)
        val AUTOSAVE_LENGTH_VALUES = intArrayOf(-1, 1, 5, 10, 60)   /* -1 = off */
        val MONITOR_LABELS = listOf("Off", "Monitor", "Heterodyne")

        const val DEFAULT_SAMPLE_RATE = 48000
        const val DEFAULT_DISPLAY_WIDTH_INDEX = 2

        const val HETERODYNE_FREQUENCY_MIN = 12000

        /* Hold-to-repeat constants (index.js) */
        const val HOLD_DELAY_MULTIPLIER = 0.9
        const val HOLD_INITIAL_DELAY = 250L
        const val HOLD_MIN_DELAY = 5L

        /* frequencyDisplay.js */
        val FREQUENCY_SCALING_VALUES = intArrayOf(100, 200, 500, 1000, 2000, 5000, 10000)
        const val KHZ = " kHz"

        val COLOUR_MAP_NAMES = listOf("Default", "Monochrome", "Inverse monochrome")

        const val MENU_LOCAL_TIME = 100
        const val MENU_NIGHT_MODE = 101
        const val MENU_LOW_AMP = 102
        const val MENU_SIMULATE = 103
        const val MENU_SAVE_FOLDER = 104
        const val MENU_DIAGNOSTICS = 105
        const val MENU_COLOUR_BASE = 200
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var usbMicrophone: UsbMicrophone
    private val handler = Handler(Looper.getMainLooper())

    /* UI elements */
    private lateinit var inputSpan: TextView
    private lateinit var clockSpan: TextView
    private lateinit var selectionSpan: TextView
    private lateinit var waveformView: PlotView
    private lateinit var spectrogramView: PlotView
    private lateinit var displayWidthPagination: SegmentedControl
    private lateinit var sampleRatePagination: SegmentedControl
    private lateinit var autosaveLengthPagination: SegmentedControl
    private lateinit var monitorPagination: SegmentedControl
    private lateinit var pausePlayButton: ImageButton
    private lateinit var clearButton: TextView
    private lateinit var captureButton: TextView
    private lateinit var heterodyneSpan: TextView
    private lateinit var heterodyneDownButton: ImageButton
    private lateinit var heterodyneUpButton: ImageButton

    /* State (index.js variables) */
    private var currentSampleRate = DEFAULT_SAMPLE_RATE
    private var maximumSampleRate = DEFAULT_SAMPLE_RATE
    private var heterodyneFrequencyMax = currentSampleRate / 2
    private var heterodyneFrequency = heterodyneFrequencyMax / 2
    private var displayWidthIndex = DEFAULT_DISPLAY_WIDTH_INDEX
    private val displayWidth get() = DISPLAY_WIDTH_VALUES[displayWidthIndex]
    private var displayWidthChanged = false
    private var canvasSizeChanged = false
    private var colourScaleChanged = false
    private var paused = false
    private var monitorMode = Backstage.MONITOR_OFF
    private var monitorIndex = 0
    private var autosaveIndex = 0
    private var localTimeEnabled = true
    private var lowAmpColourScaleEnabled = false
    private var colourMapIndex = Plotter.COLOUR_MAP_DEFAULT
    private var simulationRunning = false
    private var simulationDescription = ""
    private var deviceName = ""
    private var displayingAutosaveWarning = false
    private var lastWrittenFileCounter = 0L
    private var fileDestination = ""
    /** Folder chosen by the user that needs "All files access" before it can be used */
    private var pendingFileDestination: String? = null

    /* timeDisplay.js */
    private var currentTime = 0L

    /* Background running: backstage's getFrame() also drives device switching and
       restarts, so keep calling it (without drawing) while the activity isn't visible */
    private var notifiedDeviceName = ""
    private var pendingDeviceRefresh = false
    private val backgroundTick = object : Runnable {
        override fun run() {
            if (frameLoopRunning) return
            Backstage.getFrame(frame)
            handleFrameEvents()
            if (frame.redrawRequired) pendingDeviceRefresh = true
            updateNotification(Backstage.nativeGetDeviceName())
            handler.postDelayed(this, 100)
        }
    }

    private val frame = Backstage.Frame()
    private var engineReady = false
    private var frameLoopRunning = false
    private var lastFrameNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!frameLoopRunning) return
            /* TARGET_FRAME_RATE = 60 */
            if (frameTimeNanos - lastFrameNanos >= 15_000_000L) {
                lastFrameNanos = frameTimeNanos
                updateDisplay()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /* Lifecycle */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        displayWidthIndex = prefs.getInt("displayWidthIndex", DEFAULT_DISPLAY_WIDTH_INDEX).coerceIn(0, DISPLAY_WIDTH_VALUES.size - 1)
        colourMapIndex = prefs.getInt("colourMap", Plotter.COLOUR_MAP_DEFAULT).coerceIn(0, 2)
        lowAmpColourScaleEnabled = prefs.getBoolean("lowAmp", false)
        localTimeEnabled = prefs.getBoolean("localTime", true)
        Theme.night = prefs.getBoolean("nightMode", false)

        buildLayout()

        val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_RECORD_AUDIO)

        /* Announce an already-connected AudioMoth before initialising, so
           backstage starts on it straight away (as desktop does at launch) */
        usbMicrophone = UsbMicrophone(this, this)
        usbMicrophone.register()
        usbMicrophone.connect()

        val savedRate = prefs.getInt("sampleRate", DEFAULT_SAMPLE_RATE)
        Backstage.nativeChangeSampleRate(if (savedRate in SAMPLE_RATE_VALUES) savedRate else DEFAULT_SAMPLE_RATE)
        Backstage.nativeSetLocalTime(localTimeEnabled)

        engineReady = Backstage.initialise()
        if (!engineReady) displayError("Initialisation error", "Could not initialise the input device.")

        setUpFileDestination()
        setUpSimulation()

        /* Keep recording with the screen off / app in the background */
        updateNotification(Backstage.nativeGetDeviceName())

        if (intent?.getBooleanExtra(RecordingService.EXTRA_QUIT, false) == true) finish()
    }

    private fun updateNotification(name: String) {
        if (name == notifiedDeviceName) return
        notifiedDeviceName = name
        RecordingService.start(this, name)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) usbMicrophone.connect()
        /* "Quit" action in the recording notification */
        if (intent.getBooleanExtra(RecordingService.EXTRA_QUIT, false)) finish()
    }

    override fun onResume() {
        super.onResume()
        if (!usbMicrophone.isStreaming) usbMicrophone.connect()
        /* Returning from the "All files access" settings screen */
        pendingFileDestination?.let { if (Environment.isExternalStorageManager()) setFileDestination(it) }
        canvasSizeChanged = true
        handler.removeCallbacks(backgroundTick)
        frameLoopRunning = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onPause() {
        frameLoopRunning = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        handler.postDelayed(backgroundTick, 100)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(backgroundTick)
        /* main.js sends 'shutdown-autosave' before quitting */
        Backstage.nativeForceAutoSaveToStop()
        usbMicrophone.unregister()
        RecordingService.stop(this)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        /* If the default input failed for lack of permission, backstage's time
           mismatch check restarts it within 2 s, now with permission */
        if (requestCode != REQUEST_RECORD_AUDIO) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            /* Restart the foreground service with the microphone type */
            RecordingService.start(this, notifiedDeviceName)
        } else {
            Toast.makeText(this, "Without microphone permission only the AudioMoth can be used", Toast.LENGTH_LONG).show()
        }
    }

    /* Files */

    private fun defaultFileDestination(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS), "AudioMoth Live")

    private fun setUpFileDestination() {
        val saved = prefs.getString("fileDestination", null)
        if (saved != null && canWriteWav(File(saved))) {
            applyFileDestination(saved)
        } else {
            val dir = defaultFileDestination()
            if (!dir.exists() && !dir.mkdirs()) Log.e(TAG, "Could not create $dir")
            applyFileDestination(dir.absolutePath)
        }
    }

    private fun applyFileDestination(path: String) {
        fileDestination = path
        Backstage.nativeSetFileDestination(path)
        Log.i(TAG, "Set file destination to: $path")
    }

    /** backstage writes WAV files with fopen(), so the folder must accept a .WAV written by path */
    private fun canWriteWav(dir: File): Boolean {
        val test = File(dir, "AudioMoth_Live_write_test.WAV")
        return try {
            if (!dir.exists()) dir.mkdirs()
            test.writeBytes(ByteArray(44))
            test.delete()
            true
        } catch (e: Exception) {
            Log.i(TAG, "Cannot write to $dir: ${e.message}")
            false
        }
    }

    /** Desktop: File > Set File Destination (dialog.showOpenDialogSync with openDirectory) */
    private fun showFileDestinationDialog() {
        AlertDialog.Builder(this)
            .setTitle("File destination")
            .setMessage("Captured and autosaved WAV files are saved to:\n\n$fileDestination")
            .setPositiveButton("Change…") { _, _ ->
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_FILE_DESTINATION)
            }
            .setNeutralButton("Default") { _, _ -> setFileDestination(defaultFileDestination().absolutePath) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Map a picked document tree (external storage provider) to a file system path */
    private fun treeUriToPath(uri: android.net.Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val id = android.provider.DocumentsContract.getTreeDocumentId(uri)
        val volume = id.substringBefore(':')
        val relative = id.substringAfter(':', "")
        val root = if (volume.equals("primary", ignoreCase = true)) Environment.getExternalStorageDirectory().absolutePath else "/storage/$volume"
        return if (relative.isEmpty()) root else "$root/$relative"
    }

    private fun setFileDestination(path: String) {
        if (canWriteWav(File(path))) {
            pendingFileDestination = null
            prefs.edit().putString("fileDestination", path).apply()
            applyFileDestination(path)
            Toast.makeText(this, "Files will be saved to $path", Toast.LENGTH_LONG).show()
        } else if (!Environment.isExternalStorageManager()) {
            pendingFileDestination = path
            AlertDialog.Builder(this)
                .setTitle("Folder access")
                .setMessage("Android only lets apps write to some folders (such as Recordings, Music, Documents and Download) without extra permission.\n\n" +
                        "To save to\n$path\nallow AudioMoth Live \"All files access\" on the next screen, then return to the app.")
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Cancel") { _, _ -> pendingFileDestination = null }
                .show()
        } else {
            pendingFileDestination = null
            displayError("File destination error", "Could not write to:\n$path\n\nPlease choose a different folder.")
        }
    }

    @Deprecated("Activity result API for a plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FILE_DESTINATION || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val path = treeUriToPath(uri)
        if (path == null) {
            displayError("File destination error", "Please choose a folder on the phone's storage or SD card.")
        } else {
            setFileDestination(path)
        }
    }

    private fun scanFile(path: String) {
        if (path.isNotEmpty()) MediaScannerConnection.scanFile(this, arrayOf(path), arrayOf("audio/wav"), null)
    }

    /** The desktop's simulator/BAT.WAV is packaged as an asset; backstage reads it from a folder */
    private fun setUpSimulation() {
        val dir = File(filesDir, "simulator")
        val file = File(dir, "BAT.WAV")
        try {
            if (!file.exists()) {
                dir.mkdirs()
                assets.open("BAT.WAV").use { input -> file.outputStream().use { input.copyTo(it) } }
            }
            simulationDescription = Backstage.nativeSetSimulationPath(dir.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Simulation file unavailable: ${e.message}")
        }
    }

    /* Layout: mirrors index.html, stacked for a portrait screen */

    private fun buildLayout() {

        val pad = dp(10f)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BACKGROUND)
        }

        /* Top row: input device, selection/clock, menu */

        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        inputSpan = bodyText(deviceName.ifEmpty { "​" })
        clockSpan = bodyText("").apply { gravity = Gravity.END }
        selectionSpan = bodyText("").apply { gravity = Gravity.END; setTextColor(Theme.SELECTION); visibility = View.GONE }
        val menuButton = bodyText("⋮").apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(dp(12f), 0, dp(4f), 0)
            setOnClickListener { showOptionsMenu(this) }
        }
        topRow.addView(inputSpan, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        topRow.addView(selectionSpan, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        topRow.addView(clockSpan, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        topRow.addView(menuButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        /* Plots */

        waveformView = PlotView(this, isWaveform = true).also { it.listener = this }
        spectrogramView = PlotView(this, isWaveform = false).also { it.listener = this }
        drawWaveformBaseline()

        val plotHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        plotHolder.addView(waveformView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 30f))
        plotHolder.addView(spectrogramView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 70f).apply { topMargin = pad })

        /* Display width */

        displayWidthPagination = SegmentedControl(this, DISPLAY_WIDTH_VALUES.map { "${it}s" }) { i ->
            displayWidthIndex = i
            prefs.edit().putInt("displayWidthIndex", i).apply()
            displayWidthChanged = true
            Log.i(TAG, "Display width changed to: $displayWidth seconds")
        }.apply { select(displayWidthIndex) }

        /* Sample rate */

        sampleRatePagination = SegmentedControl(this, SAMPLE_RATE_VALUES.map { (it / 1000).toString() }) { i ->
            val requestedSampleRate = SAMPLE_RATE_VALUES[i]
            prefs.edit().putInt("sampleRate", requestedSampleRate).apply()
            Backstage.nativeChangeSampleRate(requestedSampleRate)
            Log.i(TAG, "Sample rate changed to: $requestedSampleRate")
        }

        /* Autosave */

        autosaveLengthPagination = SegmentedControl(this, AUTOSAVE_LENGTH_VALUES.map { if (it < 0) "Off" else it.toString() }) { i ->
            autosaveIndex = i
            val selection = AUTOSAVE_LENGTH_VALUES[i]
            Backstage.nativeSetAutoSave(if (selection < 0) 0 else selection)
            Log.i(TAG, if (selection < 0) "Autosave disabled" else "Autosave length changed to: $selection mins")
        }.apply { select(autosaveIndex) }

        /* Monitor / heterodyne */

        /* Item widths 45/75/95 px as in index.html */
        monitorPagination = SegmentedControl(this, MONITOR_LABELS, listOf(45f, 75f, 95f)) { i -> onMonitorSelected(i) }.apply { select(monitorIndex) }

        heterodyneSpan = bodyText("").apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(6f).toFloat(); setColor(Theme.BACKGROUND); setStroke(dp(1f), Theme.BORDER)
            }
        }
        heterodyneDownButton = iconButton(R.drawable.ic_caret_down, "Decrement heterodyne frequency")
        heterodyneUpButton = iconButton(R.drawable.ic_caret_up, "Increment heterodyne frequency")
        addHoldRepeat(heterodyneDownButton) { changeHeterodyneFrequency(heterodyneFrequency - 100) }
        addHoldRepeat(heterodyneUpButton) { changeHeterodyneFrequency(heterodyneFrequency + 100) }

        val heterodyneRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heterodyneRow.addView(monitorPagination, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        heterodyneRow.addView(heterodyneSpan, LinearLayout.LayoutParams(dp(80f), dp(38f)).apply { marginStart = dp(6f) })
        heterodyneRow.addView(heterodyneDownButton, LinearLayout.LayoutParams(dp(34f), dp(38f)).apply { marginStart = dp(4f) })
        heterodyneRow.addView(heterodyneUpButton, LinearLayout.LayoutParams(dp(34f), dp(38f)).apply { marginStart = dp(4f) })

        /* Pause / clear / capture */

        pausePlayButton = ImageButton(this).apply {
            contentDescription = "Pause/play"
            setOnClickListener { setPause(!paused) }
        }
        clearButton = bootstrapButton("Clear", Theme.SECONDARY).apply { setOnClickListener { Backstage.nativeClear() } }
        captureButton = bootstrapButton("Capture WAV", Theme.PRIMARY).apply { setOnClickListener { capture() } }

        val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttonRow.addView(pausePlayButton, LinearLayout.LayoutParams(0, dp(40f), 1f))
        buttonRow.addView(clearButton, LinearLayout.LayoutParams(0, dp(40f), 1f).apply { marginStart = pad })
        buttonRow.addView(captureButton, LinearLayout.LayoutParams(0, dp(40f), 2f).apply { marginStart = pad })

        /* Assemble */

        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun add(v: View, top: Int) = controls.addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = top })
        add(displayWidthPagination, pad)
        add(labelText("Sample rate (kHz):"), pad)
        add(sampleRatePagination, dp(2f))
        add(labelText("Autosave file duration (mins):"), pad)
        add(autosaveLengthPagination, dp(2f))
        add(heterodyneRow, pad)
        add(buttonRow, pad)

        root.addView(topRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(4f) })
        root.addView(plotHolder, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)

        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(pad + bars.left, pad / 2 + bars.top, pad + bars.right, pad + bars.bottom)
            WindowInsets.CONSUMED
        }

        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (Theme.night) 0 else lightBars, lightBars)

        /* Restore control states (also after a night mode rebuild) */
        updateSampleRateButtons(currentSampleRate, maximumSampleRate)
        applyPauseState()
        updateHeterodyneUI(false)
        canvasSizeChanged = true
    }

    private fun bodyText(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Theme.BODY_TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Theme.TEXT_SP)
        typeface = Typeface.DEFAULT
        fontFeatureSettings = "tnum"   /* .mono-text */
    }

    private fun labelText(text: String) = bodyText(text).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, Theme.TEXT_SP - 1) }

    private fun iconButton(icon: Int, description: String) = ImageButton(this).apply {
        setImageResource(icon)
        contentDescription = description
        setButtonColour(this, Theme.SECONDARY)
    }

    /** addHoldRepeat() in index.js */
    private fun addHoldRepeat(button: View, fn: () -> Unit) {
        var delay = HOLD_INITIAL_DELAY
        val repeat = object : Runnable {
            override fun run() {
                fn()
                delay = maxOf(HOLD_MIN_DELAY, (delay * HOLD_DELAY_MULTIPLIER).toLong())
                handler.postDelayed(this, delay)
            }
        }
        button.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!v.isEnabled) return@setOnTouchListener true
                    v.isPressed = true
                    delay = HOLD_INITIAL_DELAY
                    fn()
                    handler.postDelayed(repeat, delay)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeat)
                    if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }
    }

    /* Dialogs (displayError / twoOption in index.js) */

    private fun displayError(title: String, body: String, onOkay: (() -> Unit)? = null) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setCancelable(false)
            .setPositiveButton("Okay") { _, _ -> onOkay?.invoke() }
            .show()
    }

    /** twoOption.displayTwoOptionCheck() for the audio feedback warning */
    private fun feedbackWarning(disableLabel: String, onContinue: () -> Unit, onDisable: () -> Unit) {
        if (!prefs.getBoolean("alwaysDisplayFeedbackWarning", true)) { onContinue(); return }
        val check = CheckBox(this).apply { text = "Always display warning"; isChecked = true }
        val holder = LinearLayout(this).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(check) }
        AlertDialog.Builder(this)
            .setTitle("Warning")
            .setMessage("Make sure your microphone is positioned away from your speakers to prevent audio feedback.")
            .setView(holder)
            .setCancelable(false)
            .setPositiveButton("Continue") { _, _ -> prefs.edit().putBoolean("alwaysDisplayFeedbackWarning", check.isChecked).apply(); onContinue() }
            .setNegativeButton(disableLabel) { _, _ -> prefs.edit().putBoolean("alwaysDisplayFeedbackWarning", check.isChecked).apply(); onDisable() }
            .show()
    }

    /* Menu (main.js) */

    private fun showOptionsMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        val m = menu.menu
        m.add(0, MENU_LOCAL_TIME, 0, "Local Time").apply { isCheckable = true; isChecked = localTimeEnabled }
        m.add(0, MENU_NIGHT_MODE, 1, "Night Mode").apply { isCheckable = true; isChecked = Theme.night }
        val sim = m.addSubMenu(0, 0, 2, "Simulate")
        sim.add(0, MENU_SIMULATE, 0, simulationDescription.ifEmpty { "Bat" }).apply {
            isCheckable = true; isChecked = simulationRunning; isEnabled = simulationDescription.isNotEmpty()
        }
        val colours = m.addSubMenu(0, 0, 3, "Colour")
        COLOUR_MAP_NAMES.forEachIndexed { i, name -> colours.add(1, MENU_COLOUR_BASE + i, i, name).isChecked = i == colourMapIndex }
        colours.setGroupCheckable(1, true, true)
        colours.add(2, MENU_LOW_AMP, 10, "Enable Low-Amplitude Colour Scale").apply { isCheckable = true; isChecked = lowAmpColourScaleEnabled }
        m.add(0, MENU_SAVE_FOLDER, 4, "Set File Destination…")
        m.add(0, MENU_DIAGNOSTICS, 5, "USB Diagnostics…")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_LOCAL_TIME -> {
                    localTimeEnabled = !localTimeEnabled
                    prefs.edit().putBoolean("localTime", localTimeEnabled).apply()
                    Backstage.nativeSetLocalTime(localTimeEnabled)
                }
                MENU_NIGHT_MODE -> {
                    Theme.night = !Theme.night
                    prefs.edit().putBoolean("nightMode", Theme.night).apply()
                    buildLayout()
                }
                MENU_SIMULATE -> onSimulate(!simulationRunning)
                MENU_LOW_AMP -> {
                    lowAmpColourScaleEnabled = !lowAmpColourScaleEnabled
                    prefs.edit().putBoolean("lowAmp", lowAmpColourScaleEnabled).apply()
                    colourScaleChanged = true
                }
                MENU_SAVE_FOLDER -> showFileDestinationDialog()
                MENU_DIAGNOSTICS -> {
                    usbMicrophone.disconnect()
                    startActivity(Intent(this, PocActivity::class.java))
                }
                in MENU_COLOUR_BASE until MENU_COLOUR_BASE + 3 -> {
                    colourMapIndex = item.itemId - MENU_COLOUR_BASE
                    prefs.edit().putInt("colourMap", colourMapIndex).apply()
                    Log.i(TAG, "Changing colour map - ${COLOUR_MAP_NAMES[colourMapIndex]}")
                    drawWaveformBaseline()
                }
            }
            true
        }
        menu.show()
    }

    /* Simulation (changeSimulation in index.js) */

    private fun onSimulate(enable: Boolean) {
        if (monitorMode == Backstage.MONITOR_OFF || !enable) { changeSimulation(enable); return }
        feedbackWarning(if (monitorMode == 1) "Disable Monitor" else "Disable Heterodyne", { changeSimulation(enable) }) {
            monitorPagination.select(0); monitorIndex = 0
            changeAndSaveMonitorMode(Backstage.MONITOR_OFF)
            changeSimulation(enable)
        }
    }

    private fun changeSimulation(enable: Boolean) {
        if (Backstage.nativeSetSimulation(enable, 0)) {
            Log.i(TAG, if (enable) "Switched to simulate: $simulationDescription" else "No longer simulating")
        } else {
            displayError("Simulation error", "Failed to load simulation.")
        }
    }

    /* index.js: drawWaveformBaseline() */

    private fun drawWaveformBaseline() {
        waveformView.baselineColour = if (colourMapIndex == Plotter.COLOUR_MAP_DEFAULT) {
            if (Theme.night) Color.WHITE else Color.BLACK
        } else Color.GRAY
        waveformView.invalidate()
    }

    /* index.js: updateSampleRateButtons() - disable rates above the device's rate */

    private fun updateSampleRateButtons(currentSampleRate: Int, maximumSampleRate: Int) {
        SAMPLE_RATE_VALUES.forEachIndexed { i, rate ->
            var enabled = true
            if (!simulationRunning && rate > maximumSampleRate) enabled = false
            if (simulationRunning && rate != currentSampleRate) enabled = false
            if (paused) enabled = false
            sampleRatePagination.setItemEnabled(i, enabled)
            if (rate == currentSampleRate) sampleRatePagination.select(i)
        }
    }

    /* Monitor / heterodyne (index.js) */

    private fun isHeterodyneEnabled() = monitorIndex == 2

    private fun isMonitorEnabled() = monitorIndex == 1

    private fun changeAndSaveMonitorMode(mode: Int) {
        monitorMode = mode
        Backstage.nativeSetMonitor(mode, if (mode == Backstage.MONITOR_HETERODYNE) heterodyneFrequency else 0)
    }

    private fun onMonitorSelected(index: Int) {
        if (index == 0) {
            Log.i(TAG, "Monitor off.")
            monitorIndex = 0
            changeAndSaveMonitorMode(Backstage.MONITOR_OFF)
            updateHeterodyneUI(false)
            return
        }
        val handle = {
            monitorIndex = index
            if (index == 1) changeAndSaveMonitorMode(Backstage.MONITOR_PLAYTHROUGH)
            updateHeterodyneUI(false)
        }
        if (simulationRunning) { handle(); return }
        feedbackWarning(if (index == 1) "Disable Monitor" else "Disable Heterodyne", handle) {
            Log.i(TAG, "Monitor off.")
            monitorPagination.select(0)
            monitorIndex = 0
            changeAndSaveMonitorMode(Backstage.MONITOR_OFF)
            updateHeterodyneUI(false)
        }
    }

    private fun changeHeterodyneFrequency(newFrequency: Int) {
        heterodyneFrequency = newFrequency.coerceIn(HETERODYNE_FREQUENCY_MIN, maxOf(HETERODYNE_FREQUENCY_MIN, heterodyneFrequencyMax))
        updateHeterodyneUI(false)
    }

    private fun checkHeterodyneButtons() {
        heterodyneUpButton.setBootstrapEnabled(heterodyneFrequency + 100 <= heterodyneFrequencyMax)
        heterodyneDownButton.setBootstrapEnabled(heterodyneFrequency - 100 >= HETERODYNE_FREQUENCY_MIN)
    }

    private fun updateHeterodyneUI(reset: Boolean) {
        val sampleRate = currentSampleRate
        heterodyneFrequencyMax = sampleRate / 2
        if (reset || sampleRate < 48000) heterodyneFrequency = sampleRate / 4
        heterodyneSpan.text = "%.1f".format(heterodyneFrequency / 1000.0) + KHZ
        if (!isHeterodyneEnabled()) {
            heterodyneUpButton.setBootstrapEnabled(false)
            heterodyneDownButton.setBootstrapEnabled(false)
            heterodyneSpan.setTextColor(Theme.GREY_TEXT)
        } else {
            heterodyneSpan.setTextColor(Theme.BODY_TEXT)
            checkHeterodyneButtons()
            changeAndSaveMonitorMode(Backstage.MONITOR_HETERODYNE)
        }
        updateHeterodyneLine()
    }

    private fun updateHeterodyneLine() {
        if (!isHeterodyneEnabled()) { spectrogramView.heterodyneLineY = null; return }
        val h = spectrogramView.height.toFloat()
        if (h <= 0f) return
        val nyquist = currentSampleRate / 2f
        var y = h - heterodyneFrequency / nyquist * h
        if (y == 0f) y = 1f
        spectrogramView.heterodyneLineY = y.roundToInt() + 0.5f
    }

    /* Pause / capture (index.js) */

    private fun setPause(newPause: Boolean) {
        paused = newPause
        applyPauseState()
        Backstage.nativeSetPause(paused, displayWidth, LongArray(3))
    }

    private fun applyPauseState() {
        pausePlayButton.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        setButtonColour(pausePlayButton, if (paused) Theme.SUCCESS else Theme.DANGER)
        if (paused) {
            sampleRatePagination.setAllEnabled(false)
            displayWidthPagination.setAllEnabled(false)
            autosaveLengthPagination.setAllEnabled(false)
            clearButton.setBootstrapEnabled(false)
        } else {
            updateSampleRateButtons(currentSampleRate, maximumSampleRate)
            displayWidthPagination.setAllEnabled(true)
            autosaveLengthPagination.setAllEnabled(true)
            clearButton.setBootstrapEnabled(true)
        }
    }

    private fun capture() {
        captureButton.setBootstrapEnabled(false)
        Backstage.nativeCapture(displayWidth)
    }

    private fun onCaptureResult(success: Boolean) {
        if (success) {
            val path = Backstage.nativeGetCaptureFilename()
            scanFile(path)
            Toast.makeText(this, "Saved ${File(path).name}", Toast.LENGTH_SHORT).show()
        } else {
            displayError("Capture WAV error", "Failed to capture WAV file. Check the file destination is still valid (⋮ > Set File Destination).")
        }
        handler.postDelayed({ captureButton.setBootstrapEnabled(true) }, 200)
    }

    /** Polled replacements for the N-API callbacks (also run by the background tick) */
    private fun handleFrameEvents() {

        if (frame.captureResult != 0) onCaptureResult(frame.captureResult == 1)

        if (frame.autosaveError && !displayingAutosaveWarning) {
            displayingAutosaveWarning = true
            displayError("Autosave error", "Failed to save a WAV file. Check the file destination is still valid.") { displayingAutosaveWarning = false }
        }

        if (frame.writtenFileCounter != lastWrittenFileCounter) {
            lastWrittenFileCounter = frame.writtenFileCounter
            scanFile(Backstage.nativeGetLastWrittenFilename())
        }
    }

    /* index.js: updateDisplay() */

    private fun updateDisplay() {

        var redraw = false

        Backstage.getFrame(frame)

        handleFrameEvents()

        simulationRunning = frame.simulationRunning

        /* pendingDeviceRefresh: a redraw/device change happened while in the background */
        if (frame.redrawRequired || pendingDeviceRefresh) {

            pendingDeviceRefresh = false
            updateNotification(Backstage.nativeGetDeviceName())

            val oldDeviceName = deviceName
            val newDeviceName = Backstage.nativeGetDeviceName()

            /* If Default Input <-> AudioMoth, disable monitor/heterodyne to prevent accidental feedback */
            if (!oldDeviceName.contains("Simulated") && !newDeviceName.contains("Simulated")) {
                val switched = (oldDeviceName.contains("Default Input") && newDeviceName.contains("AudioMoth USB Microphone")) ||
                        (newDeviceName.contains("Default Input") && oldDeviceName.contains("AudioMoth USB Microphone"))
                if (switched && (isMonitorEnabled() || isHeterodyneEnabled())) {
                    Log.i(TAG, "Switched between default input and AudioMoth USB Mic, disabling monitor/heterodyne")
                    monitorPagination.select(0)
                    monitorIndex = 0
                    changeAndSaveMonitorMode(Backstage.MONITOR_OFF)
                }
            }

            val sampleRateChanged = currentSampleRate != frame.currentSampleRate
            currentSampleRate = frame.currentSampleRate
            maximumSampleRate = frame.maximumSampleRate
            deviceName = newDeviceName
            inputSpan.text = newDeviceName

            updateSampleRateButtons(currentSampleRate, maximumSampleRate)

            if (currentSampleRate < 48000) {
                if (isHeterodyneEnabled()) {
                    monitorPagination.select(1)
                    monitorIndex = 1
                    changeAndSaveMonitorMode(Backstage.MONITOR_PLAYTHROUGH)
                }
                monitorPagination.setItemEnabled(2, false)
            } else {
                monitorPagination.setItemEnabled(2, true)
            }

            updateHeterodyneUI(sampleRateChanged)
            if (sampleRateChanged) updateSelectionText()

            redraw = true
        }

        if (displayWidthChanged || canvasSizeChanged || colourScaleChanged) {
            displayWidthChanged = false
            canvasSizeChanged = false
            colourScaleChanged = false
            updateHeterodyneLine()
            redraw = true
        }

        val wb = waveformView.bitmap
        val sb = spectrogramView.bitmap

        if (wb != null && sb != null) {
            val changed = Plotter.nativeUpdate(
                Backstage.audioBuffer, Backstage.stftBuffer, wb, sb,
                redraw, frame.audioIndex, frame.audioCount, displayWidth.toLong() * currentSampleRate,
                Theme.night, lowAmpColourScaleEnabled, colourMapIndex
            )
            if (changed) { waveformView.invalidate(); spectrogramView.invalidate() }
        }

        /* timeDisplay.updateTime() */
        currentTime = frame.audioTime
        clockSpan.text = formatTime(currentTime, false, false) + if (localTimeEnabled) "" else " UTC"
        if (waveformView.selectionStart != null) updateSelectionText()
    }

    /* UsbMicrophone.Listener */

    override fun onError(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    /* PlotView.Listener */

    override fun onPlotResized() {
        canvasSizeChanged = true
    }

    override fun onSelectionChanged(view: PlotView) {
        if (view === waveformView) spectrogramView.clearSelection() else waveformView.clearSelection()
        updateSelectionText()
    }

    /** frequencyDisplay.addDoubleClickListener() */
    override fun onDoubleTap(view: PlotView, position: Float) {
        if (view !== spectrogramView) return
        var clickedFrequency = scaleFrequency(position.toDouble(), view.axisLength.toDouble(), currentSampleRate / 2.0).toInt()
        if (!isHeterodyneEnabled() || clickedFrequency > heterodyneFrequencyMax) return
        clickedFrequency = maxOf(HETERODYNE_FREQUENCY_MIN, clickedFrequency)
        changeHeterodyneFrequency(clickedFrequency)
        checkHeterodyneButtons()
    }

    private fun updateSelectionText() {
        val text = when {
            spectrogramView.selectionStart != null -> frequencyText()
            waveformView.selectionStart != null -> timeText()
            else -> null
        }
        if (text == null) {
            selectionSpan.visibility = View.GONE
            clockSpan.visibility = View.VISIBLE
        } else {
            selectionSpan.text = text
            selectionSpan.visibility = View.VISIBLE
            clockSpan.visibility = View.GONE
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

    /** t already includes the local time offset, so read it as UTC (as the desktop does) */
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

        val suffix = if (localTimeEnabled) "" else " UTC"
        return if (s == e) {
            val (t, r) = position(s, false)
            formatTime(t, true, r) + suffix
        } else {
            val (t0, r0) = position(minOf(s, e), false)
            val (t1, r1) = position(maxOf(s, e), true)
            formatTime(t0, true, r0) + " - " + formatTime(t1, true, r1) + suffix
        }
    }
}
