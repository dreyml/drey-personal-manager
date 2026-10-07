package com.nxuslab.dreymanager

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nxuslab.dreymanager.update.HttpUpdateRepository
import com.nxuslab.dreymanager.update.UpdateChecker
import com.nxuslab.dreymanager.update.UpdateState
import com.nxuslab.dreymanager.data.MoneyTransaction
import com.nxuslab.dreymanager.data.PersonalDataStore
import com.nxuslab.dreymanager.data.PersonalTask
import com.nxuslab.dreymanager.data.TransactionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStore = PersonalDataStore(application)
    private val updateChecker: UpdateChecker = UpdateChecker(
        repository = HttpUpdateRepository(BuildConfig.UPDATE_MANIFEST_URL),
        installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
    )
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Checking)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _transactions = MutableStateFlow(dataStore.loadTransactions())
    val transactions: StateFlow<List<MoneyTransaction>> = _transactions.asStateFlow()

    private val _tasks = MutableStateFlow(dataStore.loadTasks())
    val tasks: StateFlow<List<PersonalTask>> = _tasks.asStateFlow()

    init {
        viewModelScope.launch {
            _updateState.value = updateChecker.check()
        }
    }

    fun addTransaction(description: String, amount: Double, type: TransactionType) {
        if (description.isBlank() || amount <= 0) return
        val updated = listOf(
            MoneyTransaction(description = description.trim(), amount = amount, type = type),
        ) + _transactions.value
        _transactions.value = updated
        dataStore.saveTransactions(updated)
    }

    fun deleteTransaction(id: String) {
        val updated = _transactions.value.filterNot { it.id == id }
        _transactions.value = updated
        dataStore.saveTransactions(updated)
    }

    fun addTask(title: String, dueDate: String?) {
        if (title.isBlank()) return
        val updated = listOf(PersonalTask(title = title.trim(), dueDate = dueDate?.trim()?.takeIf { it.isNotBlank() })) + _tasks.value
        _tasks.value = updated
        dataStore.saveTasks(updated)
    }

    fun toggleTask(id: String) {
        val updated = _tasks.value.map { task ->
            if (task.id == id) task.copy(completed = !task.completed) else task
        }.sortedWith(compareBy<PersonalTask> { it.completed }.thenByDescending { it.createdAt })
        _tasks.value = updated
        dataStore.saveTasks(updated)
    }

    fun deleteTask(id: String) {
        val updated = _tasks.value.filterNot { it.id == id }
        _tasks.value = updated
        dataStore.saveTasks(updated)
    }
}
