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
import com.nxuslab.dreymanager.data.BankEntity
import com.nxuslab.dreymanager.data.SavingsJarEntity
import com.nxuslab.dreymanager.security.VaultCrypto
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import android.util.Log
import com.nxuslab.dreymanager.backup.BackupCodec
import com.nxuslab.dreymanager.backup.BackupFormatException
import com.nxuslab.dreymanager.backup.BackupSnapshot
import com.nxuslab.dreymanager.security.VaultException
import kotlinx.coroutines.flow.first
import kotlin.math.roundToLong

private const val TAG = "NxusLife"

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

    val banks: StateFlow<List<BankEntity>> = database.bankDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val savingsJars: StateFlow<List<SavingsJarEntity>> = database.savingsJarDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())


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

    fun updateRecurringBill(existing: RecurringBillEntity, title: String, amount: Double, dayOfMonth: Int, categoryId: String?) {
        if (title.isBlank() || amount <= 0 || dayOfMonth !in 1..31) return
        viewModelScope.launch { database.recurringBillDao().insert(existing.copy(title = title.trim(), amountCents = (amount * 100).roundToLong(), dayOfMonth = dayOfMonth, categoryId = categoryId)) }
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

    fun updateGoal(existing: GoalEntity, title: String, target: Double, deadline: String?) {
        if (title.isBlank() || target <= 0) return
        viewModelScope.launch { database.goalDao().insert(existing.copy(title = title.trim(), targetCents = (target * 100).roundToLong(), deadline = deadline?.trim()?.takeIf { it.isNotBlank() })) }
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

    fun updateTransaction(id: String, description: String, amount: Double, type: TransactionType, categoryId: String?) {
        if (description.isBlank() || amount <= 0) return
        viewModelScope.launch {
            database.transactionDao().findById(id)?.let { row ->
                database.transactionDao().insert(row.copy(description = description.trim(), amountCents = (amount * 100).roundToLong(), type = type.name, categoryId = categoryId))
            }
        }
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

    fun updateTask(id: String, title: String, dueDate: String?) {
        if (title.isBlank()) return
        viewModelScope.launch {
            database.taskDao().findById(id)?.let { row ->
                database.taskDao().insert(row.copy(title = title.trim(), dueDate = dueDate?.trim()?.takeIf { it.isNotBlank() }))
            }
        }
    }

    /** Lê o segredo de um item. Exige autenticação recente (o Keystore recusa sem ela). */
    suspend fun revealVaultSecret(item: VaultItemEntity): Result<String> = vaultCall {
        VaultCrypto.decrypt(item.encryptedSecret, item.id)
    }

    /** Cria ([existing] nulo) ou edita um item do cofre. Um segredo em branco na edição mantém o atual. */
    suspend fun saveVaultItem(existing: VaultItemEntity?, title: String, username: String, secret: String): Result<Unit> = vaultCall {
        require(title.isNotBlank()) { "Informe um nome." }
        if (existing == null) {
            require(secret.isNotBlank()) { "Informe a senha ou o segredo." }
            val id = java.util.UUID.randomUUID().toString()
            database.vaultDao().insert(VaultItemEntity(id, title.trim(), username.trim(), VaultCrypto.encrypt(secret, id), null, System.currentTimeMillis()))
        } else {
            val encrypted = if (secret.isBlank()) existing.encryptedSecret else VaultCrypto.encrypt(secret, existing.id)
            database.vaultDao().insert(existing.copy(title = title.trim(), username = username.trim(), encryptedSecret = encrypted))
        }
    }

    /**
     * Recifra com a chave nova (que exige autenticação) os itens salvos com a chave antiga.
     * Deve ser chamada logo depois de o usuário desbloquear o cofre. Retorna quantos itens foram
     * migrados; itens que não puderam ser lidos ficam intactos e a chave antiga é mantida.
     */
    suspend fun migrateLegacyVault(): Result<Int> = vaultCall {
        val legacy = database.vaultDao().observeAll().first().filterNot { VaultCrypto.isCurrentFormat(it.encryptedSecret) }
        if (legacy.isEmpty()) {
            VaultCrypto.deleteLegacyKey()
            return@vaultCall 0
        }
        val migrated = legacy.mapNotNull { item ->
            runCatching { item.copy(encryptedSecret = VaultCrypto.encrypt(VaultCrypto.decrypt(item.encryptedSecret, item.id), item.id)) }.getOrNull()
        }
        database.withTransaction { migrated.forEach { database.vaultDao().insert(it) } }
        if (migrated.size == legacy.size) VaultCrypto.deleteLegacyKey()
        if (migrated.size < legacy.size) {
            throw VaultException("${legacy.size - migrated.size} item(ns) antigo(s) do cofre não puderam ser lidos e foram mantidos como estavam.")
        }
        migrated.size
    }

    private suspend fun <T> vaultCall(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: VaultException) {
            Result.failure(e)
        } catch (e: IllegalArgumentException) {
            Result.failure(VaultException(e.message ?: "Dados inválidos.", e))
        } catch (e: Exception) {
            Log.e(TAG, "Falha no cofre", e)
            Result.failure(VaultException("Não foi possível salvar no cofre. Tente de novo.", e))
        }
    }
    fun deleteVaultItem(id: String) { viewModelScope.launch { database.vaultDao().delete(id) } }

    fun addInstallment(description: String, total: Double, count: Int, firstDueDate: String) {
        if (description.isBlank() || total <= 0 || count <= 0 || firstDueDate.isBlank()) return
        val firstDue = runCatching { java.time.LocalDate.parse(firstDueDate) }.getOrNull() ?: return
        val totalCents = (total * 100).roundToLong()
        val installmentCents = totalCents / count
        viewModelScope.launch {
            database.installmentDao().insert(InstallmentPlanEntity(
                java.util.UUID.randomUUID().toString(), description.trim(), totalCents,
                installmentCents, count, java.time.YearMonth.from(firstDue).toString(),
                java.time.YearMonth.from(firstDue.plusMonths(count - 1L)).toString(), firstDue.dayOfMonth,
            ))
        }
    }

    fun deleteInstallment(id: String) { viewModelScope.launch { database.installmentDao().delete(id) } }
    fun markInstallmentPaid(id: String) { viewModelScope.launch { database.installmentDao().markNextPaid(id) } }

    fun updateInstallment(existing: InstallmentPlanEntity, description: String, total: Double, count: Int, firstDueDate: String) {
        if (description.isBlank() || total <= 0 || count <= 0) return
        val firstDue = runCatching { java.time.LocalDate.parse(firstDueDate) }.getOrNull() ?: return
        val totalCents = (total * 100).roundToLong()
        viewModelScope.launch { database.installmentDao().insert(existing.copy(description = description.trim(), totalCents = totalCents, installmentCents = totalCents / count, installmentCount = count, startMonth = java.time.YearMonth.from(firstDue).toString(), endMonth = java.time.YearMonth.from(firstDue.plusMonths(count - 1L)).toString(), dueDay = firstDue.dayOfMonth, paidCount = existing.paidCount.coerceAtMost(count))) }
    }

    fun addDebt(person: String, description: String, amount: Double, dueDate: String?) {
        if (person.isBlank() || description.isBlank() || amount <= 0) return
        viewModelScope.launch { database.debtDao().insert(DebtEntity(java.util.UUID.randomUUID().toString(), person.trim(), description.trim(), (amount * 100).roundToLong(), dueDate?.trim()?.takeIf { it.isNotBlank() }, false, System.currentTimeMillis())) }
    }

    fun deleteDebt(id: String) { viewModelScope.launch { database.debtDao().delete(id) } }
    fun updateDebt(existing: DebtEntity, person: String, description: String, amount: Double, dueDate: String?) {
        if (person.isBlank() || description.isBlank() || amount <= 0) return
        viewModelScope.launch { database.debtDao().insert(existing.copy(person = person.trim(), description = description.trim(), amountCents = (amount * 100).roundToLong(), dueDate = dueDate?.trim()?.takeIf { it.isNotBlank() })) }
    }
    fun addBank(name: String, balance: Double) {
        if (name.isBlank()) return
        viewModelScope.launch { database.bankDao().insert(BankEntity(java.util.UUID.randomUUID().toString(), name.trim(), (balance * 100).roundToLong())) }
    }
    fun updateBank(existing: BankEntity, name: String, balance: Double) {
        if (name.isBlank()) return
        viewModelScope.launch { database.bankDao().insert(existing.copy(name = name.trim(), balanceCents = (balance * 100).roundToLong())) }
    }
    fun deleteBank(id: String) { viewModelScope.launch { database.bankDao().delete(id) } }
    fun addSavingsJar(name: String, current: Double, target: Double?) {
        if (name.isBlank() || current < 0 || target?.let { it <= 0 } == true) return
        viewModelScope.launch { database.savingsJarDao().insert(SavingsJarEntity(java.util.UUID.randomUUID().toString(), name.trim(), (current * 100).roundToLong(), target?.times(100)?.roundToLong())) }
    }
    fun updateSavingsJar(existing: SavingsJarEntity, name: String, current: Double, target: Double?) {
        if (name.isBlank() || current < 0 || target?.let { it <= 0 } == true) return
        viewModelScope.launch { database.savingsJarDao().insert(existing.copy(name = name.trim(), currentCents = (current * 100).roundToLong(), targetCents = target?.times(100)?.roundToLong())) }
    }
    fun deleteSavingsJar(id: String) { viewModelScope.launch { database.savingsJarDao().delete(id) } }
    fun setDebtSettled(id: String, settled: Boolean) { viewModelScope.launch { database.debtDao().setSettled(id, settled) } }
    fun addGoalContribution(id: String, amount: Double) {
        if (amount <= 0) return
        viewModelScope.launch { database.goalDao().addContribution(id, (amount * 100).roundToLong()) }
    }
    fun deleteGoal(id: String) { viewModelScope.launch { database.goalDao().delete(id) } }
    fun setRecurringBillActive(id: String, active: Boolean) { viewModelScope.launch { database.recurringBillDao().setActive(id, active) } }
    fun deleteRecurringBill(id: String) { viewModelScope.launch { database.recurringBillDao().delete(id) } }

    /** Gera o JSON de backup com todas as tabelas, exceto o cofre (a chave dele não sai do aparelho). */
    suspend fun exportSnapshot(): String = withContext(Dispatchers.IO) {
        BackupCodec.encode(
            BackupSnapshot(
                exportedAt = System.currentTimeMillis(),
                categories = database.categoryDao().observeAll().first(),
                transactions = database.transactionDao().observeAll().first(),
                tasks = database.taskDao().observeAll().first(),
                recurringBills = database.recurringBillDao().observeAll().first(),
                goals = database.goalDao().observeAll().first(),
                installments = database.installmentDao().observeAll().first(),
                debts = database.debtDao().observeAll().first(),
                banks = database.bankDao().observeAll().first(),
                savingsJars = database.savingsJarDao().observeAll().first(),
            ),
        )
    }

    /** Lê e valida o arquivo sem gravar nada. Use antes de pedir confirmação ao usuário. */
    suspend fun parseSnapshot(json: String): Result<BackupSnapshot> = withContext(Dispatchers.Default) {
        try {
            Result.success(BackupCodec.decode(json))
        } catch (e: BackupFormatException) {
            Result.failure(e)
        }
    }

    /**
     * Grava um backup já validado numa única transação: ou entra tudo, ou nada.
     * Registros com o mesmo id são substituídos; registros que não estão no backup são mantidos.
     */
    suspend fun restoreSnapshot(snapshot: BackupSnapshot): Result<Int> = withContext(Dispatchers.IO) {
        try {
            database.withTransaction {
                database.categoryDao().insertAll(snapshot.categories)
                database.transactionDao().insertAll(snapshot.transactions)
                database.taskDao().insertAll(snapshot.tasks)
                database.recurringBillDao().insertAll(snapshot.recurringBills)
                database.goalDao().insertAll(snapshot.goals)
                snapshot.installments.forEach { database.installmentDao().insert(it) }
                snapshot.debts.forEach { database.debtDao().insert(it) }
                snapshot.banks.forEach { database.bankDao().insert(it) }
                snapshot.savingsJars.forEach { database.savingsJarDao().insert(it) }
            }
            Result.success(snapshot.totalRecords)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao restaurar backup", e)
            Result.failure(Exception("Não foi possível restaurar o backup. Nenhum dado foi alterado.", e))
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
