package com.nxuslab.dreymanager.domain.wish

import com.nxuslab.dreymanager.domain.model.Wish
import com.nxuslab.dreymanager.domain.model.WishPriority
import com.nxuslab.dreymanager.domain.model.WishStatus
import com.nxuslab.dreymanager.domain.money.Money
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

data class WishProjection(
    val wish: Wish,
    val savedCents: Long,
    val remainingCents: Long,
    /** Quanto guardar por mês para este desejo. */
    val monthlyNeededCents: Long,
    /** Quando dá para comprar à vista; nulo quando não há sobra para este desejo. */
    val realisticDate: LocalDate?,
    /** Quanto o limite semanal diminui para cumprir a data (só prioridade alta). */
    val weeklyReductionCents: Long,
    val ready: Boolean,
    val message: String?,
)

data class CardComparison(
    val installments: Int,
    val installmentCents: Long,
    val extraCostCents: Long,
    /** Meses de atraso na saída do cartão; nulo quando não há extra de quitação definido. */
    val payoffDelayMonths: Int?,
    val message: String,
)

/**
 * Lista de desejos (especificação §6.8). Prioridade alta tem dinheiro reservado no orçamento do
 * mês (e reduz a semana); média e baixa recebem só a sobra, em fila: média antes de baixa, depois
 * por ordem de criação.
 */
object WishPlanner {
    const val DEFAULT_HIGH_MONTHS = 6L

    fun plan(wishes: List<Wish>, savedByWish: Map<String, Long>, today: LocalDate, monthlyLeftoverCents: Long): List<WishProjection> {
        val active = wishes.filter { it.status == WishStatus.JUNTANDO || it.status == WishStatus.PRONTO }
        val result = mutableListOf<WishProjection>()

        active.filter { it.priority == WishPriority.ALTA }.forEach { wish ->
            val saved = savedByWish[wish.id] ?: 0
            result += readyOrNull(wish, saved, today) ?: run {
                val remaining = wish.priceCents - saved
                val months = wish.targetDate?.let { monthsUntil(today, it) } ?: DEFAULT_HIGH_MONTHS
                val monthly = ceilDiv(remaining, months)
                WishProjection(
                    wish = wish,
                    savedCents = saved,
                    remainingCents = remaining,
                    monthlyNeededCents = monthly,
                    realisticDate = wish.targetDate ?: today.plusMonths(months),
                    weeklyReductionCents = ceilDiv(monthly * 12, 52),
                    ready = false,
                    message = null,
                )
            }
        }

        var queuedCents = 0L
        active.filter { it.priority != WishPriority.ALTA }
            .sortedWith(compareBy<Wish> { it.priority.ordinal }.thenBy { it.createdAt })
            .forEach { wish ->
                val saved = savedByWish[wish.id] ?: 0
                result += readyOrNull(wish, saved, today) ?: run {
                    val remaining = wish.priceCents - saved
                    queuedCents += remaining
                    if (monthlyLeftoverCents <= 0) {
                        WishProjection(wish, saved, remaining, 0, null, 0, false, "Sem sobra no orçamento por enquanto. Guarde quando puder ou mude para prioridade alta.")
                    } else {
                        val date = today.plusMonths(ceilDiv(queuedCents, monthlyLeftoverCents))
                        val late = wish.targetDate?.let { date.isAfter(it) } == true
                        WishProjection(
                            wish = wish,
                            savedCents = saved,
                            remainingCents = remaining,
                            monthlyNeededCents = wish.targetDate?.let { ceilDiv(remaining, monthsUntil(today, it)) } ?: minOf(monthlyLeftoverCents, remaining),
                            realisticDate = date,
                            weeklyReductionCents = 0,
                            ready = false,
                            message = if (late) "Com a sobra atual, fica para ${date.monthValue}/${date.year}, depois da data desejada." else null,
                        )
                    }
                }
            }
        return result
    }

    /** Valor que o orçamento do mês reserva para desejos de prioridade alta. */
    fun highPriorityMonthlyCents(projections: List<WishProjection>): Long =
        projections.filter { it.wish.priority == WishPriority.ALTA && !it.ready }.sumOf { it.monthlyNeededCents }

    /**
     * Comparação informativa com parcelar no cartão. Com juros, usa a tabela Price; o custo total das
     * parcelas deixa de ir para a quitação, atrasando a saída do cartão.
     */
    fun compareWithCard(priceCents: Long, installments: Int, monthlyInterestBp: Int?, payoffExtraMonthlyCents: Long): CardComparison {
        val n = installments.coerceAtLeast(1)
        val installment = if (monthlyInterestBp == null || monthlyInterestBp == 0) {
            ceilDiv(priceCents, n.toLong())
        } else {
            val rate = BigDecimal(monthlyInterestBp).divide(BigDecimal(10_000), MathContext.DECIMAL64)
            val factor = BigDecimal.ONE.add(rate).pow(n, MathContext.DECIMAL64)
            val denominator = BigDecimal.ONE.subtract(BigDecimal.ONE.divide(factor, MathContext.DECIMAL64))
            BigDecimal(priceCents).multiply(rate).divide(denominator, MathContext.DECIMAL64).setScale(0, RoundingMode.HALF_UP).toLong()
        }
        val total = installment * n
        val extraCost = (total - priceCents).coerceAtLeast(0)
        val delay = if (payoffExtraMonthlyCents > 0) ceilDiv(total, payoffExtraMonthlyCents).toInt() else null
        val costText = if (extraCost > 0) "sai ${Money.format(extraCost)} a mais" else "não tem juros"
        val delayText = delay?.let { " e atrasa a saída do cartão em $it ${if (it == 1) "mês" else "meses"}" } ?: ""
        return CardComparison(n, installment, extraCost, delay, "Parcelado em $n× de ${Money.format(installment)} $costText$delayText. Juntar e comprar à vista é melhor.")
    }

    private fun readyOrNull(wish: Wish, saved: Long, today: LocalDate): WishProjection? =
        if (saved >= wish.priceCents) {
            WishProjection(wish, saved, 0, 0, today, 0, true, "Pronto! Pode comprar ${wish.name} à vista.")
        } else {
            null
        }

    private fun monthsUntil(today: LocalDate, target: LocalDate): Long =
        ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(target)).coerceAtLeast(1)

    private fun ceilDiv(value: Long, divisor: Long): Long = if (divisor <= 0) value else (value + divisor - 1) / divisor
}
