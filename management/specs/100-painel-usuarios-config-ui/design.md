---
name: Configuração fina por lojista — UI do Master Console
status: audited
---

# Spec 100 — Design: Configuração fina por lojista (UI Master Console)

## D1 — Conceito

Uma página de formulário no console (`/console/merchants/:id/config`) que
edita a `MerchantConfig` da **Spec 98** com **PUT parcial** (null = não
alterar). Formulários por seção (padrão `Field` da Spec 73), ações sensíveis
com **step-up TOTP no cliente** (Spec 72). Sem lib de UI (CSS puro — decisão
2026-08-14).

```text
console/src/
  pages/MerchantConfigPage.tsx  → nova rota /console/merchants/:id/config
  components/ConfigSection.tsx  → seções reutilizáveis (Field + helper)
  components/DebitModal.tsx     → modal debitar saldo (step-up)
  components/BanModal.tsx       → modal banir (step-up)
  App.tsx / Layout.tsx / SecurityConfig(Java) → rota/menu/CSP/SPA
  types.ts                      → MerchantConfig*, DebitResult
```

## D2 — Rota e navegação

- `App.tsx`: `<Route path="/console/merchants/:id/config" element={<MerchantConfigPage />} />`
  (antes da rota `:id/*` — ordem do React Router).
- `SecurityConfig.spaPublicPaths()` += `/console/merchants/:id/config` (ou
  coberto pelo padrão `/console/merchants/*` — verificar; lição Spec 80:
  rotas fora do fallback → 401 em F5).
- `ConsoleSpaController` (fallback) idem.
- Menu "Lojistas" → linha → "Config" (Spec 99 `MerchantActionsMenu`).

## D3 — Estado e formulário

- **Fonte**: `GET /api/v1/master/merchants/{id}/config` (Spec 98) → estado
  local (objeto `MerchantConfig`). Loading com `Spinner`, erro com
  `ErrorBanner`.
- **PUT parcial**: cada seção tem botão "Salvar seção" → `PUT
  /api/v1/master/merchants/{id}/config` com **só os campos da seção** (null
  nos não alterados) → 200 → banner de sucesso (Spec 73) + recarrega.
  - Validação client-side espelhando o backend (400 → `ErrorBanner`).
- **"Padrão da plataforma"**: campos vazios → null → o backend usa o default
  global; a UI mostra o placeholder ("R$ 0,50", "R$ 2,50", etc.) + texto muted
  "usa padrão global".

## D4 — Seções

1. **Regra de Taxa**: select `pix_fee_type` (vazio/"Padrão", FIXED, PERCENT);
   `pix_fee_fixed` (FIXED); `pix_fee_percent` + `pix_fee_threshold` (PERCENT).
   Tooltip/helper: "Pix até R$ X cobra taxa fixa; acima, percentual".
2. **Taxas e Limites de Saque**: `withdrawal_fee`, `pix_min`, `pix_max`,
   `withdrawal_min`, `withdrawal_max` (2 casas; validação `min ≤ max`).
3. **Gateway (Adquirente)**: select `gateway_route` — "PADRAO (cascata
   global)" ou providers (`pagarme`, `asaas`, …). Opções hardcoded conforme os
   adapters da Spec 91 (o backend valida; a lista pode vir de um enum/const).
4. **Rate Limit**: `rate_limit_rps`, `rate_limit_rpm` (inteiros > 0);
   helper "proteção contra força bruta / robôs de teste de cartão ou chave".
5. **Controle de Conta**: radio Ativo/Inativo-Bloqueado (do `status` do
   lojista) → `POST .../config/activate|deactivate` (ConfirmDialog explicando
   o bloqueio); botão "Debitar saldo" → `DebitModal`.
6. **Honeypot**: toggle (do `honeypot`) → `ConfirmDialog` com o texto do
   ambiente controlado (decisão do usuário 2026-08-15) → `POST
   .../config/honeypot`.
7. **Bloquear API**: toggle (do `apiBlocked`) → `ConfirmDialog` → `PUT config`
   (ou endpoint específico da Spec 98).
8. **Banir**: botão "Banir conta" (danger) → `BanModal` (motivo obrigatório +
   irreversibilidade) → `POST /merchants/{id}/ban`.

## D5 — Step-up TOTP no cliente (Spec 72)

- `DebitModal`/`BanModal`: ação → se `ApiError.stepUpRequired` (403
  `step_up_required`) → `StepUpPrompt` → `POST /api/v1/master/mfa/step-up` →
  **repete a ação** automaticamente; falha 401/400/429 mantém o modal; cancelar
  rejeita com o `ApiError` original (padrão `useStepUp` — Spec 72).
- Cache 15 min só UX (fail-closed); a janela real é do backend.

## D6 — Tipos (types.ts)

```ts
type PixFeeType = 'FIXED' | 'PERCENT' | null;
interface MerchantConfig {
  pixFeeType?: PixFeeType; pixFeeFixed?: number; pixFeePercent?: number;
  pixFeeThreshold?: number; withdrawalFee?: number; pixMin?: number;
  pixMax?: number; withdrawalMin?: number; withdrawalMax?: number;
  gatewayRoute?: string; rateLimitRps?: number; rateLimitRpm?: number;
  apiBlocked: boolean; honeypot: boolean; status: MerchantStatus;
}
interface DebitResult { newBalance: number; debitedAmount: number; }
```

## D7 — RBAC-aware

- Seções/buttons escondem sem permissão (Spec 69): formulário = `merchant.config`;
  Debir/Banir = `merchant.debit`/`merchant.ban` (com step-up). `RequirePermission`.
- Backend continua a autoridade (403 → banner, sessão permanece — Spec 71).

## D8 — Testes

- `npm run build` (tsc) limpo no console.
- E2E `master-config-ui.spec.js` (D9): carregar config → editar regra de taxa
  (FIXED salva e recarrega) → PERCENT com threshold → gateway override →
  rate limit → kill switch (deactivate/activate) → debitar com step-up (TOTP
  — seed de `stepped_up_at` expirado, padrão Spec 72) → honeypot on/off →
  api_blocked → banir com motivo (irreversível; botão some) → 403 sem
  permissão/step-up; suíte completa sem regressão.
- Observação: ordem de build portal → console (lição Spec 73).
