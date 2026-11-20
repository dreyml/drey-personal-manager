package com.nxuslab.dreymanager.domain.payoff

import com.nxuslab.dreymanager.domain.model.Card
import com.nxuslab.dreymanager.domain.model.CardDebt
import com.nxuslab.dreymanager.domain.model.DebtKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class PayoffPlannerTest {
    private val jan = YearMonth.of(2027, 1)
    private val due = LocalDate.of(2027, 1, 10)

    private val itau = Card("itau", "Itaú", closingDay = 3, dueDay = 10, monthlyInterestBp = 1290)
    private val nubank = Card("nubank", "Nubank", closingDay = 3, dueDay = 10, monthlyInterestBp = 800)
    private val nubankSemJuros = nubank.copy(monthlyInterestBp = null)

    private fun parcela(id: String, card: String, amount: Long, n: Int) = CardDebt(id, card, DebtKind.PARCELA, id, amount, n, due)
    private fun rotativo(id: String, card: String, amount: Long) = CardDebt(id, card, DebtKind.ROTATIVO, id, amount, null, due)

    private fun plan(result: PayoffResult): PayoffResult.Plan {
        assertTrue("esperava um plano, veio $result", result is PayoffResult.Plan)
        return result as PayoffResult.Plan
    }

    @Test
    fun parcelasSemExtraTerminamNaUltimaParcela() {
        val result = plan(PayoffPlanner.plan(listOf(parcela("nb", "nubank", 30_000, 10)), listOf(nubank), 0, jan))
        assertEquals(YearMonth.of(2027, 10), result.debtFreeMonth)
        assertEquals(300_000, result.totalPaidCents)
        assertEquals(270_000, result.timeline.first().totalCents)
    }

    @Test
    fun extraAntecipaAQuitacao() {
        val result = plan(PayoffPlanner.plan(listOf(parcela("nb", "nubank", 30_000, 10)), listOf(nubank), 30_000, jan))
        assertEquals(YearMonth.of(2027, 5), result.debtFreeMonth) // 60.000 por mês
        assertEquals(YearMonth.of(2027, 5), result.payoffMonthByCard["nubank"])
    }

    @Test
    fun todosComJurosUsaAvalanche() {
        val debts = listOf(rotativo("i", "itau", 400_000), rotativo("n", "nubank", 300_000))
        val result = plan(PayoffPlanner.plan(debts, listOf(itau, nubank), 100_000, jan))
        assertEquals(PayoffStrategy.AVALANCHE, result.strategy)
        assertEquals(listOf("itau", "nubank"), result.order)
        assertTrue(result.explanation.contains("juros"))
        assertTrue("avalanche deve economizar", (result.savingsVsAlternativeCents ?: 0) > 0)
    }

    @Test
    fun cartaoSemJurosInformadoUsaMenorSaldo() {
        val debts = listOf(rotativo("i", "itau", 400_000), rotativo("n", "nubank", 300_000))
        val result = plan(PayoffPlanner.plan(debts, listOf(itau, nubankSemJuros), 100_000, jan))
        assertEquals(PayoffStrategy.BOLA_DE_NEVE, result.strategy)
        assertEquals(listOf("nubank", "itau"), result.order)
        assertTrue(result.explanation.contains("Nubank"))
    }

    @Test
    fun extraPassaParaOProximoCartaoAoZerar() {
        val debts = listOf(rotativo("a", "nubank", 50_000), rotativo("b", "itau", 100_000))
        val result = plan(PayoffPlanner.plan(debts, listOf(itau.copy(monthlyInterestBp = null), nubankSemJuros), 50_000, jan))
        assertEquals(YearMonth.of(2027, 1), result.payoffMonthByCard["nubank"])
        assertEquals(YearMonth.of(2027, 3), result.payoffMonthByCard["itau"])
    }

    @Test
    fun parcelaLiberadaSomaNoExtraDoProximoCartao() {
        // Nubank: 4 × 20.000 (80.000); Itaú: rotativo 100.000. Extra 40.000, sem juros.
        // Jan: parcela 20.000 + extra 40.000 → Nubank 20.000. Fev: parcela zera o Nubank; extra 40.000 → Itaú 60.000.
        // Mar: extra 40.000 + parcela liberada 20.000 → Itaú zera (sem a liberação, só em abril).
        val debts = listOf(parcela("a", "nubank", 20_000, 4), rotativo("b", "itau", 100_000))
        val result = plan(PayoffPlanner.plan(debts, listOf(itau.copy(monthlyInterestBp = null), nubankSemJuros), 40_000, jan))
        assertEquals(YearMonth.of(2027, 2), result.payoffMonthByCard["nubank"])
        assertEquals(YearMonth.of(2027, 3), result.debtFreeMonth)
    }

    @Test
    fun faturaAbertaEPagaNoVencimento() {
        val fatura = CardDebt("f", "nubank", DebtKind.FATURA_ABERTA, "Fatura", 50_000, null, LocalDate.of(2027, 2, 10))
        val result = plan(PayoffPlanner.plan(listOf(fatura), listOf(nubank), 0, jan))
        assertEquals(YearMonth.of(2027, 2), result.debtFreeMonth)
    }

    @Test
    fun jurosDoRotativoMaioresQueOPagamentoNuncaTerminam() {
        val result = PayoffPlanner.plan(listOf(rotativo("r", "itau", 100_000)), listOf(itau.copy(monthlyInterestBp = 1500)), 10_000, jan)
        assertTrue(result is PayoffResult.NeverPaysOff)
        assertTrue((result as PayoffResult.NeverPaysOff).message.isNotBlank())
    }

    @Test
    fun simuladorComExtraMaior() {
        val debts = listOf(parcela("nb", "nubank", 30_000, 10))
        val simulation = PayoffPlanner.simulate(debts, listOf(nubank), 30_000, jan, PayoffChange.ExtraPerMonth(15_000))
        assertEquals(YearMonth.of(2027, 5), simulation.currentFreeMonth)
        assertEquals(YearMonth.of(2027, 4), simulation.newFreeMonth) // 75.000 por mês
        assertEquals(-1, simulation.monthsDiff)
    }

    @Test
    fun simuladorQuitandoUmaDividaAgora() {
        val debts = listOf(parcela("nb", "nubank", 30_000, 10), parcela("it", "itau", 10_000, 3))
        val simulation = PayoffPlanner.simulate(debts, listOf(nubank, itau), 0, jan, PayoffChange.PayOffNow("nb"))
        assertEquals(YearMonth.of(2027, 10), simulation.currentFreeMonth)
        assertEquals(YearMonth.of(2027, 3), simulation.newFreeMonth)
        assertEquals(-7, simulation.monthsDiff)
    }

    @Test
    fun umCartaoSoNaoFalaEmOrdemDeJuros() {
        val result = plan(PayoffPlanner.plan(listOf(rotativo("n", "nubank", 100_000)), listOf(nubank), 30_000, jan))
        assertTrue(result.explanation, result.explanation.startsWith("Todo o extra vai para o Nubank"))
    }

    @Test
    fun semDividas() {
        val result = plan(PayoffPlanner.plan(emptyList(), listOf(nubank), 30_000, jan))
        assertEquals(null, result.debtFreeMonth)
        assertTrue(result.timeline.isEmpty())
    }
}
