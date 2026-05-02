package io.github.abcsds.rrstreamer.ble

import java.util.UUID

// ---------- Standard Heart Rate Service (0x180D) — used by H10, TICKR, etc. ----------
val HR_SERVICE_UUID:     UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
val HR_MEASUREMENT_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
val CCCD_UUID:           UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

// ---------- Polar Measurement Data (PMD) service ----------
// Custom service exposed by Polar OH1, OH1+, Verity Sense (and ancillary on H10).
// We use it to receive PPI (peak-to-peak) samples from PPG devices that don't
// advertise R-R intervals via the standard 0x2A37 flag bit.
val POLAR_PMD_SERVICE_UUID: UUID = UUID.fromString("FB005C80-02E7-F387-1CAD-8ACD2D8DF0C8")
val POLAR_PMD_CP_UUID:      UUID = UUID.fromString("FB005C81-02E7-F387-1CAD-8ACD2D8DF0C8")
val POLAR_PMD_DATA_UUID:    UUID = UUID.fromString("FB005C82-02E7-F387-1CAD-8ACD2D8DF0C8")

// PMD measurement type codes (subset; enough for our PPI use case).
const val PMD_TYPE_PPI: Byte = 0x03

// PMD control-point op codes.
const val PMD_OP_REQUEST_MEASUREMENT_START: Byte = 0x02
const val PMD_OP_REQUEST_MEASUREMENT_STOP:  Byte = 0x03
