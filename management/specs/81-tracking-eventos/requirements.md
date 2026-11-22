---
name: Tracking — perfis e disparo server-side de eventos (backend)
status: audited
references:
  - "management/vision.md#V58"
  - "management/specs/75-leads-metricas-bots/requirements.md"
  - "management/specs/74-registro-bots/requirements.md"
  - "management/specs/35-outbound-webhook/requirements.md"
  - "management/specs/41-master-profiles-activity/requirements.md"
  - "management/specs/53-job-robustez/requirements.md"
---

# Spec 81 — Tracking: perfis e disparo server-side de eventos (backend)

## O que entrega

O **motor de tracking server-to-server**: o lojista cria **perfis de
rastreamento** (configuração dos provedores), vincula aos bots (Spec 74) e o
backend dispara os eventos do **funil da Spec 75** (`lead`,
`checkout_started`, `paid`) para **Facebook CAPI**, **TikTok Events API**,
**UTMify** e **Otimizey** — com **captura de UTM** no início da conversa. O
sistema externo analisado (PRD do irmão) guarda tokens em texto puro e
"enfileira" disparos; **aqui**: tokens criptografados em repouso (padrão Spec
74) e dispatch via **outbox da Spec 35** (retry/backoff — sem fila nova).

1. **Tabelas `tracking_profiles`, `tracking_profile_bots` e `event_logs`** —
   o perfil (configurações dos provedores, tokens **criptografados**), o
   vínculo perfil↔bot e o **log de eventos** (ok/erros, idempotente).
2. **Captura de UTM** — o bot parseia `?utm_source/medium/campaign/content/
   term` do link de entrada e persiste vinculado ao lead (Spec 75): colunas
   novas em `bot_leads`. (Não existe UTM em lugar nenhum hoje.)
3. **Disparo server-to-server** dos eventos do funil — mapeamento:
   `lead` → `Lead`/`PageView`, `checkout_started` → `InitiateCheckout`,
   `paid` → `Purchase` (com valor e moeda) — para os provedores vinculados ao
   perfil do bot, via outbox (Spec 35).
4. **Clientes por provedor**: Facebook CAPI v19 (`POST
   /{pixel_id}/events`, `user_data` com email/phone **SHA-256** + `fbp`/
   `fbc`), TikTok Events API (`POST /open_api/v1.3/pixel/track/`), UTMify/
   Otimizey (webhook com dados do pedido + UTMs).
5. **`event_logs`** — por `(profile_id, event_id)` (idempotente), com
   `status OK/ERROR` e `response_payload` — alimenta a métrica "X ok, Y
   erros" do painel (Spec 82).
6. **Ação de sincronizar/teste** — `POST /api/v1/portal/tracking/{id}/sync`
   valida a conexão com cada provedor configurado (resposta amigável por
   serviço).

O contrato do funil (Spec 75) **não muda**: o tracking é uma **camada de
dispatch por cima** dos eventos que a Spec 75 já grava.

## Contexto (por que agora)

A Spec 75 criou o funil por bot — a fonte dos eventos. O módulo do irmão
mostra o valor de **levar esses eventos para os provedores de mídia** (Meta/
TikTok/atribuição) com dados precisos (server-side, sem adblocker). Decisão
do usuário (2026-08-12): implementar a mesma ideia **melhor** — tokens
criptografados, dispatch via outbox (Spec 35), `user_data` hasheado (CAPI),
log idempotente.

## Critérios de aceite

1. **Migration `V52__tracking.sql`** (verificar próximo número livre):
   `tracking_profiles` (`id UUID PK`, `merchant_id UUID NOT NULL REFERENCES
   merchants ON DELETE CASCADE`, `name VARCHAR(255) NOT NULL`, `status CHECK
   IN ('ACTIVE','INACTIVE')`, `facebook_pixel_id VARCHAR`, 
   `facebook_access_token_encrypted TEXT`, `tiktok_pixel_id VARCHAR`,
   `tiktok_access_token_encrypted TEXT`, `utmify_token_encrypted TEXT`,
   `otimizey_token_encrypted TEXT`, `created_at`/`updated_at`),
   `tracking_profile_bots` (`profile_id`, `bot_id`, `PRIMARY KEY
   (profile_id, bot_id)`, FKs `ON DELETE CASCADE`) e `event_logs` (`id`,
   `profile_id FK`, `event_type VARCHAR`, `event_id UUID` (do funil da Spec
   75), `status CHECK IN ('OK','ERROR')`, `response_payload TEXT`,
   `created_at`, `UNIQUE (profile_id, event_id)`).
2. **Tokens criptografados em repouso**: `*_token_encrypted` via
   `CryptoConverter` (AES-256/GCM — padrão Spec 74); nenhum endpoint devolve
   token em claro (só máscara/últimos 4); `tracking_profiles` nunca expõe
   token no response.
3. **CRUD no portal** (ROLE_MERCHANT_PORTAL, escopo por lojista): `POST/GET/
   PUT/DELETE /api/v1/portal/tracking` + `POST /{id}/activate` e
   `/deactivate`; `POST /{id}/duplicate` (copia o perfil com novos ids);
   `POST /{id}/sync` (teste de conexão por provedor); vínculo de bots via
   `tracking_profile_bots` no mesmo CRUD; cross-lojista → 404.
4. **Captura de UTM**: migration adiciona `utm_source`, `utm_medium`,
   `utm_campaign`, `utm_content`, `utm_term` (VARCHAR, anuláveis) em
   `bot_leads` (Spec 75); a integração (Telegram webhook da Spec 74 / Discord
   manager) persiste as UTMs no registro do lead quando presentes no link de
   entrada; idempotente (primeiro valor vence).
5. **Mapeamento de eventos**: o dispatcher consome os eventos do funil
   (Spec 75) — `lead` → `Lead`, `checkout_started` → `InitiateCheckout`,
   `paid` → `Purchase` com `custom_data { value, currency: "BRL" }` (valor da
   transação da Spec 75/23) — e publica **um job de outbox por (perfil,
   evento)** para cada perfil vinculado ao bot do evento (Spec 35).
6. **Facebook CAPI v19**: adapter real com `POST
   https://graph.facebook.com/v19.0/{pixel_id}/events`; `user_data`: `em`/
   `ph` com **SHA-256** quando email/telefone presentes no payload do funil,
   `fbp`/`fbc` repassados se capturados; `event_time` Unix; assinatura/
   validação de resposta; mock de contrato (padrão `PayoutProviderContractTest`).
7. **TikTok Events API**: adapter real com `POST
   https://business-api.tiktok.com/open_api/v1.3/pixel/track/` (Bearer token);
   payload com `event_name`, `event_time`, `user`, `properties` (value/
   currency), `context` (UTMs); mock de contrato.
8. **UTMify/Otimizey**: adapter webhook (POST assinado com o token no header
   ou body conforme a ferramenta) com dados do pedido + UTMs capturadas;
   validação de URL/token SSRF-safe (padrão Spec 35).
9. **Outbox e retry**: cada disparo é um evento de outbox (Spec 35) com
   retry/backoff (max tentativas, `OUTBOX_DEAD`); falha → `event_logs`
   `ERROR` com `response_payload`; sucesso → `OK`; **idempotente** por
   `(profile_id, event_id)` — replay do webhook/funil não duplica.
10. **Sincronizar**: `POST /{id}/sync` chama um teste por provedor
    configurado (CAPI: `GET /{pixel_id}` com token? ou POST de teste;
    TikTok: validação do token; UTMify/Otimizey: ping) e retorna por serviço
    `{ service, ok, message }`.
11. **Auditoria e métricas**: ações de escrita (`TRACKING_PROFILE_CREATED/
    UPDATED/ACTIVATED/DEACTIVATED/DUPLICATED/DELETED`) — Spec 41; métricas
    `nyx_tracking_*` (enviados, ok, erros, latência) — Spec 53; `mvn test`
    verde + E2E (funil → disparo para cada provedor mockado).
12. **Sem regressão**: o funil da Spec 75 e o dispatch da Spec 35 não mudam
    de contrato; perfis `INACTIVE` não disparam; bot sem perfil não dispara.

## Dependências

- **Spec 75** (funil) — fonte dos eventos e do valor/moeda.
- **Spec 74** (registro de bots) — vínculo perfil↔bot e captura de UTM na
  integração.
- **Spec 35** (outbox) — dispatch com retry/backoff e validação SSRF.
- **Specs 41/53** (auditoria/jobs/métricas).
- **Spec 46/73** (portal/UX) — consumidores (Spec 82).

## Fora de escopo

- **Painel/UI** → Spec 82.
- **Novos provedores** (Google Ads, Pinterest) — extensão futura (porta
  `TrackingProviderClient` já abstrai).
- **Attribution server próprio / modelos de atribuição** — os provedores
  externos fazem a atribuição.
- **Pixel no cliente** — só server-side (decisão do módulo).
