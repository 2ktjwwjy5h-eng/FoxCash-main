# Spec 44 — Design: MFA 2FA TOTP para o master (B-A5)

## Contexto

O master é o operador da plataforma (Spec 13/41) — acesso comprometido do
painel master = controle total (saques administrativos, suspensões, perfis).
A homologação registrou B-A5 (2FA) como decisão de produto. Esta spec
implementa TOTP (RFC 6238) sem lib externa pesada — o algoritmo é ~30 linhas
com `Mac`/`HmacSHA1`.

## Decisões de arquitetura

### 1. TOTP próprio (RFC 6238) — sem dependência nova

- `domain/service/Totp`: `generateSecret()` (base32, 20 bytes = 160 bits,
  SecureRandom), `code(secret, timeStep)` (HMAC-SHA1 → 6 dígitos), `verify(
  secret, code, now, ±1 step)`. Testes contra vetores oficiais do RFC 6238
  (SHA-1).
- `otpauth://totp/FoxCash:{username}?secret={base32}&issuer=FoxCash` para o
  QR do app.

### 2. Persistência — migration V41 (números 36–40 consumidos pelas Specs 48–51)

**Modelagem preparada para múltiplos métodos de MFA** (decisão 2026-08-06):
o registro de métodos é separado do detalhe por tipo — WebAuthn/FIDO2 futuro =
nova tabela de detalhe, sem refatorar o fluxo.

```sql
-- Registro de métodos de MFA do master (futuro: WEBAUTHN = novo type)
CREATE TABLE master_mfa_methods (
    id           UUID PRIMARY KEY,
    master_id    UUID NOT NULL REFERENCES masters(id),
    type         VARCHAR(20) NOT NULL,          -- 'TOTP' (futuro: 'WEBAUTHN')
    status       VARCHAR(20) NOT NULL,          -- PENDING | ACTIVE | DISABLED
    created_at   TIMESTAMPTZ NOT NULL,
    activated_at TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ                    -- TTL do estado PENDING (10min)
);

-- Detalhe TOTP (só existe para type='TOTP')
CREATE TABLE master_totp (
    method_id         UUID PRIMARY KEY REFERENCES master_mfa_methods(id),
    secret_encrypted  TEXT NOT NULL,            -- v1: AES-256/GCM — NUNCA em claro
    last_code_hash    VARCHAR(64),              -- anti-replay (SHA-256 do código)
    failed_attempts   INT NOT NULL DEFAULT 0,
    locked_until      TIMESTAMPTZ
);

-- Recovery codes vinculados ao método (SHA-256 single-use)
CREATE TABLE master_recovery_codes (
    id         UUID PRIMARY KEY,
    method_id  UUID NOT NULL REFERENCES master_mfa_methods(id),
    code_hash  VARCHAR(64) NOT NULL,            -- SHA-256; single-use
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_master_recovery_code UNIQUE (method_id, code_hash)
);
```

**Secret TOTP armazenado CRIPTOGRAFADO (decisão 2026-08-06):** para verificar
um código TOTP o servidor precisa **recalcular o código esperado com o secret
original** — hash (SHA-256) é unidirecional e tornaria a verificação
impossível. O secret é persistido via **`CryptoConverter` (AES-256/GCM,
formato `v1:`)** — o mesmo padrão já usado em `pix_key` (Spec 42/B-03),
payloads de entrega (Spec 49) e secrets de webhook (Spec 35). Em memória apenas
no momento da verificação; nunca em logs.

**Recovery codes como SHA-256:** são comparados **diretamente** (hash do input
vs hash persistido), não usados para gerar códigos — hash puro é adequado
(mesmo padrão dos tokens de sessão/reset).

### 3. Fluxos

- **Ativação (2 passos):** `POST /master/mfa/activate` (ROLE_MASTER, próprio)
  → gera secret, devolve `{otpauthUri, manualEntryKey}` **uma vez** e persiste
  o secret criptografado em método `PENDING` (TTL 10min, `expires_at`).
  `POST /master/mfa/confirm {code}` verifica TOTP sobre o secret pendente →
  `ACTIVE` + `activated_at` + gera 8 recovery codes (devolve 1x, hashes
  persistidos).
- **Login:** `MasterAuthService.login` — senha ok + método MFA `ACTIVE` →
  exige `totpCode` no `LoginCommand`; verifica (janela ±1), atualiza
  `last_code_hash` (anti-replay: mesmo código na janela → 401) e
  `failed_attempts` (5 falhas → `locked_until` 15min, 401 uniforme — mesmo
  padrão B-08, sem oráculo). Recovery code aceito no lugar do TOTP no login
  (1x, hashed). Sem método ativo → login comporta-se exatamente como hoje.
- **Desativação:** `POST /master/mfa/deactivate {code, password}` — valida
  senha atual + TOTP válido (ou recovery) → remove o método + recovery codes.
- **Regenerar recovery:** `POST /master/mfa/recovery-codes` (requer TOTP
  válido) → revoga os antigos, devolve novos 1x.
- **Lockout de TOTP:** 5 falhas de código → `locked_until` 15min; **expira
  automaticamente** — não há endpoint de unlock (evita escalada
  admin-sobre-admin e oráculo; decisão 2026-08-06).

### 4. Segurança

- Secret TOTP **nunca em claro**: criptografado AES-256/GCM via
  `CryptoConverter` (`v1:` no banco). Recovery codes: SHA-256 single-use.
- **Comparação em tempo constante** (decisão 2026-08-06): recovery codes,
  `last_code_hash` e o próprio código TOTP comparados com
  `MessageDigest.isEqual` (padrão já usado na chave admin — Spec 26/L1).
- `failed_attempts`/`locked_until` no TOTP: brute-force de 6 dígitos (10^6)
  inviável por janela com lockout + rate limit por IP já existente no login.
- Anti-replay: `last_code_hash` impede reuso do mesmo código dentro da janela.
- **`manualEntryKey`** na resposta de ativação (não `secret`): o valor é
  necessário **uma única vez** para configurar o app autenticador; nunca é
  re-exposto, nunca em logs.

### 5. Auditoria (Spec 37/41) — 7 eventos reais (decisão 2026-08-06)

`MFA_ACTIVATED`, `MFA_DEACTIVATED`, `MFA_LOGIN_SUCCESS`, `MFA_LOGIN_FAILED`,
`MFA_LOCKED`, `MFA_RECOVERY_USED`, `MFA_RECOVERY_REGENERATED` — actor =
master.id(), IP via `ClientIpResolver`. **Sem `MFA_UNLOCKED`** (o lockout
expira sozinho; um evento/endpoint de unlock seria complexidade + superfície de
ataque sem ação real correspondente — adicionar apenas junto com uma
funcionalidade futura de desbloqueio).

### 6. Escopo (decisão de produto)

- 2FA **opcional por master** (cada operador ativa no próprio perfil); o admin
  **não** gerencia o TOTP de terceiros (não há endpoint de reset do 2FA de
  outro master — evita escalada de admin sobre admin). A exigência de 2FA para
  TODOS fica como evolução (config `foxcash.master.require-mfa` futuro).

## Arquivos (visão geral)

- `domain/service/Totp` + `domain/model/master/MasterMfaMethod`/
  `MasterTotp`/`MasterRecoveryCode`
- Migration `V41__master_totp.sql`
- `application/service/MasterMfaService` + portas (repo methods/TOTP/recovery)
- `MasterAuthService` (2º fator no login)
- `infrastructure/inbound/rest/MasterMfaController` (ROLE_MASTER)
- `MasterAuditRecorder`/`PlatformActivityRecorder` (7 eventos MFA)
- Testes: `TotpTest` (RFC 6238 vectors), `MasterMfaServiceTest`,
  `MasterAuthServiceTest` (2FA no login), controller
