package com.pocketfx.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketfx.R
import com.pocketfx.domain.HistoryPoint
import com.pocketfx.domain.HistoryStats
import com.pocketfx.ui.components.LocalAppLocale
import com.pocketfx.ui.components.MutedText
import com.pocketfx.ui.components.asRate
import com.pocketfx.ui.components.formatDay
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs

private data class PlotPoint(val x: Float, val y: Float)

@Composable
internal fun RateChart(points: List<HistoryPoint>, stats: HistoryStats) {
    val locale = LocalAppLocale.current
    val palette = MaterialTheme.colorScheme
    val description = stringResource(R.string.chart_description)
    val first = points.first().timestamp
    val last = points.last().timestamp
    var selected by remember(points) { mutableIntStateOf(points.lastIndex) }
    val normalized = remember(points, stats) {
        val span = BigDecimal.valueOf(last - first)
        val range = stats.maximum - stats.minimum
        points.map { point ->
            val x = BigDecimal.valueOf(point.timestamp - first).divide(span, MathContext.DECIMAL64).toFloat()
            val y = if (range.signum() == 0) 0.5f else {
                (stats.maximum - point.rate).divide(range, MathContext.DECIMAL64).toFloat()
            }
            PlotPoint(x, y)
        }
    }

    Canvas(
        Modifier.fillMaxWidth().height(160.dp)
            .semantics { contentDescription = description }
            .pointerInput(normalized) {
                detectTapGestures { touch ->
                    val padding = 8.dp.toPx()
                    val width = (size.width - 2 * padding).coerceAtLeast(1f)
                    val position = (touch.x - padding) / width
                    selected = normalized.indices.minBy { abs(normalized[it].x - position) }
                }
            },
    ) {
        val padding = 8.dp.toPx()
        val width = (size.width - 2 * padding).coerceAtLeast(1f)
        val height = (size.height - 2 * padding).coerceAtLeast(1f)
        val plot = normalized.map { Offset(padding + it.x * width, padding + it.y * height) }
        for (line in 0..4) {
            val y = padding + line * height / 4
            drawLine(palette.outlineVariant, Offset(padding, y), Offset(padding + width, y), 1.dp.toPx())
        }
        val line = Path().apply {
            moveTo(plot.first().x, plot.first().y)
            plot.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(line, palette.primary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        plot.forEach { drawCircle(palette.primary, 2.dp.toPx(), it) }
        val focus = plot[selected]
        drawLine(palette.primary.copy(alpha = 0.45f), Offset(focus.x, padding), Offset(focus.x, padding + height), 1.dp.toPx())
        drawCircle(palette.surface, 6.dp.toPx(), focus)
        drawCircle(palette.primary, 4.dp.toPx(), focus)
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        MutedText(formatDay(first, locale))
        MutedText(formatDay(last, locale))
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { selected-- }, enabled = selected > 0) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.chart_previous))
        }
        val point = points[selected]
        Text(
            stringResource(R.string.observation, formatDay(point.timestamp, locale, includeYear = true), point.rate.asRate(locale)),
            Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
        )
        IconButton(onClick = { selected++ }, enabled = selected < points.lastIndex) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.chart_next))
        }
    }
}

