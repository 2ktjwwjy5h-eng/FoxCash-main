---
name: Broadcast — editor e preview (UI portal)
status: audited
references:
  - "management/vision.md#V57"
  - "management/specs/79-broadcast-motor/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
  - "management/specs/46-portal-frontend/requirements.md"
---

# Spec 80 — Broadcast: editor e preview (UI portal)

## O que entrega

A tela do portal onde o lojista **compõe e agenda transmissões** com preview
em tempo real — sobre os endpoints da Spec 79. Padrão UX da Spec 73 (banners,
`Field`, `ConfirmDialog`, `Spinner`, empty states), sem backend novo além do
que a Spec 79 define.

1. **Editor de mensagem rich-text**: toolbar de formatação (negrito, itálico,
   sublinhado, riscado, código inline/bloco, citação, spoiler) que insere o
   markup nativo da plataforma selecionada (Telegram HTML/Markdown; Discord
   Markdown) — sem HTML arbitrário (a Spec 79 valida no servidor).
2. **Tags dinâmicas**: inserção de `{primeiro_nome}`, `{username}`, `{data}`
   com dica do que cada uma resolve.
3. **Mídia**: upload de imagem/vídeo/áudio (limites da Spec 79: 10MB/50MB) com
   preview do anexo; o upload usa `POST /api/v1/portal/broadcasts/media`.
4. **Botões inline**: editor de grade (linhas × botões) com tipo **Link**
   (URL — checkout/suporte/grupo) ou **Callback** (ação interna), validação de
   limites por plataforma (Spec 79 D8).
5. **Barra de modos**: `Enviar agora` / `Agendada` (data-hora) / `Bem-vindos`
   (sem data — evento de entrada) / `Repost` (intervalo 12h/24h/custom ≥1h);
   o formulário muda conforme o modo.
6. **Preview em tempo real**: bolha simulada da plataforma (Telegram/Discord)
   renderizando texto formatado, mídia e botões exatamente como o envio.
7. **Validação de formulário**: botão "Enviar/Agendar" desabilitado até
   bot + destino selecionados e (mensagem OU mídia) preenchidos — padrão do
   PRD externo; mensagens de erro claras.
8. **Lista de broadcasts**: tela com os envios (modo, destino, status,
   data), ações de **cancelar** (PENDING/SCHEDULED com `ConfirmDialog`) e
   **duplicar** (abre o editor pré-preenchido) — padrão Spec 73.

## Contexto (por que agora)

A Spec 79 entrega o motor; sem o editor, o lojista não tem como compor uma
transmissão com formatação/mídia/botões nem ver o resultado antes de
disparar — o grande diferencial de UX do módulo do irmão. Decisão do usuário
(2026-08-12): editor com **preview em tempo real**, um dos requisitos
centrais do PRD externo.

## Critérios de aceite

1. **Tela "Transmissões" no portal** (rota `/broadcasts`, menu lateral):
   lista com modo, destino (título do chat), status (badge), data; empty
   state com CTA "Nova transmissão".
2. **Editor com toolbar**: inserção de markup da plataforma selecionada
   (negrito/itálico/sublinhado/riscado/código/citação/spoiler); alternância
   de plataforma limpa o preview e ajusta o markup (o destino determina a
   plataforma do bot selecionado).
3. **Tags dinâmicas**: botão de inserir `{primeiro_nome}`/`{username}`/
   `{data}` com dica de resolução.
4. **Upload de mídia**: imagem ≤10MB, vídeo/áudio ≤50MB (validação no
   cliente espelhando a Spec 79); preview do anexo; erro de upload →
   `ErrorBanner`; "mensagem OU mídia pelo menos um" validado.
5. **Editor de botões**: adicionar/remover linhas e botões; tipo Link
   (URL obrigatória válida) ou Callback; limites da Spec 79 (Telegram ≤100
   botões/≤8 por linha; Discord ≤5 por linha) com aviso ao exceder.
6. **Modos**: barra com Agora/Agendada (datetime-local, valida futuro)/
   Bem-vindos/Repost (select de intervalo 12h/24h/custom ≥1h); campos
   condicionais por modo.
7. **Preview em tempo real**: bolha simulada (Telegram: balão + botões
   inline; Discord: embed/mensagem + botões) atualizada a cada digitação/
   upload/edição de botões; tags mostradas como exemplo (`João`, `@joao`,
   data atual) no preview.
8. **Validação do envio**: botão desabilitado até (bot, destino, conteúdo
   ou mídia) válidos; ao enviar → `POST /api/v1/portal/broadcasts`; sucesso →
   banner + volta para a lista; erro → `ErrorBanner` (ex.: `invalid_target`,
   validação de botões).
9. **Cancelar/duplicar**: cancelar PENDING/SCHEDULED com `ConfirmDialog`
   `danger`; duplicar abre o editor pré-preenchido (idempotência no cliente
   — não duplica envios sem ação do usuário).
10. **Testes**: E2E do editor (preview reage a texto/mídia/botões; validação
    bloqueia envio inválido; modos mudam campos; cancelar com confirmação);
    `npm run build` (tsc) limpo; sem regressão na suíte E2E existente.

## Dependências

- **Spec 79** (motor) — endpoints consumidos (create, media, chats, lista).
- **Spec 74** (registro) — `GET /api/v1/portal/bots` (seleção de bot) e
  `GET /api/v1/portal/bots/{id}/chats` (destino).
- **Spec 73** (UX) — banners, modais, `Field`, `Spinner`.
- **Spec 46** (portal) — padrão do frontend.

## Fora de escopo

- **Motor/disparo** → Spec 79.
- **Editor no Master Console** — o broadcast é do lojista (portal); o console
  não ganha tela nesta fase.
- **Modelos/templates reutilizáveis** (biblioteca de mensagens) — evolução.
