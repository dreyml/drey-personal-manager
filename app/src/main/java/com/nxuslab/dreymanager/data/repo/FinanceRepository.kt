package com.nxuslab.dreymanager.data.repo

import android.util.Log
import androidx.room.withTransaction
import com.nxuslab.dreymanager.data.db.AccountEntity
import com.nxuslab.dreymanager.data.db.CardDebtEntity
import com.nxuslab.dreymanager.data.db.CardEntity
import com.nxuslab.dreymanager.data.db.CategoryEntity
import com.nxuslab.dreymanager.data.db.EnvelopeEntity
import com.nxuslab.dreymanager.data.db.ExpenseEntity
import com.nxuslab.dreymanager.data.db.FixedBillEntity
import com.nxuslab.dreymanager.data.db.IncomeEntity
import com.nxuslab.dreymanager.data.db.NxusDatabase
import com.nxuslab.dreymanager.data.db.SettingsEntity
import com.nxuslab.dreymanager.data.db.WishEntity
import com.nxuslab.dreymanager.data.db.WishSavingEntity
import com.nxuslab.dreymanager.domain.model.BudgetInputs
import com.nxuslab.dreymanager.domain.model.BudgetSettings
import com.nxuslab.dreymanager.domain.model.Card
import com.nxuslab.dreymanager.domain.model.CardDebt
import com.nxuslab.dreymanager.domain.model.Envelope
import com.nxuslab.dreymanager.domain.model.Expense
import com.nxuslab.dreymanager.domain.model.FixedBill
import com.nxuslab.dreymanager.domain.model.Income
import com.nxuslab.dreymanager.domain.model.Wish
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.DayOfWeek
import java.time.LocalDate

data class Account(val id: String, val name: String, val balanceCents: Long, val updatedAt: LocalDate)
data class Category(val id: String, val name: String, val emoji: String, val color: Long)

data class AppSettings(
    val budget: BudgetSettings = BudgetSettings(),
    val reminderMinutes: Int = 21 * 60,
    val onboardingDone: Boolean = false,
    val notificationsDenied: Boolean = false,
    val legacyWiped: Boolean = false,
)

/** Tudo o que as telas precisam, já convertido para os modelos de domínio. */
data class FinanceSnapshot(
    val settings: AppSettings = AppSettings(),
    val incomes: List<Income> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val fixedBills: List<FixedBill> = emptyList(),
    val cards: List<Card> = emptyList(),
    val debts: List<CardDebt> = emptyList(),
    val categories: List<Category> = emptyList(),
    val envelopes: List<Envelope> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val wishes: List<Wish> = emptyList(),
    val savedByWish: Map<String, Long> = emptyMap(),
) {
    val openingBalanceCents: Long get() = accounts.sumOf { it.balanceCents }

    fun budgetInputs(highPriorityWishMonthlyCents: Long = 0) = BudgetInputs(
        settings = settings.budget,
        incomes = incomes,
        fixedBills = fixedBills,
        debts = debts,
        expenses = expenses,
        highPriorityWishMonthlyCents = highPriorityWishMonthlyCents,
    )
}

/**
 * Acesso aos dados financeiros. Leituras são Flows de modelos de domínio; gravações devolvem
 * [Result] para a tela mostrar sucesso ou erro (nunca falham em silêncio).
 */
class FinanceRepository(private val db: NxusDatabase) {

    val snapshot: Flow<FinanceSnapshot> = combine(
        db.settingsDao().observe(),
        db.incomeDao().observeAll(),
        db.accountDao().observeAll(),
        db.fixedBillDao().observeAll(),
        db.cardDao().observeAll(),
        db.cardDao().observeDebts(),
        db.categoryDao().observeAll(),
        db.categoryDao().observeEnvelopes(),
        db.expenseDao().observeAll(),
        db.wishDao().observeAll(),
        db.wishDao().observeSavings(),
    ) { rows ->
        @Suppress("UNCHECKED_CAST")
        FinanceSnapshot(
            settings = (rows[0] as SettingsEntity?).toDomain(),
            incomes = (rows[1] as List<IncomeEntity>).map { it.toDomain() },
            accounts = (rows[2] as List<AccountEntity>).map { Account(it.id, it.name, it.balanceCents, it.balanceUpdatedAt) },
            fixedBills = (rows[3] as List<FixedBillEntity>).map { FixedBill(it.id, it.title, it.amountCents, it.dayOfMonth, it.categoryId, it.active) },
            cards = (rows[4] as List<CardEntity>).map { Card(it.id, it.name, it.closingDay, it.dueDay, it.monthlyInterestBp, it.status) },
            debts = (rows[5] as List<CardDebtEntity>).map { it.toDomain() },
            categories = (rows[6] as List<CategoryEntity>).map { Category(it.id, it.name, it.emoji, it.color) },
            envelopes = (rows[7] as List<EnvelopeEntity>).map { Envelope(it.categoryId, it.monthlyLimitCents) },
            expenses = (rows[8] as List<ExpenseEntity>).map { it.toDomain() },
            wishes = (rows[9] as List<WishEntity>).map { Wish(it.id, it.name, it.priceCents, it.targetDate, it.priority, it.status, it.createdAt) },
            savedByWish = (rows[10] as List<WishSavingEntity>).groupBy { it.wishId }.mapValues { (_, list) -> list.sumOf { it.amountCents } },
        )
    }

    // --- Ajustes ---

    suspend fun settings(): AppSettings = db.settingsDao().get().toDomain()

    suspend fun updateSettings(change: (AppSettings) -> AppSettings): Result<Unit> = safe {
        db.settingsDao().upsert(change(settings()).toEntity())
    }

    // --- Cadastros ---

    suspend fun saveIncome(income: Income, accountId: String? = null): Result<Unit> = safe {
        db.incomeDao().upsert(IncomeEntity(income.id, income.kind, income.amountCents, income.dayOfMonth, income.date, accountId, income.toDebtCents))
    }
    suspend fun deleteIncome(id: String): Result<Unit> = safe { db.incomeDao().delete(id) }

    suspend fun saveAccount(account: Account): Result<Unit> = safe {
        db.accountDao().upsert(AccountEntity(account.id, account.name, account.balanceCents, account.updatedAt))
    }
    suspend fun deleteAccount(id: String): Result<Unit> = safe { db.accountDao().delete(id) }

    suspend fun saveFixedBill(bill: FixedBill): Result<Unit> = safe {
        db.fixedBillDao().upsert(FixedBillEntity(bill.id, bill.title, bill.amountCents, bill.dayOfMonth, bill.categoryId, bill.active))
    }
    suspend fun deleteFixedBill(id: String): Result<Unit> = safe { db.fixedBillDao().delete(id) }

    /** Grava o cartão e substitui suas dívidas numa única transação. */
    suspend fun saveCard(card: Card, debts: List<CardDebt>? = null): Result<Unit> = safe {
        db.withTransaction {
            db.cardDao().upsert(CardEntity(card.id, card.name, card.closingDay, card.dueDay, card.monthlyInterestBp, card.status))
            debts?.forEach { db.cardDao().upsertDebt(it.toEntity()) }
        }
    }
    suspend fun deleteCard(id: String): Result<Unit> = safe { db.cardDao().delete(id) }
    suspend fun saveDebt(debt: CardDebt): Result<Unit> = safe { db.cardDao().upsertDebt(debt.toEntity()) }
    suspend fun deleteDebt(id: String): Result<Unit> = safe { db.cardDao().deleteDebt(id) }

    suspend fun saveEnvelope(envelope: Envelope): Result<Unit> = safe {
        db.categoryDao().upsertEnvelope(EnvelopeEntity(envelope.categoryId, envelope.monthlyLimitCents))
    }
    suspend fun deleteEnvelope(categoryId: String): Result<Unit> = safe { db.categoryDao().deleteEnvelope(categoryId) }
    suspend fun saveCategory(category: Category): Result<Unit> = safe {
        db.categoryDao().upsertAll(listOf(CategoryEntity(category.id, category.name, category.emoji, category.color)))
    }

    /** Cria as categorias padrão na primeira abertura. */
    suspend fun seedCategoriesIfEmpty(): Result<Unit> = safe {
        if (db.categoryDao().count() == 0) db.categoryDao().upsertAll(DEFAULT_CATEGORIES)
    }

    // --- Gastos e desejos ---

    suspend fun saveExpense(expense: Expense, accountId: String? = null, note: String? = null): Result<Unit> = safe {
        db.expenseDao().upsert(expense.toEntity(accountId, note))
    }
    suspend fun deleteExpense(id: String): Result<Unit> = safe { db.expenseDao().delete(id) }

    suspend fun saveWish(wish: Wish): Result<Unit> = safe {
        db.wishDao().upsert(WishEntity(wish.id, wish.name, wish.priceCents, wish.targetDate, wish.priority, wish.status, wish.createdAt))
    }
    suspend fun deleteWish(id: String): Result<Unit> = safe { db.wishDao().delete(id) }
    suspend fun addWishSaving(saving: WishSavingEntity): Result<Unit> = safe { db.wishDao().upsertSaving(saving) }

    private suspend fun <T> safe(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Falha ao gravar no banco", e)
        Result.failure(Exception("Não foi possível salvar. Tente de novo.", e))
    }

    companion object {
        private const val TAG = "NxusLife"

        val DEFAULT_CATEGORIES = listOf(
            CategoryEntity("mercado", "Mercado", "🛒", 0xFF7FB77E),
            CategoryEntity("delivery", "Delivery", "🍔", 0xFFF2A93B),
            CategoryEntity("lazer", "Lazer", "🎮", 0xFFE07A8E),
            CategoryEntity("transporte", "Transporte", "🚌", 0xFF7AA7E0),
            CategoryEntity("saude", "Saúde", "💊", 0xFF8FD3C1),
            CategoryEntity("casa", "Casa", "🏠", 0xFFB59AE0),
            CategoryEntity("assinaturas", "Assinaturas", "📺", 0xFFB0304A),
            CategoryEntity("outros", "Outros", "•", 0xFF9C8A8F),
        )
    }
}

private fun SettingsEntity?.toDomain(): AppSettings {
    val row = this ?: SettingsEntity()
    return AppSettings(
        budget = BudgetSettings(
            weekStart = DayOfWeek.of(row.weekStartDay.coerceIn(1, 7)),
            reserveCents = row.reserveCents,
            payoffExtraMonthlyCents = row.payoffExtraMonthlyCents,
            weeklyLimitOverrideCents = row.weeklyLimitOverrideCents,
            extraSplitToDebtPercent = row.extraSplitToDebtPercent,
        ),
        reminderMinutes = row.reminderMinutes,
        onboardingDone = row.onboardingDone,
        notificationsDenied = row.notificationsDenied,
        legacyWiped = row.legacyWiped,
    )
}

private fun AppSettings.toEntity() = SettingsEntity(
    weekStartDay = budget.weekStart.value,
    reminderMinutes = reminderMinutes,
    reserveCents = budget.reserveCents,
    payoffExtraMonthlyCents = budget.payoffExtraMonthlyCents,
    weeklyLimitOverrideCents = budget.weeklyLimitOverrideCents,
    extraSplitToDebtPercent = budget.extraSplitToDebtPercent,
    legacyWiped = legacyWiped,
    onboardingDone = onboardingDone,
    notificationsDenied = notificationsDenied,
)

private fun IncomeEntity.toDomain() = Income(id, kind, amountCents, dayOfMonth, date, toDebtCents)
private fun CardDebtEntity.toDomain() = CardDebt(id, cardId, kind, description, amountCents, remainingInstallments, nextDueDate)
private fun CardDebt.toEntity() = CardDebtEntity(id, cardId, kind, description, amountCents, remainingInstallments, nextDueDate)
private fun ExpenseEntity.toDomain() = Expense(id, amountCents, categoryId, date, method, cardId, reservedForInvoice)
private fun Expense.toEntity(accountId: String?, note: String?) =
    ExpenseEntity(id, amountCents, categoryId, date, method, accountId, cardId, reservedForInvoice, note)
