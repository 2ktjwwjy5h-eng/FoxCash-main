---
name: Configuração fina por lojista — UI do Master Console
status: audited
---

# Spec 100 — Task: Configuração fina por lojista (UI Master Console)

## F0 — Tipos + rota

- [x] **T0.1** — `types.ts` += `MerchantConfig`, `PixFeeType`, `DebitResult`,
      `BanRequest`.
- [x] **T0.2** — `App.tsx`: rota `/console/merchants/:id/config` →
      `MerchantConfigPage` (antes da `:id/*`). Menu "Lojistas" → "Config"
      (Spec 99).
- [x] **T0.3** — `SecurityConfig.spaPublicPaths()` + `ConsoleSpaController`:
      rota `/console/merchants/:id/config` coberta (lição Spec 80/84).

## F1 — Página de config (seções)

- [x] **T1.1** — `MerchantConfigPage.tsx`: `GET /master/merchants/{id}/config`
      (Spec 98) → formulário por seção; `PUT` parcial por seção com banner de
      sucesso/erro (Spec 73); loading/empty; recarregar sem zerar.
- [x] **T1.2** — Seção **Regra de Taxa**: select FIXED/PERCENT/"Padrão" +
      campos condicionais + validação (0–100, threshold > 0, 2 casas).
- [x] **T1.3** — Seção **Taxas e Limites de Saque**: `withdrawal_fee` +
      limites (min ≤ max, ≥ 0; placeholder "R$ 2,50 padrão"/"limite global").
- [x] **T1.4** — Seção **Gateway**: NÃO implementada (desvio deliberado — o backend da Spec 98 não tem `gateway_route`; coordenação 2026-08-15 — ver design D4.3).
- [x] **T1.5** — Seção **Rate Limit**: `rate_limit_rps`/`rate_limit_rpm`
      (inteiros > 0; helper de proteção contra força bruta/robôs).
- [x] **T1.6** — `ConfigSection.tsx` (componente reutilizável: título, Field,
      botão Salvar, banner).

## F2 — Controle de conta (kill switch + debit)

- [x] **T2.1** — Radio Ativo/Inativo-Bloqueado (do `status`) → `POST
      .../config/activate|deactivate` com `ConfirmDialog` (explica o bloqueio).
- [x] **T2.2** — `DebitModal.tsx`: valor + motivo (obrigatório) + saldo atual;
      **step-up TOTP no cliente** (`useStepUp`/`StepUpPrompt` — Spec 72);
      sucesso → banner com novo saldo; erro → `ErrorBanner`.

## F3 — Honeypot + bloquear API + banir

- [x] **T3.1** — Toggle **honeypot** → `ConfirmDialog` (ambiente controlado:
      conta continua operando, fluxo trap, saques bloqueados, sem alertar o
      atacante) → `POST .../config/honeypot` `{enabled}`.
- [x] **T3.2** — Toggle **api_blocked** → `ConfirmDialog` → `PUT config`
      (`apiBlocked`).
- [x] **T3.3** — `BanModal.tsx`: motivo obrigatório + aviso irreversível +
      **step-up TOTP** → `POST /merchants/{id}/ban`; sucesso → banner + status
      BANNED; botão some após banido.

## F4 — RBAC-aware + build

- [x] **T4.1** — `RequirePermission` nas seções/ações (Spec 69:
      `merchant.config` no formulário; `merchant.debit`/`merchant.ban` nas
      ações sensíveis com step-up). 403 → banner (sessão permanece — Spec 71).
- [x] **T4.2** — `npm run build` (tsc) limpo no console (ordem portal →
      console — lição Spec 73).

## F5 — E2E + fechamento

- [x] **T5.1** — E2E `master-config-ui.spec.js` (D8): carregar → regra de taxa
      FIXED/PERCENT → gateway → rate limit → kill switch → debitar com step-up
      (janela TOTP — seed `stepped_up_at` expirado, padrão Spec 72) → honeypot
      on/off → api_blocked → banir (irreversível) → 403 sem permissão/step-up.
- [x] **T5.2** — Suíte E2E completa sem regressão (limpeza no `beforeAll` —
      L6/`global-setup`).
- [x] **T5.3** — README: tela de config documentada.
- [x] **T5.4** — Atualizar roadmap/status.

## Verificação (`/spec-verify 100`)

- [x] Checar critérios ↔ design ↔ task contra o código real (endpoints da
      Spec 98 quando implementados, `App.tsx`/rotas, `spaPublicPaths`,
      `ConsoleSpaController`, `useStepUp`/`StepUpPrompt`, `ConfirmDialog`,
      `Field`, E2E existentes) — sem achados de escopo.

## `/spec-audit 100` (2026-08-17) — AUDITADA

**12/12 critérios OK, 0 achados abertos** (seção gateway NÃO implementada —
desvio por design documentado: rota virou por-perfil na Spec 122; sem endpoint,
coordenação 2026-08-15). Verificação: rota `/console/merchants/:id/config` (SPA
fallback + `spaPublicPaths` + CSP), formulário de regra de taxa
(FIXED/PERCENT/threshold com validação), taxas/limites de saque (override do
R$ 2,50), rate limit por lojista (rps/rpm), kill switch Ativo/Inativo com
ConfirmDialog, Debitar saldo (modal + step-up TOTP no cliente — Spec 72/73),
honeypot (toggle + confirmação do ambiente controlado), bloquear API, banir
(danger + motivo + step-up), RBAC-aware + UX Spec 73. E2E
`master-config-ui.spec.js` presente; `npm run build` limpo. Frontmatter/roadmap
→ `audited`.
