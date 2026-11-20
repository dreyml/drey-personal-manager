package com.nxuslab.dreymanager.feature.gastos

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nxuslab.dreymanager.NxusApp
import com.nxuslab.dreymanager.core.theme.NxusColors
import com.nxuslab.dreymanager.core.ui.AlertCard
import com.nxuslab.dreymanager.core.ui.AlertKind
import com.nxuslab.dreymanager.core.ui.CompactRow
import com.nxuslab.dreymanager.core.ui.EnvelopeBar
import com.nxuslab.dreymanager.core.ui.MoneyField
import com.nxuslab.dreymanager.core.ui.SectionHeader
import com.nxuslab.dreymanager.core.ui.br
import com.nxuslab.dreymanager.data.repo.Category
import com.nxuslab.dreymanager.data.repo.FinanceSnapshot
import com.nxuslab.dreymanager.domain.budget.WeekBudget
import com.nxuslab.dreymanager.domain.model.Expense
import com.nxuslab.dreymanager.domain.model.PaymentMethod
import com.nxuslab.dreymanager.domain.money.Money
import com.nxuslab.dreymanager.feature.common.envelopeStatus
import com.nxuslab.dreymanager.feature.common.week
import com.nxuslab.dreymanager.feature.common.weekGroups
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

class WeekViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as NxusApp).repository
    val snapshot: StateFlow<FinanceSnapshot?> = repository.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    suspend fun save(expense: Expense) = repository.saveExpense(expense)
    suspend fun delete(id: String) = repository.deleteExpense(id)
}

private val pt = Locale.forLanguageTag("pt-BR")

@Composable
fun WeekScreen(onMessage: (String) -> Unit, viewModel: WeekViewModel = viewModel()) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val data = snapshot ?: return
    val today = LocalDate.now()
    var offsetWeeks by rememberSaveable { mutableStateOf(0L) }
    val day = today.plusWeeks(offsetWeeks)
    val (start, groups) = data.weekGroups(day)
    val week = data.week(day) as? WeekBudget.Ready
    val categories = data.categories.associateBy { it.id }
    var editing by remember { mutableStateOf<Expense?>(null) }
    val scope = rememberCoroutineScope()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { offsetWeeks-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Semana anterior") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (offsetWeeks == 0L) "Esta semana" else "Semana de ${start.br()}", style = MaterialTheme.typography.titleLarge)
                    Text("${start.br()} a ${start.plusDays(6).br()}", color = NxusColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = { offsetWeeks++ }, enabled = offsetWeeks < 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Próxima semana") }
            }
        }
        week?.let { w ->
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Gasto ${Money.format(w.spentCents)}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (w.remainingCents >= 0) "livre ${Money.format(w.remainingCents)}" else "acima ${Money.format(-w.remainingCents)}",
                        color = if (w.remainingCents >= 0) NxusColors.AccentLight else NxusColors.Danger,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            if (w.carriedOverExcessCents > 0) {
                item { AlertCard("Semana anterior estourou", "${Money.format(w.carriedOverExcessCents)} foram descontados desta semana.", AlertKind.WARNING) }
            }
        }
        if (offsetWeeks == 0L) {
            val envelopes = data.envelopeStatus(today)
            if (envelopes.isNotEmpty()) {
                item { SectionHeader("Envelopes do mês") }
                items(envelopes, key = { "env-${it.categoryId}" }) { EnvelopeBar(it.emoji, it.name, it.spentCents, it.limitCents) }
            }
        }
        if (groups.isEmpty()) {
            item { Text("Nenhum gasto nesta semana.", color = NxusColors.TextMuted, modifier = Modifier.fillMaxWidth()) }
        }
        groups.forEach { group ->
            item(key = "day-${group.date}") { SectionHeader("${dayLabel(group.date, today)} · ${Money.format(group.totalCents)}") }
            items(group.expenses, key = { it.id }) { expense ->
                val category = categories[expense.categoryId]
                CompactRow(
                    title = "${category?.emoji ?: "•"} ${category?.name ?: expense.categoryId}",
                    subtitle = methodLabel(expense),
                    trailing = Money.format(expense.amountCents),
                    trailingColor = if (expense.method == PaymentMethod.CARTAO) NxusColors.Alert else NxusColors.Text,
                    onClick = { editing = expense },
                )
            }
        }
    }

    editing?.let { expense ->
        EditExpenseDialog(
            expense = expense,
            categories = data.categories,
            onDismiss = { editing = null },
            onSave = { updated -> editing = null; scope.launch { viewModel.save(updated).fold({ onMessage("Gasto atualizado.") }, { onMessage(it.message ?: "Erro ao salvar.") }) } },
            onDelete = { editing = null; scope.launch { viewModel.delete(expense.id).fold({ onMessage("Gasto excluído.") }, { onMessage(it.message ?: "Erro ao excluir.") }) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditExpenseDialog(expense: Expense, categories: List<Category>, onDismiss: () -> Unit, onSave: (Expense) -> Unit, onDelete: () -> Unit) {
    var text by remember { mutableStateOf("${expense.amountCents / 100},${(expense.amountCents % 100).toString().padStart(2, '0')}") }
    var amount by remember { mutableStateOf<Long?>(expense.amountCents) }
    var categoryId by remember { mutableStateOf(expense.categoryId) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = NxusColors.Surface,
            title = { Text("Excluir este gasto?") },
            text = { Text("${Money.format(expense.amountCents)} em ${expense.date.br()}. Não dá para desfazer.") },
            confirmButton = { Button(onClick = { confirmDelete = false }) { Text("Manter") } },
            dismissButton = { TextButton(onClick = onDelete) { Text("Excluir", color = NxusColors.Danger) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NxusColors.Surface,
        title = { Text("Gasto de ${expense.date.br()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyField(text, { text = it }, "Valor", onValid = { amount = it })
                // Quebra linha: todas as categorias à vista, sem rolar de lado dentro do diálogo.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { c ->
                        FilterChip(selected = categoryId == c.id, onClick = { categoryId = c.id }, label = { Text("${c.emoji} ${c.name}") })
                    }
                }
                Text(methodLabel(expense), color = NxusColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { Button(onClick = { onSave(expense.copy(amountCents = amount!!, categoryId = categoryId)) }, enabled = amount != null) { Text("Salvar") } },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmDelete = true }) { Text("Excluir", color = NxusColors.Danger) }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}

private fun methodLabel(expense: Expense): String = when (expense.method) {
    PaymentMethod.PIX -> "Pix"
    PaymentMethod.DEBITO -> "Débito"
    PaymentMethod.DINHEIRO -> "Dinheiro"
    PaymentMethod.CARTAO -> "Cartão · fatura de ${expense.reservedForInvoice?.let { "${it.month.getDisplayName(TextStyle.SHORT, pt).trimEnd('.')}/${it.year}" } ?: "—"}"
}

private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Hoje"
    today.minusDays(1) -> "Ontem"
    else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, pt).replaceFirstChar { it.uppercase() } + " " + date.br().take(5)
}
