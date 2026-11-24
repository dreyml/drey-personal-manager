package com.nxuslab.dreymanager.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nxuslab.dreymanager.data.db.NxusDatabase
import com.nxuslab.dreymanager.data.db.WishSavingEntity
import com.nxuslab.dreymanager.data.repo.FinanceRepository
import com.nxuslab.dreymanager.domain.model.Card
import com.nxuslab.dreymanager.domain.model.CardDebt
import com.nxuslab.dreymanager.domain.model.DebtKind
import com.nxuslab.dreymanager.domain.model.Expense
import com.nxuslab.dreymanager.domain.model.PaymentMethod
import com.nxuslab.dreymanager.domain.model.Wish
import com.nxuslab.dreymanager.domain.model.WishPriority
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
class NxusDatabaseTest {
    private lateinit var context: Context
    private lateinit var db: NxusDatabase
    private lateinit var repo: FinanceRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NxusDatabase::class.java).build()
        repo = FinanceRepository(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun gastosSaoFiltradosPorSemana() = runTest {
        repo.seedCategoriesIfEmpty().getOrThrow()
        listOf(10, 12, 18).forEach { day ->
            repo.saveExpense(Expense("e$day", 1_000L * day, "mercado", LocalDate.of(2027, 1, day), PaymentMethod.PIX)).getOrThrow()
        }
        val week = db.expenseDao().observeBetween(LocalDate.of(2027, 1, 11), LocalDate.of(2027, 1, 17)).first()
        assertEquals(listOf("e12"), week.map { it.id })
    }

    @Test
    fun compraNoCartaoGuardaOMesDaFatura() = runTest {
        repo.saveExpense(Expense("c", 8_900, "lazer", LocalDate.of(2027, 1, 13), PaymentMethod.CARTAO, cardId = "nubank", reservedForInvoice = YearMonth.of(2027, 2))).getOrThrow()
        val saved = repo.snapshot.first().expenses.single()
        assertEquals(YearMonth.of(2027, 2), saved.reservedForInvoice)
        assertEquals(PaymentMethod.CARTAO, saved.method)
    }

    @Test
    fun apagarCartaoApagaSuasDividas() = runTest {
        val card = Card("nubank", "Nubank", 3, 10, 800)
        val debt = CardDebt("p", "nubank", DebtKind.PARCELA, "Notebook", 30_000, 10, LocalDate.of(2027, 1, 15))
        repo.saveCard(card, listOf(debt)).getOrThrow()
        assertEquals(1, repo.snapshot.first().debts.size)
        repo.deleteCard("nubank").getOrThrow()
        assertTrue(repo.snapshot.first().debts.isEmpty())
    }

    @Test
    fun valorGuardadoPorDesejoESomado() = runTest {
        repo.saveWish(Wish("fone", "Fone", 120_000, priority = WishPriority.ALTA, createdAt = 1)).getOrThrow()
        repo.addWishSaving(WishSavingEntity("s1", "fone", 20_000, LocalDate.of(2027, 1, 5))).getOrThrow()
        repo.addWishSaving(WishSavingEntity("s2", "fone", 5_000, LocalDate.of(2027, 2, 5))).getOrThrow()
        assertEquals(25_000L, repo.snapshot.first().savedByWish["fone"])
        assertEquals(25_000L, db.wishDao().savedFor("fone"))
    }

    @Test
    fun ajustesTemPadraoEPodemSerAlterados() = runTest {
        val defaults = repo.snapshot.first().settings
        assertEquals(DayOfWeek.MONDAY, defaults.budget.weekStart)
        assertEquals(21 * 60, defaults.reminderMinutes)
        assertFalse(defaults.onboardingDone)

        repo.updateSettings { it.copy(onboardingDone = true, budget = it.budget.copy(payoffExtraMonthlyCents = 30_000)) }.getOrThrow()
        val updated = repo.snapshot.first().settings
        assertTrue(updated.onboardingDone)
        assertEquals(30_000L, updated.budget.payoffExtraMonthlyCents)
    }

    @Test
    fun categoriasPadraoSoSaoCriadasUmaVez() = runTest {
        repo.seedCategoriesIfEmpty().getOrThrow()
        repo.seedCategoriesIfEmpty().getOrThrow()
        assertEquals(FinanceRepository.DEFAULT_CATEGORIES.size, repo.snapshot.first().categories.size)
    }

    @Test
    fun limpezaDaVersaoAntigaRodaUmaVez() = runTest {
        // Simula o banco da versão antiga; num app recém-instalado nem a pasta databases/ existe.
        val legacy = context.getDatabasePath(LegacyWipe.LEGACY_DATABASE)
        assertTrue("não criou a pasta ${legacy.parentFile}", legacy.parentFile!!.isDirectory || legacy.parentFile!!.mkdirs())
        legacy.writeText("legado")
        assertTrue(context.getDatabasePath(LegacyWipe.LEGACY_DATABASE).exists())

        assertTrue(LegacyWipe.runOnce(context, repo).getOrThrow())
        assertFalse(context.getDatabasePath(LegacyWipe.LEGACY_DATABASE).exists())
        assertTrue(repo.settings().legacyWiped)

        assertFalse(LegacyWipe.runOnce(context, repo).getOrThrow())
    }
}
