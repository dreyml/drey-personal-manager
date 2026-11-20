package com.nxuslab.dreymanager.domain.budget

import com.nxuslab.dreymanager.domain.model.clampDay
import com.nxuslab.dreymanager.domain.money.Money
import java.time.LocalDate
import java.time.YearMonth

/** Situação do envelope da categoria escolhida, no mês do gasto. */
data class EnvelopeState(val categoryId: String, val name: String, val limitCents: Long, val spentCents: Long)

/** Dados do cartão quando o gasto é feito no crédito. */
data class CardContext(
    val cardName: String,
    val closingDay: Int,
    val dueDay: Int,
    /** Compras no cartão já lançadas neste mês, sem contar a atual. */
    val purchasesThisMonthBefore: Int,
    val payoffExtraMonthlyCents: Long,
)

/** O que o app mostra sempre que um gasto vai no cartão. */
data class CardNotice(
    val cardName: String,
    val invoiceMonth: YearMonth,
    val reservedCents: Long,
    /** Compras no cartão no mês, contando esta. */
    val purchasesThisMonth: Int,
    /** Quantos dias o estouro atrasa a quitação; nulo quando não há estouro. */
    val payoffDelayDays: Int?,
)

sealed interface GuardResult {
    val card: CardNotice?

    data class Ok(override val card: CardNotice? = null) : GuardResult
    data class Warning(val message: String, override val card: CardNotice? = null) : GuardResult
    data class Confirm(val title: String, val message: String, val excessCents: Long, override val card: CardNotice? = null) : GuardResult
}

/** Alerta firme (especificação §6.5): decide como o app reage antes de salvar um gasto. */
object SpendGuard {
    fun evaluate(amountCents: Long, date: LocalDate, week: WeekBudget.Ready, envelope: EnvelopeState?, card: CardContext?): GuardResult {
        val weekAfter = week.spentCents + amountCents
        val weekExcess = (weekAfter - week.limitCents).coerceAtLeast(0)
        val envelopeAfter = envelope?.let { it.spentCents + amountCents }
        val envelopeExcess = if (envelope != null) (envelopeAfter!! - envelope.limitCents).coerceAtLeast(0) else 0L

        val cardNotice = card?.let {
            CardNotice(
                cardName = it.cardName,
                invoiceMonth = invoiceDueMonth(date, it.closingDay, it.dueDay),
                reservedCents = amountCents,
                purchasesThisMonth = it.purchasesThisMonthBefore + 1,
                payoffDelayDays = if (weekExcess > 0) payoffDelayDays(weekExcess, it.payoffExtraMonthlyCents) else null,
            )
        }

        if (envelopeExcess > 0 && envelope != null) {
            val carry = if (weekExcess > 0) " O excesso de ${Money.format(weekExcess)} sai da próxima semana." else " Ainda cabe na semana, mas o envelope estoura."
            return GuardResult.Confirm(
                title = "Isso estoura seu limite de ${envelope.name}",
                message = "${envelope.name}: ${Money.format(envelopeAfter!!)} de ${Money.format(envelope.limitCents)} (+${Money.format(envelopeExcess)}).$carry",
                excessCents = maxOf(envelopeExcess, weekExcess),
                card = cardNotice,
            )
        }
        if (weekExcess > 0) {
            return GuardResult.Confirm(
                title = "Isso estoura seu limite da semana",
                message = "Semana: ${Money.format(weekAfter)} de ${Money.format(week.limitCents)} (+${Money.format(weekExcess)}). O excesso sai da próxima semana.",
                excessCents = weekExcess,
                card = cardNotice,
            )
        }

        val warnings = buildList {
            if (envelope != null && reachesWarning(envelopeAfter!!, envelope.limitCents)) {
                add("${envelope.name} chega a ${percent(envelopeAfter, envelope.limitCents)}% do limite do mês.")
            }
            if (reachesWarning(weekAfter, week.limitCents)) {
                add("Depois deste gasto restam ${Money.format(week.limitCents - weekAfter)} na semana.")
            }
        }
        return if (warnings.isEmpty()) GuardResult.Ok(cardNotice) else GuardResult.Warning(warnings.joinToString(" "), cardNotice)
    }

    /**
     * Mês de vencimento da fatura em que a compra entra. A compra feita no dia do fechamento ou depois
     * vai para a fatura seguinte; o vencimento cai no mesmo mês do fechamento quando o dia de
     * vencimento é posterior ao de fechamento, senão no mês seguinte.
     */
    fun invoiceDueMonth(purchaseDate: LocalDate, closingDay: Int, dueDay: Int): YearMonth {
        val purchaseMonth = YearMonth.from(purchaseDate)
        val closingThisMonth = purchaseMonth.clampDay(closingDay)
        val closingMonth = if (purchaseDate.isBefore(closingThisMonth)) purchaseMonth else purchaseMonth.plusMonths(1)
        return if (dueDay > closingDay) closingMonth else closingMonth.plusMonths(1)
    }

    /** Estimativa: o excesso deixa de ir para a quitação, atrasando-a proporcionalmente ao extra mensal. */
    fun payoffDelayDays(excessCents: Long, payoffExtraMonthlyCents: Long): Int? {
        if (payoffExtraMonthlyCents <= 0) return null
        return ((excessCents * 30 + payoffExtraMonthlyCents - 1) / payoffExtraMonthlyCents).toInt()
    }

    private fun reachesWarning(after: Long, limit: Long): Boolean = limit > 0 && after * 10 >= limit * 8

    private fun percent(value: Long, limit: Long): Long = if (limit <= 0) 100 else value * 100 / limit
}
