package com.nxuslab.dreymanager.domain.water

import java.time.LocalDate
import java.time.LocalTime

/** Plano de água do usuário. Horários em minutos desde 00:00; fim sempre depois do início. */
data class WaterSettings(
    val goalMl: Int,
    val bottleMl: Int,
    val startMinute: Int,
    val endMinute: Int,
    val remindersOn: Boolean,
) {
    val start: LocalTime get() = LocalTime.of(startMinute / 60, startMinute % 60)
    val end: LocalTime get() = LocalTime.of(endMinute / 60, endMinute % 60)

    companion object {
        val GOAL_RANGE = 500..8000
        val BOTTLE_RANGE = 100..3000
        fun isValid(goalMl: Int, bottleMl: Int, startMinute: Int, endMinute: Int) =
            goalMl in GOAL_RANGE && bottleMl in BOTTLE_RANGE && startMinute in 0 until 24 * 60 &&
                endMinute in 1..(24 * 60 - 1) && endMinute > startMinute
    }
}

enum class WaterPhase { BEFORE, DURING, AFTER }
enum class SlotState { DONE, CURRENT, LATER }

data class BottleSlot(val number: Int, val deadline: LocalTime, val state: SlotState, val late: Boolean)

data class WaterDay(
    val totalMl: Int,
    val goalMl: Int,
    val bottleMl: Int,
    val bottleCount: Int,
    val bottlesDone: Int,
    /** Quanto ainda está dentro da garrafa atual. */
    val leftInBottleMl: Int,
    val idealMl: Int,
    val behindMl: Int,
    val phase: WaterPhase,
    val slots: List<BottleSlot>,
) {
    val goalReached: Boolean get() = totalMl >= goalMl
    val progress: Float get() = (totalMl.toFloat() / goalMl).coerceIn(0f, 1f)
}

data class DayBar(val date: LocalDate, val totalMl: Int, val hit: Boolean, val today: Boolean)

object WaterPlan {
    /** Atraso a partir do qual a tela mostra o ritmo em pêssego. */
    const val BEHIND_SHOW_ML = 150
    /** Atraso a partir do qual o aviso é enviado. */
    const val BEHIND_REMIND_ML = 250
    const val REMIND_GAP_MINUTES = 60L

    fun bottleCount(s: WaterSettings): Int = (s.goalMl + s.bottleMl - 1) / s.bottleMl

    /** Minutos entre uma garrafa e outra. */
    fun intervalMinutes(s: WaterSettings): Int = (s.endMinute - s.startMinute) / bottleCount(s)

    /** Horário-limite de cada garrafa, distribuído por igual na janela. A última termina no fim. */
    fun schedule(s: WaterSettings): List<LocalTime> {
        val n = bottleCount(s)
        val span = s.endMinute - s.startMinute
        return (1..n).map { k ->
            val m = s.startMinute + span * k / n
            LocalTime.of(m / 60, m % 60)
        }
    }

    fun phase(s: WaterSettings, now: LocalTime): WaterPhase {
        val m = now.hour * 60 + now.minute
        return when {
            m < s.startMinute -> WaterPhase.BEFORE
            m >= s.endMinute -> WaterPhase.AFTER
            else -> WaterPhase.DURING
        }
    }

    /** Quanto já deveria ter bebido até [now], em linha reta pela janela. */
    fun idealBy(s: WaterSettings, now: LocalTime): Int {
        val m = now.hour * 60 + now.minute
        val span = s.endMinute - s.startMinute
        val elapsed = (m - s.startMinute).coerceIn(0, span)
        return (s.goalMl.toLong() * elapsed / span).toInt()
    }

    fun today(s: WaterSettings, totalMl: Int, now: LocalTime): WaterDay {
        val n = bottleCount(s)
        val done = totalMl / s.bottleMl
        val left = s.bottleMl - totalMl % s.bottleMl
        val ideal = idealBy(s, now)
        val slots = schedule(s).mapIndexed { i, t ->
            val state = when {
                i < done -> SlotState.DONE
                i == done -> SlotState.CURRENT
                else -> SlotState.LATER
            }
            BottleSlot(i + 1, t, state, late = state == SlotState.CURRENT && now.isAfter(t))
        }
        return WaterDay(
            totalMl = totalMl, goalMl = s.goalMl, bottleMl = s.bottleMl, bottleCount = n,
            bottlesDone = done, leftInBottleMl = left, idealMl = ideal,
            behindMl = (ideal - totalMl).coerceAtLeast(0), phase = phase(s, now), slots = slots,
        )
    }

    /** Os últimos 7 dias, terminando em [today]. */
    fun week(goalMl: Int, totals: Map<LocalDate, Int>, today: LocalDate): List<DayBar> =
        (6 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            val ml = totals[d] ?: 0
            DayBar(d, ml, ml >= goalMl, back == 0)
        }

    /** Dias seguidos batendo a meta: conta de ontem para trás, mais hoje se já bateu. */
    fun streak(goalMl: Int, totals: Map<LocalDate, Int>, today: LocalDate): Int {
        var count = if ((totals[today] ?: 0) >= goalMl) 1 else 0
        var d = today.minusDays(1)
        while ((totals[d] ?: 0) >= goalMl) {
            count++
            d = d.minusDays(1)
        }
        return count
    }

    /**
     * Quanto falta para o ritmo, se for hora de avisar; null se não. Só dentro da janela, com atraso
     * de pelo menos [BEHIND_REMIND_ML] e sem outro aviso na última hora.
     */
    fun reminderDue(s: WaterSettings, totalMl: Int, now: LocalTime, minutesSinceLast: Long?): Int? {
        if (!s.remindersOn || phase(s, now) != WaterPhase.DURING) return null
        if (minutesSinceLast != null && minutesSinceLast < REMIND_GAP_MINUTES) return null
        val behind = idealBy(s, now) - totalMl
        return behind.takeIf { it >= BEHIND_REMIND_ML }
    }

    /** "2h15", "45 min", "3h". */
    fun durationLabel(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "${h}h"
            else -> "${h}h" + m.toString().padStart(2, '0')
        }
    }

    /** "1,5 L" ou "750 ml". */
    fun litersLabel(ml: Int): String =
        if (ml < 1000) "$ml ml" else {
            val tenths = (ml + 50) / 100
            if (tenths % 10 == 0) "${tenths / 10} L" else "${tenths / 10},${tenths % 10} L"
        }
}
