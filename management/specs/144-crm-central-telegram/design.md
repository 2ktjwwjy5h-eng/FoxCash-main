---
name: Central de atendimento estilo Telegram no CRM do portal (Spec 84 UI)
status: audited
---

# Spec 144 — Design: Central de atendimento estilo Telegram no CRM do portal

## D1 — Conceito

A página `/crm` (`portal/src/pages/crm/CrmPage.tsx`) troca a **tabela de
clientes + modal de Live Chat** por uma **central de 2 painéis inline** (sem
modal, sem `useDialogFocus`):

```
[ h1 Clientes ]
[ cards de resumo ×5 ]
[ filtros: busca | bot | status | período ]
[ ┌─────────────┬───────────────────────────────────────┐ ]
[ │ sidebar      │ painel direito                        │ ]
[ │ Chats (N) ✕  │ header: avatar · nome · ID·bot ·      │ ]
[ │ 🔍 Buscar…   │   plano · últ. interação · status ·   │ ]
[ │ [avatar]nome │   [Pausar][Som][‹][3/50][›][✕]        │ ]
[ │  @user/bot   │ ───────────────────────────────────── │ ]
[ │  🤖 bot      │  bolhas CLIENT / ADMIN / BOT_AUTO     │ ]
[ │  [badge][não]│  (mídia + ✓/✓✓ + horário)            │ ]
[ │ …            │ ───────────────────────────────────── │ ]
[ │ ← páginas →  │  [ Mensagem…      ] [Enviar]          │ ]
[ └─────────────┴───────────────────────────────────────┘ ]
```

**CSS puro** (sem Tailwind/lib de ícones — decisão Fase 29): as classes novas
são `crm-inbox-*`, `crm-chat-item*`, `crm-panel-*`, `crm-chat-*` (histórico).
Onde o nome colide com classes compartilhadas (`.crm-chat-header`,
`.crm-chat-actions`, `.crm-chat-sub` — usadas por `CommunityPage` e
`WebhooksPage`), o painel usa **prefixo próprio `crm-panel-*`** — as páginas
existentes não mudam de visual.

## D2 — Estrutura da CrmPage (estados)

```tsx
const [q, setQ] = useState('');            // busca (filtro de topo E sidebar)
const [qDebounced, setQDebounced] = useState('');
const [botId, setBotId] = useState('');
const [status, setStatus] = useState('');
const [period, setPeriod] = useState('');
const [page, setPage] = useState(0);
const [leads, setLeads] = useState<CrmLead[]>([]);      // lista local
const [selectedLead, setSelectedLead] = useState<CrmLead | null>(null);
```

1. **Lista local sincronizada** — `useEffect([data])` faz `merge` do servidor
   com a lista local preservando overrides locais (não lidas, handoff, status):
   `prevMap.get(server.id) ?? server`. Assim o SSE atualiza a lista em tempo
   real sem depender de refetch.
2. **`updateLead(id, updater)`** — aplica o updater no `leads` e no
   `selectedLead` (se for o mesmo id). `openChat(chat)` seleciona + zera a não
   lida na hora.
3. **SSE no nível da página** (`useCrmStream`) com `selectedRef` +
   `appendRef` (o `ChatPanel` registra o append via `onRegisterAppend`):
   - evento do **lead aberto** → `appendRef.current(message)` (painel append +
     beep se CLIENT);
   - evento de **outro lead** → `updateLead(leadId, l => ({...l, unreadCount:
     l.unreadCount + 1}))` — **corrige o bug pré-existente** que incrementava o
     lead aberto (o `onLeadUpdated` antigo usava `currentRef.current.id`).
4. **Navegação `‹ ›`** — `nav(delta)` anda na página atual da lista (wrap).
   `position = page*size + index + 1`; `total = totalElements`.
5. **`?lead={id}`** (Spec 102) — `useEffect([searchParams])` busca o lead,
   seleciona e faz prepend na lista; limpa o param.

## D3 — Sidebar (ChatItem)

```tsx
<div className="crm-chat-item [selected|unread|human]"
     role="button" tabIndex={0} onClick={() => onSelect(chat)}
     onKeyDown={Enter/Space → onSelect} aria-label="Abrir conversa com X">
  <div className="crm-chat-avatar">{initialsOf(chat)}</div>
  <div className="crm-chat-item-body">
    <div className="crm-chat-item-top">
      <span className="crm-chat-item-name">{nome}</span>
      <span className="crm-chat-item-date">{chatDate(lastInteractionAt)}</span>
    </div>
    <span className="crm-chat-item-sub">@username | phone | ID</span>
    <span className="crm-chat-item-bot">🤖 {botName}</span>
  </div>
  <div className="crm-chat-item-meta">
    {unread > 0 && <span className="crm-unread">{n}</span>}
    <StatusBadge status={status} />
  </div>
</div>
```

- `initialsOf` — iniciais do nome (senão `@username`, senão ID).
- `chatDate` — dd/mm ou hora se for hoje.
- Seleção: `.selected` = fundo `#eff6ff` + `::after` barra azul `#2563eb` à
  direita. `.human` = dot laranja no avatar (handoff).
- Busca "Buscar..." no topo da sidebar espelha o MESMO `q` do filtro de topo
  (ambos filtram a lista, aria-label "Buscar conversas").
- Paginação `Anterior/Próxima` no rodapé quando `totalPages > 1`.

## D4 — Painel direito (ChatPanel)

Props: `lead, position, total, onPrev, onNext, onClose, onHandoffChanged,
onLeadUpdated, onRegisterAppend, botName`. Estado: `messages, loading, draft,
sending, handoffBusy, error, success, handoff, current, muted` + refs
(`bodyRef`, `inputRef`, `audioRef`, `handoffRef`, `currentRef`, `mutedRef`,
`playBeepRef`).

1. **Abrir conversa** (`useEffect([lead.id])`) — reseta estado, `GET
   .../messages?size=100` (histórico), `POST .../read` + zera não lida na
   lista, foca o input. `cancelled` evita race ao trocar de lead.
2. **Header** — `crm-panel-header`: avatar + nome + sub (`ID · via bot` ·
   `· Plano: X` · `· Últ. interação: <relativeTime>` · badge handoff) +
   `crm-panel-header-actions`: `StatusBadge`, botões **Pausar/Retomar bot** e
   **Som ligado/desligado** (aria-pressed), `‹ pos/total ›`, **Fechar**
   (`aria-label="Fechar"`).
3. **Histórico** — `crm-chat-history` (`role="log"`, `aria-label`): bolhas
   `.chat-bubble.chat-{client|admin|bot_auto}` (branca/azul/âmbar), mídia
   `.chat-media`, horário + `✓`/`✓✓` para ADMIN (`m.read`), scroll no fim ao
   adicionar. Loading → `Spinner`; vazio → empty state.
4. **Rodapé** — `crm-panel-footer`: input (`aria-label="Mensagem"`, Enter
   envia) + botão **Enviar**; lead BLOQUEADO → `alert-warning` "Lead bloqueou
   o bot — envio desabilitado." no lugar do input (sem botão Enviar).
5. **Registro do SSE** — `useEffect([onRegisterAppend, handleLiveMessage])`:
   `onRegisterAppend(handleLiveMessage)` (append dedup + beep se CLIENT via
   `playBeepRef`) e limpa no unmount.

## D5 — CSS (classes novas)

| Classe | Uso |
|---|---|
| `.crm-inbox` | contêiner flex (2 painéis), borda + radius, `height: calc(100vh - 330px)` |
| `.crm-inbox-sidebar` / `-head` / `-search` / `-foot` | coluna esquerda + header "Chats (N)" + busca + paginação |
| `.crm-chat-list` | scroll da lista |
| `.crm-chat-item*` | item da lista (avatar, nome, sub, bot, meta) + `.selected::after` barra azul |
| `.crm-chat-avatar` / `-lg` / `.human::after` | avatar com iniciais + dot handoff |
| `.crm-inbox-chat` | painel direito (coluna) |
| `.crm-panel-header*` / `.crm-panel-footer` | header/rodapé do painel (**prefixo próprio** — não colide com `.crm-chat-header` de Comunidade/Webhooks) |
| `.crm-nav-btn` / `.crm-icon-btn` / `.crm-nav-pos` | navegação `‹›`, fechar, posição |
| `.crm-chat-history` | área de mensagens com textura de pontos + overrides das bolhas (branca/azul/âmbar) |
| `.chat-read-mark` | `✓`/`✓✓` nas mensagens do atendente |
| `.crm-chat-empty` | empty state (painel sem conversa selecionada / histórico vazio) |

Tema claro do portal (`--surface`, `--border`, `#2563eb`, etc.) — **nenhum
Tailwind**, nenhuma lib de ícones (emoji/símbolos `‹ › ✕ 🤖 ✓`).

## D6 — Integração (arquivos tocados)

| Arquivo | Mudança |
|---|---|
| `portal/src/pages/crm/CrmPage.tsx` | reescrita: `ChatItem` + `ChatPanel` + página de 2 painéis (SSE page-level, lista local, `?lead`, handoff, beep/mute) |
| `portal/src/styles.css` | bloco novo `/* Central de atendimento estilo Telegram (Specs 83/84/90) */` (D5) |
| `e2e-tests/tests/crm.spec.js` | 3 cenários → novos seletores (`.crm-chat-item`, `.crm-inbox-chat`, `Fechar exact`) |
| `e2e-tests/tests/portal-shell-ui.spec.js` | avatar do header → `.crm-inbox-chat` (antes `.crm-chat-modal`) |
| `e2e-tests/tests/portal-leves.spec.js` | busca por telefone → `.crm-chat-item` + Plano/Última interação no painel |
| `src/main/resources/static/` | build do portal (novo asset `index-BYxT3KmZ.js`); console reconstruído por último (ordem README — `emptyOutDir` do portal apaga `static/console/`) |

**Zero mudança de backend** — endpoints Specs 83/90 intactos.

## D7 — Testes

- **E2E `crm.spec.js`** — (1) cards ×5 + lista de conversas com status/não lida/
  busca; (2) abrir conversa → histórico persistido + marcar lida (reload → 0 não
  lidas) + envio ADMIN; (3) lead BLOQUEADO → aviso "envio desabilitado" sem
  botão Enviar.
- **E2E `portal-shell-ui.spec.js`** — avatar do header → `/crm?lead=` → painel
  com o nome do lead.
- **E2E `portal-leves.spec.js`** — busca por telefone filtra a lista; plano +
  última interação visíveis no cabeçalho da conversa.
- **Build** — `tsc -b` (portal) + vite; console por último (estáticos intactos).

**Âncoras validadas na spec:** endpoints Specs 83/90 (`/crm/leads`,
`.../messages`, `.../read`, `.../handoff`, stream SSE); `useAsync`,
`ErrorBanner`/`SuccessBanner`, `Spinner` (Spec 73); `LeadAvatar`/HeaderGlobal
(Spec 102 — `?lead=`); campos `lastPlanName`/`lastInteractionAt` no
`CrmLead` (Spec 119); classes `.crm-chat-header`/`.crm-chat-actions`/
`.crm-chat-sub` compartilhadas (Comunidade/Webhooks — intactas).
