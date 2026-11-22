# Edge/WAF e bot mitigation — Cloudflare na frente do Railway (Spec 63)

Runbook de configuração, verificação e rollback do Cloudflare como proxy da API
pública do FoxCash. Decisão de arquitetura: **ADR-014** (Cloudflare como edge).

> **Zona nova `foxcash.example.com` — configurada via API em 2026-08-17** (rebrand
> Spec 140 / Fase 6). Ver §Zona foxcash.example.com (2026-08-17) no fim deste arquivo.

> **Estado aplicado em 2026-08-10 (verificado ao vivo):**
>
> | Item | Estado |
> |---|---|
> | Domínio público | **`api.foxcash.example.com`** (CNAME → `foxcash-production.up.railway.app`, proxy ON) |
> | SSL mode | **Full (strict)** |
> | WAF custom | 4 regras (exceção webhook PSP, SQLi/XSS, path traversal, content-type) |
> | Inspeção de corpo (app) | `RequestBodyInspectionFilter` — SQLi/XSS/path traversal no body de POST/PUT/PATCH → 403 `body_inspection_failed` antes da auth (cobre o que o WAF Free não inspeciona) |
> | Edge rate limit | 1 regra consolidada 50 req/10s/IP (plano Free) |
> | Bot Fight Mode | **ON** (`fight_mode: true`) |
> | Turnstile | Widget "FoxCash" ativo em produção (`FOXCASH_TURNSTILE_ENABLED=true`) |
> | Verificação | critérios 1, 3 (query), 4, 5, 8 verificados; 2 e 7 parcial; 6 e 9 com achados (ver §Fase D) |

## Topologia

```
Cliente ──HTTPS──► Cloudflare (proxy ON: WAF/DDoS/edge rate limit/TLS/bot)
                       │  TLS Full (strict) — origina para o domínio do Railway
                       ▼
              Edge do Railway (origin: *.up.railway.app — TLS gerenciado)
                       ▼
              Backend FoxCash (app)  — inalterado

```

> **Camada app (complementa o edge):** além do Cloudflare, o backend roda o
> `RequestBodyInspectionFilter` (Spec 63 camada app) — inspeciona o RAW body de
> POST/PUT/PATCH **antes** da auth e responde 403 `body_inspection_failed` em
> payload de ataque (SQLi/XSS/path traversal), com alerta
> `BODY_INSPECTION_BLOCKED` no outbox (Spec 64). Cobre o que o WAF do
> Cloudflare Free **não** inspeciona (`http.request.body.raw` exige WAF
> Advanced). Padrões calibrados por combinação (ver §Fase B.5).

- **Domínio público:** `api.foxcash.example.com` → CNAME `foxcash-production.up.railway.app`
  (record id `<DNS_RECORD_ID>`, zona `<CF_ZONE_ID>`).
- **Origin:** o Cloudflare conecta no domínio do Railway (`up.railway.app`, cert
  válido do Railway) — **sem cert extra no app**; o `railway.json`/healthcheck e o
  endereço interno não mudam.
- **O edge reduz, o app controla:** o rate limit distribuído da app (Specs
  43/58/59 — Redis, por chave/IP/rota) permanece **autoritativo**; o edge é a
  primeira camada (absorve volume antes de gastar CPU/DB/threads).

## Pré-requisitos

| Item | Ação |
|---|---|
| Domínio próprio (`foxcash.example.com`) | Zona no Cloudflare (nameservers do Cloudflare) |
| Conta Cloudflare (plano Free) | Zona ativa; **limites do Free:** 5 regras WAF custom, 1 regra de rate limit (período só 10s), sem `matches` (regex) em WAF, sem `http.request.body.raw`/`body.len` (WAF Advanced) |
| API Token / Global Key | Global API Key (ou token com `Zone:DNS:Edit`, `Zone:WAF:Edit`, `Zone:Rate Limiting:Edit`, `Zone:SSL:Edit`, `Account:Turnstile:Edit`) |

> **Segurança:** o token/secret do Cloudflare **nunca** vai para o repositório.
> Use env var da sessão (ex.: `export CF_API_TOKEN=...`). A Global API Key exposta
> em chat deve ser **rotacionada** após o uso.

## Fase A — DNS / TLS / edge

### 1. Zona + registro DNS

```bash
# Criar/verificar CNAME api → foxcash-production.up.railway.app com proxy ON (proxied=true)
curl -X POST "https://api.cloudflare.com/client/v4/zones/$ZONE_ID/dns_records" \
  -H "Authorization: Bearer $CF_API_TOKEN" -H "Content-Type: application/json" \
  -d '{"type":"CNAME","name":"api","content":"foxcash-production.up.railway.app","proxied":true}'
```

**Status (2026-08-10):** registro existente, `proxied=true` ✅. `dig @1.1.1.1
api.foxcash.example.com` → IPs do Cloudflare (104.21.x / 172.67.x).

### 2. SSL mode = Full (strict)

```bash
curl -X PATCH "https://api.cloudflare.com/client/v4/zones/$ZONE_ID/settings/ssl" \
  -H "Authorization: Bearer $CF_API_TOKEN" -H "Content-Type: application/json" \
  -d '{"value":"strict"}'
```

**Status (2026-08-10):** aplicado ✅ (`value: strict`). Verificação:
`curl -I https://api.foxcash.example.com` → **200**, `cf-ray` presente, TLS válido.

### 3. `trust-proxy` / IP real (critério 7)

Com o Cloudflare no meio, o app vê o IP do cliente via `CF-Connecting-IP` (o
código já resolve isso — `ClientIpResolver`, Spec 63). O rate limit da app e os
logs usam o IP real do cliente (não o do edge). `FOXCASH_TRUST_PROXY=true` já confia
em `CF-Connecting-IP`/`X-Forwarded-For`.

**Ranges de IP do Cloudflare:** https://www.cloudflare.com/ips/ — atualizar
conforme o CF publicar (para `TrustedProxies` do Spring, se o Railway reescrever
o XFF).

**Verificação:** request com `CF-Connecting-IP: <ip-teste>` → o log do app e o
bucket do rate limit resolvem `<ip-teste>` (pendente de evidência no log — feito
por inspeção de código, que resolve `CF-Connecting-IP` primeiro).

## Fase B — WAF e edge rate limit

### 4. DDoS protection

Ativo por default no plano Free (proteção L3/L4/L7) ✅ (ruleset `ddos_l7` ativo).

### 5. WAF (ruleset gerenciado + regras customizadas)

**Gerenciado:** "Cloudflare Managed Free Ruleset" ativo ✅.

**Custom rules aplicadas (2026-08-10) — 4 regras** (limite Free = 5):

| # | Expressão | Ação | Verificação |
|---|---|---|---|
| 1 | `(http.request.uri.path contains "/webhooks/pix") or (http.request.uri.path contains "/webhooks/payouts")` | skip (`ruleset: "current"`) | exceção PSP — reentrega nunca sacrificada |
| 2 | SQLi / Code Injection / XSS (query string) — `union`, `select`, `<script>`, `../`, `eval`, `base64`, `system`, etc. | block | SQLi em query de `/merchant-auth/login` → **403** |
| 3 | Path traversal — `/etc/`, `.env`, `.git/`, `wp-admin`, `.sql`, etc. | block | `/api/v1/.env` → **403** |
| 4 | `(uri contains "/api/v1/merchants" or uri contains "/merchant-auth") and not any(content-type contains "application/json")` | block | POST `/api/v1/merchants` com `text/plain` → **403** |

> **Limitações Free (documentadas):**
> - Inspeção de **body** (`http.request.body.raw`/`body.len`) → exige **WAF
>   Advanced**; a regra "body > 16 KB" do design **não foi aplicada** (campo
>   indisponível). SQLi no body chega à app (rejeitada por validação de
>   credencial, ex.: **401**), mas não é bloqueada na borda.
> - Operador `matches` (regex) em expressão → exige **Business**; a exceção do
>   webhook usa `contains` no lugar do regex `^/webhooks/(pix|payouts)$`.

### 6. Edge rate limit (Cloudflare Rate Limiting Rules)

**Plano Free = 1 regra** (período restrito a 10s). Regra **consolidada**
aplicada (2026-08-10) — substitui a regra antiga que cobria só `/pix/cob`:

| Rota | Teto (edge) | App (autoritativa) |
|---|---|---|
| `POST /api/v1/merchants`, `POST /merchant-auth/*`, `/api/v1/foxcash/pix/cob`, `/portal/charges` | **50 req/10s/IP** (= 300/min; `ip.src` + `cf.colo.id`, block, timeout 10s) | merchants 20/min; auth 120/min; transacional 300/min |

- **Webhooks ficam FORA da regra** (reentrega do PSP não cortada; a app limita a
  120/min — Spec 59).
- Para tetos **por rota** (tabela do design: merchants 60/min, auth 120/min, cob
  600/min) → exigiria **plano Pro** (múltiplas regras).
- **Verificação (2026-08-10):** flood de 30 req/s por ~4s em
  `POST /api/v1/merchants` → ~20 requests chegaram à app (400, corpo inválido) e
  depois **~100× 429 na borda**; o burst seguinte (dentro do mitigation timeout)
  foi **100% 429**. Criterion 4 ✅.

### 7. Bot Fight Mode

**Status (2026-08-10):** **ON** (`GET /zones/{zone}/bot_management` →
`fight_mode: true`, `enable_js: true`).

> **Comportamento observado:** no plano Free, UA de bot conhecido (Googlebot,
> Semrush) recebe **200** (modo "verificação", não bloqueia por UA sozinho). O
> bloqueio agressivo por UA/score exige Super Bot Fight Mode (plano pago). A
> proteção anti-automação efetiva do portal é o **Turnstile** (Fase C).

### 7.5 Camada app — inspeção de corpo (`RequestBodyInspectionFilter`)

O Cloudflare Free **não inspeciona o body** (`http.request.body.raw` exige WAF
Advanced) — SQLi/XSS no corpo chega à app. Para cobrir esse gap, o backend roda o
**`RequestBodyInspectionFilter`** (Spec 63 camada app): inspeciona o RAW body de
**POST/PUT/PATCH** e responde **403 `body_inspection_failed`** **antes** do rate
limit e da autenticação — o ataque não gasta CPU/DB de auth nem vira login.

- **Padrões calibrados por COMBINAÇÃO** (nunca palavras soltas): SQLi
  (`union select`, `select x from`, `insert into`, `update x set ...=`,
  `delete from`, `drop/alter/create table`, `sleep(`/`benchmark(`,
  `xp_cmdshell`, `information_schema`, `concat(`, `cast(... as ...)`,
  `' OR 1=1 --`, `1=1`, `char(N)`), XSS (`<script`, `javascript:`, `vbscript:`,
  `onerror=`/`onload=`, `<iframe`/`<img`/`<svg`, `document.cookie`,
  `alert(`/`eval(`/`prompt(`, `expression(`, `data:text/html`, `\u003c`,
  `&lt;script`) e path traversal (`../`, `..\`, `%2e%2e%2f`, `/etc/passwd`,
  `c:\windows`, `win.ini`, `file://`).
- **Exclusões (default):** `/webhooks/pix` e `/webhooks/payouts` — a reentrega
  do PSP nunca é sacrificada (a assinatura HMAC da app é o controle).
- **Alerta (Spec 64):** a cada bloqueio o `SecurityAlertPublisher` conta a
  rajada (default 5 em 60s) e publica **`BODY_INSPECTION_BLOCKED`** no outbox
  (payload mascarado: path + padrão + categoria).
- **Config:** `foxcash.security.body-inspection.*` — `enabled`
  (`FOXCASH_BODY_INSPECTION_ENABLED`, default **true**), `max-inspect-bytes` (1 MiB),
  `excluded-paths`. Desligar imediatamente se um falso positivo legítimo
  aparecer (`FOXCASH_BODY_INSPECTION_ENABLED=false`) e afinar o padrão.
- **Verificação (2026-08-10, testes):** SQLi `' OR 1=1 --`, `union select`,
  XSS `<script>`, path traversal `/etc/passwd` → **403 `body_inspection_failed`**;
  corpos legítimos (cobrança/cadastro/login) passam intactos; webhook excluído.
- **Monitoramento:** `ops/monitor-body-inspection.sh` (Railway CLI) — conta
  bloqueios por inspeção (WARN throttled 1/10s por instância) + alertas
  `BODY_INSPECTION_BLOCKED` publicados; breakdown por path/pattern/category;
  `--json` p/ automação; exit 2 = houve bloqueio (cron pode alertar).

### 7.6 Métrica e calibração (Spec 66 — Fase C)

- **Métrica** `foxcash.body_inspection.blocks` (contador por `pattern`/`category`,
  total = soma das séries) exposta no `/actuator/prometheus` (ROLE_ADMIN) —
  cada bloqueio incrementa ANTES do 403. Evidência contínua de tentativas de
  ataque (além do monitor por logs).
- **Cron do monitor (máquina do operador):**
  ```cron
  */15 * * * * /caminho/do/projeto/ops/monitor-body-inspection.sh >> /var/log/foxcash-body-inspection.log 2>&1
  ```
  (exit 2 = houve bloqueio → hook de alerta opcional; mesma janela de evidência
  do `BODY_INSPECTION_BLOCKED`.)
- **Agendado no Windows (operador — 2026-08-20, R5 fechado):** tarefa no
  Agendador de Tarefas `FoxCash Body Inspection Monitor`, a cada 15 min, via
  `ops/cron-body-inspection.cmd` → `ops/cron-body-inspection.sh` (wrapper com
  `RAILWAY_PROJECT`/`SERVICE`/`ENV` explícitos — roda de qualquer diretório, sem
  `railway link`) → `ops/monitor-body-inspection.sh --json --minutes 15`.
  Log em `logs/body-inspection-monitor.log`. Recriar se necessário:
  ```cmd
  schtasks /create /tn "FoxCash Body Inspection Monitor" /sc minute /mo 15 /f ^
    /tr "C:\caminho\do\projeto\ops\cron-body-inspection.cmd"
  schtasks /run /tn "FoxCash Body Inspection Monitor"   :: teste manual
  schtasks /query /tn "FoxCash Body Inspection Monitor" /v /fo LIST   :: ver Último resultado (0 = OK)
  ```
  **OBS Windows:** o login shell do Task Scheduler NÃO resolve caminhos MSYS
  (`/c/...`) — use `C:/...` no `.cmd`; o monitor evita `mktemp` (usa arquivo com
  PID em `$TMPDIR`, com fallback `cygpath`/`<repo>/.tmp`).
- **Calibração (quando ajustar/reverter):**
  1. Coletar evidência: `ops/monitor-body-inspection.sh --json` + métrica
     `foxcash.body_inspection.blocks` + logs (WARN com `X-Request-Id`).
  2. Classificar: ataque real (padrão de bot/flood — cruzar com Cloudflare
     analytics e o alerta `BODY_INSPECTION_BLOCKED`) vs **falso positivo**
     legítimo (campo de texto livre com combinação SQL em prosa).
  3. Falso positivo → afinar o padrão específico (refinar/remover o regex no
     `RequestBodyInspectionFilter.PATTERNS`) **ou** reversão imediata
     `FOXCASH_BODY_INSPECTION_ENABLED=false` no Railway (sem redeploy), e registrar
     em `management/lessons.md`.
  4. Ataque real → bloquear IP/ASN no WAF (Fase B §5) + usar o alerta
     `BODY_INSPECTION_BLOCKED` (Spec 64; limiar **global** com
     `FOXCASH_SECURITY_ALERT_STORE=redis` — Spec 66 Fase B).

## Fase C — Turnstile no portal (bot mitigation)

### 8. Widget (site key + secret key)

Widget **"FoxCash"** (criado 2026-08-09, modo **managed**, domínios
`api.foxcash.example.com` + `foxcash.example.com`):

| Chave | Valor |
|---|---|
| Site key (pública — vai no frontend) | `<TURNSTILE_SITE_KEY>` |
| Secret key (privada — server-side) | `<TURNSTILE_SECRET_KEY>` |

### 9. Frontend (portal/ — Spec 46)

Código já implementado (commit `7f305fb`): script do Turnstile + campo token nos
forms de **login e cadastro**. O `LoginPage.tsx` busca a site key em
`/api/v1/portal/turnstile-config` e monta o widget (invisible/managed) — sem site
key o widget não renderiza (feature off).

### 10. Backend (validação server-side)

Código já implementado: `TurnstileVerifier` (siteverify, timeout 5s,
**fail-open** em rede/5xx com WARN throttled; **HTTP 4xx do siteverify → rejeita**
`turnstile_failed`) + handler `turnstile_failed` (403) + properties
`foxcash.security.turnstile.*` (default **off**). Aplica nos endpoints
`POST /api/v1/merchant-auth/login` e `POST /api/v1/merchants`.

### 11. Aplicado em produção (Railway) — 2026-08-10

| Env var | Valor |
|---|---|
| `FOXCASH_TURNSTILE_ENABLED` | `true` |
| `FOXCASH_TURNSTILE_SITE_KEY` | `<TURNSTILE_SITE_KEY>` |
| `FOXCASH_TURNSTILE_SECRET_KEY` | `<TURNSTILE_SECRET_KEY>` |

**Verificação (2026-08-10, após deploy):**
- `curl` login **sem token** → **403** `{"code":"turnstile_failed",...}` ✅
  (antes: 401 `unauthorized`).
- `siteverify` com a secret real + token inválido → `success:false,
  error-codes:["invalid-input-response"]` (secret **válida** — login legítimo com
  widget resolvido passará; `invalid-input-secret` indicaria secret errada).
- `/api/v1/portal/turnstile-config` → `{"siteKey":"<TURNSTILE_SITE_KEY>"}`.

> **Exceção de benchmark (Spec 62):** o loadgen cria lojistas via
> `POST /api/v1/merchants` — durante F8/F9 futuras, desligar
> `FOXCASH_TURNSTILE_ENABLED=false` **e** relaxar/desligar a regra de edge rate limit
> (50/10s) para o IP do gerador; restaurar após.

## Fase D — Verificação (critérios 1-9) — resultados de 2026-08-10

Contra `https://api.foxcash.example.com`, rate limit da app em tetos normais:

| # | Cenário | Esperado | Resultado |
|---|---|---|---|
| 1 | `curl -I https://api.foxcash.example.com/` | 200, `cf-ray`, TLS válido | ✅ **200**, `CF-Ray`, HSTS |
| 2 | Flood 100 req/s × 30s | 429/block do edge; app sem pico | ⚠️ **parcial** — flood 30/s×4s: ~20 chegam (400), resto **429 edge**; soak completo recomendado em janela de baixo tráfego |
| 3 | SQLi `' OR 1=1 --` em `/merchant-auth/login` | **403** WAF, sem `LOGIN_FAILED` | ⚠️ **parcial** — na **query string** → **403** ✅; no **body** → **401** (chegou à app; edge Free não inspeciona body) |
| 4 | 30 req/s em `POST /api/v1/merchants` | **429** na borda | ✅ **429 edge** (cf-ray; ~20 só chegaram à app antes do corte) |
| 5 | curl sem token Turnstile no login/cadastro | **403 `turnstile_failed`** | ✅ **403 `turnstile_failed`**; UI real com widget → login normal (secret validada) |
| 6 | Webhook `POST /webhooks/pix` assinatura válida | **204** | ⏳ **não executado** (exige chave real do PSP p/ assinar; regra skip do WAF + webhooks fora do RL aplicadas) |
| 7 | `CF-Connecting-IP` → app loga IP real | IP real no log/rate limit | ⚠️ **por código** (`ClientIpResolver` resolve `CF-Connecting-IP`; evidência de log pendente) |
| 8 | Respostas mantêm JSON `ErrorResponse` | body inalterado (só headers `cf-*`) | ✅ observado (403/400/429 em JSON do projeto; edge só adiciona headers `cf-*`) |
| 9 | **Rollback**: proxy OFF | serviço volta sem Cloudflare | ✅ **VALIDADO (2026-08-10, Spec 66 Fase A)** — CNAME → `<custom-endpoint>.up.railway.app` + TXT `_railway-verify` (Verified yes) → flip proxy OFF → Railway emitiu o cert (`CN=api.foxcash.example.com`, Let's Encrypt) → **HTTPS direto 200** (`Server: railway-hikari`, sem `CF-Ray`, health UP) → proxy ON restaurado (200 via Cloudflare, `CF-Ray` presente). Procedimento completo em §Rollback |

## Rollback

Toda mudança de edge é **config (DNS/proxy)**, não código — mas **proxy OFF
sozinho NÃO restaura HTTPS** (achado original 2026-08-10, corrigido pela
Spec 66 Fase A):

1. **Proxy OFF (parcial):** Dashboard → DNS → CNAME `api` → "DNS only" (ou API
   `proxied:false`). O DNS passa a apontar para o Railway, **mas o TLS direto
   falha** (handshake reset) porque o CNAME aponta para o domínio default
   (`foxcash-production.up.railway.app`) e o Railway não serve o cert de
   `api.foxcash.example.com` nesse IP.
2. **Rollback completo (Spec 66 — Fase A; VALIDADO 2026-08-10):** o custom domain
   `api.foxcash.example.com` está registrado e ACTIVE no Railway (`railway domain` → Type
   `custom`, id `<DOMAIN_ID>`). O Railway instrui um **endpoint de custom domain
   distinto** (`<custom-endpoint>.up.railway.app` — ver `railway domain status … --json`,
   campo `dnsRecords[].requiredValue`), que é o CNAME correto:
   a. **CNAME**: `api` → `<custom-endpoint>.up.railway.app` (o antigo
      `foxcash-production.up.railway.app` NÃO serve o cert do domínio custom).
   b. **TXT de verificação**: `_railway-verify.api` →
      `railway-verify=<VERIFY_TOKEN>` (criado via Cloudflare API 2026-08-10; sem ele o
      Railway fica `Verified: no` e o cert `VALIDATING_OWNERSHIP`). Com o TXT,
      `Verified: yes`.
   c. **Emissão do cert exige o CNAME visível**: com proxy ON o Cloudflare esconde
      o CNAME do DNS público e o Railway fica `VALIDATING_OWNERSHIP` sem emitir —
      o flip proxy OFF (DNS only) expõe o CNAME e o Railway emite o cert do domínio
      (Let's Encrypt) em ~3–5 min (`CERTIFICATE_STATUS_TYPE_VALID`).
   d. **testar (VALIDADO 2026-08-10)**: proxy OFF → `curl -I https://api.foxcash.example.com`
      respondeu **200 direto** (`Server: railway-hikari`, **sem `CF-Ray`**, health
      `UP`, TLS `CN=api.foxcash.example.com` verify 0) → **proxy ON restaurado** (DNS →
      Cloudflare 172.67.x/104.21.x, `CF-Ray` presente, 200).
   e. **Runbook de rollback em produção (ordem)**: 1) garantir TXT `_railway-verify`
      + CNAME → `<custom-endpoint>.up.railway.app`; 2) proxy OFF (expõe o CNAME, Railway
      emite o cert — aguardar `VALID`); 3) validar HTTPS direto 200; 4) manter proxy
      OFF (rollback completo) **ou** restaurar proxy ON (edge de volta).
   Alternativa (sem flip): manter proxy ON e usar
   `https://foxcash-production.up.railway.app` para acesso direto.
3. **Zona inteira:** remover a zona no Cloudflare e apontar os nameservers de
   volta ao registrar → DNS direto (mesma limitação de cert acima).
4. **Turnstile:** `FOXCASH_TURNSTILE_ENABLED=false` no Railway (rollback do bot
   mitigation sem tocar no edge) — testado e reversível.

## Troubleshooting

| Sintoma | Causa provável | Ação |
|---|---|---|
| `ERR_SSL_VERSION_OR_CIPHER_MISMATCH` no browser | SSL mode ≠ Full (strict) ou cert do origin | Dashboard → SSL/TLS → Full (strict); validar cert do Railway |
| Rate limit da app vê IP do Cloudflare (1.1.1.1) | `FOXCASH_TRUST_PROXY` off ou XFF reescrito | ligar `FOXCASH_TRUST_PROXY=true`; conferir `CF-Connecting-IP` (código resolve); ranges do CF no `TrustedProxies` |
| Webhook do PSP caindo (429/403) | regra de rate limit/WAF pegando o PSP | webhooks fora da regra de RL + regra 1 (skip) do WAF; conferir reentrega |
| Login legítimo bloqueado com `turnstile_failed` | secret errado ou site key ≠ domínio | conferir `FOXCASH_TURNSTILE_SECRET_KEY` (siteverify com token inválido: `invalid-input-secret` = secret errada); widget registrado para `api.foxcash.example.com` |
| Turnstile down derrubando login | — (não deve acontecer) | fail-open cobre rede/5xx; conferir WARN throttled no log |
| Regra de WAF/RL não aceita (20127/50001/20155) | limite/permissão do plano Free | ver §Notas (limites Free); `matches`/`body.raw`/período 60s exigem plano pago |

## Notas

- **Plano Free — limites aplicáveis (2026-08-10):** 5 regras WAF custom (usadas 4);
  1 regra de rate limit (período só 10s); sem `matches` (regex) em expressões;
  sem `http.request.body.raw`/`body.len` (inspeção de body = WAF Advanced);
  Bot Fight Mode em modo "verificação" (não bloqueia por UA sozinho). Upgrade
  (Pro/WAF Advanced) só com evidência de ataque real — regra de ouro da Spec 62.
- **Filosofia:** o edge **nunca** substitui um controle de integridade/segurança
  da app; toda regra de bloqueio tem fail-open ou exceção documentada
  (disponibilidade primeiro — ADR-010).
- Ranges de IP do Cloudflare: https://www.cloudflare.com/ips/

---

## Zona foxcash.example.com (2026-08-17) — rebrand Spec 140 / Fase 6

Zona nova registrada no Cloudflare pelo usuário para o rebrand para FoxCash.
Configurada via API com 4 tokens escopados (um por permissão):

| Token (label do usuário) | Escopo confirmado na API |
|---|---|
| `zone dns edit` (`<REDACTED_TOKEN>`) | `Zone:DNS:Edit` → DNS records |
| `zone zonewaf edit` (`<REDACTED_TOKEN>`) | `Zone:Zone WAF:Edit` → WAF ruleset + rate limit |
| `ssl` (`<REDACTED_TOKEN>`) | `Zone:SSL and Certificates` → certificados (`/ssl/*`) — **NÃO** cobre `/settings/ssl` |
| `turnstile` (`<REDACTED_TOKEN>`) | `Account:Turnstile` → widget |

### Estado aplicado (verificado ao vivo 2026-08-17)

| Item | Estado |
|---|---|
| Zona | `foxcash.example.com` (id `<CF_ZONE_ID>`), ativa |
| Railway custom domain | `api.foxcash.example.com` (id `<DOMAIN_ID_API>`) — **Verified: yes, cert VALID** |
| Railway custom domain (apex) | `foxcash.example.com` (id `<DOMAIN_ID_APEX>`) — **Verified: yes, cert VALID (Let's Encrypt, 2026-08-17)**; dance proxy OFF→cert→ON aplicado; DNS: CNAME `@` → `<apex-endpoint>.up.railway.app` (proxied) + TXT `_railway-verify` |
| Env vars (Railway, 2026-08-17) | `FOXCASH_BOTS_WEBHOOK_BASE_URL=https://foxcash.example.com` + `FOXCASH_PORTAL_BASE_URL=https://foxcash.example.com` — **corrige o bug**: sem a webhook base, os bots Telegram registravam webhook p/ `localhost:8080` |
| DNS | CNAME `api` → `<api-endpoint>.up.railway.app` (proxied=true) + TXT `_railway-verify.api` → `railway-verify=<VERIFY_TOKEN>` (proxied=false) |
| WAF custom | 4 regras (espelho da zona antiga, Spec 63): exceção webhook PSP, SQLi/XSS query, path traversal, content-type JSON |
| Edge rate limit | 1 regra 50 req/10s/IP (`ip.src`+`cf.colo.id`, block, timeout 10s) em cadastro/auth/cob/charges — **path rebrand: `/api/v1/foxcash/pix/cob`** |
| Turnstile | Widget `<TURNSTILE_SITE_KEY>` atualizado: domínios agora incluem `api.foxcash.example.com` + `foxcash.example.com` |
| SSL mode | **Full (strict) — aplicado pelo usuário no dashboard (2026-08-17)** ✅ (os 4 tokens não cobrem `/settings/ssl`; passo manual) |

### Passos manuais restantes (usuário)

1. ~~**SSL/TLS → Overview → SSL mode = `Full (strict)`**~~ — ✅ **feito (2026-08-17)**.
2. Verificar webhooks do PSP e do bot apontando para o domínio novo (rebrand
   Fase 6): `https://foxcash.example.com/webhooks/...` (apex = domínio principal;
   `api.foxcash.example.com` continua ativo como alias). O endpoint `/webhooks/pix`
   já foi verificado ao vivo (401 em assinatura inválida — assinatura ativa).
3. ~~**Trocar env vars no Railway p/ `FOXCASH_*`**~~ — ✅ **feito (2026-08-17)**:
   `FOXCASH_BOTS_WEBHOOK_BASE_URL` + `FOXCASH_PORTAL_BASE_URL` →
   `https://foxcash.example.com` (Turnstile já estava com `FOXCASH_TURNSTILE_*`).

> **Segurança:** os 4 tokens foram usados na sessão e NÃO são commitados. O
> `operator-bot/.env` (gitignored) recebeu o token WAF + `CF_ZONE_ID` da zona nova
> para o `!bloquear <ip>`; rotacionar se expostos.
