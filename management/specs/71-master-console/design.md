---
name: Master Console — frontend web do perfil master (fatia 1)
status: audited
---

# Spec 71 — Design: Master Console (fatia 1)

## D1 — Conceito

O Master Console é uma **SPA React/Vite (`console/`)** servida pelo próprio Spring
Boot em `/console/*` — mesmo padrão do portal do lojista (Spec 46), thin client:
**zero regra de negócio no frontend**. O console consome exclusivamente APIs
`ROLE_MASTER` (todas existentes e auditadas + **3 endpoints novos, aditivos e
somente-leitura**). A autoridade de autorização é sempre o backend
(Spec 69, deny-by-default); a UI RBAC-aware **esconde, nunca autoriza**.

A fatia 1 entrega **leitura completa + ações reversíveis de OPERATOR** (sem
step-up). As ações sensíveis (saque administrativo, write-off, cobrança manual,
gestão de perfis master) com step-up TOTP ficam na **Spec 72**.

### D1.1 Decisões do usuário (2026-08-11) → regras

| Decisão | Regra |
|---|---|
| Endpoint novo `GET /api/v1/master/me` (opção b) | Perfil do master autenticado com `permissions[]`/`role`/`canManageMasters`/`mfaActive` (D2.1) — `MasterLoginResponse` **inalterado** |
| Escopo em 2 fatias | Spec 71 = leitura + OPERATOR sem step-up; Spec 72 = FINANCE/ADMIN sensíveis + step-up TOTP no cliente |
| Espelhos read-only no master (Sim) | `GET /api/v1/master/merchants` e `GET /api/v1/master/lookup/ip/{ip}` — hoje só existem sob `/api/v1/admin/**` (ROLE_ADMIN), que o master não alcança (D2.2/D2.3) |
| Contratos existentes intactos | Nada em `/api/v1/admin/**`, `/api/v1/master/**` ou `/api/v1/auth/master/**` existente muda — os 3 endpoints são adições |

## D2 — Backend aditivo (somente leitura, `ROLE_MASTER`)

Sem migration nova nesta spec: `permissions[]`/`role` vêm do catálogo da
**Spec 69** resolvido por nível (`can_manage_masters` — a implementar antes da
71 — critério 12). Os 3 endpoints são **aditivos**; os controllers existentes
não são alterados.

### D2.1 `GET /api/v1/master/me`

**Novo** `MasterMeController` (`infrastructure/inbound/rest/`), base
`/api/v1/master`, `@GetMapping("/me")`:

```java
@RestController
@RequestMapping("/api/v1/master")
public class MasterMeController {
    // GET /me → 200 MasterMeResponse
    // principal = masterId (MasterAuthFilter) — nunca do body (sem IDOR)
}
```

- **`MasterMeResponse`** (novo record em `dto/`):
  `{masterId: UUID, username, name, role, permissions: List<String>,
  canManageMasters: boolean, mfaActive: boolean}` — **nunca** `passwordHash`,
  nunca PII extra além do perfil.
- **Use case**: novo método `selfProfile(UUID masterId)` no
  `MasterProfileUseCase` (Spec 41 já lê o próprio perfil; estender a interface
  com o método `SelfProfile` é aditivo e reusa o repositório existente).
  - `permissions[]` + `role`: **consumo direto da Spec 69** (catálogo
    `MasterPermission` resolvido por nível — decisão do usuário 2026-08-11):
    `role` = `ADMIN` (canManageMasters) | `OPERATOR`; `permissions[]` = bloco
    operacional completo (7 permissões) para OPERATOR e bloco +
    `master.profiles.manage` para ADMIN — nunca `[]` para master ativo
    (critério 1).
  - `mfaActive`: `MasterMfaService.hasActiveMfa(masterId)` — método **existente**
    (Spec 44; usado por `MasterAuthService.login` para exigir o 2º fator) — sem
    expor secret; `false` quando não há método ACTIVE.
- **Rota de leitura**: **sem** guard de permissão — todo master autenticado
  vê o próprio perfil (o guard de permissão protege **ações**, não a
  auto-leitura; critério 1). 401 sem token válido (padrão do `SecurityConfig`).

### D2.2 `GET /api/v1/master/merchants`

**Novo** `MasterReadController` (`infrastructure/inbound/rest/`), base
`/api/v1/master` — espelho **read-only** do `GET /api/v1/admin/merchants`
(Spec 8). O `AdminDashboardUseCase.searchMerchants` é reutilizado **como está**
(o use case não tem role embutida — a fronteira de role é o controller; o
admin e o master leem o mesmo serviço).

```java
@GetMapping("/merchants")
public PageResponse<MerchantResponse> merchants(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
    MerchantSearchQuery query = new MerchantSearchQuery(
            QueryParamParser.parseEnum(MerchantStatus.class, status, "status"),
            q,
            QueryParamParser.parseInstant(from, "from"),
            QueryParamParser.parseInstant(to, "to"),
            QueryParamParser.clampPage(page),
            QueryParamParser.clampSize(size));
    return PageResponse.from(adminDashboard.searchMerchants(query), MerchantResponse::from);
}
```

- **Mesmo shape e validações** do admin: paginação (page/size, cap 100),
  filtros `status`/`q`/`from`/`to`, parâmetro inválido → 400
  `invalid_argument` (via `IllegalArgumentException` → `GlobalExceptionHandler`).
- **Reuso do `QueryParamParser`** (já tem `parseEnum`/`parseInstant`/
  `parseUuid`/`clampPage`/`clampSize` — usados pelos controllers do master).
- Contrato `/api/v1/admin/merchants` **inalterado**.

### D2.3 `GET /api/v1/master/lookup/ip/{ip}`

Mesmo `MasterReadController` — espelho **read-only** do
`GET /api/v1/admin/lookup/ip/{ip}` (Spec 67, `!ip` do bot):

```java
@GetMapping("/lookup/ip/{ip}")
public IpLookupResponse lookupByIp(@PathVariable String ip) {
    if (!QueryParamParser.isValidIp(ip)) {
        throw new IllegalArgumentException("Parâmetro inválido 'ip': '" + ip + "'");
    }
    return IpLookupResponse.from(adminDashboard.lookupByIp(ip));
}
```

- Reuso de `AdminDashboardUseCase.lookupByIp` + `IpLookupResponse` (mesmo shape:
  `{matches[]}` com resumo de vendas + bot da loja, máscara de PII).
- `isValidIp` (IPv4/IPv6, mesmo critério do bot) **adicionado ao
  `QueryParamParser`** (novo método estático); o `AdminController` passa a usá-lo
  (troca de 1 linha no call-site + remoção do método privado duplicado — DRY,
  comportamento idêntico). IP inválido → 400 `invalid_argument`; sem associação
  → 200 com `matches` vazio.
- Contrato `/api/v1/admin/lookup/ip/{ip}` **inalterado**.

## D3 — Configuração (`foxcash.console.*`)

Novo `ConsoleProperties` (`infrastructure/config/`),
`@ConfigurationProperties("foxcash.console")` — espelho mínimo do `PortalProperties`:

| Propriedade | Tipo | Default | Env (prod) | Uso |
|---|---|---|---|---|
| `serve-frontend` | `boolean` | `false` | `NYXCASH_CONSOLE_SERVE_FRONTEND` | Liga o `ConsoleSpaController` + rotas públicas + CSP da SPA (D4) |

Bloco `console:` sob `foxcash:` nos `application*.yml` (base com default
`false`; prod seta a env quando o console for servido). Binding testado contra o
yml (padrão Spec 68) e `@ConfigurationProperties` registrado (padrão
`PortalProperties`).

**CORS: sem config nova.** Em produção o console é servido no **mesmo origin**
do Spring Boot (sem CORS); em dev o Vite faz proxy (D5.1). O bloco CORS
existente (portal, `foxcash.portal.cors-allowed-origins`) permanece o único gate
para consumo externo — nada muda (critério 4: "CORS apenas para origins
configurados" já satisfeito).

## D4 — Servir a SPA (Spring Boot)

### D4.1 `ConsoleSpaController`

Novo `@Controller` com `@ConditionalOnProperty("foxcash.console.serve-frontend",
havingValue = "true")` — padrão exato do `PortalSpaController`:

```java
@RequestMapping(path = {"/console", "/console/**"}, method = RequestMethod.GET)
public String forwardToIndex() {
    return "forward:/console/index.html";
}
```

- **Nota**: os paths são literais na anotação (campos `String[]` não são
  constantes de compilação — JLS 4.12.4; mesmo comentário do
  `PortalSpaController`).
- Sem o flag, o bean não existe → **nenhuma rota extra exposta** (a API pura
  permanece sem HTML; qualquer rota fora da API cai em
  `anyRequest().authenticated()` → 401).

### D4.2 `SecurityConfig`

- **Rotas públicas** (`spaPublicPaths()`): incluir `"/console"` e
  `"/console/**"` quando `consoleProperties.isServeFrontend()` (junto das rotas
  do portal; o console roda no mesmo origin — sem CORS).
- **CSP**: a política da SPA fica ativa quando
  `portalProperties.isServeFrontend() || consoleProperties.isServeFrontend()`
  (mesma string da Spec 46/63 — inclui `challenges.cloudflare.com` do Turnstile;
  é **inerte** quando o console não carrega o widget, e evita duplicar a
  política). Sem nenhuma SPA servida → CSP restrita `default-src 'none'` (API
  pura, inalterado).
- CORS: inalterado (D3).

### D4.3 Build (ordem importa)

- `console/vite.config.ts`: `outDir: '../src/main/resources/static/console'`,
  `emptyOutDir: true` (limpa **só** `static/console`).
- **Atenção**: o build do **portal** usa `outDir: '../src/main/resources/static'`
  com `emptyOutDir: true` — ele **apaga o `static/` inteiro** (incluindo
  `console/`). Regra de build: **o console SEMPRE é construído DEPOIS do
  portal** (scripts `package.json` raiz + CI) — registrado em D7.2.

## D5 — Frontend (`console/`)

### D5.1 Estrutura (espelho do `portal/`, Spec 46)

```
console/
  package.json            react, react-dom, react-router-dom, vite, typescript
  vite.config.ts          outDir ../src/main/resources/static/console; port 5174;
                          proxy /api + /webhooks → http://localhost:8080
  tsconfig.json, index.html
  src/
    main.tsx              ReactDOM root (padrão portal)
    App.tsx               rotas (D5.6)
    apiClient.ts          cliente HTTP (D5.2)
    auth.tsx              AuthProvider + RequireAuth (D5.3)
    hooks/usePermission.ts  RBAC UI (D5.4)
    components/Layout.tsx   nav filtrada + header com role/logout
    components/ui.tsx       primitivos (ErrorBanner, StatusBadge, Pagination...)
    components/ConfirmDialog.tsx  confirmação de ações de OPERATOR
    pages/                LoginPage, DashboardPage, TransactionsPage,
                          MerchantsPage, MerchantDetailPage, WithdrawalsPage,
                          FinancialPage, ReceivablesPage, AuditPage,
                          ActivityPage, ReconciliationPage, LookupPage,
                          SecurityPage (MFA)
    styles.css            base (padrão portal)
```

### D5.2 `apiClient.ts` (padrão do portal, chave própria)

- `TOKEN_KEY = 'foxcash.console.token'` (sessionStorage — nunca localStorage).
- `PUBLIC_PATHS = ['/api/v1/auth/master/login']` — 401 nessas rotas **não**
  redireciona (login inválido tratado na página).
- Interceptor **401** → logout local + `redirectToLogin()` (→ `/console/login`).
- **403/409/etc → `ApiError` propagado, NUNCA logout** — 403 `forbidden` =
  "sem permissão" (a sessão continua viva; a UI mostra o banner). O 401 uniforme
  do login com MFA ausente também é tratado na página de login (D5.8).

### D5.3 `auth.tsx`

- `AuthProvider`: `login(username, password, totpCode?)` →
  `POST /api/v1/auth/master/login {username, password, totpCode}` (auth:false)
  → `setToken` → `GET /api/v1/master/me` → `setMe(MasterMe)`.
- `logout()` → `POST /api/v1/auth/master/logout` (revoga no servidor) + limpa
  storage (mesmo padrão do portal).
- `RequireAuth` → sem token → `<Navigate to="/console/login" state={{from}}>`.
- `MasterMe` interface: `{masterId, username, name, role, permissions[],
  canManageMasters, mfaActive}`.

### D5.4 RBAC-aware (`usePermission`)

```ts
export function usePermission(permission: string): boolean {
  const { me } = useAuth();
  return !!me && me.permissions.includes(permission);
}
export function useCanManageMasters(): boolean {
  const { me } = useAuth();
  return !!me && me.canManageMasters;
}
```

- **NavigationMap** (constante em `Layout.tsx`): `{path, label, permission?}` —
  sem a permissão (ou `permission` ausente = rota de leitura, visível a todo
  master autenticado) → item **escondido** do menu. **Na fatia 1**, todo master
  ativo tem as 7 permissões operacionais (bloco completo, decisão do usuário
  2026-08-11) — o filtro só ocultaria a seção de gestão de perfis master
  (`master.profiles.manage`, admin-only), que é da fatia 2 (Spec 72).
- `<RequirePermission permission="...">` (componente em `usePermission.ts`)
  envolve botões de ação — renderiza os filhos só com a permissão.
- Badge de papel no header (`me.role`, e `ADMIN` quando `canManageMasters`).
- **Regra de ouro** (critério 6): a UI só decide **apresentação**; ao receber
  403 do backend, mostra "sem permissão" (e remove o item do menu se aplicável)
  — nunca tenta autorizar.

### D5.5 Telas de leitura (rota ↔ endpoint ↔ permissão)

| Rota (`/console/...`) | Endpoint (ROLE_MASTER) | Spec de origem | Permissão |
|---|---|---|---|
| `/dashboard` | `GET /api/v1/master/transactions` (recentes) + `GET /api/v1/master/revenue/summary` | 15 | — (leitura) |
| `/transactions` | `GET /api/v1/master/transactions` (filtros) | 15 | — |
| `/merchants` | `GET /api/v1/master/merchants` (**novo**, D2.2) | 8/71 | — |
| `/merchants/:id` (tabs) | `GET /master/merchants/{id}/earnings` · `/fees` · `/risk` · `/master/merchants/{id}/ledger` · `/master/merchants/{id}/receivables` · `GET /master/withdrawals?merchantId={id}` | 15/16/19/27/33/14 | — |
| `/withdrawals` | `GET /api/v1/master/withdrawals` (filtros `status`/`from`/`to`) | 14/32/36 | — |
| `/financial` | `GET /api/v1/master/revenue/summary` + `/ledger` | 34 | — |
| `/receivables` | `GET /api/v1/master/receivables` | 33 | — |
| `/audit` | `GET /api/v1/master/audit` | 37 | — |
| `/activity` | `GET /api/v1/master/report/activity` | 41 | — |
| `/reconciliation` | `GET /api/v1/master/reconciliation/issues` (**read-only** na fatia 1) | 30 | — |
| `/lookup` | `GET /api/v1/master/lookup/ip/{ip}` (**novo**, D2.3) | 67/71 | — |

Toda rota de leitura é visível a **qualquer master autenticado** (a Spec 69
protege ações; leitura de `ROLE_MASTER` já é o guard).

### D5.6 Ações reversíveis de OPERATOR (sem step-up)

| Ação | Endpoint | Permissão (Spec 69) | Onde |
|---|---|---|---|
| Suspender lojista | `POST /api/v1/master/merchants/{id}/suspend` | `merchant.suspend` | MerchantDetailPage |
| Liberar lojista | `POST /api/v1/master/merchants/{id}/release` | `merchant.release` | MerchantDetailPage |
| Bloquear saques | `POST /api/v1/master/merchants/{id}/withdrawals/block` | `withdrawal.block` | WithdrawalsPage + MerchantDetail |
| Liberar saques | `POST /api/v1/master/merchants/{id}/withdrawals/release` | `withdrawal.release` | WithdrawalsPage + MerchantDetail |

- Botões renderizados via `<RequirePermission>`; clique → `ConfirmDialog`
  (texto explícito da ação + lojista); sucesso → feedback; erro → `ErrorBanner`
  (`ApiError.message`); **403 → banner "sem permissão"** (a sessão permanece).
- Nenhuma confirmação no servidor além do guard da Spec 69 (ações reversíveis
  não exigem step-up — critério 8).

### D5.7 Autogestão de MFA (`/security`)

Sempre o **próprio perfil** (Spec 44 — o admin não gerencia TOTP de terceiros):

| Ação | Endpoint | Fluxo na UI |
|---|---|---|
| Ativar (passo 1) | `POST /api/v1/master/mfa/activate` | Exibe `otpauthUri` (QR) + `manualEntryKey` **1x** |
| Confirmar (passo 2) | `POST /api/v1/master/mfa/confirm` `{code}` | Primeiro TOTP → recovery codes exibidos **1x** |
| Desativar | `POST /api/v1/master/mfa/deactivate` `{code, password}` | Senha + TOTP (ou recovery) → 204 |
| Regenerar codes | `POST /api/v1/master/mfa/recovery-codes` `{code}` | Novos 1x |

`mfaActive` do `/me` alimenta o estado da tela (botão ativar/desativar).

### D5.8 Login + MFA

- Formulário: `username`, `password`, **campo TOTP opcional** (hint: "se o MFA
  estiver ativo, informe o código do app"). `MasterLoginRequest.totpCode` já é
  opcional; com MFA ativo a senha não basta → **401 uniforme, sem oráculo**
  (Spec 44) — a página mostra erro genérico "credenciais inválidas".
- `h1.auth-title` = "FoxCash" (hook do E2E, padrão portal).

## D6 — E2E browser (`e2e-tests/tests/master-console-ui.spec.js`)

Playwright, `test.describe.serial`, padrão do `portal-ui.spec.js` (requer a app
com `NYXCASH_CONSOLE_SERVE_FRONTEND=true` + `NYXCASH_PORTAL_SERVE_FRONTEND=true`
— o build do console deve existir em `static/console`). No CI, o job e2e do
`ci.yml` ganha a env `NYXCASH_CONSOLE_SERVE_FRONTEND: 'true'` (hoje só a do
portal está lá, perto de `NYXCASH_PORTAL_SERVE_FRONTEND`).

**Helper TOTP**: extrair `totpCode`/`totpAt` de `auth.spec.js` (RFC 6238, mesmo
algoritmo do `Totp.java`) para `e2e-tests/tests/helpers/totp.js`; `auth.spec.js`
passa a importar (migração mecânica de 2 funções + 1 import) e o novo spec
reusa. Seed de master via **SQL direto** (INSERT em `masters` com BCrypt fixo —
padrão do `auth.spec.js` B1; o seed `NYX_MASTER_SEED_*` não é garantido em CI).

Cenários (critério 10):

1. **Login sem MFA** → dashboard (transações + revenue) → `/merchants` (lista
   com filtro) → `/merchants/:id` (tabs earnings/fees/risk) → ação
   `suspend` (ConfirmDialog) → 204 → badge "suspenso" → `release` → 204.
2. **MFA**: `POST /master/mfa/activate` via API → `manualEntryKey` →
   `confirm` com `totpAt(secret, 0)` → login no browser **com** TOTP → 200 →
   dashboard. Login **sem** TOTP (MFA ativo) → 401 + permanece em `/console/login`
   com erro genérico (sem oráculo).
3. **403 não derruba sessão**: master sem `merchant.suspend` tenta suspender →
   403 `forbidden` → banner "sem permissão" → continua autenticado (navegação
   intacta; botão oculto por `usePermission`).
4. **Logout** → 204 → rota privada redireciona `/console/login` → API master sem
   token → 401 (e 401 no interceptor → volta ao login).

## D7 — Decisões de design (com racional)

| # | Decisão | Racional |
|---|---|---|
| D7.1 | Espelhos `/master/merchants` e `/master/lookup/ip/{ip}` **reusam** `AdminDashboardUseCase` | O use case não tem role embutida; a fronteira de role é o controller. Zero duplicação de regra de consulta; contratos admin intactos. |
| D7.2 | Ordem de build: **console depois do portal** | O `vite` do portal usa `emptyOutDir: true` no `static/` raiz e apagaria `static/console`. Regra documentada em scripts/CI. |
| D7.3 | CSP da SPA compartilhada (`portal \|\| console`) | Mesma política (inclui Turnstile, inerte no console); um único código path — sem risco de divergência de CSP entre SPAs. |
| D7.4 | Interceptor trata **só 401** como morte de sessão | 403 `forbidden`/409/etc são `ApiError` propagados — a sessão não cai (UX B-A8: o 403 é "sem permissão", não "deslogue"). |
| D7.5 | Campo TOTP **sempre presente** no login (opcional) | `totpCode` opcional no backend; sem ele a UI não saberia exigir TOTP (401 uniforme, sem oráculo — Spec 44). |
| D7.6 | `/me` sem guard de permissão (rota de leitura) | Todo master autenticado vê o próprio perfil; permissões protegem **ações** (Spec 69), não a auto-leitura. |
| D7.7 | Reconciliação **read-only** na fatia 1 (sem `POST /reconciliation/run`) | `run` é ação não-permissionada no catálogo da Spec 69; incluir exigiria decisão de produto — fica fora (Spec 72/futuro). |
| D7.8 | `usePermission` = apresentação; **backend é a autoridade** | A UI esconde, nunca autoriza; 403 do backend continua sendo o contrato real (deny-by-default, Spec 69). |

## D8 — Rastreabilidade (critérios ↔ design ↔ task)

| Critério (requirements) | Design | Task |
|---|---|---|
| 1 (`/me`) | D2.1 | F0 |
| 2 (`/master/merchants`) | D2.2 | F1 |
| 3 (`/master/lookup/ip/{ip}`) | D2.3 | F1 |
| 4 (SPA servida + flags + CSP) | D3, D4 | F2 |
| 5 (login + MFA no cliente) | D5.2, D5.3, D5.8 | F3 |
| 6 (UI RBAC-aware) | D5.4 | F3, F5 |
| 7 (telas de leitura) | D5.5 | F4 |
| 8 (ações de OPERATOR) | D5.6 | F5 |
| 9 (autogestão de MFA) | D5.7 | F5 |
| 10 (E2E browser) | D6 | F6 |
| 11 (sem regressão) | D2, D4 | F6 |
| 12 (dependência da Spec 69) | D2.1 | F0, F5 |
