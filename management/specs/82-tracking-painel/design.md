---
name: Tracking — painel (UI portal)
status: implemented
---

# Spec 82 — Design: Tracking (painel)

## D1 — Conceito

Camada **puramente de apresentação** sobre os endpoints da Spec 81 (e Spec
74 p/ bots). Padrão da `WebhooksPage` (Spec 35): página de credenciais com
cards + modal. Nenhuma rota de backend nova.

### D1.1 Decisões do usuário (2026-08-12) → regras

| Decisão | Regra |
|---|---|
| Painel no portal (lojista) | `TrackingPage`; console não ganha tela nesta fase |
| Perfis reutilizáveis | duplicar cria novo perfil (Spec 81) |
| Tokens mascarados | UI nunca mostra token em claro (criptografia Spec 81) |
| Saúde por perfil | linha "X ok · Y erros" + sync por serviço |

## D2 — Página e rotas (portal)

- `/tracking` — `TrackingPage` (resumo + lista de perfis).
- Sem rotas aninhadas: criar/editar/log via **modal** (padrão WebhooksPage).

Dados:
- `GET /api/v1/portal/tracking` (Spec 81) → resumo + lista (com badges e
  contagem do log).
- `GET /api/v1/portal/tracking/{id}/events` (Spec 81) → log paginado.
- `GET /api/v1/portal/bots` (Spec 74) → multiselect de vínculo.
- `POST/PUT/DELETE /api/v1/portal/tracking...` (Spec 81) → ações.
- `POST /api/v1/portal/tracking/{id}/sync` (Spec 81) → sincronizar.

## D3 — Componentes

- `TrackingPage` — 3 `SummaryCard` (Perfis Ativos, Fluxos Rastreados, Total
  de Eventos) + lista de `TrackingProfileCard`; empty state com CTA "Criar
  perfil".
- `TrackingProfileCard` — nome + `StatusBadge` (Ativo/Inativo), `ServiceBadge`
  (Pixel/TikTok/UTMify/Otimizey — só os configurados), chips de bots
  (nome do bot), `LogStatusLine` ("1.024 ok · 3 erros") e ações:
  - Editar → abre `TrackingProfileModal` (pré-preenchido).
  - Duplicar → confirma (`ConfirmDialog` `primary`) → `POST /duplicate`.
  - Ativar/Desativar → toggle → `POST /activate|deactivate`.
  - Sincronizar → `POST /sync` → `SyncResultModal`.
  - Excluir → `ConfirmDialog` `danger` → `DELETE`.
- `TrackingProfileModal` — `Field` nome (obrigatório); seções por serviço:
  - Facebook: `Field` Pixel ID + `Field` token (mascarado; placeholder
    "••••" quando já configurado — só pede novo se quiser trocar).
  - TikTok: Pixel Code + token.
  - UTMify/Otimizey: token/webhook.
  - `BotMultiSelect`: multiselect dos bots (chips) — carregado de
    `GET /api/v1/portal/bots`.
  - Validação: nome obrigatório; serviço configurado exige pixel/code + token
    juntos (aviso inline).
- `SyncResultModal` — lista `{ service, ok, message }` com badges
  sucesso/erro; botão fechar.
- `EventLogModal` — histórico paginado: evento (badge), status OK/ERROR
  (badge), data, payload resumido com "ver mais" (expand).
- `useTracking` (hook) — carrega lista/resumo via `useAsync` (sem blink,
  Spec 73); ações com try/catch + `SuccessBanner`/`ErrorBanner`.

## D4 — Validação e feedback

- Criar/editar: `POST`/`PUT` (Spec 81) → sucesso → banner + recarrega lista;
  erro → `ErrorBanner` (mensagem do backend).
- Tokens: ao editar perfil existente, o campo token aparece **vazio com
  placeholder mascarado**; enviar vazio = mantém o atual (não sobrescreve) —
  contrato da Spec 81 (PUT parcial).
- Sincronizar: resultado por serviço; falha de um não impede os demais;
  `Spinner` durante a chamada.

## D5 — UX (padrão Spec 73)

- Banners anunciáveis; `Spinner` no loading; empty states; modais acessíveis
  (focus trap/Escape/autofocus/aria); `aria-label` nos botões de ícone;
  skip-link herdado.

## D6 — Testes

- **E2E**: criar perfil (badges acendem só os configurados), vincular bot
  (chips), editar com token mascarado (vazio não sobrescreve), sincronizar
  (resultado por serviço), excluir com confirmação, log paginado.
- Validação cliente (nome obrigatório; pixel sem token) sem rede.
- `npm run build` (tsc) limpo; suíte E2E existente sem regressão (ordem de
  build: portal antes do console — achado da Spec 73).
