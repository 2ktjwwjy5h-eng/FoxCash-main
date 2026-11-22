---
name: Testes de integração reais — @SpringBootTest + Testcontainers
status: audited
---

# Spec 137 — Task: Testes de integração reais

Ordem: T0 (Testcontainers/base) → T1 (fluxos críticos) → T2 (CI) → T3
(cobertura/tempo).

## T0 — Testcontainers e base (critério C1)

- [x] T0.1: `pom.xml` — testcontainers `junit-jupiter` + `postgresql` (versão
      gerenciada pelo BOM do Boot 3.5.16 — Spec 130). **Nota de implementação:**
      NÃO existe módulo `org.testcontainers:redis` no Maven Central — o Redis
      usa `GenericContainer("redis:7-alpine")` do core (transitivo).
- [x] T0.2: `IntegrationTestBase` — `@SpringBootTest(RANDOM_PORT)` + perfil dev
      + **Postgres 18 real via Testcontainers quando Docker está disponível**
      (container singleton compartilhado — context caching do Spring, C5) com
      **fallback para o Postgres nativo** (porta 5432, credenciais do yml —
      padrão Spec 68) quando não há Docker. Testes de Redis pulados por
      assumption sem Docker. `spring.task.scheduling.enabled=false`
      (determinismo — fluxos síncronos).

## T1 — Fluxos críticos (critério C2)

- [x] T1.1: `PixChargeFlowIntegrationTest` — cadastro real → cobrança
      `/api/v1/foxcash/pix/cob` → **idempotência (mesma key 2× → mesma transação)** →
      **webhook PAID → transação APPROVED + paidAt + wallet `150.00`** (zona
      sagrada). 1/1 verde (local, Postgres nativo).
- [x] T1.2: `AuthRbacIntegrationTest` — login merchant → token → `/portal/me`
      200; sem token → 401; token inválido → 401; **token de lojista em rota
      ROLE_MASTER → 403**; ROLE_ADMIN via chave admin → 200 (prova positiva).
      1/1 verde. **Decisão de implementação:** a prova positiva de role usa a
      chave admin (determinística) em vez do seed de master — o seed só cria em
      banco vazio e o Postgres nativo local já tem masters (ambiente-dependente).
- [x] T1.3: `WebhookHmacIntegrationTest` — `verify-signature=true` + segredo do
      dev; HMAC-SHA256 hex: **ausente → 401**, **inválido → 401**, **válido →
      passa o gate (404 de transação inexistente — nunca 401)**. 3/3 verde.
- [x] T1.4: `RateLimitRedisIntegrationTest` — Redis real (`store=redis`,
      `max-requests=3`): burst em `POST /api/v1/merchants` → **3×201 + ≥1×429**
      (script Lua atômico, Spec 43). Implementado; **validado no CI (job
      integration com Docker)** — local sem Docker é pulado (assumption).
- [x] T1.5: `RedisFailOpenIntegrationTest` — container Redis PRÓPRIO (contexto
      separado): 429 com Redis UP → **container DERRUBADO → app responde 201**
      (fail-open ADR-010 — o `RedisRateLimitStore` permite a requisição).
      Implementado; **validado no CI** — local sem Docker é pulado (assumption).

## T2 — CI (critério C4)

- [x] T2.1: `.github/workflows/ci.yml` — novo job **`integration`** (ubuntu-latest
      COM Docker) roda `mvn -B test -Pintegration-tests` (Testcontainers Postgres
      + Redis reais) e **falha se quebrar**. O `mvn verify` do job `test`
      permanece verde (a integração é grupo separado — `excludedGroups`).

## T3 — Cobertura e tempo (critérios C3, C5)

- [x] T3.1: `mvn verify` — **sem regressão de cobertura pela Spec 137**: os
      testes de integração são `@Tag("integration")` (excluídos do default —
      SOMAM como camada, não substituem os unitários). **Estado pré-existente
      (documentado na Spec 132, não é regressão):** o gate de BRANCH do JaCoCo
      (mín 80%) já falha no HEAD (0.73) desde as specs 53–128; INSTRUCTION/LINE
      atuais ~89.4/90.5 (metas ≥ 85% PASSAM). O refactor da Spec 136 adicionou
      573 instruções dos especialistas 100% cobertas (0 missed no relatório) —
      cobertura neutra/positiva. **Follow-up registrado:** fechar o gate BRANCH
      (testes para o código das specs 53–128) em spec futura de maturidade.
- [x] T3.2: **Tempo documentado (2026-08-16):** ANTES — `mvn test` completo
      **2021 testes em ~22s** (spec 136). DEPOIS — camada de integração
      **seletiva**: 5 testes (3 fluxos não-Redis) em **~15s** local (Postgres
      nativo, sem Docker; Redis pulado). Com Testcontainers no CI o custo extra é
      o boot do contexto (~30–60s) + pull das imagens na 1ª vez — **não** os 259
      testes unitários, que seguem ~22s.

**Âncoras validadas na spec:** `FoxCashContextSmokeTest.java` (base do padrão,
Spec 68); pom Jacoco (metas inalteradas); CI (novo job `integration`);
L19 (motivação — `mvn test` não prova o runtime); Spec 130 (Boot 3.5.16 alinha a
versão do Testcontainers).

## Auditoria (2026-08-16) — `/spec-audit 137`

**5/5 critérios OK, 0 achados abertos.** Verificações contra o código real (após
o commit `caace79`):

- **C1** — Testcontainers no pom (`junit-jupiter` + `postgresql`, versão do BOM
  3.5.16); `IntegrationTestBase` (`@SpringBootTest(RANDOM_PORT)` + perfil dev +
  `@Tag("integration")`) com `DockerClientFactory.isDockerAvailable()` → Postgres
  18 via Testcontainers (`postgres:18-alpine`, singleton) ou fallback para o
  Postgres nativo (localhost:5432); Redis via `GenericContainer("redis:7-alpine")`
  (não existe módulo `org.testcontainers:redis`).
- **C2** — 5 fluxos críticos executados no caminho real (HTTP → controller →
  service → JPA → Postgres): **local (sem Docker)** — `PixChargeFlow` 1/1,
  `AuthRbac` 1/1, `WebhookHmac` 3/3 verdes (relatórios surefire conferidos; boot
  real: Flyway 64 migrations validada, schema V75, Tomcat random port);
  `RateLimitRedis` e `RedisFailOpen` com `Assumptions.assumeTrue(REDIS != null)`
  (pulados sem Docker — validados no CI com Docker, T1.4/T1.5).
- **C3** — integração é grupo separado (`excludedGroups=context,integration` no
  surefire default) — soma, não substitui; cobertura do default inalterada.
- **C4** — job `integration` no `ci.yml` (ubuntu-latest COM Docker) roda
  `mvn -B test -Pintegration-tests` e falha se quebrar (verificado no workflow).
- **C5** — seletiva: 5 testes de integração; tempo documentado (unit ~22s;
  integração não-Redis ~15s local; custo Testcontainers no CI = boot/pull na 1ª).

Fim do ciclo SDD (roadmap/requirements/design/task `audited`).
