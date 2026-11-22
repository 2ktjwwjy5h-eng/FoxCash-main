# Spec 40 — Design: Recuperação de senha do lojista (token self-service)

## Contexto

O lojista perde a senha do portal (Spec 20/39). Não há infraestrutura de e-mail
no backend e o master não deve ser o único caminho (o master gerencia a
plataforma, não a conta de cada lojista). Solução: **token self-service one-time**
via API — `forgot-password` emite um token `rst_<32 hex>` (hash SHA-256 no
banco, expira ~30min), `reset-password` o consome e redefine a senha revogando
as sessões ativas. Em dev o token é devolvido na resposta (testável sem e-mail);
em prod a resposta é sempre 204 (anti-enumeração).

## Decisões de arquitetura

### 1. Migration V32 — `password_reset_tokens`

```sql
CREATE TABLE password_reset_tokens (
    id          UUID PRIMARY KEY,
    merchant_id UUID NOT NULL REFERENCES merchants(id),
    token_hash  VARCHAR(64) NOT NULL,              -- SHA-256 hex (nunca em claro)
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,                        -- single-use (NULL = ativo)
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_password_reset_token_hash UNIQUE (token_hash)
);
CREATE UNIQUE INDEX uq_password_reset_open_per_merchant
    ON password_reset_tokens(merchant_id) WHERE used_at IS NULL;
CREATE INDEX ix_password_reset_merchant ON password_reset_tokens(merchant_id);
```

- O **índice único parcial** `(merchant_id) WHERE used_at IS NULL` garante **no
  máximo um token ativo por lojista** — um novo `forgot-password` precisa
  invalidar (apagar) o token anterior antes de inserir; em corrida, o índice é o
  backstop (→ `409 reset_token_already_open`).
- `token_hash` é o SHA-256 hex do token em claro (mesmo padrão de
  `merchant_sessions.token_hash`, Spec 20 — via `DocumentHasher`).

### 2. Domínio — `PasswordResetToken`

```java
// domain/model/auth/PasswordResetToken.java
public class PasswordResetToken {
    private final UUID id;
    private final UUID merchantId;
    private final String tokenHash;
    private final Instant createdAt;
    private final Instant expiresAt;
    private Instant usedAt;

    public static PasswordResetToken issue(UUID merchantId, String tokenHash, Instant now, Duration ttl);
    public static PasswordResetToken restore(UUID id, UUID merchantId, String tokenHash,
                                             Instant createdAt, Instant expiresAt, Instant usedAt);

    public boolean isValid(Instant now);   // !used && now < expiresAt
    public void use(Instant now);          // single-use
    // accessors
}
```

### 3. Portas

- `domain/port/out/ResetTokenGenerator.java` — `TokenPair generate()` +
  `hash(String)`; impl `ResetTokenGeneratorImpl` gera `rst_<32 hex>` (SecureRandom,
  mesmo padrão de `SessionTokenGeneratorImpl`, prefixo `rst_`).
- `domain/port/out/PasswordResetTokenRepository.java`:
  - `PasswordResetToken save(PasswordResetToken token)`;
  - `Optional<PasswordResetToken> findByTokenHash(String tokenHash)`;
  - `void deleteOpenByMerchantId(UUID merchantId)` (apaga ativos/abertos).
- `domain/port/out/MerchantSessionRepository.java` (aditivo):
  - `void revokeAllByMerchantId(UUID merchantId, Instant now)` — bulk UPDATE
    `revoked_at` nas sessões ativas (JPQL `@Modifying`).
- `domain/port/in/PasswordResetUseCase.java`:
  - `ForgotResult forgot(ForgotCommand email)` — `ForgotResult(Optional<String> resetToken)`
    (token em claro **só em dev**, ver flag);
  - `void reset(ResetCommand token, newPassword)`.

### 4. Serviço — `PasswordResetService`

```java
@Transactional
public ForgotResult forgot(ForgotCommand command) {
    String email = normalizeEmail(command.email());            // lower/trim + EmailValidator
    Merchant merchant = merchantRepository.findByEmail(email).orElse(null);
    if (merchant == null) {
        return ForgotResult.empty();                           // resposta uniforme (204/anti-enumeração)
    }
    resetTokenRepository.deleteOpenByMerchantId(merchant.id()); // um ativo por lojista
    ResetTokenGenerator.TokenPair token = resetTokenGenerator.generate();
    resetTokenRepository.save(PasswordResetToken.issue(merchant.id(), token.hash(), now, ttl));
    return ForgotResult.of(token.plaintext());
}

@Transactional
public void reset(ResetCommand command) {
    requireNewPassword(command.newPassword());                 // 8–72 -> 400
    String tokenHash = resetTokenGenerator.hash(command.token());
    PasswordResetToken token = resetTokenRepository.findByTokenHash(tokenHash)
            .orElseThrow(InvalidResetTokenException::new);      // desconhecido
    if (!token.isValid(now)) {                                  // usado OU expirado
        throw new InvalidResetTokenException::new;
    }
    MerchantCredential credential = credentialRepository.findByMerchantId(token.merchantId())
            .orElseThrow(InvalidResetTokenException::new);
    credential.changePassword(passwordHasher.hash(command.newPassword()), now);
    credentialRepository.save(credential);
    token.use(now);
    resetTokenRepository.save(token);
    sessionRepository.revokeAllByMerchantId(token.merchantId(), now); // invalida sessões
}
```

- `InvalidResetTokenException` → **400 `invalid_token`** (desconhecido/expirado/
  usado — mesmo código, sem oráculo).
- Normalização do e-mail reutiliza `EmailValidator` + `MerchantRepository.findByEmail`
  (Spec 39).
- Tudo na **mesma transação** (senha + token + sessões atômicas).

### 5. `MerchantCredential.changePassword`

```java
public void changePassword(String newPasswordHash, Instant now) {
    this.passwordHash = newPasswordHash;
    this.updatedAt = now;
}
```

(necessário porque hoje o hash é `final` — Spec 20 não previa troca).

### 6. REST — `MerchantAuthController` (aditivo, rotas públicas)

- `POST /forgot-password` `{email}`:
  - dev (`foxcash.auth.expose-reset-token=true`, default em dev) → **200**
    `{resetToken}` quando o e-mail existe, **204** quando não (sem token);
  - prod (`expose-reset-token=false`) → **sempre 204** (anti-enumeração);
  - e-mail inválido/ausente → **400 `validation_error`** (Bean Validation).
- `POST /reset-password` `{token, newPassword}` → **204**; erros → 400
  `invalid_token` / `validation_error`.

DTOs: `ForgotPasswordRequest(email)`, `ForgotPasswordResponse(resetToken)`,
`ResetPasswordRequest(token, newPassword)`.

### 7. Security & rate limit & logs

- `SecurityConfig`: adiciona `/api/v1/merchant-auth/forgot-password` e
  `/api/v1/merchant-auth/reset-password` aos `permitAll` (públicas, como login).
- `RateLimitFilter.LIMITED_PATHS`: adiciona `/api/v1/merchant-auth/forgot-password`
  (POST, por IP — mesmo limite do login).
- `SensitiveDataLoggingFilter.SENSITIVE_FIELD`: adiciona `resetToken`/`reset_token`
  (o `token` genérico já cobre o campo `token` do reset).

### 8. Config

`application.yml` (dev) + `application-prod.yml`:

```yaml
foxcash:
  auth:
    reset-token-ttl-seconds: 1800      # 30min
    expose-reset-token: true           # dev: devolve o token; prod: false
```

## Arquivos

- `db/migration/V32__password_reset_tokens.sql` (novo)
- `domain/model/auth/PasswordResetToken.java` (novo)
- `domain/exception/InvalidResetTokenException.java` (novo)
- `domain/port/out/ResetTokenGenerator.java` (novo) +
  `infrastructure/security/ResetTokenGeneratorImpl.java` (novo)
- `domain/port/out/PasswordResetTokenRepository.java` (novo)
- `domain/port/in/PasswordResetUseCase.java` (novo)
- `application/service/PasswordResetService.java` (novo)
- `domain/model/merchant/MerchantCredential.java` (`changePassword`)
- `domain/port/out/MerchantSessionRepository.java` (`revokeAllByMerchantId`)
- `infrastructure/outbound/persistence/...` adapter/repository/entity de
  `PasswordResetToken` + `revokeAllByMerchantId` no adapter de sessão
- `infrastructure/inbound/rest/MerchantAuthController.java` (2 rotas)
- `infrastructure/inbound/rest/dto/` (ForgotPasswordRequest/Response,
  ResetPasswordRequest)
- `infrastructure/security/SecurityConfig.java`, `RateLimitFilter.java`,
  `SensitiveDataLoggingFilter.java`
- `src/main/resources/application.yml` + `application-prod.yml`
- Testes: `PasswordResetServiceTest`, `EmailValidator` já coberto,
  `SensitiveDataLoggingFilterTest` (+resetToken); E2E cenário novo; README

## Fora de escopo

- Envio de e-mail/webhook do token em prod (integração futura — a porta fica
  pronta com o flag `expose-reset-token=false`).
- Política de reuso de senha (senha repetida permitida nesta spec).
- Reset de senha do **master** (Spec 41/gestão de perfis master ou fluxo
  próprio futuro).
- Bloqueio por tentativas no `reset-password` (o token single-use já limita;
  rate limit por IP pode ser adicionado depois se necessário).
