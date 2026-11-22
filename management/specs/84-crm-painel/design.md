---
name: CRM — painel (UI portal)
status: audited
---

# Spec 84 — Design: CRM (painel)

## D1 — Conceito

Camada **puramente de apresentação** sobre os endpoints da Spec 83 (e Spec
74 p/ bots). Página + modal de chat com **SSE** (`EventSource` nativo do
browser — sem lib). Nenhuma rota de backend nova.

### D1.1 Decisões do usuário (2026-08-12) → regras

| Decisão | Regra |
|---|---|
| Painel no portal (lojista) | `CrmPage`; console não ganha tela nesta fase |
| Tempo real via SSE | `EventSource` (reconexão nativa); sem lib de websocket |
| Status automático | badges só exibem o status da Spec 83 (sem edição manual) |
| Sinal sonoro com mute | beep leve (Web Audio); toggle persistido em `localStorage` |

## D2 — Página e rotas (portal)

- `/crm` — `CrmPage` (cards + tabela); chat via **modal** (sem rota
  aninhada).

Dados:
- `GET /api/v1/portal/crm/summary` (Spec 83) → cards.
- `GET /api/v1/portal/crm/leads?q&botId&status&from&to&page` (Spec 83) →
  tabela.
- `GET /api/v1/portal/crm/leads/{id}/messages` (Spec 83) → histórico.
- `POST /api/v1/portal/crm/leads/{id}/messages` (Spec 83) → enviar.
- `POST /api/v1/portal/crm/leads/{id}/read` (Spec 83) → marcar lida.
- `GET /api/v1/portal/crm/stream` (Spec 83) → **SSE** (EventSource).
- `GET /api/v1/portal/bots` (Spec 74) → filtro de bot.

## D3 — Componentes

- `CrmPage` — 5 `SummaryCard` (Novos/Pendentes/Pagos/Bloqueados/Total, cores
  por estado) + `CrmFilters` + `CrmTable`.
- `CrmFilters` — busca `q` (debounce), select de bot (Spec 74), select de
  status (NOVO/PENDENTE/PAGO/BLOQUEADO), select de período
  (Hoje/Ontem/Semana/Mês/Tudo → `from`/`to`); recarga sem zerar a lista
  (padrão Spec 73).
- `CrmTable` — colunas: Cliente (nome fallback `@username`/ID), @username,
  ID da plataforma, Bot & Fluxo (nome do bot — Spec 74), Status (`StatusBadge`
  com cores), Não lida (bolinha + contagem), ação "Abrir Chat".
- `LiveChatModal` — histórico (bolhas: CLIENT à esquerda, ADMIN à direita,
  BOT_AUTO com ícone), mídia recebida (`media_url` renderizada), campo de
  envio (desabilitado se `BLOQUEADO` com aviso, ou sem conteúdo), footer com
  mute toggle.
- `useCrmStream` (hook) — `EventSource` no `stream`; filtra eventos por
  `leadId` do chat aberto; append no histórico; beep (Web Audio) ao receber
  com janela aberta; reconexão nativa do EventSource; cleanup no unmount.
- `useCrmLeads` (hook) — lista + summary via `useAsync` (sem blink); ações
  com try/catch + banners (Spec 73).

## D4 — Fluxo do chat

1. Abrir "Abrir Chat" → `LiveChatModal` + carrega histórico (paginado, scroll
   no fim) + `POST /read` (limpa não lidas da linha).
2. `useCrmStream` conecta (uma conexão por página, não por modal) — evento
   `{ type: "message", leadId, message }`:
   - leadId aberto → append na conversa + beep (se não mute) + scroll.
   - outro lead → incrementa o contador de não lida da linha (indicador na
     tabela).
3. Enviar → `POST .../messages` → append otimista (ou após resposta) →
   `SuccessBanner`/`ErrorBanner` (`lead_blocked` → badge BLOQUEADO atualizado
   na tabela).

## D5 — UX (padrão Spec 73)

- Banners anunciáveis; `Spinner` no loading; empty state (tabela e chat);
  modais acessíveis (focus trap/Escape/autofocus/aria); `aria-label` nos
  botões de ícone; skip-link herdado.
- Beep: `AudioContext` + oscilador curto (500ms, volume baixo); mute toggle
  com ícone; sem autoplay problemático (inicia no primeiro gesto do usuário).

## D6 — Testes

- **E2E**: cards refletem summary; busca/filtros aplicam; abrir chat carrega
  histórico; envio com banner; **SSE** (mock) entrega mensagem do cliente →
  append + beep + não lida; abrir marca lida; lead bloqueado desabilita envio
  (aviso).
- Validação cliente (envio sem conteúdo desabilitado) sem rede.
- `npm run build` (tsc) limpo; suíte E2E existente sem regressão (ordem de
  build: portal antes do console — achado da Spec 73).
