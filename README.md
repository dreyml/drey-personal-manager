# NxusLife

Aplicativo Android de **finanças pessoais para sair do cartão de crédito** e gastar com limite consciente. Kotlin + Jetpack Compose, tudo local no aparelho.

A tela inicial responde uma pergunta: **quanto ainda posso gastar esta semana?**

## O que o app faz (2.0 — Fase 1)

- **Limite semanal automático**: renda − contas fixas − faturas/parcelas − extra de quitação − desejos de prioridade alta − reserva, distribuído pelos dias do mês. Dá para baixar o limite, nunca subir. Excesso de uma semana é descontado da seguinte.
- **Envelopes** mensais nas categorias que mais pesam (delivery, lazer…).
- **Lançamento em 2 toques** (valor + categoria) com **alerta firme**: aviso aos 80% e confirmação ao estourar, com "Cancelar" em destaque.
- **Compra emergencial no cartão**: conta na semana como Pix e o valor fica reservado para a fatura certa.
- **Plano de saída do cartão**: juros maiores primeiro quando informados, senão menor saldo; linha do tempo, data em que cada cartão zera e **simulador** ("+R$ X por mês", "quitar esta dívida agora").
- **Previsão "vai faltar?"** de 90 dias com simulação "e se" (nunca gravada).
- **Lista de desejos**: quanto guardar por mês, data realista, comparação com parcelar no cartão.
- **Notificações**: "lançou tudo de hoje?" no horário escolhido, vencimentos de amanhã, falta prevista, desejo pronto e resumo semanal.
- Configuração inicial em 5 passos (todos puláveis), bloqueio por biometria ou PIN do aparelho, tema escuro "Bordô profundo".

Próximas fases: tarefas e lembretes, metas/cofrinhos, valores a receber e cofre de senhas (Fase 2); leitura de notificações do banco (Fase 3). Especificação e plano em [`docs/superpowers/`](docs/superpowers/).

## Arquitetura

```text
feature/   telas + ViewModels (onboarding, home, gasto, gastos, plano, mais)
   │
   ▼
data/      Room (nxuslife.db) + FinanceRepository → snapshot de modelos de domínio
   │
   ▼
domain/    regras em Kotlin puro, testadas na JVM
           MoneyParser · BudgetCalculator · SpendGuard · PayoffPlanner
           CashForecaster · WishPlanner
core/      tema, componentes e gráficos
notifications/  ReminderPlanner (puro) + workers do WorkManager
update/    checagem do manifesto de atualização
```

- Dinheiro sempre em centavos (`Long`); nenhum `Double` para valores.
- Toda gravação devolve `Result` e a tela mostra sucesso ou erro.
- Na primeira abertura da 2.0, os dados da versão 0.x são apagados (recomeço do zero).

## Como compilar e testar

Requisitos: Android Studio (JDK do próprio Studio), Android SDK 36.

```bash
./gradlew testDebugUnitTest          # regras de negócio e lógica das telas (JVM)
./gradlew connectedDebugAndroidTest  # banco Room num emulador/aparelho
./gradlew assembleDebug              # APK de desenvolvimento
./gradlew assembleRelease            # APK de produção (R8)
```

No build de debug as capturas de tela ficam liberadas para testes; no release o app usa `FLAG_SECURE`.

## Assinatura do release

Crie um `keystore.properties` na raiz (já está no `.gitignore`) apontando para a sua chave:

```properties
storeFile=../chaves/nxuslife-release.jks
storePassword=…
keyAlias=nxuslife
keyPassword=…
```

Com o arquivo presente, `assembleRelease` gera `app/build/outputs/apk/release/app-release.apk` já assinado. **A chave nunca entra no Git** e deve ter cópia de segurança: sem ela não dá para publicar atualizações por cima da versão instalada.

## Publicar uma atualização

1. Aumente `versionCode` (sempre maior que o publicado) e `versionName` em `app/build.gradle.kts`.
2. Gere o release assinado e envie para `/var/www/updateappdrey/downloads/nxuslife-<versão>.apk` no servidor.
3. Publique o manifesto em `/var/www/updateappdrey/manifest.json` (exemplo: [`server/manifest-2.0.0.json`](server/manifest-2.0.0.json)):

```json
{
  "versionCode": 14,
  "versionName": "2.0.0",
  "message": "Texto do comunicado",
  "downloadUrl": "https://updateappdrey.nxuslab.com/downloads/nxuslife-2.0.0.apk"
}
```

O app só aceita manifesto e download por HTTPS e **no mesmo domínio do manifesto**, e nunca instala sozinho: o comunicado abre o link para o usuário baixar. Configuração do Nginx: [`server/nginx/README.md`](server/nginx/README.md).

## Segurança

- Sem backup automático do Android (`allowBackup=false`) e sem tráfego HTTP em texto puro.
- Bloqueio do app por biometria forte ou credencial do aparelho, com novo bloqueio após 5 minutos fora.
- O cofre de senhas volta na Fase 2 com chave do Keystore presa à autenticação do usuário.

## Licença

Ainda não definida.
