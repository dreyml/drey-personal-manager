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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCodecTest {
    private val full = BackupSnapshot(
        exportedAt = 1_700_000_000_000,
        categories = listOf(CategoryEntity("food", "Alimentação", 0xFFF59E0B)),
        transactions = listOf(
            TransactionEntity("t1", "Mercado", 12_345, "EXPENSE", "food", 1_700_000_000_001, recurring = false),
            TransactionEntity("t2", "Salário", 500_000, "INCOME", null, 1_700_000_000_002, recurring = true),
        ),
        tasks = listOf(TaskEntity("k1", "Pagar luz", "2026-01-31", completed = false, reminderAt = null, createdAt = 1)),
        recurringBills = listOf(RecurringBillEntity("r1", "Internet", 9_990, 31, null, active = true)),
        goals = listOf(GoalEntity("g1", "Viagem", 1_000_000, 250_000, null)),
        installments = listOf(InstallmentPlanEntity("i1", "Notebook", 300_000, 30_000, 10, "2026-01", "2026-10", 15, 2)),
        debts = listOf(DebtEntity("d1", "Ana", "Almoço", 4_500, null, settled = false, createdAt = 2)),
        banks = listOf(BankEntity("b1", "Banco X", 123_456, 0xFF8994FF)),
        savingsJars = listOf(SavingsJarEntity("j1", "Reserva", 50_000, null), SavingsJarEntity("j2", "Carro", 10_000, 2_000_000)),
    )

    @Test
    fun roundTripKeepsEveryTableIncludingBanksAndJars() {
        val decoded = BackupCodec.decode(BackupCodec.encode(full))
        assertEquals(full, decoded)
        assertEquals(11, decoded.totalRecords)
    }

    @Test
    fun legacyBackupWithoutNewTablesIsAccepted() {
        val legacy = """{"exportedAt": 1, "transactions": [{"id":"t1","description":"x","amountCents":100,"type":"EXPENSE","createdAt":1,"recurring":false}]}"""
        val decoded = BackupCodec.decode(legacy)
        assertEquals(1, decoded.transactions.size)
        assertTrue(decoded.banks.isEmpty())
        assertTrue(decoded.savingsJars.isEmpty())
    }

    @Test
    fun invalidJsonIsRejectedWithReadableMessage() {
        assertRejected("isto não é json")
        assertRejected("""{"transactions": []}""")
    }

    @Test
    fun missingRequiredFieldNamesTheBrokenItem() {
        val broken = """{"exportedAt": 1, "banks": [{"id":"b1","name":"ok","balanceCents":1}, {"id":"b2","name":"sem saldo"}]}"""
        val message = assertRejected(broken)
        assertTrue(message, message.contains("banks") && message.contains("item 2"))
    }

    @Test
    fun unknownTransactionTypeIsRejected() {
        assertRejected("""{"exportedAt": 1, "transactions": [{"id":"t1","description":"x","amountCents":1,"type":"OUTRO","createdAt":1}]}""")
    }

    @Test
    fun backupFromNewerSchemaIsRejected() {
        assertRejected("""{"exportedAt": 1, "schemaVersion": ${BackupCodec.SCHEMA_VERSION + 1}}""")
    }

    private fun assertRejected(json: String): String {
        try {
            BackupCodec.decode(json)
        } catch (e: BackupFormatException) {
            return e.message.orEmpty()
        }
        fail("Esperava BackupFormatException para: $json")
        error("unreachable")
    }
}
