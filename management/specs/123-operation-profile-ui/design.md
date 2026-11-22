---
name: Perfil de operação UI — seletor de contexto de perfil (fase 1: fundação)
status: audited
---

# Spec 123 — Design: Perfil de operação (UI, fase 1)

## D1 — Conceito

O portal ganha um **perfil de operação ativo** como estado global: um seletor no
**HeaderGlobal** (Spec 102, `Layout.tsx`) alterna entre os `merchant_profiles` do
lojista (Spec 122). O perfil ativo sai em toda chamada autenticada como
`X-Profile-ID` (seleção de contexto — o backend valida pertinência; nunca decide
rota de cobrança). **Fase 1**: o seletor troca o contexto e pré-seleciona dados;
**nenhum módulo esconde dados** (filtro rígido = Spec 124).

### D1.1 Decisões do usuário (2026-08-15, revisadas) → UI

| Decisão | Regra de UI |
|---|---|
| N perfis por lojista (entidade) | dropdown de perfis (`profile.name`); "criar perfil" (POST) disponível |
| Fase 1 = fundação | troca de contexto local + header; SEM filtro rígido (Spec 124) |
| Rota de gateway por perfil | PATCH `/portal/profile/{id}` no seletor/Segurança; PADRAO + availableRoutes |
| Só a estrutura | sem 2º adquirente real, o switch de rota fica pronto (lista PADRAO + configurados) |
| Bot do perfil = escopo de dados | pré-seleção em broadcast + destaque em bots (do perfil ativo) |
| Só cobrança | o seletor é de COBRANÇA (nada de saque/payout) |

## D2 — `ProfileContext` (novo)

`portal/src/profile.tsx`:

```tsx
export interface OperationProfile {
  id: string;
  name: string;
  gatewayRoute: string;        // "PADRAO" | key
  availableRoutes: string[];   // ["PADRAO", ...]
  bots: StoreBot[];            // bots do perfil (GET /portal/profile, Spec 122)
}

interface ProfileContextValue {
  profiles: OperationProfile[];
  loading: boolean;
  activeProfileId: string | null;
  setActiveProfileId: (id: string) => void;              // só local
  setGatewayRoute: (profileId: string, route: string) => Promise<void>; // PATCH
  createProfile: (name: string) => Promise<void>;        // POST
  refreshProfile: () => Promise<void>;
}
```

- **Provider** montado dentro do `AuthProvider` (só logado); `refreshProfile` =
  `GET /api/v1/portal/profile` (chamado no `Layout` junto do `refreshMe`).
- **`localStorage`** key `foxcash.portal.profile` = `{ activeProfileId }` (token
  fica em sessionStorage — Spec 46; a **preferência de perfil** é persistente por
  design, como um seletor de país).
- **`setActiveProfileId(id)`** — só localStorage + estado; sem API (o backend
  recebe o perfil em cada request via header).
- **`setGatewayRoute(profileId, route)`**:
  1. `PATCH /api/v1/portal/profile/{profileId} {gatewayRoute: route}` (Spec 122).
  2. 200 → atualiza `profiles[i].gatewayRoute` + contexto.
  3. Erro (400 `unknown_gateway_route`/rede) → **não muda** o contexto (rollback
     local) + `ErrorBanner` (mensagem do backend).
- **`createProfile(name)`** — `POST /api/v1/portal/profile {name}` (Spec 122) →
  `refreshProfile`; o perfil ativo NÃO muda automaticamente (o usuário troca
  depois).
- **Sanidade no load**: `activeProfileId` salvo que não existe em `profiles` →
  cai para o default (e reescreve o storage) sem erro.

## D3 — `apiClient` com header `X-Profile-ID`

`portal/src/apiClient.ts` — no `request()` autenticado:

```ts
if (auth) {
  const token = getToken();
  if (token) requestHeaders['Authorization'] = `Bearer ${token}`;
  const saved = localStorage.getItem(PROFILE_KEY);       // {activeProfileId}
  if (saved) {
    try { const p = JSON.parse(saved);
      if (p?.activeProfileId) requestHeaders['X-Profile-ID'] = p.activeProfileId;
    } catch { /* storage corrompido → ignora */ }
  }
}
```

- Exportar `PROFILE_KEY` do `profile.tsx` (evita duplicar string).
- **Documentado no código**: seleção de contexto — o backend (Spec 122) valida
  pertinência e **nunca** o usa para decidir rota.
- Rotas públicas (`PUBLIC_PATHS`) não enviam (o `auth: false` já exclui).

## D4 — `ProfileSelector` no HeaderGlobal (novo componente)

`portal/src/components/ProfileSelector.tsx`:

- Renderizado no **HeaderGlobal** do `Layout.tsx` (Spec 102 — área do header,
  junto da saudação/faturamento), antes do menu; visível sempre no shell logado.
- **Dropdown de perfis** (`select aria-label="Perfil de operação"`): opções =
  `profiles` (`name`, default primeiro); value = `activeProfileId`; onChange →
  `setActiveProfileId(value)`.
- **Ação "criar perfil"** (botão "+"): `Dialog` com campo de nome →
  `createProfile(name)`; erro (400 `invalid_name`) → `ErrorBanner` (padrão Spec
  73).
- **Dropdown de rota do perfil** (no seletor e/ou na página Segurança): opções =
  `activeProfile.availableRoutes` (PADRAO primeiro); value =
  `activeProfile.gatewayRoute`; onChange → `setGatewayRoute(activeProfileId,
  value)`.
- Estados: `loading` (primeiro load) → `Spinner` pequeno; erro do PATCH/POST →
  `ErrorBanner` compacto; `saving` → desabilitado com feedback (padrão Spec 73).
- Acessibilidade: label explícito, `aria-live` no erro.

## D5 — Bot do perfil ativo como pré-seleção (escopo de dados)

- **`BroadcastEditorPage.tsx`**: novo draft sem `botId` (rota "Novo" / prefill sem
  `storeBotId`) inicializa com o **bot do perfil ativo** (`activeProfile.bots[0]`
  — KISS: primeiro bot do perfil); se o perfil ativo não tem bots, mantém vazio.
  **Não altera** edição de existente.
- **`BotsPage.tsx`**: bots do perfil ativo recebem destaque (`is-active` + badge
  "ativo"); os demais continuam visíveis (fase 1 não esconde nada).
- **`SecurityPage.tsx`** (Segurança/Config): seção "Perfil de operação" exibe a
  rota atual do perfil ativo (read) + dropdown para trocar (PATCH).

## D6 — Arquivos

| Arquivo | Mudança |
|---|---|
| `portal/src/profile.tsx` | novo (`ProfileProvider`/`useProfile` + `PROFILE_KEY`) |
| `portal/src/apiClient.ts` | header `X-Profile-ID` (D3) |
| `portal/src/components/ProfileSelector.tsx` | novo (D4) |
| `portal/src/components/Layout.tsx` | renderiza o seletor no HeaderGlobal |
| `portal/src/pages/broadcasts/BroadcastEditorPage.tsx` | pré-seleção do bot do perfil (D5) |
| `portal/src/pages/BotsPage.tsx` | destaque dos bots do perfil ativo (D5) |
| `portal/src/pages/SecurityPage.tsx` | rota do perfil ativo (read + PATCH) |

## D7 — Testes

- **Componente** (padrão dos testes do portal): render com 1 perfil (só default);
  change do perfil → contexto + localStorage; criar 2º perfil (POST → refresh);
  PATCH de rota falho → rollback + banner; persistência no reload; sanidade
  (perfil removido → default).
- **`apiClient`** — header `X-Profile-ID` presente quando há perfil ativo;
  ausente em rotas públicas e sem perfil.
- **E2E** (`profile.spec.js`, padrão Spec 73): login → seletor visível → criar 2º
  perfil → trocar → `GET /portal/profile` reflete (assert no backend) → trocar
  rota do perfil → PATCH → reload mantém → novo broadcast pré-seleciona bot do
  perfil.

**Âncoras validadas na spec:** `Layout.tsx` (HeaderGlobal — Spec 102, ponto do
seletor), `auth.tsx` (`AuthProvider`/`useAuth`/`PortalMe`), `apiClient.ts`
(`request()` autenticado — ponto do header), `BroadcastEditorPage.tsx` (já tem
`draft.botId` + `GET /api/v1/portal/bots`), `BotsPage.tsx`, `SecurityPage.tsx`,
`GET/POST/PATCH /api/v1/portal/profile` (Spec 122), `OperationProfile`
(id/name/gatewayRoute/availableRoutes/bots), `StoreBot` (displayName).
