---
name: Master Console — frontend web do perfil master (fatia 1)
status: audited
---

# Spec 71 — Task: Master Console (fatia 1)

> **Pré-requisito**: implementar a **Spec 69** antes da 71 (critério 12 —
> `permissions[]`/`role` do `/me` e os guards das ações vêm dela). Sem a 69, o
> catálogo `MasterPermission` não existe — o `/me` não monta `permissions[]` e a
> UI fica em modo somente-leitura.

## F0 — Backend: `GET /api/v1/master/me` (critérios 1, 9)

- [x] T0.1: `MasterMeResponse` (record em `dto/`) — `{masterId, username, name,
      role, permissions[], canManageMasters, mfaActive}`; **sem** `passwordHash`.
- [x] T0.2: `MasterProfileUseCase.selfProfile(UUID masterId)` (ou método novo na
      interface existente, D2.1) — perfil + `permissions[]`/`role` (Spec 69:
      catálogo `MasterPermission` resolvido por nível — OPERATOR → bloco
      completo; ADMIN → bloco + `master.profiles.manage`) + `mfaActive` (TOTP
      ativo, Spec 44).
- [x] T0.3: `MasterMeController` — `GET /api/v1/master/me`, `ROLE_MASTER`,
      principal = masterId (nunca do body), **sem** guard de permissão (rota de
      leitura). 401 sem token válido.
- [x] T0.4: Testes — `MasterMeControllerTest`: 200 com shape completo (papel
      correto, permissions, mfaActive), 200 com `permissions: []` para master
      sem linhas na tabela, 401 sem token, resposta **não** contém hash/PII.

## F1 — Backend: espelhos read-only (critérios 2, 3)

- [x] T1.1: `QueryParamParser.isValidIp(String)` (IPv4/IPv6 — mesmo critério do
      bot, D2.3); `AdminController.lookupByIp` passa a usá-lo (remover o método
      privado duplicado — troca de 1 linha, comportamento idêntico).
- [x] T1.2: `MasterReadController` — `GET /api/v1/master/merchants` (D2.2):
      filtros `status`/`q`/`from`/`to` + `page`/`size` (cap 100), reuso do
      `AdminDashboardUseCase.searchMerchants` + `MerchantResponse` +
      `PageResponse`; parâmetro inválido → 400 `invalid_argument`.
- [x] T1.3: `MasterReadController` — `GET /api/v1/master/lookup/ip/{ip}`
      (D2.3): reuso do `AdminDashboardUseCase.lookupByIp` + `IpLookupResponse`;
      400 em IP inválido; 200 com `matches` vazio sem associação.
- [x] T1.4: Testes — `MasterReadControllerTest`: shape dos 2 endpoints, filtros
      e validações 400, 401 sem token, 403 com API key do lojista, **contratos
      `/api/v1/admin/**` inalterados** (spec existente do AdminController segue
      verde).

## F2 — Config + servir a SPA (critério 4)

- [x] T2.1: `ConsoleProperties` (`foxcash.console.serve-frontend`, default
      `false`) + bloco `console:` nos `application*.yml` (base default `false`;
      prod com env `NYXCASH_CONSOLE_SERVE_FRONTEND`).
- [x] T2.2: `ConsoleSpaController` (`@ConditionalOnProperty`) — forward de
      `/console` e `/console/**` para `/console/index.html` (paths literais na
      anotação — JLS 4.12.4).
- [x] T2.3: `SecurityConfig` — `spaPublicPaths()` inclui `"/console"` +
      `"/console/**"` quando o console serve; CSP da SPA ativa quando
      `portal || console` serve (mesma política da Spec 46/63). CORS inalterado.
- [x] T2.4: Testes — binding do `ConsoleProperties` contra o yml (padrão Spec
      68); `ConsoleSpaControllerTest`: sem flag → 404/401 (bean ausente), com
      flag → forward para `/console/index.html`; security: rota `/console`pública
      com flag, 401 sem flag.

## F3 — Frontend: shell + RBAC (critérios 4, 5, 6)

- [x] T3.1: Scaffold `console/` (package.json, tsconfig, vite.config.ts com
      `outDir: ../src/main/resources/static/console` + proxy dev, index.html,
      `main.tsx`, `styles.css`) — espelho do `portal/` (Spec 46).
- [x] T3.2: `apiClient.ts` — `TOKEN_KEY = 'foxcash.console.token'`,
      `PUBLIC_PATHS = ['/api/v1/auth/master/login']`; 401 → redirect
      `/console/login` (exceto pública); 403/409 → `ApiError` (nunca logout).
- [x] T3.3: `auth.tsx` — `login(username, password, totpCode?)` →
      `POST /api/v1/auth/master/login` → `setToken` → `GET /api/v1/master/me` →
      `setMe`; `logout()` revoga no servidor; `RequireAuth` →
      `/console/login` (preserva `state.from`).
- [x] T3.4: `hooks/usePermission.ts` — `usePermission(p)`,
      `useCanManageMasters()`, `<RequirePermission permission>`; NavigationMap
      (rota de leitura sem `permission` = visível a todo master autenticado).
- [x] T3.5: `Layout.tsx` — sidebar filtrada pelo NavigationMap + `usePermission`,
      header com badge de papel (`me.role`/ADMIN), nome e logout.
- [x] T3.6: `LoginPage.tsx` — username/senha/TOTP opcional; 401 genérico (sem
      oráculo); `h1.auth-title` = "FoxCash".
- [x] T3.7: `App.tsx` — rotas públicas (`/console/login`) + privadas sob
      `RequireAuth`+`Layout` (D5.5/D5.6); `/console` → redirect `/console/dashboard`.
- [x] T3.8: `npm run build` do console limpo (`tsc` sem erro); `static/console/`
      gerado; **ordem de build documentada: console DEPOIS do portal** (D7.2).

## F4 — Frontend: telas de leitura (critério 7)

- [x] T4.1: `DashboardPage` — `GET /master/transactions` (recentes) +
      `GET /master/revenue/summary`.
- [x] T4.2: `TransactionsPage` — `GET /master/transactions` (filtros/paginação).
- [x] T4.3: `MerchantsPage` — `GET /master/merchants` (filtros
      `status`/`q`/`from`/`to` + paginação) → link para o detalhe.
- [x] T4.4: `MerchantDetailPage` — tabs: earnings (`/earnings`), fees (`/fees`),
      risk (`/risk`), ledger (`/master/merchants/{id}/ledger`), receivables
      (`/master/merchants/{id}/receivables`), saques
      (`GET /master/withdrawals?merchantId={id}`) + ações de OPERATOR (F5).
- [x] T4.5: `WithdrawalsPage` — `GET /master/withdrawals` (filtros
      `status`/`from`/`to`) + ações block/release (F5).
- [x] T4.6: `FinancialPage` — `GET /master/revenue/summary` + `/ledger`.
- [x] T4.7: `ReceivablesPage` — `GET /master/receivables` (leitura).
- [x] T4.8: `AuditPage` — `GET /master/audit`.
- [x] T4.9: `ActivityPage` — `GET /master/report/activity`.
- [x] T4.10: `ReconciliationPage` — `GET /master/reconciliation/issues`
      (**read-only** na fatia 1 — sem botão `run`, D7.7).
- [x] T4.11: `LookupPage` — input de IP → `GET /master/lookup/ip/{ip}` → matches
      (PII mascarada já no contrato).
- [x] T4.12: `npm run build` limpo (tsc sem erro) após todas as telas.

## F5 — Frontend: ações OPERATOR + MFA (critérios 6, 8, 9)

- [x] T5.1: `ConfirmDialog.tsx` + `ErrorBanner`/feedback (padrões `ui.tsx`).
- [x] T5.2: MerchantDetail — `suspend`/`release` (`merchant.suspend`/
      `merchant.release`) → `POST /master/merchants/{id}/suspend|release` com
      confirmação; 403 → banner "sem permissão" (sessão permanece).
- [x] T5.3: WithdrawalsPage — `block`/`release` (`withdrawal.block`/
      `withdrawal.release`) → `POST /master/merchants/{id}/withdrawals/block|release`.
- [x] T5.4: `SecurityPage` (MFA, D5.7) — activate (2 passos, secrets **1x**),
      confirm (recovery codes **1x**), deactivate (senha + TOTP/recovery → 204),
      recovery-codes (regenera); estado guiado por `me.mfaActive`.
- [x] T5.5: Tratamento de 403 nos fluxos — mensagem "sem permissão" + remoção do
      item do menu quando aplicável (nunca logout).

## F6 — E2E + fechamento (critérios 10, 11, 12)

- [x] T6.1: `e2e-tests/tests/helpers/totp.js` — extrair `totpCode`/`totpAt` de
      `auth.spec.js` (RFC 6238, mesmo algoritmo do `Totp.java`); `auth.spec.js`
      passa a importar (migração mecânica).
- [x] T6.2: `master-console-ui.spec.js` (D6, serial) — seed de master via SQL
      direto (BCrypt fixo, padrão auth.spec B1); cenários: login sem MFA →
      dashboard → merchants → detalhe → suspend → release → logout → 401/redirect;
      MFA (activate via API → confirm com `totpAt` → login com TOTP → 200; login
      sem TOTP com MFA ativo → 401 + erro genérico); 403 não derruba sessão.
- [x] T6.3: `mvn -o clean test` verde — suíte completa sem regressão
      (AdminController inalterado; auth.spec B2/B3 seguem 401 após revogar).
- [x] T6.4: E2E completo verde (`portal-ui.spec.js` + `master-console-ui.spec.js`
      + demais specs — app com `NYXCASH_CONSOLE_SERVE_FRONTEND=true`; **adicionar
      a env `NYXCASH_CONSOLE_SERVE_FRONTEND: 'true'` ao job e2e do `ci.yml`**,
      hoje só a do portal está lá).
- [x] T6.5: README (seção curta — flag `NYXCASH_CONSOLE_SERVE_FRONTEND`, URL
      `/console`, ordem de build console-depois-portal) e sync
      roadmap/requirements/design/task.
- [x] T6.6: `/spec-audit` — verificar os **12 critérios** do requirements um a um
      (concluído 2026-08-12 — ver §Auditoria).

## Verificação (`/spec-verify` 2026-08-11)

2 achados corrigidos nos documentos (precisão, sem mudança de contrato):

1. **`mfaActive` do `/me`** (requirements critério 1, design D2.1): a primeira
   versão dizia "verificar o portão existente na implementação" — verificado no
   código: **`MasterMfaService.hasActiveMfa(masterId)` já existe** (Spec 44;
   usado por `MasterAuthService.login` para exigir o 2º fator quando ativo). O
   design agora referencia o método real, sem expor secret.
2. **CI do E2E** (design D6, task T6.4): o job e2e do `ci.yml` só seta
   `NYXCASH_PORTAL_SERVE_FRONTEND` — a task ganhou o item explícito de
   **adicionar `NYXCASH_CONSOLE_SERVE_FRONTEND: 'true'`** ao job (o
   `master-console-ui.spec.js` exige a flag ligada).

Referências validadas: V51 (visão), specs 13/20/44 (auth/MFA do master),
46 (padrão da SPA), 69 (RBAC/step-up — dependência explícita), 8/67 (contratos
admin espelhados), 15/16/19/27/33/34/37/41/30/14 (endpoints ROLE_MASTER lidos
pelo console).

Código verificado: `SecurityConfig` (`/api/v1/master/**` → `hasRole("MASTER")`;
`spaPublicPaths()`; CSP condicional `portalProperties.isServeFrontend()`),
`MasterAuthFilter` (principal = `masterId` UUID + `ROLE_MASTER`),
`MasterAuthService.login` (TOTP → 401 uniforme, sem oráculo),
`MasterLoginRequest.totpCode` (opcional), `MasterMfaController`
(activate/confirm/deactivate/recovery-codes) + `MasterMfaService.hasActiveMfa`,
`MasterRiskController` (suspend/release), `MasterWithdrawalController`
(block/release + `GET /withdrawals` com filtro `merchantId`),
`MasterDashboardController`/`MasterRevenueController`/`MasterAuditController`/
`MasterActivityReportController`/`ReceivableController`/`ReconciliationController`/
`WalletLedgerController` (todos os endpoints ROLE_MASTER de leitura citados),
`AdminController` (shape dos espelhos + validações 400) e
`QueryParamParser` (já tem `parseEnum`/`parseInstant`; falta `isValidIp`),
`GlobalExceptionHandler` (`IllegalArgumentException` → 400 `invalid_argument`),
`PortalSpaController`/`PortalProperties` (padrão do flag + forward da SPA),
`portal/` (`App.tsx`, `auth.tsx`, `apiClient.ts` — padrão do shell/RBAC),
`e2e-tests/tests/auth.spec.js` (helper `totpCode` + `PASSWORD_BCRYPT` + seed SQL)
+ `playwright.config.js` (baseURL 8080) + `ci.yml` (envs do job e2e).

Sem achados estruturais: os 3 endpoints novos são aditivos (nenhum controller
existente é alterado além da troca de 1 linha do `isValidIp` no `AdminController`,
comportamento idêntico); os contratos `/api/v1/admin/**` e `/api/v1/master/**`
existentes ficam intactos; o `auth.spec.js` B2/B3 (401 após revogar via
`/api/v1/master/merchants`) continua válido — o 401 é decidido pelo
`MasterAuthFilter` antes do roteamento.

## Implementação (`/spec-implement` 2026-08-11)

F0–F5 concluídas (checkboxes acima). Resumo do que foi entregue e os desvios
deliberados:

- **F0 — `/master/me`**: `MasterMeResponse` (dto), `MasterProfileUseCase.SelfProfile`
  + `selfProfile(masterId)` (resolução do catálogo da Spec 69 por nível via
  `MasterLevel.of` — OPERATOR 7 permissões / ADMIN + `master.profiles.manage`),
  `MasterProfileService.selfProfile` (injeta `MasterMfaUseCase` para o
  `mfaActive`) e `MasterMeController` (`GET /api/v1/master/me`, principal do
  auth, sem guard). Testes: `MasterMeControllerTest` 4/4 +
  `MasterProfileServiceTest` +3.
- **F1 — espelhos**: `QueryParamParser.isValidIp` (extraído do `AdminController`,
  comportamento idêntico — DRY) + `MasterReadController` (`/merchants` e
  `/lookup/ip/{ip}` reusando `AdminDashboardUseCase`). Testes:
  `MasterReadControllerTest` 7/7 + `QueryParamParserTest` +4.
- **F2 — config + SPA**: `ConsoleProperties` (`foxcash.console.serve-frontend`,
  default false; registrado no `ApplicationConfig`) + bloco `console:` no
  `application.yml` (env `NYXCASH_CONSOLE_SERVE_FRONTEND`) e `application-prod.yml`;
  `ConsoleSpaController` (`@ConditionalOnProperty`) + `SecurityConfig`
  (`spaPublicPaths` com as rotas do console quando serve; CSP ativa quando
  `portal || console`). Testes: `ConsolePropertiesTest` 2/2,
  `ConsolePropertiesBindingTest` 3/3 (padrão Spec 68), `ConsoleSpaControllerTest` 3/3.
- **F3–F5 — SPA `console/`**: scaffold espelho do portal (`base '/console/'`,
  porta 5174, outDir `static/console`); `apiClient.ts` (chave própria
  `foxcash.console.token`, 401 → `/console/login`, 403/409 → `ApiError` sem
  logout); `auth.tsx` (login com TOTP opcional + `/master/me`); `usePermission`
  (RBAC — esconde, nunca autoriza); `Layout` com NavigationMap; 13 páginas
  (login, dashboard, transações, lojistas, detalhe com tabs, saques com
  block/release, financeiro, receivables, auditoria, atividades, reconciliação
  read-only, lookup IP, MFA). Build: `npm run build` limpo (`tsc` sem erro);
  `static/console/` gerado.
- **F6 — E2E + fechamento**: helper `e2e-tests/tests/helpers/totp.js` (extraído
  de `auth.spec.js` — migração mecânica, mesmo algoritmo RFC 6238);
  `master-console-ui.spec.js` (4 cenários: login→dashboard→lojistas→detalhe→
  suspend/release; MFA com TOTP + 401 sem TOTP; 403 não derruba a sessão via
  `/master/profiles` admin-only; logout→redirect→401); `ci.yml` job e2e ganhou
  `NYXCASH_CONSOLE_SERVE_FRONTEND: 'true'`; README com seção do console.

**Desvios deliberados (registrados para a auditoria):**

1. **`ConsoleSpaController` com paths EXPLÍCITOS** (não `/console/**` como no
   D4.1 literal): um mapping amplo sombrearia os assets (`/console/assets/**` —
   `base '/console/'` no Vite) porque a ordem do Spring MVC coloca os
   controllers à frente do handler de recursos estáticos — a SPA quebraria
   (assets virados em `index.html`). Comentado no código; comportamento
   idêntico ao `PortalSpaController`.
2. **`/console/merchants/:id/*` no App.tsx** (não `:id`): em React Router v6 o
   segmento `:id` casa UM segmento — as sub-rotas (tabs) do detalhe não casariam
   (fees/ledger/...) e o fallback `*` redirecionaria para o dashboard. Achado
   no E2E (navegação Fees → dashboard).
3. **`/console/` (com barra) também mapeado** no controller + rotas públicas:
   sem isso, `/console/` respondia 401 (cai no `anyRequest().authenticated()`).
4. **E2E cenário 3 adaptado**: no modelo de 2 níveis da Spec 69 (decisão do
   usuário 2026-08-11) TODOS os masters ativos têm as 7 permissões operacionais —
   o 403 de `merchant.suspend` é inalcançável. O cenário usa `GET
   /api/v1/master/profiles` (admin-only → `MasterAdminGuard` → 403 `forbidden`)
   e valida que a sessão NÃO cai (token continua válido; UI intacta).

**Verificação**: `mvn -o clean test` **1372/0/0** (26 testes novos);
`master-console-ui.spec.js` **4/4**; suíte E2E completa (ver resultado na
rodada/commit); contratos `/api/v1/admin/**` e `/api/v1/master/**` existentes
inalterados (testes do `AdminController`/master seguem verdes). Rota `/console/`
e `/console/login` servidas com o flag; sem o flag → 401 (bean ausente).

## Auditoria (pós-implementação)

**`/spec-audit` (2026-08-12): 12/12 critérios OK — 0 achados de código.**

Verificação (1:1 com o requirements.md):

1. **`GET /api/v1/master/me`** — `MasterMeController` (rota de leitura, sem guard;
   principal do `MasterAuthFilter`, nunca do body); `MasterMeResponse` sem
   `passwordHash`/PII; `MasterProfileService.selfProfile` resolve o catálogo da
   Spec 69 por nível (`MasterLevel.of`: OPERATOR → 7 permissões; ADMIN → +8ª
   `master.profiles.manage` — nunca `[]` para ativo) + `mfaActive` via
   `MasterMfaService.hasActiveMfa` (método existente, Spec 44). `MasterMeControllerTest`
   4/4 (shape OPERATOR 7/ADMIN 8, principal, sem hash). 401 sem token: `SecurityConfig`
   (`/api/v1/master/**` → `hasRole("MASTER")`) + `MasterAuthFilter` — verificado no E2E
   (cenário 4: `/master/me` sem token → 401).
2. **`GET /api/v1/master/merchants`** — `MasterReadController` reusa
   `AdminDashboardUseCase.searchMerchants` + `QueryParamParser` (mesmos filtros
   `status`/`q`/`from`/`to`, paginação, cap 100, 400 `invalid_argument`);
   `MasterReadControllerTest` 7/7 (delegation com filtros parseados, 400 em
   `status`/`from` inválidos, clamp size 100). Contrato `/api/v1/admin/merchants`
   **inalterado** (testes do `AdminController` verdes).
3. **`GET /api/v1/master/lookup/ip/{ip}`** — espelho read-only do
   `AdminDashboardUseCase.lookupByIp` + `IpLookupResponse` (PII mascarada no
   contrato); `QueryParamParser.isValidIp` (IPv4/IPv6) extraído do `AdminController`
   (DRY, comportamento idêntico — verificado no diff); 400 em IP inválido; 200 com
   `matches` vazio. `MasterReadControllerTest` cobre os 3 casos.
4. **SPA servida** — `ConsoleProperties` (`foxcash.console.serve-frontend`, default
   `false`) + binding contra o yml real (`ConsolePropertiesBindingTest` 3/3 — padrão
   Spec 68/L16); `ConsoleSpaController` (`@ConditionalOnProperty`, paths explícitos —
   desvio deliberado documentado, sem `/console/**` amplo que sombrearia os assets);
   `SecurityConfig.spaPublicPaths()` (rotas do console + `/console/assets/**` só com o
   flag) + CSP da SPA compartilhada (`portal || console`) e CORS inalterado; sem o
   flag → bean ausente + array vazio (nenhuma rota extra). Build do console presente em
   `static/console/` e `npm run build` limpo (`tsc` sem erro, 53 módulos).
5. **Login com MFA no cliente** — `apiClient.ts` (token em `sessionStorage`, chave
   própria `foxcash.console.token`, `PUBLIC_PATHS` = login, 401 → redirect exceto
   pública, 403/409 → `ApiError` sem logout); `auth.tsx` (login com `totpCode?` →
   `POST /auth/master/login` → `GET /master/me`; logout revoga no servidor);
   `LoginPage` (campo TOTP opcional, `h1.auth-title` = "FoxCash", 401 genérico sem
   oráculo). E2E cenário 2: login sem TOTP com MFA ativo → 401 + permanece no login;
   com TOTP → dashboard.
6. **UI RBAC-aware** — `usePermission`/`useCanManageMasters`/`RequirePermission`
   (apresentação, nunca autorização); `Layout` com NavigationMap e badge de papel
   (`ADMIN`/`OPERATOR`); 403 → banner "sem permissão" + sessão viva (E2E cenário 3
   verificado: `/master/profiles` admin-only → 403 `forbidden` → `/master/me` 200).
7. **Telas de leitura** — 13 páginas em `console/src/pages/`: Dashboard (transactions +
   revenue summary), Transactions, Merchants (lista), MerchantDetail (tabs
   earnings/fees/risk/ledger/receivables/withdrawals), Withdrawals, Financial,
   Receivables, Audit, Activity, Reconciliation (read-only), Lookup IP, Security (MFA)
   — todas via APIs ROLE_MASTER existentes + os 3 endpoints novos.
8. **Ações de OPERATOR** — suspend/release (MerchantDetail) e block/release
   (Withdrawals) com `RequirePermission` + `ConfirmDialog` + feedback; 403 tratado
   como "sem permissão". E2E cenário 1: suspend → 204 → feedback → release → 204.
9. **Autogestão de MFA** — `SecurityPage` (activate 2 passos com `otpauthUri` +
   `manualEntryKey` 1x, confirm → recovery codes 1x, deactivate senha+TOTP, regenerate)
   guiado por `me.mfaActive`. E2E cenário 2 ativa/confirma via API com `totpAt`.
10. **E2E browser** — `master-console-ui.spec.js` **4/4** (2 execuções: isolado e na
    suíte completa); helper `e2e-tests/tests/helpers/totp.js` extraído de
    `auth.spec.js` (RFC 6238, mesmo algoritmo do `Totp.java`); `ci.yml` job e2e com
    `NYXCASH_CONSOLE_SERVE_FRONTEND: 'true'`.
11. **Sem regressão** — `mvn -o clean test` **1372/0/0** re-executado (179 suítes;
    +26 testes novos); E2E completo **117/117** re-executado com banco limpo
    (`master-console-ui` 4/4 incluído; `mvn test` + E2E com as envs do CI:
    `NYXCASH_SECURITY_RATE_LIMIT_ENABLED=false` + `NYXCASH_PORTAL_SERVE_FRONTEND=true`
    + `NYXCASH_CONSOLE_SERVE_FRONTEND=true`); contratos `/api/v1/admin/**` e
    `/api/v1/master/**` existentes **inalterados** (o `AdminController` só trocou a
    linha do `isValidIp` — comportamento idêntico).
12. **Dependência da Spec 69** — `permissions[]`/`role` e os guards das ações vêm do
    catálogo `MasterPermission` + `MasterLevel` da Spec 69 (o console **consome, não
    duplica**); Spec 69 `audited` antes da 71.

**Observação registrada (fora do escopo da Spec 71):** durante a re-execução do E2E
completo, o teste 23 da Spec 48 (`updatedAt` da loja) falhou 1× por race de precisão
de timestamp (CREATE e PATCH no mesmo microssegundo do Postgres — `updated_at`
armazenado com precisão de µs). Sem relação com a Spec 71 (arquivo da Spec 48,
pré-existente); a jornada isolada com banco limpo passou 27/27 e a suíte completa
final passou 117/117. Flakiness registrada para follow-up da Spec 48 (não bloqueia).

**0 achados de código na Spec 71.** Fim do ciclo SDD
(roadmap/requirements/design/task `audited`).
