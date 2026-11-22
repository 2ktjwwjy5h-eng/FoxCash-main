---
name: Auditoria de Broadcasts no Master Console
status: audited
references:
  - "management/vision.md#V66"
  - "management/specs/79-broadcast-motor/requirements.md"
  - "management/specs/71-master-console/requirements.md"
  - "management/specs/69-rbac-master-stepup/requirements.md"
  - "management/specs/41-master-profiles-activity/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
---

# Spec 89 — Auditoria de Broadcasts no Master Console

## O que entrega

Uma interface no **Master Console** (Specs 71/72) para operadores da plataforma
monitorarem, auditarem e intervirem nas campanhas de disparo em massa criadas
pelos lojistas (motor da Spec 79). O objetivo é proteger a reputação dos bots
(evitar banimentos no Telegram/Discord) e coibir spam — capacidade do sistema
do irmão do usuário (Spec 82 do PRD externo) que não existia no FoxCash.

1. **Visão global (read-only):** lista paginada de **todos** os broadcasts da
   plataforma, com filtros cruzados (Lojista, Bot, Status, Modo, Data) e dados
   do `merchant` + `store_bot` em cada linha.
2. **Inspeção de conteúdo:** o operador clica em um broadcast e vê exatamente
   o que está sendo/foi enviado — o conteúdo formatado, a mídia, os botões e as
   **métricas de entrega por destinatário** (`SUCCESS`/`BLOCKED`/`FAILED` dos
   `broadcast_logs` da Spec 79).
3. **Intervenção de emergência (Force Cancel):** ação de "Cancelar Campanha"
   para broadcasts abusivos que estejam `PENDING` — o broadcast vai para
   `FAILED` com `error_message = 'Cancelled by Master Operator'` (não é delete
   físico: o registro permanece para auditoria).
4. **RBAC + auditoria:** leitura para todo master ativo; o force-cancel é ação
   de **OPERATOR** (Spec 69, bloco operacional — sem step-up, padrão
   suspend/release da Spec 71) e gera trilha `BROADCAST_FORCE_CANCELLED` com o
   **ID do operador** (Spec 37).

## Contexto (por que agora)

Comparação (2026-08-14) com o sistema do irmão do usuário (Spec 82 do PRD
externo — auditoria de broadcasts no master). O FoxCash já tem o motor de
broadcast (`audited`, Spec 79) e o editor (`audited`, Spec 80), mas o Master
Console não tem nenhuma tela `/broadcasts` e não existe força de cancelamento
com trilha de operador: o `DELETE` do portal (Spec 79) **apaga fisicamente**
broadcasts `PENDING` do próprio lojista, sem trilha master. Decisão do usuário
(2026-08-14): implementar a auditoria + force-cancel no console — sem migration
nova (reusa `broadcasts`/`broadcast_logs` da Spec 79).

## Critérios de aceite

1. **API Master — listagem:** `GET /api/v1/master/broadcasts` (ROLE_MASTER),
   paginada (default/cap como os demais lists do master — cap 100), filtros
   `merchantId`, `botId`, `status`, `mode`, `from`/`to` (scheduled_at/created).
   Cada item: `id`, `merchantId`, `merchantName`, `botId`, `botDisplayName`,
   `botPlatform`, `mode`, `status`, `targetChatId`, `scheduledAt`,
   `createdAt` — **sem `content` na lista** (só no detalhe; conteúdo pode ser
   longo).
2. **API Master — detalhe:** `GET /api/v1/master/broadcasts/{id}` →
   broadcast completo (content, media, buttons, error_message) + `logs` (por
   destinatário, com `SUCCESS`/`BLOCKED`/`FAILED` + `error_message`). Não
   encontrado → 404 `broadcast_not_found` (sem revelar existência cross-tabela).
3. **Force-cancel:** `POST /api/v1/master/broadcasts/{id}/force-cancel` →
   somente status `PENDING` (qualquer modo — NOW/SCHEDULED/REPOST/WELCOME);
   sucesso → 200 com o broadcast atualizado (`status: FAILED`,
   `error_message: 'Cancelled by Master Operator'`); `PROCESSING`/
   `COMPLETED`/`FAILED` → **409 `invalid_broadcast_state`**; inexistente → 404.
   **Achado do verify:** o `Broadcast.fail()` existente (Spec 79) só aceita
   `PROCESSING` → o force-cancel usa um **novo método no domínio**
   `forceCancel(reason, now)` (`PENDING → FAILED`), aditivo.
4. **Auditoria (Spec 37):** o force-cancel é ação de **MASTER** — registra
   `MasterAuditAction.BROADCAST_FORCE_CANCELLED` via `MasterAuditRecorder`
   (trilha `master_audit_log` da Spec 37, **mesma transação**), com actor = ID
   do operador, `referenceType = "BROADCAST"`, `referenceId = broadcastId`,
   before/after = `{status: PENDING}` → `{status: FAILED}` e reason =
   'Cancelled by Master Operator'. (Não é `PlatformAction`/Spec 41 — essa
   trilha é de ações MERCHANT/SYSTEM; os `BROADCAST_*` do portal ficam como
   estão.)
5. **RBAC:** leitura disponível a qualquer master ativo (espelho read-only,
   padrão Spec 71); o force-cancel exige nível **OPERATOR** (Spec 69 —
   `MasterPermissionGuard`, sem step-up — ação reversível, padrão
   suspend/release). Sem permissão → 403 `forbidden`. **Achado do verify:** o
   catálogo `MasterPermission` (8 permissões) não tem permissão de broadcast —
   adicionar `BROADCAST_FORCE_CANCEL("broadcast.force_cancel")` (aditivo, 9ª);
   o guard tier-based concede a qualquer master ativo e o `permissions[]` do
   `/master/me` passa a incluí-la automaticamente (`MasterPermission.values()`).
6. **Rate limit e segurança:** rotas `/api/v1/master/**` já cobertas pela
   cardinalidade do `RateLimitKey` (Spec 69); nenhum token/segredo do bot em
   resposta; `content` exibido como armazenado (sem sanitização — é o conteúdo
   que o operador precisa auditar).
7. **Master Console UI:** rota `/console/broadcasts` (menu + SPA fallback +
   whitelist CSP/security, padrão Specs 71/76). Página com tabela padrão Spec
   73 (UX atual — CSS puro; a migração Shadcn das Specs 85/86 foi `discarded`
   em 2026-08-14): colunas Lojista, Bot, Modo, Status, Agendado,
   Criado; filtros (lojista por texto, bot, status, modo); paginação
   server-side.
8. **Drawer de detalhe:** clicar na linha abre painel lateral com o conteúdo
   completo (texto/mídia/botões) e as estatísticas de envio (contagens de
   `SUCCESS`/`BLOCKED`/`FAILED` + logs por destinatário).
9. **Botão "Cancelar Campanha":** exibido **apenas** quando `status = PENDING`;
   abre `ConfirmDialog` (tom `danger` — Spec 73) com texto claro da
   irreversibilidade; sucesso → linha atualiza para `FAILED` + banner; 409 →
   banner com mensagem.
10. **Testes:** unitários + integração (query com filtros, force-cancel:
    sucesso/409/404, RBAC 403) e E2E Playwright (login admin → lista →
    drawer → cancelar → status `FAILED` no banco + trilha `BROADCAST_FORCE_CANCELLED`
    com o operador); `mvn test` verde + E2E completo sem regressão.

## Dependências

- **Spec 79** (motor) — `broadcasts`/`broadcast_logs`, statuses, modo.
- **Spec 71/72** (console) — padrão de página, rota, SPA controller, RBAC-aware.
- **Spec 69** (RBAC 2 níveis) — nível OPERATOR para o force-cancel.
- **Spec 37** (auditoria master) — `MasterAuditRecorder` para o force-cancel;
  os `BROADCAST_*` do portal (Spec 79) continuam na trilha da Spec 41
  (`PlatformAction`, actor MERCHANT).
- **Spec 73** (UX — CSS puro; a Spec 86/Shadcn foi `discarded` em 2026-08-14)
  — tabela, drawer, `ConfirmDialog` `danger`.

## Fora de escopo

- **Editar/criar broadcast pelo master** — o console é audit/leitura + cancelamento.
- **Cancelamento do Portal** — o `DELETE` da Spec 79 permanece como está
  (lojista cancela o próprio; o master usa o force-cancel).
- **Step-up TOTP** — force-cancel é ação de OPERATOR reversível (padrão
  suspend/release, Spec 71), sem step-up.
- **Notificação ao lojista** ao ter a campanha cancelada (evolução).
