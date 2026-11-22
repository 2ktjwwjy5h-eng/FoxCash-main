---
name: Planos visuais e ações de entrega (backend)
status: audited
references:
  - "management/vision.md#V31"
  - "management/specs/48-stores-storefront/requirements.md"
  - "management/specs/28-charge-idempotency/requirements.md"
  - "management/specs/19-antifraud-real/requirements.md"
  - "management/specs/41-master-profiles-activity/requirements.md"
---

# Spec 49 — Planos visuais e ações de entrega (backend)

## O que entrega

Os **planos** da loja (Spec 48) — vendidos pelos bots (Spec 52) — com:

1. **Card visual:** nome, descrição, preço, **imagem** (`imageUrl`), **cor do
   embed** (`embedColor`), **destaque** (`featured`) e `active`. O storefront
   renderiza o plano como um card/embed bonito.
2. **Ações de entrega por plano** (a decisão-chave do usuário): o vendedor
   define **exatamente o que acontece quando o pagamento é aprovado**. Cada
   plano tem **uma ou mais ações** executadas em ordem:
   - `grant_role` — dar cargo (Discord) `{roleId}`
   - `send_message` — enviar mensagem de boas-vindas `{text}`
   - `send_link` — enviar link permanente `{url, label?}`
   - `add_to_group` — adicionar ao grupo (Telegram) `{groupId}`
   - `send_file` — enviar arquivo `{fileUrl, filename?}`
   - (extensível: novo tipo = validação + adapter no bot)
3. **Compra vinculada:** `POST /api/v1/foxcash/plans/{id}/purchase` (API key do
   lojista) cria a cobrança Pix com `plan_id` (idempotência Spec 28, triagem
   antifraude Spec 19) e devolve QR/Copia-e-Cola/transactionId. O request
   carrega **`platform`** (`DISCORD`|`TELEGRAM`) e **`platformUserId`** — o
   identificador do comprador na plataforma — que o backend **persiste no
   pedido/entrega** (decisão de arquitetura 2026-08-05: PostgreSQL = única
   fonte de verdade; os bots são stateless e a entrega precisa sobreviver a
   reinício/substituição do bot). O `buyerId` opaco (antifraude, Spec 19)
   continua opcional e é hash independente.
4. **Entrega com progresso por ação:** quando a transação do plano é aprovada,
   o backend cria a entrega `PENDING` e **copia as ações do plano para a
   execução** (`plan_delivery_actions_exec` — snapshot com status por ação). O
   bot executa cada ação e confirma individualmente; quando todas `DONE` →
   entrega `DELIVERED`. Idempotente (unique por `transaction_id`; gate de
   status por ação).
5. **Identificação do destinatário centralizada no backend:** a entrega guarda
   `platform` + `platform_user_id` (e o `buyer_id_hash` para antifraude). O
   bot **não mantém estado**: ao processar (webhook `SALE_PAID` ou polling de
   pendências), ele busca a entrega na API e recebe o `platformUserId` para
   executar a ação (dar cargo, DM, grupo, link, arquivo). Nenhuma informação
   crítica depende de memória do bot.

> **Decisão de produto (2026-08-05):** o gateway passa a armazenar o
> **identificador da plataforma do comprador** (`platform` + `platform_user_id`)
> — necessária para a entrega automática ser idempotente a reinícios do bot.
> Isso **ajusta** a premissa de 2026-08-04 ("nenhum dado do comprador"): o
> backend continua **sem nome/CPF/telefone** do comprador; guarda apenas o ID
> opaco da plataforma (ex.: `123456789012345678` do Discord/Telegram),
> **criptografado em repouso**, e o `buyer_id_hash` pseudônimo para antifraude.

## Critérios de aceite

- **Planos (portal):** CRUD (`GET/POST /api/v1/portal/plans`,
  `PATCH /{id}`, `POST /{id}/activate|deactivate`) — nome 1–120, preço > 0 e ≤
  teto, descrição ≤ 500, `imageUrl` ≤ 500, `embedColor` `#RRGGBB`; 400
  `invalid_argument`, 404 `plan_not_found`; privado por lojista (404 cross-loja);
  payload de ação **nunca devolvido em claro** na listagem (só `hasDeliveryActions`).
- **Ações de entrega (portal):** `GET/POST /api/v1/portal/plans/{id}/actions`
  (lista ordenada por `seq`), `PATCH /actions/{actionId}`, `DELETE /actions/{actionId}`
  (204) — tipos validados (enum), payload obrigatório e validado por tipo,
  `seq` reordenável (0..N); sem ação → entrega é no-op `DELIVERED` direto
  (decidir: sim, plano sem ação entrega `DELIVERED` sem execução).
- **Purchase:** `POST /api/v1/foxcash/plans/{id}/purchase` {platform (DISCORD|
  TELEGRAM, obrigatório), platformUserId (obrigatório), buyerId?, expirationSeconds?,
  idempotencyKey} → 201 (mesmo contrato do `/nyx/pix/cob` + `planId`); plano
  inativo/inexistente → 409/404; replay da chave → mesma transação; `platform`
  inválido → 400 `invalid_argument`; `platformUserId` vazio → 400.
- **Entrega:** webhook PAID com `plan_id` → `plan_deliveries` PENDING (com
  `platform` + `platform_user_id` persistidos **criptografados** e o
  `buyer_id_hash`) + `plan_delivery_actions_exec` (snapshot das ações, todas
  `PENDING`) na MESMA transação; **replay de PAID não duplica**; transação sem
  plano → nada. **O destinatário fica no backend** — nenhum estado no bot.
- **Consulta/execução (API nyx):** `GET /api/v1/foxcash/deliveries/pending`,
  `GET /api/v1/foxcash/deliveries/by-transaction/{tx}` (devolve as ações com
  payload **em claro apenas ao dono**, 404 cross-loja);
  `POST /api/v1/foxcash/deliveries/{id}/actions/{seq}/complete {status: DONE|FAILED,
  note?}` — gate (só `PENDING`), replay 204; quando todas DONE → entrega
  `DELIVERED` automaticamente; qualquer FAILED → entrega `FAILED` (não pendura);
  `POST /api/v1/foxcash/deliveries/{id}/retry` — reabre as ações `FAILED` como
  `PENDING` e a entrega volta a `PENDING` (apenas do lojista dono; idempotente).
- **Garantia de concorrência (adendo 2026-08-07, commit `8ce706f`):**
  completes **concorrentes da MESMA ação** são serializados por
  `SELECT ... FOR UPDATE` (`findByDeliveryIdOrderBySeqForUpdate` no
  `DeliveryActionCompletionService`) — o 2º request bloqueia, relê a ação já
  concluída e vira **replay idempotente** (204): **1 execução, 1 registro de
  auditoria `DELIVERY_ACTION_COMPLETED`** (invariante nº 4 do
  `e2e-tests/tests/concurrency.spec.js`). O lock é aplicado também no retry.
  Sem o lock, 2 threads liam `PENDING` e executavam a ação **2×** (bug exposto
  pelo E2E). Evidência: `mvn test` 1094/0/0, E2E concurrency **7/7**,
  k6 `concurrency.js` (50 VUs, p95 451ms, 0% erro) e `critical-endpoints.js`
  (16.315 checks, webhook PAID concorrente → 204 sempre, **1 SALE_CREDIT**, 1
  delivery no banco).
- **Regressão:** `mvn test` verde; E2E **22/22 + cenário novo** (plano com 3
  ações → purchase → PAID → entrega com 3 ações PENDING → executa 1,2 → falha 3
  → entrega FAILED → retry → DONE → DELIVERED; replay idempotente; 404/409).
- **Cross-cutting:** rate limit (Spec 43), validação de entrada, logs mascarados
  (payloads de ação no `SensitiveDataLoggingFilter`), atividade `PLAN_CREATED`,
  `PLAN_PURCHASED`, `DELIVERY_ACTION_COMPLETED`, `DELIVERY_COMPLETED` (Spec 41).

## Dependências

- `management/specs/48-stores-storefront/requirements.md` (plano pertence à loja)
- `management/specs/28-charge-idempotency/requirements.md` (purchase)
- `management/specs/19-antifraud-real/requirements.md` (triagem no purchase)
- `management/specs/41-master-profiles-activity/requirements.md` (auditoria)
