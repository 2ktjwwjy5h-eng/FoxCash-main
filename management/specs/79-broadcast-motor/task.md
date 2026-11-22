---
name: Broadcast — motor de disparo em massa (backend)
status: audited
---

# Spec 79 — Task: Broadcast (motor de disparo em massa)

## F0 — Domínio + migration (critérios 1, 9–10)

- [x] T0.1: Migration `V54__broadcast.sql` (D2.1) — número livre era V54
      (V53 é a última conhecida). Tabelas `broadcasts` (com `error_message`,
      critério 4) + `broadcast_logs` (UNIQUE broadcast_id+recipient_id) +
      `broadcast_welcome_sent` (idempotência do WELCOME) + índices.
- [x] T0.2: JPA: `BroadcastJpaEntity`/`BroadcastJpaRepository`/
      `BroadcastLogJpaEntity`/`BroadcastLogJpaRepository` + mappers (D2.2).
- [x] T0.3: Portas `BroadcastRepository`/`BroadcastLogRepository`/`BroadcastWelcomeSentRepository` + adapters.
- [x] T0.4: Domínio `Broadcast` + enums `BroadcastMode`/`BroadcastStatus`/
      `BroadcastLogStatus`/`BroadcastMediaType` + value object `BroadcastButtons` (D3/D8).
- [x] T0.5: `PlatformAction` += `BROADCAST_CREATED`, `BROADCAST_SCHEDULED`,
      `BROADCAST_CANCELLED` (D10).
- [x] T0.6: Testes — validações (target obrigatório por modo, scheduled_at >
      now, interval >= 3600), máquina de estados, parse/validação de botões.

## F1 — Services + REST portal (critérios 2–3)

- [x] T1.1: `BroadcastCommandService` (D4) — create/cancel com auditoria na
      mesma transação; DELETE de PENDING/SCHEDULED; 409 se PROCESSING
      (uploadMedia fica no F3).
- [x] T1.2: `BroadcastQueryService` — listagem (filtro modo/status), detalhe +
      logs.
- [x] T1.3: `PortalBroadcastController` — `POST/GET/GET{id}/DELETE
      /api/v1/portal/broadcasts` (o `POST .../media` multipart fica no F3).
- [x] T1.4: `GET /api/v1/portal/bots/{id}/chats` (D5) — via integração da
      Spec 74 (Telegram `getUpdates`/chat; Discord `GET /users/@me/guilds`);
      cache curto TTL 60s.
- [x] T1.5: Testes — CRUD feliz, validação de botões/SSRF, cross-lojista 404,
      cancelar PROCESSING → 409, cache de chats.

## F2 — Dispatch + API do bot (critérios 4–6, 11)

- [x] T2.1: `BroadcastDispatchService.due(dueBefore)` — NOW sem agendamento +
      SCHEDULED vencidos → devolve pendências e marca PROCESSING (padrão Spec 51).
- [x] T2.2: `markSent(id)` — conclui (COMPLETED se ≥1 SUCCESS; FAILED se
      nenhum; logs preservados); idempotente (replay → no-op).
- [x] T2.3: `reportLog(broadcastId, recipientId, status, error)` —
      idempotente (UNIQUE), nunca trava a fila.
- [x] T2.4: `welcomeTrigger(botId, chatId, userId)` (D6) — devolve a
      mensagem WELCOME ativa do chat (ou no-op); idempotente por
      (chatId, userId) via `broadcast_welcome_sent`.
- [x] T2.5: `FoxBroadcastController` (API key) — `due`/`sent`/`logs`/
      `welcome-trigger`.
- [x] T2.6: Testes — due filtra corretamente; replay de log não duplica;
      welcome-trigger 1× por usuário; falha de um destinatário não derruba
      os demais.

## F3 — Mídia CDN (critério 8)

- [x] T3.1: `BroadcastMediaService.upload` (D7) — valida tipo/tamanho (10MB/
      50MB); Telegram: `file_id` via canal CDN (adapter Spec 74 estendido com
      `sendMedia`); Discord: URL do attachment; resposta `{media_url,
      media_file_id, media_type}`.
- [x] T3.2: Testes — limites (400), tipo inválido, reuso de file_id no envio,
      404 cross-lojista.

## F4 — Modo REPOST + fechamento (critérios 7, 12)

- [x] T4.1: Job `foxcash-broadcast-repost` (ShedLock + `JobExecutionService`,
      Spec 53; kill-switch `foxcash.broadcast.repost-enabled`) — re-agenda
      `scheduled_at = now + interval_seconds` → `PENDING`; métricas
      `nyx.job.*` via JobExecutionService.
- [x] T4.2: `BroadcastProperties` + binding contra o yml real (padrão
      Spec 68/L16).
- [x] T4.3: E2E — NOW/SCHEDULED/WELCOME/REPOST com Telegram/Discord mockados
      (due → envio → logs → sent; mock de contrato do client Telegram com
      MockWebServer).
- [x] T4.4: `mvn test` verde sem regressão (**1591/0/0** — verificado em
      2026-08-13, 82 testes novos de broadcast inclusos).
- [x] T4.5: `/spec-audit 79` (critérios 1–12) — **12/12 OK em 2026-08-13**
      (detalhe na seção Auditoria abaixo).

## Auditoria (`/spec-audit` 2026-08-13)

- [x] T5.1: `/spec-audit 79` — **12/12 critérios OK**; 2 achados de
      documentação (nenhum bloqueante), corrigidos na própria rodada:
      (1) **DOC (critério 12)** — requirements citava jobs `foxcash-broadcast-due`
      e métricas `nyx_broadcast_*`; o padrão real é o design D10 (só
      `foxcash-broadcast-repost` com `JobExecutionService` → `nyx.job.*`;
      NOW/SCHEDULED viram PROCESSING via **pull do bot** no `due`, sem job —
      padrão Spec 51). Requirements alinhada ao design/código.
      (2) **OBS (critério 10)** — `content` validado apenas como "conteúdo
      ou mídia"; sem whitelist de tags HTML nem limite de tamanho (Spec 51
      tem `MAX_TEXT=1000`). Mitigado por reuso: bot envia com `parse_mode:
      Markdown` (não HTML) + WAF da Spec 63 bloqueia XSS no body. Risco
      baixo; opcional adicionar limite de tamanho de content.
      `mvn test` verde na auditoria (**1591/0/0**).
