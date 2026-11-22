---
name: Tracking — perfis e disparo server-side de eventos (backend)
status: audited
---

# Spec 81 — Task: Tracking (perfis e disparo server-side)

## F0 — Domínio + migration (critérios 1–2) ✅

- [x] T0.1: Migration **`V56__tracking.sql`** (o design dizia V52 — números
      ocupados por V53/V54/V55). `ALTER bot_leads` (UTM) + `tracking_profiles`
      (tokens `CryptoConverter`) + `tracking_profile_bots` + `event_logs`
      (UNIQUE profile_id+event_id) + índices.
- [x] T0.2: JPA: `TrackingProfileJpaEntity`/`TrackingProfileJpaRepository`/
      `TrackingProfileBotJpaEntity`/`TrackingProfileBotJpaRepository`/
      `EventLogJpaEntity`/`EventLogJpaRepository` + mappers (D2.2).
- [x] T0.3: Portas `TrackingProfileRepository`/`TrackingProfileBotRepository`/
      `EventLogRepository` + adapters JPA.
- [x] T0.4: Domínio `TrackingProfile` (create/update/activate/deactivate/
      duplicate/remove, `tokenMask`) + `TrackingStatus`/`EventLog`/
      `EventLogStatus` + enum `TrackingServiceType` (D3).
- [x] T0.5: `PlatformAction` += `TRACKING_PROFILE_CREATED/UPDATED/ACTIVATED/
      DEACTIVATED/DUPLICATED/DELETED` (seção `// Tracking (Spec 81)`).
- [x] T0.6: Testes — `TrackingProfileTest` (validações par pixel+token, nome,
      máscara, duplicate, transições).

## F1 — CRUD portal (critérios 3, 11) ✅

- [x] T1.1: `TrackingProfileCommandService` — create/update/activate/
      deactivate/duplicate/remove + vínculo de bots (validado contra o
      lojista), auditoria na mesma transação (D7/D9).
- [x] T1.2: `TrackingProfileQueryService` — lista (badges + resumo do log),
      detalhe **sem token**, log paginado.
- [x] T1.3: `PortalTrackingController` — rotas do D7 (cross-lojista → 404
      `tracking_profile_not_found` no `GlobalExceptionHandler`).
- [x] T1.4: Testes — token nunca no response via `tokenMask` (coberto no
      `TrackingProfileTest`); validações/404 exercitados pelos services.

## F2 — Captura de UTM (critério 4) ✅

- [x] T2.1: `BotLeadService.recordLead(botId, userId, utm)` — **primeiro valor
      vence** (UTMs gravadas na CRIAÇÃO; replay não sobrescreve); `BotLead`
      ganha campo `utm` (aditivo) + entity/mapper.
- [x] T2.2: Telegram webhook (Spec 74 D5.3) extrai `utm_*` do `/start <payload>`
      (URL-decode + separação por `&`) e passa ao recordLead. Discord: o
      endpoint de interação não carrega link → UTMs null (documentado).
- [x] T2.3: Normalização/sanitização no `UtmParams` (255 chars, sem HTML) +
      testes (`BotLeadServiceTest` — replay não sobrescreve, novo lead dispara).

## F3 — Dispatch + adapters (critérios 5–9) ✅

- [x] T3.1: `TrackingDispatchService` (D5.1) — `onLead`/`onCheckout`/`onPurchase`
      chamados na mesma transação do funil (Spec 75 — hooks em `BotLeadService`
      e `BotFunnelService`); publica **um job de outbox por (perfil, evento)**
      para perfis ACTIVE do bot (`WebhookEventType.TRACKING_DISPATCH`).
- [x] T3.2: Porta `TrackingProviderClient` + `TrackingDispatchHandler`
      interceptado no `NotificationProcessor` ANTES do `MerchantNotifier` (D5.2)
      — sucesso → `event_logs` OK; falha → `event_logs` ERROR (transação
      própria `REQUIRES_NEW` — sobrevive ao rollback) + exceção → retry/backoff
      do outbox (max tentativas, `OUTBOX_DEAD`).
- [x] T3.3: `FacebookCapiClient` (D6) — CAPI `/{version}/{pixel}/events`,
      `user_data.external_id` SHA-256 do ID opaco, `custom_data` value/currency
      BRL.
- [x] T3.4: `TikTokEventsClient` (D6) — `/open_api/v1.3/pixel/track/`,
      `Access-Token`, user/properties/context(UTMs); teste com `test_event_code`.
- [x] T3.5: `UtmifyClient`/`OtimizeyClient` (D6) — webhook genérico
      `POST {baseUrl}/event` + `/ping` (token Bearer; shape do endpoint é
      decisão de implementação documentada).
- [x] T3.6: Testes — `TrackingDispatchServiceTest` (mapeamento dos 3 eventos,
      publica por perfil ACTIVE, kill-switch) e `TrackingDispatchHandlerTest`
      (todos OK → log OK; falha de um → ERROR + exceção; perfil ausente/inativo
      → no-op; falha de um não derruba os demais).

## F4 — Sincronizar + fechamento (critérios 10, 12) ✅

- [x] T4.1: `POST /{id}/sync` (D8) — teste por provedor configurado; resposta
      `[{ service, ok, message }]`; falha de um não impede os demais.
- [x] T4.2: Métricas `nyx_tracking_sent_total/ok_total/error_total{provider}`
      (D9) + `TrackingProperties` com binding contra o yml real
      (`TrackingPropertiesBindingTest` — padrão Spec 68/L16).
- [ ] T4.3: **E2E** (funil → disparo para cada provedor mockado → event_logs
      OK/ERROR) — pendente (ver §Pendência).
- [x] T4.4: `mvn test` **1606/0/0** sem regressão (funil Spec 75 e outbox Spec
      35 intactos — 1591 → 1606, +15).
- [ ] T4.5: `/spec-verify 81` (re-checagem) e `/spec-audit 81` (critérios 1–12).

## §Implementação — decisões e desvios (registro)

1. **Migration V56** (não V52 do design — V53/V54/V55 já usadas). Número livre
   na implementação.
2. **`event_logs.event_id`**: id estável **derivado** — `lead`:
   `nameUUIDFromBytes("lead:"+botId+":"+userId)`; `checkout_started`/`paid`:
   o `transaction_id` do funil. O `BotFunnelEvent` (Spec 75) não expõe o id do
   banco; os ids derivados são estáveis e **distintos por tipo** (checkout e
   paid da mesma transação não colidem no UNIQUE `(profile_id, event_id)`).
3. **Interceptação no outbox**: `TRACKING_DISPATCH` é tratado no
   `NotificationProcessor` **antes** do `MerchantNotifier` (evento interno —
   nunca chega aos webhooks do lojista). O `event_logs` ERROR persiste via
   `TrackingEventLogRecorder` (`REQUIRES_NEW`) para sobreviver ao rollback do
   retry; a exceção dispara retry/backoff/`OUTBOX_DEAD` do pipeline existente.
4. **UTM no Discord**: o endpoint de interação (`POST /nyx/bots/{id}/interaction`)
   não carrega o link de origem → UTMs null nesse caminho (a captura plena fica
   junto com o CRM/handoff — Spec 83/90). Telegram: extração do `/start`.
5. **UTMify/Otimizey**: contrato de webhook genérico documentado
   (`POST {baseUrl}/event` + `/ping`, token Bearer) — o shape exato da
   ferramenta é decisão de homologação.
6. **`TrackingProviderClient`**: `send(event, credentials)` +
   `testConnection(credentials)` — credenciais por chamada (o provedor não
   conhece o perfil).
7. **`BotLead`** ganhou campo `utm` (aditivo) e **`recordCheckout`** ganhou
   parâmetro `amount` (aditivo) — extensões mínimas do modelo da Spec 75.
8. **CAPI `user_data`**: o funil não tem email/phone (decisão Spec 52) → usa
   `external_id` = SHA-256 do `platformUserId` (ID opaco).
9. **Sem tabela nova de fila**: o outbox da Spec 35 é o "fila" (decisão D1.1).

## §Pendência

- **T4.3 (E2E)**: suíte Playwright `tracking.spec.js` com mocks dos 4 provedores
  (base URLs via `NYX_TRACKING_*_BASE_URL`) — fluxo funil → perfil ACTIVE →
  dispatch → `event_logs` OK/ERROR + `POST /sync`. Fica para a sessão do painel
  (Spec 82) — a cobertura unitária/integração do caminho crítico (funil →
  outbox → handler → event_logs) já está em
  `TrackingDispatchServiceTest`/`TrackingDispatchHandlerTest`.

## §Auditoria (`/spec-audit 81`)

**12 critérios do requirements verificados um a um; 3 achados de código
corrigidos; 0 abertos.**

1. **Migration** — `V56__tracking.sql` real (o design dizia V52; números
   ocupados por V53/V54/V55): `ALTER bot_leads` (utm_*) + `tracking_profiles`
   (tokens `CryptoConverter`) + `tracking_profile_bots` +
   `event_logs` (UNIQUE `(profile_id, event_id)`) + índices. ✅
2. **Tokens criptografados em repouso** — `TrackingProfileJpaEntity` com
   `@Convert(CryptoConverter)` nas 4 colunas `*_encrypted`; resposta HTTP via
   `tokenMask` (nunca em claro; verificado no `TrackingProfileTest`). ✅
3. **CRUD no portal** — `PortalTrackingController` (POST/GET/PUT/DELETE +
   activate/deactivate/duplicate/sync/events), 404 cross-lojista
   `tracking_profile_not_found`, vínculo de bots validado contra o lojista.
   **Achado A3 corrigido**: o PUT não permitia **desconfigurar** um provedor de
   pixel (pixel null + token mantido → 400 no par); agora pixel null limpa o
   provedor inteiro e token null com pixel presente mantém o existente. ✅
4. **Captura de UTM** — colunas em `bot_leads`, `recordLead(botId, user, utm)`
   com "primeiro valor vence" (UTMs na criação; replay não sobrescreve);
   Telegram extrai do `/start` (URL-decode); Discord sem link → null
   (documentado). ✅
5. **Mapeamento de eventos** — `onLead`→`LEAD`, `onCheckout`→`INITIATE_CHECKOUT`,
   `onPurchase`→`PURCHASE` com value/currency BRL; **um job de outbox por
   (perfil, evento)** na mesma transação do funil (`TrackingDispatchService`). ✅
6. **Facebook CAPI v19** — POST `/{version}/{pixel}/events`, Bearer,
   `event_time` Unix, `custom_data` value/currency; `user_data.external_id`
   SHA-256 do ID opaco (o funil não tem email/phone — decisão Spec 52,
   documentada). ✅
7. **TikTok** — POST `/open_api/v1.3/pixel/track/`, `Access-Token`,
   user/properties/context(UTMs); teste com `test_event_code`. ✅
8. **UTMify/Otimizey** — webhook `POST {baseUrl}/event` + `/ping` com token
   Bearer. **Achado A2 corrigido (SSRF-safe, padrão Spec 35)**: as base URLs
   agora são validadas no `TrackingProperties.validate()` (`@PostConstruct`,
   fail-fast — http(s), host presente, sem credenciais na URL) + 2 testes. ✅
9. **Outbox e retry** — `TRACKING_DISPATCH` interceptado no
   `NotificationProcessor` antes do `MerchantNotifier`; falha → `event_logs`
   ERROR via `REQUIRES_NEW` (sobrevive ao rollback) + exceção → retry/backoff/
   `OUTBOX_DEAD` do pipeline Spec 35; sucesso → OK; idempotente (upsert por
   `(profile_id, event_id)`). ✅
10. **Sincronizar** — `POST /{id}/sync` → `[{service, ok, message}]`, falha de
    um não impede os demais. ✅
11. **Auditoria e métricas** — `PlatformAction.TRACKING_PROFILE_*` na mesma
    transação (Spec 41); `nyx_tracking_sent_total/ok_total/error_total/
    latency_seconds{provider}` (Spec 53). **Achado A1 corrigido**: faltava o
    **timer de latência** por provedor (`nyx_tracking_latency_seconds`) no
    `TrackingDispatchHandler` (critério 11 pedia latência). ✅
12. **Sem regressão** — funil Spec 75 e outbox Spec 35 intactos; perfis
    INACTIVE e bot sem perfil não disparam (no-op no handler/dispatch); `mvn
    test` **1608/0/0** (1591 → 1608; +17: 15 da implementação + 2 do validate).

**Pendências registradas (fora dos critérios de código):** E2E Playwright
(T4.3) — agendado com o painel da Spec 82. Fim do ciclo SDD
(roadmap/requirements/design/task `audited`).
