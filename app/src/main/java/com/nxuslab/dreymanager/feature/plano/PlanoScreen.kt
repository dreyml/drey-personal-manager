package com.nxuslab.dreymanager.feature.plano

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.nxuslab.dreymanager.core.ui.DebtChart
import com.nxuslab.dreymanager.core.ui.SectionHeader
import com.nxuslab.dreymanager.data.db.WishSavingEntity
import com.nxuslab.dreymanager.data.repo.FinanceSnapshot
import com.nxuslab.dreymanager.domain.budget.BudgetCalculator
import com.nxuslab.dreymanager.domain.budget.WeekBudget
import com.nxuslab.dreymanager.domain.model.Card
import com.nxuslab.dreymanager.domain.model.CardStatus
import com.nxuslab.dreymanager.domain.model.Wish
import com.nxuslab.dreymanager.domain.money.Money
import com.nxuslab.dreymanager.domain.payoff.PayoffChange
import com.nxuslab.dreymanager.domain.payoff.PayoffPlanner
import com.nxuslab.dreymanager.domain.payoff.PayoffResult
import com.nxuslab.dreymanager.feature.common.budgetFor
import com.nxuslab.dreymanager.feature.common.cardUsedCents
import com.nxuslab.dreymanager.feature.common.payoffPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import com.nxuslab.dreymanager.domain.model.IncomeKind
import com.nxuslab.dreymanager.domain.model.WishStatus
import com.nxuslab.dreymanager.domain.model.clampDay
import com.nxuslab.dreymanager.domain.plan.ChecklistItem
import com.nxuslab.dreymanager.domain.plan.MonthChecklist
import com.nxuslab.dreymanager.feature.common.forecast
import com.nxuslab.dreymanager.feature.common.wishProjections

class PlanoViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as NxusApp).repository
    val snapshot: StateFlow<FinanceSnapshot?> = repository.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun setPaid(key: String, month: YearMonth, paid: Boolean) = repository.setPaid(key, month, paid)
    suspend fun payInvoice(debtId: String, key: String, month: YearMonth) = repository.payInvoice(debtId, key, month)
    suspend fun applyExtra(cents: Long) = repository.updateSettings { it.copy(budget = it.budget.copy(payoffExtraMonthlyCents = cents)) }
    suspend fun cancelCard(card: Card) = repository.saveCard(card.copy(status = CardStatus.CANCELADO))
    suspend fun saveWish(wish: Wish) = repository.saveWish(wish)
    suspend fun deleteWish(id: String) = repository.deleteWish(id)
    suspend fun addSaving(wishId: String, cents: Long) =
        repository.addWishSaving(WishSavingEntity(UUID.randomUUID().toString(), wishId, cents, LocalDate.now()))
}

/** Executa uma gravação e mostra sucesso ou o erro (nunca falha em silêncio). */
internal fun CoroutineScope.launchResult(action: suspend () -> Result<Unit>, onMessage: (String) -> Unit, success: String) {
    launch { action().fold({ onMessage(success) }, { onMessage(it.message ?: "Algo deu errado.") }) }
}

/** Seções do plano (deep link `plano?aba=` rola até a seção). */
enum class PlanoTab(val label: String) { CARTOES("Cartões"), PREVISAO("Previsão"), DESEJOS("Desejos") }

/**
 * Plano numa tela só, respondendo de cima para baixo: o que pagar este mês, se vai faltar dinheiro
 * e quando você sai das dívidas; os desejos ficam por último. Cada seção tem "Ver detalhes".
 */
@Composable
fun PlanoScreen(initialTab: PlanoTab, onMessage: (String) -> Unit, viewModel: PlanoViewModel = viewModel()) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    var detail by rememberSaveable { mutableStateOf<PlanoTab?>(null) }
    BackHandler(enabled = detail != null) { detail = null }
    val data = snapshot ?: return
    when (detail) {
        PlanoTab.CARTOES -> DetailFrame("Saída das dívidas", { detail = null }) { CardsTab(data, viewModel, onMessage) }
        PlanoTab.PREVISAO -> DetailFrame("Previsão do saldo", { detail = null }) { ForecastTab(data) }
        PlanoTab.DESEJOS -> DetailFrame("Desejos", { detail = null }) { WishesTab(data, viewModel, onMessage) }
        null -> PlanOverview(data, initialTab, viewModel, onMessage, onDetail = { detail = it })
    }
}

@Composable
private fun DetailFrame(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Voltar") }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        content()
    }
}

@Composable
private fun SectionTitle(number: Int, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
        Box(Modifier.size(28.dp).background(NxusColors.Text, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Text("$number", color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun PlanOverview(data: FinanceSnapshot, initialTab: PlanoTab, viewModel: PlanoViewModel, onMessage: (String) -> Unit, onDetail: (PlanoTab) -> Unit) {
    val today = LocalDate.now()
    val month = YearMonth.from(today)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val checklist = remember(data) {
        MonthChecklist.build(
            month, data.fixedBills, data.variableBills, data.variableActuals, data.debts,
            data.cards.associate { it.id to it.name }, data.settings.budget.payoffExtraMonthlyCents, data.paidItems,
        )
    }
    var confirmInvoice by remember { mutableStateOf<ChecklistItem?>(null) }
    LaunchedEffect(initialTab) {
        when (initialTab) {
            PlanoTab.PREVISAO -> listState.scrollToItem(checklist.size + 3)
            PlanoTab.DESEJOS -> listState.scrollToItem(checklist.size + 7)
            PlanoTab.CARTOES -> Unit
        }
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column {
                Text("Seu plano", style = MaterialTheme.typography.bodyMedium, color = NxusColors.TextMuted)
                Text("O que fazer com o dinheiro", style = MaterialTheme.typography.headlineMedium)
            }
        }

        // 1. Este mês, pague
        item { SectionTitle(1, "Este mês, pague") }
        item {
            val left = checklist.filter { !it.paid }.sumOf { it.amountCents }
            Text(
                when {
                    checklist.isEmpty() -> "Nada cadastrado para este mês."
                    left == 0L -> "Tudo pago este mês. Muito bem!"
                    else -> "Marque conforme for pagando. Falta: ${Money.format(left)}"
                },
                color = NxusColors.TextMuted,
            )
        }
        items(checklist, key = { it.key }) { item ->
            Row(
                Modifier.fillMaxWidth().background(NxusColors.Surface, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (item.paid) NxusColors.TextMuted else NxusColors.Text,
                        textDecoration = if (item.paid) TextDecoration.LineThrough else null,
                    )
                    Text(
                        buildList {
                            add(item.dueDate?.let { "vence ${it.format(ddmm)}" } ?: "quando o salário cair")
                            add(Money.format(item.amountCents))
                            if (item.needsValue) add("estimado")
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = NxusColors.TextMuted,
                    )
                }
                if (item.paid) {
                    OutlinedButton(onClick = { scope.launchResult({ viewModel.setPaid(item.key, month, false) }, onMessage, "Desmarcado.") }) { Text("Pago") }
                } else {
                    Button(onClick = {
                        if (item.debtId != null) confirmInvoice = item
                        else scope.launchResult({ viewModel.setPaid(item.key, month, true) }, onMessage, "${item.title}: pago.")
                    }) { Text("Paguei") }
                }
            }
        }

        // 2. Vai faltar dinheiro?
        item { SectionTitle(2, "Vai faltar dinheiro?") }
        item { CashOutlook(data, today) { onDetail(PlanoTab.PREVISAO) } }

        // 3. Quando você sai das dívidas
        item { SectionTitle(3, "Quando você sai das dívidas") }
        item { PayoffSummary(data, today, viewModel, onMessage) { onDetail(PlanoTab.CARTOES) } }

        // 4. Seus desejos
        item { SectionTitle(4, "Seus desejos") }
        item { WishesSummary(data, today) { onDetail(PlanoTab.DESEJOS) } }
    }

    confirmInvoice?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmInvoice = null },
            containerColor = NxusColors.Surface,
            title = { Text("${item.title} paga?") },
            text = { Text("A fatura de ${Money.format(item.amountCents)} sai das dívidas e o plano é recalculado.") },
            confirmButton = {
                Button(onClick = {
                    confirmInvoice = null
                    scope.launchResult({ viewModel.payInvoice(item.debtId!!, item.key, month) }, onMessage, "${item.title} paga. Plano atualizado.")
                }) { Text("Sim, paguei") }
            },
            dismissButton = { TextButton(onClick = { confirmInvoice = null }) { Text("Ainda não") } },
        )
    }
}

private val ddmm: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")

/** Calendário de hoje até o próximo salário, com os dias de aperto marcados. */
@Composable
private fun CashOutlook(data: FinanceSnapshot, today: LocalDate, onDetail: () -> Unit) {
    val card = Modifier.fillMaxWidth().background(NxusColors.Surface, RoundedCornerShape(22.dp)).padding(16.dp)
    if (data.accounts.isEmpty()) {
        Column(card) { Text("Cadastre o saldo das suas contas para ver se vai faltar.", color = NxusColors.TextMuted) }
        return
    }
    val month = YearMonth.from(today)
    val payday = data.incomes.filter { it.kind == IncomeKind.FIXA && it.dayOfMonth != null }
        .flatMap { inc -> listOf(month, month.plusMonths(1)).map { it.clampDay(inc.dayOfMonth!!) } }
        .filter { it.isAfter(today) }.minOrNull() ?: today.plusDays(30)
    val days = remember(data) { data.forecast(today).days }.filter { !it.date.isBefore(today) && !it.date.isAfter(payday) }.take(35)
    val reserve = data.settings.budget.reserveCents.coerceAtLeast(10_000)
    val negative = days.firstOrNull { it.balanceCents < 0 }
    val tight = days.filter { it.balanceCents in 0 until reserve && it.date != payday }
    val summary = when {
        negative != null -> "Falta dinheiro em ${negative.date.format(ddmm)}: ${Money.format(-negative.balanceCents)}. Segure os gastos grandes até o salário."
        tight.isNotEmpty() -> "Aperta de ${tight.first().date.format(ddmm)} a ${tight.last().date.format(ddmm)}: sobra pouco até o salário (${payday.format(ddmm)}). Evite compras grandes nesses dias."
        else -> "Tranquilo até o salário (${payday.format(ddmm)}). Os gastos do dia a dia já estão contados."
    }
    Column(card, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(summary, style = MaterialTheme.typography.bodyLarge, color = if (negative != null) NxusColors.Danger else if (tight.isNotEmpty()) NxusColors.Alert else NxusColors.Positive)
        days.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { d ->
                    val (bg, fg) = when {
                        d.date == payday -> NxusColors.Sage to NxusColors.SageDark
                        d.balanceCents < 0 -> Color(0xFFF7D9D7) to NxusColors.Danger
                        d.balanceCents < reserve -> NxusColors.Peach to NxusColors.Alert
                        d.date == today -> NxusColors.Lavender to NxusColors.AccentLight
                        else -> NxusColors.SurfaceHigh to NxusColors.Text
                    }
                    Column(Modifier.weight(1f).background(bg, RoundedCornerShape(12.dp)).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale("pt", "BR")).take(3), style = MaterialTheme.typography.labelMedium, color = fg)
                        Text("${d.date.dayOfMonth}", style = MaterialTheme.typography.titleMedium, color = fg)
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        TextButton(onClick = onDetail, contentPadding = PaddingValues(0.dp)) { Text("Ver previsão completa") }
    }
}

@Composable
private fun PayoffSummary(data: FinanceSnapshot, today: LocalDate, viewModel: PlanoViewModel, onMessage: (String) -> Unit, onDetail: () -> Unit) {
    val scope = rememberCoroutineScope()
    val month = YearMonth.from(today)
    val card = Modifier.fillMaxWidth().background(NxusColors.Lavender, RoundedCornerShape(24.dp)).padding(18.dp)
    if (data.debts.isEmpty()) {
        Column(card) { Text("Sem dívidas de cartão. Livre!", style = MaterialTheme.typography.titleLarge, color = NxusColors.AccentLight) }
        return
    }
    val plan = remember(data) { data.payoffPlan(today) }
    val currentExtra = data.settings.budget.payoffExtraMonthlyCents
    var extra by remember { mutableStateOf(0L) }
    val sim = if (extra > 0) PayoffPlanner.simulate(data.debts, data.cards, currentExtra, month, PayoffChange.ExtraPerMonth(extra)) else null
    Column(card, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("LIVRE DO CARTÃO EM", style = MaterialTheme.typography.labelMedium, color = NxusColors.AccentLight)
        when (plan) {
            is PayoffResult.Plan -> {
                val shown = sim?.newFreeMonth ?: plan.debtFreeMonth
                Text(shown?.let { PayoffPlanner.label(it).replaceFirstChar { c -> c.uppercase() } } ?: "Sem dívida", style = MaterialTheme.typography.headlineMedium)
                val goalNames = plan.goalCards.mapNotNull { id -> data.cards.firstOrNull { it.id == id }?.name }
                val text = when {
                    sim != null -> {
                        val diff = sim.monthsDiff ?: 0
                        if (diff < 0) "Com ${Money.format(extra)} a mais por mês você sai ${-diff} ${if (diff == -1) "mês" else "meses"} antes."
                        else "Com ${Money.format(extra)} a mais a data não muda; tente um valor maior."
                    }
                    goalNames.isNotEmpty() && plan.goalFreeMonth != null ->
                        "Primeiro o ${goalNames.joinToString(" e ")} zera em ${PayoffPlanner.label(plan.goalFreeMonth)} (aí é só cancelar). Depois todo o extra vai para os outros."
                    else -> plan.explanation
                }
                Text(text, color = NxusColors.Text)
            }
            is PayoffResult.NeverPaysOff -> Text(plan.message, color = NxusColors.Danger)
        }
        Text("E se eu pagar a mais por mês?", style = MaterialTheme.typography.labelLarge, color = NxusColors.AccentLight, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2_500L, 5_000L, 10_000L, 20_000L).forEach { value ->
                FilterChip(selected = extra == value, onClick = { extra = if (extra == value) 0 else value }, label = { Text("+${Money.formatShort(value)}") })
            }
        }
        if (extra > 0) {
            Button(onClick = {
                val newExtra = currentExtra + extra
                scope.launchResult({ viewModel.applyExtra(newExtra) }, onMessage, "Agora você separa ${Money.format(newExtra)} por mês para quitar.")
                extra = 0
            }) { Text("Usar no meu plano") }
        }
        TextButton(onClick = onDetail, contentPadding = PaddingValues(0.dp)) { Text("Ver ordem de quitação e gráfico") }
    }
}

@Composable
private fun WishesSummary(data: FinanceSnapshot, today: LocalDate, onDetail: () -> Unit) {
    val projections = remember(data) { data.wishProjections(today) }.filter { it.wish.status == WishStatus.JUNTANDO || it.wish.status == WishStatus.PRONTO }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (projections.isEmpty()) Text("Nenhum desejo ainda. Anote o que quer comprar e o app diz quando dá.", color = NxusColors.TextMuted)
        projections.take(3).forEach { p ->
            Column(Modifier.fillMaxWidth().background(NxusColors.Surface, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Text(p.wish.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${Money.formatShort(p.savedCents)} de ${Money.formatShort(p.wish.priceCents)}", color = NxusColors.TextMuted)
                }
                LinearProgressIndicator(
                    progress = { if (p.wish.priceCents <= 0) 0f else (p.savedCents.toFloat() / p.wish.priceCents).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = Color(0xFFF4B9A0),
                    trackColor = NxusColors.SurfaceHigh,
                    drawStopIndicator = {},
                )
                Text(
                    when {
                        p.ready -> "Já dá para comprar."
                        p.realisticDate != null -> "Dá para comprar em ${PayoffPlanner.label(YearMonth.from(p.realisticDate))}."
                        else -> p.message ?: "Sem sobra para este desejo agora."
                    },
                    style = MaterialTheme.typography.bodyMedium, color = NxusColors.TextMuted,
                )
            }
        }
        OutlinedButton(onClick = onDetail) { Text(if (projections.isEmpty()) "Adicionar desejo" else "Ver todos e adicionar") }
    }
}

@Composable
private fun CardsTab(data: FinanceSnapshot, viewModel: PlanoViewModel, onMessage: (String) -> Unit) {
    val today = LocalDate.now()
    val month = YearMonth.from(today)
    val scope = rememberCoroutineScope()
    val plan = data.payoffPlan(today)
    val currentExtra = data.settings.budget.payoffExtraMonthlyCents
    var addReais by remember { mutableFloatStateOf(0f) }
    var payNowId by remember { mutableStateOf<String?>(null) }
    val addCents = (addReais.toLong() / 10) * 1_000 // passos de R$ 10
    val change = payNowId?.let { PayoffChange.PayOffNow(it) } ?: PayoffChange.ExtraPerMonth(addCents)
    val simulating = addCents > 0 || payNowId != null
    val simulation = if (simulating) PayoffPlanner.simulate(data.debts, data.cards, currentExtra, month, change) else null

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (data.debts.isEmpty()) {
            item { AlertCard("Sem dívidas de cartão", "Cadastre seus cartões e parcelas em Mais › Cartões para montar o plano de saída.", AlertKind.INFO) }
            return@LazyColumn
        }
        when (plan) {
            is PayoffResult.Plan -> {
                item {
                    val goalNames = plan.goalCards.mapNotNull { id -> data.cards.firstOrNull { it.id == id }?.name }
                    val headline = if (goalNames.isNotEmpty() && plan.goalFreeMonth != null) {
                        "${goalNames.joinToString(" e ")} zera em ${PayoffPlanner.label(plan.goalFreeMonth)}"
                    } else {
                        plan.debtFreeMonth?.let { "Livre do cartão em ${PayoffPlanner.label(it)}" } ?: "Sem dívida"
                    }
                    Text(headline, style = MaterialTheme.typography.headlineMedium, color = NxusColors.AccentLight)
                    if (goalNames.isNotEmpty()) plan.debtFreeMonth?.let { Text("Todas as dívidas de cartão terminam em ${PayoffPlanner.label(it)}.", color = NxusColors.Text) }
                    Text(plan.explanation, color = NxusColors.TextMuted)
                }
                item {
                    DebtChart(plan.timeline.map { it.totalCents }, PayoffPlanner.label(plan.timeline.first().month), plan.debtFreeMonth?.let(PayoffPlanner::label).orEmpty())
                }
                item { SectionHeader("Ordem de quitação") }
                items(plan.order, key = { it }) { cardId ->
                    val card = data.cards.firstOrNull { it.id == cardId }
                    val balance = data.debts.filter { it.cardId == cardId }.sumOf { it.balanceCents }
                    val position = plan.order.indexOf(cardId) + 1
                    val interest = card?.monthlyInterestBp?.let { " · juros %d,%02d%% a.m.".format(it / 100, it % 100) } ?: ""
                    val limit = card?.limitCents?.let { " · disponível ${Money.format(it - data.cardUsedCents(cardId, today))}" } ?: ""
                    CompactRow(
                        "${position}º ${card?.name ?: cardId}${if (card?.cancelGoal == true) " · cancelar" else ""}",
                        "zera em ${plan.payoffMonthByCard[cardId]?.let(PayoffPlanner::label) ?: "—"}$interest$limit",
                        Money.format(balance),
                        trailingColor = NxusColors.Danger,
                    )
                }
            }
            is PayoffResult.NeverPaysOff -> item { AlertCard("A dívida não fecha assim", plan.message, AlertKind.DANGER) }
        }

        val paidOff = data.cards.filter { card -> card.status == CardStatus.ATIVO && data.debts.none { it.cardId == card.id && it.balanceCents > 0 } }
        if (paidOff.isNotEmpty()) {
            item { SectionHeader("Cartões zerados") }
            items(paidOff, key = { "off-${it.id}" }) { card ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${card.name} está zerado. Cancele no banco e marque aqui.", modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launchResult({ viewModel.cancelCard(card) }, onMessage, "${card.name} marcado como cancelado.") }) { Text("Cancelado") }
                }
            }
        }

        item { SectionHeader("Simulador") }
        item {
            Column(Modifier.fillMaxWidth().background(NxusColors.Surface, RoundedCornerShape(22.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Extra atual: ${Money.format(currentExtra)} por mês", style = MaterialTheme.typography.titleMedium)
                Text("E se eu pagar mais ${Money.format(addCents)} por mês?", color = NxusColors.TextMuted)
                Slider(
                    value = addReais,
                    onValueChange = { addReais = it; payNowId = null },
                    valueRange = 0f..2_000f,
                    colors = SliderDefaults.colors(thumbColor = NxusColors.AccentLight, activeTrackColor = NxusColors.Accent),
                )
                Text("Ou: e se eu quitar uma dívida agora?", color = NxusColors.TextMuted)
                data.debts.filter { it.balanceCents > 0 }.forEach { debt ->
                    TextButton(onClick = { payNowId = if (payNowId == debt.id) null else debt.id; addReais = 0f }) {
                        Text("${if (payNowId == debt.id) "✓ " else ""}${debt.description} (${Money.format(debt.balanceCents)})")
                    }
                }
                simulation?.let { sim ->
                    val newDate = sim.newFreeMonth?.let(PayoffPlanner::label) ?: "não termina"
                    val diff = sim.monthsDiff?.let { if (it < 0) " ($it meses)" else if (it > 0) " (+$it meses)" else " (mesma data)" } ?: ""
                    Text("Nova data: $newDate$diff", style = MaterialTheme.typography.titleMedium, color = NxusColors.Positive)
                    if (payNowId == null) {
                        val inputs = data.budgetFor(today)
                        val newWeek = BudgetCalculator.week(today, inputs.copy(settings = inputs.settings.copy(payoffExtraMonthlyCents = currentExtra + addCents)))
                        (newWeek as? WeekBudget.Ready)?.let { Text("Sua semana ficaria em ${Money.format(it.limitCents)}.", color = NxusColors.TextMuted) }
                        Button(onClick = {
                            val newExtra = currentExtra + addCents
                            scope.launchResult({ viewModel.applyExtra(newExtra) }, onMessage, "Extra de quitação agora é ${Money.format(newExtra)} por mês.")
                            addReais = 0f
                        }) { Text("Aplicar ao plano") }
                    }
                }
                if (currentExtra > 0 && !simulating) {
                    OutlinedButton(onClick = { scope.launchResult({ viewModel.applyExtra((currentExtra - 10_000).coerceAtLeast(0)) }, onMessage, "Extra reduzido em R$ 100,00.") }) { Text("Diminuir R$ 100") }
                }
            }
        }
    }
}
