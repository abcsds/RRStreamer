package io.github.abcsds.rrstreamer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.github.abcsds.rrstreamer.ble.HeartRateClient
import io.github.abcsds.rrstreamer.ble.HeartRateMeasurement
import io.github.abcsds.rrstreamer.lsl.LslHeartRateStreamer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Foreground service that owns the GATT connection and the LSL outlets.
 * Started with EXTRA_DEVICE_ADDRESS + EXTRA_DEVICE_NAME; stopped with [stop].
 */
class HeartRateService : Service() {

    private val binder = LocalBinder()
    private var client: HeartRateClient? = null
    private var streamer: LslHeartRateStreamer? = null
    // LSL announces over multicast. Without an active multicast lock, modern
    // Android Wi-Fi stacks drop incoming multicast packets when the screen is
    // off, so a fresh `pylsl resolve_streams` from another machine times out
    // even though TCP unicast (re-)connects work fine.
    private var multicastLock: WifiManager.MulticastLock? = null

    private val _state = MutableStateFlow<StreamingState>(StreamingState.Idle)
    val state: StateFlow<StreamingState> = _state.asStateFlow()

    inner class LocalBinder : Binder() {
        fun service(): HeartRateService = this@HeartRateService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        acquireMulticastLock()
        // Always promote to foreground BEFORE doing BLE work so the OS lets us run.
        startForeground(NOTIF_ID, buildNotification("Starting…"))
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return
            val lock = wifi.createMulticastLock("rrstreamer-lsl").apply {
                setReferenceCounted(false)
                acquire()
            }
            multicastLock = lock
            Log.d(TAG, "Multicast lock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire multicast lock: $e")
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let {
            try { if (it.isHeld) it.release() } catch (e: Exception) {
                Log.w(TAG, "Multicast lock release threw: $e")
            }
        }
        multicastLock = null
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
        val name = intent?.getStringExtra(EXTRA_DEVICE_NAME) ?: "device"
        if (address == null) {
            Log.e(TAG, "Missing EXTRA_DEVICE_ADDRESS"); stopSelf(); return START_NOT_STICKY
        }

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device: BluetoothDevice = bm.adapter.getRemoteDevice(address)

        // Sanitise the device name for use as part of LSL stream/source ids
        val sanitised = name.ifBlank { "device" }
        val outlets = try {
            LslHeartRateStreamer(sanitised)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start LSL outlets: $e", e)
            _state.value = StreamingState.Error(e.message ?: "LSL init failed")
            stopSelf(); return START_NOT_STICKY
        }
        streamer = outlets

        _state.value = StreamingState.Connecting(name)
        updateNotification("Connecting to $name…")

        client = HeartRateClient(
            context = this,
            device = device,
            onSample = { m -> handleSample(m) },
            onState = { s ->
                Log.d(TAG, "BLE state -> $s")
                when (s) {
                    HeartRateClient.State.Subscribed -> {
                        _state.value = StreamingState.Streaming(
                            deviceName = name,
                            lastHr = null,
                            lastRr = null,
                            startedAtMs = System.currentTimeMillis(),
                        )
                        updateNotification("Streaming from $name")
                    }
                    HeartRateClient.State.Disconnected -> {
                        _state.value = StreamingState.Disconnected(name)
                        updateNotification("Disconnected from $name")
                    }
                    HeartRateClient.State.Error -> {
                        _state.value = StreamingState.Error("BLE error")
                        updateNotification("BLE error")
                    }
                    HeartRateClient.State.Connecting,
                    HeartRateClient.State.Connected -> Unit
                }
            },
        ).also { it.connect() }

        return START_NOT_STICKY
    }

    private fun handleSample(m: HeartRateMeasurement) {
        val s = streamer ?: return
        s.pushHr(m.heartRateBpm)
        // RR intervals are pushed to LSL as **milliseconds** (rounded int).
        // Conversion happens in HeartRateParser, so what arrives here is already ms.
        m.rrIntervalsMs.forEach { s.pushRr(it) }
        // Atomic update — handleSample runs on the GATT binder thread, and stop()/state
        // transitions can land on other threads, so a plain read-modify-write would
        // silently drop samples or clobber a Disconnected transition.
        var deviceName: String? = null
        _state.update { current ->
            if (current is StreamingState.Streaming) {
                deviceName = current.deviceName
                val newHrHistory = (current.hrHistory + m.heartRateBpm).takeLast(HR_HISTORY_CAP)
                val newRrHistory = (current.rrHistory + m.rrIntervalsMs)
                    .takeLast(RR_HISTORY_CAP)
                current.copy(
                    lastHr = m.heartRateBpm,
                    lastRr = m.rrIntervalsMs.lastOrNull() ?: current.lastRr,
                    samples = current.samples + 1,
                    hrHistory = newHrHistory,
                    rrHistory = newRrHistory,
                    rmssdMs = computeRmssdMs(newRrHistory),
                )
            } else current
        }
        deviceName?.let { updateNotification("$it · ${m.heartRateBpm} bpm") }
        Log.d(TAG, "HR=${m.heartRateBpm} RR=${m.rrIntervalsMs}ms")
    }

    /**
     * Root Mean Square of Successive RR-interval Differences, in milliseconds.
     * A standard short-term HRV indicator. Returns null until at least 5 RR
     * samples have accumulated to keep the readout from twitching wildly.
     */
    private fun computeRmssdMs(rrMs: List<Int>): Int? {
        val window = rrMs.takeLast(RMSSD_WINDOW)
        if (window.size < 5) return null
        var sumSq = 0.0
        var n = 0
        for (i in 1 until window.size) {
            val diffMs = (window[i] - window[i - 1]).toDouble()
            sumSq += diffMs * diffMs
            n += 1
        }
        return kotlin.math.sqrt(sumSq / n).toInt()
    }

    fun stop() {
        client?.close(); client = null
        streamer?.close(); streamer = null
        _state.value = StreamingState.Idle
        releaseMulticastLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        client?.close(); client = null
        streamer?.close(); streamer = null
        releaseMulticastLock()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.notif_channel_desc) }
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("RRStreamer")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "HeartRateService"
        private const val CHANNEL_ID = "rr_streaming"
        private const val NOTIF_ID = 1

        // Rolling-buffer caps. HR is for the inline sparkline (one sample / sec from
        // the H10), RR is for the 100-beat graph + RMSSD window.
        const val HR_HISTORY_CAP = 60
        const val RR_HISTORY_CAP = 100
        const val RMSSD_WINDOW  = 30

        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_DEVICE_NAME = "device_name"

        fun start(context: Context, address: String, name: String) {
            val intent = Intent(context, HeartRateService::class.java)
                .putExtra(EXTRA_DEVICE_ADDRESS, address)
                .putExtra(EXTRA_DEVICE_NAME, name)
            context.startForegroundService(intent)
        }
    }
}

sealed interface StreamingState {
    data object Idle : StreamingState
    data class Connecting(val deviceName: String) : StreamingState
    data class Streaming(
        val deviceName: String,
        val lastHr: Int?,
        val lastRr: Int?,
        val samples: Int = 0,
        val startedAtMs: Long = 0L,
        val hrHistory: List<Int> = emptyList(),
        val rrHistory: List<Int> = emptyList(),
        val rmssdMs: Int? = null,
    ) : StreamingState
    data class Disconnected(val deviceName: String) : StreamingState
    data class Error(val message: String) : StreamingState
}
