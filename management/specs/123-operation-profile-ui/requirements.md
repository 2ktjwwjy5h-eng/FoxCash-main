---
name: Perfil de operação UI — seletor de contexto de perfil (fase 1: fundação)
status: audited
references:
  - "management/vision.md#V90"
  - "management/specs/122-operation-profile-backend/requirements.md"
  - "management/specs/102-portal-shell-ui/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
  - "management/specs/46-portal-frontend/requirements.md"
---

# Spec 123 — Perfil de operação (UI, fase 1)

## O que entrega

O **seletor de contexto de perfil** no **HeaderGlobal** do portal (`Layout.tsx`,
Spec 102): o lojista alterna o **perfil de operação ativo** (ex.: "Padrão BR" ↔
"Global/Stripe") — cada perfil carrega a rota de gateway (cobrança Pix) e a frota
de bots. O estado persiste entre sessões e todas as chamadas autenticadas enviam
`X-Profile-ID`. **Fase 1 = fundação**: o seletor troca o contexto; o **filtro
rígido de dados por perfil** nos módulos chega na **Spec 124 (fase 2)**. Nenhum
dado é escondido nesta fase.

1. **`ProfileContext`** — estado global `{ profiles, activeProfileId,
   setActiveProfileId, setGatewayRoute, createProfile, refreshProfile }`;
   persistido em `localStorage` (`foxcash.portal.profile` = `{ activeProfileId }`);
   carrega `GET /api/v1/portal/profile` no login/layout.
2. **`ProfileSelector`** — componente no **HeaderGlobal** (`Layout.tsx`):
   dropdown de perfis (`profile.name`); trocar perfil → contexto + localStorage
   (sem API — o backend recebe o perfil em cada request via header); **"criar
   perfil"** (POST) e **trocar a rota de gateway do perfil** (dropdown
   `availableRoutes` → PATCH) no mesmo seletor ou na página Segurança/Config.
3. **Header `X-Profile-ID`** — `apiClient` anexa o perfil ativo em todas as
   chamadas autenticadas (documentado: **seleção de contexto validada no
   backend** — Spec 122; nunca decide rota de cobrança).
4. **Bot do perfil ativo = pré-seleção (escopo de dados)** —
   `BroadcastEditorPage`: novo broadcast (sem `botId`) usa o bot do perfil ativo;
   `BotsPage`: destaque dos bots do perfil ativo. Sem vínculo bot→gateway além
   do perfil.
5. **Estados** — loading do profile, `ErrorBanner` em falha do PATCH/POST,
   desabilitado durante saving (padrão UX Spec 73).

## Contexto (por que agora)

O backend (Spec 122) entrega a entidade `merchant_profiles` (N por lojista, rota
de gateway + frota de bots por perfil), os endpoints `GET/POST/PATCH
/api/v1/portal/profile` e o header `X-Profile-ID`. Falta a porta de entrada: hoje
o portal não tem estado de perfil além do auth (`auth.tsx` guarda só `me`); o
`apiClient` anexa só o Bearer; o `Layout.tsx` tem o HeaderGlobal (Spec 102) —
ponto natural do seletor; o `BroadcastEditorPage` já tem seletor de bot
(`draft.botId` de `/api/v1/portal/bots`). Decisões do usuário (2026-08-15): N
perfis por lojista (entidade); fase 1 = fundação (o filtro rígido é a Spec 124);
só a estrutura (o switch de rota passa a valer com um 2º adquirente real
configurado).

## Critérios de aceite

1. **Seletor no HeaderGlobal** — dropdown de perfis (`profile.name`, default
   primeiro); acessível (label/aria — Spec 73); visível só para logado (rota
   privada garante); ação "criar perfil" disponível.
2. **Persistência** — `activeProfileId` em `localStorage`; ao abrir o portal, o
   seletor reflete o salvo; se o salvo não existe mais (perfil removido), cai
   para o default sem erro.
3. **Troca de perfil** — contexto + localStorage; sem API; todas as chamadas
   subsequentes levam `X-Profile-ID: <activeProfileId>`.
4. **Rota de gateway do perfil** — alterável no seletor e/ou na página
   Segurança/Config: `PATCH /api/v1/portal/profile/{id} {gatewayRoute}`; 200 →
   contexto atualizado; 400 `unknown_gateway_route` → `ErrorBanner` com mensagem
   do backend; falha de rede → o contexto NÃO muda (rollback local).
5. **Criar perfil** — `POST /api/v1/portal/profile {name}`; sucesso →
   `refreshProfile`; 400 `invalid_name` → `ErrorBanner`; o perfil ativo NÃO muda
   automaticamente (o usuário troca depois).
6. **Bot do perfil ativo** — novo broadcast (sem `botId`) pré-seleciona o bot do
   perfil ativo (primeiro bot do perfil; perfil sem bots → vazio); `BotsPage`
   destaca os bots do perfil ativo; trocar o perfil reflete na próxima criação.
7. **Regressão** — saque/PVS/broadcast/cobrança seguem funcionando; o seletor não
   quebra o `Layout` em telas com 1 perfil só; `npm run build` verde.
8. **Testes** — componente (render/change/persistência/rollback/criar 2º perfil)
   + E2E (`profile.spec.js`): abrir portal → criar 2º perfil → trocar → header
   `X-Profile-ID` presente → novo broadcast pré-seleciona bot do perfil.

## Dependências

- **Spec 122** (backend) — `GET/POST/PATCH /api/v1/portal/profile`,
  `availableRoutes`, semântica do header (seleção validada, nunca rota).
- **Spec 102** (shell) — HeaderGlobal (`Layout.tsx`) — ponto do seletor.
- **Spec 73** (UX) — `Card`/`ErrorBanner`/`Field`/`Spinner`/`useAsync`, estados
  loading/erro.
- **Spec 46** (portal) — `apiClient`, `useAuth`, `Layout`.
- **Spec 74** (bots) — `GET /api/v1/portal/bots` (já consumido pelo
  `BroadcastEditorPage`); bots por perfil via `GET profile`.
- **Spec 124** (fase 2, planejada) — filtro rígido de dados por perfil.

## Fora de escopo

- **Backend do perfil** — Spec 122.
- **Filtro rígido de dados por perfil** — Spec 124 (fase 2); nesta fase nada é
  escondido.
- **Adapter Stripe novo** — mecanismo apenas; seletor inerte até 2º adquirente.
- **Vínculo bot→gateway direto** — o perfil é o único elo (rota + frota).
- **Master console** — o master continua na Spec 98/100.
