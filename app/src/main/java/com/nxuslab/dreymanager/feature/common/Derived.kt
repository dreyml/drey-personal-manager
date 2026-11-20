package com.nxuslab.dreymanager.feature.common

import com.nxuslab.dreymanager.data.repo.FinanceSnapshot
import com.nxuslab.dreymanager.domain.budget.BudgetCalculator
import com.nxuslab.dreymanager.domain.budget.WeekBudget
import com.nxuslab.dreymanager.domain.forecast.CashEvent
import com.nxuslab.dreymanager.domain.forecast.CashForecaster
import com.nxuslab.dreymanager.domain.forecast.Forecast
import com.nxuslab.dreymanager.domain.forecast.ForecastInputs
import com.nxuslab.dreymanager.domain.model.BudgetInputs
import com.nxuslab.dreymanager.domain.payoff.PayoffPlanner
import com.nxuslab.dreymanager.domain.payoff.PayoffResult
import com.nxuslab.dreymanager.domain.wish.WishPlanner
import com.nxuslab.dreymanager.domain.wish.WishProjection
import java.time.LocalDate
import java.time.YearMonth

/*
 * Cálculos derivados do snapshot, usados por várias telas. Kotlin puro: testável sem aparelho.
 */

data class EnvelopeStatus(val categoryId: String, val name: String, val emoji: String, val spentCents: Long, val limitCents: Long)

data class UpcomingItem(val date: LocalDate, val title: String, val amountCents: Long)

/** Quanto sobrou, por mês, das semanas recentes (média das últimas 4 semanas completas). */
fun FinanceSnapshot.monthlyLeftoverCents(today: LocalDate): Long {
    val inputs = budgetFor(today)
    if (inputs.incomes.isEmpty()) return 0
    val thisWeek = BudgetCalculator.weekStartOf(today, settings.budget.weekStart)
    val unspent = (1L..4L).sumOf { back ->
        val week = BudgetCalculator.week(thisWeek.minusWeeks(back), inputs) as? WeekBudget.Ready ?: return@sumOf 0L
        week.remainingCents.coerceAtLeast(0)
    }
    return unspent / 4 * 52 / 12
}

fun FinanceSnapshot.wishProjections(today: LocalDate): List<WishProjection> =
    WishPlanner.plan(wishes, savedByWish, today, monthlyLeftoverCents(today))

/** Desejos de prioridade alta não dependem da sobra, então a sobra pode ser zero aqui. */
private fun FinanceSnapshot.highWishMonthlyCents(today: LocalDate): Long =
    WishPlanner.highPriorityMonthlyCents(WishPlanner.plan(wishes, savedByWish, today, 0))

/** Entradas do orçamento já com o valor reservado para desejos de prioridade alta. */
fun FinanceSnapshot.budgetFor(today: LocalDate): BudgetInputs =
    budgetInputs(highPriorityWishMonthlyCents = highWishMonthlyCents(today))

fun FinanceSnapshot.week(today: LocalDate): WeekBudget = BudgetCalculator.week(today, budgetFor(today))

fun FinanceSnapshot.envelopeStatus(today: LocalDate): List<EnvelopeStatus> {
    val month = YearMonth.from(today)
    val byId = categories.associateBy { it.id }
    return envelopes.mapNotNull { envelope ->
        val category = byId[envelope.categoryId] ?: return@mapNotNull null
        EnvelopeStatus(category.id, category.name, category.emoji, BudgetCalculator.envelopeSpent(month, category.id, expenses), envelope.monthlyLimitCents)
    }.sortedByDescending { if (it.limitCents == 0L) 0.0 else it.spentCents.toDouble() / it.limitCents }
}

fun FinanceSnapshot.forecastInputs(today: LocalDate): ForecastInputs {
    val week = week(today) as? WeekBudget.Ready
    return ForecastInputs(
        openingBalanceCents = openingBalanceCents,
        incomes = incomes,
        fixedBills = fixedBills,
        debts = debts,
        cards = cards,
        expenses = expenses,
        dailySpendCents = (week?.suggestedLimitCents ?: 0) / 7,
        payoffExtraMonthlyCents = settings.budget.payoffExtraMonthlyCents,
        wishesMonthlyCents = highWishMonthlyCents(today),
    )
}

fun FinanceSnapshot.forecast(today: LocalDate, whatIf: List<CashEvent> = emptyList()): Forecast =
    CashForecaster.forecast(today, 90, forecastInputs(today), whatIf)

fun FinanceSnapshot.payoffPlan(today: LocalDate): PayoffResult =
    PayoffPlanner.plan(debts, cards, settings.budget.payoffExtraMonthlyCents, YearMonth.from(today))

/** Saídas dos próximos [days] dias: contas, faturas e parcelas (sem salário nem gasto do dia a dia). */
fun FinanceSnapshot.upcoming(today: LocalDate, days: Int = 7): List<UpcomingItem> =
    CashForecaster.events(today, days + 1, forecastInputs(today).copy(payoffExtraMonthlyCents = 0, wishesMonthlyCents = 0))
        .filter { it.amountCents < 0 }
        .map { UpcomingItem(it.date, it.reason, -it.amountCents) }

fun FinanceSnapshot.debtTotalCents(): Long = debts.sumOf { it.balanceCents }
