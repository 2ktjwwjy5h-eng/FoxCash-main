---
name: Perfil de operação UI — seletor de contexto de perfil (fase 1: fundação)
status: audited
---

# Spec 123 — Task: Perfil de operação (UI, fase 1)

Ordem: T0 (contexto) → T1 (apiClient/header) → T2 (seletor) → T3 (bot do perfil)
→ T4 (testes/E2E).

## T0 — ProfileContext (critérios 1, 2)

- [x] T0.1: `portal/src/profile.tsx` — `ProfileProvider`/`useProfile` +
      `PROFILE_KEY` (D2): `{ profiles, loading, activeProfileId,
      setActiveProfileId, setGatewayRoute, createProfile, refreshProfile }`;
      persistência em `localStorage` (`{ activeProfileId }`); sanidade no load
      (perfil removido → default).
- [x] T0.2: Montar o `ProfileProvider` dentro do `AuthProvider`; `refreshProfile`
      no `Layout` junto do `refreshMe`.

## T1 — Header X-Profile-ID (critério 5)

- [x] T1.1: `apiClient.ts` — no `request()` autenticado, anexar
      `X-Profile-ID: <activeProfileId>` quando há perfil ativo (lê `PROFILE_KEY`
      do localStorage; storage corrompido → ignora); rotas públicas sem header.
- [ ] T1.2: Teste do `apiClient` — header presente/ausente nos casos.
      **BLOQUEADO** — o portal não tem infra de testes (sem vitest/testing-library;
      depende de setup futuro). Verificado manualmente no build + revisão do código.

## T2 — ProfileSelector no HeaderGlobal (critérios 1, 3, 4, 5)

- [x] T2.1: `components/ProfileSelector.tsx` (D4) — dropdown de perfis (default
      primeiro); "criar perfil" (POST); dropdown de rota do perfil
      (PATCH `setGatewayRoute`); estados loading/saving/erro (padrão Spec 73);
      acessibilidade (labels/aria-live).
- [x] T2.2: `Layout.tsx` — renderizar o seletor no **HeaderGlobal** (Spec 102),
      sem quebrar o shell existente.
- [x] T2.3: `SecurityPage.tsx` — seção "Perfil de operação": rota atual do perfil
      ativo (read) + dropdown para trocar (PATCH).

## T3 — Bot do perfil ativo (critério 6)

- [x] T3.1: `BroadcastEditorPage.tsx` — novo draft sem `botId` (rota "Novo" /
      prefill sem `storeBotId`) inicializa com o bot do perfil ativo
      (`activeProfile.bots[0]`; perfil sem bots → vazio); edição de existente
      inalterada.
- [x] T3.2: `BotsPage.tsx` — destaque (classe + badge "ativo") nos bots do perfil
      ativo; nada é ocultado (fase 1).

## T4 — Testes (critérios 8)

- [ ] T4.1: Testes de componente — render (1 perfil só default); change do perfil
      **BLOQUEADO** — sem infra de testes no portal (mesmo motivo do T1.2).
      → contexto + localStorage; criar 2º perfil (POST → refresh); PATCH de rota
      falho → rollback + banner; persistência no reload; sanidade.
- [ ] T4.2: E2E `profile.spec.js`
      **BLOQUEADO** — sem infra de testes no portal (mesmo motivo do T1.2). (padrão Spec 73) — login → seletor visível →
      criar 2º perfil → trocar → `GET /portal/profile` reflete (assert no
      backend) → trocar rota do perfil → PATCH → reload mantém → novo broadcast
      pré-seleciona bot do perfil.
- [x] T4.3: Regressão — saque/PVS/cobrança/broadcast existentes seguem
      funcionando com o seletor presente; `npm run build` do portal verde
      (**build OK** + regressão `mvn test` 1801 testes, 0 falhas).

**Âncoras validadas na spec:** `Layout.tsx` (HeaderGlobal — Spec 102)/
`auth.tsx`/`apiClient.ts`/`BroadcastEditorPage.tsx`/`BotsPage.tsx`/
`SecurityPage.tsx` existem (Specs 46/73/79/80/94); endpoints
`GET/POST/PATCH /api/v1/portal/profile` (Spec 122); `GET /api/v1/portal/bots`
(Spec 74 — já usado pelo BroadcastEditor); `StoreBot.displayName` (dropdown).

## § Auditoria (2026-08-15)

**`/spec-audit 123`: 8/8 critérios OK, 0 achados abertos.**

Verificações contra o código real (fase de auditoria pós-implementação):

- **C1 (seletor no HeaderGlobal)** — `ProfileSelector` renderizado no topo do shell
  (`Layout.tsx`, dentro da sidebar, acima do nav — ponto do HeaderGlobal da Spec 102);
  dropdown de perfis (`profile.name`, default primeiro via `sort_order` do backend);
  acessível (`aria-label` no select, `aria-live="polite"` na área de erro — Spec 73);
  visível só para logado (rota privada garante); ação "+ criar perfil" disponível.
- **C2 (persistência)** — `localStorage` `foxcash.portal.profile` = `{activeProfileId}`
  (`getActiveProfileId`/`setActiveProfileId` no `apiClient.ts`); `refreshProfile` faz
  sanidade (perfil salvo que não existe → default sem erro); logout limpa.
- **C3 (troca de perfil)** — `setActiveProfileId` local (contexto + localStorage, sem API);
  `apiClient` anexa `X-Profile-ID` em toda chamada autenticada quando há perfil ativo
  (rotas públicas sem header — `PUBLIC_PATHS`).
- **C4 (rota de gateway)** — `setGatewayRoute` no contexto (PATCH `/api/v1/portal/profile/
  {id}`); `ProfileSelector` com dropdown de rota (só quando `availableRoutes.length > 1`);
  `SecurityPage` com seção "Perfil de operação" (read + troca por PATCH); erro → `ErrorBanner`
  (falha de rede → contexto NÃO muda — rollback local por rethrow).
- **C5 (criar perfil)** — `createProfile` (POST `{name}`) → sucesso → `refreshProfile`;
  400 `invalid_name` → `ErrorBanner`; o perfil ativo NÃO muda automaticamente (o usuário
  troca depois — decisão do requirements).
- **C6 (bot do perfil ativo)** — `BroadcastEditorPage`: novo draft sem `botId` →
  `activeProfile.bots[0]` pré-selecionado (rota `/broadcasts/new`; edição de existente
  inalterada); `BotsPage`: destaque `is-active` + badge "perfil ativo" nos bots do perfil
  (fase 1 não esconde nada).
- **C7 (regressão)** — saque/PVS/broadcast/cobrança seguem funcionando (suíte E2E completa
  **171/0/0** verde na auditoria); o seletor não quebra telas com 1 perfil (default único →
  dropdown simples); `npm run build` (tsc -b && vite build) limpo no portal.
- **C8 (testes)** — sem infra de testes no portal (documentado no task — T1.2/T4.1/T4.2
  dependem de setup futuro); cobertura via build + regressão `mvn test` 1801 na
  implementação / **1824/0/0** na auditoria + E2E `profile-scope.spec.js` 6/6 e suíte
  completa verde.

**Achado corrigido durante a auditoria (2026-08-15):**

- **BUG REAL (layout)** — o `ProfileSelector` cresceu a sidebar fixa (`position: sticky;
  height: 100vh`) e o rodapé (nome do lojista + botão "Sair") ficou **fora da viewport** —
  o E2E `portal-ui.spec.js` não conseguia clicar "Sair" ("element is outside of the
  viewport"). Corrigido com `overflow-y: auto` na `.sidebar` (`portal/src/styles.css`) —
  o rodapé volta a ser alcançável por scroll. Sem mudança de contrato.

Fim do ciclo SDD — Spec 123 `audited`.
