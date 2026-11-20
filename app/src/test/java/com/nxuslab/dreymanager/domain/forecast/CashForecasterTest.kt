package com.nxuslab.dreymanager.domain.forecast

import com.nxuslab.dreymanager.domain.model.Card
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

class CashForecasterTest {
    private val start = LocalDate.of(2027, 1, 1)
    private val salary = Income("s", IncomeKind.FIXA, 400_000, dayOfMonth = 5)
    private val rent = FixedBill("r", "Aluguel", 100_000, dayOfMonth = 10)
    private val nubank = Card("nubank", "Nubank", closingDay = 3, dueDay = 10)

    private val healthy = ForecastInputs(
        openingBalanceCents = 100_000,
        incomes = listOf(salary),
        fixedBills = listOf(rent),
        cards = listOf(nubank),
        dailySpendCents = 1_000,
    )

    @Test
    fun orcamentoSaudavelNaoTemFalta() {
        val forecast = CashForecaster.forecast(start, 90, healthy)
        assertEquals(90, forecast.days.size)
        assertTrue(forecast.shortfalls.isEmpty())
        assertEquals(100_000L - 4 * 1_000 + 400_000 - 1_000, forecast.days[4].balanceCents) // 5/jan
    }

    @Test
    fun faltaNoDiaDaFaturaComMotivo() {
        val fatura = CardDebt("f", "nubank", DebtKind.FATURA_ABERTA, "Fatura jan", 500_000, null, LocalDate.of(2027, 1, 10))
        val inputs = healthy.copy(openingBalanceCents = 50_000, fixedBills = emptyList(), debts = listOf(fatura), dailySpendCents = 0)
        val shortfall = CashForecaster.forecast(start, 90, inputs).shortfalls.first()
        assertEquals(LocalDate.of(2027, 1, 10), shortfall.date)
        assertEquals(50_000, shortfall.cents)
        assertEquals("Fatura Nubank", shortfall.reason)
    }

    @Test
    fun contaAntesDoSalarioGeraFalta() {
        val inputs = healthy.copy(openingBalanceCents = 0, fixedBills = listOf(rent.copy(dayOfMonth = 3)), dailySpendCents = 0)
        val shortfall = CashForecaster.forecast(start, 30, inputs).shortfalls.single()
        assertEquals(LocalDate.of(2027, 1, 3), shortfall.date)
        assertEquals("Aluguel", shortfall.reason)
    }

    @Test
    fun salarioAntesDaContaCobre() {
        val inputs = healthy.copy(openingBalanceCents = 0, dailySpendCents = 0)
        assertTrue(CashForecaster.forecast(start, 30, inputs).shortfalls.isEmpty())
    }

    @Test
    fun parcelasECompraReservadaSaemNoVencimento() {
        val parcela = CardDebt("p", "nubank", DebtKind.PARCELA, "Notebook", 30_000, 3, LocalDate.of(2027, 1, 15))
        val reserved = Expense("e", 8_900, "lazer", LocalDate.of(2027, 1, 13), PaymentMethod.CARTAO, cardId = "nubank", reservedForInvoice = YearMonth.of(2027, 2))
        val events = CashForecaster.events(start, 90, healthy.copy(debts = listOf(parcela), expenses = listOf(reserved)))
        assertEquals(3, events.count { it.reason == "Parcela Notebook" })
        assertTrue(events.any { it.date == LocalDate.of(2027, 2, 15) && it.amountCents == -30_000L })
        assertTrue(events.any { it.date == LocalDate.of(2027, 2, 10) && it.amountCents == -8_900L && it.reason == "Fatura Nubank" })
    }

    @Test
    fun eSeComGastoCriaFalta() {
        val whatIf = listOf(CashEvent(LocalDate.of(2027, 1, 20), -900_000, "Simulação: TV"))
        val shortfall = CashForecaster.forecast(start, 90, healthy, whatIf).shortfalls.first()
        assertEquals(LocalDate.of(2027, 1, 20), shortfall.date)
        assertEquals("Simulação: TV", shortfall.reason)
    }

    @Test
    fun eSeComEntradaRemoveFalta() {
        val fatura = CardDebt("f", "nubank", DebtKind.FATURA_ABERTA, "Fatura jan", 500_000, null, LocalDate.of(2027, 1, 10))
        val inputs = healthy.copy(openingBalanceCents = 50_000, fixedBills = emptyList(), debts = listOf(fatura), dailySpendCents = 0)
        val whatIf = listOf(CashEvent(LocalDate.of(2027, 1, 8), 60_000, "Simulação: freela"))
        assertTrue(CashForecaster.forecast(start, 30, inputs, whatIf).shortfalls.isEmpty())
    }

    @Test
    fun salarioDia31EmFevereiroCaiNoUltimoDia() {
        val inputs = healthy.copy(incomes = listOf(salary.copy(dayOfMonth = 31)))
        val events = CashForecaster.events(LocalDate.of(2027, 2, 1), 28, inputs)
        assertTrue(events.any { it.date == LocalDate.of(2027, 2, 28) && it.amountCents == 400_000L })
    }

    @Test
    fun gastoDiarioSegueOOrcamentoDeCadaMes() {
        // Janeiro gasta 1.000/dia; fevereiro só 100/dia (mês com fatura grande).
        val inputs = healthy.copy(
            openingBalanceCents = 0, incomes = emptyList(), fixedBills = emptyList(), dailySpendCents = 999_999,
            dailySpendByMonth = mapOf(YearMonth.of(2027, 1) to 1_000L, YearMonth.of(2027, 2) to 100L),
        )
        val days = CashForecaster.forecast(LocalDate.of(2027, 1, 30), 4, inputs).days
        assertEquals(listOf(-1_000L, -2_000L, -2_100L, -2_200L), days.map { it.balanceCents })
    }

    @Test
    fun quitacaoEDesejosSaemNoDiaDoSalario() {
        val events = CashForecaster.events(start, 31, healthy.copy(payoffExtraMonthlyCents = 30_000, wishesMonthlyCents = 10_000))
        assertTrue(events.any { it.date == LocalDate.of(2027, 1, 5) && it.amountCents == -30_000L && it.reason == "Quitação do cartão" })
        assertTrue(events.any { it.date == LocalDate.of(2027, 1, 5) && it.amountCents == -10_000L && it.reason == "Desejos" })
    }
}
