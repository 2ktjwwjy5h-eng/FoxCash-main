---
name: Dashboard master — transações, ganhos e taxas (leitura + execução)
status: audited
references:
  - "management/vision.md#V13"
  - "management/specs/13-master-auth/requirements.md"
  - "management/specs/14-withdrawals/requirements.md"
  - "management/specs/8-admin-dashboard/requirements.md"
---

# Spec 15 — Dashboard master: transações, ganhos e taxas

## O que entrega

**Dashboard do master** sob `/api/v1/master/**` (role `ROLE_MASTER`, Spec 13) —
leitura dos dados financeiros e **execução** dos controles de saque (Spec 14):

1. **Transações:** `GET /api/v1/master/transactions` — lista paginada com
   filtros `merchantId`/`status`/`from`/`to` (reusa o domínio da Spec 8,
   exposto ao master).
2. **Ganhos por lojista:** `GET /api/v1/master/merchants/{merchantId}/earnings`
   — saldo disponível (`wallet_balance`), volume aprovado (`SUM` de
   `APPROVED`), total de saques realizados e status do bloqueio de saque.
3. **Taxas por lojista/período:** `GET /api/v1/master/merchants/{merchantId}/
   fees?month=yyyy-MM` — breakdown das tarifas pagas (0,50/transação +
   69,95/mês), derivado das faturas (Spec 8/domínio de billing).
4. **Execução:** os controles de bloqueio/liberação de saques (Spec 14) são os
   únicos endpoints de escrita do master — leitura **e** ação num único perfil.
5. **Consulta de saques:** `GET /api/v1/master/withdrawals` (já da Spec 14).

## Critérios de aceite

1. `GET /api/v1/master/transactions` retorna transações paginadas com filtros
   `merchantId`/`status`/`from`/`to`; parâmetros inválidos → `400
   invalid_argument`; exige `ROLE_MASTER`.
2. `GET /api/v1/master/merchants/{merchantId}/earnings` retorna
   `walletBalance`, `approvedVolume`, `totalWithdrawn`, `withdrawalsBlocked`;
   lojista inexistente → `404 merchant_not_found`.
3. `GET /api/v1/master/merchants/{merchantId}/fees?month=yyyy-MM` retorna
   `transactionCount`, `transactionFees`, `monthlyFee`, `totalFees` coerentes
   com as faturas do período; mês inválido → `400 invalid_argument`.
4. Todos os endpoints exigem `ROLE_MASTER` (401 sem token, 403 com lojista/
   admin); `mvn test` verde (**~70 testes: 64 + ~6 novos** — serviço de
   dashboard master) e E2E **5/5**.

## Dependências

- Spec 13 (role `ROLE_MASTER`, filtro Bearer).
- Spec 14 (saques: `totalWithdrawn`, flag de bloqueio, `GET /master/withdrawals`).
- Spec 8 (agregações `SUM`/`COALESCE`, paginação, `PageResult`).

## Observações

- **Somente leitura** exceto os controles de saque (Spec 14) — nenhuma escrita
  adicional no domínio financeiro.
- **Reuso:** a lógica de agregação da Spec 8 (`AdminDashboardPersistenceAdapter`,
  `SUM`/`COALESCE`, Specifications) é reaproveitada, não duplicada.
- **Página/limite:** mesmo padrão da Spec 8 (default 0/20, max 100).
- **Autorização de posse não se aplica** (master enxerga todos os lojistas) —
  é o operador da plataforma.
