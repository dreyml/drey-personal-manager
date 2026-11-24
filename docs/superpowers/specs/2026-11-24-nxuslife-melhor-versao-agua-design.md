# NxusLife 3.1 — "Seja sua melhor versão": Água

Data: 2026-11-24 · Estado: aprovado (mockup no canvas, página "Melhor versão · Água")

## Objetivo

Primeira parte da área "Seja sua melhor versão": beber a meta de água do dia dentro de uma
janela de horário, com uma garrafa na tela, ritmo claro, histórico curto e avisos só quando
atrasar. Tom estoico: foco no que depende de você, sem culpa. Alimentação e outros hábitos
ficam para versões seguintes.

## Decisões do usuário

- Escopo: só Água nesta versão.
- Registro: garrafa na tela que vai esvaziando (arrastar o dedo até o nível que sobrou).
- Meta: o usuário digita em ml (sem cálculo por peso).
- Lembretes: só quando estiver atrasado em relação ao ritmo, dentro da janela.
- Histórico: últimos 7 dias + sequência de dias cumpridos.
- Estoicismo: frase do dia no topo e tom estoico nos textos e avisos.

## Telas

### Hub
O card "Seja sua melhor versão" fica ativo (céu #D4E6F5 / #2C5A80, ícone de gota), mostra
"Água · 1,5 de 3 L" e uma barra de progresso. Sem configuração: "Monte seu plano de água".
Toque abre a rota `melhor`.

### Primeira vez (sem configuração salva)
Campos: meta do dia (ml, 500–8000), tamanho da garrafa (chips 500 / 750 / 1000 ml ou "Outro",
100–3000), início e fim da janela (fim > início), interruptor "Avisar se eu atrasar"
(padrão ligado). Prévia: "4 garrafas de 750 ml, uma a cada 2h15" e os horários-limite de cada
garrafa. Botão "Começar". O mesmo formulário abre pelo ícone de ajuste na tela da água.

### Tela do dia
1. Topo: voltar ao hub, título "Melhor versão", ícone de ajuste.
2. Frase estoica do dia (lista própria sobre disciplina e corpo, escolhida pelo dia do ano).
3. Card principal: "ÁGUA HOJE · 1,5 L de 3 L"; garrafa desenhada em Compose com a água no
   nível do que ainda resta na garrafa atual; ao lado "Nesta garrafa: 450 ml ainda dentro" e
   "Garrafas: 1 de 4". Arrastar verticalmente na garrafa ajusta o nível (passos de 50 ml); ao
   soltar, registra a diferença como um gole. Arrastar até o fundo conclui a garrafa: conta
   +1, a garrafa enche de novo e aparece snackbar "Garrafa 2 concluída · Desfazer".
   Toque simples na garrafa também posiciona o nível; botões −/+ 50 ml com rótulos atendem
   leitor de tela.
4. Card de ritmo: ideal até agora = meta × (agora − início) / (fim − início), limitado a
   [0, meta]. Atrasado (falta ≥ 150 ml): pêssego, "Faltam X ml para o ritmo" + "Não precisa
   compensar de uma vez: um copo agora já ajuda." Em dia: sálvia, "Você está no ritmo".
   Antes da janela: "Seu dia de água começa às 08:00"; depois: resumo do dia.
5. Cronograma: N = ceil(meta / garrafa) garrafas; a garrafa k tem limite
   início + k × (fim − início) / N. Estados: concluída (✓ sálvia), atual ("Agora · faltam X ml"),
   depois. Horário em pêssego se a atual já passou do limite.
6. Últimos 7 dias: barras (sálvia bateu, pêssego não, céu = hoje) com linha tracejada da meta;
   "N dias seguidos" conta dias consecutivos até ontem que bateram a meta (+ hoje se já bateu).

## Dados (Room v3 → v4)

- `water_settings` (linha única, id = 1): `goalMl`, `bottleMl`, `startMinute`, `endMinute`
  (minutos desde 00:00), `remindersOn`, `updatedAt`.
- `water_log`: `id` autoincremento, `at` (epoch ms), `ml` (Int > 0), índice em `at`.
- `MIGRATION_3_4` cria as duas tabelas; nada das finanças muda. Teste de migração v3→v4.
- O nível da garrafa atual não é salvo: deriva do total de hoje (dentro = garrafa −
  total mod garrafa; concluídas = total div garrafa). Desfazer apaga o último registro.
  Mudar meta/garrafa vale para o dia inteiro (o histórico guarda só ml).

## Lógica (domínio, pura e testada)

`domain/water/WaterPlan.kt`:
- `schedule(settings) -> List<LocalTime>` (limites das garrafas).
- `idealBy(settings, now) -> Int` (ml).
- `today(settings, logs, now) -> WaterDay` (totalMl, bottlesDone, leftInBottleMl, behindMl,
  fase BEFORE/DURING/AFTER, itens do cronograma).
- `week(settings, totalsByDay, today) -> List<DayBar>` e `streak(...)`.

## Lembretes

`WaterReminderWorker` periódico de 30 min (WorkManager), agendado ao salvar o plano com
avisos ligados e cancelado ao desligar. Notifica só se: dentro da janela, atraso ≥ 250 ml e
último aviso há ≥ 60 min (guardado em SharedPreferences). Canal novo "Água" em
`notifications/Reminders.kt`. Textos estoicos rotativos, ex.: "O que está ao seu alcance
agora: um copo d'água. Faltam 400 ml para o ritmo de hoje." Toque abre a tela da água.

## Arquitetura

- `feature/melhor/MelhorScreen.kt` (`MelhorViewModel`, tela do dia), `WaterSetup.kt`
  (formulário), `Bottle.kt` (garrafa com arrasto), `StoicBody.kt` (frases).
- `data/repo/WaterRepository.kt` (observa settings e logs, adiciona/desfaz, salva plano).
- `AppShell`: rota `melhor` sem barra inferior (como o hub); hub recebe `onOpenMelhor`.
- Cálculos com `flowOn(Dispatchers.Default)`; o relógio da tela atualiza a cada minuto.

## Erros e bordas

- Meta ou garrafa fora do intervalo: "Começar" desabilitado com texto de ajuda.
- Janela que atravessa a meia-noite: não suportada nesta versão (fim precisa ser > início).
- Bebeu acima da meta: o total segue somando, a barra fica cheia, ritmo "Meta do dia cumprida".
- Notificações negadas no Android: a tela mostra "Avisos desligados no Android" com atalho.

## Testes

- Unitários: cronograma, ideal, nível da garrafa, desfazer, sequência, fases da janela.
- Migração v3→v4 (androidTest).
- Emulador: configurar, arrastar, concluir garrafa, desfazer, voltar ao hub e ver o resumo.

## Publicação

versionCode 19, versionName 3.1.0, manifesto `server/manifest-3.1.0.json`, mesmo fluxo de
assinatura e envio das versões anteriores.
