package com.nxuslab.dreymanager.feature.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.Color
import com.nxuslab.dreymanager.data.sync.BankSync
import com.nxuslab.dreymanager.feature.mais.syncedLabel
import androidx.compose.ui.platform.LocalContext
import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.nxuslab.dreymanager.core.ui.AlertCard
import com.nxuslab.dreymanager.core.ui.AlertKind
import com.nxuslab.dreymanager.core.ui.BigRing
import com.nxuslab.dreymanager.core.ui.CompactRow
import com.nxuslab.dreymanager.core.ui.EnvelopeBar
import com.nxuslab.dreymanager.core.ui.MoneyField
import com.nxuslab.dreymanager.core.ui.SectionHeader
import com.nxuslab.dreymanager.core.ui.br
import com.nxuslab.dreymanager.data.repo.Account
import com.nxuslab.dreymanager.domain.budget.WeekBudget
import com.nxuslab.dreymanager.domain.forecast.Shortfall
import com.nxuslab.dreymanager.domain.model.AccountKind
import com.nxuslab.dreymanager.domain.money.Money
import com.nxuslab.dreymanager.domain.payoff.PayoffPlanner
import com.nxuslab.dreymanager.domain.payoff.PayoffResult
import com.nxuslab.dreymanager.feature.common.AccountBalance
import com.nxuslab.dreymanager.feature.common.EnvelopeStatus
import com.nxuslab.dreymanager.feature.common.RealFree
import com.nxuslab.dreymanager.feature.common.UpcomingItem
import com.nxuslab.dreymanager.feature.common.accountBalances
import com.nxuslab.dreymanager.feature.common.debtTotalCents
import com.nxuslab.dreymanager.feature.common.envelopeStatus
import com.nxuslab.dreymanager.feature.common.forecast
import com.nxuslab.dreymanager.feature.common.payoffPlan
import com.nxuslab.dreymanager.feature.common.realFree
import com.nxuslab.dreymanager.feature.common.upcoming
import com.nxuslab.dreymanager.feature.common.week
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

data class HomeState(
    val loading: Boolean = true,
    val week: WeekBudget? = null,
    val realFree: RealFree? = null,
    val balances: List<AccountBalance> = emptyList(),
    val envelopes: List<EnvelopeStatus> = emptyList(),
    val shortfall: Shortfall? = null,
    val payoff: PayoffResult? = null,
    val goalNames: List<String> = emptyList(),
    val debtTotalCents: Long = 0,
    val upcoming: List<UpcomingItem> = emptyList(),
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as NxusApp).repository

    val state: StateFlow<HomeState> = repository.snapshot.map { snapshot ->
        val today = LocalDate.now()
        val week = snapshot.week(today)
        val payoff = snapshot.payoffPlan(today)
        HomeState(
            loading = false,
            week = week,
            realFree = (week as? WeekBudget.Ready)?.let { snapshot.realFree(today, it) },
            balances = snapshot.accountBalances(),
            envelopes = snapshot.envelopeStatus(today),
            // Sem saldo cadastrado a previsão parte de zero e acusaria falta sem motivo.
            shortfall = if (snapshot.accounts.isEmpty()) null else snapshot.forecast(today).shortfalls.firstOrNull(),
            payoff = payoff,
            goalNames = (payoff as? PayoffResult.Plan)?.goalCards.orEmpty().mapNotNull { id -> snapshot.cards.firstOrNull { it.id == id }?.name },
            debtTotalCents = snapshot.debtTotalCents(),
            upcoming = snapshot.upcoming(today),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    /** Atualiza o saldo de uma conta (ou cria a carteira) a partir da tela inicial. */
    fun saveBalance(account: Account, cents: Long, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch { onResult(repository.saveAccount(account.copy(balanceCents = cents))) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(onOpenPlan: () -> Unit, onOpenForecast: () -> Unit, onOpenMore: () -> Unit, onMessage: (String) -> Unit = {}, viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Account?>(null) }
    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val context = LocalContext.current
    val bankStatus by BankSync.status.collectAsStateWithLifecycle()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                Text(greeting(), style = MaterialTheme.typography.bodyMedium, color = NxusColors.TextMuted)
                Text(todayLabel(), style = MaterialTheme.typography.headlineMedium)
            }
        }

        item {
            when (val week = state.week) {
                is WeekBudget.Ready -> Column(
                    Modifier.fillMaxWidth().background(NxusColors.Surface, RoundedCornerShape(28.dp)).padding(vertical = 20.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val free = state.realFree?.freeCents ?: week.remainingCents
                    BigRing(
                        remainingCents = free,
                        limitCents = week.limitCents,
                        caption = if (free >= 0) "livre esta semana" else "acima do que dá",
                        footer = "${week.daysLeft} ${if (week.daysLeft == 1) "dia" else "dias"} · ~${Money.formatShort(free.coerceAtLeast(0) / week.daysLeft)}/dia",
                        size = 220.dp,
                    )
                    state.realFree?.takeIf { it.limitedByCash }?.let { rf ->
                        Text(
                            "Seu limite da semana é ${Money.format(rf.weekRemainingCents)}, mas até o salário${rf.nextPayday?.let { " (${it.br().take(5)})" } ?: ""} sobram ${Money.format(rf.cashUntilPaydayCents ?: 0)} nas contas depois das contas a pagar.",
                            color = NxusColors.Alert,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (week.carriedOverExcessCents > 0) {
                        AlertCard("Semana passada estourou", "${Money.format(week.carriedOverExcessCents)} já foram descontados desta semana.", AlertKind.WARNING, Modifier.padding(top = 12.dp))
                    }
                }
                is WeekBudget.Incomplete -> AlertCard("Falta pouco", week.reason, AlertKind.INFO, actionLabel = "Completar", onAction = onOpenMore)
                null -> Unit
            }
        }

        if (state.realFree?.staleBalances == true) {
            item { AlertCard("O salário caiu?", "Confira os saldos abaixo: o app não sabe em qual conta ele entrou. Toque numa conta para corrigir.", AlertKind.INFO) }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Seu dinheiro", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (bankStatus.configured) {
                        Text(
                            if (bankStatus.running) "Sincronizando…" else "${syncedLabel(bankStatus)} · atualizar",
                            color = if (bankStatus.lastFailed) NxusColors.Alert else NxusColors.TextMuted,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.clickable(enabled = !bankStatus.running) { BankSync.requestNow(context) }.padding(vertical = 6.dp),
                        )
                    }
                }
                val tiles = buildList {
                    state.balances.forEach { b -> add(Triple(b.account, b.currentCents, false)) }
                    if (state.balances.none { it.account.kind == AccountKind.DINHEIRO }) {
                        add(Triple(Account(UUID.randomUUID().toString(), "Carteira", 0, LocalDate.now(), AccountKind.DINHEIRO), 0L, true))
                    }
                }
                tiles.chunked(2).forEachIndexed { row, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEachIndexed { col, (account, cents, isNew) ->
                            val (fill, ink) = tileColors(account.kind, row * 2 + col)
                            BalanceTile(
                                name = account.name,
                                value = if (isNew) "adicionar" else Money.format(cents),
                                negative = cents < 0,
                                fill = fill,
                                ink = ink,
                                modifier = Modifier.weight(1f),
                            ) { editing = account }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        state.shortfall?.let { shortfall ->
            item {
                AlertCard(
                    "Vai faltar dinheiro",
                    "Em ${shortfall.date.br()} faltam ${Money.format(shortfall.cents)} para ${shortfall.reason.replaceFirstChar { it.lowercase() }}.",
                    AlertKind.DANGER,
                    actionLabel = "Ver previsão",
                    onAction = onOpenForecast,
                )
            }
        }

        if (state.envelopes.isNotEmpty()) {
            item { SectionHeader("Envelopes do mês") }
            items(state.envelopes, key = { it.categoryId }) { EnvelopeBar(it.emoji, it.name, it.spentCents, it.limitCents) }
        }

        if (state.debtTotalCents > 0) {
            item { SectionHeader("Saída do cartão") }
            item { PayoffCard(state.debtTotalCents, state.payoff, state.goalNames, onOpenPlan) }
        }

        if (state.upcoming.isNotEmpty()) {
            item { SectionHeader("Vence em breve") }
            items(state.upcoming, key = { "${it.date}-${it.title}" }) { item ->
                CompactRow(item.title, relativeDay(item.date), Money.format(item.amountCents))
            }
        }
    }

    editing?.let { account ->
        var text by remember(account.id) { mutableStateOf("") }
        var cents by remember(account.id) { mutableStateOf<Long?>(null) }
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = NxusColors.Surface,
            title = { Text(if (account.kind == AccountKind.DINHEIRO) "Dinheiro na carteira" else "Saldo de ${account.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Quanto tem agora? Os próximos gastos e entradas lançados nesta conta ajustam o saldo sozinhos.", color = NxusColors.TextMuted)
                    MoneyField(text, { text = it }, "Saldo de agora", onValid = { cents = it })
                    TextButton(onClick = { text = ""; cents = 0 }) { Text("Está zerado") }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val value = cents ?: return@Button
                    editing = null
                    viewModel.saveBalance(account, value) { r -> onMessage(r.fold({ "Saldo atualizado." }, { it.message ?: "Erro ao salvar." })) }
                }, enabled = cents != null) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancelar") } },
        )
    }
}

/** Carteira em pêssego; bancos alternam céu, lavanda, sálvia e branco. */
private fun tileColors(kind: AccountKind, index: Int): Pair<Color, Color> = when {
    kind == AccountKind.DINHEIRO -> NxusColors.Peach to NxusColors.Alert
    else -> listOf(
        NxusColors.Sky to NxusColors.SkyDark,
        NxusColors.Lavender to NxusColors.AccentLight,
        NxusColors.Sage to NxusColors.SageDark,
        NxusColors.Surface to NxusColors.TextMuted,
    )[index % 4]
}

@Composable
private fun BalanceTile(name: String, value: String, negative: Boolean, fill: Color, ink: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.background(fill, RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(name, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleMedium, color = if (negative) NxusColors.Danger else NxusColors.Text)
    }
}

@Composable
private fun PayoffCard(debtTotalCents: Long, payoff: PayoffResult?, goalNames: List<String>, onOpenPlan: () -> Unit) {
    val (title, subtitle) = when (payoff) {
        is PayoffResult.Plan -> if (goalNames.isNotEmpty() && payoff.goalFreeMonth != null) {
            "${goalNames.joinToString(" e ")} zera em ${PayoffPlanner.label(payoff.goalFreeMonth)}" to "depois é só cancelar · ${Money.formatShort(debtTotalCents)} em dívidas de cartão"
        } else {
            "Faltam ${Money.formatShort(debtTotalCents)}" to (payoff.debtFreeMonth?.let { "cartão zera em ${PayoffPlanner.label(it)}" } ?: "")
        }
        is PayoffResult.NeverPaysOff -> "Faltam ${Money.formatShort(debtTotalCents)}" to "com o extra atual a dívida não termina"
        null -> "Faltam ${Money.formatShort(debtTotalCents)}" to ""
    }
    Row(
        Modifier.fillMaxWidth().background(NxusColors.Lavender, RoundedCornerShape(24.dp)).clickable(onClick = onOpenPlan).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = NxusColors.AccentLight)
            Text(subtitle, color = if (payoff is PayoffResult.NeverPaysOff) NxusColors.Danger else NxusColors.Text)
        }
        Text("Ver plano", color = NxusColors.AccentLight, style = MaterialTheme.typography.labelLarge)
    }
}

private fun todayLabel(): String {
    val d = LocalDate.now()
    val ptBr = java.util.Locale("pt", "BR")
    val day = d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, ptBr).substringBefore('-').replaceFirstChar { it.uppercase() }
    val month = d.month.getDisplayName(java.time.format.TextStyle.SHORT, ptBr).trimEnd('.')
    return "$day, ${d.dayOfMonth} $month"
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Bom dia"
    in 12..17 -> "Boa tarde"
    else -> "Boa noite"
}

private fun relativeDay(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "hoje"
        today.plusDays(1) -> "amanhã"
        else -> "em ${java.time.temporal.ChronoUnit.DAYS.between(today, date)} dias · ${date.br()}"
    }
}
