---
name: MFA TOTP no portal do lojista
status: audited
references:
  - "management/vision.md#V70"
  - "management/specs/44-mfa-totp-master/requirements.md"
  - "management/specs/20-portal-foundation/requirements.md"
  - "management/specs/39-email-registration/requirements.md"
  - "management/specs/41-master-profiles-activity/requirements.md"
  - "management/specs/63-edge-waf-bot-mitigation/requirements.md"
---

# Spec 94 — MFA TOTP no portal do lojista

## O que entrega

O lojista pode **ativar autenticação em 2 fatores (TOTP, RFC 6238)** no portal —
fechando a lacuna apontada na auditoria de segurança (2026-08-14): o master já
tem MFA (Spec 44 `audited`), o lojista não. Com o MFA ativo, a senha **não
basta** para o login no portal: exige o 2º fator (código TOTP do app
autenticador **ou** recovery code single-use).

Implementação **espelho do padrão master (Spec 44)** — mesma lib custom
(`Totp`), mesma criptografia em repouso (`CryptoConverter` AES-256/GCM), mesmo
lockout de TOTP (5 falhas → 15 min, expira sozinho), mesmos recovery codes
(SHA-256 single-use, consume atômico) e mesmos eventos de auditoria (Spec 41,
actor `MERCHANT`). **Sem dependência externa** (sem Clerk/Google Auth — stack
Java, decisão da auditoria).

O login continua **single-step** (padrão do master): a request de login aceita
`totpCode` opcional; com MFA ativo, código ausente/inválido → **401 uniforme**
(sem oráculo sobre a existência do MFA). O portal ganha o campo TOTP no login
(sempre visível, padrão do console) e uma página **Segurança** para ativar em 2
passos / desativar / regenerar recovery codes.

## Contexto (por que agora)

Auditoria de segurança 2026-08-14 (lacuna #1): o portal do lojista é o
**ponto de entrada com acesso a dinheiro** (saques, destinos, credenciais API)
e hoje é protegido apenas por senha + Turnstile (Spec 63). Um token de sessão
roubado (phishing/device comprometido) dá acesso total. O master já tem MFA
(Spec 44) e step-up (Spec 69); estender o mesmo padrão ao lojista é o maior
valor da auditoria — o resto da lista (RBAC/rate limit/HTTPS/WAF/cripto) já
está coberto (Specs 43/58/59/63/69).

## Critérios de aceite

1. **Ativação em 2 passos** — `POST /api/v1/portal/mfa/activate`
   (ROLE_MERCHANT_PORTAL) devolve `{otpauthUri, manualEntryKey}` **1x** (secret
   nunca mais sai do servidor); `POST /confirm` valida o 1º TOTP (janela ±1) e
   ativa + devolve os **8 recovery codes 1x**. Secret **criptografado em
   repouso** (CryptoConverter AES-256/GCM — hash puro tornaria a verificação
   impossível). Método PENDING expira em 10 min (descartado no confirm
   expirado). Ativação exige não haver método ativo (409/erro claro).
2. **Login com 2º fator** — `POST /api/v1/merchant-auth/login` aceita
   `totpCode` opcional; com MFA ativo, senha não basta: `verifySecondFactor`
   valida TOTP (janela ±1, tempo constante, **anti-replay** via
   `last_code_hash`) **ou** recovery code single-use. Código ausente/inválido →
   **401 uniforme** (mesmo tempo/código, sem oráculo). Sem MFA → fluxo atual
   intacto.
3. **Recovery codes** — 8 códigos `mrc_<32 hex>` no confirm/regeneração,
   armazenados **SHA-256**, single-use com **consume atômico** (`UPDATE`
   condicional — corrida com o mesmo código executa 1x, padrão SEC-20);
   regeneração exige TOTP válido (`POST /api/v1/portal/mfa/recovery-codes`).
4. **Desativação com posse** — `POST /api/v1/portal/mfa/deactivate` exige
   **senha atual** (BCrypt) + TOTP/recovery válido → 204 (mesmo padrão master).
5. **Lockout de TOTP** — 5 falhas → 15 min (expira sozinho, sem unlock
   manual); incremento **atômico** (persister `REQUIRES_NEW` + `FOR UPDATE` —
   o throw do login não reverte o lockout; sem lost update entre verificações
   paralelas). Recovery code válido em lockout? Não — o lockout cobre o TOTP;
   recovery continua disponível (padrão master).
6. **Auditoria (Spec 41)** — eventos MFA com actor `MERCHANT`:
   `MFA_ACTIVATED`, `MFA_DEACTIVATED`, `MFA_LOGIN_SUCCESS`,
   `MFA_LOGIN_FAILED`, `MFA_LOCKED`, `MFA_RECOVERY_USED`,
   `MFA_RECOVERY_REGENERATED` (eventos já existentes do master — reutilizados).
7. **`GET /api/v1/portal/me`** — ganha `mfaActive` (aditivo; contrato
   existente inalterado) para a UI decidir o estado da página Segurança.
8. **Frontend portal** — campo **TOTP no login** (opcional, sempre visível —
   padrão console) e página **Segurança** (`/security` + menu): ativar em 2
   passos (chave manual + URI 1x, input do código, recovery codes 1x com
   copiar), desativar (senha + TOTP/recovery), regenerar recovery codes;
   padrão UX da Spec 73 (banners, Field, ConfirmDialog não necessário —
   desativação tem posse).
9. **Migration `V59__merchant_mfa.sql`** — `merchant_mfa_methods` /
   `merchant_totp` / `merchant_recovery_codes` (espelho das V41/V42 do master
   com CASCADE nativo nas FKs — sem precisar de migration de correção).
10. **Testes** — unitários (service: ativação/confirmação/lockout/anti-replay/
    recovery/desativação; login com MFA; controller) + `mvn test` verde + E2E
    `merchant-mfa.spec.js` (ativar 2 passos → logout → login com TOTP 200 /
    código errado 401 / recovery code 200 → desativar → login só senha 200).

## Dependências

- **Spec 44** (padrão MFA master) — `Totp` (RFC 6238 custom), lockout,
  recovery codes, anti-replay — reuso integral.
- **Specs 20/39** (auth do portal — e-mail + BCrypt + sessão opaca).
- **Spec 41** (auditoria `platform_activity_log` — eventos `MFA_*`).
- **Spec 58** (rate limit por principal — as rotas `/api/v1/portal/mfa/**`
  autenticadas são cobertas automaticamente).
- **Spec 73** (padrão UX dos frontends).

## Fora de escopo

- **Step-up MFA** para ações sensíveis do lojista (saque/credenciais) — fica
  para spec futura (o master tem step-up, Spec 69; o lojista só exige MFA no
  login nesta fase).
- **WebAuthn/FIDO2** — a modelagem (`merchant_mfa_methods.type`) suporta, mas
  só TOTP é implementado.
- **MFA para a API key** (integração bot) — o 2º fator é do login do portal;
  as chamadas de API por chave continuam como estão.
- **Clerk/Auth externo** — stack Java; MFA próprio (decisão da auditoria).
