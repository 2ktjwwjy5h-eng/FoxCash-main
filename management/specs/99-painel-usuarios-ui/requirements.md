---
name: Painel Geral de Usuários — UI do Master Console
status: audited
references:
  - "management/vision.md#V75"
  - "management/specs/97-painel-usuarios-leitura/requirements.md"
  - "management/specs/71-master-console/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
  - "management/specs/72-master-console-stepup/requirements.md"
  - "management/specs/14-withdrawals/requirements.md"
  - "management/specs/48-stores-storefront/requirements.md"
  - "management/specs/16-antifraud-compliance/requirements.md"
---

# Spec 99 — Painel Geral de Usuários: UI do Master Console

## O que entrega

A **central de comando** da operação no Master Console (Specs 71/72) — o
"Painel Geral de Usuários" da descrição de produto do usuário (2026-08-15).
Puramente **apresentação** sobre os endpoints de leitura da Spec 97 (e reuso
dos existentes), padrão UX da Spec 73 (CSS puro + `ui.tsx` — a migração Shadcn
das Specs 85–87 foi `discarded` em 2026-08-14).

1. **Tabela rica de lojistas** (`/console/merchants`, substitui a listagem
   atual por uma com métricas): colunas **nome/e-mail**, **status da conta**
   (badge — Aprovado/ACTIVE, Suspenso, Inativo, Banido), **taxas aplicadas**,
   **gateway utilizado** (PADRAO ou rota específica), **taxa de conversão**
   (%), **nº de cobranças/Pix pagos** e a coluna vital **MED%** (termômetro de
   risco colorido com a contagem de ocorrências — ex.: 8.73% / 62 acende
   alerta vermelho).
2. **Menu de ações administrativas** por lojista: **Ver detalhes**, **Ver
   shops**, **Config**, **Sacar** (links/atalhos) e as ações de controle
   (**Desativar/Ativar**, **Bloquear API/Desbloquear**, **Banir**, **Marcar
   honeypot**) com `ConfirmDialog` (Spec 73). As ações de escrita são da
   **Spec 100** (esta spec só cria o menu/links — para não duplicar o
   comportamento de escrita, o menu navega para a config ou dispara as ações
   quando o endpoint da 98 existir; a decisão: **a tabela exibe e navega**; a
   Spec 100 implementa os formulários/modos de escrita).
3. **Perfil detalhado do lojista** (enriquece `/console/merchants/:id`):
   - **KPIs de topo**: saldo atual na carteira principal, faturamento
     histórico com nº de Pix pagos, taxa de conversão global, nº de shops.
   - **Dados da conta**: e-mail, telefone, CPF/CNPJ mascarado, titular,
     gateway, data de cadastro.
   - **Painel de taxas e limites**: Taxa Pix, Taxa Fixa, Taxa de Saque, MED
     acumulado (índice + ocorrências) e limites operacionais de Pix e Saque.
4. **Aba Transações** (extrato vivo): valor, status (PENDENTE/PAGO), **nome do
   comprador** (ou `buyerIdHash` opaco quando sem lead), **produto/serviço**
   ("VIP Semanal"), TXID, e **líquido** já descontando a taxa.
5. **Aba Saques**: valor, status (CONCLUIDO/FALHOU), chave Pix mascarada,
   carimbo de data/hora e taxa da operação (reuso do `/master/withdrawals` —
   `WithdrawalResponse` já tem `fee` e `destination` mascarada, Spec 88).
6. **Ver Shops** (modal ou página simples): lista das stores do lojista
   (identidade — Spec 48) via espelho da Spec 97.

## Contexto (por que agora)

Descrição de produto do usuário (2026-08-15) — Painel Geral de Usuários do
sistema de referência (tabela com MED, perfil, transações, saques e ações
diretas). A lacuna no Master Console: `MerchantsPage` atual é básica (sem
métricas de risco), o `MerchantDetailPage` tem tabs de leitura mas sem KPIs de
topo/dados da conta/taxas-limites, e não há menu de ações nem "Ver Shops".
Decisão do usuário (2026-08-15): **4 specs** — esta (99) é a UI do Painel
Geral; a **Spec 100** é a UI da Config fina. A Spec 99 é **read-only
+ navegação**; a escrita acontece na Spec 100 (a tabela só navega/exibe).

## Critérios de aceite

1. **Tabela rica** (`/console/merchants`): consome `GET
   /api/v1/master/merchants/panel` (Spec 97). Colunas: Lojista (nome + e-mail),
   Status (badge colorido — ACTIVE=verde "Aprovado", SUSPENDED=amarelo,
   INACTIVE=cinza, BANNED=vermelho), Taxas (Pix/Fixa/Saque), Gateway (PADRAO
   ou provider), Conversão (%) com tooltip, Pix pagos, **MED%** (coluna
   destacada — cor por faixa: verde < 2%, amarelo 2–5%, vermelho > 5% — com a
   contagem "8.73% / 62" na célula). Filtros existentes (status/q/from/to) +
   novo filtro `risk` (baixo/médio/alto). Paginação server-side.
2. **Menu de ações** por linha: dropdown/links com **Ver detalhes** (→
   `/console/merchants/:id`), **Ver shops** (modal — endpoint da Spec 97),
   **Config** (→ `/console/merchants/:id/config`, Spec 100), **Sacar** (→
   `/console/merchants/:id` aba Saques), e os controles de escrita
   (**Desativar/Ativar**, **Bloquear API**, **Banir**, **Marcar honeypot**)
   que **apontam para a Spec 100** (se o endpoint da 98 existir, o botão
   dispara com `ConfirmDialog` `danger` + banners Spec 73; caso contrário,
   desabilitado com tooltip "na Config"). A UI **esconde** ações sem permissão
   (Spec 69 — `RequirePermission`).
3. **Perfil detalhado** (`/console/merchants/:id`): seção de **KPIs de topo**
   (4 cards: Saldo, Faturamento + Pix pagos, Conversão, Shops) + seção
   **Dados da Conta** (e-mail, telefone, CPF/CNPJ mascarado, titular, gateway,
   cadastro) + seção **Taxas e Limites** (Taxa Pix, Taxa Fixa, Taxa de Saque,
   MED com contagem, limites Pix/Saque). Consome `GET
   /api/v1/master/merchants/{id}/panel` (Spec 97) — **novo endpoint**, o
   `earnings`/`fees` existentes permanecem (sem regressão).
4. **Aba Transações** (nova tab no detalhe): `GET
   /api/v1/master/merchants/{id}/transactions` (Spec 97) — colunas Valor,
   Status (badge), Comprador (nome do lead ou `buyerIdHash` truncado), Produto
   (ou "—"), TXID (`external_reference`), Líquido (com tooltip "bruto − taxa"),
   Data. Filtros status/período + paginação.
5. **Aba Saques** (mantém a atual, integrada): `GET
   /api/v1/master/withdrawals?merchantId=` — colunas Valor, Status (CONCLUIDO/
   FALHOU badge), Chave Pix (mascarada — o `destination` da Spec 88), Taxa,
   Data. Sem mudança de contrato.
6. **Ver Shops** (modal): `GET /api/v1/master/merchants/{id}/stores` (Spec 97)
   — lista com identidade (nome, título, cor/emoji, descrição, datas); sem
   `social_links`; vazio → empty state (Spec 73).
7. **UX (Spec 73):** Spinner uniforme, empty states em todas as listas/tabs,
   `SuccessBanner`/`ErrorBanner` (`role=status`/`role=alert`), `ConfirmDialog`
   `danger` para as ações de controle (quando presentes), formato monetário e
   de data padrão (`formatMoney`/`formatDate`), skip-link/navegação preservada.
8. **Rotas/SPA/CSP:** rota `/console/merchants/:id/config` (se a Spec 100 já
   existir) + `/console/merchants/:id/stores` se modal; `spaPublicPaths()`/
   `ConsoleSpaController`/CSP atualizados (padrão Specs 76/80/84 — lição:
   rotas fora do fallback → 401 em F5).
9. **RBAC-aware:** colunas/linhas de ações escondem sem permissão (Spec 69);
   o backend continua a autoridade (403 → banner, sessão permanece — padrão
   Spec 71).
10. **Testes:** `npm run build` (tsc) limpo no console (ordem portal → console
    — lição Spec 73) + E2E Playwright `master-panel-ui.spec.js` (login master →
    tabela com MED/conversão/gateway → perfil com KPIs/dados/taxas → aba
    transações com comprador/produto/líquido → aba saques com taxa → Ver Shops
    → empty states) + suíte completa sem regressão (E2E 151+/etc.).

## Dependências

- **Spec 97** (backend leitura) — endpoints do panel, transações, stores.
- **Spec 71/72** (console) — padrão de página, rotas, SPA controller,
  RBAC-aware, step-up.
- **Spec 73** (UX) — tabela, modal, banners, `ConfirmDialog`, empty states.
- **Spec 14/88** (saques) — aba Saques com taxa + destino mascarado.
- **Spec 48** (stores) — identidade das stores.
- **Spec 100** (config UI) — o menu de ações de escrita depende dela (esta
  spec navega; a escrita completa na 100).

## Fora de escopo

- **Escrita** (config, debit, honeypot, ban, api_blocked): **Spec 100**.
- **Nova página de config**: **Spec 100**.
- **Backend** (endpoints novos): **Spec 97/98**.
- **Mobile/responsividade**: evolução futura (decisão do descarte Shadcn,
  2026-08-14).
