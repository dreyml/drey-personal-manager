package com.nxuslab.dreymanager.domain.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

class ReminderRuleTest {
    private val tuesday = LocalDate.of(2026, 11, 24)
    private val at15 = 15 * 60

    @Test fun umaVezDevolveADataMesmoSeJaPassou() {
        val rule = ReminderRule(tuesday, at15, Repeat.ONCE)
        assertEquals(tuesday.atTime(15, 0), ReminderSchedule.next(rule, tuesday.atTime(16, 0)))
    }

    @Test fun todoDiaPulaParaAmanhaDepoisDoHorario() {
        val rule = ReminderRule(tuesday, at15, Repeat.DAILY)
        assertEquals(tuesday.atTime(15, 0), ReminderSchedule.next(rule, tuesday.atTime(10, 0)))
        assertEquals(tuesday.plusDays(1).atTime(15, 0), ReminderSchedule.next(rule, tuesday.atTime(15, 0)))
    }

    @Test fun naoComecaAntesDaDataDeInicio() {
        val rule = ReminderRule(tuesday.plusDays(3), at15, Repeat.DAILY)
        assertEquals(tuesday.plusDays(3).atTime(15, 0), ReminderSchedule.next(rule, tuesday.atTime(8, 0)))
    }

    @Test fun diasDaSemanaEscolhidos() {
        val mask = ReminderRule.bit(DayOfWeek.MONDAY) or ReminderRule.bit(DayOfWeek.WEDNESDAY) or ReminderRule.bit(DayOfWeek.FRIDAY)
        val rule = ReminderRule(tuesday, at15, Repeat.WEEKLY, mask)
        assertEquals(LocalDate.of(2026, 11, 25).atTime(15, 0), ReminderSchedule.next(rule, tuesday.atTime(9, 0)))
        assertEquals(LocalDate.of(2026, 11, 30).atTime(15, 0), ReminderSchedule.next(rule, LocalDateTime.of(2026, 11, 27, 16, 0)))
        assertEquals("seg, qua, sex", ReminderSchedule.repeatLabel(rule))
    }

    @Test fun semanaSemDiaNaoEValida() {
        val rule = ReminderRule(tuesday, at15, Repeat.WEEKLY, 0)
        assertFalse(rule.isValid())
        assertNull(ReminderSchedule.next(rule, tuesday.atTime(9, 0)))
    }

    @Test fun todoMesUsaOUltimoDiaEmMesCurto() {
        val rule = ReminderRule(LocalDate.of(2026, 1, 31), 8 * 60, Repeat.MONTHLY)
        assertEquals(LocalDate.of(2026, 2, 28).atTime(8, 0), ReminderSchedule.next(rule, LocalDateTime.of(2026, 2, 1, 0, 0)))
        assertEquals(LocalDate.of(2026, 3, 31).atTime(8, 0), ReminderSchedule.next(rule, LocalDateTime.of(2026, 2, 28, 9, 0)))
    }

    @Test fun resumos() {
        assertEquals("Avisa todo dia às 15:00.", ReminderSchedule.summary(ReminderRule(tuesday, at15, Repeat.DAILY), tuesday))
        assertEquals("Avisa uma vez: amanhã, 25/11, às 15:00.", ReminderSchedule.summary(ReminderRule(tuesday.plusDays(1), at15, Repeat.ONCE), tuesday))
    }
}
