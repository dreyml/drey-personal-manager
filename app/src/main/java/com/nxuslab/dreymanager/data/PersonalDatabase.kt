package com.nxuslab.dreymanager.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transactions")
data class TransactionEntity(
    @androidx.room.PrimaryKey val id: String,
    val description: String,
    val amountCents: Long,
    val type: String,
    val categoryId: String? = null,
    val createdAt: Long,
    val recurring: Boolean = false,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val color: Long,
    val kind: String = "EXPENSE",
)

@Entity(tableName = "recurring_bills")
data class RecurringBillEntity(
    @androidx.room.PrimaryKey val id: String,
    val title: String,
    val amountCents: Long,
    val dayOfMonth: Int,
    val categoryId: String? = null,
    val active: Boolean = true,
)

@Entity(tableName = "goals")
data class GoalEntity(
    @androidx.room.PrimaryKey val id: String,
    val title: String,
    val targetCents: Long,
    val currentCents: Long = 0,
    val deadline: String? = null,
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @androidx.room.PrimaryKey val id: String,
    val title: String,
    val dueDate: String? = null,
    val completed: Boolean = false,
    val reminderAt: Long? = null,
    val createdAt: Long,
)

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transactions: List<TransactionEntity>)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY completed ASC, createdAt DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<TaskEntity>)

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): TaskEntity?

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int
}

@Dao
interface RecurringBillDao {
    @Query("SELECT * FROM recurring_bills WHERE active = 1 ORDER BY dayOfMonth")
    fun observeActive(): Flow<List<RecurringBillEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bill: RecurringBillEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(bills: List<RecurringBillEntity>)
}

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals ORDER BY deadline IS NULL, deadline")
    fun observeAll(): Flow<List<GoalEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(goal: GoalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(goals: List<GoalEntity>)
}

@Database(entities = [TransactionEntity::class, TaskEntity::class, CategoryEntity::class, RecurringBillEntity::class, GoalEntity::class], version = 2, exportSchema = true)
abstract class PersonalDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun taskDao(): TaskDao
    abstract fun categoryDao(): CategoryDao
    abstract fun recurringBillDao(): RecurringBillDao
    abstract fun goalDao(): GoalDao

    companion object {
        @Volatile private var instance: PersonalDatabase? = null

        fun get(context: Context): PersonalDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PersonalDatabase::class.java,
                "drey_manager.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS categories (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, color INTEGER NOT NULL, kind TEXT NOT NULL)")
                database.execSQL("CREATE TABLE IF NOT EXISTS recurring_bills (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, amountCents INTEGER NOT NULL, dayOfMonth INTEGER NOT NULL, categoryId TEXT, active INTEGER NOT NULL)")
                database.execSQL("CREATE TABLE IF NOT EXISTS goals (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, targetCents INTEGER NOT NULL, currentCents INTEGER NOT NULL, deadline TEXT)")
            }
        }
    }
}
