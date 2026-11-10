package info.openacousticdevices.audiomothlive

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
import android.util.Log

/**
 * Finds the AudioMoth USB Microphone, obtains permission, claims the
 * AudioStreaming interface and starts the native engine.
 */
class UsbMicrophone(private val context: Context, private val listener: Listener) {

    interface Listener {
        /** deviceName is null when no microphone is connected */
        fun onDeviceChanged(deviceName: String?, deviceSampleRate: Int)
        fun onError(message: String)
    }

    companion object {
        const val TAG = "AudioMothLive"
        const val AUDIOMOTH_VID = 0x16d0
        const val AUDIOMOTH_PID = 0x06f3
        const val ACTION_USB_PERMISSION = "info.openacousticdevices.audiomothlive.USB_PERMISSION_LIVE"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var device: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var streamingInterface: UsbInterface? = null
    private var idleInterface: UsbInterface? = null
    private var permissionRequested = false

    val isStreaming get() = connection != null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    permissionRequested = false
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect()
                    else listener.onError("USB permission denied")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val d = deviceFromIntent(intent)
                    if (d == null || d.deviceName == device?.deviceName) {
                        Log.i(TAG, "Microphone detached")
                        disconnect()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> connect()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceFromIntent(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    fun register() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
    }

    fun unregister() {
        context.unregisterReceiver(receiver)
        disconnect()
    }

    /** Look for the microphone and start streaming if possible. */
    fun connect() {

        if (connection != null) return

        val d = usbManager.deviceList.values.firstOrNull { it.vendorId == AUDIOMOTH_VID && it.productId == AUDIOMOTH_PID }

        if (d == null) {
            listener.onDeviceChanged(null, 0)
            return
        }

        device = d

        if (!usbManager.hasPermission(d)) {
            if (!permissionRequested) {
                permissionRequested = true
                val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                usbManager.requestPermission(d, PendingIntent.getBroadcast(context, 1, intent, flags))
            }
            return
        }

        val c = usbManager.openDevice(d)
        if (c == null) { listener.onError("Could not open the USB microphone"); return }

        val alt = UacDescriptors.findStreamingAlts(c.rawDescriptors).firstOrNull()
        if (alt == null) { c.close(); listener.onError("No audio stream found on the USB device"); return }

        val deviceRate = alt.sampleRates.firstOrNull() ?: UacDescriptors.sampleRateFromName(d.productName) ?: 384000

        var stream: UsbInterface? = null
        var idle: UsbInterface? = null
        for (i in 0 until d.interfaceCount) {
            val intf = d.getInterface(i)
            if (intf.id == alt.interfaceNumber && intf.alternateSetting == alt.alternateSetting) stream = intf
            if (intf.id == alt.interfaceNumber && intf.alternateSetting == 0) idle = intf
        }

        if (stream == null || !c.claimInterface(stream, true) || !c.setInterface(stream)) {
            c.close()
            listener.onError("Could not start the USB audio stream")
            return
        }

        if (!Engine.nativeStart(c.fileDescriptor, alt.endpointAddress, alt.maxPacketSize, deviceRate)) {
            c.releaseInterface(stream)
            c.close()
            listener.onError("Could not start the capture engine")
            return
        }

        connection = c
        streamingInterface = stream
        idleInterface = idle

        /* Same naming as backstage.c: "%dkHz AudioMoth USB Microphone" */
        val name = "${deviceRate / 1000}kHz AudioMoth USB Microphone"
        Log.i(TAG, "Streaming from $name (${d.productName})")
        listener.onDeviceChanged(name, deviceRate)
    }

    fun disconnect() {
        val c = connection ?: run { device = null; return }
        Engine.nativeStop()
        idleInterface?.let { runCatching { c.setInterface(it) } }
        streamingInterface?.let { runCatching { c.releaseInterface(it) } }
        c.close()
        connection = null
        streamingInterface = null
        idleInterface = null
        device = null
        listener.onDeviceChanged(null, 0)
    }
}
