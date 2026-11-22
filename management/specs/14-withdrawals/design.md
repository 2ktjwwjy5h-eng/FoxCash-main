# Spec 14 — Design (Saques: fluxo automático + controle master)

## 1. Domínio

### `Withdrawal` (entidade)
`domain/model/withdrawal/` — nova classe `Withdrawal` (id, `merchantId`,
`Money amount`, `WithdrawalStatus status`, `createdAt`, `completedAt`,
`rejectedAt`) + enum `WithdrawalStatus { COMPLETED, REJECTED }`. Factory
`complete(merchantId, amount)` → status `COMPLETED`, `createdAt = completedAt =
now`. `restore(...)` para o adapter.

### `Merchant` (extensão)
Novo campo `boolean withdrawalsBlocked` (default `false` no `register()`; novo
parâmetro no `restore()`).

```java
public void withdraw(Money amount) {
    ensureActive();                              // MerchantNotActiveException
    if (withdrawalsBlocked) throw new WithdrawalsBlockedException(id);
    if (walletBalance.amount().compareTo(amount.amount()) < 0)
        throw new InsufficientBalanceException(id, amount);
    this.walletBalance = walletBalance.subtract(amount);
}
public void blockWithdrawals()  { this.withdrawalsBlocked = true; }
public void releaseWithdrawals(){ this.withdrawalsBlocked = false; }
```

`Money` ganha `subtract(Money)` (resultado nunca negativo — validado antes).

### Exceções (domínio)
- `InsufficientBalanceException` → handler **409 `insufficient_balance`**.
- `WithdrawalsBlockedException` → handler **409 `withdrawals_blocked`**.

### Portas
- `domain/port/out/WithdrawalRepository`:
  `save`, `findByMerchantId(UUID)`, `search(WithdrawalSearchQuery)`.
- `domain/model/withdrawal/WithdrawalSearchQuery`:
  `(UUID merchantId, WithdrawalStatus status, Instant from, Instant to, int page, int size)`.
- `domain/port/in/WithdrawalUseCase`:
  `Result withdraw(Command)` — `Command(UUID merchantId, Money amount)`,
  `Result(UUID withdrawalId, Money amount, Money walletBalance)`.
- `domain/port/in/MasterWithdrawalControlUseCase`: `block(UUID)`, `release(UUID)`.
- `domain/port/in/QueryWithdrawalUseCase`: `findByMerchantId`,
  `search(WithdrawalSearchQuery)` → `PageResult<Withdrawal>`.

## 2. Aplicação

- `WithdrawalService implements WithdrawalUseCase` (`@Transactional`): carrega
  lojista (404 se não existe), `merchant.withdraw(amount)`, persiste lojista
  (saldo debitado) e a saída `Withdrawal.complete(...)` na mesma transação.
- `MasterWithdrawalControlService implements MasterWithdrawalControlUseCase`:
  carrega lojista, `blockWithdrawals()`/`releaseWithdrawals()`, persiste.
- `QueryWithdrawalService implements QueryWithdrawalUseCase`
  (`@Transactional(readOnly = true)`).

## 3. Infraestrutura

### Migration `V8__withdrawals.sql`
```sql
CREATE TABLE withdrawals (
    id           UUID PRIMARY KEY,
    merchant_id  UUID         NOT NULL REFERENCES merchants(id),
    amount       NUMERIC(15,2) NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    completed_at TIMESTAMP,
    rejected_at  TIMESTAMP
);
CREATE INDEX ix_withdrawals_merchant_id  ON withdrawals(merchant_id);
CREATE INDEX ix_withdrawals_status       ON withdrawals(status);
CREATE INDEX ix_withdrawals_created_at   ON withdrawals(created_at);

ALTER TABLE merchants ADD COLUMN withdrawals_blocked BOOLEAN NOT NULL DEFAULT FALSE;
```

### Persistência
- `WithdrawalJpaEntity` + `WithdrawalMapper` + `WithdrawalJpaRepository`
  (`JpaSpecificationExecutor` + `findByMerchantIdOrderByCreatedAtDesc` +
  `@Query sumCompletedAmountByMerchantId` para o earnings da Spec 15).
- `WithdrawalPersistenceAdapter` (Specifications p/ `search`).
- `MerchantJpaEntity`: campo `withdrawalsBlocked` + getter;
  `MerchantMapper` mapeia nos dois sentidos.

### REST
- `WithdrawalController` (`/api/v1/merchants/{merchantId}/withdrawals`):
  `POST` → 201 `WithdrawalResponse` (acesso via `MerchantAccessGuard`);
  `GET` → lista do lojista.
- `MasterWithdrawalController` (`/api/v1/master`): `POST
  /merchants/{merchantId}/withdrawals/block|release` → 204; `GET
  /withdrawals` → `PageResponse<WithdrawalResponse>` com filtros
  `merchantId`/`status`/`from`/`to` (parsing idêntico ao AdminController).
- `GlobalExceptionHandler`: handlers 409 para as duas novas exceções.
- Segurança: `/api/v1/master/**` já exige `ROLE_MASTER`; rotas de lojista
  já exigem autenticação — sem mudanças no `SecurityConfig`.

### DTOs
- `WithdrawalRequest(amount)` com `@NotNull @DecimalMin("0.01")`.
- `WithdrawalResponse` (`id, merchantId, amount, status, createdAt,
  completedAt, rejectedAt`) com `from(Withdrawal)` e `from(Result)`.

## 4. Testes (unit, Mockito)
- `WithdrawalServiceTest` (~5): sucesso (debito + COMPLETED + saves), lojista
  inexistente → 404, saldo insuficiente → `InsufficientBalanceException`,
  bloqueado → `WithdrawalsBlockedException`, não-ACTIVE →
  `MerchantNotActiveException`.
- `MasterWithdrawalControlServiceTest` (~2): block e release persistem a flag.

## Fora de escopo (follow-up)
- Aprovação por saque (decisão do usuário: fluxo é automático).
- Estorno de saques (bloqueio só impede novos).
- Endpoints de gestão de master (criar/desativar perfis) — Spec futura.
