package info.openacousticdevices.audiomothlive

object UsbCapture {

    init {
        System.loadLibrary("usbcapture")
    }

    /** Blocking. Returns a statistics report, or a string starting with "ERROR". */
    @JvmStatic
    external fun nativeCapture(
        fd: Int,
        endpoint: Int,
        maxPacketSize: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        seconds: Double,
        path: String
    ): String

    @JvmStatic
    external fun nativeStop()
}
