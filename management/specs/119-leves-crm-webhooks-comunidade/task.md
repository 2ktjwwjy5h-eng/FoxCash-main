---
name: Leves — CRM enriquecido, Webhooks melhorado e Comunidade
status: implemented
---

# Spec 119 — Task: Leves (backend + UI)

## F0 — CRM enriquecido

- [ ] **T0.1** — `PortalCrmController`/service: `q` inclui `phone`; DTO aditivo
      `lastPlanName`/`lastInteractionAt`/`flowName` (Specs 83/84).
- [ ] **T0.2** — UI (`CrmPage`): colunas Plano/Fluxo/Última interação; busca
      por telefone.

## F1 — Webhooks melhorado

- [ ] **T1.1** — `GET /portal/webhooks` += `status`/`lastError`/`attempts`
      (Spec 35); `GET /portal/webhooks/{id}/sample?type=` (JSON mock sem PII).
- [ ] **T1.2** — UI (`WebhooksPage`): drawer do payload + aviso de segurança.

## F2 — Comunidade

- [ ] **T2.1** — Migration **V67** (`community_posts`, D2) + entidade/porta.
- [ ] **T2.2** — `GET /api/v1/portal/community` (paginada, cap 50).
- [ ] **T2.3** — `MasterCommunityController` (`/api/v1/master/community/posts` —
      ROLE_MASTER, RBAC Spec 69, auditoria `COMMUNITY_POST_*` Spec 37).
- [ ] **T2.4** — UI: `CommunityPage` (rota `/community`) + menu (Spec 102).

## F3 — Rotas/SPA + build + E2E

- [ ] **T3.1** — `/community` no `PortalSpaController` + `spaPublicPaths()`.
- [ ] **T3.2** — `npm run build` (tsc) limpo no portal.
- [ ] **T3.3** — E2E `portal-leves.spec.js` (D6) + suíte completa sem regressão
      + roadmap/status.

## Verificação (`/spec-verify 119`)

- [ ] Checar âncoras (`PortalCrmController`, `WebhooksPage`, `merchant_webhooks`,
      `MasterAuditRecorder`, `MasterPermissionGuard`, `App.tsx`,
      `PortalSpaController`) — sem achados.

---

## Implementação (Spec 119) — 2026-08-17

Implementação concluída (Lote 10 do plano Fase 34). F0–F3 entregues.

### F0 — CRM enriquecido
- `BotLeadPersistenceAdapter.crmSpec`: o filtro `q` agora busca também em `phone`
  (Specification `LIKE` em name/username/email/**phone**/platformUserId — Spec 119 C1).
- `CrmLeadResponse` aditivo: `lastPlanName` (nome do plano da última compra
  APPROVED do lead), `lastInteractionAt` (de `bot_leads.updated_at`, fallback
  `created_at`), `flowName` (**null** — o funil de origem da Spec 117 não está
  implementado; campo presente para o contrato).
- Vínculo lead→transação: **via `bot_funnel_events`** (evento PAID →
  `transaction_id` → `pix_transactions.plan_id` → `plans.name`) — a coluna
  `pix_transactions.platform_user_id` é criptografada em repouso (IV aleatório)
  e nunca casa por igualdade (decisão documentada no JpaRepository).
- UI (`CrmPage`): colunas Plano/Fluxo/Última interação (hora relativa pt-BR com
  `Intl.RelativeTimeFormat`) + placeholder da busca com "telefone".

### F1 — Webhooks melhorado
- `WebhookResponse` aditivo: `status` (ACTIVE/INACTIVE derivado de `active`),
  `attempts` + `lastError` (última falha de entrega do lojista — do outbox
  `notification_outbox`, Spec 35; o outbox não referencia webhook id — a falha
  é por entrega do lojista, decisão documentada).
- `GET /api/v1/portal/webhooks/{id}/sample?type=` → `WebhookSampleResponse`
  (mock do contrato Spec 35, **sem PII** — `buyerIdHash` máscara fixa `******`;
  404 cross-lojista).
- UI (`WebhooksPage`): drawer "Ver payload" (select de evento + `<pre>` do JSON
  + Copiar + Atualizar + erro com tentar novamente) + aviso de segurança fixo
  (HMAC-SHA256 / HTTPS / desativação após 5 falhas) + badge de falhas.

### F2 — Comunidade
- Migration **V78** (`community_posts` — a última real era V77; o design citava
  V67/V66 mas estava desatualizado).
- Domínio `CommunityPost` + porta `CommunityPostRepository` + JPA/adapter.
- `GET /api/v1/portal/community` (ROLE_MERCHANT_PORTAL, paginada, cap 50,
  pinados primeiro) — `PortalCommunityController`.
- `MasterCommunityController` (`/api/v1/master/community/posts` — GET/POST/
  PATCH/DELETE): ROLE_MASTER, **RBAC Spec 69** (13ª permissão
  `COMMUNITY_MANAGE` no catálogo — OPERATOR, sem step-up) e auditoria
  `COMMUNITY_POST_CREATED/UPDATED/DELETED` na mesma transação (Spec 37).
- Seed `CommunityPostSeedRunner` (post "Bem-vindo à Comunidade FoxCash",
  idempotente — só com master ativo no banco).
- UI: `CommunityPage` (rota `/community`, lista + detail modal KISS) + menu
  (Sidebar "Comunidade" deixa de ser "em breve") + SPA paths (PortalSpaController
  + spaPublicPaths).

### F3 — Build + testes
- `mvn test`: **2097/0/0** (13 novos: CommunityServiceTest 6, CommunityControllerTest
  4, WebhookSampleResponseTest 3, PortalWebhookControllerTest +3).
- `npm run build` (portal): limpo (tsc + vite).
- E2E `portal-leves.spec.js`: **3/3** (CRM com telefone+plano, webhook drawer sem
  PII, comunidade com post).
- Suíte E2E completa: **213 passed, 0 failed, 3 skipped** (skips honestos do
  rate limit — padrão do CI; rodada com `FOXCASH_SECURITY_RATE_LIMIT_ENABLED=false`
  propagada ao processo do Playwright, bug do CI 2026-08-17).

### Achados corrigidos durante a implementação
1. `plans.store_id` FK exige store — E2E cria a store 1:1 do lojista.
2. `pix_transactions.platform_user_id` criptografada — `lastPlanName` via
   `bot_funnel_events` (não por igualdade na coluna).
3. Ordem de build portal → console (`emptyOutDir` do portal apaga
   `static/console/` — achado conhecido da Spec 73); rebuild do console antes
   do package.
4. `FOXCASH_SECURITY_RATE_LIMIT_ENABLED=false` precisa ser propagada ao processo
   do Playwright (senão o teste 9 do master-config roda sem o skip honesto).
