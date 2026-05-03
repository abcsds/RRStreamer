package io.github.abcsds.rrstreamer.ui

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ScanScreen(
    devices: List<BluetoothDevice>,
    scanning: Boolean,
    error: String?,
    logEnabled: Boolean,
    onToggleLog: (Boolean) -> Unit,
    onScan: () -> Unit,
    onPick: (BluetoothDevice, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Spacer(Modifier.height(12.dp))

        // ── Header ──────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BrandMark()
            Text(
                "RRSTREAMER",
                style = MonoTabularLabel.copy(color = Tokens.Text, fontSize = 13.sp, letterSpacing = 0.7.sp),
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.height(36.dp))

        // ── Title block ─────────────────────────────────────────
        Text(
            buildString { append("Pair a heart rate band.") },
            style = MaterialTheme.typography.headlineMedium.copy(
                color = Tokens.Text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 32.sp,
                letterSpacing = (-0.5).sp,
                lineHeight = 36.sp,
            ),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Bands advertising the standard BLE Heart Rate service appear here. Tap to connect and start the LSL stream.",
            style = MaterialTheme.typography.bodyMedium.copy(
                color = Tokens.TextSoft,
                lineHeight = 20.sp,
            ),
        )
        if (error != null) {
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Tokens.Hr.copy(alpha = 0.10f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(error, color = Tokens.HrSoft,
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Local log toggle ────────────────────────────────────
        LogToggleCard(enabled = logEnabled, onToggle = onToggleLog)

        Spacer(Modifier.height(20.dp))

        // ── Scan controls ───────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (scanning) {
                LivePulse(label = "SCANNING…", color = Tokens.Signal)
            } else {
                Text(
                    "TAP TO SCAN",
                    style = MonoTabularLabel.copy(color = Tokens.TextFaint, fontSize = 11.sp),
                )
            }
            Button(
                onClick = onScan,
                enabled = !scanning,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Tokens.Text,
                    contentColor = Tokens.Background,
                    disabledContainerColor = Tokens.SurfaceElev,
                    disabledContentColor = Tokens.TextSoft,
                ),
            ) {
                Text(
                    if (scanning) "Scanning…" else (if (devices.isEmpty()) "Scan" else "Rescan"),
                    style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 0.4.sp),
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        SectionHeader(
            label = if (devices.isEmpty()) "DISCOVERED · 0" else "DISCOVERED · ${devices.size}",
            trailing = "BLE 0X180D",
        )

        Spacer(Modifier.height(10.dp))

        if (devices.isEmpty() && !scanning) {
            EmptyHint()
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(devices, key = { it.address }) { dev ->
                    val display = try { dev.name } catch (_: SecurityException) { dev.address }
                        ?: dev.address
                    DeviceCard(name = display, address = dev.address) {
                        onPick(dev, display)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(name: String, address: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Tokens.Surface)
            .border(1.dp, Tokens.Rule, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // Heart icon tile
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Tokens.Hr.copy(alpha = 0.08f))
                    .border(1.dp, Tokens.Hr.copy(alpha = 0.25f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = Tokens.Hr,
                    modifier = Modifier.size(20.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = Tokens.Text, fontWeight = FontWeight.SemiBold,
                    ),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    address,
                    style = MonoTabularLabel.copy(
                        color = Tokens.TextFaint,
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp,
                    ),
                )
            }

            // Tap-to-connect affordance + button surface
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Tokens.SurfaceElev)
                    .border(1.dp, Tokens.Rule, RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    "CONNECT",
                    style = MonoTabularLabel.copy(color = Tokens.Signal, fontSize = 10.sp),
                )
            }
        }
        // Make whole card tap-to-connect
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
        ) {
            // Use a child Button to absorb taps without visual chrome
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier.fillMaxSize(),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = Color.Transparent,
                ),
                border = androidx.compose.foundation.BorderStroke(0.dp, Color.Transparent),
                shape = RoundedCornerShape(18.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) { /* invisible — just absorbs the tap */ }
        }
    }
}

@Composable
private fun LogToggleCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Tokens.Surface)
            .border(1.dp, Tokens.Rule, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Save intervals to file",
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = Tokens.Text,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "Fallback log if the LSL stream isn't recorded over the network. " +
                        "One value per line, in ms.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Tokens.TextSoft,
                        lineHeight = 16.sp,
                    ),
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Tokens.Background,
                    checkedTrackColor = Tokens.Signal,
                    checkedBorderColor = Tokens.Signal,
                    uncheckedThumbColor = Tokens.TextSoft,
                    uncheckedTrackColor = Tokens.SurfaceElev,
                    uncheckedBorderColor = Tokens.Rule,
                ),
            )
        }
    }
}

@Composable
private fun EmptyHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.012f))
            .padding(horizontal = 18.dp, vertical = 22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "No devices yet.",
                style = MaterialTheme.typography.titleMedium.copy(
                    color = Tokens.Text,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Text(
                "Wear the band so its electrodes make contact, then tap Scan. " +
                    "Polar H10 starts advertising as soon as you put it on.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = Tokens.TextSoft,
                    lineHeight = 18.sp,
                ),
            )
        }
    }
}
