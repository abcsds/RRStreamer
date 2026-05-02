package io.github.abcsds.rrstreamer.lsl

import android.util.Log
import edu.ucsd.sccn.LSL

/**
 * Two LSL outlets, one for HR samples and one for RR intervals. Layout matches the
 * Rust/Python references: type "Markers", 1 int32 channel, irregular rate.
 *
 * Thread-safety: [pushHr] and [pushRr] are invoked from the BLE binder thread.
 * That works because liblsl's per-outlet `lsl_push_sample_*` C entrypoints are
 * thread-safe and the JNA `Pointer`s held in the Java wrapper are effectively
 * final. **Do not add mutable state to this class without synchronising it.**
 */
class LslHeartRateStreamer(deviceName: String) : AutoCloseable {

    private val hrInfo: LSL.StreamInfo
    private val rrInfo: LSL.StreamInfo
    private val hrOutlet: LSL.StreamOutlet
    private val rrOutlet: LSL.StreamOutlet

    init {
        Log.d(TAG, "Creating LSL outlets for device '$deviceName'")
        hrInfo = LSL.StreamInfo(
            "HR $deviceName",
            "Markers",
            1,
            LSL.IRREGULAR_RATE,
            LSL.ChannelFormat.int32,
            "HR_markers_$deviceName",
        )
        rrInfo = LSL.StreamInfo(
            "RR $deviceName",
            "Markers",
            1,
            LSL.IRREGULAR_RATE,
            LSL.ChannelFormat.int32,
            "RR_markers_$deviceName",
        )
        hrOutlet = LSL.StreamOutlet(hrInfo)
        rrOutlet = LSL.StreamOutlet(rrInfo)
        Log.d(TAG, "LSL outlets ready")
    }

    fun pushHr(bpm: Int) {
        hrOutlet.push_sample(intArrayOf(bpm))
    }

    /** Push a single R-R interval. Units are **milliseconds (rounded int)**. */
    fun pushRr(rrMs: Int) {
        rrOutlet.push_sample(intArrayOf(rrMs))
    }

    override fun close() {
        try { hrOutlet.close() } catch (e: Exception) { Log.w(TAG, "hrOutlet.close() threw: $e") }
        try { rrOutlet.close() } catch (e: Exception) { Log.w(TAG, "rrOutlet.close() threw: $e") }
        try { hrInfo.destroy() } catch (e: Exception) { Log.w(TAG, "hrInfo.destroy() threw: $e") }
        try { rrInfo.destroy() } catch (e: Exception) { Log.w(TAG, "rrInfo.destroy() threw: $e") }
    }

    companion object { private const val TAG = "LslStreamer" }
}
