package io.github.abcsds.rrstreamer.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.abcsds.rrstreamer.HeartRateService
import io.github.abcsds.rrstreamer.StreamingState
import io.github.abcsds.rrstreamer.ble.IntervalKind
import kotlinx.coroutines.delay

@Composable
fun StreamingScreen(
    state: StreamingState,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val deviceLabel = when (state) {
        is StreamingState.Streaming  -> state.deviceName
        is StreamingState.Connecting -> state.deviceName
        else -> "device"
    }
    val isConnecting = state is StreamingState.Connecting
    val streaming = state as? StreamingState.Streaming
    // Default the visible label to RR until the first sample tells us otherwise;
    // most bands take this path, and the only mismatch is a brief sub-second
    // window before the first PPI frame on a Verity Sense.
    val kind: IntervalKind = streaming?.intervalKind ?: IntervalKind.RR

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ── Header ──────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BrandMark()
            Text(
                text = "RRSTREAMER",
                style = MonoTabularLabel.copy(color = Tokens.Text, fontSize = 13.sp, letterSpacing = 0.7.sp),
                fontWeight = FontWeight.Medium,
            )
            Text("·", style = MonoTabularLabel.copy(color = Tokens.TextFaint))
            Text(
                text = deviceLabel.uppercase(),
                style = MonoTabularLabel.copy(color = Tokens.TextSoft, fontSize = 11.sp),
                maxLines = 1,
            )
        }

        // ── Pulse row ───────────────────────────────────────────
        LivePulse(
            label = if (isConnecting) "CONNECTING · LSL MARKERS" else "STREAMING · LSL MARKERS",
            color = if (isConnecting) Tokens.HrSoft else Tokens.Signal,
        )

        // ── Hero HR card ────────────────────────────────────────
        HeroCard(state = streaming, isConnecting = isConnecting)

        // ── Interval (RR or PP) + RMSSD row ─────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatCard(
                label = kind.label,
                labelColor = Tokens.Rr,
                value = streaming?.lastInterval?.toString() ?: "—",
                unit  = "ms · last beat",
                glow  = Tokens.RrGlow,
                modifier = Modifier.weight(1f),
            )
            // RMSSD is only "true" when computed from RR. With PP we still
            // compute it (same algorithm) but flag it as approximate via the
            // ≈ prefix and a clarifying unit string.
            val approx = kind == IntervalKind.PP
            StatCard(
                label = if (approx) "RMSSD ≈" else "RMSSD",
                labelColor = Tokens.TextSoft,
                value = streaming?.rmssdMs?.toString() ?: "—",
                unit  = if (approx)
                    "ms · approx · last ${HeartRateService.RMSSD_WINDOW} beats"
                else
                    "ms · last ${HeartRateService.RMSSD_WINDOW} beats",
                modifier = Modifier.weight(1f),
            )
        }

        // ── Metadata strip ──────────────────────────────────────
        val uptimeText = remember { mutableStateOf("00:00") }
        LaunchedEffect(streaming?.startedAtMs) {
            val started = streaming?.startedAtMs ?: 0L
            if (started == 0L) {
                uptimeText.value = "00:00"
                return@LaunchedEffect
            }
            while (true) {
                val secs = ((System.currentTimeMillis() - started) / 1000).toInt()
                uptimeText.value = "%02d:%02d".format(secs / 60, secs % 60)
                delay(1000)
            }
        }
        MetaStrip(
            rows = listOf(
                "STREAM"  to "${kind.lslTag} ${truncate(deviceLabel, 14)}",
                "UPTIME"  to uptimeText.value,
                "SAMPLES" to (streaming?.samples?.toString() ?: "0"),
                "BUFFER"  to "${streaming?.intervalHistory?.size ?: 0} / ${HeartRateService.INTERVAL_HISTORY_CAP}",
            ),
        )

        // ── Interval graph (last 100 beats) ─────────────────────
        IntervalHistoryCard(
            kind = kind,
            intervalsMs = streaming?.intervalHistory ?: emptyList(),
        )

        Spacer(Modifier.height(20.dp))

        // ── Stop button ─────────────────────────────────────────
        OutlinedButton(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Tokens.HrSoft,
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, Tokens.Rule),
        ) {
            Icon(
                Icons.Outlined.Stop,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(10.dp))
            Text(
                "Stop streaming",
                style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 0.4.sp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HeroCard(
    state: StreamingState.Streaming?,
    isConnecting: Boolean,
) {
    val bpm = state?.lastHr
    val triggerKey = state?.samples ?: 0
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.verticalGradient(listOf(Tokens.Surface, Tokens.Surface.copy(alpha = 0.4f)))
            )
            .background(
                Brush.radialGradient(
                    colors = listOf(Tokens.HrGlow, Color.Transparent),
                    radius = 600f,
                    center = androidx.compose.ui.geometry.Offset(900f, 100f),
                )
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    "HR",
                    style = MonoTabularLabel.copy(color = Tokens.Hr, fontSize = 11.sp),
                    fontWeight = FontWeight.Medium,
                )
                val avgText = state?.hrHistory
                    ?.takeLast(15)?.takeIf { it.isNotEmpty() }
                    ?.let { "AVG ${it.average().toInt()}" } ?: ""
                Text(avgText, style = MonoTabularLabel.copy(color = Tokens.TextFaint, fontSize = 10.sp))
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                BeatingHeart(
                    triggerKey = triggerKey,
                    color = Tokens.Hr,
                    modifier = Modifier.size(28.dp),
                )
                if (isConnecting && bpm == null) {
                    CircularProgressIndicator(
                        color = Tokens.HrSoft,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "Connecting…",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            color = Tokens.TextSoft,
                        ),
                    )
                } else {
                    Text(
                        text = bpm?.toString() ?: "—",
                        fontFamily = FiraCode,
                        fontWeight = FontWeight.Medium,
                        fontSize = 100.sp,
                        color = Tokens.Text,
                        letterSpacing = (-3).sp,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Text(
                        text = "BPM",
                        style = MonoTabularLabel.copy(color = Tokens.TextSoft, fontSize = 13.sp, letterSpacing = 1.6.sp),
                        modifier = Modifier.alignByBaseline().padding(bottom = 14.dp),
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Sparkline(
                values = state?.hrHistory ?: emptyList(),
                color = Tokens.Hr,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("−${HeartRateService.HR_HISTORY_CAP}s",
                    style = MonoTabularLabel.copy(color = Tokens.TextFaint, fontSize = 9.5.sp))
                Text("now",
                    style = MonoTabularLabel.copy(color = Tokens.TextFaint, fontSize = 9.5.sp))
            }
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    labelColor: Color,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    glow: Color = Color.Transparent,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Tokens.Surface)
            .background(
                Brush.radialGradient(
                    colors = listOf(glow, Color.Transparent),
                    radius = 320f,
                    center = androidx.compose.ui.geometry.Offset(900f, 600f),
                )
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MonoTabularLabel.copy(color = labelColor, fontSize = 10.sp))
            Text(
                text = value,
                fontFamily = FiraCode,
                fontWeight = FontWeight.Medium,
                fontSize = 30.sp,
                color = Tokens.Text,
                letterSpacing = (-0.4).sp,
            )
            Text(unit, style = MaterialTheme.typography.bodySmall.copy(
                color = Tokens.TextFaint,
                fontSize = 11.sp,
            ))
        }
    }
}

private fun truncate(s: String, max: Int): String =
    if (s.length <= max) s else s.take(max - 1) + "…"

/**
 * Rolling card for the last [HeartRateService.INTERVAL_HISTORY_CAP] beat-to-beat
 * intervals. Shows MIN / MEAN / MAX in milliseconds + a sparkline.
 *
 * Title swaps "RR" ↔ "PP" based on [kind]; we add a small "(approx)" suffix
 * when the source is PP so a viewer of a screenshot doesn't mistake it for
 * ECG-derived RR data.
 */
@Composable
private fun IntervalHistoryCard(
    kind: IntervalKind,
    intervalsMs: List<Int>,
) {
    val cap = HeartRateService.INTERVAL_HISTORY_CAP
    val min = intervalsMs.minOrNull()
    val max = intervalsMs.maxOrNull()
    val avg = if (intervalsMs.isNotEmpty()) intervalsMs.average().toInt() else null

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Tokens.Surface)
            .background(
                Brush.radialGradient(
                    colors = listOf(Tokens.RrGlow, Color.Transparent),
                    radius = 700f,
                    center = androidx.compose.ui.geometry.Offset(900f, 100f),
                )
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        kind.label,
                        style = MonoTabularLabel.copy(color = Tokens.Rr, fontSize = 11.sp),
                        fontWeight = FontWeight.Medium,
                    )
                    val tail = if (kind == IntervalKind.PP)
                        "· LAST $cap PEAKS · APPROX"
                    else
                        "· LAST $cap BEATS"
                    Text(
                        tail,
                        style = MonoTabularLabel.copy(color = Tokens.TextFaint, fontSize = 10.sp),
                    )
                }
                Text(
                    "${intervalsMs.size} / $cap",
                    style = MonoTabularLabel.copy(color = Tokens.TextFaint, fontSize = 10.sp),
                )
            }

            Sparkline(
                values = intervalsMs,
                color = Tokens.Rr,
                strokeWidth = 2.0f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IntervalStat(label = "MIN",  value = min?.let { "${it}ms" } ?: "—")
                IntervalStat(label = "MEAN", value = avg?.let { "${it}ms" } ?: "—",
                    valueColor = Tokens.Text)
                IntervalStat(label = "MAX",  value = max?.let { "${it}ms" } ?: "—")
            }
        }
    }
}

@Composable
private fun IntervalStat(label: String, value: String, valueColor: Color = Tokens.RrSoft) {
    Column {
        Text(label, style = MonoTabularLabel.copy(color = Tokens.TextFaint, fontSize = 9.5.sp))
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            fontFamily = FiraCode,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            color = valueColor,
            letterSpacing = (-0.2).sp,
        )
    }
}
