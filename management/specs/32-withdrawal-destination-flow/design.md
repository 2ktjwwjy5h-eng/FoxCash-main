# Spec 32 — Design: Saque com destino + saque administrativo do master

## Contexto

Hoje o saque (`WithdrawalService.withdraw`) recebe `{amount}`, debita sob lock
e grava `COMPLETED`. Sem destino (problema de produto) e sem escape para o
master quando o bloqueio está ativo (problema operacional). Decisões
2026-08-04: saque instantâneo **com destino selecionado** (Spec 31) e bloqueio
**lacrado** com saque administrativo do master até o saldo disponível.

## Decisões de arquitetura

### Migration V21 — colunas no `withdrawals`

```sql
ALTER TABLE withdrawals ADD COLUMN destination_id UUID
    REFERENCES merchant_withdrawal_destinations(id);
ALTER TABLE withdrawals ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'MERCHANT'; -- MERCHANT | ADMINISTRATIVE
ALTER TABLE withdrawals ADD COLUMN reason VARCHAR(255);
ALTER TABLE withdrawals ADD COLUMN destination_key_snapshot VARCHAR(255);  -- criptografado AES-256/GCM
CREATE INDEX ix_withdrawals_type ON withdrawals(type);
```

- `destination_key_snapshot` (criptografado via `CryptoConverter`): preserva o
  endereço de saque na data do saque — o histórico não muda se o destino for
  editado/inativado depois.
- `type` distingue saque do vendedor (`MERCHANT`) do recolhimento do master
  (`ADMINISTRATIVE`).

### Domínio

```java
// domain/model/merchant/Merchant.java (novo método)
/** Saque administrativo (master): NÃO valida ACTIVE/bloqueio — apenas saldo.
 *  O master recolhe de lojista bloqueado/suspenso; resultado nunca negativo. */
public void adminWithdraw(Money amount) {
    if (walletBalance.amount().compareTo(amount.amount()) < 0) {
        throw new InsufficientBalanceException(id, amount);
    }
    this.walletBalance = walletBalance.subtract(amount);
}

// domain/model/withdrawal/Withdrawal.java (factory ampliada)
public static Withdrawal complete(UUID merchantId, Money amount, UUID destinationId,
                                  String destinationKeySnapshot, WithdrawalType type,
                                  String reason) { ... }

// domain/model/withdrawal/WithdrawalType.java
public enum WithdrawalType { MERCHANT, ADMINISTRATIVE }
```

### `WithdrawalService.withdraw` (vendedor) — destino obrigatório

```java
@Transactional
public Result withdraw(Command command) {   // Command ganha destinationId
    // Destino: pertence ao lojista e está ATIVO (404 / 409) — SEM lock
    // na tabela de destinos (dados privados; leitura simples).
    WithdrawalDestination destination = destinationRepository
            .findByIdAndMerchantId(command.destinationId(), command.merchantId())
            .orElseThrow(() -> new DestinationNotFoundException(command.destinationId()));
    if (!destination.active()) {
        throw new DestinationInactiveException(command.destinationId());  // 409
    }

    // Lock pessimista no lojista: serializa saques concorrentes (Spec 26) —
    // o débito e a validação acontecem sob o MESMO lock (sem overdraft).
    Merchant merchant = merchantRepository.findByIdForUpdate(command.merchantId())
            .orElseThrow(() -> new MerchantNotFoundException(command.merchantId()));
    merchant.withdraw(command.amount());      // ACTIVE + não-bloqueado + saldo (409s)

    Withdrawal withdrawal = Withdrawal.complete(
            command.merchantId(), command.amount(), destination.id(),
            destination.maskOrSnapshot(), WithdrawalType.MERCHANT, null);
    merchantRepository.save(merchant);
    Withdrawal saved = withdrawalRepository.save(withdrawal);
    ledgerRecorder.record(merchant, WITHDRAWAL_DEBIT, withdrawal.amount(),
                          "WITHDRAWAL", saved.id());                    // Spec 27
    return new Result(saved.id(), saved.amount(), merchant.walletBalance());
}
```

- `destination.maskOrSnapshot()`: para o snapshot **persistido** usa-se o valor
  em claro (criptografado na coluna); para a **resposta** usa-se a máscara
  (Spec 31). O controller nunca devolve o snapshot em claro.
- Exceções novas: `DestinationNotFoundException` (404 `destination_not_found`),
  `DestinationInactiveException` (409 `destination_inactive`) + handlers.

### `MasterWithdrawalService` (novo) — saque administrativo (bloqueio lacrado)

```java
@Transactional
public Result administrativeWithdraw(UUID merchantId, Money amount,
                                     String destinationKey, String reason) {
    if (destinationKey == null || destinationKey.isBlank() || reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("destinationKey e reason sao obrigatorios"); // 400
    }
    Merchant merchant = merchantRepository.findByIdForUpdate(merchantId)
            .orElseThrow(() -> new MerchantNotFoundException(merchantId));
    merchant.adminWithdraw(amount);   // valida APENAS saldo (nunca negativo)

    Withdrawal withdrawal = Withdrawal.complete(
            merchantId, amount, null, destinationKey,
            WithdrawalType.ADMINISTRATIVE, reason);
    merchantRepository.save(merchant);
    Withdrawal saved = withdrawalRepository.save(withdrawal);
    ledgerRecorder.record(merchant, WITHDRAWAL_DEBIT, amount,
                          "WITHDRAWAL", saved.id());
    return new Result(saved.id(), saved.amount(), merchant.walletBalance());
}
```

- **Bloqueio lacrado:** `adminWithdraw` não chama `ensureActive` nem checa
  `withdrawalsBlocked` — o master recolhe mesmo de lojista bloqueado/suspenso.
  O vendedor continua bloqueado por `Merchant.withdraw` (nenhuma rota dele
  contorna).
- **Privacidade:** `destinationKey` é informada pelo master no request (não
  lê a tabela privada do lojista — Spec 31). Sem destino privado envolvido.
- **Nunca negativo:** `adminWithdraw` valida saldo suficiente → 409.

### REST

| Rota | Body | Resposta |
|---|---|---|
| `POST /api/v1/merchants/{id}/withdrawals` (vendedor) | `{amount, destinationId}` | 201 `{withdrawalId, amount, status, walletBalance}` |
| `POST /api/v1/master/merchants/{id}/withdrawals/administrative` (master) | `{amount, destinationKey, reason}` | 201 `{withdrawalId, amount, status, walletBalance}` |

- `WithdrawalRequest` ganha `destinationId` (`@NotNull`); novo
  `AdministrativeWithdrawalRequest` (`amount`, `destinationKey`, `reason`).
- `WithdrawalResponse` ganha `type` e `destination` mascarado.
- `GET /api/v1/master/withdrawals` e `GET /api/v1/merchants/{id}/withdrawals`
  passam a exibir `type` (o master distingue recolhimentos).

## Arquivos

- `src/main/resources/db/migration/V21__withdrawal_destination.sql` (novo)
- `domain/model/merchant/Merchant.java` (`adminWithdraw`)
- `domain/model/withdrawal/WithdrawalType.java` (novo); `Withdrawal.java`
  (factory com destino/tipo/reason/snapshot)
- `domain/exception/DestinationNotFoundException.java`,
  `DestinationInactiveException.java` (novos) + handlers
- `domain/port/in/MasterWithdrawalService`/`MasterWithdrawalUseCase.java`
  (administrativeWithdraw)
- `application/service/WithdrawalService.java` (destino obrigatório + ledger);
  `application/service/MasterWithdrawalService.java` (novo)
- `infrastructure/outbound/persistence/entity/WithdrawalJpaEntity.java` + mapper
  (colunas novas, snapshot criptografado)
- `infrastructure/inbound/rest/WithdrawalController.java` + DTOs;
  `MasterWithdrawalController.java` (rota administrative)
- Testes: `WithdrawalServiceTest` (destino obrigatório/404/409, concorrência),
  `MasterWithdrawalServiceTest` (admin com bloqueio/status, nunca negativo, 400)
- E2E: cenário 12 (saque com destino, bloqueio lacrado, saque administrativo,
  400/404/409)

## Fora de escopo

- Transferência real via PSP (Pix de saída / TED) — spec futura.
- Reserva de valor / processamento assíncrono (solicitação em 2 etapas) —
  rejeitado pelo usuário (saque permanece instantâneo).
- Acesso do master aos destinos privados (Spec 31).
