package info.openacousticdevices.audiomothlive

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** JNI wrapper for engine.c (USB streaming, resampling, STFT). */
object Engine {

    init {
        System.loadLibrary("engine")
    }

    /** Same ring buffer size as backstage.c */
    const val AUDIO_BUFFER_SIZE = 1 shl 25

    const val STATE_STOPPED = 0
    const val STATE_RUNNING = 1
    const val STATE_ERROR = 2

    /** Sample rates offered by the desktop app */
    val SAMPLE_RATES = intArrayOf(8000, 16000, 32000, 48000, 96000, 192000, 250000, 384000)

    lateinit var audioBuffer: ByteBuffer
        private set
    lateinit var stftBuffer: ByteBuffer
        private set

    fun initialise(): Boolean {
        if (!nativeInitialise()) return false
        audioBuffer = nativeGetAudioBuffer().order(ByteOrder.LITTLE_ENDIAN)
        stftBuffer = nativeGetStftBuffer().order(ByteOrder.LITTLE_ENDIAN)
        return true
    }

    class Frame {
        val raw = LongArray(7)
        val audioIndex get() = raw[0]
        val audioCount get() = raw[1]
        val currentSampleRate get() = raw[2].toInt()
        val audioTimeLocal get() = raw[3]
        val redrawRequired get() = raw[4] != 0L
        val state get() = raw[5].toInt()
        val deviceSampleRate get() = raw[6].toInt()
    }

    fun getFrame(frame: Frame) = nativeGetFrame(frame.raw)

    @JvmStatic external fun nativeInitialise(): Boolean
    @JvmStatic external fun nativeGetAudioBuffer(): ByteBuffer
    @JvmStatic external fun nativeGetStftBuffer(): ByteBuffer
    @JvmStatic external fun nativeStart(fd: Int, endpoint: Int, maxPacketSize: Int, deviceSampleRate: Int): Boolean
    @JvmStatic external fun nativeStop()
    @JvmStatic external fun nativeChangeSampleRate(sampleRate: Int)
    @JvmStatic external fun nativeClear()
    @JvmStatic external fun nativeGetFrame(out: LongArray)
}
