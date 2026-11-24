package com.nxuslab.dreymanager.feature.financas

import com.nxuslab.dreymanager.data.repo.FinanceSnapshot
import com.nxuslab.dreymanager.domain.advice.NextStep
import com.nxuslab.dreymanager.domain.advice.NextStepAdvisor
import com.nxuslab.dreymanager.domain.advice.Shortfall
import com.nxuslab.dreymanager.domain.budget.WeekBudget
import com.nxuslab.dreymanager.domain.daily.DailyAllowance
import com.nxuslab.dreymanager.domain.daily.DailyView
import com.nxuslab.dreymanager.domain.model.AccountKind
import com.nxuslab.dreymanager.domain.plan.MonthChecklist
import com.nxuslab.dreymanager.feature.common.UpcomingItem
import com.nxuslab.dreymanager.feature.common.forecast
import com.nxuslab.dreymanager.feature.common.payoffPlan
import com.nxuslab.dreymanager.feature.common.realFree
import com.nxuslab.dreymanager.feature.common.upcoming
import com.nxuslab.dreymanager.feature.common.week
import java.time.LocalDate
import java.time.YearMonth

/** Tudo o que a Início das finanças (e o resumo do hub) mostra, calculado de uma vez. */
data class FinanceOverview(
    val daily: DailyView?,
    /** Motivo quando o valor do dia não dá para calcular (ex.: falta a renda). */
    val incompleteReason: String?,
    val nextStep: NextStep,
    val wallet: List<WalletItem>,
    val totalCents: Long,
    val upcoming: List<UpcomingItem>,
)

fun FinanceSnapshot.financeOverview(today: LocalDate, syncFailed: Boolean): FinanceOverview {
    val week = week(today)
    val ready = week as? WeekBudget.Ready
    val rf = ready?.let { realFree(today, it) }
    val daily = ready?.let { w ->
        // Com pouco dinheiro até o salário, a semana encolhe para o que existe de verdade.
        val limit = if (rf?.limitedByCash == true) w.spentCents + rf.freeCents.coerceAtLeast(0) else w.limitCents
        val spentByDay = expenses.filter { !it.date.isBefore(w.start) && !it.date.isAfter(w.end) }
            .groupBy { it.date }.mapValues { (_, list) -> list.sumOf { it.amountCents } }
        DailyAllowance.compute(today, w.start, limit, spentByDay)
    }
    val month = YearMonth.from(today)
    val cardNames = cards.associate { it.id to it.name }
    val checklist = listOf(month, month.plusMonths(1)).flatMap { m ->
        MonthChecklist.build(m, fixedBills, variableBills, variableActuals, debts, cardNames, 0, paidItems)
    }
    val shortfall = if (accounts.isEmpty()) null else forecast(today).shortfalls.firstOrNull()?.let { Shortfall(it.date, it.cents) }
    val stale = if (rf?.staleBalances == true) accounts.firstOrNull { it.kind == AccountKind.BANCO }?.name ?: "suas contas" else null
    val step = NextStepAdvisor.advise(today, shortfall, checklist, stale, syncFailed, daily, cards.firstOrNull { it.cancelGoal }?.name)
    return FinanceOverview(
        daily = daily,
        incompleteReason = (week as? WeekBudget.Incomplete)?.reason,
        nextStep = step,
        wallet = walletItems(today, payoffPlan(today)),
        totalCents = openingBalanceCents,
        upcoming = upcoming(today),
    )
}
