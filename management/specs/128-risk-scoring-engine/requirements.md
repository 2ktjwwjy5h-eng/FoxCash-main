---
name: Risk Scoring Engine — automação de risco por MED (Gap 2)
status: audited
references:
  - "management/vision.md#V95"
  - "management/specs/16-antifraud-compliance/requirements.md"
  - "management/specs/97-painel-usuarios-leitura/requirements.md"
  - "management/specs/98-painel-usuarios-config/requirements.md"
  - "management/specs/41-master-profiles-activity/requirements.md"
  - "management/specs/53-job-robustez/requirements.md"
---

# Spec 128 — Risk Scoring Engine: automação de risco por MED (Gap 2)

## O que entrega

O risco hoje é **calculado** (`merchant_risk`, Spec 16 — snapshot diário por
lojista) e **exibido** (painel do master, Spec 97 — `medPercent` com faixas
LOW/MEDIUM/HIGH), mas **nunca age**: ban/deactivate/honeypot/rate limit (Spec
98) são decisões manuais do master (RBAC + step-up). Com dezenas de lojistas,
alguém precisa olhar o painel e agir — e a ação chega atrasada.

Esta spec entrega o **Risk Scoring Engine**: uma regra declarativa (config,
não código) que roda no job diário de risco (ponto de extensão natural) e
**reduz automaticamente o rate limit do lojista** quando o MED (medida de
ocorrências problemáticas) da janela de 30 dias ultrapassa o limiar — e
**restaura** quando o MED normaliza. Tudo reversível, auditável, com override
manual e fail-safes.

**Decisões do usuário (2026-08-16):**

1. **Sem bloqueio de conta automático** — nem `deactivate` (INACTIVE) nem
   `suspend` (SUSPENDED): o produto vai rodar produtos digitais, vertical com
   chargeback **inerente** — bloquear por chargeback alto mataria o negócio
   legítimo. O chargeback alimenta o MED e a **única ação automática é reduzir
   o rate limit**. O auto-suspend da Spec 19
   (`foxcash.risk.auto-suspend-enabled`) passa a **default `false`** (desligado;
   quem quiser reativa por config).
2. **Regra de falha PSP adiada** — a métrica `nyx_psp_route_total` (Spec 91) é
   global (sem fonte por lojista); a regra "falha PSP > Z% → bloquear/reduzir"
   exige um contador por lojista no router (fase futura registrada). v1 =
   MED → rate limit apenas.

## Critérios de aceitação

**C1 — Regra declarativa MED → rate limit (config, não código).** Nova
configuração `foxcash.risk.auto.*` com defaults conservadores: `enabled`
(default `true` — kill-switch global), `med-threshold-percent` (default `10.0` —
dispara a redução), `med-recover-percent` (default `5.0` — histerese: abaixo
disso restaura), `rate-limit-reduction-percent` (default `50.0` — % de redução),
`default-rps` (default `5` — referência para redução quando o lojista não tem
override), `default-rpm` (default `300`), `floor-rps` (default `1` — piso, nunca
abaixo), `floor-rpm` (default `60`). O MED é calculado na **janela de 30 dias**:
`(refunded + chargebacks) ÷ paid × 100` (mesma fórmula do painel, Spec 97) com
queries novas por período (não o snapshot all-time).

**C2 — Ação: reduzir `rateLimitRps`/`rateLimitRpm` (reversível).** Quando
`MED > med-threshold-percent` e o lojista **não tem override manual** de rate
limit, o engine seta o override reduzido: `newRps = max(floor-rps,
round(default-rps × (1 − redução%)))`, `newRpm = max(floor-rpm, round(default-rpm
× (1 − redução%)))` — aplicado via `Merchant.updateConfig` com **lock pessimista**
(padrão SEC-19: `findWithConfigForUpdate`). Ação **idempotente**: enquanto o MED
permanece alto, o override não é re-reduzido (sem efeito catraca). Quando
`MED <= med-recover-percent` E o override foi posto pelo engine, **restaura para
`null`** (volta ao teto global). Nunca abaixo do piso.

**C3 — Fail-safes.** (a) kill-switch global `foxcash.risk.auto.enabled` (default
true; `false` → engine não age); (b) **nunca age em lojista com 0 pagos na
janela** (MED = 0 — evita falso positivo); (c) só age em lojista `ACTIVE`;
(d) pisos de rate limit (C2); (e) **sem bloqueio de status** — o engine NÃO
chama `deactivate`/`suspend`/`ban` (decisão do usuário; ban continua manual com
step-up).

**C4 — Override manual vence.** Migration **V75**: `merchants.risk_auto_managed
BOOLEAN NOT NULL DEFAULT false`. Toda escrita **manual do master** que interfere
com as regras do engine (updateConfig, markHoneypot, deactivate, activate, ban,
suspend, release — Specs 98/16) marca `risk_auto_managed = true` **na mesma
transação**. O engine **pula** lojistas com a flag `true` (humano controla —
sem guerra de edição). Reversão: o master desmarca (via SQL/edição manual —
documentado; sem UI na v1).

**C5 — Auditoria SYSTEM + métrica.** O engine audita na trilha da plataforma
(Spec 41 — `PlatformActivityRecorder` com `PlatformActorType.SYSTEM`,
`actorId = null`): `PlatformAction.RISK_AUTO_RATE_LIMIT_REDUCED` (motivo com
`medPercent`, `before`/`after` dos overrides) e
`PlatformAction.RISK_AUTO_RATE_LIMIT_RESTORED`. Métrica
`nyx_risk_auto_actions_total{action=reduced|restored|skipped_manual}` (Spec 53 —
dashboards/alertas da Spec 126). O `MasterAuditRecorder` (Spec 37) exige
`actorId` de master — não se aplica a SYSTEM; a trilha é o `platform_activity_log`.

**C6 — Roda no job diário existente + testes.** O engine é invocado pelo
`RiskMonitoringService.computeForAllMerchants()` (mesmo job
`foxcash-risk-monitoring`, ShedLock + `JobExecutionService` — sem job novo,
sem lock novo): para cada lojista do batch, após salvar o snapshot, o engine
avalia. `foxcash.risk.auto-suspend-enabled` default `false` (decisão do usuário).
Testes: engine (dispara/não dispara/restaura/idempotente/piso/fail-safes),
flag manual (escritas do master marcam; engine pula), métrica, auditoria;
`mvn test` verde.

## Fora de escopo

- **Regra de falha PSP** (bloquear API/reduzir teto por falha de adquirente) —
  adiada; exige contador de falhas PSP **por lojista** no router (fase futura
  registrada no task.md).
- **UI no painel** para reabilitar `risk_auto_managed` (master desmarca via
  SQL/edição manual — documentado; endpoint/UI ficam para fase futura).
- Mudança de fluxo de dinheiro (nenhuma alteração financeira/ledger).
- Ban/deactivate/suspend automáticos (decisão do usuário — sem bloqueio).

## Dependências

- **Spec 16** (`RiskMonitoringScheduler` + `merchant_risk` + auto-suspend —
  ponto de extensão; default desligado por decisão do usuário).
- **Spec 97** (fórmula do MED e faixas do painel — a janela 30d é nova).
- **Spec 98** (`MerchantConfigService` + `MerchantConfig.rateLimitRps/Rpm` +
  lock SEC-19 + `AuthenticatedRateLimitFilter` lendo o override em runtime).
- **Spec 41** (trilha `platform_activity_log` com actor SYSTEM).
- **Spec 53** (métrica de ação + padrão de job).
