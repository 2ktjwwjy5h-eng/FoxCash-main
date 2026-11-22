---
name: Testes de integração reais — @SpringBootTest + Testcontainers
status: audited
---

# Spec 137 — Design: Testes de integração reais (@SpringBootTest + Testcontainers)

## D1 — Conceito

O due diligence apontou a maior fraqueza de testes: **1 único `@SpringBootTest`**
(`FoxCashContextSmokeTest` — smoke de contexto, Spec 68), **0 Mockito** (mocks
manuais `mock(Interface.class)`). Os 92.8% de cobertura provam lógica unitária,
**não** o wiring real (Spring context, yml/env, JPA, Flyway, runtime). O L19
documenta o acidente: `mvn test` verde mas boot quebrado por jar corrompido.
Esta spec adiciona uma **camada de integração real** para os fluxos financeiros
críticos — seletiva (C5), sem substituir os unitários.

## D2 — Testcontainers no projeto (C1)

- `pom.xml`: dependências `org.testcontainers:postgresql` e
  `org.testcontainers:redis` + `org.testcontainers:junit-jupiter` (versão
  alinhada ao Boot 3.5.x — Spec 130).
- **Profile de teste** (`application-test.yml` ou config de teste): sobe
  Postgres + Redis reais em Docker (Testcontainers). **Fallback** para o
  Postgres nativo do runner no CI (padrão da Spec 68 — o CI já instala
  Postgres para o smoke test).
- Base `IntegrationTestBase` (novo): `@SpringBootTest(webEnvironment=RANDOM_PORT)`
  + `@Testcontainers` + context caching para não subir o contexto por teste.

## D3 — Fluxos críticos com integração real (C2)

Mínimo obrigatório — caminho real HTTP → controller → service → JPA →
Postgres/Redis:

| Fluxo | Asserção |
|---|---|
| Criação de cobrança Pix + idempotência | mesma key 2× → 1 transação |
| Webhook PSP PAID | ledger/wallet atualizados (zona sagrada) |
| Autenticação merchant (login → token → acesso) | token válido acessa; inválido 401 |
| RBAC master | 403 sem role |
| Webhook PSP com HMAC | assinatura válida → processa; inválida → 401 |
| Rate limit | 429 no abuso (Redis real) |
| Falha do Redis | fail-open (ADR-010) — Testcontainers derrubado → app responde |

## D4 — Cobertura mantida (C3)

- Metas Jacoco do `verify` (INSTRUCTION/LINE ≥ 85%; atual 92.8%) **inalteradas** —
  os testes de integração **somam** (não substituem) os unitários.
- Verificação: `mvn verify` após a adição — cobertura ≥ 92.8% e suíte completa
  verde.

## D5 — CI roda a camada de integração (C4)

- Workflow `ci.yml`: executa a camada de integração (Testcontainers ou Postgres
  nativo do runner — mesmo padrão da Spec 68) e **falha se quebrar**.
- `mvn verify` completo verde no CI.

## D6 — Seletivo e sem duplicação lenta (C5)

- Testes de integração **seletivos**: fluxos críticos (D3), não todos os 259
  testes. Suíte completa continua em tempo razoável — **documentar tempo
  antes/depois** no task.

## D7 — Arquivos

| Arquivo | Mudança |
|---|---|
| `pom.xml` | testcontainers (postgresql/redis/junit-jupiter) (D2) |
| `src/test/java/.../integration/IntegrationTestBase.java` | novo (D2) |
| `src/test/java/.../integration/PixChargeFlowIntegrationTest.java` | novo (D3) |
| `src/test/java/.../integration/AuthRbacIntegrationTest.java` | novo (D3) |
| `src/test/java/.../integration/WebhookHmacIntegrationTest.java` | novo (D3) |
| `src/test/java/.../integration/RateLimitRedisIntegrationTest.java` | novo (D3) |
| `src/test/java/.../integration/RedisFailOpenIntegrationTest.java` | novo (D3) |
| `.github/workflows/ci.yml` | roda a camada de integração (D5) |

## D8 — Fora de escopo

Cobertura 100% (irreal — 92.8% já é forte); testes E2E de browser (camada
separada existente).

**Âncoras validadas na spec:** `FoxCashContextSmokeTest.java` (1 único
@SpringBootTest, Spec 68, perfil dev + Postgres, Flyway no-op/Hibernate
validate); pom Jacoco (INSTRUCTION/LINE ≥ 85%, atual 92.8%); CI já instala
Postgres para a Spec 68; L19 (motivação); Spec 130 (Boot 3.5.x alinha a versão
do Testcontainers).
