package com.nxuslab.dreymanager.domain.advice

import com.nxuslab.dreymanager.domain.daily.DailyView
import com.nxuslab.dreymanager.domain.money.Money
import com.nxuslab.dreymanager.domain.plan.ChecklistItem
import com.nxuslab.dreymanager.domain.plan.ChecklistKind
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class StepAction { PLANO, PREVISAO, CONTAS, CONTAS_VARIAVEIS, NONE }

data class NextStep(val title: String, val detail: String, val actionLabel: String?, val action: StepAction)

data class Shortfall(val date: LocalDate, val cents: Long)

/** Escolhe uma única ação clara para agora, pela ordem de urgência. */
object NextStepAdvisor {
    private val day = DateTimeFormatter.ofPattern("dd/MM")

    fun advise(
        today: LocalDate,
        shortfall: Shortfall?,
        checklist: List<ChecklistItem>,
        staleAccountName: String?,
        syncFailed: Boolean,
        daily: DailyView?,
        goalCardName: String?,
    ): NextStep {
        if (shortfall != null && ChronoUnit.DAYS.between(today, shortfall.date) in 0..7) {
            return NextStep(
                "Segure os gastos até ${shortfall.date.format(day)}",
                "Nesse dia faltam ${Money.format(shortfall.cents)} para pagar o que vence. Evite compras grandes até lá.",
                "Ver previsão", StepAction.PREVISAO,
            )
        }
        checklist.firstOrNull { !it.paid && it.kind != ChecklistKind.PAYOFF && it.dueDate != null && ChronoUnit.DAYS.between(today, it.dueDate) in 0..5 }?.let { item ->
            val goal = if (goalCardName != null && item.title.contains(goalCardName, ignoreCase = true)) " É o cartão que você quer cancelar." else ""
            return NextStep(
                "Pague ${item.title} até ${item.dueDate!!.format(day)}",
                "${Money.format(item.amountCents)}.$goal Depois, marque como pago no plano.",
                "Abrir o plano", StepAction.PLANO,
            )
        }
        checklist.firstOrNull { !it.paid && it.needsValue && it.dueDate != null && ChronoUnit.DAYS.between(today, it.dueDate) in 0..3 }?.let { item ->
            return NextStep(
                "Lance o valor da ${item.title}",
                "Vence em ${item.dueDate!!.format(day)}. O app está usando a estimativa de ${Money.format(item.amountCents)}.",
                "Lançar valor", StepAction.CONTAS_VARIAVEIS,
            )
        }
        if (staleAccountName != null || syncFailed) {
            val name = staleAccountName ?: "seus bancos"
            return NextStep(
                "Atualize o saldo de $name",
                if (syncFailed) "A última sincronização com os bancos não funcionou. Toque para tentar de novo ou corrija à mão."
                else "O salário já caiu, mas o saldo ainda é o de antes. Com o saldo certo, o valor de hoje fica certo.",
                "Corrigir saldo", StepAction.CONTAS,
            )
        }
        if (daily != null && daily.overTodayCents > 0) {
            return NextStep(
                "Hoje passou ${Money.format(daily.overTodayCents)}",
                daily.tomorrowCents?.let { "Tudo bem: amanhã fica ${Money.format(it)} para o dia." } ?: "Tudo bem: a semana que vem começa do zero.",
                null, StepAction.NONE,
            )
        }
        val left = daily?.remainingTodayCents
        return NextStep(
            if (left != null) "Hoje dá para gastar ${Money.format(left)}" else "Tudo em dia",
            if (goalCardName != null) "Cada real que sobra hoje ajuda o $goalCardName a zerar antes." else "O que sobrar hoje passa para amanhã.",
            null, StepAction.NONE,
        )
    }
}
