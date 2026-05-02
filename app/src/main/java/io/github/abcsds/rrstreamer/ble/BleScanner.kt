package io.github.abcsds.rrstreamer.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A Heart Rate-service-filtered BLE scanner. */
class BleScanner(context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    val isReady: Boolean get() = adapter?.isEnabled == true

    /**
     * Emits [BluetoothDevice]s advertising the standard Heart Rate Service.
     * The flow stays open until cancelled; cancel to stop scanning.
     *
     * Mirrors the Python and Rust references' filter rule: drop devices whose
     * advertised name is missing, empty, or contains `-`. The filter is applied
     * here (at the Flow boundary) so the rest of the app sees one consistent
     * device list — the UI never has to remember to re-apply the same rule.
     */
    @SuppressLint("MissingPermission")
    fun scan(): Flow<BluetoothDevice> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth not available or disabled"))
            return@callbackFlow
        }

        val seen = mutableSetOf<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val name = try { dev.name } catch (_: SecurityException) { null }
                if (name.isNullOrBlank() || name.contains('-')) return
                if (seen.add(dev.address)) trySend(dev)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(0, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed: $errorCode"))
            }
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HR_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        Log.d(TAG, "Starting BLE scan (filtering for Heart Rate service)")
        scanner.startScan(listOf(filter), settings, callback)

        awaitClose {
            Log.d(TAG, "Stopping BLE scan")
            try { scanner.stopScan(callback) } catch (e: Exception) {
                Log.w(TAG, "stopScan threw: $e")
            }
        }
    }

    companion object {
        private const val TAG = "BleScanner"
    }
}
