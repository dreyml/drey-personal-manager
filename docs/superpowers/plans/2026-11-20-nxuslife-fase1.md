# Plano de implementação — NxusLife 2.0, Fase 1

Especificação: [`docs/superpowers/specs/2026-11-20-nxuslife-fase1-design.md`](../specs/2026-11-20-nxuslife-fase1-design.md)
Branch: `redesign/fase-1` · Base: `main` (0.9.1)

## Como executar este plano

- Uma tarefa por vez, na ordem. Cada tarefa termina com testes verdes e **um commit** (mensagem sugerida no fim da tarefa).
- Tarefas de `domain/` são **TDD**: escrever os testes listados, ver falhar, implementar, ver passar.
- Comando de verificação (JDK do Android Studio):
  ```bash
  JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest assembleDebug
  ```
- Testes instrumentados (Room e UI) rodam com `connectedDebugAndroidTest` num emulador ou aparelho; quando não houver um disponível, registrar no commit que não foram executados.
- Pacote raiz continua `com.nxuslab.dreymanager`. Abaixo, `…/` = `app/src/main/java/com/nxuslab/dreymanager/` e `test/…/` = `app/src/test/java/com/nxuslab/dreymanager/`.
- Dinheiro sempre `Long` em centavos. Nenhum `Double` para valores.

---

## Tarefa 0 — Preparar o build

**Arquivos:** `app/build.gradle.kts`

- `versionCode = 14`, `versionName = "2.0.0"`.
- Adicionar:
  - `implementation("androidx.navigation:navigation-compose:2.9.0")`
  - `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")`
  - `androidTestImplementation("androidx.room:room-testing:2.8.5")`
  - `androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")`
- Release: `isMinifyEnabled = true`, `isShrinkResources = true` (as regras do Room/KSP já vêm nos artefatos).

**Pronto quando:** `assembleDebug` e `assembleRelease` compilam sem mudança de código.
**Commit:** `chore: prepare build for NxusLife 2.0`

## Tarefa 1 — `MoneyParser` e formatação

**Arquivos:** `…/domain/money/MoneyParser.kt`, `…/domain/money/Money.kt`, `test/…/domain/money/MoneyParserTest.kt`

- `MoneyParser.parse(text): MoneyResult` → `Valid(cents)` ou `Invalid(mensagem)`.
- `Money.format(cents): String` → `R$ 1.234,56` (pt-BR, sem `Locale` depreciado: `Locale.forLanguageTag("pt-BR")`).

**Testes:** `32`→3200; `32,9`→3290; `32,90`→3290; `1.234,56`→123456; `R$ 1.234,56`→123456; ` 7 `→700; inválidos com mensagem: vazio, `-5`, `1,234`, `abc`, `1e3`, `1.23,4`, `10000000,01`; formatação de 0, 5, 123456, 100000000.
**Commit:** `feat(domain): add Brazilian money parser and formatter`

## Tarefa 2 — Modelos de domínio e `BudgetCalculator`

**Arquivos:** `…/domain/model/*.kt` (data classes puras: `FixedBill`, `Income`, `Card`, `CardDebt`, `Envelope`, `Expense`, `Wish`, `BudgetSettings`, enums), `…/domain/budget/BudgetCalculator.kt`, `test/…/domain/budget/BudgetCalculatorTest.kt`

- `monthPlan(month, inputs): MonthPlan` aplica a ordem 1–7 da spec §6.2 e devolve cada parcela alocada.
- `week(date, inputs): WeekBudget` → `Ready(limit, spent, remaining, daysLeft, perDay, carriedOverExcess)` ou `Incomplete(motivo)`.
- `validateOverride(cents, inputs)` recusa valor acima do sugerido.

**Testes:** sem renda → `Incomplete`; ordem de alocação (contas antes de faturas antes de quitação…); semana que cruza meses é proporcional; override menor aplica, maior é recusado; excesso da semana anterior é descontado; gasto de envelope conta na semana; renda extra dividida por `extraSplitToDebtPercent`; compra no cartão reservada entra no item 2 do mês da fatura.
**Commit:** `feat(domain): add weekly budget calculator`

## Tarefa 3 — `SpendGuard`

**Arquivos:** `…/domain/budget/SpendGuard.kt`, `test/…/domain/budget/SpendGuardTest.kt`

- `evaluate(expense, week, envelopes, cardContext): GuardResult` → `Ok`, `Warning(msg)`, `Confirm(msg, excessCents, delayDays?)`; para `CARTAO` sempre inclui `invoiceMonth`, `reservedCents`, `cardPurchasesThisMonth`.
- Mês da fatura calculado por `closingDay` (compra no dia do fechamento ou depois vai para a fatura seguinte).

**Testes:** folga → Ok; 80% exato → Warning; estouro de semana → Confirm com excesso; estouro só de envelope → Confirm; cartão dentro do limite → Ok com reserva; cartão estourando → Confirm com `delayDays`; fechamento no dia 31 em mês de 30 dias.
**Commit:** `feat(domain): add firm spending guard`

## Tarefa 4 — `PayoffPlanner` e simulador

**Arquivos:** `…/domain/payoff/PayoffPlanner.kt`, `test/…/domain/payoff/PayoffPlannerTest.kt`

- `plan(debts, cards, extraMonthly, start): PayoffPlan` → linha do tempo mensal (saldo por cartão e total), data de quitação por cartão, estratégia (`AVALANCHE`/`BOLA_DE_NEVE`) com frase explicativa e economia estimada.
- `simulate(plan inputs, change): Simulation` com `change` = `ExtraPerMonth(cents)` ou `PayOffNow(debtId)`; devolve nova data e diferença em meses, sem efeitos colaterais.
- Juros mensais (pontos-base) sobre rotativo/parcelamento de fatura; parcelas fixas não têm juros adicionais.
- Limite de segurança: se a dívida não zera em 120 meses, retorna `NeverPaysOff` com mensagem.

**Testes:** um cartão sem juros; dois cartões todos com juros → avalanche; um sem juros → bola de neve; extra rola para o próximo cartão após zerar; simulação +R$ 150 antecipa data; `PayOffNow`; juros maiores que o pagamento → `NeverPaysOff`.
**Commit:** `feat(domain): add credit card payoff planner and simulator`

## Tarefa 5 — `CashForecaster`

**Arquivos:** `…/domain/forecast/CashForecaster.kt`, `test/…/domain/forecast/CashForecasterTest.kt`

- `forecast(start, days = 90, inputs, whatIf = emptyList()): Forecast` → saldo por dia e lista de `Shortfall(date, cents, reason)` (primeiro dia negativo por compromisso).
- Limite semanal distribuído igualmente pelos dias da semana.

**Testes:** sem falta; falta no dia da fatura com motivo correto; salário no dia 5 cobre conta no dia 10; "e se" com gasto cria falta; "e se" com entrada remove falta; mês com dia de pagamento 31 em fevereiro cai no último dia.
**Commit:** `feat(domain): add cash forecaster with what-if`

## Tarefa 6 — `WishPlanner`

**Arquivos:** `…/domain/wish/WishPlanner.kt`, `test/…/domain/wish/WishPlannerTest.kt`

- `plan(wishes, savings, monthPlans): List<WishProjection>` → valor mensal necessário, data realista, redução semanal (só ALTA), `ready` quando guardado ≥ preço.
- `compareWithCard(wish, installments, monthlyInterestBp, payoffPlan): CardComparison` → custo extra e meses de atraso na quitação.

**Testes:** ALTA com data cumpre e reduz semana; MÉDIA recebe só sobra e data se estende; sem sobra → data `null` com mensagem; desejo pronto; comparação com cartão.
**Commit:** `feat(domain): add wish planner`

## Tarefa 7 — Dados: Room v1, repositórios e limpeza do legado

**Arquivos:** `…/data/db/NxusDatabase.kt`, `…/data/db/entity/*.kt`, `…/data/db/dao/*.kt`, `…/data/db/Converters.kt`, `…/data/repo/*.kt`, `…/data/LegacyWipe.kt`, `app/schemas/…NxusDatabase/1.json` (gerado), `app/src/androidTest/…/data/*DaoTest.kt`

- Tabelas da spec §5 (`nxuslife.db`, versão 1, `exportSchema = true`), conversores `LocalDate`/`YearMonth` ↔ texto ISO.
- Repositórios mapeiam entidade ↔ modelo de domínio e expõem `Flow` + funções `suspend` que retornam `Result`.
- `LegacyWipe.runOnce(context)`: apaga `drey_manager.db` (+ `-wal`/`-shm`), `SharedPreferences` `drey_manager_v1`, aliases `nxuslife_vault_key` e `nxuslife_vault_key_v2`; grava `settings.legacyWiped = true`. Idempotente.
- Remover o código antigo de dados: `data/PersonalData.kt`, `data/PersonalDatabase.kt`, `app/schemas/com.nxuslab.dreymanager.data.PersonalDatabase/`.

**Testes (instrumentados):** insert/observe de `expense` por semana; `card_debt` por cartão; `wish_saving` soma; `LegacyWipe` roda duas vezes sem erro.
**Commit:** `feat(data): add NxusLife 2.0 Room schema and legacy wipe`

## Tarefa 8 — Tema e componentes (`core/`)

**Arquivos:** `…/core/theme/Theme.kt`, `…/core/theme/Color.kt`, `…/core/ui/*.kt`, remover `ui/AppTheme.kt`, atualizar `res/values/themes.xml`

- Paleta Bordô profundo da spec §7 como `darkColorScheme`; app sempre escuro.
- Componentes: `BigRing` (número grande + anel), `EnvelopeBar`, `MoneyField` (usa `MoneyParser`, mostra erro), `CompactRow`, `AlertCard`, `SectionHeader`, `DatePickerField` (sem digitação livre), `ResultSnackbar`.
- `@Preview` para cada componente.

**Pronto quando:** previews renderizam; contraste do texto sobre fundo e superfície ≥ 4.5:1 (anotar valores no KDoc de `Color.kt`).
**Commit:** `feat(core): add Bordô profundo theme and base components`

## Tarefa 9 — Casca do app: bloqueio, navegação e atualização

**Arquivos:** `…/MainActivity.kt` (reescrito), `…/app/AppLock.kt`, `…/app/NxusNavHost.kt`, `…/app/Routes.kt`; remover `MainViewModel.kt`, `ui/screens/HomeScreen.kt`

- `MainActivity`: `FLAG_SECURE`, `LegacyWipe.runOnce`, `AppLock`, `NxusNavHost`.
- `AppLock`: `BiometricPrompt` com `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` (API 30+) / `BIOMETRIC_WEAK or DEVICE_CREDENTIAL` (API < 30), `ContextCompat.getMainExecutor`, estado em `rememberSaveable`, re-bloqueio após 5 min em segundo plano.
- Navegação: rotas `onboarding`, `home`, `gastos`, `plano/{aba}`, `mais`, `mais/{secao}`; barra com 4 abas; FAB **+ Gasto** abre a sheet de lançamento (Tarefa 12). Deep links `nxuslife://` para as notificações.
- Se `onboardingDone == false`, abre `onboarding`.
- Checador de atualização (`update/`) mantido; aviso aparece em **Mais** e como faixa discreta na Início.

**Pronto quando:** app abre, bloqueia, navega entre 4 telas vazias; `assembleDebug` verde.
**Commit:** `feat(app): add app shell with lock and navigation`

## Tarefa 10 — Configuração inicial

**Arquivos:** `…/feature/onboarding/OnboardingScreen.kt`, `OnboardingViewModel.kt`, `steps/*.kt`

- 5 passos da spec §7, todos com **Pular**; progresso no topo.
- Passo de cartões aceita vários cartões, cada um com fatura atual, parcelas (valor + restantes), rotativo/parcelamento e juros opcionais.
- Tela final: limite semanal (`BudgetCalculator`), data de saída (`PayoffPlanner`), extra de quitação sugerido (30% da sobra, múltiplo de R$ 10) com ajuste.

**Testes:** ViewModel com repositórios falsos: pular tudo deixa estado `Incomplete`; concluir grava `onboardingDone`.
**Commit:** `feat(onboarding): add five-step initial setup`

## Tarefa 11 — Início

**Arquivos:** `…/feature/home/HomeScreen.kt`, `HomeViewModel.kt`

- `BigRing` com "livre esta semana", dias restantes e média/dia; envelopes; `AlertCard` de falta prevista (`CashForecaster`); resumo da saída do cartão; vence em breve (7 dias); faixa de excesso descontado.
- Estado `Incomplete` mostra o motivo e botão para completar.

**Testes:** ViewModel monta o estado a partir de repositórios falsos (pronto, incompleto, com falta).
**Commit:** `feat(home): add Foco total home screen`

## Tarefa 12 — Lançar gasto com alerta firme

**Arquivos:** `…/feature/gasto/AddExpenseSheet.kt`, `AddExpenseViewModel.kt`, `GuardDialogs.kt`, `app/src/androidTest/…/feature/gasto/AddExpenseFlowTest.kt`

- Teclado numérico próprio, chips de categoria (mais usadas primeiro), meio de pagamento (Pix padrão; Cartão abre escolha do cartão), conta de origem, prévia "depois: restam…".
- Ao salvar: `SpendGuard` → `Ok` salva; `Warning` salva e mostra snackbar âmbar; `Confirm` abre diálogo (Cancelar em destaque); cartão sempre mostra o diálogo de reserva.
- Renda extra também é lançada aqui (alternância Gasto/Entrada) com a divisão sugerida para quitação.

**Testes:** UI test: valor que estoura → diálogo → Cancelar não salva; → Gastar mesmo assim salva. Unit test do ViewModel para cartão gerar `reservedForInvoice`.
**Commit:** `feat(expense): add two-tap expense entry with firm alerts`

## Tarefa 13 — Gastos (semana)

**Arquivos:** `…/feature/gastos/WeekScreen.kt`, `WeekViewModel.kt`

- Lista agrupada por dia, etiqueta do meio (cartão destacado), envelopes no topo, navegação ← → entre semanas, editar/excluir gasto com confirmação.

**Testes:** ViewModel agrupa por dia e respeita `weekStartDay`.
**Commit:** `feat(expenses): add weekly expenses screen`

## Tarefa 14 — Plano › Cartões

**Arquivos:** `…/feature/plano/PlanoScreen.kt` (abas), `cartoes/CardsPlanScreen.kt`, `cartoes/CardsPlanViewModel.kt`, `core/ui/DebtChart.kt`

- Ordem de quitação com selo, frase da estratégia, gráfico mês a mês (Canvas próprio, sem biblioteca), data de "livre do cartão".
- Simulador: slider de extra mensal e "quitar agora" por dívida; mostra nova data ao lado; **Aplicar ao plano** grava `payoffExtraMonthlyCents`.
- Marcar cartão como cancelado quando zerado.

**Testes:** ViewModel: simular não grava; aplicar grava.
**Commit:** `feat(plan): add card payoff timeline and simulator`

## Tarefa 15 — Plano › Previsão

**Arquivos:** `…/feature/plano/previsao/ForecastScreen.kt`, `ForecastViewModel.kt`, `core/ui/BalanceChart.kt`

- Curva de saldo 90 dias com marcação das faltas; lista de faltas; "e se": adicionar gasto/entrada hipotética com data e ver a curva alternativa sobreposta; limpar simulação.

**Testes:** ViewModel: hipóteses não persistem.
**Commit:** `feat(plan): add cash forecast with what-if`

## Tarefa 16 — Plano › Desejos

**Arquivos:** `…/feature/plano/desejos/WishesScreen.kt`, `WishesViewModel.kt`, `WishDialog.kt`

- Lista com prioridade, progresso, valor/mês e data realista; criar/editar (nome, preço com `MoneyField`, data opcional, prioridade); "guardar agora" registra `wish_saving`; confirmação ao escolher ALTA mostrando a redução semanal; comparação com cartão expansível; marcar comprado/desistir.

**Testes:** ViewModel: ALTA exige confirmação; desejo pronto muda status.
**Commit:** `feat(plan): add wish list with saving suggestions`

## Tarefa 17 — Mais (cadastros e ajustes)

**Arquivos:** `…/feature/mais/*.kt`

- Telas de lista + edição para renda/extras, contas fixas, contas bancárias (atualizar saldo), categorias e envelopes, cartões e dívidas; ajustes (início da semana, horário do lembrete, reserva, divisão dos extras, override do limite com validação); atualização do app; aviso de notificações negadas.

**Testes:** ViewModel de ajustes recusa override acima do sugerido.
**Commit:** `feat(more): add settings and registries`

## Tarefa 18 — Notificações

**Arquivos:** `…/notifications/*.kt` (remover `ReminderWorker.kt` antigo)

- Canais: Lembretes, Vencimentos, Alertas. Workers: lembrete diário em `reminderTime`, vencimentos da véspera, resumo semanal, falta prevista (3 dias antes), desejo pronto. Todos com `PendingIntent` para a rota certa, checagem de `areNotificationsEnabled()` e `Result.retry()` em erro de banco.
- Permissão `POST_NOTIFICATIONS` pedida com `ActivityResultContracts.RequestPermission` ao fim da configuração inicial; recusa registrada para o aviso em **Mais**.
- Reagendar quando `reminderTime` mudar.

**Testes:** unit tests das funções puras que decidem o que notificar em uma data.
**Commit:** `feat(notifications): add reminders, due dates and shortfall alerts`

## Tarefa 19 — Verificação final e entrega

- `testDebugUnitTest`, `assembleDebug`, `assembleRelease`; `connectedDebugAndroidTest` se houver aparelho/emulador.
- Atualizar `README.md` (visão 2.0, arquitetura, como publicar) e criar `server/manifest-2.0.0.json` apontando para um APK **release assinado** (`nxuslife-2.0.0.apk`); a assinatura usa um keystore do dono, fora do repositório.
- Checklist manual no aparelho: instalação por cima da 0.9.x apaga dados antigos; configuração inicial; limite semanal coerente; estouro e cancelamento; compra no cartão; simulador; previsão com falta; desejo ALTA; lembrete às 21h; bloqueio e PIN.
- Abrir PR `redesign/fase-1` → `main` com o resumo e o checklist.

**Commit:** `docs: update README for NxusLife 2.0`

---

## Ordem e dependências

```
0 → 1 → 2 → 3 → 4 → 5 → 6      (domínio: só Kotlin puro, testável sem aparelho)
          ↘
           7 → 8 → 9 → 10 → 11 → 12 → 13 → 14 → 15 → 16 → 17 → 18 → 19
```

As tarefas 1–6 podem ser revisadas e aprovadas antes de qualquer tela existir.
