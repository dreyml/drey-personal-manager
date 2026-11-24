package com.nxuslab.dreymanager.domain.water

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class WaterPlanTest {
    private val plan = WaterSettings(goalMl = 3000, bottleMl = 750, startMinute = 8 * 60, endMinute = 17 * 60, remindersOn = true)

    @Test fun cronogramaDivideAJanelaPorIgual() {
        assertEquals(4, WaterPlan.bottleCount(plan))
        assertEquals(listOf("10:15", "12:30", "14:45", "17:00"), WaterPlan.schedule(plan).map { it.toString() })
        assertEquals("2h15", WaterPlan.durationLabel(WaterPlan.intervalMinutes(plan)))
    }

    @Test fun metaQueNaoFechaEmGarrafasArredondaParaCima() {
        assertEquals(5, WaterPlan.bottleCount(plan.copy(goalMl = 3100)))
    }

    @Test fun idealSobeEmLinhaRetaDentroDaJanela() {
        assertEquals(0, WaterPlan.idealBy(plan, LocalTime.of(7, 0)))
        assertEquals(1666, WaterPlan.idealBy(plan, LocalTime.of(13, 0)))
        assertEquals(3000, WaterPlan.idealBy(plan, LocalTime.of(20, 0)))
    }

    @Test fun nivelDaGarrafaVemDoTotal() {
        val day = WaterPlan.today(plan, totalMl = 1050, now = LocalTime.of(13, 0))
        assertEquals(1, day.bottlesDone)
        assertEquals(450, day.leftInBottleMl)
        assertEquals(616, day.behindMl)
        assertEquals(SlotState.DONE, day.slots[0].state)
        assertEquals(SlotState.CURRENT, day.slots[1].state)
        assertTrue("passou das 12:30", day.slots[1].late)
        assertEquals(SlotState.LATER, day.slots[2].state)
    }

    @Test fun garrafaTerminadaEnchendeDeNovo() {
        val day = WaterPlan.today(plan, totalMl = 1500, now = LocalTime.of(13, 0))
        assertEquals(2, day.bottlesDone)
        assertEquals(750, day.leftInBottleMl)
    }

    @Test fun fasesDaJanela() {
        assertEquals(WaterPhase.BEFORE, WaterPlan.phase(plan, LocalTime.of(7, 59)))
        assertEquals(WaterPhase.DURING, WaterPlan.phase(plan, LocalTime.of(8, 0)))
        assertEquals(WaterPhase.AFTER, WaterPlan.phase(plan, LocalTime.of(17, 0)))
    }

    @Test fun sequenciaContaAteOntemEMaisHojeSeJaBateu() {
        val today = LocalDate.of(2026, 11, 24)
        val totals = mapOf(today.minusDays(1) to 3100, today.minusDays(2) to 3000, today.minusDays(3) to 2000, today to 1000)
        assertEquals(2, WaterPlan.streak(3000, totals, today))
        assertEquals(3, WaterPlan.streak(3000, totals + (today to 3000), today))
        val week = WaterPlan.week(3000, totals, today)
        assertEquals(7, week.size)
        assertTrue(week.last().today)
        assertFalse(week[3].hit)
    }

    @Test fun avisoSoQuandoAtrasadoDentroDaJanelaEUmaVezPorHora() {
        val one = LocalTime.of(13, 0)
        assertEquals(1666, WaterPlan.reminderDue(plan, 0, one, null))
        assertNull("em dia", WaterPlan.reminderDue(plan, 1500, one, null))
        assertNull("aviso recente", WaterPlan.reminderDue(plan, 0, one, 30))
        assertNull("fora da janela", WaterPlan.reminderDue(plan, 0, LocalTime.of(18, 0), null))
        assertNull("avisos desligados", WaterPlan.reminderDue(plan.copy(remindersOn = false), 0, one, null))
    }

    @Test fun rotulos() {
        assertEquals("750 ml", WaterPlan.litersLabel(750))
        assertEquals("1,5 L", WaterPlan.litersLabel(1500))
        assertEquals("3 L", WaterPlan.litersLabel(3000))
        assertTrue(WaterSettings.isValid(3000, 750, 480, 1020))
        assertFalse(WaterSettings.isValid(3000, 750, 1020, 480))
    }
}
