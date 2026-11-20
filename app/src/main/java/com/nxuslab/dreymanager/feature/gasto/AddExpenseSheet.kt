package com.nxuslab.dreymanager.feature.gasto

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nxuslab.dreymanager.NxusApp
import com.nxuslab.dreymanager.core.theme.NxusColors
import com.nxuslab.dreymanager.data.repo.FinanceSnapshot
import com.nxuslab.dreymanager.domain.budget.BudgetCalculator
import com.nxuslab.dreymanager.domain.budget.CardNotice
import com.nxuslab.dreymanager.domain.budget.GuardResult
import com.nxuslab.dreymanager.domain.model.CardStatus
import com.nxuslab.dreymanager.domain.model.Expense
import com.nxuslab.dreymanager.domain.model.PaymentMethod
import com.nxuslab.dreymanager.domain.money.Money
import com.nxuslab.dreymanager.domain.payoff.PayoffPlanner
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class AddExpenseViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as NxusApp).repository
    val snapshot: StateFlow<FinanceSnapshot?> = repository.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun saveExpense(expense: Expense): Result<Unit> = repository.saveExpense(expense)

    fun saveIncome(amountCents: Long, percentToDebt: Int, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch { onResult(repository.saveIncome(AddExpenseLogic.extraIncome(LocalDate.now(), amountCents, percentToDebt))) }
    }
}

private val methods = listOf(PaymentMethod.PIX to "Pix", PaymentMethod.DEBITO to "Débito", PaymentMethod.DINHEIRO to "Dinheiro", PaymentMethod.CARTAO to "Cartão")

/**
 * Lançamento em 2 toques: valor no teclado e categoria. Antes de salvar, o alerta firme decide se
 * salva direto, avisa ou pede confirmação (especificação §6.5). [onDone] recebe a mensagem para a
 * tela mostrar, ou null se o usuário cancelou.
 */
@Composable
fun AddExpenseSheet(onDone: (String?) -> Unit, viewModel: AddExpenseViewModel = viewModel()) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val data = snapshot ?: return
    val today = LocalDate.now()
    val scope = rememberCoroutineScope()

    var income by remember { mutableStateOf(false) }
    var digits by remember { mutableStateOf("") }
    val categories = remember(data.categories, data.expenses) { AddExpenseLogic.categoryOrder(data, today) }
    // Sem pré-seleção: escolher a categoria é o segundo toque, e evita classificar gasto errado sem perceber.
    var categoryId by remember { mutableStateOf<String?>(null) }
    val selectedCategory = categoryId
    var method by remember { mutableStateOf(PaymentMethod.PIX) }
    val cards = data.cards.filter { it.status == CardStatus.ATIVO }
    var cardId by remember { mutableStateOf(cards.firstOrNull()?.id) }
    var pending by remember { mutableStateOf<Pair<GuardResult, Expense>?>(null) }
    var saving by remember { mutableStateOf(false) }
    val amount = Keypad.cents(digits)

    fun persist(expense: Expense, message: String) {
        saving = true
        scope.launch {
            viewModel.saveExpense(expense).onSuccess { onDone(message) }.onFailure { onDone(it.message) }
            saving = false
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false to "Gasto", true to "Entrada extra").forEachIndexed { index, (value, label) ->
                SegmentedButton(selected = income == value, onClick = { income = value }, shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(label) }
            }
        }

        Text(
            Money.format(amount),
            style = MaterialTheme.typography.displayLarge,
            color = if (amount == 0L) NxusColors.TextMuted else NxusColors.AccentLight,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        if (income) {
            val percent = data.settings.budget.extraSplitToDebtPercent
            val (toDebt, toWeek) = BudgetCalculator.splitExtra(amount, percent)
            Text(
                "$percent% vai para a quitação (${Money.format(toDebt)}) e ${Money.format(toWeek)} entra no mês. Dá para mudar o percentual em Mais › Ajustes.",
                color = NxusColors.TextMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(categories, key = { it.id }) { category ->
                    FilterChip(
                        selected = selectedCategory == category.id,
                        onClick = { categoryId = category.id },
                        label = { Text("${category.emoji} ${category.name}") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NxusColors.SurfaceHigh, selectedLabelColor = NxusColors.AccentLight),
                    )
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                methods.forEachIndexed { index, (value, label) ->
                    SegmentedButton(selected = method == value, onClick = { method = value }, shape = SegmentedButtonDefaults.itemShape(index, methods.size)) {
                        Text(label, maxLines = 1)
                    }
                }
            }
            if (method == PaymentMethod.CARTAO) {
                if (cards.isEmpty()) {
                    Text("Nenhum cartão cadastrado. Cadastre em Mais › Cartões.", color = NxusColors.Alert)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(cards, key = { it.id }) { card ->
                            FilterChip(selected = cardId == card.id, onClick = { cardId = card.id }, label = { Text(card.name) })
                        }
                    }
                }
            }
            if (amount > 0 && selectedCategory != null) {
                val preview = AddExpenseLogic.review(data, today, amount, selectedCategory, method, cardId)
                preview.remainingAfterCents?.let {
                    Text(
                        "Depois: ${if (it >= 0) "restam ${Money.format(it)}" else "${Money.format(-it)} acima do limite"} na semana",
                        color = if (it >= 0) NxusColors.TextMuted else NxusColors.Danger,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        NumberPad { key -> digits = Keypad.press(digits, key) }

        val canSave = amount > 0 && !saving && (income || (selectedCategory != null && (method != PaymentMethod.CARTAO || cardId != null)))
        Button(
            onClick = {
                if (income) {
                    saving = true
                    viewModel.saveIncome(amount, data.settings.budget.extraSplitToDebtPercent) { result ->
                        saving = false
                        onDone(result.fold({ "Entrada de ${Money.format(amount)} registrada." }, { it.message }))
                    }
                } else {
                    val review = AddExpenseLogic.review(data, today, amount, selectedCategory!!, method, cardId)
                    when (val guard = review.guard) {
                        null, is GuardResult.Ok -> if (guard?.card != null) pending = guard to review.expense else persist(review.expense, "Gasto de ${Money.format(amount)} salvo.")
                        is GuardResult.Warning -> if (guard.card != null) pending = guard to review.expense else persist(review.expense, guard.message)
                        is GuardResult.Confirm -> pending = guard to review.expense
                    }
                }
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (saving) "Salvando…" else "Salvar") }
    }

    pending?.let { (guard, expense) ->
        GuardDialog(
            guard = guard,
            onCancel = { pending = null },
            onConfirm = {
                pending = null
                persist(expense, (guard as? GuardResult.Warning)?.message ?: "Gasto de ${Money.format(expense.amountCents)} salvo.")
            },
        )
    }
}

/** Alerta firme: "Cancelar" é o botão destacado; gastar mesmo assim fica em segundo plano. */
@Composable
private fun GuardDialog(guard: GuardResult, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val card = guard.card
    val title = when (guard) {
        is GuardResult.Confirm -> guard.title
        else -> "Compra no cartão ${card?.cardName.orEmpty()}"
    }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = NxusColors.Surface,
        title = { Text(title, color = if (guard is GuardResult.Confirm) NxusColors.Alert else NxusColors.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (guard is GuardResult.Confirm) Text(guard.message)
                card?.let { CardNoticeText(it) }
            }
        },
        confirmButton = { Button(onClick = onCancel) { Text("Cancelar") } },
        dismissButton = {
            OutlinedButton(onClick = onConfirm) { Text(if (guard is GuardResult.Confirm) "Gastar mesmo assim" else "Confirmar") }
        },
    )
}

@Composable
private fun CardNoticeText(card: CardNotice) {
    Text("${Money.format(card.reservedCents)} serão reservados para a fatura de ${PayoffPlanner.label(card.invoiceMonth)}. Conta no seu limite da semana como Pix.")
    Text(
        "${card.purchasesThisMonth}ª compra no cartão este mês." + (card.payoffDelayDays?.let { " Atrasa a saída do cartão em ~$it dias." } ?: ""),
        color = NxusColors.Danger,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun NumberPad(onKey: (Char) -> Unit) {
    val rows = listOf("123", "456", "789", "0<")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (row.length == 2) TextButton(onClick = { onKey('0'); onKey('0') }, modifier = Modifier.weight(1f).height(52.dp)) { Text("00", style = MaterialTheme.typography.titleLarge, color = NxusColors.Text) }
                row.forEach { key ->
                    TextButton(onClick = { onKey(key) }, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text(if (key == '<') "⌫" else key.toString(), style = MaterialTheme.typography.titleLarge, color = NxusColors.Text)
                    }
                }
            }
        }
    }
}
