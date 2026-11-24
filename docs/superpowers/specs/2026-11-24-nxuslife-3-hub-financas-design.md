# NxusLife 3.0 — Entrada (hub) e finanças mais claras

Data: 2026-11-24 · Versão alvo: 3.0.0 (versionCode 18) · Mockup aprovado: canvas "NxusLife 3.0", página "3.0 · Hub e finanças".

Primeira de três partes. As outras duas (Anotações; Seja sua melhor versão) terão specs próprias; aqui elas só aparecem no hub como "em breve".

## Objetivo

O app abre num hub pessoal e as finanças ficam claras e guiadas: o usuário sempre sabe quanto pode gastar hoje, qual é o próximo passo e o que pagar no mês, sem números negativos nem jargão.

## 1. Entrada (hub)

- Rota nova `hub` vira o destino inicial depois do onboarding (o bloqueio por biometria continua antes de tudo).
- Conteúdo: logo, data, "Bem-vindo, {nome}!", "Por onde quer começar hoje?", cartão **Pensamento do dia** e três cartões:
  - **Controle financeiro** → abre as finanças; subtítulo "Hoje dá para gastar R$ X" (ou o próximo passo, se for urgente).
  - **Anotações** e **Seja sua melhor versão** → desabilitados, "em breve".
- **Nome**: nova coluna `userName` em `settings` (texto, padrão vazio). Vazio → o hub pergunta "Como quer ser chamado?" uma vez. Editável em Mais › Ajustes.
- **Pensamento do dia**: lista embutida de ~40 frases curtas de Sêneca, Marco Aurélio e Epicteto, em português, com redação própria; escolhida pelo dia do ano (mesma frase o dia todo).
- Nas finanças, a barra de baixo (Início, Gastos, Plano, Mais) continua; o "voltar" do Início das finanças leva ao hub. O hub não tem barra de baixo.

## 2. Finanças · Início

De cima para baixo:

1. **Seu próximo passo** (cartão escuro): uma única ação, escolhida por `NextStepAdvisor` (Kotlin puro, testado), nesta prioridade:
   1. Vai faltar dinheiro em até 7 dias → "Segure os gastos: em {data} faltam R$ X".
   2. Fatura, parcela ou conta vencendo em até 5 dias e não marcada como paga → "Pague {nome} até {data} · R$ X".
   3. Conta variável vencendo em até 3 dias sem valor real → "Lance o valor da {conta}".
   4. Saldos desatualizados (salário caiu e saldo não mudou) ou sincronização com erro → "Atualize o saldo de {conta}".
   5. Hoje já passou do valor do dia → "Hoje passou R$ X. Amanhã fica R$ Y."
   6. Caso padrão → "Hoje dá para gastar R$ X" com uma dica curta ligada ao plano.
   Cada passo tem um botão que leva à tela certa (Plano, saldo da conta, lançar valor).
2. **Hoje dá para gastar R$ X** (sem anel), calculado por `DailyAllowance` (Kotlin puro, testado):
   - valor do dia = livre da semana no início do dia ÷ dias restantes na semana (incluindo hoje). O livre da semana já respeita o dinheiro real até o salário (regra atual de "livre de verdade").
   - O que não for gasto hoje aumenta os dias seguintes automaticamente; o que passar diminui. Nunca mostra número negativo: acima do valor, mostra "Hoje passou R$ X · amanhã fica R$ Y".
   - Linha "Já gastou R$ X hoje", barra fina e os 7 dias da semana em bolinhas: verde (ficou dentro), pêssego (passou), contorno lilás (hoje), cinza (ainda não chegou). O status de dias passados usa o valor do dia calculado naquele dia.
3. **Contas e cartões** — carteira animada:
   - `HorizontalPager` com cartões empilhados (os próximos aparecem atrás, menores e mais transparentes); deslizar troca; tocar expande os detalhes com animação.
   - Itens, nesta ordem: contas bancárias, cartões de crédito ativos, carteira (dinheiro). Cartão de crédito mostra a fatura aberta do ciclo atual e o limite livre; conta mostra o saldo atual; carteira mostra o dinheiro.
   - Visual de cartão real (proporção 1,586, chip, nome do banco, CRÉDITO/DÉBITO/DINHEIRO, nome do usuário em maiúsculas), com degradê na cor do banco: Nubank roxo, Sicoob verde-azulado, Mercado Pago azul, Inter laranja, Itaú laranja-escuro, Bradesco/Santander vermelho suave, Caixa/BB azul; outros, lavanda. Carteira em pêssego com texto escuro. Texto branco só onde o contraste passa de 4,5:1.
   - Detalhes (conta): saldo, atualizado quando, origem (banco/manual), botão "Corrigir saldo". Detalhes (cartão): fatura aberta, vencimento, limite, livre, "zera em" (do plano).
   - **Patrimônio total** abaixo, contando até o valor ao abrir (`animateIntAsState`), e "Sincronizado há X · atualizar".
4. **Vence em breve** (como hoje).

## 3. Plano · tela única (sem abas)

1. **Este mês, pague**: faturas, parcelas, contas fixas e variáveis do mês e o extra de quitação, com o total que falta. Botão **Paguei** em cada item:
   - nova tabela `paid_item(key TEXT, month TEXT, paidAt INTEGER, PRIMARY KEY(key, month))`; `key` = tipo + id (ex.: `bill:<id>`, `debt:<id>`, `variable:<id>`, `payoff`).
   - Fatura aberta marcada como paga também sai das dívidas (é removida), igual à sincronização quando detecta o pagamento; pede confirmação antes. Desfazer apenas tira a marcação (a fatura removida volta pelo próximo sync ou à mão).
   - Itens pagos aparecem riscados no fim da lista; o "Próximo passo" ignora itens pagos.
2. **Vai faltar dinheiro?**: frase-resumo ("Aperta de 1 a 4/12…" ou "Tranquilo até o salário") e calendário de hoje até o próximo salário a partir do `CashForecaster` atual: pêssego = sobra menos que a reserva, rosado = falta, verde = dia do salário. Uma dica de ação.
3. **Quando você sai das dívidas**: data grande, frase do plano atual (meta de cancelar primeiro) e botão "E se eu pagar R$ 50 a mais?" que alterna a simulação (`PayoffPlanner.simulate`); um seletor troca o valor (R$ 25/50/100/200).
4. **Seus desejos**: lista compacta com barra de progresso e a previsão de compra do `WishPlanner`; botão para adicionar.

As abas antigas (Cartões, Previsão, Desejos) deixam de existir; a rota `plano?aba=` passa a rolar até a seção correspondente (deep links continuam funcionando).

## 4. Acabamento

- Fonte **DM Sans** por fontes para download do Google (`ui-text-google-fonts`), sem embutir arquivos; sem internet ou sem Google Play, cai na fonte do sistema.
- Animações: carteira (pager com mola), expansão de detalhes, número do patrimônio, transições de navegação com fade/slide curtos. Respeita "remover animações" do Android.
- Textos revisados para linguagem simples; nenhum valor negativo exibido na Início.

## Dados e migração

- Banco v2 → v3: `settings.userName TEXT NOT NULL DEFAULT ''` e tabela `paid_item`. Migração testada (`MigrationTest`).
- Nada muda no servidor nem na sincronização com bancos.

## Componentes novos (puros e testados)

- `domain/daily/DailyAllowance` — valor do dia, gasto de hoje, valor de amanhã, status dos 7 dias.
- `domain/advice/NextStepAdvisor` — escolhe o próximo passo pelas regras acima.
- `domain/plan/MonthChecklist` — itens do mês com estado pago/não pago.
- `feature/hub/StoicQuotes` — frase do dia por dia do ano.
- `feature/financas/WalletItems` — monta os itens da carteira e as cores por banco.

## Testes

Unitários para os cinco componentes acima, migração 2→3 instrumentada, testes atuais continuam passando; verificação visual no emulador (hub, Início, carteira deslizando, Plano com "Paguei") antes de publicar.

## Fora do escopo

Anotações e "Seja sua melhor versão" (só o cartão "em breve" no hub); refazer as telas Gastos e Mais (recebem apenas o acabamento).
