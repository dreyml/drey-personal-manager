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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
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
import com.nxuslab.dreymanager.domain.model.AccountKind
import com.nxuslab.dreymanager.domain.model.CardDebt
import com.nxuslab.dreymanager.domain.model.CardStatus
import com.nxuslab.dreymanager.domain.model.Expense
import com.nxuslab.dreymanager.domain.model.PaymentMethod
import com.nxuslab.dreymanager.domain.money.Money
import com.nxuslab.dreymanager.domain.payoff.PayoffPlanner
import com.nxuslab.dreymanager.feature.common.cardUsedCents
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class AddExpenseViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as NxusApp).repository
    val snapshot: StateFlow<FinanceSnapshot?> = repository.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun saveExpense(expense: Expense): Result<Unit> = repository.saveExpense(expense)
    suspend fun saveDebt(debt: CardDebt): Result<Unit> = repository.saveDebt(debt)

    fun saveIncome(amountCents: Long, percentToDebt: Int, accountId: String?, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch { onResult(repository.saveIncome(AddExpenseLogic.extraIncome(LocalDate.now(), amountCents, percentToDebt, accountId))) }
    }
}

private enum class Mode(val label: String) { GASTO("Gasto"), PARCELADO("Parcelado"), ENTRADA("Entrada") }

private val methods = listOf(PaymentMethod.PIX to "Pix", PaymentMethod.DEBITO to "Débito", PaymentMethod.DINHEIRO to "Dinheiro", PaymentMethod.CARTAO to "Cartão")

/**
 * Lançamento em 2 toques (valor + categoria), compra parcelada no cartão ou entrada extra. Antes de
 * salvar, o alerta firme decide se salva direto, avisa ou pede confirmação (especificação §6.5).
 * [onDone] recebe a mensagem para a tela mostrar.
 */
@Composable
fun AddExpenseSheet(onDone: (String?) -> Unit, viewModel: AddExpenseViewModel = viewModel()) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val data = snapshot ?: return
    val today = LocalDate.now()
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(Mode.GASTO) }
    var digits by remember { mutableStateOf("") }
    val categories = remember(data.categories, data.expenses) { AddExpenseLogic.categoryOrder(data, today) }
    // Sem pré-seleção: escolher a categoria é o segundo toque, e evita classificar gasto errado sem perceber.
    var categoryId by remember { mutableStateOf<String?>(null) }
    var method by remember { mutableStateOf(PaymentMethod.PIX) }
    val cards = data.cards.filter { it.status == CardStatus.ATIVO }
    var cardId by remember { mutableStateOf(cards.firstOrNull { !it.cancelGoal }?.id ?: cards.firstOrNull()?.id) }
    val banks = data.accounts.filter { it.kind == AccountKind.BANCO }
    val wallet = data.accounts.firstOrNull { it.kind == AccountKind.DINHEIRO }
    var bankId by remember { mutableStateOf(banks.firstOrNull()?.id) }
    var description by remember { mutableStateOf("") }
    var countText by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<GuardResult, Expense>?>(null) }
    var pendingInstallment by remember { mutableStateOf<InstallmentReview?>(null) }
    var saving by remember { mutableStateOf(false) }
    val amount = Keypad.cents(digits)
    val accountId = when (method) {
        PaymentMethod.PIX, PaymentMethod.DEBITO -> bankId
        PaymentMethod.DINHEIRO -> wallet?.id
        PaymentMethod.CARTAO -> null
    }

    fun persist(expense: Expense, message: String) {
        saving = true
        scope.launch {
            viewModel.saveExpense(expense).onSuccess { onDone(message) }.onFailure { onDone(it.message) }
            saving = false
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Mode.entries.forEachIndexed { index, m ->
                SegmentedButton(selected = mode == m, onClick = { mode = m }, shape = SegmentedButtonDefaults.itemShape(index, Mode.entries.size)) { Text(m.label) }
            }
        }

        Text(
            Money.format(amount) + if (mode == Mode.PARCELADO) " /parcela" else "",
            style = MaterialTheme.typography.displayLarge.copy(fontSize = MaterialTheme.typography.displayLarge.fontSize * 0.8f),
            color = if (amount == 0L) NxusColors.TextMuted else NxusColors.AccentLight,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        when (mode) {
            Mode.ENTRADA -> {
                val percent = data.settings.budget.extraSplitToDebtPercent
                val (toDebt, toWeek) = BudgetCalculator.splitExtra(amount, percent)
                AccountChips(banks, wallet, bankId) { bankId = it }
                Text("$percent% vai para a quitação (${Money.format(toDebt)}) e ${Money.format(toWeek)} entra no mês.", color = NxusColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
            }
            Mode.PARCELADO -> {
                CardChips(cards, cardId) { cardId = it }
                OutlinedTextField(description, { description = it }, label = { Text("O que foi parcelado") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    countText, { countText = it.filter(Char::isDigit).take(2) }, label = { Text("Quantas parcelas") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
                val count = countText.toIntOrNull()
                if (amount > 0 && count != null && count > 0) Text("Total: ${Money.format(amount * count)} em $count× de ${Money.format(amount)}", color = NxusColors.TextMuted)
            }
            Mode.GASTO -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(categories, key = { it.id }) { category ->
                        FilterChip(
                            selected = categoryId == category.id,
                            onClick = { categoryId = category.id },
                            label = { Text("${category.emoji} ${category.name}") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NxusColors.SurfaceHigh, selectedLabelColor = NxusColors.AccentLight),
                        )
                    }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    methods.forEachIndexed { index, (value, label) ->
                        SegmentedButton(selected = method == value, onClick = { method = value }, shape = SegmentedButtonDefaults.itemShape(index, methods.size)) { Text(label, maxLines = 1) }
                    }
                }
                when (method) {
                    PaymentMethod.CARTAO -> CardChips(cards, cardId) { cardId = it }
                    PaymentMethod.PIX, PaymentMethod.DEBITO -> if (banks.size > 1) AccountChips(banks, null, bankId) { bankId = it }
                    PaymentMethod.DINHEIRO -> if (wallet == null) Text("Cadastre a carteira na tela inicial para o dinheiro sair dela.", color = NxusColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
                }
                val chosen = categoryId
                if (amount > 0 && chosen != null) {
                    AddExpenseLogic.review(data, today, amount, chosen, method, cardId, accountId).remainingAfterCents?.let {
                        Text(
                            "Depois: ${if (it >= 0) "restam ${Money.format(it)}" else "${Money.format(-it)} acima do limite"} na semana",
                            color = if (it >= 0) NxusColors.TextMuted else NxusColors.Danger,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        NumberPad { key -> digits = Keypad.press(digits, key) }

        val count = countText.toIntOrNull()
        val canSave = amount > 0 && !saving && when (mode) {
            Mode.ENTRADA -> true
            Mode.PARCELADO -> cardId != null && count != null && count > 0
            Mode.GASTO -> categoryId != null && (method != PaymentMethod.CARTAO || cardId != null)
        }
        Button(
            onClick = {
                when (mode) {
                    Mode.ENTRADA -> {
                        saving = true
                        viewModel.saveIncome(amount, data.settings.budget.extraSplitToDebtPercent, bankId ?: wallet?.id) { result ->
                            saving = false
                            onDone(result.fold({ "Entrada de ${Money.format(amount)} registrada." }, { it.message }))
                        }
                    }
                    Mode.PARCELADO -> pendingInstallment = AddExpenseLogic.installment(data, today, cardId!!, description.trim(), amount, count!!)
                    Mode.GASTO -> {
                        val review = AddExpenseLogic.review(data, today, amount, categoryId!!, method, cardId, accountId)
                        when (val guard = review.guard) {
                            null, is GuardResult.Ok -> if (guard?.card != null) pending = guard to review.expense else persist(review.expense, "Gasto de ${Money.format(amount)} salvo.")
                            is GuardResult.Warning -> if (guard.card != null) pending = guard to review.expense else persist(review.expense, guard.message)
                            is GuardResult.Confirm -> pending = guard to review.expense
                        }
                    }
                }
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (saving) "Salvando…" else "Salvar") }
    }

    pending?.let { (guard, expense) ->
        val available = expense.cardId?.let { id -> data.cards.firstOrNull { it.id == id }?.limitCents?.let { it - data.cardUsedCents(id, today) } }
        GuardDialog(
            guard = guard,
            availableLimitCents = available,
            onCancel = { pending = null },
            onConfirm = {
                pending = null
                persist(expense, (guard as? GuardResult.Warning)?.message ?: "Gasto de ${Money.format(expense.amountCents)} salvo.")
            },
        )
    }

    pendingInstallment?.let { review ->
        AlertDialog(
            onDismissRequest = { pendingInstallment = null },
            containerColor = NxusColors.Surface,
            title = { Text("Parcelar no ${review.cardName}?", color = NxusColors.Alert) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${review.debt.remainingInstallments}× de ${Money.format(review.debt.amountCents)} = ${Money.format(review.totalCents)}, a partir da fatura de ${PayoffPlanner.label(java.time.YearMonth.from(review.debt.nextDueDate))}.")
                    Text("As parcelas entram no orçamento de cada mês e reduzem as próximas semanas.", color = NxusColors.TextMuted)
                    review.payoffDelayMonths?.takeIf { it > 0 }?.let { Text("Atrasa a saída do cartão em $it ${if (it == 1) "mês" else "meses"}.", color = NxusColors.Danger) }
                }
            },
            confirmButton = { Button(onClick = { pendingInstallment = null }) { Text("Cancelar") } },
            dismissButton = {
                OutlinedButton(onClick = {
                    pendingInstallment = null
                    saving = true
                    scope.launch {
                        viewModel.saveDebt(review.debt).onSuccess { onDone("Parcelamento salvo no ${review.cardName}.") }.onFailure { onDone(it.message) }
                        saving = false
                    }
                }) { Text("Parcelar mesmo assim") }
            },
        )
    }
}

@Composable
private fun CardChips(cards: List<com.nxuslab.dreymanager.domain.model.Card>, selected: String?, onSelect: (String) -> Unit) {
    if (cards.isEmpty()) {
        Text("Nenhum cartão cadastrado. Cadastre em Mais › Cartões.", color = NxusColors.Alert)
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(cards, key = { it.id }) { card ->
            FilterChip(selected = selected == card.id, onClick = { onSelect(card.id) }, label = { Text(card.name + if (card.cancelGoal) " (cancelar)" else "") })
        }
    }
}

@Composable
private fun AccountChips(banks: List<com.nxuslab.dreymanager.data.repo.Account>, wallet: com.nxuslab.dreymanager.data.repo.Account?, selected: String?, onSelect: (String) -> Unit) {
    val all = banks + listOfNotNull(wallet)
    if (all.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        item { Text("Conta:", color = NxusColors.TextMuted, style = MaterialTheme.typography.labelMedium) }
        items(all, key = { it.id }) { a ->
            FilterChip(selected = selected == a.id, onClick = { onSelect(a.id) }, label = { Text((if (a.kind == AccountKind.DINHEIRO) "💵 " else "🏦 ") + a.name) })
        }
    }
}

/** Alerta firme: "Cancelar" é o botão destacado; gastar mesmo assim fica em segundo plano. */
@Composable
private fun GuardDialog(guard: GuardResult, availableLimitCents: Long?, onCancel: () -> Unit, onConfirm: () -> Unit) {
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
                card?.let { CardNoticeText(it, availableLimitCents) }
            }
        },
        confirmButton = { Button(onClick = onCancel) { Text("Cancelar") } },
        dismissButton = {
            OutlinedButton(onClick = onConfirm) { Text(if (guard is GuardResult.Confirm) "Gastar mesmo assim" else "Confirmar") }
        },
    )
}

@Composable
private fun CardNoticeText(card: CardNotice, availableLimitCents: Long?) {
    Text("${Money.format(card.reservedCents)} serão reservados para a fatura de ${PayoffPlanner.label(card.invoiceMonth)}. Conta no seu limite da semana como Pix.")
    availableLimitCents?.let {
        Text("Limite disponível depois desta compra: ${Money.format(it - card.reservedCents)}", color = if (it - card.reservedCents < 0) NxusColors.Danger else NxusColors.TextMuted)
    }
    Text(
        "${card.purchasesThisMonth}ª compra no cartão este mês." + (card.payoffDelayDays?.let { " Atrasa a saída do cartão em ~$it dias." } ?: ""),
        color = NxusColors.Danger,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun NumberPad(onKey: (Char) -> Unit) {
    val rows = listOf("123", "456", "789", "0<")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (row.length == 2) TextButton(onClick = { onKey('0'); onKey('0') }, modifier = Modifier.weight(1f).height(48.dp)) { Text("00", style = MaterialTheme.typography.titleLarge, color = NxusColors.Text) }
                row.forEach { key ->
                    TextButton(onClick = { onKey(key) }, modifier = Modifier.weight(1f).height(48.dp)) {
                        Text(if (key == '<') "⌫" else key.toString(), style = MaterialTheme.typography.titleLarge, color = NxusColors.Text)
                    }
                }
            }
        }
    }
}
