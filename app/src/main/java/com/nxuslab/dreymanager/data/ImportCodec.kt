package com.nxuslab.dreymanager.data

import com.nxuslab.dreymanager.data.repo.Account
import com.nxuslab.dreymanager.data.repo.ImportBundle
import com.nxuslab.dreymanager.data.repo.ImportedCard
import com.nxuslab.dreymanager.domain.model.AccountKind
import com.nxuslab.dreymanager.domain.model.Card
import com.nxuslab.dreymanager.domain.model.CardDebt
import com.nxuslab.dreymanager.domain.model.DebtKind
import com.nxuslab.dreymanager.domain.model.FixedBill
import com.nxuslab.dreymanager.domain.model.VariableBill
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

class ImportFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Arquivo de importação ("format": "nxuslife-import", "version": 1). Valores em centavos, datas
 * ISO. O arquivo inteiro é validado antes de qualquer gravação.
 */
object ImportCodec {
    const val FORMAT = "nxuslife-import"
    const val MAX_BYTES = 1024 * 1024

    fun decode(json: String): ImportBundle {
        val root = try { JSONObject(json) } catch (e: JSONException) { throw ImportFormatException("O arquivo não é um arquivo de importação do NxusLife.", e) }
        if (root.optString("format") != FORMAT) throw ImportFormatException("O arquivo não é um arquivo de importação do NxusLife.")
        if (root.optInt("version", 0) != 1) throw ImportFormatException("Versão do arquivo não suportada. Atualize o app.")
        return ImportBundle(
            cards = list(root, "cards") { c ->
                val cardId = UUID.randomUUID().toString()
                val card = Card(
                    id = cardId,
                    name = c.getString("name").trim().also { if (it.isEmpty()) throw JSONException("nome vazio") },
                    closingDay = day(c, "closingDay"),
                    dueDay = day(c, "dueDay"),
                    monthlyInterestBp = if (c.isNull("interestBp")) null else c.getInt("interestBp"),
                    limitCents = if (c.isNull("limitCents")) null else c.getLong("limitCents"),
                    cancelGoal = c.optBoolean("cancelGoal"),
                )
                val debts = c.optJSONArray("debts")?.let { array ->
                    (0 until array.length()).map { i ->
                        val d = array.getJSONObject(i)
                        val kind = DebtKind.entries.firstOrNull { it.name == d.getString("kind") } ?: throw JSONException("tipo de dívida desconhecido: ${d.getString("kind")}")
                        val remaining = if (d.isNull("remaining")) null else d.getInt("remaining")
                        if ((kind == DebtKind.PARCELA || kind == DebtKind.PARCELAMENTO_FATURA) && (remaining == null || remaining <= 0)) throw JSONException("parcelas restantes inválidas em ${d.optString("description")}")
                        CardDebt(UUID.randomUUID().toString(), cardId, kind, d.getString("description"), positive(d, "amountCents"), remaining, date(d, "nextDue"))
                    }
                }.orEmpty()
                ImportedCard(card, debts)
            },
            accounts = list(root, "accounts") { a ->
                Account(UUID.randomUUID().toString(), a.getString("name"), a.getLong("balanceCents"), LocalDate.now(), AccountKind.valueOf(a.optString("kind", "BANCO")))
            },
            fixedBills = list(root, "fixedBills") { b -> FixedBill(UUID.randomUUID().toString(), b.getString("title"), positive(b, "amountCents"), day(b, "day")) },
            variableBills = list(root, "variableBills") { b -> VariableBill(UUID.randomUUID().toString(), b.getString("title"), day(b, "day"), positive(b, "estimateCents")) },
        )
    }

    private fun <T> list(root: JSONObject, key: String, read: (JSONObject) -> T): List<T> {
        val array: JSONArray = root.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { i ->
            try {
                read(array.getJSONObject(i))
            } catch (e: Exception) {
                if (e is ImportFormatException) throw e
                throw ImportFormatException("Erro em \"$key\", item ${i + 1}: ${e.message}", e)
            }
        }
    }

    private fun day(o: JSONObject, key: String): Int = o.getInt(key).also { if (it !in 1..31) throw JSONException("$key fora de 1 a 31") }
    private fun positive(o: JSONObject, key: String): Long = o.getLong(key).also { if (it <= 0) throw JSONException("$key precisa ser maior que zero") }
    private fun date(o: JSONObject, key: String): LocalDate = try { LocalDate.parse(o.getString(key)) } catch (e: DateTimeParseException) { throw JSONException("$key não é uma data AAAA-MM-DD") }
}
