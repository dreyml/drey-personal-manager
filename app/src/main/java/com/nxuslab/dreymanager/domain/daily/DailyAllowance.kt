package com.nxuslab.dreymanager.domain.daily

import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class DayState { OK, OVER, TODAY, FUTURE }

data class DayStatus(val date: LocalDate, val state: DayState)

/**
 * Quanto dá para gastar hoje. Nunca negativo: acima do valor do dia aparece [overTodayCents]
 * e quanto fica para amanhã.
 */
data class DailyView(
    val todayAllowanceCents: Long,
    val spentTodayCents: Long,
    val remainingTodayCents: Long,
    val overTodayCents: Long,
    /** Valor de amanhã se hoje terminar como está; nulo no último dia da semana. */
    val tomorrowCents: Long?,
    /** Quanto o valor de hoje ganhou com o que sobrou dos dias anteriores (0 se nada sobrou). */
    val carriedCents: Long,
    val days: List<DayStatus>,
)

/**
 * Divide o limite da semana por dia: cada dia recebe o que resta da semana dividido pelos dias que
 * faltam (incluindo ele). O que sobra de um dia aumenta os seguintes; o que passa diminui.
 */
object DailyAllowance {

    fun compute(today: LocalDate, weekStart: LocalDate, weekLimitCents: Long, spentByDay: Map<LocalDate, Long>): DailyView {
        val todayIndex = ChronoUnit.DAYS.between(weekStart, today).toInt().coerceIn(0, 6)
        var remaining = weekLimitCents
        val days = mutableListOf<DayStatus>()
        var todayAllowance = 0L
        for (i in 0..6) {
            val date = weekStart.plusDays(i.toLong())
            when {
                i < todayIndex -> {
                    val allowance = remaining.coerceAtLeast(0) / (7 - i)
                    val spent = spentByDay[date] ?: 0
                    days += DayStatus(date, if (spent > allowance) DayState.OVER else DayState.OK)
                    remaining -= spent
                }
                i == todayIndex -> {
                    todayAllowance = remaining.coerceAtLeast(0) / (7 - i)
                    days += DayStatus(date, DayState.TODAY)
                }
                else -> days += DayStatus(date, DayState.FUTURE)
            }
        }
        val spentToday = spentByDay[today] ?: 0
        val afterToday = remaining - spentToday
        val daysAfter = 6 - todayIndex
        return DailyView(
            todayAllowanceCents = todayAllowance,
            spentTodayCents = spentToday,
            remainingTodayCents = (todayAllowance - spentToday).coerceAtLeast(0),
            overTodayCents = (spentToday - todayAllowance).coerceAtLeast(0),
            tomorrowCents = if (daysAfter > 0) afterToday.coerceAtLeast(0) / daysAfter else null,
            carriedCents = (todayAllowance - weekLimitCents.coerceAtLeast(0) / 7).coerceAtLeast(0),
            days = days,
        )
    }
}
