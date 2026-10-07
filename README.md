# Drey Manager

Aplicativo Android de gerenciamento pessoal, construído em Kotlin e Jetpack Compose. A proposta é reunir finanças, lembretes, metas e outros recursos pessoais em uma experiência local, simples e segura.

## Estado atual

Este repositório contém a primeira versão local do aplicativo: painel inicial, lançamentos financeiros, tarefas e uma consulta de atualização feita na inicialização. O app busca um manifesto em `https://updateappdrey.nxuslab.com/manifest.json`, compara o `versionCode` publicado com a versão instalada e mostra um comunicado quando há uma versão mais recente.

O aplicativo **não instala atualizações silenciosamente**. A ação do comunicado apenas abre uma URL HTTPS para que o usuário escolha baixar a nova versão.

## Arquitetura inicial

```text
Jetpack Compose (UI)
        │
        ▼
MainViewModel (estado da tela)
        │
        ▼
UpdateChecker (regra de comparação)
        │
        ▼
UpdateRepository (manifesto HTTPS)
```

- **UI:** Kotlin + Jetpack Compose e Material 3.
- **Estado:** ViewModel + StateFlow.
- **Dados da V1:** lançamentos e tarefas persistidos localmente no aparelho.
- **Atualizações:** requisição HTTPS curta, executada fora da thread principal.
- **Falha segura:** indisponibilidade do servidor não bloqueia o app.
- **Dados futuros:** Room para dados locais; Android Keystore para material criptográfico.
- **Tarefas futuras:** WorkManager para lembretes e verificações periódicas autorizadas.

## Contrato do manifesto

Publique no servidor o arquivo `/manifest.json` com `Content-Type: application/json`:

```json
{
  "versionCode": 2,
  "versionName": "0.2.0",
  "message": "Uma nova versão do Drey Manager está disponível.",
  "downloadUrl": "https://updateappdrey.nxuslab.com/downloads/drey-manager-0.2.0.apk"
}
```

Regras:

- `versionCode` deve ser um inteiro e sempre aumentar a cada versão.
- `versionName` é o nome legível mostrado ao usuário.
- `message` é o texto do comunicado.
- `downloadUrl` precisa usar HTTPS.
- O DNS `updateappdrey.nxuslab.com` deve apontar para a VM OCI `137.131.136.166`, com certificado TLS válido.

Um exemplo pronto está em [`server/manifest.example.json`](server/manifest.example.json).
Para publicar esse endpoint sem interferir nos demais sites da VM, consulte a
[configuração Nginx isolada](server/nginx/README.md).

## Como executar

1. Instale o Android Studio com Android SDK 36 e JDK 17.
2. Abra a raiz deste repositório no Android Studio.
3. Aguarde a sincronização do Gradle.
4. Execute a configuração `app` em um emulador ou aparelho com Android 8.0 ou superior.

## Roadmap

- [x] Estrutura Android em Kotlin + Jetpack Compose.
- [x] Verificação de atualização na inicialização.
- [x] Comunicado com link de download, sem instalação silenciosa.
- [x] Navegação inicial entre painel, finanças e tarefas.
- [x] Finanças: receitas, despesas, saldo e resumo mensal.
- [x] Tarefas locais com prazo opcional e conclusão.
- [ ] Lembretes e tarefas recorrentes com notificações.
- [ ] Metas pessoais e financeiras.
- [ ] Migração da persistência local da V1 para Room.
- [ ] Backup e restauração criptografados.
- [ ] Cofre de senhas com desenho de segurança e auditoria próprios.
- [ ] Pipeline de build, testes e publicação de APK assinado.
- [ ] Manifesto assinado ou distribuição por canal oficial.

## Segurança

- Somente URLs HTTPS são aceitas para manifesto e download.
- O servidor de atualização deve usar certificado válido e ser mantido atualizado.
- A chave privada usada para assinar o APK nunca deve entrar no Git.
- O cofre de senhas será tratado como módulo separado; não deve ser implementado sem modelo de ameaça, criptografia autenticada e revisão de segurança.

## Licença

Ainda não definida.
