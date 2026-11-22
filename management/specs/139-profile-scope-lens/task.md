---
name: Escopo do perfil de operação (Geral/Bot-Only/Gateway-Only) + lente de contexto Bot/Gateway no portal
status: audited
---

# Spec 139 — Task: Escopo do perfil + lente Bot/Gateway

Ordem: T0 (migration/domínio) → T1 (serviço/endpoints) → T2 (resolver) → T3
(portal: contexto/lente) → T4 (seletor 2 níveis) → T5 (telas escopo-aware) → T6
(testes).

## T0 — Migration V76 + domínio (critérios 1, 2)

- [x] T0.1: `src/main/resources/db/migration/V76__operation_profile_scope.sql` —
      `ALTER TABLE merchant_profiles ADD COLUMN scope VARCHAR(20) NOT NULL
      DEFAULT 'GENERAL'` + CHECK `scope IN ('GENERAL','BOT_ONLY','GATEWAY_ONLY')`
      (backfill implícito pelo DEFAULT; idempotente).
- [x] T0.2: `ProfileScope` (enum: `GENERAL`, `BOT_ONLY`, `GATEWAY_ONLY`) +
      campo `scope` em `MerchantProfile` (default `GENERAL` no restore).
- [x] T0.3: `ProfileResponse` ganha `scope` (GET /api/v1/portal/profile);
      demais campos intactos.

## T1 — Serviço e endpoints (critérios 3, 4, 5)

- [x] T1.1: `OperationProfileService.create` aceita `scope` (default
      `GENERAL`); escopo inválido → 400 `invalid_scope`; `BOT_ONLY` + rota →
      400 `route_not_allowed_for_scope`; auditoria `PROFILE_CREATED` preservada.
- [x] T1.2: `OperationProfileService.update` aceita `scope`: `BOT_ONLY` força
      rota a null (rota junto → 400 `route_not_allowed_for_scope`);
      `GATEWAY_ONLY` desanexa a frota na mesma transação
      (`store_bots.profile_id = NULL`); auditoria preservada.
- [x] T1.3: `replaceBots` (novo, `PUT /api/v1/portal/profile/{id}/bots
      {botIds}`): substituição total; lista vazia esvazia; bot de outro
      lojista/deletado → 400 `bot_not_found`; `GATEWAY_ONLY` → 400
      `bots_not_allowed_for_scope`; auditoria `PROFILE_BOTS_CHANGED` (Spec 41);
      retorna `ProfileResponse` atualizado.
- [x] T1.4: Controller/DTO da Spec 122 — expor `scope` no request/response e a
      rota `PUT /{id}/bots`.

## T2 — Resolver com defesa por escopo (critério 6)

- [x] T2.1: `RoutingPolicyResolver.resolveProviders(merchantId, profileId)` —
      perfil `BOT_ONLY` nunca aplica override (mesmo com rota stale no banco);
      GENERAL/GATEWAY_ONLY inalterados.
- [x] T2.2: `resolvePolicyName` — `"override"` só para GENERAL/GATEWAY_ONLY com
      rota válida; `BOT_ONLY` → política por risco.
- [x] T2.3: Regressão Spec 91 (`PixProviderRouter` inalterado) — suíte do router
      verde.

## T3 — Portal: `ProfileContext` com lente (critérios 7)

- [x] T3.1: `profile.tsx` — `Lens`, `ProfileScope`, `OperationProfile.scope`,
      `lens`/`setLens` (localStorage `foxcash.portal.lens`, `LENS_KEY`),
      `visibleProfiles` (memo), `createProfile({name, scope, gatewayRoute?})`,
      `assignBots` (PUT), `ROUTE_LABEL`/`routeLabel`/`SCOPE_LABEL`.
- [x] T3.2: `setLens` valida o perfil ativo (fora da lente → primeiro visível +
      reescreve `foxcash.portal.profile`); sanidade no load (lens inválida →
      `'BOT'`).
- [x] T3.3: `apiClient.ts` inalterado (header `X-Profile-ID` segue saindo do
      perfil ativo).

## T4 — Seletor 2 níveis no HeaderGlobal (critérios 8, 9)

- [x] T4.1: `ProfileSelector.tsx` — nível 1: segmented Bot | Gateway
      (`aria-pressed`); nível 2: menu customizado (`button` + `listbox` de
      `visibleProfiles`, `name` + label do escopo; navegação ↑/↓/Enter/Esc —
      ARIA combobox/menu); painel do contexto (frota chips + "gerenciar bots"
      na lente Bot; `ROUTE_LABEL` + ajuda + dropdown de rota na lente Gateway).
- [x] T4.2: Dialog "Criar Novo Perfil" (modal limpo) — nome + **Tipo de
      Operação** (radio: 🤖 Somente Bot / 🌐 Somente Gateway / ⚡ Completo,
      com descrição); **sem campos condicionais** (rota/frota atribuídas
      depois no painel da lente); `POST {name, scope}`; erros → `ErrorBanner`.
- [x] T4.3: Estados loading/saving/erro (padrão Spec 73); acessibilidade
      (labels/aria-live).
- [x] T4.4: **Tooltip de resumo do perfil** (D5.2) — hover/foco numa `option`
      exibe escopo (`SCOPE_LABEL`) + bots (avatar/`displayName`, vazio →
      "sem bots na frota") + rota (`ROUTE_LABEL`; `BOT_ONLY` → "rota por
      risco"); `aria-describedby` ligando a opção ao tooltip; dados de
      `OperationProfile` (sem chamada extra de API); implementação local ao
      `ProfileSelector` (sem componente genérico).

## T5 — Feedback visual + telas escopo-aware (critérios 8, 10)

- [x] T5.1: Banner transitório na troca de perfil ("Perfil ativo: <nome> ·
      <SCOPE_LABEL>", `aria-live="polite"`, auto-dismiss ~3s); banner na troca
      de lente quando o perfil ativo muda.
- [x] T5.2: `SecurityPage.tsx` — escopo do perfil ativo (read); dropdown de rota
      só quando `scope != 'BOT_ONLY'`.
- [x] T5.3: `BotsPage.tsx`/`BroadcastEditorPage.tsx` — revisão (badge do perfil
      ativo mantido; `GATEWAY_ONLY` → pré-seleção de bot vazia).

## T6 — Testes (critérios 1–6, 11)

- [x] T6.1: JUnit — migration V76 (default + CHECK); create/update com escopo
      (erros 400); `replaceBots` (substituição, vazio, `bot_not_found`,
      GATEWAY_ONLY, auditoria); resolver BOT_ONLY com rota stale → por risco.
- [x] T6.2: `mvn test` verde (regressão Spec 91/122/123/124 intactas); `npm run
      build` do portal verde.
- [x] T6.3: E2E (quando infra de teste existir, padrão Spec 123 T4) — lente →
      criar perfil escopado → trocar lente (filtro) → trocar perfil (banner) →
      reload mantém lente + perfil. Spec criado (`e2e-tests/tests/profile-scope-lens.spec.js`);
      **execução CONCLUÍDA 2026-08-16** — ver §E2E abaixo.

**Âncoras validadas na spec:** `merchant_profiles` (Spec 122) + `store_bots`
(Spec 74); `OperationProfileService`/`MerchantProfile.gatewayRoute`;
`RoutingPolicyResolver`/`PixProviderRouter` (Spec 91); `profile.tsx`
(`gatewayRoute`/`availableRoutes`/`setGatewayRoute`/`createProfile` — Spec 123);
`ProfileSelector.tsx`/`Layout.tsx` (HeaderGlobal); `SecurityPage.tsx`/
`BotsPage.tsx`/`BroadcastEditorPage.tsx`; migrações até **V75** (V76 livre);
auditoria `PROFILE_*` (Spec 41); padrões UI Spec 73.

## Implementação — backend (T0.2 → T2 + T6.1/T6.2), 2026-08-16

Incremento de **backend** da Spec 139 (a migration V76 já havia sido criada na
sessão anterior — T0.1). `mvn test` **2038/0/0** (suíte completa; +14 testes:
service escopo/frota, controller PUT /bots, resolver BOT_ONLY) — regressão Specs
91/122/123/124 intacta. `ExceptionMappingContractTest` ganhou as 3 exceções novas.

**Desvios deliberados (registrados):**
1. **`MerchantProfile.withScope(ProfileScope)` (design D2.1) → implementado como
   `changeScope(ProfileScope, Instant)`** — consistente com o padrão do aggregate
   (`changeGatewayRoute(route, now)`); o `Instant` atualiza `updatedAt` (demais
   mutações do aggregate fazem o mesmo).
2. **`route_changed_at` (design D3.1, update BOT_ONLY)** — a coluna **não existe**
   no código real (`merchant_profiles` tem `updated_at`); o design citava uma
   coluna de outra espec. A limpeza de rota é coberta pelo `changeGatewayRoute(null)`
   + `updated_at`.
3. **Bots desanexados (`profile_id = NULL`) voltam ao default do lojista** — ao
   esvaziar a frota de um perfil, os bots desanexados passam a pertencer
   **logicamente** ao perfil default (regra auditada da Spec 122/124 —
   includeUnassigned). Efeito prático: **esvaziar a frota do perfil DEFAULT não
   remove os bots da visão do default** (eles voltam por NULL). O endpoint faz o
   esperado no banco (NULL); a exibição segue a regra da Spec 124 (fora do escopo
   desta spec). Coberto nos testes com perfil não-default.
4. **Rate limit** — `/api/v1/portal/profile` já está no `VARIABLE_PREFIXES`
   (SEC-23); o novo `PUT /{id}/bots` herda a cobertura. Sem mudança.

**Pendente (próximo incremento):** T3 (ProfileContext com lente), T4 (seletor 2
níveis + tooltip), T5 (telas escopo-aware + feedback visual) e T6.3 (E2E do
portal) — frontend.

## Implementação — frontend (T3 → T5 + T6.3 spec), 2026-08-16

Incremento de **frontend do portal** (T3–T5 concluídas; T6.3 — spec E2E criado).

- **T3 — `profile.tsx`**: `Lens`/`ProfileScope`, `OperationProfile.scope`,
  `lens`/`setLens` (localStorage `foxcash.portal.lens`, `LENS_KEY`, sanidade no
  load → `'BOT'`), `visibleProfiles` (derivado: Bot → GENERAL+BOT_ONLY; Gateway →
  GENERAL+GATEWAY_ONLY), `setLens` ajusta o perfil ativo fora da lente (reescreve
  `foxcash.portal.profile`), `createProfile({name, scope})` (POST), `assignBots`
  (PUT /{id}/bots), `ROUTE_LABEL`/`routeLabel`/`SCOPE_LABEL`. `apiClient.ts`
  inalterado (T3.3).
- **T4 — `ProfileSelector.tsx`** (reescrito): nível 1 **segmented** Bot|Gateway
  (`aria-pressed`); nível 2 **menu customizado** (button trigger + listbox de
  `visibleProfiles` com `name` + `SCOPE_SHORT`; ↑/↓/Enter/Esc; `aria-selected`);
  **painel do contexto** (lente Bot → chips da frota + "gerenciar bots"; lente
  Gateway → `routeLabel` + ajuda + dropdown de rota quando >1); **dialog "Criar
  Novo Perfil"** (modal limpo: nome + radio Tipo de Operação com descrição de uma
  linha, sem campos condicionais); **dialog "gerenciar bots"** (multi-select via
  `GET /api/v1/portal/bots` → `assignBots`); **tooltip de resumo do perfil**
  (D5.2 — hover/foco na opção, `aria-describedby`, escopo + bots + rota amigável,
  dados de `OperationProfile`); dialogs com focus trap/Escape (padrão Spec 73,
  componente `ProfileDialog` local — sem componente genérico novo no `ui.tsx`).
- **T5 — feedback + telas escopo-aware**: banner transitório na troca de perfil
  ("Perfil ativo: <nome> · <SCOPE_LABEL>") e de lente ("Contexto: Bot|Gateway"),
  `role="status"` + auto-dismiss ~3s (T5.1); `SecurityPage.tsx` mostra o escopo do
  perfil ativo e só expõe o dropdown de rota quando `scope != 'BOT_ONLY'` (T5.2);
  `BotsPage`/`BroadcastEditorPage` revisados — **sem mudança funcional**
  (badge do perfil ativo e pré-seleção `activeProfile.bots[0]` já corretos;
  GATEWAY_ONLY mantém vazio) (T5.3).
- **CSS** (`styles.css`): lente segmented, menu customizado, tooltip, painel do
  contexto, banner, opções de escopo e lista de bots.
- **T6.3 — spec E2E criado** (`e2e-tests/tests/profile-scope-lens.spec.js`,
  padrão Spec 123 T4): login → lente Bot → criar BOT_ONLY (modal) → lente Gateway
  (filtro: BOT_ONLY some + ativo cai) → criar GATEWAY_ONLY (painel com rota) →
  trocar perfil (banner) → reload mantém lente + perfil → modal "gerenciar bots"
  com bot do lojista. **Execução pendente de infra** (app dev + Postgres —
  mesmo padrão do job e2e do CI; app não estava rodando na sessão).
- **Verificação**: `npm run build` do portal (tsc + vite) **limpo**; ordem de
  build **portal → console** respeitada (o build do portal apaga `static/console/`
  — achado da Spec 73 — e o console foi rebuildado com hashes idênticos, sem
  mudança de código); `mvn test` **2038/0/0** re-executado verde.

**Desvios deliberados (frontend):**
1. **Banner de contexto sempre na troca de lente** (design D6: "quando o perfil
   ativo muda junto") — implementado de forma simples: o banner "Contexto: X"
   aparece em toda troca de lente (menos código, UX consistente; o ajuste do
   perfil ativo é feito pelo `setLens` do contexto).
2. **`ProfileDialog` local** ao `ProfileSelector` — o portal não tem componente
   `Dialog` no `ui.tsx` (só `ConfirmDialog`); o focus trap/Escape/aria foi
   encapsulado num componente local (sem criar componente genérico novo fora do
   escopo — mesmo princípio do tooltip D5.2).
3. **`createProfile(input)` sem `gatewayRoute`** — o design D4 lista
   `gatewayRoute?` na assinatura, mas o modal limpo (decisão do usuário) nunca
   envia rota; o campo não foi incluído (KISS — a rota é atribuída depois no
   painel da lente via PATCH).

## §E2E — Execução (T6.3 concluída, 2026-08-16)

**Ambiente:** Postgres **18 nativo** (sem Docker — decisão do usuário),
banco **separado `nyxcash_e2e`** (criado para não truncar o banco dev), app
dev (`SPRING_PROFILES_ACTIVE=dev`) com as envs do job e2e do CI
(`NYXCASH_PORTAL_SERVE_FRONTEND=true`, `NYXCASH_CONSOLE_SERVE_FRONTEND=true`,
`NYXCASH_SECURITY_RATE_LIMIT_ENABLED=false`, `NYX_TELEGRAM_API_BASE_URL=
http://localhost:9001`, `NYX_BOTS_TELEGRAM_CDN_CHANNEL_ID`, `NYX_DISCORD_API_BASE_URL=9002`).
**Flyway em banco frio: 65 migrations → v76** (`operation profile scope`)
aplicadas no boot sem erro — migration V76 validada em cold-start.

**Resultado: `profile-scope-lens.spec.js` 1/1 verde** (2.4s) — login → lente
Bot → criar BOT_ONLY (modal limpo) → lente Gateway filtra (BOT_ONLY some, ativo
cai) → criar GATEWAY_ONLY (painel mostra rota "Padrão (por risco)") → trocar
perfil (banner) → reload mantém lente + perfil → modal "gerenciar bots" com o
bot do lojista.

**1 bug REAL de a11y/UX encontrado e corrigido no `ProfileSelector.tsx`** (o
E2E é o contrato — padrão das Specs 74/80/124): o **`Escape` não fechava o menu
de perfis quando o foco estava no trigger** — o handler `onMenuKeyDown` vive só
no listbox; com o foco no botão (após clique), o `Escape` do usuário era
ignorado e o menu ficava aberto; o clique seguinte no trigger **alternava para
fechado** (o teste falhava em `waiting for .profile-option 'Padrão BR'`).
Corrigido com 3 mudanças mínimas (ARIA combobox): (1) `onKeyDown` de `Escape`
no trigger; (2) abrir o dialog "gerenciar bots" agora `setMenuOpen(false)`;
(3) abrir o dialog "+ criar perfil" também `setMenuOpen(false)` (modal por cima
de um dropdown aberto é estado inválido). Portal rebuilt (tsc + vite limpos,
portal → console), `mvn compile` sincronizou `static/` → `target/classes`.
**Suíte E2E completa re-executada após o fix (padrão do CI) — ver resultado na
seção abaixo/roadmap.**

## Auditoria (2026-08-17) — `/spec-audit 139`

**12/12 critérios OK, 0 achados de código, 0 abertos** (detalhe no §Auditoria
abaixo). Verificações contra o código real:

- **C1 (migration V76)** — `V76__operation_profile_scope.sql` presente:
  `scope VARCHAR(20) NOT NULL DEFAULT 'GENERAL'` + CHECK dos 3 valores; backfill
  implícito (sem UPDATE); `store_bots` inalterada. **Validada em cold-start** no
  E2E (banco frio `nyxcash_e2e` → 65→v76 aplicadas sem erro).
- **C2 (GET profile com scope)** — `ProfileResponse` com `scope`
  (`OperationProfileService`/controller); campos intactos (testes
  `OperationProfileControllerTest`).
- **C3 (POST profile)** — `create` aceita `scope` (default GENERAL); escopo
  inválido → `invalid_scope`; BOT_ONLY + rota → `route_not_allowed_for_scope`;
  auditoria `PROFILE_CREATED` preservada (`OperationProfileServiceTest`
  `createComEscopoInvalidoLanca400`/`createBotOnlyNaoAceitaRota`/
  `createBotOnlyComPADRAOForcaRotaNull`/`createGatewayOnlyComRotaValidaPersisteScope`).
- **C4 (PATCH profile)** — `update` com `scope`: BOT_ONLY força rota null (rota
  junto → 400); GATEWAY_ONLY desanexa frota na mesma transação; escopo inválido
  → 400 (`updateParaBotOnlyForcaRotaNullSemEnviarRota`/
  `updateParaBotOnlyComRotaEnviadaLanca400`/
  `updateParaGatewayOnlyDesanexaFrotaNaMesmaTransacao`/
  `updateComEscopoInvalidoLanca400`).
- **C5 (PUT /{id}/bots)** — `replaceBots`: substituição total (desanexa os de
  fora + anexa os novos), lista vazia esvazia, bot de outro lojista/deletado →
  `bot_not_found`, GATEWAY_ONLY → `bots_not_allowed_for_scope`, auditoria
  `PROFILE_BOTS_CHANGED` (`replaceBotsSubstituiFrotaEAudita`/
  `replaceBotsComListaVaziaEsvaziaFrota`/
  `replaceBotsComBotDeOutroLojistaLanca404`/
  `replaceBotsEmPerfilGatewayOnlyLanca400`). 3 exceções novas no
  `ExceptionMappingContractTest` (87 cases mantidos).
- **C6 (resolver)** — `resolveProviders(merchantId, profileId)` +
  `resolvePolicyName(merchantId, profileId)`: BOT_ONLY **nunca** aplica override
  mesmo com rota stale (`botOnlyNuncaAplicaOverrideMesmoComRotaStale`/
  `botOnlyComScoreBaixoUsaDefaultPorRisco`); GENERAL/GATEWAY_ONLY inalterados;
  regressão Spec 91 verde.
- **C7 (lente no frontend)** — `profile.tsx`: `Lens`/`LENS_KEY`
  (`foxcash.portal.lens`), `lens`/`setLens` (valida perfil ativo fora da lente →
  primeiro visível + reescreve storage), `visibleProfiles` memo (Bot → GENERAL+
  BOT_ONLY; Gateway → GENERAL+GATEWAY_ONLY), sanidade no load → `'BOT'`;
  `apiClient.ts` inalterado (header X-Profile-ID segue do perfil ativo).
- **C8 (seletor 2 níveis)** — `ProfileSelector.tsx`: segmented Bot|Gateway
  (`aria-pressed`), menu customizado (button trigger + listbox, ↑/↓/Enter/Esc,
  `aria-selected`), painel do contexto (frota chips + "gerenciar bots" na lente
  Bot; `routeLabel` + dropdown de rota na lente Gateway).
- **C9 (criar perfil modal limpo)** — dialog com nome + radio Tipo de Operação
  (🤖/🌐/⚡ com descrição), sem campos condicionais, erros → ErrorBanner.
- **C10 (feedback visual)** — banner `role="status"` "Perfil ativo: <nome> ·
  <SCOPE_LABEL>" (auto-dismiss ~3s) + banner de contexto na troca de lente;
  estados saving desabilitam controles.
- **C11 (tooltip)** — tooltip por opção (`aria-describedby` → `role="tooltip"`),
  dados de `OperationProfile` sem chamada extra de API.
- **C12 (regressão)** — `mvn test` **2050/0/0** re-executado nesta auditoria +
  builds portal (70 módulos) e console (67) limpos + docs-consistency OK. E2E
  `profile-scope-lens.spec.js` **1/1 verde** (executado 2026-08-16 — §E2E:
  login → criar BOT_ONLY → lente Gateway filtra → criar GATEWAY_ONLY → banner →
  reload mantém lente+perfil → gerenciar bots; 1 bug real de a11y corrigido).

**docs-consistency:** OK (check A/B/C verdes). Fim do ciclo SDD — Spec 139
`audited`, feature LIVE.
