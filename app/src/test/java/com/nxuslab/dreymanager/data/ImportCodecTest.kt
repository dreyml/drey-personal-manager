package com.nxuslab.dreymanager.data

import com.nxuslab.dreymanager.domain.model.DebtKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class ImportCodecTest {
    private val valid = """
        {"format": "nxuslife-import", "version": 1,
         "cards": [{"name": "Cartão A", "closingDay": 30, "dueDay": 10, "interestBp": null, "limitCents": 300000, "cancelGoal": true,
           "debts": [
             {"kind": "FATURA_ABERTA", "description": "Fatura", "amountCents": 121742, "remaining": null, "nextDue": "2026-12-10"},
             {"kind": "PARCELA", "description": "Curso", "amountCents": 3569, "remaining": 9, "nextDue": "2027-01-10"}
           ]}],
         "variableBills": [{"title": "Energia", "day": 15, "estimateCents": 20000}]}
    """.trimIndent()

    @Test
    fun arquivoValido() {
        val bundle = ImportCodec.decode(valid)
        val card = bundle.cards.single()
        assertEquals("Cartão A", card.card.name)
        assertTrue(card.card.cancelGoal)
        assertEquals(300_000L, card.card.limitCents)
        assertEquals(listOf(DebtKind.FATURA_ABERTA, DebtKind.PARCELA), card.debts.map { it.kind })
        assertTrue(card.debts.all { it.cardId == card.card.id })
        assertEquals(LocalDate.of(2027, 1, 10), card.debts[1].nextDueDate)
        assertEquals(9, card.debts[1].remainingInstallments)
        assertEquals("Energia", bundle.variableBills.single().title)
    }

    @Test
    fun outroTipoDeArquivoERecusado() {
        rejected("""{"exportedAt": 1}""")
        rejected("não é json")
    }

    @Test
    fun parcelaSemQuantidadeERecusadaComMensagem() {
        val message = rejected(valid.replace("\"remaining\": 9", "\"remaining\": null"))
        assertTrue(message, message.contains("cards") && message.contains("Curso"))
    }

    private fun rejected(json: String): String {
        try {
            ImportCodec.decode(json)
        } catch (e: ImportFormatException) {
            return e.message.orEmpty()
        }
        fail("esperava ImportFormatException")
        error("unreachable")
    }
}
