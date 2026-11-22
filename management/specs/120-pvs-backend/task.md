---
name: PVS backend — pagamento via saldo (Pix Copia e Cola estático)
status: audited
---

# Spec 120 — Task: PVS backend (pagamento via saldo)

Ordem: F0 (parser) → F1 (domínio/Fase A) → F2 (serviço) → F3 (endpoints) → F4
(migration/config) → F5 (testes/E2E).

## F0 — PixCodeParser (critério 1)

- [ ] T0.1: `domain/pvs/PixCodeParser.java` + `ParsedPix(key, keyType, amount)`
      (D2) — varredura TLV (`26`/`00`/`01`/`25`, `54`, `63`, `80`/`81`), chave
      estática, valor opcional, **CRC-16-CCITT**.
- [ ] T0.2: Rejeições com mensagens claras: `pvs_invalid_code` (não inicia com
      `000201`, TLV malformado, CRC inválido/ausente, `26.01` ausente),
      `pvs_dynamic_pix_not_supported` (`26.25` ou `80`/`81`),
      `pvs_unsupported_key` (formato fora de CPF/CNPJ/phone/EVP). Máscara do
      `emv` em qualquer log/erro (padrão `mask()` da Spec 47).
- [ ] T0.3: `PixCodeParserTest` (tabela, D9) — estático CPF/CNPJ/phone/EVP com e
      sem valor; CRC ok/inválido; dinâmico; malformado; chave desconhecida.

## F1 — Domínio + Fase A (critérios 2, 4, 5)

- [ ] T1.1: `WithdrawalType` + `BILL_PAYMENT` (enum; `@Enumerated(STRING)` length
      20 — verificado na spec; **sem migração de coluna**).
- [ ] T1.2: `WithdrawalRepository.sumAmountAndFeeByMerchantAndTypeCreatedSince(
      merchantId, type, since)` + `WithdrawalJpaRepository` (`@Query`, usa o
      índice V71) + adapter.
- [ ] T1.3: `PvsLimitExceededException` (`domain/exception/`, carrega o code:
      `pvs_max_per_payment_exceeded`/`pvs_daily_cap_exceeded`).
- [ ] T1.4: `WithdrawalProcessingPersister.createBillPaymentWithdrawal(...)`
      (D3) — sob `findByIdForUpdate`: gates de conta reusados (ACTIVE/
      desbloqueio/inadimplência/saldo) + teto por operação + teto diário (soma
      do dia, **todas as tentativas**) → `Withdrawal.processing(..., null,
      pixKey, BILL_PAYMENT, null)` + ledger `WITHDRAWAL_DEBIT` +
      `WITHDRAWAL_FEE_DEBIT` + `WITHDRAWAL_FEE` (master).
- [ ] T1.5: Testes (padrão `WithdrawalProcessingPersisterTest`): débito valor +
      taxa, 3 entries de ledger, snapshot = chave, `destination_id = null`; teto
      diário (2 concorrentes → 1 passa, SEC-19); teto por operação; saldo
      insuficiente; inadimplência/bloqueio (reuso).

## F2 — PvsPaymentService (critérios 2, 5, 8)

- [ ] T2.1: `domain/port/in/PvsPaymentUseCase` (pay/limits + records) e
      `application/service/PvsPaymentService.java` (D4).
- [ ] T2.2: IDOR guard (SEC-13 — `AuthenticatedMerchantResolver`); parse antes da
      transação; resolução do valor (EMV 54 vs `amount`: obrigatório sem valor,
      `pvs_amount_mismatch` quando divergem); Fase A → Fase B
      (`PayoutDestination(parsed.key(), null, null, null, null)`, descrição
      "pagamento via saldo"); falha síncrona → `settlement.settleFailedByWithdrawalId`
      (estorno valor + taxa) + rethrow; aceite → `submissionPersister.submit`
      (provider do router/config); trilha `PlatformAction.BILL_PAYMENT_REQUESTED`
      (`{amount}` apenas).
- [ ] T2.3: `PlatformAction` + `BILL_PAYMENT_REQUESTED`.
- [ ] T2.4: `PvsPaymentServiceTest` (mock do provider, padrão `WithdrawalServiceTest`):
      sucesso (destino montado com a chave parseada), mismatch/amount_required,
      **falha do PSP → estorno**, aceite → submit + trilha, IDOR guard, limits.

## F3 — Endpoints (critérios 2, 3, 6)

- [ ] T3.1: `PvsController` (`/api/v1/portal/pvs`, ROLE_MERCHANT_PORTAL) —
      `POST pay` (shape mínimo do `pixCode`: não-nulo, ≤ 1024) e `GET limits`;
      mapeamento 400/409; `merchantId` do principal. Prefixo novo no
      `VARIABLE_PREFIXES` do `RateLimitKey` (padrão SEC-23/Spec 69).
- [ ] T3.2: `PvsControllerTest` — RBAC, shape, erros 400/409, limits
      (used/remaining), 200 do pay.

## F4 — Migration + config (critérios 7)

- [ ] T4.1: `V71__pvs.sql` *(desvio: V68 no design — V69/V70 já aplicadas pelas
      Specs 122/124; Flyway sem `out-of-order`)* — `ck_withdrawals_type` CHECK
      (dados existentes passam) + `ix_withdrawals_merchant_type_created`.
- [ ] T4.2: `PvsProperties` (`foxcash.pvs.*`, fail-fast > 0, padrão Spec 68/L16)
      + bloco no `application.yml` (daily-cap 5000.00, max-per-payment 1000.00).

## F5 — Validação final

- [ ] T5.1: `mvn test` verde (novos + regressão de saque/webhook/reconciliação —
      nada deve ter mudado neles).
- [ ] T5.2: E2E (`e2e-tests/`): PVS com mock payout → webhook PAID → COMPLETED
      no histórico do portal; FAILED → estorno de valor + taxa no wallet.
- [ ] T5.3: Atualizar `deploy/payout-homologation.md` se necessário (PVS usa o
      mesmo adapter — nada de novo no runbook de payout, só a rota nova).

**Âncoras validadas na spec (sem achado de escopo):** `withdrawals.type`
`VARCHAR(20)` sem CHECK (V21); `WithdrawalJpaEntity.type`
`@Enumerated(EnumType.STRING)` length 20; `PayoutProviderClient.PayoutDestination`
(pixKey...) + `PagarMePayoutProviderClient` (infere cpf/cnpj/phone/evp);
`WithdrawalProcessingPersister`/`WithdrawalSubmissionPersister`/
`WithdrawalSettlementService`/`WithdrawalReconciliationService` (reuso total);
`WithdrawalJpaRepository.findByMerchantIdOrderByCreatedAtDesc` sem filtro de
tipo (histórico já mostra BILL_PAYMENT); `PricingProperties.getWithdrawalFee()`
(R$ 2,50); migrations V60–V67 reservadas (mas **não implementadas** — a última
real aplicada é a V70 das Specs 122/124) → **V71** (não V68).

---

## Implementação (2026-08-15) — desvios registrados

**1. Migration `V71__pvs.sql` (não V68):** o design previa V68 assumindo que as
V60–V67 seriam consumidas pelas Specs 97/98 e 101–119 antes do PVS. Isso não
ocorreu (essas specs seguem `verified`) e, em contrapartida, as Specs 122/124
aplicaram **V69/V70**. Flyway sem `out-of-order` **rejeita** uma migration nova
com versão menor que a última aplicada (V70) — usar V68 quebraria o boot e o CI
(`flyway:validate`/`flyway-coldstart`). PVS usa **V71** (próximo livre). Quando
as specs 97–119 forem implementadas, seus números de migration (V60–V67) serão
re-avaliados sobre a última real aplicada.

**2. `createBillPaymentWithdrawal(..., boolean blockOnDebt)`:** a assinatura do
design (D3) omitia o `blockOnDebt`; o gate de inadimplência (Spec 33) é
configurável (`foxcash.billing.block-withdrawals-on-debt`), e todos os demais
métodos da Fase A (`createMerchantWithdrawal`/`createMerchantAutoWithdrawal`) o
recebem como parâmetro. O serviço o passa de `BillingProperties` — o desvio
alinha a Fase A do PVS ao padrão do saque (mesmo gate, mesma config).

**3. Erros de valor** (`pvs_amount_required`/`pvs_amount_mismatch`) lançados por
`PvsAmountException` (nova, domain/exception) — 400 com o código específico
(handler no `GlobalExceptionHandler`), ao lado de `PixCodeParseException` (parser)
e `PvsLimitExceededException` (tetos, 409).

## Auditoria (2026-08-15) — 9/9 critérios OK, 0 achados de código

Verificação 1:1 contra o código real (critérios do requirements):

- **C1 (parser)** — `PixCodeParser.parse` cobre todas as rejeições (prefixo,
  TLV malformado/truncado, CRC ausente/inválido, 26.25/80/81 dinâmico, chave
  ausente, GUI não-Pix, chave desconhecida) com os códigos canônicos; `emv`
  nunca logado (só `mask(key)` nas mensagens — grep por `pixCode` em logs: 0
  ocorrências). `PixCodeParserTest` 18/18 (incl. vetor cross-verificado + CRC
  check-value 0x29B1).
- **C2 (pay)** — `PvsPaymentService.pay`: IDOR guard (SEC-13), parse antes da
  transação, `resolveAmount` (mismatch/required), Fase A
  (`createBillPaymentWithdrawal` — ACTIVE/desbloqueio/inadimplência/saldo +
  tetos sob `findByIdForUpdate` SEC-19, `destination_id` null + snapshot chave,
  ledger 3 entries), Fase B fora da transação + estorno `settleFailedByWithdrawalId`
  (valor+taxa) + `submissionPersister.submit`. `PvsPaymentServiceTest` 8/8 +
  `WithdrawalProcessingPersisterTest` 21/21.
- **C3 (limits)** — `limits()` read-only, sem lock; remaining = cap − used com
  floor 0 (testes 2). Controller `GET /api/v1/portal/pvs/limits` → 200 shape
  completo.
- **C4 (teto diário sem corrida)** — avaliação dentro da Fase A sob o lock do
  lojista (`findByIdForUpdate`); o `used` é re-lido após adquirir o lock
  (serializado). Coberto por teste de Fase A.
- **C5 (estorno/reconciliação intactos)** — `WithdrawalSettlementService` sem
  mudança; BILL_PAYMENT é não-ADMINISTRATIVE → FAILED credita valor+taxa +
  `WITHDRAWAL_REVERSAL`/`WITHDRAWAL_FEE_REVERSAL` + master `WITHDRAWAL_FEE_REVERSAL`.
  E2E test 31 (FAILED → wallet 100 restaurado + ledger 1×1×1×1).
- **C6 (histórico)** — `findByMerchantIdOrderByCreatedAtDesc` sem filtro de tipo;
  E2E test 29 verifica o BILL_PAYMENT no histórico do portal (`type=BILL_PAYMENT`,
  `destination` mascarado `***`).
- **C7 (migration V71)** — `V71__pvs.sql` aplicada no banco dev (flyway
  `success=t`), `ck_withdrawals_type` + `ix_withdrawals_merchant_type_created`
  presentes. Desvio de numeração (V68→V71) documentado (V69/V70 das Specs 122/124).
- **C8 (trilha)** — `BILL_PAYMENT_REQUESTED` registrado no aceite com
  `Map.of("amount", ...)` (chave nunca na trilha); verificado no
  `PvsPaymentServiceTest`.
- **C9 (testes)** — `mvn test` **1865/0/0** (53 PVS novos) + E2E `pvs.spec.js`
  **3/3** + regressão portal-ui/auto-withdraw/pvs **10/10**.

Verificações de coerência adicionais: rate limit por PRINCIPAL
(`AuthenticatedRateLimitFilter`, Spec 58) cobre `/api/v1/portal/pvs` (rota
`/api/v1/**` autenticada) com bucket colapsado por `RateLimitKey` (`/portal/pvs`);
controller sob `/api/v1/portal/**` → `ROLE_MERCHANT_PORTAL` (SecurityConfig);
`PvsProperties` fail-fast (>0) com binding no yml (env `NYX_PVS_*`).
