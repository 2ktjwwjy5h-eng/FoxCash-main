# Rebranding TrevoPay → FoxCash — Planejamento (Spec 140 proposta)

> **Status:** `planned` — planejamento aprovado pelo usuário em 2026-08-16;
> execução agendada para a próxima sessão.
> **Decisões do usuário (2026-08-16):**
> 1. Escopo: **tudo, inclusive package Java** (`com.foxcash` → `com.foxcash`)
> 2. Infra: **incluir Railway** (serviço/projeto/domínio/rede + env vars) — exige
>    domínio novo registrado pelo usuário
> 3. Métricas/Redis/jobs: **renomear tudo** (`nyx_*` → `foxcash_*`, `nyx:*` →
>    `foxcash:*`, `nyxcash-*` → `foxcash-*`)

---

## Objetivo

Renomear **todo** o projeto de TrevoPay/FoxCash para **FoxCash**, sem quebrar o
banco de dados (nenhuma tabela usa prefixo `nyx_`) e com rollback preservado.

## O que muda (medido em 2026-08-16)

| Vetor | Quantidade | Exemplo de mudança |
|---|---|---|
| Package Java | **1.210 arquivos** (949 main + 261 test) | `com.foxcash` → `com.foxcash` |
| Classes `Nyx*` | 11 (6 controllers + 5 testes) | `FoxStorefrontController` → `FoxStorefrontController` |
| Classe principal | 1 | `FoxCashApplication` → `FoxCashApplication` |
| pom.xml | 1 | groupId `com.foxcash`, artifactId/name `trevopay-backend` → `foxcash-backend` |
| Dockerfile | 1 | jar `trevopay-backend-0.1.0-SNAPSHOT.jar` → `foxcash-backend-0.1.0-SNAPSHOT.jar` |
| logback-spring.xml | 1 | logger `nyxcash.*` → `foxcash.*` + `AxiomHttpAppender` |
| Env vars | 112 no código / **22 ativas no Railway** | `NYX_*` → `FOXCASH_*` |
| Métricas | 25 | `nyx_http_5xx_total` → `foxcash_http_5xx_total` |
| Redis keys | ~12 | `nyx:crm:events:` → `foxcash:crm:events:` |
| Jobs ShedLock | 12 | `nyxcash-invoice-generation` → `foxcash-invoice-generation` |
| Docs management | 70 arquivos | roadmap, vision, arch, adr, lessons, specs |
| Frontend/E2E | 46 arquivos | console/src, portal/src, e2e-tests |
| CI | 2 workflows | refs `nyxcash` em ci.yml / dependency-check.yml |
| Infra Railway | serviço + projeto + domínio + rede | `api.foxcash.example.com` → novo; `nyxcash.railway.internal` → `foxcash.railway.internal` |

## Boa notícia (verificada)

**Nenhuma tabela do banco tem prefixo `nyx_`** (`chargebacks`,
`merchant_sessions`, `pix_transactions` etc. — 51 tabelas sem prefixo). O
rebrand **não exige migration de dados**. Zero risco para o banco.

## Risco crítico (ação externa do usuário)

A troca do domínio público (Fase 6) quebra webhooks externos até serem
reconfigurados nos painéis de terceiros:
- **Webhooks do PSP** (pagar.me / asaas) apontam para o domínio atual
- **setWebhook do Telegram/Discord** precisa apontar para o novo domínio

## Pendências para a próxima sessão (resolver ANTES de executar)

1. **Domínio novo** (registrado pelo usuário) — ex.: `api.foxcash.com.br`,
   `api.foxcash.app`, etc.
2. **Branch** — proposta: criar `rebrand-foxcash` a partir do main (produção
   intacta até o fim)
3. **Registrar a spec no SDD** — proposta: Spec 140 (rebranding-foxcash) via
   `/spec-plan` + `/spec-new`

## Fases de execução

### Fase 0 — Base
- [ ] Criar branch `rebrand-foxcash` a partir do main
- [ ] Snapshot de rollback dos arquivos-chave
- [ ] Registrar Spec 140 no SDD (requirements/design/task)

### Fase 1 — Package Java (1.210 arquivos)
- [ ] `com.foxcash` → `com.foxcash` (mover diretórios + package/imports)
- [ ] Classes `Nyx*` → `Fox*` (6 controllers + 5 testes)
- [ ] `FoxCashApplication` → `FoxCashApplication`
- [ ] logback-spring.xml: `nyxcash.*` → `foxcash.*`
- [ ] Gate: `mvn compile` + `mvn test` verdes

### Fase 2 — Build & identidade
- [ ] pom.xml: groupId/artifactId/name
- [ ] Dockerfile: nome do jar
- [ ] CI: refs `nyxcash` (obrigatório — package mudou)
- [ ] Gate: build Docker local OK

### Fase 3 — Config, métricas, Redis, jobs
- [ ] application.yml/dev/prod: prefixo `nyxcash:` → `foxcash:` + 112 env vars
- [ ] 25 métricas `nyx_*` → `foxcash_*` (+ docs/observability)
- [ ] Redis keys `nyx:*` → `foxcash:*`
- [ ] Jobs ShedLock `nyxcash-*` → `foxcash-*` (12 jobs)
- [ ] Gate: context-tests + boot OK

### Fase 4 — Docs management (70 arquivos)
- [ ] roadmap.md, vision.md, arch.md, adr.md, lessons.md
- [ ] specs/*/requirements+design+task
- [ ] deploy/*.md, load-tests/k6 runbooks

### Fase 5 — Frontend/E2E (46 arquivos)
- [ ] console/src, portal/src, e2e-tests
- [ ] assets estáticos embutidos (index-*.js)
- [ ] Gate: `npm run build` (console+portal) + E2E

### Fase 6 — Infra Railway (janela de manutenção)
- [x] **Pré**: domínio novo registrado + DNS (**`foxcash.example.com` configurado no Cloudflare em 2026-08-17** — ver `deploy/edge.md` §Zona foxcash.example.com)
- [x] Renomear serviço FoxCash → FoxCash + rede interna — **serviço `FoxCash`** (display + `RAILWAY_SERVICE_NAME`); **endpoint privado `nyxcash` → `foxcash.railway.internal`** (2026-08-17, `railway private-network update foxcash`, ACTIVE); operator-bot atualizado (`FOXCASH_BASE_URL=http://foxcash.railway.internal:8080`, deploy `ce38f5a5`)
- [x] **27 env vars `FOXCASH_*` adicionadas no Railway** (2026-08-17, `--skip-deploys`; código antigo segue lendo `NYX_*` até o deploy do rebrand)
- [x] Trocar domínio público (DNS + TLS) — **DNS/CNAME/TXT + Railway custom domain `api.foxcash.example.com` + cert VALID + SSL Full (strict)** ✅
- [x] **Risco alto**: reconfigurar webhooks PSP + Telegram/Discord — **nada a fazer (verificado 2026-08-17)**: `FOXCASH_PSP_PROVIDER=mock` (sem webhook real de PSP) e operator-bot usa **gateway do Discord** (token); bots de venda Discord/Telegram não estão no Railway
- [x] Deploy do código rebrand + **remover as 27 vars `NYX_*` antigas** + health + smoke — deploy `34145e3a` SUCCESS; 0 `NYX_*` em todos os serviços; health UP; smoke OK (Turnstile ativo)

### Fase 7 — Verificação final
- [ ] `mvn test` completo + context-tests
- [ ] E2E completo
- [ ] Audit: grep `nyxcash|TrevoPay|Nyx` = 0 no código novo (exceto histórico)
- [ ] `/spec-audit` da Spec 140 + commit final

## Estado da produção (antes do rebrand — snapshot 2026-08-16)

- Deploy `94861317` SUCCESS, 2/2 réplicas, health 200, schema V75
- `NYX_SECURITY_ALERT_STORE=redis` ativo (task 10 Spec 66 validada ao vivo)
- Specs 126/127/128 ao vivo; Specs 62/125 audited; `mvn test` 1995/0/0
- Pendente: R5 (cron do monitor — ação do operador, instruções Windows no roadmap)

---

## Mapeamento das env vars do Railway (2026-08-17)

**27 vars** renomeadas `NYX_*`/`NYXCASH_*` → `FOXCASH_*` (1:1, mesmos valores).
Aplicado em **2 fases seguras**:

1. **`FOXCASH_*` ADICIONADAS** (feito — `--skip-deploys`): o código antigo em
   produção (deploy `10b47723`, anterior ao rebrand `719f3c1`) ignora `FOXCASH_*`
   e segue lendo `NYX_*` → **nenhum risco**.
2. **`NYX_*` REMOVIDAS** (PENDENTE — fazer JUNTO com o deploy do código rebrand,
   que lê `FOXCASH_*`; se remover antes, o boot antigo falha por falta de
   `NYX_ADMIN_API_KEY`/`NYX_CRYPTO_SECRET`).

| Antiga (Railway) | Nova (Railway) |
|---|---|
| `NYXCASH_SECURITY_RATE_LIMIT_AUTHENTICATED_MAX_REQUESTS` | `FOXCASH_SECURITY_RATE_LIMIT_AUTHENTICATED_MAX_REQUESTS` |
| `NYXCASH_SECURITY_RATE_LIMIT_MAX_REQUESTS` | `FOXCASH_SECURITY_RATE_LIMIT_MAX_REQUESTS` |
| `NYXCASH_SECURITY_RATE_LIMIT_TRANSACTIONAL_MAX_REQUESTS` | `FOXCASH_SECURITY_RATE_LIMIT_TRANSACTIONAL_MAX_REQUESTS` |
| `NYXCASH_SECURITY_RATE_LIMIT_WEBHOOK_MAX_REQUESTS` | `FOXCASH_SECURITY_RATE_LIMIT_WEBHOOK_MAX_REQUESTS` |
| `NYXCASH_SECURITY_REQUIRE_HTTPS` | `FOXCASH_SECURITY_REQUIRE_HTTPS` |
| `NYX_ADMIN_API_KEY` | `FOXCASH_ADMIN_API_KEY` |
| `NYX_CRYPTO_SECRET` | `FOXCASH_CRYPTO_SECRET` |
| `NYX_DB_POOL_SIZE` | `FOXCASH_DB_POOL_SIZE` |
| `NYX_MASTER_SEED_PASSWORD` | `FOXCASH_MASTER_SEED_PASSWORD` |
| `NYX_MASTER_SEED_USERNAME` | `FOXCASH_MASTER_SEED_USERNAME` |
| `NYX_NOTIFICATION_MAX_BATCH_SIZE` | `FOXCASH_NOTIFICATION_MAX_BATCH_SIZE` |
| `NYX_NOTIFICATION_OPERATOR_WEBHOOK_SECRET` | `FOXCASH_NOTIFICATION_OPERATOR_WEBHOOK_SECRET` |
| `NYX_NOTIFICATION_OPERATOR_WEBHOOK_URL` | `FOXCASH_NOTIFICATION_OPERATOR_WEBHOOK_URL` |
| `NYX_NOTIFICATION_POLL_INTERVAL` | `FOXCASH_NOTIFICATION_POLL_INTERVAL` |
| `NYX_PORTAL_SERVE_FRONTEND` | `FOXCASH_PORTAL_SERVE_FRONTEND` |
| `NYX_PSP_PAYOUT_PROVIDER` | `FOXCASH_PSP_PAYOUT_PROVIDER` |
| `NYX_PSP_PAYOUT_WEBHOOK_SECRET` | `FOXCASH_PSP_PAYOUT_WEBHOOK_SECRET` |
| `NYX_PSP_PROVIDER` | `FOXCASH_PSP_PROVIDER` |
| `NYX_PSP_WEBHOOK_SECRET` | `FOXCASH_PSP_WEBHOOK_SECRET` |
| `NYX_RATE_LIMIT_STORE` | `FOXCASH_RATE_LIMIT_STORE` |
| `NYX_SECURITY_ALERT_STORE` | `FOXCASH_SECURITY_ALERT_STORE` |
| `NYX_TOMCAT_ACCEPT_COUNT` | `FOXCASH_TOMCAT_ACCEPT_COUNT` |
| `NYX_TOMCAT_MAX_CONNECTIONS` | `FOXCASH_TOMCAT_MAX_CONNECTIONS` |
| `NYX_TRUST_PROXY` | `FOXCASH_TRUST_PROXY` |
| `NYX_TURNSTILE_ENABLED` | `FOXCASH_TURNSTILE_ENABLED` |
| `NYX_TURNSTILE_SECRET_KEY` | `FOXCASH_TURNSTILE_SECRET_KEY` |
| `NYX_TURNSTILE_SITE_KEY` | `FOXCASH_TURNSTILE_SITE_KEY` |

**Não mudam:** `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`, `SPRING_DATA_REDIS_*`,
`SPRING_PROFILES_ACTIVE=prod,railway`, `JAVA_*`, `AXIOM_API_TOKEN`, `RAILWAY_*`,
`SERVER_TOMCAT_*`.

**Obrigatórias no boot novo** (sem default no yml): `FOXCASH_ADMIN_API_KEY`,
`FOXCASH_CRYPTO_SECRET`, `FOXCASH_PSP_PROVIDER`, `FOXCASH_PSP_PAYOUT_PROVIDER`,
`FOXCASH_PSP_WEBHOOK_SECRET`, `FOXCASH_PSP_PAYOUT_WEBHOOK_SECRET` — **todas já
adicionadas**. `FOXCASH_SSL_KEYSTORE_PASSWORD` não é exigida (profile `railway`
desliga o keystore). Secrets de pagarme/asaas não são exigidos (provider `mock`).
