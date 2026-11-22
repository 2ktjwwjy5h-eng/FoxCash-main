---
name: Rebranding TrevoPay/FoxCash → FoxCash (nome, package, env, métricas, Redis, jobs, docs, frontend)
status: audited
references:
  - "management/vision.md#V100"
  - "management/rebranding-foxcash.md"
  - "management/adr.md"
---

**Auditada (2026-08-17):** critérios de aceite **5/5 OK, 0 achados** — (1)
`mvn compile`+`mvn test` **2038/0/0** e context-tests **7/0/0**; (2) boot dev com
`foxcash:*` OK; (3) `grep -riE "nyxcash|TrevoPay|Nyx"` = 0 no código novo (só
referências deliberadas: banco/role `nyxcash`, defaults de dev); (4) builds
portal+console limpos + E2E **203/0/3** com `FOXCASH_*`; (5) rollback preservado
(branch `rebrand-foxcash` + snapshot) e zero migration de dados. **Fase 6
(Railway) concluída 2026-08-17**: domínio `foxcash.example.com` ativo
(`api.foxcash.example.com`, cert VALID, SSL Full strict), 27 vars `FOXCASH_*` (0
`NYX_*`), deploy `34145e3a` SUCCESS, rede interna `foxcash.railway.internal`,
TRUNCATE + Flyway V76, webhooks nada a fazer, domínio antigo removido
(NXDOMAIN). Detalhes em `deploy/edge.md` §Zona foxcash.example.com e
`management/rebranding-foxcash.md`.

# Spec 140 — Rebranding TrevoPay → FoxCash

## O que entrega

Renomeia **todo** o projeto de TrevoPay/NyxCash para **FoxCash** — identidade
visual, nome de produto, package Java, classes, env vars, métricas, chaves
Redis, nomes de jobs ShedLock, config Spring, CI, docs e frontend/E2E — **sem
quebrar o banco de dados** (nenhuma tabela usa prefixo `nyx_` — verificado) e
com rollback preservado (branch própria + snapshot).

1. **Package Java** — `com.nyxcash` → `com.foxcash` (1.210 arquivos: 949 main +
   261 test): mover diretórios `src/main/java/com/nyxcash` e
   `src/test/java/com/nyxcash` + atualizar `package`/`imports`.
2. **Classes `Nyx*` → `Fox*`** (6 controllers + 5 testes) e
   `TrevoPayApplication` → `FoxCashApplication` (arquivo + classe + todas as
   referências: jacoco no pom, Dockerfile, docs, CI).
3. **Build & identidade** — pom.xml (`groupId com.nyxcash` →
   `com.foxcash`, `artifactId/name trevopay-backend` → `foxcash-backend`),
   Dockerfile (jar `trevopay-backend-0.1.0-SNAPSHOT.jar` →
   `foxcash-backend-0.1.0-SNAPSHOT.jar`), CI (refs `nyxcash` em
   `.github/workflows/ci.yml` e `dependency-check.yml`).
4. **Config & runtime** — prefixo `nyxcash:` → `foxcash:` no
   `application.yml`/`application-dev.yml`/`application-prod.yml` e em todos os
   `@ConfigurationProperties`/`@Value`; env vars `NYX_*` → `FOXCASH_*` (112 no
   código; 27 ativas no Railway — Fase 6 concluída 2026-08-17); logger
   `nyxcash.*` → `foxcash.*` no `logback-spring.xml`; headers públicos de
   webhook `X-NyxCash-*` → `X-FoxCash-*`; domínio dummy de e-mail
   `nao-existe@nyxcash.invalid` → `nao-existe@foxcash.invalid`.
5. **Métricas/Redis/jobs** — métricas Micrometer `nyx_*` → `foxcash_*` (25);
   chaves Redis `nyx:*` → `foxcash:*` (~12); nomes ShedLock `nyxcash-*` →
   `foxcash-*` (12 jobs).
6. **Docs management** (~70 arquivos) e **frontend/E2E** (~46 arquivos:
   console, portal, e2e-tests, assets estáticos embutidos).

**Não muda:** o banco de dados (nome `nyxcash`, role `nyxcash`, tabelas sem
prefixo) — credenciais são infra, não branding. Zero migration de dados.

## Critérios de aceite

1. **Gate compilação+teste:** `mvn compile` e `mvn test` verdes após Fase 1
   (package + classes) e na Fase 7 (suíte completa + context-tests).
2. **Gate boot:** app sobe com `SPRING_PROFILES_ACTIVE=dev` e configuração
   `foxcash:*` binding corretamente (context-tests + boot real).
3. **Audit de nomes:** `grep -riE "nyxcash|TrevoPay|Nyx"` = 0 no código novo
   (exceto histórico git e referências deliberadas em docs de migração).
4. **Frontend/E2E:** `npm run build` limpo (console+portal) e suíte E2E verde
   com as novas env `FOXCASH_*`.
5. **Rollback preservado:** branch `rebrand-foxcash` a partir do main + snapshot
   dos arquivos-chave; sem alteração de schema.

## Dependências

- `management/rebranding-foxcash.md` (plano aprovado 2026-08-16).
- **Ação externa (Fase 6) — concluída 2026-08-17:** domínio `foxcash.example.com`
  registrado pelo usuário + DNS (Cloudflare); webhooks PSP e
  Telegram/Discord verificados — **nada a fazer** (`FOXCASH_PSP_PROVIDER=mock` +
  operator-bot usa gateway do Discord); ver `deploy/edge.md`.

## Fora de escopo

- Migration de dados (inexistente — nenhuma tabela usa prefixo `nyx_`).
