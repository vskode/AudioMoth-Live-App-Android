package info.openacousticdevices.audiomothlive

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowInsets
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PocActivity : Activity() {

    companion object {
        const val TAG = "AudioMothLive"
        const val AUDIOMOTH_VID = 0x16d0
        const val AUDIOMOTH_PID = 0x06f3
        const val ACTION_USB_PERMISSION = "info.openacousticdevices.audiomothlive.USB_PERMISSION"
        const val RECORD_SECONDS = 10.0
    }

    private lateinit var usbManager: UsbManager
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var connectButton: Button
    private lateinit var recordButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())

    private var device: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var streamingAlt: StreamingAlt? = null
    private var sampleRate = 0

    @Volatile private var recording = false

    /** Set by `adb shell am start -n <pkg>/.PocActivity --ez record true` */
    private var recordRequested = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    log(if (granted) "USB permission granted" else "USB permission DENIED")
                    if (granted) openDevice()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    log("Device detached")
                    UsbCapture.nativeStop()
                    closeDevice()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        connectButton = Button(this).apply { text = "Connect"; setOnClickListener { findDevice() } }
        recordButton = Button(this).apply {
            text = "Record ${RECORD_SECONDS.toInt()} s"
            isEnabled = false
            setOnClickListener { startRecording() }
        }
        val clearButton = Button(this).apply { text = "Clear"; setOnClickListener { logView.text = "" } }

        buttons.addView(connectButton)
        buttons.addView(recordButton)
        buttons.addView(clearButton)

        logView = TextView(this).apply { textSize = 12f; setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }
        scrollView = ScrollView(this).apply { addView(logView) }

        root.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scrollView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        /* Android 15+ forces edge-to-edge: keep content clear of status/navigation bars and cutouts */
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(24 + bars.left, 24 + bars.top, 24 + bars.right, 24 + bars.bottom)
            WindowInsets.CONSUMED
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(usbReceiver, filter)

        log("AudioMoth Live diagnostics - Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        log("Output folder: ${getExternalFilesDir(null)}")

        recordRequested = intent.getBooleanExtra("record", false)

        findDevice()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            log("Device attached")
            findDevice()
        }
        if (intent.getBooleanExtra("record", false)) {
            log("Record requested via intent")
            if (streamingAlt != null) mainHandler.post { startRecording() } else { recordRequested = true; findDevice() }
        }
    }

    override fun onDestroy() {
        UsbCapture.nativeStop()
        unregisterReceiver(usbReceiver)
        closeDevice()
        super.onDestroy()
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        mainHandler.post {
            logView.append(msg + "\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun findDevice() {

        if (connection != null) { log("Already connected"); return }

        val devices = usbManager.deviceList.values

        if (devices.isEmpty()) { log("No USB devices found. Is the AudioMoth plugged in via an OTG adapter?"); return }

        for (d in devices) {
            log("USB device: %04x:%04x '%s' / '%s'".format(d.vendorId, d.productId, d.manufacturerName ?: "?", d.productName ?: "?"))
        }

        val d = devices.firstOrNull { it.vendorId == AUDIOMOTH_VID && it.productId == AUDIOMOTH_PID }

        if (d == null) { log("No AudioMoth USB Microphone (16d0:06f3) found"); return }

        device = d

        if (usbManager.hasPermission(d)) {
            openDevice()
        } else {
            log("Requesting USB permission...")
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            usbManager.requestPermission(d, PendingIntent.getBroadcast(this, 0, intent, flags))
        }
    }

    private fun openDevice() {

        val d = device ?: return

        val c = usbManager.openDevice(d)
        if (c == null) { log("ERROR: openDevice failed"); return }
        connection = c

        log("Opened device, fd=${c.fileDescriptor}")

        val raw = c.rawDescriptors
        log("--- Raw descriptors ---\n" + UacDescriptors.hexDump(raw) + "-----------------------")

        for (i in 0 until d.interfaceCount) {
            val intf = d.getInterface(i)
            log("Interface ${intf.id} alt ${intf.alternateSetting}: class ${intf.interfaceClass} subclass ${intf.interfaceSubclass}, ${intf.endpointCount} endpoint(s)")
        }

        val alts = UacDescriptors.findStreamingAlts(raw)

        for (a in alts) {
            log("Streaming alt: if ${a.interfaceNumber} alt ${a.alternateSetting}, ep 0x%02x attr 0x%02x mps ${a.maxPacketSize}, ${a.channels} ch, ${a.bitResolution} bit, rates ${a.sampleRates}, SR control ${a.supportsSampleRateControl}"
                .format(a.endpointAddress, a.endpointAttributes))
        }

        val alt = alts.firstOrNull()
        if (alt == null) { log("ERROR: no AudioStreaming interface with an isochronous IN endpoint found"); closeDevice(); return }

        val nameRate = UacDescriptors.sampleRateFromName(d.productName)
        sampleRate = alt.sampleRates.firstOrNull() ?: nameRate ?: 48000

        log("Sample rate: $sampleRate Hz (descriptor ${alt.sampleRates}, product name $nameRate)")

        if (alt.bitResolution != 16 || alt.channels != 1) log("WARNING: expected 16-bit mono, got ${alt.bitResolution}-bit ${alt.channels} ch")

        val bytesPerMs = sampleRate / 1000.0 * alt.channels * alt.subframeSize
        log("Bandwidth: %.0f bytes/ms vs max packet ${alt.maxPacketSize}".format(bytesPerMs))

        streamingAlt = alt
        mainHandler.post { recordButton.isEnabled = true }
        log("Ready. Tap 'Record ${RECORD_SECONDS.toInt()} s' above.")

        if (recordRequested) { recordRequested = false; mainHandler.post { startRecording() } }
    }

    private fun findInterface(number: Int, alternate: Int): UsbInterface? {
        val d = device ?: return null
        for (i in 0 until d.interfaceCount) {
            val intf = d.getInterface(i)
            if (intf.id == number && intf.alternateSetting == alternate) return intf
        }
        return null
    }

    private fun startRecording() {

        val c = connection ?: return
        val alt = streamingAlt ?: return
        if (recording) return

        val streamIntf = findInterface(alt.interfaceNumber, alt.alternateSetting)
        val idleIntf = findInterface(alt.interfaceNumber, 0)

        if (streamIntf == null) { log("ERROR: UsbInterface for streaming alt not found"); return }

        recording = true
        recordButton.isEnabled = false

        Thread {
            try {
                record(c, alt, streamIntf, idleIntf)
            } catch (e: Exception) {
                log("ERROR: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                recording = false
                mainHandler.post { recordButton.isEnabled = connection != null }
            }
        }.start()
    }

    private fun record(c: UsbDeviceConnection, alt: StreamingAlt, streamIntf: UsbInterface, idleIntf: UsbInterface?) {

        /* Claim with force=true detaches the kernel snd-usb-audio driver */

        if (!c.claimInterface(streamIntf, true)) { log("ERROR: claimInterface failed"); return }
        log("Claimed interface ${alt.interfaceNumber}")

        if (!c.setInterface(streamIntf)) { log("ERROR: setInterface(alt ${alt.alternateSetting}) failed"); c.releaseInterface(streamIntf); return }
        log("Selected alt setting ${alt.alternateSetting}")

        if (alt.supportsSampleRateControl) {
            /* UAC1 SET_CUR SAMPLING_FREQ_CONTROL on the endpoint */
            val data = byteArrayOf((sampleRate and 0xff).toByte(), ((sampleRate shr 8) and 0xff).toByte(), ((sampleRate shr 16) and 0xff).toByte())
            val r = c.controlTransfer(0x22, 0x01, 0x0100, alt.endpointAddress, data, 3, 1000)
            log("SET_CUR sample rate $sampleRate -> result $r")
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(getExternalFilesDir(null), "AM_${sampleRate / 1000}kHz_$stamp.wav")

        log("Recording ${RECORD_SECONDS.toInt()} s to ${file.name} ...")

        val report = UsbCapture.nativeCapture(
            c.fileDescriptor, alt.endpointAddress, alt.maxPacketSize,
            sampleRate, alt.channels, alt.bitResolution, RECORD_SECONDS, file.absolutePath
        )

        log(report)

        if (idleIntf != null) c.setInterface(idleIntf)
        c.releaseInterface(streamIntf)

        if (report.startsWith("OK") && alt.bitResolution == 16 && alt.channels == 1) {
            log("Analysing spectrum...")
            log(SpectrumCheck.analyse(file, sampleRate))
        }

        log("Saved: ${file.absolutePath}")
        log("Pull with: adb pull ${file.absolutePath}")
    }

    private fun closeDevice() {
        connection?.close()
        connection = null
        streamingAlt = null
        mainHandler.post { recordButton.isEnabled = false }
    }
}
