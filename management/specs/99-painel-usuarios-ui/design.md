---
name: Painel Geral de Usuários — UI do Master Console
status: audited
---

# Spec 99 — Design: Painel Geral de Usuários (UI Master Console)

## D1 — Conceito

**Thin client** sobre os endpoints de leitura da Spec 97, no padrão das
páginas do console (Specs 71/72/73): CSS puro + `ui.tsx` + `useAsync` +
`RequirePermission` + `useDialogFocus`. A Spec 99 **exibe e navega**; as
escritas (config/controle) ficam na Spec 100. Nenhum contrato HTTP novo (a
Spec 97 fornece tudo); reuso dos endpoints existentes de saques.

```text
console/src/
  pages/MerchantsPage.tsx       → reescrita: tabela rica (panel) + menu de ações
  pages/MerchantDetailPage.tsx  → ampliada: KPIs + Dados da Conta + Taxas/Limites
                                  + aba Transações (nova) + aba Saques (integrada)
  pages/StoresModal.tsx         → novo: Ver Shops (modal)
  types.ts                      → MerchantPanel*, MerchantPanelTransaction,
                                  MerchantStore, etc.
  App.tsx / components/Layout.tsx / SecurityConfig(Java) → rotas/menu/CSP
```

## D2 — Tabela rica (`MerchantsPage`)

- **Fonte**: `GET /api/v1/master/merchants/panel` (Spec 97 D3.1).
- **Colunas**: Lojista (nome + e-mail), Status (badge), Taxas (Pix/Fixa/Saque —
  compacto "0,50 / 0,40 / 2,50"), Gateway (PADRAO ou provider), Conversão
  (%, 2 casas), Pix pagos (inteiro), **MED%** (célula destacada: cor por faixa
  — verde <2%, amarelo 2–5%, vermelho >5%; conteúdo `8.73% / 62` com tooltip
  "Índice de devoluções/chargebacks"), Criado em.
- **Filtros**: status (select), `q` (busca nome/e-mail), `from`/`to` (datas),
  `risk` (select baixo/médio/alto → query param da Spec 97). Paginação
  server-side (`Pagination` Spec 73).
- **Badges de status**: `ACTIVE` → "Aprovado" (verde), `SUSPENDED` → "Suspenso"
  (amarelo), `INACTIVE` → "Inativo" (cinza), `BANNED` → "Banido" (vermelho) —
  `statusLabel/statusClass` helper (padrão dos badges existentes).
- **Sort** por MED%: se o backend não ordenar por padrão, a ordem é a do
  `PageResponse` (Spec 97 define a ordenação default — por `createdAt` desc;
  sort por MED fica como evolução).

## D3 — Menu de ações por linha

Dropdown (ou link de ações) em cada linha:

- **Ver detalhes** → `Link` `/console/merchants/:id`.
- **Ver shops** → abre `StoresModal` (D5) — `GET /master/merchants/{id}/stores`.
- **Config** → `Link` `/console/merchants/:id/config` (Spec 100; se a rota
  ainda não existir, desabilitado com tooltip).
- **Sacar** → `Link` `/console/merchants/:id` tab Saques.
- **Controle (escrita)** — Desativar/Ativar, Bloquear API/Desbloquear, Banir,
  Marcar honeypot: implementados **na Spec 100**; nesta spec o menu mostra os
  itens (com `RequirePermission`) mas o clique dispara **só** se o endpoint da
  Spec 98 estiver disponível (decisão: a Spec 99 faz o wiring de navegação e o
  **placeholder** `ConfirmDialog`; a Spec 100 substitui pelos chamados reais).
  Para evitar duplicação: a Spec 99 define os **componentes de ação**
  (`MerchantActionsMenu`) com `onAction` props; a Spec 100 fornece os handlers.

## D4 — Perfil detalhado (`MerchantDetailPage` ampliada)

- **Fonte**: `GET /api/v1/master/merchants/{id}/panel` (Spec 97 D3.2) +
  `earnings` (Spec 15) mantido para não quebrar.
- **Topo** — 4 KPIs (cards): **Saldo do wallet** (`kpis.walletBalance`),
  **Faturamento histórico** (`kpis.grossRevenue` + `kpis.paidPixCount` Pix
  pagos), **Conversão global** (`kpis.conversionRate`%), **Shops**
  (`kpis.storesCount`).
- **Dados da Conta** — grid: E-mail, Telefone (`account.phone` ou "—"),
  CPF/CNPJ (`account.documentMasked`), Titular (`account.name`), Gateway
  (`account.gateway` ou PADRAO), Cadastro (`account.createdAt` formatado).
- **Taxas e Limites** — grid: Taxa Pix (`feesAndLimits.transactionFee`),
  Taxa Fixa (`feesAndLimits.fixedFee`), Taxa de Saque
  (`feesAndLimits.withdrawalFee`), MED (`feesAndLimits.medPercent`% /
  `medOccurrences` ocorrências, cor por faixa), Limites Pix/Saque
  (`pixMin–pixMax`, `withdrawalMin–withdrawalMax` ou "global").
- Ações de operação existentes (suspend/release — Spec 16) permanecem.

## D5 — Ver Shops (`StoresModal`)

- **Fonte**: `GET /api/v1/master/merchants/{id}/stores` (Spec 97 D3.4).
- Modal acessível (`useDialogFocus` Spec 73): lista das stores com nome,
  título, cor/emoji, descrição (truncada), datas de criação/atualização; sem
  `social_links`. Vazio → empty state "Nenhuma loja cadastrada".
- **Sem PII**: URLs de logo/banner são públicas (spec 48); sem dados sensíveis.

## D6 — Aba Transações (nova tab no detalhe)

- **Rota**: `/console/merchants/:id/transactions` (nova tab no `TABS` do
  `MerchantDetailPage` — após "Saques" ou "Faturas"; decisão: após "Saques").
- **Fonte**: `GET /api/v1/master/merchants/{id}/transactions` (Spec 97 D3.3).
- **Colunas**: Valor, Status (badge PENDENTE/PAGO — APP logo, APPROVED verde),
  Comprador (`buyerName` ou `buyerIdHash` truncado com tooltip), Produto
  (`productName` ou "—"), TXID (`externalReference` monospace), Líquido
  (tooltip "bruto − taxa"), Data (`createdAt`).
- Filtros status/período + paginação; empty state.

## D7 — Aba Saques (integrada)

- Mantém a `MerchantWithdrawalsTab` existente (`/master/withdrawals?merchantId=`),
  garantindo as colunas: Valor, Status (badge CONCLUIDO/FALHOU), Chave Pix
  (destino mascarado — `WithdrawalResponse.destination`), Taxa (`fee` Spec 88),
  Data. Apenas ajuste de rótulo ("CONCLUIDO" em vez de "COMPLETED" na UI).

## D8 — Rotas/SPA/CSP

- `App.tsx`: adiciona a tab (interno ao `MerchantDetailPage` — não precisa de
  rota nova) e `StoresModal` (componente). A rota `/console/merchants/:id/config`
  é da Spec 100.
- `SecurityConfig.spaPublicPaths()`/`ConsoleSpaController`: sem rota nova nesta
  spec (tab é subrota do detalhe já coberto). Verificar se `:id/*` já cobre
  `/transactions` (sim — Spec 71: `/console/merchants/:id/*`).
- CSP: inalterado (sem estilo inline novo além do padrão já permitido).

## D9 — Testes

- `npm run build` (tsc) limpo no console.
- E2E `master-panel-ui.spec.js`: login master → tabela com MED/conversão/
  gateway (seed com 1 lojista + cobranças via API do portal) → perfil (KPIs,
  dados da conta com documento mascarado, taxas/limites) → aba transações
  (comprador via lead CRM + produto + líquido; sem lead → `buyerIdHash`) →
  aba saques (taxa + destino mascarado) → Ver Shops (modal + empty) →
  401 redirect. Suíte completa sem regressão.
- Observação: a suíte E2E completa roda na ordem alfabética dos specs; a
  `master-panel-ui.spec.js` deve limpar o estado no `beforeAll` (padrão
  `global-setup`/L6) para não interferir nas demais.
