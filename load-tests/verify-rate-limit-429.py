#!/usr/bin/env python3
"""Verificacao da DEGRADACAO CONTROLADA (Spec 58).

Quando a carga passa do limite, o sistema deve responder 429/409 de forma
controlada — em vez de deixar Tomcat/DB/memoria saturarem. Este script prova
isso contra a app RODANDO, cobrindo as tres camadas:

  A. 429 por PRINCIPAL autenticado (AuthenticatedRateLimitFilter)
     Mesma API key + mesma rota acima de authenticated.max-requests
     (baseline 120/min) -> 429 too_many_requests. Chave: path|principal
     (o lojista e o bucket, nao o IP). Rota usada: GET /api/v1/merchants/{id}
     — o filtro cobre TODO /api/v1/**, e esta rota nao tem loja nem bucket
     de IP (determinismo em runs repetidos).

  B. 429 por IP em rota TRANSACIONAL (RateLimitFilter, grupo transactional)
     Flood anonimo numa rota transacional acima de transactional.max-requests
     (baseline 300/min) -> 429. Dentro do limite: 401 (barreira de auth);
     estourou: 429 ANTES de auth — o IP e a rede de seguranca final.
     Chave: path|IP.

  C. 409 do CAP de cobrancas pendentes (CreatePixChargeService)
     Acima de max-pending-charges (baseline 100), nova cobranca -> 409
     too_many_pending_charges — o PSP nunca e chamado (sem custo).

Como rodar (app em pe):

  1. Suba a app com limites REDUZIDOS para um run rapido. O banco e o
     PostgreSQL NATIVO local (esta maquina nao usa Docker — ver README; role
     e database `foxcash` devem existir):
       SPRING_PROFILES_ACTIVE=dev \
         FOXCASH_SECURITY_RATE_LIMIT_AUTHENTICATED_MAX_REQUESTS=5 \
         FOXCASH_SECURITY_RATE_LIMIT_TRANSACTIONAL_MAX_REQUESTS=20 \
         FOXCASH_PRICING_MAX_PENDING_CHARGES=3 \
         mvn spring-boot:run
  2. Rode a verificacao (mesmos limites como argumentos):
       python load-tests/verify-rate-limit-429.py \
         --auth-limit 5 --transactional-limit 20 --cap 3

  Sem argumentos, usa os baselines (120/300/100) — funciona contra a app
  default. Restricao: transactional >= auth + probe (senao a camada A e
  mascarada pelo limite de IP na mesma rota); auth >= cap + probe (senao a
  camada C e mascarada pelo 429). Exit 0 = todas as camadas verificadas.
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request

HEALTH_PATH = "/actuator/health"
# Layer A: GET do proprio lojista — autenticado, sem loja e SEM bucket de IP
# (nao esta nos prefixos transacionais); o AuthenticatedRateLimitFilter cobre
# todo /api/v1/**. Layer B: probe transacional UNICO por run (nonce) para o
# bucket path|IP nascer fresco a cada execucao. Layer C: cobranca (cap).
AUTH_PATH = "/api/v1/merchants/{merchant_id}"
PROBE_PATH = f"/api/v1/transactions/verification-probe-{int(time.time() * 1000)}"
COB_PATH = "/api/v1/foxcash/pix/cob"


def call(base, method, path, body=None, headers=None, timeout=30):
    """Retorna (status, dict-or-None). Segue o padrao de audit-production.py."""
    data = None if body is None else json.dumps(body).encode()
    hdrs = {"Content-Type": "application/json"}
    if headers:
        hdrs.update(headers)
    req = urllib.request.Request(base + path, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            status = r.status
    except urllib.error.HTTPError as e:
        raw = e.read()
        status = e.code
    except Exception as e:  # rede/infra: a app nao respondeu — nao e 429, e falha real
        return None, str(e)
    parsed = None
    if raw:
        try:
            parsed = json.loads(raw)
        except Exception:
            pass
    return status, parsed


def register_merchant(base):
    ts = int(time.time() * 1000)
    email = f"rl429.{ts}@example.com"
    status, merchant = call(base, "POST", "/api/v1/merchants",
                            {"name": f"Loja RL429 {ts}", "email": email,
                             "password": "Senha-rl429-1"})
    if status != 201 or not merchant:
        raise RuntimeError(f"registro de lojista falhou (status={status}) — app no ar? "
                           f"SPRING_PROFILES_ACTIVE=dev?")
    return merchant


def expect_blocked(status, parsed, expected, code):
    """Valida o contrato do bloqueio (429/409): status + JSON com o code certo."""
    if status != expected:
        return False, f"esperava {expected}, veio {status}"
    if not parsed or parsed.get("code") != code:
        return False, f"body esperava code={code}, veio {parsed}"
    return True, ""


def layer_a(base, api_key, merchant_id, auth_limit, probe):
    """429 por principal autenticado: dentro do limite 200; estourou 429."""
    path = AUTH_PATH.format(merchant_id=merchant_id)
    total = auth_limit + probe
    seen = []
    for i in range(total):
        status, parsed = call(base, "GET", path, headers={"X-Api-Key": api_key})
        seen.append(status)
        if i >= auth_limit:
            ok, why = expect_blocked(status, parsed, 429, "too_many_requests")
            if not ok:
                return False, f"item {i}: {why}"
    if seen[:auth_limit] != [200] * auth_limit:
        return False, f"esperava {auth_limit}x200 no inicio, veio {seen[:auth_limit]}"
    return True, f"{auth_limit}x200 + {probe}x429 too_many_requests (chave: path|principal)"


def layer_b(base, trans_limit, probe):
    """429 por IP em rota transacional: dentro 401 (auth barra); estourou 429."""
    total = trans_limit + probe
    seen = []
    for i in range(total):
        status, parsed = call(base, "GET", PROBE_PATH)  # anonimo, sem API key
        seen.append(status)
        if i >= trans_limit:
            ok, why = expect_blocked(status, parsed, 429, "too_many_requests")
            if not ok:
                return False, f"item {i}: {why}"
    if seen[:trans_limit] != [401] * trans_limit:
        return False, f"esperava {trans_limit}x401 no inicio, veio {seen[:trans_limit]}"
    return True, f"{trans_limit}x401 + {probe}x429 too_many_requests (chave: path|IP)"


def layer_c(base, api_key, merchant_id, cap, probe):
    """409 do cap: primeiras cap cobrancas 201; depois 409 too_many_pending_charges."""
    total = cap + probe
    seen = []
    for i in range(total):
        status, parsed = call(base, "POST", COB_PATH, {
            "merchantId": merchant_id,
            "amount": 10.0,
            "buyerId": "verify-429-buyer",
            "expirationSeconds": 3600,
            "idempotencyKey": f"rl429-{i}-{time.time_ns()}",
        }, headers={"X-Api-Key": api_key})
        seen.append(status)
        if i >= cap:
            ok, why = expect_blocked(status, parsed, 409, "too_many_pending_charges")
            if not ok:
                return False, f"item {i}: {why}"
    if seen[:cap] != [201] * cap:
        return False, f"esperava {cap}x201 no inicio, veio {seen[:cap]}"
    return True, f"{cap}x201 + {probe}x409 (cap de pendentes, sem chamada ao PSP)"


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--auth-limit", type=int, default=120, help="authenticated.max-requests")
    parser.add_argument("--transactional-limit", type=int, default=300, help="transactional.max-requests")
    parser.add_argument("--cap", type=int, default=100, help="max-pending-charges")
    parser.add_argument("--probe", type=int, default=2, help="requests extras apos estourar o limite")
    args = parser.parse_args()

    if args.transactional_limit < args.auth_limit + args.probe:
        print("AVISO: transactional-limit < auth-limit+probe — a camada A (principal) seria "
              "mascarada pelo limite de IP na mesma rota. Aumente o transactional-limit.")
    if args.auth_limit < args.cap + args.probe:
        print("AVISO: auth-limit < cap+probe — a camada C (cap) seria mascarada pelo 429 "
              "do principal na rota de cobranca. Aumente o auth-limit.")

    # 0. App no ar?
    status, _ = call(args.base_url, "GET", HEALTH_PATH)
    if status != 200:
        print(f"[FALHA] app indisponivel ({args.base_url}{HEALTH_PATH} -> {status}). "
              f"Subiu com SPRING_PROFILES_ACTIVE=dev? Postgres no ar?")
        sys.exit(1)

    merchant = register_merchant(args.base_url)
    api_key = merchant["apiKey"]
    merchant_id = merchant["id"]

    print(f"=== Verificacao de degradacao controlada (Spec 58) — {args.base_url} ===")
    print(f"    auth={args.auth_limit}/min por principal | transactional={args.transactional_limit}/min "
          f"por IP | cap={args.cap} pendentes\n")

    results = []
    ok_a, why_a = layer_a(args.base_url, api_key, merchant_id, args.auth_limit, args.probe)
    results.append(("A. 429 por principal autenticado", ok_a, why_a))

    ok_b, why_b = layer_b(args.base_url, args.transactional_limit, args.probe)
    results.append(("B. 429 por IP (rota transacional)", ok_b, why_b))

    ok_c, why_c = layer_c(args.base_url, api_key, merchant_id, args.cap, args.probe)
    results.append(("C. 409 do cap de pendentes", ok_c, why_c))

    print("Resultado por camada:")
    all_ok = True
    for name, ok, why in results:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}")
        print(f"         {why}")
        all_ok = all_ok and ok
    print(f"\n{'TODAS AS CAMADAS OK — degradacao controlada verificada.' if all_ok else 'FALHA: veja acima.'}")
    sys.exit(0 if all_ok else 1)


if __name__ == "__main__":
    main()
