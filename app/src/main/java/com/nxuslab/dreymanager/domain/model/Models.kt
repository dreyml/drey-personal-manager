package com.nxuslab.dreymanager.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/*
 * Modelos de domínio: Kotlin puro, sem Android nem Room. Valores sempre em centavos (Long).
 * As entidades do banco (camada data/) são convertidas para estes tipos nos repositórios.
 */

enum class IncomeKind { FIXA, EXTRA }

/** Banco (Pix/débito) ou carteira (dinheiro físico). */
enum class AccountKind { BANCO, DINHEIRO }

data class Income(
    val id: String,
    val kind: IncomeKind,
    val amountCents: Long,
    /** Dia do pagamento, para renda fixa. Dias além do fim do mês caem no último dia. */
    val dayOfMonth: Int? = null,
    /** Data de recebimento, para renda extra. */
    val date: LocalDate? = null,
    /** Parte de uma renda extra destinada à quitação do cartão. */
    val toDebtCents: Long = 0,
    /** Conta onde a renda extra entrou (soma no saldo dela). */
    val accountId: String? = null,
    /** Momento do lançamento (ms), para saber se já entrou no saldo informado. */
    val createdAt: Long = 0,
)

/**
 * Conta que se repete todo mês sem valor fixo (energia, celular…). O orçamento usa o valor real
 * do mês quando lançado e, antes disso, a estimativa.
 */
data class VariableBill(
    val id: String,
    val title: String,
    val dayOfMonth: Int,
    val estimateCents: Long,
    val active: Boolean = true,
)

data class FixedBill(
    val id: String,
    val title: String,
    val amountCents: Long,
    val dayOfMonth: Int,
    val categoryId: String? = null,
    val active: Boolean = true,
)

enum class CardStatus { ATIVO, QUITADO, CANCELADO }

data class Card(
    val id: String,
    val name: String,
    val closingDay: Int,
    val dueDay: Int,
    /** Juros ao mês em pontos-base (1290 = 12,90% a.m.). Nulo quando o usuário não sabe. */
    val monthlyInterestBp: Int? = null,
    val status: CardStatus = CardStatus.ATIVO,
    /** Limite de crédito do cartão; nulo quando não informado. */
    val limitCents: Long? = null,
    /** Cartão que o usuário quer quitar e cancelar: o plano foca nele primeiro. */
    val cancelGoal: Boolean = false,
)

/**
 * Tipos de dívida de cartão:
 * - [FATURA_ABERTA]: valor total de uma fatura que vence em [CardDebt.nextDueDate].
 * - [PARCELA] e [PARCELAMENTO_FATURA]: valor fixo por mês, por [CardDebt.remainingInstallments] meses
 *   a partir de [CardDebt.nextDueDate]. Os juros já estão embutidos na parcela.
 * - [ROTATIVO]: saldo devedor sem parcela fixa; rende juros do cartão todo mês até ser quitado.
 */
enum class DebtKind { FATURA_ABERTA, PARCELA, ROTATIVO, PARCELAMENTO_FATURA }

data class CardDebt(
    val id: String,
    val cardId: String,
    val kind: DebtKind,
    val description: String,
    /** Valor da parcela (PARCELA/PARCELAMENTO_FATURA) ou saldo total (FATURA_ABERTA/ROTATIVO). */
    val amountCents: Long,
    val remainingInstallments: Int? = null,
    val nextDueDate: LocalDate,
) {
    val isInstallment: Boolean get() = kind == DebtKind.PARCELA || kind == DebtKind.PARCELAMENTO_FATURA

    /** Quanto ainda se deve nesta dívida. */
    val balanceCents: Long
        get() = if (isInstallment) amountCents * (remainingInstallments ?: 0) else amountCents
}

data class Envelope(val categoryId: String, val monthlyLimitCents: Long)

enum class PaymentMethod { PIX, DEBITO, DINHEIRO, CARTAO }

data class Expense(
    val id: String,
    val amountCents: Long,
    val categoryId: String,
    val date: LocalDate,
    val method: PaymentMethod,
    val cardId: String? = null,
    /** Mês de vencimento da fatura em que uma compra no cartão foi reservada. */
    val reservedForInvoice: YearMonth? = null,
    /** Conta de onde saiu (Pix/débito: banco; dinheiro: carteira). */
    val accountId: String? = null,
    /** Momento do lançamento (ms), para saber se já entrou no saldo informado. */
    val createdAt: Long = 0,
)

enum class WishPriority { ALTA, MEDIA, BAIXA }
enum class WishStatus { JUNTANDO, PRONTO, COMPRADO, DESISTI }

data class Wish(
    val id: String,
    val name: String,
    val priceCents: Long,
    val targetDate: LocalDate? = null,
    val priority: WishPriority,
    val status: WishStatus = WishStatus.JUNTANDO,
    val createdAt: Long = 0,
)

data class BudgetSettings(
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    val reserveCents: Long = 0,
    val payoffExtraMonthlyCents: Long = 0,
    val weeklyLimitOverrideCents: Long? = null,
    val extraSplitToDebtPercent: Int = 50,
)

/** Tudo o que o cálculo do orçamento precisa saber. */
data class BudgetInputs(
    val settings: BudgetSettings = BudgetSettings(),
    val incomes: List<Income> = emptyList(),
    val fixedBills: List<FixedBill> = emptyList(),
    val debts: List<CardDebt> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    /** Valor mensal reservado para desejos de prioridade alta (calculado pelo WishPlanner). */
    val highPriorityWishMonthlyCents: Long = 0,
    val variableBills: List<VariableBill> = emptyList(),
    /** Valores reais lançados: (id da conta variável, mês) → centavos. */
    val variableActuals: Map<Pair<String, YearMonth>, Long> = emptyMap(),
) {
    /** Valor de uma conta variável no mês: o real lançado ou, sem ele, a estimativa. */
    fun variableAmount(bill: VariableBill, month: YearMonth): Long = variableActuals[bill.id to month] ?: bill.estimateCents
}

/** Ajusta um dia do mês (ex.: 31) para o último dia válido daquele mês. */
fun YearMonth.clampDay(day: Int): LocalDate = atDay(day.coerceIn(1, lengthOfMonth()))
