---
name: Painel de gestão de bots (UI — console + portal)
status: audited
---

# Spec 76 — Design: Painel de gestão de bots (UI — console + portal)

## D1 — Conceito

Camada **puramente de apresentação** sobre os endpoints das Specs 74/75. Dois
frontends (React/Vite — padrão Specs 46/71), **zero mudança de contrato de
backend** além do que as Specs 74/75 já definem; se a UI precisar de algo a
mais, é aditivo mínimo documentado no task (nunca silencioso).

- **Portal** (`portal/src/pages/BotsPage.tsx`): CRUD dos bots do lojista.
- **Console** (`console/src/pages/BotsPage.tsx`): espelho read-only.

### D1.1 Decisões do usuário (2026-08-12) → regras

| Decisão | Regra |
|---|---|
| Lojista opera os próprios bots no portal | CRUD completo no portal (Spec 74) |
| Operador vê tudo, sem escrever | Console read-only (RBAC Spec 69 — ações escondidas/403) |
| Criadores separados | "Conectar bot" aponta para os wizards (Specs 77/78); modal simples de token também disponível |
| Padrão UX existente | Banners, ConfirmDialog, Spinner, empty states (Spec 73) |

## D2 — Portal: `BotsPage`

### D2.1 Estrutura

- Rota `/bots` (menu lateral do portal, junto de Loja/Vendas).
- `useAsync` para carregar: `GET /api/v1/portal/bots` (Spec 74) +
  `GET /api/v1/portal/bots/metrics` (Spec 75) — sem blink (padrão Spec 73).
- Cards: ícone da plataforma (Telegram/Discord), `display_name`, `@username`,
  badge de status (ONLINE=ACTIVE, OFFLINE=PAUSED/REGISTERING, ERRO=ERROR),
  leads/vendas (e conversão quando > 0).
- Filtro por plataforma (abas ou select).
- Botões: Pausar/Reativar (toggle), Editar (modal), Excluir (`ConfirmDialog`
  `danger`), Conectar (modal de conexão ou link para os criadores).

### D2.2 Componentes

- `BotCard`, `BotStatusBadge`, `ConnectBotModal`, `EditBotModal` —
  `ConfirmDialog`/`SuccessBanner`/`ErrorBanner`/`Spinner`/`Field` reusados do
  `ui.tsx` (Spec 73).
- Ações: `pauseBot`/`resumeBot` (`POST .../pause|resume`), `updateBot` (`PUT`),
  `deleteBot` (`DELETE`) — todas com try/catch + banner (sem unhandled
  rejection).

## D3 — Console: `BotsPage` (read-only)

- Rota `/bots` (menu do Master Console, RBAC-aware — navegação só com
  permissão de operação, Spec 69).
- `GET /api/v1/master/bots` (Spec 74) + `GET /api/v1/master/bots/metrics`
  (Spec 75): tabela/lista com lojista (nome), plataforma, status, leads,
  vendas; filtros (plataforma/status/lojista).
- **Sem ações de escrita** — nenhum botão de pausar/editar/excluir; se o
  backend responder 403 em tentativa, a UI já não oferece a ação.

## D4 — Modais

### D4.1 `ConnectBotModal` (portal)

1. Passo 1: selecionar plataforma (Telegram/Discord).
2. Passo 2: token (Telegram: `BOT_TOKEN`; Discord: token + Application ID) +
   botão "Validar e conectar" → `POST /api/v1/portal/bots` (Spec 74) que
   integra em tempo real (getMe/gateway).
3. Feedback: sucesso → banner + fecha modal + card novo aparece; erro →
   `ErrorBanner` (mensagem do backend; ex.: `invalid_bot_token`).

### D4.2 `EditBotModal` (portal)

- `display_name` (sempre) + descrição/bio e comandos (Telegram:
  `setMyDescription`/`setMyCommands` via Spec 74; Discord: slash commands) —
  campos condicionais por plataforma.
- Salvar → `PUT /api/v1/portal/bots/{id}` (+ endpoints de edição da
  integração, Spec 74) → banners de sucesso/erro.

## D5 — Estados e acessibilidade

- Loading: `Spinner` (sem "Carregando…" textual — padrão Spec 73).
- Empty state: "Nenhum bot conectado ainda" + CTA "Conectar bot".
- Modais com focus trap/Escape/autofocus/aria (padrão Spec 73); `aria-label`
  nos botões de ícone; skip-link herdado do layout.
- Erro de rede/429: `ErrorBanner` com retry manual (recarregar lista).

## D6 — Testes

- **E2E portal** (`e2e-tests/`): conectar (mock do backend de integração),
  pausar/reativar, editar, excluir com confirmação; banners de sucesso/erro.
- **E2E console**: leitura + RBAC (operador vê; sem ações de escrita; 403 não
  exposto como erro).
- `npm run build` (tsc) limpo nos dois frontends; suíte E2E existente sem
  regressão.
