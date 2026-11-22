---
name: Resiliência de saldo no PSP (sweep + credencial isolada)
status: audited
---

# Spec 92 — Design: Resiliência de saldo no PSP (sweep + credencial isolada)

## D1 — Conceito

O caixa no PSP é tratado como **risco operacional**: quanto mais saldo
"Disponível" fica parado no gateway, maior a perda potencial num freeze. O
**sweep** esvazia o excedente diariamente para a conta blindada corporativa
(retendo um buffer de saques), a **credencial do sweep é segregada** da chave
de cobrança (menor privilégio), e o **payout fallback** cobre falha operacional
de solicitação — não freeze (que a defesa real é o próprio sweep).

### D1.1 Decisões do usuário (2026-08-14) → regras

| Decisão | Regra |
|---|---|
| Sweep com buffer | excedente (available − buffer) → conta blindada; job diário |
| Credencial isolada | sweep usa SÓ `payout-*.secret-key`; fail-fast se == chave de cobrança em prod |
| Payout fallback | solicitação: timeout/5xx de A → tenta B (freeze não é roteável) |
| Config/env primeiro | `foxcash.psp.sweep.*`/`payout-routing.*` no yml; sem UI |

## D2 — Porta de saldo

`domain/port/out/PspBalanceProvider.java`:

```java
/** Saldo da conta master no PSP (Spec 92 — sweep). */
record PspBalance(java.math.BigDecimal available, java.math.BigDecimal blocked,
                  String currency) {}

PspBalance getBalance();
```

- **Pagar.me**: `GET /balance` → `available` (soma dos balances disponíveis).
- **Asaas**: `GET /v3/finance/balance` → `availableBalance`/`blockedBalance`.
- **Mock**: valor configurável (`foxcash.psp.mock-balance`, default 0).
- Bean selecionado pelo mesmo mecanismo do provider (`@ConditionalOnProperty`);
  sem balance provider configurado → o job não roda (ou roda com skip + log).

## D3 — Job de sweep

`PspSweepScheduler` (ShedLock `foxcash-psp-sweep` + `JobExecutionService` Spec
53; cron `foxcash.psp.sweep.cron` default `0 0 6 * * *`; kill-switch
`foxcash.psp.sweep.enabled`):

```text
run():
  bal = balanceProvider.getBalance()              # disponível no PSP
  exposure = bal.available - sweep.bufferAmount
  if exposure <= 0: registrar skip (nada a varrer); return
  sweepId = UUID.nameUUIDFromBytes(("sweep:" + LocalDate.now()).getBytes())  # determinístico/dia
  payoutProvider.createPayout(                    # MESMO PayoutProviderClient (Spec 36)
      withdrawalId = sweepId,                     # code do PSP derivado dele → estável no dia
      amount = exposure,
      destination = PayoutDestination(pixKey = sweep.destinationKey, ...))   # Pix EVP/CPF
  métricas + job_runs + alerta de sucesso/falha
```

- **Idempotência**: o `PayoutCommand.withdrawalId` é um **UUID determinístico por
  dia** (`UUID.nameUUIDFromBytes("sweep:"+LocalDate)`); como o `code` do PSP é
  derivado do `withdrawalId` (Pagar.me `code`=withdrawal.id — Spec 47), o code é
  **estável no dia** → replay do job no mesmo dia não duplica (dedup no
  adquirente; o `createPayout` do mock também). O sweep é **fora do domínio de
  saque** (não cria linha em `withdrawals` — é operação de tesouraria; a trilha é
  `job_runs` + métricas + o próprio PSP).
- **Destino**: `sweep.destination-key` = chave Pix da **conta blindada
  corporativa** (EVP recomendada); obrigatória quando enabled (fail-fast).
- **Falha**: `PayoutProviderException`/timeout → job FAILED (`job_runs` Spec
  53) + alerta `SWEEP_FAILED`; o retry do próximo dia usa o `code` novo
  (idempotência diária preservada; órfão de saque é resolvido pela
  reconciliação da Spec 36 — reusa o pipeline de payout).
- **Exposição em risco num freeze** = `available` pós-último-sweep + buffer +
  cobranças em voo (PENDING). Métrica `nyx_psp_balance_exposure` expõe isso.

## D4 — Credencial isolada (segurança)

- O `PayoutProviderClient` **já** injeta `foxcash.psp.payout-*.secret-key`
  (Specs 36/38) — o sweep passa por ele, logo **nunca** toca a chave de
  cobrança.
- **Fail-fast no boot (prod)**: `PspProperties.validate()` — se
  `payout-pagarme.secret-key` == `pagarme.secret-key` (ou o par Asaas) →
  `APPLICATION FAILED` com mensagem clara ("chave de cobrança não pode ser a
  chave de saque/sweep — configure credencial payout-scoped").
- **IP whitelist**: runbook `deploy/railway.md` documenta restringir a
  credencial payout-scoped ao **IP de egresso do Railway** (o mesmo que o
  `PayoutProviderClient` usa para chamadas reais); a chave de cobrança fica
  sem permissão de transferência no painel do adquirente.
- Se a **chave de cobrança** vazar → o ofensor cria cobranças (fraude
  limitada/auditável), mas **não move saldo** (permissão negada no adquirente +
  sem a chave de saque).

## D5 — Payout fallback

`PayoutProviderRouter` (seleção por prioridade `foxcash.psp.payout-routing.providers`,
config/env; bean `@Primary` quando `payout-routing.enabled=true`):

> **Auto-contida (implementação 92 → 91, decisão do usuário 2026-08-14):** esta
> spec é implementada ANTES da 91 — o payout fallback é **fallback simples por
> prioridade, sem circuito breaker** (o registry de saúde da Spec 91 pode ser
> plugado depois como follow-up, reusando a mecânica de falha/fallback).

```text
createPayout(cmd):
  for provider in ordenadosPor(prioridade):
    try: return provider.createPayout(cmd)   # code = withdrawal.id (Spec 36)
    catch timeout/5xx: registrarFalha; continue
    catch erro definitivo (ex.: chave inválida): throw (não faz sentido tentar B)
  throw ultimaExcecao   # fluxo atual: PROCESSING + reconciliação resolve órfão
```

- **Sem poll** (diferente da cobrança da Spec 91): o saque nasce `PROCESSING`
  e a reconciliação (Spec 36) resolve órfãos consultando o provedor — tentar
  "confirmar" antes do fallback duplicaria o desenho sem ganho (o saque não tem
  QR/custo de dupla cobrança; o débito do wallet é único via ledger Spec 27).
- **Roteamento pós-fallback (achado da verificação 2026-08-14)**: o
  `PayoutProviderRouter` roteia o **`createPayout`** (a solicitação), mas o
  `getPayout(externalReference)` da reconciliação/webhook **não identifica o
  provedor**. A coluna **`withdrawals.payout_provider` já existe** (V28, Spec
  36) → o provedor que **aceitou** é gravado nela (a persister atual grava o
  provedor configurado; com o router, grava o provedor escolhido); a
  reconciliação e o `POST /webhooks/payouts` roteiam pelo provedor gravado.
- **Freeze ≠ roteável**: se o provedor de saque **bloqueou a conta** (403/400
  de permissão/saldo), o fallback para outro provedor NÃO move o dinheiro
  (que está no PSP congelado) — a defesa é o sweep (D3), que já esvaziou o
  excedente; o fallback cobre apenas falha **operacional** de solicitação.

## D6 — Métricas + alertas

- `nyx_psp_balance_available{provider}` (gauge) — disponível no PSP.
- `nyx_psp_balance_exposure` (gauge) — `available − buffer` (o que está em
  risco num freeze além do buffer).
- `nyx_psp_sweep_total{outcome}` / `nyx_psp_sweep_amount_total` /
  `nyx_psp_sweep_last_timestamp` — execuções do job.
- **Alerta exposição alta**: `exposure > foxcash.psp.sweep.exposure-alert` →
  `SWEEP_EXPOSURE_HIGH` no outbox (Spec 35, `REQUIRES_NEW`, payload mascarado)
  → webhook de operador (Spec 64); **alerta falha do sweep** → `SWEEP_FAILED`.
  Fail-safe (WARN throttled se o alerta falhar).

## D7 — Configuração

```yaml
foxcash:
  psp:
    mock-balance: ${NYX_PSP_MOCK_BALANCE:0}
    sweep:
      enabled: ${NYX_PSP_SWEEP_ENABLED:false}
      cron: ${NYX_PSP_SWEEP_CRON:0 0 6 * * *}
      buffer-amount: ${NYX_PSP_SWEEP_BUFFER_AMOUNT:0}        # R$ retido p/ saques
      destination-key: ${NYX_PSP_SWEEP_DESTINATION_KEY:}     # chave Pix da conta blindada
      exposure-alert: ${NYX_PSP_SWEEP_EXPOSURE_ALERT:10000}
    payout-routing:
      enabled: ${NYX_PSP_PAYOUT_ROUTING_ENABLED:false}
      providers: ${NYX_PSP_PAYOUT_ROUTING_PROVIDERS:}        # ex.: "pagarme,asaas"
```

`SweepProperties` + binding contra o yml real (Spec 68/L16); fail-fast:
enabled sem destination-key / buffer negativo / credencial isolada violada (D4).

## D8 — Testes

**Unitários/integração:** balance adapters + contract, sweep (transfere
excedente, skip abaixo do buffer, idempotência por dia, kill-switch, falha →
job FAILED + alerta), credencial isolada (fail-fast), payout fallback (A 5xx →
B; A erro definitivo → propaga), binding.

**E2E (`psp-sweep.spec.js`):** configura balance mock alto → roda o sweep
(disparo manual via endpoint de admin/master ou chamada direta ao service com
cron forçado) → payout criado com `code=sweep:<hoje>` para a conta blindada;
replay no mesmo dia → sem duplicar; saldo abaixo do buffer → skip; payout
fallback com mock A fora do ar → saque PROCESSING no B.
