package com.nxuslab.dreymanager

import com.nxuslab.dreymanager.domain.advice.NextStepAdvisor
import com.nxuslab.dreymanager.domain.advice.Shortfall
import com.nxuslab.dreymanager.domain.advice.StepAction
import com.nxuslab.dreymanager.domain.daily.DailyAllowance
import com.nxuslab.dreymanager.domain.daily.DayState
import com.nxuslab.dreymanager.domain.model.CardDebt
import com.nxuslab.dreymanager.domain.model.DebtKind
import com.nxuslab.dreymanager.domain.model.FixedBill
import com.nxuslab.dreymanager.domain.model.VariableBill
import com.nxuslab.dreymanager.domain.plan.MonthChecklist
import com.nxuslab.dreymanager.feature.financas.WalletKind
import com.nxuslab.dreymanager.feature.financas.brandFor
import com.nxuslab.dreymanager.feature.hub.StoicQuotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class V3LogicTest {
    private val monday = LocalDate.of(2026, 11, 23)
    private val tuesday = monday.plusDays(1)

    // ---- Valor do dia ----

    @Test fun dailySplitsWeekAndCarriesLeftover() {
        // 700 na semana: segunda tinha 100 e gastou 40 → terça tem (700-40)/6 = 110, com 10 que sobraram.
        val view = DailyAllowance.compute(tuesday, monday, 70_000, mapOf(monday to 4_000))
        assertEquals(11_000L, view.todayAllowanceCents)
        assertEquals(1_000L, view.carriedCents)
        assertEquals(DayState.OK, view.days[0].state)
        assertEquals(DayState.TODAY, view.days[1].state)
        assertEquals(DayState.FUTURE, view.days[2].state)
    }

    @Test fun overspendingNeverShowsNegativeAndShrinksTomorrow() {
        val view = DailyAllowance.compute(tuesday, monday, 70_000, mapOf(monday to 15_000, tuesday to 20_000))
        assertEquals(DayState.OVER, view.days[0].state)
        // Terça: (700-150)/6 = 91,66 → 9166; gastou 200 → passou 108,34 e sobra 0.
        assertEquals(9_166L, view.todayAllowanceCents)
        assertEquals(0L, view.remainingTodayCents)
        assertEquals(10_834L, view.overTodayCents)
        assertEquals((70_000L - 35_000) / 5, view.tomorrowCents)
    }

    @Test fun lastDayHasNoTomorrow() {
        val sunday = monday.plusDays(6)
        assertNull(DailyAllowance.compute(sunday, monday, 70_000, emptyMap()).tomorrowCents)
    }

    // ---- Este mês, pague ----

    private val month = YearMonth.of(2026, 12)
    private val invoice = CardDebt("inv", "nu", DebtKind.FATURA_ABERTA, "Fatura", 56_465, null, LocalDate.of(2026, 12, 22))
    private val installment = CardDebt("p", "si", DebtKind.PARCELA, "Curso", 3_569, 9, LocalDate.of(2027, 1, 10))
    private val oldInstallment = CardDebt("q", "si", DebtKind.PARCELA, "Shein", 6_757, 2, LocalDate.of(2026, 11, 10))

    @Test fun checklistListsMonthItemsUnpaidFirst() {
        val items = MonthChecklist.build(
            month,
            fixedBills = listOf(FixedBill("luz", "Internet", 10_000, 5)),
            variableBills = listOf(VariableBill("ener", "Energia", 20, 18_000)),
            variableActuals = emptyMap(),
            debts = listOf(invoice, installment, oldInstallment),
            cardNames = mapOf("nu" to "Nubank", "si" to "Sicoob"),
            payoffExtraCents = 20_000,
            paid = setOf("bill:luz" to month),
        )
        assertEquals(listOf("debt:q", "variable:ener", "debt:inv", "payoff", "bill:luz"), items.map { it.key })
        assertTrue(items.last().paid)
        assertEquals("Fatura Nubank", items.first { it.key == "debt:inv" }.title)
        assertEquals("inv", items.first { it.key == "debt:inv" }.debtId)
        assertTrue(items.first { it.key == "variable:ener" }.needsValue)
        assertFalse(items.any { it.key == "debt:p" }) // parcela começa em janeiro
    }

    // ---- Próximo passo ----

    @Test fun shortfallComesFirst() {
        val step = NextStepAdvisor.advise(tuesday, Shortfall(tuesday.plusDays(3), 5_000), emptyList(), null, false, null, null)
        assertEquals(StepAction.PREVISAO, step.action)
    }

    @Test fun billDueSoonBeatsDailyAmount() {
        val items = MonthChecklist.build(YearMonth.of(2026, 11), listOf(FixedBill("a", "Aluguel", 80_000, 27)), emptyList(), emptyMap(), emptyList(), emptyMap(), 0, emptySet())
        val daily = DailyAllowance.compute(tuesday, monday, 70_000, emptyMap())
        val step = NextStepAdvisor.advise(tuesday, null, items, null, false, daily, null)
        assertEquals("Pague Aluguel até 27/11", step.title)
        assertEquals(StepAction.PLANO, step.action)
    }

    @Test fun defaultIsTodaysAmountWithoutNegative() {
        val daily = DailyAllowance.compute(tuesday, monday, 70_000, mapOf(tuesday to 50_000))
        val over = NextStepAdvisor.advise(tuesday, null, emptyList(), null, false, daily, "Nubank")
        assertTrue(over.title.startsWith("Hoje passou"))
        val calm = NextStepAdvisor.advise(tuesday, null, emptyList(), null, false, DailyAllowance.compute(tuesday, monday, 70_000, emptyMap()), "Nubank")
        assertTrue(calm.title.startsWith("Hoje dá para gastar"))
        assertTrue(calm.detail.contains("Nubank"))
    }

    // ---- Hub e carteira ----

    @Test fun quoteIsStablePerDayAndCycles() {
        assertEquals(StoicQuotes.of(tuesday), StoicQuotes.of(tuesday))
        assertEquals(StoicQuotes.all[0], StoicQuotes.of(LocalDate.of(2026, 1, 1)))
    }

    @Test fun bankBrands() {
        assertEquals("nu", brandFor("Nubank", WalletKind.CREDITO).wordmark)
        assertEquals("sicoob", brandFor("Sicoob", WalletKind.CONTA).wordmark)
        assertEquals("mercado pago", brandFor("Mercado Pago", WalletKind.CONTA).wordmark)
        assertFalse(brandFor("Carteira", WalletKind.DINHEIRO).lightText)
        assertFalse(brandFor("Banco X", WalletKind.CONTA).lightText)
    }
}
