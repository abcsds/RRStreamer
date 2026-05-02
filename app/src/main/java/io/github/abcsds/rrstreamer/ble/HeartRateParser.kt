package io.github.abcsds.rrstreamer.ble

/**
 * What kind of beat-to-beat interval we're looking at.
 *
 *   RR — true R-R intervals from an ECG-based band's standard
 *        BLE Heart Rate Measurement notification (0x2A37). Highest accuracy;
 *        suitable for clinical-grade HRV analysis.
 *
 *   PP — peak-to-peak intervals derived from PPG (optical) sensors via
 *        Polar's Measurement Data service (PPI). Lower accuracy than RR —
 *        the PPG waveform smooths out short cycles, so derived HRV metrics
 *        like RMSSD are an *approximation*.
 */
enum class IntervalKind {
    RR,
    PP;

    /** Display label in the UI ("RR" / "PP"). */
    val label: String get() = name

    /** Which LSL stream-name + source-id prefix this kind contributes. */
    val lslTag: String get() = name
}

/**
 * Parsed beat-to-beat measurement. The same shape works for ECG-based RR data
 * (from 0x2A37) and PPG-based PP data (from the Polar PMD PPI stream); the
 * [intervalKind] field tells consumers which one this is.
 */
data class HeartRateMeasurement(
    val heartRateBpm: Int,
    val intervalKind: IntervalKind,
    val intervalsMs: List<Int>,
    val sensorContact: SensorContact = SensorContact.NotSupported,
    val energyExpended: Int? = null,
)

enum class SensorContact { NotSupported, NotDetected, Detected }

/**
 * Parses the BLE Heart Rate Measurement characteristic (0x2A37). Always
 * produces [IntervalKind.RR] — by definition this characteristic carries
 * R-R intervals when it carries any intervals at all.
 */
object HeartRateParser {
    /**
     * Throws IllegalArgumentException on malformed input.
     *
     * Flags byte (byte 0):
     *   bit 0    : HR format (0 = uint8, 1 = uint16 LE)
     *   bits 1-2 : sensor contact status
     *   bit 3    : energy-expended present (uint16 LE)
     *   bit 4    : RR intervals present (uint16 LE pairs, 1/1024 s units)
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
            val v = u16le(data, idx); idx += 2; v
        } else {
            require(data.size >= 2) { "Insufficient data for uint8 heart rate" }
            val v = data[idx].toInt() and 0xFF; idx += 1; v
        }

        val energy = if (hasEnergy) {
            require(data.size >= idx + 2) { "Insufficient data for energy expended" }
            val v = u16le(data, idx); idx += 2; v
        } else null

        val rrMs = mutableListOf<Int>()
        if (hasRr) {
            while (idx + 1 < data.size) {
                rrMs.add(rawToMs(u16le(data, idx)))
                idx += 2
            }
            require(idx == data.size) { "Incomplete RR interval data (odd byte at end)" }
        }

        return HeartRateMeasurement(
            heartRateBpm = hr,
            intervalKind = IntervalKind.RR,
            intervalsMs = rrMs,
            sensorContact = contact,
            energyExpended = energy,
        )
    }

    /** Convert the BLE raw 1/1024-s RR value to milliseconds, rounded. */
    private fun rawToMs(raw: Int): Int = ((raw * 1000L + 512L) / 1024L).toInt()

    /** Returns true if this notification advertises RR intervals via flag bit 4. */
    fun hasRrIntervals(data: ByteArray): Boolean =
        data.isNotEmpty() && ((data[0].toInt() and 0x10) != 0)

    private fun u16le(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
}
