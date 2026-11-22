---
name: Saques — fluxo automático + bloqueio/liberação por lojista (master)
status: audited
references:
  - "management/vision.md#V12"
  - "management/specs/13-master-auth/requirements.md"
  - "management/specs/12-api-authentication/requirements.md"
  - "management/specs/8-admin-dashboard/requirements.md"
---

# Spec 14 — Saques: fluxo automático + controle master

## O que entrega

Fluxo de **saques** sobre o wallet existente, **automático** (sem aprovação
intermediária por saque), porém **controlável pelo master por lojista**:

1. **Saque automático:** `POST /api/v1/merchants/{merchantId}/withdrawals`
   `{amount}` (autenticado com chave de lojista, posse via Spec futura — nesta
   spec o masterId no principal da chave) debita o saldo do lojista
   (`wallet_balance`) e cria a saída na tabela `withdrawals` (migration **V8**,
   status `COMPLETED`). Lojista **sem saldo suficiente** → `409
   insufficient_balance`; lojista **não-ACTIVE** → `409 merchant_not_active`.
2. **Controle master:** flag `merchants.withdrawals_blocked` (migration V8,
   default `false`). `POST /api/v1/master/merchants/{merchantId}/withdrawals/
   block` e `.../release` (role `ROLE_MASTER`, Spec 13) → `204`. Enquanto
   **bloqueado**, a tentativa de saque do lojista responde `409
   withdrawals_blocked`; ao **liberar**, o fluxo automático volta a funcionar.
   O bloqueio impede **novos** saques — não estorna os já efetivados.
3. **Consulta:** lojista vê os próprios saques (`GET /api/v1/merchants/
   {merchantId}/withdrawals`, chave do lojista); master lista todos
   (`GET /api/v1/master/withdrawals`, filtros `merchantId`/`status`/`from`/`to`).
4. **Domínio:** `Withdrawal` (id, merchantId, `Money amount`, status
   `COMPLETED`/`REJECTED`, `createdAt`, `completedAt`/`rejectedAt`),
   `Merchant.withdraw(Money)` (valida ACTIVE + bloqueio + saldo), 
   `Merchant.blockWithdrawals()`/`releaseWithdrawals()`.

## Critérios de aceite

1. Migration V8 cria `withdrawals` (FK merchant, amount, status, timestamps) e
   adiciona `merchants.withdrawals_blocked BOOLEAN NOT NULL DEFAULT FALSE`.
2. `POST /api/v1/merchants/{id}/withdrawals` com saldo suficiente → `201` com a
   saída `COMPLETED` e `wallet_balance` reduzido em `amount`; saldo insuficiente
   → `409 insufficient_balance`; lojista não-ACTIVE → `409 merchant_not_active`.
3. Com `withdrawals_blocked=true`: a tentativa de saque → `409
   withdrawals_blocked` (o saldo **não** é debitado). Com `false` (default) o
   saque flui normalmente.
4. `POST /api/v1/master/merchants/{id}/withdrawals/block` e `/release` (role
   `ROLE_MASTER`) → `204` e alteram a flag; endpoints respondem `401` sem token
   master e `403` com token de lojista/admin.
5. `GET /api/v1/master/withdrawals` lista com filtros `merchantId`/`status`/
   `from`/`to` (parâmetros inválidos → `400 invalid_argument`).
6. `mvn test` verde (**~64 testes: 58 + ~6 novos** — `Withdrawal`/serviço de
   saque) e E2E **5/5** sem regressão (jornada existente inalterada; sem E2E
   novo de saque nesta spec).

## Dependências

- Spec 13 (role `ROLE_MASTER` e rotas `/api/v1/master/**`).
- Spec 12 (autenticação por API key do lojista; `merchantId` no principal).
- Spec 4 (criptografia/domínio existente; `ErrorResponse`).

## Observações

- **Bloqueio não estorna:** o master bloqueia a **realização de novos saques**;
  saques já `COMPLETED` permanecem.
- **Posse (lojista só saca da própria conta):** o `merchantId` no path deve ser
  comparado com o principal da chave — ver Spec 16 (autorização de posse); nesta
  spec o controller valida a posse de forma explícita mínima (mesmo lojista).
- **Aprovação por saque não existe** (decisão do usuário): o fluxo é automático
  quando liberado — só o bloqueio global por lojista o interrompe.
- **Endpoints de gestão de master** (criar/desativar perfis) fora de escopo.
