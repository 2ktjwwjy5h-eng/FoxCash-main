---
name: Resiliência de saldo no PSP (sweep + credencial isolada)
status: audited
references:
  - "management/vision.md#V69"
  - "management/adr.md#adr-017-fallback-de-adquirente--resiliência-de-saldo-no-psp-specs-9192"
  - "management/specs/14-withdrawals/requirements.md"
  - "management/specs/32-withdrawal-destination-flow/requirements.md"
  - "management/specs/36-payout-psp/requirements.md"
  - "management/specs/53-job-robustez/requirements.md"
  - "management/specs/64-sec-observability-incident-response/requirements.md"
---

# Spec 92 — Resiliência de saldo no PSP (sweep + credencial isolada)

## O que entrega

Protege o caixa do FoxCash contra **freeze/indisponibilidade do PSP** (bloqueio
jurídico da conta master por fraude/chargeback/quebra de termos — cenário em
que **nenhum fallback reativo funciona**: as chamadas de saque retornam
403/400 e o saldo está travado). Decisões de arquitetura (ADR-017):

1. **Sweep Zero-Balance com buffer** — job diário (ShedLock +
   `JobExecutionService`, Spec 53) transfere o saldo **Disponível** da conta
   master do PSP para a **conta blindada corporativa**, **retendo um buffer**
   configurável (`foxcash.psp.sweep.*`) para os saques dos lojistas. Exposição
   máxima a um freeze = **o que entrou desde o último sweep + buffer** — nunca
   meses acumulados.
2. **Credencial isolada (refino do usuário 2026-08-14)** — o sweep usa
   **somente** a credencial payout-scoped (`foxcash.psp.payout-*.secret-key`,
   bloco já existente no `PspProperties`), **nunca** a chave de cobrança; se a
   chave de cobrança vazar, o ofensor **não move o saldo master**. IP
   whitelist no adquirente (IP de egresso do Railway) + **fail-fast no boot**
   se charge-secret == payout-secret em prod.
3. **Payout fallback** — na **solicitação** do saque do lojista (timeout/5xx do
   provedor de saque A → tenta B). **Freeze não é roteável** — a defesa contra
   freeze é o sweep (item 1), não o fallback de saque; o fallback cobre falha
   operacional.

## Contexto (por que agora)

A análise de operação de alta performance (2026-08-14) deixou claro que o
maior risco de caixa é o **freeze da conta master**: se o gateway congela a
conta (chaves + saldo), o FoxCash fica sem pagar saques e sem acesso ao
dinheiro — independente de fallback. A estratégia é **preventiva e
arquitetural**: nunca deixar o dinheiro "dormir" no gateway (sweep), minimizar
o saldo em risco (buffer) e segregar credenciais (a chave de cobrança não pode
mover saldo). Decisões do usuário: sweep com buffer, credencial isolada,
payout fallback na solicitação, config/env primeiro.

## Critérios de aceite

1. **Consulta de saldo** — porta nova `PspBalanceProvider.getBalance()` →
   `{ available, blocked?, currency }`; adapters Pagar.me (`GET /balance`) e
   Asaas (`GET /v3/finance/balance`) implementam; mock em dev/test.
2. **Job de sweep** — `PspSweepScheduler` (ShedLock `foxcash-psp-sweep` +
   `JobExecutionService` Spec 53; cron `foxcash.psp.sweep.cron`, default
   06:00 UTC; kill-switch `foxcash.psp.sweep.enabled`): se `available > buffer`
   → `PayoutProviderClient.createPayout` para a **conta blindada corporativa**
   (chave Pix configurada, `foxcash.psp.sweep.destination-key`), valor =
   `available − buffer`; **idempotente por dia**: o `PayoutCommand.withdrawalId`
   é **UUID determinístico** `UUID.nameUUIDFromBytes("sweep:"+LocalDate)`
   (o `code` do PSP é derivado do `withdrawalId` — Pagar.me `code`=withdrawal.id —
   logo o code é **estável no dia** e o replay não duplica, padrão Spec 28);
   falha → `job_runs` FAILED + alerta.
3. **Buffer** — `foxcash.psp.sweep.buffer-amount` (default 0 = zero-balance
   estrito; produção configura para cobrir N dias de saques projetados);
   validação `buffer >= 0` e `destination-key` obrigatória quando enabled.
4. **Credencial isolada** — o job usa `PayoutProviderClient` (que já injeta
   `foxcash.psp.payout-*.secret-key`, Spec 36/38); **fail-fast no boot
   (qualquer perfil — decisão da implementação, mais forte que prod)** se
   `payout-*.secret-key` == `pagarme/asaas.secret-key` (chave de cobrança) →
   mensagem clara; IP whitelist documentada no runbook (egresso do Railway).
5. **Payout fallback** — `PayoutProviderRouter` (seleção de provedor de saque
   por prioridade `foxcash.psp.payout-routing.providers`, config/env): na
   solicitação (timeout/5xx de A) → tenta B (sem poll: saque tem estado
   `PROCESSING` + reconciliação da Spec 36 que resolve o órfão; o `code` é o
   `withdrawal.id`, idempotente por adquirente). **Sem circuito breaker nesta
   spec** — fallback simples por prioridade (**auto-contida**; o registry de
   saúde da Spec 91 pode ser plugado depois, follow-up — implementação 92 → 91,
   decisão do usuário 2026-08-14). **Roteamento
   pós-fallback**: o provedor que **aceitou** é gravado na coluna
   `withdrawals.payout_provider` (já existe — V28); a reconciliação e o webhook
   `/webhooks/payouts` roteiam pelo provedor gravado (o `externalReference`
   sozinho não identifica o provedor).
6. **Métricas + alerta** — `nyx_psp_balance_available{provider}`,
   `nyx_psp_balance_exposure` (available − buffer), `nyx_psp_sweep_*`
   (última varredura, valor, ok/falha) e alerta **exposição alta** (exposure >
   limiar `foxcash.psp.sweep.exposure-alert`) via outbox Spec 35 + webhook de
   operador Spec 64 (payload mascarado, fail-safe).
7. **Config/binding** — `SweepProperties` + binding contra o yml real (Spec
   68/L16); fail-fast (enabled sem destination-key/buffer negativo).
8. **Testes** — `mvn test` verde (balance adapters + contract, sweep:
   transfere excedente/idempotência/skip abaixo do buffer/kill-switch,
   credencial isolada fail-fast, payout fallback A→B, métricas/alerta) + E2E
   (sweep manual via endpoint/run, payout fallback com mock A fora do ar).

## Dependências

- **Specs 14/32/36/38** (saque/payout) — `PayoutProviderClient`, estados,
  reconciliação.
- **Spec 53** (jobs) — `JobExecutionService` + `job_runs` + métricas.
- **Specs 35/64** (outbox + alerta operador) — eventos de sweep/exposição.
- **Spec 91** (router/circuit breaker) — **follow-up (opcional)**: a 92 é
  auto-contida (fallback simples por prioridade — implementação **92 → 91**,
  decisão do usuário 2026-08-14); o registry de saúde da 91 pode ser plugado no
  payout fallback depois (reuso da mecânica de falha/fallback).
- **Spec 68/L16** (binding de config) — padrão dos properties.

## Fora de escopo

- **Split/sub-contas** — Spec 93 futura (ADR-017 item 6).
- **UI de sweep/buffer** — config/env; painel é follow-up.
- **Freeze reativo** — não se automatiza "saque universal" contra freeze (403/
  400 garantidos); a defesa é o sweep preventivo (item 1).
- **Reinvestimento do saldo blindado** — o destino da conta blindada e o que
  fazer com o dinheiro são operacionais (fora do código).
