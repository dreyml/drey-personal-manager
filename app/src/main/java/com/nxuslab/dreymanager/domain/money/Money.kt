package com.nxuslab.dreymanager.domain.money

import kotlin.math.abs

/** Resultado da leitura de um valor digitado. */
sealed interface MoneyResult {
    data class Valid(val cents: Long) : MoneyResult
    data class Invalid(val message: String) : MoneyResult
}

/**
 * Lê valores no formato brasileiro e devolve centavos.
 *
 * Aceita `32`, `32,9`, `32,90`, `1.234,56`, `R$ 1.234,56` e também `32.90` (ponto como decimal
 * quando há 1 ou 2 dígitos depois dele, o que não pode ser separador de milhar).
 */
object MoneyParser {
    const val MAX_CENTS = 1_000_000_000L // R$ 10.000.000,00

    private val brazilian = Regex("""^(\d{1,3}(?:\.\d{3})+|\d+)(?:,(\d{1,2}))?$""")
    private val dotDecimal = Regex("""^(\d+)\.(\d{1,2})$""")

    fun parse(text: String): MoneyResult {
        val cleaned = text.trim().removePrefix("R$").trim().replace(" ", "")
        if (cleaned.isEmpty()) return MoneyResult.Invalid("Informe um valor.")
        if (cleaned.startsWith("-")) return MoneyResult.Invalid("O valor não pode ser negativo.")

        val (integerPart, decimalPart) = brazilian.matchEntire(cleaned)?.let { it.groupValues[1].replace(".", "") to it.groupValues[2] }
            ?: dotDecimal.matchEntire(cleaned)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: return MoneyResult.Invalid("Valor inválido. Use o formato 1.234,56.")

        if (integerPart.length > 10) return MoneyResult.Invalid("Valor alto demais.")
        val cents = integerPart.toLong() * 100 + decimalPart.padEnd(2, '0').toLong()
        return when {
            cents == 0L -> MoneyResult.Invalid("O valor precisa ser maior que zero.")
            cents > MAX_CENTS -> MoneyResult.Invalid("O valor máximo é R$ 10.000.000,00.")
            else -> MoneyResult.Valid(cents)
        }
    }
}

/** Formatação de centavos em reais, sem depender do Locale do aparelho. */
object Money {
    private const val MINUS = "−"

    /** `R$ 1.234,56`; negativos ganham o sinal de menos tipográfico: `−R$ 23,00`. */
    fun format(cents: Long): String {
        val sign = if (cents < 0) MINUS else ""
        val absolute = abs(cents)
        return "${sign}R$ ${group(absolute / 100)},${(absolute % 100).toString().padStart(2, '0')}"
    }

    /** Sem centavos, arredondando para baixo: `R$ 186`. Usado nos números grandes da tela. */
    fun formatShort(cents: Long): String {
        val sign = if (cents < 0) MINUS else ""
        return "${sign}R$ ${group(abs(cents) / 100)}"
    }

    private fun group(reais: Long): String =
        reais.toString().reversed().chunked(3).joinToString(".").reversed()
}
