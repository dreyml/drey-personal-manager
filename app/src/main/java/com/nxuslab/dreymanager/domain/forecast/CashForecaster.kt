package com.nxuslab.dreymanager.domain.forecast

import com.nxuslab.dreymanager.domain.model.Card
import com.nxuslab.dreymanager.domain.model.CardDebt
import com.nxuslab.dreymanager.domain.model.DebtKind
import com.nxuslab.dreymanager.domain.model.Expense
import com.nxuslab.dreymanager.domain.model.FixedBill
import com.nxuslab.dreymanager.domain.model.Income
import com.nxuslab.dreymanager.domain.model.IncomeKind
import com.nxuslab.dreymanager.domain.model.PaymentMethod
import com.nxuslab.dreymanager.domain.model.clampDay
import java.time.LocalDate
import java.time.YearMonth

/** Uma entrada (positiva) ou saída (negativa) de dinheiro numa data. */
data class CashEvent(val date: LocalDate, val amountCents: Long, val reason: String)

data class ForecastDay(val date: LocalDate, val balanceCents: Long)

/** Primeiro dia em que o saldo fica negativo, com quanto falta e o principal motivo. */
data class Shortfall(val date: LocalDate, val cents: Long, val reason: String)

data class Forecast(val days: List<ForecastDay>, val shortfalls: List<Shortfall>)

data class ForecastInputs(
    val openingBalanceCents: Long,
    val incomes: List<Income> = emptyList(),
    val fixedBills: List<FixedBill> = emptyList(),
    val debts: List<CardDebt> = emptyList(),
    val cards: List<Card> = emptyList(),
    /** Compras no cartão já lançadas; as reservadas saem no vencimento da fatura. */
    val expenses: List<Expense> = emptyList(),
    /** Gasto diário previsto (limite semanal ÷ 7). */
    val dailySpendCents: Long = 0,
    val payoffExtraMonthlyCents: Long = 0,
    val wishesMonthlyCents: Long = 0,
)

/**
 * Previsão "vai faltar?" (especificação §6.7): projeta o saldo em conta dia a dia.
 * Quitação extra e desejos saem no dia do salário, quando o dinheiro é separado.
 */
object CashForecaster {
    private const val DAILY_REASON = "Gastos do dia a dia"

    fun forecast(start: LocalDate, days: Int = 90, inputs: ForecastInputs, whatIf: List<CashEvent> = emptyList()): Forecast {
        val end = start.plusDays(days.toLong() - 1)
        val byDate = (events(start, days, inputs) + whatIf.filter { !it.date.isBefore(start) && !it.date.isAfter(end) }).groupBy { it.date }
        var balance = inputs.openingBalanceCents
        var negative = balance < 0
        val result = ArrayList<ForecastDay>(days)
        val shortfalls = mutableListOf<Shortfall>()

        for (offset in 0 until days) {
            val date = start.plusDays(offset.toLong())
            val todays = byDate[date].orEmpty()
            balance += todays.sumOf { it.amountCents } - inputs.dailySpendCents
            result += ForecastDay(date, balance)
            if (balance < 0 && !negative) {
                val reason = todays.filter { it.amountCents < 0 }.minByOrNull { it.amountCents }?.reason ?: DAILY_REASON
                shortfalls += Shortfall(date, -balance, reason)
            }
            negative = balance < 0
        }
        return Forecast(result, shortfalls)
    }

    /** Todas as entradas e saídas previstas entre [start] e [start] + [days] − 1. */
    fun events(start: LocalDate, days: Int, inputs: ForecastInputs): List<CashEvent> {
        val end = start.plusDays(days.toLong() - 1)
        val months = generateSequence(YearMonth.from(start)) { it.plusMonths(1) }.takeWhile { !it.isAfter(YearMonth.from(end)) }.toList()
        val cardName = inputs.cards.associate { it.id to it.name }
        val events = mutableListOf<CashEvent>()

        for (month in months) {
            inputs.incomes.filter { it.kind == IncomeKind.FIXA && it.dayOfMonth != null }.forEachIndexed { index, income ->
                val payday = month.clampDay(income.dayOfMonth!!)
                events += CashEvent(payday, income.amountCents, "Salário")
                if (index == 0) {
                    if (inputs.payoffExtraMonthlyCents > 0) events += CashEvent(payday, -inputs.payoffExtraMonthlyCents, "Quitação do cartão")
                    if (inputs.wishesMonthlyCents > 0) events += CashEvent(payday, -inputs.wishesMonthlyCents, "Desejos")
                }
            }
            inputs.fixedBills.filter { it.active }.forEach { events += CashEvent(month.clampDay(it.dayOfMonth), -it.amountCents, it.title) }
            inputs.debts.forEach { debt -> debtEvent(debt, month, start, cardName)?.let { events += it } }
        }
        inputs.incomes.filter { it.kind == IncomeKind.EXTRA && it.date != null }.forEach { events += CashEvent(it.date!!, it.amountCents, "Renda extra") }

        val cardsById = inputs.cards.associateBy { it.id }
        inputs.expenses
            .filter { it.method == PaymentMethod.CARTAO && it.reservedForInvoice != null && it.cardId != null }
            .groupBy { it.cardId!! to it.reservedForInvoice!! }
            .forEach { (key, purchases) ->
                val (cardId, invoiceMonth) = key
                val dueDay = cardsById[cardId]?.dueDay ?: 10
                events += CashEvent(invoiceMonth.clampDay(dueDay), -purchases.sumOf { it.amountCents }, "Fatura ${cardName[cardId] ?: "do cartão"}")
            }

        return events.filter { !it.date.isBefore(start) && !it.date.isAfter(end) }.sortedBy { it.date }
    }

    private fun debtEvent(debt: CardDebt, month: YearMonth, start: LocalDate, cardName: Map<String, String>): CashEvent? {
        val due = if (debt.nextDueDate.isBefore(start)) start else debt.nextDueDate
        val firstMonth = YearMonth.from(due)
        return when (debt.kind) {
            DebtKind.FATURA_ABERTA ->
                if (month == firstMonth) CashEvent(due, -debt.amountCents, "Fatura ${cardName[debt.cardId] ?: "do cartão"}") else null
            DebtKind.PARCELA, DebtKind.PARCELAMENTO_FATURA -> {
                val last = firstMonth.plusMonths(((debt.remainingInstallments ?: 0) - 1).toLong())
                if (!month.isBefore(firstMonth) && !month.isAfter(last)) CashEvent(month.clampDay(due.dayOfMonth), -debt.amountCents, "Parcela ${debt.description}") else null
            }
            // Rotativo é pago pelo extra de quitação, que já sai no dia do salário.
            DebtKind.ROTATIVO -> null
        }
    }
}
