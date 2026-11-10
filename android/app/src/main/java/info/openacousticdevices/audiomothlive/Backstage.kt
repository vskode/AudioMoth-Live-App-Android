package info.openacousticdevices.audiomothlive

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Kotlin counterpart of backstage/index.js: JNI wrapper for the ported
 * backstage engine (cpp/backstage_jni.c).
 */
object Backstage {

    init {
        System.loadLibrary("backstage")
    }

    const val AUDIO_BUFFER_SIZE = 1 shl 25

    const val MONITOR_OFF = 0
    const val MONITOR_PLAYTHROUGH = 1
    const val MONITOR_HETERODYNE = 2

    /** Maximum duration of Capture WAV / pause buffer (MAXIMUM_RECORD_DURATION) */
    const val MAXIMUM_RECORD_DURATION = 60

    lateinit var audioBuffer: ByteBuffer
        private set
    lateinit var stftBuffer: ByteBuffer
        private set

    fun initialise(): Boolean {
        val success = nativeInitialise()
        audioBuffer = nativeGetAudioBuffer().order(ByteOrder.LITTLE_ENDIAN)
        stftBuffer = nativeGetStftBuffer().order(ByteOrder.LITTLE_ENDIAN)
        return success
    }

    /** Result of getFrame(); layout matches FRAME_* in backstage_jni.c */
    class Frame {
        val raw = LongArray(12)
        val redrawRequired get() = raw[0] != 0L
        val simulationRunning get() = raw[1] != 0L
        val oldAudioMothFound get() = raw[2] != 0L
        val maximumSampleRate get() = raw[3].toInt()
        val currentSampleRate get() = raw[4].toInt()
        val audioTime get() = raw[5]
        val audioIndex get() = raw[6]
        val audioCount get() = raw[7]
        /** 0 = nothing new, 1 = capture succeeded, 2 = capture failed */
        val captureResult get() = raw[8].toInt()
        val autosaveError get() = raw[9] != 0L
        val writtenFileCounter get() = raw[10]
        val usingAudioMoth get() = raw[11] != 0L
    }

    fun getFrame(frame: Frame) = nativeGetFrame(frame.raw)

    @JvmStatic external fun nativeInitialise(): Boolean
    @JvmStatic external fun nativeGetAudioBuffer(): ByteBuffer
    @JvmStatic external fun nativeGetStftBuffer(): ByteBuffer
    @JvmStatic external fun nativeSetAudioMothDevice(fd: Int, endpoint: Int, maxPacketSize: Int, sampleRate: Int)
    @JvmStatic external fun nativeClearAudioMothDevice()
    @JvmStatic external fun nativeChangeSampleRate(sampleRate: Int)
    @JvmStatic external fun nativeGetFrame(out: LongArray)
    @JvmStatic external fun nativeGetDeviceName(): String
    @JvmStatic external fun nativeGetCaptureFilename(): String
    @JvmStatic external fun nativeGetLastWrittenFilename(): String
    @JvmStatic external fun nativeClear()
    @JvmStatic external fun nativeCapture(duration: Int)
    /** out = [audioStartTime, audioIndex, audioCount] when paused */
    @JvmStatic external fun nativeSetPause(enable: Boolean, duration: Int, out: LongArray): Boolean
    @JvmStatic external fun nativeSetFileDestination(path: String)
    @JvmStatic external fun nativeSetAutoSave(minutes: Int)
    @JvmStatic external fun nativeSetSimulationPath(path: String): String
    @JvmStatic external fun nativeSetSimulation(enable: Boolean, index: Int): Boolean
    @JvmStatic external fun nativeSetMonitor(mode: Int, frequency: Int)
    @JvmStatic external fun nativeSetHighDefaultSampleRate(enable: Boolean)
    @JvmStatic external fun nativeSetLocalTime(enable: Boolean)
    @JvmStatic external fun nativeForceAutoSaveToStop()
}
