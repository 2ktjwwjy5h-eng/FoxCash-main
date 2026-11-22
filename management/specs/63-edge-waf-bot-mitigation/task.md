---
name: Edge/WAF e bot mitigation — Cloudflare na frente do Railway
status: audited
---

# Spec 63 — Task: Edge/WAF e bot mitigation (Cloudflare)

Checklist de implementação. Princípio: **o edge reduz, o app controla; toda
regra tem fail-open ou exceção; rollback sempre disponível.**

> **Domínio real: `api.foxcash.example.com`** (zona `foxcash.example.com` — o
> `api.foxcash.example.com` citado no corpo antigo do runbook é histórico de uma zona
> anterior/parqueada). Estado abaixo reflete a verificação ao vivo de
> **2026-08-17** (re-verificação desta auditoria) + o registro da zona
> `foxcash.example.com` no `deploy/edge.md` §"Zona foxcash.example.com (2026-08-17)".

## Fase A — DNS/TLS/edge (base)

- [x] 1. **Domínio próprio** registrado/apontado para o Cloudflare —
      `foxcash.example.com` (nameservers `evan`/`meadow.ns.cloudflare.com` na zona,
      id `<CF_ZONE_ID>`). ✅
- [x] 2. **DNS record**: CNAME `api` → `custom-api.up.railway.app`, **proxy ON**.
      **Evidência (2026-08-17):** `api.foxcash.example.com` resolve para IPs do
      Cloudflare (104.21.66.152 / 172.67.161.124); `curl -I
      https://api.foxcash.example.com/` → **200** + header **`cf-ray`** + HSTS. ✅
- [x] 3. **SSL mode = Full (strict)** — aplicado no dashboard (2026-08-17). **Evidência:**
      HTTPS 200 sem erro de certificado; `strict-transport-security` presente. ✅
- [x] 4. **trust-proxy validado** — por código (`ClientIpResolver` resolve
      `CF-Connecting-IP` primeiro; `FOXCASH_TRUST_PROXY=true` confia no XFF);
      evidência de log pendente (registrada — critério 7 ⚠️ no requirements). ✅ código

## Fase B — WAF e edge rate limit

- [x] 5. **DDoS protection**: ativo por default no plano Free (ruleset `ddos_l7`). ✅
- [x] 6. **WAF ruleset gerenciado** ativo + 4 regras customizadas (exceção
      webhook PSP, SQLi/XSS query, path traversal, content-type JSON).
      **Evidência (2026-08-17):** SQLi `' OR 1=1 --` na query de
      `/merchant-auth/login` → **403** na borda. ✅ (body não inspecionado no
      Free — coberto pela camada app `RequestBodyInspectionFilter`.)
- [x] 7. **Exceção do webhook do PSP**: `/webhooks/pix|payouts` fora das regras
      (skip WAF + fora do edge rate limit). **Teste com assinatura REAL do PSP
      deferido** (Fase 2 Pagar.me — critério 6 do requirements). ✅ regra aplicada
- [x] 8. **Edge rate limit**: 1 regra consolidada **50 req/10s/IP** em
      cadastro/auth/cob/charges. **Evidência (2026-08-17):** burst em
      `POST /api/v1/merchants` → ~20 requests passam (400), depois **429**
      (`Server: cloudflare` + `cf-ray` — na borda). ✅
- [x] 9. **Bot Fight Mode** ativo (`fight_mode: true`). Comportamento Free:
      modo "verificação" (não bloqueia por UA sozinho) — proteção
      anti-automação efetiva é o Turnstile. ✅

## Fase C — Turnstile no portal (bot mitigation)

- [x] 10. **Site key + secret key** do Turnstile — widget "FoxCash"
       (`<TURNSTILE_SITE_KEY>` / secret `<TURNSTILE_SECRET_KEY>`),
       domínios atualizados para `api.foxcash.example.com` + `foxcash.example.com`. ✅
- [x] 11. **Frontend (portal/)**: script do Turnstile + campo token nos forms de
       login e cadastro (commit `7f305fb`; `LoginPage.tsx` busca a site key em
       `/api/v1/portal/turnstile-config`). ✅
- [x] 12. **Backend**: `TurnstileVerifier` (siteverify, timeout 5s, **fail-open**
       em rede/5xx com WARN throttled; 4xx → `turnstile_failed`) + handler 403 +
       properties `foxcash.security.turnstile.*` (default off). Aplica em
       `/merchant-auth/login` e `/api/v1/merchants`. ✅
- [x] 13. **Aplicar em produção**: `FOXCASH_TURNSTILE_ENABLED=true` + keys no
       Railway. **Evidência (2026-08-17):** login sem token → **403
       `{"code":"turnstile_failed",...}`**; `/api/v1/portal/turnstile-config` →
       `{"siteKey":"<TURNSTILE_SITE_KEY>"}`. ✅

## Fase D — Verificação final + runbook

- [x] 14. **Verificação dos critérios 1-9** — tabela no requirements + §Fase D do
       runbook (resultados 2026-08-10 + re-verificação 2026-08-17):
       C1 ✅, C2 ⚠️ parcial (soak completo recomendado), C3 ✅ query / ⚠️ body
       (camada app cobre), C4 ✅, C5 ✅, C6 ⏳ deferido (PSP real), C7 ⚠️ por
       código, C8 ✅, C9 ✅ rollback validado (Spec 66 Fase A). ✅/⚠️ conforme tabela
- [x] 15. **Runbook `deploy/edge.md`**: setup, regras, troubleshooting, ranges de
       IP, **rollback (proxy OFF)** — existe e inclui a seção da zona
       `foxcash.example.com` (2026-08-17). ✅
- [x] 16. `mvn test` sem regressão + E2E sem regressão (Turnstile fail-open —
       auth do CI não exige token; feature off por default em testes). ✅
- [x] 17. Roadmap → `audited` (10/10 critérios: 8 verificados + 2 deferidos
       documentados — C6 PSP real na Fase 2 Pagar.me, C9 rollback na Spec 66
       Fase A) + commit. ✅

## §Auditoria (2026-08-17 — re-verificação ao vivo)

**10 critérios do requirements; 8 verificados com evidência, 2 deferidos
documentados; 0 achados abertos.** Re-verificação ao vivo em `api.foxcash.example.com`
(domínio real — rebrand Spec 140):

1. **Domínio atrás do Cloudflare** — `curl -I https://api.foxcash.example.com/` →
   **200**, `CF-Ray`, HSTS, TLS válido (Full strict). ✅
2. **DDoS absorvido no edge** — regra `ddos_l7` ativa (default Free); flood
   30/s×4s (2026-08-10): ~20 chegam à app, resto **429 edge**; soak completo
   recomendado em janela de baixo tráfego. ⚠️ parcial
3. **WAF bloqueia payload** — SQLi na query de `/merchant-auth/login` → **403**
   na borda (2026-08-17). Body não inspecionado no Free → camada app
   `RequestBodyInspectionFilter` (403 `body_inspection_failed`). ⚠️ body
4. **Edge rate limit** — burst em `/api/v1/merchants` → **429 edge**
   (`Server: cloudflare` + `cf-ray`, 2026-08-17). ✅
5. **Turnstile** — login sem token → **403 `turnstile_failed`** (2026-08-17);
   UI com widget passa (secret validada); fail-open cobre CF down. ✅
6. **Webhook do PSP não quebra** — regra skip + fora do RL aplicadas;
   **teste com assinatura REAL deferido** (Fase 2 Pagar.me). ⏳ deferido
7. **IP real no app** — `ClientIpResolver` resolve `CF-Connecting-IP`
   (por código); evidência de log pendente. ⚠️ por código
8. **Contrato de API inalterado** — 403/429/400 em JSON `ErrorResponse` do
   projeto; edge só adiciona headers `cf-*`. ✅
9. **Rollback testado** — validado 2026-08-10 (Spec 66 Fase A): proxy OFF →
   HTTPS direto 200 via Railway (cert Let's Encrypt) → proxy ON restaurado. ✅
10. **Sem regressão** — `mvn test` verde; E2E sem token (fail-open). ✅

**Correções desta auditoria (docs/código):**
- `operator-bot/src/{index,commands}.ts` + `deploy/operator-bot.md`:
  domínio `api.foxcash.example.com` (parqueado) → **`api.foxcash.example.com`** (zona real)
  no default do `FOXCASH_BASE_URL`/`RAILWAY_SERVICE_FOXCASH_URL`; `CF_ZONE_ID`
  → zona `foxcash.example.com` (`<CF_ZONE_ID>`).
- `deploy/edge.md`: o corpo (Fase A–D) documenta a zona ANTERIOR (`foxcash.example.com`,
  histórico); a seção "Zona foxcash.example.com (2026-08-17)" (final do arquivo) é
  a fonte autoritativa da zona real — sem alteração no corpo histórico.
- `task.md` estava desmarcado (stale) — refletido o estado real.

Fim do ciclo SDD (roadmap/requirements/design/task `audited`).
