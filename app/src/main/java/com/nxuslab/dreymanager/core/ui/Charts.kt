package com.nxuslab.dreymanager.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nxuslab.dreymanager.core.theme.NxusColors
import com.nxuslab.dreymanager.domain.money.Money

/** Barras da dívida restante mês a mês (saída do cartão). */
@Composable
fun DebtChart(totals: List<Long>, firstLabel: String, lastLabel: String, modifier: Modifier = Modifier) {
    if (totals.isEmpty()) return
    val max = totals.max().coerceAtLeast(1)
    Column(modifier.semantics { contentDescription = "Dívida de ${Money.format(totals.first())} até zerar em $lastLabel" }) {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val gap = 4.dp.toPx()
            val barWidth = ((size.width - gap * (totals.size - 1)) / totals.size).coerceAtLeast(2f)
            totals.forEachIndexed { index, value ->
                val height = size.height * value / max
                drawRoundRect(
                    color = if (value == 0L) NxusColors.Positive else NxusColors.Accent,
                    topLeft = Offset(index * (barWidth + gap), size.height - height),
                    size = Size(barWidth, height.coerceAtLeast(2f)),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(firstLabel, style = MaterialTheme.typography.labelMedium, color = NxusColors.TextMuted, modifier = Modifier.weight(1f))
            Text(lastLabel, style = MaterialTheme.typography.labelMedium, color = NxusColors.TextMuted)
        }
    }
}

/**
 * Curva do saldo previsto. Abaixo de zero fica vermelho; [alternative] (simulação "e se") aparece
 * tracejada por cima.
 */
@Composable
fun BalanceChart(values: List<Long>, alternative: List<Long>?, modifier: Modifier = Modifier) {
    if (values.isEmpty()) return
    val all = values + (alternative ?: emptyList()) + 0L
    val max = all.max()
    val min = all.min()
    val range = (max - min).coerceAtLeast(1)
    Canvas(modifier.fillMaxWidth().height(160.dp).semantics { contentDescription = "Saldo previsto para os próximos ${values.size} dias" }) {
        fun y(v: Long) = size.height * (max - v) / range
        fun x(i: Int) = if (values.size == 1) 0f else size.width * i / (values.size - 1)
        drawLine(NxusColors.Outline, Offset(0f, y(0)), Offset(size.width, y(0)), strokeWidth = 1.dp.toPx())

        fun path(series: List<Long>) = Path().apply {
            series.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) }
        }
        drawPath(path(values), NxusColors.AccentLight, style = Stroke(2.5.dp.toPx()))
        alternative?.let { drawPath(path(it), NxusColors.Alert, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))) }
        values.forEachIndexed { i, v -> if (v < 0) drawCircle(NxusColors.Danger, 2.5.dp.toPx(), Offset(x(i), y(v))) }
    }
}
