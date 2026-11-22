# Spec 14 — Task breakdown (Saques)

## Domínio
- [x] `WithdrawalStatus` enum (`COMPLETED`, `REJECTED`)
- [x] `Withdrawal` (factory `complete` + `restore` + getters)
- [x] `Merchant.withdraw` / `blockWithdrawals` / `releaseWithdrawals` /
      `withdrawalsBlocked()` + campo no `register()`/`restore()`
- [x] `Money.subtract(Money)`
- [x] Exceções: `InsufficientBalanceException`, `WithdrawalsBlockedException`
- [x] Portas: `WithdrawalRepository`, `WithdrawalSearchQuery`,
      `WithdrawalUseCase`, `MasterWithdrawalControlUseCase`,
      `QueryWithdrawalUseCase`

## Aplicação
- [x] `WithdrawalService` (saque: valida + debita + persiste saída COMPLETED)
- [x] `MasterWithdrawalControlService` (block/release)
- [x] `QueryWithdrawalService` (findByMerchantId + search + findById)
- [x] `ProcessPixWebhookService` credita o wallet na aprovação (PAID → APPROVED)

## Infraestrutura
- [x] Migration `V8__withdrawals.sql` (tabela + índices + coluna da flag)
- [x] `WithdrawalJpaEntity` + `WithdrawalMapper` + `WithdrawalJpaRepository` +
      `WithdrawalPersistenceAdapter` (Specifications p/ search)
- [x] `MerchantJpaEntity.withdrawalsBlocked` + `MerchantMapper`
- [x] `WithdrawalController` (POST/GET lojista + `MerchantAccessGuard`)
- [x] `MasterWithdrawalController` (block/release 204 + GET /master/withdrawals)
- [x] `GlobalExceptionHandler` (409 `insufficient_balance`, 409
      `withdrawals_blocked`, 404 `withdrawal_not_found`)
- [x] DTOs: `WithdrawalRequest`, `WithdrawalResponse`

## Testes e verificação
- [x] `WithdrawalServiceTest` (5 testes)
- [x] `MasterWithdrawalControlServiceTest` (3 testes)
- [x] `MerchantTest` (3 novos: withdraw/bloqueio/flag) + `ProcessPixWebhookServiceTest`
      (1 novo: crédito do wallet)
- [x] `mvn test` verde (**100 testes**)
- [x] Docs: task.md, design.md; roadmap `implemented`; README; commit

## Fora de escopo
- [ ] Aprovação por saque (fluxo é automático por decisão do usuário)
- [ ] Estorno de saques ao bloquear (bloqueio impede novos, não desfaz)
- [ ] Débito do wallet em REFUND (reembolso — spec futura)
- [ ] E2E novo de saque (jornada existente inalterada; E2E 6/6)
