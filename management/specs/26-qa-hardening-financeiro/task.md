# Spec 26 — Task breakdown

Implementação concluída na sessão de auditoria QA (2026-08-04). Todos os itens
verificados: `mvn test` **175/0/0** e E2E Playwright **8/8**.

- [x] C1 — `@Lock(PESSIMISTIC_WRITE)` em merchant/transação; ports + adapters;
      consumo no webhook, saque e chargeback.
- [x] H1 — `Money` negativo; `Merchant.debit`; webhook `REFUNDED` debita;
      chargeback exige `APPROVED` e debita.
- [x] H2 — CI: health check com `X-Api-Key`; comentários atualizados.
- [x] H3 — `expirationSeconds` no `CreateChargeCommand` → `expires_in` Pagar.me;
      validação > 0.
- [x] M1/M6 — teto de valor no `CreatePixChargeService` (todos os fluxos).
- [x] M2 — timeouts (5s/10s) no adapter Pagar.me.
- [x] M4 — `MerchantChargeControllerTest` (8 testes).
- [x] M5 — `DataIntegrityViolationException` → 409 no cadastro.
- [x] L1 — chave admin em tempo constante.
- [x] L4 — gift `presetId`+`amount` → 400.
- [x] E2E — cenário 8 do portal (login, /portal/me, cobrança avulsa, gift,
      logout).
- [x] Merge/commit (`047fa62`) e `/spec-audit` — **10/10 critérios OK**
      (auditoria 2026-08-04).
