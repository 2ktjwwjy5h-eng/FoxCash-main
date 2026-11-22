---
name: Gateways — UI do portal
status: audited
---

# Spec 108 — Task: Gateways (UI)

## F0 — Página + componentes

- [x] **T0.1** — `GatewaysPage.tsx` (rota `/gateways`) + `GatewayCard.tsx`,
      `FallbackChain.tsx`, `GatewayCatalog.tsx` (Spec 107).

## F1 — Rotas/SPA + build

- [x] **T1.1** — `/gateways` no `PortalSpaController` + `spaPublicPaths()`.
- [x] **T1.2** — `npm run build` (tsc) limpo no portal.

## F2 — E2E + fechamento

- [x] **T2.1** — E2E `portal-gateways-ui.spec.js` (D3).
- [x] **T2.2** — Suíte completa sem regressão + roadmap/status.

## Verificação (`/spec-verify 108`)

- [x] Checar âncoras (`App.tsx`, `PortalSpaController`, `spaPublicPaths`,
      `useAsync`) — sem achados.

---

## Audit (Spec 108) — 2026-08-17

Implementação auditada com sucesso. Código revisado, testes validados.

**Frontend (Spec 108) — entregue e auditado:**
- `GatewaysPage.tsx` (rota `/gateways`): Spinner + ErrorBanner + empty state
  ("Nenhum adquirente configurado.") — padrão UX Spec 73.
- `FallbackChain`: fila horizontal Principal → Fallback 1 → Fallback 2 com setas
  "→" + badge de circuito (CLOSED verde / OPEN vermelho / HALF_OPEN âmbar) +
  texto "Rotação automática em falha (circuit breaker — Spec 91)".
- `GatewayCard`: displayName, badge de ordem (Principal/Fallback N),
  circuitState, volumePeriod (formatMoney) + countPeriod ("N pagamentos no mês").
- `GatewayCatalog`: seções PIX/CARTÃO/CRIPTO com badges "Disponível"/"Em breve"
  (available) e "Conectado"/"Não conectado" (configured) — read-only (sem
  botão de ativação — evolução, mudaria o router Spec 91).
- Rota wired: `/gateways` no `App.tsx` + `PortalSpaController` +
  `spaPublicPaths()` (`SecurityConfig`) — lição Specs 80/84.

**Verificações executadas:**
- `npm run build` (portal): limpo, sem erros (tsc).
- E2E `portal-gateways-ui.spec.js`: 2/2 passed (fallback chain com ordem da
  policy, cards com extrato R$ 123,45 · 1 pagamento, catálogo com seções e
  badges, API sem token → 401).
- `mvn test` (backend): 2082/0/0 (sem regressão).

**Achados:**
- 0 achados de código (o único achado de doc — nome do E2E no design 107 D4 —
  foi corrigido na spec 107).
