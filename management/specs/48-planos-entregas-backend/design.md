# Spec 48 — Design: Planos e entrega de acesso (backend)

## Contexto

O bot de venda (Spec 49) precisa de: catálogo de planos, cobrança vinculada ao
plano e um registro de entrega idempotente. O backend já tem o fluxo de cobrança
(Spec 19/23/28), a aprovação via webhook (`ProcessPixWebhookService`) e o
`SALE_PAID` no outbox (Spec 35). Esta spec adiciona **planos** e **entregas**
sem alterar o contrato externo dos fluxos existentes.

## Decisões de arquitetura

### 1. Modelo de dados (migration V36)

- `merchant_plans`:
  - `id UUID PK`, `merchant_id UUID NOT NULL` (FK merchants, índice),
    `name VARCHAR(120) NOT NULL`, `description VARCHAR(255)`,
    `price NUMERIC(15,2) NOT NULL`, `active BOOLEAN NOT NULL DEFAULT TRUE`,
    `delivery_payload TEXT NOT NULL` (JSON criptografado AES-256/GCM via
    `CryptoConverter` — `v1:` no banco; ex.: `{"discordRoleId": "...", "message": "..."}`),
    `created_at TIMESTAMP`, `updated_at TIMESTAMP`.
- `pix_transactions.plan_id UUID NULL` (FK `merchant_plans`, RESTRICT; coluna
  nova — cobranças avulsas continuam `NULL`). Índice `ix_pix_transactions_plan`.
- `plan_deliveries`:
  - `id UUID PK`, `merchant_id UUID NOT NULL` (FK merchants, índice
    `(merchant_id, status)`), `plan_id UUID NOT NULL` (FK merchant_plans),
    `transaction_id UUID NOT NULL UNIQUE` (idempotência — replay de PAID não
    duplica), `buyer_id_hash VARCHAR(64)`, `delivery_payload TEXT` (snapshot
    criptografado do payload NA DATA do pagamento), `status VARCHAR(20) NOT NULL`
    (PENDING/DELIVERED/FAILED), `attempts INT NOT NULL DEFAULT 0`,
    `note VARCHAR(255)`, `created_at TIMESTAMP`, `delivered_at TIMESTAMP`.

### 2. Domínio

- `Plan` (domain/model/plan): `create(merchantId, name, description, price,
  deliveryPayload, now)` + `restore` + `activate()/deactivate()` + `isActive()`.
  Validações: nome não-branco ≤120, preço > 0 (Money) e ≤
  `foxcash.portal.charge-max-amount`, payload não-branco.
- `PlanDelivery` (domain/model/delivery): record imutável com `PlanDeliveryStatus`
  (PENDING/DELIVERED/FAILED); `createPending(merchant, plan, transaction,
  buyerIdHash, payloadSnapshot, now)` e `complete(status, note, now)` (gate:
  apenas PENDING; transições inválidas lançam `IllegalStateException`).
- Portas: `PlanRepository` (save, findByIdAndMerchantId, findByMerchantId,
  findByMerchantIdAndActive) e `PlanDeliveryRepository` (save,
  findByTransactionId, findByMerchantIdAndStatus, findByIdAndMerchantId,
  findPendingByMerchantId com limite).

### 3. Fluxo de compra

- `PlanPurchaseService` (aplicação, `@Service`) — rota `POST
  /api/v1/foxcash/plans/{id}/purchase` (API key do lojista):
  1. Busca o plano ativo do lojista (404/409).
  2. Monta `CreatePixChargeUseCase.Command(merchantId, plan.price(),
     expirationSeconds, buyerId, "Plano: " + name, idempotencyKey, planId)`
     — **`planId` é campo novo opcional** no command (Spec 28 preservada).
  3. Delega ao `CreatePixChargeService.create(...)` (idempotência, antifraude,
     teto, PSP fora da transação) e devolve o `Result` + `planId`.
- `PixTransaction` ganha `planId` (campo persistido `plan_id`); `CreatePixChargeService`
  propaga o `planId` ao criar/restaurar a transação.

### 4. Registro da entrega (idempotente)

- `DeliveryRecorder` (aplicação): chamado pelo `ProcessPixWebhookService` no
  ramo `APPROVED`, **depois** do `creditWallet`, **somente quando**
  `transaction.planId() != null`:
  - `planDeliveryRepository.findByTransactionId(tx.id)` — se existe → no-op
    (replay). Senão: lê o plano (sempre existe — FK RESTRICT), cria
    `PlanDelivery.createPending(..., payloadSnapshot = plan.deliveryPayload(), ...)`
    e salva — **na mesma transação** do APPROVED (idempotência por `transaction_id`).
- Replay de PAID (transação já APPROVED) retorna antes no service — sem dupla
  entrega; o unique `transaction_id` é o backstop em corrida
  (`DataIntegrityViolationException` → reutiliza a PENDING).

### 5. Consulta e confirmação da entrega (API nyx, API key do lojista)

- `DeliveryQueryService`: `GET /api/v1/foxcash/deliveries/pending` (paginado,
  `size` clamp 1–100, ordenado por `created_at` DESC) e
  `GET /api/v1/foxcash/deliveries/by-transaction/{tx}` — ambos filtram pelo
  `merchantId` do principal (404 cross-loja sem revelar existência). Devolvem o
  `delivery_payload` **descriptografado** apenas ao dono.
- `DeliveryCompletionService`: `POST /api/v1/foxcash/deliveries/{id}/complete`
  `{status, note?}` — carrega por `id + merchantId` (404 cross-loja); se
  `status == PENDING` → `complete(status, note, now)` (DELIVERED|FAILED, 204);
  replay (já DELIVERED/FAILED) → no-op 204; status inválido → 400
  `invalid_argument`. Registra `DELIVERY_COMPLETED` na atividade (Spec 41).

### 6. Gestão de planos (portal, ROLE_MERCHANT_PORTAL)

- `PlanManagementService` + `PortalPlanController` (`/api/v1/portal/plans`):
  `GET` (lista do lojista), `POST` (201, `{id, name, description, price, active,
  hasPayload}` — payload nunca devolvido em claro), `PATCH {name?, description?,
  price?, deliveryPayload?}` (200), `POST /{id}/activate|deactivate` (204).
  400 `invalid_argument`, 404 `plan_not_found`, `MerchantAccessGuard` para o
  recurso. Registra `PLAN_CREATED`.

### 7. Atividade (Spec 41) e logs

- `PlatformAction` ganha `PLAN_CREATED`, `PLAN_PURCHASED`, `DELIVERY_COMPLETED`.
- `deliveryPayload`/`plan_deliveries.delivery_payload` entra no mascaramento do
  `SensitiveDataLoggingFilter` (chaves sensíveis — pode conter segredo de acesso).

### 8. Segurança

- Acesso por recurso (principal == merchantId) em TODAS as rotas (portal e nyx).
- Payload criptografado em repouso; devolvido em claro apenas ao lojista dono.
- Nenhuma rota expõe dado de outro lojista (404 cross-loja).

## Arquivos (visão geral)

- `domain/model/plan/Plan.java`, `domain/model/delivery/PlanDelivery.java` (+ enums)
- `domain/port/out/PlanRepository.java`, `PlanDeliveryRepository.java`
- `application/service/PlanManagementService.java`, `PlanPurchaseService.java`,
  `DeliveryRecorder.java`, `DeliveryQueryService.java`, `DeliveryCompletionService.java`
- `infrastructure/outbound/persistence/entity/PlanJpaEntity.java`,
  `PlanDeliveryJpaEntity.java` (+ mappers/adapters)
- `infrastructure/inbound/rest/PortalPlanController.java`,
  `FoxPlanDeliveryController.java` (nyx) + DTOs
- `infrastructure/outbound/persistence/entity/PixTransactionJpaEntity.java` (plan_id)
- `ProcessPixWebhookService.java` (chama `DeliveryRecorder` no APPROVED)
- `domain/model/activity/PlatformAction.java` (novos valores)
- `infrastructure/security/SensitiveDataLoggingFilter.java` (mascarar payload)
- migration `V36__plans_deliveries.sql`
- Testes: domínio, serviços, controllers; E2E cenário novo
