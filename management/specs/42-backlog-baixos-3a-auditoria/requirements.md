---
name: Backlog de baixos da 3ª auditoria de homologação (B-01..B-06)
status: audited
references:
  - "management/vision.md#V25"
  - "management/specs/13-master-auth/requirements.md"
  - "management/specs/18-security-hardening/requirements.md"
  - "management/specs/40-password-reset/requirements.md"
---

# Spec 42 — Backlog de baixos da 3ª auditoria de homologação (B-01..B-06)

## O que entrega

Corrige os **6 achados Baixos** da 3ª auditoria de homologação (2026-08-05),
todos em segurança/higiene do backend — remediação direta, sem mudança de
contrato público (nenhuma rota existente muda de semântica; apenas respostas
mais robustas e dados mais protegidos):

1. **B-01 — timing side-channel no login do master (enumeração de username):**
   `MasterAuthService.login` responde 401 imediato para usuário inexistente,
   mas executa BCrypt para usuário existente — o atacante mede o tempo e
   descobre quais usernames existem. Aplicar o **mesmo padrão do login do
   lojista** (`DUMMY_BCRYPT_HASH` em `MerchantPortalAuthService`): hash BCrypt
   de senha aleatória verificado quando o usuário não existe, mantendo o tempo
   uniforme. Complementa o lockout (B-08) e o rate limit já existentes.
2. **B-02 — `reset-password` sem rate limit:** `POST
   /api/v1/merchant-auth/reset-password` é público e está fora do
   `RateLimitFilter.LIMITED_PATHS` (login/forgot/cadastro estão). Adicionar a
   rota à lista (consistência com o B-01 da 1ª auditoria — todo endpoint
   público de auth limitado por IP).
3. **B-03 — `pix_key` em repouso sem criptografia:** o campo
   `pix_transactions.pix_key` é gravado em claro; todos os demais dados
   sensíveis (`merchants.document`, destinos de saque, segredo de webhook)
   usam `CryptoConverter` (AES-256/GCM). Aplicar o mesmo converter à coluna.
4. **B-04 — saque chama o PSP dentro da `@Transactional`:** o I/O de rede do
   `payoutProvider.createPayout` ocorre dentro da transação do
   `WithdrawalService`/`MasterWithdrawalService` (conexão do pool retida
   durante a latência do PSP — padrão inconsistente com o fluxo de cobrança,
   que já chama o PSP fora da transação desde a Spec 28). Reestruturar para o
   PSP fora da transação **preservando a atomicidade débito+registro**.
5. **B-05 — webhook de PAID não valida o `amount` do PSP:** o payload do
   webhook traz `amount`, mas o `ProcessPixWebhookService` o ignora e credita
   o valor gravado — divergência silenciosa de valor entre PSP e plataforma.
   Validar o valor recebido contra o valor da transação e registrar a
   divergência (issue de reconciliação) em vez de creditar às cegas.
6. **B-06 — login do lojista sem lockout por conta:** só o master tem lockout
   (B-08). Adicionar contador/lockout por conta (e-mail) no login do portal,
   mesmo padrão do master (`failed_login_attempts`/`locked_until`), mantendo o
   401 uniforme sem oráculo.

## Critérios de aceite

- **B-01:** teste de tempo não é determinístico em CI, mas o comportamento é
  verificável por código — `MasterAuthService.login` executa a verificação
  BCrypt **também** quando o usuário não existe (dummy hash), e o teste
  garante que nenhum caminho retorna sem passar pela operação BCrypt.
- **B-02:** `RateLimitFilter.LIMITED_PATHS` inclui
  `/api/v1/merchant-auth/reset-password`; 21+ chamadas no mesmo IP+rota → 429.
- **B-03:** novas escritas de `pix_key` gravam `v1:<base64>` no banco
  (verificado via SQL); leitura em claro preservada pelo converter; migração
  dos registros existentes (backfill criptografando valores em claro).
- **B-04:** o PSP de saque é chamado **fora** da transação de banco; falha
  síncrona do PSP não deixa saque criado nem débito; saque nasce
  `PROCESSING` sem `external_reference` e é concluído pelo mesmo caminho do
  retry idempotente (nova tentativa com o mesmo `id` do saque); **nenhum**
  cenário de duplo débito ou saque fantasma.
- **B-05:** webhook `PAID` com `amount` divergente → a transação **não** é
  aprovada às cegas; o caso é registrado (log + issue de reconciliação, se
  aplicável) e o fluxo normal (amount igual) permanece inalterado.
- **B-06:** após N falhas seguidas de senha no login do portal, a conta fica
  bloqueada por M minutos (401 uniforme, sem oráculo); login correto reseta o
  contador; usuário inexistente → 401 sem contador (rate limit por IP cobre).
- **Regressão:** `mvn test` verde com novos testes por item; E2E **22/22**
  sem regressão (dev com `expose-reset-token=true` e lockout de lojista
  ajustado no E2E se necessário).

## Dependências

- `management/specs/13-master-auth/requirements.md` (padrão de sessão/lockout)
- `management/specs/18-security-hardening/requirements.md` (rate limit)
- `management/specs/40-password-reset/requirements.md` (reset token)
- `management/specs/30-wallet-reconciliation/requirements.md` (issues de
  reconciliação — B-05)
- `management/specs/36-payout-psp/requirements.md` (fluxo de saque — B-04)
