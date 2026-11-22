# Spec 28 — Design: Idempotência na criação de cobrança

## Contexto

Os três serviços que criam cobrança chamam o PSP **antes** de persistir:

- `CreatePixTransactionService.create` (`POST /api/v1/transactions`);
- `CreatePixChargeService.create` (`POST /api/v1/foxcash/pix/cob` e
  `POST /api/v1/portal/charges`);
- Fluxo de gift do portal (`POST /api/v1/portal/charges/gift` → reusa o
  `CreatePixChargeService` com valor do preset).

No timeout do PSP (5s/10s, Spec 26) a resposta é incerta: o provedor **pode**
ter criado a cobrança. Sem idempotência, o reenvio do bot gera **2 cobranças**
(diagnóstico **C2**). O Pagar.me já recebe `code = transactionId` — um
identificador único estável que permite re-enviar `createCharge` com o mesmo
`code` sem duplicar (idempotência do provedor).

## Decisões de arquitetura

### Migration V29 — idempotency por lojista

```sql
ALTER TABLE pix_transactions ADD COLUMN idempotency_key VARCHAR(64);
CREATE UNIQUE INDEX uq_pix_transactions_idempotency
    ON pix_transactions(merchant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
```

- **Por lojista:** lojistas diferentes podem usar a mesma chave (padrão
  Stripe). Índice parcial: transações históricas (sem chave) não colidem.
- `VARCHAR(64)` aceita UUIDs e chaves legadas (ex.: `cob_<32>`).

### Porta e domínio

```java
// domain/port/out/TransactionRepository.java (novos métodos)
Optional<PixTransaction> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);

// domain/model/transaction/PixTransaction.java (nova transição)
public void fail() { requireStatus(PENDING); this.status = FAILED; touch(); }
```

### `CreatePixChargeService.create` (fluxo principal) — ordem

```java
@Transactional
public Result create(Command command) {           // Command ganha idempotencyKey
    requireIdempotencyKey(command.idempotencyKey());     // blank -> 400
    merchant = merchantRepository.findById(...); ensureActive();
    validateAmount / validateExpirationSeconds;
    buyerIdHash = hash(command.buyerId()); fraudScreening.screen(buyerIdHash);

    // 1) Retry idempotente: reusa a transação da chave, se houver.
    Optional<PixTransaction> existing =
        transactionRepository.findByMerchantIdAndIdempotencyKey(merchantId, key);
    if (existing.isPresent()) {
        PixTransaction tx = existing.get();
        if (tx.status() == FAILED) throw new IdempotencyConflictException(key);
        if (tx.externalReference() == null && tx.status() == PENDING) {
            // Timeout anterior: re-tenta o PSP com o mesmo transactionId (code).
            charge = pixProviderClient.createCharge(chargeCommandFor(tx));
            tx.attachCharge(...); transactionRepository.save(tx);
        }
        return new Result(tx.id());               // mesmo transactionId (replay)
    }

    // 2) Criação nova: PENDING persistida ANTES do PSP (não some no timeout).
    PixTransaction tx = PixTransaction.create(merchantId, amount, description, null, buyerIdHash);
    // A chave é atribuída ANTES da chamada ao PSP e fica no tx a partir daqui
    // (REQUIRES_NEW via TransactionTemplate): os saves seguintes mantêm a coluna
    // idempotency_key preenchida — retry nunca duplica.
    tx = idempotencyPersister.persistPending(tx, key);

    try {
        charge = pixProviderClient.createCharge(chargeCommandFor(tx));
        tx.attachCharge(...);
    } catch (PixProviderTimeoutException e) {
        throw new PspTimeoutException(...);        // 502; PENDING fica persistida
    } catch (PixProviderException e) {
        tx.fail(); transactionRepository.save(tx); // marca FAILED
        throw e;                                    // 4xx/5xx ao cliente
    }
    transactionRepository.save(tx);
    return new Result(tx.id());
}
```

### Idempotência da persistência antecipada

`IdempotencyPersister` (bean separado) resolve o problema de self-invocation:

```java
@Component
public class IdempotencyPersister {
    private final TransactionTemplate txTemplate;
    private final TransactionRepository repository;

    /** Persiste a PENDING com a chave em transação própria e DEVOLVE o tx
     *  com a chave atribuída — o chamador usa o retorno daqui em diante
     *  (saves seguintes mantêm a coluna idempotency_key). */
    public PixTransaction persistPending(PixTransaction tx, String key) {
        return txTemplate.execute(s -> repository.save(tx.withIdempotencyKey(key)));
    }
}
```

- **Por que `REQUIRES_NEW`:** a chamada ao PSP acontece na transação
  principal; se ela der rollback (timeout/erro), o save inicial **já foi
  commitado** e a chave fica registrada — o retry nunca duplica.
- **Corrida de retries simultâneos com a mesma chave:** a constraint única
  `(merchant_id, idempotency_key)` falha no segundo INSERT → rollback (sem
  cobrança duplicada); o segundo request recebe 409 `idempotency_conflict`
  (ou retorna a transação vencedora em nova leitura). Comportamento
  documentado.
- **`withIdempotencyKey`:** `PixTransaction` ganha o campo persistido
  (`idempotencyKey`) exposto no record/entidade; a factory `create` não o
  recebe (definido na persistência). O `persistPending` **retorna o tx com a
  chave atribuída** — o fluxo principal reassina `tx = persistPending(tx, key)`
  para que os saves posteriores (attachCharge/fail) preservem a coluna.

### Exceções e mapeamento HTTP (GlobalExceptionHandler)

| Exceção | HTTP | Código |
|---|---|---|
| `IdempotencyConflictException` | 409 | `idempotency_conflict` |
| `PspTimeoutException` | 502 | `psp_timeout` |
| `IllegalArgumentException` (chave ausente) | 400 | `invalid_argument` |

### `PixProviderClient` — exceções tipadas (timeout vs. erro real)

```java
// domain/port/out/PixProviderClient.java (novas exceções)
class PixProviderException extends RuntimeException { ... }      // erro real
class PixProviderTimeoutException extends PixProviderException { ... } // timeout
```

- `PagarMePixProviderClient`: captura `ResourceAccessException` (timeout do
  RestClient 5s/10s) → `PixProviderTimeoutException`; demais `RestClientException`
  → `PixProviderException`.
- `MockPixProviderClient`: por padrão responde normal; em testes, um perfil
  (ex.: `foxcash.psp.mock-timeout=true` ou um provider test mock) reproduz o
  timeout para validar o cenário.

### Rotas afetadas (idempotencyKey no request DTO)

- `CreatePixTransactionRequest.idempotencyKey` (`@NotBlank`).
- `PixChargeRequest.idempotencyKey` (`@NotBlank`).
- `PortalChargeRequest` e `PortalGiftChargeRequest` (`@NotBlank`).
- `CreatePixTransactionUseCase.Command` e `CreatePixChargeUseCase.Command`
  ganham `idempotencyKey`.

## Arquivos

- `src/main/resources/db/migration/V29__pix_charge_idempotency.sql` (novo)
- `domain/port/out/PixProviderClient.java` (exceções tipadas)
- `domain/model/transaction/PixTransaction.java` (campo `idempotencyKey` +
  `fail()`)
- `domain/exception/IdempotencyConflictException.java`,
  `domain/exception/PspTimeoutException.java` (novos)
- `application/service/IdempotencyPersister.java` (novo)
- Alterados: `CreatePixTransactionService`, `CreatePixChargeService`,
  `TransactionRepository`/adapter/JpaRepository, controllers de cobrança +
  DTOs, `GlobalExceptionHandler`
- `PixTransactionJpaEntity` + mapper (coluna `idempotency_key`)
- Testes: `CreatePixChargeIdempotencyTest` (replay, retry pós-timeout, FAILED →
  409, unicidade por lojista); ajuste do mock para cenário de timeout
- E2E: cenário de replay da mesma chave + timeout simulado

## Fora de escopo

- Retry automático (backoff) — o retry é responsabilidade do cliente/bot.
- Fila/outbox de cobranças — reconciliação (Spec 30) cobre as órfãs.
- Mudança do `code` enviado ao PSP (continua `transactionId`).
