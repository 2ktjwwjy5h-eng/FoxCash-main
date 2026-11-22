---
name: Central de atendimento estilo Telegram no CRM do portal (Spec 84 UI)
status: audited
references:
  - "management/vision.md#V103"
  - "management/specs/83-crm-chat/requirements.md"
  - "management/specs/84-crm-painel/requirements.md"
  - "management/specs/90-crm-handoff/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
  - "management/specs/46-portal-frontend/requirements.md"
  - "management/specs/102-portal-shell-ui/requirements.md"
  - "management/specs/119-leves-crm-webhooks-comunidade/requirements.md"
---

# Spec 144 — Central de atendimento estilo Telegram no CRM do portal (Spec 84 UI)

## O que entrega

A tela **Clientes** (`/crm`) do portal deixa de ser tabela + modal de Live Chat e
vira uma **central de atendimento de 2 painéis no padrão visual do Telegram**
Desktop/Web: **sidebar de conversas** à esquerda + **painel da conversa ativa**
à direita. Mudança **puramente de apresentação** — nenhum contrato HTTP muda,
nenhuma dependência nova, CSS puro no tema claro do portal (decisão da Fase 29:
Tailwind/Shadcn descartados — Spec 73).

1. **Sidebar de conversas** — cabeçalho "Chats" com o total de conversas, busca
   "Buscar...", e a lista de leads com avatar (iniciais), nome, `@username`/
   telefone/ID, bot 🤖, data/hora da última interação, badge de **não lida**
   (azul), **badge de status** e dot laranja no avatar quando o lead está em
   **atendimento manual** (handoff HUMAN — Spec 90). A conversa ativa ganha
   fundo azul-claro + **barra azul na borda direita** (seleção Telegram).
   Paginação "Anterior/Próxima" no rodapé (server-side, mesmo contrato).
2. **Painel direito (conversa ativa)** — cabeçalho com avatar, nome, `ID: xxx ·
   via @bot`, **Plano** e **Última interação** (Spec 119), badge de status,
   botões **Pausar/Retomar bot** (Spec 90) e **Som ligado/desligado** (beep +
   mute, Spec 84 C6), navegação `‹ pos/total ›` e **Fechar**. Histórico em
   bolhas por remetente (CLIENT branca à esquerda, ADMIN azul à direita com
   **✓/✓✓ de leitura**, BOT_AUTO âmbar), mídia e fundo com textura discreta.
   Rodapé de envio (Enter ou botão) — lead **BLOQUEADO** mostra o aviso "envio
   desabilitado" no lugar do input.
3. **Dados reais preservados** — cards de resumo e filtros (busca/bot/status/
   período) no topo; SSE em tempo real (mensagem do lead aberto vai para o
   painel; dos demais incrementa a não lida na lista); envio via REST; marcar
   lida ao abrir; handoff humano; abertura direta por `?lead={id}` (avatar do
   header, Spec 102).

## Contexto (por que agora)

As Specs 83/84/90 (`audited`) entregaram o CRM com **tabela de clientes +
modal de Live Chat**. O usuário pediu o layout de **central de atendimento
estilo Telegram** (lista de conversas + painel de mensagens com cabeçalho de
navegação, mídia, histórico e botões). A Fase 29 descartou Tailwind/Shadcn —
então o visual novo é **CSS puro** no tema claro existente, sem lib de ícones
(usamos texto/emoji simples). O componente original proposto usava Tailwind e
dados de demonstração fixos; esta spec adapta o layout para o **padrão do
projeto** (CSS puro + dados reais da API).

## Critérios de aceite

1. **Layout de 2 painéis** — sidebar de conversas (largura fixa ~300px, com
   scroll próprio) + painel direito flexível; classes novas `crm-inbox-*`/
   `crm-panel-*`/`crm-chat-item` que **não alteram** as classes compartilhadas
   `.crm-chat-header`/`.crm-chat-actions`/`.crm-chat-sub` usadas por Comunidade
   e Webhooks.
2. **Sidebar** — cabeçalho "Chats" + total; busca; item com avatar (iniciais),
   nome, `@username`/telefone/ID, bot, data da última interação, badge de não
   lida, badge de status e dot de handoff HUMAN; item ativo com barra azul;
   paginação server-side no rodapé.
3. **Painel** — cabeçalho com avatar, nome, `ID · via bot`, Plano e Última
   interação (Spec 119), badge de status, Pausar/Retomar bot, som, navegação
   `‹ pos/total ›` e Fechar; histórico em bolhas (CLIENT/ADMIN/BOT_AUTO) com
   mídia e ✓/✓✓ de leitura; rodapé de envio.
4. **Envio e bloqueio** — `POST /leads/{id}/messages` (Enter ou botão Enviar);
   lead BLOQUEADO → aviso "envio desabilitado" e **sem** botão Enviar.
5. **SSE correto** — mensagem do lead aberto → append no painel (+ beep se
   CLIENT e som ligado); mensagem de outro lead → incrementa a **não lida do
   lead certo** na lista (corrige o bug pré-existente que incrementava o lead
   aberto).
6. **Handoff e leitura** — Pausar/Retomar bot (`PUT /handoff`, Spec 90) com
   badge "Atendimento manual"; marcar lida ao abrir (`POST /read`) → não lida
   zera na lista.
7. **Entrada `?lead={id}`** — avatar do header (Spec 102) navega para
   `/crm?lead={id}` e abre a conversa no painel.
8. **CSS puro, zero contrato novo** — sem Tailwind/lib de ícones; sem mudança
   em nenhuma rota/contrato HTTP; tema claro do portal (Spec 73).
9. **Builds e E2E** — `npm run build` (tsc + vite) limpo no portal e no console
   (estáticos restaurados — ordem portal → console, achado da Spec 73); E2E
   `crm.spec.js`, `portal-shell-ui.spec.js` e `portal-leves.spec.js`
   atualizados para os novos seletores.

## Dependências

- **Spec 83** (CRM backend) — leads, mensagens, SSE, envio, read.
- **Spec 84** (painel UI) — página `/crm`, cards, filtros, Live Chat (base).
- **Spec 90** (handoff) — `PUT /leads/{id}/handoff` + badge "Atendimento manual".
- **Spec 73** (UX) — `useAsync`, `ErrorBanner`/`SuccessBanner`, `Spinner`,
  empty states, acessibilidade (role/aria).
- **Spec 46** (portal foundation) — SPA, `apiClient`, rotas.
- **Spec 102** (shell UI) — avatar do header navega `/crm?lead={id}`.
- **Spec 119** (leves) — campos Plano/Última interação no lead (exibidos no
  cabeçalho da conversa).

## Fora de escopo

- **Backend do CRM** — Specs 83/90 (audited); nenhum endpoint novo.
- **Botões inline interativos** — não existem no contrato `chat_messages`
  (são do Broadcast, Specs 79/80); o painel renderiza apenas o que a API
  entrega (texto/mídia).
- **Tema escuro / Tailwind / lib de ícones** — descartados na Fase 29.
- **Master Console** — nada muda.
- **Responsividade mobile** — evolução futura (decisão da Fase 29).
