package com.nxuslab.dreymanager.feature.gasto

import com.nxuslab.dreymanager.data.repo.Category
import com.nxuslab.dreymanager.data.repo.FinanceSnapshot
import com.nxuslab.dreymanager.domain.budget.GuardResult
import com.nxuslab.dreymanager.domain.model.Card
import com.nxuslab.dreymanager.domain.model.Envelope
import com.nxuslab.dreymanager.domain.model.Expense
import com.nxuslab.dreymanager.domain.model.Income
import com.nxuslab.dreymanager.domain.model.IncomeKind
import com.nxuslab.dreymanager.domain.model.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class AddExpenseLogicTest {
    private val today = LocalDate.of(2027, 1, 13)
    private val snapshot = FinanceSnapshot(
        incomes = listOf(Income("s", IncomeKind.FIXA, 310_000, dayOfMonth = 5)), // 10.000 por dia em janeiro → semana de 70.000
        cards = listOf(Card("nubank", "Nubank", closingDay = 3, dueDay = 10)),
        categories = listOf(Category("delivery", "Delivery", "🍔", 0), Category("mercado", "Mercado", "🛒", 0)),
        envelopes = listOf(Envelope("delivery", 12_000)),
        expenses = listOf(Expense("old", 10_000, "delivery", LocalDate.of(2027, 1, 12), PaymentMethod.PIX)),
    )

    @Test
    fun tecladoEmCentavos() {
        var digits = ""
        "3290".forEach { digits = Keypad.press(digits, it) }
        assertEquals(3_290L, Keypad.cents(digits))
        digits = Keypad.press(digits, '<')
        assertEquals(329L, Keypad.cents(digits))
        assertEquals("", Keypad.press("", '0')) // zero à esquerda não entra
    }

    @Test
    fun compraNoCartaoReservaAFaturaEAvisa() {
        val review = AddExpenseLogic.review(snapshot, today, 2_000, "mercado", PaymentMethod.CARTAO, "nubank")
        assertEquals(YearMonth.of(2027, 2), review.expense.reservedForInvoice)
        assertEquals("nubank", review.expense.cardId)
        assertTrue(review.guard is GuardResult.Ok)
        assertEquals(1, review.guard!!.card!!.purchasesThisMonth)
    }

    @Test
    fun estouroDoEnvelopePedeConfirmacao() {
        val review = AddExpenseLogic.review(snapshot, today, 3_000, "delivery", PaymentMethod.PIX, null)
        assertTrue(review.guard is GuardResult.Confirm) // 13.000 de 12.000
        assertNull(review.expense.reservedForInvoice)
    }

    @Test
    fun semRendaSalvaSemAlerta() {
        val review = AddExpenseLogic.review(snapshot.copy(incomes = emptyList()), today, 3_000, "delivery", PaymentMethod.PIX, null)
        assertNull(review.guard)
        assertNull(review.remainingAfterCents)
    }

    @Test
    fun rendaExtraSeparaParteParaQuitacao() {
        val income = AddExpenseLogic.extraIncome(today, 100_000, percentToDebt = 50)
        assertEquals(IncomeKind.EXTRA, income.kind)
        assertEquals(50_000L, income.toDebtCents)
        assertEquals(today, income.date)
    }

    @Test
    fun categoriasMaisUsadasPrimeiro() {
        assertEquals(listOf("delivery", "mercado"), AddExpenseLogic.categoryOrder(snapshot, today).map { it.id })
    }
}
