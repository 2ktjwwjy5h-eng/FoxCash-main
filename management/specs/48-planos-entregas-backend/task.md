# Spec 48 — Task breakdown

Status: `created` (2026-08-05). Aguardando `/spec-verify`.

## Implementação

- [ ] **Migration V36 (`V36__plans_deliveries.sql`)**
  - [ ] `merchant_plans` (id/merchant_id/name/description/price/active/
        delivery_payload/created_at/updated_at + índice merchant_id).
  - [ ] `pix_transactions.plan_id UUID NULL` (FK merchant_plans RESTRICT) + índice.
  - [ ] `plan_deliveries` (id/merchant_id/plan_id/transaction_id UNIQUE/
        buyer_id_hash/delivery_payload/status/attempts/note/created_at/delivered_at
        + índice `(merchant_id, status)`).
- [ ] **Domínio**
  - [ ] `Plan` (create/restore/activate/deactivate/isActive; validações nome/preço/payload).
  - [ ] `PlanDelivery` + `PlanDeliveryStatus` (PENDING/DELIVERED/FAILED;
        createPending/complete com gate de status).
  - [ ] Portas `PlanRepository` e `PlanDeliveryRepository`.
- [ ] **PixTransaction com planId**
  - [ ] `PixTransaction.planId` (domínio) + `PixTransactionJpaEntity.plan_id` + mapper.
  - [ ] `CreatePixChargeUseCase.Command.planId` (opcional) propagado pelo
        `CreatePixChargeService.create` (idempotência/antifraude intactas).
- [ ] **Planos (portal, ROLE_MERCHANT_PORTAL)**
  - [ ] `PlanManagementService` + `PortalPlanController`
        (`GET/POST /api/v1/portal/plans`, `PATCH /{id}`,
        `POST /{id}/activate|deactivate`; payload nunca devolvido em claro;
        `MerchantAccessGuard`; 400/404).
  - [ ] `GET /api/v1/foxcash/plans` (API key — planos ativos do lojista).
- [ ] **Compra (API nyx)**
  - [ ] `PlanPurchaseService` + `POST /api/v1/foxcash/plans/{id}/purchase`
        {buyerId?, expirationSeconds?, idempotencyKey} → delega ao
        `CreatePixChargeService` com planId; 404/409/400; atividade `PLAN_PURCHASED`.
- [ ] **Entrega (idempotente)**
  - [ ] `DeliveryRecorder` chamado no `ProcessPixWebhookService` (APPROVED +
        planId != null) → cria PENDING na mesma transação (unique transaction_id;
        corrida → reutiliza); atividade `SALE_PAID` inalterada.
  - [ ] `DeliveryQueryService`: `GET /api/v1/foxcash/deliveries/pending` e
        `GET .../by-transaction/{tx}` (por lojista; 404 cross-loja).
  - [ ] `DeliveryCompletionService`: `POST /api/v1/foxcash/deliveries/{id}/complete`
        {status, note?} (gate PENDING; replay 204; 404 cross-loja; 400 status
        inválido); atividade `DELIVERY_COMPLETED`.
- [ ] **Atividade e logs**
  - [ ] `PlatformAction` += PLAN_CREATED/PLAN_PURCHASED/DELIVERY_COMPLETED.
  - [ ] `SensitiveDataLoggingFilter` mascara `deliveryPayload`.
- [ ] **Testes**
  - [ ] Domínio: Plan/PlanDelivery (transições, gate, validações).
  - [ ] Serviços: PlanManagement, PlanPurchase (idempotência/plano inativo),
        DeliveryRecorder (replay no-op; corrida), DeliveryQuery (404 cross-loja),
        DeliveryCompletion (gate/replay/404).
  - [ ] REST: PortalPlanControllerTest, FoxPlanDeliveryControllerTest.
  - [ ] E2E cenário novo: plano → purchase → webhook PAID → entrega PENDING →
        complete → replay idempotente → 404/409.

## Verificação

- [ ] `mvn test` verde (novos testes por item).
- [ ] E2E **22/22 + novo cenário** sem regressão.
- [ ] Smoke ao vivo: criar plano no portal, `GET /nyx/plans`, purchase, webhook
      PAID → entrega no banco (`v1:` no payload), complete, replay no-op.
