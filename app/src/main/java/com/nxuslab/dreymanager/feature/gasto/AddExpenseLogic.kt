package com.nxuslab.dreymanager.feature.gasto

import com.nxuslab.dreymanager.data.repo.Category
import com.nxuslab.dreymanager.data.repo.FinanceSnapshot
import com.nxuslab.dreymanager.domain.budget.BudgetCalculator
import com.nxuslab.dreymanager.domain.budget.CardContext
import com.nxuslab.dreymanager.domain.budget.EnvelopeState
import com.nxuslab.dreymanager.domain.budget.GuardResult
import com.nxuslab.dreymanager.domain.budget.SpendGuard
import com.nxuslab.dreymanager.domain.budget.WeekBudget
import com.nxuslab.dreymanager.domain.model.CardDebt
import com.nxuslab.dreymanager.domain.model.DebtKind
import com.nxuslab.dreymanager.domain.model.clampDay
import com.nxuslab.dreymanager.domain.payoff.PayoffPlanner
import com.nxuslab.dreymanager.domain.payoff.PayoffResult
import com.nxuslab.dreymanager.domain.model.Expense
import com.nxuslab.dreymanager.domain.model.Income
import com.nxuslab.dreymanager.domain.model.IncomeKind
import com.nxuslab.dreymanager.domain.model.PaymentMethod
import com.nxuslab.dreymanager.feature.common.week
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** Teclado em centavos, como em caixa eletrônico: 3, 2, 9, 0 → R$ 32,90. */
object Keypad {
    const val MAX_DIGITS = 9 // até R$ 9.999.999,99

    fun press(digits: String, key: Char): String = when {
        key == '<' -> digits.dropLast(1)
        key.isDigit() && digits.length < MAX_DIGITS -> (digits + key).trimStart('0')
        else -> digits
    }

    fun cents(digits: String): Long = digits.toLongOrNull() ?: 0
}

/** O que o app decide antes de salvar um gasto: o alerta e o gasto pronto para gravar. */
data class ExpenseReview(val guard: GuardResult?, val expense: Expense, val remainingAfterCents: Long?)

object AddExpenseLogic {
    fun review(
        snapshot: FinanceSnapshot,
        today: LocalDate,
        amountCents: Long,
        categoryId: String,
        method: PaymentMethod,
        cardId: String?,
        accountId: String? = null,
    ): ExpenseReview {
        val card = if (method == PaymentMethod.CARTAO) snapshot.cards.firstOrNull { it.id == cardId } else null
        val invoice = card?.let { SpendGuard.invoiceDueMonth(today, it.closingDay, it.dueDay) }
        val expense = Expense(
            UUID.randomUUID().toString(), amountCents, categoryId, today, method, card?.id, invoice,
            accountId = if (method == PaymentMethod.CARTAO) null else accountId,
        )

        val week = snapshot.week(today) as? WeekBudget.Ready
            ?: return ExpenseReview(guard = null, expense = expense, remainingAfterCents = null)

        val month = YearMonth.from(today)
        val envelope = snapshot.envelopes.firstOrNull { it.categoryId == categoryId }?.let { env ->
            val name = snapshot.categories.firstOrNull { it.id == categoryId }?.name ?: categoryId
            EnvelopeState(categoryId, name, env.monthlyLimitCents, BudgetCalculator.envelopeSpent(month, categoryId, snapshot.expenses))
        }
        val cardContext = card?.let { c ->
            CardContext(
                cardName = c.name,
                closingDay = c.closingDay,
                dueDay = c.dueDay,
                purchasesThisMonthBefore = snapshot.expenses.count { it.method == PaymentMethod.CARTAO && it.cardId == c.id && YearMonth.from(it.date) == month },
                payoffExtraMonthlyCents = snapshot.settings.budget.payoffExtraMonthlyCents,
            )
        }
        val guard = SpendGuard.evaluate(amountCents, today, week, envelope, cardContext)
        return ExpenseReview(guard, expense, week.remainingCents - amountCents)
    }

    /** Renda extra: a parte destinada à quitação segue o percentual dos ajustes. */
    fun extraIncome(today: LocalDate, amountCents: Long, percentToDebt: Int, accountId: String? = null): Income {
        val (toDebt, _) = BudgetCalculator.splitExtra(amountCents, percentToDebt)
        return Income(UUID.randomUUID().toString(), IncomeKind.EXTRA, amountCents, date = today, toDebtCents = toDebt, accountId = accountId)
    }

    /**
     * Nova compra parcelada no cartão: a primeira parcela cai na fatura em que a compra entra.
     * Devolve a dívida e quantos meses ela atrasa a saída do(s) cartão(ões) a cancelar.
     */
    fun installment(snapshot: FinanceSnapshot, today: LocalDate, cardId: String, description: String, installmentCents: Long, count: Int): InstallmentReview {
        val card = snapshot.cards.first { it.id == cardId }
        val dueMonth = SpendGuard.invoiceDueMonth(today, card.closingDay, card.dueDay)
        val debt = CardDebt(UUID.randomUUID().toString(), cardId, DebtKind.PARCELA, description.ifBlank { "Parcelado" }, installmentCents, count, dueMonth.clampDay(card.dueDay))
        val month = YearMonth.from(today)
        val extra = snapshot.settings.budget.payoffExtraMonthlyCents
        fun freeMonth(debts: List<CardDebt>) = (PayoffPlanner.plan(debts, snapshot.cards, extra, month) as? PayoffResult.Plan)?.let { it.goalFreeMonth ?: it.debtFreeMonth }
        val before = freeMonth(snapshot.debts)
        val after = freeMonth(snapshot.debts + debt)
        val delay = if (before != null && after != null) java.time.temporal.ChronoUnit.MONTHS.between(before, after).toInt() else null
        return InstallmentReview(debt, card.name, installmentCents * count, delay, after)
    }

    /** Categorias mais usadas nos últimos 60 dias primeiro. */
    fun categoryOrder(snapshot: FinanceSnapshot, today: LocalDate): List<Category> {
        val recent = snapshot.expenses.filter { !it.date.isBefore(today.minusDays(60)) }.groupingBy { it.categoryId }.eachCount()
        return snapshot.categories.sortedWith(compareByDescending<Category> { recent[it.id] ?: 0 }.thenBy { it.name })
    }
}

data class InstallmentReview(val debt: CardDebt, val cardName: String, val totalCents: Long, val payoffDelayMonths: Int?, val newFreeMonth: YearMonth?)
