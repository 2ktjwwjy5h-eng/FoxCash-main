---
name: Saque automático por limiar de saldo (auto-withdraw)
status: audited
---

# Spec 70 — Design: Saque automático por limiar de saldo

## D1 — Conceito

O auto-saque é um **saque de vendedor comum** (Spec 32) disparado por um **job
agendado** em vez de um POST do vendedor. As duas únicas novidades são:

1. **Configuração** (novo recurso 1:1 por lojista): `enabled`, `threshold`
   (default R$ 1.000,00) e `destinationId` — gerenciada pelo vendedor no portal.
2. **Gatilho** (job ShedLock + `JobExecutionService`, Spec 53): para cada config
   ativa, se `wallet_balance >= threshold` **sob o lock pessimista do lojista**,
   cria um saque no valor **igual ao saldo total**.

Tudo o mais (destinos, bloqueios, inadimplência, ledger, PSP de saída, webhook
de conclusão, reconciliação, estorno) é **reuso** — nenhuma regra financeira
existente é tocada. O gatilho é **assíncrono** (fora do caminho crítico da
venda): uma venda aprovada credita o saldo (Spec 14) e o job — na próxima
execução — detecta o cruzamento do limiar.

### D1.1 Decisões do usuário (2026-08-11) → regras

| Decisão | Regra |
|---|---|
| Gatilho = saldo `>=` limiar | `wallet_balance` (fonte oficial, Spec 22) comparado com `threshold` **dentro do lock** (D5.2) |
| Valor = saldo total | `amount = merchant.walletBalance()` na Fase A (D5.3) — sem resíduo por corrida |
| Destino = escolhido pelo vendedor | `destination_id` na config; validado 404/409 no PUT (D5.1) e ativo no job (D5.2) |
| Ativação = painel do portal | `GET/PUT /api/v1/portal/auto-withdraw` (D7) + card na `WithdrawPage` (D8) |

## D2 — Persistência

### D2.1 Migration (Flyway)

Nova migration `V48__merchant_auto_withdraw_config.sql`. **Atenção:** a Spec 69
(design, 2026-08-11) reservou **V47** para `master_permissions`; na implementação
usar o **próximo número livre** em `src/main/resources/db/migration` (V48 se a 69
já tiver sido aplicada antes, senão V47) — nunca colidir com uma migration já
aplicada (CI flyway `validate` falha em checksum/ordem).

```sql
CREATE TABLE merchant_auto_withdraw_config (
    id              UUID PRIMARY KEY,
    merchant_id     UUID        NOT NULL UNIQUE REFERENCES merchants (id) ON DELETE CASCADE,
    enabled         BOOLEAN     NOT NULL DEFAULT FALSE,
    threshold_amount NUMERIC(15,2) NOT NULL,
    destination_id  UUID        NOT NULL REFERENCES merchant_withdrawal_destinations (id) ON DELETE RESTRICT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_merchant_auto_withdraw_enabled ON merchant_auto_withdraw_config (enabled);
```

- `merchant_id UNIQUE` — 1:1 por lojista (mesmo padrão de `stores`, Spec 48);
  upsert sem violação.
- `threshold_amount NUMERIC(15,2)` — mesmo tipo de `merchants.wallet_balance`
  (BigDecimal precision 15 scale 2).
- `destination_id NOT NULL` com `ON DELETE RESTRICT` — a config nunca referencia
  um destino inexistente; remover destino exige desativar o automático antes
  (RESTRICT deixa o DELETE do destino falhar com FK — comportamento intencional,
  visível ao vendedor como erro ao excluir; documentar no frontend).
- **Sem row default** — linha ausente = desabilitado (a API devolve defaults
  calculados, D5.1).

### D2.2 JPA + porta

- `AutoWithdrawConfigJpaEntity` + `AutoWithdrawConfigJpaRepository`
  (`findByMerchantId`, `findByEnabledTrue`).
- `AutoWithdrawConfigJpaAdapter` implementa a porta `AutoWithdrawConfigRepository`
  (`domain/port/out`):
  - `Optional<AutoWithdrawConfig> findByMerchantId(UUID merchantId)`
  - `List<AutoWithdrawConfig> findEnabled()`
  - `AutoWithdrawConfig save(AutoWithdrawConfig config)`
- Mapper `AutoWithdrawConfigMapper` (entity ↔ domínio), padrão dos demais mappers
  (ex.: `WithdrawalDestinationMapper`).

## D3 — Domínio

`AutoWithdrawConfig` (`domain/model/withdrawal/`): record/classe imutável com
`id, merchantId, enabled, threshold (Money), destinationId, createdAt,
updatedAt`.

- Fábricas: `enable(merchantId, threshold, destinationId)`,
  `update(enabled, threshold, destinationId)`, `restore(...)` (persistência).
- Validação no domínio: `threshold` via `Money.of` (rejeita `> 2 casas` — regra
  M9, Spec 26) **e** `threshold > 0` validado **explicitamente** (`Money` permite
  negativo — cada uso valida seu domínio, padrão de `PixTransaction.create`);
  `enabled=true` exige `destinationId != null` (`IllegalArgumentException` →
  400 `invalid_argument` no handler do portal).
- `disable()` → `enabled=false` (mantém `destinationId` anterior para o próximo
  enable ser 1-clique; o job ignora configs desabilitadas).

## D4 — Configuração (`foxcash.auto-withdraw.*`)

Novo `AutoWithdrawProperties` (`@ConfigurationProperties("foxcash.auto-withdraw")`):

| Propriedade | Tipo | Default | Env (prod) | Uso |
|---|---|---|---|---|
| `enabled` | `boolean` | `true` | `NYX_AUTO_WITHDRAW_ENABLED` | Kill-switch global (job não executa nada) |
| `cron` | `String` | `0 */5 * * * *` | `NYX_AUTO_WITHDRAW_CRON` | Cron do scheduler (UTC) |
| `default-threshold` | `Money` | `1000.00` | `NYX_AUTO_WITHDRAW_DEFAULT_THRESHOLD` | Default devolvido no GET não-configurado |
| `min-threshold` | `Money` | `1.00` | `NYX_AUTO_WITHDRAW_MIN_THRESHOLD` | Piso de validação do PUT |
| `max-withdrawals-per-run` | `int` | `100` | `NYX_AUTO_WITHDRAW_MAX_PER_RUN` | Teto de saques criados por execução |

Bloco `auto-withdraw:` sob `foxcash:` nos `application*.yml` (base com defaults,
prod com env vars sem default). Binding testado contra o yml (padrão Spec 68 —
camada 3) e `@ConfigurationProperties` registrado (padrão `PspProperties`/
`BillingProperties`).

## D5 — Services

### D5.1 `AutoWithdrawConfigService` (portal — leitura/escrita da config)

- `ConfigView get(UUID merchantId)`:
  - config existente → `{enabled, threshold, destinationId}`;
  - ausente → `{enabled: false, threshold: properties.getDefaultThreshold(),
    destinationId: null}` (**200, nunca 404** — decisão deliberada: settings com
    default da plataforma; o frontend não duplica o limiar; ver D11).
- `ConfigView update(UUID merchantId, boolean enabled, Money threshold,
  UUID destinationId)` — **mesma transação**:
  1. `threshold` obrigatório; `Money.of` rejeita `> 2 casas` (400
     `invalid_argument`) e `threshold <= 0` validado explicitamente (400 —
     `Money` permite negativo, o serviço valida); `threshold < min-threshold` →
     400 `invalid_argument`.
  2. `enabled=true` → `destinationId` obrigatório (400); resolve via
     `destinationRepository.findByIdAndMerchantId(destinationId, merchantId)` →
     ausente → 404 `destination_not_found`; `!active` → 409
     `destination_inactive` (mesmas regras do saque manual, Spec 32).
  3. `enabled=false` → `destinationId` pode ser `null` (grava o anterior se
     enviado).
  4. Upsert: ausente → `AutoWithdrawConfig.enable(...)` + `save`; presente →
     `update(...)` + `save`.
  5. `activityRecorder.record(MERCHANT, merchantId,
     AUTO_WITHDRAW_SETTINGS_UPDATED, "AUTO_WITHDRAW", config.id(), merchantId,
     {enabled, threshold, destinationId})` — sem PII.

### D5.2 `AutoWithdrawRunnerService` (job — execução do gatilho)

- `RunReport runAll(Instant now)`:
  - `!properties.isEnabled()` → log INFO "auto-withdraw desligado" e retorna
    (kill-switch).
  - `configRepository.findEnabled()` → para cada config, em ordem:
    `created = runForConfig(config)`; soma `created`; **pára quando `created >=
    max-withdrawals-per-run`** (os restantes ficam para a próxima execução).
    Skips (não criam saque) **não contam** no teto.
- `runForConfig(config)`:
  1. Destino: `destinationRepository.findByIdAndMerchantId(config.destinationId(),
     config.merchantId())` → ausente → skip (log WARN); `!active` → skip (log
     WARN — destino desativado depois da config).
  2. Fase A: `processingPersister.createMerchantAutoWithdrawal(merchantId,
     destination, config.threshold(), billing.isBlockWithdrawalsOnDebt())`.
     Exceções esperadas → **skip silencioso** (log INFO, sem propagar):
     `MerchantNotActiveException`, `WithdrawalsBlockedException`,
     `OutstandingDebtException`, `AutoWithdrawBelowThresholdException`,
     `InsufficientBalanceException`, `MerchantNotFoundException`.
  3. Fase B (FORA da transação, mesmo padrão do `WithdrawalService`):
     `payoutProvider.createPayout(PayoutCommand(id, amount, toPayoutDestination(destination),
     "saque automatico"))` → `submissionPersister.submit(id, ref,
     provider.toUpperCase())` → `activityRecorder.record(SYSTEM, null,
     AUTO_WITHDRAW_REQUESTED, "WITHDRAWAL", id, merchantId, {amount})`.
  4. Falha síncrona do PSP → `settlement.settleFailedByWithdrawalId(id,
     "Falha no PSP: ...")` (estorno + `FAILED` + ledger `WITHDRAWAL_REVERSAL`) e
     **rethrow** (o `JobExecutionService` marca o job como falho e emite
     `JOB_FAILED` — Spec 53; o saldo foi restaurado, então a próxima execução
     re-tenta naturalmente).
- **Sem leitura de saldo fora do lock**: o threshold e o valor são avaliados
  dentro da Fase A (D5.3) — sem corrida de crédito/débito concorrente.

### D5.3 `WithdrawalProcessingPersister.createMerchantAutoWithdrawal` (Fase A)

Método **novo** na classe existente (mesmo padrão de `createMerchantWithdrawal`,
com a determinação do valor **dentro** do lock):

```java
@Transactional
public MerchantWithdrawal createMerchantAutoWithdrawal(UUID merchantId,
        WithdrawalDestination destination, Money threshold, boolean blockOnDebt) {
    Merchant merchant = merchantRepository.findByIdForUpdate(merchantId)
            .orElseThrow(() -> new MerchantNotFoundException(merchantId));
    merchant.markOutstandingDebt(receivableRepository.hasOpenDebt(merchantId));
    merchant.ensureNoOutstandingDebt(blockOnDebt);                 // dívida → skip
    if (merchant.walletBalance().amount().compareTo(threshold.amount()) < 0) {
        throw new AutoWithdrawBelowThresholdException(merchantId,
                merchant.walletBalance(), threshold);              // corrida com refund → skip
    }
    Money amount = merchant.walletBalance();                       // TODO o saldo (decisão)
    merchant.withdraw(amount);                                     // ACTIVE + bloqueio + saldo
    merchantRepository.save(merchant);
    Withdrawal withdrawal = Withdrawal.processing(merchantId, amount,
            destination.id(), destination.snapshotValue(), WithdrawalType.MERCHANT, null);
    Withdrawal saved = withdrawalRepository.save(withdrawal);
    ledgerRecorder.record(merchant, WalletLedgerEntryType.WITHDRAWAL_DEBIT,
            saved.amount(), "WITHDRAWAL", saved.id());
    return new MerchantWithdrawal(saved, merchant.walletBalance());
}
```

Nova exceção de domínio `AutoWithdrawBelowThresholdException`
(`domain/exception/`): **não** mapeada para HTTP (só o job a trata como skip).

### D5.4 Reuso explícito (nada de duplicar regra)

| Peça | Reusada de |
|---|---|
| Destino ativo/404/409 | `WithdrawalService.withdraw` (mesma porta + mesmas exceções) |
| Fase A (débito + ledger) | `WithdrawalProcessingPersister` (novo método, D5.3) |
| Fase B (PSP + submit) | `payoutProvider` + `WithdrawalSubmissionPersister` (iguais) |
| Estorno de falha síncrona | `WithdrawalSettlementService.settleFailedByWithdrawalId` |
| Conclusão/estorno assíncrono | `WithdrawalSettlementService` + `WithdrawalReconciliationService` |
| Ledger / activity / audit | `WalletLedgerRecorder` / `PlatformActivityRecorder` (existente) |

## D6 — Scheduler

`AutoWithdrawalScheduler` (`infrastructure/scheduler/`), padrão exato do
`WithdrawalReconciliationScheduler` (Spec 36/53):

```java
@Scheduled(cron = "${foxcash.auto-withdraw.cron:0 */5 * * * *}", zone = "UTC")
@SchedulerLock(name = "foxcash-auto-withdraw", lockAtMostFor = "PT15M", lockAtLeastFor = "PT30S")
public void run() {
    jobs.run("foxcash-auto-withdraw", () -> runner.runAll(Instant.now()));
}
```

`JobExecutionService` (Spec 53) grava `job_runs` (trilha auditável), métricas
`nyx_job_*` e publica `JOB_FAILED` no outbox em falha não tratada — o run report
(retorno do `runAll`) alimenta o log de conclusão (criados/skips).

## D7 — REST (portal)

`AutoWithdrawController` (`infrastructure/inbound/rest/`), base
`/api/v1/portal/auto-withdraw`, `ROLE_MERCHANT_PORTAL`, principal = merchantId
(nunca do body — impossível IDOR, mesmo padrão do `PortalWalletController`):

- `GET` → 200 `AutoWithdrawConfigResponse {enabled: bool, threshold: BigDecimal,
  destinationId: UUID|null}` (D5.1 — nunca 404).
- `PUT` body `AutoWithdrawConfigRequest {enabled: bool, threshold: BigDecimal,
  destinationId: UUID?}` → 200 com `AutoWithdrawConfigResponse`.

DTOs em `infrastructure/inbound/rest/dto/`; `threshold` como `BigDecimal`
(serialização padrão de `Money.amount()` — mesmo do `PortalWalletBalanceResponse`).
Validação de shape no DTO (`@NotNull`/`@Positive` no `threshold`) + regras de
domínio no service (M9/min-threshold/404/409). Exceções mapeadas no
`GlobalExceptionHandler`: `invalid_argument` (400), `destination_not_found`
(404), `destination_inactive` (409) — handlers existentes.

## D8 — Frontend (portal)

`portal/src/pages/WithdrawPage.tsx` — novo card "Saque automático" (abaixo do
"Solicitar saque"):

- Estado local: `enabled`, `threshold`, `destinationId` — carregados de
  `GET /api/v1/portal/auto-withdraw` (threshold pré-preenchido com o default).
- Controles: checkbox/toggle `enabled`, input numérico `threshold` (step 0.01),
  `<select>` de destinos ATIVOS (`destinations.data` já carregado na página,
  `d.status === 'ACTIVE'`) preselecionado com `destinationId`.
- Salvar → `PUT /api/v1/portal/auto-withdraw` com `ErrorBanner`/mensagem de
  sucesso (padrões existentes da página); recarrega o estado.
- Desabilitado quando `activeDestinations.length === 0` (hint "cadastre um
  destino de saque primeiro").
- Texto explicativo: "Quando o saldo atingir R$ X, sacamos automaticamente todo
  o valor para o destino escolhido."
- Validação cliente: `threshold > 0`; ao ligar, exige destino selecionado.
- Nunca exibe chave Pix em claro — usa `mask`/`label` (Spec 31, já no DOM da
  página).

## D9 — Eventos / atividade / enums

- `PlatformAction` (+2, seção "Operações financeiras"):
  - `AUTO_WITHDRAW_SETTINGS_UPDATED` (config alterada — D5.1);
  - `AUTO_WITHDRAW_REQUESTED` (saque automático criado — D5.2, actor `SYSTEM`).
- Conclusão reusa `WITHDRAWAL_COMPLETED` (settlement) → outbox → webhook de
  saída (Spec 35) — **nenhum evento novo** no pipeline de conclusão.

## D10 — Testes

Unit (padrão dos testes existentes):

- `AutoWithdrawConfigTest` — enable/update/disable/restore; validação de
  threshold (<= 0, > 2 casas), destinationId obrigatório quando enabled.
- `AutoWithdrawConfigServiceTest` — GET com config e sem config (default do
  properties); PUT validações (threshold 400, min-threshold 400, destinationId
  obrigatório 400, 404 `destination_not_found`, 409 `destination_inactive`),
  upsert create/update, auditoria `AUTO_WITHDRAW_SETTINGS_UPDATED`.
- `AutoWithdrawRunnerServiceTest` — skips (config ausente/desabilitada, destino
  ausente/inativo, não-ACTIVE, bloqueado, dívida, abaixo do limiar no lock);
  sucesso com `amount == walletBalance`; PSP falha síncrona →
  `settleFailedByWithdrawalId` + rethrow; kill-switch; `max-withdrawals-per-run`
  (pára após N criados, skips não contam).
- `WithdrawalProcessingPersisterTest` — `createMerchantAutoWithdrawal`: valor =
  saldo sob lock, abaixo do limiar → `AutoWithdrawBelowThresholdException`,
  ledger `WITHDRAWAL_DEBIT` registrado, 1 única entry.
- `AutoWithdrawPropertiesTest`/context (Spec 68 camadas 1–3) — binding contra o
  yml real (defaults + overrides), `enabled`/`cron`/`default-threshold`/
  `min-threshold`/`max-withdrawals-per-run`.

E2E (novo cenário, `e2e-tests/tests/`):

1. Portal cria destino → `PUT /api/v1/portal/auto-withdraw`
   `{enabled: true, threshold: 1000, destinationId}` → 200; `GET` reflete.
2. Validações: threshold inválido → 400; ligar sem destino → 400; destino de
   outro lojista → 404; destino inativo → 409.
3. Venda PAID credita saldo `>=` limiar (webhook do PSP) → aguarda o job (cron
   curto no perfil dev/test — ver D10.1) → saque `PROCESSING` criado
   automaticamente com `amount == saldo` → webhook de saída `PAID` → `COMPLETED`
   → saldo pós-débito ~0.
4. `PUT {enabled: false}` → nova venda acima do limiar **não** gera saque.
5. Auditoria: `AUTO_WITHDRAW_SETTINGS_UPDATED` e `AUTO_WITHDRAW_REQUESTED` no
   banco.

### D10.1 Execução do job no E2E

O Spring cron aceita campo de segundos; no perfil dev/test o E2E seta
`foxcash.auto-withdraw.cron: "*/30 * * * * *"` (a cada 30s) e faz **polling** do
`GET /merchants/{id}/withdrawals` por até ~90s após o PAID (padrão tolerante a
timing). O CI já documenta o padrão de envs do E2E (`NYXCASH_SECURITY_RATE_LIMIT_ENABLED=false`).

## D11 — Decisões de design (com racional)

| # | Decisão | Racional |
|---|---|---|
| D11.1 | `GET /auto-withdraw` → **200 com defaults** (nunca 404) | Settings com default da plataforma (`default-threshold`); 404 forçaria o frontend a duplicar o limiar. Desvio deliberado do padrão `store_not_found` (recurso de dados); aqui é preferência com default autoritativo no backend. |
| D11.2 | Valor e threshold avaliados **dentro do lock** (Fase A) | Sem corrida de crédito/refund: `amount` é sempre o saldo real no momento do débito — zero resíduo e zero saque abaixo do limiar. |
| D11.3 | Job **in-process** com ShedLock (não worker) | Mesmo padrão dos 6 jobs existentes (Spec 53); o gatilho é tolerante a atraso (minutos); worker externo fica adiado. |
| D11.4 | `destination_id ON DELETE RESTRICT` | Config nunca órfã; excluir destino ativo da config falha na FK (erro visível) em vez de auto-saque com destino sumido. |
| D11.5 | Skips **não contam** no `max-withdrawals-per-run` | Teto protege pool/PSP do **trabalho real** (saques criados); skips são leituras + lock baratos. |
| D11.6 | `AUTO_WITHDRAW_REQUESTED` separado de `WITHDRAWAL_REQUESTED` | Ops distingue saque manual de automático na trilha (Spec 41) sem tocar o contrato existente. |
| D11.7 | Payout `FAILED` → re-try natural na próxima execução | O estorno restaura o saldo acima do limiar; sem lógica extra de retry (auto-retry por re-gatilho). |

## D12 — Rastreabilidade (critérios ↔ design ↔ task)

| Critério (requirements) | Design | Task |
|---|---|---|
| 1 (modelo persistido) | D2 | F0 |
| 2 (API portal) | D5.1, D7 | F2 |
| 3 (auditoria) | D5.1, D9 | F1, F2 |
| 4 (job + kill-switch) | D4, D6 | F3 |
| 5 (gatilho e valor) | D5.2, D5.3 | F1 |
| 6 (regras existentes → skip) | D5.2, D5.3 | F1 |
| 7 (anti-duplo) | D5.2, D5.3, D6 | F1 |
| 8 (falha síncrona PSP) | D5.2, D5.4 | F1 |
| 9 (conclusão + atividade) | D5.2, D9 | F1 |
| 10 (limite por execução) | D4, D5.2 | F1 |
| 11 (frontend) | D8 | F4 |
| 12 (sem quebra + testes) | D10, D10.1 | F5 |
