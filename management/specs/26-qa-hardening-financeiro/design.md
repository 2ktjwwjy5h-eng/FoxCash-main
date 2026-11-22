# Spec 26 — Design (hardening financeiro e de contrato)

Implementação retrospectiva da auditoria QA de 2026-08-04 sobre specs já
concluídas (14/16/19/20/23). Nenhuma migration necessária.

## 1. Concorrência (C1) — locks pessimistas

**Por quê não `@Version`:** os adapters (`MerchantPersistenceAdapter.save`,
`TransactionPersistenceAdapter.save`) recriam a entidade JPA via mapper a cada
`save()` — a versão se perderia (default 0) e todo `UPDATE` de registro
existente conflitaria. Lock pessimista evita propagar versão pelo domínio e
serializa exatamente os fluxos que tocam o saldo.

**Mudanças:**
- `MerchantJpaRepository.findByIdForUpdate(UUID)` e
  `PixTransactionJpaRepository.findByExternalReferenceForUpdate(String)` —
  `@Lock(LockModeType.PESSIMISTIC_WRITE)` + `@Query`.
- Ports `MerchantRepository.findByIdForUpdate` e
  `TransactionRepository.findByExternalReferenceForUpdate` + implementação nos
  adapters.
- Consumo (todos `@Transactional`):
  - `ProcessPixWebhookService.process` — transação com lock (replay de `PAID`
    concorrente → o 2º vê `APPROVED` → no-op) e merchant com lock no crédito.
  - `WithdrawalService.withdraw` — merchant com lock (saques serializados).
  - `RegisterChargebackService.register` — merchant com lock no débito.

**Ordem de lock consistente** (transação → merchant) → sem deadlock entre os
fluxos.

## 2. Estorno financeiro (H1)

- `Money`: remoção da restrição de negativo (escala 2 mantida); cada uso
  valida seu domínio (ex.: `PixTransaction.create` exige valor > 0).
- `Merchant.debit(Money)`: `walletBalance = walletBalance.subtract(amount)`
  — sem validações de saque; pode ficar negativo.
- `ProcessPixWebhookService`: `case REFUNDED -> { transaction.refund();
  debitWallet(transaction); }` — idempotência mantida pelo early-return de
  status igual.
- `RegisterChargebackService`: novo pré-requisito `status == APPROVED` (sem
  crédito → sem estorno) e débito do wallet na mesma transação.

## 3. Contrato de cobrança (H3, M1/M6)

- `PixProviderClient.CreateChargeCommand` ganhou `Integer expirationSeconds`
  (null → default do PSP). Chamadas atualizadas: `CreatePixChargeService`
  (propaga do `Command`) e `CreatePixTransactionService` (null — fluxo legado).
- `PagarMePixProviderClient`: `expires_in = command.expirationSeconds() != null
  ? … : expiresInSeconds` (config). 
- `CreatePixChargeService` agora injeta `PortalProperties` e valida
  `amount ≤ charge-max-amount` e `expirationSeconds > 0` (quando informado) →
  cobre `/nyx/pix/cob`, portal avulsa e gift.
- `GiftPresetService.resolveAmount`: `presetId` XOR `amount` (ambos → 400).

## 4. Resiliência/CI (H2, M2)

- `.github/workflows/ci.yml`: health check com `X-Api-Key:
  dev-only-admin-api-key`; comentários de contagem atualizados.
- `PagarMePixProviderClient`: `SimpleClientHttpRequestFactory` (connect 5s /
  read 10s).

## 5. Cadastro concorrente (M5)

- `RegisterMerchantService.register`: `try/catch` em `merchantRepository.save`
  → `DataIntegrityViolationException` → `MerchantAlreadyExistsException` (409).

## 6. Polimento (L1, L4)

- `ApiKeyAuthFilter`: chave admin comparada com `MessageDigest.isEqual`.
- `GiftPresetService`: rejeita `presetId`+`amount` juntos.

## Arquivos alterados

- Domínio: `Money`, `Merchant`, `PixProviderClient`, ports `MerchantRepository`/
  `TransactionRepository`.
- Aplicação: `CreatePixChargeService`, `CreatePixTransactionService`,
  `ProcessPixWebhookService`, `WithdrawalService`, `RegisterChargebackService`,
  `RegisterMerchantService`, `GiftPresetService`.
- Infra: `MerchantJpaRepository`, `PixTransactionJpaRepository`, adapters de
  persistência, `PagarMePixProviderClient`, `ApiKeyAuthFilter`.
- Testes: +18 (locks via mocks, estorno, teto, expiração, XOR, 409-race,
  controller REST). E2E: cenário 8 (portal) em `merchant-journey.spec.js`.
