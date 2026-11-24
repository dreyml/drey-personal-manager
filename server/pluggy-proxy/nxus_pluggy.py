"""Proxy pessoal entre a Pluggy (Open Finance) e o app NxusLife.

O Client Secret da Pluggy fica só aqui, na VM. O app fala apenas com este serviço,
autenticado por um token próprio (NXUS_API_TOKEN). Só biblioteca padrão do Python 3.10+.
"""
from __future__ import annotations

import hmac
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta, timezone
from decimal import ROUND_HALF_UP, Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PLUGGY_BASE = "https://api.pluggy.ai"
API_KEY_TTL = 100 * 60  # a apiKey vale 2 h; renovamos antes
RATE_LIMIT = 30  # requisições por minuto por IP
MAX_PAGES = 40


KNOWN_BANKS = (
    ("nu pagamentos", "Nubank"), ("nubank", "Nubank"), ("sicoob", "Sicoob"),
    ("mercado pago", "Mercado Pago"), ("mercadopago", "Mercado Pago"), ("inter", "Inter"),
    ("itau", "Itaú"), ("itaú", "Itaú"), ("bradesco", "Bradesco"), ("santander", "Santander"),
    ("caixa", "Caixa"), ("banco do brasil", "Banco do Brasil"), ("c6", "C6 Bank"),
    ("picpay", "PicPay"),
)


def institution_name(connector: str | None, account_names: list[str]) -> str | None:
    """Pelo Meu Pluggy o conector se chama "MeuPluggy"; o banco real vem do nome das contas."""
    for name in account_names:
        lower = (name or "").lower()
        for key, bank in KNOWN_BANKS:
            if key in lower:
                return bank
    return connector


class PluggyError(Exception):
    """Falha ao falar com a Pluggy. A mensagem nunca contém segredos."""


def to_cents(value) -> int | None:
    """Reais (número ou texto) para centavos inteiros, arredondando meio para cima."""
    if value is None:
        return None
    cents = (Decimal(str(value)) * 100).quantize(Decimal("1"), rounding=ROUND_HALF_UP)
    return int(cents)


BRT = timezone(timedelta(hours=-3))


def iso_day(value) -> str | None:
    """Data da Pluggy (UTC, ex.: 2026-11-23T03:00:00.000Z) para o dia em Brasília."""
    if not value:
        return None
    text = str(value)
    if "T" not in text:
        return text[:10]
    moment = datetime.fromisoformat(text.replace("Z", "+00:00"))
    # Meia-noite UTC = data pura (vencimentos); demais horários são instantes reais.
    if moment.tzinfo is None or (moment.hour, moment.minute, moment.second) == (0, 0, 0):
        return text[:10]
    return moment.astimezone(BRT).date().isoformat()


def signed_cents(tx: dict) -> int:
    """Negativo = dinheiro saindo (compra), positivo = entrando.

    O sinal de `amount` na Pluggy muda entre conta e cartão; o `type` (DEBIT/CREDIT)
    não muda, então ele decide o sinal.
    """
    cents = abs(to_cents(tx.get("amount")) or 0)
    return -cents if str(tx.get("type", "")).upper() == "DEBIT" else cents


def default_http(method: str, url: str, headers: dict, body: bytes | None):
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, {}
    except (urllib.error.URLError, TimeoutError, ValueError) as e:
        raise PluggyError(f"rede: {type(e).__name__}") from None


class PluggyClient:
    def __init__(self, client_id: str, client_secret: str, http=default_http, clock=time.monotonic):
        self._id, self._secret = client_id, client_secret
        self._http, self._clock = http, clock
        self._key: str | None = None
        self._key_at = 0.0
        self._lock = threading.Lock()

    def _api_key(self, force: bool = False) -> str:
        with self._lock:
            if force or not self._key or self._clock() - self._key_at > API_KEY_TTL:
                body = json.dumps({"clientId": self._id, "clientSecret": self._secret}).encode()
                status, data = self._http("POST", f"{PLUGGY_BASE}/auth",
                                          {"Content-Type": "application/json"}, body)
                if status != 200 or not data.get("apiKey"):
                    raise PluggyError(f"auth falhou (HTTP {status})")
                self._key, self._key_at = data["apiKey"], self._clock()
            return self._key

    def get(self, path: str, params: dict | None = None) -> dict:
        url = f"{PLUGGY_BASE}{path}"
        if params:
            url += "?" + urllib.parse.urlencode(params)
        for attempt in (0, 1):
            headers = {"X-API-KEY": self._api_key(force=attempt == 1), "Accept": "application/json"}
            status, data = self._http("GET", url, headers, None)
            if status == 401 and attempt == 0:
                continue  # apiKey vencida: renova e tenta de novo
            if status != 200:
                raise PluggyError(f"GET {path} falhou (HTTP {status})")
            return data
        raise PluggyError(f"GET {path} sem autorização")

    def cursor_pages(self, path: str, params: dict) -> list:
        """Rotas v2 da Pluggy: cada resposta traz `next` (query string pronta) ou null."""
        out, query, pages = [], "?" + urllib.parse.urlencode(params), 0
        while query and pages < MAX_PAGES:
            data = self.get(path + query)
            out.extend(data.get("results", []))
            query, pages = data.get("next"), pages + 1
        return out


def build_sync(client: PluggyClient, item_ids: list[str], since: str) -> dict:
    accounts, transactions, errors = [], [], []
    for item_id in item_ids:
        try:
            item = client.get(f"/items/{urllib.parse.quote(item_id)}")
        except PluggyError as e:
            # Um banco com problema (conexão removida, expirada…) não derruba os outros.
            sys.stderr.write(f"pluggy: {e}\n")
            errors.append({"itemId": item_id, "error": str(e)})
            continue
        item_accounts = client.get("/accounts", {"itemId": item_id}).get("results", [])
        institution = institution_name((item.get("connector") or {}).get("name"),
                                       [a.get("name") or "" for a in item_accounts])
        for acc in item_accounts:
            kind = "CREDIT" if acc.get("type") == "CREDIT" else "BANK"
            credit = acc.get("creditData") or {}
            # Saldo do cartão na Pluggy = valor em aberto da fatura atual.
            bill_amount = abs(to_cents(acc.get("balance")) or 0) if kind == "CREDIT" else None
            accounts.append({
                "id": acc.get("id"),
                "itemId": item_id,
                "institution": institution,
                # Quando a Pluggy buscou os dados no banco (o saldo vale a partir daí).
                "updatedAt": item.get("lastUpdatedAt") or item.get("updatedAt"),
                "type": kind,
                "name": acc.get("marketingName") or acc.get("name"),
                "balanceCents": to_cents(acc.get("balance")) or 0,
                "creditLimitCents": to_cents(credit.get("creditLimit")),
                "availableCreditCents": to_cents(credit.get("availableCreditLimit")),
                "billAmountCents": bill_amount,
                "billDueDate": iso_day(credit.get("balanceDueDate")),
                "billCloseDate": iso_day(credit.get("balanceCloseDate")),
            })
            for tx in client.cursor_pages("/v2/transactions", {"accountId": acc.get("id"), "dateFrom": since}):
                transactions.append({
                    "id": tx.get("id"),
                    "accountId": acc.get("id"),
                    "date": iso_day(tx.get("date")),
                    "amountCents": signed_cents(tx),
                    "description": tx.get("description") or "",
                    "category": tx.get("category"),
                    "type": tx.get("type"),
                    "status": tx.get("status"),
                })
    return {
        "syncedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "accounts": accounts,
        "transactions": transactions,
        "errors": errors,
    }


class RateLimiter:
    def __init__(self, limit: int = RATE_LIMIT, clock=time.monotonic):
        self.limit, self.clock = limit, clock
        self.hits: dict[str, list[float]] = {}
        self.lock = threading.Lock()

    def allow(self, ip: str) -> bool:
        now = self.clock()
        with self.lock:
            recent = [t for t in self.hits.get(ip, []) if now - t < 60]
            ok = len(recent) < self.limit
            if ok:
                recent.append(now)
            self.hits[ip] = recent
            return ok


def make_handler(client: PluggyClient, item_ids: list[str], token: str, limiter: RateLimiter):
    class Handler(BaseHTTPRequestHandler):
        server_version = "nxus-pluggy"
        sys_version = ""

        def _send(self, status: int, payload: dict):
            body = json.dumps(payload, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, fmt, *args):  # sem query string nem cabeçalhos no log
            sys.stderr.write(f"{self.command} {self.path.split('?')[0]} {args[1] if len(args) > 1 else ''}\n")

        def _client_ip(self) -> str:
            # O nginx define X-Forwarded-For com o IP real; o serviço só escuta em 127.0.0.1.
            fwd = self.headers.get("X-Forwarded-For", "")
            return fwd.split(",")[-1].strip() or self.client_address[0]

        def do_GET(self):
            if len(self.path) > 256:
                return self._send(414, {"error": "url longa demais"})
            parsed = urllib.parse.urlparse(self.path)
            if parsed.path == "/api/health":
                return self._send(200, {"ok": True})
            if parsed.path != "/api/sync":
                return self._send(404, {"error": "não encontrado"})
            if not limiter.allow(self._client_ip()):
                return self._send(429, {"error": "muitas requisições"})
            auth = self.headers.get("Authorization", "")
            given = auth[7:] if auth.startswith("Bearer ") else ""
            if not given or not hmac.compare_digest(given.encode(), token.encode()):
                return self._send(401, {"error": "não autorizado"})
            since = (urllib.parse.parse_qs(parsed.query).get("from") or [""])[0]
            try:
                since = date.fromisoformat(since).isoformat() if since else \
                    (date.today() - timedelta(days=60)).isoformat()
            except ValueError:
                return self._send(400, {"error": "from deve ser AAAA-MM-DD"})
            try:
                result = build_sync(client, item_ids, since)
                if item_ids and len(result["errors"]) == len(item_ids):
                    raise PluggyError(result["errors"][0]["error"])
                return self._send(200, result)
            except PluggyError as e:
                sys.stderr.write(f"pluggy: {e}\n")
                return self._send(502, {"error": str(e)})

        def _not_allowed(self):
            self._send(405, {"error": "método não permitido"})

        do_POST = do_PUT = do_DELETE = do_PATCH = _not_allowed

    return Handler


def main():
    env = os.environ
    missing = [k for k in ("PLUGGY_CLIENT_ID", "PLUGGY_CLIENT_SECRET", "PLUGGY_ITEM_IDS",
                           "NXUS_API_TOKEN") if not env.get(k)]
    if missing:
        sys.exit(f"faltam variáveis: {', '.join(missing)}")
    if len(env["NXUS_API_TOKEN"]) < 32:
        sys.exit("NXUS_API_TOKEN curto demais; gere com: openssl rand -hex 32")
    item_ids = [i.strip() for i in env["PLUGGY_ITEM_IDS"].split(",") if i.strip()]
    client = PluggyClient(env["PLUGGY_CLIENT_ID"], env["PLUGGY_CLIENT_SECRET"])
    handler = make_handler(client, item_ids, env["NXUS_API_TOKEN"], RateLimiter())
    port = int(env.get("NXUS_PORT", "8787"))
    server = ThreadingHTTPServer(("127.0.0.1", port), handler)
    sys.stderr.write(f"nxus-pluggy ouvindo em 127.0.0.1:{port} ({len(item_ids)} bancos)\n")
    server.serve_forever()


if __name__ == "__main__":
    main()
