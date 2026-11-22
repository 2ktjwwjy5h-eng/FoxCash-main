---
name: Recuperação de senha do lojista (token self-service one-time)
status: audited
references:
  - "management/vision.md#V23"
  - "management/specs/20-portal-foundation/requirements.md"
  - "management/specs/39-email-registration/requirements.md"
  - "management/specs/13-master-auth/requirements.md"
---

# Spec 40 — Recuperação de senha do lojista (token self-service one-time)

## O que entrega

O lojista que esqueceu a senha do portal se recupera **sem depender do master**
nem de infraestrutura de e-mail: dois endpoints **públicos** em
`/api/v1/merchant-auth`:

1. **`POST /api/v1/merchant-auth/forgot-password`** `{email}` — se o e-mail
   existe, gera um token one-time `rst_<32 hex>` (apenas o hash **SHA-256** é
   persistido em `password_reset_tokens`), com **expiração** (~30min
   configurável) e **um único token ativo por lojista** (novo pedido invalida o
   anterior). Em **dev** (`foxcash.auth.expose-reset-token=true`, default) o
   token é devolvido na resposta `{resetToken}`; em **produção** o flag é
   `false` e a resposta é sempre **204** (anti-enumeração — idêntica com e-mail
   existente ou não), com a entrega do token por canal futuro (e-mail/webhook).
2. **`POST /api/v1/merchant-auth/reset-password`** `{token, newPassword}` —
   valida o token (existe, não usado, não expirado), redefine a senha da
   credencial do portal (hash **BCrypt**, nunca em claro), **marca o token como
   usado** (single-use) e **revoga todas as sessões ativas** do lojista (troca
   de senha invalida sessões existentes).

Mudanças de contrato (aditivas — nenhuma rota existente muda):

- `POST /api/v1/merchant-auth/forgot-password` — público (rate limit por IP,
  como login).
- `POST /api/v1/merchant-auth/reset-password` — público (posse do token = posse
  do controle da conta).
- Migration **V32** — tabela `password_reset_tokens` (hash SHA-256 do token,
  expiração, single-use via `used_at`, índice único parcial por lojista).

## Critérios de aceite

1. `POST /api/v1/merchant-auth/forgot-password` `{email}` de lojista existente
   → **200** `{resetToken: "rst_<32 hex>"}` em dev (`expose-reset-token=true`);
   e-mail inexistente → **204** (sem vazar existência); e-mail inválido/ausente
   → **400 `validation_error`**.
2. Com `expose-reset-token=false` (prod) → **sempre 204** (existente ou não —
   anti-enumeração) e nenhum token em claro na resposta.
3. Token `rst_<32 hex>` é **one-time**: persistido apenas como hash SHA-256;
   novo `forgot-password` invalida o token anterior do mesmo lojista (um único
   ativo); expira após `foxcash.auth.reset-token-ttl-seconds` (default 1800).
4. `POST /api/v1/merchant-auth/reset-password` `{token, newPassword}` com token
   válido → **204**; `password_hash` da credencial trocado (BCrypt novo) na
   mesma transação; token marcado `used_at`; **todas as sessões ativas do
   lojista revogadas** (o token de sessão antigo deixa de autenticar).
5. Token desconhecido / expirado / já usado → **400 `invalid_token`** (mesmo
   código para os três — sem oráculo); `newPassword` < 8 ou > 72 → **400
   `validation_error`**; `newPassword` igual à senha atual → permitido (não há
   restrição de reuso no escopo).
6. `forgot-password` sujeito a **rate limit** por IP (mesmo padrão do login —
   `RateLimitFilter`), e `resetToken`/`token` **mascarados nos logs**
   (`SensitiveDataLoggingFilter`).
7. `mvn test` verde (387 + novos) e E2E sem regressão (cenário novo: forgot →
   reset → login com a nova senha 200, senha antiga 401, token reusado 400,
   e-mail inexistente 204, sessão antiga revogada 401).

## Dependências

- Spec 20 (sessões do portal `merchant_sessions` — revogadas no reset).
- Spec 39 (login por e-mail — o identificador do forgot é o `email`).
- Spec 13 (padrão de token opaco/hash SHA-256, espelhado no reset token).
- Spec 18 (rate limit por IP, ADR-009).

## Observações

- **Sem infra de e-mail:** o token é devolvido na resposta apenas em dev; em
  prod a entrega fica para integração futura (e-mail ou evento no webhook do
  bot, Spec 35). O backend não depende de provedor de e-mail nesta spec.
- **Anti-enumeração:** em prod a resposta do `forgot-password` é idêntica com
  e-mail existente ou não (204). Em dev o token só aparece quando o e-mail
  existe (conveniência de teste).
- **`resetToken` é segredo de posse:** quem tem o token pode redefinir a senha —
  por isso nunca é persistido em claro, expira, é single-use e as sessões são
  revogadas no uso.
- **Não há restrição de reuso de senha** (fora de escopo — política de senha
  futura). `DocumentValidator`/`EmailValidator` permanecem (Spec 31/39).
