package com.nxuslab.dreymanager.domain.budget

import com.nxuslab.dreymanager.domain.model.BudgetInputs
import com.nxuslab.dreymanager.domain.model.BudgetSettings
import com.nxuslab.dreymanager.domain.model.CardDebt
import com.nxuslab.dreymanager.domain.model.DebtKind
import com.nxuslab.dreymanager.domain.model.Expense
import com.nxuslab.dreymanager.domain.model.FixedBill
import com.nxuslab.dreymanager.domain.model.Income
import com.nxuslab.dreymanager.domain.model.IncomeKind
import com.nxuslab.dreymanager.domain.model.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class BudgetCalculatorTest {
    private val jan = YearMonth.of(2027, 1)
    private val salary = Income("s", IncomeKind.FIXA, 400_000, dayOfMonth = 5)
    private val rent = FixedBill("r", "Aluguel", 100_000, dayOfMonth = 10)
    private val notebook = CardDebt("p", "itau", DebtKind.PARCELA, "Notebook", 30_000, remainingInstallments = 10, nextDueDate = LocalDate.of(2027, 1, 15))
    private val openInvoice = CardDebt("f", "nubank", DebtKind.FATURA_ABERTA, "Fatura jan", 50_000, nextDueDate = LocalDate.of(2027, 1, 10))
    private val rotativo = CardDebt("rot", "nubank", DebtKind.ROTATIVO, "Rotativo", 80_000, nextDueDate = LocalDate.of(2027, 1, 10))

    private val base = BudgetInputs(
        settings = BudgetSettings(payoffExtraMonthlyCents = 20_000),
        incomes = listOf(salary),
        fixedBills = listOf(rent),
        debts = listOf(notebook, openInvoice, rotativo),
    )

    // Janeiro: 400.000 − 100.000 − (30.000 + 50.000) − 20.000 = 200.000 para as semanas (31 dias).
    private val janWeek = 7L * 200_000 / 31 // 45.161

    @Test
    fun ordemDoOrcamentoDoMes() {
        val plan = BudgetCalculator.monthPlan(jan, base.copy(highPriorityWishMonthlyCents = 10_000, settings = base.settings.copy(reserveCents = 5_000)))
        assertEquals(400_000, plan.incomeCents)
        assertEquals(100_000, plan.fixedBillsCents)
        assertEquals(80_000, plan.cardObligationsCents) // parcela + fatura; rotativo não tem parcela fixa
        assertEquals(20_000, plan.payoffCents)
        assertEquals(10_000, plan.highWishesCents)
        assertEquals(5_000, plan.reserveCents)
        assertEquals(185_000, plan.availableForWeeksCents)
    }

    @Test
    fun parcelaSoEntraNosMesesDoParcelamento() {
        assertEquals(0L, BudgetCalculator.cardObligations(YearMonth.of(2026, 12), base.debts, emptyList()))
        assertEquals(30_000L, BudgetCalculator.cardObligations(YearMonth.of(2027, 10), base.debts, emptyList()))
        assertEquals(0L, BudgetCalculator.cardObligations(YearMonth.of(2027, 11), base.debts, emptyList()))
    }

    @Test
    fun semRendaFixaFicaIncompleto() {
        val week = BudgetCalculator.week(LocalDate.of(2027, 1, 13), base.copy(incomes = emptyList()))
        assertTrue(week is WeekBudget.Incomplete)
        assertTrue((week as WeekBudget.Incomplete).reason.contains("renda"))
    }

    @Test
    fun semanaDentroDeUmMes() {
        val week = BudgetCalculator.week(LocalDate.of(2027, 1, 13), base) as WeekBudget.Ready
        assertEquals(LocalDate.of(2027, 1, 11), week.start)
        assertEquals(LocalDate.of(2027, 1, 17), week.end)
        assertEquals(janWeek, week.suggestedLimitCents)
        assertEquals(janWeek, week.limitCents)
        assertEquals(5, week.daysLeft) // quarta a domingo
        assertEquals(janWeek / 5, week.perDayCents)
    }

    @Test
    fun semanaQueCruzaMesesEProporcional() {
        // 28/12 a 31/12 em dezembro (sem obrigações de cartão: 280.000) + 1/1 a 3/1 em janeiro (200.000).
        val week = BudgetCalculator.week(LocalDate.of(2026, 12, 29), base) as WeekBudget.Ready
        assertEquals(LocalDate.of(2026, 12, 28), week.start)
        assertEquals(280_000L * 4 / 31 + 200_000L * 3 / 31, week.suggestedLimitCents)
    }

    @Test
    fun gastosDaSemanaIncluindoCartaoReduzemOQueResta() {
        val inputs = base.copy(
            expenses = listOf(
                Expense("e1", 3_290, "delivery", LocalDate.of(2027, 1, 12), PaymentMethod.PIX),
                Expense("e2", 8_900, "lazer", LocalDate.of(2027, 1, 13), PaymentMethod.CARTAO, cardId = "nubank", reservedForInvoice = YearMonth.of(2027, 2)),
                Expense("e3", 10_000, "mercado", LocalDate.of(2027, 1, 18), PaymentMethod.DEBITO), // semana seguinte
            ),
        )
        val week = BudgetCalculator.week(LocalDate.of(2027, 1, 13), inputs) as WeekBudget.Ready
        assertEquals(12_190, week.spentCents)
        assertEquals(janWeek - 12_190, week.remainingCents)
    }

    @Test
    fun compraNoCartaoReservadaEntraNasObrigacoesDoMesDaFatura() {
        val expense = Expense("e", 8_900, "lazer", LocalDate.of(2027, 1, 13), PaymentMethod.CARTAO, cardId = "nubank", reservedForInvoice = YearMonth.of(2027, 2))
        assertEquals(30_000L + 8_900, BudgetCalculator.cardObligations(YearMonth.of(2027, 2), base.debts, listOf(expense)))
    }

    @Test
    fun excessoDaSemanaAnteriorEDescontado() {
        val inputs = base.copy(expenses = listOf(Expense("e", 50_000, "lazer", LocalDate.of(2027, 1, 6), PaymentMethod.PIX)))
        val week = BudgetCalculator.week(LocalDate.of(2027, 1, 13), inputs) as WeekBudget.Ready
        val previousWeekLimit = 7L * 200_000 / 31
        assertEquals(50_000 - previousWeekLimit, week.carriedOverExcessCents)
        assertEquals(janWeek - (50_000 - previousWeekLimit), week.limitCents)
    }

    @Test
    fun ajusteMenorQueOSugeridoEAplicado() {
        val inputs = base.copy(settings = base.settings.copy(weeklyLimitOverrideCents = 30_000))
        val week = BudgetCalculator.week(LocalDate.of(2027, 1, 13), inputs) as WeekBudget.Ready
        assertEquals(30_000, week.limitCents)
        assertTrue(week.overrideApplied)
    }

    @Test
    fun ajusteMaiorQueOSugeridoERecusado() {
        val check = BudgetCalculator.validateOverride(50_000, LocalDate.of(2027, 1, 13), base)
        assertTrue(check is OverrideCheck.TooHigh)
        assertEquals(janWeek, (check as OverrideCheck.TooHigh).maxCents)
        assertTrue(BudgetCalculator.validateOverride(30_000, LocalDate.of(2027, 1, 13), base) is OverrideCheck.Ok)
    }

    @Test
    fun rendaExtraDivideEntreQuitacaoESemana() {
        val (toDebt, toWeek) = BudgetCalculator.splitExtra(100_001, percentToDebt = 50)
        assertEquals(50_000, toDebt)
        assertEquals(50_001, toWeek)

        val extra = Income("x", IncomeKind.EXTRA, 100_000, date = LocalDate.of(2027, 1, 20), toDebtCents = 50_000)
        val plan = BudgetCalculator.monthPlan(jan, base.copy(incomes = listOf(salary, extra)))
        assertEquals(500_000, plan.incomeCents)
        assertEquals(70_000, plan.payoffCents)
        assertEquals(250_000, plan.availableForWeeksCents)
    }

    @Test
    fun gastoEmEnvelopeContaNoMes() {
        val inputs = listOf(
            Expense("a", 3_800, "delivery", LocalDate.of(2027, 1, 3), PaymentMethod.PIX),
            Expense("b", 2_000, "delivery", LocalDate.of(2027, 2, 1), PaymentMethod.PIX),
            Expense("c", 9_000, "lazer", LocalDate.of(2027, 1, 3), PaymentMethod.PIX),
        )
        assertEquals(3_800L, BudgetCalculator.envelopeSpent(jan, "delivery", inputs))
    }

    @Test
    fun sugestaoDeExtraDeQuitacaoE30PorCentoDaSobraEmDezenas() {
        // Janeiro: 400.000 − 100.000 − 80.000 = 220.000 de sobra; 30% = 66.000 (já múltiplo de R$ 10).
        assertEquals(66_000L, BudgetCalculator.suggestPayoffExtra(jan, base))
        // Sobra de 220.330 → 30% = 66.099 → arredonda para baixo: 66.000.
        val odd = base.copy(incomes = listOf(salary.copy(amountCents = 400_330)))
        assertEquals(66_000L, BudgetCalculator.suggestPayoffExtra(jan, odd))
        // Mês sem sobra não sugere nada.
        assertEquals(0L, BudgetCalculator.suggestPayoffExtra(jan, base.copy(incomes = listOf(salary.copy(amountCents = 100_000)))))
    }

    @Test
    fun mesNegativoNaoGeraLimiteNegativo() {
        val poor = base.copy(incomes = listOf(salary.copy(amountCents = 50_000)))
        val week = BudgetCalculator.week(LocalDate.of(2027, 1, 13), poor) as WeekBudget.Ready
        assertEquals(0, week.suggestedLimitCents)
    }
}
