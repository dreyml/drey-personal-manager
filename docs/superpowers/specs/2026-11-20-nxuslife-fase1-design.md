# NxusLife 2.0 — Fase 1: núcleo financeiro

Data: 2026-11-20 · Status: aprovado em conversa, aguardando revisão do documento

## 1. Objetivo

Reescrever o NxusLife como um app de **finanças pessoais focado em sair do cartão de crédito** e gastar com limite consciente. A tela inicial responde uma pergunta: **quanto ainda posso gastar esta semana?**

Perfil de uso (respostas do dono do app):

- Só finanças pessoais.
- Renda fixa mensal + extras ocasionais.
- Hoje a maior parte dos gastos vai no cartão; há fatura alta, parcelas longas e rotativo/parcelamento de fatura em **dois ou mais cartões**.
- Objetivo: quitar e cancelar os cartões, migrar para Pix/débito com limites, criar consciência financeira.
- Começar do zero: nenhum dado do app atual (inclusive o cofre) precisa ser preservado.

## 2. Fases

| Fase | Escopo |
|---|---|
| **1 (este documento)** | Configuração inicial, limite semanal + envelopes, lançamento rápido com alerta firme, plano de saída do cartão com simulador, compra emergencial no cartão, previsão "vai faltar?", lista de desejos, lembretes, tela inicial Foco total |
| 2 | Tarefas e lembretes, metas/cofrinhos, valores a receber, cofre de senhas |
| 3 | Leitura opcional das notificações do banco para sugerir lançamentos |

## 3. Abordagem

Reescrita no **mesmo repositório**, com o **mesmo `applicationId`** (`com.nxuslab.dreymanager`) para manter o canal de atualização `https://updateappdrey.nxuslab.com/manifest.json`. O `versionCode` continua crescendo a partir do maior já publicado (13), com `versionName` 2.0.0.

Na primeira abertura da 2.0 o app **apaga** o banco antigo (`drey_manager.db`), as preferências antigas e as chaves antigas do Keystore (`nxuslife_vault_key`, `nxuslife_vault_key_v2`), e abre a configuração inicial. Uma flag em `settings` marca que a limpeza já ocorreu.

Mantido do app atual: checador de atualização (`update/`), bloqueio por biometria ao abrir (agora com PIN/padrão como alternativa e executor compatível com API 26), `FLAG_SECURE`.

## 4. Arquitetura

Pacote raiz: `com.nxuslab.dreymanager` (o namespace não muda, para não quebrar o `applicationId`).

```
core/        tema Bordô profundo, componentes, formatação de dinheiro e data
domain/      regras em Kotlin puro, sem Android, 100% testáveis
  MoneyParser, BudgetCalculator, SpendGuard, PayoffPlanner,
  CashForecaster, WishPlanner
data/        Room (nxuslife.db), DAOs, repositórios
feature/     uma pasta por área, cada uma com Screen + ViewModel
  onboarding/, home/, gasto/, gastos/, plano/ (cartoes, previsao, desejos), mais/
notifications/  lembrete noturno, vencimentos, resumo semanal, alerta de falta
update/      mantido
```

Regras:

- Dinheiro é sempre `Long` em centavos, do campo de texto até a tela. `Double` não é usado para valores.
- `domain/` não importa nada de Android nem de Room; recebe e devolve modelos simples.
- ViewModels expõem um `StateFlow` de estado da tela e funções `suspend` que devolvem `Result` para a UI mostrar sucesso ou erro.
- Nenhum arquivo de tela passa de ~400 linhas; componentes reutilizáveis ficam em `core/`.

## 5. Dados (Room, `nxuslife.db`, versão 1)

| Tabela | Campos principais |
|---|---|
| `settings` | `weekStartDay` (padrão segunda), `reminderTime` (padrão 21:00), `reserveCents`, `payoffExtraMonthlyCents`, `weeklyLimitOverrideCents?`, `extraSplitToDebtPercent` (padrão 50), `legacyWiped` |
| `income` | `id`, `kind` (FIXA / EXTRA), `amountCents`, `dayOfMonth?` (fixa), `date?` (extra), `accountId` |
| `account` | `id`, `name`, `balanceCents`, `balanceUpdatedAt` |
| `fixed_bill` | `id`, `title`, `amountCents`, `dayOfMonth`, `categoryId?`, `active` |
| `card` | `id`, `name`, `closingDay`, `dueDay`, `monthlyInterestBp?` (juros a.m. em pontos-base), `status` (ATIVO / QUITADO / CANCELADO) |
| `card_debt` | `id`, `cardId`, `kind` (FATURA_ABERTA / PARCELA / ROTATIVO / PARCELAMENTO_FATURA), `description`, `amountCents` (valor da parcela ou saldo), `remainingInstallments?`, `nextDueDate` |
| `category` | `id`, `name`, `emoji`, `color` |
| `envelope` | `categoryId` (PK), `monthlyLimitCents` |
| `expense` | `id`, `amountCents`, `categoryId`, `date`, `method` (PIX / DEBITO / DINHEIRO / CARTAO), `accountId?`, `cardId?`, `reservedForInvoice?` (mês da fatura `2026-12`), `note?` |
| `wish` | `id`, `name`, `priceCents`, `targetDate?`, `priority` (ALTA / MEDIA / BAIXA), `status` (JUNTANDO / PRONTO / COMPRADO / DESISTI) |
| `wish_saving` | `id`, `wishId`, `amountCents`, `date` |

Datas são `LocalDate` armazenadas como texto ISO (`2026-01-31`); meses como `YearMonth` ISO (`2026-12`). Schema exportado em `app/schemas/`.

## 6. Regras de negócio (`domain/`)

### 6.1 `MoneyParser`

Converte texto para centavos aceitando o formato brasileiro: `32`, `32,9`, `32,90`, `1.234,56`, `R$ 1.234,56`. Rejeita (retorna erro com mensagem) vazio, negativo, mais de 2 casas, letras, notação científica e valores acima de R$ 10.000.000,00.

### 6.2 Ordem do orçamento do mês

Para cada mês, o dinheiro disponível (renda fixa do mês + extras recebidos destinados ao mês) é alocado nesta ordem:

1. Contas fixas.
2. Parcelas e faturas do cartão que vencem no mês (incluindo valores reservados de compras emergenciais).
3. Valor extra de quitação (`settings.payoffExtraMonthlyCents`), acima das parcelas e faturas obrigatórias do item 2. Na configuração inicial o app sugere 30% do que sobra após os itens 1 e 2, arredondado para baixo a múltiplos de R$ 10; o usuário ajusta no simulador do Plano e confirma com **Aplicar ao plano**.
4. Desejos de prioridade **alta** (valor mensal necessário para cumprir a data).
5. Reserva (`reserveCents`, opcional).
6. Limite das semanas.
7. Desejos de prioridade média e baixa, com o que sobrar.

### 6.3 `BudgetCalculator` — limite semanal

```
sobra = renda do mês − itens 1 a 5
limite semanal sugerido = sobra restante ÷ semanas restantes no mês
limite efetivo = min(sugerido, weeklyLimitOverrideCents) − excesso da semana anterior
```

- O usuário só pode **reduzir** o limite (override maior que o sugerido é recusado com mensagem).
- Semana começa em `weekStartDay`; semanas que cruzam meses são proporcionais aos dias de cada mês.
- Renda extra: ao lançar, o app sugere enviar `extraSplitToDebtPercent`% para a quitação e o resto para a semana; o usuário pode mudar na hora.
- Se não há renda cadastrada, o resultado é `Incompleto("Complete sua renda para calcular")`, nunca R$ 0.

### 6.4 Envelopes

Limites mensais por categoria escolhida. Gastar numa categoria com envelope consome o envelope **e** o limite da semana.

### 6.5 `SpendGuard` — alerta firme

Avaliado antes de salvar cada gasto:

| Situação | Resultado |
|---|---|
| Cabe com folga | `Ok` — salva direto |
| Passa de 80% do envelope ou da semana | `Aviso` — mensagem amarela, salva |
| Estoura envelope ou semana | `Confirmar` — tela com o excesso ("+R$ 23 em Delivery; o excesso sai da próxima semana"); botão destacado é **Cancelar**, secundário **Gastar mesmo assim** |
| `method = CARTAO` | sempre inclui "R$ X serão reservados para a fatura de <mês>" e o número de compras no cartão no mês; se também estourar, inclui quantos dias atrasa a quitação |

Compra no cartão conta no limite da semana como se fosse Pix e gera um valor reservado para a fatura correspondente ao `closingDay` do cartão; esse valor entra no item 2 do orçamento daquele mês e **não** aumenta a dívida do plano.

### 6.6 `PayoffPlanner` — saída do cartão

- Entrada: dívidas de todos os cartões (`card_debt`), parcelas obrigatórias do mês e `payoffExtraMonthlyCents`. O extra vai inteiro para o primeiro cartão da ordem; quando ele zera, o extra mais o que ele consumia em parcelas passa para o próximo.
- Estratégia: se **todos** os cartões com saldo têm juros informados, ordena por maior juro (avalanche); caso contrário, por menor saldo (bola de neve). Sempre devolve uma frase explicando a escolha e, quando há juros, a economia estimada em relação à outra estratégia.
- Saída: linha do tempo mês a mês (saldo por cartão e total) e a data em que cada cartão zera ("pode cancelar o cartão X em ago/2027").
- Simulador: aplica "+R$ X por mês" ou "quitar a dívida Y agora" sobre uma cópia do plano e devolve a nova data e a diferença em meses; nada é gravado até o usuário tocar em **Aplicar ao plano**.
- Juros do rotativo/parcelamento de fatura são aplicados mensalmente sobre o saldo quando informados.

### 6.7 `CashForecaster` — "vai faltar?"

- Projeta o saldo em conta **dia a dia nos próximos 90 dias**: saldo atual + rendas previstas − contas fixas − faturas/parcelas − limite semanal (distribuído por dia) − valores mensais de desejos.
- Gera `Falta(data, valor, motivo)` para o primeiro dia negativo de cada conta/compromisso, ex.: "Em 12/jan faltam R$ 140 para a fatura do Itaú".
- "E se": recebe uma lista de lançamentos hipotéticos (gasto ou entrada com data) e devolve a curva alternativa; nada é gravado.

### 6.8 `WishPlanner` — lista de desejos

- Para cada desejo calcula o valor mensal necessário até `targetDate` e a data realista dado o que sobra no orçamento.
- **Alta**: pode reduzir o limite semanal; antes de confirmar, mostra quanto reduz por semana.
- **Média/baixa**: recebem só a sobra (item 7); a data realista se ajusta.
- Comparação com cartão: "parcelado em N× sai R$ X a mais e atrasa a saída do cartão em M meses" — apenas informativa, o app recomenda juntar.
- Quando a soma de `wish_saving` atinge o preço: status `PRONTO` e notificação "Pode comprar à vista".

## 7. Telas e navegação

Estilo **Foco total**, paleta **Bordô profundo**:

| Papel | Cor |
|---|---|
| Fundo | `#140A0D` |
| Superfície | `#22121A` |
| Destaque (anel, botões) | `#B0304A` |
| Destaque claro (número grande) | `#E07A8E` |
| Texto | `#F3E8EA` |
| Alerta | `#F2A93B` |

Contraste de texto mínimo WCAG AA. Referência visual: mockups em `.superpowers/brainstorm/` (`home-estilo.html` opção C, `telas-fase1.html`, `cores-vinho.html` opção A).

Barra inferior com quatro abas e o botão **+ Gasto** sempre visível:

- **Início**: número grande "livre esta semana" com anel de progresso, dias restantes e média por dia; envelopes; alerta "vai faltar?" quando houver; saída do cartão resumida; vence em breve.
- **Gastos**: semana atual agrupada por dia, com meio de pagamento (cartão em destaque vermelho/âmbar), navegação entre semanas, aviso de excesso descontado.
- **Plano**: abas internas **Cartões** (linha do tempo + simulador), **Previsão** (curva de saldo + "e se"), **Desejos**.
- **Mais**: renda e extras, contas fixas, contas bancárias, categorias e envelopes, cartões, ajustes (início da semana, horário do lembrete, reserva, divisão dos extras), atualização. Áreas da Fase 2 entram aqui depois.

**Lançar gasto (2 toques)**: bottom sheet com teclado numérico, valor, chips de categoria, meio de pagamento (Pix padrão), prévia "depois: restam R$ X na semana" e botão Salvar; passa pelo `SpendGuard`.

**Configuração inicial (5 passos, todos puláveis)**: renda fixa e dia; saldo da conta; contas fixas; cartões (fatura atual, parcelas restantes, juros opcionais); envelopes sugeridos (delivery, lazer). Termina mostrando o limite semanal e a data de saída do cartão.

## 8. Notificações

| Quando | Mensagem |
|---|---|
| Diariamente em `reminderTime` | "Lançou tudo de hoje? Restam R$ X na semana." |
| Véspera de cada vencimento (conta, parcela, fatura) | "Amanhã vence <item>: R$ X" |
| Início da semana, 8h | resumo da semana anterior e novo limite |
| 3 dias antes de uma `Falta` prevista | "Em <data> faltam R$ X para <motivo>" |
| Desejo atinge o preço | "Pronto! Pode comprar <desejo> à vista" |

Todas tocam e abrem o app na tela certa. Antes de agendar, o app checa `areNotificationsEnabled()`; se negado, mostra um aviso fixo em **Mais** com botão para as configurações do sistema.

## 9. Tratamento de erros

- Campos de dinheiro usam `MoneyParser`; o botão Salvar fica desabilitado com erro visível no campo enquanto o valor é inválido.
- Toda gravação devolve `Result`; a UI mostra confirmação curta ou a mensagem de erro. Nenhum `catch` vazio, nenhum `runCatching` que engula `CancellationException`.
- Dados insuficientes produzem estados explícitos (`Incompleto`) em vez de zero.
- Datas são escolhidas por seletor (sem digitação livre), eliminando datas inválidas.

## 10. Testes

- **Unitários (`domain/`)**: `MoneyParser` (formatos válidos/inválidos), `BudgetCalculator` (ordem do orçamento, semanas que cruzam meses, override só para baixo, excesso levado à semana seguinte, renda ausente), `SpendGuard` (todas as faixas e cartão), `PayoffPlanner` (avalanche vs bola de neve, juros, simulador), `CashForecaster` (detecção de falta, "e se"), `WishPlanner` (prioridades, data realista, comparação com cartão).
- **Room**: testes instrumentados dos DAOs principais.
- **UI**: um teste Compose do fluxo lançar gasto → alerta → cancelar/confirmar.
- **Build**: `testDebugUnitTest` e `assembleDebug` verdes; teste manual no aparelho do dono antes de publicar.

## 11. Fora de escopo da Fase 1

Tarefas, metas/cofrinhos genéricos, valores a receber, cofre de senhas (Fase 2); leitura de notificações do banco (Fase 3); backup/exportação (será reespecificado sobre o novo schema, com senha, numa fase posterior); sincronização em nuvem; múltiplas moedas.
