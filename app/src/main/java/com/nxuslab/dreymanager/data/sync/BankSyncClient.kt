package com.nxuslab.dreymanager.data.sync

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate

/** Fala com o serviço nxus-pluggy na VM (nunca com a Pluggy direto: o segredo fica lá). */
object BankSyncClient {

    class SyncError(message: String) : Exception(message)

    fun fetch(serverUrl: String, token: String, from: LocalDate): SyncPayload {
        val base = serverUrl.trim().trimEnd('/')
        if (!base.startsWith("https://")) throw SyncError("O endereço do servidor precisa começar com https://")
        val connection = (URL("$base/api/sync?from=$from").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 90_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return when (code) {
                200 -> parse(body)
                401 -> throw SyncError("Chave de acesso recusada. Confira o token em Mais › Bancos conectados.")
                429 -> throw SyncError("Muitas tentativas seguidas. Espere um minuto.")
                else -> throw SyncError("Os bancos não responderam agora (erro $code). Tente mais tarde.")
            }
        } catch (e: IOException) {
            throw SyncError("Sem conexão com o servidor. Verifique a internet.")
        } finally {
            connection.disconnect()
        }
    }

    fun parse(body: String): SyncPayload {
        val root = JSONObject(body)
        val accounts = root.getJSONArray("accounts").let { arr ->
            (0 until arr.length()).map { i ->
                val a = arr.getJSONObject(i)
                RemoteAccount(
                    id = a.getString("id"),
                    institution = a.optStringOrNull("institution") ?: "Banco",
                    type = a.getString("type"),
                    name = a.optStringOrNull("name") ?: "",
                    balanceCents = a.optLong("balanceCents"),
                    creditLimitCents = if (a.isNull("creditLimitCents")) null else a.optLong("creditLimitCents"),
                    billDueDate = a.optStringOrNull("billDueDate")?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
                    updatedAtMillis = a.optStringOrNull("updatedAt")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                )
            }
        }
        val transactions = root.getJSONArray("transactions").let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val t = arr.getJSONObject(i)
                val date = t.optStringOrNull("date")?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: return@mapNotNull null
                RemoteTransaction(
                    id = t.getString("id"),
                    accountId = t.getString("accountId"),
                    date = date,
                    amountCents = t.getLong("amountCents"),
                    description = t.optStringOrNull("description") ?: "",
                    category = t.optStringOrNull("category"),
                )
            }
        }
        return SyncPayload(accounts, transactions, root.optJSONArray("errors")?.length() ?: 0)
    }

    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
