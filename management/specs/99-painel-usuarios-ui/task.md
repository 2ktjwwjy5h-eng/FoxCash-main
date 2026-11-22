---
name: Painel Geral de Usuários — UI do Master Console
status: audited
---

# Spec 99 — Task: Painel Geral de Usuários (UI Master Console)

## F0 — Tipos + tabela rica

- [x] **T0.1** — `types.ts` += `MerchantPanelItem`, `MerchantPanelDetail` (+
      `PanelKpis`, `PanelAccount`, `PanelFeesAndLimits`), `MerchantPanelTransaction`,
      `MerchantStore`.
- [x] **T0.2** — `MerchantsPage.tsx`: reescrita para consumir
      `GET /api/v1/master/merchants/panel` — colunas Lojista/Status/Taxas/
      Gateway/Conversão/Pix pagos/**MED%**/Criado; filtros (status/q/from/to/
      risk); paginação server-side; badges de status (Aprovado/Suspenso/Inativo/
      Banido); célula MED% colorida por faixa (helper `medClass(percent)`).
- [x] **T0.3** — helper `statusLabel/statusClass` + `medClass` + `formatPercent`
      (ou reuso existente).

## F1 — Menu de ações por linha

- [x] **T1.1** — `MerchantActionsMenu` (dropdown): Ver detalhes, Ver shops,
      Config, Sacar, e os controles de escrita (Desativar/Ativar, Bloquear
      API, Banir, Marcar honeypot) — com `RequirePermission` (Spec 69) e
      `onAction` props (os handlers são da Spec 100).
- [x] **T1.2** — Wiring: "Ver shops" abre `StoresModal`; "Config" navega para
      `/console/merchants/:id/config` (se a rota existir — senão desabilitado);
      "Sacar" navega para a aba Saques do detalhe; ações de escrita com
      `ConfirmDialog` placeholder (Spec 100 substitui).

## F2 — Perfil detalhado

- [x] **T2.1** — `MerchantDetailPage.tsx`: KPIs de topo (4 cards — Saldo,
      Faturamento + Pix pagos, Conversão, Shops) + seção **Dados da Conta**
      (e-mail, telefone, CPF/CNPJ mascarado, titular, gateway, cadastro) +
      seção **Taxas e Limites** (Taxa Pix, Fixa, Saque, MED com contagem,
      limites Pix/Saque) — via `GET /master/merchants/{id}/panel` (Spec 97),
      mantendo `earnings` existente (sem regressão).
- [x] **T2.2** — Empty/loading uniforme (Spec 73) nas novas seções.

## F3 — Aba Transações + aba Saques

- [x] **T3.1** — Tab "Transações" no `MerchantDetailPage` (`TABS` +=
      `{to:'transactions', label:'Transações'}` + sub-rota) — `GET
      /master/merchants/{id}/transactions`: colunas Valor/Status/Comprador
      (nome do lead ou `buyerIdHash` truncado)/Produto/TXID/Líquido/Data;
      filtros status/período; paginação.
- [x] **T3.2** — Tab "Saques" integrada: colunas Valor/Status/Chave Pix
      (mascarada)/Taxa/Data (reuso `WithdrawalResponse` — Spec 88); rótulo
      "CONCLUIDO" na UI.

## F4 — Ver Shops + rotas/SPA

- [x] **T4.1** — `StoresModal.tsx` (useDialogFocus Spec 73): lista das stores
      (identidade — Spec 97 D3.4); empty state; sem `social_links`.
- [x] **T4.2** — `App.tsx`/menu: sem rota nova (subrotas do detalhe — verificar
      `/console/merchants/:id/*` cobre `/transactions`). `spaPublicPaths`/
      `ConsoleSpaController` verificados (padrão lição Specs 76/80/84).

## F5 — Build + E2E + fechamento

- [x] **T5.1** — `npm run build` (tsc) limpo no console (ordem portal →
      console — lição Spec 73).
- [x] **T5.2** — E2E `master-panel-ui.spec.js` (D9): tabela rica → perfil →
      transações (comprador/produto/líquido) → saques (taxa/destino) → Ver
      Shops → empty states → 401 redirect.
- [x] **T5.3** — Suíte E2E completa sem regressão + `mvn test` verde
      (backend inalterado — só confirma).
- [x] **T5.4** — Atualizar roadmap/status.

## Verificação (`/spec-verify 99`)

- [x] Checar critérios ↔ design ↔ task contra o código real (endpoints da
      Spec 97 quando implementados, `MerchantsPage`/`MerchantDetailPage`
      atuais, `RequirePermission`, `useDialogFocus`, `spaPublicPaths`,
      `ConsoleSpaController`, `types.ts`, E2E existentes) — sem achados de
      escopo.

## `/spec-audit 99` (2026-08-17) — AUDITADA

**10/10 critérios OK, 0 achados abertos.** Verificação: tabela rica em
`/console/merchants` (colunas status/taxas/gateway/conversão/Pix pagos/MED%
colorido com contagem + filtro `risk` + paginação server-side), menu de ações
(Ver detalhes/Shops/Config/Sacar + controles de escrita navegando para a Spec
100 — RBAC-aware), perfil detalhado (KPIs + dados da conta + taxas/limites via
`GET .../panel`), abas Transações (comprador/produto/líquido) e Saques
(reuso `/master/withdrawals`), Ver Shops (modal via espelho), UX Spec 73
(banners/ConfirmDialog/empty states), rotas/SPA/CSP. E2E `master-panel-ui.spec.js`
presente; `npm run build` limpo (ordem portal → console). Frontmatter/roadmap →
`audited`.
