package com.nxuslab.dreymanager.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.YearMonth

class Converters {
    @TypeConverter fun dateToText(value: LocalDate?): String? = value?.toString()
    @TypeConverter fun textToDate(value: String?): LocalDate? = value?.let(LocalDate::parse)
    @TypeConverter fun monthToText(value: YearMonth?): String? = value?.toString()
    @TypeConverter fun textToMonth(value: String?): YearMonth? = value?.let(YearMonth::parse)
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 1") fun observe(): Flow<SettingsEntity?>
    @Query("SELECT * FROM settings WHERE id = 1") suspend fun get(): SettingsEntity?
    @Upsert suspend fun upsert(settings: SettingsEntity)
}

@Dao
interface IncomeDao {
    @Query("SELECT * FROM income ORDER BY kind, date") fun observeAll(): Flow<List<IncomeEntity>>
    @Upsert suspend fun upsert(income: IncomeEntity)
    @Query("DELETE FROM income WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM account ORDER BY name") fun observeAll(): Flow<List<AccountEntity>>
    @Upsert suspend fun upsert(account: AccountEntity)
    @Query("DELETE FROM account WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface FixedBillDao {
    @Query("SELECT * FROM fixed_bill ORDER BY dayOfMonth") fun observeAll(): Flow<List<FixedBillEntity>>
    @Upsert suspend fun upsert(bill: FixedBillEntity)
    @Query("DELETE FROM fixed_bill WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface CardDao {
    @Query("SELECT * FROM card ORDER BY name") fun observeAll(): Flow<List<CardEntity>>
    @Upsert suspend fun upsert(card: CardEntity)
    @Query("DELETE FROM card WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT * FROM card_debt ORDER BY nextDueDate") fun observeDebts(): Flow<List<CardDebtEntity>>
    @Query("SELECT * FROM card_debt WHERE cardId = :cardId ORDER BY nextDueDate") fun observeDebtsOf(cardId: String): Flow<List<CardDebtEntity>>
    @Upsert suspend fun upsertDebt(debt: CardDebtEntity)
    @Query("DELETE FROM card_debt WHERE id = :id") suspend fun deleteDebt(id: String)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM category ORDER BY name") fun observeAll(): Flow<List<CategoryEntity>>
    @Query("SELECT COUNT(*) FROM category") suspend fun count(): Int
    @Upsert suspend fun upsertAll(categories: List<CategoryEntity>)
    @Query("DELETE FROM category WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT * FROM envelope") fun observeEnvelopes(): Flow<List<EnvelopeEntity>>
    @Upsert suspend fun upsertEnvelope(envelope: EnvelopeEntity)
    @Query("DELETE FROM envelope WHERE categoryId = :categoryId") suspend fun deleteEnvelope(categoryId: String)
}

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expense ORDER BY date DESC, id") fun observeAll(): Flow<List<ExpenseEntity>>
    /** Datas são texto ISO, então a comparação de texto respeita a ordem cronológica. */
    @Query("SELECT * FROM expense WHERE date BETWEEN :from AND :to ORDER BY date DESC, id")
    fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<ExpenseEntity>>
    @Upsert suspend fun upsert(expense: ExpenseEntity)
    @Query("DELETE FROM expense WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface WishDao {
    @Query("SELECT * FROM wish ORDER BY createdAt") fun observeAll(): Flow<List<WishEntity>>
    @Upsert suspend fun upsert(wish: WishEntity)
    @Query("DELETE FROM wish WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT * FROM wish_saving ORDER BY date") fun observeSavings(): Flow<List<WishSavingEntity>>
    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM wish_saving WHERE wishId = :wishId") suspend fun savedFor(wishId: String): Long
    @Upsert suspend fun upsertSaving(saving: WishSavingEntity)
}

@Database(
    entities = [
        SettingsEntity::class, IncomeEntity::class, AccountEntity::class, FixedBillEntity::class,
        CardEntity::class, CardDebtEntity::class, CategoryEntity::class, EnvelopeEntity::class,
        ExpenseEntity::class, WishEntity::class, WishSavingEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NxusDatabase : RoomDatabase() {
    abstract fun settingsDao(): SettingsDao
    abstract fun incomeDao(): IncomeDao
    abstract fun accountDao(): AccountDao
    abstract fun fixedBillDao(): FixedBillDao
    abstract fun cardDao(): CardDao
    abstract fun categoryDao(): CategoryDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun wishDao(): WishDao

    companion object {
        const val NAME = "nxuslife.db"

        @Volatile private var instance: NxusDatabase? = null

        fun get(context: Context): NxusDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, NxusDatabase::class.java, NAME).build().also { instance = it }
        }
    }
}
