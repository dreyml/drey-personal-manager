package com.nxuslab.dreymanager.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nxuslab.dreymanager.core.theme.NxusColors
import com.nxuslab.dreymanager.core.ui.AlertCard
import com.nxuslab.dreymanager.core.ui.AlertKind
import com.nxuslab.dreymanager.core.ui.BigRing
import com.nxuslab.dreymanager.core.ui.CompactRow
import com.nxuslab.dreymanager.core.ui.MoneyField
import com.nxuslab.dreymanager.core.ui.SectionHeader
import com.nxuslab.dreymanager.domain.budget.WeekBudget
import com.nxuslab.dreymanager.domain.money.Money
import com.nxuslab.dreymanager.domain.money.MoneyParser
import com.nxuslab.dreymanager.domain.money.MoneyResult
import com.nxuslab.dreymanager.domain.payoff.PayoffPlanner
import com.nxuslab.dreymanager.domain.payoff.PayoffResult
import java.util.UUID

private val stepTitles = listOf(
    "Quanto você ganha?" to "Sua renda fixa do mês e o dia em que ela cai.",
    "Quanto tem na conta?" to "O saldo de hoje na sua conta principal.",
    "Contas fixas" to "Aluguel, internet, luz… o que se repete todo mês.",
    "Seus cartões" to "Fatura atual, parcelas e rotativo de cada cartão.",
    "Envelopes" to "Limites mensais só para as categorias que mais pesam.",
)

@Composable
fun OnboardingScreen(onFinished: () -> Unit, viewModel: OnboardingViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isSummary = state.step == OnboardingViewModel.SUMMARY_STEP
    // O botão voltar do Android volta um passo em vez de sair e perder o que foi digitado.
    BackHandler(enabled = state.step > 0) { viewModel.back() }

    // imePadding: o teclado empurra a barra de botões para cima em vez de cobri-la.
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp, vertical = 16.dp)) {
        if (!isSummary) {
            Text("Passo ${state.step + 1} de ${OnboardingViewModel.STEP_COUNT}", style = MaterialTheme.typography.labelMedium, color = NxusColors.TextMuted)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (state.step + 1f) / OnboardingViewModel.STEP_COUNT },
                modifier = Modifier.fillMaxWidth(),
                color = NxusColors.Accent,
                trackColor = NxusColors.SurfaceHigh,
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(20.dp))
            Text(stepTitles[state.step].first, style = MaterialTheme.typography.headlineMedium)
            Text(stepTitles[state.step].second, color = NxusColors.TextMuted)
            Spacer(Modifier.height(16.dp))
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state.step) {
                0 -> IncomeStep(state.draft, viewModel::edit)
                1 -> BalanceStep(state.draft, viewModel::edit)
                2 -> BillsStep(state.draft, viewModel::edit)
                3 -> CardsStep(state.draft, viewModel::edit)
                4 -> EnvelopesStep(state, viewModel::edit)
                else -> state.summary?.let { SummaryStep(it, state.draft, viewModel::edit) }
            }
            state.error?.let { AlertCard("Não deu para salvar", it, AlertKind.DANGER) }
        }

        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.step > 0) TextButton(onClick = viewModel::back) { Text("Voltar") }
            Spacer(Modifier.weight(1f))
            if (isSummary) {
                Button(onClick = { viewModel.finish(onFinished) }, enabled = !state.saving) { Text(if (state.saving) "Salvando…" else "Começar") }
            } else {
                TextButton(onClick = viewModel::next) { Text("Pular") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = viewModel::next) { Text("Próximo") }
            }
        }
    }
}

@Composable
private fun IncomeStep(draft: OnboardingDraft, edit: ((OnboardingDraft) -> OnboardingDraft) -> Unit) {
    var text by remember { mutableStateOf(draft.salaryCents?.let { centsToInput(it) }.orEmpty()) }
    MoneyField(text, { text = it }, "Renda fixa mensal", onValid = { cents -> edit { it.copy(salaryCents = cents) } })
    DayField("Dia do pagamento", draft.salaryDay) { day -> edit { it.copy(salaryDay = day) } }
}

@Composable
private fun BalanceStep(draft: OnboardingDraft, edit: ((OnboardingDraft) -> OnboardingDraft) -> Unit) {
    var text by remember { mutableStateOf(draft.balanceCents?.let { centsToInput(it) }.orEmpty()) }
    MoneyField(text, { text = it }, "Saldo atual", onValid = { cents -> edit { it.copy(balanceCents = cents) } })
    Text("É usado na previsão \"vai faltar?\". Dá para atualizar depois em Mais.", color = NxusColors.TextMuted)
}

@Composable
private fun BillsStep(draft: OnboardingDraft, edit: ((OnboardingDraft) -> OnboardingDraft) -> Unit) {
    draft.bills.forEach { bill ->
        CompactRow(bill.title, "todo dia ${bill.day}", Money.format(bill.amountCents), onClick = { edit { d -> d.copy(bills = d.bills - bill) } })
    }
    if (draft.bills.isNotEmpty()) Text("Toque numa conta para removê-la.", style = MaterialTheme.typography.labelMedium, color = NxusColors.TextMuted)

    var title by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf<Long?>(null) }
    var day by remember { mutableStateOf(10) }
    SectionHeader("Nova conta fixa")
    OutlinedTextField(title, { title = it }, label = { Text("Nome (ex.: Aluguel)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    MoneyField(amountText, { amountText = it }, "Valor mensal", onValid = { amount = it })
    DayField("Dia do vencimento", day) { day = it }
    OutlinedButton(
        onClick = {
            val cents = amount ?: return@OutlinedButton
            edit { it.copy(bills = it.bills + BillDraft(UUID.randomUUID().toString(), title.trim(), cents, day)) }
            title = ""; amountText = ""; amount = null
        },
        enabled = title.isNotBlank() && amount != null,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Adicionar conta") }
}

@Composable
private fun CardsStep(draft: OnboardingDraft, edit: ((OnboardingDraft) -> OnboardingDraft) -> Unit) {
    var editing by remember { mutableStateOf<CardDraft?>(null) }
    draft.cards.forEach { card ->
        val total = (card.openInvoiceCents ?: 0) + (card.rotativoCents ?: 0) + card.installments.sumOf { it.amountCents * it.remaining }
        CompactRow(card.name, "fecha dia ${card.closingDay} · vence dia ${card.dueDay}", Money.format(total), trailingColor = NxusColors.Danger, onClick = { editing = card })
    }
    OutlinedButton(onClick = { editing = CardDraft(UUID.randomUUID().toString(), "", 3, 10) }, modifier = Modifier.fillMaxWidth()) { Text("Adicionar cartão") }
    Text("Sem cartão? Pode pular este passo.", style = MaterialTheme.typography.labelMedium, color = NxusColors.TextMuted)

    editing?.let { card ->
        CardDialog(
            initial = card,
            onDismiss = { editing = null },
            onDelete = { edit { d -> d.copy(cards = d.cards.filterNot { it.id == card.id }) }; editing = null },
            onSave = { saved -> edit { d -> d.copy(cards = d.cards.filterNot { it.id == saved.id } + saved) }; editing = null },
        )
    }
}

@Composable
private fun CardDialog(initial: CardDraft, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (CardDraft) -> Unit) {
    var card by remember { mutableStateOf(initial) }
    var interestText by remember { mutableStateOf(initial.interestBp?.let { centsToInput(it.toLong()) }.orEmpty()) }
    var invoiceText by remember { mutableStateOf(initial.openInvoiceCents?.let { centsToInput(it) }.orEmpty()) }
    var rotativoText by remember { mutableStateOf(initial.rotativoCents?.let { centsToInput(it) }.orEmpty()) }
    var installmentDesc by remember { mutableStateOf("") }
    var installmentText by remember { mutableStateOf("") }
    var installmentValue by remember { mutableStateOf<Long?>(null) }
    var installmentCount by remember { mutableStateOf("") }

    // Tela cheia (não AlertDialog): o formulário é longo e o teclado cobriria os campos e o Salvar.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = NxusColors.Background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(20.dp)) {
                Text(if (initial.name.isBlank()) "Novo cartão" else initial.name, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(card.name, { card = card.copy(name = it) }, label = { Text("Nome do cartão") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DayField("Fecha dia", card.closingDay, Modifier.weight(1f)) { card = card.copy(closingDay = it) }
                    DayField("Vence dia", card.dueDay, Modifier.weight(1f)) { card = card.copy(dueDay = it) }
                }
                OutlinedTextField(
                    interestText,
                    { text -> interestText = text; card = card.copy(interestBp = (MoneyParser.parse(text) as? MoneyResult.Valid)?.cents?.toInt()) },
                    label = { Text("Juros ao mês (%), se souber") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MoneyField(invoiceText, { invoiceText = it }, "Fatura atual", onValid = { card = card.copy(openInvoiceCents = it) })
                MoneyField(rotativoText, { rotativoText = it }, "Rotativo / parcelamento da fatura", onValid = { card = card.copy(rotativoCents = it) })

                SectionHeader("Parcelas")
                card.installments.forEach { item ->
                    CompactRow(item.description, "${item.remaining}× restantes", Money.format(item.amountCents), onClick = { card = card.copy(installments = card.installments - item) })
                }
                OutlinedTextField(installmentDesc, { installmentDesc = it }, label = { Text("O que foi parcelado") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                MoneyField(installmentText, { installmentText = it }, "Valor da parcela", onValid = { installmentValue = it })
                OutlinedTextField(
                    installmentCount,
                    { installmentCount = it.filter(Char::isDigit).take(3) },
                    label = { Text("Parcelas restantes") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val count = installmentCount.toIntOrNull()
                TextButton(
                    onClick = {
                        card = card.copy(installments = card.installments + InstallmentDraft(UUID.randomUUID().toString(), installmentDesc.trim(), installmentValue!!, count!!))
                        installmentDesc = ""; installmentText = ""; installmentValue = null; installmentCount = ""
                    },
                    enabled = installmentDesc.isNotBlank() && installmentValue != null && count != null && count > 0,
                ) { Text("Adicionar parcela") }
            }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (initial.name.isNotBlank()) TextButton(onClick = onDelete) { Text("Remover", color = NxusColors.Danger) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancelar") }
                    Button(onClick = { onSave(card.copy(name = card.name.trim())) }, enabled = card.name.isNotBlank()) { Text("Salvar") }
                }
            }
        }
    }
}

@Composable
private fun EnvelopesStep(state: OnboardingUiState, edit: ((OnboardingDraft) -> OnboardingDraft) -> Unit) {
    state.categories.forEach { category ->
        val selected = category.id in state.draft.envelopes
        var text by remember(category.id) { mutableStateOf(state.draft.envelopes[category.id]?.takeIf { it > 0 }?.let { centsToInput(it) }.orEmpty()) }
        Column(Modifier.fillMaxWidth().background(NxusColors.Surface, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(selected, { checked ->
                    edit { d -> d.copy(envelopes = if (checked) d.envelopes + (category.id to 0L) else d.envelopes - category.id) }
                })
                Text("${category.emoji} ${category.name}", style = MaterialTheme.typography.titleMedium)
            }
            if (selected) {
                MoneyField(text, { text = it }, "Limite por mês", onValid = { cents -> edit { d -> d.copy(envelopes = d.envelopes + (category.id to (cents ?: 0L))) } })
            }
        }
    }
}

@Composable
private fun SummaryStep(summary: OnboardingSummary, draft: OnboardingDraft, edit: ((OnboardingDraft) -> OnboardingDraft) -> Unit) {
    Text("Seu plano", style = MaterialTheme.typography.headlineMedium)
    when (val week = summary.week) {
        is WeekBudget.Ready -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                BigRing(week.limitCents, week.limitCents, "limite desta semana", "~${Money.formatShort(week.limitCents / 7)} por dia")
            }
        }
        is WeekBudget.Incomplete -> AlertCard("Falta a renda", week.reason, AlertKind.WARNING)
    }
    when (val payoff = summary.payoff) {
        is PayoffResult.Plan -> payoff.debtFreeMonth?.let { free ->
            AlertCard("Livre do cartão em ${PayoffPlanner.label(free)}", payoff.explanation, AlertKind.INFO)
        }
        is PayoffResult.NeverPaysOff -> AlertCard("A dívida não fecha assim", payoff.message, AlertKind.DANGER)
    }
    SectionHeader("Extra para quitar o cartão por mês")
    var text by remember { mutableStateOf(centsToInput(summary.appliedExtraCents)) }
    MoneyField(text, { text = it }, "Extra de quitação", onValid = { cents -> if (cents != null) edit { it.copy(payoffExtraCents = cents) } })
    Text(
        "Sugestão: ${Money.format(summary.suggestedExtraCents)} (30% do que sobra depois das contas e faturas). Quanto maior, mais cedo você sai do cartão, mas menor fica a semana.",
        style = MaterialTheme.typography.bodyMedium,
        color = NxusColors.TextMuted,
    )
    if (draft.payoffExtraCents != null) TextButton(onClick = { edit { it.copy(payoffExtraCents = null) }; text = centsToInput(summary.suggestedExtraCents) }) { Text("Usar a sugestão") }
}

/** Campo de dia do mês (1 a 31) com erro visível. */
@Composable
private fun DayField(label: String, value: Int, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in 1..31
    OutlinedTextField(
        value = text,
        onValueChange = { new -> text = new.filter(Char::isDigit).take(2); text.toIntOrNull()?.takeIf { it in 1..31 }?.let(onChange) },
        label = { Text(label) },
        isError = !valid,
        supportingText = if (!valid) ({ Text("De 1 a 31") }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier,
    )
}

/** Centavos para o texto que o MoneyParser aceita de volta: 123456 → "1234,56". */
private fun centsToInput(cents: Long): String = "${cents / 100},${(cents % 100).toString().padStart(2, '0')}"
