package com.nxuslab.dreymanager

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nxuslab.dreymanager.update.HttpUpdateRepository
import com.nxuslab.dreymanager.update.UpdateChecker
import com.nxuslab.dreymanager.update.UpdateState
import com.nxuslab.dreymanager.data.MoneyTransaction
import com.nxuslab.dreymanager.data.PersonalTask
import com.nxuslab.dreymanager.data.PersonalDatabase
import com.nxuslab.dreymanager.data.TaskEntity
import com.nxuslab.dreymanager.data.TransactionEntity
import com.nxuslab.dreymanager.data.TransactionType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val database = PersonalDatabase.get(application)
    private val updateChecker: UpdateChecker = UpdateChecker(
        repository = HttpUpdateRepository(BuildConfig.UPDATE_MANIFEST_URL),
        installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
    )
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Checking)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    val transactions: StateFlow<List<MoneyTransaction>> = database.transactionDao().observeAll()
        .map { rows -> rows.map { it.toModel() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tasks: StateFlow<List<PersonalTask>> = database.taskDao().observeAll()
        .map { rows -> rows.map { it.toModel() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            _updateState.value = updateChecker.check()
        }
    }

    fun addTransaction(description: String, amount: Double, type: TransactionType) {
        if (description.isBlank() || amount <= 0) return
        viewModelScope.launch {
            database.transactionDao().insert(
                TransactionEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    description = description.trim(),
                    amountCents = (amount * 100).roundToLong(),
                    type = type.name,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun deleteTransaction(id: String) {
        viewModelScope.launch { database.transactionDao().delete(id) }
    }

    fun addTask(title: String, dueDate: String?) {
        if (title.isBlank()) return
        viewModelScope.launch {
            database.taskDao().insert(
                TaskEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    title = title.trim(),
                    dueDate = dueDate?.trim()?.takeIf { it.isNotBlank() },
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun toggleTask(id: String) {
        viewModelScope.launch {
            database.taskDao().findById(id)?.let { row ->
                database.taskDao().insert(row.copy(completed = !row.completed))
            }
        }
    }

    fun deleteTask(id: String) {
        viewModelScope.launch { database.taskDao().delete(id) }
    }
}

private fun TransactionEntity.toModel() = MoneyTransaction(
    id = id,
    description = description,
    amount = amountCents / 100.0,
    type = TransactionType.valueOf(type),
    createdAt = createdAt,
)

private fun TaskEntity.toModel() = PersonalTask(
    id = id,
    title = title,
    dueDate = dueDate,
    completed = completed,
    createdAt = createdAt,
)
