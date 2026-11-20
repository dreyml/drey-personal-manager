package com.nxuslab.dreymanager.domain.wish

import com.nxuslab.dreymanager.domain.model.Wish
import com.nxuslab.dreymanager.domain.model.WishPriority
import com.nxuslab.dreymanager.domain.model.WishStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WishPlannerTest {
    private val today = LocalDate.of(2027, 1, 13)

    private val fone = Wish("fone", "Fone", 120_000, targetDate = LocalDate.of(2027, 5, 13), priority = WishPriority.ALTA)
    private val tenis = Wish("tenis", "Tênis", 50_000, priority = WishPriority.MEDIA, createdAt = 1)
    private val livro = Wish("livro", "Livro", 30_000, priority = WishPriority.BAIXA, createdAt = 2)

    @Test
    fun altaComDataCumpreEReduzASemana() {
        val projection = WishPlanner.plan(listOf(fone), mapOf("fone" to 20_000L), today, monthlyLeftoverCents = 0).single()
        assertEquals(100_000, projection.remainingCents)
        assertEquals(25_000, projection.monthlyNeededCents) // 4 meses
        assertEquals(LocalDate.of(2027, 5, 13), projection.realisticDate)
        assertEquals(5_770, projection.weeklyReductionCents) // 25.000 × 12 ÷ 52, arredondado para cima
    }

    @Test
    fun altaSemDataUsaSeisMeses() {
        val projection = WishPlanner.plan(listOf(fone.copy(targetDate = null)), emptyMap(), today, 0).single()
        assertEquals(20_000, projection.monthlyNeededCents)
        assertEquals(LocalDate.of(2027, 7, 13), projection.realisticDate)
    }

    @Test
    fun mediaEBaixaRecebemSoASobraEmOrdem() {
        val projections = WishPlanner.plan(listOf(livro, tenis), emptyMap(), today, monthlyLeftoverCents = 10_000).associateBy { it.wish.id }
        assertEquals(LocalDate.of(2027, 6, 13), projections.getValue("tenis").realisticDate) // 5 meses
        assertEquals(LocalDate.of(2027, 9, 13), projections.getValue("livro").realisticDate) // depois do tênis: 8 meses
        assertEquals(0, projections.getValue("tenis").weeklyReductionCents)
    }

    @Test
    fun semSobraNaoHaDataEOAppExplica() {
        val projection = WishPlanner.plan(listOf(tenis), emptyMap(), today, monthlyLeftoverCents = 0).single()
        assertNull(projection.realisticDate)
        assertTrue(projection.message!!.contains("prioridade alta"))
    }

    @Test
    fun desejoPronto() {
        val projection = WishPlanner.plan(listOf(tenis), mapOf("tenis" to 50_000L), today, 0).single()
        assertTrue(projection.ready)
        assertEquals(0, projection.remainingCents)
        assertTrue(projection.message!!.contains("à vista"))
    }

    @Test
    fun compradosEDesistidosFicamDeFora() {
        val projections = WishPlanner.plan(listOf(tenis.copy(status = WishStatus.COMPRADO), livro.copy(status = WishStatus.DESISTI)), emptyMap(), today, 10_000)
        assertTrue(projections.isEmpty())
    }

    @Test
    fun somaMensalDasAltas() {
        val projections = WishPlanner.plan(listOf(fone, tenis), mapOf("fone" to 20_000L), today, 10_000)
        assertEquals(25_000, WishPlanner.highPriorityMonthlyCents(projections))
    }

    @Test
    fun comparacaoComCartaoSemJuros() {
        val comparison = WishPlanner.compareWithCard(100_000, installments = 10, monthlyInterestBp = null, payoffExtraMonthlyCents = 30_000)
        assertEquals(10_000, comparison.installmentCents)
        assertEquals(0, comparison.extraCostCents)
        assertEquals(4, comparison.payoffDelayMonths)
        assertTrue(comparison.message.contains("10×"))
    }

    @Test
    fun comparacaoComCartaoComJuros() {
        val comparison = WishPlanner.compareWithCard(100_000, installments = 10, monthlyInterestBp = 299, payoffExtraMonthlyCents = 0)
        assertTrue(comparison.installmentCents in 11_700..11_730)
        assertEquals(comparison.installmentCents * 10 - 100_000, comparison.extraCostCents)
        assertNull(comparison.payoffDelayMonths)
    }
}
