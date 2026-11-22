# Spec 63 — Design: Edge/WAF e bot mitigation (Cloudflare na frente do Railway)

## Contexto

- Topologia atual: `cliente → https://nyxcash-production.up.railway.app (edge
  Railway: TLS, DDoS básico, healthcheck) → app (2 réplicas, porta ${PORT:8080})`.
- O app já lê `X-Forwarded-For` com `NYX_TRUST_PROXY=true` (Spec 18) e o rate
  limit usa o IP resolvido (`RateLimitFilter`/`WebhookSignatureFilter` — Specs
  43/58/59).
- Gaps de borda identificados (análise 2026-08-09, ADR-014): sem WAF, sem bot
  mitigation, sem rate limit na borda; floods/bots chegam inteiros no app.
- A Spec 62 provou (F7/Fase 2B) que o app sustenta ~470-480 vendas/s e que o
  edge do Railway recusa conexões ~1000 concorrentes — mas isso é proteção de
  **capacidade**, não de **ataque** (payload malicioso, automação de conta).

## Decisões

### 1. Topologia (Cloudflare como proxy na frente)

```
cliente → Cloudflare (proxy ON: WAF/DDoS/edge rate limit/TLS) → edge Railway
         → app (2 réplicas, inalterado)
```

- Domínio público: **`api.foxcash.example.com`** (CNAME para
  `nyxcash-production.up.railway.app`, proxy ON). Se o usuário não tiver
  domínio próprio, registrar um barato (ex.: `.dev`/`.app`) — pré-requisito.
- Origin: o Cloudflare conecta no **domínio do Railway** (up.railway.app) com
  TLS Full (strict) — o cert do Railway é válido para `*.up.railway.app`, então
  não precisa de cert extra no origin.
- O `railway.json`/healthcheck e o endereço interno não mudam (o Railway edge
  continua como origin do Cloudflare).

### 2. Configuração Cloudflare (baseline = plano Free, upgrade Pro se evidência)

| Recurso | Plano Free | Config para esta spec |
|---|---|---|
| DDoS protection | ✅ (L3/L4/L7) | default |
| TLS/SSL | ✅ (Full strict) | SSL mode = Full (strict) |
| Bot Fight Mode | ✅ (básico) | ON (modo de verificação com `cf` UA) |
| Turnstile | ✅ ilimitado | site key + secret key (secção 5) |
| WAF (ruleset gerenciado) | ⚠️ limitado (Cloudflare Free WAF) | default + regras customizadas |
| Rate limiting (regras) | ⚠️ 1 regra no free | priorizar `/merchant-auth/*`; se >1 regra → Pro |

Regras **customizadas (WAF)** sugeridas (criar via WAF → Custom Rules / Managed
Rules, avaliar por endpoint):

1. `POST /api/v1/merchants` e `/merchant-auth/*`: bloquear requests sem
   `Content-Type: application/json` e com body > 16 KB (cadastro/login nunca
   enviam corpo grande);
2. `/webhooks/pix` e `/webhooks/payouts`: **exceção de WAF/rate limit** para o
   ASN/IP do Pagar.me (nunca sacrificar a reentrega do PSP — critério 6);
3. Path traversal / SQLi / XSS patterns → challenge/block (ruleset gerenciado).

### 3. Edge rate limiting (Cloudflare Rate Limiting Rules)

Teto por IP (combinar com os tetos da app — Specs 58/59 — que continuam
válidos; o edge usa tetos **mais folgados** para não cortar antes da app):

| Rota | Teto (edge) | Justificativa |
|---|---|---|
| `POST /api/v1/merchants` | 60/min/IP | app: 20/min (Spec 18) — edge 3× folga |
| `POST /merchant-auth/*` | 120/min/IP | app: 120/min autenticado |
| `/api/v1/foxcash/pix/cob` + `/portal/charges` | 600/min/IP | app: 300/min transacional |
| `/webhooks/*` | 600/min/IP | app: 120/min (Spec 59) — folga p/ reentrega PSP |

> O edge NÃO substitui o rate limit da app: é a primeira camada (absorve volume
> antes de gastar CPU/DB). A app continua autoritativa (Redis, por chave/IP/rota).

### 4. Bot mitigation (Turnstile no portal — Spec 46)

- **Frontend (portal/ — React SPA)**: adicionar o script do Turnstile nos
  formulários de **login** e **cadastro** (Spec 46; o portal é servido pelo
  próprio Spring Boot). Widget invisible; token em um campo oculto do form.
- **Backend**: nos endpoints `POST /merchant-auth/login` e
  `POST /api/v1/merchants` (cadastro do portal — e no login do portal se for
  rota própria), validar o token Turnstile via **siteverify**
  (`https://challenges.cloudflare.com/turnstile/v0/siteverify`, `secret` +
  `response`, timeout 5s). Resposta inválida → **400/403 `turnstile_failed`**.
- **Fail-open (disponibilidade primeiro)**: se a API do Turnstile estiver
  inacessível/timeout → **deixar passar** + WARN throttled (mesmo padrão da
  ADR-010/rate limit fail-open). Uma queda do Turnstile **nunca** derruba o
  login do portal.
- **Config**: `foxcash.security.turnstile.site-key` (frontend),
  `foxcash.security.turnstile.secret-key` + `enabled` (default false — ativa
  com env `NYX_TURNSTILE_SECRET_KEY`; se ausente → validação desligada, para não
  quebrar dev/CI/E2E). Secrets nunca em `application.yml` de prod (env var).
- **Exceção de benchmark (loadgen)**: o `capacity-stages.js` (Spec 62) cria
  lojistas via `POST /api/v1/merchants` no setup. Com Turnstile ativo, o
  benchmark precisaria de exceção — procedimento: durante F8/F9 futuras,
  desligar `NYX_TURNSTILE_ENABLED=false` (ou configurar exceção de IP do
  gerador) e restaurar após; documentar no runbook `deploy/edge.md`.

### 5. `trust-proxy` / IP real (critério 7)

- Com o Cloudflare no meio: `cliente → CF → Railway edge → app`. O Cloudflare
  seta `CF-Connecting-IP` (IP real) e faz **append** em `X-Forwarded-For`.
- O app com `NYX_TRUST_PROXY=true` resolve o primeiro IP do `X-Forwarded-For` —
  validar que é o IP do cliente (e não o do Railway/CF). Se o Railway edge
  reescrever o `X-Forwarded-For`, configurar o `TrustedProxies`/`forwarded` do
  Spring para confiar nos prefixos do Cloudflare (documentar os ranges de IP
  do Cloudflare em `deploy/edge.md`).
- Verificação: request com header `CF-Connecting-IP: <ip-teste>` → o log do app
  e o rate limit resolvem `<ip-teste>`.

### 6. Verificação (critérios 1-9) e rollback

- **Ambiente de teste dos ataques**: usar o k6 local (já instalado) e `curl`
  contra `https://api.foxcash.example.com` com rate limit da app **restaurado** (tetos
  normais 20/120/300/120 — o objetivo é provar a borda, não gerar carga).
- Cenários: flood 30s (DDoS/edge RL → 429/browser-challenge), SQLi pattern
  (WAF → 403), bot UA (Bot Fight → 403/block), brute-force no login sem
  Turnstile (→ 400/403 `turnstile_failed`), webhook válido (→ 204).
- **Rollback**: Cloudflare → DNS only (proxy OFF) restaura o acesso direto ao
  edge Railway em minutos (documentar no runbook `deploy/edge.md`).

## Arquivos

| Arquivo | Ação |
|---|---|
| `deploy/edge.md` (novo) | runbook Cloudflare: DNS, TLS Full strict, WAF rules, edge rate limit, Turnstile keys, ranges de IP do CF p/ trust-proxy, verificação (critérios 1-9) e rollback |
| `portal/` (Spec 46) | script Turnstile + campo token nos forms de login/cadastro |
| Backend (auth + cadastro) | validação server-side Turnstile (`TurnstileVerifier` + properties + handler `turnstile_failed`) |
| `src/main/resources/application.yml` / prod | `foxcash.security.turnstile.*` (default desligado; env override) |
| `deploy/railway.md` | nota: domínio público passa a ser via Cloudflare (origin = Railway) |
| `management/specs/63-edge-waf-bot-mitigation/` | documentos da spec |

## Fora de escopo

- Gestão de segredos profissional (Doppler/Vault) — proposta futura (roadmap
  §Fase 17).
- Autenticação adaptativa/step-up — proposta futura.
- Substituir o rate limit da app (edge complementa; app é autoritativa).
- Mudança de contrato de API / de endereço interno do Railway.
