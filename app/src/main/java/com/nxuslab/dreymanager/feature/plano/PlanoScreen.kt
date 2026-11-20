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
import com.nxuslab.dreymanager.feature.common.payoffPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

class PlanoViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as NxusApp).repository
    val snapshot: StateFlow<FinanceSnapshot?> = repository.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

enum class PlanoTab(val label: String) { CARTOES("Cartões"), PREVISAO("Previsão"), DESEJOS("Desejos") }

@Composable
fun PlanoScreen(initialTab: PlanoTab, onMessage: (String) -> Unit, viewModel: PlanoViewModel = viewModel()) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    var tab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = NxusColors.Background, contentColor = NxusColors.AccentLight) {
            PlanoTab.entries.forEach { t -> Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) }) }
        }
        val data = snapshot ?: return@Column
        when (tab) {
            PlanoTab.CARTOES -> CardsTab(data, viewModel, onMessage)
            PlanoTab.PREVISAO -> ForecastTab(data)
            PlanoTab.DESEJOS -> WishesTab(data, viewModel, onMessage)
        }
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
                    Text(plan.debtFreeMonth?.let { "Livre do cartão em ${PayoffPlanner.label(it)}" } ?: "Sem dívida", style = MaterialTheme.typography.headlineMedium, color = NxusColors.AccentLight)
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
                    CompactRow(
                        "${position}º ${card?.name ?: cardId}",
                        "zera em ${plan.payoffMonthByCard[cardId]?.let(PayoffPlanner::label) ?: "—"}$interest",
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
            Column(Modifier.fillMaxWidth().background(NxusColors.Surface, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
