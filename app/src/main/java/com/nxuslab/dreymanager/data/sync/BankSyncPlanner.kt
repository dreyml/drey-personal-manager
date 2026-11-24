package com.nxuslab.dreymanager.data.sync

import com.nxuslab.dreymanager.data.repo.Account
import com.nxuslab.dreymanager.data.repo.Category
import com.nxuslab.dreymanager.data.repo.FinanceSnapshot
import com.nxuslab.dreymanager.domain.model.AccountKind
import com.nxuslab.dreymanager.domain.model.Card
import com.nxuslab.dreymanager.domain.model.CardDebt
import com.nxuslab.dreymanager.domain.model.DebtKind
import com.nxuslab.dreymanager.domain.model.Expense
import com.nxuslab.dreymanager.domain.model.PaymentMethod
import com.nxuslab.dreymanager.domain.model.clampDay
import java.text.Normalizer
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** O que o serviço da VM devolve (já em centavos). */
data class RemoteAccount(
    val id: String,
    val institution: String,
    /** "BANK" ou "CREDIT". */
    val type: String,
    val name: String,
    val balanceCents: Long,
    val creditLimitCents: Long? = null,
    /** Vencimento informado pela Pluggy; usado só para criar um cartão novo. */
    val billDueDate: LocalDate? = null,
    /** Quando a Pluggy buscou os dados no banco. */
    val updatedAtMillis: Long? = null,
)

data class RemoteTransaction(
    val id: String,
    val accountId: String,
    val date: LocalDate,
    /** Negativo = saída (compra), positivo = entrada. */
    val amountCents: Long,
    val description: String,
    val category: String? = null,
)

data class SyncPayload(
    val accounts: List<RemoteAccount>,
    val transactions: List<RemoteTransaction>,
    /** Conexões que falharam no servidor. */
    val failedConnections: Int = 0,
)

data class ImportedExpense(val expense: Expense, val note: String)

/** Tudo o que uma sincronização muda no app, calculado sem tocar no banco de dados. */
data class SyncPlan(
    val accounts: List<Account> = emptyList(),
    val cards: List<Card> = emptyList(),
    val debtUpserts: List<CardDebt> = emptyList(),
    val debtDeletes: List<String> = emptyList(),
    val expenses: List<ImportedExpense> = emptyList(),
    val skippedDuplicates: Int = 0,
    val paidInvoices: Int = 0,
)

/**
 * Regras da sincronização com os bancos:
 * - Conta (BANK): o saldo do banco substitui o do app, valendo a partir da hora em que a Pluggy o
 *   buscou. Lançamentos importados entram com data de criação anterior a isso, para não serem
 *   descontados de novo.
 * - Cartão (CREDIT): atualiza o limite; o saldo do cartão é a fatura em aberto, que vira a fatura do
 *   ciclo atual (descontando compras manuais já reservadas nela). Pagamento de fatura detectado
 *   remove a fatura paga.
 * - Transações: só saídas a partir de importFrom, sem transferências entre contas próprias,
 *   pagamentos de fatura e investimentos. Gasto que já foi lançado à mão (mesmo valor, data ±1 dia,
 *   mesmo tipo) é ignorado. Entradas (salário etc.) só mudam o saldo.
 */
object BankSyncPlanner {

    const val ID_PREFIX = "pluggy:"

    private val NOT_SPENDING = setOf(
        "credit card payment", "same person transfer", "same person transfer - cash", "transfer - internal",
        "investments", "proceeds interests and dividends", "loans and financing", "automatic investment",
    )

    fun plan(payload: SyncPayload, data: FinanceSnapshot, today: LocalDate, nowMillis: Long, importFrom: LocalDate): SyncPlan {
        val remoteById = payload.accounts.associateBy { it.id }
        val accountMap = linkedMapOf<String, Account>() // id remoto → conta do app
        val cardMap = linkedMapOf<String, Card>() // id remoto → cartão do app
        val usedAccounts = mutableSetOf<String>()

        payload.accounts.filter { it.type == "BANK" }.forEach { remote ->
            val stamp = minOf(remote.updatedAtMillis ?: nowMillis, nowMillis)
            val existing = data.accounts.firstOrNull { it.id == ID_PREFIX + remote.id }
                ?: data.accounts.firstOrNull { it.kind == AccountKind.BANCO && it.id !in usedAccounts && sameBank(it.name, remote.institution) }
            val base = existing ?: Account(
                id = ID_PREFIX + remote.id,
                name = if (accountMap.values.any { sameBank(it.name, remote.institution) }) "${remote.institution} ${remote.name}" else remote.institution,
                balanceCents = 0,
                updatedAt = today,
            )
            usedAccounts += base.id
            accountMap[remote.id] = base.copy(balanceCents = remote.balanceCents, updatedAt = today, updatedAtMillis = stamp)
        }

        val debtUpserts = mutableListOf<CardDebt>()
        val debtDeletes = mutableListOf<String>()
        var paid = 0
        payload.accounts.filter { it.type == "CREDIT" }.forEach { remote ->
            val txs = payload.transactions.filter { it.accountId == remote.id }
            val existing = data.cards.firstOrNull { it.id == ID_PREFIX + remote.id } ?: data.cards.firstOrNull { sameBank(it.name, remote.institution) }
            if (existing == null && remote.balanceCents == 0L && txs.none { it.amountCents < 0 && it.date >= importFrom }) return@forEach
            val card = (existing ?: newCard(remote)).let { if (remote.creditLimitCents != null) it.copy(limitCents = remote.creditLimitCents) else it }
            cardMap[remote.id] = card

            val openInvoices = data.debts.filter { it.cardId == card.id && it.kind == DebtKind.FATURA_ABERTA }
            // Pagamento de fatura: entrada no cartão com o valor de uma fatura que vence perto dessa data.
            val payments = txs.filter { it.amountCents > 0 && it.category.orEmpty().lowercase() == "credit card payment" }
            val paidIds = openInvoices.filter { inv ->
                payments.any { p -> abs(p.amountCents - inv.amountCents) <= 100 && abs(ChronoUnit.DAYS.between(inv.nextDueDate, p.date)) <= 10 }
            }.map { it.id }
            debtDeletes += paidIds
            paid += paidIds.size

            val due = cycleDueDate(card, today)
            val month = YearMonth.from(due)
            val reservedManual = data.expenses.filter {
                it.method == PaymentMethod.CARTAO && it.cardId == card.id && it.reservedForInvoice == month && !it.id.startsWith(ID_PREFIX)
            }.sumOf { it.amountCents }
            val amount = (abs(remote.balanceCents) - reservedManual).coerceAtLeast(0)
            val current = openInvoices.firstOrNull { it.id !in paidIds && YearMonth.from(it.nextDueDate) == month }
            when {
                current != null -> if (current.amountCents != amount) debtUpserts += current.copy(amountCents = amount)
                amount > 0 -> debtUpserts += CardDebt(
                    id = "pluggy-bill:${card.id}:$month",
                    cardId = card.id,
                    kind = DebtKind.FATURA_ABERTA,
                    description = "Fatura ${card.name} (sincronizada)",
                    amountCents = amount,
                    nextDueDate = due,
                )
            }
        }

        // Transações viram gastos, sem repetir o que já existe.
        val knownIds = data.expenses.map { it.id }.toSet()
        val candidates = data.expenses.filter { !it.id.startsWith(ID_PREFIX) }.toMutableList()
        val expenses = mutableListOf<ImportedExpense>()
        var skipped = 0
        payload.transactions
            .filter { it.amountCents < 0 && it.date >= importFrom && it.category.orEmpty().lowercase() !in NOT_SPENDING }
            .sortedBy { it.date }
            .forEach { tx ->
                val id = ID_PREFIX + tx.id
                if (id in knownIds) return@forEach
                val remote = remoteById[tx.accountId] ?: return@forEach
                val amount = -tx.amountCents
                val isCard = remote.type == "CREDIT"
                val card = cardMap[remote.id]
                val account = accountMap[remote.id]
                if ((isCard && card == null) || (!isCard && account == null)) return@forEach
                val twin = candidates.firstOrNull { e ->
                    e.amountCents == amount && abs(ChronoUnit.DAYS.between(e.date, tx.date)) <= 1 &&
                        (e.method == PaymentMethod.CARTAO) == isCard &&
                        (if (isCard) e.cardId == null || e.cardId == card?.id else e.accountId == null || e.accountId == account?.id)
                }
                if (twin != null) {
                    candidates.remove(twin)
                    skipped++
                    return@forEach
                }
                expenses += ImportedExpense(
                    Expense(
                        id = id,
                        amountCents = amount,
                        categoryId = categoryFor(tx.category, data.categories),
                        date = tx.date,
                        method = when {
                            isCard -> PaymentMethod.CARTAO
                            tx.category.orEmpty().contains("pix", ignoreCase = true) || tx.description.contains("pix", ignoreCase = true) -> PaymentMethod.PIX
                            else -> PaymentMethod.DEBITO
                        },
                        cardId = card?.id,
                        accountId = account?.id,
                        // Já está no saldo do banco: criado antes do momento em que o saldo passou a valer.
                        createdAt = account?.let { it.updatedAtMillis - 1 } ?: nowMillis,
                    ),
                    note = tx.description.take(80),
                )
            }

        return SyncPlan(
            accounts = accountMap.values.toList(),
            cards = cardMap.values.toList(),
            debtUpserts = debtUpserts,
            debtDeletes = debtDeletes,
            expenses = expenses,
            skippedDuplicates = skipped,
            paidInvoices = paid,
        )
    }

    /** Vencimento da fatura do ciclo em que [today] cai (a que recebe as compras de hoje). */
    fun cycleDueDate(card: Card, today: LocalDate): LocalDate {
        val thisMonth = YearMonth.from(today)
        val closeMonth = if (today.dayOfMonth <= card.closingDay) thisMonth else thisMonth.plusMonths(1)
        val dueMonth = if (card.dueDay > card.closingDay) closeMonth else closeMonth.plusMonths(1)
        return dueMonth.clampDay(card.dueDay)
    }

    fun sameBank(appName: String, institution: String): Boolean {
        val a = normalize(appName)
        val b = normalize(institution)
        return a.isNotEmpty() && b.isNotEmpty() && (a.contains(b) || b.contains(a))
    }

    private fun newCard(remote: RemoteAccount): Card {
        val due = remote.billDueDate?.dayOfMonth ?: 10
        val closing = if (due > 8) due - 7 else due + 23
        return Card(id = ID_PREFIX + remote.id, name = remote.institution, closingDay = closing.coerceIn(1, 31), dueDay = due)
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase().replace(Regex("[^a-z0-9]"), "")

    /** Categoria da Pluggy (em inglês) para as categorias do app. */
    fun categoryFor(pluggy: String?, categories: List<Category>): String {
        val c = pluggy.orEmpty().lowercase()
        val wanted = when {
            listOf("groceries", "supermarket").any { it in c } -> "mercado"
            listOf("eating out", "food delivery", "restaurant", "bakery").any { it in c } -> "delivery"
            listOf("taxi", "ride", "gas station", "transport", "parking", "toll", "vehicle").any { it in c } -> "transporte"
            listOf("pharmacy", "health", "hospital", "doctor", "dentist", "wellness").any { it in c } -> "saude"
            listOf("electricity", "water", "housing", "rent", "home").any { it in c } -> "casa"
            listOf("digital services", "streaming", "telecommunications", "mobile", "internet", "software", "subscription").any { it in c } -> "assinaturas"
            listOf("leisure", "entertainment", "games", "travel", "tickets", "bars", "cinema").any { it in c } -> "lazer"
            else -> "outros"
        }
        return categories.firstOrNull { it.id == wanted }?.id ?: categories.firstOrNull { it.id == "outros" }?.id ?: categories.firstOrNull()?.id ?: "outros"
    }
}
