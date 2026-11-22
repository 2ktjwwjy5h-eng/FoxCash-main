# Spec 44 — Task breakdown

Status: `audited` (2026-08-06). Ciclo SDD completo.

## Implementação

- [x] `domain/service/Totp` — RFC 6238 (SHA-1, 6 dígitos, 30s): `generateSecret`
      (base32 160 bits), `code`, `verify(±1 step)`; `otpauth://` URI.
- [x] Migration **`V41__master_totp.sql`** (números 36–40 consumidos):
      `master_mfa_methods` (registro multi-método: type/status/PENDING TTL),
      `master_totp` (**`secret_encrypted` via CryptoConverter — AES-256/GCM,
      NUNCA hash**, last_code_hash, failed_attempts, locked_until) e
      `master_recovery_codes` (code_hash SHA-256 single-use). + V42 (cascade).
- [x] `MasterMfaMethod`/`MasterTotp`/`MasterRecoveryCode` (domain model) +
      repositórios (port out) + adapter JPA + mappers.
- [x] `MasterMfaService`: activate (manualEntryKey 1x, estado pendente TTL
      10min), confirm (verifica TOTP → ativa + gera 8 recovery codes 1x),
      deactivate (senha + TOTP/recovery), regenerate-recovery (TOTP válido).
- [x] `MasterAuthService.login`: com método MFA ativo → exige `totpCode`/
      recovery; verificação com **tempo constante** (`MessageDigest.isEqual`),
      lockout de TOTP (5 falhas → 15min, expira sozinho — **sem unlock
      manual**), anti-replay (`last_code_hash`).
- [x] `MasterMfaController` (ROLE_MASTER): activate/confirm/deactivate/
      recovery-codes; auditoria Spec 37/41.
- [x] Auditoria: **7 eventos** no `PlatformAction` — `MFA_ACTIVATED`,
      `MFA_DEACTIVATED`, `MFA_LOGIN_SUCCESS`, `MFA_LOGIN_FAILED`, `MFA_LOCKED`,
      `MFA_RECOVERY_USED`, `MFA_RECOVERY_REGENERATED` (sem `MFA_UNLOCKED`).
- [x] Testes: `TotpTest` (**vetores oficiais do RFC 6238**), `MasterMfaServiceTest`
      (ativação 2 passos, recovery 1x, desativação), `MasterAuthServiceTest`
      (2FA no login: sem código 401, código válido 200, inválido 401, replay
      401, lockout TOTP), `MasterMfaControllerTest` (401/403/auditoria).

## Verificação

- [x] `mvn test` verde (**728/0/0** — 725 da implementação + 3 do
      `MasterTotpFailurePersisterTest` da auditoria) sem regressão.
- [x] E2E **27/27** (26/26 sem regressão + cenário 27 novo): ativação com
      `manualEntryKey`, login com código TOTP calculado no teste, 401 sem
      código, recovery code 1x, desativação.

## Auditoria do ciclo SDD (2026-08-06) — 1 achado corrigido

- **Achado (crítico): lockout de TOTP inócuo por rollback da transação.** O
  `MasterAuthService.login` é `@Transactional` e o `verifySecondFactor`
  (REQUIRED, mesma transação) lançava `InvalidMfaCodeException` **depois** de
  `totpRepository.save(failed)` e `activityRecorder.record(MFA_LOGIN_FAILED)`.
  Com o rollback do login, o `failed_attempts`/`locked_until` e os eventos de
  falha eram **revertidos** — o lockout de TOTP nunca disparava de verdade
  (os testes unitários usam mocks e não pegam transação). **Correção:** novo
  `MasterTotpFailurePersister` (transação PRÓPRIA `REQUIRES_NEW`, mesmo padrão
  do `MasterLoginLockoutPersister` B-08) persiste a falha + evento ANTES do
  throw. **Verificado ao vivo:** 5 falhas → banco `failed_attempts=5`,
  `locked_until` definido, código correto → 401 durante a janela, e os eventos
  `MFA_LOGIN_FAILED` (4) + `MFA_LOCKED` (1) na trilha.
- **Verificação ao vivo completa (22/22 checks):** login sem MFA 200; activate
  200 (otpauthUri + manualEntryKey 1x); re-activate com pendente 200 (descarta
  antigo); confirm 200 (8 recovery `mrc_`); re-activate com ativo → **409
  `invalid_state`**; login sem código 401; login TOTP válido 200; **replay do
  mesmo TOTP → 401** (anti-replay); TOTP inválido 401; recovery 1x → 200 e
  reuso → 401; regenerate 200 (8 novos); recovery revogado → 401; deactivate
  senha errada 401 / correta 204; login pós-desativação 200; secret `v1:` no
  banco (nunca em claro); cascata V42 remove método+detalhes+recovery.
- `mvn test` **728/0/0** e E2E **27/27** sem regressão. Fim do ciclo SDD.
