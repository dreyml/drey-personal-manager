package com.nxuslab.dreymanager.feature.common

import com.nxuslab.dreymanager.data.repo.AppSettings
import com.nxuslab.dreymanager.data.repo.Category
import com.nxuslab.dreymanager.data.repo.FinanceSnapshot
import com.nxuslab.dreymanager.domain.budget.WeekBudget
import com.nxuslab.dreymanager.domain.model.BudgetSettings
import com.nxuslab.dreymanager.domain.model.Envelope
import com.nxuslab.dreymanager.domain.model.Expense
import com.nxuslab.dreymanager.domain.model.FixedBill
import com.nxuslab.dreymanager.domain.model.Income
import com.nxuslab.dreymanager.domain.model.IncomeKind
import com.nxuslab.dreymanager.domain.model.PaymentMethod
import com.nxuslab.dreymanager.domain.model.Wish
import com.nxuslab.dreymanager.domain.model.WishPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DerivedTest {
    private val today = LocalDate.of(2027, 1, 13)
    private val snapshot = FinanceSnapshot(
        settings = AppSettings(budget = BudgetSettings(payoffExtraMonthlyCents = 30_000)),
        incomes = listOf(Income("s", IncomeKind.FIXA, 400_000, dayOfMonth = 15)),
        fixedBills = listOf(FixedBill("i", "Internet", 9_990, 14), FixedBill("a", "Aluguel", 100_000, 25)),
        categories = listOf(Category("delivery", "Delivery", "🍔", 0)),
        envelopes = listOf(Envelope("delivery", 12_000)),
        expenses = listOf(Expense("e", 3_800, "delivery", LocalDate.of(2027, 1, 12), PaymentMethod.PIX)),
    )

    @Test
    fun venceEmBreveSoTemSaidas() {
        val upcoming = snapshot.upcoming(today)
        assertEquals(listOf("Internet"), upcoming.map { it.title }) // salário (dia 15) e aluguel (dia 25) ficam de fora
        assertEquals(9_990L, upcoming.single().amountCents)
    }

    @Test
    fun envelopeMostraGastoDoMes() {
        val status = snapshot.envelopeStatus(today).single()
        assertEquals(3_800L, status.spentCents)
        assertEquals(12_000L, status.limitCents)
    }

    @Test
    fun desejoDePrioridadeAltaReduzASemana() {
        val without = snapshot.week(today) as WeekBudget.Ready
        val withWish = snapshot.copy(
            wishes = listOf(Wish("w", "Fone", 60_000, LocalDate.of(2027, 7, 13), WishPriority.ALTA)),
        ).week(today) as WeekBudget.Ready
        assertTrue(withWish.limitCents < without.limitCents)
    }

    @Test
    fun gastosDaSemanaAgrupadosPorDia() {
        val withExpenses = snapshot.copy(
            expenses = listOf(
                Expense("a", 1_000, "delivery", LocalDate.of(2027, 1, 11), PaymentMethod.PIX), // segunda
                Expense("b", 2_000, "delivery", LocalDate.of(2027, 1, 13), PaymentMethod.PIX),
                Expense("c", 3_000, "delivery", LocalDate.of(2027, 1, 13), PaymentMethod.CARTAO),
                Expense("d", 4_000, "delivery", LocalDate.of(2027, 1, 10), PaymentMethod.PIX), // domingo anterior
            ),
        )
        val (start, groups) = withExpenses.weekGroups(today)
        assertEquals(LocalDate.of(2027, 1, 11), start)
        assertEquals(listOf(LocalDate.of(2027, 1, 13), LocalDate.of(2027, 1, 11)), groups.map { it.date })
        assertEquals(5_000L, groups.first().totalCents)

        // Semana começando no domingo inclui o dia 10.
        val sunday = withExpenses.copy(settings = withExpenses.settings.copy(budget = withExpenses.settings.budget.copy(weekStart = java.time.DayOfWeek.SUNDAY)))
        assertEquals(LocalDate.of(2027, 1, 10), sunday.weekGroups(today).first)
        assertEquals(3, sunday.weekGroups(today).second.size)
    }

    @Test
    fun semRendaNaoHaSobraParaDesejos() {
        assertEquals(0L, snapshot.copy(incomes = emptyList()).monthlyLeftoverCents(today))
    }
}
