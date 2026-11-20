package com.nxuslab.dreymanager.notifications

import com.nxuslab.dreymanager.data.repo.FinanceSnapshot
import com.nxuslab.dreymanager.domain.budget.WeekBudget
import com.nxuslab.dreymanager.domain.model.Wish
import com.nxuslab.dreymanager.domain.model.WishStatus
import com.nxuslab.dreymanager.domain.money.Money
import com.nxuslab.dreymanager.feature.common.forecast
import com.nxuslab.dreymanager.feature.common.upcoming
import com.nxuslab.dreymanager.feature.common.week
import com.nxuslab.dreymanager.feature.common.wishProjections
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Uma notificação pronta para mostrar, com a tela que ela abre. */
data class PlannedNotice(val key: String, val channel: Channel, val title: String, val text: String, val route: String)

enum class Channel(val id: String, val label: String) {
    LEMBRETES("lembretes", "Lembretes"),
    VENCIMENTOS("vencimentos", "Vencimentos"),
    ALERTAS("alertas", "Alertas"),
}

/**
 * Decide o que avisar em cada dia (especificação §8). Kotlin puro: o worker só mostra o resultado.
 */
object ReminderPlanner {
    private val dayMonth = DateTimeFormatter.ofPattern("dd/MM")

    /** Lembrete da noite: lançou tudo de hoje? */
    fun evening(snapshot: FinanceSnapshot, today: LocalDate): PlannedNotice? {
        val week = snapshot.week(today) as? WeekBudget.Ready ?: return null
        val left = if (week.remainingCents >= 0) "Restam ${Money.format(week.remainingCents)} na semana." else "A semana está ${Money.format(-week.remainingCents)} acima do limite."
        return PlannedNotice("noite", Channel.LEMBRETES, "Lançou tudo de hoje?", left, "gastos")
    }

    /** Avisos da manhã: vencimentos de amanhã, falta prevista, desejos prontos e resumo semanal. */
    fun morning(snapshot: FinanceSnapshot, today: LocalDate): List<PlannedNotice> = buildList {
        val tomorrow = today.plusDays(1)
        val month = java.time.YearMonth.from(tomorrow)
        val pendingVariable = snapshot.variableBills.filter { it.active && snapshot.variableActuals[it.id to month] == null }.map { it.title }.toSet()
        snapshot.upcoming(today, days = 1).filter { it.date == tomorrow }.forEach {
            if (it.title in pendingVariable) {
                add(PlannedNotice("vence-${it.title}", Channel.VENCIMENTOS, "Amanhã vence ${it.title}", "Lance o valor real da conta (estimativa ${Money.format(it.amountCents)}).", "mais"))
            } else {
                add(PlannedNotice("vence-${it.title}", Channel.VENCIMENTOS, "Amanhã vence ${it.title}", Money.format(it.amountCents), "home"))
            }
        }
        // Sem saldo cadastrado não há como prever falta de verdade.
        snapshot.forecast(today).shortfalls.takeIf { snapshot.accounts.isNotEmpty() }.orEmpty().firstOrNull { !it.date.isAfter(today.plusDays(3)) }?.let {
            add(
                PlannedNotice(
                    "falta", Channel.ALERTAS, "Vai faltar dinheiro",
                    "Em ${it.date.format(dayMonth)} faltam ${Money.format(it.cents)} para ${it.reason.replaceFirstChar { c -> c.lowercase() }}.",
                    "plano?aba=PREVISAO",
                ),
            )
        }
        newlyReadyWishes(snapshot, today).forEach {
            add(PlannedNotice("desejo-${it.id}", Channel.ALERTAS, "Pronto!", "Você já pode comprar ${it.name} à vista.", "plano?aba=DESEJOS"))
        }
        weeklySummary(snapshot, today)?.let { add(it) }
    }

    /** Desejos que atingiram o preço e ainda não foram marcados como prontos. */
    fun newlyReadyWishes(snapshot: FinanceSnapshot, today: LocalDate): List<Wish> =
        snapshot.wishProjections(today).filter { it.ready && it.wish.status == WishStatus.JUNTANDO }.map { it.wish }

    /** No primeiro dia da semana: como foi a anterior e o limite da nova. */
    fun weeklySummary(snapshot: FinanceSnapshot, today: LocalDate): PlannedNotice? {
        if (today.dayOfWeek != snapshot.settings.budget.weekStart) return null
        val previous = snapshot.week(today.minusWeeks(1)) as? WeekBudget.Ready ?: return null
        val current = snapshot.week(today) as? WeekBudget.Ready ?: return null
        val verdict = if (previous.remainingCents >= 0) "dentro do limite" else "${Money.format(-previous.remainingCents)} acima"
        return PlannedNotice(
            "semana", Channel.LEMBRETES, "Nova semana: ${Money.format(current.limitCents)}",
            "Semana passada: ${Money.format(previous.spentCents)} de ${Money.format(previous.limitCents)} ($verdict).",
            "home",
        )
    }
}
