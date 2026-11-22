# Campanha de Mutation Testing (PITest) — registro e retomada

> Última atualização: 2026-08-16 (retomada round 13 — gatilho 1 disparado por
> mudanças de segurança ddf6905 + Specs 97/98/124; ver "Rodadas executadas").
> Este documento é o registro completo: o que foi feito, o estado final e as
> condições de retomada. Commits na `main`, ferramentas de análise em `target/`
> (não versionadas).

## Objetivo

Elevar a cobertura por **mutation testing** (PITest) das classes de produção:
cada mutante (comportamento alterado) deve ser morto por um teste; sobreviventes
são analisados um a um — matáveis → novo teste, equivalentes → prova + registro.

## Baseline e resultado

| Métrica | Baseline (fim R2, commit 7f9a3d8) | **Final (fim R12, commit dfe3d25)** | Δ |
|---|---|---|---|
| Killed | 1617 (80.3%) | **1908 (95%)** | +291 |
| Survived | 130 | 38 | −92 |
| NO_COVERAGE | 266 | 67 | −199 |
| Test strength | — | **98%** | — |
| Line coverage (mutadas) | — | **94%** | — |

(O console do PIT reporta 95% = 1915: ele conta 7 `TIMED_OUT` como mortos; o
script `summary-pit.js` separa TIMED_OUT em noCoverage.)

**Cobertura por triagem:** A) gravação financeira, B) financeiro-adjacente,
C) segurança/auth, D) domínio/validação — **todas a 0 survivors**. As categorias
remanescentes (E init/migração, long tail) foram avaliadas como **baixo valor**:
~24 survivors + ~63 NO_COVERAGE restantes são getters de domínio, query services
de delegação e 1–2 survivors por classe em ~45 classes — a maioria provável
equivalente.

## Rodadas executadas

| Round | Foco | Classes-alvo | Resultado | Commit |
|---|---|---|---|---|
| 2 | Sobreviventes | RiskMonitoringService, StoreButtonService, WebhookUrlValidator | 19 → 3 equivalentes (JDK 21 desembrulha IPv4-mapped — `isV4MappedPrefix`/`unwrapV4Mapped` são dead code) | `7f9a3d8` |
| 3 | NO_COVERAGE | CreatePixTransactionService, GlobalExceptionHandler, ApiKeyScope, MerchantWebhook | 93/93 | `a1d7868` |
| 4 | NO_COVERAGE | SensitiveDataLoggingFilter, MasterRecoveryCode, PspProperties, PlatformActivityRecorder | 73/73 | `5127ec9` |
| 5 | NO_COVERAGE | OpeningBalanceSeedRunner, ScheduledMessageAudience, Platform, StoreMessageEvent, DeliveryQueryService | 20/21 (1 equiv.: requireDelivery) | `984143c` |
| 6 | Sobreviventes | StoreButtonPayloadCodec, PlanService, ProcessPixWebhookService, PagarMePayoutProviderClient, RedisRateLimitStore | 129/132 (3 equiv.) | `d118ca6` |
| 7 | Núcleo financeiro | PixTransaction, CreatePixChargeService, WithdrawalReconciliationService, Merchant | 16 → 3 sobreviventes | `9a5bce6` |
| 8 | Infra/segurança + perfis | RateLimitFilter, WalletReconciliationService, ApiKeyManagementService, MasterProfileService | 4 classes → 0 sobreviventes (22/22, 16/16, 13/13, 12/14) | `2ce41e5` |
| 9 | Gravação financeira (triagem A) | WalletLedgerRecorder, MasterLedgerRecorder, RegisterChargebackService, PixChargePersistence, MasterRevenueService, ReceivableRecorder | 6 classes → 100% (4/4, 6/6, 10/10, 6/6, 4/4, 3/3) | `404c239` |
| 10 | Financeiro-adjacente (triagem B) | GiftPresetService, InvoiceCollectionService, MasterWithdrawalControlService, NotificationProcessor | 3 classes → 100% (17/17, 5/5, 14/14); Invoice 28/29 (L192 equiv.) | `c2fd29d` |
| 11 | Segurança/auth (triagem C) | 14 classes: 6 services (ApiKey/MasterAuth/MasterMfa/MerchantPortalAuth/PasswordReset/WebhookManagement), WebhookSignatureFilter, Master/MasterSession/MasterMfaMethod/MerchantCredential/MerchantSession/PasswordResetToken/MerchantApiKey | 14 classes → 0 survivors (233/233); + Totp 45/47 + InMemoryRateLimitStore 33/33 (2 equiv. Totp) | `614cbcb` |
| 12 | Domínio/validação (triagem D) | Plan, ScheduledMessage, StoreButton, DocumentValidator, MasterAuditRecorder, DeliveryAccessLog, DeliveryActionExec, PlanDelivery | 8 classes → 211/212 (99%), 0 NO_COVERAGE (1 equiv.: StoreButton L98) | `fce3770` |
| 13 | Retomada — gatilho 1 (ddf6905 + Specs 97/98/124) | 11 classes afetadas: 3 security filters (ApiKeyAuthFilter, RateLimitFilter, AuthenticatedRateLimitFilter) + 8 services (CreatePixCharge, CreatePixTransaction, HoneypotAlertPublisher, InvoiceCollection, MerchantConfig, MerchantPanelQuery, WithdrawalReconciliation, Withdrawal) | 373 mutações → **348 killed (93%)**, 6 survivors — TODOS equivalentes provados, 19 NO_COVERAGE, test strength 98% | (working tree — 20 testes novos/extendidos) |

**Achado principal do round 3:** `CreatePixTransactionService` (rota legada
`/api/v1/transactions`, viva via `TransactionController`) estava **100% sem
teste** — o maior buraco real do projeto.

## Estado final (fim do round 12 — campanha encerrada)

Estratégia desde o round 9: **triagem dos sobreviventes por risco** (não mais por
classe) — A) gravação financeira (CONCLUÍDA), B) financeiro-adjacente (CONCLUÍDA),
C) segurança/auth (CONCLUÍDA), D) domínio/validação (CONCLUÍDA),
E) init/migração (NÃO EXECUTADA — avaliada como baixo valor), F) equivalentes
provados (remover da fila).

- **Categorias A–D encerradas:** todo o código que move dinheiro (gravação
  financeira, chargeback, receivables, withdrawals) e autentica (master, MFA,
  portal lojista, API keys, rate limit) está a **0 survivors**.
- **Sobreviventes restantes (~38):** long tail — 1–2 por classe em ~45 classes
  (Chargeback, MerchantRiskSnapshot, Withdrawal, Receivable, MerchantDashboardService…),
  muitos provavelmente equivalentes; não investigados por baixo valor.
- **NO_COVERAGE remanescente (~67):** getters de domínio (baixo valor), query
  services/persisters de delegação, e o dead code do WebhookUrlValidator
  (não vale teste — ver round 2).
- **Equivalentes provados (não reabrir):** WebhookUrlValidator L106/120/122/135,
  DeliveryQueryService L82, PlanService L169, RedisRateLimitStore L72/L73,
  CreatePixChargeService L187, WithdrawalReconciliationService L125/L130,
  InvoiceCollectionService L192 (break de saldo zero redundante — o guard
  `debit <= 0 → continue` L196 cobre o caso), Totp L117 (`bits >= 5` — quando
  bits==5 o laço e o trailing `if (bits > 0)` emitem o MESMO caractere
  `(buffer >>> 0) & 0x1F` == `(buffer << 0) & 0x1F`), Totp L89 (`+ window` —
  a janela varre {-1, 0, +1}; subtrair window produz o MESMO conjunto de steps,
  só que em outra ordem — `verify` é agnóstico à ordem),
  StoreButton L98 (`action.name().length() > 30` — o enum tem 6 valores, o maior
  nome tem 7 chars "PREVIEW"; o boundary 30 é inalcançável — `>=` e `>` idênticos),
  CreatePixChargeService L111 (BooleanTrue no `filter(id==merchantId)` — o guard
  SEC-13 L107 já lança 403 para lojista alheio; quando `current` está presente o
  id SEMPRE casa → filter sempre true), CreatePixChargeService L253 (boundary
  `signum() <= 0` — mascarado por `PixTransaction.create` L146-147, que lança IAE
  "amount must be positive" antes de qualquer efeito PSP/save),
  CreatePixTransactionService L88 (BooleanTrue no `filter` — mesmo padrão do
  CreatePixCharge L111, guard L84), InvoiceCollectionService L219 (boundary
  `walletBalance <= 0` no `collectReceivables` — o guard `debit <= 0 → continue`
  L223 cobre o caso zero; sem efeito observável — mesmo padrão do equivalente
  L192), WithdrawalReconciliationService L136 (`idx > 0` → `>= 0` em
  `snapshot.substring(0, idx)` — em idx==0 ambos os ramos retornam `""`;
  `substring(0,0)` == `""`), WithdrawalReconciliationService L141
  (`parts.length > 0` → `>= 0` — `String.split` nunca retorna array vazio
  (sempre ≥ 1) → `> 0` é sempre true).

## Condições de retomada

A campanha está **encerrada** — o estado (95% killed, 98% test strength) é
saudável e o esforço restante (categoria E + long tail) não compensa. **Retomar
apenas se:** (1) houver mudança de código em classes das categorias A–D
(financeiro/auth) — rodar o PIT escopado nas classes alteradas e matar novos
survivors; ou (2) surgir um survivor novo no PIT global de uma classe crítica
(rodar `mvn ... mutationCoverage -DoutputFormats=XML` e analisar com
`target/summary-pit.js`). Não reabrir equivalentes provados acima.

## Ferramentas (em `target/`, não versionadas)

| Script | Uso |
|---|---|
| `target/summary-pit.js` | Resumo global do `target/pit-reports/mutations.xml` (killed/survived/noCoverage por classe) |
| `target/no-coverage.js` | Lista NO_COVERAGE por classe/linha/mutador |
| `target/survivors-r6.js` | Lista SURVIVED das classes-alvo (editar a lista `targets` no topo) |
| `target/pit-status.js` | Status por classe do relatório atual |
| `target/extract-survivors.js` | (round 2) extração de sobreviventes |

## Comandos

```bash
# PIT escopado (round) — substituir targetClasses/targetTests
mvn test-compile org.pitest:pitest-maven:mutationCoverage \
  -DtargetClasses="com.foxcash.application.service.PixTransaction,..." \
  -DtargetTests="...PixTransactionTest,..." \
  -DoutputFormats=XML -DtimestampedReports=false

# PIT global (~6 min)
mvn test-compile org.pitest:pitest-maven:mutationCoverage -DoutputFormats=XML -DtimestampedReports=false

# Análise do relatório
node target/summary-pit.js && node target/no-coverage.js

# Suíte completa
mvn test
```

> Nota: rodar o PIT global em **foreground** (o background task do DsCode pode
> colidir ID com comandos idênticos já executados — variar o pipe final se for
> usar background).

## Rodadas executadas (resumo — detalhe na tabela acima)

1. **Round 10 — financeiro-adjacente: CONCLUÍDO** (commit `c2fd29d`): 3 classes a
   100% (GiftPresetService 17/17, NotificationProcessor 14/14,
   MasterWithdrawalControlService 5/5); InvoiceCollectionService 28/29 (L192
   provado equivalente). PIT global 90.8% → 91.2% (1836/2013). Triagem:
   categorias A e B encerradas.
2. **Round 11 — segurança/auth (categoria C): CONCLUÍDO** (commit `614cbcb`): 14
   classes → 0 survivors (233/233, 0 NO_COVERAGE); 7 testes novos/atualizados
   (3 survivors mortos: Master.username, MerchantApiKey.id, MerchantSession.merchantId;
   +32 NO_COVERAGE de getters/isValid/behaviors). Bônus: InMemoryRateLimitStore
   ganhou `Clock` injetado (padrão do projeto — `ApplicationConfig.clock()`) +
   2 testes de boundary exato (L36/L40 `>=`→`>` mortos, 33/33); Totp 45/47
   (L117 e L89 provados equivalentes). Triagem: categoria C encerrada.
3. **Round 12 — domínio/validação (categoria D): CONCLUÍDO** (commit `fce3770`):
   8 classes → 211/212 (99%), 0 NO_COVERAGE; 19 testes novos/atualizados
   (7 survivors mortos: Plan.deactivate touch, Plan.featured, ScheduledMessage
   cron 100, StoreButton moveTo(0), DocumentValidator rem==2, DeliveryActionExec
   seq, MasterAuditRecorder record/mask; +2 testes classes novos Delivery*).
   Equivalente provado: StoreButton L98 (enum max 7 chars < 30). Triagem:
   categoria D encerrada.
4. **Fechamento (2026-08-07):** PIT global final = **95% killed (1908/2013),
   38 survivors, 67 NO_COVERAGE, test strength 98%**. Categorias E e long tail
   avaliadas como baixo valor → **campanha encerrada** (ver "Condições de retomada").
5. **Round 13 — retomada (gatilho 1: ddf6905 + Specs 97/98/124): CONCLUÍDO.**
   Mudanças de segurança (ddf6905: SEC-13/14/16, api_blocked Spec 98, throttle
   Spec 65) e Specs 97/98/124 (perfis de operação, config fina, painel, honeypot)
   criaram survivors NOVOS em 11 classes (categorias A–D). PIT escopado: 373
   mutações → 312 killed (84%) ANTES → **348 killed (93%)** DEPOIS de 20 testes
   novos/extendidos; **6 survivors remanescentes — TODOS equivalentes provados**
   (ver lista acima); 19 NO_COVERAGE; test strength 98%. Destaques:
   - **ApiKeyAuthFilter** (6 → 0): poda do throttle testada via reflexão no mapa
     (10k entradas — L210/L211/L218×2/L220) + 3-request throttle (L205) + atributo
     do fallback legado (L124).
   - **RateLimitFilter/AuthenticatedRateLimitFilter** (8 → 0): alerta de rajada
     (Spec 64), janela transacional no tryAcquire (L151), override de rate limit
     é EXCLUSIVO do ROLE_MERCHANT (L127 — fail-closed preservado).
   - **CreatePixCharge/CreatePixTransaction** (8 → 3 equiv.): perfis de operação
     (Spec 124) — header > bot > default gravados na transação persistida.
   - **MerchantConfigService** (8 → 0): estado salvo + payloads de auditoria
     (honeypot/status/walletBalance/configMap) capturados.
   - **HoneypotAlertPublisher** L54: REQUIRES_NEW provado capturando a
     TransactionDefinition (fail-safe do caminho trap).
   - **InvoiceCollectionService** L146: re-leitura fresca (SEC-14) com fatura já
     quitada → noop (sem re-registro de receita/ledger/webhook).
   - **MerchantPanelQueryService** L183/L237: boundary LOW exato (2.00 == 2.0) +
     taxa efetiva exibida no painel.
   Estado pós-round: 0 survivors MATÁVEIS nas classes afetadas; categoria C
   (segurança) de volta a 0 survivors em produção.

## Lições reutilizáveis (detalhe em `lessons.md` L12)

- Scripts node: usar **regex literal**, nunca `new RegExp(string)` — `\s` em
  string JS vira `s` (quebra o parse silenciosamente).
- Mutantes de `log.*` morrem com `ListAppender` do logback (set DEBUG/WARN +
  assert na contagem de mensagens) — ver `SensitiveDataLoggingFilterTest`,
  `OpeningBalanceSeedRunnerTest`, `RedisRateLimitStoreTest`.
- Boundaries: testar o valor **exatamente no limite** (0.00, == max, 1000 chars,
  10 itens, label 80) para matar `ConditionalsBoundary`.
- Mutante mascarado por validação duplicada no domínio = equivalente (provar
  lendo a classe de domínio).
- Time-dependent (rate limit/expiração): injetar `Clock` (padrão do projeto) e
  avançar **exatamente** `windowMs` para matar `ConditionalsBoundary` de
  `>=`/`>` — ver `InMemoryRateLimitStoreTest.cleanupRemovesWindowExactlyAtBoundary`.
- Equivalente por simetria: loop de janela {-1, 0, +1} com `+ window` → `- window`
  produz o MESMO conjunto de steps (Totp L89); laço `bits >= 5` cujo trailing
  `if (bits > 0)` emite o mesmo caractere (Totp L117).
