---
name: Escopo do perfil de operação (Geral/Bot-Only/Gateway-Only) + lente de contexto Bot/Gateway no portal
status: audited
---

# Spec 139 — Design: Escopo do perfil + lente Bot/Gateway

## D1 — Conceito

O perfil de operação (Spec 122) ganha um **escopo** — `GENERAL` | `BOT_ONLY` |
`GATEWAY_ONLY` — que **classifica** o perfil sem quebrar o modelo auditado
(perfil continua UMA entidade: rota + frota). No portal, um **seletor de lente**
(Bot | Gateway) no shell aparece **antes** do seletor de perfil (Spec 123) e
filtra os perfis exibidos por escopo relacionado. O "criar perfil" passa a pedir
**especificações** (escopo + rota e/ou bots), a rota ganha **nome amigável** e a
troca de perfil dá **feedback visual**. Tudo sem esconder dados (mesmo contrato
da fase 1 — Spec 124 é a fase 2).

### D1.1 Decisões do usuário (2026-08-16) → regras

| Decisão | Regra |
|---|---|
| Perfil nasce com escopo definido | `scope`: `GENERAL` / `BOT_ONLY` / `GATEWAY_ONLY` (migration V76) |
| Painel vira lente baseada no escopo | seletor de contexto (Bot\|Gateway) antes do seletor de perfil |
| Perfis da lente | Bot → `GENERAL` + `BOT_ONLY`; Gateway → `GENERAL` + `GATEWAY_ONLY` |
| Criar perfil com especificações | modal limpo: **nome + Tipo de Operação** (radio: 🤖 Somente Bot / 🌐 Somente Gateway / ⚡ Completo, com descrição de uma linha); **sem campos condicionais** — rota/frota atribuídas depois no painel da lente |
| Bot e Gateway são contextos explícitos | lente = estado de UI (localStorage), não muda modelo/backend |
| Nomes amigáveis de rota | `PADRAO` → "Padrão (por risco)", `pagarme` → "Pagar.me", etc. |
| Feedback visual na troca | banner transitório (nome + escopo), acessível |

## D2 — Backend: escopo no modelo (migration V76)

`src/main/resources/db/migration/V76__operation_profile_scope.sql`:

```sql
ALTER TABLE merchant_profiles
  ADD COLUMN scope VARCHAR(20) NOT NULL DEFAULT 'GENERAL';
ALTER TABLE merchant_profiles
  ADD CONSTRAINT chk_merchant_profiles_scope
  CHECK (scope IN ('GENERAL','BOT_ONLY','GATEWAY_ONLY'));
```

- **Backfill**: `DEFAULT 'GENERAL'` aplica a todos os perfis existentes — sem
  UPDATE explícito (idempotente por natureza; migração não remove a constraint
  no rollback, padrão das migrações do projeto).
- **`store_bots` inalterada** — `profile_id` continua a âncora da frota.

### D2.1 `MerchantProfile` (domínio) — `scope`

`domain/model/profile/MerchantProfile.java`:

- Novo campo `ProfileScope scope` (enum `GENERAL`, `BOT_ONLY`, `GATEWAY_ONLY`;
  `domain/model/profile/ProfileScope.java`).
- `withScope(ProfileScope)` / validação no construtor/`restore` (default
  `GENERAL`). Nenhum outro campo muda.
- Regras de consistência (defesa no serviço, não no enum):
  - `BOT_ONLY` → `gatewayRoute` sempre `null` (PADRAO).
  - `GATEWAY_ONLY` → frota vazia (desanexada).

## D3 — Backend: serviços e endpoints

### D3.1 `OperationProfileService` (Spec 122) — `scope` nos DTOs

`application/service/OperationProfileService.java`:

- **`create(merchantId, CreateProfileRequest)`** — request ganha `scope`
  (default `GENERAL`). Validações:
  - escopo fora do enum → 400 `invalid_scope`;
  - `BOT_ONLY` + `gatewayRoute` presente → 400 `route_not_allowed_for_scope`.
- **`update(merchantId, profileId, UpdateProfileRequest)`** — request ganha
  `scope`:
  - `BOT_ONLY`: `gatewayRoute` forçado a `null` (rota enviada junto → 400
    `route_not_allowed_for_scope`); `route_changed_at` limpo.
  - `GATEWAY_ONLY`: frota **desanexada na mesma transação**
    (`store_bots.profile_id = NULL` para os bots do perfil).
  - escopo inválido → 400 `invalid_scope`.
  - Auditoria existente preservada (`PROFILE_UPDATED`/`GATEWAY_ROUTE_CHANGED`).
- **`replaceBots(merchantId, profileId, botIds)`** — **endpoint novo**
  `PUT /api/v1/portal/profile/{id}/bots`:
  1. Carrega o perfil (404 `operation_profile_not_found`); se `scope ==
     GATEWAY_ONLY` → 400 `bots_not_allowed_for_scope`.
  2. Valida cada `botId` (pertence ao lojista, não deletado) → 400
     `bot_not_found` (Spec 74 `StoreBotNotFoundException`).
  3. **Substituição total**: `profile_id` dos bots do perfil → `NULL`;
     `botIds` → `profile_id = {id}` (transação única, desanexa e anexa).
  4. Auditoria `PROFILE_BOTS_CHANGED` (Spec 41) com contagem antes/depois.
  5. Retorna o `ProfileResponse` atualizado.

### D3.2 `ProfileResponse` (GET /api/v1/portal/profile)

Ganha `scope: "GENERAL" | "BOT_ONLY" | "GATEWAY_ONLY"`. Campos existentes
intactos: `id`, `name`, `gatewayRoute`, `availableRoutes`, `bots`,
`activeProfileId`.

### D3.3 `RoutingPolicyResolver` — defesa por escopo

`infrastructure/outbound/pix/RoutingPolicyResolver.java` (Spec 91):

- `resolveProviders(merchantId, profileId)`: perfil `BOT_ONLY` → **nunca**
  aplica override, mesmo com rota legada/stale no banco (rota = por risco
  sempre). GENERAL/GATEWAY_ONLY seguem o fluxo atual.
- `resolvePolicyName(...)`: retorna `"override"` apenas para
  GENERAL/GATEWAY_ONLY com rota válida no catálogo; `BOT_ONLY` → nome de
  política por risco.
- `PixProviderRouter` (Spec 91) inalterado — o branch mora no resolver.

## D4 — Frontend: `ProfileContext` com lente

`portal/src/profile.tsx` (Spec 123):

```tsx
export type Lens = 'BOT' | 'GATEWAY';
export type ProfileScope = 'GENERAL' | 'BOT_ONLY' | 'GATEWAY_ONLY';

export interface OperationProfile {
  id: string;
  name: string;
  scope: ProfileScope;          // novo
  gatewayRoute: string;         // "PADRAO" | key
  availableRoutes: string[];    // ["PADRAO", ...]
  bots: StoreBot[];
}

interface ProfileContextValue {
  profiles: OperationProfile[];
  loading: boolean;
  activeProfileId: string | null;
  lens: Lens;                                        // novo
  setLens: (l: Lens) => void;                        // novo
  visibleProfiles: OperationProfile[];               // novo (derivado)
  setActiveProfileId: (id: string) => void;
  setGatewayRoute: (profileId: string, route: string) => Promise<void>;
  createProfile: (input: { name: string; scope: ProfileScope }) => Promise<void>; // mudou (modal limpo: sem rota/bots)
  assignBots: (profileId: string, botIds: string[]) => Promise<void>;   // novo (PUT)
  refreshProfile: () => Promise<void>;
}
```

- **`lens` persistido em `localStorage`** (`foxcash.portal.lens`, exportar
  `LENS_KEY`; chave nova — não reusa `foxcash.portal.profile`). Default `'BOT'`
  (primeira superfície de compra do lojista). Sanidade no load: valor inválido →
  `'BOT'`.
- **`visibleProfiles`** (derivado, memo): `lens === 'BOT'` → `scope ∈
  {GENERAL, BOT_ONLY}`; `lens === 'GATEWAY'` → `scope ∈ {GENERAL, GATEWAY_ONLY}`.
- **`setLens(l)`** — grava storage + estado; se `activeProfileId` não está em
  `visibleProfiles` da nova lente → troca para o primeiro visível (ou `null` se
  nenhum) e reescreve `foxcash.portal.profile`. Sem chamada de API (lente é
  estado de UI; o header `X-Profile-ID` continua saindo do perfil ativo).
- **`createProfile(input)`** — `POST /api/v1/portal/profile {name, scope}`
  (D3.1; **modal limpo** — sem rota/bots no request). Rota e frota são
  atribuídas depois, no painel do contexto da lente (D5: PATCH rota /
  "gerenciar bots"). Sucesso → `refreshProfile`. O perfil ativo **não** muda
  automaticamente (contrato Spec 123 preservado).
- **`assignBots(profileId, botIds)`** — `PUT /api/v1/portal/profile/{id}/bots`
  (D3.1); 200 → atualiza `profiles[i].bots`; erro → rethrow (rollback local) +
  `ErrorBanner`.
- **`ROUTE_LABEL`** — mapa de rótulos exportado: `PADRAO` → "Padrão (por
  risco)", `pagarme` → "Pagar.me", `asaas` → "Asaas", `mock` → "Mock (teste)",
  `stripe` → "Stripe". Helper `routeLabel(route)` com fallback (chave em caixa
  alta). A API continua devolvendo chaves; rótulo é exibição.
- **`SCOPE_LABEL`** — `GENERAL` → "⚡ Completo (Bot + Gateway integrados)",
  `BOT_ONLY` → "🤖 Somente Bot (foco em chat e automações)", `GATEWAY_ONLY` →
  "🌐 Somente Gateway (foco em páginas web e tráfego)".

## D5 — Frontend: seletor 2 níveis no HeaderGlobal

`portal/src/components/ProfileSelector.tsx` (Substitui o dropdown único da Spec
123; continua no HeaderGlobal do `Layout.tsx`):

1. **Nível 1 — contexto (lente)**: `segmented` (dois botões, `aria-pressed`)
   "Bot" | "Gateway" → `setLens`. Descrição curta: "Contexto de operação".
2. **Nível 2 — perfil**: dropdown de perfis (`visibleProfiles`); cada opção
   mostra `name` + label do escopo (`SCOPE_LABEL`, ex.: "Padrão BR — ⚡
   Completo"). Trocar → `setActiveProfileId` + **banner transitório** (D6).
3. **Painel do contexto** (abaixo do dropdown, resumo do perfil ativo):
   - Lente **Bot**: chips da frota (`activeProfile.bots` —
     avatar/`displayName`/status) + ação "gerenciar bots" → `Dialog` com
     multi-select de bots do lojista (`GET /api/v1/portal/bots`, Spec 74) →
     `assignBots`. Se `activeProfile.scope === GATEWAY_ONLY` (impossível na
     lente Bot, mas defesa): mensagem "sem frota neste perfil".
   - Lente **Gateway**: `ROUTE_LABEL(activeProfile.gatewayRoute)` + texto de
     ajuda ("A rota define qual adquirente processa o Pix deste perfil") +
     dropdown de rota quando `availableRoutes.length > 1` (PATCH — contrato
     Spec 123 preservado).
   - Perfil sem dados no contexto (ex.: BOT_ONLY na lente Bot sem bots):
     estado vazio explicativo ("Este perfil é só de bot e ainda não tem bots na
     frota").
4. **Ação "criar perfil"** ("+"): `Dialog` "Criar Novo Perfil" — modal
   limpo com nome + Tipo de Operação (D5.1).
5. **Estados**: `loading` → Spinner pequeno; `saving` (PATCH/POST/PUT) →
   desabilitado; erro → `ErrorBanner` (padrão Spec 73) com rollback local
   (contexto não muda).

### D5.1 Dialog "Criar Novo Perfil" (modal limpo)

- Campo **nome** (`Field`, Spec 73).
- **Tipo de Operação** (radio, 3 opções com descrição de uma linha —
  `SCOPE_LABEL`):
  - 🤖 **Somente Bot** — "Foco em chat e automações" (→ `BOT_ONLY`).
  - 🌐 **Somente Gateway** — "Foco em páginas web e tráfego" (→
    `GATEWAY_ONLY`).
  - ⚡ **Completo** — "Bot + Gateway integrados" (→ `GENERAL`, default).
- **Sem campos condicionais** no modal: rota e frota de bots **não** são
  pedidas aqui — são atribuídas depois, no painel do contexto da lente (D5:
  PATCH rota na lente Gateway; "gerenciar bots"/PUT na lente Bot).
- Submit: `createProfile({name, scope})` → `POST {name, scope}` (default
  `GENERAL` se nada selecionado). Erros do backend (400 `invalid_scope`) →
  `ErrorBanner`. Após sucesso → `refreshProfile`; perfil ativo não muda.

### D5.2 Tooltip de resumo do perfil (dica flutuante)

**Motivação:** o `<select>`/`<option>` nativo (Spec 123) **não suporta** tooltip
por item de forma confiável (a maioria dos browsers ignora `title` em
`<option>`). Para a dica flutuante funcionar em hover **e** foco, o nível 2 do
seletor (dropdown de perfis) deixa de ser `<select>` nativo e vira um **menu
customizado** (`button` trigger + `listbox` de `option`s), seguindo o padrão
ARIA combobox/menu (Spec 73).

- **Trigger**: `button` mostrando `activeProfile.name` (+ label do escopo
  compacto); `aria-haspopup="listbox"`, `aria-expanded`.
- **Opções**: `role="option"` por perfil de `visibleProfiles`, com `name` +
  `SCOPE_LABEL`. Navegação por teclado (↑/↓ + Enter/Esc), padrão ARIA.
- **Tooltip por opção** (`role="tooltip"`), disparado por **hover (mouse)** e
  **foco (teclado)** — sem delay longo (≤ 200ms para abrir; fecha em blur/leave).
  Posicionado ao lado da opção (sem cortar o shell; se estourar o viewport,
  inverte para o lado oposto).
- **Conteúdo do tooltip** (dados já existentes em `OperationProfile` — **sem
  chamada extra de API**):
  - **Escopo**: `SCOPE_LABEL` (ex.: "⚡ Completo (Bot + Gateway integrados)").
  - **Bots vinculados**: chips compactos (avatar + `displayName`, até ~5, com
    "+N" se houver mais); **vazio** → "sem bots na frota" (esperado para
    `GATEWAY_ONLY`).
  - **Rota de gateway**: `ROUTE_LABEL(activeProfile.gatewayRoute)` (ex.:
    "Pagar.me"); `BOT_ONLY` → "rota por risco (padrão)"; `GATEWAY_ONLY` sem
    rota → "Padrão (por risco)".
- **Acessibilidade**: cada `option` recebe `aria-describedby` apontando para o
  `id` do tooltip (o leitor de tela anuncia o resumo ao focar a opção);
  tooltip é texto simples, sem interação (não rouba foco).
- **Escopo (KISS)**: tooltip **apenas** no dropdown de perfis (nível 2), como
  pedido; o dropdown de rota da lente Gateway e os demais controles não ganham
  tooltip nesta spec. Não cria componente genérico de tooltip reutilizável — a
  implementação fica local ao `ProfileSelector` (se um 2º uso aparecer, vira
  componente no `ui.tsx`).

## D6 — Feedback visual na troca de perfil

- Banner transitório no shell (`aria-live="polite"`, auto-dismiss ~3s):
  "Perfil ativo: **<nome>** · <SCOPE_LABEL>". Implementado no `ProfileSelector`
  (área do shell, padrão Spec 73 — reutilizar `Card`/`useAsync`).
- Estados `saving` desabilitam os controles (lente + perfil + rota + criar).
- Trocar a **lente** também dispara o banner ("Contexto: Bot" / "Contexto:
  Gateway") quando o perfil ativo muda junto.

## D7 — Escopo-aware em telas existentes

- **`SecurityPage.tsx`** — card "Perfil de operação" mostra o **escopo** do
  perfil ativo (read); o dropdown de troca de rota só é renderizado quando
  `activeProfile.scope !== 'BOT_ONLY'`. Sem mudança de contrato.
- **`BotsPage.tsx`** — mantém o destaque/badge dos bots do perfil ativo (Spec
  123); fase 1 não esconde nada.
- **`BroadcastEditorPage.tsx`** — pré-seleção `activeProfile.bots[0]`
  inalterada; se o perfil ativo é `GATEWAY_ONLY` (sem bots), mantém vazio.
- **`apiClient.ts`** — inalterado (header `X-Profile-ID` continua saindo do
  perfil ativo via `foxcash.portal.profile`).

## D8 — Auditoria

`audit_type` novo `PROFILE_BOTS_CHANGED` (Spec 41) — payload `{profileId,
beforeCount, afterCount}`.

## D9 — Arquivos

| Arquivo | Mudança |
|---|---|
| `src/main/resources/db/migration/V76__operation_profile_scope.sql` | novo (D2) |
| `src/main/java/.../domain/model/profile/ProfileScope.java` | novo (D2.1) |
| `src/main/java/.../domain/model/profile/MerchantProfile.java` | campo `scope` (D2.1) |
| `src/main/java/.../application/service/OperationProfileService.java` | scope em create/update + `replaceBots` (D3.1) |
| `src/main/java/.../infrastructure/outbound/pix/RoutingPolicyResolver.java` | defesa BOT_ONLY (D3.3) |
| DTO/controller de profile (Spec 122) | `scope` no request/response + rota `PUT /{id}/bots` |
| `portal/src/profile.tsx` | `lens`/`setLens`/`visibleProfiles`/`assignBots`/`createProfile(input)` + `ROUTE_LABEL`/`SCOPE_LABEL`/`LENS_KEY` (D4) |
| `portal/src/components/ProfileSelector.tsx` | seletor 2 níveis (menu customizado) + painel do contexto + dialog criar limpo + **tooltip de resumo do perfil** (D5/D5.2/D6) |
| `portal/src/pages/SecurityPage.tsx` | escopo read + rota só se != BOT_ONLY (D7) |
| `portal/src/pages/BotsPage.tsx` | sem mudança funcional (revisar badge) (D7) |

## D10 — Testes

- **Backend (JUnit, padrão Spec 122/123)**: migration V76 (scope default +
  CHECK); create com scope inválido → `invalid_scope`; BOT_ONLY + rota →
  `route_not_allowed_for_scope`; PATCH para BOT_ONLY força rota null e para
  GATEWAY_ONLY desanexa frota (transação); `replaceBots` substitui frota, lista
  vazia esvazia, bot de outro lojista/deletado → `bot_not_found`, GATEWAY_ONLY →
  `bots_not_allowed_for_scope`, auditoria `PROFILE_BOTS_CHANGED`; resolver com
  BOT_ONLY + rota stale → política por risco (regressão Spec 91).
- **Frontend**: sem infra de testes no portal (documentado na Spec 123, T1.2);
  cobertura via `npm run build` + regressão manual/E2E (quando infra existir,
  padrão Spec 123 T4).
- **E2E** (padrão Spec 123 T4): login → lente Bot → criar perfil BOT_ONLY com
  bots → lente Gateway → criar GATEWAY_ONLY com rota → trocar lente → perfis
  filtrados → trocar perfil → banner → `X-Profile-ID` reflete → recarregar
  mantém lente + perfil.

**Âncoras validadas na spec:** `merchant_profiles` + `store_bots.profile_id`
(Spec 122/74); `OperationProfileService`/`MerchantProfile.gatewayRoute`
existem; `RoutingPolicyResolver`/`PixProviderRouter` (Spec 91);
`ProfileSelector.tsx`/`profile.tsx` (Spec 123 — `gatewayRoute`/
`availableRoutes`/`setGatewayRoute`); `SecurityPage.tsx`/`BotsPage.tsx`/
`BroadcastEditorPage.tsx`; migrações até **V75** (V76 livre);
`apiClient.ts` (`X-Profile-ID`); auditoria `PROFILE_*` (Spec 41).
