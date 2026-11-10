package info.openacousticdevices.audiomothlive

import android.graphics.Bitmap
import java.nio.ByteBuffer

/** JNI wrapper for plotter.c (port of js/plotter.js). */
object Plotter {

    init {
        System.loadLibrary("engine")
    }

    const val COLOUR_MAP_DEFAULT = 0
    const val COLOUR_MAP_MONOCHROME = 1
    const val COLOUR_MAP_INVERSE_MONOCHROME = 2

    @JvmStatic external fun nativeUpdate(
        audioBuffer: ByteBuffer, stftBuffer: ByteBuffer,
        waveform: Bitmap, spectrogram: Bitmap,
        redraw: Boolean, index: Long, count: Long, displayWidthSamples: Long,
        nightMode: Boolean, lowAmpColourScale: Boolean, colourMapIndex: Int
    ): Boolean
}
