---
name: Frontend UX — fluxos e usabilidade (console + portal)
status: audited
---

# Spec 73 — Task: Frontend UX (console + portal)

> **Pré-requisito**: Specs 71/72 `audited` (console), Spec 46 `audited`
> (portal). Sem mudança de backend — nenhum contrato HTTP novo. Verificação:
> `npm run build` (console + portal) + `mvn -o clean test` + E2E existente.

## F0 — Fundação `ui.tsx` (ambos) — critérios 1, 8, 11

- [x] T0.1: `console/src/components/ui.tsx` — `ErrorBanner` ganha
      `role="alert"`; `SuccessBanner` ganha `role="status"`.
- [x] T0.2: `portal/src/components/ui.tsx` — `SuccessBanner` NOVO
      (paridade console) com `role="status"`; `ErrorBanner` ganha
      `role="alert"`.
- [x] T0.3: `useAsync` (ambos) — mantém `data` anterior durante reload;
      `loading=true` só quando `data === null` (primeiro carregamento).
- [x] T0.4: `copyToClipboard(text): Promise<boolean>` (ambos `ui.tsx`) —
      `navigator.clipboard.writeText` com try/catch.

## F1 — Feedback de ações (ambos) — critérios 1, 2, 11

- [x] T1.1: `portal/src/pages/WebhooksPage.tsx` — feedback de sucesso em
      `handleCreate` ("Webhook cadastrado.") e `toggleActive` ("Webhook
      ativado/desativado."); `toggleActive` com try/catch → `ErrorBanner`.
- [x] T1.2: `portal/src/pages/CredentialsPage.tsx` — feedback em `revoke`
      ("Chave revogada.") + try/catch → `ErrorBanner`.
- [x] T1.3: `portal/src/pages/DestinationsPage.tsx` — feedback em
      `handleCreate` ("Destino cadastrado.") e `toggleActive` ("Destino
      ativado/desativado."); `toggleActive`/`updateLabel` com try/catch →
      `ErrorBanner`.
- [x] T1.4: `console/src/pages/ProfilesPage.tsx` — botão "Copiar" na senha
      temporária (`.secret-box`) com "Copiado!" ~2s.
- [x] T1.5: `portal/src/pages/ChargePage.tsx` — botão "Copiar" no copia-e-cola
      com "Copiado!" ~2s.
- [x] T1.6: `portal/src/pages/CredentialsPage.tsx` — botão "Copiar" na chave
      gerada com "Copiado!" ~2s.
- [x] T1.7: `console/src/pages/SecurityPage.tsx` — botão "Copiar" nos recovery
      codes (todos) com "Copiado!" ~2s.

## F2 — Diálogos acessíveis — critérios 3, 4, 5

- [x] T2.1: hook `useDialogFocus` (console — `components/ConfirmDialog.tsx` ou
      `hooks/`) e aplicado no `ConfirmDialog`: focus trap (Tab cíclico),
      Escape → `onCancel`, autofocus guiado pelo tom (`danger` → Cancelar —
      primeiro focável; `primary` → Confirmar — último focável), restaura foco
      ao fechar.
- [x] T2.2: `ConfirmDialog` (console) — `aria-labelledby` (título) +
      `aria-describedby` (mensagem) com ids únicos.
- [x] T2.3: `ConfirmDialog` (console) — prop `tone: 'danger' | 'primary'`
      (default `danger`): `btn-danger` vs `btn-primary`.
- [x] T2.4: `StepUpPrompt` (console) — `useDialogFocus` com
      `initialFocus={inputRef}` (campo TOTP, não no botão) +
      `aria-labelledby`/`aria-describedby`; Escape cancela.
- [x] T2.5: `portal/src/components/ConfirmDialog.tsx` NOVO — cópia acessível
      do console (focus trap/Escape/aria/autofocus + `tone`).
- [x] T2.6: `portal/src/styles.css` — adicionar `.modal-backdrop`, `.modal`,
      `.modal-actions` (seção "Modal de confirmação" do console).
- [x] T2.7: `portal/src/pages/CredentialsPage.tsx` — "Revogar" abre
      `ConfirmDialog` (tone danger, mensagem de irreversibilidade) antes de
      executar; feedback após confirmação (T1.2).
- [x] T2.8: `console` — aplicar `tone="primary"` nas confirmações neutras:
      "Já copiei" (ProfilesPage), "Confirmar cobrança" (MerchantInvoicesTab),
      "Liberar" (MerchantDetailPage), "Confirmar saque" (FinancialPage) — onde
      a ação não for destrutiva.

## F3 — Loading/vazio — critérios 6, 7, 8

- [x] T3.1: portal — trocar texto "Carregando…" por `<Spinner />`:
      `CredentialsPage`, `DestinationsPage`, `WebhooksPage`, `WithdrawPage`.
- [x] T3.2: `portal/src/pages/ChargePage.tsx` — fix bug: `presets.loading` →
      `<Spinner />` (era `<ErrorBanner error={undefined} />`).
- [x] T3.3: `console/src/pages/FinancialPage.tsx` — select de lojistas com
      estado de loading (desabilitado + opção "Carregando lojistas…") durante
      `merchants.loading`.
- [x] T3.4: empty states (console): `FinancialPage` (ledger — "Nenhum
      lançamento no ledger."), `ProfilesPage` ("Nenhum perfil criado ainda."),
      `MerchantDetailPage` tabs ledger/receivables/withdrawals ("Nenhum
      lançamento."/"Nenhum receivable."/"Nenhum saque.").
- [x] T3.5: empty states (portal): `DashboardPage` (faturamento — "Sem dados
      de faturamento."; últimas vendas — "Nenhuma venda ainda."), `ChargePage`
      (gift presets — "Nenhum preset disponível."), `SalesPage` ("Nenhuma
      venda no período."), `WalletPage` ("Nenhum lançamento na carteira.").
      Verificar colSpan real de cada tabela.

## F4 — Navegação — critério 9

- [x] T4.1: skip-link "Pular para o conteúdo" no `Layout` (console + portal)
      apontando para `#main-content`; CSS `.skip-link` visível no foco
      (ambos `styles.css`).
- [x] T4.2: `console/src/pages/MerchantDetailPage.tsx` — `h1` com o NOME do
      lojista (resolver por `id` via lista `GET /master/merchants` já
      carregada — fallback "{id}…").

## F5 — Formulários — critério 10

- [x] T5.1: `console/src/pages/FinancialPage.tsx` — usar componente `Field`
      com `required` nos 4 campos do saque administrativo (paridade).
- [x] T5.2: portal — mensagem de valor específica "Informe um valor maior que
      zero" em `ChargePage` (avulsa), `WithdrawPage` (saque) e `WithdrawPage`
      (auto-withdraw).

## F6 — Fechamento — critério 12

- [x] T6.1: `npm run build` limpo nos dois frontends (`console` + `portal`).
- [x] T6.2: `mvn -o clean test` verde (sem regressão backend).
- [x] T6.3: E2E existente verde (mesma app/ambiente do CI): `master-console-ui`,
      `master-console-stepup`, `portal-ui` e demais specs.
- [x] T6.4: sync roadmap/requirements/design/task (status `implemented`).
- [x] T6.5: `/spec-audit` — verificar os **12 critérios** do requirements um a
      um.

## Verificação (`/spec-verify` 2026-08-12)

**12 critérios checados contra o código real (inventário fundamentado);
2 achados de precisão corrigidos nos documentos (sem mudança de contrato):**

1. **Banners (critério 1)** — console `ui.tsx` já tem `SuccessBanner`/`ErrorBanner`
   (sem role); portal `ui.tsx` NÃO tem `SuccessBanner` (só `ErrorBanner`).
   Achado de precisão: `role="status"`/`role="alert"` adicionados ao design
   (D2.1). ✅
2. **Feedback portal (critério 2)** — confirmado no código: `WebhooksPage`
   (`handleCreate`/`toggleActive` sem feedback; `toggleActive` sem try/catch),
   `CredentialsPage` (`revoke` sem feedback e sem try/catch — linha 48),
   `DestinationsPage` (`handleCreate`/`toggleActive`/`updateLabel` sem feedback;
   `toggleActive`/`updateLabel` sem try/catch — linhas 45/50). ✅
3. **Diálogos (critério 3)** — `ConfirmDialog` (console) sem focus
   trap/Escape/aria/restauração; `StepUpPrompt` já tem autofocus no campo TOTP
   (setTimeout) mas sem trap/Escape/aria; portal sem `ConfirmDialog`. ✅
4. **Tom (critério 4)** — `ConfirmDialog` sempre `btn-danger` (incl. "Já
   copiei"/"Confirmar cobrança"). Achado de precisão: **autofocus guiado pelo
   tom** — o 1º focável do DOM é o botão Cancelar; `danger` → foca Cancelar
   (Enter não confirma acidentalmente), `primary` → foca Confirmar (D3.1/D3.2
   atualizados). ✅
5. **Confirmação portal (critério 5)** — `CredentialsPage` "Revogar" executa
   direto (`void revoke(key)` — irreversível, sem confirmação). CSS de modal
   NÃO existe no portal (`styles.css` sem `.modal-*` — só o console tem).
   T2.6 confirmado necessário. ✅
6. **Loading (critério 6)** — portal usa texto "Carregando…" em 4 telas
   (Credentials/Destinations/Webhooks/Withdraw) vs `Spinner` no console e em
   Dashboard/Wallet/Sales do próprio portal; `ChargePage` bug confirmado:
   `{presets.loading ? <ErrorBanner error={undefined} /> : …}` (linha ~86);
   select de lojistas do `FinancialPage` sem estado de loading. ✅
7. **Empty states (critério 7)** — ausentes confirmados: console `FinancialPage`
   (ledger), `ProfilesPage`, tabs do `MerchantDetailPage` (ledger/receivables/
   withdrawals — invoices tem "Nenhuma fatura"); portal `DashboardPage`
   (faturamento + últimas vendas — `<ul>` vazio), `ChargePage` (gift presets),
   `SalesPage` (tabela sem empty), `WalletPage` (grep sem "Nenhum"). ✅
8. **Recarga (critério 8)** — `useAsync` atual: `setLoading(true)` no início do
   effect → páginas renderizam `Spinner` e escondem os dados anteriores (blink
   ao trocar página/filtro); o `data` NÃO é zerado, mas o loading esconde.
   Fix confirmado: `setLoading(data === null)`. ✅
9. **Navegação (critério 9)** — nenhum `Layout` tem skip-link;
   `MerchantDetailPage` `h1` = "Lojista {id}…" (sem nome; endpoint
   `GET /master/merchants` existe — cap 100, padrão FinancialPage). ✅
10. **Formulários (critério 10)** — `FinancialPage` usa `<label className=
    "field">` inline (não `Field`) sem `required`; portal valida com "Informe
    um valor válido" (genérico) em Charge/Withdraw/auto-withdraw. ✅
11. **Copiar (critério 11)** — `ChargePage` botão "Copiar" sem feedback; caixas
    de secret (ProfilesPage senha, CredentialsPage chave, SecurityPage recovery)
    sem botão de copiar. ✅
12. **Sem regressão (critério 12)** — `npm run build`/`mvn test`/E2E são o
    fechamento (F6) — sem mudança de backend, risco baixo. ✅

Rastreabilidade critérios ↔ design ↔ task 1:1 (D8). Pronta para
`/spec-implement`.

## Implementação (`/spec-implement` 2026-08-12)

F0–F5 concluídas nos dois frontends (console + portal) — resumo:

- **F0 (ui.tsx ambos)**: `ErrorBanner` com `role="alert"`; `SuccessBanner` com
  `role="status"` (NOVO no portal — paridade); `useAsync` mantém os dados
  anteriores em reload (`setLoading(data === null)` — sem blink ao trocar
  página/filtro); `copyToClipboard(text)` com try/catch.
- **F2 (diálogos)**: hook `useDialogFocus` novo no console (focus trap Tab
  cíclico, Escape fecha, autofocus guiado `'first'|'last'|ref`, restaura foco
  ao fechar); `ConfirmDialog` do console com `tone: 'danger'|'primary'`
  (danger → foca Cancelar; primary → foca Confirmar) + `aria-labelledby`/
  `aria-describedby` (ids via `useId`); `StepUpPrompt` com o mesmo tratamento
  (`initialFocus` no campo TOTP); `ConfirmDialog` NOVO no portal + CSS do
  modal copiado do console; `tone="primary"` aplicado nas confirmações neutras
  (Ativar/Já copiei do ProfilesPage, Liberar/Confirmar cobrança do
  MerchantDetailPage, Confirmar saque do FinancialPage).
- **F1 (feedback)**: portal `WebhooksPage` (create/toggle com feedback +
  try/catch no toggle), `CredentialsPage` (revoke com `ConfirmDialog` +
  feedback + try/catch), `DestinationsPage` (create/toggle/updateLabel com
  feedback + try/catch); botões "Copiar" com "Copiado!" ~2s: copia-e-cola do
  `ChargePage`, senha temporária do `ProfilesPage`, chave do
  `CredentialsPage`, recovery codes do `SecurityPage` ("Copiar todos").
- **F3 (loading/vazio)**: portal "Carregando…" → `Spinner` (Credentials/
  Destinations/Webhooks/Withdraw); fix do bug do `ChargePage` (`presets.loading`
  renderizava `<ErrorBanner error={undefined}/>` → `Spinner`); select de
  lojistas do `FinancialPage` com "Carregando lojistas…" (disabled);
  empty states em 9 telas/tabs (FinancialPage ledger, ProfilesPage, tabs
  ledger/receivables/withdrawals do MerchantDetailPage, Dashboard
  faturamento/últimas vendas, ChargePage presets, SalesPage, WalletPage).
- **F4 (navegação)**: skip-link "Pular para o conteúdo" nos 2 `Layout`
  (aponta para `#main-content`; CSS `.skip-link` visível no foco); h1 do
  `MerchantDetailPage` mostra o NOME do lojista (resolvido via
  `GET /master/merchants` cap 100 — fallback "{id}…").
- **F5 (formulários)**: `FinancialPage` com componente `Field` + `required`
  nos 4 campos do saque administrativo; mensagens de valor específicas
  ("Informe um valor/limiar maior que zero") no `ChargePage` e `WithdrawPage`.
- **F6 (fechamento)**: `npm run build` limpo nos 2 frontends (tsc sem erro);
  E2E de UI **10/10** (master-console-stepup 5 + master-console-ui 4 +
  portal-ui 1); suíte E2E completa + `mvn test` (ver §Auditoria).

**Achado operacional registrado (build dos frontends):** os dois `vite.config`
têm `emptyOutDir: true` e o portal aponta para `static/` (raiz) — rodar o
build do **portal depois** do console apaga `static/console/`. Ordem canônica:
**portal primeiro, console depois** (ou rebuild do console ao final). O CI não
builda os frontends (usa os bundles commitados em `static/`).

## Auditoria (`/spec-audit` 2026-08-12)

**12 critérios do requirements verificados um a um contra o código e o E2E;
1 achado de código corrigido; 0 achados abertos.**

1. **Banners anunciáveis (c1)** — `SuccessBanner` com `role="status"` e
   `ErrorBanner` com `role="alert"` em AMBOS os `ui.tsx` (portal ganhou o
   `SuccessBanner` — paridade); verificado no código + aria-live implícito. ✅
2. **Feedback completo no portal (c2)** — `WebhooksPage` (create/toggle),
   `CredentialsPage` (revoke), `DestinationsPage` (create/toggle/updateLabel)
   com sucesso (`SuccessBanner`) e erro (`ErrorBanner` + try/catch — nada de
   unhandled rejection). Verificado no código. ✅
3. **Diálogos acessíveis (c3)** — `useDialogFocus` (console) e o `ConfirmDialog`
   do portal: focus trap (Tab cíclico), Escape cancela, autofocus guiado,
   `aria-labelledby`/`aria-describedby`, foco restaurado ao fechar. ✅
4. **Tom danger/primary (c4)** — `tone` no `ConfirmDialog` (console + portal);
   aplicado: Ativar/Já copiei (ProfilesPage), Liberar/Confirmar cobrança
   (MerchantDetailPage), Confirmar saque (FinancialPage) = `primary`; ações
   destrutivas mantêm `danger`. ✅
5. **Confirmação no portal (c5)** — `ConfirmDialog` novo (portal) + CSS do
   modal copiado; "Revogar chave" (irreversível) abre confirmação antes de
   executar; toggles reversíveis (webhook/destino) mantêm 1 clique (decisão
   registrada no design D3.4). ✅
6. **Loading uniforme (c6)** — portal "Carregando…" → `Spinner` (4 telas);
   bug do `ChargePage` corrigido (`presets.loading` → `Spinner`); select de
   lojistas do `FinancialPage` com "Carregando lojistas…" (disabled). ✅
7. **Empty states (c7)** — 9 telas/tabs: console FinancialPage (ledger),
   ProfilesPage, tabs do MerchantDetailPage (ledger/receivables/withdrawals);
   portal Dashboard (faturamento + últimas vendas), ChargePage (presets),
   SalesPage, WalletPage. Mensagens específicas. ✅
8. **Recarga não zera a lista (c8)** — `useAsync` com `setLoading(data ===
   null)`: dados anteriores permanecem visíveis em reload (troca de
   página/filtro); `Spinner` só no 1º carregamento. ✅
9. **Skip-link + orientação (c9)** — "Pular para o conteúdo" nos 2 `Layout`
   (foco → `#main-content`; `.skip-link` visível no foco); h1 do
   `MerchantDetailPage` com o NOME do lojista (via `GET /master/merchants`,
   fallback "{id}…"). ✅
10. **Formulários (c10)** — `FinancialPage` com `Field` + `required` (4
    campos do saque admin); mensagens "Informe um valor/limiar maior que zero"
    no `ChargePage`/`WithdrawPage`. ✅
11. **Copiar com feedback (c11)** — `copyToClipboard` + "Copiado!" ~2s:
    ChargePage (copia-e-cola), ProfilesPage (senha), CredentialsPage (chave),
    SecurityPage (recovery codes — "Copiar todos"). ✅
12. **Sem regressão (c12)** — `npm run build` limpo (tsc sem erro) nos 2
    frontends; `mvn test` **1376/0/0**; E2E de UI **10/10** (master-console-ui
    4 + master-console-stepup 5 + portal-ui 1 — re-executado em 3 runs);
    suíte E2E completa **112/122** com as envs do CI (rate limit off +
    allow-loopback + serve-frontend ambos). ✅

**Achados (implementação):**

1. **Código (1 corrigido)**: o effect de foco dos diálogos (`useDialogFocus`
   do console e o `ConfirmDialog` do portal) dependia de callbacks inline
   (`onClose`/`onCancel` — nova identidade por render) → o effect re-rodava a
   cada render com o modal aberto (foco pulava, `restoreRef` guardava o
   elemento errado). Corrigido lendo os callbacks via ref (`onCloseRef`/
   `toneRef`) com deps `[open]` — o effect roda só na abertura/fechamento.
2. **Operacional (registrado)**: ordem de build dos frontends — os dois
   `vite.config` têm `emptyOutDir: true` e o portal aponta para `static/`
   (raiz); build do portal depois do console apaga `static/console/`. Ordem
   canônica: **portal primeiro, console depois** (§Implementação).
3. **E2E (robustez)**: asserções pós-step-up do `master-console-stepup.spec.js`
   ganharam timeout explícito de 15s (sob carga da suíte completa o round-trip
   step-up + repetição da ação excede os 5s default — o spec passa isolado 3×).

**Observações de ambiente registradas (não são achados da spec — suíte
completa local 112/122):** (1) `master-console-stepup` teste 4 falha apenas na
suíte completa (POST do step-up stall >15s sob carga; passa isolado 3×,
inclusive com o DB pós-suíte) — sem relação com a Spec 73 (fluxo do Spec 72);
(2) `merchant-journey` 14.8/18 falhou 1× por asserção de saldo do wallet
(80 vs 90 — fluxo 100% API de dívida/recebível; passou nos 2 runs anteriores;
mesma classe da flakiness pré-existente documentada na auditoria da Spec 71);
(3) os 8 testes "did not run" são dependentes serial dos 2 acima. O CI
(Ubuntu, 122/122 histórico) não é afetado — as mudanças da Spec 73 são
frontend-only e validam 10/10 nos specs de UI.

**0 achados abertos na Spec 73.** Fim do ciclo SDD
(roadmap/requirements/design/task `audited`).
