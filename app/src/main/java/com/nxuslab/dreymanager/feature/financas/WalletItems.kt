package com.nxuslab.dreymanager.feature.financas

import com.nxuslab.dreymanager.data.repo.Account
import com.nxuslab.dreymanager.data.repo.FinanceSnapshot
import com.nxuslab.dreymanager.domain.model.AccountKind
import com.nxuslab.dreymanager.domain.model.CardStatus
import com.nxuslab.dreymanager.domain.model.DebtKind
import com.nxuslab.dreymanager.domain.money.Money
import com.nxuslab.dreymanager.domain.payoff.PayoffPlanner
import com.nxuslab.dreymanager.domain.payoff.PayoffResult
import com.nxuslab.dreymanager.feature.common.cardUsedCents
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class WalletKind(val label: String) { CONTA("DÉBITO"), CREDITO("CRÉDITO"), DINHEIRO("DINHEIRO") }

/** Cores do cartão: degradê do início ao fim e se o texto é claro (contraste ≥ 4,5:1). */
data class BankBrand(val wordmark: String, val start: Long, val end: Long, val lightText: Boolean)

data class WalletItem(
    val id: String,
    val name: String,
    val kind: WalletKind,
    val brand: BankBrand,
    val mainLabel: String,
    val mainCents: Long,
    val tag: String,
    val sub: String,
    val details: List<Pair<String, String>>,
    /** Conta que o toque em "Corrigir saldo" edita; nulo para cartões. */
    val account: Account? = null,
)

private val dayMonth = DateTimeFormatter.ofPattern("dd/MM")

/** Cor de cada banco, suavizada. Bancos desconhecidos ficam em lavanda. */
fun brandFor(name: String, kind: WalletKind): BankBrand {
    if (kind == WalletKind.DINHEIRO) return BankBrand("carteira", 0xFFFADBCB, 0xFFF4C3AA, lightText = false)
    val n = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase().trim()
    return when {
        "nubank" in n || n.startsWith("nu ") || n == "nu" -> BankBrand("nu", 0xFF8A63D2, 0xFF6A47B3, true)
        "sicoob" in n -> if (kind == WalletKind.CREDITO) BankBrand("sicoob", 0xFF3E7D8C, 0xFF2C6271, true) else BankBrand("sicoob", 0xFF2F8F86, 0xFF24736C, true)
        "mercado" in n -> BankBrand("mercado pago", 0xFF3A8CC8, 0xFF2A71A8, true)
        "inter" in n -> BankBrand("inter", 0xFFE07B39, 0xFFC4601F, true)
        "itau" in n -> BankBrand("itaú", 0xFFD8702E, 0xFFB5561A, true)
        "bradesco" in n || "santander" in n -> BankBrand(n.substringBefore(' '), 0xFFC9545A, 0xFFA83E45, true)
        "caixa" in n || "brasil" in n || n == "bb" -> BankBrand(n.substringBefore(' '), 0xFF3F6FB5, 0xFF2D5694, true)
        "c6" in n -> BankBrand("c6", 0xFF4A4A55, 0xFF2D2A38, true)
        "picpay" in n -> BankBrand("picpay", 0xFF3AA36B, 0xFF2A8454, true)
        else -> BankBrand(n, 0xFFB9AEE6, 0xFF9C8FD6, false)
    }
}

/** Contas, cartões de crédito ativos e a carteira, nessa ordem. */
fun FinanceSnapshot.walletItems(today: LocalDate, payoff: PayoffResult?): List<WalletItem> {
    val zeroMonth = (payoff as? PayoffResult.Plan)?.payoffMonthByCard.orEmpty()
    val banks = accounts.filter { it.kind == AccountKind.BANCO }.sortedBy { it.name }.map { a ->
        val cents = currentBalance(a)
        val updated = if (a.updatedAt == today) "atualizado hoje" else "atualizado em ${a.updatedAt.format(dayMonth)}"
        WalletItem(
            id = a.id, name = a.name, kind = WalletKind.CONTA, brand = brandFor(a.name, WalletKind.CONTA),
            mainLabel = "Saldo", mainCents = cents, tag = "Conta", sub = updated,
            details = listOf("Saldo agora" to Money.format(cents), "Última atualização" to a.updatedAt.format(dayMonth)),
            account = a,
        )
    }
    val credit = cards.filter { it.status == CardStatus.ATIVO }.sortedBy { it.name }.map { c ->
        val invoice = debts.filter { it.cardId == c.id && it.kind == DebtKind.FATURA_ABERTA && !it.nextDueDate.isBefore(today) }.minByOrNull { it.nextDueDate }
        val installments = debts.filter { it.cardId == c.id && it.isInstallment }
        val free = c.limitCents?.let { (it - cardUsedCents(c.id, today)).coerceAtLeast(0) }
        val zero = zeroMonth[c.id]?.let { PayoffPlanner.label(it) }
        WalletItem(
            id = c.id, name = c.name, kind = WalletKind.CREDITO, brand = brandFor(c.name, WalletKind.CREDITO),
            mainLabel = if (invoice != null) "Fatura aberta" else "Sem fatura aberta",
            mainCents = invoice?.amountCents ?: 0,
            tag = when {
                c.cancelGoal -> "Meta: cancelar"
                installments.isNotEmpty() -> "${installments.size} parcelamento(s)"
                else -> "Crédito"
            },
            sub = buildList {
                invoice?.let { add("vence ${it.nextDueDate.format(dayMonth)}") }
                free?.let { add("limite livre ${Money.formatShort(it)}") }
            }.joinToString(" · ").ifEmpty { "sem fatura cadastrada" },
            details = buildList {
                invoice?.let { add("Fatura aberta" to Money.format(it.amountCents)); add("Vencimento" to it.nextDueDate.format(dayMonth)) }
                c.limitCents?.let { add("Limite" to Money.formatShort(it)) }
                free?.let { add("Limite livre" to Money.formatShort(it)) }
                if (installments.isNotEmpty()) add("Parcelas restantes" to Money.format(installments.sumOf { it.balanceCents }))
                zero?.let { add("Zera em" to it) }
            },
        )
    }
    val cash = accounts.filter { it.kind == AccountKind.DINHEIRO }.map { a ->
        val cents = currentBalance(a)
        WalletItem(
            id = a.id, name = a.name, kind = WalletKind.DINHEIRO, brand = brandFor(a.name, WalletKind.DINHEIRO),
            mainLabel = "Na carteira", mainCents = cents, tag = "Dinheiro físico",
            sub = "toque para corrigir", details = listOf("Dinheiro na carteira" to Money.format(cents)), account = a,
        )
    }
    return banks + credit + cash
}
