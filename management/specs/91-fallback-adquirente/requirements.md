---
name: Fallback automático de adquirente (cobrança Pix)
status: audited
references:
  - "management/vision.md#V68"
  - "management/adr.md#adr-017-fallback-de-adquirente--resiliência-de-saldo-no-psp-specs-9192"
  - "management/specs/10-psp-integration-foundation/requirements.md"
  - "management/specs/11-pagarme-adapter/requirements.md"
  - "management/specs/28-charge-idempotency/requirements.md"
  - "management/specs/16-antifraud-compliance/requirements.md"
  - "management/specs/64-sec-observability-incident-response/requirements.md"
---

# Spec 91 — Fallback automático de adquirente (cobrança Pix)

## O que entrega

O FoxCash deixa de depender de um **único adquirente selecionado estático no
boot** (`foxcash.psp.provider=mock|pagarme|asaas` — UM adapter via
`@ConditionalOnProperty`). Um **`PixProviderRouter`** tenta os adquirentes em
ordem de prioridade configurável (`foxcash.psp.routing.*`, config/env, **sem
UI** — decisão do usuário 2026-08-14). Decisões de arquitetura (ADR-017):

- **Fallback seguro na criação** — o Pix só permite fallback **na criação** (o
  QR nasce atrelado ao adquirente que o gerou; cobrança já criada não migra).
- **Anti-dupla-cobrança (refino do usuário)** — em erro **incerto** do primário
  (timeout/5xx), o router **consulta o primário via `getChargeByCode`** (novo método
  no `PixProviderClient`) antes de cair para o secundário: se A criou a cobrança
  (QR existe), usa A; só tenta B se A **confirmar que não criou**. Erro
  definitivo (rejeição de negócio) → fallback imediato, sem consulta.
- **Circuit breaker preventivo** — saúde por adquirente (janela de
  falhas/timeouts, padrão Spec 64, + input de chargeback da Spec 16/19); acima
  do limiar o circuito abre e o volume é **desviado antes** do gateway virar
  risco; métricas `nyx_psp_*` + alerta `PROVIDER_CIRCUIT_OPEN` ao operador.
- **Roteamento por risco do lojista (política — decisão do usuário
  2026-08-14)** — além da saúde do adquirente, o router escolhe a **lista de
  prioridade** conforme o **tier de risco do lojista** (`merchant_risk.risk_score`,
  Spec 19): `default` (ex.: Pagar.me/Asaas) vs `high-risk` (ex.: Suitpay/Asaas)
  — **isola o tráfego por nicho** sem suspender o lojista; a malha antifraude
  (Specs 16/19) continua monitorando o tier alto normalmente (se o lojista
  estourar o limite, é suspenso como hoje — a operação white não sente o
  tráfego dele). Plug-and-play: uma nova policy (ex.: `international`) é só
  config + adapter.
- **Asaas real (refino do usuário)** — adapter real `AsaasPixProviderClient` já
  na 91 (não plug-and-play), herdando um harness **`PixProviderContractTest`**
  junto com o Pagar.me — força a porta `PixProviderClient` verdadeiramente
  agnóstica e habilita smart routing por custo no futuro.
- **Webhook por adquirente** — `/webhooks/pix` valida o segredo de cada
  adquirente configurado; `pix_transactions` ganha a coluna `provider` (V58)
  para rotear webhook/reconciliação.
- **Idempotência preservada** (Spec 28) — mesmo `code` (= transactionId) e
  `idempotencyKey`; o `external_reference` tem namespace por adquirente.

## Contexto (por que agora)

Análise de operação de alta performance (2026-08-14): hoje, se o adquirente
cair, a cobrança falha (502/PENDING, idempotência Spec 28) sem tentar outro —
vendas perdidas por indisponibilidade de terceiro. O ADR-005 já previa
"Pagar.me primário, Asaas alternativa"; a 91 transforma essa alternativa em
**fallback em runtime** com o rigor de produção (sem dupla cobrança, com
proteção preventiva de saúde). Decisões do usuário: **config/env primeiro** (UI
de gateways fica para follow-up), **Asaas real já**, **anti-dupla-cobrança via
poll** e **circuit breaker preventivo**.

## Critérios de aceite

1. **`PixProviderClient.getChargeByCode`** — novo método no port
   (`getChargeByCode(String code)` → status canônico + QR se existir). A busca
   é pelo **`code` (= transactionId)** — no timeout o `external_reference` é
   **desconhecido** (a resposta do PSP se perdeu); só conhecemos o `code` que
   geramos. Pagar.me: `GET /charges?code={code}` (busca por código, v5); Asaas:
   `GET /v3/pix/charges?externalReference={code}` (o `code` vai no
   `externalReference` do Asaas); Mock: lookup no cache por `transactionId`
   (que é o `code`). Timeout/5xx da consulta → `PixProviderTimeoutException`
   (resposta incerta — o router trata como "não confirmou").
2. **`PixProviderContractTest`** — harness abstrato (padrão
   `PayoutProviderContractTest` da Spec 38): `createCharge` devolve
   `externalReference` + QR, `getChargeByCode` devolve estado/QR, timeout → exceção
   tipada. **`MockPixProviderClient` e `PagarMePixProviderClient` herdam** e
   passam.
3. **`AsaasPixProviderClient`** — adapter real (`@ConditionalOnProperty
   provider=asaas`), API v3 (cobrança Pix + consulta), auth por token
   (`access_token`), timeouts 5s/10s, **herda o `PixProviderContractTest`**;
   `PspProperties` ganha o bloco `asaas` (cobrança) com **fail-fast** no boot
   quando `provider=asaas`.
4. **`PixProviderRouter`** — implementa o mesmo contrato do port; consome a
   lista de prioridade `foxcash.psp.routing.providers` (ex.: `pagarme,asaas`):
   - Erro **definitivo** de A → fallback imediato para o próximo.
   - Erro **incerto** (timeout/5xx) de A → **poll `getChargeByCode` em A** (1–2
     tentativas, orçamento curto): **achou** (QR) → usa A (sem fallback, sem
     dupla cobrança); **não achou** (ou consulta incerta) → tenta B.
   - Todos falham → `PixProviderTimeoutException`/`PixProviderException` do
     último (fluxo atual da Spec 28 preservado: 502/PENDING ou FAILED).
   - **Circuit breaker**: provedor com circuito aberto é **pulado** na
     ordenação (só volta após cooldown/half-open).
5. **Circuit breaker preventivo** — `PixProviderHealthRegistry`: janela de
   falhas/timeouts por provedor (`foxcash.psp.circuit-breaker.*`: limiar, janela,
   cooldown, kill-switch por provedor); input de chargeback (Spec 16/19):
   provedor do lojista com chargeback acima do limiar abre o circuito **por
   lojista**; métricas `nyx_psp_circuit_*{provider}` + **alerta ao operador**
   `PROVIDER_CIRCUIT_OPEN` (outbox Spec 35 + webhook Spec 64, payload mascarado,
   fail-safe).
6. **Webhook por adquirente** — `WebhookSignatureFilter` valida `/webhooks/pix`
   contra o segredo de **cada adquirente configurado** (em ordem, tempo
   constante; Pagar.me = HMAC-SHA256, Asaas = token no header); a transação
   resolvida carrega o `provider`; sem segredo configurado para o adquirente →
   o filtro rejeita (fail-closed).
7. **Migration `V58__provider_on_pix_transactions.sql`** — `pix_transactions`
   ganha `provider VARCHAR(16)` (null = legado/mock); preenchido na criação via
   router; usado na reconciliação/consulta de venda.
8. **Config/binding** — `PspProperties` + `RoutingProperties` (blocos
   `foxcash.psp.routing.*` e `foxcash.psp.circuit-breaker.*`) com binding contra
   o yml real (padrão Spec 68/L16); **fail-fast**: `routing.providers` vazio ou
   com provedor sem adapter/segredo em prod → boot falha.
9. **Idempotência Spec 28 intacta** — replay com a mesma `idempotencyKey`
   devolve a MESMA transação (e o `provider` que a criou); timeout → PENDING
   retido; retry re-envia o mesmo `code`.
10. **Testes** — `mvn test` verde (harness 2 adapters + router: fallback
    definitivo/incerto/poll-achou/poll-não-achou/todos-falham, circuit breaker
    abre/cooldown/kill-switch, webhook por adquirente, binding) + E2E (mock A
    fora do ar → cobrança criada no B; timeout de A com cobrança criada → poll
    acha → sem dupla cobrança e sem fallback; circuito aberto → vai direto ao B;
    webhook do B processa).
11. **Política de roteamento por risco do lojista** — `RoutingPolicyResolver`
    resolve o tier do lojista a partir do `merchant_risk.risk_score` (Spec 19,
    snapshot V9) → escolhe a lista de prioridade (`foxcash.psp.routing.policies.*`):
    - `default` (lojista sem snapshot/score baixo) → lista padrão;
    - `high-risk` (score ≥ limiar configurável) → lista própria (ex.:
      `suitpay,asaas`);
    - thresholds das faixas configuráveis; lojista sem policy → `default`;
    - **isolamento**: a operação white não envia tráfego para os adquirentes do
      tier alto (e vice-versa) — a suspensão/antifraude (Specs 16/19) continua
      agindo normalmente sobre o lojista;
    - teste unitário (resolver por score/faixas/fallback default) + E2E
      (lojista high-risk → cobrança criada no adquirente da policy dele).

## Dependências

- **Specs 10/11** (porta + Pagar.me) — base dos adapters.
- **Spec 28** (idempotência) — `code`/`idempotencyKey` preservados.
- **Spec 38** (padrão `PayoutProviderContractTest`) — harness de contrato.
- **Specs 16/19** (chargeback/risco) — input do circuit breaker.
- **Spec 35/64** (outbox + alerta de operador) — `PROVIDER_CIRCUIT_OPEN`.
- **Spec 53** (jobs/métricas) — infra de métricas e jobs.
- **Spec 68/L16** (binding de config) — padrão dos properties.

## Fora de escopo

- **UI de gateways/prioridade** — config/env nesta fase; painel é follow-up.
- **Smart routing por custo** — só a fundação (interface agnóstica + 2
  adapters) + a política por risco (C11); rotear por **taxa** fica para spec
  futura.
- **Split/sub-contas** — Spec 93 futura (ADR-017 item 6).
- **Sweep/resiliência de saldo** — Spec 92.
- **Cartão de crédito** — a plataforma é Pix; o padrão do router (poll
  anti-dupla-cobrança) foi desenhado pensando no futuro, mas nada de cartão
  nesta spec.
