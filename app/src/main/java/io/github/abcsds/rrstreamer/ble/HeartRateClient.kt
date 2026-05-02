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
 * Connects to a heart-rate band, picks the right BLE protocol per device, and
 * forwards each parsed sample to [onSample]. Connection lifecycle goes to
 * [onState].
 *
 * Protocol selection (decided on the first 0x2A37 notification):
 *
 *   1. Connect, discover services, subscribe to the standard Heart Rate
 *      Measurement characteristic (0x2A37).
 *   2. On the FIRST notification, look at the flag byte:
 *        a. If RR-intervals bit (bit 4) is set → ECG-style band (Polar H10,
 *           Wahoo TICKR, Garmin HRM-Pro, etc.). Stay subscribed; emit
 *           HeartRateMeasurement(kind = RR).
 *        b. Else if the device exposes the Polar PMD service → PPG-style band
 *           (Polar Verity Sense, OH1, OH1+). Unsubscribe 0x2A37, write a
 *           "start PPI" request to the PMD control point, subscribe to PMD
 *           data; emit HeartRateMeasurement(kind = PP).
 *        c. Else → HR-only band. Stay subscribed; emit
 *           HeartRateMeasurement(kind = RR, intervalsMs = []).
 *
 * Single-shot: call [connect] once, [close] when finished. Any [State.Error]
 * emission means the underlying GATT has already been torn down.
 */
class HeartRateClient(
    private val context: Context,
    private val device: BluetoothDevice,
    private val onSample: (HeartRateMeasurement) -> Unit,
    private val onState: (State) -> Unit,
) {
    enum class State { Connecting, Connected, Subscribed, Disconnected, Error }

    private enum class Mode { Initial, StandardRr, HrOnly, PolarPpi }

    private var gatt: BluetoothGatt? = null
    private var mode: Mode = Mode.Initial

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
        // Best-effort: ask the device to stop the PPI stream so its battery isn't
        // drained by an orphaned subscription. Failures are non-fatal — we're
        // closing anyway.
        if (mode == Mode.PolarPpi) {
            try {
                val cp = g.getService(POLAR_PMD_SERVICE_UUID)?.getCharacteristic(POLAR_PMD_CP_UUID)
                if (cp != null) writeCharacteristic(g, cp, PolarPmd.STOP_PPI_REQUEST)
            } catch (e: Exception) {
                Log.w(TAG, "PMD stop request threw: $e")
            }
        }
        try { g.disconnect() } catch (e: Exception) { Log.w(TAG, "disconnect threw: $e") }
        try { g.close() } catch (e: Exception) { Log.w(TAG, "close threw: $e") }
    }

    @SuppressLint("MissingPermission")
    private fun fail(reason: String) {
        Log.e(TAG, "Aborting: $reason")
        close()
        onState(State.Error)
    }

    // ---------------------------------------------------------------
    // GATT callback
    // ---------------------------------------------------------------
    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT connected; requesting MTU $REQUESTED_MTU")
                    onState(State.Connected)
                    // Polar PMD's start-measurement request returns ERROR_INVALID_MTU
                    // on the default 23-byte MTU. Bump first; service discovery
                    // continues from onMtuChanged. If the request itself can't be
                    // queued, fall through to discovery with the default MTU —
                    // bands that don't need PMD (H10, TICKR, etc.) work fine at 23.
                    if (!g.requestMtu(REQUESTED_MTU)) {
                        Log.w(TAG, "requestMtu rejected; proceeding with default MTU")
                        g.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT disconnected (status=$status)")
                    gatt = null
                    try { g.close() } catch (_: Exception) {}
                    onState(State.Disconnected)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU negotiated: $mtu (status=$status); discovering services")
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                return fail("service discovery failed: $status")
            }
            Log.d(TAG, "Services discovered. PMD present: ${g.getService(POLAR_PMD_SERVICE_UUID) != null}")

            val service = g.getService(HR_SERVICE_UUID)
                ?: return fail("Heart Rate service (0x180D) not present on device")
            val ch = service.getCharacteristic(HR_MEASUREMENT_UUID)
                ?: return fail("Heart Rate Measurement characteristic not found")
            if (!enableNotifications(g, ch, indication = false)) {
                return fail("Failed to enable HR notifications")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CCCD_UUID) return
            val charUuid = descriptor.characteristic?.uuid
            if (status != BluetoothGatt.GATT_SUCCESS) {
                return fail("CCCD write failed (uuid=$charUuid, status=$status)")
            }
            when (charUuid) {
                HR_MEASUREMENT_UUID -> {
                    Log.d(TAG, "Subscribed to 0x2A37 (standard HR)")
                    if (mode == Mode.Initial) onState(State.Subscribed)
                }
                POLAR_PMD_CP_UUID -> {
                    // CP CCCD enabled (indications). Now chain the data CCCD write —
                    // Android's GATT queue is strictly serial, so we must wait for
                    // the previous descriptor write to complete before firing the next.
                    Log.d(TAG, "PMD CP indications enabled; subscribing to PMD data")
                    val data = g.getService(POLAR_PMD_SERVICE_UUID)
                        ?.getCharacteristic(POLAR_PMD_DATA_UUID)
                        ?: return fail("PMD data characteristic disappeared after discovery")
                    if (!enableNotifications(g, data, indication = false)) {
                        return fail("Could not subscribe to PMD data characteristic")
                    }
                }
                POLAR_PMD_DATA_UUID -> {
                    // Data CCCD enabled (notifications). Now write the START_PPI
                    // request to the control point. PPI frames will start arriving
                    // on the data characteristic shortly after the CP write is acked.
                    Log.d(TAG, "PMD data notifications enabled; writing START_PPI request")
                    val cp = g.getService(POLAR_PMD_SERVICE_UUID)
                        ?.getCharacteristic(POLAR_PMD_CP_UUID)
                        ?: return fail("PMD control point disappeared after discovery")
                    if (!writeCharacteristic(g, cp, PolarPmd.START_PPI_REQUEST)) {
                        return fail("PMD start-PPI write rejected")
                    }
                }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (ch.uuid == POLAR_PMD_CP_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "PMD start-PPI request accepted; awaiting frames")
                    onState(State.Subscribed)
                } else {
                    fail("PMD start-PPI write returned status=$status")
                }
            }
        }

        // Pre-Tiramisu API
        @Deprecated("Use the byte[]-array overload on API 33+")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleNotification(g, ch.uuid, ch.value ?: return)
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(g, ch.uuid, value)
        }
    }

    // ---------------------------------------------------------------
    // Notification routing & first-frame protocol-selection logic
    // ---------------------------------------------------------------
    @SuppressLint("MissingPermission")
    private fun handleNotification(g: BluetoothGatt, uuid: java.util.UUID, value: ByteArray) {
        if (value.isEmpty()) return
        when (uuid) {
            HR_MEASUREMENT_UUID -> handleStandardHrFrame(g, value)
            POLAR_PMD_DATA_UUID -> handlePmdDataFrame(value)
            POLAR_PMD_CP_UUID   -> Log.d(TAG, "PMD CP indication: ${value.toHexShort()}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleStandardHrFrame(g: BluetoothGatt, value: ByteArray) {
        // Only the FIRST 0x2A37 frame drives the protocol-selection decision.
        if (mode == Mode.Initial) {
            val rrAdvertised = HeartRateParser.hasRrIntervals(value)
            val pmdAvailable = g.getService(POLAR_PMD_SERVICE_UUID) != null

            mode = when {
                rrAdvertised -> Mode.StandardRr
                pmdAvailable -> Mode.PolarPpi
                else         -> Mode.HrOnly
            }
            Log.d(TAG, "Protocol selected: $mode (rrFlag=$rrAdvertised, pmd=$pmdAvailable)")

            if (mode == Mode.PolarPpi) {
                // Switch off the standard HR notifications and bring the PMD path online.
                val service = g.getService(HR_SERVICE_UUID)
                val hrCh = service?.getCharacteristic(HR_MEASUREMENT_UUID)
                if (hrCh != null) {
                    g.setCharacteristicNotification(hrCh, false)
                }
                if (!startPolarPpi(g)) {
                    fail("Could not bring up Polar PMD/PPI subscription")
                }
                // Drop this 0x2A37 frame; PPI will provide HR + PP from now on.
                return
            }
        }

        // Standard-RR or HR-only mode — just parse and forward.
        try {
            onSample(HeartRateParser.parse(value))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse 0x2A37 notification: $e")
        }
    }

    private fun handlePmdDataFrame(value: ByteArray) {
        if (mode != Mode.PolarPpi) return
        val samples = PolarPmd.parsePpiFrame(value)
        if (samples.isEmpty()) {
            // Either a non-PPI frame or every sample was filtered (no contact /
            // HR=0). Log a short hexdump so we can tell which from logcat.
            Log.d(TAG, "PMD frame produced 0 samples (${value.size}B): ${value.toHexShort()}")
        } else {
            for (s in samples) onSample(s)
        }
    }

    /**
     * Kick off the PMD subscription chain. Only the FIRST step is fired here —
     * Android's GATT queue is strictly serial, so the remaining steps (data
     * CCCD write, START_PPI write) are chained from [onDescriptorWrite] and
     * [onCharacteristicWrite] as each prior operation completes.
     *
     * Sequence:
     *   1. CP CCCD = 0x02 0x00 (enable indications) — fired here
     *   2. on CP CCCD ack → DATA CCCD = 0x01 0x00 (enable notifications)
     *   3. on DATA CCCD ack → write [0x02, 0x03] to CP (START_PPI)
     *   4. on CP write ack → onState(Subscribed); PPI frames arrive
     */
    @SuppressLint("MissingPermission")
    private fun startPolarPpi(g: BluetoothGatt): Boolean {
        val pmd = g.getService(POLAR_PMD_SERVICE_UUID) ?: return false
        val cp = pmd.getCharacteristic(POLAR_PMD_CP_UUID) ?: return false
        // Data CCCD lookup is deferred to the CP-CCCD-ack callback to keep
        // failure paths localised; here we just need to confirm the char exists
        // before kicking off the chain so we don't fail silently mid-sequence.
        if (pmd.getCharacteristic(POLAR_PMD_DATA_UUID) == null) return false
        return enableNotifications(g, cp, indication = true)
    }

    // ---------------------------------------------------------------
    // GATT helpers
    // ---------------------------------------------------------------
    @SuppressLint("MissingPermission")
    private fun enableNotifications(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        indication: Boolean,
    ): Boolean {
        if (!g.setCharacteristicNotification(ch, true)) return false
        val cccd = ch.getDescriptor(CCCD_UUID) ?: return false
        val payload = if (indication)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, payload) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = payload
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.value = bytes
            @Suppress("DEPRECATION")
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
    }

    private fun ByteArray.toHexShort(): String =
        joinToString(" ", limit = 16) { "%02x".format(it.toInt() and 0xFF) }

    companion object {
        private const val TAG = "HeartRateClient"
        // Bumped from the BLE default of 23 because Polar's PMD control point
        // rejects the start-PPI request with ERROR_INVALID_MTU on small MTUs.
        // 232 is the value used by Polar's own polar-ble-sdk.
        private const val REQUESTED_MTU = 232
    }
}
