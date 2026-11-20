package com.nxuslab.dreymanager.domain.payoff

import com.nxuslab.dreymanager.domain.model.Card
import com.nxuslab.dreymanager.domain.model.CardDebt
import com.nxuslab.dreymanager.domain.model.DebtKind
import com.nxuslab.dreymanager.domain.money.Money
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class PayoffStrategy { AVALANCHE, BOLA_DE_NEVE }

/** Saldos ao fim de um mês do plano. */
data class PayoffMonth(val month: YearMonth, val balanceByCard: Map<String, Long>, val totalCents: Long)

sealed interface PayoffResult {
    data class Plan(
        val strategy: PayoffStrategy,
        val explanation: String,
        /** Ordem de quitação (ids dos cartões). */
        val order: List<String>,
        val timeline: List<PayoffMonth>,
        val payoffMonthByCard: Map<String, YearMonth>,
        /** Mês em que todas as dívidas zeram; nulo quando não há dívidas. */
        val debtFreeMonth: YearMonth?,
        val totalPaidCents: Long,
        /** Juros economizados em relação à outra estratégia (só para avalanche). */
        val savingsVsAlternativeCents: Long?,
    ) : PayoffResult

    data class NeverPaysOff(val message: String, val strategy: PayoffStrategy, val order: List<String>) : PayoffResult
}

sealed interface PayoffChange {
    data class ExtraPerMonth(val cents: Long) : PayoffChange
    data class PayOffNow(val debtId: String) : PayoffChange
}

data class Simulation(
    val currentFreeMonth: YearMonth?,
    val newFreeMonth: YearMonth?,
    /** Diferença em meses (negativo = termina antes). Nulo se algum dos planos não termina. */
    val monthsDiff: Int?,
    val newPlan: PayoffResult,
)

/**
 * Plano de saída do cartão (especificação §6.6).
 *
 * Cada mês: (1) o rotativo rende os juros do cartão; (2) faturas e parcelas do mês são pagas;
 * (3) o extra de quitação, mais as parcelas de dívidas que já foram zeradas antes do prazo, vai
 * para o primeiro cartão da ordem; o que sobrar passa para o próximo.
 */
object PayoffPlanner {
    const val MAX_MONTHS = 120

    fun plan(debts: List<CardDebt>, cards: List<Card>, extraMonthlyCents: Long, start: YearMonth): PayoffResult {
        val active = debts.filter { it.balanceCents > 0 }
        if (active.isEmpty()) {
            return PayoffResult.Plan(PayoffStrategy.BOLA_DE_NEVE, "Nenhuma dívida de cartão cadastrada.", emptyList(), emptyList(), emptyMap(), null, 0, null)
        }
        val cardsById = cards.associateBy { it.id }
        val balanceByCard = active.groupBy { it.cardId }.mapValues { (_, list) -> list.sumOf { it.balanceCents } }
        val allHaveInterest = balanceByCard.keys.all { cardsById[it]?.monthlyInterestBp != null }
        val strategy = if (allHaveInterest) PayoffStrategy.AVALANCHE else PayoffStrategy.BOLA_DE_NEVE
        val order = order(strategy, balanceByCard, cardsById)

        val run = simulateRun(active, cardsById, extraMonthlyCents, start, order)
            ?: return PayoffResult.NeverPaysOff(
                "Com o valor atual, a dívida não termina em 10 anos: os juros do rotativo crescem mais rápido que os pagamentos. Aumente o extra de quitação.",
                strategy,
                order,
            )

        val savings = if (strategy == PayoffStrategy.AVALANCHE) {
            val alternative = simulateRun(active, cardsById, extraMonthlyCents, start, order(PayoffStrategy.BOLA_DE_NEVE, balanceByCard, cardsById))
            alternative?.let { (it.totalPaid - run.totalPaid).coerceAtLeast(0) }
        } else {
            null
        }

        return PayoffResult.Plan(
            strategy = strategy,
            explanation = explain(strategy, order, cardsById, savings),
            order = order,
            timeline = run.timeline,
            payoffMonthByCard = run.payoffMonthByCard,
            debtFreeMonth = run.timeline.lastOrNull()?.month,
            totalPaidCents = run.totalPaid,
            savingsVsAlternativeCents = savings,
        )
    }

    /** Testa uma mudança sem gravar nada: devolve a data atual, a nova e a diferença em meses. */
    fun simulate(debts: List<CardDebt>, cards: List<Card>, extraMonthlyCents: Long, start: YearMonth, change: PayoffChange): Simulation {
        val current = plan(debts, cards, extraMonthlyCents, start)
        val changed = when (change) {
            is PayoffChange.ExtraPerMonth -> plan(debts, cards, extraMonthlyCents + change.cents, start)
            is PayoffChange.PayOffNow -> plan(debts.filterNot { it.id == change.debtId }, cards, extraMonthlyCents, start)
        }
        val currentFree = (current as? PayoffResult.Plan)?.debtFreeMonth
        val newFree = (changed as? PayoffResult.Plan)?.debtFreeMonth
        val diff = if (currentFree != null && newFree != null) ChronoUnit.MONTHS.between(currentFree, newFree).toInt() else null
        return Simulation(currentFree, newFree, diff, changed)
    }

    private fun order(strategy: PayoffStrategy, balanceByCard: Map<String, Long>, cardsById: Map<String, Card>): List<String> =
        when (strategy) {
            PayoffStrategy.AVALANCHE -> balanceByCard.keys.sortedWith(
                compareByDescending<String> { cardsById[it]?.monthlyInterestBp ?: 0 }.thenBy { balanceByCard[it] }.thenBy { it },
            )
            PayoffStrategy.BOLA_DE_NEVE -> balanceByCard.keys.sortedWith(compareBy<String> { balanceByCard[it] }.thenBy { it })
        }

    private fun explain(strategy: PayoffStrategy, order: List<String>, cardsById: Map<String, Card>, savings: Long?): String {
        val first = cardsById[order.first()]
        val name = first?.name ?: order.first()
        return when (strategy) {
            PayoffStrategy.AVALANCHE -> {
                val rate = first?.monthlyInterestBp?.let { "%d,%02d%% a.m.".format(it / 100, it % 100) } ?: ""
                val economy = if (savings != null && savings > 0) " Economia estimada de ${Money.format(savings)} em juros." else ""
                "Começando pelo $name, que tem os juros maiores ($rate), você paga menos juros no total.$economy"
            }
            PayoffStrategy.BOLA_DE_NEVE ->
                "Como nem todos os cartões têm juros informados, o plano começa pelo menor saldo: você zera o $name mais rápido e já pode cancelá-lo."
        }
    }

    private class DebtState(debt: CardDebt, start: YearMonth, interestBp: Int?) {
        val id = debt.id
        val cardId = debt.cardId
        val kind = debt.kind
        val installment = debt.amountCents
        /** Dívidas vencidas antes do início do plano passam a vencer no primeiro mês. */
        val firstDue: YearMonth = maxOf(YearMonth.from(debt.nextDueDate), start)
        val lastScheduled: YearMonth = if (debt.isInstallment) firstDue.plusMonths(((debt.remainingInstallments ?: 1) - 1).toLong()) else firstDue
        val interestBp = if (debt.kind == DebtKind.ROTATIVO) interestBp else null
        var balance = debt.balanceCents
        var zeroedIn: YearMonth? = null

        /** Quanto o cronograma original cobraria neste mês. */
        fun scheduledFor(month: YearMonth): Long = when (kind) {
            DebtKind.FATURA_ABERTA -> if (month == firstDue) installment else 0
            DebtKind.PARCELA, DebtKind.PARCELAMENTO_FATURA -> if (!month.isBefore(firstDue) && !month.isAfter(lastScheduled)) installment else 0
            DebtKind.ROTATIVO -> 0
        }

        fun pay(amount: Long, month: YearMonth): Long {
            val paid = minOf(amount, balance)
            balance -= paid
            if (balance == 0L && paid > 0 && zeroedIn == null) zeroedIn = month
            return paid
        }
    }

    private class RunResult(val timeline: List<PayoffMonth>, val payoffMonthByCard: Map<String, YearMonth>, val totalPaid: Long)

    private fun simulateRun(debts: List<CardDebt>, cardsById: Map<String, Card>, extraMonthly: Long, start: YearMonth, order: List<String>): RunResult? {
        val states = debts.map { DebtState(it, start, cardsById[it.cardId]?.monthlyInterestBp) }
        val timeline = mutableListOf<PayoffMonth>()
        val payoffMonthByCard = mutableMapOf<String, YearMonth>()
        var totalPaid = 0L
        var month = start

        repeat(MAX_MONTHS) {
            states.filter { it.balance > 0 && it.interestBp != null }.forEach { it.balance += it.balance * it.interestBp!! / 10_000 }

            // Parcelas liberadas: dívidas zeradas antes do prazo cujo cronograma ainda cobraria este mês.
            val freed = states.filter { it.zeroedIn != null && it.zeroedIn!!.isBefore(month) }.sumOf { it.scheduledFor(month) }

            states.filter { it.balance > 0 }.forEach { totalPaid += it.pay(it.scheduledFor(month), month) }

            var pool = extraMonthly + freed
            for (cardId in order) {
                if (pool <= 0) break
                val cardDebts = states.filter { it.cardId == cardId && it.balance > 0 }
                    .sortedBy { if (it.kind == DebtKind.ROTATIVO) 0 else if (it.kind == DebtKind.FATURA_ABERTA) 1 else 2 }
                for (debt in cardDebts) {
                    val paid = debt.pay(pool, month)
                    pool -= paid
                    totalPaid += paid
                }
            }

            val byCard = states.groupBy { it.cardId }.mapValues { (_, list) -> list.sumOf { it.balance } }
            byCard.forEach { (cardId, balance) -> if (balance == 0L && cardId !in payoffMonthByCard) payoffMonthByCard[cardId] = month }
            val total = byCard.values.sum()
            timeline += PayoffMonth(month, byCard, total)
            if (total == 0L) return RunResult(timeline, payoffMonthByCard, totalPaid)
            month = month.plusMonths(1)
        }
        return null
    }

    /** "ago/2027" — formato curto usado nas telas. */
    fun label(month: YearMonth): String =
        "${month.month.getDisplayName(TextStyle.SHORT, Locale.forLanguageTag("pt-BR")).trimEnd('.')}/${month.year}"
}
