---
name: Configuração fina por lojista — UI do Master Console
status: audited
references:
  - "management/vision.md#V76"
  - "management/specs/98-painel-usuarios-config/requirements.md"
  - "management/specs/99-painel-usuarios-ui/requirements.md"
  - "management/specs/71-master-console/requirements.md"
  - "management/specs/72-master-console-stepup/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
  - "management/specs/69-rbac-master-stepup/requirements.md"
  - "management/specs/88-withdrawal-fee/requirements.md"
---

# Spec 100 — Configuração fina por lojista: UI do Master Console

## O que entrega

A tela onde o administrador **molda as regras de negócio individualmente** para
cada lojista (descrição de produto do usuário 2026-08-15) — a UI da **Spec 98**.
Rota `/console/merchants/:id/config`, padrão UX da Spec 73 (CSS puro +
`ui.tsx`), RBAC-aware (Spec 69) e **step-up TOTP no cliente** para as ações
sensíveis (Spec 72/73).

1. **Regra de Taxa**: configuração flexível — `pix_fee_type` FIXED/PERCENT,
   `pix_fee_fixed`, `pix_fee_percent`, `pix_fee_threshold` ("Pix até
   determinado valor cobra taxa fixa; acima disso, cobra percentual").
2. **Limites e Taxas de Saque**: `withdrawal_fee` (override do R$ 2,50 global
   da Spec 88) e limites operacionais (`pix_min`/`pix_max`,
   `withdrawal_min`/`withdrawal_max`).
3. **Gateway (Adquirente)**: `gateway_route` — **PADRAO** (cascata global da
   Spec 91) ou um provedor específico (dropdown: PADRAO / pagarme / asaas / …).
4. **Rate Limit Integrado**: `rate_limit_rps` (req/s) e `rate_limit_rpm`
   (req/min) — controle de requisições para blindar a API contra força bruta
   ou robôs de teste de cartão/chave.
5. **Controle de Conta (Kill Switch)**: botões de rádio **Ativo**/**Inativo
   (Bloqueado)** (`deactivate`/`activate`) + a função crítica **"Debitar
   saldo"** (modal com valor + motivo, **step-up TOTP no cliente** — padrão
   Spec 72).
6. **Honeypot**: toggle "Marcar como honeypot" com `ConfirmDialog` explicando
   o ambiente controlado (conta continua operando, cobranças vão para o fluxo
   trap, saques bloqueados — decisão do usuário 2026-08-15).
7. **Bloquear API**: toggle `api_blocked` (API key → 403; portal segue).
8. **Banir**: ação "Banir conta" (irreversível) com `ConfirmDialog` `danger` +
   campo de motivo + **step-up TOTP** (Spec 69).

## Contexto (por que agora)

Descrição de produto do usuário (2026-08-15) — a tela de **configuração de
ajuste fino por lojista** do sistema de referência (regra de taxa, limites e
taxas de saque, gateway/adquirente, rate limit integrado, kill switch e debitar
saldo). Backend: **Spec 98** (criada junto — endpoints e colunas). A UI segue o
padrão dos formulários do console (Specs 72/73) com `Field` + validação,
banners e step-up no cliente para debit/ban.

## Critérios de aceite

1. **Rota**: `/console/merchants/:id/config` (SPA fallback + `spaPublicPaths` +
   CSP — lição Specs 76/80/84). Menu "Lojistas" → linha → "Config" (Spec 99).
2. **Formulário de Regra de Taxa**: select `pix_fee_type` (FIXED / PERCENT /
   "Padrão da plataforma" = null) + campos condicionais (`pix_fee_fixed` para
   FIXED; `pix_fee_percent` + `pix_fee_threshold` para PERCENT); validação
   (percentual 0–100, threshold > 0, valores ≥ 0, 2 casas); placeholder "usa
   padrão global (R$ 0,50)" quando vazio.
3. **Taxas e Limites de Saque**: campos `withdrawal_fee` (placeholder "R$ 2,50
   padrão"), `pix_min`/`pix_max`, `withdrawal_min`/`withdrawal_max`; validação
   `min ≤ max` e ≥ 0; "limite global" quando vazio.
4. **Gateway (Adquirente)**: select `gateway_route` — "PADRAO (cascata
   global)" ou os provedores conhecidos (`pagarme`, `asaas`, …); validação
   contra os providers reais (o backend rejeita desconhecido — 400).
5. **Rate Limit Integrado**: campos `rate_limit_rps` e `rate_limit_rpm`
   (inteiros > 0; vazio = global); helper de texto "blindar a API contra força
   bruta ou robôs de teste de cartão/chave".
6. **Kill Switch**: radio Ativo (`ACTIVE`) / Inativo-Bloqueado (`INACTIVE`) —
   `POST .../config/activate|deactivate` com `ConfirmDialog` (confirmar que
   bloqueia login/API/saques); estado reflete o `status` atual do lojista.
7. **Debitar saldo**: botão "Debitar saldo" → modal com valor + motivo
   (obrigatório) + preview do saldo atual; **step-up TOTP no cliente** (403
   `step_up_required` → `StepUpPrompt` → repete a ação — padrão Spec 72/73);
   sucesso → banner com novo saldo; erro → `ErrorBanner`.
8. **Honeypot**: toggle "Marcar como honeypot" com `ConfirmDialog` explicando:
   "a conta continua operando, mas cobranças passam por um fluxo de
   monitoramento controlado (provedor trap) e saques ficam bloqueados — sem
   alertar o atacante". Reversível (desligar).
9. **Bloquear API**: toggle `api_blocked` com `ConfirmDialog` ("bloqueia a API
   key do lojista; o portal continua funcionando").
10. **Banir**: botão "Banir conta" (tom `danger`) → modal com motivo
    (obrigatório) + aviso de irreversibilidade; **step-up TOTP**; sucesso →
    banner + linha atualiza (status BANNED); botão some após banido.
11. **RBAC + UX**: ações escondidas sem permissão (Spec 69 — `merchant.config`
    para o formulário; step-up para debit/ban); `Field` + `required`,
    `SuccessBanner`/`ErrorBanner` (`role=status`/`role=alert`), Spinner,
    `ConfirmDialog`/`StepUpPrompt` acessíveis (Spec 73); estado de loading/
    vazio; recarregar a config sem zerar o formulário.
12. **Testes**: `npm run build` (tsc) limpo no console + E2E Playwright
    `master-config-ui.spec.js` (carregar config → editar regra de taxa →
    salvar (banner) → gateway override → rate limit → kill switch →
    debitar com step-up (janela TOTP) → honeypot → bloquear API → banir com
    motivo; 403 sem permissão/step-up) + suíte completa sem regressão.

## Dependências

- **Spec 98** (backend) — endpoints config/debit/honeypot/ban/activate/
  deactivate.
- **Spec 99** (painel) — menu de ações navega para `/config`.
- **Spec 72** (step-up no cliente) — `useStepUp`/`StepUpPrompt` para debit/ban.
- **Spec 73** (UX) — formulários, banners, `ConfirmDialog`, `useDialogFocus`.
- **Spec 69** (RBAC) — permissões (`merchant.config`, `merchant.debit`,
  `merchant.ban` — adicionadas na Spec 98).

## Fora de escopo

- **Backend** — Spec 98.
- **Tabela do painel** — Spec 99.
- **Aprovação manual de cadastro / Rejeitar conta** — fora de escopo
  (decisão do usuário 2026-08-15).
- **Telefone do lojista** — editável aqui? Decisão: **não** nesta spec
  (o `phone` nasce na Spec 97 para exibição; a edição via PUT da config é
  aditiva da Spec 98 — a UI da 100 pode expor o campo se o backend aceitar,
  decisão na implementação).
- **Unban** — BANNED é irreversível (decisão do usuário).
