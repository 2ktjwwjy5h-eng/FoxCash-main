---
name: Painel de usuários — backend escrita (configuração fina por lojista + controle de conta)
status: audited
---

# Spec 98 — Design: Painel de usuários (backend de escrita)

## D1 — Conceito

Um **aggregate `MerchantConfig`** por lojista (colunas em `merchants`, não
tabela separada — KISS: 1:1 e os dados são lidos nos gates existentes) + um
**`MerchantConfigService`** que aplica RBAC/auditoria/step-up em todas as
escritas. O comportamento em runtime (taxa, rota, rate limit, api_blocked,
honeypot) é **consultado pelos pontos existentes** (PricingCalculator,
PixProviderRouter, AuthenticatedRateLimitFilter, ApiKeyAuthFilter,
MerchantStatusControlService).

```text
MasterConfigController (REST, ROLE_MASTER) — config/debit/honeypot/ban/activate/deactivate
   └─ MerchantConfigService (aplicacao) — RBAC + auditoria + step-up
        ├─ Merchant (domínio) — métodos de domínio p/ cada flag/valor
        ├─ MerchantConfigPersistenceAdapter — reads/writes das colunas
        ├─ LedgerRecorders (Specs 27/34) — ADMIN_DEBIT
        └─ OutboxPublisher (Spec 35) — HONEYPOT_TRIGGERED (com throttle)
```

## D2 — Migration V61 (aditiva)

```sql
ALTER TABLE merchants
  ADD COLUMN pix_fee_type        VARCHAR(8)  NULL,          -- 'FIXED' | 'PERCENT' | NULL
  ADD COLUMN pix_fee_fixed       NUMERIC(15,2) NULL,
  ADD COLUMN pix_fee_percent     NUMERIC(5,2)  NULL,        -- 0-100
  ADD COLUMN pix_fee_threshold   NUMERIC(15,2) NULL,        -- valor de corte p/ PERCENT
  ADD COLUMN withdrawal_fee      NUMERIC(15,2) NULL,        -- override do global (Spec 88)
  ADD COLUMN pix_min             NUMERIC(15,2) NULL,
  ADD COLUMN pix_max             NUMERIC(15,2) NULL,
  ADD COLUMN withdrawal_min      NUMERIC(15,2) NULL,
  ADD COLUMN withdrawal_max      NUMERIC(15,2) NULL,
  ADD COLUMN rate_limit_rps      INTEGER      NULL,         -- override req/s
  ADD COLUMN rate_limit_rpm      INTEGER      NULL,         -- override req/min
  ADD COLUMN api_blocked         BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN honeypot            BOOLEAN NOT NULL DEFAULT FALSE;
```

- **V61** (V60 = Spec 97). `MerchantStatus` ganha `BANNED` — como o status é
  `VARCHAR(20)` sem CHECK (V1), **não precisa de migration de constraint**; o
  enum novo + os gates que já usam `status` passam a tratar BANNED (ver D4).
- Ledger `entry_type` é `VARCHAR(30)` (Spec 52) — `ADMIN_DEBIT` (10 chars) OK.

## D3 — Domínio: `Merchant` + métodos de domínio

Novos métodos no aggregate `Merchant` (e campos na entity/mapper):

- `updateConfig(...)` — valida o conjunto (valores negativos → exceção;
  `pix_fee_type` desconhecido → exceção; percentual 0–100; `pix_fee_threshold
  > 0`; `withdrawal_fee ≥ 0`; limites `min ≤ max` quando ambos; `rate_limit_*
  > 0`). **Sem `gateway_route`** — rota é por perfil (Spec 122).
- `deactivate()` / `activate()` — transições de status (reuso das regras do
  `MerchantStatusControlService`; `INACTIVE` não autentica no portal — Spec 20).
- `ban(reason)` — `ACTIVE|SUSPENDED|INACTIVE → BANNED`, irreversível.
- `debit(amount, reason)` — `walletBalance −= amount` (negativo permitido —
  decisão, padrão estorno Spec 26) + dispara o ledger via callback/evento.
- Flags simples: `markApiBlocked(boolean)`, `markHoneypot(boolean)`.

**Entity/mapper**: `MerchantJpaEntity` ganha as colunas novas; `MerchantMapper`
restaura/grava.

## D4 — Comportamento em runtime (pontos de integração)

### D4.1 — Regra de taxa (`PricingCalculator`)

O `PricingCalculator` (domain/service) hoje usa `plan.transactionFee()`. Passa a
receber o override do lojista (via porta `MerchantPricingConfigProvider`):

```text
transactionFee(merchantId, amount):
  cfg = provider.config(merchantId)
  if cfg == null || cfg.pixFeeType == null: return plan.transactionFee()   # global
  if cfg.pixFeeType == FIXED:               return cfg.pixFeeFixed
  if amount > cfg.pixFeeThreshold:          return round(amount * cfg.pixFeePercent / 100)
  return cfg.pixFeeFixed
```

- Chamadores (fatura Spec 8, `netAmount` Spec 25, `MerchantSalesService`) usam
  o `PricingCalculator` — **mudança de assinatura** (adiciona `merchantId` +
  `amount` quando ausentes). Verificar os call-sites (achado de implementação a
  registrar) — a fatura atual usa o fee do plano; passa a usar o override.
- `netAmount` do panel (Spec 97) passa a refletir o override automaticamente.

### D4.2 — Gateway/rota (`PixProviderRouter`) — **removido desta spec**

O override de rota **não é mais coluna do `merchants`**: passou a ser **por
perfil de operação** (`merchant_profiles`, Spec 122 — dona da entidade). O
`RoutingPolicyResolver` (Spec 91) ganha `resolveProviders(merchantId,
profileId)` na Spec 122 (override do perfil vence risco). O master console desta
spec **não gerencia rota** (nenhum endpoint/UI aqui). O **honeypot** continua
nesta spec: o `PixProviderRouter` roteia o lojista honeypot para o trap
**antes** de qualquer policy/perfil (D4.5).

### D4.3 — Rate limit por lojista (`AuthenticatedRateLimitFilter`)

O filtro (Spec 58) resolve o principal (merchantId) e consulta o override:

```text
limit = configProvider.rateLimit(merchantId)      # rps/rpm
if limit != null: usar limit (janela 60s; rps → x60 para janela, ou janela 1s)
else: usar o teto global (RateLimitProperties)
```

- **Fail-closed**: se a config não existir → global (nunca menos restritivo).
- O filtro já resolve o `Merchant` no request (Spec 65 — `AuthenticatedMerchantResolver`)
  → **sem query extra** (reuso). Para o mapeamento rps/rpm → janela: `rps`
  vira janela de 1s (`path|principal` com `max = rps`); `rpm` vira janela de
  60s com `max = rpm`. Prioridade: `rps` se definido, senão `rpm`, senão global
  (decisão de implementação a registrar).

### D4.4 — `api_blocked` (`ApiKeyAuthFilter`)

No `ApiKeyAuthFilter` (Spec 12/24), após resolver a chave do lojista: se
`merchant.apiBlocked` → **403** `api_blocked` (antes de prosseguir com escopos).
Não afeta `ROLE_ADMIN`/`ROLE_MASTER`. O portal (sessão) não usa API key → segue
funcionando.

### D4.5 — Honeypot (provedor trap)

- **`HoneypotPixProviderClient`** (infrastructure/outbound/pix,
  `@ConditionalOnProperty(foxcash.psp.honeypot-enabled=true)`, default ON no
  runtime quando houver lojista honeypot — o router injeta direto, sem
  `@ConditionalOnProperty` por provider): implementa `PixProviderClient`
  (Spec 91 — `createCharge`/`getChargeByCode`). `createCharge` → devolve QR
  fictício + `externalReference` (`HP<uuid>`) e **nunca** dispara webhook PAID
  (nenhum `SALE_CREDIT`). `getChargeByCode` → `NOT_FOUND` (ou FOUND com status
  pending — decisão: NOT_FOUND para não revelar; registrado na implementação).
- **Router**: se `merchant.honeypot` → provider = trap **antes** da policy
  (e **antes** de considerar circuito/kill-switch do adquirente real).
- **Saques bloqueados**: o gate de saque (Spec 14/32 — `Merchant.withdraw` e
  `adminWithdraw`) consulta `merchant.honeypot` → 409 `honeypot_blocked`
  (vendedor) e **bloqueia o administrativo também** (não há saque de honeypot).
- **Alerta com throttle**: cada `createCharge` de lojista honeypot publica
  `HONEYPOT_TRIGGERED` no outbox (Spec 35, `REQUIRES_NEW`, fail-safe) com
  **throttle por janela** (padrão `SecurityAlertPublisher` Spec 64 — ex.: 1
  evento a cada 60s por lojista; `HoneypotAlertPublisher` próprio).
- **Auditoria**: `HONEYPOT_MARKED`/`HONEYPOT_UNMARKED` (Spec 37) na ação do
  master; `HONEYPOT_TRIGGERED` no `PlatformAction` (Spec 41, actor SYSTEM) em
  cada disparo (throttled).

### D4.6 — Kill switch e ban

- `deactivate` → `INACTIVE`: o `MerchantStatusControlService` (Spec 16) já
  gerencia `suspend/release`; a config reusa o mesmo padrão com os status
  existentes (`Merchant.deactivate()`). `INACTIVE` não autentica no portal
  (Spec 20) e não processa cobranças (Spec 12).
- `ban` → `BANNED`: novo valor no enum; todos os pontos que validam status
  (`ApiKeyAuthFilter` — só ACTIVE; `MerchantPortalAuthService` —
  `merchant_not_active`; `Merchant.withdraw`/cobranças) tratam BANNED como
  bloqueado. **Irreversível** (sem endpoint de unbanned).

## D5 — Endpoints (ROLE_MASTER, em `MasterConfigController`)

| Endpoint | Ação | RBAC | Step-up | Auditoria |
|---|---|---|---|---|
| `GET /merchants/{id}/config` | leitura | master ativo | — | — |
| `PUT /merchants/{id}/config` | atualiza (parcial) | OPERATOR | — | `MERCHANT_CONFIG_UPDATED` |
| `POST /merchants/{id}/config/debit` `{amount, reason}` | débito manual | OPERATOR | **sim** | `ADMIN_DEBIT` |
| `POST /merchants/{id}/config/honeypot` `{enabled}` | honeypot | OPERATOR | — | `HONEYPOT_MARKED/UNMARKED` |
| `POST /merchants/{id}/config/deactivate` | INACTIVE | OPERATOR | — | `MERCHANT_DEACTIVATED` |
| `POST /merchants/{id}/config/activate` | ACTIVE | OPERATOR | — | `MERCHANT_ACTIVATED` |
| `POST /merchants/{id}/ban` `{reason}` | BANNED | OPERATOR | **sim** | `MERCHANT_BANNED` |

- **Permissões**: reuso das 9 existentes (`merchant.suspend`/`release` para
  activate/deactivate — ou permissões novas `merchant.config`,
  `merchant.debit`, `merchant.ban`; decisão: **adicionar 3 novas** —
  `merchant.config`, `merchant.debit`, `merchant.ban` — para a auditoria e o
  guard ficarem explícitos; o catálogo vai de 9 → 12, todas concedidas ao nível
  OPERATOR; `merchant.debit`/`merchant.ban` entram na lista de step-up
  `required-permissions`). Ver achado do verify (Spec 89: catálogo
  `MasterPermission` + `permissions[]` do `/master/me` via `values()`).
- **Handler**: `GlobalExceptionHandler` ganha 400 `invalid_argument`,
  `reason_required`, 409 `invalid_state`/`insufficient_balance` (se debit >
  saldo? decisão: negativo permitido → **sem** insufficient_balance), 404
  `merchant_not_found`, 403 `forbidden`/`step_up_required`.
- **DTOs**: `MerchantConfigResponse`, `MerchantConfigRequest` (parcial — PATCH
  semantics), `DebitRequest {amount, reason}`, `BanRequest {reason}`,
  `HoneypotRequest {enabled}`.

## D6 — Configuração

```yaml
foxcash:
  psp:
    honeypot-enabled: ${NYX_PSP_HONEYPOT_ENABLED:true}   # kill-switch do trap
  master:
    config: {}
  security:
    master:
      step-up:
        required-permissions: # Spec 69 — + merchant.debit, merchant.ban
          - withdrawal.administrative
          - receivable.write_off
          - invoice.collect
          - master.profiles.manage
          - merchant.debit
          - merchant.ban
  billing:
    withdrawal-fee: 2.50      # global (Spec 88) — override por lojista
```

`MerchantConfigProperties` não é necessário (colunas no banco) além do
`honeypot-enabled` já em `PspProperties` (aditivo). Binding: testar o
`MasterStepUpProperties` contra o yml real (padrão Spec 68).

## D7 — Testes

**Unitários/integração:** `MerchantConfigServiceTest` (validações, PATCH
parcial, activate/deactivate, ban irreversível, honeypot mark/unmark, debit
com ledger + saldo negativo, RBAC 403, step-up 403), `MerchantTest` (novos
métodos de domínio), `PricingCalculatorTest` (FIXED/PERCENT/threshold/fallback),
`PixProviderRouterTest` (override + honeypot precedência), `AuthenticatedRateLimitFilterTest`
(override + fail-closed), `ApiKeyAuthFilterTest` (api_blocked 403),
`HoneypotAlertPublisherTest` (throttle), `MasterConfigControllerTest`,
`MasterAuditRecorderTest` (novas actions).

**E2E (`master-config.spec.js`):** login master → PUT config (taxa FIXED →
fatura/net refletem; PERCENT com threshold → acima cobra %; gateway override →
cobrança grava o provider do override; rate limit por lojista → 429 no teto do
lojista) → deactivate → login portal 403/API 401 → activate → 200 →
honeypot on → cobrança cria com `HP` e **sem** webhook PAID (nenhum crédito;
saldo inalterado) + saque 409 `honeypot_blocked` + `HONEYPOT_TRIGGERED`
throttled no outbox → honeypot off → volta ao normal → api_blocked → API key
403 → debit com step-up (janela TOTP) → saldo −amount + ledger `ADMIN_DEBIT`
+ auditoria → ban → API/portal/saques/cobranças bloqueados + auditoria; suíte
completa sem regressão.

## D8 — Fora de escopo (reforço)

- UI (Spec 100), rejeitar conta, unbanned, telefone (V60/97).
- Mudança de contrato das rotas existentes (aditivo; `/master/merchants/{id}`
  de escrita NÃO conflita com `/master/merchants/panel` de leitura — rotas
  distintas).
