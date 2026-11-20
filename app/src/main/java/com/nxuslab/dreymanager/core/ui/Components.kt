package com.nxuslab.dreymanager.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nxuslab.dreymanager.core.theme.NxusColors
import com.nxuslab.dreymanager.core.theme.NxusTheme
import com.nxuslab.dreymanager.domain.money.Money
import com.nxuslab.dreymanager.domain.money.MoneyParser
import com.nxuslab.dreymanager.domain.money.MoneyResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

fun LocalDate.br(): String = format(dateFormat)

/** Número grande do Foco total com anel de progresso do quanto já foi usado. */
@Composable
fun BigRing(remainingCents: Long, limitCents: Long, caption: String, footer: String, modifier: Modifier = Modifier, size: Dp = 240.dp) {
    val used = if (limitCents <= 0) 1f else ((limitCents - remainingCents).toFloat() / limitCents).coerceIn(0f, 1f)
    val negative = remainingCents < 0
    val description = "$caption: ${Money.format(remainingCents)} de ${Money.format(limitCents)}"
    Box(modifier.size(size).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 16.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(NxusColors.SurfaceHigh, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(
                color = if (negative) NxusColors.Danger else NxusColors.Accent,
                startAngle = -90f,
                sweepAngle = 360f * (1f - used),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(caption, style = MaterialTheme.typography.labelMedium, color = NxusColors.TextMuted)
            Text(
                Money.formatShort(remainingCents),
                style = MaterialTheme.typography.displayLarge,
                color = if (negative) NxusColors.Danger else NxusColors.AccentLight,
            )
            Text("de ${Money.formatShort(limitCents)}", style = MaterialTheme.typography.bodyMedium, color = NxusColors.TextMuted)
            Spacer(Modifier.height(4.dp))
            Text(footer, style = MaterialTheme.typography.labelMedium, color = NxusColors.Text, textAlign = TextAlign.Center)
        }
    }
}

/** Barra de um envelope: âmbar a partir de 80%, vermelha quando estoura. */
@Composable
fun EnvelopeBar(emoji: String, name: String, spentCents: Long, limitCents: Long, modifier: Modifier = Modifier) {
    val ratio = if (limitCents <= 0) 0f else spentCents.toFloat() / limitCents
    val color = when {
        ratio > 1f -> NxusColors.Danger
        ratio >= 0.8f -> NxusColors.Alert
        else -> NxusColors.AccentLight
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$emoji $name", style = MaterialTheme.typography.labelLarge)
            Text("${Money.formatShort(spentCents)} de ${Money.formatShort(limitCents)}", style = MaterialTheme.typography.labelMedium, color = color)
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { ratio.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = color,
            trackColor = NxusColors.SurfaceHigh,
            drawStopIndicator = {},
        )
    }
}

/**
 * Campo de dinheiro: valida com o [MoneyParser] e mostra o erro no próprio campo.
 * [onValid] recebe os centavos quando o texto é válido, ou null quando não é.
 */
@Composable
fun MoneyField(text: String, onTextChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, onValid: (Long?) -> Unit = {}) {
    val result = if (text.isBlank()) null else MoneyParser.parse(text)
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            onTextChange(new)
            onValid((MoneyParser.parse(new) as? MoneyResult.Valid)?.cents)
        },
        label = { Text(label) },
        prefix = { Text("R$ ") },
        isError = result is MoneyResult.Invalid,
        supportingText = (result as? MoneyResult.Invalid)?.let { invalid -> { Text(invalid.message) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Linha compacta usada em listas: título, subtítulo e valor à direita. */
@Composable
fun CompactRow(title: String, subtitle: String?, trailing: String?, modifier: Modifier = Modifier, trailingColor: Color = NxusColors.Text, onClick: (() -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = NxusColors.TextMuted) }
        }
        trailing?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = trailingColor, fontWeight = FontWeight.SemiBold) }
    }
}

enum class AlertKind { INFO, WARNING, DANGER }

@Composable
fun AlertCard(title: String, message: String, kind: AlertKind, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    val color = when (kind) {
        AlertKind.INFO -> NxusColors.AccentLight
        AlertKind.WARNING -> NxusColors.Alert
        AlertKind.DANGER -> NxusColors.Danger
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(NxusColors.Surface, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Box(Modifier.width(4.dp).height(40.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = color)
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) { Text(actionLabel) }
            }
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = NxusColors.TextMuted, modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

/** Seletor de data sem digitação livre: só datas válidas chegam à tela. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerField(label: String, date: LocalDate?, onDateChange: (LocalDate?) -> Unit, modifier: Modifier = Modifier, allowClear: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier.fillMaxWidth()) {
        Text(date?.let { "$label: ${it.br()}" } ?: label)
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onDateChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = {
                Row {
                    if (allowClear) TextButton(onClick = { onDateChange(null); open = false }) { Text("Sem data") }
                    TextButton(onClick = { open = false }) { Text("Cancelar") }
                }
            },
        ) { DatePicker(state) }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF140A0D)
@Composable
private fun ComponentsPreview() {
    NxusTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BigRing(18_640, 40_000, "livre esta semana", "4 dias · ~R$ 46/dia")
            EnvelopeBar("🍔", "Delivery", 9_800, 12_000)
            EnvelopeBar("🎮", "Lazer", 16_000, 15_000)
            AlertCard("Vai faltar dinheiro", "Em 12/jan faltam R$ 140 para a fatura do Itaú.", AlertKind.WARNING, actionLabel = "Ver previsão", onAction = {})
            CompactRow("Internet", "amanhã", Money.format(9_990))
            MoneyField("1.234,5", {}, "Valor")
            MoneyField("1,234", {}, "Valor inválido")
        }
    }
}
