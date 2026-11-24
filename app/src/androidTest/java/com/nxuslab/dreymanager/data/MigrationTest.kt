package com.nxuslab.dreymanager.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nxuslab.dreymanager.data.db.MIGRATION_1_2
import com.nxuslab.dreymanager.data.db.MIGRATION_2_3
import com.nxuslab.dreymanager.data.db.NxusDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A 2.0.1 migra o banco da 2.0.0 sem perder nada do que o usuário já cadastrou. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NxusDatabase::class.java)

    @Test
    fun v1ParaV2MantemOsDados() {
        helper.createDatabase(DB, 1).apply {
            execSQL("INSERT INTO account (id, name, balanceCents, balanceUpdatedAt) VALUES ('c', 'Conta principal', 1145, '2026-11-20')")
            execSQL("INSERT INTO card (id, name, closingDay, dueDay, monthlyInterestBp, status) VALUES ('nu', 'Nubank', 15, 22, NULL, 'ATIVO')")
            execSQL("INSERT INTO card_debt (id, cardId, kind, description, amountCents, remainingInstallments, nextDueDate) VALUES ('f', 'nu', 'FATURA_ABERTA', 'Fatura', 65881, NULL, '2026-11-23')")
            execSQL("INSERT INTO expense (id, amountCents, categoryId, date, method, accountId, cardId, reservedForInvoice, note) VALUES ('e', 2500, 'mercado', '2026-11-20', 'PIX', NULL, NULL, NULL, NULL)")
            execSQL("INSERT INTO income (id, kind, amountCents, dayOfMonth, date, accountId, toDebtCents) VALUES ('s', 'FIXA', 300000, 5, NULL, 'c', 0)")
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 2, true, MIGRATION_1_2)

        db.query("SELECT name, balanceCents, kind, balanceUpdatedAtMillis FROM account").use {
            it.moveToFirst()
            assertEquals("Conta principal", it.getString(0))
            assertEquals(1145L, it.getLong(1))
            assertEquals("BANCO", it.getString(2))
            assertEquals(0L, it.getLong(3))
        }
        db.query("SELECT limitCents, cancelGoal FROM card WHERE id = 'nu'").use {
            it.moveToFirst()
            assertEquals(true, it.isNull(0))
            assertEquals(0, it.getInt(1))
        }
        db.query("SELECT COUNT(*) FROM card_debt").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT createdAt FROM expense").use { it.moveToFirst(); assertEquals(0L, it.getLong(0)) }
        db.query("SELECT COUNT(*) FROM variable_bill").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }

    @Test
    fun v2ParaV3MantemOsDadosEAdicionaNomeEPagos() {
        helper.createDatabase(DB3, 2).apply {
            execSQL("INSERT INTO settings (id, weekStartDay, reminderMinutes, reserveCents, payoffExtraMonthlyCents, weeklyLimitOverrideCents, extraSplitToDebtPercent, legacyWiped, onboardingDone, notificationsDenied) VALUES (1, 1, 1260, 0, 20000, NULL, 50, 1, 1, 0)")
            execSQL("INSERT INTO account (id, name, balanceCents, balanceUpdatedAt, kind, balanceUpdatedAtMillis) VALUES ('c', 'Sicoob', 48620, '2026-11-24', 'BANCO', 5)")
            close()
        }

        val db = helper.runMigrationsAndValidate(DB3, 3, true, MIGRATION_2_3)

        db.query("SELECT onboardingDone, payoffExtraMonthlyCents, userName FROM settings").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
            assertEquals(20000L, it.getLong(1))
            assertEquals("", it.getString(2))
        }
        db.query("SELECT balanceCents FROM account").use { it.moveToFirst(); assertEquals(48620L, it.getLong(0)) }
        db.execSQL("INSERT INTO paid_item (`key`, month, paidAt) VALUES ('bill:luz', '2026-11', 1)")
        db.query("SELECT COUNT(*) FROM paid_item").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
    }

    @Test
    fun v3ParaV4MantemOsDadosEAdicionaAgua() {
        helper.createDatabase(DB4, 3).apply {
            execSQL("INSERT INTO account (id, name, balanceCents, balanceUpdatedAt, kind, balanceUpdatedAtMillis) VALUES ('c', 'Sicoob', 48620, '2026-11-24', 'BANCO', 5)")
            close()
        }

        val db = helper.runMigrationsAndValidate(DB4, 4, true, com.nxuslab.dreymanager.data.db.MIGRATION_3_4)

        db.query("SELECT balanceCents FROM account").use { it.moveToFirst(); assertEquals(48620L, it.getLong(0)) }
        db.execSQL("INSERT INTO water_settings (id, goalMl, bottleMl, startMinute, endMinute, remindersOn, updatedAt) VALUES (1, 3000, 750, 480, 1020, 1, 1)")
        db.execSQL("INSERT INTO water_log (at, ml) VALUES (10, 300)")
        db.query("SELECT SUM(ml) FROM water_log").use { it.moveToFirst(); assertEquals(300, it.getInt(0)) }
    }

    private companion object {
        const val DB4 = "migration-test-4.db"
        const val DB3 = "migration-test-3.db"
        const val DB = "migration-test.db"
    }
}
