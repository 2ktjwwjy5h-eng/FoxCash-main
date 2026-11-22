---
name: Tracking — painel (UI portal)
status: audited
---

# Spec 82 — Task: Tracking (painel)

## F0 — Página + resumo (critérios 1–2, 7) ✅

- [x] T0.1: `portal/src/pages/tracking/TrackingPage.tsx` + rota `/tracking` +
      item de menu (Layout) — `GET /api/v1/portal/tracking` (Spec 81) via
      `useAsync` sem blink (D2/D3). SPA paths do backend (SecurityConfig +
      `PortalSpaController`) ganharam `/tracking` e `/tracking/*` (full reload).
- [x] T0.2: `SummaryCard` ×3 — Perfis Ativos, Fluxos Rastreados (bots únicos),
      Total de Eventos com sucesso (somados da lista).
- [x] T0.3: Card do perfil — nome + `StatusBadge` (Ativo/Inativo), `ServiceBadge`
      (Pixel/TikTok/UTMify/Otimizey — só os configurados), chips dos bots
      vinculados (nome via `GET /api/v1/portal/bots`, Spec 74), `LogStatusLine`
      (X ok · Y erros — classes ok/erro por cor).
- [x] T0.4: Empty state ("Nenhum perfil de tracking ainda") + CTA "Criar perfil";
      `Spinner` no loading.

## F1 — Ações (critérios 3, 5) ✅

- [x] T1.1: Editar → `TrackingProfileModal` pré-preenchido (D3).
- [x] T1.2: Duplicar → `ConfirmDialog` `primary` → `POST /duplicate` (Spec 81).
- [x] T1.3: Ativar/Desativar → toggle → `POST /activate|deactivate`.
- [x] T1.4: Excluir → `ConfirmDialog` `danger` → `DELETE` (Spec 81).
- [x] T1.5: Sincronizar → `POST /{id}/sync` (Spec 81) → `SyncResultModal`
      (`{ service, ok, message }` com badges; falha de um não derruba os
      demais; `Spinner` durante a chamada).
- [x] T1.6: Todas com `SuccessBanner`/`ErrorBanner` + try/catch (sem unhandled
      rejection — Spec 73).

## F2 — Modal de configuração (critério 4) ✅

- [x] T2.1: `TrackingProfileModal` — `Field` nome (obrigatório); seções por
      serviço (`fieldset` Facebook Pixel+token, TikTok Code+token,
      UTMify/Otimizey token).
- [x] T2.2: **Tokens mascarados** — ao editar, campo vazio com placeholder
      `•••• (manter atual)`; enviar vazio = mantém o atual (PUT parcial da Spec
      81); pixel vazio = desativa o provedor (comportamento do audit da 81).
- [x] T2.3: `BotMultiSelect` — chips toggle dos bots (`GET /api/v1/portal/bots`,
      Spec 74) com `aria-pressed`.
- [x] T2.4: Validação — nome obrigatório; pixel/code sem token (e sem token
      existente) → aviso inline.

## F3 — Log + fechamento (critérios 6, 8–9) ✅

- [x] T3.1: `EventLogModal` — `GET /{id}/events` (Spec 81) paginado: evento,
      status OK/ERROR (badge), data, payload com "Ver detalhes" (`<details>`).
- [x] T3.2: Modais acessíveis (`useDialogFocus` — focus trap/Escape/autofocus/
      aria — Spec 73); skip-link herdado.
- [x] T3.3: **E2E** — criar perfil (badges), vincular bot (chips), editar com
      token mascarado (vazio não sobrescreve), sincronizar, excluir com
      confirmação, log paginado (ver §Auditoria).
- [x] T3.4: `npm run build` (tsc) **limpo no portal** (65 módulos) + backend
      **`mvn test` 1608/0/0** sem regressão; ordem de build respeitada
      (console rebuildado após o portal — `emptyOutDir` do portal apaga
      `static/console`, achado da Spec 73).
- [x] T3.5: `/spec-verify 82` e depois `/spec-audit 82` (critérios 1–9).

## §Implementação — decisões e desvios (registro)

1. **Camada puramente de apresentação** — nenhuma rota de backend nova; tudo
   sobre os endpoints da Spec 81 (`/api/v1/portal/tracking*`) e Spec 74
   (`/api/v1/portal/bots`).
2. **SPA paths**: `/tracking` e `/tracking/*` adicionados ao
   `spaPublicPaths()` (SecurityConfig) e ao `PortalSpaController` — full
   reload (F5) de `/tracking` serve o `index.html` (mesmo padrão do fix da
   Spec 80 para `/broadcasts`).
3. **Semântica dos tokens no modal** (contrato da Spec 81): campo token vazio
   = mantém o atual (placeholder mascarado); pixel/código vazio = desativa o
   provedor (o audit da 81 corrigiu o PUT para permitir isso).
4. **CSS**: classes novas em `styles.css` (`summary-cards`, `chip`, `trk-*`,
   `tag-primary`, `pagination-row`) — sem lib de UI (padrão do portal).

## §Auditoria

**`audited` 2026-08-14 — `/spec-audit` 9/9 critérios OK, 2 achados corrigidos, 0
abertos** (E2E `tracking.spec.js` **6/6 verde** com os mocks dos 4 provedores;
`npm run build` limpo no portal; `mvn clean test` tracking 38/38):

- **A1 — E2E não cobria as ações Duplicar e Ativar/Desativar (critérios 3 e 9):**
  o `tracking.spec.js` validava criar/editar/sincronizar/log/excluir, mas não o
  duplicar (ConfirmDialog) nem o toggle ativar/desativar. Adicionado o **teste 6**
  (duplicar com confirmação → cópia herda badges/chips; desativar → badge
  Inativo; reativar → Ativo; excluir a cópia). 3 ajustes de locator no caminho:
  o card original é resolvido por nome **exato** (`filter({ has: getByText(...,
  { exact: true }) })` — o `hasText` casaria também com a cópia "(copia)"), e o
  badge de status é `.trk-card-title .tag` (o card também tem 4 `.tag-primary`
  de serviço — strict mode).
- **A2 — `POST /duplicate` quebrava em runtime com `Unresolved compilation
  problem`:** o E2E expôs 500 no duplicar. Causa raiz: o `.class` de
  `TrackingProfileCommandService` no `target/` era bytecode de um compilador que
  **falhou parcialmente** (o método `duplicate` tinha `new Error("Unresolved
  compilation problem")` embutido — assinatura do ECJ). O build incremental do
  Maven não recompilou (mtime do `.class` ≥ source) e `mvn test` passava. O
  `mvn clean test` expôs o erro real: linha 129 passava `Set<UUID>` (do
  `findBotIdsByProfileId`) para `replaceBotLinks(List<UUID>)`. **Fix:**
  `replaceBotLinks` do service passou a aceitar `Collection<UUID>` (alinhado com
  a porta). **Padrão a seguir (reforça L4/L5):** qualquer suspeita de
  comportamento estranho de endpoint → `mvn clean` antes de culpar o código; um
  build incremental verde não prova que o source compila.

**E2E T3.3** (agora parte da auditoria): `tracking.spec.js` com mocks dos 4
provedores (base URLs via `NYX_TRACKING_*_BASE_URL`) — criar perfil, badges,
chips, editar com token mascarado, sincronizar, excluir, log paginado,
duplicar e ativar/desativar. Requer app dev rodando com os mocks (sessão de E2E
dedicada, padrão `e2e-tests/`).
