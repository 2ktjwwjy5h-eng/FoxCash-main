# Spec 37 — Task breakdown

Status: `implemented` (2026-08-04). Código implementado e validado
(284 testes unitários, E2E 15/15) — aguardando `/spec-audit` para `audited`.

## Implementação

- [x] Migration `V26__master_audit_log.sql`: tabela `master_audit_log`
      (append-only) + índices `(merchant_id, created_at)`, `(actor_id,
      created_at)`, `(action, created_at)`. Aplicada no boot (Flyway schema v26).
- [x] `MasterAuditAction` (enum: WITHDRAWALS_BLOCK, WITHDRAWALS_RELEASE,
      ADMINISTRATIVE_WITHDRAWAL, RECEIVABLE_WRITE_OFF, INVOICE_COLLECT,
      RISK_ADJUSTMENT — este último **reservado** p/ Spec 19) + `MasterAuditEntry`
      (record imutável) + `MasterAuditJpaEntity` + mapper.
- [x] `MasterAuditRepository` (port out: `save`, `search(criteria)` paginado —
      sem update/delete) + adapter JPA.
- [x] `MasterAuditRecorder` (application): `record(actorId, action, merchantId,
      referenceType, referenceId, before, after, reason, ip)` — serializa com
      máscara de dados sensíveis; grava na mesma transação.
- [x] `ClientIpResolver` (util) — extrai IP do `HttpServletRequest`.
- [x] `MasterAuditController` (`GET /api/v1/master/audit`, ROLE_MASTER):
      filtros merchantId/action/actorId/from/to + paginação; **sem** rotas de
      mutação (POST → 405 `method_not_allowed`).
- [x] Integrar `MasterAuditRecorder` nos pontos de ação (chamada no
      `@Transactional` existente, `actorId` do principal):
      - `MerchantWithdrawalBlockService` (Spec 14) — block/release;
      - `MasterWithdrawalService` (Spec 32) — saque administrativo
        (reason obrigatório → 400 `reason_required`);
      - `ReceivableWriteOffService` (Spec 34) — write-off (reason obrigatório);
      - `ReceivableController.collect` manual (Spec 33) — `INVOICE_COLLECT`;
      - ajuste de risco (Spec 19) — reservado no enum, sem fluxo ativo.
- [x] Validação: ações financeiras sem `reason` → 400 `reason_required`
      (`AuditReasonRequiredException` + handler).
- [x] DTOs: `AuditEntryResponse`, `PageResponse` (padrão existente).

## Verificação

- [x] `MasterAuditRecorderTest`: grava 1 entrada com before/after mascarados
      (documento/chave nunca em claro); reason obrigatório → 400.
- [x] `MasterAuditControllerTest`: filtros por merchantId/action/actorId/período;
      paginação; POST/PATCH/DELETE → 405 (imutável); 401/403 de auth.
- [x] Integração por ponto: block → entrada `WITHDRAWALS_BLOCK` com
      `before/after.withdrawalsBlocked`; write-off → `RECEIVABLE_WRITE_OFF` +
      receivable PAID; saque administrativo → `ADMINISTRATIVE_WITHDRAWAL`
      (reason obrigatório) — tudo na mesma transação (rollback da ação →
      rollback da trilha).
- [x] E2E: cenário 18 (block + write-off geram trilha consultável; POST /audit
      → 405; 401/403) — suite **15/15** sem regressão.
- [x] `mvn test` verde (**284 testes**, 0 falhas).
- [x] Roadmap `verified` → `implemented`; requirements/design/task atualizados
      com contagens reais.
- [ ] `/spec-audit` → `audited` (fim do ciclo SDD).
