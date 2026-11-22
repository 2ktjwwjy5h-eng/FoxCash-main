---
name: Autenticação da API — API key por lojista + chave admin (X-Api-Key, hash SHA-256)
status: audited
references:
  - "management/vision.md#V10"
  - "management/adr.md#ADR-006"
  - "management/specs/4-security-and-e2e/requirements.md"
  - "management/specs/8-admin-dashboard/requirements.md"
  - "management/specs/10-psp-integration-foundation/requirements.md"
---

# Spec 12 — Autenticação da API (API keys)

## O que entrega

Protege os endpoints `/api/v1/**` — hoje **todos** `permitAll` no
`SecurityConfig` — com **autenticação por API key** no header `X-Api-Key`
(ADR-006), seguindo o padrão do próprio Pagar.me:

1. **Chave por lojista**: gerada no cadastro (`POST /api/v1/merchants` — único
   endpoint público de escrita), devolvida **uma única vez** na resposta e
   armazenada apenas como **hash SHA-256** (`merchants.api_key_hash`, migration
   **V6**, índice único) — o texto claro nunca é persistido. Um
   `ApiKeyGenerator` (infra) gera a chave (`mch_<32 hex>`); o hash é calculado
   com SHA-256.
2. **Chave admin**: separada, via env var `NYX_ADMIN_API_KEY` (sem default em
   prod — fail-fast; default fake em dev) — protege `/api/v1/admin/**`
   (dashboard da Spec 8).
3. **Filtro Spring Security `ApiKeyAuthFilter`** (infrastructure/security):
   resolve o header `X-Api-Key` → `ROLE_ADMIN` (chave admin) ou `ROLE_MERCHANT`
   (hash → lojista **ACTIVE**, com o `merchantId` no principal para futuras
   checagens de posse). Rota protegida sem chave/inválida → **401
   unauthorized**; lojista tentando `/api/v1/admin/**` → **403 forbidden**.
4. **Rotas públicas**: `POST /api/v1/merchants` (cadastro) e `/webhooks/pix`
   (o `WebhookSignatureFilter` da Spec 10 continua protegendo o webhook). Todo o
   restante exige chave válida.
5. **E2E atualizado**: a jornada captura a chave no cadastro e envia
   `X-Api-Key` nas chamadas seguintes (webhook continua sem chave).

## Critérios de aceite

1. `POST /api/v1/merchants` (público) gera a chave do lojista (formato
   `mch_<32 hex>`), devolve o texto claro **uma única vez** no body da resposta
   (campo `apiKey`) e persiste apenas o hash SHA-256 em `merchants.api_key_hash`
   (migration V6 com índice único). O texto claro **não** aparece em nenhuma
   outra resposta nem no banco.
2. `ApiKeyAuthFilter` autentica por `X-Api-Key`: chave admin → `ROLE_ADMIN`;
   hash de chave de lojista `ACTIVE` → `ROLE_MERCHANT` com `merchantId` no
   principal; chave inválida/desconhecida ou lojista não-`ACTIVE` → nenhuma
   autenticação.
3. Rota protegida sem chave ou com chave inválida → **401 unauthorized** (JSON
   `ErrorResponse`); `GET /api/v1/admin/*` com chave de lojista → **403
   forbidden**; com chave admin → 200.
4. `SecurityConfig` protege `/api/v1/**` (exceto `POST /api/v1/merchants` e
   `/webhooks/pix`) e exige `ROLE_ADMIN` em `/api/v1/admin/**`; o
   `WebhookSignatureFilter` continua funcionando em `/webhooks/pix`.
5. `application.yml` (dev) define `foxcash.security.admin-api-key` com default
   fake; `application-prod.yml` (prod) exige `NYX_ADMIN_API_KEY` sem default —
   boot `prod` sem a env var falha (fail-fast), mesma mecânica da Spec 9.
6. `mvn test` verde (**49 testes: 39 + 10 novos** — 5 do `ApiKeyAuthFilterTest`,
   3 do `ApiKeyGeneratorTest`, 2 do `MerchantTest`) e E2E **5/5** sem regressão
   (a jornada E2E usa a chave emitida no cadastro).
7. `README.md` documenta a autenticação (header `X-Api-Key`, obtenção da chave
   no cadastro, chave admin, rotas públicas); ADR-006 registrada.

## Dependências

- Spec 4 (SecurityConfig existente, `require-https`, `ErrorResponse`).
- Spec 8 (endpoints admin que passam a exigir `ROLE_ADMIN`).
- Spec 9 (padrão fail-fast de env vars no profile prod).
- Spec 10 (webhook continua público via assinatura HMAC).
- ADR-006 (decisão do mecanismo de autenticação).

## Observações

- **Por que hash SHA-256 e não criptografia AES?** A chave precisa de lookup
  exato (`findByApiKeyHash`) — o mesmo padrão de `document_hash` (ADR-004). O
  hash é unidirecional; a chave em claro existe apenas na resposta do cadastro.
- **Por que só o cadastro é público?** É onde a credencial é emitida. O
  `MerchantResponse` normal **não** expõe `apiKey` (o campo só aparece no
  retorno do `POST /merchants`).
- **Autorização de posse** (lojista só acessa os próprios dados) fica **fora de
  escopo** — spec futura; o principal já carrega o `merchantId` para isso
  (ADR-006).
- **Rotação/expiração de chaves** fica fora de escopo nesta versão (ADR-006).
- **Fluxo do webhook**: `/webhooks/pix` permanece público no Spring Security
  pois a autenticação é a assinatura HMAC (Spec 10) — `ApiKeyAuthFilter` não
  deve bloquear esse caminho.
- **Formato da resposta de erro**: reutiliza `ErrorResponse` (`code`/`message`)
  do `GlobalExceptionHandler` — 401 `unauthorized`, 403 `forbidden`.
- **Chave em dev**: default fake em `application.yml` para testes locais; em
  prod vem de `NYX_ADMIN_API_KEY` (fail-fast).
