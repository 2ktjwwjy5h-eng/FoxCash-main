# Spec 37 — Design: Auditoria de ações do master

## Contexto

O master bloqueia saques, recolhe valores e baixa dívidas — tudo sem rastro.
Esta spec adiciona `master_audit_log` (append-only) capturado na **mesma
transação** de cada ação administrativa, com `actor_id` vindo do principal
(`master.id()`, padrão do `MasterAuthFilter`), `before`/`after` em JSON e
`reason` obrigatório para ações que alteram dinheiro.

## Decisões de arquitetura

### Migration V26 — `master_audit_log`

```sql
CREATE TABLE master_audit_log (
    id             UUID PRIMARY KEY,
    actor_id       UUID NOT NULL REFERENCES masters(id),   -- quem (master.id())
    action         VARCHAR(50) NOT NULL,                   -- WITHDRAWALS_BLOCK | WITHDRAWALS_RELEASE |
                                                           -- ADMINISTRATIVE_WITHDRAWAL | RECEIVABLE_WRITE_OFF |
                                                           -- INVOICE_COLLECT | RISK_ADJUSTMENT
    merchant_id    UUID REFERENCES merchants(id),          -- alvo (quando houver)
    reference_type VARCHAR(20),                            -- WITHDRAWAL | RECEIVABLE | INVOICE | MERCHANT
    reference_id   UUID,
    before_json    TEXT,                                   -- estado anterior (mascarado)
    after_json     TEXT,                                   -- estado posterior (mascarado)
    reason         VARCHAR(255),                           -- obrigatório p/ ações financeiras
    ip             VARCHAR(45),
    created_at     TIMESTAMP NOT NULL
);
CREATE INDEX ix_audit_merchant_created ON master_audit_log(merchant_id, created_at);
CREATE INDEX ix_audit_actor_created   ON master_audit_log(actor_id, created_at);
CREATE INDEX ix_audit_action_created  ON master_audit_log(action, created_at);
```

- **Append-only:** nenhuma operação de update/delete; o repositório só expõe
  `save` e `search`.
- **Máscara:** antes de serializar, campos sensíveis (documento, chave Pix,
  conta) passam pelo mesmo `WithdrawalDestinationMasker`/`DocumentMasker` —
  nunca em claro no log.

### Domínio e porta

```java
// domain/model/audit/MasterAuditAction.java -> enum dos actions
// domain/model/audit/MasterAuditEntry.java (record imutável:
//   id, actorId, action, merchantId, referenceType, referenceId,
//   beforeJson, afterJson, reason, ip, createdAt)

// domain/port/out/MasterAuditRepository.java
void save(MasterAuditEntry entry);
Page<MasterAuditEntry> search(AuditSearchCriteria criteria); // merchantId, action, actorId, from, to
```

### `MasterAuditRecorder` (application) — grava na mesma transação

```java
// application/service/MasterAuditRecorder.java
public void record(UUID actorId, MasterAuditAction action, UUID merchantId,
                   String referenceType, UUID referenceId,
                   Object before, Object after, String reason, String ip) {
    auditRepository.save(MasterAuditEntry.create(
        actorId, action, merchantId, referenceType, referenceId,
        maskAndSerialize(before), maskAndSerialize(after), reason, ip, now()));
}
```

- `actorId` vem de `Authentication.getPrincipal()` (UUID do master) — nunca do
  body (mesmo padrão `MasterAuthFilter`/`PortalCredentialsController`).
- `ip` resolvido do `HttpServletRequest` (util `ClientIpResolver`).

### Pontos de captura (integração nos services existentes)

| Ação | Ponto de integração | before/after |
|---|---|---|
| `WITHDRAWALS_BLOCK`/`WITHDRAWALS_RELEASE` | `MerchantWithdrawalBlockService` (Spec 14) | `withdrawalsBlocked` |
| `ADMINISTRATIVE_WITHDRAWAL` | `MasterWithdrawalService` (Spec 32) | saldo antes/depois + amount (reason obrigatório) |
| `RECEIVABLE_WRITE_OFF` | `ReceivableWriteOffService` (Spec 34) | receivable OPEN/PARTIAL → PAID (reason obrigatório) |
| `INVOICE_COLLECT` | `ReceivableController.collect` manual (Spec 33) | fatura + débito |
| `RISK_ADJUSTMENT` | ajuste de risco (Spec 19) | score antes/depois |

### REST (master)

`GET /api/v1/master/audit?merchantId=&action=&actorId=&from=&to=&page=&size=`
→ `PageResponse<{id, actorId, action, merchantId, referenceType, referenceId,
before, after, reason, ip, createdAt}>`. Sem POST/PATCH/DELETE (imutável).

## Arquivos

- Migration `V26__master_audit_log.sql`
- `domain/model/audit/MasterAuditAction.java`, `MasterAuditEntry.java`,
  `domain/port/out/MasterAuditRepository.java` + adapter JPA + entity
- `application/service/MasterAuditRecorder.java`
- `infrastructure/inbound/rest/MasterAuditController.java` + DTO
- `infrastructure/security` — resolver de IP (util)
- Alterados: `MerchantWithdrawalBlockService` (14), `MasterWithdrawalService`
  (32), `ReceivableWriteOffService` (34), `ReceivableController.collect` (33),
  ajuste de risco (19) — chamam `auditRecorder.record(...)`
- Testes: `MasterAuditRecorderTest` (before/after mascarado, reason obrigatório),
  `MasterAuditControllerTest` (filtros/paginação, sem mutação)
- E2E: cenário 18 (block + write-off geram trilha consultável)

## Fora de escopo

- Arquivamento/retention policy (LGPD) — spec futura (C2).
- Auditoria do portal do lojista (ações do vendedor) — Spec 31 já isola por
  lojista; trilha do vendedor é spec futura.
- Frontend de visualização da trilha (fase futura de frontend).
