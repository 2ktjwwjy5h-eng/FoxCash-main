---
name: Risk Scoring Engine — automação de risco por MED (Gap 2)
status: audited
---

# Spec 128 — Task: Risk Scoring Engine (automação de risco por MED)

Ordem: T0 (migration) → T1 (domínio/infra) → T2 (config) → T3 (engine) → T4
(job + manual vence) → T5 (auditoria/métrica) → T6 (testes) → T7 (auditoria).
Nenhuma mudança de fluxo de dinheiro; sem bloqueio de status automático.

## T0 — Migration (critério C4)

- [x] T0.1: **`V75__merchants_risk_auto_managed.sql`** — `ALTER TABLE merchants
      ADD COLUMN risk_auto_managed BOOLEAN NOT NULL DEFAULT false`. Confirmar
      V75 livre (V74 usado pela Spec 127) e `mvn test` (boot aplica).

## T1 — Domínio e infra (C4)

- [x] T1.1: `Merchant` — campo `boolean riskAutoManaged` (construtor/restore +
      getter `riskAutoManaged()` + `markRiskAutoManaged()` — SEM touch).
- [x] T1.2: `MerchantJpaEntity` — coluna `risk_auto_managed`
      (`@Column(nullable = false)` + `@ColumnDefault("false")`) + getter/setter.
- [x] T1.3: `MerchantMapper` — `toEntity`/`toDomain` mapeiam o campo.
- [x] T1.4: `MerchantRiskStats` (novo record em domain/model/risk) — `(paid,
      refunded, chargebacks)` + `medPercent()` (`paid == 0 → ZERO`).

## T2 — Configuração (C1)

- [x] T2.1: `RiskProperties` — bloco `auto` (`RiskAutoProperties`: enabled true,
      med-threshold-percent 10.0, med-recover-percent 5.0,
      rate-limit-reduction-percent 50.0, default-rps 5, default-rpm 300,
      floor-rps 1, floor-rpm 60).
- [x] T2.2: `application.yml` — bloco `foxcash.risk.auto.*` com env vars
      (`NYX_RISK_AUTO_*`).
- [x] T2.3: `foxcash.risk.auto-suspend-enabled` default → `false` (decisão do
      usuário — sem bloqueio automático).

## T3 — RiskScoringEngine (C2, C3)

- [x] T3.1: `RiskScoringEngine.evaluate(Merchant)` — gates: `auto.enabled`,
      `!riskAutoManaged`, `status == ACTIVE`; MED 30d
      (`countByMerchantIdAndStatusAndCreatedAtBetween` APPROVED/REFUNDED +
      `countByMerchantIdAndCreatedAtAfter` chargebacks); `paid == 0 → null`.
- [x] T3.2: `reduce(merchant, med)` — relê com `findWithConfigForUpdate`
      (SEC-19), re-valida gates sob lock, calcula
      `newRps = max(floor-rps, floor(default-rps × (1 − redução%)))` /
      `newRpm = max(floor-rpm, floor(default-rpm × (1 − redução%)))`, monta o
      `MerchantConfig` com overrides, `updateConfig` + `save`.
- [x] T3.3: `restore(merchant, med)` — relê com lock, limpa overrides
      (`rateLimitRps/Rpm → null`), salva.
- [x] T3.4: Idempotência — dispara só na transição "sem override → reduzido";
      restaura só na transição "com override (engine) → sem". Sem catraca.

## T4 — Integração no job + override manual vence (C6, C4)

- [x] T4.1: `RiskMonitoringService` — injeta `RiskScoringEngine`; no loop do
      batch, após `riskRepository.save(snapshot)`, chama `engine.evaluate(merchant)`.
- [x] T4.2: `MerchantConfigService` — `updateConfig`, `markHoneypot`,
      `deactivate`, `activate`, `ban` marcam `merchant.markRiskAutoManaged()`
      (mesma transação). `debit` NÃO marca.
- [x] T4.3: `MerchantStatusControlService` — `suspend`, `release` marcam a flag.
- [x] T4.4: Auto-suspend (Spec 19) — default `false` (T2.3); sem mudança no
      código do `maybeAutoSuspend` (apenas o default da config).

## T5 — Auditoria e métrica (C5)

- [x] T5.1: `PlatformAction` += `RISK_AUTO_RATE_LIMIT_REDUCED` /
      `RISK_AUTO_RATE_LIMIT_RESTORED` (seção Operacional — Spec 41).
- [x] T5.2: `RiskScoringEngine` audita via `PlatformActivityRecorder` (SYSTEM,
      actorId null, "MERCHANT", merchantId) com `{medPercent, before, after}`.
- [x] T5.3: Métrica `nyx_risk_auto_actions_total{action=reduced|restored|
      skipped_manual}` (Spec 53).

## T6 — Testes e verificação (C1–C6)

- [x] T6.1: `RiskScoringEngineTest` — dispara (MED alto, sem override),
      não dispara (MED baixo, kill-switch off, não-ACTIVE, paid==0,
      riskAutoManaged), restaura (MED normalizou), idempotente (MED alto
      mantém), piso (redução nunca abaixo do floor), auditoria + métrica.
- [x] T6.2: `MerchantRiskStatsTest` — fórmula, zero pagos → 0.
- [x] T6.3: `RiskPropertiesTest`/binding — bloco `auto` contra o yml real
      (padrão Spec 68/L16).
- [x] T6.4: `MerchantConfigServiceTest`/`MerchantStatusControlServiceTest` —
      escritas manuais marcam `riskAutoManaged`.
- [x] T6.5: `RiskMonitoringServiceTest` — engine invocado no loop; auto-suspend
      default off (3 testes reativam explicitamente).
- [x] T6.6: `mvn test` completo verde — **1993/0/0** (1973 + 20 novos).

## T7 — Auditoria

- [x] T7.1: Verificação critério a critério (C1–C6) + docs atualizados
      (status `implemented`/`audited` no roadmap e specs).
- [x] T7.2: Smoke manual (documentar no task.md): job roda com lojista de teste
      → reduz rate limit quando MED alto; restaura quando MED normaliza; flag
      manual impede a ação.

## §Auditoria (2026-08-16)

**`/spec-audit 128`: 6/6 critérios OK — 1 achado de código corrigido, 0
abertos.** Verificação critério a critério (ao vivo contra app dev + banco
real + suíte completa):

- **C1 (regra declarativa)** — `foxcash.risk.auto.*` binding testado contra o
  yml real (`RiskPropertiesBindingTest` — padrão Spec 68/L16; o bloco não
  colidiu com o yml); MED 30d via `MerchantRiskStats`.
- **C2 (reduz/restaura)** — `RiskScoringEngineTest` (12): dispara, restaura
  (histerese), idempotente, piso, re-checagem sob lock. Redução `floor`
  (nunca aumenta o teto).
- **C3 (fail-safes)** — kill-switch, 0 vendas pagas, não-ACTIVE, piso — todos
  testados; sem bloqueio de status (decisão do usuário).
- **C4 (override manual vence)** — **ao vivo**: config manual do master
  (`PUT /master/merchants/{id}/config`) → `merchants.risk_auto_managed = t` no
  banco + auditoria `MERCHANT_CONFIG_UPDATED`; teste unitário do engine
  (skip + métrica `skipped_manual`).
- **C5 (auditoria SYSTEM + métrica)** — ações novas no `PlatformAction`;
  `PlatformActivityRecorder` com actor SYSTEM; métrica
  `nyx_risk_auto_actions_total{action=reduced|restored|skipped_manual}`.
- **C6 (job + testes)** — `RiskMonitoringService` invoca o engine no loop do
  batch (mesmo job/lock); `mvn test` **1995/0/0** (1973 + 22 novos, re-executado
  após o achado do MED); `auto-suspend-enabled` default `false` verificado no
  yml real.

**Smoke ao vivo (T7.2)**: boot (V75 aplicada — `risk_auto_managed BOOLEAN NOT
NULL DEFAULT false`); lojista → cobrança → PAID → REFUNDED (MED 100% na janela);
**override rps=2 via config do master → `risk_auto_managed = t` + 429 após 2
req/s na API do lojista** (prova que a ação do engine — escrever o override —
tem efeito real em runtime) + auditoria da ação manual.

**Achado corrigido (1, MÉDIO — do smoke)**: o denominador do MED era
`paid = só APPROVED`; como estornar **move** a transação de APPROVED para
REFUNDED, um lojista com vendas estornadas zerava o denominador e o fail-safe
(`paid == 0`) **mascarava o caso mais arriscado** (todas as vendas estornadas →
MED 0 → engine nunca agiria). Corrigido: `MerchantRiskStats.paidCount() =
approved + refunded` (estornada JÁ foi paga) + teste
`allRefundedMerchantStillActs` + `MerchantRiskStatsTest.allRefundedIsHighMedNotZero`.
Fim do ciclo SDD — **Spec 128 `audited`**. Fases futuras: regra de falha PSP
(contador por lojista no router), UI para desmarcar `risk_auto_managed`,
alerta de operador da ação (webhook Spec 64).

## §Implementação (2026-08-16)

T0–T6 concluídas — **status `implemented`** (aguardando `/spec-audit`):

- **T0/T1 (domínio/infra)**: migration **V75** (`merchants.risk_auto_managed
  BOOLEAN NOT NULL DEFAULT false`); `Merchant` com `riskAutoManaged()` +
  `markRiskAutoManaged()` (SEM touch; overload novo de `restore` com config +
  flag usado pelo mapper); `MerchantJpaEntity` coluna + getter/setter;
  `MerchantMapper` mapeia; `MerchantRiskStats` (record: MED 30d, `paid==0 → 0`).
- **T2 (config)**: `RiskProperties` bloco `auto` (enabled/med-threshold 10%/
  med-recover 5%/reduction 50%/default-rps 5/default-rpm 300/floor-rps 1/
  floor-rpm 60) + `auto-suspend-enabled` default `false` (decisão do usuário);
  `application.yml` com env vars `NYX_RISK_AUTO_*`.
- **T3 (engine)**: `RiskScoringEngine.evaluate(merchant)` — gates
  (`auto.enabled`, `!riskAutoManaged`, `ACTIVE`), MED 30d (queries
  `countByMerchantIdAndStatusAndCreatedAtBetween` + novo
  `countByMerchantIdAndCreatedAtAfter` no chargeback), `paid==0 → sem ação`;
  `reduce`/`restore` com **lock SEC-19** (`findWithConfigForUpdate`) e
  re-checagem sob lock; redução `max(floor, floor(ref × (1−redução%)))` —
  **arredonda para baixo** (fail-safe: nunca aumenta o teto); idempotente (sem
  catraca).
- **T4 (job + manual vence)**: `RiskMonitoringService` injeta o engine e chama
  `evaluate(merchant)` no loop do batch (mesmo job/lock — ponto de extensão);
  `MerchantConfigService` (updateConfig/honeypot/deactivate/activate/ban) e
  `MerchantStatusControlService` (suspend/release) marcam
  `markRiskAutoManaged()` (debit NÃO marca).
- **T5 (auditoria/métrica)**: `PlatformAction` += `RISK_AUTO_RATE_LIMIT_REDUCED`/
  `RISK_AUTO_RATE_LIMIT_RESTORED`; auditoria via `PlatformActivityRecorder`
  (SYSTEM, actorId null, `{medPercent, reason, before, after}`); métrica
  `nyx_risk_auto_actions_total{action=reduced|restored|skipped_manual}`.
- **T6 (testes)**: `RiskScoringEngineTest` (11), `MerchantRiskStatsTest` (4),
  `RiskPropertiesTest` (3), `RiskPropertiesBindingTest` (3 — binding do yml
  real, padrão Spec 68/L16); `RiskMonitoringServiceTest` atualizado (engine
  mockado no loop; 3 testes de auto-suspend reativam explicitamente o flag —
  default agora é false). **`mvn test` 1993/0/0** (1973 + 20).
- **Desvios**: (1) arredondamento da redução é `floor` (o design inicial dizia
  `round` — `Math.round(2.5)=3` aumentaria o teto além do alvo; floor é o
  fail-safe correto; design D5 atualizado); (2) `auto-suspend-enabled` não
  existia no yml (default morava no código) — a chave foi adicionada
  explicitamente no yml + default do campo → `false`.

## Fases futuras registradas (fora de escopo)

- **Regra de falha PSP** (bloquear API/reduzir teto por falha de adquirente) —
  exige contador de falhas PSP **por lojista** no `PixProviderRouter` (a métrica
  `nyx_psp_route_total` é global hoje) + período de coleta para baseline.
- **UI para desmarcar `risk_auto_managed`** — master desmarca via SQL/edição
  manual (runbook); endpoint/painel para uma spec futura.
- **Alerta de operador da ação** (webhook Spec 64) — coberto por
  métrica/dashboards (Spec 126) na v1; evento de operador fica para fase futura
  se a operação pedir.
