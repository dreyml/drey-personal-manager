package com.nxuslab.dreymanager.feature.common

import com.nxuslab.dreymanager.data.repo.FinanceSnapshot
import com.nxuslab.dreymanager.domain.budget.BudgetCalculator
import com.nxuslab.dreymanager.domain.budget.WeekBudget
import com.nxuslab.dreymanager.domain.forecast.CashEvent
import com.nxuslab.dreymanager.domain.forecast.CashForecaster
import com.nxuslab.dreymanager.domain.forecast.Forecast
import com.nxuslab.dreymanager.domain.forecast.ForecastInputs
import com.nxuslab.dreymanager.domain.model.BudgetInputs
import com.nxuslab.dreymanager.domain.model.clampDay
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
    val inputs = budgetFor(today)
    // Cada mês gasta o que o próprio orçamento deixa para as semanas (meses com fatura grande gastam menos).
    val byMonth = (0L..3L).map { YearMonth.from(today).plusMonths(it) }.associateWith { month ->
        BudgetCalculator.monthPlan(month, inputs).availableForWeeksCents.coerceAtLeast(0) / month.lengthOfMonth()
    }
    return ForecastInputs(
        dailySpendByMonth = byMonth,
        openingBalanceCents = openingBalanceCents,
        incomes = incomes,
        fixedBills = fixedBills,
        debts = debts,
        cards = cards,
        expenses = expenses,
        dailySpendCents = (week?.suggestedLimitCents ?: 0) / 7,
        payoffExtraMonthlyCents = settings.budget.payoffExtraMonthlyCents,
        wishesMonthlyCents = highWishMonthlyCents(today),
        variableBills = variableBills,
        variableActuals = variableActuals,
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

/** Quanto do limite do cartão está comprometido: dívidas cadastradas + compras reservadas em faturas futuras. */
fun FinanceSnapshot.cardUsedCents(cardId: String, today: LocalDate): Long {
    val month = YearMonth.from(today)
    val reserved = expenses.filter { it.cardId == cardId && it.reservedForInvoice != null && !it.reservedForInvoice.isBefore(month) }.sumOf { it.amountCents }
    return debts.filter { it.cardId == cardId }.sumOf { it.balanceCents } + reserved
}

data class AccountBalance(val account: com.nxuslab.dreymanager.data.repo.Account, val currentCents: Long)

/** Carteira primeiro, depois os bancos, com o saldo já ajustado pelos lançamentos. */
fun FinanceSnapshot.accountBalances(): List<AccountBalance> =
    accounts.sortedWith(compareBy({ it.kind != com.nxuslab.dreymanager.domain.model.AccountKind.DINHEIRO }, { it.name }))
        .map { AccountBalance(it, currentBalance(it)) }

/**
 * O "livre de verdade": o menor entre o que resta do limite da semana e o dinheiro que sobra nas
 * contas até o próximo salário, depois das contas e faturas que vencem antes dele.
 */
data class RealFree(
    val freeCents: Long,
    val weekRemainingCents: Long,
    /** Dinheiro que sobra até o salário; nulo sem contas cadastradas. */
    val cashUntilPaydayCents: Long?,
    val nextPayday: LocalDate?,
    val limitedByCash: Boolean,
    /** O salário já caiu depois do último saldo informado: os saldos precisam ser conferidos. */
    val staleBalances: Boolean,
)

fun FinanceSnapshot.realFree(today: LocalDate, week: WeekBudget.Ready): RealFree {
    val notCapped = RealFree(week.remainingCents, week.remainingCents, null, null, limitedByCash = false, staleBalances = false)
    if (accounts.isEmpty()) return notCapped
    val paydays = incomes.filter { it.kind == com.nxuslab.dreymanager.domain.model.IncomeKind.FIXA && it.dayOfMonth != null }.map { it.dayOfMonth!! }
    val month = YearMonth.from(today)
    val candidates = paydays.flatMap { day -> listOf(month.minusMonths(1), month, month.plusMonths(1)).map { it.clampDay(day) } }
    val lastPayday = candidates.filter { !it.isAfter(today) }.maxOrNull()
    val nextPayday = candidates.filter { it.isAfter(today) }.minOrNull()
    val lastUpdate = accounts.maxOf { it.updatedAt }
    if (lastPayday != null && lastUpdate.isBefore(lastPayday)) return notCapped.copy(nextPayday = nextPayday, staleBalances = true)

    val until = nextPayday ?: today.plusDays(30)
    val days = java.time.temporal.ChronoUnit.DAYS.between(today, until).toInt().coerceAtLeast(1)
    val outflows = CashForecaster.events(today, days, forecastInputs(today).copy(payoffExtraMonthlyCents = 0, wishesMonthlyCents = 0))
        .filter { it.amountCents < 0 && it.date.isBefore(until) }
        .sumOf { -it.amountCents }
    val cash = openingBalanceCents - outflows
    return RealFree(
        freeCents = minOf(week.remainingCents, cash),
        weekRemainingCents = week.remainingCents,
        cashUntilPaydayCents = cash,
        nextPayday = nextPayday,
        limitedByCash = cash < week.remainingCents,
        staleBalances = false,
    )
}

data class DayGroup(val date: LocalDate, val expenses: List<com.nxuslab.dreymanager.domain.model.Expense>) {
    val totalCents: Long get() = expenses.sumOf { it.amountCents }
}

/** Gastos da semana que contém [day], agrupados por dia (mais recente primeiro), respeitando o início da semana. */
fun FinanceSnapshot.weekGroups(day: LocalDate): Pair<LocalDate, List<DayGroup>> {
    val start = BudgetCalculator.weekStartOf(day, settings.budget.weekStart)
    val end = start.plusDays(6)
    val groups = expenses
        .filter { !it.date.isBefore(start) && !it.date.isAfter(end) }
        .groupBy { it.date }
        .map { (date, list) -> DayGroup(date, list) }
        .sortedByDescending { it.date }
    return start to groups
}
