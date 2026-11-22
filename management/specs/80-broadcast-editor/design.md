---
name: Broadcast — editor e preview (UI portal)
status: audited
---

# Spec 80 — Design: Broadcast (editor e preview)

## D1 — Conceito

Camada **puramente de apresentação** sobre os endpoints da Spec 79 (e Spec
74 p/ bots/chats). O editor é **client-side**: o markup e o preview são
montados no navegador; o servidor valida no `POST` (Spec 79 D8). Nenhuma rota
de backend nova.

### D1.1 Decisões do usuário (2026-08-12) → regras

| Decisão | Regra |
|---|---|
| Preview em tempo real | bolha simulada (Telegram/Discord) atualizada a cada mudança |
| Plataforma segue o bot | o destino (chats) e o markup/preview dependem do bot selecionado |
| Validação no cliente + servidor | botão desabilitado até válido; backend continua a autoridade |
| Sem templates | editor único; duplicar pré-preenche (sem biblioteca de modelos nesta fase) |

## D2 — Páginas e rotas (portal)

- `/broadcasts` — `BroadcastsPage` (lista).
- `/broadcasts/new` — `BroadcastEditorPage` (editor + preview).
- `/broadcasts/:id/edit` — editor pré-preenchido (duplicar/editar rascunho).

Dados:
- `GET /api/v1/portal/bots` (Spec 74) → select de bot.
- `GET /api/v1/portal/bots/{id}/chats` (Spec 79 D5) → select de destino
  (recarrega ao trocar o bot).
- `GET /api/v1/portal/broadcasts` (Spec 79) → lista.
- `POST /api/v1/portal/broadcasts` (Spec 79) → criar.
- `POST /api/v1/portal/broadcasts/media` (Spec 79) → upload.
- `DELETE /api/v1/portal/broadcasts/{id}` (Spec 79) → cancelar.

## D3 — Componentes

- `BroadcastsPage` — lista com badges de modo/status, filtros, empty state,
  ações Cancelar (`ConfirmDialog` `danger`, só PENDING/SCHEDULED) e Duplicar.
- `BroadcastEditorPage` — layout 2 colunas: editor (esquerda) + preview
  (direita); barra de modos no topo.
- `ModeBar` — Agora/Agendada/Bem-vindos/Repost; campos condicionais
  (datetime-local / intervalo select 12h/24h/custom ≥1h).
- `BroadcastForm` — select de bot → select de destino (chats, carregado ao
  trocar bot), `Field` com `required`.
- `RichTextToolbar` + `RichTextArea` — insere markup nativo da plataforma
  (Telegram: HTML `b/i/u/s/code/pre/blockquote/tg-spoiler`; Discord:
  `**`/`*`/`__`/`~~`/`` ` ``/``` ```) na posição do cursor; estado do editor
  como texto puro + markup (sem lib de rich-text — CSS puro, padrão Spec 73).
- `TagInserter` — botões `{primeiro_nome}`/`{username}`/`{data}` com
  `title` de dica.
- `MediaUploader` — input file + validação (imagem ≤10MB; vídeo/áudio ≤50MB)
  → `POST .../media` → `{media_url, media_file_id, media_type}`; preview do
  anexo; erro → `ErrorBanner`.
- `ButtonsGridEditor` — linhas × botões; tipo Link (URL) ou Callback;
  limites da plataforma (Telegram ≤100/≤8 por linha; Discord ≤5 por linha)
  com aviso; monta o `buttons_json` da Spec 79.
- `BroadcastPreview` — bolha simulada:
  - Telegram: balão com texto (markup HTML/Markdown renderizado), mídia
    (thumbnail/vídeo/áudio placeholder), botões inline em grade.
  - Discord: mensagem estilo embed/DM com autor (username do bot), mídia,
    botões (action row).
  - Tags resolvidas com exemplo (`João`, `@joao`, data atual).
- `useBroadcastForm` (hook) — estado do formulário + validação
  (`bot`, `destino`, `conteudo OU mídia` por modo; `scheduled_at > now`;
  `interval >= 3600`), habilita/desabilita o botão de envio.

## D4 — Validação e envio

- O botão "Enviar"/"Agendar" fica **desabilitado** até válido (padrão do PRD
  externo); mensagem de validação específica por campo (`Field` da Spec 73).
- Envio: `POST /api/v1/portal/broadcasts` com
  `{botId, mode, targetChatId, content, mediaUrl?, mediaFileId?, mediaType?,
  buttons?, scheduledAt?, intervalSeconds?}` (contrato da Spec 79).
- Sucesso → `SuccessBanner` + navega para `/broadcasts`; erro →
  `ErrorBanner` (mensagem do backend).

## D5 — UX (padrão Spec 73)

- Banners anunciáveis; `Spinner` no carregamento de bots/chats; empty state
  na lista; modais (Cancelar) acessíveis; preview com `aria-live` polida
  (anúncio discreto ao atualizar — sem flood).

## D6 — Testes

- **E2E**: seleção de bot carrega chats; formatação insere markup correto por
  plataforma; preview reage a texto/mídia/botões; validação bloqueia envio
  inválido (sem bot/sem destino/sem conteúdo e mídia); modos mudam campos;
  cancelar com confirmação; duplicar pré-preenche.
- Validação de tamanho de mídia (cliente) sem rede.
- `npm run build` (tsc) limpo; suíte E2E existente sem regressão (ordem de
  build: portal antes do console — achado da Spec 73).
