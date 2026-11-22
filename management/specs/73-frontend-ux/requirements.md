---
name: Frontend UX — fluxos e usabilidade (console + portal)
status: audited
references:
  - "management/vision.md#V52"
  - "management/specs/71-master-console/requirements.md"
  - "management/specs/72-master-console-stepup/requirements.md"
  - "management/specs/46-portal-frontend/requirements.md"
  - "management/specs/70-auto-withdraw/requirements.md"
  - "management/specs/23-pix-charge-generator/requirements.md"
  - "management/specs/31-withdrawal-destinations/requirements.md"
  - "management/specs/24-api-credentials/requirements.md"
  - "management/specs/35-outbound-webhook/requirements.md"
  - "management/specs/21-merchant-dashboard/requirements.md"
---

# Spec 73 — Frontend UX: fluxos e usabilidade (console + portal)

## O que entrega

Camada de **usabilidade** sobre os dois frontends (Master Console — Specs
71/72 — e Portal do Lojista — Spec 46+). Foco em **fluxos**: feedback de
ações, confirmações, estados de carregamento/vazio e navegação. **Puramente
apresentação** — nenhum contrato HTTP muda, nenhuma lib de UI é adicionada
(CSS puro, padrão das Specs 46/71).

O backend e os fluxos de negócio já existem e estão auditados (Specs 32/33/34/
69/70/71/72). Esta spec **padroniza a experiência**: hoje cada tela trata
loading/erro/vazio/sucesso de um jeito, alguns diálogos não são acessíveis a
teclado, ações destrutivas do portal executam **sem confirmação** e várias
ações de escrita **não dão feedback** (nem de sucesso nem de erro).

Inventário dos gaps (2026-08-12, fundamentado no código):

- **Feedback ausente no portal**: `WebhooksPage` (create/toggle sem mensagem de
  sucesso), `CredentialsPage` (revoke sem mensagem), `DestinationsPage`
  (create/toggle/updateLabel sem mensagem). `toggleActive`/`revoke`/`updateLabel`
  rodam **sem try/catch** → falha vira unhandled rejection silencioso.
- **Diálogos não acessíveis** (console `ConfirmDialog`/`StepUpPrompt`): sem
  focus trap, sem Escape, sem autofocus, sem `aria-labelledby`/`describedby`,
  sem restauração de foco. O portal **não tem** `ConfirmDialog` — "Revogar
  chave" (irreversível) executa sem confirmação.
- **`ConfirmDialog` sempre danger**: até confirmações neutras ("Já copiei",
  "Confirmar cobrança") usam o botão vermelho.
- **Loading inconsistente**: portal usa texto "Carregando…" em 4 telas
  (Credentials/Destinations/Webhooks/Withdraw) vs `Spinner` no console e em
  outras telas do próprio portal. Bug no `ChargePage`: `presets.loading`
  renderiza `<ErrorBanner error={undefined}/>` → **nada visível** durante o
  carregamento. Select de lojistas do `FinancialPage` (console) fica vazio
  sem indicação de carregamento.
- **Empty states ausentes**: console `FinancialPage` (ledger), `ProfilesPage`,
  tabs do `MerchantDetailPage` (ledger/receivables/withdrawals); portal
  `DashboardPage` (faturamento/últimas vendas), `ChargePage` (gift presets),
  `SalesPage`, `WalletPage` — listas vazias mostram só o cabeçalho.
- **Recarga zera a lista**: `useAsync` descarta os dados anteriores durante um
  reload (troca de página/filtro → blink/lista some).
- **Navegação**: sem skip-link nos dois layouts; `MerchantDetailPage` mostra
  "Lojista {id}…" no `h1` (sem o nome do lojista).
- **Formulários divergentes**: `FinancialPage` usa `<label class="field">`
  inline (não o componente `Field`) e sem `required`; mensagens de validação
  genéricas ("Informe um valor válido") sem contexto.
- **Copiar sem feedback**: botão "Copiar" do `ChargePage` (copia-e-cola) e as
  caixas de secret (senha temporária do `ProfilesPage`, chave do
  `CredentialsPage`, recovery codes do `SecurityPage`) não confirmam a cópia.

## Contexto (por que agora)

As Specs 71/72 acabaram de entregar o Master Console completo e o portal já
existe desde a Spec 46. Os fluxos funcionam (E2E verde), mas a experiência é
**inconsistente entre telas e entre os dois frontends** — o mesmo padrão de
loading/vazio/feedback não se repete, e ações críticas do portal (revogar
chave) não pedem confirmação. Decisão do usuário (2026-08-12): nova spec
focada em **fluxos e usabilidade** nos dois frontends, sem mexer em contrato.

## Critérios de aceite

1. **Banners anunciáveis (ambos)**: `SuccessBanner` com `role="status"` e
   `ErrorBanner` com `role="alert"` (aria-live) em **ambos** os frontends — o
   portal ganha `SuccessBanner` no `ui.tsx` (paridade com o console). Todo
   banner de sucesso/erro existente passa a usar os componentes.
2. **Feedback completo no portal**: **toda** ação de escrita do portal mostra
   sucesso E trata erro com `ErrorBanner` (nada de unhandled rejection):
   `WebhooksPage` (create → "Webhook cadastrado"; toggle → "Webhook ativado/
   desativado"), `CredentialsPage` (revoke → "Chave revogada"), `DestinationsPage`
   (create → "Destino cadastrado"; toggle → "Destino ativado/desativado";
   updateLabel → erro tratado). Fracasso → `ErrorBanner` com a mensagem do
   backend; sucesso → banner verde.
3. **Diálogos acessíveis (console `ConfirmDialog` + `StepUpPrompt`, portal
   `ConfirmDialog` novo)**: focus trap (Tab cíclico dentro do modal), Escape
   cancela/fecha, autofocus no botão de confirmação, `aria-labelledby` no
   título e `aria-describedby` na mensagem, foco restaurado no elemento que
   abriu o modal ao fechar; o fundo não fica focável enquanto o modal está
   aberto.
4. **`ConfirmDialog` com tom**: prop `tone: 'danger' | 'primary'` (default
   `danger`) — ações destrutivas (suspender, revogar, desativar, write-off)
   usam `danger`; confirmações neutras ("Já copiei", "Confirmar cobrança",
   "Liberar") usam `primary`. Mesmo comportamento no portal.
5. **Confirmação no portal**: ações destrutivas **irreversíveis** do portal
   abrem `ConfirmDialog` antes de executar — mínimo: "Revogar chave" do
   `CredentialsPage`; demais toggles (webhook/destino) confirmam apenas quando
   o efeito não for trivialmente reversível (decisão de implementação,
   registrada no task).
6. **Loading uniforme (ambos)**: todo carregamento de lista/dados usa o
   `Spinner` (remover o texto "Carregando…" do portal: Credentials/
   Destinations/Webhooks/Withdraw); corrigir o bug do `ChargePage`
   (`presets.loading` → `Spinner`); select de lojistas do `FinancialPage`
   mostra "Carregando lojistas…" (ou fica desabilitado) até os dados chegarem.
7. **Empty states (ambos)**: toda lista/tabela sem dados mostra mensagem
   clara no lugar do cabeçalho vazio — adicionar onde falta: console
   `FinancialPage` (ledger), `ProfilesPage`, tabs do `MerchantDetailPage`
   (ledger/receivables/withdrawals); portal `DashboardPage` (faturamento e
   últimas vendas), `ChargePage` (gift presets), `SalesPage`, `WalletPage`.
   Padrão: linha `<td colSpan>` com `.muted` (tabelas) ou `<li>`/`<p>` com
   `.muted` (listas) — mensagens específicas ("Nenhum lançamento no ledger",
   "Nenhum perfil criado ainda", "Nenhuma venda no período", etc.).
8. **Recarga não zera a lista**: `useAsync` mantém os dados anteriores
   visíveis durante um reload (troca de página/filtro); o `Spinner` aparece
   apenas quando **não há** dados para mostrar (primeiro carregamento).
9. **Skip-link + orientação (ambos)**: link "Pular para o conteúdo" no
   `Layout` (console + portal) que move o foco para `<main>` (visível no
   foco); `MerchantDetailPage` mostra o **nome** do lojista no `h1` (não só o
   id) — obtido da lista já carregada de `/master/merchants` (sem endpoint
   novo).
10. **Formulários consistentes (console)**: `FinancialPage` usa o componente
    `Field` com `required` nos 4 campos do saque administrativo (paridade com
    o restante do console); validação de valor/quantia com mensagem
    específica ("Informe um valor maior que zero") nos formulários de valor
    do portal (`ChargePage`, `WithdrawPage`, auto-withdraw) — sem mensagem
    genérica.
11. **Copiar com feedback**: botões de copiar (copia-e-cola do `ChargePage`,
    senha temporária do `ProfilesPage`, chave do `CredentialsPage`, recovery
    codes do `SecurityPage`) mostram "Copiado!" temporário (~2s) após a cópia;
    falha de clipboard → feedback de erro.
12. **Sem regressão**: `npm run build` limpo nos dois frontends (`console` +
    `portal`); `mvn -o clean test` verde; E2E existente verde
    (`master-console-ui`, `master-console-stepup`, `portal-ui`, demais specs
    — mesma app/ambiente do CI).

## Fora de escopo

- **Mudança de contrato HTTP** (nenhum endpoint novo/alteração de shape).
- **Polish visual** (cores, tipografia, identidade) — critério de escopo
  separado; esta spec só ajusta classes CSS existentes quando necessário para
  os fluxos (ex.: tom `primary` do `ConfirmDialog`).
- **Responsividade/mobile**.
- **Acessibilidade auditada por completo** (contraste, WCAG) — apenas os
  itens de teclado/aria diretamente ligados aos fluxos (critérios 3, 9).
- **Refatoração de arquitetura** (ex.: consolidar os dois `ui.tsx` num pacote
  compartilhado) — os frontends são builds separados; o padrão é espelhar o
  componente em cada um (KISS).
