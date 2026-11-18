package com.pocketfx.ui.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.drawWithCache
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
import com.pocketfx.ui.components.LocalAppLocale
import com.pocketfx.ui.components.MutedText
import com.pocketfx.ui.components.asRate
import com.pocketfx.ui.components.formatDay

@Composable
internal fun RateChart(data: HistoryChartData) {
    val points = data.points
    val stats = data.stats
    val locale = LocalAppLocale.current
    val palette = MaterialTheme.colorScheme
    val description = stringResource(R.string.chart_description)
    val first = points.first().date
    val last = points.last().date
    var selected by remember(data) { mutableIntStateOf(points.lastIndex) }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val flat = stats.maximum.compareTo(stats.minimum) == 0
        Column(
            Modifier.height(160.dp).widthIn(max = 96.dp),
            verticalArrangement = if (flat) Arrangement.Center else Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            MutedText(stats.maximum.asRate(locale))
            if (!flat) MutedText(stats.minimum.asRate(locale))
        }
        Column(Modifier.weight(1f)) {
            Box(
                Modifier.fillMaxWidth().height(160.dp)
                    .semantics { contentDescription = description }
                    .pointerInput(data) {
                        detectTapGestures { touch ->
                            val padding = 8.dp.toPx()
                            val width = (size.width - 2 * padding).coerceAtLeast(1f)
                            selected = data.nearestIndex((touch.x - padding) / width)
                        }
                    }
                    .drawWithCache {
                        val padding = 8.dp.toPx()
                        val width = (size.width - 2 * padding).coerceAtLeast(1f)
                        val height = (size.height - 2 * padding).coerceAtLeast(1f)
                        val x = FloatArray(points.size) { padding + data.x[it] * width }
                        val y = FloatArray(points.size) { padding + data.y[it] * height }
                        val grid = Path()
                        for (index in 0..4) {
                            val lineY = padding + index * height / 4
                            grid.moveTo(padding, lineY)
                            grid.lineTo(padding + width, lineY)
                        }
                        val path = Path().apply {
                            moveTo(x[0], y[0])
                            for (index in 1..x.lastIndex) lineTo(x[index], y[index])
                        }
                        val gridStroke = Stroke(1.dp.toPx())
                        val lineStroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        val markerLine = palette.primary.copy(alpha = 0.45f)
                        val showDots = points.size <= width / 5.dp.toPx()
                        onDrawBehind {
                            drawPath(grid, palette.outlineVariant, style = gridStroke)
                            drawPath(path, palette.primary, style = lineStroke)
                            if (showDots) {
                                for (index in x.indices) {
                                    drawCircle(palette.primary, 2.dp.toPx(), Offset(x[index], y[index]))
                                }
                            }
                            val focus = Offset(x[selected], y[selected])
                            drawLine(markerLine, Offset(focus.x, padding), Offset(focus.x, padding + height), gridStroke.width)
                            drawCircle(palette.surface, 6.dp.toPx(), focus)
                            drawCircle(palette.primary, 4.dp.toPx(), focus)
                        }
                    },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MutedText(formatDay(first, locale))
                MutedText(formatDay(last, locale))
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { selected-- }, enabled = selected > 0) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.chart_previous))
        }
        val point = points[selected]
        Text(
            stringResource(R.string.observation, formatDay(point.date, locale, includeYear = true), point.rate.asRate(locale)),
            Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
        )
        IconButton(onClick = { selected++ }, enabled = selected < points.lastIndex) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.chart_next))
        }
    }
}

