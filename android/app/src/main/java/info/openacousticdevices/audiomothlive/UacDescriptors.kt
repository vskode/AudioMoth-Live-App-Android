package info.openacousticdevices.audiomothlive

/**
 * Minimal parser for USB Audio Class 1.0 descriptors, enough to find the
 * AudioStreaming interface, its isochronous IN endpoint and the sample format.
 */

data class StreamingAlt(
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val endpointAddress: Int,
    val endpointAttributes: Int,
    val maxPacketSize: Int,
    val channels: Int,
    val subframeSize: Int,
    val bitResolution: Int,
    val sampleRates: List<Int>,
    val supportsSampleRateControl: Boolean
)

object UacDescriptors {

    private const val DESC_INTERFACE = 0x04
    private const val DESC_ENDPOINT = 0x05
    private const val DESC_CS_INTERFACE = 0x24
    private const val DESC_CS_ENDPOINT = 0x25
    private const val CLASS_AUDIO = 0x01
    private const val SUBCLASS_AUDIOSTREAMING = 0x02
    private const val AS_FORMAT_TYPE = 0x02

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff

    fun hexDump(raw: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < raw.size) {
            val len = u8(raw, i)
            if (len == 0) break
            val end = minOf(i + len, raw.size)
            sb.append(raw.copyOfRange(i, end).joinToString(" ") { "%02x".format(it) }).append('\n')
            i += len
        }
        return sb.toString()
    }

    fun findStreamingAlts(raw: ByteArray): List<StreamingAlt> {

        val result = mutableListOf<StreamingAlt>()

        var inStreaming = false
        var ifNum = -1
        var alt = -1
        var channels = 0
        var subframe = 0
        var bits = 0
        var rates = listOf<Int>()
        var epAddr = -1
        var epAttr = 0
        var epMps = 0
        var srControl = false

        fun flush() {
            if (inStreaming && epAddr >= 0 && (epAddr and 0x80) != 0 && epMps > 0) {
                result += StreamingAlt(ifNum, alt, epAddr, epAttr, epMps, channels, subframe, bits, rates, srControl)
            }
        }

        var i = 0
        while (i + 1 < raw.size) {
            val len = u8(raw, i)
            if (len < 2 || i + len > raw.size) break
            val type = u8(raw, i + 1)

            when (type) {
                DESC_INTERFACE -> {
                    flush()
                    ifNum = u8(raw, i + 2)
                    alt = u8(raw, i + 3)
                    inStreaming = u8(raw, i + 5) == CLASS_AUDIO && u8(raw, i + 6) == SUBCLASS_AUDIOSTREAMING
                    channels = 0; subframe = 0; bits = 0; rates = listOf()
                    epAddr = -1; epAttr = 0; epMps = 0; srControl = false
                }
                DESC_CS_INTERFACE -> {
                    if (inStreaming && len >= 8 && u8(raw, i + 2) == AS_FORMAT_TYPE && u8(raw, i + 3) == 1) {
                        channels = u8(raw, i + 4)
                        subframe = u8(raw, i + 5)
                        bits = u8(raw, i + 6)
                        val nFreq = u8(raw, i + 7)
                        val count = if (nFreq == 0) 2 else nFreq
                        rates = (0 until count).mapNotNull { k ->
                            val o = i + 8 + 3 * k
                            if (o + 2 < i + len) u8(raw, o) or (u8(raw, o + 1) shl 8) or (u8(raw, o + 2) shl 16) else null
                        }
                    }
                }
                DESC_ENDPOINT -> {
                    if (inStreaming && len >= 7) {
                        epAddr = u8(raw, i + 2)
                        epAttr = u8(raw, i + 3)
                        epMps = (u8(raw, i + 4) or (u8(raw, i + 5) shl 8)) and 0x7ff
                    }
                }
                DESC_CS_ENDPOINT -> {
                    if (inStreaming && len >= 3) srControl = (u8(raw, i + 3) and 0x01) != 0
                }
            }
            i += len
        }
        flush()

        return result
    }

    /** Parse "384kHz AudioMoth USB Microphone" -> 384000, as backstage.c does. */
    fun sampleRateFromName(name: String?): Int? {
        if (name == null) return null
        val m = Regex("(\\d+)kHz").find(name) ?: return null
        return m.groupValues[1].toInt() * 1000
    }
}
