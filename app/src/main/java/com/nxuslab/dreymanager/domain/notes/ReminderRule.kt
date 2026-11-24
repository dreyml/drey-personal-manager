package com.nxuslab.dreymanager.domain.notes

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

enum class Repeat { ONCE, DAILY, WEEKLY, MONTHLY }

/** Quando um lembrete toca. [weekDays]: seg = bit 0 … dom = bit 6 (só para WEEKLY). */
data class ReminderRule(
    val startDate: LocalDate,
    val minuteOfDay: Int,
    val repeat: Repeat,
    val weekDays: Int = 0,
) {
    val time: LocalTime get() = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)

    fun isValid(): Boolean = minuteOfDay in 0 until 24 * 60 && (repeat != Repeat.WEEKLY || weekDays and 0x7F != 0)

    companion object {
        fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)
    }
}

object ReminderSchedule {
    /** Próxima ocorrência estritamente depois de [after]; para ONCE, a única (mesmo se já passou). */
    fun next(rule: ReminderRule, after: LocalDateTime): LocalDateTime? {
        if (!rule.isValid()) return null
        val time = rule.time
        return when (rule.repeat) {
            Repeat.ONCE -> rule.startDate.atTime(time)
            Repeat.DAILY -> firstDay(rule, after) { true }
            Repeat.WEEKLY -> firstDay(rule, after) { rule.weekDays and ReminderRule.bit(it.dayOfWeek) != 0 }
            Repeat.MONTHLY -> {
                var month = YearMonth.from(maxOf(rule.startDate, after.toLocalDate()))
                repeat(3) {
                    val day = monthDay(rule.startDate.dayOfMonth, month).atTime(time)
                    if (day.isAfter(after) && !day.toLocalDate().isBefore(rule.startDate)) return day
                    month = month.plusMonths(1)
                }
                null
            }
        }
    }

    /** Dia do mês, ou o último dia quando o mês é mais curto. */
    fun monthDay(dayOfMonth: Int, month: YearMonth): LocalDate = month.atDay(minOf(dayOfMonth, month.lengthOfMonth()))

    private inline fun firstDay(rule: ReminderRule, after: LocalDateTime, matches: (LocalDate) -> Boolean): LocalDateTime? {
        var d = maxOf(rule.startDate, after.toLocalDate())
        repeat(8) {
            val at = d.atTime(rule.time)
            if (at.isAfter(after) && matches(d)) return at
            d = d.plusDays(1)
        }
        return null
    }

    private val shortDays = listOf("seg", "ter", "qua", "qui", "sex", "sáb", "dom")

    fun daysLabel(mask: Int): String = DayOfWeek.entries.filter { mask and ReminderRule.bit(it) != 0 }.joinToString(", ") { shortDays[it.value - 1] }

    /** "uma vez", "todo dia", "seg, qua, sex", "todo dia 24". */
    fun repeatLabel(rule: ReminderRule): String = when (rule.repeat) {
        Repeat.ONCE -> "uma vez"
        Repeat.DAILY -> "todo dia"
        Repeat.WEEKLY -> if (rule.weekDays and 0x7F == 0x7F) "todo dia" else daysLabel(rule.weekDays)
        Repeat.MONTHLY -> "todo dia ${rule.startDate.dayOfMonth}"
    }

    /** Resumo do editor: "Avisa às 15:00 toda seg, qua, sex." */
    fun summary(rule: ReminderRule, today: LocalDate): String {
        val t = rule.time.toString()
        return when (rule.repeat) {
            Repeat.ONCE -> "Avisa uma vez: ${dayLabel(rule.startDate, today)}, às $t."
            Repeat.DAILY -> "Avisa todo dia às $t."
            Repeat.WEEKLY -> if (rule.weekDays and 0x7F == 0) "Escolha pelo menos um dia." else "Avisa às $t toda ${daysLabel(rule.weekDays)}."
            Repeat.MONTHLY -> "Avisa todo dia ${rule.startDate.dayOfMonth} às $t." +
                if (rule.startDate.dayOfMonth > 28) " Em meses sem esse dia, no último dia." else ""
        }
    }

    fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "hoje, %02d/%02d".format(date.dayOfMonth, date.monthValue)
        today.plusDays(1) -> "amanhã, %02d/%02d".format(date.dayOfMonth, date.monthValue)
        else -> "%02d/%02d".format(date.dayOfMonth, date.monthValue)
    }
}
