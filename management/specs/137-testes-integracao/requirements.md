---
name: Testes de integração reais — @SpringBootTest + Testcontainers
status: audited
references:
  - "management/roadmap.md#Fase 41"
  - "management/specs/68-smoke-contexto/requirements.md"
  - "management/lessons.md#L19"
---

# Spec 137 — Testes de integração reais: @SpringBootTest + Testcontainers

**Implementada (2026-08-16):** camada de integração real criada — `IntegrationTestBase`
(`@SpringBootTest(RANDOM_PORT)` + Testcontainers Postgres 18 com fallback para Postgres
nativo; Redis via `GenericContainer` — não existe módulo `org.testcontainers:redis`), 5
fluxos críticos (`PixChargeFlow` 1, `AuthRbac` 1, `WebhookHmac` 3, `RateLimitRedis`,
`RedisFailOpen`), job **`integration`** no CI (ubuntu-latest com Docker → Testcontainers
reais; sem Docker os testes de Redis pulam por assumption). Local (sem Docker): 5/5 dos
não-Redis verdes; Redis validado no CI. `mvn verify` verde — Jacoco ≥ 92.8% inalterado
(integração é grupo separado, `excludedGroups=context,integration`). Detalhe no task.md.

## O que entrega

O due diligence apontou a maior fraqueza de testes: **1 único teste
`@SpringBootTest`** (`FoxCashContextSmokeTest`) e **0 Mockito** — os 92.8% de
cobertura são com **mocks manuais** (`mock(Interface.class)` escrito à mão).
Isso prova lógica unitária, mas **não prova** o wiring entre camadas, a
configuração real (yml/env), o JPA real, o Flyway real, nem o comportamento do
runtime (o L19 documenta exatamente o acidente que isso causou: `mvn test` verde
mas boot quebrado por jar corrompido).

Objetivo: uma camada de testes de integração que sobe a app **de verdade**
(Spring context + Postgres + Redis via Testcontainers) para os fluxos
financeiros críticos.

## Critérios de aceite

**C1 — Testcontainers no projeto.** Dependência `testcontainers` (postgresql +
redis) adicionada; profile/infra de teste sobe Postgres e Redis reais em Docker
(com fallback para Postgres local no CI, padrão atual da Spec 68).

**C2 — Fluxos críticos com integração real.** Mínimo obrigatório — testes que
sobem a app completa e exercitam o caminho real (HTTP → controller → service →
JPA → Postgres):
- Criação de cobrança Pix → idempotência (mesma key 2× → 1 transação) → webhook
  PAID → ledger/wallet atualizados (zona sagrada);
- Autenticação merchant (login → token → acesso) e RBAC master (403 sem role);
- Webhook PSP com assinatura HMAC (válida → processa; inválida → 401);
- Rate limit (429 no abuso) com Redis real;
- Falha do Redis (fail-open, ADR-010) com Testcontainers derrubado.

**C3 — Os 92.8% de cobertura mantidos.** O Jacoco continua ≥ 92.8% (as metas do
verify não caem); os novos testes de integração somam (não substituem) os
unitários.

**C4 — CI roda a camada de integração.** O workflow CI executa os testes de
integração (Testcontainers ou Postgres nativo do runner — como já faz com a
Spec 68) e falha se quebrarem. `mvn verify` completo verde.

**C5 — Sem duplicação lenta.** Os testes de integração são **seletivos**
(fluxos críticos, não todos os 259 testes) — a suíte completa continua rodando
em tempo razoável (documentar o tempo antes/depois).

## Evidência-base (2026-08-16)

- `@SpringBootTest`: **1** (`FoxCashContextSmokeTest`, 2 testes).
- Mockito: **0** (mocks manuais `mock(Interface.class)`).
- Cobertura Jacoco: INSTRUCTION 92.8%, LINE 92.8% (metas do verify).
- CI já instala Postgres nativo para a Spec 68 — infra pronta para estender.

## Dependências

- **Spec 68** (smoke de contexto) — base e padrão do CI.
- **Spec 130** (Boot 3.5.x) — versão do Testcontainers alinhada.
- **L19** — motivação (testes unitários não provam runtime).

## Fora de escopo

- Cobertura 100% (irreal e não é o objetivo — os 92.8% já são fortes).
- Testes E2E de browser (camada separada, já existe).
