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
import com.nxuslab.dreymanager.data.CategoryEntity
import com.nxuslab.dreymanager.data.GoalEntity
import com.nxuslab.dreymanager.data.RecurringBillEntity
import com.nxuslab.dreymanager.data.TaskEntity
import com.nxuslab.dreymanager.data.TransactionEntity
import com.nxuslab.dreymanager.data.TransactionType
import com.nxuslab.dreymanager.data.VaultItemEntity
import com.nxuslab.dreymanager.data.InstallmentPlanEntity
import com.nxuslab.dreymanager.data.DebtEntity
import com.nxuslab.dreymanager.security.VaultCrypto
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
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

    val categories: StateFlow<List<CategoryEntity>> = database.categoryDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recurringBills: StateFlow<List<RecurringBillEntity>> = database.recurringBillDao().observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val goals: StateFlow<List<GoalEntity>> = database.goalDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val vaultItems: StateFlow<List<VaultItemEntity>> = database.vaultDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val installmentPlans: StateFlow<List<InstallmentPlanEntity>> = database.installmentDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val debts: StateFlow<List<DebtEntity>> = database.debtDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var vaultDialog = false

    init {
        viewModelScope.launch { seedCategoriesIfNeeded() }
        viewModelScope.launch {
            _updateState.value = updateChecker.check()
        }
    }

    private suspend fun seedCategoriesIfNeeded() {
        if (database.categoryDao().count() == 0) {
            database.categoryDao().insertAll(
                listOf(
                    CategoryEntity("housing", "Moradia", 0xFF8B5CF6),
                    CategoryEntity("food", "Alimentação", 0xFFF59E0B),
                    CategoryEntity("transport", "Transporte", 0xFF38BDF8),
                    CategoryEntity("subscriptions", "Assinaturas", 0xFFEC4899),
                    CategoryEntity("loans", "Cobranças", 0xFF34D399),
                    CategoryEntity("other", "Outros", 0xFF94A3B8),
                ),
            )
        }
    }

    fun addRecurringBill(title: String, amount: Double, dayOfMonth: Int, categoryId: String?) {
        if (title.isBlank() || amount <= 0 || dayOfMonth !in 1..31) return
        viewModelScope.launch {
            database.recurringBillDao().insert(
                RecurringBillEntity(
                    id = java.util.UUID.randomUUID().toString(), title = title.trim(),
                    amountCents = (amount * 100).roundToLong(), dayOfMonth = dayOfMonth, categoryId = categoryId,
                ),
            )
        }
    }

    fun addGoal(title: String, target: Double, deadline: String?) {
        if (title.isBlank() || target <= 0) return
        viewModelScope.launch {
            database.goalDao().insert(
                GoalEntity(
                    id = java.util.UUID.randomUUID().toString(), title = title.trim(),
                    targetCents = (target * 100).roundToLong(), deadline = deadline?.trim()?.takeIf { it.isNotBlank() },
                ),
            )
        }
    }

    fun addTransaction(description: String, amount: Double, type: TransactionType, categoryId: String?) {
        if (description.isBlank() || amount <= 0) return
        viewModelScope.launch {
            database.transactionDao().insert(
                TransactionEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    description = description.trim(),
                    amountCents = (amount * 100).roundToLong(),
                    type = type.name,
                    categoryId = categoryId,
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

    fun addVaultItem(title: String, username: String, secret: String, notes: String?) {
        if (title.isBlank() || secret.isBlank()) return
        viewModelScope.launch { database.vaultDao().insert(VaultItemEntity(java.util.UUID.randomUUID().toString(), title.trim(), username.trim(), VaultCrypto.encrypt(secret), notes?.trim(), System.currentTimeMillis())) }
    }

    fun deleteVaultItem(id: String) { viewModelScope.launch { database.vaultDao().delete(id) } }

    fun addInstallment(description: String, total: Double, installment: Double, count: Int, startMonth: String, endMonth: String) {
        if (description.isBlank() || total <= 0 || installment <= 0 || count <= 0 || startMonth.isBlank() || endMonth.isBlank()) return
        viewModelScope.launch { database.installmentDao().insert(InstallmentPlanEntity(java.util.UUID.randomUUID().toString(), description.trim(), (total * 100).roundToLong(), (installment * 100).roundToLong(), count, startMonth.trim(), endMonth.trim())) }
    }

    fun deleteInstallment(id: String) { viewModelScope.launch { database.installmentDao().delete(id) } }

    fun addDebt(person: String, description: String, amount: Double, dueDate: String?) {
        if (person.isBlank() || description.isBlank() || amount <= 0) return
        viewModelScope.launch { database.debtDao().insert(DebtEntity(java.util.UUID.randomUUID().toString(), person.trim(), description.trim(), (amount * 100).roundToLong(), dueDate?.trim()?.takeIf { it.isNotBlank() }, false, System.currentTimeMillis())) }
    }

    fun deleteDebt(id: String) { viewModelScope.launch { database.debtDao().delete(id) } }

    suspend fun exportSnapshot(): String {
        val root = JSONObject()
        root.put("exportedAt", System.currentTimeMillis())
        root.put("transactions", JSONArray(database.transactionDao().observeAll().first().map { row ->
            JSONObject().apply {
                put("id", row.id); put("description", row.description); put("amountCents", row.amountCents)
                put("type", row.type); put("categoryId", row.categoryId); put("createdAt", row.createdAt); put("recurring", row.recurring)
            }
        }))
        root.put("tasks", JSONArray(database.taskDao().observeAll().first().map { row ->
            JSONObject().apply {
                put("id", row.id); put("title", row.title); put("dueDate", row.dueDate)
                put("completed", row.completed); put("reminderAt", row.reminderAt); put("createdAt", row.createdAt)
            }
        }))
        root.put("categories", JSONArray(database.categoryDao().observeAll().first().map { row ->
            JSONObject().apply { put("id", row.id); put("name", row.name); put("color", row.color); put("kind", row.kind) }
        }))
        root.put("recurringBills", JSONArray(database.recurringBillDao().observeActive().first().map { row ->
            JSONObject().apply { put("id", row.id); put("title", row.title); put("amountCents", row.amountCents); put("dayOfMonth", row.dayOfMonth); put("categoryId", row.categoryId); put("active", row.active) }
        }))
        root.put("goals", JSONArray(database.goalDao().observeAll().first().map { row ->
            JSONObject().apply { put("id", row.id); put("title", row.title); put("targetCents", row.targetCents); put("currentCents", row.currentCents); put("deadline", row.deadline) }
        }))
        return root.toString(2)
    }

    fun restoreSnapshot(json: String) {
        viewModelScope.launch {
            val root = JSONObject(json)
            root.optJSONArray("transactions")?.let { array -> database.transactionDao().insertAll((0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                TransactionEntity(o.getString("id"), o.getString("description"), o.getLong("amountCents"), o.getString("type"), o.optString("categoryId").takeIf { it != "null" && it.isNotBlank() }, o.getLong("createdAt"), o.optBoolean("recurring"))
            }) }
            root.optJSONArray("tasks")?.let { array -> database.taskDao().insertAll((0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                TaskEntity(o.getString("id"), o.getString("title"), o.optString("dueDate").takeIf { it != "null" && it.isNotBlank() }, o.optBoolean("completed"), if (o.isNull("reminderAt")) null else o.optLong("reminderAt"), o.getLong("createdAt"))
            }) }
            root.optJSONArray("recurringBills")?.let { array -> database.recurringBillDao().insertAll((0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                RecurringBillEntity(o.getString("id"), o.getString("title"), o.getLong("amountCents"), o.getInt("dayOfMonth"), o.optString("categoryId").takeIf { it != "null" && it.isNotBlank() }, o.optBoolean("active", true))
            }) }
            root.optJSONArray("goals")?.let { array -> database.goalDao().insertAll((0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                GoalEntity(o.getString("id"), o.getString("title"), o.getLong("targetCents"), o.optLong("currentCents"), o.optString("deadline").takeIf { it != "null" && it.isNotBlank() })
            }) }
        }
    }
}

private fun TransactionEntity.toModel() = MoneyTransaction(
    id = id,
    description = description,
    amount = amountCents / 100.0,
    type = TransactionType.valueOf(type),
    categoryId = categoryId,
    createdAt = createdAt,
)

private fun TaskEntity.toModel() = PersonalTask(
    id = id,
    title = title,
    dueDate = dueDate,
    completed = completed,
    createdAt = createdAt,
)
