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

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY completed ASC, createdAt DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity)

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): TaskEntity?

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [TransactionEntity::class, TaskEntity::class], version = 1, exportSchema = true)
abstract class PersonalDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun taskDao(): TaskDao

    companion object {
        @Volatile private var instance: PersonalDatabase? = null

        fun get(context: Context): PersonalDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PersonalDatabase::class.java,
                "drey_manager.db",
            ).build().also { instance = it }
        }
    }
}
