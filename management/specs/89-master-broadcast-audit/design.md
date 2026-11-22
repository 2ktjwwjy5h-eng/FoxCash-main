---
name: Auditoria de Broadcasts no Master Console
status: audited
---

# Spec 89 — Design: Auditoria de Broadcasts no Master Console

## D1 — Conceito

O master (Specs 71/72) ganha a **camada de auditoria do broadcast** (Spec 79):
leitura global paginada (todos os lojistas), inspeção de conteúdo + logs e o
**force-cancel** de campanhas `PENDING`. Reusa 100% o domínio da Spec 79
(`Broadcast`/`BroadcastLog`/statuses) — **sem migration nova**. A única lógica
nova é: (a) a **query cross-merchant** com dados de `merchant` + `store_bot` e
(b) o **force-cancel** com gate de estado + RBAC + auditoria.

### D1.1 Decisões do usuário (2026-08-14) → regras

| Decisão | Regra |
|---|---|
| Sem migration nova | reusa `broadcasts`/`broadcast_logs` (Spec 79) |
| Force-cancel = marca FAILED | não é DELETE físico — registro permanece p/ auditoria |
| Apenas PENDING | PROCESSING/COMPLETED/FAILED → 409 `invalid_broadcast_state` |
| Nível OPERATOR sem step-up | padrão suspend/release da Spec 71 (ação reversível) |
| Auditoria com ID do operador | `BROADCAST_FORCE_CANCELLED` na mesma transação (Spec 37) |

## D2 — API Master (padrão espelho da Spec 71)

### D2.1 `GET /api/v1/master/broadcasts`

Query service cross-merchant (`MasterBroadcastQueryService`) com paginação e
filtros opcionais: `merchantId`, `botId`, `status`, `mode`, `from`/`to`
(sobre `created_at`; `scheduled_at` como filtro alternativo documentado no
task). Paginação padrão dos lists do master (page/size, cap 100).

A query precisa juntar `broadcasts → store_bots → merchants`:

```sql
SELECT b.id, b.mode, b.status, b.scheduled_at, b.created_at,
       sb.id AS bot_id, sb.display_name AS bot_display_name, sb.platform AS bot_platform,
       sb.merchant_id, m.name AS merchant_name
FROM broadcasts b
JOIN store_bots sb ON sb.id = b.store_bot_id
JOIN merchants  m  ON m.id  = sb.merchant_id
WHERE (:merchantId IS NULL OR sb.merchant_id = :merchantId)
  AND (:botId      IS NULL OR b.store_bot_id = :botId)
  AND (:status     IS NULL OR b.status = :status)
  AND (:mode       IS NULL OR b.mode = :mode)
  AND (:from       IS NULL OR b.created_at >= :from)
  AND (:to         IS NULL OR b.created_at <= :to)
ORDER BY b.created_at DESC
```

`BroadcastRepository` (porta hexagonal) ganha `searchMaster(filters, pageable)`
e **`findById(UUID id)` sem escopo de lojista** (o master não tem merchant —
a porta hoje só expõe `findByIdAndMerchantId`, escopada) com o adapter JPA
implementando a query nativa/JPQL acima. A lista **não** devolve `content`
(só no detalhe) — evita payloads pesados.

Resposta (DTO `MasterBroadcastSummary`): `id, merchantId, merchantName, botId,
botDisplayName, botPlatform, mode, status, targetChatId, scheduledAt,
createdAt`.

### D2.2 `GET /api/v1/master/broadcasts/{id}`

`MasterBroadcastQueryService.detail(id)` — mesmo `BroadcastQueryService` da
Spec 79 (detalhe + logs) mais os dados de merchant/bot, carregado pelo
`findById(id)` sem escopo (achado do verify — a porta existente é
merchant-scoped). Logs agrupados com contagens: `{ success, blocked, failed }`
+ lista paginada de `broadcast_logs`. `404 broadcast_not_found` (mesmo
handler da Spec 79).

DTO `MasterBroadcastDetail`: tudo do summary + `content`, `mediaUrl`,
`mediaType`, `mediaFileId` (sem o file_id se sensível? **decisão:** devolve o
`media_url`/`media_type`; o `media_file_id` é interno do Telegram — fora da
resposta, padrão "sem token/segredo do bot em resposta"), `buttons`,
`errorMessage`, `logs` (contagens + lista).

### D2.3 `POST /api/v1/master/broadcasts/{id}/force-cancel`

`MasterBroadcastCommandService.forceCancel(broadcastId, operatorId, ip)`:

1. Carrega o broadcast pelo `findById(id)` sem escopo (404 se inexistente).
2. Gate de estado: `status != PENDING` → `InvalidBroadcastStateException` →
   409 `invalid_broadcast_state`.
3. `broadcast.forceCancel("Cancelled by Master Operator", now)` — **novo
   método no domínio** (`PENDING → FAILED`; o `fail()` existente só aceita
   `PROCESSING` — achado do verify) + `save`.
4. Auditoria na **mesma transação**: `MasterAuditRecorder.record(operatorId,
   MasterAuditAction.BROADCAST_FORCE_CANCELLED, merchantId, "BROADCAST",
   broadcastId, {status: PENDING}, {status: FAILED}, 'Cancelled by Master
   Operator', ip)` (Spec 37 — ação de MASTER).

Retorno 200: `MasterBroadcastDetail` com `status: FAILED` e
`errorMessage = 'Cancelled by Master Operator'`.

O `BroadcastRepostScheduler` (Spec 79) só re-agenda `PENDING` → um REPOST em
`FAILED` morre definitivamente (não volta).

## D3 — RBAC e segurança

- **Leitura** (GET list/detail): qualquer master ativo — espelho read-only,
  sem guard extra (padrão dos espelhos da Spec 71).
- **Force-cancel**: `MasterPermissionGuard.requirePermission(actorId,
  MasterPermission.BROADCAST_FORCE_CANCEL)` — permissão **nova** no catálogo
  (Spec 69 hoje tem 8; esta adiciona a 9ª `broadcast.force_cancel`). O guard é
  tier-based: qualquer permissão exceto `MASTER_PROFILES_MANAGE` vale para
  todo master ATIVO → o nível OPERATOR é coberto automaticamente; o
  `permissions[]` do `/master/me` passa a incluir a nova sem mudança no
  resolver (`MasterPermission.values()`). **Touch-points de doc** (não de
  código): javadoc do `MasterPermission` ("7 permissões operacionais" → 8),
  comentário do `MasterProfileService.selfProfile` e a Spec 69 ("catálogo 8
  permissões" → 9). Sem permissão → 403 `forbidden`. **Sem step-up** (ação de
  intervenção reversível, padrão suspend/release — não está nas 4 sensíveis
  da Spec 69).
- Rate limit: `/api/v1/master/**` já no `RateLimitKey.VARIABLE_PREFIXES`
  (Spec 69) — nada novo.
- Cross-scope: o master não tem escopo de lojista — lê a plataforma inteira
  (é o ponto).

## D4 — Auditoria

`MasterAuditAction` += `BROADCAST_FORCE_CANCELLED` (Spec 37 — trilha do
MASTER). Gravado com `MasterAuditRecorder` na mesma transação do
`forceCancel` (padrão `MerchantStatusControlService`): actor = master id,
`referenceType = "BROADCAST"`, `referenceId = broadcastId`, before/after =
`{status}` e reason = 'Cancelled by Master Operator'. **Não** é `PlatformAction`
(Spec 41) — essa trilha é de ações MERCHANT/SYSTEM (os `BROADCAST_CREATED/
SCHEDULED/CANCELLED` do portal ficam inalterados no enum).

## D5 — Frontend (Master Console)

### D5.1 Rota e navegação

- `BroadcastsPage.tsx` em `console/src/pages/` (padrão `BotsPage`/Spec 76).
- Rota `/console/broadcasts` no `App.tsx` + item de menu (seção Bots/Operação).
- **Achado do verify:** `/broadcasts` e `/broadcasts/*` do PORTAL já estão no
  `spaPublicPaths()` (Spec 80). O que falta é `/console/broadcasts` na seção
  do console do `spaPublicPaths()` (`SecurityConfig`) **e** nos paths do
  `ConsoleSpaController` (full reload não pode cair em 401 JSON — padrão do
  bug corrigido na Spec 80).

### D5.2 Página

- **Tabela** (padrão Spec 73/86): Lojista (nome + id), Bot (@displayName +
  plataforma), Modo (badge), Status (badge colorido), Agendado, Criado.
- **Filtros**: lojista (busca por texto/nome), bot (select), status, modo —
  server-side (query params) + paginação.
- **Drawer de detalhe**: ao clicar na linha, painel lateral com conteúdo
  completo (texto formatado, mídia thumbnail, grade de botões) e métricas de
  envio (contagens SUCCESS/BLOCKED/FAILED + lista de logs por destinatário).
- **Botão "Cancelar Campanha"**: visível **apenas** quando `status =
  PENDING`; `ConfirmDialog` tom `danger` (Spec 73) com texto de
  irreversibilidade; ao confirmar → `POST force-cancel` → linha vira
  `FAILED` + `SuccessBanner`; 409/403 → `ErrorBanner`.

## D6 — Configuração

Nenhuma config nova (reusa padrões existentes). Kill-switch do force-cancel
**não** é necessário (ação manual rara; o RBAC já restringe).

## D7 — Testes

**Unitários/integração (Java):**
- `MasterBroadcastQueryServiceTest` — filtros (merchantId/botId/status/mode/
  período), paginação, lista sem `content`.
- `MasterBroadcastCommandServiceTest` — force-cancel: sucesso (status FAILED +
  error_message + auditoria na mesma transação), 409 em PROCESSING/
  COMPLETED/FAILED, 404.
- `MasterBroadcastControllerTest` — RBAC: leitura master ativo OK; force-cancel
  sem permissão → 403; roteamento/status codes.
- `MasterAuditAction` += `BROADCAST_FORCE_CANCELLED` e `MasterPermission` +=
  `BROADCAST_FORCE_CANCEL` (catálogo).

**E2E Playwright (`master-console-broadcast.spec.js`):** login admin →
`/console/broadcasts` → lista com seeds (2 lojistas × broadcasts em statuses
diferentes) → drawer mostra conteúdo + logs → "Cancelar Campanha" no PENDING →
status `FAILED` no banco + `master_audit_log` com
`BROADCAST_FORCE_CANCELLED` e o operador; botão ausente em PROCESSING/
COMPLETED; 403 para master sem permissão (se aplicável no seed).

**CI:** job e2e herda envs existentes (nenhuma nova).
