# Spec 32 — Task breakdown

Status: `audited` (2026-08-04). Ciclo SDD completo — implementada (commit `f14a044`) e auditada.

## Implementação

- [x] Migration `V21__withdrawal_destination.sql`: `withdrawals` ganha
      `destination_id` (FK), `type` (default 'MERCHANT'), `reason`,
      `destination_key_snapshot` (criptografado) + índice `ix_withdrawals_type`.
- [x] `WithdrawalType` (enum) + `Withdrawal.complete(...)` ampliada (destino,
      snapshot, tipo, reason).
- [x] `Merchant.adminWithdraw(Money)`: valida APENAS saldo (nunca negativo);
      não valida ACTIVE/bloqueio.
- [x] `DestinationNotFoundException` (404 `destination_not_found`) +
      `DestinationInactiveException` (409 `destination_inactive`) + handlers.
- [x] `WithdrawalService.withdraw`: `destinationId` obrigatório no Command;
      valida posse+ativo (404/409); debita sob lock (já existente); grava
      `type=MERCHANT` + `destination_id` + snapshot; registra `WITHDRAWAL_DEBIT`
      no ledger (Spec 27).
- [x] `MasterWithdrawalUseCase` + `MasterWithdrawalService.administrativeWithdraw`
      (novo): valida `destinationKey`+`reason` (400); `adminWithdraw` sob lock;
      grava `type=ADMINISTRATIVE` + `reason`; ledger.
- [x] DTOs: `WithdrawalRequest` ganha `destinationId` (`@NotNull`);
      `AdministrativeWithdrawalRequest`; `WithdrawalResponse` ganha `type` +
      destino mascarado + `walletBalance` pós-débito (POST).
- [x] Controllers: `WithdrawalController` (destino no vendedor);
      `MasterWithdrawalController` (rota
      `/merchants/{id}/withdrawals/administrative`, ROLE_MASTER).
- [x] `WithdrawalJpaEntity` + mapper (colunas novas; snapshot com
      `@Convert(CryptoConverter.class)`).

## Verificação

- [x] `WithdrawalServiceTest`: sem `destinationId` → 400; destino de outro
      lojista → 404; destino inativo → 409; saque válido → 201 + ledger
      (WITHDRAWAL_DEBIT) + snapshot; **sem overdraft**: 2 saques sobre o mesmo
      saldo → o segundo recebe `insufficient_balance`.
- [x] `MasterWithdrawalServiceTest`: admin com bloqueio ativo → 201; admin de
      lojista não-ACTIVE → 201; `amount > saldo` → 409 `insufficient_balance`
      (saldo nunca negativo); sem `reason`/`destinationKey` → 400; ledger
      registrado.
- [x] E2E: cenário 13 (cadastrar destino → saque com destino 201 mascarado;
      bloqueio → vendedor 409 e master administrativo 201; destino de outro
      lojista 404; 400 sem destino; 409 sem saldo); suite **13/13** sem
      regressão (11-13 incluem Spec 27/31/32).
- [x] `mvn test` verde (246 testes, 0 falhas).
- [x] Roadmap `verified` → `implemented`; requirements/design/task atualizados
      com contagens reais.
- [x] `/spec-audit` → `audited` (fim do ciclo SDD).
