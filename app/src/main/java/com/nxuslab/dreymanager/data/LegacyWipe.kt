package com.nxuslab.dreymanager.data

import android.content.Context
import android.util.Log
import com.nxuslab.dreymanager.data.repo.FinanceRepository
import java.security.KeyStore

/**
 * Recomeço do zero (especificação §3): na primeira abertura da 2.0, apaga os dados da versão
 * antiga (banco, preferências e chaves do cofre) e marca que a limpeza já foi feita.
 * Pode ser chamado em toda abertura: depois da primeira vez não faz nada.
 */
object LegacyWipe {
    const val LEGACY_DATABASE = "drey_manager.db"
    const val LEGACY_PREFERENCES = "drey_manager_v1"
    val LEGACY_KEY_ALIASES = listOf("nxuslife_vault_key", "nxuslife_vault_key_v2")

    /** Devolve true quando a limpeza rodou agora, false quando já tinha sido feita. */
    suspend fun runOnce(context: Context, repository: FinanceRepository): Result<Boolean> {
        if (repository.settings().legacyWiped) return Result.success(false)

        context.deleteDatabase(LEGACY_DATABASE) // também remove -wal, -shm e -journal
        context.deleteSharedPreferences(LEGACY_PREFERENCES)
        runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            LEGACY_KEY_ALIASES.filter(store::containsAlias).forEach(store::deleteEntry)
        }.onFailure { Log.w("NxusLife", "Não foi possível apagar chaves antigas do Keystore", it) }

        return repository.updateSettings { it.copy(legacyWiped = true) }.map { true }
    }
}
