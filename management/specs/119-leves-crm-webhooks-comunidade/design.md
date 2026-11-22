---
name: Leves — CRM enriquecido, Webhooks melhorado e Comunidade
status: implemented
---

# Spec 119 — Design: Leves (backend + UI)

## D1 — Conceito

Três pequenas melhorias no portal, cada uma com backend aditivo mínimo + UI
sobre o existente (Specs 83/84/35). KISS.

```text
PortalCrmController  — GET /crm/leads (filtro telefone + campos aditivos)
PortalWebhookController — GET /webhooks (status/lastError/attempts) + /{id}/sample
PortalCommunityController — GET /portal/community
MasterCommunityController — GET/POST/PATCH/DELETE /master/community/posts
CommunityPage (UI) / CrmPage (colunas) / WebhooksPage (drawer + aviso)
```

## D2 — Migration V67

```sql
CREATE TABLE community_posts (id UUID PK, title VARCHAR(160) NOT NULL,
  content TEXT NOT NULL, published_by UUID NOT NULL REFERENCES masters(id),
  pinned BOOLEAN NOT NULL DEFAULT FALSE, published_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL);
CREATE INDEX ix_community_posts_published ON community_posts (published_at DESC);
```

**V67** (V66 = Spec 117).

## D3 — CRM enriquecido

- `GET /api/v1/portal/crm/leads` — `q` passa a buscar também em `phone`
  (Specification com `LIKE` em name/username/email/phone); DTO aditivo:
  `lastPlanName` (última compra via `pix_transactions.plan_id` do lead —
  join), `lastInteractionAt` (`bot_leads.updated_at`), `flowName` (funil —
  `bot_funnel_events` de origem ou nó do fluxo; `null` sem).
- UI (`CrmPage` Spec 84): colunas Plano/Fluxo/Última interação; busca por
  telefone.

## D4 — Webhooks melhorado

- `GET /api/v1/portal/webhooks` — DTO aditivo `{status, lastError, attempts}`
  (da `merchant_webhooks`/outbox Spec 35).
- `GET /api/v1/portal/webhooks/{id}/sample?type=` — exemplo JSON do payload
  do evento (mock do contrato Spec 35, **sem PII** — valores placeholder).
- UI (`WebhooksPage`): botão "Ver payload" → drawer com o JSON; aviso de
  segurança (HMAC/HTTPS/desativação após 5 falhas — texto informativo).

## D5 — Comunidade

- `GET /api/v1/portal/community` → `PageResponse<CommunityPost>` (paginada,
  cap 50).
- `MasterCommunityController`: `GET/POST/PATCH/DELETE /api/v1/master/community/
  posts` — ROLE_MASTER, RBAC (Spec 69 — OPERATOR), auditoria
  `MasterAuditAction.COMMUNITY_POST_CREATED/...` (Spec 37).
- Seed: um post inicial "Bem-vindo" no boot (opcional, idempotente).

## D6 — Testes

**Unitários/integração:** `PortalCrmControllerTest` (q com telefone, campos
aditivos), `PortalWebhookControllerTest` (status/lastError/sample sem PII),
`MasterCommunityControllerTest` (RBAC + auditoria). **E2E**
(`portal-leves.spec.js`): CRM colunas/telefone; webhook sample + aviso;
comunidade com post seed. `mvn test` verde + suíte completa sem regressão.

## D7 — Wireframes aprofundados (registro de design 2026-08-16)

### D7.1 — CRM enriquecido (`/crm` — reescrita parcial da Spec 84)

```
┌────────────────────────────────────────────────────────────────────────┐
│  Clientes                                                              │
│  [Buscar nome / @ / e-mail /  telefone...]                        │
│  ┌────────────┬────────────┬───────────┬──────────┬──────────────────┐ │
│  │ Cliente    │ Plano      │ Fluxo     │ Últ. inter│ Ações           │ │
│  ├────────────┼────────────┼───────────┼──────────┼──────────────────┤ │
│  │ @fulano    │ PREMIUM    │ Bem-vindo │ 5 min atr│ [Abrir Chat ]  │ │
│  │ @ciclana   │ —          │ —         │ 1 h atrás│ [Abrir Chat ]  │ │
│  │ +55 11 9…  │ BÁSICO     │ Upsell    │ ontem    │ [Abrir Chat ]  │ │
│  └────────────┴────────────┴───────────┴──────────┴──────────────────┘ │
└────────────────────────────────────────────────────────────────────────┘
```

**Interações/estados:**
- **Colunas novas** (Spec 119 D3): `Plano` (`lastPlanName` — última compra via
  `pix_transactions.plan_id`), `Fluxo` (`flowName` — funil de origem), `Última
  interação` (`lastInteractionAt` — `bot_leads.updated_at`, hora relativa com
  `Intl.RelativeTimeFormat`).
- **Busca por telefone**: o `q` do `GET /portal/crm/leads` passa a buscar
  `LIKE` em name/username/email/**phone** (Spec 119 D3); input único com hint
  "nome, @, e-mail ou telefone".
- Valores ausentes → `—`; ordenação por última interação (desc) como default.
- Live Chat (Spec 84) preservado no botão "Abrir Chat".

### D7.2 — Webhooks melhorado (`/webhooks` — Spec 35 enriquecido)

```
┌────────────────────────────────────────────────────────────────────────┐
│  Webhooks                                                              │
│  ┌──────────────┬─────────┬─────────┬────────┬──────────────────────┐  │
│  │ URL          │ Eventos │ Status  │ Falhas │ Ações                │  │
│  ├──────────────┼─────────┼─────────┼────────┼──────────────────────┤  │
│  │ https://...  │ 7       │[ATIVO]  │ 0      │ [Ver payload] [Testar]│
│  │ https://...  │ 3       │[INATIVO]│ 5    │ [Ver payload] [Ativar]│
│  └──────────────┴─────────┴─────────┴────────┴──────────────────────┘  │
│   Aviso de segurança: autenticação HMAC-SHA256 + HTTPS obrigatório.  │
│     Desativação automática após 5 falhas de entrega.                   │
│                                                                        │
│  [Ver payload — drawer]                                             │
│  ┌────────────────────────────────────────────────────────────────┐   │
│  │ PAYLOAD REAL — SALE_PAID (mock do contrato, sem PII)          │   │
│  │ {                                                              │   │
│  │   "eventId": "…", "type": "SALE_PAID",                        │   │
│  │   "transactionId": "…", "amount": 49.90,                      │   │
│  │   "buyerIdHash": "****"   ← mascarado                          │   │
│  │ }                                                              │   │
│  └────────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────┘
```

**Interações/estados:**
- **Colunas aditivas** (Spec 119 D4): `status` (ACTIVE/INACTIVE — badge),
  `attempts` (contador), `lastError` (tooltip no ícone de falha).
- **Falhas ≥ 5** → badge  "Desativado" (dados reais da Spec 35: o backend
  desativa após 5 falhas); botão vira **Ativar** (`POST /webhooks/{id}/activate`).
- **Drawer "Ver payload"**: `GET /portal/webhooks/{id}/sample?type=` → JSON
  do contrato do evento (**sem PII** — `buyerIdHash` mascarado, padrão Spec
  35/64); `<pre>` com syntax highlight leve + botão "Copiar JSON" (feedback
  Spec 73) e select de tipo de evento.
- **Aviso de segurança**: bloco fixo informativo (HMAC-SHA256, HTTPS
  obrigatório, desativação automática após 5 falhas) — texto, sem ação.
- **Testar**: reuso do `POST /test` existente (Spec 35) — dispara evento de
  teste real.

### D7.2.1 — Drawer de payload: interações e mascaramento (aprofundamento)

O drawer ("Ver payload") é o ponto de depuração do lojista. Estados e
comportamento:

```
┌────────────────────────────────────────────────────────────────────────┐
│  ◀ Voltar                                PAYLOAD DO EVENTO             │
│  ───────────────────────────────────────────────────────────────────── │
│  Evento:  [SALE_PAID ▾]          [Atualizar]  [Copiar JSON]      │
│  ───────────────────────────────────────────────────────────────────── │
│   Exemplo do contrato — sem dados reais (valores mascarados)         │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │ {                                                                │  │
│  │   "eventId":      "evt_8f2c9a…",                                 │  │
│  │   "type":         "SALE_PAID",                                   │  │
│  │   "timestamp":    "2026-08-16T02:14:00Z",                        │  │
│  │   "transaction": {                                               │  │
│  │     "id":         "TREVO-2026-08-16-0001",                       │  │
│  │     "amount":     49.90,                                         │  │
│  │     "status":     "PAID",                                        │  │
│  │     "buyerIdHash":"******"   ← SEM PII (máscara fixa)            │  │
│  │   }                                                              │  │
│  │ }                                                                │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                        │
│  ℹ Este exemplo segue o contrato da Spec 35 (outbox). Campos sensíveis │
│     (buyerIdHash, payload original) nunca são exibidos em claro.        │
└────────────────────────────────────────────────────────────────────────┘
```

**Interações:**
- **Select de evento** → recarrega o sample (`GET /webhooks/{id}/sample?type=`)
  — tipos: `SALE_PAID`, `SALE_PENDING`, `SALE_EXPIRED`, `SALE_REFUNDED`,
  `DELIVERY_COMPLETED`, `JOB_FAILED` (subset exibido conforme os eventos
  assinados pelo webhook).
- **Copiar JSON**: `copyToClipboard` do texto do `<pre>` → " Copiado!" 2s.
- **Atualizar**: refetch do sample (útil se o contrato mudar).
- **Loading do drawer**: `Spinner` pequeno na área do JSON (o drawer abre com
  o select já no 1º evento; conteúdo carrega sob demanda).
- **Erro**: mensagem inline "Não foi possível carregar o exemplo." + botão
  "Tentar novamente".

**Mascaramento (regra de segurança — Spec 35/64):**
- `buyerIdHash` → máscara fixa `"******"` (nunca o hash real — mesmo em dev,
  para o E2E não depender de dado sensível).
- Payload original da transação real: **não exposto** — o sample é um mock do
  contrato (Spec 119 D4), com `amount`/`status` de exemplo e ids placeholder
  (`evt_…`, `TREVO-…`).
- Nenhum campo de cliente/comprador (nome, telefone, e-mail) no sample.

### D7.3 — Comunidade (`/community`)

```
┌────────────────────────────────────────────────────────────────────────┐
│  Comunidade                                                           │
│   [PINADO]  Novo recurso: checkout customizado!                   │
│     "Agora você pode criar páginas de venda com a sua cara…"  16/08   │
│  ┌──────────────────────────────────────────────────────────────────┐ │
│  │  Manutenção programada — domingo 02h                          │ │
│  │    Teremos uma janela de manutenção de 30 min…          15/08   │ │
│  └──────────────────────────────────────────────────────────────────┘ │
│  (lista read-only paginada, cap 50; detail por post)                  │
└────────────────────────────────────────────────────────────────────────┘
```

**Interações/estados:**
- **Lista**: `GET /api/v1/portal/community` (ROLE_MERCHANT_PORTAL, paginada,
  cap 50) — post pinado primeiro (), depois por `published_at` desc.
- **Post**: título, conteúdo, autor (nome do master `published_by`), data;
  click abre **detail** (modal ou página — decisão: modal simples, KISS).
- **Empty**: "Nenhum aviso publicado ainda." — **sem chat/comentários**
  (fora de escopo).
- **Master Console**: `GET/POST/PATCH/DELETE /api/v1/master/community/posts`
  (ROLE_MASTER, RBAC Spec 69 OPERATOR, auditoria `COMMUNITY_POST_*` Spec 37)
  — página simples de posts no console (listagem + editor), mesmo padrão da
  Spec 119 D5; seed opcional "Bem-vindo" no boot (idempotente).
