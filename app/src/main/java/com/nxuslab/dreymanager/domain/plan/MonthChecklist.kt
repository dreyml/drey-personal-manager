package com.nxuslab.dreymanager.domain.plan

import com.nxuslab.dreymanager.domain.model.CardDebt
import com.nxuslab.dreymanager.domain.model.DebtKind
import com.nxuslab.dreymanager.domain.model.FixedBill
import com.nxuslab.dreymanager.domain.model.VariableBill
import com.nxuslab.dreymanager.domain.model.clampDay
import java.time.LocalDate
import java.time.YearMonth

enum class ChecklistKind { INVOICE, INSTALLMENT, BILL, VARIABLE, PAYOFF }

data class ChecklistItem(
    /** Chave salva quando o item é marcado como pago: `debt:<id>`, `bill:<id>`, `variable:<id>`, `payoff`. */
    val key: String,
    val kind: ChecklistKind,
    val title: String,
    /** Nulo para o extra de quitação (sai quando o salário cair). */
    val dueDate: LocalDate?,
    val amountCents: Long,
    val paid: Boolean,
    /** Fatura aberta: marcar como paga também a tira das dívidas. */
    val debtId: String? = null,
    /** Conta variável sem valor real lançado (usa a estimativa). */
    val needsValue: Boolean = false,
)

/** "Este mês, pague": tudo o que sai no mês, com o que já foi marcado como pago. */
object MonthChecklist {

    fun build(
        month: YearMonth,
        fixedBills: List<FixedBill>,
        variableBills: List<VariableBill>,
        variableActuals: Map<Pair<String, YearMonth>, Long>,
        debts: List<CardDebt>,
        cardNames: Map<String, String>,
        payoffExtraCents: Long,
        paid: Set<Pair<String, YearMonth>>,
    ): List<ChecklistItem> {
        fun isPaid(key: String) = (key to month) in paid
        val items = mutableListOf<ChecklistItem>()
        debts.forEach { debt ->
            val first = YearMonth.from(debt.nextDueDate)
            val card = cardNames[debt.cardId] ?: "cartão"
            when (debt.kind) {
                DebtKind.FATURA_ABERTA -> if (first == month) {
                    items += ChecklistItem("debt:${debt.id}", ChecklistKind.INVOICE, "Fatura $card", debt.nextDueDate, debt.amountCents, isPaid("debt:${debt.id}"), debtId = debt.id)
                }
                DebtKind.PARCELA, DebtKind.PARCELAMENTO_FATURA -> {
                    val remaining = debt.remainingInstallments ?: 0
                    val last = first.plusMonths((remaining - 1).toLong())
                    if (remaining > 0 && !month.isBefore(first) && !month.isAfter(last)) {
                        items += ChecklistItem(
                            "debt:${debt.id}", ChecklistKind.INSTALLMENT, "${debt.description} ($card)",
                            month.clampDay(debt.nextDueDate.dayOfMonth), debt.amountCents, isPaid("debt:${debt.id}"),
                        )
                    }
                }
                DebtKind.ROTATIVO -> Unit
            }
        }
        fixedBills.filter { it.active }.forEach { bill ->
            items += ChecklistItem("bill:${bill.id}", ChecklistKind.BILL, bill.title, month.clampDay(bill.dayOfMonth), bill.amountCents, isPaid("bill:${bill.id}"))
        }
        variableBills.filter { it.active }.forEach { bill ->
            val actual = variableActuals[bill.id to month]
            items += ChecklistItem(
                "variable:${bill.id}", ChecklistKind.VARIABLE, bill.title, month.clampDay(bill.dayOfMonth),
                actual ?: bill.estimateCents, isPaid("variable:${bill.id}"), needsValue = actual == null,
            )
        }
        if (payoffExtraCents > 0) {
            items += ChecklistItem("payoff", ChecklistKind.PAYOFF, "Extra para quitar o cartão", null, payoffExtraCents, isPaid("payoff"))
        }
        return items.sortedWith(compareBy<ChecklistItem>({ it.paid }, { it.dueDate ?: LocalDate.MAX }, { it.title }))
    }
}
