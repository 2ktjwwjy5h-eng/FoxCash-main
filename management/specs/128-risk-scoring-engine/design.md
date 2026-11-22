---
name: Risk Scoring Engine — automação de risco por MED (Gap 2)
status: audited
---

# Spec 128 — Design: Risk Scoring Engine (automação de risco por MED)

## D1 — Conceito

O risco calculado (Spec 16) e exibido (Spec 97) passa a **agir** com uma única
ação automática, declarativa e reversível: **reduzir o rate limit** do lojista
quando o MED (30 dias) ultrapassa o limiar, e **restaurar** quando normaliza.

- **MED 30d** = `(refunded + chargebacks) ÷ paidCount × 100` onde
  `paidCount = approved + refunded` — mesma fórmula do painel (Spec 97), mas com
  **janela de 30 dias** (o snapshot `merchant_risk` é all-time; o painel calcula
  ao vivo sem janela) e denominador de vendas **que já foram pagas** (uma
  estornada JÁ foi paga — achado do smoke 2026-08-16: sem isso, um lojista com
  vendas estornadas zeraria o denominador e o fail-safe mascararia o risco).
  Queries novas por período.
- **Única ação**: `rateLimitRps`/`rateLimitRpm` (Spec 98). **Sem bloqueio de
  status** — decisão do usuário (produtos digitais tem chargeback inerente;
  `deactivate`/`suspend`/`ban` continuam 100% manuais).
- **Reversível**: restauração automática com histerese; nenhuma ação
  irreversível.
- **Override manual vence**: coluna `risk_auto_managed` sticky — humano tocou →
  engine não age.
- **Fail-safe**: kill-switch global, piso de rate limit, zero pagos = sem ação.

## D2 — Migration V75 (`V75__merchants_risk_auto_managed.sql`)

```sql
ALTER TABLE merchants ADD COLUMN risk_auto_managed BOOLEAN NOT NULL DEFAULT false;
```

- Sticky: `true` = um humano (master) tocou config/status do lojista → o
  automático não age (nem reduz nem restaura). Reversão manual (master desmarca
  via SQL/edição — documentado; sem UI na v1).
- **V75 livre** (V74 usado pela Spec 127). Sem índice (lookup é por lojista no
  batch do job).

## D3 — Configuração (`foxcash.risk.auto.*`)

`RiskProperties` (classe existente, `@ConfigurationProperties("foxcash.risk")`)
ganha o bloco `auto` (`RiskAutoProperties` — inner class, padrão
`MasterPanelProperties.RiskThresholds`):

| Property | Default | Significado |
|---|---|---|
| `enabled` | `true` | Kill-switch global (fail-safe) |
| `med-threshold-percent` | `10.0` | MED 30d acima disto → reduzir rate limit |
| `med-recover-percent` | `5.0` | MED 30d ≤ isto → restaurar (histerese) |
| `rate-limit-reduction-percent` | `50.0` | % de redução sobre a referência |
| `default-rps` | `5` | Referência rps p/ redução quando sem override |
| `default-rpm` | `300` | Referência rpm p/ redução quando sem override |
| `floor-rps` | `1` | Piso rps (nunca abaixo) |
| `floor-rpm` | `60` | Piso rpm (nunca abaixo) |

Defaults conservadores: limiar alto (10% — não age em variação normal),
histerese (restaura só bem abaixo do disparo — evita oscilação), piso que
mantém o lojista operando. `application.yml` ganha o bloco com env vars
(`NYX_RISK_AUTO_*`); binding testado contra o yml real (padrão Spec 68/L16).

## D4 — Domínio e infra

| Arquivo | Mudança |
|---|---|
| `Merchant` (model) | Campo `boolean riskAutoManaged` (construtor/restore + `riskAutoManaged()` + `markRiskAutoManaged()` — SEM `touch`; metadado de origem, padrão `attachProvider`). Restore com o campo novo. |
| `MerchantJpaEntity` | Coluna `risk_auto_managed` (`@Column(nullable = false)` + `@ColumnDefault("false")`) + getter/setter. |
| `MerchantMapper` | `toEntity`/`toDomain` mapeiam o campo (bloco da config). |
| `MerchantConfigProvider` | Sem mudança (o engine usa `findWithConfigForUpdate` + `save` existentes). |
| `TransactionRepository` | Sem mudança — `countByMerchantIdAndStatusAndCreatedAtBetween` já existe. |
| `ChargebackRepository` | **Novo método** `countByMerchantIdAndCreatedAtAfter(UUID, Instant)` (porta + `ChargebackJpaRepository` + adapter — `created_at` existe). |
| `MerchantRiskStats` (novo, domain/model/risk) | Record `(long paid, long refunded, long chargebacks)` + `medPercent()` — `paid == 0 → ZERO` (fail-safe), senão `(refunded+chargebacks)/paid×100` (2 casas, HALF_EVEN). |

## D5 — RiskScoringEngine (novo service)

`application/service/RiskScoringEngine` — regra declarativa, sem estado:

```java
@Service
public class RiskScoringEngine {
    // deps: MerchantConfigProvider, TransactionRepository, ChargebackRepository,
    //       RiskProperties (bloco auto), PlatformActivityRecorder, MeterRegistry

    /** Janela de avaliação do MED (30 dias). */
    private static final int MED_WINDOW_DAYS = 30;

    /** Avalia UM lojista (chamado pelo RiskMonitoringService no loop do batch).
     *  Retorna o nome da ação (null = nenhuma). */
    public String evaluate(Merchant merchant) {
        RiskAutoProperties auto = riskProperties.getAuto();
        if (!auto.isEnabled() || merchant.riskAutoManaged()
                || merchant.status() != MerchantStatus.ACTIVE) {
            return null;
        }
        Instant since = Instant.now().minus(MED_WINDOW_DAYS, ChronoUnit.DAYS);
        long paid = transactionRepository.countByMerchantIdAndStatusAndCreatedAtBetween(
                merchant.id(), TransactionStatus.APPROVED, since, Instant.now());
        if (paid == 0) {
            return null; // fail-safe: sem volume recente → sem ação (falso positivo)
        }
        long refunded = transactionRepository.countByMerchantIdAndStatusAndCreatedAtBetween(
                merchant.id(), TransactionStatus.REFUNDED, since, Instant.now());
        long chargebacks = chargebackRepository.countByMerchantIdAndCreatedAtAfter(
                merchant.id(), since);
        BigDecimal med = medPercent(paid, refunded + chargebacks);

        MerchantConfig config = merchant.config();
        boolean hasOverride = config.rateLimitRps() != null || config.rateLimitRpm() != null;
        if (med.compareTo(auto.getMedThresholdPercent()) > 0 && !hasOverride) {
            return reduce(merchant, med);        // dispara
        }
        if (med.compareTo(auto.getMedRecoverPercent()) <= 0 && hasOverride) {
            return restore(merchant, med);       // normalizou (override era do engine — flag false)
        }
        return null; // idempotente enquanto MED alto; sem ação nos demais casos
    }
}
```

- **`reduce`** (idempotente — só age na transição "sem override → reduzido"):
  relê com **lock** (`findWithConfigForUpdate` — padrão SEC-19; o merchant do
  batch pode estar stale), valida `!riskAutoManaged` + `status ACTIVE` + sem
  override (re-checagem sob lock), calcula
  `newRps = max(floor-rps, floor(default-rps × (1 − redução%/100)))` e
  `newRpm = max(floor-rpm, floor(default-rpm × (1 − redução%/100)))` — o
  arredondamento é para **BAIXO** (a redução nunca aumenta o teto além do alvo;
  fail-safe de mitigação), monta o
  `MerchantConfig` com os overrides novos, `updateConfig` + `save`, audita
  `RISK_AUTO_RATE_LIMIT_REDUCED` e incrementa
  `nyx_risk_auto_actions_total{action=reduced}`.
- **`restore`**: relê com lock, valida `!riskAutoManaged` + override presente,
  limpa os overrides (`rateLimitRps/Rpm → null` — volta ao teto global), salva,
  audita `RISK_AUTO_RATE_LIMIT_RESTORED`, métrica `{action=restored}`.
- **Motivo da auditoria** (Spec 41, `PlatformActivityRecorder.record(SYSTEM,
  null, action, "MERCHANT", merchantId, merchantId, details)`): `details` =
  `{medPercent, before: {rateLimitRps, rateLimitRpm}, after: {...}}` — mascarado
  pelo recorder (sem chaves sensíveis envolvidas).
- **Métrica**: `nyx_risk_auto_actions_total{action}` (Spec 53) — também
  incrementa `{action=skipped_manual}` quando o engine pula por
  `riskAutoManaged` (visibilidade: quantos lojistas estão sob controle humano).

**Decisão — referência da redução**: quando o lojista NÃO tem override, o
engine usa `default-rps`/`default-rpm` da config como a "capacidade normal" de
referência e seta o override reduzido (ex.: 5 rps → 2 rps, 300 rpm → 150 rpm
com redução 50% e pisos 1/60). Enquanto o MED permanece alto o override é
mantido (sem catraca); a restauração volta para `null` (teto global) — nunca
"recria" um valor de humano (humano → flag `risk_auto_managed` → engine não
toca).

## D6 — Integração no job (ponto de extensão natural)

`RiskMonitoringService.computeForAllMerchants()` (Spec 16/53 — ShedLock +
`JobExecutionService`, batch 200) injeta o `RiskScoringEngine` e, no loop por
lojista, após `riskRepository.save(snapshot)`:

```java
// Spec 128: após o snapshot, o engine avalia o lojista (MED 30d → rate limit).
engine.evaluate(merchant);
```

- Mesmo job (`foxcash-risk-monitoring`), mesmo lock — **sem job novo**. O
  `RiskMonitoringService` não precisa re-varrer (o merchant já está no batch).
- O engine relê com lock apenas quando decide agir (2 leituras extras só na
  transição, não por lojista).
- `foxcash.risk.auto-suspend-enabled` → default **`false`** (decisão do
  usuário: sem bloqueio automático; o `maybeAutoSuspend` da Spec 19 fica
  desligado por default, reativável por config).

## D7 — Override manual vence (marca da flag)

As escritas **manuais do master** que interferem com as regras do engine
marcam `risk_auto_managed = true` **na mesma transação** (o `Merchant` já está
sob lock — SEC-19):

| Service | Métodos | Ação |
|---|---|---|
| `MerchantConfigService` (Spec 98) | `updateConfig`, `markHoneypot`, `deactivate`, `activate`, `ban` | `merchant.markRiskAutoManaged()` antes do save |
| `MerchantStatusControlService` (Spec 16) | `suspend`, `release` | idem |

- `debit` (débito administrativo) **não** marca (é financeiro, não interfere
  com rate limit — o engine continua livre para agir).
- O auto-suspend do engine/job não marca (é SYSTEM, não humano).
- Efeito: humano toca → engine nunca mais age naquele lojista (nem reduz nem
  restaura) até o master desmarcar manualmente (documentado no runbook).

## D8 — Riscos e mitigação

- **Falso positivo (lojista legítimo com MED alto momentâneo)** → limiar alto
  default 10% + janela 30d (amortiza picos) + zero pagos = sem ação + histerese
  + ação reversível (rate limit, não bloqueio).
- **Guerra de edição** (engine desfaz decisão humana) → flag `risk_auto_managed`
  sticky: humano toca → engine não age.
- **Snapshot stale do merchant no batch** (SEC-19) → re-leitura com
  `findWithConfigForUpdate` antes de qualquer escrita.
- **Efeito catraca** (redução progressiva até o piso) → redução só na transição
  "sem override → reduzido"; MED alto mantém o override (idempotente).
- **Restauração indevida de valor humano** → restaura só com `!riskAutoManaged`
  (humano → flag → engine não toca) e só quando há override presente.
- **Métrica/auditoria em falta** → `PlatformActivityRecorder` (Spec 41) +
  `nyx_risk_auto_actions_total` (Spec 53) — dashboards/alertas da Spec 126.

## D9 — Arquivos previstos

**Backend:**
- Novo: `src/main/resources/db/migration/V75__merchants_risk_auto_managed.sql`
- Novo: `domain/model/risk/MerchantRiskStats.java`
- Novo: `application/service/RiskScoringEngine.java`
- Modificados: `RiskProperties` (+ bloco `auto`), `Merchant`, `MerchantJpaEntity`,
  `MerchantMapper`, `MerchantConfigProvider`? (não — engine usa o existente),
  `ChargebackRepository`/`ChargebackJpaRepository`/`ChargebackPersistenceAdapter`
  (`countByMerchantIdAndCreatedAtAfter`), `RiskMonitoringService` (injeção do
  engine + default auto-suspend false), `MerchantConfigService`,
  `MerchantStatusControlService`, `PlatformAction` (+2 ações), `application.yml`.

**Testes:**
- Novo: `RiskScoringEngineTest` (dispara/não dispara/restaura/idempotente/piso/
  fail-safes/manual vence), `MerchantRiskStatsTest` (fórmula, zero pagos),
  `RiskPropertiesTest` (binding do bloco `auto` contra o yml — padrão Spec 68),
  `MerchantMapperTest`/verifies (campo `riskAutoManaged`).
- Modificados: `MerchantConfigServiceTest`/`MerchantStatusControlServiceTest`
  (escritas manuais marcam a flag), `RiskMonitoringServiceTest` (engine
  invocado; auto-suspend default off), `PlatformActivityRecorderTest` (ações
  novas), binding properties.

## D10 — Fora de escopo / fase futura

- **Regra de falha PSP** (bloquear/reduzir por falha de adquirente) — exige
  contador de falhas PSP **por lojista** no `PixProviderRouter` (hoje a métrica
  `nyx_psp_route_total` é global) + período de coleta para baseline. Registrada
  no task.md como fase futura.
- **UI para desmarcar `risk_auto_managed`** — master desmarca via SQL/edição
  manual (runbook); endpoint/painel ficam para fase futura.
- Sem mudança de fluxo de dinheiro; sem ban/deactivate/suspend automáticos.
