---
name: Automações avançadas — Meus Fluxos, Redirecionadores e Remarketing (backend)
status: verified
---

# Spec 117 — Task: Automações avançadas (backend)

## F0 — Migration + domínio

- [ ] **T0.1** — Migration **V66** (D2): `bot_flows`, `bot_flow_nodes`,
      `remarketing_campaigns`, `remarketing_logs`.
- [ ] **T0.2** — Entidades JPA + mappers + portas (`BotFlowRepository`,
      `RemarketingCampaignRepository`, `RemarketingLogRepository`).

## F1 — Services

- [ ] **T1.1** — `FlowService`: CRUD fluxos + nós (validação por tipo,
      SSRF-safe no SEND_LINK), reorder, `flowForBot(id)`.
- [ ] **T1.2** — `RedirectService`: listagem/criação (reuso `TrafficLinkService`
      Spec 109).
- [ ] **T1.3** — `RemarketingService`: CRUD campanhas + `run()` (detecta
      PENDING expirada na janela, publica `REMARKETING_SEND` no outbox +
      log idempotente).
- [ ] **T1.4** — Fluxos/remarketing escopados pela **frota do perfil ativo**
      (`store_bots.profile_id`, Specs 122/139/124) via bot-set.

## F2 — Controllers + scheduler

- [ ] **T2.1** — `PortalFlowController` (`/api/v1/portal/flows` + nós),
      `PortalRedirectController` (`/api/v1/portal/redirects`),
      `PortalRemarketingController` (`/api/v1/portal/remarketing`),
      `FoxFlowController` (`GET /api/v1/foxcash/flows/{id}`, API key).
- [ ] **T2.2** — `RemarketingScheduler` (ShedLock + `JobExecutionService`, cron
      `foxcash.remarketing.cron`, kill-switch) + `PlatformAction` +=
      `FLOW_*`/`REMARKETING_*` + métricas `nyx_remarketing_*`.

## F3 — Testes + E2E

- [ ] **T3.1** — Unitários/integração (D7) + `mvn test` verde.
- [ ] **T3.2** — E2E `portal-automacoes.spec.js` + roadmap/status.

## Verificação (`/spec-verify 117`)

- [ ] Checar âncoras (`StoreButton`/`StoreMessage` Specs 50/51, `traffic_links`
      Spec 109, `JobExecutionService`, outbox Spec 35, `WebhookUrlValidator`,
      `RateLimitKey` nyx) — sem achados.
