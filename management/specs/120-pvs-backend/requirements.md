---
name: PVS backend — pagamento via saldo (Pix Copia e Cola estático)
status: audited
references:
  - "management/vision.md#V87"
  - "management/specs/14-withdrawals/requirements.md"
  - "management/specs/32-withdrawal-destination-flow/requirements.md"
  - "management/specs/36-payout-psp/requirements.md"
  - "management/specs/38-payout-acquirer-ready/requirements.md"
  - "management/specs/47-payout-acquirer-adapter/requirements.md"
  - "management/specs/88-withdrawal-fee/requirements.md"
  - "management/specs/92-resiliencia-saldo-psp/requirements.md"
---

# Spec 120 — PVS backend (pagamento via saldo)

## O que entrega

O lojista **paga um Pix Copia e Cola estático** (chave Pix embutida no brcode)
usando o **saldo do wallet** — pagamento a fornecedor/loja **sem precisar
cadastrar destino** (destino arbitrário) e sem sacar. Tecnicamente é **uma
variação do pipeline de saque**: o pagamento nasce como um `withdrawal` de novo
tipo `BILL_PAYMENT`, passa pelo **mesmo fluxo em 2 fases** e tem o desfecho
(COMPLETED/FAILED) resolvido pelos **mesmos webhook, estorno automático e
reconciliação** — **zero mudança** nesses serviços.

1. **Novo tipo `WithdrawalType.BILL_PAYMENT`** — a coluna `withdrawals.type`
   (V21) é `VARCHAR(20) NOT NULL DEFAULT 'MERCHANT'` **sem CHECK no banco** (só
   o enum Java restringe); a entidade usa `@Enumerated(EnumType.STRING)`
   (`length = 20`, `BILL_PAYMENT` = 12 chars). **Mudança de enum apenas** — sem
   alteração de coluna.
2. **Destino arbitrário** — `destination_id = null` (não é um destino cadastrado
   da Spec 31) + a **chave extraída** do Copia e Cola gravada no
   `destination_key_snapshot` (criptografada em repouso pelo `CryptoConverter`,
   mesmo padrão do saque).
3. **`PixCodeParser`** (novo, domínio puro) — parseia o EMV BR Code
   (`000201...`) e extrai a chave Pix (TLV 26.00.01). **Só Pix estático**:
   brcode com payload dinâmico (URL `br.gov.bcb.pix`) e boleto são **rejeitados**
   com mensagem clara. Valida CRC-16 e o formato da chave (CPF/CNPJ/phone/EVP —
   mesmas regras da Spec 31/47).
4. **`PvsPaymentService`** (novo) espelhando o `WithdrawalService`: Fase A nova
   (`WithdrawalProcessingPersister.createBillPaymentWithdrawal`) — ACTIVE +
   desbloqueio + inadimplência + saldo (valor + taxa R$ 2,50, Spec 88) + **teto
   diário + teto por operação** sob o lock do lojista (`findByIdForUpdate`,
   SEC-19) → Fase B `payoutProvider.createPayout` (fora da transação) + estorno
   via `WithdrawalSettlementService.settleFailedByWithdrawalId` em falha
   síncrona + `WithdrawalSubmissionPersister` no aceite.
5. **Ledger reuso** — `WITHDRAWAL_DEBIT` (valor) + `WITHDRAWAL_FEE_DEBIT` (taxa)
   no wallet e `WITHDRAWAL_FEE` no ledger do master (Spec 88). **Sem entry novo**
   — o estorno/reconciliação existentes funcionam sem mudança; a semântica de
   "pagamento" fica no `type = BILL_PAYMENT`.
6. **Anti-abuso (teto diário + gates)** — config `foxcash.pvs.daily-cap`
   (default R$ 5.000,00) somando **todos os PVS criados no dia** (qualquer
   status — tentativa contabiliza) + `foxcash.pvs.max-per-payment` (default R$
   1.000,00) por operação; avaliados **dentro da Fase A sob o lock** (sem
   corrida). Gates de conta reusados (ACTIVE, desbloqueio, inadimplência, saldo).
7. **Endpoints portal** (ROLE_MERCHANT_PORTAL) — `POST /api/v1/portal/pvs/pay`
   `{pixCode, amount?}` e `GET /api/v1/portal/pvs/limits`.
8. **Migration V68** — CHECK documentando `withdrawals.type` + índice composto
   `ix_withdrawals_merchant_type_created` para o teto diário. (V60–V67 estão
   reservadas pelos Specs 97/98 e 101–119.)
9. **Trilha** — `PlatformAction.BILL_PAYMENT_REQUESTED` (Spec 41) no aceite
   (valor sem PII; a chave nunca vai em claro — o recorder só recebe o id/valor).

## Contexto (por que agora)

Análise do código real (2026-08-15): o pipeline de saque **já entrega tudo** que
um pagamento via saldo precisa — `PayoutProviderClient.createPayout` (Specs
36/38/47/92 com fallback), Fase A/B (B-04), estorno automático de **valor +
taxa** (Spec 88) e reconciliação. O que **falta** é só o que o saque **proíbe**
hoje: destino **arbitrário** (o saque exige `destinationId`, Spec 31), parsing
do Copia e Cola (o código só **gera** brcode via `pixCopyPaste`; nunca parseia)
e **anti-abuso** para chave livre. Decisões do usuário (2026-08-15): só Pix
Copia e Cola → **só Pix estático** (o `createPayout` só paga chave; dinâmico/
boleto fora); reusar `withdrawals`; taxa R$ 2,50; teto diário + gates.

## Critérios de aceite

1. **`PixCodeParser`** — dado um EMV estático `000201...`, extrai a chave
   (CPF/CNPJ/phone/EVP) e o valor (campo 54, opcional). Rejeita: não inicia com
   `000201`, TLV malformado, CRC-16 inválido, chave de formato desconhecido,
   **Pix dinâmico** (payload com URL `br.gov.bcb.pix`) — cada caso com mensagem
   clara (400 `pvs_invalid_code` / `pvs_dynamic_pix_not_supported` /
   `pvs_unsupported_key`). `pixCode` **nunca** é logado (máscara).
2. **`POST /api/v1/portal/pvs/pay`** — resolve o valor: se o EMV carrega valor
   (54) e o request manda `amount`, eles **devem bater** (400
   `pvs_amount_mismatch`); se o EMV não carrega valor, `amount` é **obrigatório**
   (400 `pvs_amount_required`). Valida ACTIVE/desbloqueio/inadimplência/saldo
   (reuso Fase A) + teto por operação (409 `pvs_max_per_payment_exceeded`) +
   teto diário (409 `pvs_daily_cap_exceeded`) **sob o lock**. Sucesso → 200
   `{withdrawalId, amount, fee, walletBalance, status: "PROCESSING"}`; cria o
   `withdrawal` com `type = BILL_PAYMENT`, `destination_id = null`,
   `destination_key_snapshot` = chave extraída; ledger `WITHDRAWAL_DEBIT` +
   `WITHDRAWAL_FEE_DEBIT` + `WITHDRAWAL_FEE` (master); PSP chamado fora da
   transação; falha síncrona → **estorno automático** (mesmo fluxo do saque) +
   saque FAILED; aceite → referência gravada (`WithdrawalSubmissionPersister`).
3. **`GET /api/v1/portal/pvs/limits`** — 200 `{dailyCap, usedToday, remaining,
   maxPerPayment}` (usedToday = soma dos PVS criados hoje; remaining = cap −
   used). Read-only, sem lock.
4. **Teto diário sem corrida** — dois PVS concorrentes somando acima do cap:
   exatamente um passa (o lock serializa — SEC-19).
5. **Estorno/reconciliação intactos** — um BILL_PAYMENT FAILED devolve valor +
   taxa (Spec 88); webhook `/webhooks/payouts` e reconciliação tratam o
   BILL_PAYMENT como qualquer outro saque, **sem mudança de código**.
6. **Histórico** — `GET /api/v1/merchants/{id}/withdrawals` (portal) já mostra
   os BILL_PAYMENT (o `findByMerchantIdOrderByCreatedAtDesc` não filtra por
   tipo) — **sem mudança**.
7. **Migration V71** *(desvio de numeração: o design previa V68, mas V69/V70 já
   foram aplicadas pelas Specs 122/124 — Flyway sem `out-of-order` não aplica
   versão menor; o número real usado é o V71)* — aditiva: `CHECK (type IN
   ('MERCHANT','ADMINISTRATIVE','BILL_PAYMENT'))` (dados existentes passam) +
   `ix_withdrawals_merchant_type_created`.
8. **Trilha** — `BILL_PAYMENT_REQUESTED` registrado no aceite (actor MERCHANT,
   `{amount}` apenas).
9. **Testes** — `mvn test` verde: parser (estático/CPF/CNPJ/phone/EVP, valor no
   EMV, CRC, dinâmico, malformado, chave desconhecida), Fase A (gates sob lock,
   saldo, taxa), serviço (sucesso, mismatch/amount_required, falha PSP →
   estorno, aceite → submit), limits, controller (RBAC/erros). E2E: PVS com
   mock payout PAID.

## Dependências

- **Specs 14/32/36/38/47** (saque/payout) — `PayoutProviderClient`, estados,
  Fase A/B, `WithdrawalSettlementService`/`WithdrawalReconciliationService`,
  `WithdrawalSubmissionPersister`, webhook de saída.
- **Spec 88** (taxa de saque) — `PricingProperties.getWithdrawalFee()` (R$ 2,50)
  e estorno de valor + taxa.
- **Spec 92** (resiliência PSP) — fallback de payout na solicitação (router).
- **Spec 31** (destinos) — formato canônico da chave (regras do parser).
- **Spec 41** (trilha) — `PlatformAction` novo valor.
- **Spec 68/L16** (binding de config) — padrão dos `PvsProperties`.
- **Spec 121** (UI) — consome `pay`/`limits` (depende desta spec).

## Fora de escopo

- **Pix dinâmico** (brcode de conta/boleto com URL `br.gov.bcb.pix` + txid) —
  rejeitado com mensagem clara; exigiria **nova capacidade na porta de payout**
  (endpoint do PSP para pagar cobrança externa) → spec futura.
- **Boleto de barras** — exigiria adapter/provedor de boleto (fora do pipeline
  atual).
- **Ledger novo** (`BILL_PAYMENT_DEBIT`) — reuso de `WITHDRAWAL_DEBIT`/
  `WITHDRAWAL_FEE_DEBIT` (estorno/reconciliação intactos); a semântica fica no
  `type`.
- **UI** — Spec 121 (formulário no `WithdrawPage`).
- **Override de limites por lojista** — teto diário/per-op são globais
  (`foxcash.pvs.*`); override por lojista é a Spec 98 (config fina).
- **Chargeback do pagamento recebido pelo lojista** — PVS é saída; o fluxo de
  entrada (cobrança/refund) não muda.
