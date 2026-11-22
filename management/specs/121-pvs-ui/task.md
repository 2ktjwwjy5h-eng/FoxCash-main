---
name: PVS UI portal — pagar Pix Copia e Cola com o saldo
status: audited
---

# Spec 121 — Task: PVS UI portal (pagamento via saldo)

Ordem: T0 (estrutura) → T1 (prévia/pagamento) → T2 (limites/erros) → T3
(sucesso/histórico) → T4 (testes/E2E).

## T0 — Estrutura (critério 1)

- [ ] T0.1: Seção "Pagamento via Saldo" no `WithdrawPage.tsx` (D2) — textarea
      `#pvs-pix-code`, validação leve (não-vazio; aviso se não inicia com
      `000201`), estado `pvsPreview`/`pvsLimits`; **sem reestruturar** o
      formulário de saque/card auto-saque existentes.
- [ ] T0.2: `maskKey` para a chave (mesmo padrão da listagem de saques).

## T1 — Prévia + pagamento (critérios 2, 6)

- [ ] T1.1: Card "Prévia do pagamento" — destino mascarado, valor (readonly
      quando o código informa valor / editável e obrigatório quando não), taxa
      R$ 2,50, **total**; botão Pagar desabilitado se total > restante do dia.
- [ ] T1.2: `ConfirmDialog` (Spec 73) com o total antes do submit; `POST
      /api/v1/portal/pvs/pay` `{pixCode, amount?}`; loading/desabilitar durante
      a chamada (padrão do saque existente).

## T2 — Limites + erros (critérios 3, 4)

- [ ] T2.1: Card "Limites do dia" via `GET /api/v1/portal/pvs/limits` — teto
      diário, usado hoje, restante (floor 0), teto por operação; barra de
      progresso (aviso ≥ 80%).
- [ ] T2.2: Mapa de erros (D4) para os códigos da Spec 120 +
      `ErrorBanner`; erro de teto recarrega `pvsLimits`.

## T3 — Sucesso + histórico (critério 5)

- [ ] T3.1: 200 → mostra `PROCESSING` + saldo atualizado; limpa o formulário;
      recarrega saldo, limites e a listagem de saques (o item BILL_PAYMENT já
      vem — backend não filtra).

## T4 — Testes (critérios 7, 8)

- [ ] T4.1: Testes de componente (padrão do portal) — máscara; valor readonly vs
      editável; botão Pagar desabilitado por limite; mapa de erros; sucesso
      limpa/recarrega.
- [ ] T4.2: E2E (`portal-ui.spec.js`, padrão Spec 73) — fluxo feliz (cola →
      prévia → confirma → PROCESSING → item no histórico); `pvs_dynamic_pix_not_supported`;
      `pvs_daily_cap_exceeded` (banner + limites recarregados).
- [ ] T4.3: Regressão na tela — saque com destino (Spec 32/36) e saque
      automático (Spec 70) seguem funcionando.

---

## Implementação (2026-08-15) — notas

- **Prévia best-effort no cliente** (D3): sem parser completo no front — um
  `walkTlv` leve extrai a chave (26.01), o valor (54) e a flag de Pix dinâmico
  (26.25/80/81) SÓ para a prévia/máscara. O backend valida de verdade (CRC/tipo/
  dinâmico). Pix dinâmico é bloqueado client-side com a mensagem mapeada (D4).
- **`pvsErrorMessage`** usada no catch do `handlePvsConfirm` (o `ErrorBanner`
  mostra a mensagem mapeada por código — D4); erros de teto recarregam
  `pvsLimits` (T2.2).
- **E2E no `portal-ui.spec.js`** (T4.2): bloco novo `test.describe.serial` com
  lojista próprio + saldo; 3 testes (fluxo feliz, Pix dinâmico → aviso,
  `pvs_max_per_payment_exceeded` → banner no dialog). Ajustes de locator: label
  `Valor (R$)` ambígua (2 campos na página) → escopado ao card "Prévia"; o erro
  do dialog aparece em 2 banners (página + dialog) → asserção escopada ao dialog.
- **Sem testes de componente** (T4.1): o portal não tem infra de vitest (mesmo
  padrão da Spec 123 — coberto por build + E2E).

**Âncoras validadas na spec:** `WithdrawPage.tsx` existente (Spec 73/70);
`Card`/`Field`/`ErrorBanner`/`formatMoney`/`useAsync`/`ConfirmDialog` no
`components/ui` (Spec 73); `GET /api/v1/merchants/{id}/withdrawals` sem filtro de
tipo (Spec 120); endpoints `pay`/`limits` (Spec 120).

## Auditoria (2026-08-15) — 8/8 critérios OK, 1 achado corrigido, 0 abertos

Verificação 1:1 contra o código real (critérios do requirements):

- **C1 (formulário)** — colar o brcode + "Ver prévia" monta a prévia; com valor
  no código (54) o campo fica `readOnly` (preenchido do EMV); sem valor é
  editável e obrigatório; validação client-side (não-vazio + aviso se não
  começa com `000201`).
- **C2 (prévia + confirmação)** — chave mascarada (`529***725`), valor, taxa
  R$ 2,50, total; botão "Pagar R$ X" abre `ConfirmDialog` (danger, Spec 73) com
  o total; botão desabilitado se total > restante do dia.
- **C3 (limites do dia)** — card via `GET /pvs/limits` (teto diário, usado,
  restante floor 0, teto por operação) + barra de progresso (aviso ≥ 80%).
- **C4 (erros explícitos)** — `PVS_ERROR_MESSAGES` (D4) + `pvsErrorMessage` no
  catch do submit; erro de teto → `pvsLimits.reload()` (T2.2).
- **C5 (sucesso)** — 200 → banner PROCESSING + saldo atualizado; formulário
  limpo; `balance`/`withdrawals`/`pvsLimits` recarregados (o BILL_PAYMENT já vem
  no histórico — backend não filtra).
- **C6 (estados de UI)** — `pvsSubmitting` desabilita o botão ("Pagar…"),
  `Spinner` no card de limites, `ErrorBanner`.
- **C7 (acessibilidade/UX)** — labels claros; taxa e total visíveis ANTES de
  pagar. **ACHADO CORRIGIDO**: o critério "foco no campo ao entrar na aba" não
  estava implementado — `autoFocus` no `#pvs-pix-code` + asserção E2E
  `toBeFocused` no fluxo feliz (re-validado: `npm run build` limpo + E2E UI 3/3).
- **C8 (E2E)** — `portal-ui.spec.js` bloco PVS 3/3 (fluxo feliz, Pix dinâmico →
  aviso, `pvs_max_per_payment_exceeded` → banner) + regressão 10/10 (saque com
  destino e auto-saque intactos).
