# Spec 34 — Task breakdown

Status: `implemented` (2026-08-04). Código implementado e validado
(284 testes unitários, E2E 15/15) — aguardando `/spec-audit` para `audited`.

## Implementação

- [x] Migration `V23__master_ledger.sql`: tabela `master_ledger` (colunas,
      índices `ix_master_ledger_merchant_created`/`ix_master_ledger_ref`,
      unique `uq_master_ledger_ref` **incluindo `entry_type`**:
      `(merchant_id, entry_type, reference_type, reference_id)` — fatura paga
      gera `TRANSACTION_FEE` + `MONTHLY_FEE` com a mesma referência).
      Aplicada no boot (Flyway schema v26).
- [x] `MasterLedgerEntryType` (TRANSACTION_FEE/MONTHLY_FEE/RECEIVABLE_RECOVERY/
      ADMIN_RECOVERY/CHARGEBACK_LOSS) + `MasterLedgerEntry` (record imutável).
- [x] `MasterLedgerRepository` (port out: save, findByMerchantId paginado,
      sumByMerchantIdAndTypeBetween, findLatestByMerchantId) + adapter JPA.
- [x] `MasterLedgerRecorder` (application): grava entry com `balance_after`
      por lojista (chamado dentro do `@Transactional` dos fluxos origem).
- [x] Integrar recorder: fatura paga (33 → TRANSACTION_FEE+MONTHLY_FEE),
      receivable pago (33 → RECEIVABLE_RECOVERY), saque administrativo
      (32 → ADMIN_RECOVERY), write-off (34 → CHARGEBACK_LOSS).
- [x] `ReceivableWriteOffService`: `POST /receivables/{id}/write-off`
      (ROLE_MASTER, `reason` obrigatório → 400 `reason_required`; receivable →
      PAID por baixa; entry CHARGEBACK_LOSS; auditoria via Spec 37).
- [x] `MasterRevenueUseCase` + `MasterRevenueService`: summary (SUM por tipo +
      COALESCE; net = entradas − perdas) e ledger paginado.
- [x] `MasterRevenueController` (ROLE_MASTER): `GET /revenue/summary`,
      `GET /revenue/ledger`, `POST /receivables/{id}/write-off` + DTOs.

## Verificação

- [x] `MasterLedgerRecorderTest`: cada origem grava 1 entry com `balance_after`
      correto; unique por origem (replay não duplica).
- [x] `MasterRevenueServiceTest`: summary por período = Σ entries; net
      entradas − perdas; ledger paginado com filtros; 400 em inválido.
- [x] `ReceivableWriteOffServiceTest`: write-off → CHARGEBACK_LOSS + receivable
      PAID; sem reason → 400; já pago → 409; auditado.
- [x] E2E: cenário 18 (write-off → CHARGEBACK_LOSS + receita no summary;
      ledger filtrado por tipo) — suite **15/15** sem regressão.
- [x] `mvn test` verde (**284 testes**, 0 falhas).
- [x] Roadmap `verified` → `implemented`; requirements/design/task atualizados
      com contagens reais.
- [ ] `/spec-audit` → `audited` (fim do ciclo SDD).
