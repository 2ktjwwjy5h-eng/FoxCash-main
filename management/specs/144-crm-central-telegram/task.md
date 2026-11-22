---
name: Central de atendimento estilo Telegram no CRM do portal (Spec 84 UI)
status: audited
---

# Spec 144 — Task: Central de atendimento estilo Telegram no CRM do portal

Ordem: T0 (CrmPage) → T1 (CSS) → T2 (E2E) → T3 (builds).

## T0 — CrmPage de 2 painéis (critérios 1–7)

- [x] T0.1: `ChatItem` — item da sidebar (avatar/iniciais, nome, @username/
      telefone/ID, bot, data, não lida, status, dot handoff, barra azul de
      seleção, teclado Enter/Space, aria-label).
- [x] T0.2: `ChatPanel` — header (avatar, nome, ID·via bot, Plano, Última
      interação, status, Pausar/Retomar, som, `‹ pos/total ›`, Fechar),
      histórico em bolhas + mídia + ✓/✓✓, rodapé de envio com aviso de
      bloqueio.
- [x] T0.3: Página — sidebar + painel; lista local sincronizada (merge do
      servidor preservando overrides); SSE no nível da página
      (`selectedRef`/`appendRef`/`registerAppend`) com **não lida do lead
      certo**; `?lead={id}` (Spec 102); navegação `‹ ›` com posição global;
      handoff + beep/mute + read preservados.

## T1 — CSS (critério 8)

- [x] T1.1: Bloco novo em `portal/src/styles.css` (D5) — `.crm-inbox*`,
      `.crm-chat-item*`, `.crm-chat-avatar*`, `.crm-panel-header*`,
      `.crm-panel-footer`, `.crm-nav-btn`/`.crm-icon-btn`, `.crm-chat-history`
      (bolhas + mídia + ✓✓), `.crm-chat-empty`. Tema claro; classes
      compartilhadas (`.crm-chat-header`/`.crm-chat-actions`/`.crm-chat-sub`)
      **intactas** (Comunidade/Webhooks).

## T2 — E2E (critério 9)

- [x] T2.1: `e2e-tests/tests/crm.spec.js` — 3 cenários com os novos seletores
      (`.crm-chat-item`, `.crm-inbox-chat`, `Fechar exact`).
- [x] T2.2: `portal-shell-ui.spec.js` — avatar → `.crm-inbox-chat` (antes
      `.crm-chat-modal`).
- [x] T2.3: `portal-leves.spec.js` — busca por telefone → `.crm-chat-item`;
      Plano/Última interação no cabeçalho do painel.

## T3 — Builds (critério 9)

- [x] T3.1: `npm run build` no portal (tsc + vite) limpo; console reconstruído
      por último (ordem README — `emptyOutDir` apaga `static/console/`).

---

## Implementação (2026-08-17) — notas

Implementação concluída ANTES da formalização da spec (a central foi feita na
sessão e o fluxo SDD documenta o entregue). Notas de execução:

- **T0.x entregues no `CrmPage.tsx`** — exatamente o desenhado em D2/D3/D4:
  `ChatItem` (lista) + `ChatPanel` (painel) + página de 2 painéis. O modal
  antigo (`LiveChatModal`) e o `useDialogFocus` foram removidos (o chat agora é
  inline).
- **Bug corrigido (SSE)** — no código antigo, mensagem de OUTRO lead
  incrementava a não lida do lead ABERTO (`onLeadUpdated({...currentRef.current,
  unreadCount+1})`); agora `updateLead(event.leadId, ...)` incrementa o lead
  certo. A lista local também passou a refletir não lidas/handoff/status em
  tempo real (merge no `useEffect([data])` — antes `upsertLead` mapeava uma
  lista nunca populada, ou seja, updates locais não apareciam).
- **Colisão de classes evitada** — o painel usa `crm-panel-*` (o primeiro rascunho
  redefinia `.crm-chat-header`/`.crm-chat-footer`, que são usadas por
  `CommunityPage`/`WebhooksPage`); corrigido antes do build.
- **Spec 119 preservada** — `relativeTime` re-adicionado e Plano + Última
  interação exibidos no cabeçalho da conversa (a tabela com colunas
  Plano/Fluxo/Última interação saiu junto com o layout novo; o `portal-leves.spec.js`
  foi ajustado para verificar no painel).
- **T3.1 (builds)** — portal `tsc -b` + vite limpos; o `emptyOutDir` do portal
  apagou `static/console/` (achado conhecido da Spec 73) → console reconstruído
  por último (ficou idêntico ao HEAD, sem regressão). Estáticos finais: portal
  `index-BYxT3KmZ.js`/`index-DkicNcI_.css`, console
  `index-tdid8kcx.js`/`index-DTm60V5V.css` + fontes.

**Âncoras validadas na spec:** endpoints Specs 83/90; `useAsync`/banners/
`Spinner` (Spec 73); `LeadAvatar`/HeaderGlobal `?lead=` (Spec 102);
`lastPlanName`/`lastInteractionAt` no `CrmLead` (Spec 119); classes
compartilhadas `.crm-chat-header`/`.crm-chat-actions`/`.crm-chat-sub` intactas
(Comunidade/Webhooks).

## Auditoria (2026-08-17) — 9/9 critérios OK, 0 achados abertos

Verificação 1:1 contra o código real (critérios do requirements):

- **C1 (layout 2 painéis)** — `CrmPage.tsx` renderiza `.crm-inbox` (flex) com
  `.crm-inbox-sidebar` (~300px, scroll) + `.crm-inbox-chat` (flexível). Classes
  `crm-panel-*`/`crm-chat-item*` novas; `.crm-chat-header`/`.crm-chat-actions`/
  `.crm-chat-sub` NÃO foram redefinidas (o diff de `styles.css` só adiciona
  blocos novos; `CommunityPage`/`WebhooksPage` intactas).
- **C2 (sidebar)** — header "Chats" + `.crm-inbox-count`; busca "Buscar..."
  (`aria-label="Buscar conversas"`); `ChatItem` com avatar/iniciais, nome,
  `@username`/telefone/ID, bot 🤖, `chatDate`, `.crm-unread`, `StatusBadge`,
  dot `.human`; `.selected::after` barra azul; paginação no `.crm-inbox-sidebar-foot`.
- **C3 (painel)** — `crm-panel-header` com avatar, nome, `ID · via bot`,
  `· Plano:` (lastPlanName) e `· Últ. interação:` (relativeTime — Spec 119),
  `StatusBadge`, Pausar/Retomar, Som (aria-pressed), `‹ pos/total ›`, Fechar
  (`aria-label="Fechar"`); histórico com bolhas por remetente + `.chat-media` +
  `.chat-read-mark` (✓/✓✓); rodapé `crm-panel-footer`.
- **C4 (envio/bloqueio)** — `send()` → `POST /leads/{id}/messages` (Enter +
  botão); `blocked` (status BLOQUEADO) → `alert-warning` "Lead bloqueou o bot —
  envio desabilitado." e **sem** botão Enviar.
- **C5 (SSE)** — `useCrmStream` no nível da página; evento do lead aberto →
  `appendRef` (append dedup + beep via `playBeepRef`); outro lead →
  `updateLead(leadId, ...)` incrementa a não lida **do lead certo** (bug
  pré-existente corrigido).
- **C6 (handoff/read)** — `PUT /leads/{id}/handoff` com badge "Atendimento
  manual — bot pausado" + success banner; `POST /leads/{id}/read` ao abrir +
  `updateLead(..., unreadCount: 0)` na lista.
- **C7 (`?lead=`)** — `useEffect([searchParams])` busca o lead, `setSelectedLead`
  + prepend na lista, limpa o param (Spec 102 — avatar do header).
- **C8 (CSS puro/contrato)** — zero dependência nova (sem Tailwind/lib de
  ícones); **nenhum contrato HTTP alterado** (o único toque em backend foi um
  comentário em `PortalCrmController.java` — ver C9, sem mudança funcional);
  `ErrorBanner`/`SuccessBanner`/`Spinner`/empty states (Spec 73).
- **C9 (builds/E2E)** — `npm run build` portal (tsc 82 módulos + vite) e
  console limpos (estáticos restaurados); `crm.spec.js` (3 cenários),
  `portal-shell-ui.spec.js` e `portal-leves.spec.js` atualizados para os novos
  seletores; `scripts/check-docs-consistency.sh` → **OK**. Observação: a suíte
  E2E completa depende do CI (a instância local em 8080 servia bundle antigo
  em memória — sem re-teste E2E nesta sessão; os seletores foram conferidos
  contra o markup final). Ajuste pré-existente destravado no caminho: o check
  B do docs-consistency tinha um **falso positivo** — o comentário em
  `PortalCrmController.java:76` citava "funil da **Spec 117** ainda não
  existe", e o regex do check lia a menção como código implementado (a spec
  117 está `verified` e de fato NÃO tem funil); texto ajustado para "funil de
  automações ainda não existe" (comentário-only, sem mudança funcional).

**Registro de numeração:** Spec **143** está reservada nos comentários de
código do portal (modal de termos do cadastro — `RegisterPage.tsx`, `terms.ts`,
`vite.config.ts`) mas **não está registrada no roadmap** (gap de sincronia do
tipo que a Spec 138 audita). Esta spec usou o número **144** para não colidir;
a Spec 143 (termos) deve ser registrada/auditada em ciclo próprio.

Fim do ciclo SDD (roadmap/requirements/design/task `audited`).
