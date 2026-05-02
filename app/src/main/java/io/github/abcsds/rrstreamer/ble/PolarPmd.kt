package io.github.abcsds.rrstreamer.ble

/**
 * Polar Measurement Data (PMD) protocol — the proprietary BLE service used by
 * Polar OH1, OH1+, and Verity Sense to expose PPG-derived peak-to-peak
 * intervals (PPI). Polar publishes the protocol via their open polar-ble-sdk;
 * this file implements just enough of it to subscribe to PPI and parse frames.
 *
 * Protocol summary (PPI start request):
 *   1. Subscribe to indications on the PMD Control Point (CCCD = 0x02 0x00).
 *   2. Subscribe to notifications on the PMD Data char  (CCCD = 0x01 0x00).
 *   3. Write [0x02, 0x03] to the control point — "start measurement type=PPI".
 *      No additional setting bytes are needed; PPI is event-driven (one sample
 *      per detected beat). The device responds with an indication and then
 *      starts emitting PPI frames on the data characteristic.
 *
 * Frame layout (PMD data notification, PPI):
 *   [0]    measurement type, must equal 0x03
 *   [1..8] timestamp uint64 LE (ns since 2000-01-01)
 *   [9]    frame format
 *   [10..] sample blocks, 6 bytes each
 *
 * Each sample block:
 *   [0]    HR (uint8 bpm)
 *   [1..2] PPI (uint16 LE, milliseconds — already in the unit we want)
 *   [3..4] PPI error estimate (uint16 LE) — confidence indicator
 *   [5]    blocker flags
 *            bit 0 — skin contact bit (1 = no contact)
 *            bit 1 — skin contact supported
 *            bit 2 — low signal
 *            bit 3..7 — reserved
 *
 * We drop samples flagged as no-skin-contact and samples with HR == 0.
 */
object PolarPmd {

    /** Bytes to write to the PMD CP to start a PPI measurement. */
    val START_PPI_REQUEST: ByteArray = byteArrayOf(
        PMD_OP_REQUEST_MEASUREMENT_START, PMD_TYPE_PPI,
    )

    /** Bytes to write to the PMD CP to stop a PPI measurement. */
    val STOP_PPI_REQUEST: ByteArray = byteArrayOf(
        PMD_OP_REQUEST_MEASUREMENT_STOP, PMD_TYPE_PPI,
    )

    /**
     * Parse a PMD data notification carrying PPI samples.
     * Returns one [HeartRateMeasurement] per accepted sample block. May return
     * an empty list if the frame is malformed or every sample was filtered.
     */
    fun parsePpiFrame(data: ByteArray): List<HeartRateMeasurement> {
        if (data.size < HEADER_SIZE) return emptyList()
        if (data[0] != PMD_TYPE_PPI) return emptyList()

        val out = mutableListOf<HeartRateMeasurement>()
        var idx = HEADER_SIZE
        while (idx + SAMPLE_SIZE <= data.size) {
            val hr  = data[idx].toInt() and 0xFF
            val ppi = (data[idx + 1].toInt() and 0xFF) or
                      ((data[idx + 2].toInt() and 0xFF) shl 8)
            // val err = (data[idx + 3].toInt() and 0xFF) or
            //           ((data[idx + 4].toInt() and 0xFF) shl 8)
            val blockers = data[idx + 5].toInt() and 0xFF
            idx += SAMPLE_SIZE

            val noContact = (blockers and 0x01) != 0
            if (noContact || hr == 0 || ppi == 0) continue

            out += HeartRateMeasurement(
                heartRateBpm = hr,
                intervalKind = IntervalKind.PP,
                intervalsMs = listOf(ppi),
                sensorContact = SensorContact.Detected,
            )
        }
        return out
    }

    private const val HEADER_SIZE = 10
    private const val SAMPLE_SIZE = 6
}
