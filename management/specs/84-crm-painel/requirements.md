---
name: CRM — painel (UI portal)
status: audited
references:
  - "management/vision.md#V61"
  - "management/specs/83-crm-chat/requirements.md"
  - "management/specs/74-registro-bots/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
  - "management/specs/46-portal-frontend/requirements.md"
---

# Spec 84 — CRM: painel (UI portal)

## O que entrega

A tela do portal onde o lojista **vê a base de clientes e atende em tempo
real** — padrão UX da Spec 73 — sobre os endpoints da Spec 83. Sem backend
novo além do que a Spec 83 define.

1. **Cards superiores**: Novos, Pendentes, Pagos, Bloqueados e Total de leads
   (`GET /api/v1/portal/crm/summary`).
2. **Busca e filtros**: campo de busca (nome/@username/e-mail/ID — `q`),
   filtros por **bot**, **status** e **período** (Hoje/Ontem/Semana/Mês/Tudo)
   — `GET /api/v1/portal/crm/leads`.
3. **Tabela de clientes**: nome/username/ID (platform), **bot & fluxo**,
   **badge de status** (cores por estado), botão **"Abrir Chat"** e indicador
   de **mensagem não lida** na linha.
4. **Live Chat (modal)**: histórico persistido (Spec 83), conexão **SSE**
   (`GET /api/v1/portal/crm/stream`) para receber mensagens do cliente em
   tempo real, campo de envio (REST — `POST .../messages`), bolha do cliente
   à esquerda / resposta à direita, **sinal sonoro leve** ao receber mensagem
   com a janela aberta e **marcar lida** ao abrir (`POST .../read`).

## Contexto (por que agora)

A Spec 83 entrega a base e a ponte; sem o painel, o lojista não vê a base nem
atende em tempo real — o valor central do módulo do irmão. Decisão do usuário
(2026-08-12): painel no portal com Live Chat via SSE.

## Critérios de aceite

1. **Tela "Clientes" no portal** (rota `/crm`, menu lateral): cards de
   resumo + tabela; empty state ("Nenhum cliente ainda").
2. **Cards**: Novos/Pendentes/Pagos/Bloqueados/Total (cores por estado),
   atualizados do `GET /api/v1/portal/crm/summary` — `useAsync` sem blink.
3. **Busca/filtros**: `q` (nome/@/e-mail/ID) + filtros bot (`GET
   /api/v1/portal/bots`, Spec 74), status e período (Hoje/Ontem/Semana/Mês/
   Tudo — `from`/`to`); recarga sem zerar a lista (padrão Spec 73).
4. **Tabela**: nome (ou fallback `@username`/`user_platform_id`), @username,
   ID da plataforma, bot & fluxo (Spec 74), badge de status
   (NOVO=cinza, PENDENTE=amarelo, PAGO=verde, BLOQUEADO=vermelho), indicador
   de não lida (bolinha + contagem) e botão "Abrir Chat".
5. **Live Chat (modal)**: histórico paginado com scroll no fim; mensagens do
   cliente à esquerda, do admin à direita; mídia recebida exibida
   (`media_url`); campo de envio desabilitado se lead `BLOQUEADO` (aviso) e
   se sem conteúdo; envio → `POST .../messages` com `SuccessBanner`/
   `ErrorBanner` (ex.: `lead_blocked`, 502 do provedor).
6. **Tempo real**: ao abrir o modal, conecta no **SSE**
   (`GET /api/v1/portal/crm/stream` via `EventSource`); mensagem do cliente
   do lead aberto → append no histórico + **sinal sonoro leve** (Web Audio
   beep curto) + contador de não lida decrementa ao marcar lida; reconexão
   automática do `EventSource` (nativo) com `Last-Event-ID` se suportado.
7. **Não lidas**: linha do lead com mensagens `CLIENT` não lidas mostra
   bolinha + contagem (`COUNT(is_read=false)` da Spec 83); abrir o chat →
   `POST .../read` → limpa.
8. **Acessibilidade/UX**: modal com focus trap/Escape/autofocus/aria (Spec
   73); `aria-label` nos botões; sinal sonoro com toggle de mute
   (persistido em `localStorage`); skip-link herdado.
9. **Testes**: E2E (cards, busca/filtros, abrir chat com histórico, envio,
   SSE recebe mensagem do cliente — mockado, não lida marca ao abrir,
   lead bloqueado desabilita envio); `npm run build` (tsc) limpo; sem
   regressão na suíte E2E existente (ordem de build: portal antes do console
   — achado da Spec 73).

## Dependências

- **Spec 83** (backend) — endpoints consumidos (leads, summary, messages,
  read, stream).
- **Spec 74** (bots) — `GET /api/v1/portal/bots` (filtro e nome do bot).
- **Spec 73** (UX) — banners, modais, `Field`, `Spinner`.
- **Spec 46** (portal) — padrão do frontend.

## Fora de escopo

- **CRM no Master Console** — é do lojista (portal); console não ganha tela
  nesta fase.
- **Edição manual de status/etiquetas** — status é automático (Spec 83).
- **Upload de mídia no chat pelo painel** — texto primeiro (Spec 83).
