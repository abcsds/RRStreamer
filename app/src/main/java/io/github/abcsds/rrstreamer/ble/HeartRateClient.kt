package io.github.abcsds.rrstreamer.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Connects to a heart rate band, subscribes to characteristic 0x2A37, and forwards
 * each parsed sample to [onSample]. Connection lifecycle events go to [onState].
 *
 * Single-shot: call [connect] once, [close] when finished. Any [State.Error] emission
 * means the underlying GATT has already been torn down — callers do not need to call
 * [close] in response, but doing so is harmless.
 */
class HeartRateClient(
    private val context: Context,
    private val device: BluetoothDevice,
    private val onSample: (HeartRateMeasurement) -> Unit,
    private val onState: (State) -> Unit,
) {
    enum class State { Connecting, Connected, Subscribed, Disconnected, Error }

    private var gatt: BluetoothGatt? = null

    @SuppressLint("MissingPermission")
    fun connect() {
        onState(State.Connecting)
        Log.d(TAG, "Connecting GATT to ${device.address}")
        val g = device.connectGatt(context, /* autoConnect = */ false, gattCallback)
        if (g == null) {
            Log.e(TAG, "connectGatt returned null — BLE stack unavailable")
            onState(State.Error)
            return
        }
        gatt = g
    }

    @SuppressLint("MissingPermission")
    fun close() {
        val g = gatt ?: return
        gatt = null
        try { g.disconnect() } catch (e: Exception) { Log.w(TAG, "disconnect threw: $e") }
        try { g.close() } catch (e: Exception) { Log.w(TAG, "close threw: $e") }
    }

    /** Tear the GATT down and emit [State.Error]. Safe to call from any callback. */
    @SuppressLint("MissingPermission")
    private fun fail(reason: String) {
        Log.e(TAG, "Aborting: $reason")
        close()
        onState(State.Error)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT connected, discovering services")
                    onState(State.Connected)
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT disconnected (status=$status)")
                    // The OS already closed its side; release ours and null the field so
                    // any subsequent close() call is a no-op.
                    gatt = null
                    try { g.close() } catch (_: Exception) {}
                    onState(State.Disconnected)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("service discovery failed: $status"); return
            }
            val service = g.getService(HR_SERVICE_UUID)
                ?: return fail("Heart Rate service not present on device")
            val ch = service.getCharacteristic(HR_MEASUREMENT_UUID)
                ?: return fail("Heart Rate Measurement characteristic not found")
            if (!g.setCharacteristicNotification(ch, true)) {
                return fail("setCharacteristicNotification returned false")
            }
            val cccd = ch.getDescriptor(CCCD_UUID)
                ?: return fail("CCCD descriptor missing on HR characteristic")
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            if (!ok) fail("CCCD writeDescriptor request was rejected")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CCCD_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Subscribed to HR notifications")
                onState(State.Subscribed)
            } else {
                fail("CCCD write failed: $status")
            }
        }

        // Pre-Tiramisu API
        @Deprecated("Use the byte[]-array overload on API 33+")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid != HR_MEASUREMENT_UUID) return
            @Suppress("DEPRECATION")
            handle(ch.value)
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid != HR_MEASUREMENT_UUID) return
            handle(value)
        }
    }

    private fun handle(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return
        try {
            onSample(HeartRateParser.parse(bytes))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse HR notification: $e")
        }
    }

    companion object { private const val TAG = "HeartRateClient" }
}
