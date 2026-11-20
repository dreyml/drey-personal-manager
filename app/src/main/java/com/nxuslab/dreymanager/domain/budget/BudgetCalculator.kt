package com.nxuslab.dreymanager.domain.budget

import com.nxuslab.dreymanager.domain.model.BudgetInputs
import com.nxuslab.dreymanager.domain.model.CardDebt
import com.nxuslab.dreymanager.domain.model.DebtKind
import com.nxuslab.dreymanager.domain.model.Expense
import com.nxuslab.dreymanager.domain.model.IncomeKind
import com.nxuslab.dreymanager.domain.model.PaymentMethod
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Alocação do dinheiro de um mês, na ordem da especificação (§6.2). */
data class MonthPlan(
    val month: YearMonth,
    val incomeCents: Long,
    val fixedBillsCents: Long,
    val cardObligationsCents: Long,
    val payoffCents: Long,
    val highWishesCents: Long,
    val reserveCents: Long,
) {
    /** O que sobra para as semanas. Pode ser negativo quando o mês não fecha. */
    val availableForWeeksCents: Long
        get() = incomeCents - fixedBillsCents - cardObligationsCents - payoffCents - highWishesCents - reserveCents
}

sealed interface WeekBudget {
    data class Incomplete(val reason: String) : WeekBudget

    data class Ready(
        val start: LocalDate,
        val end: LocalDate,
        /** Limite calculado a partir do orçamento, antes de ajustes. */
        val suggestedLimitCents: Long,
        /** Limite efetivo: menor entre sugerido e ajuste do usuário, menos o excesso da semana anterior. */
        val limitCents: Long,
        val carriedOverExcessCents: Long,
        val spentCents: Long,
        val remainingCents: Long,
        /** Dias da semana que faltam, contando hoje. */
        val daysLeft: Int,
        /** Quanto dá para gastar por dia no resto da semana (nunca negativo). */
        val perDayCents: Long,
        val overrideApplied: Boolean,
    ) : WeekBudget
}

sealed interface OverrideCheck {
    data object Ok : OverrideCheck
    data class TooHigh(val maxCents: Long) : OverrideCheck
}

object BudgetCalculator {
    /** Quantas semanas para trás o excesso é acompanhado ao calcular a semana atual. */
    private const val CARRY_LOOKBACK_WEEKS = 8L

    fun monthPlan(month: YearMonth, inputs: BudgetInputs): MonthPlan {
        val fixedIncome = inputs.incomes.filter { it.kind == IncomeKind.FIXA }.sumOf { it.amountCents }
        val extrasInMonth = inputs.incomes.filter { it.kind == IncomeKind.EXTRA && it.date != null && YearMonth.from(it.date) == month }
        return MonthPlan(
            month = month,
            incomeCents = fixedIncome + extrasInMonth.sumOf { it.amountCents },
            fixedBillsCents = inputs.fixedBills.filter { it.active }.sumOf { it.amountCents },
            cardObligationsCents = cardObligations(month, inputs.debts, inputs.expenses),
            payoffCents = inputs.settings.payoffExtraMonthlyCents + extrasInMonth.sumOf { it.toDebtCents },
            highWishesCents = inputs.highPriorityWishMonthlyCents,
            reserveCents = inputs.settings.reserveCents,
        )
    }

    /**
     * Pagamentos obrigatórios de cartão no mês: faturas que vencem nele, parcelas em andamento e
     * compras emergenciais reservadas para a fatura daquele mês. Rotativo não tem parcela fixa:
     * é quitado com o valor extra de quitação.
     */
    fun cardObligations(month: YearMonth, debts: List<CardDebt>, expenses: List<Expense>): Long {
        val fromDebts = debts.sumOf { debt ->
            val first = YearMonth.from(debt.nextDueDate)
            when (debt.kind) {
                DebtKind.FATURA_ABERTA -> if (first == month) debt.amountCents else 0L
                DebtKind.PARCELA, DebtKind.PARCELAMENTO_FATURA -> {
                    val remaining = debt.remainingInstallments ?: 0
                    val last = first.plusMonths((remaining - 1).toLong())
                    if (remaining > 0 && !month.isBefore(first) && !month.isAfter(last)) debt.amountCents else 0L
                }
                DebtKind.ROTATIVO -> 0L
            }
        }
        val reserved = expenses.filter { it.method == PaymentMethod.CARTAO && it.reservedForInvoice == month }.sumOf { it.amountCents }
        return fromDebts + reserved
    }

    fun weekStartOf(date: LocalDate, weekStart: DayOfWeek): LocalDate = date.with(TemporalAdjusters.previousOrSame(weekStart))

    /**
     * Limite sugerido para a semana que começa em [start]: cada dia recebe a fatia diária do mês a
     * que pertence, então semanas que cruzam meses ficam proporcionais.
     */
    fun suggestedWeeklyLimit(start: LocalDate, inputs: BudgetInputs): Long {
        val end = start.plusDays(6)
        val months = generateSequence(YearMonth.from(start)) { it.plusMonths(1) }.takeWhile { !it.isAfter(YearMonth.from(end)) }
        return months.sumOf { month ->
            val from = maxOf(start, month.atDay(1))
            val to = minOf(end, month.atEndOfMonth())
            val days = ChronoUnit.DAYS.between(from, to) + 1
            val available = monthPlan(month, inputs).availableForWeeksCents.coerceAtLeast(0)
            available * days / month.lengthOfMonth()
        }
    }

    fun week(today: LocalDate, inputs: BudgetInputs): WeekBudget {
        if (inputs.incomes.none { it.kind == IncomeKind.FIXA }) {
            return WeekBudget.Incomplete("Cadastre sua renda fixa para calcular o limite da semana.")
        }
        val start = weekStartOf(today, inputs.settings.weekStart)
        val carry = excessCarriedInto(start, inputs)
        val suggested = suggestedWeeklyLimit(start, inputs)
        val override = inputs.settings.weeklyLimitOverrideCents
        val overrideApplied = override != null && override < suggested
        val limit = (if (overrideApplied) override!! else suggested) - carry
        val spent = spentBetween(start, start.plusDays(6), inputs.expenses)
        val remaining = limit - spent
        val daysLeft = (ChronoUnit.DAYS.between(today, start.plusDays(6)) + 1).toInt().coerceIn(1, 7)
        return WeekBudget.Ready(
            start = start,
            end = start.plusDays(6),
            suggestedLimitCents = suggested,
            limitCents = limit,
            carriedOverExcessCents = carry,
            spentCents = spent,
            remainingCents = remaining,
            daysLeft = daysLeft,
            perDayCents = remaining.coerceAtLeast(0) / daysLeft,
            overrideApplied = overrideApplied,
        )
    }

    /** O usuário só pode baixar o limite, nunca subir acima do que o orçamento comporta. */
    fun validateOverride(cents: Long, today: LocalDate, inputs: BudgetInputs): OverrideCheck {
        val suggested = suggestedWeeklyLimit(weekStartOf(today, inputs.settings.weekStart), inputs)
        return if (cents <= suggested) OverrideCheck.Ok else OverrideCheck.TooHigh(suggested)
    }

    /** Divide uma renda extra: (para a quitação, para a semana). O arredondamento favorece a semana. */
    fun splitExtra(amountCents: Long, percentToDebt: Int): Pair<Long, Long> {
        val toDebt = amountCents * percentToDebt.coerceIn(0, 100) / 100
        return toDebt to amountCents - toDebt
    }

    fun envelopeSpent(month: YearMonth, categoryId: String, expenses: List<Expense>): Long =
        expenses.filter { it.categoryId == categoryId && YearMonth.from(it.date) == month }.sumOf { it.amountCents }

    private fun spentBetween(from: LocalDate, to: LocalDate, expenses: List<Expense>): Long =
        expenses.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }.sumOf { it.amountCents }

    /** Excesso que chega na semana que começa em [start], acompanhando as semanas anteriores em cadeia. */
    private fun excessCarriedInto(start: LocalDate, inputs: BudgetInputs): Long {
        var carry = 0L
        var weekStart = start.minusWeeks(CARRY_LOOKBACK_WEEKS)
        val override = inputs.settings.weeklyLimitOverrideCents
        while (weekStart.isBefore(start)) {
            val suggested = suggestedWeeklyLimit(weekStart, inputs)
            val limit = (if (override != null && override < suggested) override else suggested) - carry
            val spent = spentBetween(weekStart, weekStart.plusDays(6), inputs.expenses)
            carry = (spent - limit).coerceAtLeast(0)
            weekStart = weekStart.plusWeeks(1)
        }
        return carry
    }
}
