package com.nxuslab.dreymanager.data.sync

import com.nxuslab.dreymanager.data.repo.Account
import com.nxuslab.dreymanager.data.repo.Category
import com.nxuslab.dreymanager.data.repo.FinanceRepository
import com.nxuslab.dreymanager.data.repo.FinanceSnapshot
import com.nxuslab.dreymanager.domain.model.AccountKind
import com.nxuslab.dreymanager.domain.model.Card
import com.nxuslab.dreymanager.domain.model.CardDebt
import com.nxuslab.dreymanager.domain.model.DebtKind
import com.nxuslab.dreymanager.domain.model.Expense
import com.nxuslab.dreymanager.domain.model.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

class BankSyncPlannerTest {
    private val today = LocalDate.of(2026, 11, 23)
    private val now = 1_000_000_000L
    private val refreshed = now - 3_600_000
    private val categories = FinanceRepository.DEFAULT_CATEGORIES.map { Category(it.id, it.name, it.emoji, it.color) }

    private val nubankAccount = Account("acc-nu", "Nubank", 5_000, today.minusDays(3), AccountKind.BANCO, 1)
    private val wallet = Account("wallet", "Carteira", 2_000, today.minusDays(3), AccountKind.DINHEIRO, 1)
    private val nubankCard = Card("card-nu", "Nubank", closingDay = 15, dueDay = 22, cancelGoal = true)
    private val novInvoice = CardDebt("inv-nov", "card-nu", DebtKind.FATURA_ABERTA, "Nov", 65_881, null, LocalDate.of(2026, 11, 23))
    private val decInvoice = CardDebt("inv-dec", "card-nu", DebtKind.FATURA_ABERTA, "Dez", 53_695, null, LocalDate.of(2026, 12, 22))

    private val remoteBank = RemoteAccount("r-bank", "Nubank", "BANK", "Nu Pagamentos", 30, updatedAtMillis = refreshed)
    private val remoteCard = RemoteAccount("r-card", "Nubank", "CREDIT", "gold", 56_465, creditLimitCents = 240_000)

    private fun snapshot(expenses: List<Expense> = emptyList()) = FinanceSnapshot(
        accounts = listOf(wallet, nubankAccount),
        cards = listOf(nubankCard),
        debts = listOf(novInvoice, decInvoice),
        categories = categories,
        expenses = expenses,
    )

    private fun tx(id: String, account: String, cents: Long, date: LocalDate = today, category: String? = null, description: String = "Compra") =
        RemoteTransaction(id, account, date, cents, description, category)

    private fun plan(txs: List<RemoteTransaction>, data: FinanceSnapshot = snapshot(), importFrom: LocalDate = today.minusDays(2)) =
        BankSyncPlanner.plan(SyncPayload(listOf(remoteBank, remoteCard), txs), data, today, now, importFrom)

    @Test fun bankBalanceReplacesAppBalanceFromRefreshTime() {
        val acc = plan(emptyList()).accounts.single()
        assertEquals("acc-nu", acc.id)
        assertEquals(30L, acc.balanceCents)
        assertEquals(refreshed, acc.updatedAtMillis)
    }

    @Test fun cardGetsLimitAndCurrentCycleInvoice() {
        val p = plan(emptyList())
        assertEquals(240_000L, p.cards.single().limitCents)
        assertEquals(listOf(decInvoice.copy(amountCents = 56_465)), p.debtUpserts)
    }

    @Test fun invoicePaymentRemovesPaidInvoice() {
        val p = plan(listOf(tx("pay", "r-card", 65_881, category = "Credit card payment")))
        assertEquals(listOf("inv-nov"), p.debtDeletes)
        assertEquals(1, p.paidInvoices)
        assertTrue(p.expenses.isEmpty())
    }

    @Test fun manualReservedPurchaseIsNotCountedTwice() {
        val manual = Expense("m1", 2_000, "outros", today, PaymentMethod.CARTAO, "card-nu", YearMonth.of(2026, 12), createdAt = 5)
        assertEquals(54_465L, plan(emptyList(), snapshot(listOf(manual))).debtUpserts.single().amountCents)
    }

    @Test fun spendingBecomesExpensesWithMappedCategoryAndMethod() {
        val p = plan(
            listOf(
                tx("t1", "r-bank", -1_550, category = "Transfer - PIX", description = "Pix Joao"),
                tx("t2", "r-card", -2_770, category = "Taxi and ride-hailing"),
                tx("t3", "r-bank", 300_000, category = "Salary"),
                tx("t4", "r-bank", -10_000, category = "Same person transfer"),
                tx("t5", "r-bank", -999, date = today.minusDays(10), category = "Groceries"),
            ),
        )
        val byId = p.expenses.associateBy { it.expense.id }
        assertEquals(setOf("pluggy:t1", "pluggy:t2"), byId.keys)
        val pix = byId.getValue("pluggy:t1").expense
        assertEquals(PaymentMethod.PIX, pix.method)
        assertEquals("acc-nu", pix.accountId)
        assertTrue("já está no saldo do banco", pix.createdAt < refreshed)
        val card = byId.getValue("pluggy:t2").expense
        assertEquals(PaymentMethod.CARTAO, card.method)
        assertEquals("card-nu", card.cardId)
        assertEquals("transporte", card.categoryId)
        assertEquals(null, card.reservedForInvoice)
    }

    @Test fun manualEntryIsNotDuplicated() {
        val manual = Expense("m1", 1_550, "mercado", today.minusDays(1), PaymentMethod.PIX, accountId = "acc-nu", createdAt = 5)
        val p = plan(listOf(tx("t1", "r-bank", -1_550)), snapshot(listOf(manual)))
        assertTrue(p.expenses.isEmpty())
        assertEquals(1, p.skippedDuplicates)
    }

    @Test fun alreadyImportedTransactionIsSkipped() {
        val imported = Expense("pluggy:t1", 1_550, "outros", today, PaymentMethod.PIX, accountId = "acc-nu")
        val p = plan(listOf(tx("t1", "r-bank", -1_550)), snapshot(listOf(imported)))
        assertTrue(p.expenses.isEmpty())
        assertEquals(0, p.skippedDuplicates)
    }

    @Test fun unknownBankCreatesAccountButIdleCardIsIgnored() {
        val mp = RemoteAccount("r-mp", "Mercado Pago", "BANK", "Conta", 4_200)
        val mpCard = RemoteAccount("r-mpc", "Mercado Pago", "CREDIT", "Mercado Pago", 0, 10_000)
        val p = BankSyncPlanner.plan(SyncPayload(listOf(mp, mpCard), emptyList()), snapshot(), today, now, today)
        assertEquals("Mercado Pago", p.accounts.single().name)
        assertEquals("pluggy:r-mp", p.accounts.single().id)
        assertTrue(p.cards.isEmpty())
    }

    @Test fun cycleDueDates() {
        assertEquals(LocalDate.of(2026, 12, 22), BankSyncPlanner.cycleDueDate(nubankCard, today))
        assertEquals(LocalDate.of(2026, 11, 22), BankSyncPlanner.cycleDueDate(nubankCard, LocalDate.of(2026, 11, 10)))
        assertEquals(LocalDate.of(2026, 12, 10), BankSyncPlanner.cycleDueDate(Card("s", "Sicoob", closingDay = 30, dueDay = 10), today))
    }

    @Test fun bankNameMatching() {
        assertTrue(BankSyncPlanner.sameBank("Mercado pago", "Mercado Pago"))
        assertTrue(BankSyncPlanner.sameBank("Conta Nubank", "Nubank"))
        assertFalse(BankSyncPlanner.sameBank("Sicoob", "Nubank"))
    }

    @Test fun parsesServerJson() {
        val json = """
            {"syncedAt":"2026-11-23T19:00:00+00:00","errors":[{"itemId":"x","error":"404"}],
             "accounts":[{"id":"a","itemId":"i","institution":"Nubank","type":"CREDIT","name":"gold","balanceCents":56465,
               "creditLimitCents":240000,"availableCreditCents":65613,"billAmountCents":56465,"billDueDate":"2026-11-23",
               "billCloseDate":null,"updatedAt":"2026-11-23T18:22:35.435Z"}],
             "transactions":[{"id":"t","accountId":"a","date":"2026-11-23","amountCents":-2770,"description":"Uber","category":null,"type":"DEBIT","status":"PENDING"}]}
        """.trimIndent()
        val payload = BankSyncClient.parse(json)
        assertEquals(1, payload.failedConnections)
        val a = payload.accounts.single()
        assertEquals(240_000L, a.creditLimitCents)
        assertEquals(LocalDate.of(2026, 11, 23), a.billDueDate)
        assertEquals(Instant.parse("2026-11-23T18:22:35.435Z").toEpochMilli(), a.updatedAtMillis)
        assertEquals(null, payload.transactions.single().category)
    }
}
