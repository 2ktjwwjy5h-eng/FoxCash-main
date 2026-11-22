# Spec 42 — Task breakdown

Status: `audited` (2026-08-05). `mvn test` **479/0/0** · E2E **22/22**.

## Implementação

- [x] **B-01 — dummy BCrypt no login do master**
  - [x] `DummyCredentials` (infrastructure/security): constante `DUMMY_BCRYPT_HASH`
        compartilhada com `MerchantPortalAuthService` (refatorar o uso local).
  - [x] `MasterAuthService.login`: usuário inexistente executa `matches` dummy
        antes do 401.
  - [x] Teste: `MasterAuthServiceTest` — usuário inexistente → `matches`
        executado (mock) + 401.
- [x] **B-02 — rate limit no `reset-password`**
  - [x] `RateLimitFilter.LIMITED_PATHS` += `/api/v1/merchant-auth/reset-password`.
  - [x] Teste: `RateLimitFilterTest` — 21+ POSTs → 429.
- [x] **B-03 — `pix_key` criptografada em repouso**
  - [x] `@Convert(CryptoConverter.class)` em `PixTransactionJpaEntity.pixKey`.
  - [x] Backfill idempotente via `PixKeyEncryptionRunner` (registros sem
        prefixo `v1:` reescritos via SQL nativo com o converter aplicado — o
        valor em claro não passa pelo `@Convert` na leitura) — sem mudança de
        schema (V35 é do B-06).
  - [x] Verificar inexistência de lookup por `pixKey` (sem coluna hash nova).
  - [x] Teste: `PixKeyEncryptionRunnerTest` — save → `v1:` no banco (smoke ao
        vivo via SQL); read → claro (API devolve o valor descriptografado).
- [x] **B-04 — PSP de saque fora da transação**
  - [x] `WithdrawalService`/`MasterWithdrawalService`: Fase A
        (`WithdrawalProcessingPersister`, transação própria: débito + saque
        PROCESSING sem external_reference + ledger, commit) → Fase B (PSP fora
        da tx) → `WithdrawalSubmissionPersister` (REQUIRES_NEW) grava a
        referência; falha síncrona → estorno + `FAILED` (`settleFailedByWithdrawalId`,
        mesmo fluxo do settlement).
  - [x] Retry idempotente reusa o mesmo `withdrawal.id` (reconciliação cobre
        PROCESSING sem referência).
  - [x] Testes: `WithdrawalServiceTest`/`MasterWithdrawalServiceTest`
        (orquestração + estorno), `WithdrawalProcessingPersisterTest` (Fase A:
        validações + débito + ledger + auditoria), `WithdrawalReconciliationServiceTest`
        (retry sem duplo débito).
- [x] **B-05 — validação do `amount` no webhook PAID**
  - [x] `ProcessPixWebhookService`: PAID com `amount` divergente → no-op +
        issue de reconciliação (`ReconciliationIssueRepository.open`, WARN);
        amount nulo/igual → fluxo atual.
  - [x] Testes: igual → APPROVED; divergente → no-op + issue; nulo → APPROVED.
- [x] **B-06 — lockout por conta no login do lojista**
  - [x] Migration `V35__merchant_login_lockout.sql`: `merchant_credentials`
        ganha `failed_login_attempts`/`locked_until`.
  - [x] `MerchantLoginLockoutPersister` (REQUIRES_NEW) + integração no
        `MerchantPortalAuthService.login` (lockout → 401 uniforme; falha →
        incrementa; sucesso → reseta).
  - [x] Testes: 5 falhas → bloqueio; bloqueado → 401; sucesso reseta;
        inexistente → 401 sem contador.

## Verificação

- [x] `mvn test` verde — **478/0/0** (60 testes novos: B-01 1, B-02 1, B-03 3,
      B-04 20, B-05 3, B-06 6, mais cobertura movida para o
      `WithdrawalProcessingPersisterTest`).
- [x] E2E **22/22** sem regressão (dev; lockout de lojista não disparou na
      suíte — contadores resetam no TRUNCATE do global-setup).
- [x] Smoke ao vivo: reset-password **429** após flood (17×400 → 18º 429);
      `pix_key` gravada como `v1:<base64>` no banco e devolvida em claro pela
      API; login do lojista com 5 falhas → bloqueado (401 mesmo com senha
      correta); B-01 coberto por teste unitário (BCrypt dummy executado em
      usuário inexistente).

## Auditoria (ciclo SDD — `/spec-audit`, 2026-08-05)

**6/6 critérios OK** — `mvn test` **479/0/0** (479 = 478 + 1 do fix abaixo) e
E2E **22/22** sem regressão.

- **Achado corrigido (B-04):** o `try/catch` da Fase B cobria toda a etapa —
  se o PSP JÁ tivesse aceito o saque (`submit` OK) e uma etapa posterior
  falhasse (ex.: `activityRecorder`), o catch estornava um saque já pago no
  adquirente (duplo pagamento/ghost). Fix: o estorno passou a cobrir APENAS a
  falha síncrona do `createPayout` (`WithdrawalService`/`MasterWithdrawalService`)
  + teste `noReversalWhenPspAlreadyAcceptedAndLaterStepFails`.
- **Aceitos por design:**
  - Caminho de conta bloqueada (B-01/B-06) não executa BCrypt — espelha o B-08
    do master; não é oráculo de enumeração (o lockout só é atingível quando a
    existência da conta já é conhecida).
  - Issue de reconciliação do B-05 reusa a `OPEN` existente do lojista
    (idempotência `uq_reconciliation_open` da Spec 30); o log WARN sempre
    registra a divergência de valor.
  - A reconciliação de saques (Spec 36) consulta o PSP dentro da transação do
    job — comportamento pré-existente, fora do escopo do B-04 (que ataca o
    fluxo de solicitação do saque).
- **Fim do ciclo SDD:** roadmap `audited` (feature LIVE).
