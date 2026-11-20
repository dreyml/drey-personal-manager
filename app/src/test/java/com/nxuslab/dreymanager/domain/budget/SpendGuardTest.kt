package com.nxuslab.dreymanager.domain.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class SpendGuardTest {
    private val today = LocalDate.of(2027, 1, 13)

    private fun week(limit: Long = 40_000, spent: Long = 0) = WeekBudget.Ready(
        start = LocalDate.of(2027, 1, 11),
        end = LocalDate.of(2027, 1, 17),
        suggestedLimitCents = limit,
        limitCents = limit,
        carriedOverExcessCents = 0,
        spentCents = spent,
        remainingCents = limit - spent,
        daysLeft = 5,
        perDayCents = (limit - spent) / 5,
        overrideApplied = false,
    )

    private val delivery = EnvelopeState("delivery", "Delivery", limitCents = 12_000, spentCents = 3_800)
    private val nubank = CardContext("Nubank", closingDay = 3, dueDay = 10, purchasesThisMonthBefore = 1, payoffExtraMonthlyCents = 30_000)

    @Test
    fun comFolgaSalvaDireto() {
        val result = SpendGuard.evaluate(1_000, today, week(), delivery, card = null)
        assertTrue(result is GuardResult.Ok)
        assertNull(result.card)
    }

    @Test
    fun oitentaPorCentoDaSemanaAvisa() {
        val result = SpendGuard.evaluate(2_000, today, week(limit = 40_000, spent = 30_000), envelope = null, card = null)
        assertTrue(result is GuardResult.Warning) // 32.000 de 40.000 = 80%
    }

    @Test
    fun oitentaPorCentoDoEnvelopeAvisa() {
        val result = SpendGuard.evaluate(6_000, today, week(), delivery, card = null)
        assertTrue(result is GuardResult.Warning) // 9.800 de 12.000
        assertTrue((result as GuardResult.Warning).message.contains("Delivery"))
    }

    @Test
    fun estouroDaSemanaPedeConfirmacao() {
        val result = SpendGuard.evaluate(5_000, today, week(limit = 40_000, spent = 38_000), envelope = null, card = null)
        assertTrue(result is GuardResult.Confirm)
        result as GuardResult.Confirm
        assertEquals(3_000, result.excessCents)
        assertTrue(result.message.contains("próxima semana"))
    }

    @Test
    fun estouroSoDoEnvelopePedeConfirmacao() {
        val result = SpendGuard.evaluate(10_500, today, week(), delivery, card = null)
        assertTrue(result is GuardResult.Confirm)
        result as GuardResult.Confirm
        assertEquals("Isso estoura seu limite de Delivery", result.title)
        assertEquals(2_300, result.excessCents) // 14.300 − 12.000
    }

    @Test
    fun semanaJaNegativaSempreConfirma() {
        val result = SpendGuard.evaluate(100, today, week(limit = 40_000, spent = 41_000), envelope = null, card = null)
        assertTrue(result is GuardResult.Confirm)
    }

    @Test
    fun cartaoDentroDoLimiteMostraReserva() {
        val result = SpendGuard.evaluate(8_900, today, week(), envelope = null, card = nubank)
        assertTrue(result is GuardResult.Ok)
        val card = assertNotNullCard(result)
        assertEquals(8_900, card.reservedCents)
        assertEquals(2, card.purchasesThisMonth)
        assertEquals(YearMonth.of(2027, 2), card.invoiceMonth) // fecha 3/fev, vence 10/fev
        assertNull(card.payoffDelayDays)
    }

    @Test
    fun cartaoEstourandoInformaAtrasoNaQuitacao() {
        val result = SpendGuard.evaluate(13_000, today, week(limit = 40_000, spent = 37_000), envelope = null, card = nubank)
        assertTrue(result is GuardResult.Confirm)
        val card = assertNotNullCard(result)
        assertEquals(10, card.payoffDelayDays) // excesso 10.000 de 30.000/mês = 10 dias
    }

    @Test
    fun mesDaFatura() {
        // Fecha dia 3, vence dia 10: compra em 13/jan entra na fatura que fecha 3/fev e vence 10/fev.
        assertEquals(YearMonth.of(2027, 2), SpendGuard.invoiceDueMonth(LocalDate.of(2027, 1, 13), closingDay = 3, dueDay = 10))
        // Compra antes do fechamento entra na fatura do mês.
        assertEquals(YearMonth.of(2027, 1), SpendGuard.invoiceDueMonth(LocalDate.of(2027, 1, 2), closingDay = 3, dueDay = 10))
        // Fecha dia 25, vence dia 5 do mês seguinte.
        assertEquals(YearMonth.of(2027, 2), SpendGuard.invoiceDueMonth(LocalDate.of(2027, 1, 20), closingDay = 25, dueDay = 5))
        assertEquals(YearMonth.of(2027, 3), SpendGuard.invoiceDueMonth(LocalDate.of(2027, 1, 26), closingDay = 25, dueDay = 5))
    }

    @Test
    fun fechamentoDia31EmMesDe30Dias() {
        // Abril tem 30 dias: o fechamento cai em 30/abr; compra no dia 30 já vai para a fatura seguinte.
        assertEquals(YearMonth.of(2027, 5), SpendGuard.invoiceDueMonth(LocalDate.of(2027, 4, 29), closingDay = 31, dueDay = 8))
        assertEquals(YearMonth.of(2027, 6), SpendGuard.invoiceDueMonth(LocalDate.of(2027, 4, 30), closingDay = 31, dueDay = 8))
    }

    private fun assertNotNullCard(result: GuardResult): CardNotice {
        assertNotNull("esperava aviso de cartão", result.card)
        return result.card!!
    }
}
