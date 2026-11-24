# nxus-pluggy — sincronização com os bancos

Serviço pequeno que roda na VM. Ele guarda o Client Secret da Pluggy e entrega ao app
apenas os seus próprios dados (saldos, cartões e transações). O app nunca vê o segredo:
ele usa um token próprio (`NXUS_API_TOKEN`).

- `GET /api/health` → `{"ok": true}`
- `GET /api/sync?from=AAAA-MM-DD` com `Authorization: Bearer <token>` → contas e transações (em centavos)

## Instalar na VM (Ubuntu 22.04)

Rode os comandos na VM, um por vez. **Digite os segredos você mesmo; nunca cole em chat.**

```bash
# 1) Usuário dedicado e arquivos
sudo useradd --system --no-create-home --shell /usr/sbin/nologin nxuspluggy
sudo mkdir -p /opt/nxus-pluggy /etc/nxuslife
sudo cp nxus_pluggy.py /opt/nxus-pluggy/
sudo cp nxus-pluggy.service /etc/systemd/system/

# 2) Token do app (anote; ele vai no app depois)
openssl rand -hex 32

# 3) Segredos (copie o modelo de pluggy.env.example e preencha)
sudo nano /etc/nxuslife/pluggy.env
sudo chown root:root /etc/nxuslife/pluggy.env
sudo chmod 600 /etc/nxuslife/pluggy.env

# 4) Ligar o serviço
sudo systemctl daemon-reload
sudo systemctl enable --now nxus-pluggy
sudo systemctl status nxus-pluggy --no-pager
```

O systemd lê o `pluggy.env` como root antes de iniciar o serviço, então o arquivo pode
ficar só com permissão do root.

## Nginx

Cole o conteúdo de `nginx-location.conf` dentro do bloco `server { listen 443 ... }` de
`/etc/nginx/sites-available/updateappdrey.nxuslab.com.conf`, **antes** de
`location / { return 404; }`. Depois:

```bash
sudo nginx -t && sudo systemctl reload nginx
curl https://updateappdrey.nxuslab.com/api/health
curl -H "Authorization: Bearer SEU_TOKEN" "https://updateappdrey.nxuslab.com/api/sync?from=2026-11-01" | head -c 600
```

## Onde achar os ids das conexões (PLUGGY_ITEM_IDS)

No plano gratuito do Meu Pluggy a API que lista conexões (`/v2/items`) não fica
liberada, por isso os ids vão no `pluggy.env`. Cada banco conectado ao seu app aparece no
painel [dashboard.pluggy.ai](https://dashboard.pluggy.ai), na área de conexões (items) da
aplicação, com um id no formato `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`. Coloque os três
(Nubank, Sicoob e Mercado Pago) separados por vírgula.

## Logs e testes

```bash
journalctl -u nxus-pluggy -n 50 --no-pager   # nunca mostra segredo nem token
python3 -m unittest -v                        # dentro desta pasta, sem rede
```
