package io.github.abcsds.rrstreamer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import kotlin.math.max
import kotlin.math.min

// -----------------------------------------------------------------
// BeatingHeart — tied to the actual measured beat. Each new sample
// triggers one scale "throb"; gives the UI a real biological cadence.
// -----------------------------------------------------------------
@Composable
fun BeatingHeart(
    triggerKey: Any?,
    modifier: Modifier = Modifier,
    color: Color = Tokens.Hr,
) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(triggerKey) {
        // Quick rise → soft retract → settle, ~450 ms total.
        scale.snapTo(1f)
        scale.animateTo(1.18f, tween(110, easing = EaseOutCubic))
        scale.animateTo(0.94f, tween(140, easing = EaseInOutCubic))
        scale.animateTo(1f,    tween(200, easing = EaseInOutCubic))
    }
    Icon(
        imageVector = Icons.Filled.Favorite,
        contentDescription = "heart",
        tint = color,
        modifier = modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value },
    )
}

// -----------------------------------------------------------------
// LivePulse — the small "● STREAMING · LSL MARKERS" indicator.
// -----------------------------------------------------------------
@Composable
fun LivePulse(label: String, modifier: Modifier = Modifier, color: Color = Tokens.Signal) {
    val transition = rememberInfiniteTransition(label = "live-pulse")
    val opacity by transition.animateFloat(
        initialValue = 0.35f,
        targetValue  = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "opacity",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color.copy(alpha = opacity), shape = RoundedCornerShape(4.dp)),
        )
        Text(
            text = label,
            style = MonoTabularLabel.copy(color = color),
        )
    }
}

// -----------------------------------------------------------------
// Sparkline — fast Canvas-drawn line + gradient fill. Auto-scales
// to the current value range with a small padding to avoid the
// data hugging the top/bottom edges.
// -----------------------------------------------------------------
@Composable
fun Sparkline(
    values: List<Int>,
    modifier: Modifier = Modifier,
    color: Color = Tokens.Hr,
    strokeWidth: Float = 2.4f,
    showLastPoint: Boolean = true,
) {
    val gridStroke = Tokens.Rule
    val gridStrokeSoft = Tokens.RuleSoft
    val areaBrush = Brush.verticalGradient(
        listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0f))
    )
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Three subtle horizontal gridlines (top, middle, bottom thirds)
        drawLine(gridStroke,    Offset(0f, h * 0.25f),  Offset(w, h * 0.25f),  1f)
        drawLine(gridStrokeSoft, Offset(0f, h * 0.5f),  Offset(w, h * 0.5f),   1f)
        drawLine(gridStroke,    Offset(0f, h * 0.75f),  Offset(w, h * 0.75f),  1f)

        if (values.size < 2) return@Canvas
        val rawMin = values.min().toFloat()
        val rawMax = values.max().toFloat()
        val span   = max(1f, rawMax - rawMin)
        // Pad the y-range so flat-line periods don't render as a single horizontal stripe
        val padded = max(span * 0.25f, 4f)
        val lo = rawMin - padded * 0.5f
        val hi = rawMax + padded * 0.5f

        val stepX = w / (values.size - 1).toFloat()
        val line  = Path()
        val area  = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val norm = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
            val y = h - norm * h
            if (i == 0) {
                line.moveTo(x, y)
                area.moveTo(x, h)
                area.lineTo(x, y)
            } else {
                line.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(w, h)
        area.close()
        drawPath(area, brush = areaBrush)
        drawPath(line, color = color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))

        if (showLastPoint) {
            val v = values.last()
            val norm = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
            drawCircle(color = color.copy(alpha = 0.30f), radius = 6f, center = Offset(w, h - norm * h))
            drawCircle(color = color,                    radius = 3f, center = Offset(w, h - norm * h))
        }
    }
}

// -----------------------------------------------------------------
// MetaStrip — tabular key/value rows. Mono font for instrument feel.
// -----------------------------------------------------------------
@Composable
fun MetaStrip(
    rows: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.012f), shape = RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        // Two-column layout, splitting the rows into left/right halves
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
            val mid = (rows.size + 1) / 2
            val left = rows.take(mid)
            val right = rows.drop(mid)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { left.forEach { MetaRow(it.first, it.second) } }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { right.forEach { MetaRow(it.first, it.second) } }
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MonoTabularLabel.copy(color = Tokens.TextFaint, fontSize = 10.sp),
        )
        Text(
            text = value,
            style = MonoTabularLabel.copy(color = Tokens.Text, fontSize = 10.5.sp, letterSpacing = 0.4.sp),
        )
    }
}

// -----------------------------------------------------------------
// SectionHeader — small mono caps + horizontal rule.
// -----------------------------------------------------------------
@Composable
fun SectionHeader(label: String, trailing: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MonoTabularLabel.copy(color = Tokens.TextFaint, fontSize = 10.5.sp))
        trailing?.let {
            Text(it, style = MonoTabularLabel.copy(color = Tokens.TextFaint, fontSize = 10.5.sp))
        }
    }
}

// -----------------------------------------------------------------
// BrandMark — bordered heart glyph used in the app header.
// -----------------------------------------------------------------
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(28.dp)
            .background(Color.Transparent, shape = RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawWithCache {
                    val stroke = 1.5.dp.toPx()
                    val r = 8.dp.toPx()
                    onDrawBehind {
                        drawRoundRect(
                            color = Tokens.Hr,
                            style = Stroke(stroke),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                        )
                    }
                },
        )
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = null,
            tint = Tokens.Hr,
            modifier = Modifier.size(14.dp),
        )
    }
}
