---
name: MFA TOTP no portal do lojista
status: audited
---

# Spec 94 — Design: MFA TOTP no portal do lojista

## Princípio: espelho do padrão master (Spec 44)

A implementação é **espelho 1:1** do MFA do master (Spec 44 `audited`), com o
mínimo de divergência:

| Master (Spec 44) | Lojista (Spec 94) |
|---|---|
| `MasterMfaMethod` / `MasterTotp` / `MasterRecoveryCode` | `MerchantMfaMethod` / `MerchantTotp` / `MerchantRecoveryCode` |
| `MasterMfaService` + `MasterTotpFailurePersister` | `MerchantMfaService` + `MerchantTotpFailurePersister` |
| `MasterMfaMethodRepository` / `MasterTotpRepository` / `MasterRecoveryCodeRepository` | `MerchantMfaMethodRepository` / `MerchantTotpRepository` / `MerchantRecoveryCodeRepository` |
| tabelas `master_mfa_methods` / `master_totp` / `master_recovery_codes` (V41/V42) | tabelas `merchant_mfa_methods` / `merchant_totp` / `merchant_recovery_codes` (V59) |
| `POST /api/v1/master/mfa/activate|confirm|deactivate|recovery-codes` | `POST /api/v1/portal/mfa/activate|confirm|deactivate|recovery-codes` |
| `MasterAuthService.login` → `verifySecondFactor` se `hasActiveMfa` | `MerchantPortalAuthService.login` → idem |
| eventos `MFA_*` actor `MASTER` | mesmos eventos `MFA_*` actor `MERCHANT` |

**Reuso integral (sem duplicar código):** `Totp` (RFC 6238), `CryptoConverter`
(AES-256/GCM), `DocumentHasher` (SHA-256), `BCryptPasswordHasher`, exceção
`InvalidMfaCodeException` e o record `MfaCodeRequest`/`MfaDeactivateRequest`
(já são agnósticos de master/merchant — reutilizados como estão).

**Não há step-up** (Spec 69 é do master; o lojista exige MFA apenas no login —
fora de escopo, ver requirements).

## Arquitetura (camadas hexagonais — mesmo layout do master)

```
domain/model/merchant/MerchantMfaMethod.java        (PENDING → ACTIVE, TTL 10min)
domain/model/merchant/MerchantTotp.java             (secret criptografado, anti-replay, lockout)
domain/model/merchant/MerchantRecoveryCode.java     (SHA-256 single-use)
domain/port/in/MerchantMfaUseCase.java              (activate/confirm/deactivate/regenerate/hasActiveMfa/verifySecondFactor)
domain/port/out/MerchantMfaMethodRepository.java
domain/port/out/MerchantTotpRepository.java         (findByIdForUpdate — SEC-01)
domain/port/out/MerchantRecoveryCodeRepository.java (consumeIfOpen — SEC-20)
application/service/MerchantMfaService.java
application/service/MerchantTotpFailurePersister.java (REQUIRES_NEW + FOR UPDATE)
infrastructure/inbound/rest/PortalMfaController.java  (ROLE_MERCHANT_PORTAL, /api/v1/portal/mfa/**)
infrastructure/outbound/persistence/entity/MerchantMfaMethodJpaEntity.java
infrastructure/outbound/persistence/entity/MerchantTotpJpaEntity.java
infrastructure/outbound/persistence/entity/MerchantRecoveryCodeJpaEntity.java
infrastructure/outbound/persistence/adapter/MerchantMfaMethodPersistenceAdapter.java
infrastructure/outbound/persistence/adapter/MerchantTotpPersistenceAdapter.java
infrastructure/outbound/persistence/adapter/MerchantRecoveryCodePersistenceAdapter.java
infrastructure/outbound/persistence/mapper/MerchantMfaMethodMapper.java
infrastructure/outbound/persistence/mapper/MerchantTotpMapper.java
infrastructure/outbound/persistence/mapper/MerchantRecoveryCodeMapper.java
infrastructure/outbound/persistence/repository/MerchantMfaMethodJpaRepository.java
infrastructure/outbound/persistence/repository/MerchantTotpJpaRepository.java
infrastructure/outbound/persistence/repository/MerchantRecoveryCodeJpaRepository.java
resources/db/migration/V59__merchant_mfa.sql
```

## Migration `V59__merchant_mfa.sql`

Espelho das V41/V42 do master **com CASCADE nativo** (lição V42: as FKs foram
criadas sem `ON DELETE CASCADE` e precisaram de migration de correção — aqui já
nascem corretas):

```sql
CREATE TABLE merchant_mfa_methods (
    id           UUID PRIMARY KEY,
    merchant_id  UUID NOT NULL REFERENCES merchants(id),
    type         VARCHAR(20) NOT NULL,          -- 'TOTP' (futuro: 'WEBAUTHN')
    status       VARCHAR(20) NOT NULL,          -- PENDING | ACTIVE | DISABLED
    created_at   TIMESTAMPTZ NOT NULL,
    activated_at TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ                    -- TTL do estado PENDING (10min)
);
CREATE INDEX ix_merchant_mfa_methods_merchant ON merchant_mfa_methods(merchant_id);

CREATE TABLE merchant_totp (
    method_id         UUID PRIMARY KEY REFERENCES merchant_mfa_methods(id) ON DELETE CASCADE,
    secret_encrypted  TEXT NOT NULL,            -- CryptoConverter AES-256/GCM (v1:)
    last_code_hash    VARCHAR(64),              -- anti-replay (SHA-256)
    failed_attempts   INT NOT NULL DEFAULT 0,
    locked_until      TIMESTAMPTZ
);

CREATE TABLE merchant_recovery_codes (
    id         UUID PRIMARY KEY,
    method_id  UUID NOT NULL REFERENCES merchant_mfa_methods(id) ON DELETE CASCADE,
    code_hash  VARCHAR(64) NOT NULL,            -- SHA-256; single-use
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_merchant_recovery_code UNIQUE (method_id, code_hash)
);
CREATE INDEX ix_merchant_recovery_codes_method ON merchant_recovery_codes(method_id);
```

## API (espelho do master com prefixo portal)

- `POST /api/v1/portal/mfa/activate` (ROLE_MERCHANT_PORTAL) → 200
  `{otpauthUri, manualEntryKey}` — 1x; 409 `mfa_already_active` se houver método
  ativo (o master lança `IllegalStateException` → mapeado em `RestExceptionHandler`
  como 409; espelhar).
- `POST /api/v1/portal/mfa/confirm` `{code}` → 200 `{recoveryCodes[8]}` — 1x;
  TTL PENDING expirado → 401 uniforme.
- `POST /api/v1/portal/mfa/deactivate` `{code, password}` → 204. **Senha é a do
  portal** (`MerchantCredential.passwordHash`, BCrypt) + TOTP/recovery válido.
- `POST /api/v1/portal/mfa/recovery-codes` `{code}` → 200 `{recoveryCodes[8]}` — 1x.
- `GET /api/v1/portal/me` → **aditivo** `mfaActive: boolean` (contrato atual
  inalterado — `PortalMeResponse.from` passa a receber o estado do MFA).

### Login com 2º fator

`MerchantPortalAuthUseCase.LoginCommand` ganha `totpCode` (3º campo, com
construtor compat de 2 args — espelho do `MasterLoginCommand.of`). No
`MerchantPortalAuthService.login`, **após** o check de status ACTIVE (mesma
posição do master — a senha e o lockout já foram validados):

```java
if (mfaService.hasActiveMfa(merchant.id())) {
    mfaService.verifySecondFactor(merchant.id(), command.totpCode());
}
```

401 uniforme (mesma `InvalidMfaCodeException` → 401 `unauthorized`). Sem MFA →
campo ignorado, zero regressão.

### Rate limit

- `AuthenticatedRateLimitFilter` cobre **todas** as rotas autenticadas
  `/api/v1/**` por principal — as 4 rotas `/api/v1/portal/mfa/**` já entram.
- **Teto por IP** em `RateLimitFilter.LIMITED_PATHS`: adicionar
  `/api/v1/portal/mfa/confirm` (superfície de verificação de código — mesmo
  racional do step-up, Spec 69 critério 12: defesa em profundidade antes do
  lockout; os demais endpoints são cobertos por principal).

## Diferenças intencionais vs master

1. **`requireMerchant`** usa `MerchantRepository.findById` (mesmo padrão do
   `PortalController.me`) + `MerchantNotFoundException`.
2. **Desativação valida senha do portal** via `MerchantCredentialRepository`
   (o master usa `Master.passwordHash()` direto; o lojista separa credencial —
   Spec 20/39). Senha incorreta → `InvalidMfaCodeException` (401 uniforme, sem
   oráculo).
3. **`otpauthUri`** usa `merchant.name()` como label (o master usa
   `master.username()`).
4. **Auditoria**: actor `PlatformActorType.MERCHANT`, `entityType "MERCHANT"`,
   entityId = merchantId, merchantId = merchantId (mesmo padrão do
   `MERCHANT_LOGIN`).
5. **Sem `findByIdAndMasterId`** no `MerchantMfaMethodRepository` — o master o
   usa para o step-up (Spec 69); o lojista não tem step-up. Manter o port
   mínimo: `save`, `findActiveByMerchantId`, `findPendingByMerchantId`,
   `deleteByMerchantId`.

## Frontend (portal)

- **`PortalMe`** (auth.tsx) ganha `mfaActive?: boolean`.
- **`LoginPage.tsx`**: campo "Código TOTP (se o MFA estiver ativo)" — opcional,
  sempre visível (padrão console Spec 71), enviado como `totpCode`.
- **`auth.tsx` `login()`**: assinatura passa a aceitar `totpCode?: string` e
  enviar no body.
- **`pages/SecurityPage.tsx`** (novo): espelho do console
  `SecurityPage.tsx` (Spec 71) com endpoints `/api/v1/portal/mfa/*` — ativar em
  2 passos (chave manual + URI 1x, input do código, recovery codes 1x com
  copiar), desativar (senha + TOTP/recovery), regenerar recovery codes. Sem
  step-up (não existe no portal). `me.mfaActive` alimenta o estado.
- **`App.tsx`**: rota `/security` (privada) + **`Layout.tsx`**: item de menu
  `{ to: '/security', label: 'Segurança' }` (final da sidebar).

## Testes

### Unitários (espelho do `MasterMfaServiceTest` — 57 testes)

- `MerchantMfaServiceTest` (Mockito, Clock fixo):
  - ativação (gera secret, `otpauthUri` com name, criptografa secret, 409 se
    ativo, descarta PENDING anterior);
  - confirmação (TOTP válido → ativa + 8 recovery codes; expirado → 401;
    código errado → 401);
  - desativação (senha errada → 401; TOTP válido → 204; recovery → 204);
  - regeneração (requer TOTP; apaga antigos; 8 novos);
  - `verifySecondFactor`: lockout ativo → 401; recovery `mrc_*` consumeIfOpen
    atômico; TOTP válido anti-replay (mesmo código 2x → 2ª falha); falha →
    `failurePersister.recordFailure` chamado (5 → lockout);
  - `hasActiveMfa`.
- `MerchantTotpFailurePersisterTest` (REQUIRES_NEW + FOR UPDATE, evento
  MFA_LOGIN_FAILED/MFA_LOCKED).
- `MerchantTotpTest` (modelo: recordValidCode/recordFailedAttempt/isLocked).
- `MerchantMfaControllerTest` → `PortalMfaControllerTest` (MockMvc: status,
  validação `@NotBlank`, mapeamento 409, DTOs).
- Login: ajustar `MerchantPortalAuthServiceTest` p/ o novo `LoginCommand`
  (3 args) + testes de MFA no login (com/sem MFA ativo).

### E2E (`e2e-tests/tests/merchant-mfa.spec.js`)

Fluxo completo (helper `helpers/totp.js` já existe):

1. registrar lojista → login (senha só) 200 → `me.mfaActive === false`;
2. `POST /portal/mfa/activate` → `manualEntryKey`/`otpauthUri`; `confirm` com
   TOTP do helper → `recoveryCodes` (8× `mrc_`);
3. logout → login sem TOTP → **401**; login com TOTP correto → **200**; login
   com TOTP errado → **401**; login com recovery code → **200** (e reuso do
   mesmo recovery → 401);
4. `me.mfaActive === true`; desativar (senha + TOTP) → 204; logout → login só
   senha → **200**; `me.mfaActive === false`.

`global-setup.js`: adicionar `merchant_mfa_methods, merchant_totp,
merchant_recovery_codes` ao TRUNCATE (antes de `merchants` na lista — a
cascata do CASCADE já cobriria, mas o TRUNCATE explícito das filhas antes dos
pais mantém o padrão do arquivo).

## Decisões registradas

- **D1** Número da spec: **94** (o roadmap reserva "Spec 93" para
  Split/sub-contas — decisão do usuário 2026-08-14; renumeração feita em
  2026-08-15 para preservar a narrativa).
- **D2** Sem step-up para o lojista nesta spec (fora de escopo — requirements).
- **D3** `totpCode` no `LoginCommand` com construtor compat — zero regressão
  nos call sites existentes (espelho do `MasterLoginCommand.of`).
- **D4** CASCADE nativo nas FKs da migration V59 (lição V42).
