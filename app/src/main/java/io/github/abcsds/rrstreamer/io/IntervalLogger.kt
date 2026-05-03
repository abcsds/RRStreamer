package io.github.abcsds.rrstreamer.io

import android.content.Context
import android.os.Environment
import android.util.Log
import io.github.abcsds.rrstreamer.ble.IntervalKind
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only text log of beat-to-beat intervals (RR or PP), one rounded
 * millisecond integer per line. Acts as a local fallback when the LSL stream
 * isn't being recorded over the network — the file lives in the app's
 * external Documents directory so it can be pulled via `adb pull`, the
 * system Files app, or USB transfer without any extra runtime permission.
 *
 * Logger failures are swallowed (with a warn log) on purpose: a missing SD
 * card or an I/O hiccup must never tear down the live BLE → LSL pipeline.
 */
class IntervalLogger private constructor(
    private val file: File,
    private val writer: BufferedWriter,
) : AutoCloseable {

    val path: String get() = file.absolutePath

    fun append(intervalMs: Int) {
        try {
            writer.write(intervalMs.toString())
            writer.newLine()
            // Flush after every line so a crash or kill never loses more than
            // the in-flight sample. Cost is trivial at ~1 Hz interval rates.
            writer.flush()
        } catch (e: Exception) {
            Log.w(TAG, "append failed (${file.name}): $e")
        }
    }

    override fun close() {
        // BufferedWriter.close() flushes on its way out, so no explicit flush.
        try { writer.close() } catch (e: Exception) { Log.w(TAG, "close failed (${file.name}): $e") }
    }

    companion object {
        private const val TAG = "IntervalLogger"

        fun open(context: Context, kind: IntervalKind): IntervalLogger? {
            // getExternalFilesDir(...) creates the directory on first call, so
            // no explicit mkdirs. Returns null only when external storage is
            // genuinely unmounted.
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: run {
                    Log.w(TAG, "External Documents dir unavailable; not logging")
                    return null
                }
            // Millisecond precision avoids stop/start-in-the-same-second
            // collisions silently merging two sessions into one file.
            val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())
            val file = File(dir, "$ts.txt")
            return try {
                val writer = BufferedWriter(FileWriter(file, /* append = */ false))
                Log.i(TAG, "Logging $kind intervals to ${file.absolutePath}")
                IntervalLogger(file, writer)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open ${file.absolutePath}: $e", e)
                null
            }
        }
    }
}
