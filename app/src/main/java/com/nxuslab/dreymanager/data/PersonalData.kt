package com.nxuslab.dreymanager.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class TransactionType { INCOME, EXPENSE }

data class MoneyTransaction(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val amount: Double,
    val type: TransactionType,
    val categoryId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

data class PersonalTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val dueDate: String? = null,
    val completed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Persistência local da V1. Nenhum dado pessoal é enviado a um servidor. */
class PersonalDataStore(context: Context) {
    private val preferences = context.getSharedPreferences("drey_manager_v1", Context.MODE_PRIVATE)

    fun loadTransactions(): List<MoneyTransaction> = readArray("transactions").mapNotNull { json ->
        runCatching {
            MoneyTransaction(
                id = json.getString("id"),
                description = json.getString("description"),
                amount = json.getDouble("amount"),
                type = TransactionType.valueOf(json.getString("type")),
                createdAt = json.getLong("createdAt"),
            )
        }.getOrNull()
    }.sortedByDescending { it.createdAt }

    fun saveTransactions(transactions: List<MoneyTransaction>) {
        writeArray("transactions", transactions.map { item ->
            JSONObject().apply {
                put("id", item.id)
                put("description", item.description)
                put("amount", item.amount)
                put("type", item.type.name)
                put("createdAt", item.createdAt)
            }
        })
    }

    fun loadTasks(): List<PersonalTask> = readArray("tasks").mapNotNull { json ->
        runCatching {
            PersonalTask(
                id = json.getString("id"),
                title = json.getString("title"),
                dueDate = json.optString("dueDate").takeIf { it.isNotBlank() },
                completed = json.getBoolean("completed"),
                createdAt = json.getLong("createdAt"),
            )
        }.getOrNull()
    }.sortedWith(compareBy<PersonalTask> { it.completed }.thenByDescending { it.createdAt })

    fun saveTasks(tasks: List<PersonalTask>) {
        writeArray("tasks", tasks.map { item ->
            JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("dueDate", item.dueDate ?: "")
                put("completed", item.completed)
                put("createdAt", item.createdAt)
            }
        })
    }

    private fun readArray(key: String): List<JSONObject> = runCatching {
        val array = JSONArray(preferences.getString(key, "[]"))
        List(array.length()) { index -> array.getJSONObject(index) }
    }.getOrDefault(emptyList())

    private fun writeArray(key: String, values: List<JSONObject>) {
        val array = JSONArray()
        values.forEach(array::put)
        preferences.edit().putString(key, array.toString()).apply()
    }
}
