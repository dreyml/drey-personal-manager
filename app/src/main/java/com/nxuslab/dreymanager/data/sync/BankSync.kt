package com.nxuslab.dreymanager.data.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nxuslab.dreymanager.NxusApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class BankSyncStatus(
    val configured: Boolean = false,
    val serverUrl: String = BankSync.DEFAULT_SERVER,
    val running: Boolean = false,
    val lastSyncMillis: Long = 0,
    val lastMessage: String? = null,
    val lastFailed: Boolean = false,
)

/**
 * Sincronização com os bancos: guarda endereço e token (o token cifrado com uma chave do Android
 * Keystore), busca os dados no servidor e aplica as mudanças numa única transação.
 */
object BankSync {
    const val DEFAULT_SERVER = "https://updateappdrey.nxuslab.com"
    private const val PREFS = "bank_sync"
    private const val KEY_ALIAS = "nxus_bank_token"
    private const val WORK_DAILY = "bank-sync-daily"
    private const val WORK_NOW = "bank-sync-now"
    /** Dias de transações pedidos a cada vez (o suficiente para achar pagamentos de fatura). */
    private const val LOOKBACK_DAYS = 40L
    private const val TAG = "NxusLife"

    private val _status = MutableStateFlow(BankSyncStatus())
    val status: StateFlow<BankSyncStatus> = _status
    private val mutex = Mutex()

    fun init(context: Context) {
        val p = prefs(context)
        _status.value = BankSyncStatus(
            configured = p.contains("token"),
            serverUrl = p.getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER,
            lastSyncMillis = p.getLong("lastSync", 0),
            lastMessage = p.getString("lastMessage", null),
            lastFailed = p.getBoolean("lastFailed", false),
        )
        if (_status.value.configured) scheduleDaily(context)
    }

    /** Salva a conexão. A partir de hoje os gastos do banco entram sozinhos (os anteriores já estão no saldo). */
    fun configure(context: Context, serverUrl: String, token: String) {
        val p = prefs(context)
        p.edit()
            .putString("server", serverUrl.trim().trimEnd('/'))
            .putString("token", encrypt(token.trim()))
            .putString("importFrom", p.getString("importFrom", null) ?: LocalDate.now().toString())
            .apply()
        _status.value = _status.value.copy(configured = true, serverUrl = serverUrl.trim().trimEnd('/'))
        scheduleDaily(context)
    }

    fun disconnect(context: Context) {
        prefs(context).edit().clear().apply()
        WorkManager.getInstance(context).cancelUniqueWork(WORK_DAILY)
        _status.value = BankSyncStatus()
    }

    /** Pede uma sincronização em segundo plano (funciona mesmo se a tela fechar). */
    fun requestNow(context: Context) {
        if (!_status.value.configured) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NOW, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BankSyncWorker>().setConstraints(networkOnly()).build(),
        )
    }

    /** Sincroniza ao abrir o app se a última vez foi há mais de 6 horas. */
    fun syncIfStale(context: Context) {
        val s = _status.value
        if (s.configured && System.currentTimeMillis() - s.lastSyncMillis > TimeUnit.HOURS.toMillis(6)) requestNow(context)
    }

    suspend fun run(context: Context): Result<String> = mutex.withLock {
        val p = prefs(context)
        val token = p.getString("token", null)?.let { runCatching { decrypt(it) }.getOrNull() }
            ?: return Result.failure(IllegalStateException("Conecte os bancos em Mais › Bancos conectados."))
        _status.value = _status.value.copy(running = true)
        val result = runCatching {
            val today = LocalDate.now()
            val importFrom = p.getString("importFrom", null)?.let { LocalDate.parse(it) } ?: today
            val payload = withContext(Dispatchers.IO) {
                BankSyncClient.fetch(_status.value.serverUrl, token, today.minusDays(LOOKBACK_DAYS))
            }
            val repository = (context.applicationContext as NxusApp).repository
            val snapshot = repository.snapshot.first()
            val plan = BankSyncPlanner.plan(payload, snapshot, today, System.currentTimeMillis(), importFrom)
            repository.applyBankSync(plan).getOrThrow()
            summary(plan, payload.failedConnections)
        }
        val now = System.currentTimeMillis()
        val message = result.getOrElse { e ->
            Log.w(TAG, "Sincronização falhou", e)
            (e as? BankSyncClient.SyncError)?.message ?: "Não foi possível sincronizar agora."
        }
        p.edit().putString("lastMessage", message).putBoolean("lastFailed", result.isFailure)
            .apply { if (result.isSuccess) putLong("lastSync", now) }.apply()
        _status.value = _status.value.copy(
            running = false,
            lastMessage = message,
            lastFailed = result.isFailure,
            lastSyncMillis = if (result.isSuccess) now else _status.value.lastSyncMillis,
        )
        result.map { message }
    }

    private fun summary(plan: SyncPlan, failed: Int): String = buildList {
        add("${plan.accounts.size} conta(s) e ${plan.cards.size} cartão(ões) atualizados")
        if (plan.expenses.isNotEmpty()) add("${plan.expenses.size} gasto(s) novo(s)")
        if (plan.skippedDuplicates > 0) add("${plan.skippedDuplicates} já lançado(s) por você")
        if (plan.paidInvoices > 0) add("${plan.paidInvoices} fatura(s) paga(s)")
        if (failed > 0) add("$failed banco(s) sem resposta")
    }.joinToString(" · ")

    private fun scheduleDaily(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_DAILY, ExistingPeriodicWorkPolicy.KEEP,
            // Atraso inicial: quem conecta já sincroniza na hora pelo botão.
            PeriodicWorkRequestBuilder<BankSyncWorker>(12, TimeUnit.HOURS)
                .setInitialDelay(12, TimeUnit.HOURS)
                .setConstraints(networkOnly())
                .build(),
        )
    }

    private fun networkOnly() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- Token cifrado (AES-GCM com chave que nunca sai do Keystore) ---

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val data = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        }
        return String(cipher.doFinal(data, 12, data.size - 12))
    }
}

class BankSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (BankSync.run(applicationContext).isSuccess) Result.success() else Result.retry().takeIf { runAttemptCount < 2 } ?: Result.failure()
}
