package com.nxuslab.dreymanager.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nxuslab.dreymanager.domain.model.CardStatus
import com.nxuslab.dreymanager.domain.model.DebtKind
import com.nxuslab.dreymanager.domain.model.IncomeKind
import com.nxuslab.dreymanager.domain.model.PaymentMethod
import com.nxuslab.dreymanager.domain.model.WishPriority
import com.nxuslab.dreymanager.domain.model.WishStatus
import java.time.LocalDate
import java.time.YearMonth

/*
 * Tabelas do NxusLife 2.0 (especificação §5). Valores em centavos; datas como texto ISO via
 * Converters; enums gravados pelo nome.
 */

/** Linha única (id = 1) com os ajustes do app. */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    /** 1 = segunda … 7 = domingo (java.time.DayOfWeek). */
    val weekStartDay: Int = 1,
    /** Minutos desde a meia-noite (21:00 = 1260). */
    val reminderMinutes: Int = 21 * 60,
    val reserveCents: Long = 0,
    val payoffExtraMonthlyCents: Long = 0,
    val weeklyLimitOverrideCents: Long? = null,
    val extraSplitToDebtPercent: Int = 50,
    val legacyWiped: Boolean = false,
    val onboardingDone: Boolean = false,
    val notificationsDenied: Boolean = false,
)

@Entity(tableName = "income")
data class IncomeEntity(
    @PrimaryKey val id: String,
    val kind: IncomeKind,
    val amountCents: Long,
    val dayOfMonth: Int? = null,
    val date: LocalDate? = null,
    val accountId: String? = null,
    val toDebtCents: Long = 0,
)

@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val balanceCents: Long,
    val balanceUpdatedAt: LocalDate,
)

@Entity(tableName = "fixed_bill")
data class FixedBillEntity(
    @PrimaryKey val id: String,
    val title: String,
    val amountCents: Long,
    val dayOfMonth: Int,
    val categoryId: String? = null,
    val active: Boolean = true,
)

@Entity(tableName = "card")
data class CardEntity(
    @PrimaryKey val id: String,
    val name: String,
    val closingDay: Int,
    val dueDay: Int,
    val monthlyInterestBp: Int? = null,
    val status: CardStatus = CardStatus.ATIVO,
)

@Entity(
    tableName = "card_debt",
    foreignKeys = [ForeignKey(entity = CardEntity::class, parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("cardId")],
)
data class CardDebtEntity(
    @PrimaryKey val id: String,
    val cardId: String,
    val kind: DebtKind,
    val description: String,
    val amountCents: Long,
    val remainingInstallments: Int? = null,
    val nextDueDate: LocalDate,
)

@Entity(tableName = "category")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val emoji: String,
    val color: Long,
)

@Entity(
    tableName = "envelope",
    foreignKeys = [ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.CASCADE)],
)
data class EnvelopeEntity(
    @PrimaryKey val categoryId: String,
    val monthlyLimitCents: Long,
)

@Entity(tableName = "expense", indices = [Index("date"), Index("categoryId")])
data class ExpenseEntity(
    @PrimaryKey val id: String,
    val amountCents: Long,
    val categoryId: String,
    val date: LocalDate,
    val method: PaymentMethod,
    val accountId: String? = null,
    val cardId: String? = null,
    val reservedForInvoice: YearMonth? = null,
    val note: String? = null,
)

@Entity(tableName = "wish")
data class WishEntity(
    @PrimaryKey val id: String,
    val name: String,
    val priceCents: Long,
    val targetDate: LocalDate? = null,
    val priority: WishPriority,
    val status: WishStatus = WishStatus.JUNTANDO,
    val createdAt: Long,
)

@Entity(
    tableName = "wish_saving",
    foreignKeys = [ForeignKey(entity = WishEntity::class, parentColumns = ["id"], childColumns = ["wishId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("wishId")],
)
data class WishSavingEntity(
    @PrimaryKey val id: String,
    val wishId: String,
    val amountCents: Long,
    val date: LocalDate,
)
