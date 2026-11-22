---
name: PVS backend — pagamento via saldo (Pix Copia e Cola estático)
status: audited
---

# Spec 120 — Design: PVS backend (pagamento via saldo)

## D1 — Conceito

O PVS é **saque com destino arbitrário**: o lojista cola um Pix Copia e Cola
**estático**, o backend **extrai a chave** (parser), cria um `withdrawal` de tipo
novo `BILL_PAYMENT` (Fase A sob lock: débito valor + taxa R$ 2,50 + PROCESSING +
ledger) e dispara o **mesmo `PayoutProviderClient.createPayout`** do saque (Fase
B, fora da transação). Desfecho COMPLETED/FAILED + estorno + reconciliação são
**100% reuso** — o tipo novo não muda nenhuma dessas camadas.

### D1.1 Decisões do usuário (2026-08-15) → regras

| Decisão | Regra |
|---|---|
| Só Pix Copia e Cola | `POST pay` aceita apenas EMV `000201...`; nada de chave livre digitada |
| Só Pix **estático** | `PixCodeParser` extrai a chave (TLV 26.00.01); brcode dinâmico (URL `br.gov.bcb.pix`) → 400 com mensagem clara |
| Reusar `withdrawals` | `WithdrawalType.BILL_PAYMENT` (enum; V21 sem CHECK; `@Enumerated(STRING)`); `destination_id = null` + chave no snapshot |
| Taxa R$ 2,50 | débito `amount + fee` (Spec 88); estorno devolve valor + taxa |
| Teto diário + gates | `foxcash.pvs.daily-cap` (default 5000.00) + `max-per-payment` (default 1000.00); avaliados na Fase A sob lock (SEC-19) |
| 2 specs (Backend+UI) | 120 = esta; 121 = UI no `WithdrawPage` |

## D2 — `PixCodeParser` (novo, domínio puro)

`domain/pvs/PixCodeParser.java` — **sem I/O, sem estado**, testável por tabela.
Converte o EMV BR Code em `ParsedPix`:

```java
/** Resultado do parse de um Pix Copia e Cola ESTÁTICO (Spec 120). */
record ParsedPix(String key, String keyType, java.math.BigDecimal amount /* null se ausente */) {}

final class PixCodeParser {
    static ParsedPix parse(String emv);  // lança PixCodeParseException(code, mensagem)
}
```

**Algoritmo (TLV do EMV BR Code):**
1. Prefácio: deve iniciar com `000201` (Payload Format Indicator = 01). Senão →
   `pvs_invalid_code`.
2. Varredura TLV (`id` 2 dígitos + `len` 2 dígitos + `value`): `26` =
   Merchant Account Information → dentro dele, `00` = GUI (deve ser
   `br.gov.bcb.pix`), `01` = **chave Pix**, `25` = **URL dinâmica**. Campos
   `80`/`81` no nível raiz também indicam payload dinâmico.
3. **Pix dinâmico**: se existir `26.25` (ou `80`/`81`) → `pvs_dynamic_pix_not_supported`
   ("Pix dinâmico não é suportado no Pagamento via Saldo — use um Pix de chave
   (estático)").
4. **Chave**: campo `26.01` obrigatório; senão `pvs_invalid_code`. Valida o
   formato canônico (mesmas regras da Spec 31/47): 11 dígitos → `cpf`, 14 → 
   `cnpj`, `+55...` → `phone`, UUID → `evp`; desconhecido →
   `pvs_unsupported_key`.
5. **Valor**: campo `54` (opcional); parse BigDecimal (vírgula decimal), inválido
   → `pvs_invalid_code`.
6. **CRC-16**: campo `63` (CRC16-CCITT sobre o payload até o campo 63 exclusive);
   ausente/inválido → `pvs_invalid_code`. Nunca logar o `emv` (só a chave
   mascarada em erros — padrão `mask()` da Spec 47).

## D3 — Fase A nova: `createBillPaymentWithdrawal`

`WithdrawalProcessingPersister` ganha método irmão do `createMerchantWithdrawal`
(SEC-19 — tudo sob o lock do lojista):

```java
@Transactional
public MerchantWithdrawal createBillPaymentWithdrawal(UUID merchantId, Money amount,
        Money fee, String pixKey, BigDecimal dailyCap, BigDecimal maxPerPayment,
        boolean blockOnDebt)  // desvio do design (D3): o blockOnDebt é parâmetro explícito,
                              // como nos demais métodos da Fase A — o gate de inadimplência
                              // (Spec 33) é configurável (foxcash.billing.block-withdrawals-on-debt)
```

Sequência dentro da transação:
1. `merchantRepository.findByIdForUpdate(merchantId)` — **lock serializa PVS
   concorrentes** (o teto diário não tem corrida).
2. `markOutstandingDebt` + `ensureNoOutstandingDebt(blockOnDebt)` — **mesmos
   gates do saque** (ACTIVE/desbloqueio/inadimplência são validados no
   `Merchant.withdraw`, igual ao saque do lojista).
3. **Teto por operação**: `amount.add(fee) > maxPerPayment` →
   `PvsLimitExceededException("pvs_max_per_payment_exceeded")`.
4. **Teto diário**: `used = withdrawalRepository.sumAmountAndFeeByMerchantAndTypeCreatedSince(
   merchantId, BILL_PAYMENT, LocalDate.now().atStartOfDay())` (soma `amount + fee`
   de **todos** os BILL_PAYMENT criados hoje — tentativa contabiliza);
   `used.add(amount).add(fee) > dailyCap` → `PvsLimitExceededException(
   "pvs_daily_cap_exceeded")`. A consulta usa o índice novo `ix_withdrawals_merchant_type_created`
   (V71).
5. `merchant.withdraw(amount.add(fee))` + `merchantRepository.save` (saldo cobre
   valor + taxa — mesmo erro de saldo do saque).
6. `Withdrawal.processing(merchantId, amount, fee, null /*destination_id*/,
   pixKey /*snapshot*/, WithdrawalType.BILL_PAYMENT, null /*reason*/)` + save.
7. Ledger: `WITHDRAWAL_DEBIT` (amount) + `WITHDRAWAL_FEE_DEBIT` (fee) no wallet +
   `WITHDRAWAL_FEE` (fee) no master — **mesmos entries do saque** (Spec 88).
8. Retorna `MerchantWithdrawal(saved, walletBalance)`.

Exceção nova `PvsLimitExceededException` (RuntimeException, carrega o code de
erro) em `domain/exception/`.

## D4 — `PvsPaymentService` (novo)

`application/service/PvsPaymentService.java` implementando a porta nova
`domain/port/in/PvsPaymentUseCase`:

```java
public interface PvsPaymentUseCase {
    Result pay(PayCommand command);            // pixCode + amount?
    Limits limits(UUID merchantId);            // dailyCap/usedToday/remaining/maxPerPayment
    record PayCommand(UUID merchantId, String pixCode, Money amount) {}
    record Result(UUID withdrawalId, Money amount, Money fee, Money walletBalance, String status) {}
    record Limits(Money dailyCap, Money usedToday, Money remaining, Money maxPerPayment) {}
}
```

`pay(...)` espelha o `WithdrawalService.withdraw` (Spec 36/B-04):
1. **IDOR guard (SEC-13)**: se o principal autenticado é lojista e o
   `merchantId` do comando não é o dele → `MerchantAccessDeniedException`.
2. `parsed = PixCodeParser.parse(command.pixCode())` — **sem lock** (parse puro,
   antes da transação; falha → 400, nada é criado).
3. **Resolução do valor**: EMV com valor (54) → efetivo = valor do EMV; request
   com `amount` **deve bater** (`pvs_amount_mismatch`); EMV sem valor → `amount`
   do request é obrigatório (`pvs_amount_required`); efetivo > 0.
4. **Fase A**: `processingPersister.createBillPaymentWithdrawal(merchantId,
   valorEfetivo, Money.of(pricingProperties.getWithdrawalFee()), parsed.key(),
   pvsProperties.dailyCap(), pvsProperties.maxPerPayment(),
   billingProperties.isBlockWithdrawalsOnDebt())`.
5. **Fase B (FORA da transação)**: `payoutProvider.createPayout(new PayoutCommand(
   pending.withdrawal().id(), valorEfetivo,
   new PayoutDestination(parsed.key(), null, null, null, null), "pagamento via saldo"))`.
   - `RuntimeException` (falha síncrona) → `settlement.settleFailedByWithdrawalId(
     pending.withdrawal().id(), "Falha no PSP: ...")` + `throw` (mesmo fluxo do
     saque — estorno automático de valor + taxa).
6. `submissionPersister.submit(pending.withdrawal().id(), result.externalReference(),
   result.provider() != null ? result.provider().toUpperCase()
       : pspProperties.getPayoutProvider().toUpperCase())`.
7. `activityRecorder.record(PlatformActorType.MERCHANT, submitted.merchantId(),
   PlatformAction.BILL_PAYMENT_REQUESTED, "WITHDRAWAL", submitted.id(),
   submitted.merchantId(), Map.of("amount", submitted.amount().amount()))` —
   **valor sem PII; a chave nunca vai na trilha**.
8. Retorna `Result(submitted.id(), submitted.amount(), submitted.fee(),
   pending.walletBalance(), "PROCESSING")`.

`limits(...)`: `usedToday` = `withdrawalRepository.sumAmountAndFeeByMerchantAndTypeCreatedSince(...)`
(leitura simples, sem lock); remaining = dailyCap − usedToday (floor 0).

## D5 — Endpoints (portal)

`infrastructure/inbound/rest/PvsController.java` (`@RestController`, base
`/api/v1/portal/pvs`, `ROLE_MERCHANT_PORTAL`, prefixo de rate limit
`VARIABLE_PREFIXES` do `RateLimitKey` — padrão SEC-23/Spec 69):

- `POST /api/v1/portal/pvs/pay` — body `{ "pixCode": "000201...", "amount"?: 123.45 }`
  → 200 `{ withdrawalId, amount, fee, walletBalance, status: "PROCESSING" }`.
  - 400 `pvs_invalid_code` / `pvs_dynamic_pix_not_supported` /
    `pvs_unsupported_key` / `pvs_amount_required` / `pvs_amount_mismatch`
  - 409 `pvs_max_per_payment_exceeded` / `pvs_daily_cap_exceeded` / saldo
    (reuso do erro do saque)
  - `merchantId` vem do principal (`AuthenticatedMerchantResolver`), nunca do
    body — mesmo padrão do `WithdrawalService` (SEC-13).
- `GET /api/v1/portal/pvs/limits` → 200 `{ dailyCap, usedToday, remaining,
  maxPerPayment }` (decimais).
- Controller **fino**: valida shape mínimo do `pixCode` (não-nulo, ≤ 1024 chars)
  e delega o resto ao serviço.

## D6 — Migration V71 *(desvio de numeração: o design previa V68; V69/V70 já
aplicadas pelas Specs 122/124 → Flyway sem `out-of-order` não aplica versão
menor; o arquivo real é `V71__pvs.sql`)*

`src/main/resources/db/migration/V71__pvs.sql` (aditiva; V60–V67 reservadas
pelos Specs 97/98 e 101–119 — quando essas specs forem implementadas, os números
serão re-avaliados sobre a última real aplicada):

```sql
-- Spec 120: PVS (Pagamento via Saldo) — novo tipo de saída.
-- 1) A V21 criou withdrawals.type SEM CHECK (só o enum Java restringia).
--    Documenta os valores no banco (dados existentes: MERCHANT/ADMINISTRATIVE).
ALTER TABLE withdrawals
    ADD CONSTRAINT ck_withdrawals_type
    CHECK (type IN ('MERCHANT', 'ADMINISTRATIVE', 'BILL_PAYMENT'));

-- 2) Teto diário do PVS (Fase A sob lock): soma de amount+fee dos BILL_PAYMENT
--    criados no dia por lojista.
CREATE INDEX ix_withdrawals_merchant_type_created
    ON withdrawals(merchant_id, type, created_at);
```

## D7 — Config

`infrastructure/config/PvsProperties.java` (`@ConfigurationProperties(prefix =
"foxcash.pvs")`, padrão Spec 68/L16) + yml:

```yaml
foxcash:
  pvs:
    daily-cap: 5000.00        # teto diário por lojista (soma amount+fee, todas as tentativas)
    max-per-payment: 1000.00  # teto por operação (valor + taxa)
```

Fail-fast: `dailyCap > 0` e `maxPerPayment > 0` (validar no construtor — mesmo
padrão do `PspProperties.validate()`/Spec 68). Valores **operador-ajustáveis**
(env override); não são limites fixos de produto.

## D8 — Mudanças de domínio (lista finita)

| Arquivo | Mudança |
|---|---|
| `domain/model/withdrawal/WithdrawalType.java` | + `BILL_PAYMENT` (enum apenas) |
| `domain/port/out/WithdrawalRepository.java` | + `sumAmountAndFeeByMerchantAndTypeCreatedSince(...)` |
| `infrastructure/outbound/persistence/repository/WithdrawalJpaRepository.java` | + `@Query` da soma (usa índice V71) |
| `domain/model/activity/PlatformAction.java` | + `BILL_PAYMENT_REQUESTED` |
| `domain/pvs/PixCodeParser.java` | novo |
| `domain/exception/PvsLimitExceededException.java` | novo |
| `domain/port/in/PvsPaymentUseCase.java` | novo |
| `application/service/PvsPaymentService.java` | novo |
| `application/service/WithdrawalProcessingPersister.java` | + `createBillPaymentWithdrawal` |
| `infrastructure/config/PvsProperties.java` | novo |
| `infrastructure/inbound/rest/PvsController.java` | novo |
| `src/main/resources/application.yml` | + bloco `foxcash.pvs.*` |
| `src/main/resources/db/migration/V71__pvs.sql` | novo |

**Sem mudança:** `WithdrawalSettlementService`, `WithdrawalReconciliationService`,
`WithdrawalSubmissionPersister`, webhook `/webhooks/payouts`,
`QueryWithdrawalService`/`WithdrawalJpaRepository.findByMerchantIdOrderByCreatedAtDesc`
(histórico já inclui BILL_PAYMENT), adapters de payout, `Withdrawal`/entidade JPA.

## D9 — Testes

- **`PixCodeParserTest`** (tabela): CPF/CNPJ/phone/EVP estáticos (com e sem
  valor 54); CRC válido/inválido; `000202...`/lixo → invalid; `26.25` (URL) e
  `80` → dynamic; chave desconhecida; valor ausente.
- **`WithdrawalProcessingPersisterTest`** (novo método): débito valor + taxa,
  ledger (3 entries), snapshot = chave, `destination_id = null`, `type =
  BILL_PAYMENT`; **teto diário sob lock** (2 concorrentes → 1 passa, SEC-19);
  teto por operação; saldo insuficiente; ACTIVE/inadimplência (reuso).
- **`PvsPaymentServiceTest`** (mock do provider, padrão `WithdrawalServiceTest`):
  sucesso (destino montado com a chave do parser, `PayoutCommand` correto);
  mismatch/amount_required; **falha síncrona → estorno via settlement**; aceite →
  submit + trilha `BILL_PAYMENT_REQUESTED`; IDOR guard.
- **`PvsControllerTest`**: RBAC (ROLE_MERCHANT_PORTAL), shape do `pixCode`,
  mapeamento de erros 400/409, `limits` (used/remaining).
- **E2E** (`e2e-tests/`): PVS com mock payout → webhook PAID → COMPLETED no
  histórico; FAILED → estorno de valor + taxa.

## D10 — Risco e mitigação

- **Corrida no teto diário** → avaliação dentro da Fase A sob `findByIdForUpdate`
  (SEC-19): serializada, sem janela.
- **Chave inválida no PSP** (payout rejeitado depois de aceite) → fluxo normal
  de FAILED + estorno (reuso); nada de código novo.
- **Tentativa de contornar o parser** (chave digitada, brcode dinâmico) → o
  parser é a **única porta** de entrada (o endpoint só aceita `pixCode`); chave
  livre nunca chega ao serviço.
- **Enum `BILL_PAYMENT`** — `@Enumerated(EnumType.STRING)` + `length = 20`
  (12 chars): sem migração de dados.
