package io.github.abcsds.rrstreamer

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import io.github.abcsds.rrstreamer.ble.BleScanner
import io.github.abcsds.rrstreamer.ui.RRStreamerTheme
import io.github.abcsds.rrstreamer.ui.ScanScreen
import io.github.abcsds.rrstreamer.ui.StreamingScreen
import io.github.abcsds.rrstreamer.ui.Tokens
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    private val state: AppState by viewModels()
    private var bound: HeartRateService? = null
    private var stateCollectJob: Job? = null

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as HeartRateService.LocalBinder).service()
            bound = svc
            stateCollectJob?.cancel()
            stateCollectJob = lifecycleScope.launch {
                svc.state.collect { state.streaming.value = it }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            bound = null
            stateCollectJob?.cancel()
            stateCollectJob = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        state.permissionsGranted.value = result.values.all { it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindService(Intent(this, HeartRateService::class.java), serviceConn, Context.BIND_AUTO_CREATE)

        setContent {
            RRStreamerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Tokens.Background,
                ) {
                    AppRoot(
                        state = state,
                        onRequestPermissions = {
                            permissionLauncher.launch(requiredPermissions().toTypedArray())
                        },
                        onPickDevice = { dev, displayName ->
                            HeartRateService.start(this, dev.address, displayName)
                        },
                        onStop = { bound?.stop() },
                    )
                }
            }
            LaunchedEffect(Unit) {
                state.permissionsGranted.value = hasAllPermissions()
                if (!state.permissionsGranted.value) {
                    permissionLauncher.launch(requiredPermissions().toTypedArray())
                }
            }
        }
    }

    override fun onDestroy() {
        try { unbindService(serviceConn) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}

class AppState : ViewModel() {
    val permissionsGranted = MutableStateFlow(false)
    val scanning = MutableStateFlow(false)
    val devices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val streaming = MutableStateFlow<StreamingState>(StreamingState.Idle)
}

@Composable
private fun AppRoot(
    state: AppState,
    onRequestPermissions: () -> Unit,
    onPickDevice: (BluetoothDevice, String) -> Unit,
    onStop: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val permitted by state.permissionsGranted.collectAsState()
    val scanning by state.scanning.collectAsState()
    val devices by state.devices.collectAsState()
    val streaming by state.streaming.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }

    if (!permitted) {
        PermissionGate(onRequestPermissions)
        return
    }

    when (val s = streaming) {
        is StreamingState.Streaming, is StreamingState.Connecting -> {
            StreamingScreen(state = s, onStop = onStop)
        }
        is StreamingState.Idle, is StreamingState.Disconnected, is StreamingState.Error -> {
            // Decorate Idle with an optional banner from the previous run.
            val banner = when (s) {
                is StreamingState.Disconnected -> "Disconnected from ${s.deviceName}"
                is StreamingState.Error        -> "Error: ${s.message}"
                else -> null
            }
            ScanScreen(
                devices = devices,
                scanning = scanning,
                error = error ?: banner,
                onScan = {
                    if (state.scanning.value) return@ScanScreen
                    state.scanning.value = true
                    error = null
                    state.devices.value = emptyList()
                    scope.launch {
                        try {
                            val scanner = BleScanner(ctx)
                            if (!scanner.isReady) {
                                error = "Bluetooth is off — enable it in system settings."
                                return@launch
                            }
                            withTimeoutOrNull(8_000) {
                                scanner.scan().collect { dev ->
                                    state.devices.update { current ->
                                        if (current.any { it.address == dev.address }) current
                                        else current + dev
                                    }
                                }
                            }
                        } catch (_: SecurityException) {
                            error = "Bluetooth permission denied."
                        } catch (e: Exception) {
                            error = "Scan failed: ${e.message}"
                        } finally {
                            state.scanning.value = false
                        }
                    }
                },
                onPick = onPickDevice,
            )
        }
    }
}

@Composable
private fun PermissionGate(onRequestPermissions: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Permissions needed",
            style = MaterialTheme.typography.headlineSmall.copy(
                color = Tokens.Text,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        Text(
            "RRStreamer needs Bluetooth access (and on Android 13+, notification " +
                "access) to scan for and stream from your heart rate band.",
            style = MaterialTheme.typography.bodyMedium.copy(color = Tokens.TextSoft),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onRequestPermissions,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Tokens.Text,
                    contentColor = Tokens.Background,
                ),
            ) { Text("Grant permissions") }
            OutlinedButton(
                onClick = {
                    ctx.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", ctx.packageName, null),
                        )
                    )
                },
                shape = RoundedCornerShape(999.dp),
            ) { Text("App settings") }
        }
    }
}
