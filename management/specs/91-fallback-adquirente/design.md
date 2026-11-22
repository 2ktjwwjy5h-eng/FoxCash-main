---
name: Fallback automático de adquirente (cobrança Pix)
status: audited
---

# Spec 91 — Design: Fallback automático de adquirente (cobrança Pix)

## D1 — Conceito

O PSP deixa de ser "1 adapter no boot" e vira uma **fila de provedores com
saúde monitorada**. O `PixProviderRouter` (implementa o mesmo contrato do
`PixProviderClient`) orquestra: tenta o primário → em falha incerta consulta o
primário (anti-dupla-cobrança) → só então tenta o próximo → todos falham →
comportamento atual (502/PENDING da Spec 28). Um **circuit breaker preventivo**
pula provedores com saúde ruim **antes** de tentar.

### D1.1 Decisões do usuário (2026-08-14) → regras

| Decisão | Regra |
|---|---|
| Config/env primeiro (sem UI) | `foxcash.psp.routing.*` + `foxcash.psp.circuit-breaker.*` no yml; painel é follow-up |
| Asaas real já | `AsaasPixProviderClient` na 91, herdando o contract test |
| Anti-dupla-cobrança | erro incerto (timeout/5xx) → `getChargeByCode` no primário antes do fallback |
| Circuit breaker preventivo | saúde por janela de falhas + input de chargeback; desvia volume antes do risco |

## D2 — Porta e contrato

### D2.1 `PixProviderClient.getChargeByCode` (novo)

A consulta é pelo **`code` (= transactionId)**, não pelo `external_reference`:
no cenário de timeout a resposta do PSP se perdeu e o **`external_reference` é
desconhecido** — o único identificador que conhecemos é o `code` que enviamos
(que cada adquirente guarda na cobrança). Achado da verificação (2026-08-14).

```java
/** Consulta o estado da cobrança no PSP pelo code (transactionId) — Spec 91
 *  poll anti-dupla-cobrança (no timeout o external_reference é desconhecido). */
GetChargeResult getChargeByCode(String code);

/** Estado canônico: FOUND (cobrança existe + QR) | NOT_FOUND | estado bruto do PSP. */
record GetChargeResult(boolean found, String pixQrCode, String pixCopyPaste,
                       String status /* raw do PSP, ex.: "pending"/"PAID" */) {}
```

- **Mock**: o `createCharge` guarda em cache por `transactionId` (= `code`) →
  `getChargeByCode(code)` faz lookup direto: `found=true` com o QR (o
  `external_reference` do mock é `E<uuid>` aleatório — NÃO é o `code`); code
  inexistente → `found=false`.
- **Pagar.me**: `GET {base-url}/charges?code={code}` (busca por código — a v5
  suporta query por `code`; o id da cobrança é desconhecido no timeout);
  lista vazia/`status=deleted` → `NOT_FOUND`; resultado → `FOUND` com o QR da
  última transação.
- **Asaas**: o `code` vai no `externalReference` da cobrança →
  `GET /v3/pix/charges?externalReference={code}` (ou `/v3/payments?externalReference=`);
  vazio → `NOT_FOUND`; achou → `FOUND`.
- Timeout/5xx da consulta → `PixProviderTimeoutException` (resposta incerta: o
  router trata como "não confirmou que não criou").

### D2.2 `PixProviderContractTest` (harness, padrão Spec 38)

`src/test/java/com/foxcash/infrastructure/outbound/pix/PixProviderContractTest.java`
(abstrato, sem `@SpringBootTest` — instancia o adapter concreto):

1. `createCharge` devolve `externalReference` não-vazia + `pixQrCode` +
   `pixCopyPaste`.
2. `getChargeByCode(code)` após create → `found=true` com QR igual.
3. `getChargeByCode(desconhecido)` → `found=false` (ou exceção tipada, conforme o
   contrato do adapter).
4. Falha síncrona do provedor (4xx/5xx real) → `PixProviderException`.
5. Timeout (mock de contrato) → `PixProviderTimeoutException`.

`MockPixProviderClientTest` e `PagarMePixProviderClientTest` (MockWebServer)
**herdam** — a garantia de contrato dos 2 adapters é automática.

## D3 — Adapter Asaas (cobrança)

`AsaasPixProviderClient` (`@ConditionalOnProperty(prefix="foxcash.psp",
name="provider", havingValue="asaas")`), no mesmo pacote `infrastructure/outbound/pix`:

- **API v3**: `POST /v3/pix/charges` (ou `/v3/payments` com `billingType=PIX`)
  e `GET /v3/pix/charges/{id}`; auth via header `access_token: <token>`.
- **Mapeamento**: `POST` → `{id, pixQrCode (base64), encodedImage, payload
  (copia-e-cola)}` → `CreateChargeResult(externalReference=id, pixQrCode,
  pixCopyPaste=payload)`; `code` (transactionId) enviado como `externalReference`
  do Asaas quando suportado (idempotência no provedor).
- **Timeouts 5s/10s** (padrão Spec 26/M2), `ResourceAccessException` →
  `PixProviderTimeoutException`.
- **Config** (`PspProperties`): bloco `asaas` de cobrança (`secret-key`/token,
  `base-url`) com **fail-fast** quando `provider=asaas` (padrão Spec 10/38).
- **Webhook Asaas**: o Asaas envia o token da API no header (sem HMAC); o
  `WebhookSignatureFilter` valida o token do adquirente (D6).

## D4 — `PixProviderRouter` (orquestrador)

Implementa `PixProviderClient` (o `CreatePixChargeService`/`PixChargePersistence`
continuam injetando o port — o router é o bean `@Primary` quando o routing está
ativo; com `routing.enabled=false` o comportamento atual de adapter único é
preservado).

```text
createCharge(cmd, merchantId):
  policy = routingPolicyResolver.resolve(merchantId)      # D4.1 — lista por risco do lojista
  for provider in ordenadosPor(policy.prioridade, saude): # D5
    try:
      result = provider.createCharge(cmd)                 # mesmo transactionId como code
      registrarSucesso(provider); return result
    catch PixProviderException err:
      if err é DEFINITIVO (4xx de negócio / validação):   # não é timeout/5xx de rede
        registrarFalha(provider); continue                # fallback imediato
      # INCERTO (timeout / 5xx / rede):
      if pollAchouCobranca(provider, cmd.transactionId):  # getChargeByCode — D2.1
        registrarFalha(provider, ignoradaPorPoll=true)
        return provider.getChargeByCode(cmd.transactionId)  # cobrança existe: usa A
      registrarFalha(provider); continue                  # A confirmou não criar → B
  throw ultimaExcecao                                     # 502/PENDING ou FAILED (Spec 28)

pollAchouCobranca(provider, transactionId):
  até 2 tentativas (pequeno backoff, orçamento ~2s):
    r = provider.getChargeByCode(transactionId)
    if r.found: return true
    if NOT_FOUND: return false
  return false  # consulta incerta → não arrisca dupla cobrança: trata como "não confirmou"
```

**Nota de idempotência (Spec 28):** o retry com a mesma `idempotencyKey` re-envia
o mesmo `code` (= transactionId). Como o namespace do `external_reference` é por
adquirente, usar o mesmo `transactionId` no A e no B **não colide** — e a
transação persistida guarda qual provedor criou (D7).

### D4.1 — Política de roteamento por risco do lojista (decisão do usuário 2026-08-14)

`RoutingPolicyResolver.resolve(merchantId)` → `RoutingPolicy(providers[])`:

```text
resolve(merchantId):
  score = merchantRisk.score(merchantId)          # snapshot V9 (Spec 19), 0-100; null = sem snapshot
  for tier in policies.tiers (ordenado por score-min desc):
    if score != null && score >= tier.scoreMin: return tier.policy
  return policies.default                          # sem snapshot / score abaixo de todos os limiares
```

- **Fonte**: `merchant_risk.risk_score` (Spec 19 — coluna real do snapshot,
  `MerchantRiskJpaEntity.risk_score`; snapshot V9). O resolver usa a porta
  existente **`RiskRepository.latestByMerchant(merchantId)`**; sem snapshot →
  `default`.
- **Config** (`foxcash.psp.routing.policies.*`):
  ```yaml
  foxcash:
    psp:
      routing:
        policies:
          default: { providers: "pagarme,asaas" }            # lista padrão
          high-risk: { providers: "suitpay,asaas", score-min: 60 }  # ex.: Suitpay (adapter futuro)
  ```
  (nome da policy = chave do mapa; `score-min` define a faixa; várias faixas
  ordenadas por `score-min` desc.)
- **Isolamento por nicho**: a operação **white** nunca envia tráfego para os
  adquirentes da policy de risco alto (e o lojista high-risk nunca cai nos
  adquirentes white) — as listas são **disjuntas por design**. A malha de
  segurança (Specs 16/19 — auto-suspensão, score) continua agindo sobre o
  lojista normalmente: se ele estourar o limite no adquirente de risco, é
  suspenso como hoje; o tráfego white não foi tocado.
- **Plug-and-play**: uma policy nova (ex.: `international`) é só config +
  adapter herdando o `PixProviderContractTest` — o motor já sabe rotear.
- **Custo (fora de escopo)**: a escolha da lista é por **risco**; rotear por
  taxa (custo por adquirente) fica para spec futura (mesmo resolver pode
  ganhar uma dimensão de custo depois).
- **Métrica**: `nyx_psp_route_total{provider,policy}` — visibilidade de qual
  policy/provedor criou cada cobrança (auditoria e calibração das faixas).

## D5 — Circuit breaker preventivo

`PixProviderHealthRegistry` (em memória, padrão Spec 64):

- **Janela de falhas**: contador de `PixProviderTimeoutException`/5xx por
  provedor em `window` (ex.: 5 falhas em 60s) → circuito **OPEN** por
  `cooldown` (ex.: 5 min) → depois **HALF-OPEN** (deixa 1 passar; sucesso fecha,
  falha reabre).
- **Kill-switch manual**: `foxcash.psp.circuit-breaker.providers.<p>.enabled=false`
  → provedor fora da rota (mesmo efeito de circuito aberto).
- **Input de chargeback (Spec 16/19)**: `RiskMonitoringService` alimenta o
  registry por **lojista** — provedor do lojista com chargeback ≥ limiar abre o
  circuito **para aquele lojista** (desvia só o volume dele; o restante segue no
  primário).
- **Alerta**: ao abrir → publica `PROVIDER_CIRCUIT_OPEN` no outbox (Spec 35,
  transação própria `REQUIRES_NEW`, payload mascarado — só provider/reason) →
  webhook de operador (Spec 64); fail-safe (WARN throttled se falhar).
- **Métricas** `nyx_psp_circuit_{state,total}{provider}` + contadores de
  `createCharge` por provedor (`nyx_psp_charge_created_total{provider}`).

## D6 — Webhook por adquirente

`WebhookSignatureFilter` (Spec 10/59) passa a validar `/webhooks/pix` contra o
segredo de **cada adquirente configurado** (ordem de `routing.providers`):

- **Pagar.me**: HMAC-SHA256 do raw body com `foxcash.psp.pagarme.webhook-secret`
  (atual) — header `X-Psp-Signature`.
- **Asaas**: token no header (`access_token`/`x-api-key`) comparado em tempo
  constante com `foxcash.psp.asaas.secret-key`.
- Adquirente sem segredo configurado em prod → **fail-closed** (rejeita).
- A transação é resolvida por `external_reference` (como hoje); a coluna
  `provider` (D7) permite logar/rogar a origem e direcionar a reconciliação.

## D7 — Persistência (migration V58)

```sql
ALTER TABLE pix_transactions ADD COLUMN provider VARCHAR(16);
-- null = legado/mock (sem router); 'pagarme'/'asaas' preenchidos na criação via router.
-- Sem índice dedicado (o acesso é por transaction id / external_reference, já indexado).
```

- `PixTransaction` ganha `provider` (domínio + entity + mapper); resposta da
  venda (`/portal/sales/{id}` etc.) não muda o contrato (provider é interno,
  mas pode aparecer como campo aditivo se útil).

## D8 — Configuração

```yaml
foxcash:
  psp:
    routing:
      enabled: ${NYX_PSP_ROUTING_ENABLED:false}   # false = adapter único atual
      providers: ${NYX_PSP_ROUTING_PROVIDERS:}     # ex.: "pagarme,asaas" (ordem = prioridade)
      policies:                                    # D4.1 — roteamento por risco do lojista
        default: { providers: "pagarme,asaas" }     # lista padrão (sem snapshot / score baixo)
        high-risk: { providers: "suitpay,asaas", score-min: 60 }   # ex.: Suitpay (adapter futuro)
    circuit-breaker:
      window-seconds: 60
      failure-threshold: 5
      cooldown-seconds: 300
      providers: {}                                # {pagarme: {enabled: true}, asaas: {...}}
    asaas:                                          # cobrança (novo)
      base-url: ${NYX_PSP_ASAAS_BASE_URL:https://api.asaas.com}
      secret-key: ${NYX_PSP_ASAAS_SECRET_KEY:}      # fail-fast quando provider=asaas
      webhook-secret: ${NYX_PSP_ASAAS_WEBHOOK_SECRET:}
```

`RoutingProperties` + binding contra o yml real (padrão Spec 68/L16). Fail-fast:
`routing.enabled=true` com `providers` vazio, ou provedor sem adapter/segredo
em prod → boot falha.

## D9 — Testes

**Unitários/integração:** `PixProviderContractTest` (2 adapters), router
(definitivo → fallback imediato; incerto → poll acha → usa A sem fallback;
incerto → poll não acha → B; todos falham → exceção do último; circuito aberto →
pula; kill-switch; HALF-OPEN), `PixProviderHealthRegistryTest`,
`RoutingPolicyResolverTest` (score → policy; faixas; sem snapshot → default;
thresholds custom), binding.

**E2E (`psp-fallback.spec.js`):** mock A fora do ar (5xx) → cobrança criada no B
com QR; timeout de A com cobrança **criada** (mock devolve QR no getChargeByCode) →
poll acha → MESMA transação sem dupla cobrança e sem chamada ao B; circuito
aberto → vai direto ao B; webhook do B processa o PAID (provider=B); **lojista
high-risk (score ≥ limiar) → cobrança criada no adquirente da policy dele**
(ex.: Suitpay) e lojista default no Pagar.me (isolamento por policy).
