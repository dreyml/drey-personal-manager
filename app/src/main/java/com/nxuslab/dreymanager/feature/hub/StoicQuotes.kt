package com.nxuslab.dreymanager.feature.hub

import java.time.LocalDate

data class Quote(val text: String, val author: String)

/**
 * Pensamentos estoicos do dia: ideias de Sêneca, Marco Aurélio e Epicteto em redação própria e
 * curta. A mesma frase vale o dia inteiro.
 */
object StoicQuotes {
    private const val SENECA = "Sêneca"
    private const val MARCO = "Marco Aurélio"
    private const val EPICTETO = "Epicteto"

    val all = listOf(
        Quote("Não é que tenhamos pouco tempo, é que desperdiçamos muito.", SENECA),
        Quote("Sofremos mais na imaginação do que na realidade.", SENECA),
        Quote("Rico não é quem tem muito, é quem deseja pouco.", SENECA),
        Quote("Enquanto adiamos, a vida passa.", SENECA),
        Quote("Nenhum vento ajuda quem não sabe para onde vai.", SENECA),
        Quote("Comece a viver agora; conte cada dia como uma vida inteira.", SENECA),
        Quote("A dificuldade fortalece a mente, como o esforço fortalece o corpo.", SENECA),
        Quote("Quem está em toda parte não está em lugar nenhum.", SENECA),
        Quote("A sorte é quando o preparo encontra a oportunidade.", SENECA),
        Quote("Aprenda a se contentar com pouco, e o pouco será muito.", SENECA),
        Quote("Pobre é quem precisa de muito, não quem tem pouco.", SENECA),
        Quote("Às vezes, até viver é um ato de coragem.", SENECA),
        Quote("Você tem poder sobre a sua mente, não sobre os acontecimentos.", MARCO),
        Quote("A felicidade da sua vida depende da qualidade dos seus pensamentos.", MARCO),
        Quote("O que atrapalha o caminho vira o caminho.", MARCO),
        Quote("Não gaste tempo discutindo o que é um bom homem. Seja um.", MARCO),
        Quote("Faça cada coisa como se fosse a última da sua vida, com calma e atenção.", MARCO),
        Quote("Muito pouco é preciso para uma vida feliz.", MARCO),
        Quote("A melhor vingança é não ser como quem te feriu.", MARCO),
        Quote("Ao acordar, lembre-se do privilégio que é estar vivo.", MARCO),
        Quote("Se não é certo, não faça. Se não é verdade, não diga.", MARCO),
        Quote("Olhe para dentro: ali está a fonte do bem.", MARCO),
        Quote("Aceite o que vier e faça o melhor com isso.", MARCO),
        Quote("O tempo é um rio de acontecimentos; cuide do trecho que é seu.", MARCO),
        Quote("Não são as coisas que nos perturbam, mas o que pensamos delas.", EPICTETO),
        Quote("Algumas coisas dependem de nós, outras não. Cuide das primeiras.", EPICTETO),
        Quote("Primeiro diga a si mesmo quem quer ser; depois faça o que precisa.", EPICTETO),
        Quote("Ninguém é livre se não é senhor de si mesmo.", EPICTETO),
        Quote("Riqueza não é ter muitas posses, é ter poucos desejos.", EPICTETO),
        Quote("Se quer melhorar, aceite parecer ingênuo no começo.", EPICTETO),
        Quote("Não explique sua filosofia. Viva-a.", EPICTETO),
        Quote("O que você evita sofrer, não imponha aos outros.", EPICTETO),
        Quote("Nada grande nasce de repente; deixe o fruto amadurecer.", EPICTETO),
        Quote("Temos dois ouvidos e uma boca para ouvir o dobro do que falamos.", EPICTETO),
        Quote("Cuide do hoje: amanhã você só terá o que fizer agora.", SENECA),
        Quote("Disciplina é escolher entre o que você quer agora e o que mais quer.", EPICTETO),
        Quote("Um pequeno passo bem dado vale mais que um plano adiado.", SENECA),
        Quote("A raiva custa mais do que a ofensa que a causou.", SENECA),
        Quote("Seja tolerante com os outros e exigente consigo.", MARCO),
        Quote("Viva conforme a sua natureza: simples, justo e atento.", MARCO),
    )

    fun of(date: LocalDate): Quote = all[(date.dayOfYear - 1) % all.size]
}
