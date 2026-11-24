package com.nxuslab.dreymanager.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import com.nxuslab.dreymanager.domain.model.AccountKind
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
    /** Como o usuário quer ser chamado no hub (v3). */
    @ColumnInfo(defaultValue = "") val userName: String = "",
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
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
)

@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val balanceCents: Long,
    val balanceUpdatedAt: LocalDate,
    @ColumnInfo(defaultValue = "BANCO") val kind: AccountKind = AccountKind.BANCO,
    /** Momento exato em que o saldo foi informado; lançamentos depois dele ajustam o saldo. */
    @ColumnInfo(defaultValue = "0") val balanceUpdatedAtMillis: Long = 0,
)

@Entity(tableName = "variable_bill")
data class VariableBillEntity(
    @PrimaryKey val id: String,
    val title: String,
    val dayOfMonth: Int,
    val estimateCents: Long,
    val active: Boolean = true,
)

/** Valor real de uma conta variável num mês (ex.: energia de 2026-11). */
@Entity(
    tableName = "variable_bill_value",
    primaryKeys = ["billId", "month"],
    foreignKeys = [ForeignKey(entity = VariableBillEntity::class, parentColumns = ["id"], childColumns = ["billId"], onDelete = ForeignKey.CASCADE)],
)
data class VariableBillValueEntity(
    val billId: String,
    val month: YearMonth,
    val amountCents: Long,
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
    val limitCents: Long? = null,
    @ColumnInfo(defaultValue = "0") val cancelGoal: Boolean = false,
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
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
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

/**
 * Item do "Este mês, pague" marcado como pago (v3). [key] identifica o item: `bill:<id>`,
 * `debt:<id>`, `variable:<id>` ou `payoff`.
 */
@Entity(tableName = "paid_item", primaryKeys = ["key", "month"])
data class PaidItemEntity(
    val key: String,
    val month: java.time.YearMonth,
    val paidAt: Long,
)

/** Plano de água (linha única, id = 1). Horários em minutos desde 00:00. */
@Entity(tableName = "water_settings")
data class WaterSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val goalMl: Int,
    val bottleMl: Int,
    val startMinute: Int,
    val endMinute: Int,
    val remindersOn: Boolean,
    val updatedAt: Long,
)

/** Nota livre. [color]: 0 branco, 1 pêssego, 2 céu, 3 sálvia, 4 lavanda. */
@Entity(tableName = "note")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    val color: Int,
    val pinned: Boolean,
    val updatedAt: Long,
)

@Entity(tableName = "checklist")
data class ChecklistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "checklist_item",
    foreignKeys = [ForeignKey(entity = ChecklistEntity::class, parentColumns = ["id"], childColumns = ["listId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("listId")],
)
data class ChecklistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listId: Long,
    val text: String,
    val done: Boolean,
    val position: Int,
)

/** Lembrete com hora. [repeat] = nome de Repeat; [nextAt] null quando não toca mais. */
@Entity(tableName = "reminder")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val startDate: java.time.LocalDate,
    val minuteOfDay: Int,
    val repeat: String,
    val weekDays: Int,
    val nextAt: Long?,
    val doneAt: Long?,
    val createdAt: Long,
)

/** Cada gole registrado: horário (epoch ms) e quantidade. */
@Entity(tableName = "water_log", indices = [Index("at")])
data class WaterLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val ml: Int,
)
