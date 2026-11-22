---
name: Saque automático por limiar de saldo (auto-withdraw)
status: audited
---

# Spec 70 — Task: Saque automático por limiar de saldo

## F0 — Domínio + migration (critério 1)

- [x] T0.1: Enum `PlatformAction` += `AUTO_WITHDRAW_SETTINGS_UPDATED` e
      `AUTO_WITHDRAW_REQUESTED` (seção "Operações financeiras", D9).
- [x] T0.2: Exceção de domínio `AutoWithdrawBelowThresholdException`
      (`domain/exception/`) — **não** mapeada no `GlobalExceptionHandler` (só o
      job a trata como skip).
- [x] T0.3: Migration `V48__merchant_auto_withdraw_config.sql` (D2.1) — **checar
      o próximo número livre** em `src/main/resources/db/migration` (V47 está
      reservado pela Spec 69; usar V48+ se a 69 for aplicada antes). Tabela +
      índice `ix_merchant_auto_withdraw_enabled`; `merchant_id UNIQUE`, FK
      `destination_id` `ON DELETE RESTRICT`.
- [x] T0.4: `AutoWithdrawConfigJpaEntity` + `AutoWithdrawConfigJpaRepository`
      (`findByMerchantId`, `findByEnabledTrue`) + `AutoWithdrawConfigMapper`.
- [x] T0.5: Porta `AutoWithdrawConfigRepository` (D2.2) + adapter JPA.
- [x] T0.6: `AutoWithdrawConfig` (D3) — `enable`/`update`/`disable`/`restore` +
      validações (threshold via `Money.of` — `> 2 casas` rejeitado — **e** `> 0`
      explícito, pois `Money` permite negativo; destinationId obrigatório quando
      enabled).
- [x] T0.7: Testes — `AutoWithdrawConfigTest` (T0.6); migration aplica limpa
      (flyway); unique 1:1 por merchant.

## F1 — Services + persister (critérios 3, 5–10)

- [x] T1.1: `WithdrawalProcessingPersister.createMerchantAutoWithdrawal`
      (D5.3) — valor = `walletBalance()` **sob lock**; threshold avaliado dentro
      do lock (`AutoWithdrawBelowThresholdException`); reusa
      `merchant.withdraw` (ACTIVE/bloqueio/saldo) + ledger `WITHDRAWAL_DEBIT`.
- [x] T1.2: `AutoWithdrawRunnerService` (D5.2) — `runAll(now)` +
      `runForConfig(config)`: destino ativo (skip WARN), Fase A (skips: não-ACTIVE/
      bloqueado/dívida/abaixo do limiar/insuficiente/lojista sumiu), Fase B
      (PSP + `submissionPersister.submit` + `AUTO_WITHDRAW_REQUESTED` actor
      SYSTEM), falha síncrona → `settleFailedByWithdrawalId` + rethrow;
      kill-switch `properties.isEnabled()`; `max-withdrawals-per-run` conta
      **saques criados** (skips não contam).
- [x] T1.3: `AutoWithdrawConfigService` (D5.1) — `get` (default quando ausente)
      e `update` (validações 400/404/409 + upsert + auditoria
      `AUTO_WITHDRAW_SETTINGS_UPDATED` na mesma transação).
- [x] T1.4: Testes — `WithdrawalProcessingPersisterTest` (T1.1),
      `AutoWithdrawRunnerServiceTest` (T1.2 — todos os skips + sucesso com
      `amount == saldo` + falha PSP + kill-switch + teto por execução),
      `AutoWithdrawConfigServiceTest` (T1.3).

## F2 — REST do portal (critérios 2–3)

- [x] T2.1: DTOs `AutoWithdrawConfigRequest` / `AutoWithdrawConfigResponse`
      (`infrastructure/inbound/rest/dto/`) — `threshold` BigDecimal.
- [x] T2.2: `AutoWithdrawController` (D7) — `GET` (200 com defaults, nunca 404)
      e `PUT` (upsert → 200) em `/api/v1/portal/auto-withdraw`,
      `ROLE_MERCHANT_PORTAL`, principal = merchantId.
- [x] T2.3: Handlers no `GlobalExceptionHandler` reutilizados (400
      `invalid_argument`, 404 `destination_not_found`, 409
      `destination_inactive`).
- [x] T2.4: Testes — `AutoWithdrawControllerTest` (200/400/404/409, IDOR
      impossível — sem parâmetro de lojista, 401 sem token, 403 com API key).

## F3 — Config + scheduler (critério 4)

- [x] T3.1: `AutoWithdrawProperties` (D4) + bloco `auto-withdraw:` nos
      `application*.yml` (base/dev com defaults; prod com env vars sem default)
      + binding testado contra o yml (padrão Spec 68).
- [x] T3.2: `AutoWithdrawalScheduler` (D6) — `@Scheduled` + `@SchedulerLock`
      `foxcash-auto-withdraw` + `jobs.run("foxcash-auto-withdraw", ...)`.
- [x] T3.3: Testes — `AutoWithdrawPropertiesTest`/context (defaults + overrides +
      kill-switch binding); scheduler wiring (ShedLock/job name).

## F4 — Frontend do portal (critério 11)

- [x] T4.1: Card "Saque automático" na `WithdrawPage.tsx` (D8) — toggle, limiar
      (prefill do GET), select de destinos ATIVOS (preselecionado), salvar →
      PUT com `ErrorBanner`/sucesso, desabilitado sem destinos ativos, texto
      explicativo, validação cliente (threshold > 0; destino obrigatório ao
      ligar), sem chave Pix em claro (usa `mask`/`label`).
- [x] T4.2: `npm run build` do portal limpo (tsc sem erro).

## F5 — E2E + docs + fechamento (critérios 12)

- [x] T5.1: Novo cenário E2E (D10/D10.1) — config via API (200/400/404/409),
      venda PAID ≥ limiar → job (cron `*/30 * * * * *` no dev/test + polling até
      ~90s) cria `PROCESSING` com `amount == saldo` → webhook de saída PAID →
      `COMPLETED` → saldo ~0; `enabled=false` → nova venda não gera saque;
      auditoria `AUTO_WITHDRAW_SETTINGS_UPDATED`/`AUTO_WITHDRAW_REQUESTED`.
- [x] T5.2: `mvn test` verde (suíte completa, sem regressão) + E2E completo
      verde.
- [x] T5.3: README (seção curta — config de env `NYX_AUTO_WITHDRAW_*` + endpoint
      do portal) e sync roadmap/requirements/design/task.
- [x] T5.4: `/spec-audit` — verificar os **12 critérios** do requirements um a um.

## Verificação (`/spec-verify` 2026-08-11)

4 achados corrigidos:

1. **GET do portal → 200 com defaults, nunca 404** (requirements critério 2 e
   design D5.1/D7). A primeira versão previa 404 `auto_withdraw_not_found`
   seguindo o padrão `store_not_found`; para settings com default da plataforma
   o 404 força o frontend a duplicar o limiar no cliente. Decisão deliberada
   registrada em D11.1.
2. **`max-withdrawals-per-run` conta saques criados (Fase A OK), não
   tentativas** (critério 10, D5.2, task T1.2). A primeira versão contava
   tentativas; skips são leituras + lock baratos e não devem consumir o teto que
   protege pool/PSP do trabalho real. D11.5.
3. **Número da migration dinâmico** (D2.1, task T0.3): a Spec 69 (design,
   2026-08-11) **reservou V47** para `master_permissions`; a Spec 70 usa o
   **próximo número livre** na implementação (V48+ se a 69 for aplicada antes) —
   nunca colidir com migration aplicada (CI flyway `validate`).
4. **`threshold > 0` é validação explícita do serviço/domínio, não do
   `Money.of`** (requirements critério 2, design D3/D5.1, task T0.6): verificado
   em `Money.java` — o record rejeita `> 2 casas` (M9) mas **permite negativo**
   (cada uso valida seu domínio, ex.: `PixTransaction.create`). A primeira versão
   atribuía o `> 0` ao `Money.of`; corrigido nos 3 documentos.

Referências validadas: V50 (visão), specs 14/31/32/36 (pipeline de saque),
41 (auditoria), 53 (job), 46 (frontend), 26 (M9 — validação de `Money`).
Código verificado: `WithdrawalService`/`WithdrawalProcessingPersister`
(Fase A/B + estorno), `WithdrawalDestinationRepository.findByIdAndMerchantId`,
`Merchant.withdraw/withdrawalsBlocked/markOutstandingDebt`,
`Money` (permite negativo; `Money.of(BigDecimal)` existe),
`JobExecutionService` + `WithdrawalReconciliationScheduler` (padrão ShedLock),
`PlatformActorType.SYSTEM` + `PlatformActivityRecorder.record`,
`PortalWalletController` (padrão do controller do portal),
`WithdrawPage.tsx` (card existente).

## Auditoria (pós-implementação)

- [x] Verificar os **12 critérios** do requirements.md um a um (padrão
      `/spec-audit`).

## Implementação (`/spec-implement` 2026-08-11)

**F0 — Domínio + migration**: `PlatformAction` += `AUTO_WITHDRAW_SETTINGS_UPDATED`/
`AUTO_WITHDRAW_REQUESTED`; `AutoWithdrawBelowThresholdException` (não mapeada —
skip do job); **V48__merchant_auto_withdraw_config.sql** (V47 já é da Spec 69 —
último número livre checado); `AutoWithdrawConfig` (enable/update/disable/restore,
threshold via Money + `> 0` explícito, destinationId obrigatório quando enabled);
`AutoWithdrawConfigJpaEntity`/`JpaRepository`/`Mapper`/`PersistenceAdapter` +
porta `AutoWithdrawConfigRepository` (findByMerchantId/findEnabled/save).

**F1 — Services + persister**: `WithdrawalProcessingPersister.createMerchantAutoWithdrawal`
(valor = `walletBalance()` sob lock; `AutoWithdrawBelowThresholdException` quando
abaixo do limiar; reusa `merchant.withdraw` + ledger `WITHDRAWAL_DEBIT`);
`AutoWithdrawRunnerService` (kill-switch, skips silenciosos, Fase A/B, estorno +
rethrow na falha síncrona do PSP, `max-withdrawals-per-run` contando criados);
`AutoWithdrawConfigService` (GET com defaults nunca 404; PUT upsert + auditoria
na mesma transação). **Ajuste pós-primeiro-run E2E**: `enabled=false` sem
`destinationId` MANTÉM o destino anterior (`destination_id` é NOT NULL — o
requisito "mantém o anterior" não é null; o job ignora configs desabilitadas).

**F2 — REST do portal**: DTOs `AutoWithdrawConfigRequest`/`Response` +
`AutoWithdrawController` em `/api/v1/portal/auto-withdraw` (`ROLE_MERCHANT_PORTAL`,
principal = merchantId; 400 invalid_argument / 404 destination_not_found / 409
destination_inactive via handlers existentes).

**F3 — Config + scheduler**: `AutoWithdrawProperties` (registrado no
ApplicationConfig) + bloco `auto-withdraw:` no `application.yml` (defaults com
env `NYX_AUTO_WITHDRAW_*`) e cron curto no `application-dev.yml` (D10.1);
`AutoWithdrawalScheduler` (ShedLock `foxcash-auto-withdraw`, delegado ao
`JobExecutionService`).

**F4 — Frontend**: card "Saque automático" na `WithdrawPage.tsx` (toggle, limiar
prefill do GET, select de destinos ATIVOS, validação cliente, sem chave em
claro); `npm run build` limpo (tsc).

**F5 — E2E + docs**: `e2e-tests/tests/auto-withdraw.spec.js` (3 cenários:
config + validações; gatilho por saldo → PROCESSING amount==saldo → COMPLETED via
webhook de saída + auditoria no banco; desativa → nova venda não gera saque).
`mvn test` **1346/0/0** (1303 + 43 novos) re-executado; E2E **113/113** — run
completo 112/113; a única falha foi `portal-ui.spec.js` (strict mode: o card novo
duplicou o select de destino → locator escopado ao form "Solicitar saque",
re-executado verde). App dev (`NYXCASH_PORTAL_SERVE_FRONTEND=true`,
`NYXCASH_SECURITY_RATE_LIMIT_ENABLED=false`). README ganhou a seção do auto-saque
(endpoints + env).

## Auditoria (`/spec-audit` 2026-08-11)

**12/12 critérios OK** — verificação um a um:

1. Tabela `merchant_auto_withdraw_config` (V48): `merchant_id UNIQUE`, FK
   `destination_id ON DELETE RESTRICT`, `threshold_amount NUMERIC(15,2)`, sem
   row default (linha ausente = desabilitado). ✅
2. `GET /api/v1/portal/auto-withdraw` → 200 com defaults (nunca 404); `PUT`
   upsert → 200; validações 400/404/409 conforme requisito (threshold
   obrigatório/`> 0`/`>= min` → `invalid_argument`; `enabled=true` sem destino →
   400; destino inexistente/outro lojista → 404; inativo → 409). ✅
3. Auditoria `AUTO_WITHDRAW_SETTINGS_UPDATED` (MERCHANT) na mesma transação,
   details sem PII. ✅
4. Job `AutoWithdrawalScheduler` ShedLock `foxcash-auto-withdraw`, cron
   `foxcash.auto-withdraw.cron` (default 5 min UTC; 30s no dev), kill-switch
   `foxcash.auto-withdraw.enabled`, delegado ao `JobExecutionService`. ✅
5. Gatilho: `wallet_balance >= threshold` sob lock → saque `MERCHANT`
   `PROCESSING` com `amount == saldo` (Fase A) + Fase B fora da transação. ✅
6. Regras existentes respeitadas (ACTIVE/bloqueio/dívida/destino) → skip
   silencioso (log, sem exceção propagada — verificado no unit e E2E). ✅
7. Anti-duplo: débito da Fase A deixa o saldo abaixo do limiar; ShedLock +
   lock pessimista serializam; E2E confirma 1 único saque. ✅
8. Falha síncrona do PSP → `settleFailedByWithdrawalId` + rethrow
   (JobExecutionService marca FAILED; saldo restaurado → re-try natural). ✅
9. Conclusão via webhook de saída/reconciliação (Spec 36) — `WITHDRAWAL_COMPLETED`
   no outbox; `AUTO_WITHDRAW_REQUESTED` (SYSTEM, sem IP) na Fase B. ✅
10. `max-withdrawals-per-run` (default 100) conta saques CRIADOS; skips não
    contam (unit confirma parada após N criados). ✅
11. Card "Saque automático" na `WithdrawPage.tsx` (toggle, limiar prefill,
    destinos ATIVOS, validação cliente, máscara — sem chave em claro); build
    tsc limpo. ✅
12. Sem quebra de contrato: nenhuma rota existente mudou de shape; `mvn test`
    1346/0/0; E2E 113/113 (novo `auto-withdraw.spec.js` 3/3). ✅

Fim do ciclo SDD (roadmap/requirements/design/task `audited`).
