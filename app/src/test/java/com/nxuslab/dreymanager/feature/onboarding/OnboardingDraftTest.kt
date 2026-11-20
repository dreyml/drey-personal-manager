package com.nxuslab.dreymanager.feature.onboarding

import com.nxuslab.dreymanager.domain.budget.WeekBudget
import com.nxuslab.dreymanager.domain.model.DebtKind
import com.nxuslab.dreymanager.domain.model.IncomeKind
import com.nxuslab.dreymanager.domain.payoff.PayoffResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class OnboardingDraftTest {
    private val today = LocalDate.of(2027, 1, 13)

    private val nubank = CardDraft(
        id = "nubank", name = "Nubank", closingDay = 3, dueDay = 10, interestBp = 800,
        openInvoiceCents = 50_000, rotativoCents = 20_000,
        installments = listOf(InstallmentDraft("p", "Notebook", 30_000, 10)),
    )

    private val draft = OnboardingDraft(
        salaryCents = 400_000,
        salaryDay = 5,
        balanceCents = 80_000,
        bills = listOf(BillDraft("r", "Aluguel", 100_000, 10)),
        cards = listOf(nubank),
    )

    @Test
    fun proximoVencimentoNoMesOuNoSeguinte() {
        assertEquals(LocalDate.of(2027, 1, 20), nextDueDate(today, 20))
        assertEquals(LocalDate.of(2027, 2, 10), nextDueDate(today, 10)) // dia 10 já passou
        assertEquals(LocalDate.of(2027, 1, 13), nextDueDate(today, 13))
        assertEquals(LocalDate.of(2027, 2, 28), nextDueDate(LocalDate.of(2027, 2, 1), 31))
    }

    @Test
    fun rendaFixaVemDoPassoUm() {
        val income = draft.incomes().single()
        assertEquals(IncomeKind.FIXA, income.kind)
        assertEquals(400_000L, income.amountCents)
        assertEquals(5, income.dayOfMonth)
        assertTrue(draft.copy(salaryCents = null).incomes().isEmpty())
    }

    @Test
    fun cartaoViraTresTiposDeDivida() {
        val debts = draft.debts(today)
        assertEquals(setOf(DebtKind.FATURA_ABERTA, DebtKind.ROTATIVO, DebtKind.PARCELA), debts.map { it.kind }.toSet())
        assertTrue(debts.all { it.nextDueDate == LocalDate.of(2027, 2, 10) && it.cardId == "nubank" })
        assertEquals(10, debts.single { it.kind == DebtKind.PARCELA }.remainingInstallments)
    }

    @Test
    fun cartaoSemValoresNaoGeraDivida() {
        val empty = draft.copy(cards = listOf(nubank.copy(openInvoiceCents = null, rotativoCents = 0, installments = emptyList())))
        assertTrue(empty.debts(today).isEmpty())
        assertEquals(1, empty.cards().size)
    }

    @Test
    fun resumoUsaExtraSugeridoQuandoUsuarioNaoEscolheu() {
        val summary = draft.summary(today)
        // Janeiro não tem obrigações de cartão (fatura e parcela vencem em fevereiro).
        // Sobra de janeiro: 400.000 − 100.000 = 300.000 → 30% = 90.000.
        assertEquals(90_000L, summary.suggestedExtraCents)
        assertEquals(90_000L, summary.appliedExtraCents)
        assertTrue(summary.week is WeekBudget.Ready)
        assertTrue(summary.payoff is PayoffResult.Plan)
        assertEquals(YearMonth.of(2027, 1), (summary.payoff as PayoffResult.Plan).timeline.first().month)
    }

    @Test
    fun resumoRespeitaExtraEscolhido() {
        val summary = draft.copy(payoffExtraCents = 10_000).summary(today)
        assertEquals(10_000L, summary.appliedExtraCents)
    }

    @Test
    fun semRendaOResumoFicaIncompleto() {
        val summary = draft.copy(salaryCents = null).summary(today)
        assertTrue(summary.week is WeekBudget.Incomplete)
    }
}
