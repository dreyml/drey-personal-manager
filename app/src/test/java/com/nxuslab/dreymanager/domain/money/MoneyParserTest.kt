package com.nxuslab.dreymanager.domain.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyParserTest {
    private fun valid(text: String): Long {
        val result = MoneyParser.parse(text)
        assertTrue("esperava válido para '$text', veio $result", result is MoneyResult.Valid)
        return (result as MoneyResult.Valid).cents
    }

    private fun invalid(text: String) {
        val result = MoneyParser.parse(text)
        assertTrue("esperava inválido para '$text', veio $result", result is MoneyResult.Invalid)
        assertTrue((result as MoneyResult.Invalid).message.isNotBlank())
    }

    @Test fun inteiro() = assertEquals(3200L, valid("32"))
    @Test fun umaCasaDecimal() = assertEquals(3290L, valid("32,9"))
    @Test fun duasCasasDecimais() = assertEquals(3290L, valid("32,90"))
    @Test fun milharComPonto() = assertEquals(123456L, valid("1.234,56"))
    @Test fun comSimboloDeReal() = assertEquals(123456L, valid("R$ 1.234,56"))
    @Test fun comEspacos() = assertEquals(700L, valid(" 7 "))
    @Test fun pontoComoDecimal() = assertEquals(3290L, valid("32.90"))
    @Test fun milharSemDecimal() = assertEquals(1_000_000L, valid("10.000"))
    @Test fun zeroCentavos() = assertEquals(5L, valid("0,05"))
    @Test fun limiteMaximo() = assertEquals(1_000_000_000L, valid("10.000.000,00"))

    @Test fun vazio() = invalid("")
    @Test fun soEspacos() = invalid("   ")
    @Test fun negativo() = invalid("-5")
    @Test fun tresCasasDecimais() = invalid("1,234")
    @Test fun letras() = invalid("abc")
    @Test fun notacaoCientifica() = invalid("1e3")
    @Test fun grupoDeMilharErrado() = invalid("1.23,4")
    @Test fun acimaDoLimite() = invalid("10000000,01")
    @Test fun zero() = invalid("0")

    @Test
    fun formatacao() {
        assertEquals("R$ 0,00", Money.format(0))
        assertEquals("R$ 0,05", Money.format(5))
        assertEquals("R$ 1.234,56", Money.format(123456))
        assertEquals("R$ 1.000.000,00", Money.format(100_000_000))
        assertEquals("−R$ 23,00", Money.format(-2300))
    }

    @Test
    fun formatacaoCurtaSemCentavos() {
        assertEquals("R$ 186", Money.formatShort(18640))
        assertEquals("R$ 7.840", Money.formatShort(784000))
    }
}
