---
name: Auditoria de Broadcasts no Master Console
status: audited
---

# Spec 89 — Task: Auditoria de Broadcasts no Master Console

## F0 — Backend: query master + force-cancel

- [x] **T0.1** — `MasterBroadcastQueryService`: `list(filters, pageable)` com
      join `broadcasts → store_bots → merchants` (filtros `merchantId`,
      `botId`, `status`, `mode`, `from`/`to`; ordenação `created_at DESC`;
      cap 100) e `detail(id)` (broadcast + merchant/bot + logs agrupados
      `{success, blocked, failed}` + lista). Porta `BroadcastRepository`
      ganha `searchMaster(...)` e **`findById(id)` sem escopo** (a existente
      `findByIdAndMerchantId` é merchant-scoped — o master não tem merchant)
      + adapter JPA (query nativa/JPQL).
- [x] **T0.2** — `MasterBroadcastCommandService.forceCancel(broadcastId,
      operatorId, ip)`: carrega via `findById` sem escopo (404
      `broadcast_not_found`), gate `status == PENDING` (senão 409
      `invalid_broadcast_state`), **novo método no domínio
      `Broadcast.forceCancel("Cancelled by Master Operator", now)`** (`PENDING
      → FAILED`; o `fail()` existente só aceita `PROCESSING` — achado do
      verify) + save + auditoria na mesma transação.
- [x] **T0.3** — `MasterAuditAction` += `BROADCAST_FORCE_CANCELLED` (Spec 37 —
      trilha do MASTER, via `MasterAuditRecorder`; **não** é `PlatformAction`/
      Spec 41, que é de MERCHANT/SYSTEM).
- [x] **T0.4** — `MasterPermission` += `BROADCAST_FORCE_CANCEL(
      "broadcast.force_cancel")` (9ª — aditiva ao catálogo da Spec 69);
      `MasterBroadcastController` (controller master existente): `GET
      /api/v1/master/broadcasts`, `GET /api/v1/master/broadcasts/{id}`,
      `POST /api/v1/master/broadcasts/{id}/force-cancel`. Leitura sem guard
      extra; force-cancel com `MasterPermissionGuard.requirePermission(actorId,
      BROADCAST_FORCE_CANCEL)` (403 `forbidden`). DTOs
      `MasterBroadcastSummary`/`MasterBroadcastDetail` (sem
      `media_file_id`/token na resposta). Touch-points de doc: javadoc do
      `MasterPermission` e `MasterProfileService` ("7 operacionais" → 8).
- [x] **T0.5** — Testes unitários/integração: query (filtros/paginação/sem
      content na lista), force-cancel (sucesso + auditoria na mesma
      transação/409/404), RBAC (leitura master OK; force-cancel sem permissão
      → 403). `mvn test` verde.

## F1 — Frontend (Master Console)

- [x] **T1.1** — `BroadcastsPage.tsx` em `console/src/pages/` (padrão
      `BotsPage`): tabela (Lojista, Bot, Modo, Status, Agendado, Criado),
      filtros (lojista/bot/status/modo), paginação server-side; empty state;
      Spinner (padrão Spec 73).
- [x] **T1.2** — Rota `/console/broadcasts` no `App.tsx` + item de menu;
      **achado do verify:** `/broadcasts`/`/broadcasts/*` do portal JÁ estão no
      `spaPublicPaths()` (Spec 80) — falta adicionar `/console/broadcasts` na
      seção do console do `spaPublicPaths()` (`SecurityConfig`) e nos paths do
      `ConsoleSpaController` (padrão Spec 80 — full reload).
- [x] **T1.3** — `BroadcastDetailDrawer.tsx` (painel lateral read-only):
      conteúdo formatado, mídia (thumbnail), grade de botões, métricas
      SUCCESS/BLOCKED/FAILED + logs por destinatário.
- [x] **T1.4** — Botão "Cancelar Campanha" (só quando `status = PENDING`)
      com `ConfirmDialog` tom `danger` → `POST force-cancel` → linha vira
      `FAILED` + `SuccessBanner`; 409/403 → `ErrorBanner` (padrão Spec 73).
- [x] **T1.5** — `npm run build` (tsc) limpo no console.

## F2 — E2E e fechamento

- [x] **T2.1** — E2E `master-console-broadcast.spec.js`: login admin → lista
      com seeds (PENDING/PROCESSING/COMPLETED) → drawer (conteúdo + logs) →
      cancelar PENDING → `FAILED` no banco + `master_audit_log` com
      `BROADCAST_FORCE_CANCELLED` e o operador; botão ausente em não-PENDING.
- [x] **T2.2** — Suíte E2E completa sem regressão + `mvn test` verde;
      atualizar roadmap/status.

## Verificação (`/spec-verify 89`)

- [x] Checar critérios ↔ design ↔ task contra o código real (controllers
      master, `MasterAuditAction`/`MasterPermission`, `MasterPermissionGuard`,
      `ConsoleSpaController`,
      padrão `BotsPage`) — sem achados.

## §Implementação — decisões e desvios (registro)

1. **Busca cross-merchant via Specification (Criteria) em vez de JPQL com
   `IS NULL OR`:** a query JPQL inicial (`:merchantId IS NULL OR ...`) quebrou
   no PostgreSQL real com `não foi possível determinar o tipo de dados do
   parâmetro` (inferência de tipo dos parâmetros UUID/Instant num `? IS NULL`).
   Refatorado para o padrão do `MasterAuditPersistenceAdapter` (Specification —
   predicates só entram quando o filtro é não-nulo). O filtro por `merchantId`
   usa **subquery sobre `store_bots`** (o broadcast não tem FK direta para
   lojista — o join físico `root.join("storeBotId")` falha porque `store_bot_id`
   é UUID simples, não associação JPA).
2. **`Broadcast.forceCancel(reason, now)` novo no domínio** (achado do verify):
   `PENDING → FAILED`; o `fail()` existente só aceita `PROCESSING`. Aditivo.
3. **`MasterPermission` 9ª permissão** `broadcast.force_cancel` — aditiva ao
   catálogo da Spec 69; o guard tier-based concede a qualquer master ativo
   (OPERATOR); `permissions[]` do `/master/me` inclui automaticamente
   (`MasterPermission.values()`). 3 testes existentes atualizados (catálogo
   8→9, blocos do selfProfile 7→8 / 8→9).
4. **E2E seeds diretos via SQL** (master, broadcasts em statuses distintos,
   logs) + mock Telegram (registro do bot exige getMe/setWebhook) — padrão
   `master-console-ui.spec.js`/`tracking.spec.js`.
5. **`/console/broadcasts` adicionado ao `spaPublicPaths()` e ao
   `ConsoleSpaController`** (achado do verify — full reload não pode cair em
   401 JSON, mesmo padrão do fix da Spec 80).
6. **Resolução de merchant/bot name na listagem usa lookups por item (N+1
   limitado à página):** o design D2.1 previa um JOIN único para trazer
   `merchant_name`/`bot_display_name`. A implementação resolve via
   `botRepository.findById` + `merchantRepository.findById` por broadcast (até
   2×20 queries para uma página de 20). **Decisão (audit 2026-08-14):** manter —
   mesmo padrão do espelho master de bots (`StoreBotQueryService.listAllForMaster`
   também faz N+1 por bot), a tela é de auditoria de baixa frequência e o
   batch-fetch exigiria mudar portas/adapters para ganho marginal (KISS/L14).
7. **`BroadcastDetailModal` ganhou `useDialogFocus`** (audit 2026-08-14 —
   achado de acessibilidade Spec 73): o modal de inspeção não tinha focus
   trap/Escape/restauração de foco (todos os outros modais do console usam).
   Aplicado o hook; tsc revalidado.
8. **Filtro de lojista na UI é por ID (não por nome)** (audit 2026-08-14): o
   requirements critério 7 e o design D5.2 diziam "busca por texto/nome", mas o
   contrato da API (critério 1, D2.1) só expõe `merchantId` (UUID). O
   placeholder foi corrigido para "Lojista (id)…" — busca por nome exigiria
   LIKE no `merchants.name` (desvio do contrato da API, fora de escopo).
