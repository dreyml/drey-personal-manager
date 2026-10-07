# Publicação do endpoint de atualização (Nginx)

Esta configuração é exclusiva para `updateappdrey.nxuslab.com`. Ela não usa
`default_server`, não altera os outros virtual hosts e serve arquivos somente
do diretório `/var/www/updateappdrey`.

## Pré-requisitos

1. O DNS `updateappdrey.nxuslab.com` deve apontar para `137.131.136.166`.
2. As regras de entrada da OCI e do firewall do sistema devem permitir TCP 80 e 443.
3. O servidor deve estar usando Nginx e Certbot.

## Instalação segura

Como o host final faz referência a um certificado TLS que ainda não existe,
ative primeiro o arquivo temporário `updateappdrey.bootstrap.conf` no diretório
de hosts do Nginx usado pela distribuição (normalmente
`/etc/nginx/sites-available/` no Ubuntu). Crie um link simbólico em
`sites-enabled`, mantendo todos os arquivos dos outros sites intactos.

Crie o diretório de publicação, valide somente a configuração temporária e
emita o certificado:

```bash
sudo install -d -o www-data -g www-data -m 0755 /var/www/updateappdrey/downloads
sudo nginx -t
sudo systemctl reload nginx
sudo certbot certonly --webroot -w /var/www/updateappdrey -d updateappdrey.nxuslab.com
```

Depois que o certificado existir, substitua apenas o arquivo temporário por
`updateappdrey.nxuslab.com.conf`. Confirme que os caminhos em
`ssl_certificate` e `ssl_certificate_key` correspondem aos arquivos emitidos,
e só então valide e recarregue:

```bash
sudo nginx -t && sudo systemctl reload nginx
```

## Arquivos publicados

```text
/var/www/updateappdrey/
├── manifest.json
└── downloads/
    └── drey-manager-0.2.0.apk
```

Exemplo de `manifest.json`:

```json
{
  "versionCode": 2,
  "versionName": "0.2.0",
  "message": "Uma nova versão do Drey Manager está disponível.",
  "downloadUrl": "https://updateappdrey.nxuslab.com/downloads/drey-manager-0.2.0.apk"
}
```

## Verificação

```bash
curl -i https://updateappdrey.nxuslab.com/manifest.json
curl -I https://updateappdrey.nxuslab.com/downloads/drey-manager-0.2.0.apk
```

O primeiro comando deve responder `200`, `Content-Type: application/json` e
`Cache-Control: no-store`. O segundo deve responder `200` depois que o APK
for enviado ao diretório `downloads`.
