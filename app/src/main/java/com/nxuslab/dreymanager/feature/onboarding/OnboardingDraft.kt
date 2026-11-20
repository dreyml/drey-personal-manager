package com.nxuslab.dreymanager.feature.onboarding

import com.nxuslab.dreymanager.domain.budget.BudgetCalculator
import com.nxuslab.dreymanager.domain.budget.WeekBudget
import com.nxuslab.dreymanager.domain.model.BudgetInputs
import com.nxuslab.dreymanager.domain.model.BudgetSettings
import com.nxuslab.dreymanager.domain.model.Card
import com.nxuslab.dreymanager.domain.model.CardDebt
import com.nxuslab.dreymanager.domain.model.DebtKind
import com.nxuslab.dreymanager.domain.model.Envelope
import com.nxuslab.dreymanager.domain.model.FixedBill
import com.nxuslab.dreymanager.domain.model.Income
import com.nxuslab.dreymanager.domain.model.IncomeKind
import com.nxuslab.dreymanager.domain.model.clampDay
import com.nxuslab.dreymanager.domain.payoff.PayoffPlanner
import com.nxuslab.dreymanager.domain.payoff.PayoffResult
import java.time.LocalDate
import java.time.YearMonth

data class BillDraft(val id: String, val title: String, val amountCents: Long, val day: Int)

data class InstallmentDraft(val id: String, val description: String, val amountCents: Long, val remaining: Int)

data class CardDraft(
    val id: String,
    val name: String,
    val closingDay: Int,
    val dueDay: Int,
    val interestBp: Int? = null,
    val openInvoiceCents: Long? = null,
    val rotativoCents: Long? = null,
    val installments: List<InstallmentDraft> = emptyList(),
)

data class OnboardingSummary(
    val week: WeekBudget,
    val payoff: PayoffResult,
    val suggestedExtraCents: Long,
    val appliedExtraCents: Long,
)

/** Próxima data com o dia [day] a partir de [today] (no próprio mês, se ainda não passou). */
fun nextDueDate(today: LocalDate, day: Int): LocalDate {
    val thisMonth = YearMonth.from(today).clampDay(day)
    return if (thisMonth.isBefore(today)) YearMonth.from(today).plusMonths(1).clampDay(day) else thisMonth
}

/** O que o usuário preencheu nos 5 passos, antes de gravar. Tudo opcional: dá para pular. */
data class OnboardingDraft(
    val salaryCents: Long? = null,
    val salaryDay: Int = 5,
    val balanceCents: Long? = null,
    val bills: List<BillDraft> = emptyList(),
    val cards: List<CardDraft> = emptyList(),
    /** categoryId → limite mensal. */
    val envelopes: Map<String, Long> = emptyMap(),
    /** Extra de quitação escolhido; nulo = usar o sugerido. */
    val payoffExtraCents: Long? = null,
) {
    fun incomes(): List<Income> =
        salaryCents?.let { listOf(Income(SALARY_ID, IncomeKind.FIXA, it, dayOfMonth = salaryDay)) } ?: emptyList()

    fun fixedBills(): List<FixedBill> = bills.map { FixedBill(it.id, it.title, it.amountCents, it.day) }

    fun cards(): List<Card> = cards.map { Card(it.id, it.name, it.closingDay, it.dueDay, it.interestBp) }

    fun envelopeList(): List<Envelope> = envelopes.filterValues { it > 0 }.map { (category, limit) -> Envelope(category, limit) }

    /** Fatura, rotativo e parcelas de cada cartão, todos com o próximo vencimento do cartão. */
    fun debts(today: LocalDate): List<CardDebt> = cards.flatMap { card ->
        val due = nextDueDate(today, card.dueDay)
        buildList {
            card.openInvoiceCents?.takeIf { it > 0 }?.let { add(CardDebt("${card.id}-fatura", card.id, DebtKind.FATURA_ABERTA, "Fatura ${card.name}", it, null, due)) }
            card.rotativoCents?.takeIf { it > 0 }?.let { add(CardDebt("${card.id}-rotativo", card.id, DebtKind.ROTATIVO, "Rotativo ${card.name}", it, null, due)) }
            card.installments.filter { it.amountCents > 0 && it.remaining > 0 }.forEach {
                add(CardDebt(it.id, card.id, DebtKind.PARCELA, it.description, it.amountCents, it.remaining, due))
            }
        }
    }

    fun summary(today: LocalDate): OnboardingSummary {
        val month = YearMonth.from(today)
        val debts = debts(today)
        val base = BudgetInputs(settings = BudgetSettings(), incomes = incomes(), fixedBills = fixedBills(), debts = debts)
        val suggested = BudgetCalculator.suggestPayoffExtra(month, base)
        val applied = payoffExtraCents ?: suggested
        val inputs = base.copy(settings = BudgetSettings(payoffExtraMonthlyCents = applied))
        return OnboardingSummary(
            week = BudgetCalculator.week(today, inputs),
            payoff = PayoffPlanner.plan(debts, cards(), applied, month),
            suggestedExtraCents = suggested,
            appliedExtraCents = applied,
        )
    }

    companion object {
        const val SALARY_ID = "salario"
        const val MAIN_ACCOUNT_ID = "conta-principal"
    }
}
