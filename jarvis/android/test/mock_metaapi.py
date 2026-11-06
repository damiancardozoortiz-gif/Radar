#!/usr/bin/env python3
"""MetaApi SIMULADA (no es el servicio real) para probar el conector: mismas rutas y campos que el SDK oficial 29.3.4."""
import json, math, sys
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs
LOG = open(sys.argv[2], "a")
ACC = "acc-demo-1"
TF = {"1m": 1, "5m": 5, "15m": 15, "30m": 30, "1h": 60, "4h": 240, "1d": 1440}
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send(self, code, obj):
        data = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
    def do_POST(self):
        LOG.write(json.dumps({"metodo": "POST", "ruta": self.path}) + "\n"); LOG.flush()
        self.send(405, {"error": "ForbiddenError", "message": "la prueba no admite POST"})
    def do_GET(self):
        u = urlparse(self.path); p = u.path
        LOG.write(json.dumps({"metodo": "GET", "ruta": p, "token_ok": self.headers.get("auth-token") == "tok-prueba"}) + "\n"); LOG.flush()
        if self.headers.get("auth-token") != "tok-prueba": return self.send(401, {"error": "UnauthorizedError", "message": "Invalid auth-token"})
        if p == "/users/current/servers/mt-client-api": return self.send(200, {"domain": "agiliumtrade.ai", "hostname": "mt-client-api-v1"})
        base = "/users/current/accounts/"
        if not p.startswith(base): return self.send(404, {"error": "NotFoundError", "message": "ruta"})
        rest = p[len(base):].split("/")
        if rest[0] != ACC: return self.send(404, {"error": "NotFoundError", "message": "Account not found"})
        if len(rest) == 1: return self.send(200, {"_id": ACC, "name": "Demo Rafael", "server": "Broker-Demo", "state": "DEPLOYED", "connectionStatus": "CONNECTED", "region": "new-york", "type": "cloud-g2"})
        if rest[1] == "account-information": return self.send(200, {"platform": "mt5", "broker": "Broker SA", "currency": "USD", "server": "Broker-Demo", "balance": 10000, "equity": 10012.5, "margin": 0, "freeMargin": 10012.5, "leverage": 100, "marginLevel": None, "tradeAllowed": True, "investorMode": True, "type": "ACCOUNT_TRADE_MODE_DEMO", "login": 123, "name": "Rafael", "credit": 0, "currencyDigits": 2, "marginMode": "ACCOUNT_MARGIN_MODE_RETAIL_HEDGING"})
        if rest[1] == "positions": return self.send(200, [{"id": "1", "type": "POSITION_TYPE_BUY", "symbol": "EURUSD", "volume": 0.1, "openPrice": 1.1, "stopLoss": 1.095, "profit": 12.5}])
        if rest[1] == "historical-market-data" and rest[2] == "symbols" and rest[4] == "timeframes" and rest[6] == "candles":
            tf = rest[5]; n = int(parse_qs(u.query).get("limit", ["100"])[0])
            if tf not in TF: return self.send(400, {"error": "ValidationError", "message": "timeframe"})
            end = datetime(2026, 11, 6, 12, 0, tzinfo=timezone.utc); out = []
            for i in range(n):  # de la más reciente a la más antigua, como MetaApi
                t = end - timedelta(minutes=TF[tf] * i); x = 1.1 + 0.002 * math.sin((n - i) / 9.0) + 0.00001 * (n - i)
                out.append({"symbol": rest[3], "timeframe": tf, "time": t.strftime("%Y-%m-%dT%H:%M:%S.000Z"), "brokerTime": "", "open": x, "high": x + 0.0006, "low": x - 0.0006, "close": x + 0.0001, "tickVolume": 100 + i % 7, "spread": 1, "volume": 0})
            return self.send(200, out)
        self.send(404, {"error": "NotFoundError", "message": "ruta"})
ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
