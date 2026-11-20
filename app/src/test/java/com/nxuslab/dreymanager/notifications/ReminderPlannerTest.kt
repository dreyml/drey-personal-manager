package com.nxuslab.dreymanager.notifications

import com.nxuslab.dreymanager.data.repo.Account
import com.nxuslab.dreymanager.data.repo.FinanceSnapshot
import com.nxuslab.dreymanager.domain.model.FixedBill
import com.nxuslab.dreymanager.domain.model.Income
import com.nxuslab.dreymanager.domain.model.IncomeKind
import com.nxuslab.dreymanager.domain.model.Wish
import com.nxuslab.dreymanager.domain.model.WishPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReminderPlannerTest {
    private val wednesday = LocalDate.of(2027, 1, 13)
    private val snapshot = FinanceSnapshot(
        incomes = listOf(Income("s", IncomeKind.FIXA, 310_000, dayOfMonth = 5)),
        accounts = listOf(Account("c", "Conta", 1_000_000, wednesday)),
        fixedBills = listOf(FixedBill("i", "Internet", 9_990, 14)),
    )

    @Test
    fun lembreteDaNoiteMostraOQueResta() {
        val notice = ReminderPlanner.evening(snapshot, wednesday)!!
        assertEquals("Lançou tudo de hoje?", notice.title)
        assertTrue(notice.text.startsWith("Restam"))
    }

    @Test
    fun semRendaNaoHaLembreteDaNoite() {
        assertNull(ReminderPlanner.evening(snapshot.copy(incomes = emptyList()), wednesday))
    }

    @Test
    fun vencimentoDeAmanha() {
        val notices = ReminderPlanner.morning(snapshot, wednesday)
        assertEquals(listOf("Amanhã vence Internet"), notices.filter { it.channel == Channel.VENCIMENTOS }.map { it.title })
    }

    @Test
    fun faltaPrevistaNosProximosTresDias() {
        val broke = snapshot.copy(accounts = listOf(Account("c", "Conta", 0, wednesday)))
        val notice = ReminderPlanner.morning(broke, wednesday).single { it.key == "falta" }
        assertEquals("plano?aba=PREVISAO", notice.route)
    }

    @Test
    fun semSaldoCadastradoNaoAvisaFalta() {
        val noAccount = snapshot.copy(accounts = emptyList())
        assertTrue(ReminderPlanner.morning(noAccount, wednesday).none { it.key == "falta" })
    }

    @Test
    fun desejoQueAtingiuOPreco() {
        val ready = snapshot.copy(wishes = listOf(Wish("w", "Fone", 50_000, priority = WishPriority.MEDIA)), savedByWish = mapOf("w" to 50_000L))
        assertEquals(listOf("w"), ReminderPlanner.newlyReadyWishes(ready, wednesday).map { it.id })
    }

    @Test
    fun resumoSemanalSoNoPrimeiroDiaDaSemana() {
        assertNull(ReminderPlanner.weeklySummary(snapshot, wednesday))
        val monday = LocalDate.of(2027, 1, 11)
        assertTrue(ReminderPlanner.weeklySummary(snapshot, monday)!!.title.startsWith("Nova semana"))
    }
}
