---
name: MFA TOTP no portal do lojista
status: audited
---

# Spec 94 — Task: MFA TOTP no portal do lojista

## Backend — persistência (espelho Spec 44)

- [x] `src/main/resources/db/migration/V59__merchant_mfa.sql` — tabelas
      `merchant_mfa_methods` / `merchant_totp` / `merchant_recovery_codes`
      (CASCADE nativo nas FKs — lição V42; espelho das V41/V42 com merchant).
- [x] `domain/model/merchant/MerchantMfaMethod.java` (PENDING → ACTIVE, TTL,
      restore/pending/activate/isPending/isActive/isPendingExpired).
- [x] `domain/model/merchant/MerchantTotp.java` (create/restore/
      recordValidCode/recordFailedAttempt/isLocked).
- [x] `domain/model/merchant/MerchantRecoveryCode.java` (create/restore/use/
      isUsed).

## Backend — portas

- [x] `domain/port/in/MerchantMfaUseCase.java` (ActivateResult/ConfirmResult +
      activate/confirm/deactivate/regenerateRecoveryCodes/hasActiveMfa/
      verifySecondFactor — sem findByIdAndMasterId, sem step-up).
- [x] `domain/port/out/MerchantMfaMethodRepository.java`
      (save/findActiveByMerchantId/findPendingByMerchantId/deleteByMerchantId).
- [x] `domain/port/out/MerchantTotpRepository.java`
      (save/findByMethodId/findByIdForUpdate — SEC-01).
- [x] `domain/port/out/MerchantRecoveryCodeRepository.java`
      (saveAll/findByMethodId/consumeIfOpen/deleteByMethodId — SEC-20).

## Backend — persistência JPA

- [x] `entity/MerchantMfaMethodJpaEntity.java` + `MerchantTotpJpaEntity.java`
      (com `@Convert(CryptoConverter)` no secret) +
      `MerchantRecoveryCodeJpaEntity.java`.
- [x] `repository/MerchantMfaMethodJpaRepository.java`
      (`findFirstByMerchantIdAndStatus`).
- [x] `repository/MerchantTotpJpaRepository.java`
      (`findByIdForUpdate` com `@Lock(PESSIMISTIC_WRITE)`).
- [x] `repository/MerchantRecoveryCodeJpaRepository.java`
      (`consumeIfOpen` com `@Modifying` + `@Query` condicional).
- [x] `mapper/` ×3 (MerchantMfaMethod/MerchantTotp/MerchantRecoveryCodeMapper).
- [x] `adapter/` ×3 (PersistenceAdapter — espelho dos Master*).

## Backend — aplicação

- [x] `application/service/MerchantMfaService.java` — espelho do
      `MasterMfaService`: ativação 2 passos (secret criptografado, PENDING TTL
      10min, 409 se ativo), confirmação (TOTP ±1 → ativa + 8 recovery codes 1x),
      desativação (senha do portal BCrypt + TOTP/recovery → 204), regeneração
      (TOTP válido), `verifySecondFactor` (lockout 5→15min, recovery `mrc_*`
      consumeIfOpen, anti-replay `last_code_hash`, 401 uniforme), auditoria
      `MFA_*` actor MERCHANT. `requireMerchant` via `MerchantRepository.findById`
      + `MerchantNotFoundException`. `otpauthUri` com `merchant.name()`.
- [x] `application/service/MerchantTotpFailurePersister.java` — REQUIRES_NEW +
      findByIdForUpdate + evento MFA_LOGIN_FAILED/MFA_LOCKED (espelho).

## Backend — REST

- [x] `infrastructure/inbound/rest/PortalMfaController.java` —
      `/api/v1/portal/mfa/activate|confirm|deactivate|recovery-codes`
      (ROLE_MERCHANT_PORTAL; reusa `MfaCodeRequest`/`MfaDeactivateRequest`).
- [x] `MerchantLoginRequest` — campo `totpCode` opcional (construtor compat).
- [x] `MerchantPortalAuthUseCase.LoginCommand` — campo `totpCode` (construtor
      compat 2 args, espelho do `MasterLoginCommand.of`).
- [x] `MerchantPortalAuthService.login` — após status ACTIVE:
      `if (mfaService.hasActiveMfa(merchant.id())) mfaService.verifySecondFactor(...)`.
- [x] `PortalController.me` + `PortalMeResponse` — campo aditivo `mfaActive`.
- [x] `RateLimitFilter.LIMITED_PATHS` — `+ /api/v1/portal/mfa/confirm`.
- [x] `RestExceptionHandler` — `IllegalStateException("MFA ja ativado")` → 409
      (verificar mapeamento existente do master; reusar se já coberto).

## Frontend portal

- [x] `portal/src/auth.tsx` — `PortalMe.mfaActive`; `login(email, password,
      totpCode?)` enviando no body.
- [x] `portal/src/pages/LoginPage.tsx` — campo "Código TOTP (se o MFA estiver
      ativo)" opcional, sempre visível (padrão console).
- [x] `portal/src/pages/SecurityPage.tsx` — novo, espelho do console
      SecurityPage (Spec 71) com endpoints `/api/v1/portal/mfa/*`.
- [x] `portal/src/App.tsx` — rota `/security` (privada).
- [x] `portal/src/components/Layout.tsx` — item de menu `Segurança`.

## Testes unitários

- [x] `MerchantMfaServiceTest` (Mockito + Clock fixo; espelho do
      MasterMfaServiceTest — ~15+ casos).
- [x] `MerchantTotpFailurePersisterTest`.
- [x] `MerchantTotpTest` (modelo).
- [x] `PortalMfaControllerTest` (MockMvc).
- [x] Ajustar `MerchantPortalAuthServiceTest` p/ `LoginCommand` 3 args + testes
      de MFA no login.
- [x] `mvn test` verde.

## Testes E2E

- [x] `e2e-tests/global-setup.js` — TRUNCATE + `merchant_mfa_methods,
      merchant_totp, merchant_recovery_codes`.
- [x] `e2e-tests/tests/merchant-mfa.spec.js` — fluxo completo (ativar 2 passos →
      logout → login TOTP 200 / sem TOTP 401 / errado 401 / recovery 200 +
      reuso 401 → desativar → login só senha 200 → `me.mfaActive` false).
- [x] Rodar a suíte E2E do fluxo MFA.

## Docs + roadmap

- [x] `management/vision.md` — bloco **V70** (via `/spec-plan`).
- [x] `management/roadmap.md` — entrada da Spec 94 (status `implemented` ao
      final) + nota da renumeração 93→94.
- [x] Commit final com mensagem descritiva.
