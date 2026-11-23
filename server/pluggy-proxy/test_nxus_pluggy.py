import json
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

import nxus_pluggy as np

TOKEN = "t" * 64


class FakePluggy:
    """Responde como a API da Pluggy, sem rede."""

    def __init__(self):
        self.auth_calls = 0
        self.expire_next = False

    def __call__(self, method, url, headers, body):
        if url.endswith("/auth"):
            self.auth_calls += 1
            return 200, {"apiKey": f"key{self.auth_calls}"}
        if self.expire_next:
            self.expire_next = False
            return 401, {}
        if "/items/" in url:
            return 200, {"id": "it1", "connector": {"name": "Nubank"}}
        if "/accounts" in url:
            return 200, {"results": [
                {"id": "a1", "type": "BANK", "name": "Conta", "balance": 1217.42},
                {"id": "c1", "type": "CREDIT", "name": "Cartão", "balance": 658.81,
                 "creditData": {"creditLimit": 2000, "availableCreditLimit": "1341.19",
                                "balanceDueDate": "2026-12-22T00:00:00.000Z"}},
            ]}
        if "/transactions" in url:
            page = int(url.split("page=")[1].split("&")[0])
            if "accountId=a1" in url:
                tx = {"id": f"t{page}", "date": "2026-11-20T10:00:00Z", "amount": -45.76,
                      "type": "DEBIT", "description": "Mercado", "category": "Groceries"}
                return 200, {"totalPages": 2, "results": [tx]}
            return 200, {"totalPages": 1, "results": [
                {"id": "cc1", "date": "2026-11-21", "amount": 89.9, "type": "DEBIT",
                 "description": "Loja"},
                {"id": "cc2", "date": "2026-11-21", "amount": -10, "type": "CREDIT",
                 "description": "Estorno"}]}
        return 404, {}


class UnitTests(unittest.TestCase):
    def test_cents(self):
        self.assertEqual(np.to_cents("1217.42"), 121742)
        self.assertEqual(np.to_cents("-45.76"), -4576)
        self.assertEqual(np.to_cents(0.105), 11)  # meio para cima, sem erro de float
        self.assertIsNone(np.to_cents(None))

    def test_sign_comes_from_type(self):
        self.assertEqual(np.signed_cents({"amount": 89.9, "type": "DEBIT"}), -8990)
        self.assertEqual(np.signed_cents({"amount": -89.9, "type": "DEBIT"}), -8990)
        self.assertEqual(np.signed_cents({"amount": -10, "type": "CREDIT"}), 1000)

    def test_sync_merges_pages_and_maps_accounts(self):
        out = np.build_sync(np.PluggyClient("id", "secret", http=FakePluggy()), ["it1"], "2026-10-01")
        bank, card = out["accounts"]
        self.assertEqual((bank["type"], bank["balanceCents"], bank["institution"]), ("BANK", 121742, "Nubank"))
        self.assertEqual((card["creditLimitCents"], card["availableCreditCents"]), (200000, 134119))
        self.assertEqual((card["billAmountCents"], card["billDueDate"]), (65881, "2026-12-22"))
        self.assertEqual([t["id"] for t in out["transactions"]], ["t1", "t2", "cc1", "cc2"])
        self.assertEqual(out["transactions"][2]["amountCents"], -8990)
        self.assertEqual(out["transactions"][0]["date"], "2026-11-20")

    def test_api_key_refreshed_on_401(self):
        fake = FakePluggy()
        client = np.PluggyClient("id", "secret", http=fake)
        client.get("/items/it1")
        fake.expire_next = True
        client.get("/items/it1")
        self.assertEqual(fake.auth_calls, 2)

    def test_api_key_refreshed_after_ttl(self):
        now = [0.0]
        fake = FakePluggy()
        client = np.PluggyClient("id", "secret", http=fake, clock=lambda: now[0])
        client.get("/items/it1")
        now[0] = np.API_KEY_TTL + 1
        client.get("/items/it1")
        self.assertEqual(fake.auth_calls, 2)

    def test_rate_limit(self):
        limiter = np.RateLimiter(limit=2, clock=lambda: 0.0)
        self.assertEqual([limiter.allow("ip") for _ in range(3)], [True, True, False])


class HttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        client = np.PluggyClient("id", "secret", http=FakePluggy())
        handler = np.make_handler(client, ["it1"], TOKEN, np.RateLimiter(limit=1000))
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def call(self, path, token=None, method="GET"):
        req = urllib.request.Request(self.base + path, method=method)
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def test_health(self):
        self.assertEqual(self.call("/api/health"), (200, {"ok": True}))

    def test_token_required(self):
        self.assertEqual(self.call("/api/sync")[0], 401)
        self.assertEqual(self.call("/api/sync", "errado")[0], 401)

    def test_sync_ok(self):
        status, body = self.call("/api/sync?from=2026-10-01", TOKEN)
        self.assertEqual(status, 200)
        self.assertEqual(len(body["accounts"]), 2)

    def test_bad_input(self):
        self.assertEqual(self.call("/api/sync?from=ontem", TOKEN)[0], 400)
        self.assertEqual(self.call("/outra")[0], 404)
        self.assertEqual(self.call("/api/sync", TOKEN, method="POST")[0], 405)


if __name__ == "__main__":
    unittest.main()
