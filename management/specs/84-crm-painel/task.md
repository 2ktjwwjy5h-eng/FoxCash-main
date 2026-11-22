---
name: CRM — painel (UI portal)
status: audited
---

# Spec 84 — Task: CRM (painel)

## F0 — Página + cards + tabela (critérios 1–4)

- [x] T0.1: `portal/src/pages/crm/CrmPage.tsx` + rota `/crm` + item de menu
      "Clientes" — `GET /api/v1/portal/crm/summary` + `GET
      /api/v1/portal/crm/leads` (Spec 83) via `useAsync` sem blink (D2/D3).
- [x] T0.2: `SummaryCard` ×5 — Novos/Pendentes/Pagos/Bloqueados/Total (cores
      por estado).
- [x] T0.3: `CrmFilters` — busca `q` (debounce), selects de bot (Spec 74),
      status e período (Hoje/Ontem/Semana/Mês/Tudo → `from`/`to`); recarga
      sem zerar a lista.
- [x] T0.4: `CrmTable` — Cliente (fallback `@username`/ID), @username, ID,
      Bot & Fluxo, `StatusBadge` (NOVO=cinza/PENDENTE=amarelo/PAGO=verde/
      BLOQUEADO=vermelho), bolinha de não lida, botão "Abrir Chat"; empty
      state.

## F1 — Live Chat (critérios 5–7)

- [x] T1.1: `LiveChatModal` — histórico paginado (bolhas CLIENT esq./ADMIN
      dir./BOT_AUTO ícone), mídia recebida (`media_url`), scroll no fim.
- [x] T1.2: Campo de envio — desabilitado se `BLOQUEADO` (aviso) ou sem
      conteúdo; `POST /api/v1/portal/crm/leads/{id}/messages` (Spec 83) com
      banners (Spec 73); `lead_blocked` atualiza o badge na tabela.
- [x] T1.3: Abrir chat → carrega histórico + `POST /{id}/read` (Spec 83) →
      limpa não lidas da linha.
- [x] T1.4: `useCrmStream` — **`fetch` + `ReadableStream`** (parse SSE manual —
      o `EventSource` nativo não envia o Bearer) em `GET
      /api/v1/portal/crm/stream` (uma conexão por página): evento do lead
      aberto → append; de outro lead → incrementa não lida da linha;
      reconexão com backoff; cleanup no unmount.

## F2 — Fechamento (critérios 8–9)

- [x] T2.1: Modal acessível (focus trap/Escape/autofocus/aria — Spec 73 via
      `useDialogFocus`); `aria-label` nos botões; skip-link herdado.
- [x] T2.2: E2E — `crm.spec.js`: cards refletem summary; busca/filtros; abrir
      chat com histórico; envio com banner; abrir marca lida; lead bloqueado
      desabilita envio. **3/3**.
- [x] T2.3: `npm run build` (tsc) limpo + suíte E2E existente sem regressão
      (ordem de build: portal antes do console — achado da Spec 73).
- [ ] T2.4: `/spec-verify 84` e depois `/spec-audit 84` (critérios 1–9).

## §Implementação — decisões e desvios (registro)

1. **SSE via `fetch` + `ReadableStream`** (não `EventSource`): o EventSource
   nativo do browser não envia o header `Authorization` (o portal autentica com
   Bearer em sessionStorage) — o hook `useCrmStream` lê o stream com parsing
   manual (linhas `data:`) e reconexão com backoff de 3s. Mesma semântica do
   design D2/D4 (uma conexão por página, filtro por leadId).
2. **Bot & Fluxo na tabela**: o response do lead carrega `botId`; o nome do bot
   é resolvido no frontend com a lista `GET /api/v1/portal/bots` (Spec 74) —
   sem N+1 no backend (mesma decisão KISS da listagem master).
3. **Beep — IMPLEMENTADO na auditoria (2026-08-14)**: o requirements 84 C6
   pede "sinal sonoro leve" ao receber mensagem do lead aberto — entregue com
   Web Audio: `playBeep` (oscilador 880Hz ~500ms, volume baixo; `AudioContext`
   lazy — criado no 1º gesto do usuário, sem autoplay problemático), chamado no
   append de mensagem `CLIENT` do lead aberto no SSE; **mute toggle** no
   cabeçalho do modal (`aria-pressed`, persistido em `localStorage`
   `foxcash.crm.sound.muted`). Detalhe no §Auditoria.

## §Auditoria (2026-08-14) — `/spec-audit 84`: 9/9 critérios OK

Verificações contra o código real: **C1** tela `/crm` + menu "Clientes" com
empty state; **C2** 5 `SummaryCard` (novos/pendentes/pagos/bloqueados/total)
via `GET /crm/summary` com `useAsync` sem blink; **C3** busca com debounce +
filtros bot/status/período (Hoje/Ontem/Semana/Mês/Tudo → `from`/`to`) e recarga
sem zerar a lista; **C4** tabela com fallback nome/`@username`/ID, bot (Spec
74), `StatusBadge` com cores, bolinha de não lida e "Abrir Chat"; **C5** modal
com histórico paginado, bolhas CLIENT/ADMIN/BOT_AUTO, mídia recebida, envio com
banners e desabilitado em `BLOQUEADO`; **C6** SSE em tempo real (fetch +
ReadableStream — Bearer) + **beep implementado nesta auditoria** (achado 1) +
não lida decrementada ao marcar lida; **C7** indicador de não lida
(`COUNT(is_read=false)`) e `POST /read` ao abrir; **C8** modal acessível
(`useDialogFocus` — focus trap/Escape/autofocus/aria), `aria-label` nos botões,
mute com `aria-pressed`, skip-link herdado; **C9** `npm run build` (tsc) limpo
no portal + console (ordem portal → console) + E2E `crm.spec.js` 3/3 + suíte
completa sem regressão.

**Achados corrigidos (2) — 0 abertos:**

1. **C6 — sinal sonoro (beep) implementado** (pendência registrada no
   §Implementação): `playBeep` com Web Audio (oscilador 880Hz ~500ms, volume
   baixo; `AudioContext` lazy — criado no 1º gesto do usuário, sem autoplay
   problemático), chamado no append de mensagem `CLIENT` do lead aberto no SSE;
   **mute toggle** no cabeçalho do modal (botão "Som ligado/desligado" com
   `aria-pressed` e `aria-label`, persistido em `localStorage`
   `foxcash.crm.sound.muted`).
2. **MÉDIO — rota `/crm` fora da whitelist/fallback da SPA**: o
   `SecurityConfig.spaPublicPaths()` e o `PortalSpaController` não incluíam
   `/crm`/`/crm/*` → full reload (F5/goto direto) devolvia 401/404 JSON em vez
   do `index.html` (mesma classe do bug de `/broadcasts` da Spec 80, que os E2E
   não pegavam por navegarem client-side via menu); adicionados nos dois pontos
   (mesma correção documentada na Spec 83 §Auditoria, achado 2).
