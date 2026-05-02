package io.github.abcsds.rrstreamer.lsl

import android.util.Log
import io.github.abcsds.rrstreamer.ble.IntervalKind
import edu.ucsd.sccn.LSL

/**
 * Two LSL outlets, one for HR samples and one for beat-to-beat intervals.
 *
 * The HR outlet is identical regardless of source (`HR <devicename>`). The
 * interval outlet's name and source-id swap between `RR` and `PP` based on
 * [intervalKind] — RR for ECG-derived bands like the Polar H10, PP for
 * PPG-derived bands like the Verity Sense and OH1. A receiver wanting either
 * "any beat interval" can resolve by type ("Markers") and inspect the name.
 *
 * Layout per outlet matches the Rust/Python references in HRBand-LSL:
 * type "Markers", 1 int32 channel, irregular rate.
 *
 * Thread-safety: [pushHr] and [pushInterval] are invoked from the BLE binder
 * thread. liblsl's per-outlet `lsl_push_sample_*` C entrypoints are
 * thread-safe and the JNA `Pointer`s held in the Java wrapper are effectively
 * final. **Do not add mutable state to this class without synchronising it.**
 */
class LslHeartRateStreamer(
    deviceName: String,
    val intervalKind: IntervalKind,
) : AutoCloseable {

    private val tag: String = intervalKind.lslTag

    private val hrInfo: LSL.StreamInfo
    private val intervalInfo: LSL.StreamInfo
    private val hrOutlet: LSL.StreamOutlet
    private val intervalOutlet: LSL.StreamOutlet

    init {
        Log.d(TAG, "Creating LSL outlets for '$deviceName' (interval=$tag)")
        hrInfo = LSL.StreamInfo(
            "HR $deviceName",
            "Markers",
            1,
            LSL.IRREGULAR_RATE,
            LSL.ChannelFormat.int32,
            "HR_markers_$deviceName",
        )
        intervalInfo = LSL.StreamInfo(
            "$tag $deviceName",
            "Markers",
            1,
            LSL.IRREGULAR_RATE,
            LSL.ChannelFormat.int32,
            "${tag}_markers_$deviceName",
        )
        hrOutlet = LSL.StreamOutlet(hrInfo)
        intervalOutlet = LSL.StreamOutlet(intervalInfo)
        Log.d(TAG, "LSL outlets ready: HR + $tag")
    }

    fun pushHr(bpm: Int) {
        hrOutlet.push_sample(intArrayOf(bpm))
    }

    /** Push one beat-to-beat interval in **milliseconds (rounded int)**. */
    fun pushInterval(intervalMs: Int) {
        intervalOutlet.push_sample(intArrayOf(intervalMs))
    }

    override fun close() {
        try { hrOutlet.close() } catch (e: Exception) { Log.w(TAG, "hrOutlet.close() threw: $e") }
        try { intervalOutlet.close() } catch (e: Exception) { Log.w(TAG, "intervalOutlet.close() threw: $e") }
        try { hrInfo.destroy() } catch (e: Exception) { Log.w(TAG, "hrInfo.destroy() threw: $e") }
        try { intervalInfo.destroy() } catch (e: Exception) { Log.w(TAG, "intervalInfo.destroy() threw: $e") }
    }

    companion object { private const val TAG = "LslStreamer" }
}
