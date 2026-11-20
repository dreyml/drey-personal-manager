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
    fun livreDeVerdadeLimitadoPeloDinheiroAteOSalario() {
        // Conta com R$ 11,45, internet de R$ 99,90 amanhã e salário no dia 15: sobra negativa até lá.
        val acc = com.nxuslab.dreymanager.data.repo.Account("c", "Conta", 1_145, LocalDate.of(2027, 1, 12), updatedAtMillis = 10)
        val data = snapshot.copy(accounts = listOf(acc))
        val week = data.week(today) as WeekBudget.Ready
        val free = data.realFree(today, week)
        assertTrue(free.limitedByCash)
        assertEquals(1_145L - 9_990, free.cashUntilPaydayCents)
        assertEquals(free.cashUntilPaydayCents, free.freeCents)
        assertEquals(LocalDate.of(2027, 1, 15), free.nextPayday)
    }

    @Test
    fun saldoAntigoDepoisDoSalarioPedeConferencia() {
        // Saldo informado em 02/01, salário caiu em 05/01 (dia 5): não dá para confiar no saldo.
        val acc = com.nxuslab.dreymanager.data.repo.Account("c", "Conta", 1_145, LocalDate.of(2027, 1, 2))
        val data = snapshot.copy(incomes = listOf(Income("s", IncomeKind.FIXA, 400_000, dayOfMonth = 5)), accounts = listOf(acc))
        val free = data.realFree(today, data.week(today) as WeekBudget.Ready)
        assertTrue(free.staleBalances)
        assertTrue(!free.limitedByCash)
    }

    @Test
    fun gastoNoPixDescontaDaContaDepoisDoSaldoInformado() {
        val acc = com.nxuslab.dreymanager.data.repo.Account("c", "Conta", 10_000, today, updatedAtMillis = 1_000)
        val data = snapshot.copy(
            accounts = listOf(acc),
            expenses = listOf(
                Expense("antes", 500, "x", today, PaymentMethod.PIX, accountId = "c", createdAt = 900), // já estava no saldo
                Expense("depois", 2_000, "x", today, PaymentMethod.PIX, accountId = "c", createdAt = 1_100),
                Expense("cartao", 3_000, "x", today, PaymentMethod.CARTAO, accountId = "c", createdAt = 1_200), // cartão não sai da conta
            ),
        )
        assertEquals(8_000L, data.accountBalances().single().currentCents)
    }

    @Test
    fun semRendaNaoHaSobraParaDesejos() {
        assertEquals(0L, snapshot.copy(incomes = emptyList()).monthlyLeftoverCents(today))
    }
}
