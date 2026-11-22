---
name: Painel de gestão de bots (UI — console + portal)
status: audited
references:
  - "management/vision.md#V54"
  - "management/specs/74-registro-bots/requirements.md"
  - "management/specs/75-leads-metricas-bots/requirements.md"
  - "management/specs/71-master-console/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
  - "management/specs/46-portal-frontend/requirements.md"
---

# Spec 76 — Painel de gestão de bots (UI — console + portal)

## O que entrega

As telas que operam o registro central (Spec 74) e as métricas (Spec 75), nos
dois frontends existentes — padrão visual e de UX já estabelecidos (Specs
71/72/73 no console, 46 no portal):

1. **Portal do lojista — tela "Meus Bots"**: cards dos bots do lojista (foto,
   nome, @, status ONLINE/OFFLINE, total de leads e vendas — dados das Specs
   74/75), filtro por plataforma (Telegram/Discord), ações
   **pausar/reativar, editar, excluir** (com `ConfirmDialog` — Spec 73) e
   botão "Conectar bot" que abre o modal de conexão (ou aponta para os
   criadores, Specs 77/78).
2. **Master Console — tela "Bots"**: espelho **read-only** de todos os
   lojistas (RBAC-aware, Spec 69), com lojista, status e métricas; sem ações
   de escrita nesta spec.
3. **Modal de conexão**: seleção de plataforma + campo de token; validação
   em tempo real (chama a integração da Spec 74 — `getMe`/gateway) e feedback
   claro de sucesso/erro (padrão Spec 73).
4. **Modal/área de edição**: nome de exibição (display_name) e, quando a
   integração suportar, descrição/bio e comandos (Telegram via
   `setMyDescription`/`setMyCommands`; Discord via slash commands — chamadas
   da Spec 74).
5. **Estados uniformes**: loading (`Spinner`), empty state, erro com
   `ErrorBanner` e sucesso com `SuccessBanner` em toda ação de escrita —
   padrão consolidado na Spec 73.

O contrato HTTP é o das Specs 74/75 (CRUD + métricas). **Nenhuma mudança de
backend** nesta spec além do que já foi especificado; se algo faltar, é
aditivo mínimo documentado.

## Contexto (por que agora)

A Spec 74 criou o registro e a 75 as métricas — sem UI, o lojista não tem
como conectar/pausar/ver o desempenho dos bots, e o operador não tem a visão
geral. O sistema externo analisado tinha exatamente esse painel de cards;
aqui ele é construído sobre a camada segura (criptografia, RBAC, auditoria)
que as specs anteriores entregam.

## Critérios de aceite

1. **Tela "Meus Bots" no portal** (rota `/bots`, menu lateral): lista os bots
   do lojista em cards (foto/ícone da plataforma, `display_name`, `@username`,
   status ONLINE/OFFLINE/PAUSED/ERRO, leads e vendas — das métricas da Spec
   75); empty state claro quando não há bots; filtro por plataforma.
2. **Ações por card**: Pausar/Reativar (toggle com confirmação), Editar
   (modal), Excluir (ConfirmDialog `danger` — Spec 73) e "Conectar bot"
   (modal de conexão); toda ação de escrita com `SuccessBanner`/`ErrorBanner`
   e sem unhandled rejection (padrão Spec 73).
3. **Modal de conexão**: plataforma (Telegram/Discord) + token (+ Application
   ID no Discord); valida em tempo real via Spec 74 (integração real);
   sucesso → card novo aparece; erro → `ErrorBanner` com a mensagem do
   backend (ex.: token inválido).
4. **Modal de edição**: `display_name` sempre editável; descrição/bio e
   comandos quando a plataforma suporta (chamadas da Spec 74); feedback de
   sucesso/erro.
5. **Tela "Bots" no Master Console** (rota `/bots`, RBAC-aware Spec 69):
   espelho read-only com todos os lojistas (lojista, plataforma, status,
   leads/vendas), filtros (plataforma/status/lojista) e **nenhuma ação de
   escrita**.
6. **Métricas visíveis**: cada card mostra leads e vendas (e conversão quando
   disponível) vindas de `GET .../metrics` (Spec 75); carregamento com
   `Spinner` e sem blink (padrão Spec 73).
7. **Acessibilidade/navegação**: skip-link, foco nos modais (Spec 73),
   `aria-label` nos botões de ícone; mensagens anunciáveis.
8. **Testes**: E2E das duas telas (portal: conectar/pausar/editar/excluir com
   backend mockado; console: leitura + RBAC — sem ações); `npm run build`
   (tsc) limpo nos dois frontends; sem regressão na suíte E2E existente.

## Dependências

- **Spec 74** (registro) — CRUD/integração consumidos pela UI.
- **Spec 75** (métricas) — leads/vendas/conversão nos cards.
- **Specs 71/72/73** (console + UX) — padrão de telas, banners, diálogos.
- **Spec 46** (portal) — padrão do portal.
- **Specs 77/78** (criadores) — o botão "Conectar bot" pode apontar para os
  criadores (dependência opcional de navegação, não de contrato).

## Fora de escopo

- **Criadores de bot** (wizards passo a passo) → Specs 77/78.
- **Mudanças de backend** além das Specs 74/75 (qualquer gap é aditivo mínimo
  documentado no task).
- **Telefone/WhatsApp ou outras plataformas** — só Telegram/Discord nesta
  fase.
