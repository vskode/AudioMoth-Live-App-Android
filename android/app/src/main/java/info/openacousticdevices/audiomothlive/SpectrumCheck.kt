package info.openacousticdevices.audiomothlive

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * Quick on-device check that a recording contains real high-frequency
 * content: averaged power spectrum of a 16-bit mono WAV, summarised by band.
 */
object SpectrumCheck {

    private const val N = 4096

    fun analyse(file: File, sampleRate: Int): String {

        val raf = RandomAccessFile(file, "r")
        val dataBytes = (raf.length() - 44).toInt()
        val bytes = ByteArray(dataBytes)
        raf.seek(44)
        raf.readFully(bytes)
        raf.close()

        val samples = ShortArray(dataBytes / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)

        if (samples.size < N) return "Too few samples to analyse"

        val window = DoubleArray(N) { 0.5 - 0.5 * cos(2 * PI * it / (N - 1)) }
        val power = DoubleArray(N / 2)
        val re = DoubleArray(N)
        val im = DoubleArray(N)

        val blocks = minOf(200, samples.size / N)
        val stride = samples.size / blocks

        for (b in 0 until blocks) {
            val off = b * stride
            var mean = 0.0
            for (k in 0 until N) mean += samples[off + k]
            mean /= N
            for (k in 0 until N) { re[k] = (samples[off + k] - mean) * window[k]; im[k] = 0.0 }
            fft(re, im)
            for (k in 0 until N / 2) power[k] += re[k] * re[k] + im[k] * im[k]
        }

        val binHz = sampleRate.toDouble() / N
        val nyquist = sampleRate / 2
        val edges = listOf(0, 1000, 24000, 48000, 96000, 144000, 192000).filter { it < nyquist } + nyquist

        val total = power.sum()
        val sb = StringBuilder("Energy by band (dB re total):\n")

        for (e in 0 until edges.size - 1) {
            val lo = (edges[e] / binHz).toInt()
            val hi = minOf((edges[e + 1] / binHz).toInt(), N / 2)
            var s = 0.0
            for (k in lo until hi) s += power[k]
            val db = if (s > 0) 10 * log10(s / total) else -999.0
            sb.append("  %3d-%3d kHz: %7.1f dB\n".format(edges[e] / 1000, edges[e + 1] / 1000, db))
        }

        /* Strongest peak above 24 kHz */
        val startBin = (24000 / binHz).toInt()
        if (startBin < N / 2) {
            var best = startBin
            for (k in startBin until N / 2) if (power[k] > power[best]) best = k
            sb.append("Strongest component above 24 kHz: %.1f kHz".format(best * binHz / 1000))
        }

        return sb.toString()
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
