package com.nxuslab.dreymanager.feature.home

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.nxuslab.dreymanager.core.ui.BigRing
import com.nxuslab.dreymanager.core.ui.CompactRow
import com.nxuslab.dreymanager.core.ui.EnvelopeBar
import com.nxuslab.dreymanager.core.ui.SectionHeader
import com.nxuslab.dreymanager.core.ui.br
import com.nxuslab.dreymanager.domain.budget.WeekBudget
import com.nxuslab.dreymanager.domain.forecast.Shortfall
import com.nxuslab.dreymanager.domain.money.Money
import com.nxuslab.dreymanager.domain.payoff.PayoffPlanner
import com.nxuslab.dreymanager.domain.payoff.PayoffResult
import com.nxuslab.dreymanager.feature.common.EnvelopeStatus
import com.nxuslab.dreymanager.feature.common.UpcomingItem
import com.nxuslab.dreymanager.feature.common.debtTotalCents
import com.nxuslab.dreymanager.feature.common.envelopeStatus
import com.nxuslab.dreymanager.feature.common.forecast
import com.nxuslab.dreymanager.feature.common.payoffPlan
import com.nxuslab.dreymanager.feature.common.upcoming
import com.nxuslab.dreymanager.feature.common.week
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.LocalTime

data class HomeState(
    val loading: Boolean = true,
    val week: WeekBudget? = null,
    val envelopes: List<EnvelopeStatus> = emptyList(),
    val shortfall: Shortfall? = null,
    val payoff: PayoffResult? = null,
    val debtTotalCents: Long = 0,
    val upcoming: List<UpcomingItem> = emptyList(),
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as NxusApp).repository

    val state: StateFlow<HomeState> = repository.snapshot.map { snapshot ->
        val today = LocalDate.now()
        HomeState(
            loading = false,
            week = snapshot.week(today),
            envelopes = snapshot.envelopeStatus(today),
            // Sem saldo cadastrado a previsão parte de zero e acusaria falta sem motivo.
            shortfall = if (snapshot.accounts.isEmpty()) null else snapshot.forecast(today).shortfalls.firstOrNull(),
            payoff = snapshot.payoffPlan(today),
            debtTotalCents = snapshot.debtTotalCents(),
            upcoming = snapshot.upcoming(today),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())
}

@Composable
fun HomeScreen(onOpenPlan: () -> Unit, onOpenForecast: () -> Unit, onOpenMore: () -> Unit, viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                Text("NxusLife", style = MaterialTheme.typography.labelMedium, color = NxusColors.AccentLight)
                Text(greeting(), style = MaterialTheme.typography.headlineMedium)
            }
        }

        item {
            when (val week = state.week) {
                is WeekBudget.Ready -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    BigRing(
                        remainingCents = week.remainingCents,
                        limitCents = week.limitCents,
                        caption = if (week.remainingCents >= 0) "livre esta semana" else "acima do limite",
                        footer = "${week.daysLeft} ${if (week.daysLeft == 1) "dia" else "dias"} · ~${Money.formatShort(week.perDayCents)}/dia",
                    )
                    if (week.carriedOverExcessCents > 0) {
                        AlertCard(
                            "Semana passada estourou",
                            "${Money.format(week.carriedOverExcessCents)} já foram descontados desta semana.",
                            AlertKind.WARNING,
                            Modifier.padding(top = 12.dp),
                        )
                    }
                }
                is WeekBudget.Incomplete -> AlertCard("Falta pouco", week.reason, AlertKind.INFO, actionLabel = "Completar", onAction = onOpenMore)
                null -> Unit
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
            item { PayoffCard(state.debtTotalCents, state.payoff, onOpenPlan) }
        }

        if (state.upcoming.isNotEmpty()) {
            item { SectionHeader("Vence em breve") }
            items(state.upcoming, key = { "${it.date}-${it.title}" }) { item ->
                CompactRow(item.title, relativeDay(item.date), Money.format(item.amountCents))
            }
        }
    }
}

@Composable
private fun PayoffCard(debtTotalCents: Long, payoff: PayoffResult?, onOpenPlan: () -> Unit) {
    val subtitle = when (payoff) {
        is PayoffResult.Plan -> payoff.debtFreeMonth?.let { "cartão zera em ${PayoffPlanner.label(it)}" } ?: ""
        is PayoffResult.NeverPaysOff -> "com o extra atual a dívida não termina"
        null -> ""
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(NxusColors.Surface, RoundedCornerShape(16.dp))
            .clickable(onClick = onOpenPlan)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Faltam ${Money.formatShort(debtTotalCents)}", style = MaterialTheme.typography.titleLarge, color = NxusColors.AccentLight)
            Text(subtitle, color = if (payoff is PayoffResult.NeverPaysOff) NxusColors.Danger else NxusColors.TextMuted)
        }
        Text("Ver plano", color = NxusColors.AccentLight, style = MaterialTheme.typography.labelLarge)
    }
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
