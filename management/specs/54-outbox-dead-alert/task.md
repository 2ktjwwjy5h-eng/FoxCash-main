# Spec 54 — Task breakdown

Status: `audited` (2026-08-07). `mvn test` **1078/0/0** · E2E outbox-flow **1/1**
· Commit `a3ee64f`. Feature LIVE.

## Implementação (TDD — o teste veio primeiro, commit `c6d38dd`)

- [x] **Teste primeiro (E2E, commit `c6d38dd`):** `outbox-flow.spec.js` com os 5
      fluxos do outbox — o cenário 5 documentava a LACUNA (FAILED sem alerta) e
      definiu o contrato (FAILED, não reprocessado, auditável).
- [x] `WebhookEventType.OUTBOX_DEAD` (plataforma, sem merchant).
- [x] `NotificationProcessor`: injeta `WebhookEventPublisher`; no branch de
      esgotamento (`attempts >= max`) publica `OUTBOX_DEAD` na MESMA transação
      com `{eventId, originalType, merchantId, error, attempts}` (HashMap — o
      merchantId pode ser null); **anti-cascata** (evento morto do tipo
      `OUTBOX_DEAD` não republica); falha ao publicar → WARN (não mascara o
      erro original).
- [x] Testes unitários `NotificationProcessorTest` (+3):
      `failureAtMaxPublishesDeadLetterWithEventDetails` (payload completo),
      `failureWithRetryLeftDoesNotPublishDeadLetter` (retry → sem alerta),
      `deadLetterThatDiesDoesNotPublishAnotherDeadLetter` (anti-cascata).
- [x] E2E `outbox-flow.spec.js` cenário 5 estendido (após o FAILED): verifica o
      `OUTBOX_DEAD` PENDING/SENT com `data.eventId` = evento morto,
      `originalType=SALE_PAID`, `merchantId`, `error` (500), `attempts=5` e
      `merchantId` do envelope NULL (plataforma).

## Verificação

- [x] `mvn test` **1078/0/0** (1075 → 1078: +3 novos no NotificationProcessorTest).
- [x] E2E `outbox-flow.spec.js` **1/1 (2.8m)** — os 5 cenários, com o cenário 5
      validando o alerta de ponta a ponta.
- [x] Commit `a3ee64f` pushado (CI verde aguardado pelo run completo).

## Auditoria (ciclo SDD — `/spec-audit`, 2026-08-07)

**Critérios 5/5 OK** — cobertos por unit (`NotificationProcessorTest` +3) e E2E
(`outbox-flow.spec.js` cenário 5).

- **Esgotamento publica OUTBOX_DEAD na mesma transação** (payload correto): ✓
  unit + E2E.
- **Retry disponível → sem alerta**: ✓ `failureWithRetryLeftDoesNotPublishDeadLetter`.
- **Anti-cascata**: ✓ `deadLetterThatDiesDoesNotPublishAnotherDeadLetter`.
- **Plataforma (merchantId null) → operador**: ✓ E2E (`deadEnvelope.merchantId`
  null) + `HttpMerchantNotifierTest` (entrega ao operador, Spec 53).
- **Fail-open sem operador**: ✓ a entrega cai no fallback log
  (`HttpMerchantNotifierTest.platformEventWithoutOperatorWebhookFallsBackToLog`).

**Aceitos por design:**
- **Sem DLQ física** (decisão documentada no design): o `FAILED` fica na própria
  tabela como trilha; o alerta é o mecanismo. DLQ de consulta/re-enfileiramento
  manual ficam como evolução futura.

**Fim do ciclo SDD:** roadmap `audited` (feature LIVE).
