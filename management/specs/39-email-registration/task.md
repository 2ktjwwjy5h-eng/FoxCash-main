# Spec 39 — Task breakdown

Status: `audited` (2026-08-05). Ciclo SDD completo — verificação com `mvn test`
**387/0/0** e E2E **20/20** (cenário 1 novo: cadastro por nome/e-mail/senha, sem
CPF/CNPJ, hash BCrypt da senha escolhida no banco, duplicado por e-mail → 409).

## Implementação

- [x] Migration `V31__merchant_email_registration.sql`: `email` + `name`
      (backfill de legal_name), `document`/`document_hash` DROP NOT NULL,
      `uq_merchants_document_hash` parcial, `uq_merchants_email` (lower),
      DROP `trading_name`/`legal_name`.
- [x] `EmailValidator` (domain/service, formato simples) — cadastro e login.
- [x] `Merchant` (domain): campos `name`/`email`; `register(name, email, plan,
      apiKeyHash)` normaliza e-mail; accessors `name()`/`email()`.
- [x] `MerchantRepository`/`MerchantJpaRepository`/adapter: `findByEmail`
      (lower); remove `findByDocument`/`findByDocumentHash` (código morto).
- [x] `MerchantJpaEntity`/`MerchantMapper`: `name`/`email`; documentHash
      calculado só quando document != null.
- [x] `RegisterMerchantUseCase`/`RegisterMerchantService`: `Command(name, email,
      password)`; valida nome/e-mail/senha (400); e-mail duplicado → 409
      (backstop unique); credencial com senha ESCOLHIDA (BCrypt); `Result`
      sem `portalPassword`; remove `PortalPasswordGenerator`.
- [x] `MerchantPortalAuthUseCase`/`MerchantPortalAuthService`:
      `LoginCommand(email, password)` via `findByEmail`; `LoginResult` com
      `name`.
- [x] DTOs/controllers: `CreateMerchantRequest` `{name,email,password}`;
      `MerchantRegisteredResponse` (name/email/apiKey, sem portalPassword);
      `MerchantResponse`, `PortalMeResponse` (name/email);
      `MerchantLoginRequest` `{email,password}`; `MerchantLoginResponse` (name);
      `MerchantController`/`MerchantAuthController`; admin busca por `name`
      (`AdminDashboardPersistenceAdapter`, `MerchantSearchQuery` javadoc).
- [x] `SensitiveDataLoggingFilter`: `email` na lista de campos mascarados.

## Verificação

- [x] `RegisterMerchantServiceTest`: cadastro name/email/senha → 201 (apiKey,
      sem portalPassword); e-mail duplicado → 409; e-mail inválido/senha curta/
      nome ausente → 400; e-mail normalizado (maiúsculas → minúsculas).
- [x] `MerchantPortalAuthServiceTest`: login por e-mail; documento não funciona;
      senha errada → 401; result com `name`.
- [x] `EmailValidator` (unit): válidos/inválidos.
- [x] `mvn test` verde (**387/0/0**: 382 + EmailValidator 2 + cadastro 2 + ajustes).
- [x] E2E **20/20** (cenário 1 novo): cadastro `{name, email, password}` (sem
      documento) → 201 com apiKey e `email`; `document` nulo e `password_hash`
      BCrypt no banco; duplicado por e-mail → 409 `merchant_already_exists`;
      login por e-mail; suite sem regressão.
- [x] Boot com V31 aplicada (Flyway `now at version v31` — verificado no boot).
- [x] Roadmap/requirements/task `audited`; README (cadastro/login) atualizado.
- [x] `/spec-audit` → `audited` (fim do ciclo SDD).
