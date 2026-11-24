# NxusLife 3.2 — Anotações: notas, listas e lembretes

Data: 2026-11-24 · Estado: aprovado (mockup no canvas, página "Anotações")

## Decisões do usuário
- Tipos: notas livres, listas com caixinhas e lembretes com hora (diário estoico fica para a 3.3).
- Organização: três abas — Notas · Listas · Lembretes — e busca em tudo.
- Lembretes: uma vez, todo dia, dias da semana escolhidos ou todo mês; aviso com Feito e Adiar 1h.

## Telas
- **Hub:** card "Anotações" ativo (pêssego). Linha: "2 lembretes hoje · próximo às 15:00";
  sem lembretes hoje: "N notas · N listas"; vazio: "Notas, listas e lembretes".
- **Anotações (rota `notas`):** topo com voltar; campo "Buscar em tudo" (com texto, mostra
  resultados das três abas juntos); abas; botão "+ Nota / Lista / Lembrete" conforme a aba.
  - Notas: "Fixadas" e "Recentes" em duas colunas; cartão com cor, título e prévia do texto.
  - Listas: cartão com nome, "3 de 8", barra de progresso e prévia dos itens; "pronta" quando
    todos marcados.
  - Lembretes: seções Hoje (inclui atrasados, em pêssego, com "Feito"), Próximos e "Feitos (N)"
    recolhível.
- **Editor de nota (`notas/nota/{id}`):** título e texto, cores (branco, pêssego, céu, sálvia,
  lavanda), fixar, apagar (com confirmação). Salva sozinho; nota vazia não é criada.
- **Editor de lista (`notas/lista/{id}`):** nome, itens com caixinha (marcados descem riscados),
  campo "Adicionar item", remover item, "Limpar marcados", apagar lista.
- **Editor de lembrete (`notas/lembrete/{id}`):** texto, dia (ou "começa"), hora, repetição com
  dias da semana, resumo em lilás ("Avisa às 15:00 toda seg, qua, sex"), Salvar, apagar.
  Sem permissão de alarme exato: linha "Para chegar no minuto certo, permita Alarmes e
  lembretes" com atalho para a tela do Android.

## Regras dos lembretes (domínio puro, testado)
`domain/notes/ReminderRule.kt`: `next(rule, after: LocalDateTime): LocalDateTime?`
- ONCE: data+hora de início; null depois de feito.
- DAILY: próximo dia (≥ início) na hora marcada, depois de `after`.
- WEEKLY: próximo dia marcado na máscara (seg=bit0 … dom=bit6).
- MONTHLY: dia do mês do início; em mês mais curto, o último dia.
- Disparo: ONCE fica pendente (atrasado) até "Feito"; repetidos avançam `nextAt` sozinhos.
- Feito: ONCE → `doneAt`, sem próximo. Repetido (na lista) → pula para a ocorrência seguinte.
- Adiar 1h: `nextAt = agora + 1h`.

## Dados (Room v4 → v5)
- `note` (id, title, body, color, pinned, updatedAt)
- `checklist` (id, title, updatedAt) e `checklist_item` (id, listId → checklist ON DELETE
  CASCADE, text, done, position; índice em listId)
- `reminder` (id, text, startDate, minuteOfDay, repeat, weekDays, nextAt?, doneAt?, createdAt)
- `MIGRATION_4_5` só cria as tabelas. Teste de migração v4→v5.

## Avisos
- `AlarmManager.setExactAndAllowWhileIdle` quando `canScheduleExactAlarms()`, senão
  `setAndAllowWhileIdle`. Permissão `SCHEDULE_EXACT_ALARM`.
- `ReminderAlarmReceiver` mostra a notificação (canal "Lembretes" já existente) com ações
  Feito / Adiar 1h (`ReminderActionReceiver`); toque abre `notas`.
- `RECEIVE_BOOT_COMPLETED` e abertura do app reagendam todos os lembretes com `nextAt`.

## Arquitetura
`data/repo/NotesRepository.kt`, `feature/notas/` (NotasScreen + VM, NoteEditor, ListEditor,
ReminderEditor), `notifications/ReminderAlarms.kt`. Rotas sem barra inferior, como o hub.

## Publicação
versionCode 20, versionName 3.2.0, `server/manifest-3.2.0.json`.
