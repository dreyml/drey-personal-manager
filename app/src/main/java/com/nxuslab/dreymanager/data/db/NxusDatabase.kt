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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    @Query("DELETE FROM card_debt WHERE cardId = :cardId") suspend fun deleteDebtsOf(cardId: String)
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

@Dao
interface VariableBillDao {
    @Query("SELECT * FROM variable_bill ORDER BY dayOfMonth") fun observeAll(): Flow<List<VariableBillEntity>>
    @Upsert suspend fun upsert(bill: VariableBillEntity)
    @Query("DELETE FROM variable_bill WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT * FROM variable_bill_value") fun observeValues(): Flow<List<VariableBillValueEntity>>
    @Upsert suspend fun upsertValue(value: VariableBillValueEntity)
}

/**
 * v1 → v2 (2.0.1): carteira/banco e momento do saldo nas contas, limite e meta de cancelamento
 * nos cartões, momento do lançamento em gastos e rendas, e as contas variáveis. Só adiciona:
 * nenhum dado existente é apagado.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `account` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'BANCO'")
        db.execSQL("ALTER TABLE `account` ADD COLUMN `balanceUpdatedAtMillis` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `card` ADD COLUMN `limitCents` INTEGER")
        db.execSQL("ALTER TABLE `card` ADD COLUMN `cancelGoal` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `expense` ADD COLUMN `createdAt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `income` ADD COLUMN `createdAt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS `variable_bill` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `dayOfMonth` INTEGER NOT NULL, `estimateCents` INTEGER NOT NULL, `active` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `variable_bill_value` (`billId` TEXT NOT NULL, `month` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, PRIMARY KEY(`billId`, `month`), FOREIGN KEY(`billId`) REFERENCES `variable_bill`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
    }
}

@Dao
interface PaidItemDao {
    @Query("SELECT * FROM paid_item") fun observeAll(): Flow<List<PaidItemEntity>>
    @Upsert suspend fun upsert(item: PaidItemEntity)
    @Query("DELETE FROM paid_item WHERE `key` = :key AND month = :month") suspend fun delete(key: String, month: java.time.YearMonth)
}

/** v2 → v3 (3.0.0): nome do usuário no hub e itens do mês marcados como pagos. Só adiciona. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `settings` ADD COLUMN `userName` TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE TABLE IF NOT EXISTS `paid_item` (`key` TEXT NOT NULL, `month` TEXT NOT NULL, `paidAt` INTEGER NOT NULL, PRIMARY KEY(`key`, `month`))")
    }
}

@Dao
interface WaterDao {
    @Query("SELECT * FROM water_settings WHERE id = 1") fun observeSettings(): Flow<WaterSettingsEntity?>
    @Query("SELECT * FROM water_settings WHERE id = 1") suspend fun settings(): WaterSettingsEntity?
    @Upsert suspend fun upsertSettings(settings: WaterSettingsEntity)
    @Query("SELECT * FROM water_log WHERE at >= :from ORDER BY at") fun observeSince(from: Long): Flow<List<WaterLogEntity>>
    @Query("SELECT COALESCE(SUM(ml), 0) FROM water_log WHERE at >= :from AND at < :to") suspend fun sumBetween(from: Long, to: Long): Int
    @androidx.room.Insert suspend fun insert(log: WaterLogEntity)
    @Query("DELETE FROM water_log WHERE id = (SELECT MAX(id) FROM water_log WHERE at >= :from)") suspend fun deleteLastSince(from: Long)
}

/** v3 → v4 (3.1.0): plano de água e registro de goles. Só adiciona. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `water_settings` (`id` INTEGER NOT NULL, `goalMl` INTEGER NOT NULL, `bottleMl` INTEGER NOT NULL, `startMinute` INTEGER NOT NULL, `endMinute` INTEGER NOT NULL, `remindersOn` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `water_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, `ml` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_water_log_at` ON `water_log` (`at`)")
    }
}

@Database(
    entities = [
        SettingsEntity::class, IncomeEntity::class, AccountEntity::class, FixedBillEntity::class,
        CardEntity::class, CardDebtEntity::class, CategoryEntity::class, EnvelopeEntity::class,
        ExpenseEntity::class, WishEntity::class, WishSavingEntity::class,
        VariableBillEntity::class, VariableBillValueEntity::class, PaidItemEntity::class,
        WaterSettingsEntity::class, WaterLogEntity::class,
    ],
    version = 4,
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
    abstract fun variableBillDao(): VariableBillDao
    abstract fun paidItemDao(): PaidItemDao
    abstract fun waterDao(): WaterDao

    companion object {
        const val NAME = "nxuslife.db"

        @Volatile private var instance: NxusDatabase? = null

        fun get(context: Context): NxusDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, NxusDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { instance = it }
        }
    }
}
