# Spec 31 — Design: Destinos de saque do lojista

## Contexto

O saque atual é `{amount}` sem destino. Decisão de produto (2026-08-04): o
vendedor cadastra na **sua conta** os endereços de saque (4 tipos de chave Pix
do Bacen ou dados bancários), **privados** (só ele vê, mascarado) e
**criptografados** em repouso. O saque (Spec 32) passa a exigir a seleção de
um desses destinos.

## Decisões de arquitetura

### Tabela `merchant_withdrawal_destinations` (migration V20)

```sql
CREATE TABLE merchant_withdrawal_destinations (
    id           UUID PRIMARY KEY,
    merchant_id  UUID NOT NULL REFERENCES merchants(id),
    type         VARCHAR(20) NOT NULL,   -- PIX_CPF | PIX_CNPJ | PIX_PHONE |
                                         -- PIX_EVP | BANK_ACCOUNT
    value        VARCHAR(255),           -- chave Pix (criptografada AES-256/GCM)
    bank_code    VARCHAR(10),            -- BANK_ACCOUNT (criptografado)
    agency       VARCHAR(20),            -- BANK_ACCOUNT (criptografado)
    account      VARCHAR(30),            -- BANK_ACCOUNT (criptografado)
    account_type VARCHAR(20),            -- CHECKING | SAVINGS (criptografado)
    label        VARCHAR(60),
    status       VARCHAR(20) NOT NULL,   -- ACTIVE | INACTIVE
    created_at   TIMESTAMP NOT NULL,
    updated_at   TIMESTAMP NOT NULL
);
CREATE INDEX ix_destinations_merchant ON merchant_withdrawal_destinations(merchant_id);
CREATE INDEX ix_destinations_merchant_status ON merchant_withdrawal_destinations(merchant_id, status);
```

- **Criptografia:** `value`, `bank_code`, `agency`, `account`, `account_type`
  usam `@Convert(converter = CryptoConverter.class)` — AES-256/GCM, prefixo
  `v1:`, mesmo padrão do `document` (Spec 4). Backup/dump não expõe dados.
- **Limite de ativos:** regra de aplicação (default 5, configurável
  `foxcash.portal.max-withdrawal-destinations`); sem constraint SQL (contagem
  por `(merchant_id, status='ACTIVE')` no service).

### Domínio

```java
// domain/model/withdrawal/WithdrawalDestinationType.java
public enum WithdrawalDestinationType { PIX_CPF, PIX_CNPJ, PIX_PHONE, PIX_EVP, BANK_ACCOUNT }

// domain/model/withdrawal/WithdrawalDestination.java
public record WithdrawalDestination(UUID id, UUID merchantId, WithdrawalDestinationType type,
                                    String value, String bankCode, String agency,
                                    String account, String accountType, String label,
                                    boolean active, Instant createdAt, Instant updatedAt) {
    static WithdrawalDestination create(UUID merchantId, WithdrawalDestinationType type, String value,
                                        String bankCode, String agency, String account,
                                        String accountType, String label, Instant now);
    WithdrawalDestination withLabel(String newLabel, Instant now);
    WithdrawalDestination activate(Instant now);
    WithdrawalDestination deactivate(Instant now);
}

// domain/service/WithdrawalDestinationValidator.java  (regra pura)
public final class WithdrawalDestinationValidator {
    // PIX_CPF  -> DocumentValidator.isValid + 11 dígitos (dígitos verificadores)
    // PIX_CNPJ -> DocumentValidator.isValid + 14 dígitos (dígitos verificadores)
    // PIX_PHONE-> regex ^\+55[1-9]\d{10,11}$ (celular BR com DDD)
    // PIX_EVP  -> UUID válido (de 1 a 64 chars, padrão EVP)
    // BANK_ACCOUNT -> bankCode/agency/account obrigatórios (value = chave Pix opcional)
    public static void validate(WithdrawalDestinationType type, String value,
                                String bankCode, String agency, String account);
}
```

> Nota: `DocumentValidator` só expõe `isValid(String)` (aceita CPF e CNPJ, com
> dígitos verificadores). O `WithdrawalDestinationValidator` distingue por
> **comprimento dos dígitos**: `PIX_CPF` → `isValid(value)` com 11 dígitos;
> `PIX_CNPJ` → `isValid(value)` com 14 dígitos. Sem expor métodos internos.

### Portas e aplicação

```java
// domain/port/out/WithdrawalDestinationRepository.java
WithdrawalDestination save(WithdrawalDestination destination);
Optional<WithdrawalDestination> findByIdAndMerchantId(UUID id, UUID merchantId); // 404 p/ outro lojista
List<WithdrawalDestination> findByMerchantId(UUID merchantId);
long countActiveByMerchantId(UUID merchantId);

// domain/port/in/WithdrawalDestinationUseCase.java
Created create(UUID merchantId, Command cmd);      // valida tipo/formato + limite ativos
List<WithdrawalDestination> list(UUID merchantId); // mascaradas na resposta
void updateLabel(UUID merchantId, UUID id, String label);
void setActive(UUID merchantId, UUID id, boolean active);
```

- `WithdrawalDestinationService` (`@Service @Transactional`): cria (valida via
  `WithdrawalDestinationValidator`, checa limite 409 `too_many_destinations`),
  lista (devolve o domínio; o controller mascara), atualiza rótulo,
  ativa/desativa (404 `destination_not_found` se não pertence ao lojista).

### REST (`ROLE_MERCHANT_PORTAL`, `/api/v1/portal/destinations`)

| Rota | Resposta |
|---|---|
| `POST` `{type, value, bankCode?, agency?, account?, accountType?, label?}` | 201 `WithdrawalDestinationResponse` (id, type, label, mask, status) |
| `GET` | `List<WithdrawalDestinationResponse>` (mascarados) |
| `PATCH /{id}` `{label}` | 200 atualizado (mascarado) |
| `POST /{id}/activate` / `POST /{id}/deactivate` | 204 |

- **Máscara** (`WithdrawalDestinationMasker`): 
  - `PIX_CPF`: `529.***.***-25`; `PIX_CNPJ`: `12.***.***/0001-00`;
    `PIX_PHONE`: `+55 11 *****-9999`; `PIX_EVP`: `e6a2...-…b3` (8+4);
    `BANK_ACCOUNT`: `Banco 341 · ag 0001 · conta ***-9`.
- **Privacidade:** `findByIdAndMerchantId` → 404 para destino de outro lojista
  (não revela existência); o controller nunca recebe `merchantId` do client
  (usa o principal). Master não acessa (rota é `/portal/**`).
- 400 `invalid_argument` via exceção do validador; `QueryParamParser`/handlers
  existentes reutilizados.

## Arquivos

- `src/main/resources/db/migration/V20__withdrawal_destinations.sql` (novo)
- `domain/model/withdrawal/WithdrawalDestination.java`,
  `WithdrawalDestinationType.java` (novos)
- `domain/service/WithdrawalDestinationValidator.java` (novo)
- `domain/port/out/WithdrawalDestinationRepository.java` (novo)
- `domain/port/in/WithdrawalDestinationUseCase.java` (novo)
- `application/service/WithdrawalDestinationService.java` (novo)
- `infrastructure/outbound/persistence/entity/WithdrawalDestinationJpaEntity.java`
  + mapper + repository + adapter (novos)
- `infrastructure/inbound/rest/WithdrawalDestinationController.java` + DTOs
  + `WithdrawalDestinationMasker` (novos)
- Testes: `WithdrawalDestinationValidatorTest` (formatos),
  `WithdrawalDestinationServiceTest` (CRUD/limite/404),
  `WithdrawalDestinationControllerTest` (máscara, 400/404/401/403)
- E2E: cenário 11 (criar tipos válidos/inválidos, listar mascarado,
  ativar/desativar, 404 cross-loja, limite 409)

## Fora de escopo

- Uso no saque (destino selecionado) — Spec 32.
- Transferência real via PSP (Pix de saída / TED) — spec futura.
- Acesso do master aos destinos (privados por decisão do usuário).
