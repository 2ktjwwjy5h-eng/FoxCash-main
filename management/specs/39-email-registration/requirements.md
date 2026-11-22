---
name: Cadastro com nome, e-mail e senha (sem CPF/CNPJ)
status: audited
references:
  - "management/vision.md#V22"
  - "management/specs/5-merchant-duplicate-conflict/requirements.md"
  - "management/specs/12-api-authentication/requirements.md"
  - "management/specs/18-security-hardening/requirements.md"
  - "management/specs/20-portal-foundation/requirements.md"
---

# Spec 39 — Cadastro com nome, e-mail e senha (sem CPF/CNPJ)

## O que entrega

O cadastro de conta passa a exigir **apenas nome, e-mail e senha** — sem
CPF/CNPJ nem comprovação documental (decisão do usuário 2026-08-05). O e-mail
vira o **identificador único de login** do portal; a **senha é escolhida pelo
usuário** no cadastro (não há mais senha inicial gerada). O documento
(CPF/CNPJ) deixa de ser obrigatório — lojistas existentes preservam o dado
(coluna opcional/nula); novos cadastros não têm documento. `trading_name`/
`legal_name` são substituídos por um único `name` (backfill de `legal_name`).

Mudanças de contrato (intencionais):

1. **`POST /api/v1/merchants`** — request `{name, email, password}`:
   - `name` obrigatório (1–120);
   - `email` obrigatório, formato válido, **único** (normalizado em minúsculas) —
     duplicado → **409 `merchant_already_exists`** (mesmo código do documento);
   - `password` obrigatória, **escolhida pelo usuário** (8–72 caracteres) —
     hash BCrypt persistido em `merchant_credentials` (nunca o texto claro);
   - **não devolve mais `portalPassword`** (o usuário escolheu a senha).
2. **`POST /api/v1/merchant-auth/login`** — request `{email, password}`
   (substitui o `username`/documento).
3. **Respostas do lojista** (`MerchantRegisteredResponse`, `MerchantResponse`,
   `PortalMeResponse`, `MerchantLoginResponse`) usam `name`/`email` no lugar de
   `tradingName`/`legalName`/`document`.
4. **Migration V31**: `merchants.email` (único) + `merchants.name`;
   `document`/`document_hash` opcionais (nulos); `trading_name`/`legal_name`
   removidos (backfill `name = COALESCE(legal_name, trading_name, '')`).

A chave de API (`mch_<32 hex>`) **continua** gerada e devolvida **uma única
vez** no cadastro (Spec 12 — auth da API via `X-Api-Key`; não é comprovação, é
credencial de integração).

## Critérios de aceite

1. `POST /api/v1/merchants` com `{name, email, password}` → **201** com
   `{id, name, email, status, fees, createdAt, apiKey}` (sem `portalPassword`);
   e-mail normalizado em minúsculas; apenas hashes persistidos (BCrypt da
   senha, SHA-256 da chave).
2. E-mail duplicado (sequencial ou em corrida) → **409 `merchant_already_exists`**
   (backstop do índice único `uq_merchants_email`).
3. `email` inválido (formato), `name` ausente ou `password` < 8 → **400
   `validation_error`**/`invalid_argument`.
4. `POST /api/v1/merchant-auth/login` com `{email, password}` → 200 com token
   (o e-mail é o identificador; documento não funciona mais como login);
   senha errada → 401; lojista não-ACTIVE → 403 `merchant_not_active`.
5. Migration V31 aplicada: `merchants.email` + `name` presentes; `document`/
   `document_hash` **anuláveis**; `trading_name`/`legal_name` removidos;
   backfill `name = legal_name` para lojistas existentes; índice único parcial
   `uq_merchants_document_hash` (documento nulo não colide).
6. Logs sanitizam `email` (nunca em claro no request/response DEBUG).
7. `mvn test` verde (382 + ajustes/novos) e E2E sem regressão (cadastro/login
   atualizados para e-mail; cenário de duplicado via e-mail).

## Dependências

- Spec 5 (duplicado → 409 — agora por e-mail).
- Spec 12 (chave de API mantida no cadastro).
- Spec 18 (validação — `DocumentValidator` deixa de ser usada no cadastro, mas
  permanece para destinos de saque PIX_CPF/CNPJ, Spec 31).
- Spec 20 (login do portal — identificador passa de documento para e-mail).

## Observações

- **`DocumentValidator` continua** (usado pela Spec 31 nos destinos de saque
  PIX_CPF/PIX_CNPJ — a chave Pix do destino não é a identidade do cadastro).
- **`Merchant.restore` atualizado para o modelo novo** (`name`/`email`/`document`);
  os testes existentes que usavam a assinatura legada (`tradingName`/`legalName`)
  foram migrados para o modelo novo (email `null` no restore de legado).
- **E-mail em texto plano** (login identifier — busca exata por
  `lower(email)`); documentado no design (diferente do documento, que é
  fiscal e criptografado). `email` entra na lista de campos mascarados dos logs.
- **Sem KYC** (decisão 2026-08-04): o cadastro nunca pediu KYC documental;
  agora também não pede o documento fiscal.
