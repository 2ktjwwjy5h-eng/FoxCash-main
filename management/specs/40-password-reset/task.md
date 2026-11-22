# Spec 40 — Task breakdown

Status: `audited` (2026-08-05). Ciclo SDD completo — verificação com `mvn test`
**399/0/0** (12 novos) e E2E **21/21** (cenário 21 novo: forgot/reset one-time,
sessões revogadas, anti-enumeração).

## Implementação

- [x] Migration `V32__password_reset_tokens.sql`: tabela `password_reset_tokens`
      (id, merchant_id FK, token_hash SHA-256 único, expires_at, used_at,
      created_at) + índice único parcial `(merchant_id) WHERE used_at IS NULL`
      (um token ativo por lojista) + `ix_password_reset_merchant`.
- [x] `PasswordResetToken` (domain/model/auth): `issue(merchantId, tokenHash,
      now, ttl)`, `restore`, `isValid(now)`, `use(now)` (single-use), accessors.
- [x] `InvalidResetTokenException` (domain/exception) → 400 `invalid_token`.
- [x] `ResetTokenGenerator` (port out) + `ResetTokenGeneratorImpl` (`rst_<32
      hex>` SecureRandom; hash SHA-256 via `DocumentHasher`).
- [x] `PasswordResetTokenRepository` (port out): `save`, `findByTokenHash`,
      `deleteOpenByMerchantId`.
- [x] Adapter JPA + entidade `PasswordResetTokenJpaEntity` + mapper;
      `revokeAllByMerchantId(UUID, Instant)` no adapter de sessão (bulk UPDATE
      de `revoked_at` nas ativas).
- [x] `PasswordResetUseCase` (port in): `ForgotResult forgot(ForgotCommand)`,
      `void reset(ResetCommand)`.
- [x] `PasswordResetService`: forgot (normaliza e-mail, findByEmail, apaga token
      aberto, gera/salva token, devolve `rst_` só em dev); reset (valida senha
      8–72, hash do token, `isValid`, troca `password_hash` BCrypt,
      `changePassword`, marca `used`, revoga sessões — mesma transação).
- [x] `MerchantCredential.changePassword(newPasswordHash, now)` (updatedAt).
- [x] DTOs/controller: `ForgotPasswordRequest{email}`,
      `ForgotPasswordResponse{resetToken}`, `ResetPasswordRequest{token,
      newPassword}`; `MerchantAuthController` ganha `POST /forgot-password` e
      `POST /reset-password` (flag `expose-reset-token` decide 200/204;
      `resetToken` nunca aparece quando `false`).
- [x] `SecurityConfig`: `permitAll` para `/api/v1/merchant-auth/forgot-password`
      e `/reset-password`.
- [x] `RateLimitFilter.LIMITED_PATHS`: inclui `/api/v1/merchant-auth/forgot-password`.
- [x] `SensitiveDataLoggingFilter`: `resetToken`/`reset_token` nos campos
      mascarados.
- [x] Config: `foxcash.auth.reset-token-ttl-seconds` (1800) e
      `expose-reset-token` (dev `true`, prod `false`) em `application.yml` +
      `application-prod.yml` (via `AuthProperties` + bean `resetTokenTtl`).

## Verificação

- [x] `PasswordResetServiceTest` (10): forgot e-mail existente → token `rst_` +
      hash no repo; e-mail inexistente → `ForgotResult.empty()` sem persistir;
      forgot normaliza e-mail; novo forgot apaga token aberto anterior; corrida
      → 409 `ResetTokenAlreadyOpenException`; reset válido → `changePassword`
      (BCrypt novo) + token `used` + sessões revogadas; token
      desconhecido/expirado/usado → 400 `invalid_token`; senha curta → 400.
- [x] `MerchantCredentialTest` (2): `changePassword` atualiza hash/updatedAt;
      null → NPE.
- [x] `SensitiveDataLoggingFilterTest`: `resetToken`/`reset_token` mascarados.
- [x] `MerchantAuthControllerTest` (5): forgot dev/existente → 200 com token;
      dev/inexistente → 204; **prod (expose=false) → 204 mesmo com e-mail
      existente** (anti-enumeração, critério 2); reset → 204.
- [x] `mvn test` verde (**404/0/0**: 387 + 17 novos).
- [x] E2E **21/21** (cenário 21 novo): forgot e-mail existente → 200 com
      `resetToken` (dev); token apenas como hash SHA-256 no banco; reset → 204;
      login senha antiga → 401; login senha nova → 200; token reusado → 400
      `invalid_token`; sessão antiga (antes do reset) → 401; `password_hash`
      BCrypt novo no banco; e-mail inexistente → 204 (anti-enumeração); senha
      curta → 400 `validation_error`; suite sem regressão.
- [x] Boot com V32 aplicada (Flyway `now at version v32` — verificado no boot).
- [x] README (auth do lojista: login/forgot/reset + flag prod), roadmap e
      requirements `audited`; `/spec-audit` → `audited` (fim do ciclo SDD).
