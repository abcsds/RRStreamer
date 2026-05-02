package io.github.abcsds.rrstreamer.ble

/**
 * Parsed BLE Heart Rate Measurement (characteristic 0x2A37) per the spec.
 *
 * Note on units: the BLE characteristic reports R-R intervals in 1/1024-second
 * units. We convert to **milliseconds (rounded to int)** at parse time so every
 * downstream consumer — UI, RMSSD, LSL outlet — speaks the same units. This is
 * a deliberate divergence from the Python and Rust references in
 * `HRBand-LSL`, which forwarded the raw 16-bit value as-is.
 */
data class HeartRateMeasurement(
    val heartRateBpm: Int,
    val sensorContact: SensorContact,
    val energyExpended: Int?,
    /** R-R intervals in **milliseconds** (rounded). Empty when not advertised. */
    val rrIntervalsMs: List<Int>,
)

enum class SensorContact { NotSupported, NotDetected, Detected }

object HeartRateParser {
    /**
     * Throws IllegalArgumentException on malformed input.
     *
     * Flags byte (byte 0):
     *   bit 0    : HR format (0 = uint8, 1 = uint16 LE)
     *   bits 1-2 : sensor contact status
     *   bit 3    : energy-expended present (uint16 LE)
     *   bit 4    : RR intervals present (uint16 LE pairs, 1/1024 s units, repeated)
     */
    fun parse(data: ByteArray): HeartRateMeasurement {
        require(data.isNotEmpty()) { "Heart rate data is empty" }

        val flags = data[0].toInt() and 0xFF
        val isUint16 = (flags and 0x01) != 0
        val contactBits = (flags ushr 1) and 0x03
        val hasEnergy = (flags and 0x08) != 0
        val hasRr = (flags and 0x10) != 0

        val contact = when (contactBits) {
            0, 1 -> SensorContact.NotSupported
            2 -> SensorContact.NotDetected
            else -> SensorContact.Detected
        }

        var idx = 1
        val hr = if (isUint16) {
            require(data.size >= 3) { "Insufficient data for uint16 heart rate" }
            val v = u16le(data, idx)
            idx += 2
            v
        } else {
            require(data.size >= 2) { "Insufficient data for uint8 heart rate" }
            val v = data[idx].toInt() and 0xFF
            idx += 1
            v
        }

        val energy = if (hasEnergy) {
            require(data.size >= idx + 2) { "Insufficient data for energy expended" }
            val v = u16le(data, idx); idx += 2; v
        } else null

        val rr = mutableListOf<Int>()
        if (hasRr) {
            while (idx + 1 < data.size) {
                // Convert raw 1/1024-s units to milliseconds at the parse boundary.
                rr.add(rawToMs(u16le(data, idx)))
                idx += 2
            }
            require(idx == data.size) { "Incomplete RR interval data (odd byte at end)" }
        }

        return HeartRateMeasurement(hr, contact, energy, rr)
    }

    /** Convert the BLE raw 1/1024-s RR value to milliseconds, rounded. */
    private fun rawToMs(raw: Int): Int = ((raw * 1000L + 512L) / 1024L).toInt()

    private fun u16le(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
}
