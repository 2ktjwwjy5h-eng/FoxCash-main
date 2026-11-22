# Spec 140 — Task: Rebranding TrevoPay → FoxCash

## Fase 0 — Base ✅ (2026-08-16)
- [x] Commit baseline no main (`f76f772`)
- [x] Criar branch `rebrand-foxcash` a partir do main limpo
- [x] Snapshot de rollback dos arquivos-chave (`snap_012azg68`)
- [x] Registrar Spec 140 no SDD (requirements/design/task + roadmap + Visão V100)

## Fase 1 — Package Java ✅ (retomada 2026-08-16)
- [x] `git mv` de `src/main/java/com/nyxcash` → `com/foxcash` e `src/test/java/com/nyxcash` → `com/foxcash`
- [x] `com.nyxcash` → `com.foxcash` em `package`/`imports`/string (1.210 arquivos)
- [x] Classes `Nyx*` → `Fox*` (6 controllers + 5 testes)
- [x] `TrevoPayApplication.java` → `FoxCashApplication.java` (arquivo + classe + referências)
- [x] logback-spring.xml: `nyxcash.*` → `foxcash.*` + `AxiomHttpAppender`
- [x] Gate: `mvn compile` + `mvn test` verdes (**2038/0/0 BUILD SUCCESS**)
- [x] Audit: `grep -rl "com.nyxcash"` = 0 no código

## Fase 2 — Build & identidade ✅
- [x] pom.xml: groupId `com.foxcash`, artifactId/name `foxcash-backend` (+ jacoco excludes)
- [x] Dockerfile: jar `foxcash-backend-0.1.0-SNAPSHOT.jar`
- [x] CI: refs `nyxcash` em `ci.yml` / `dependency-check.yml` (restam só as refs de banco — deliberado)
- [x] Gate: build local OK (`mvn -q -DskipTests package` — validado no `mvn test` completo)

## Fase 3 — Config, métricas, Redis, jobs ✅
- [x] application.yml/dev/prod/railway: prefixo `nyxcash:` → `foxcash:` + env `${NYX_*}` → `${FOXCASH_*}`
- [x] `@ConfigurationProperties`/`@Value` com prefixo `nyxcash.` → `foxcash.` + `NYX_` → `FOXCASH_`
- [x] Métricas `nyx_*` → `foxcash_*` (25, Micrometer) + docs observability
- [x] Redis keys `nyx:*` → `foxcash:*` (~12)
- [x] Jobs ShedLock `nyxcash-*` → `foxcash-*` (12)
- [x] Headers `X-NyxCash-*` → `X-FoxCash-*` + e-mail dummy `@nyxcash.invalid` → `@foxcash.invalid`
- [x] Rotas API bots `/api/v1/nyx/*` → `/api/v1/foxcash/*` + `/webhooks/nyxcash` → `/webhooks/foxcash`
- [x] Gate: context-tests (`mvn test -Pcontext-tests` — **7/0/0**, smoke de boot real com `foxcash:*`) + boot dev OK

## Fase 4 — Docs management (~70) ✅ (retomada 2026-08-16)
- [x] roadmap.md, vision.md, arch.md, adr.md, lessons.md, budget.md, visao-cliente.md
- [x] specs/*/requirements+design+task (refs de contrato/config/classe/rota atualizadas; históricos datados e `.bak.*` preservados)
- [x] ops/*.md, deploy/*.md, load-tests runbooks, README.md (ops/README com refs deliberadas: banco + backup)
- [x] docs/observability (job names `nyxcash-*` → `foxcash-*`) + docs/politicas-uso.md (produto)

## Fase 5 — Frontend/E2E ✅
- [x] console/src, portal/src, operator-bot, bots/core
- [x] e2e-tests (envs `FOXCASH_*`, nomes de produto, headers)
- [x] assets estáticos embutidos (regenerar via `npm run build` — portal 69 módulos + console 67 módulos, tsc limpo; 0 refs antigas em `static/`)
- [x] Gate: E2E — **203 passed / 0 failed / 3 skipped** (app dev com `FOXCASH_*` + roteamento PSP `mock,suitpay`; 2 falhas do 1º run eram ambientais: env de rate limit não exportada ao Playwright + roteamento PSP desligado — corrigidas no re-run)

## Fase 6 — Infra Railway ✅ (concluída 2026-08-17)
- [x] Domínio novo + DNS (ação do usuário) — **`foxcash.example.com` ativo no Cloudflare** (zona `<CF_ZONE_ID>`, DNS CNAME/TXT, SSL Full strict, WAF, rate limit, Turnstile — ver `deploy/edge.md` §Zona foxcash.example.com)
- [x] 27 env vars `FOXCASH_*` **adicionadas** no Railway (2026-08-17, `--skip-deploys`; mapeamento em `management/rebranding-foxcash.md`)
- [x] Backup de segurança: dump **`nyxcash_2026-08-16.dump` (408 MB)** no volume do backup-cron (antes da limpeza)
- [x] **TRUNCATE das 51 tabelas** — 0 linhas restantes; schema + Flyway V75 preservados
- [x] Deploy do código rebrand — deployment **`34145e3a` SUCCESS** (2 réplicas)
- [x] **Remover as 27 vars `NYX_*` antigas** — 0 restantes; 27 `FOXCASH_*` + `RAILWAY_SERVICE_FOXCASH_URL` injetada
- [x] Trocar domínio público (DNS + TLS) — custom domain **`api.foxcash.example.com`** ativo, cert VALID, health **UP** pós-remoção das vars
- [x] Deploy + health + smoke — boot: **`Started FoxCashApplication`**; Flyway aplicou a **V76** do rebrand ("now at version v76"); health **200/UP**; smoke: cadastro de lojista com `turnstile_failed` → Turnstile ativo, confirmando leitura das `FOXCASH_*`
- [x] Validação do banco limpo — **52 tabelas** (nova da V76), **95 linhas** (eram 4.45M), **masters = 1** (master seed recriado — 1ª réplica criou, 2ª reconheceu), Flyway V76 aplicada e validada
- [x] **Renomear serviço + rede interna (Railway)** — serviço já nomeado `FoxCash`; endpoint privado renomeado `nyxcash` → **`foxcash.railway.internal`** (`railway private-network update foxcash`, ACTIVE); operator-bot redeployado com o código rebrand (deploy `ce38f5a5`) + vars `FOXCASH_BASE_URL=http://foxcash.railway.internal:8080`/`FOXCASH_ADMIN_API_KEY` (0 `NYX_*` restantes); log: `[api] baseUrl=http://foxcash.railway.internal:8080 health=UP`
- [x] Reconfigurar webhooks PSP + Telegram/Discord — **nada a fazer (verificado)**: `FOXCASH_PSP_PROVIDER=mock` (sem webhook real de PSP) e o operator-bot usa **gateway do Discord** (token, sem webhook de entrada); bots de venda Discord/Telegram não estão no Railway
- [x] **Remover domínio antigo `api.foxcash.example.com`** (2026-08-17) — custom domain **deletado no Railway** (`railway domain delete`, id `<DOMAIN_ID>`) + CNAME `api` **apagado pelo usuário no dashboard do Cloudflare** (zona `foxcash.example.com`, id `<CF_ZONE_ID>`). Verificado: **NXDOMAIN** + `Could not resolve host`; `api.foxcash.example.com` segue 200/UP

## Fase 7 — Verificação final
- [x] `mvn test` completo (**2038/0/0**) + context-tests (**7/0/0**) verdes
- [x] E2E completo — **203 passed / 0 failed / 3 skipped** (run do audit 2026-08-17, idêntico ao da Fase 5)
- [x] Audit final: `grep -riE "nyxcash|TrevoPay|Nyx"` = 0 no código novo (exceto banco/backup/infra Railway deliberados + histórico)
- [x] `/spec-audit` Spec 140 — critérios **5/5 OK, 0 achados** (gates compile+test/boot ✓, grep nomes ✓, frontend/E2E ✓, rollback ✓) + Fase 6 Railway verificada (deploy `34145e3a` SUCCESS, 0 `NYX_*`, domínio novo ativo, webhooks nada a fazer) — commit final dos docs na branch `rebrand-foxcash`
