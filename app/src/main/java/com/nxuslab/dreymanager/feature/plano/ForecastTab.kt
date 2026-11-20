package com.nxuslab.dreymanager.feature.plano

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nxuslab.dreymanager.core.theme.NxusColors
import com.nxuslab.dreymanager.core.ui.AlertCard
import com.nxuslab.dreymanager.core.ui.AlertKind
import com.nxuslab.dreymanager.core.ui.BalanceChart
import com.nxuslab.dreymanager.core.ui.CompactRow
import com.nxuslab.dreymanager.core.ui.DatePickerField
import com.nxuslab.dreymanager.core.ui.MoneyField
import com.nxuslab.dreymanager.core.ui.SectionHeader
import com.nxuslab.dreymanager.core.ui.br
import com.nxuslab.dreymanager.data.repo.FinanceSnapshot
import com.nxuslab.dreymanager.domain.forecast.CashEvent
import com.nxuslab.dreymanager.domain.money.Money
import com.nxuslab.dreymanager.feature.common.forecast
import java.time.LocalDate

/** Previsão "vai faltar?" com simulação "e se" que nunca é gravada (especificação §6.7). */
@Composable
fun ForecastTab(data: FinanceSnapshot) {
    val today = LocalDate.now()
    val whatIf = remember { mutableStateListOf<CashEvent>() }
    val base = data.forecast(today)
    val simulated = if (whatIf.isEmpty()) null else data.forecast(today, whatIf.toList())
    val shown = simulated ?: base
    val lowest = shown.days.minByOrNull { it.balanceCents }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Próximos 90 dias", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (data.accounts.isEmpty()) "Cadastre o saldo da sua conta em Mais para a previsão partir do valor real." else "Saldo hoje: ${Money.format(data.openingBalanceCents)}",
                color = NxusColors.TextMuted,
            )
        }
        item { BalanceChart(base.days.map { it.balanceCents }, simulated?.days?.map { it.balanceCents }) }
        lowest?.let { item { Text("Menor saldo: ${Money.format(it.balanceCents)} em ${it.date.br()}", color = if (it.balanceCents < 0) NxusColors.Danger else NxusColors.TextMuted) } }

        if (shown.shortfalls.isEmpty()) {
            item { AlertCard("Não falta dinheiro", "Com o que está cadastrado, o saldo fica positivo nos próximos 90 dias.", AlertKind.INFO) }
        } else {
            item { SectionHeader(if (simulated != null) "Faltas na simulação" else "Vai faltar") }
            items(shown.shortfalls, key = { "${it.date}-${it.reason}" }) { s ->
                CompactRow(s.reason, s.date.br(), "−${Money.format(s.cents)}", trailingColor = NxusColors.Danger)
            }
        }

        item { SectionHeader("E se…?", actionLabel = if (whatIf.isEmpty()) null else "Limpar", onAction = { whatIf.clear() }) }
        items(whatIf.toList(), key = { "${it.date}-${it.reason}-${it.amountCents}" }) { e ->
            CompactRow(e.reason.removePrefix("Simulação: "), e.date.br(), Money.format(e.amountCents), trailingColor = if (e.amountCents < 0) NxusColors.Danger else NxusColors.Positive, onClick = { whatIf.remove(e) })
        }
        item { WhatIfForm(today) { whatIf += it } }
        item { Text("Simulações não são salvas. Toque numa delas para remover.", style = MaterialTheme.typography.labelMedium, color = NxusColors.TextMuted) }
    }
}

@Composable
private fun WhatIfForm(today: LocalDate, onAdd: (CashEvent) -> Unit) {
    var isExpense by remember { mutableStateOf(true) }
    var description by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf<Long?>(null) }
    var date by remember { mutableStateOf<LocalDate?>(today) }
    Column(Modifier.fillMaxWidth().background(NxusColors.Surface, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(true to "Gasto", false to "Entrada").forEachIndexed { i, (value, label) ->
                SegmentedButton(selected = isExpense == value, onClick = { isExpense = value }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
            }
        }
        OutlinedTextField(description, { description = it }, label = { Text("O quê (ex.: TV nova)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        MoneyField(text, { text = it }, "Valor", onValid = { amount = it })
        DatePickerField("Data", date, { date = it })
        OutlinedButton(
            onClick = {
                val cents = amount ?: return@OutlinedButton
                onAdd(CashEvent(date ?: today, if (isExpense) -cents else cents, "Simulação: ${description.ifBlank { if (isExpense) "gasto" else "entrada" }}"))
                description = ""; text = ""; amount = null
            },
            enabled = amount != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Simular") }
    }
}
