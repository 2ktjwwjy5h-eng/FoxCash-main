---
name: Auditoria de ações do master — trilha imutável de toda ação administrativa
status: audited
references:
  - "management/vision.md#V20"
  - "management/specs/14-withdrawals/requirements.md"
  - "management/specs/32-withdrawal-destination-flow/requirements.md"
  - "management/specs/34-master-ledger/requirements.md"
  - "management/specs/13-master-auth/requirements.md"
---

# Spec 37 — Auditoria de ações do master

## O que entrega

O master ganhou poderes financeiros: bloqueio/release de saques (Spec 14),
saque administrativo (Spec 32), baixa de receivable (Spec 34). **Nenhuma dessas
ações deixa rastro** — não há registro de quem fez o quê, quando e por quê.
Para compliance e responsabilidade (diagnóstico Fase 6, **C1**), toda ação
administrativa deve ser auditável.

Esta spec entrega a **trilha de auditoria do master**:

1. **Tabela `master_audit_log`** (migration V26): entrada **imutável** por ação
   administrativa — `actor_id` (master.id()), `action`, `merchant_id`
   (alvo, quando houver), `reference_type`/`reference_id`, `before`/`after`
   (JSON), `reason` (motivo obrigatório em ações que alteram dinheiro) e `ip`.
2. **Captura automática em todos os pontos de ação do master:**
   - `POST /merchants/{id}/withdrawals/block|release` (Spec 14);
   - `POST /merchants/{id}/withdrawals/administrative` (Spec 32);
   - `POST /receivables/{id}/write-off` (Spec 34);
   - `POST /merchants/{id}/invoices/{invoiceId}/collect` (manual, Spec 33);
   - ajuste de risco (Spec 19) e demais mutações administrativas.
3. **Before/after em JSON:** o auditor vê o estado anterior e posterior do
   objeto-alvo (ex.: `withdrawalsBlocked: false → true`, `reason: "..."`).
4. **Consultas:** `GET /api/v1/master/audit?merchantId=&action=&actorId=&from=&to=&page=&size=`
   — paginado e filtrável; sem rota de edição/exclusão (imutável).
5. **Integração com o registro de quem:** o principal do master
   (`master.id()`) é resolvido do `Authentication` (mesmo padrão do portal) —
   nunca confiado no body.

## Critérios de aceite

1. Migration V26: `master_audit_log` (colunas acima + índices
   `(merchant_id, created_at)`, `(actor_id, created_at)`, `(action, created_at)`).
2. Toda ação listada gera **exatamente uma** entrada na **mesma transação**
   (verificado: block → entrada com before/after; write-off → entrada com
   reason obrigatório).
3. Ações que alteram dinheiro (saque administrativo, write-off) exigem `reason`
   não vazio → 400 `reason_required`.
4. `GET /api/v1/master/audit` filtra por merchantId/action/actorId/período,
   paginado; sem edição/exclusão.
5. `mvn test` verde (204 + novos) e E2E sem regressão (**10/10** + cenário 18:
   block + write-off geram trilha consultável).

## Dependências

- Spec 13 (auth do master — quem é o actor).
- Spec 14, 32, 33, 34 (pontos de ação que passam a auditar).
- Spec 19 (ajuste de risco — também auditado).

## Observações

- **Imutável por design:** sem update/delete. Dados sensíveis (documento, chave
  Pix) entram mascarados no JSON, nunca em claro.
- **Retenção:** tabela de append-only; arquivamento é spec futura de operação
  (LGPD, C2).
