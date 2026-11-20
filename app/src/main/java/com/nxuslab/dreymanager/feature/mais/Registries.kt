package com.nxuslab.dreymanager.feature.mais

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nxuslab.dreymanager.core.theme.NxusColors
import com.nxuslab.dreymanager.core.ui.CompactRow
import com.nxuslab.dreymanager.core.ui.DatePickerField
import com.nxuslab.dreymanager.core.ui.MoneyField
import com.nxuslab.dreymanager.core.ui.SectionHeader
import com.nxuslab.dreymanager.core.ui.br
import com.nxuslab.dreymanager.data.repo.Account
import com.nxuslab.dreymanager.data.repo.Category
import com.nxuslab.dreymanager.data.repo.FinanceSnapshot
import com.nxuslab.dreymanager.domain.model.Card
import com.nxuslab.dreymanager.domain.model.CardDebt
import com.nxuslab.dreymanager.domain.model.CardStatus
import com.nxuslab.dreymanager.domain.model.DebtKind
import com.nxuslab.dreymanager.domain.model.Envelope
import com.nxuslab.dreymanager.domain.model.FixedBill
import com.nxuslab.dreymanager.domain.model.Income
import com.nxuslab.dreymanager.domain.model.IncomeKind
import com.nxuslab.dreymanager.domain.money.Money
import com.nxuslab.dreymanager.domain.money.MoneyParser
import com.nxuslab.dreymanager.domain.money.MoneyResult
import java.time.LocalDate
import java.util.UUID

private fun newId() = UUID.randomUUID().toString()

/** Moldura comum das listas de cadastro. */
@Composable
private fun RegistryList(title: String, hint: String, addLabel: String, onAdd: () -> Unit, content: LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(hint, color = NxusColors.TextMuted)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text(addLabel) }
        }
        content()
    }
}

/** Diálogo padrão de edição com Excluir (confirmado) quando o item já existe. */
@Composable
private fun EditDialog(title: String, canSave: Boolean, onDismiss: () -> Unit, onSave: () -> Unit, onDelete: (() -> Unit)?, content: @Composable () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false }, containerColor = NxusColors.Surface,
            title = { Text("Excluir?") }, text = { Text("Isso não pode ser desfeito.") },
            confirmButton = { Button(onClick = { confirmDelete = false }) { Text("Manter") } },
            dismissButton = { TextButton(onClick = onDelete) { Text("Excluir", color = NxusColors.Danger) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = NxusColors.Surface, title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } },
        confirmButton = { Button(onClick = onSave, enabled = canSave) { Text("Salvar") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = { confirmDelete = true }) { Text("Excluir", color = NxusColors.Danger) }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}

@Composable
private fun DayInput(label: String, value: Int, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (Int?) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    val valid = text.toIntOrNull()?.let { it in 1..31 } == true
    OutlinedTextField(
        text, { new -> text = new.filter(Char::isDigit).take(2); onChange(text.toIntOrNull()?.takeIf { it in 1..31 }) },
        label = { Text(label) }, isError = !valid, supportingText = if (!valid) ({ Text("De 1 a 31") }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = modifier,
    )
}

// ---------------- Renda ----------------

@Composable
internal fun IncomeSection(data: FinanceSnapshot, vm: MoreViewModel, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Income?>(null) }
    RegistryList("Renda e extras", "A renda fixa entra todo mês no dia escolhido. Rendas extras são lançadas pelo botão + Gasto › Entrada extra.", "Adicionar renda fixa", { editing = Income(newId(), IncomeKind.FIXA, 0, dayOfMonth = 5) }) {
        val fixed = data.incomes.filter { it.kind == IncomeKind.FIXA }
        if (fixed.isNotEmpty()) item { SectionHeader("Fixa") }
        items(fixed, key = { it.id }) { CompactRow("Todo dia ${it.dayOfMonth}", null, Money.format(it.amountCents), onClick = { editing = it }) }
        val extras = data.incomes.filter { it.kind == IncomeKind.EXTRA }.sortedByDescending { it.date }
        if (extras.isNotEmpty()) item { SectionHeader("Extras") }
        items(extras, key = { it.id }) { CompactRow(it.date?.br() ?: "—", "${Money.format(it.toDebtCents)} para a quitação", Money.format(it.amountCents), onClick = { editing = it }) }
    }
    editing?.let { income ->
        var text by remember(income.id) { mutableStateOf(centsText(income.amountCents)) }
        var amount by remember(income.id) { mutableStateOf(income.amountCents.takeIf { it > 0 }) }
        var day by remember(income.id) { mutableStateOf(income.dayOfMonth) }
        val exists = data.incomes.any { it.id == income.id }
        EditDialog(
            title = if (income.kind == IncomeKind.FIXA) "Renda fixa" else "Renda extra",
            canSave = amount != null && (income.kind == IncomeKind.EXTRA || day != null),
            onDismiss = { editing = null },
            onSave = { editing = null; scope.save({ vm.repository.saveIncome(income.copy(amountCents = amount!!, dayOfMonth = if (income.kind == IncomeKind.FIXA) day else null)) }, onMessage, "Renda salva.") },
            onDelete = if (exists) ({ editing = null; scope.save({ vm.repository.deleteIncome(income.id) }, onMessage, "Renda excluída.") }) else null,
        ) {
            MoneyField(text, { text = it }, "Valor", onValid = { amount = it })
            if (income.kind == IncomeKind.FIXA) DayInput("Dia do pagamento", income.dayOfMonth ?: 5) { day = it }
        }
    }
}

// ---------------- Contas fixas ----------------

@Composable
internal fun BillsSection(data: FinanceSnapshot, vm: MoreViewModel, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<FixedBill?>(null) }
    RegistryList("Contas fixas", "Pausar uma conta tira ela do orçamento sem apagar.", "Adicionar conta", { editing = FixedBill(newId(), "", 0, 10) }) {
        items(data.fixedBills, key = { it.id }) { bill ->
            CompactRow(bill.title, "todo dia ${bill.dayOfMonth}${if (bill.active) "" else " · pausada"}", Money.format(bill.amountCents), trailingColor = if (bill.active) NxusColors.Text else NxusColors.TextMuted, onClick = { editing = bill })
        }
    }
    editing?.let { bill ->
        var title by remember(bill.id) { mutableStateOf(bill.title) }
        var text by remember(bill.id) { mutableStateOf(centsText(bill.amountCents)) }
        var amount by remember(bill.id) { mutableStateOf(bill.amountCents.takeIf { it > 0 }) }
        var day by remember(bill.id) { mutableStateOf<Int?>(bill.dayOfMonth) }
        var active by remember(bill.id) { mutableStateOf(bill.active) }
        val exists = data.fixedBills.any { it.id == bill.id }
        EditDialog(
            title = if (exists) bill.title else "Nova conta fixa",
            canSave = title.isNotBlank() && amount != null && day != null,
            onDismiss = { editing = null },
            onSave = { editing = null; scope.save({ vm.repository.saveFixedBill(bill.copy(title = title.trim(), amountCents = amount!!, dayOfMonth = day!!, active = active)) }, onMessage, "Conta salva.") },
            onDelete = if (exists) ({ editing = null; scope.save({ vm.repository.deleteFixedBill(bill.id) }, onMessage, "Conta excluída.") }) else null,
        ) {
            OutlinedTextField(title, { title = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            MoneyField(text, { text = it }, "Valor mensal", onValid = { amount = it })
            DayInput("Dia do vencimento", bill.dayOfMonth) { day = it }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Ativa", modifier = Modifier.weight(1f)); Switch(active, { active = it }) }
        }
    }
}

// ---------------- Contas bancárias ----------------

@Composable
internal fun AccountsSection(data: FinanceSnapshot, vm: MoreViewModel, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Account?>(null) }
    RegistryList("Contas bancárias", "Atualize o saldo de vez em quando para a previsão \"vai faltar?\" ficar certa.", "Adicionar conta", { editing = Account(newId(), "", 0, LocalDate.now()) }) {
        items(data.accounts, key = { it.id }) { CompactRow(it.name, "atualizado em ${it.updatedAt.br()}", Money.format(it.balanceCents), onClick = { editing = it }) }
    }
    editing?.let { account ->
        var name by remember(account.id) { mutableStateOf(account.name) }
        var text by remember(account.id) { mutableStateOf(centsText(account.balanceCents)) }
        var balance by remember(account.id) { mutableStateOf<Long?>(account.balanceCents) }
        val exists = data.accounts.any { it.id == account.id }
        EditDialog(
            title = if (exists) account.name else "Nova conta",
            canSave = name.isNotBlank() && (text.isBlank() || balance != null),
            onDismiss = { editing = null },
            onSave = { editing = null; scope.save({ vm.repository.saveAccount(account.copy(name = name.trim(), balanceCents = balance ?: 0, updatedAt = LocalDate.now())) }, onMessage, "Saldo atualizado.") },
            onDelete = if (exists) ({ editing = null; scope.save({ vm.repository.deleteAccount(account.id) }, onMessage, "Conta excluída.") }) else null,
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            MoneyField(text, { text = it }, "Saldo de hoje", onValid = { balance = it })
        }
    }
}

// ---------------- Categorias e envelopes ----------------

@Composable
internal fun CategoriesSection(data: FinanceSnapshot, vm: MoreViewModel, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Category?>(null) }
    val limits = data.envelopes.associate { it.categoryId to it.monthlyLimitCents }
    RegistryList("Categorias e envelopes", "Envelope é um limite mensal para uma categoria. Use só nas que mais pesam.", "Nova categoria", { editing = Category(newId(), "", "•", 0xFF9C8A8F) }) {
        items(data.categories, key = { it.id }) { c ->
            CompactRow("${c.emoji} ${c.name}", limits[c.id]?.let { "envelope de ${Money.format(it)} por mês" } ?: "sem limite", null, onClick = { editing = c })
        }
    }
    editing?.let { category ->
        var name by remember(category.id) { mutableStateOf(category.name) }
        var emoji by remember(category.id) { mutableStateOf(category.emoji) }
        var text by remember(category.id) { mutableStateOf(limits[category.id]?.let(::centsText).orEmpty()) }
        var limit by remember(category.id) { mutableStateOf(limits[category.id]) }
        EditDialog(
            title = if (category.name.isBlank()) "Nova categoria" else category.name,
            canSave = name.isNotBlank() && (text.isBlank() || limit != null),
            onDismiss = { editing = null },
            onSave = {
                editing = null
                scope.save({
                    vm.repository.saveCategory(category.copy(name = name.trim(), emoji = emoji.ifBlank { "•" })).mapCatching {
                        if (limit != null && text.isNotBlank()) vm.repository.saveEnvelope(Envelope(category.id, limit!!)).getOrThrow()
                        else vm.repository.deleteEnvelope(category.id).getOrThrow()
                    }
                }, onMessage, "Categoria salva.")
            },
            onDelete = null, // categorias ficam: gastos antigos dependem delas
        ) {
            OutlinedTextField(emoji, { emoji = it.take(2) }, label = { Text("Emoji") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            MoneyField(text, { text = it }, "Limite por mês (vazio = sem envelope)", onValid = { limit = it })
        }
    }
}

// ---------------- Cartões e dívidas ----------------

@Composable
internal fun CardsSection(data: FinanceSnapshot, vm: MoreViewModel, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Card?>(null) }
    RegistryList("Cartões e dívidas", "Faturas, parcelas e rotativo de cada cartão alimentam o plano de saída.", "Adicionar cartão", { editing = Card(newId(), "", 3, 10) }) {
        items(data.cards, key = { it.id }) { card ->
            val total = data.debts.filter { it.cardId == card.id }.sumOf { it.balanceCents }
            val status = when (card.status) { CardStatus.ATIVO -> ""; CardStatus.QUITADO -> " · quitado"; CardStatus.CANCELADO -> " · cancelado" }
            CompactRow(card.name, "fecha dia ${card.closingDay} · vence dia ${card.dueDay}$status", Money.format(total), trailingColor = if (total > 0) NxusColors.Danger else NxusColors.Positive, onClick = { editing = card })
        }
    }
    editing?.let { card ->
        CardEditor(
            card = card,
            debts = data.debts.filter { it.cardId == card.id },
            exists = data.cards.any { it.id == card.id },
            onDismiss = { editing = null },
            onSave = { saved, newDebts, removed ->
                editing = null
                scope.save({
                    vm.repository.saveCard(saved, newDebts).mapCatching { removed.forEach { id -> vm.repository.deleteDebt(id).getOrThrow() } }
                }, onMessage, "${saved.name} salvo.")
            },
            onDelete = { editing = null; scope.save({ vm.repository.deleteCard(card.id) }, onMessage, "Cartão excluído com suas dívidas.") },
        )
    }
}

private val debtKinds = listOf(DebtKind.FATURA_ABERTA to "Fatura", DebtKind.PARCELA to "Parcela", DebtKind.ROTATIVO to "Rotativo")

@Composable
private fun CardEditor(card: Card, debts: List<CardDebt>, exists: Boolean, onDismiss: () -> Unit, onSave: (Card, List<CardDebt>, List<String>) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(card.name) }
    var closing by remember { mutableStateOf<Int?>(card.closingDay) }
    var due by remember { mutableStateOf<Int?>(card.dueDay) }
    var interestText by remember { mutableStateOf(card.monthlyInterestBp?.let { centsText(it.toLong()) }.orEmpty()) }
    var current by remember { mutableStateOf(debts) }
    val removed = remember { mutableListOf<String>() }
    var confirmDelete by remember { mutableStateOf(false) }
    // Nova dívida
    var kind by remember { mutableStateOf(DebtKind.PARCELA) }
    var description by remember { mutableStateOf("") }
    var valueText by remember { mutableStateOf("") }
    var value by remember { mutableStateOf<Long?>(null) }
    var remaining by remember { mutableStateOf("") }
    var nextDue by remember { mutableStateOf<LocalDate?>(LocalDate.now()) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = NxusColors.Background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(20.dp)) {
                Text(if (exists) card.name else "Novo cartão", style = MaterialTheme.typography.headlineMedium)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DayInput("Fecha dia", card.closingDay, Modifier.weight(1f)) { closing = it }
                        DayInput("Vence dia", card.dueDay, Modifier.weight(1f)) { due = it }
                    }
                    OutlinedTextField(interestText, { interestText = it }, label = { Text("Juros ao mês (%), se souber") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())

                    SectionHeader("Dívidas")
                    if (current.isEmpty()) Text("Nenhuma dívida neste cartão.", color = NxusColors.TextMuted)
                    current.forEach { d ->
                        val detail = if (d.isInstallment) "${d.remainingInstallments}× de ${Money.format(d.amountCents)} · desde ${d.nextDueDate.br()}" else "vence ${d.nextDueDate.br()}"
                        CompactRow(d.description, "$detail · toque para remover", Money.format(d.balanceCents), trailingColor = NxusColors.Danger, onClick = { current = current - d; removed += d.id })
                    }

                    SectionHeader("Adicionar dívida")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        debtKinds.forEachIndexed { i, (k, label) -> SegmentedButton(selected = kind == k, onClick = { kind = k }, shape = SegmentedButtonDefaults.itemShape(i, debtKinds.size)) { Text(label) } }
                    }
                    OutlinedTextField(description, { description = it }, label = { Text("Descrição") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    MoneyField(valueText, { valueText = it }, if (kind == DebtKind.PARCELA) "Valor da parcela" else "Valor", onValid = { value = it })
                    if (kind == DebtKind.PARCELA) {
                        OutlinedTextField(remaining, { remaining = it.filter(Char::isDigit).take(3) }, label = { Text("Parcelas restantes") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    DatePickerField("Próximo vencimento", nextDue, { nextDue = it })
                    val count = remaining.toIntOrNull()
                    OutlinedButton(
                        onClick = {
                            current = current + CardDebt(newId(), card.id, kind, description.trim().ifBlank { debtKinds.first { it.first == kind }.second }, value!!, if (kind == DebtKind.PARCELA) count else null, nextDue ?: LocalDate.now())
                            description = ""; valueText = ""; value = null; remaining = ""
                        },
                        enabled = value != null && (kind != DebtKind.PARCELA || (count != null && count > 0)),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Adicionar dívida") }
                }
                if (confirmDelete) {
                    Text("Excluir o cartão apaga também as dívidas dele.", color = NxusColors.Danger)
                    Row { TextButton(onClick = onDelete) { Text("Excluir mesmo", color = NxusColors.Danger) }; TextButton(onClick = { confirmDelete = false }) { Text("Manter") } }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (exists) TextButton(onClick = { confirmDelete = true }) { Text("Excluir", color = NxusColors.Danger) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancelar") }
                    Button(
                        onClick = {
                            val bp = (MoneyParser.parse(interestText) as? MoneyResult.Valid)?.cents?.toInt()
                            onSave(card.copy(name = name.trim(), closingDay = closing!!, dueDay = due!!, monthlyInterestBp = bp), current, removed.toList())
                        },
                        enabled = name.isNotBlank() && closing != null && due != null,
                    ) { Text("Salvar") }
                }
            }
        }
    }
}
