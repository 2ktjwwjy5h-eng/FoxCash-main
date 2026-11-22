---
name: Planos e entrega de acesso (vendas via bot — backend) — SUPERSEDED: absorvida/evoluída pelas specs 48 (stores) e 49 (planos visuais + ações de entrega)
status: discarded
references:
  - "management/vision.md#V31"
  - "management/specs/19-antifraud-real/requirements.md"
  - "management/specs/23-pix-charge-generator/requirements.md"
  - "management/specs/35-outbound-webhook/requirements.md"
  - "management/specs/41-master-profiles-activity/requirements.md"
---

# Spec 48 — Planos e entrega de acesso (backend para vendas via bot)

## O que entrega

O backend FoxCash passa a suportar **venda de planos/produtos com entrega
automática de acesso** — a fundação que os bots de venda (Spec 49) consomem.
Hoje o bot cria cobranças avulsas (`/nyx/pix/cob`) e é avisado do pagamento
(`SALE_PAID`), mas **não há catálogo de planos nem registro de entrega**. Esta
spec adiciona:

1. **Catálogo de planos por lojista** (`merchant_plans`): nome, descrição,
   preço, `active` e o **payload de entrega** (criptografado em repouso — o que
   será liberado ao comprador: role do Discord, link, chave, mensagem). Gestão
   no portal (`/api/v1/portal/plans`, ROLE_MERCHANT_PORTAL).
2. **Compra de plano → cobrança vinculada**: `POST /api/v1/foxcash/plans/{id}/purchase`
   (API key do lojista) cria a cobrança Pix **com `plan_id`** (a mesma triagem
   antifraude e idempotência da Spec 28) e devolve QR/Copia-e-Cola/transactionId.
3. **Entrega de acesso idempotente** (`plan_deliveries`): quando a transação do
   plano é aprovada (webhook PAID), o backend cria a entrega `PENDING` na MESMA
   transação (unique por `transaction_id` — replay de PAID não duplica), com
   **snapshot do payload** na data do pagamento. O bot consulta e confirma.
4. **Bot consome e confirma**: `GET /api/v1/foxcash/deliveries/pending`,
   `GET /api/v1/foxcash/deliveries/by-transaction/{transactionId}` e
   `POST /api/v1/foxcash/deliveries/{id}/complete {status: DELIVERED|FAILED, note?}`
   — gate de status (idempotente; só `PENDING` completa), privado por lojista
   (404 cross-loja, sem revelar existência).
5. **Rastreabilidade** (Spec 41): `PLAN_CREATED`, `PLAN_PURCHASED` e
   `DELIVERY_COMPLETED` no `platform_activity_log`.

## Critérios de aceite

- **Planos:** CRUD no portal com validações (nome 1–120 obrigatório, preço
  > 0 e ≤ teto, payload obrigatório); inativar/ativar (204); 400
  `invalid_argument`, 404 `plan_not_found`; privado por lojista (cross-loja →
  404). `GET /api/v1/foxcash/plans` (API key) lista apenas planos **ativos** do
  lojista do principal.
- **Purchase:** `POST /plans/{planId}/purchase` com `buyerId` opcional e
  `idempotencyKey` obrigatória (Spec 28) → 201 com o mesmo contrato do
  `/nyx/pix/cob` + `planId`; plano inativo/inexistente → 409/404; teto de valor
  validado; replay da chave → mesma transação.
- **Entrega:** webhook PAID de transação com `plan_id` → `plan_deliveries`
  `PENDING` com `delivery_payload` snapshot (criptografado `v1:`) na mesma
  transação; **replay de PAID não duplica** (unique `transaction_id` — 1
  registro no banco); transação sem plano → nenhuma entrega.
- **Consulta/confirmação:** `GET .../deliveries/pending` (paginação, limit
  clamp 1–100) e `GET .../by-transaction/{tx}` (404 se não for do lojista);
  `POST .../deliveries/{id}/complete` — `PENDING` → `DELIVERED`/`FAILED` (204),
  replay no-op (204), lojista errado → 404, id inexistente → 404. O payload é
  devolvido apenas ao lojista dono (API key do principal).
- **Regressão:** `mvn test` verde (novos testes por serviço/controller/domínio)
  e E2E **22/22** + cenário novo (plano → purchase → webhook PAID → entrega
  PENDING → complete → ledger/wallet intactos; replay idempotente; 404/409).
- **Segurança:** `delivery_payload` mascarado nos logs (SensitiveDataLoggingFilter);
  nenhuma rota nova no README público expõe dados de terceiros.

## Dependências

- `management/specs/23-pix-charge-generator/requirements.md` (cobrança avulsa →
  compra de plano)
- `management/specs/28-charge-idempotency/requirements.md` (idempotencyKey no purchase)
- `management/specs/19-antifraud-real/requirements.md` (triagem no purchase)
- `management/specs/35-outbound-webhook/requirements.md` (`SALE_PAID` já notifica
  o bot — a entrega usa o mesmo `transactionId` do evento)
- `management/specs/41-master-profiles-activity/requirements.md` (atividade)
