# Spec 42 — Design: Backlog de baixos da 3ª auditoria (B-01..B-06)

## Contexto

A 3ª homologação (2026-08-05) aprovou o backend (0 Críticos/Altos, M-01
corrigido em `86af4ff`) e registrou 6 Baixos corrigíveis. Esta spec os remedia
diretamente, no padrão das correções de auditoria anteriores (`87af19f`,
`8fa06a7`, `0b9540b`), com testes unitários por item e E2E sem regressão.

## Decisões de arquitetura

### 1. B-01 — Dummy BCrypt no login do master

- `MasterAuthService.login`: quando `findByUsername` não encontra o usuário,
  executar `passwordHasher.matches(command.password(), DUMMY_BCRYPT_HASH)`
  antes de lançar `InvalidCredentialsException` (mesma constante usada pelo
  `MerchantPortalAuthService` — mover para um local compartilhado, ex.
  `DummyCredentials` no pacote `infrastructure.security`).
- O lockout (B-08) continua antes da verificação de senha **somente para
  usuário existente**; usuário inexistente nunca incrementa contador (não há
  conta), coberto pelo rate limit por IP.
- Teste: `MasterAuthServiceTest` — usuário inexistente executa `matches`
  (mock verificado) e responde 401.

### 2. B-02 — `reset-password` no rate limit

- `RateLimitFilter.LIMITED_PATHS` ganha
  `"/api/v1/merchant-auth/reset-password"`.
- Teste: `RateLimitFilterTest` — 21+ POSTs no mesmo IP+rota → 429.

### 3. B-03 — `pix_key` criptografada em repouso

- `PixTransactionJpaEntity.pixKey` recebe `@Convert(converter =
  CryptoConverter.class)` (padrão de `MerchantJpaEntity.document`).
- **Sem mudança de schema** (a coluna `VARCHAR(255)` já existe — só muda o
  converter): o backfill dos registros existentes é feito por um
  `PixKeyEncryptionRunner` idempotente (padrão do `OpeningBalanceSeedRunner`)
  que lê os registros com `pix_key` sem prefixo `v1:` e os reescreve via JPA
  (converter aplicado no save), em lote. A migration **V35** é reservada para
  o B-06 (lockout do lojista).
- **Atenção:** busca por `pixKey` deixa de ser possível por igualdade no
  ciphertext (IV aleatório) — verificar se algum fluxo consulta por `pixKey`
  (se sim, adicionar coluna hash auxiliar, padrão `document_hash`); esperado:
  nenhum lookup por `pixKey` existe.
- Teste: `PixTransactionMapperTest`/entidade — save grava `v1:` (SQL), read
  devolve em claro.

### 4. B-04 — PSP de saque fora da transação

Problema: o débito, o registro do saque e o I/O do PSP precisam ser atômicos;
hoje a atomicidade vem de manter o PSP dentro da `@Transactional` (pool retido).

Abordagem em 2 fases (mesmo padrão da Spec 28 para cobrança):

- **Fase A (transação):** débito + `Withdrawal.processing(...)` salvo como
  `PROCESSING` **sem** `external_reference` + ledger `WITHDRAWAL_DEBIT`
  (commit). Atomicidade do movimento financeiro preservada.
- **Fase B (fora da transação):** `payoutProvider.createPayout(new
  PayoutCommand(withdrawal.id(), ...))`. Sucesso → transação própria
  (`WithdrawalSubmissionPersister`, REQUIRES_NEW — padrão
  `IdempotencyPersister`) grava `external_reference`/`payout_provider`. Falha
  síncrona do PSP → **estorno do débito** em transação própria (credit +
  ledger `WITHDRAWAL_REVERSAL`, mesmo fluxo do `WithdrawalSettlementService`
  FAILED) e o saque fica `FAILED` com motivo — sem saque fantasma e sem
  reter o pool.
- Retry idempotente: saque `PROCESSING` sem `external_reference` (falha
  entre commit A e B) → nova tentativa reusa o **mesmo** `withdrawal.id`
  (idempotente no adquirente, padrão `code` = id da Spec 28); a reconciliação
  de órfãos (Spec 36) continua cobrindo PROCESSING presos.
- `MasterWithdrawalService` segue o mesmo fluxo.
- Teste: `WithdrawalServiceTest` — pool não retido (verificação por chamada
  fora da tx), falha síncrona → débito estornado + FAILED, sucesso →
  PROCESSING com external_reference, retry idempotente sem duplo débito.

### 5. B-05 — Validação do `amount` no webhook PAID

- `ProcessPixWebhookService`: no evento `PAID`, se `event.amount() != null` e
  `event.amount() != transaction.amount()` → **não** aprovar às cegas:
  registrar divergência (log WARN + `ReconciliationIssueRepository.open` com
  tipo novo ou `TransactionNotFoundException`-like guardado) e tratar como
  evento não-processável (no-op + issue), deixando o operador resolver. Se o
  PSP não enviar `amount`, mantém o comportamento atual (confia no registro).
- `PixWebhookRequest.amount` já existe no DTO (BigDecimal) — só passa a ser
  utilizado.
- Teste: `ProcessPixWebhookServiceTest` — PAID com amount igual → APPROVED;
  amount divergente → no-op + issue registrada; amount nulo → APPROVED.

### 6. B-06 — Lockout por conta no login do lojista

- Mesmo padrão do master (B-08): migration **V35**
  (`V35__merchant_login_lockout.sql`) adiciona `failed_login_attempts`/
  `locked_until` em `merchant_credentials` + persister REQUIRES_NEW
  (`MerchantLoginLockoutPersister`) — o throw dentro do `@Transactional` do
  login faria rollback de um save comum.
- `MerchantPortalAuthService.login`: e-mail existente → checa lockout (401
  uniforme se bloqueado), senha errada → incrementa (persiste em transação
  própria) + 401; senha correta → reseta contador, emite sessão. E-mail
  inexistente → dummy BCrypt + 401 sem contador (rate limit por IP cobre).
- Config reutiliza `foxcash.auth.max-login-attempts`/`lockout-minutes`.
- E2E cenário 8/21: senhas de teste seguem a política (B-07); ajustar se o
  lockout de lojista disparar durante a suíte (contadores resetam no
  TRUNCATE do global-setup).

## Arquivos (visão geral)

- `infrastructure/security/DummyCredentials` (constante compartilhada)
- `application/service/MasterAuthService` (B-01), `RateLimitFilter` (B-02)
- `outbound/persistence/entity/PixTransactionJpaEntity` + `V35` + runner (B-03)
- `application/service/WithdrawalService`/
  `MasterWithdrawalService` + `WithdrawalSubmissionPersister` (B-04)
- `application/service/ProcessPixWebhookService` + `ReconciliationIssueRepository` (B-05)
- `application/service/MerchantPortalAuthService` +
  `MerchantLoginLockoutPersister` + `V35` (B-06)
