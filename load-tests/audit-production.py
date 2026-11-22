#!/usr/bin/env python3
"""Auditoria de produção FoxCash (Spec 37/41).

Executa centenas de operações reais contra a API de produção e imprime um
resumo por status HTTP. O objetivo é popular master_audit_log e
platform_activity_log com tráfego variado (lojista, portal, webhook HMAC,
perfis master) para depois conferir presença e ausência de dados sensíveis.

Respeita o rate limit de produção (20 req/min/IP por rota pública): usa
sleep ~3.5s entre chamadas às rotas limitadas (merchants, login, purchase).

Segredos NUNCA hardcoded (homologação 2026-08-07): vêm de env vars e o script
falha no boot se ausentes (fail-fast — mesmo padrão do backend). Uso:

  FOXCASH_AUDIT_WS_SECRET=<secret> FOXCASH_AUDIT_MASTER_PASSWORD=<senha> \
    python load-tests/audit-production.py [iters] [master-profiles]
"""
import hmac
import hashlib
import json
import os
import sys
import time
import urllib.request
import urllib.error

BASE = os.environ.get("FOXCASH_AUDIT_BASE_URL", "https://foxcash-production.up.railway.app")
WS_SECRET = os.environ.get("FOXCASH_AUDIT_WS_SECRET")
MASTER_PASSWORD = os.environ.get("FOXCASH_AUDIT_MASTER_PASSWORD")
MASTER_USERNAME = os.environ.get("FOXCASH_AUDIT_MASTER_USERNAME", "master")
ITERS = int(sys.argv[1]) if len(sys.argv) > 1 else 25
MASTER_PROFILES = int(sys.argv[2]) if len(sys.argv) > 2 else 10

if not WS_SECRET or not MASTER_PASSWORD:
    print("FALHA: segredos ausentes. Defina as env vars FOXCASH_AUDIT_WS_SECRET e "
          "FOXCASH_AUDIT_MASTER_PASSWORD — nunca coloque segredos reais no script.")
    sys.exit(1)

ok = {}
fail = {}


def call(method, path, body=None, headers=None, note=""):
    data = None if body is None else json.dumps(body).encode()
    hdrs = {"Content-Type": "application/json"}
    if headers:
        hdrs.update(headers)
    req = urllib.request.Request(BASE + path, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            code = r.status
    except urllib.error.HTTPError as e:
        raw = e.read()
        code = e.code
    except Exception as e:
        print(f"  [ERRO] {note} {method} {path}: {e}")
        fail["exception"] = fail.get("exception", 0) + 1
        return None, None
    key = f"{note} {code}"
    ok[key] = ok.get(key, 0) + 1
    if code >= 400:
        fail[key] = fail.get(key, 0) + 1
    parsed = None
    if raw:
        try:
            parsed = json.loads(raw)
        except Exception:
            pass
    return code, parsed


def hmac_sign(payload: str) -> str:
    return hmac.new(WS_SECRET.encode(), payload.encode(), hashlib.sha256).hexdigest()


def master_token():
    _, resp = call("POST", "/api/v1/auth/master/login",
                   {"username": MASTER_USERNAME, "password": MASTER_PASSWORD}, note="master-login")
    return resp["token"] if resp else None


def merchant_flow(token, i):
    ts = int(time.time() * 1000)
    email = f"audit.batch{i}.{ts}@example.com"
    passw = "Senha-audit-1"
    # Rotas limitadas: espaçar ~3.5s para ficar abaixo de 20/min.
    time.sleep(3.5)
    c, reg = call("POST", "/api/v1/merchants",
                  {"name": f"Loja audit {i} {ts}", "email": email, "password": passw}, note="register")
    if c != 201 or not reg:
        return
    key = reg.get("apiKey")
    time.sleep(3.5)
    c, login = call("POST", "/api/v1/merchant-auth/login",
                    {"email": email, "password": passw}, note="login")
    if c != 200 or not login:
        return
    ptok = login["token"]
    portal = {"Authorization": f"Bearer {ptok}"}
    call("POST", "/api/v1/portal/store", {"name": f"Loja audit {i}"}, portal, note="store")
    c, plan = call("POST", "/api/v1/portal/plans",
                   {"name": f"Plano audit {i}", "price": 5.0, "description": "plano auditoria"}, portal, note="plan")
    if c != 201 or not plan:
        return
    pid = plan["id"]
    call("POST", f"/api/v1/portal/plans/{pid}/activate", None, portal, note="plan-activate")
    time.sleep(3.5)
    c, cob = call("POST", f"/api/v1/foxcash/plans/{pid}/purchase",
                  {"platform": "DISCORD", "platformUserId": f"au-{ts}", "buyerId": "audit-buyer",
                   "expirationSeconds": 3600, "idempotencyKey": f"audit-pur-{ts}", "termsAccepted": True},
                  {"X-Api-Key": key}, note="purchase")
    if c != 201 or not cob:
        return
    eref = cob.get("externalReference")
    # Webhook PAID assinado (rota pública, sem rate limit) — SALE_PAID.
    payload = json.dumps({"eventId": f"audit-ev-{ts}", "externalReference": eref,
                          "status": "PAID", "settledAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                          "amount": 5.0})
    req = urllib.request.Request(BASE + "/webhooks/pix", data=payload.encode(),
                                 headers={"Content-Type": "application/json",
                                          "X-Psp-Signature": hmac_sign(payload)}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            code = r.status
    except urllib.error.HTTPError as e:
        code = e.code
    except Exception as e:
        print(f"  [ERRO] webhook: {e}")
        code = 0
    k = f"webhook {code}"
    ok[k] = ok.get(k, 0) + 1
    if code >= 400:
        fail[k] = fail.get(k, 0) + 1
    # Portal (sem rate limit): botão e mensagem → BUTTON_CREATED/MESSAGE_CREATED.
    call("POST", "/api/v1/portal/store/buttons",
         {"label": f"Botao {i}", "messageId": None, "priority": 1, "active": True}, portal, note="button")
    call("POST", "/api/v1/portal/store/messages",
         {"title": f"Msg {i}", "content": "conteudo auditoria", "platform": "DISCORD"}, portal, note="message")


def master_profile_flow(token, i):
    auth = {"Authorization": f"Bearer {token}"}
    c, prof = call("POST", "/api/v1/master/profiles",
                   {"username": f"audit.master{i}", "name": f"Master audit {i}"}, auth, note="profile")
    if c != 201 or not prof:
        return
    pid = prof["id"]
    if i % 2 == 0:
        call("POST", f"/api/v1/master/profiles/{pid}/activate", None, auth, note="profile-activate")
    else:
        call("POST", f"/api/v1/master/profiles/{pid}/deactivate", None, auth, note="profile-deactivate")


def main():
    print(f"=== Bateria de auditoria: {ITERS} lojistas + {MASTER_PROFILES} perfis master ===")
    token = master_token()
    if not token:
        print("FALHA: master login"); sys.exit(1)
    print("master login OK")
    for i in range(1, ITERS + 1):
        merchant_flow(token, i)
        if i % 5 == 0:
            print(f"  ... {i} lojistas processados")
    for i in range(1, MASTER_PROFILES + 1):
        master_profile_flow(token, i)
    print("\n=== Resumo por status HTTP ===")
    for k, v in sorted(ok.items()):
        flag = "  <<< FALHA" if k in fail else ""
        print(f"  {v:4d}  {k}{flag}")


if __name__ == "__main__":
    main()
