# Spec 49 — Design: Planos visuais e ações de entrega (backend)

## Contexto

Os planos da Spec 48 ganham visual e **ações de entrega** tipadas. A entrega
passa a ser executada **ação por ação** (progresso), com snapshot das ações na
data do pagamento — o backend sabe exatamente o que acontecer quando o
pagamento for aprovado.

## Decisões de arquitetura

### 1. Modelo (migration V36 — parte 2)

- `plans`: `id UUID PK`, `store_id UUID NOT NULL` (FK stores; loja implica
  merchant), `name VARCHAR(120)`, `description VARCHAR(500)`,
  `price NUMERIC(15,2)`, `image_url VARCHAR(500)`, `embed_color VARCHAR(9)`,
  `featured BOOLEAN DEFAULT FALSE`, `active BOOLEAN DEFAULT TRUE`,
  `created_at`, `updated_at`. Índice `(store_id, active)`.
- `plan_delivery_actions` (config do plano): `id UUID PK`, `plan_id FK`,
  `seq INT`, `action_type VARCHAR(30)` (enum:
  GRANT_ROLE/SEND_MESSAGE/SEND_LINK/ADD_TO_GROUP/SEND_FILE),
  `payload TEXT NOT NULL` (JSON **criptografado** via `CryptoConverter` —
  `{roleId}`, `{text}`, `{url,label}`, `{groupId}`, `{fileUrl,filename}`),
  `created_at`. Unique `(plan_id, seq)`.
- `plan_deliveries`: `id UUID PK`, `store_id FK`, `plan_id FK`,
  `transaction_id UUID UNIQUE`, `platform VARCHAR(20)` (DISCORD/TELEGRAM),
  `platform_user_id VARCHAR(64)` (**criptografado** via `CryptoConverter` — ID
  da plataforma do comprador; decisão 2026-08-05: PostgreSQL = única fonte de
  verdade, bots stateless), `buyer_id_hash VARCHAR(64)` (antifraude, opcional),
  `status VARCHAR(20)` (PENDING/DELIVERED/FAILED), `created_at`,
  `delivered_at`. Índice `(store_id, status)`.
- `plan_delivery_actions_exec` (execução): `id UUID PK`,
  `delivery_id FK`, `seq INT`, `action_type VARCHAR(30)`,
  `payload_snapshot TEXT` (criptografado — snapshot da data do pagamento),
  `status VARCHAR(20)` (PENDING/DONE/FAILED), `note VARCHAR(255)`,
  `executed_at`. Unique `(delivery_id, seq)`.
- `pix_transactions.plan_id UUID NULL` (FK plans RESTRICT) + índice.

### 2. Domínio

- `Plan` (store/plan): create/restore/update visual/activate/deactivate;
  `validate` (nome/preço/descrição/URL/cor).
- `DeliveryActionType` (enum) + `DeliveryAction` (config: seq, type, payload).
- `PlanDelivery` + `PlanDeliveryStatus`; `DeliveryActionExec` +
  `DeliveryActionStatus`:
  - `completeAction(seq, status, note, now)` — gate: só PENDING → DONE|FAILED;
  - `allDone()` → entrega `DELIVERED`; qualquer FAILED → entrega `FAILED`.
- Portas: `PlanRepository`, `PlanDeliveryActionRepository`,
  `PlanDeliveryRepository`, `PlanDeliveryActionExecRepository`.

### 3. Serviços

- `PlanService` (portal): CRUD de planos + CRUD de ações (reordenação por seq),
  payload validado por tipo e **criptografado em repouso**; listagem nunca
  devolve payload (só `hasDeliveryActions`).
- `PlanPurchaseService` (nyx): valida plano ativo + `platform`/`platformUserId`
  (400 se ausentes/inválidos) → `CreatePixChargeUseCase` com `planId` (Spec
  28/19 intactas). O `platform`/`platformUserId` são **persistidos na
  transação** (via `PixTransaction.platform`/`platformUserId`, criptografados) —
  nada fica só no bot.
- `DeliveryRecorder` (chamado no `ProcessPixWebhookService` no APPROVED quando
  `plan_id != null`): cria `plan_deliveries` PENDING com
  `platform`/`platform_user_id` (lidos da transação) + copia as ações ativas do
  plano para `plan_delivery_actions_exec` (snapshot criptografado) na MESMA
  transação; corrida na unique → reutiliza.
- `DeliveryQueryService` (nyx): pending / by-transaction (por lojista; 404
  cross-loja) — devolve ações com payload **em claro apenas ao dono** e o
  `platform`/`platformUserId` do destinatário (para o bot executar a ação).
- `DeliveryActionCompletionService` (nyx): `complete(id, seq, status, note)`
  por lojista — gate por ação; ao completar, avalia `allDone()`/FAILED e
  transiciona a entrega; atividade Spec 41. **Concorrência (2026-08-07):** as
  ações da entrega são lidas com `SELECT ... FOR UPDATE`
  (`findByDeliveryIdOrderBySeqForUpdate`) — conclusões concorrentes da MESMA
  ação serializam; o 2º request bloqueia, relê a ação já concluída e vira
  replay idempotente (204) → **1 execução, 1 auditoria**. O mesmo lock é usado
  no retry (mesma família de lock da Spec 26).

### 4. Transição da entrega (automática no complete)

- Toda ação `DONE` → se todas `DONE` → entrega `DELIVERED` (mesma transação).
- Ação `FAILED` → entrega `FAILED` (note da ação); retry = re-executar a ação
  (gate permite PENDING → DONE depois de FAILED? **Não** — uma ação FAILED é
  terminal; o vendedor re-ativa pela tela ou o bot cria nova entrega? Decisão:
  ação FAILED é terminal; entrega FAILED permite `POST /deliveries/{id}/retry`
  (reabre as ações FAILED como PENDING, apenas do lojista). Assim o bot
  reprocessa sem pendurar.)
- Plano sem ações → entrega nasce `DELIVERED` direto (no-op).

### 5. Payload das ações (formatos validados)

| Tipo | Payload | Exemplo |
|---|---|---|
| `grant_role` | `{roleId}` | `{"roleId":"123456789012345678"}` |
| `send_message` | `{text}` | `{"text":"Bem-vinda à Bella Premium! 💜"}` |
| `send_link` | `{url, label?}` | `{"url":"https://t.me/+abc","label":"Grupo VIP"}` |
| `add_to_group` | `{groupId}` | `{"groupId":"-1001234567890"}` |
| `send_file` | `{fileUrl, filename?}` | `{"fileUrl":"https://cdn.x/album.zip"}` |

## Arquivos (visão geral)

- Domínio: `plan/Plan.java`, `plan/DeliveryAction.java`,
  `delivery/PlanDelivery.java`, `delivery/DeliveryActionExec.java` (+ enums)
- Portas: `PlanRepository`, `PlanDeliveryActionRepository`,
  `PlanDeliveryRepository`, `PlanDeliveryActionExecRepository`
- Serviços: `PlanService`, `PlanPurchaseService`, `DeliveryRecorder`,
  `DeliveryQueryService`, `DeliveryActionCompletionService`
- Persistência: entidades/mappers/adapters (plans, actions, deliveries, exec)
- REST: `PortalPlanController`, `FoxPlanController` (purchase),
  `FoxDeliveryController` + DTOs
- `PixTransaction`/`CreatePixChargeService` (planId)
- `ProcessPixWebhookService` (chama `DeliveryRecorder`)
- `PlatformAction` += PLAN_PURCHASED/DELIVERY_ACTION_COMPLETED/DELIVERY_COMPLETED
- `SensitiveDataLoggingFilter` (mascarar payload)
- migration `V36` (parte 2)
