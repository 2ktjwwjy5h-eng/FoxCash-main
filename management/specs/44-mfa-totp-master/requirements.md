---
name: MFA 2FA TOTP para o master (B-A5)
status: audited
references:
  - "management/vision.md#V27"
  - "management/specs/13-master-auth/requirements.md"
  - "management/specs/41-master-profiles-activity/requirements.md"
---

# Spec 44 — MFA 2FA TOTP para o master (B-A5)

## O que entrega

Adiciona **autenticação de dois fatores (2FA) por TOTP** (RFC 6238 —
Time-based One-Time Password, padrão Google Authenticator/Aegis/1Password)
para o login do perfil **master** — decisão de produto registrada como B-A5 na
2ª homologação e como pendência da 3ª. Endurece o acesso do operador da
plataforma (hoje protegido apenas por senha BCrypt + lockout + rate limit).

- **Provisionamento:** cada master (admin ou não) pode ativar o TOTP — o
  servidor gera um `secret` (base32, 160 bits), devolve um `otpauth://` URI
  (para o QR code do app autenticador) e o `secret` em claro **uma única vez**
  (apenas o estado persistido — secret **criptografado** em repouso).
- **Ativação segura:** o segredo só é armazenado **após** validação do
  primeiro código TOTP do app (prova de posse do aparelho).
- **Login em 2 etapas:** com TOTP ativo, o login exige **senha + código TOTP**
  (6 dígitos). O código é verificado com tolerância de janela de ±1 passo
  (clock skew) e **uso único** (replay de código antigo rejeitado).
- **Códigos de recuperação:** ao ativar, são gerados **8 códigos one-time**
  (hashed, 1x cada) para acesso em caso de perda do app — revogáveis.
- **Desativação:** apenas com código TOTP válido (ou recovery code) + senha —
  evita lockout do operador e roubo de sessão.
- **Rotas (`ROLE_MASTER`, admin-only para gerenciar o próprio):**
  `POST /api/v1/master/mfa/activate` (gera secret), `POST
  /api/v1/master/mfa/confirm` `{code}` (ativa), `POST
  /api/v1/master/mfa/deactivate` `{code, password}`, `POST
  /api/v1/master/mfa/recovery-codes` (gera/regenera) — todas com auditoria
  Spec 37 (`MFA_*`) e atividade Spec 41.
- **Auditoria:** ativação/desativação/uso de recovery code geram trilha no
  `master_audit_log` (Spec 37) e no `platform_activity_log` (Spec 41).

## Critérios de aceite

- TOTP válido (RFC 6238, SHA-1, 6 dígitos, 30s) autentica; inválido/expirado/
  reusado → 401 `unauthorized` (sem oráculo).
- Com TOTP ativo, login sem o código → 401 (mesmo com senha correta).
- Clock skew ±30s tolerado (janela ±1 passo).
- Segredo do TOTP nunca é persistido em claro (**criptografado AES-256/GCM via
  `CryptoConverter`, formato `v1:`** — o servidor precisa do valor original
  para recalcular o código esperado; hash puro tornaria a verificação
  impossível); recovery codes hashed (SHA-256) e single-use; re-ativação exige
  desativar antes (409 `invalid_state` com método ativo).
- **Lockout de TOTP** (decisão do design): contador **separado** por tentativa
  de código (5 falhas → `locked_until` 15min, 401 uniforme, expira sozinho —
  sem unlock manual); a falha persiste em transação própria (REQUIRES_NEW,
  mesmo padrão do lockout de senha B-08) — o rollback do login não reverte o
  contador nem o evento de falha. Não é oráculo: código errado, código ausente,
  lockout ativo e master sem MFA devolvem o mesmo 401.
- Sem TOTP ativo, o login comporta-se exatamente como hoje (nenhuma regressão).
- `mvn test` verde (serviço TOTP + controller + auditoria) e E2E 27/27 sem
  regressão (cenário 27: ativação 2 passos com `manualEntryKey`, login com
  código TOTP calculado no teste, 401 sem código, recovery code 1x, desativação).

## Dependências

- `management/specs/13-master-auth/requirements.md` (login/sessão/lockout)
- `management/specs/37-master-audit/requirements.md` (trilha)
- `management/specs/41-master-profiles-activity/requirements.md` (flag admin,
  relatório de atividades)
- Implementação própria do RFC 6238 com `javax.crypto.Mac`/`HmacSHA1` (o
  algoritmo é simples; testado contra os vetores oficiais do RFC 6238)
