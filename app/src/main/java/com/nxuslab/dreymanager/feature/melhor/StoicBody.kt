package com.nxuslab.dreymanager.feature.melhor

import com.nxuslab.dreymanager.feature.hub.Quote
import java.time.LocalDate

/** Frases estoicas (paráfrases) sobre disciplina e corpo, e os textos dos avisos de água. */
object StoicBody {
    private const val SENECA = "Sêneca"
    private const val MARCO = "Marco Aurélio"
    private const val EPICTETO = "Epicteto"
    private const val MUSONIO = "Musônio Rufo"

    val quotes = listOf(
        Quote("Ninguém é livre se não é senhor de si mesmo.", EPICTETO),
        Quote("Cuide do corpo o bastante para que ele sirva bem à mente.", SENECA),
        Quote("Não explique a sua filosofia. Viva-a.", EPICTETO),
        Quote("O que fazemos todos os dias pesa mais do que o que fazemos de vez em quando.", SENECA),
        Quote("Você tem poder sobre a sua mente, não sobre os acontecimentos. Comece por aí.", MARCO),
        Quote("Pequenos passos, repetidos, viram caráter.", MUSONIO),
        Quote("Primeiro diga a si mesmo o que quer ser; depois faça o que precisa fazer.", EPICTETO),
        Quote("Enquanto adiamos, a vida passa.", SENECA),
        Quote("Faça cada ato como se fosse o último, sem pressa e sem preguiça.", MARCO),
        Quote("O hábito é mais forte que o discurso.", MUSONIO),
        Quote("Nenhuma grande coisa nasce de repente.", EPICTETO),
        Quote("A disciplina de hoje é a liberdade de amanhã.", SENECA),
    )

    fun of(date: LocalDate): Quote = quotes[(date.dayOfYear - 1) % quotes.size]

    private val reminders = listOf(
        "O que está ao seu alcance agora: um copo d'água.",
        "Não precisa compensar de uma vez. Um copo agora já basta.",
        "O corpo pede pouco. Dê a ele o que depende de você.",
        "Ontem passou. Agora é o que você controla.",
    )

    fun reminder(behindMl: Int, seed: Int): String =
        "${reminders[Math.floorMod(seed, reminders.size)]} Faltam $behindMl ml para o ritmo de hoje."
}
