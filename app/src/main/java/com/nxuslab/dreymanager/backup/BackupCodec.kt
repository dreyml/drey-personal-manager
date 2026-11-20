package com.nxuslab.dreymanager.backup

import com.nxuslab.dreymanager.data.BankEntity
import com.nxuslab.dreymanager.data.CategoryEntity
import com.nxuslab.dreymanager.data.DebtEntity
import com.nxuslab.dreymanager.data.GoalEntity
import com.nxuslab.dreymanager.data.InstallmentPlanEntity
import com.nxuslab.dreymanager.data.RecurringBillEntity
import com.nxuslab.dreymanager.data.SavingsJarEntity
import com.nxuslab.dreymanager.data.TaskEntity
import com.nxuslab.dreymanager.data.TransactionEntity
import com.nxuslab.dreymanager.data.TransactionType
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Tudo o que entra no arquivo de backup. Os itens do cofre ficam de fora de propósito:
 * eles são cifrados com uma chave do Android Keystore que não pode sair do aparelho.
 */
data class BackupSnapshot(
    val exportedAt: Long,
    val categories: List<CategoryEntity> = emptyList(),
    val transactions: List<TransactionEntity> = emptyList(),
    val tasks: List<TaskEntity> = emptyList(),
    val recurringBills: List<RecurringBillEntity> = emptyList(),
    val goals: List<GoalEntity> = emptyList(),
    val installments: List<InstallmentPlanEntity> = emptyList(),
    val debts: List<DebtEntity> = emptyList(),
    val banks: List<BankEntity> = emptyList(),
    val savingsJars: List<SavingsJarEntity> = emptyList(),
) {
    val totalRecords: Int
        get() = categories.size + transactions.size + tasks.size + recurringBills.size + goals.size +
            installments.size + debts.size + banks.size + savingsJars.size
}

class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

object BackupCodec {
    const val SCHEMA_VERSION = 2

    /** Limite de tamanho aceito na restauração, para não estourar memória com arquivos estranhos. */
    const val MAX_BYTES = 10 * 1024 * 1024

    fun encode(snapshot: BackupSnapshot): String {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("exportedAt", snapshot.exportedAt)
        root.put("categories", array(snapshot.categories) { put("id", it.id); put("name", it.name); put("color", it.color); put("kind", it.kind) })
        root.put("transactions", array(snapshot.transactions) {
            put("id", it.id); put("description", it.description); put("amountCents", it.amountCents)
            put("type", it.type); putOpt("categoryId", it.categoryId); put("createdAt", it.createdAt); put("recurring", it.recurring)
        })
        root.put("tasks", array(snapshot.tasks) {
            put("id", it.id); put("title", it.title); putOpt("dueDate", it.dueDate)
            put("completed", it.completed); putOpt("reminderAt", it.reminderAt); put("createdAt", it.createdAt)
        })
        root.put("recurringBills", array(snapshot.recurringBills) {
            put("id", it.id); put("title", it.title); put("amountCents", it.amountCents); put("dayOfMonth", it.dayOfMonth)
            putOpt("categoryId", it.categoryId); put("active", it.active)
        })
        root.put("goals", array(snapshot.goals) {
            put("id", it.id); put("title", it.title); put("targetCents", it.targetCents); put("currentCents", it.currentCents); putOpt("deadline", it.deadline)
        })
        root.put("installments", array(snapshot.installments) {
            put("id", it.id); put("description", it.description); put("totalCents", it.totalCents); put("installmentCents", it.installmentCents)
            put("installmentCount", it.installmentCount); put("startMonth", it.startMonth); put("endMonth", it.endMonth)
            put("dueDay", it.dueDay); put("paidCount", it.paidCount)
        })
        root.put("debts", array(snapshot.debts) {
            put("id", it.id); put("person", it.person); put("description", it.description); put("amountCents", it.amountCents)
            putOpt("dueDate", it.dueDate); put("settled", it.settled); put("createdAt", it.createdAt)
        })
        root.put("banks", array(snapshot.banks) { put("id", it.id); put("name", it.name); put("balanceCents", it.balanceCents); put("color", it.color) })
        root.put("savingsJars", array(snapshot.savingsJars) {
            put("id", it.id); put("name", it.name); put("currentCents", it.currentCents); putOpt("targetCents", it.targetCents)
        })
        return root.toString(2)
    }

    /**
     * Lê e valida o arquivo inteiro antes de qualquer gravação. Qualquer problema vira
     * [BackupFormatException] com uma mensagem que pode ser mostrada ao usuário.
     */
    fun decode(json: String): BackupSnapshot {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw BackupFormatException("O arquivo não é um backup válido do NxusLife.", e)
        }
        if (!root.has("exportedAt")) throw BackupFormatException("O arquivo não é um backup válido do NxusLife.")
        val version = root.optInt("schemaVersion", 1)
        if (version > SCHEMA_VERSION) {
            throw BackupFormatException("Este backup foi criado por uma versão mais nova do app. Atualize o NxusLife e tente de novo.")
        }
        return BackupSnapshot(
            exportedAt = root.optLong("exportedAt"),
            categories = list(root, "categories") { CategoryEntity(it.getString("id"), it.getString("name"), it.getLong("color"), it.optString("kind", "EXPENSE")) },
            transactions = list(root, "transactions") {
                val type = it.getString("type")
                if (TransactionType.entries.none { t -> t.name == type }) throw JSONException("tipo de lançamento desconhecido: $type")
                TransactionEntity(it.getString("id"), it.getString("description"), it.getLong("amountCents"), type, it.optStringOrNull("categoryId"), it.getLong("createdAt"), it.optBoolean("recurring"))
            },
            tasks = list(root, "tasks") {
                TaskEntity(it.getString("id"), it.getString("title"), it.optStringOrNull("dueDate"), it.optBoolean("completed"), if (it.isNull("reminderAt")) null else it.getLong("reminderAt"), it.getLong("createdAt"))
            },
            recurringBills = list(root, "recurringBills") {
                RecurringBillEntity(it.getString("id"), it.getString("title"), it.getLong("amountCents"), it.getInt("dayOfMonth"), it.optStringOrNull("categoryId"), it.optBoolean("active", true))
            },
            goals = list(root, "goals") {
                GoalEntity(it.getString("id"), it.getString("title"), it.getLong("targetCents"), it.optLong("currentCents"), it.optStringOrNull("deadline"))
            },
            installments = list(root, "installments") {
                InstallmentPlanEntity(it.getString("id"), it.getString("description"), it.getLong("totalCents"), it.getLong("installmentCents"), it.getInt("installmentCount"), it.getString("startMonth"), it.getString("endMonth"), it.optInt("dueDay", 1), it.optInt("paidCount"))
            },
            debts = list(root, "debts") {
                DebtEntity(it.getString("id"), it.getString("person"), it.getString("description"), it.getLong("amountCents"), it.optStringOrNull("dueDate"), it.optBoolean("settled"), it.getLong("createdAt"))
            },
            banks = list(root, "banks") {
                BankEntity(it.getString("id"), it.getString("name"), it.getLong("balanceCents"), it.optLong("color", 0xFF8994FF))
            },
            savingsJars = list(root, "savingsJars") {
                SavingsJarEntity(it.getString("id"), it.getString("name"), it.getLong("currentCents"), if (it.isNull("targetCents")) null else it.getLong("targetCents"))
            },
        )
    }

    private fun <T> array(rows: List<T>, fill: JSONObject.(T) -> Unit): JSONArray =
        JSONArray().apply { rows.forEach { row -> put(JSONObject().apply { fill(row) }) } }

    private fun <T> list(root: JSONObject, key: String, read: (JSONObject) -> T): List<T> {
        val array = root.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { index ->
            try {
                read(array.getJSONObject(index))
            } catch (e: JSONException) {
                throw BackupFormatException("Backup corrompido em \"$key\", item ${index + 1}: ${e.message}", e)
            }
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }
}
