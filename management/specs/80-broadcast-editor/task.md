---
name: Broadcast — editor e preview (UI portal)
status: audited
---

# Spec 80 — Task: Broadcast (editor e preview)

## F0 — Lista + estrutura (critérios 1, 9)

- [x] T0.1: `portal/src/pages/broadcasts/BroadcastsPage.tsx` + rota
      `/broadcasts` + item de menu — lista via `GET /api/v1/portal/broadcasts`
      (Spec 79): badges de modo/status, filtros, empty state (D2/D3).
- [x] T0.2: Ações Cancelar (`ConfirmDialog` `danger`, só PENDING/SCHEDULED →
      `DELETE`) e Duplicar (abre editor pré-preenchido); banners (Spec 73).
- [x] T0.3: `useAsync` sem blink (padrão Spec 73).

## F1 — Editor + preview (critérios 2–7)

- [x] T1.1: `BroadcastEditorPage` + rotas `/broadcasts/new` e
      `/broadcasts/:id/edit` — layout editor (esq.) + preview (dir.) (D2/D3).
- [x] T1.2: `ModeBar` — Agora/Agendada/Bem-vindos/Repost; campos condicionais
      (datetime-local futuro / intervalo 12h/24h/custom ≥1h).
- [x] T1.3: `BroadcastForm` — select de bot (`GET /api/v1/portal/bots`,
      Spec 74) → select de destino (chats via `GET
      /api/v1/portal/bots/{id}/chats`, recarrega ao trocar bot, Spec 79 D5).
- [x] T1.4: `RichTextToolbar` + `RichTextArea` — insere markup nativo por
      plataforma (Telegram HTML `b/i/u/s/code/pre/blockquote/tg-spoiler`;
      Discord Markdown `**`/`*`/`__`/`~~`/`` ` ``/``` ```) no cursor.
- [x] T1.5: `TagInserter` — `{primeiro_nome}`/`{username}`/`{data}` com dica.
- [x] T1.6: `MediaUploader` — validação (imagem ≤10MB; vídeo/áudio ≤50MB) →
      `POST /api/v1/portal/broadcasts/media` (Spec 79); preview; erro →
      `ErrorBanner`.
- [x] T1.7: `ButtonsGridEditor` — linhas × botões (Link URL / Callback);
      limites da Spec 79 (Telegram ≤100/≤8 por linha; Discord ≤5) com aviso;
      monta `buttons_json`.
- [x] T1.8: `BroadcastPreview` — bolha simulada Telegram (balão + botões
      inline) e Discord (mensagem + action rows); mídia; tags com exemplo;
      `aria-live` polida.

## F2 — Validação + fechamento (critérios 8, 10)

- [x] T2.1: `useBroadcastForm` — validação (bot, destino, conteúdo OU mídia
      por modo; scheduled_at > now; interval >= 3600); botão desabilitado até
      válido; mensagens específicas por campo.
- [x] T2.2: Envio → `POST /api/v1/portal/broadcasts` (Spec 79); sucesso →
      banner + navega para a lista; erro → `ErrorBanner`.
- [x] T2.3: E2E — seleção de bot carrega chats; markup correto por
      plataforma; preview reage a texto/mídia/botões; validação bloqueia
      envio inválido; modos mudam campos; cancelar com confirmação; duplicar
      pré-preenche.
- [x] T2.4: `npm run build` (tsc) limpo + suíte E2E existente sem regressão
      (ordem de build: portal antes do console).
- [x] T2.5: `/spec-verify 80` e depois `/spec-audit 80` (critérios 1–10).

## Implementação (2026-08-14 — `/spec-implement 80`)

F0→F2 concluídas (retomada pós-crash): `BroadcastsPage` (lista + filtros +
cancelar/duplicar), `BroadcastEditorPage` (`/broadcasts/new` + `/:id/edit`),
`ModeBar`, `RichTextToolbar`/`RichTextArea`, `TagInserter`, `MediaUploader`,
`ButtonsGridEditor`, `BroadcastPreview`, `useBroadcastForm` + `markup`/`types`.
`App.tsx` (rotas), `Layout.tsx` (menu "Transmissões"), `styles.css` (editor +
preview + bolha Telegram + mensagem Discord).

Validação: `npm run build` (tsc) limpo (portal + console) · `mvn test`
**1589/0/0** · E2E `broadcast-editor.spec.js` **3/3** (lista→editor→envio;
cancelar com confirmação; duplicar pré-preenche) · suíte E2E completa
**142/142** sem regressão.

**5 bugs REAIS corrigidos durante o E2E** (latentes — nem `mvn test` nem o E2E
da Spec 79 pegavam):

1. `RequestBodySizeFilter`/`RequestBodyInspectionFilter` (Spec 63/M-01)
   re-embrulhavam o corpo via `CachedBodyHttpServletRequest` (sem `getParts()`)
   → multipart do upload de mídia (Spec 79) falhava 400; e o teto de 1 MiB
   contradizia o contrato 10MB/50MB. Filtros agora pulam `multipart/form-data`
   (parse do container; limites via `spring.servlet.multipart.*` novo no
   `application.yml`).
2. Migration V54: `CHECK (media_type IN ('image','video','audio'))` (lowercase)
   vs enum `BroadcastMediaType` (`IMAGE`/`VIDEO`/`AUDIO` — `EnumType.STRING`,
   mesmo padrão do achado Spec 74) → 500 no create com mídia. Migration **V55**
   corrige a constraint.
3. `PortalSpaController` + `spaPublicPaths()` do `SecurityConfig` sem
   `/broadcasts`/`/broadcasts/*` → full reload devolvia 401 JSON em vez do
   index.html. Rotas adicionadas.
4. `playwright.config.js` com `extraHTTPHeaders: {'Content-Type':
   'application/json'}` global — vazava para o `fetch` do browser
   (MediaUploader) e impedia o boundary multipart → 400. Header global
   removido; `security.spec.js` ganhou headers explícitos nos posts com
   `data: JSON.stringify(...)`/Buffer.
5. `BroadcastPreview` mostrava `url` em vez do `label` nos botões link
   (Telegram/Discord exibem o rótulo).

CI: job e2e ganhou `NYX_BOTS_TELEGRAM_CDN_CHANNEL_ID` (mock sendPhoto no
upload do E2E).

Próximo: `/spec-audit 80`.

## Auditoria (2026-08-14 — `/spec-audit 80`)

**10/10 critérios OK, 1 achado corrigido, 0 abertos.**

Verificação (requisitos ↔ código ↔ design ↔ task, rastreabilidade 1:1):

- **C1 (lista/rota/menu)**: `App.tsx` rotas `/broadcasts`/`new`/`:id/edit`;
  `Layout.tsx` item "Transmissões"; `BroadcastsPage` com badges de
  modo/status, filtros, empty state + CTA, título do chat resolvido via
  `GET /portal/bots/{id}/chats` (cache 60s do backend — D5).
- **C2 (toolbar/markup por plataforma)**: `RichTextToolbar`/`markup.ts` com
  `FORMAT_TOKENS` por plataforma (Telegram HTML `b/i/u/s/code/pre/blockquote/
  tg-spoiler`; Discord Markdown `**/*/__/~~/`/``` ```` ```); plataforma segue
  o bot selecionado (trocar bot limpa o preview e ajusta o markup).
- **C3 (tags)**: `TagInserter` — `{primeiro_nome}`/`{username}`/`{data}` com
  `title` de dica; inseridas no cursor (`insertAtCursor`).
- **C4 (mídia)**: `MediaUploader` valida cliente (imagem ≤10MB; vídeo/áudio
  ≤50MB — espelho Spec 79/D7) + `POST /portal/broadcasts/media` (multipart);
  preview do anexo; erro → `ErrorBanner`; "mensagem OU mídia" validado no
  `useBroadcastForm`.
- **C5 (botões)**: `ButtonsGridEditor` linhas × botões, tipo Link (URL válida)
  ou Callback (`bot:`), limites por plataforma (Telegram ≤100/≤8 por linha;
  Discord ≤5) com aviso; `validateButtons` no form.
- **C6 (modos)**: `ModeBar` Agora/Agendada (datetime-local futuro)/
  Bem-vindos/Repost (12h/24h/custom ≥1h) com campos condicionais.
- **C7 (preview)**: `BroadcastPreview` bolha simulada Telegram (balão + botões
  inline) e Discord (mensagem + action rows); tags resolvidas com exemplo
  (João/@joao/data); mídia e botões no preview.
- **C8 (validação/envio)**: `useBroadcastForm` desabilita o botão até
  válido; `POST /api/v1/portal/broadcasts`; sucesso → banner + navega para a
  lista; erro → `ErrorBanner`.
- **C9 (cancelar/duplicar)**: Cancelar só em `PENDING` (SCHEDULED pendente é
  PENDING — o backend só permite DELETE em PENDING, 409 se PROCESSING;
  contrato Spec 79) com `ConfirmDialog` `tone=danger` (default do
  componente); Duplicar abre `/broadcasts/:id/edit` pré-preenchido
  (idempotência no cliente).
- **C10 (testes)**: E2E `broadcast-editor.spec.js` **3/3** (lista→editor→
  envio com mídia/botões; cancelar com confirmação; duplicar pré-preenche e
  não duplica); `npm run build` (tsc) limpo nos 2 frontends; suíte E2E
  completa **142/142** sem regressão; `mvn test` **1589/0/0**.

**1 achado corrigido (UX/acessibilidade — design D5 "aria-live polida — sem
flood")**: o `BroadcastPreview` tinha `aria-live="polite"` no `<section>`
inteiro (container que re-renderiza a cada tecla/upload/edição) — um leitor
de tela anunciaria o texto completo do preview a cada digitação (flood,
contradizendo D5). Corrigido: `aria-live` movido para o rodapé discreto
(`Modo: X` — muda raramente); o container mutável ficou sem live region.
Build + E2E re-executados após o fix (3/3 + 142/142).

Observação registrada (fora de escopo): a suíte completa passou com o race
pré-existente do teste 23 (Spec 48 — `updatedAt` da loja no mesmo
microssegundo) documentado na auditoria da Spec 71 — não relacionado a esta
spec.

Fim do ciclo SDD (roadmap/requirements/design/task `audited`).
