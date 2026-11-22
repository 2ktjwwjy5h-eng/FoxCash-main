---
name: Escopo do perfil de operação (Geral/Bot-Only/Gateway-Only) + lente de contexto Bot/Gateway no portal
status: audited
references:
  - "management/vision.md#V99"
  - "management/specs/123-operation-profile-ui/requirements.md"
  - "management/specs/122-operation-profile-backend/requirements.md"
  - "management/specs/124-profile-data-scope/requirements.md"
  - "management/adr.md"
---

# Spec 139 — Escopo do perfil de operação + lente Bot/Gateway

## O que entrega

O perfil de operação deixa de ser "tamanho único": ele **nasce com um escopo
definido** — **⚡ Completo**, **🤖 Somente Bot** ou **🌐 Somente Gateway** — e o
portal passa a vestir a operação exatamente como o lojista precisa. Um **seletor
de contexto de lente** (Bot | Gateway) no shell aparece **antes** do seletor de
perfil, e cada lente expõe **apenas os perfis do escopo relacionado**. O
"criar perfil" é um **modal limpo** (nome + Tipo de Operação; rota e frota
atribuídas depois no painel da lente), a rota de
cobrança ganha **nome amigável**, a troca de perfil dá **feedback visual** e
passa a existir endpoint para **atribuir bots a um perfil**. Sem quebrar o
modelo auditado das Specs 122/123/124: o perfil continua UMA entidade (rota +
frota + escopo); o que muda é o campo `scope`, a lente como estado de UI e o
endpoint de frota.

1. **Escopo do perfil (modelo)** — `merchant_profiles.scope`, com **rótulo de
   UI** correspondente: `GENERAL` → "⚡ Completo (Bot + Gateway integrados)"
   (rota + frota de bots), `BOT_ONLY` → "🤖 Somente Bot (foco em chat e
   automações)" (frota de bots; rota sempre `null`/PADRAO — roteamento por
   risco), `GATEWAY_ONLY` → "🌐 Somente Gateway (foco em páginas web e
   tráfego)" (rota de gateway; frota vazia). Migration **V76** (livre; V75 é a
   última): coluna `scope` `NOT NULL DEFAULT 'GENERAL'` + CHECK `scope IN
   ('GENERAL','BOT_ONLY','GATEWAY_ONLY')`; backfill dos perfis existentes →
   `GENERAL`.
2. **Lente de contexto (UI)** — `ProfileContext` ganha `lens: 'BOT' |
   'GATEWAY'`, persistido em `localStorage` (`foxcash.portal.lens`). Perfis
   visíveis da lente: **Bot** → `GENERAL` + `BOT_ONLY`; **Gateway** →
   `GENERAL` + `GATEWAY_ONLY`. Trocar a lente valida o perfil ativo (fora da
   lente → primeiro visível). O seletor vira **2 níveis**: contexto (Bot |
   Gateway) primeiro, perfil depois.
3. **Criar perfil (modal limpo)** — `POST /api/v1/portal/profile` passa a
   aceitar `scope` (default `GENERAL`); a UI pede **nome + Tipo de Operação**
   (radio com os 3 rótulos do item 1 — "🤖 Somente Bot" / "🌐 Somente
   Gateway" / "⚡ Completo"), **sem campos condicionais** no modal: rota e
   frota de bots são atribuídas **depois**, no painel do contexto da lente
   (PATCH rota na lente Gateway; "gerenciar bots"/PUT na lente Bot).
   Validações: escopo inválido → 400 `invalid_scope`; `BOT_ONLY` com rota →
   400 `route_not_allowed_for_scope`.
4. **Atribuir bots ao perfil (endpoint novo)** —
   `PUT /api/v1/portal/profile/{id}/bots {botIds: [uuid...]}` — **substituição
   total** da frota do perfil (lista vazia = esvazia). Valida pertinência (bot
   do lojista, não-deletado → 400 `bot_not_found`); `GATEWAY_ONLY` → 400
   `bots_not_allowed_for_scope`. Auditoria += `PROFILE_BOTS_CHANGED` (Spec 41).
5. **Mudança de escopo (PATCH)** — `PATCH /api/v1/portal/profile/{id}` aceita
   `scope`: `BOT_ONLY` → rota forçada a `null` (400 `route_not_allowed_for_scope`
   se enviar rota junto); `GATEWAY_ONLY` → frota **desanexada** (bots voltam ao
   default do lojista — `store_bots.profile_id = NULL`) na mesma transação.
6. **Resolver com defesa por escopo** — `RoutingPolicyResolver.resolveProviders(
   merchantId, profileId)`: perfil `BOT_ONLY` **nunca** aplica override (rota é
   sempre por risco), mesmo com rota legada/stale no banco. `resolvePolicyName`
   idem (`"override"` só para GENERAL/GATEWAY_ONLY com rota válida).
7. **Nomes amigáveis de rota (UI)** — mapa `ROUTE_LABEL` no portal: `PADRAO` →
   "Padrão (por risco)", `pagarme` → "Pagar.me", `asaas` → "Asaas", `mock` →
   "Mock (teste)", `stripe` → "Stripe". A API continua devolvendo chaves; o
   rótulo é exibição (fallback: chave em caixa alta).
8. **Feedback visual na troca de perfil** — banner transitório (auto-dismiss
   ~3s, `aria-live="polite"`) ao trocar: "Perfil ativo: <nome> · <label do
   escopo>". Estados loading/saving/erro no padrão Spec 73.
9. **Escopo-aware em telas existentes** — `SecurityPage` mostra o escopo do
   perfil ativo e só expõe a troca de rota quando `scope != BOT_ONLY`;
   `BotsPage` mantém o destaque do perfil ativo (fase 1 não esconde nada).
10. **Dica flutuante (tooltip) no dropdown de perfis** — ao passar o mouse
    (hover) **ou focar** (teclado) uma opção do dropdown de perfis no
    `ProfileSelector`, o lojista enxerga o **resumo do que o perfil carrega**:
    label do escopo (`SCOPE_LABEL`), bots vinculados (avatar/`displayName`,
    ou "sem bots na frota") e a rota de gateway em **nome amigável**
    (`ROUTE_LABEL`; `BOT_ONLY` → "rota por risco"). Acessível
    (`aria-describedby` ligando a opção ao conteúdo da dica) e **sem mudança
    de contrato de API** (dados já existentes em `OperationProfile`).
11. **Fases futuras registradas** — o escopo enriquece o filtro rígido da Spec
    124: perfis `BOT_ONLY` escopam módulos de bot; `GATEWAY_ONLY` os de
    cobrança/financeiro. Fora do escopo desta spec.

## Contexto (por que agora)

O usuário, ao olhar o frontend (2026-08-16), achou o seletor de perfil e a rota
de cobrança "solto" e incompreensível. Diagnóstico (código real):
`ProfileSelector` troca o perfil sem **nenhuma mudança visível** (fase 1 não
esconde dados — Spec 124 é a fase 2); o dropdown de rota **só aparece** quando
`availableRoutes.length > 1` e mostra **chaves internas** (`PADRAO`, `pagarme`,
`asaas`, `mock` — `ProviderCatalog.availableRoutes()`); a frota de bots do
perfil não aparece no seletor (só como tag no `BotsPage`); e **não existe
endpoint para atribuir bots a um perfil** (a Spec 122 deixou a associação de
bots para "fase 2"). O usuário decidiu: o perfil nasce com escopo
(Geral/Bot-Only/Gateway-Only) e o painel vira lente baseada nisso — o lojista
escolhe bot ou gateway e vê os perfis relacionados. A decisão **preserva** o
modelo auditado (ADR-019: perfil = rota + frota; ADR-020: Bot × Gateway são
superfícies sobre UM backend) — o escopo é uma **classificação** do perfil, a
lente é um **filtro de UI** e a rota continua vindo do banco (fonte da verdade).

## Critérios de aceite

1. **Migration V76** — `merchant_profiles.scope` (`NOT NULL DEFAULT 'GENERAL'`,
   CHECK com os 3 valores); backfill `GENERAL`; `store_bots` inalterada. Migração
   idempotente e testada.
2. **GET /api/v1/portal/profile** — `ProfileResponse` ganha `scope`; demais
   campos intactos (`gatewayRoute`, `availableRoutes`, `bots`, `activeProfileId`).
3. **POST /api/v1/portal/profile** — `{ name, gatewayRoute?, scope? }`; escopo
   inválido → 400 `invalid_scope`; `BOT_ONLY` + rota → 400
   `route_not_allowed_for_scope`; default `GENERAL`; auditoria `PROFILE_CREATED`.
4. **PATCH /api/v1/portal/profile/{id}** — aceita `scope`; `BOT_ONLY` força rota
   a null (ou 400 se rota enviada junto); `GATEWAY_ONLY` desanexa a frota
   (transação); escopo inválido → 400 `invalid_scope`; auditoria
   `PROFILE_UPDATED`/`GATEWAY_ROUTE_CHANGED`.
5. **PUT /api/v1/portal/profile/{id}/bots** — `{ botIds }`; substitui a frota
   (atribui `store_bots.profile_id`); lista vazia esvazia; bot de outro lojista
   ou deletado → 400 `bot_not_found`; `GATEWAY_ONLY` → 400
   `bots_not_allowed_for_scope`; auditoria `PROFILE_BOTS_CHANGED`; 200 + resposta.
6. **Resolver** — `resolveProviders(merchantId, profileId)` com perfil
   `BOT_ONLY` (mesmo com rota stale) → política por risco (nunca override);
   `resolvePolicyName` retorna `"override"` apenas para GENERAL/GATEWAY_ONLY com
   rota válida no catálogo; regressão Spec 91 intacta.
7. **Lente no frontend** — `ProfileContext` com `lens` + `setLens` persistido em
   `localStorage`; `visibleProfiles` derivado por escopo; trocar a lente ajusta
   o perfil ativo se estiver fora dela; `X-Profile-ID` continua saindo do perfil
   ativo (inalterado).
8. **Seletor 2 níveis** — contexto (Bot | Gateway) antes do seletor de perfil;
   perfil exibe `name` + label do escopo; painel do contexto: lente Bot → frota
   (chips com avatar/displayName/status) + "gerenciar bots" (modal de
   atribuição → PUT); lente Gateway → rota com `ROUTE_LABEL` + texto de ajuda.
9. **Criar perfil (modal limpo)** — dialog "Criar Novo Perfil": nome +
   **Tipo de Operação** (radio: 🤖 Somente Bot / 🌐 Somente Gateway / ⚡
   Completo, cada um com descrição de uma linha) → `POST {name, scope}`;
   sem campos condicionais (rota/frota atribuídas depois no painel da lente);
   erros → `ErrorBanner` (padrão Spec 73).
10. **Feedback visual** — banner transitório na troca de perfil (nome + escopo,
    auto-dismiss, acessível); estados saving desabilitam os controles.
11. **Tooltip no dropdown de perfis** — hover/foco numa opção exibe o resumo do
    perfil (escopo + bots + rota amigável, com estados vazios e `BOT_ONLY` →
    "rota por risco"); acessível (`aria-describedby`); dados vêm de
    `OperationProfile` (sem chamada extra de API).
12. **Regressão** — Specs 122/123/124 intactas (perfil com 1 só escopo → seletor
    simples funciona); `mvn test` verde (service/controller/resolver novos);
    `npm run build` do portal verde; E2E do portal (quando infra de teste
    existir — padrão Spec 123 T4) com o novo seletor.

## Dependências

- **Spec 122** (backend) — entidade `merchant_profiles`, endpoints
  `GET/POST/PATCH /api/v1/portal/profile`, header `X-Profile-ID`, catálogo de
  rotas (`ProviderCatalog`), auditoria `PROFILE_*` (Spec 41).
- **Spec 123** (UI) — `ProfileContext`/`ProfileSelector`/`apiClient` (header),
  `SecurityPage`, `BotsPage`.
- **Spec 124** (fase 2) — filtro rígido por `profile_id`; o escopo enriquece
  futuramente (registrado, fora do escopo).
- **Spec 74** (bots) — `StoreBot.assignProfile`/`store_bots.profile_id`;
  `StoreBotManagementUseCase.list`.
- **Spec 91** (router) — `RoutingPolicyResolver`/`PixProviderRouter`
  (defesa por escopo é branch no resolver).
- **Spec 102** (shell) — `Layout.tsx` (ponto do seletor 2 níveis).
- **Spec 73** (UX) — `Card`/`ErrorBanner`/`Field`/`Spinner`/`Dialog`/`useAsync`.

## Fora de escopo

- **Filtro rígido por escopo nos módulos** — a lente NÃO esconde dados nesta
  spec (mesmo contrato da fase 1: nada é ocultado); o escopo enriquece a Spec
  124 futuramente.
- **Adapter Stripe/Global** — catálogo segue os adquirentes configurados; a
  lente e o escopo funcionam com 1 ou N adquirentes.
- **Master console** — não gerencia escopo/perfis nesta spec.
- **Header como fonte de decisão de rota** — mantido: nunca.
- **Payout** — `PayoutProviderRouter` inalterado (só cobrança).
