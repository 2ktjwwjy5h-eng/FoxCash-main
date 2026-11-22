---
name: Tracking — painel (UI portal)
status: audited
references:
  - "management/vision.md#V59"
  - "management/specs/81-tracking-eventos/requirements.md"
  - "management/specs/35-outbound-webhook/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
  - "management/specs/46-portal-frontend/requirements.md"
---

# Spec 82 — Tracking: painel (UI portal)

## O que entrega

A tela do portal onde o lojista **cria e opera perfis de tracking** — padrão
da `WebhooksPage` (Spec 35, gestão de credenciais) + UX da Spec 73 — sobre os
endpoints da Spec 81. Sem backend novo além do que a Spec 81 define.

1. **Cards superiores de resumo**: Perfis Ativos (total criado/ativo),
   Fluxos Rastreados (bots vinculados), Total de Eventos (disparos com
   sucesso) — do endpoint de lista/resumo da Spec 81.
2. **Card do perfil de tracking**: nome + status, **badges dos serviços
   conectados** (Pixel/TikTok/UTMify/Otimizey — acende só os configurados),
   **chips dos bots vinculados**, **linha de status do log** com contagem em
   tempo real (X ok / Y erros) e ações **Editar, Duplicar, Ativar/Desativar,
   Sincronizar e Excluir**.
3. **Modal de configuração**: nome; campos por serviço (Facebook: Pixel ID +
   token; TikTok: Pixel Code + token; UTMify/Otimizey: token/webhook) com
   **tokens mascarados** (nunca em claro — criptografia da Spec 81); vínculo
   dos bots (multiselect dos bots do lojista, Spec 74); validação por
   serviço (pixel + token juntos).
4. **Sincronizar**: botão por perfil → `POST /{id}/sync` (Spec 81) → resultado
   por serviço (`{ service, ok, message }`) com badges de sucesso/erro.
5. **Log de eventos**: visão por perfil (modal ou expand) com histórico
   paginado (evento, status OK/ERROR, data, payload resumido) — do endpoint
   `GET /{id}/events` da Spec 81.

## Contexto (por que agora)

A Spec 81 entrega o motor; sem o painel, o lojista não configura os pixels
nem vê a saúde dos disparos — o valor central do módulo do irmão (perfis
reutilizáveis + métrica "X ok, Y erros"). Decisão do usuário (2026-08-12):
painel no portal no padrão da WebhooksPage.

## Critérios de aceite

1. **Tela "Tracking" no portal** (rota `/tracking`, menu lateral): resumo
   (perfis ativos, fluxos rastreados, total de eventos) + lista de cards de
   perfis; empty state com CTA "Criar perfil".
2. **Card do perfil**: nome, badge de status (Ativo/Inativo), **badges dos
   serviços** (acende só os configurados — ex.: "Pixel", "TikTok", "UTMify",
   "Otimizey"), chips dos bots vinculados (nome do bot — Spec 74) e **linha
   de status do log** (X ok · Y erros) atualizada ao carregar.
3. **Ações**: Editar (modal), Duplicar (com confirmação — cria novo perfil
   via Spec 81), Ativar/Desativar (toggle), Sincronizar (resultado por
   serviço) e Excluir (`ConfirmDialog` `danger` — Spec 73); todas com
   `SuccessBanner`/`ErrorBanner` e sem unhandled rejection.
4. **Modal de configuração**: nome obrigatório; campos por serviço; **tokens
   mascarados** (mostra `••••1234` ao editar; só o placeholder pede valor
   novo); validação (pixel sem token → aviso); multiselect de bots
   (carregado de `GET /api/v1/portal/bots`, Spec 74) com chips de seleção.
5. **Sincronizar**: `POST /{id}/sync` (Spec 81) → lista por serviço com
   `ok`/erro e mensagem; falha de um não derruba os demais; loading com
   `Spinner`.
6. **Log de eventos**: por perfil (modal), paginado, com status OK/ERROR
   (badge), evento, data e payload resumido; "ver mais" no payload.
7. **Resumo**: cards superiores atualizados a partir do endpoint de lista/
   resumo da Spec 81 (perfis ativos, fluxos, total ok) — `useAsync` sem blink.
8. **Acessibilidade/UX**: modais com focus trap/Escape/autofocus/aria (Spec
   73); skip-link herdado; empty states; `Spinner` no loading.
9. **Testes**: E2E (criar perfil, badges corretos, vincular bot, sincronizar
   com resultado, excluir com confirmação, log paginado); `npm run build`
   (tsc) limpo; sem regressão na suíte E2E existente (ordem de build: portal
   antes do console — achado da Spec 73).

## Dependências

- **Spec 81** (motor) — endpoints consumidos (CRUD, sync, events, lista).
- **Spec 74** (bots) — `GET /api/v1/portal/bots` (multiselect de vínculo) e
  nome do bot nos chips.
- **Spec 35** (WebhooksPage) — padrão de tela de credenciais.
- **Spec 73** (UX) — banners, modais, `Field`, `Spinner`.

## Fora de escopo

- **Motor/disparo** → Spec 81.
- **Tela no Master Console** — tracking é do lojista (portal); console não
  ganha tela nesta fase.
- **Relatórios de conversão** (análise de campanha dentro do FoxCash) — a
  atribuição é dos provedores externos.
