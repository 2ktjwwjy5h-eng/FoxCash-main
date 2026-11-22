# Spec 41 — Design: Gestão de perfis master + relatório unificado de atividades

## Contexto

O backend não permite criar/gerir perfis master além do seed (Spec 13), e não há
uma visão unificada do que acontece na plataforma (as trilhas são separadas:
`master_audit_log`, `wallet_ledger`, `master_ledger`). Decisões 2026-08-05:
(1) criar perfis master de forma **autorizada e oculta** — flag
`can_manage_masters`, rota não-documentada, limite de 4, senha gerada 1x;
(2) dar ao admin um **relatório de tudo** — trilha unificada `platform_activity_log`.

## Parte A — Gestão de perfis master

### A1. Migration V33 (parte 1) — `masters.can_manage_masters`

```sql
ALTER TABLE masters ADD COLUMN can_manage_masters BOOLEAN NOT NULL DEFAULT FALSE;
-- Backfill: o master mais antigo vira admin (o seed cria o 1º com true).
UPDATE masters SET can_manage_masters = TRUE
    WHERE id = (SELECT id FROM masters ORDER BY created_at ASC LIMIT 1);
```

- `MasterSeedRunner` passa a criar o primeiro master com `can_manage_masters=true`.

### A2. Domínio `Master`

```java
private final boolean canManageMasters;   // admin fixo (não muda por API)

public static Master create(String username, String passwordHash, String name, boolean canManageMasters);
public static Master create(String username, String passwordHash, String name) { return create(..., false); }
// restore ganha o campo.
```

### A3. Portas

- `MasterRepository` (aditivo): `List<Master> findAll()`; `long count()` (já
  existe — usado no limite).
- `domain/port/in/MasterProfileUseCase.java`:
  - `CreatedProfile create(CreateCommand command, UUID actorMasterId, String ip)`;
  - `void deactivate(UUID targetId, UUID actorMasterId, String ip)`;
  - `void activate(UUID targetId, UUID actorMasterId, String ip)`;
  - `List<Profile> list()` (sem hash).
- `domain/port/out/MasterPasswordGenerator.java` + impl `mst_<16 hex>`
  (SecureRandom; mesmo padrão do `ResetTokenGenerator`, prefixo `mst_`).

### A4. Serviço `MasterProfileService`

- `requireAdmin(actorId)`: busca o master do principal; `!canManageMasters` →
  `MasterProfileAccessDeniedException` (403 `forbidden`).
- `create`: `requireAdmin`; `count() >= maxProfiles` → `TooManyMastersException`
  (409 `too_many_masters`); `findByUsername` presente → `MasterAlreadyExistsException`
  (409 `master_already_exists`); gera senha (hash BCrypt) + `Master.create(..., false)`;
  auditoria `MASTER_CREATED` (before=null, after={username, active, canManageMasters});
  retorna `temporaryPassword` UMA vez.
- `deactivate`: `requireAdmin`; alvo == actor → `CannotDeactivateSelfException`
  (409); alvo é o **único admin ativo** → `CannotDeactivateLastAdminException`
  (409); auditoria `MASTER_DEACTIVATED` (before={active:true}, after={active:false}).
- `activate`: `requireAdmin`; auditoria `MASTER_ACTIVATED`.
- `list`: `requireAdmin`; devolve sem `passwordHash`.
- Regras de negócio na mesma transação da auditoria (padrão Spec 37).

### A5. Exceções novas

`MasterProfileAccessDeniedException` (403), `TooManyMastersException` (409),
`MasterAlreadyExistsException` (409), `CannotDeactivateSelfException` (409),
`CannotDeactivateLastAdminException` (409) → handlers no `GlobalExceptionHandler`.

### A6. REST — `MasterProfileController` (rota não-documentada)

- `POST /api/v1/master/profiles` `{username, name}` → **201**
  `{id, username, name, active: true, temporaryPassword}` (senha UMA vez).
- `GET /api/v1/master/profiles` → lista `{id, username, name, active,
  canManageMasters, createdAt}`.
- `POST /api/v1/master/profiles/{id}/deactivate|activate` → **204**.
- Todos `ROLE_MASTER` (SecurityConfig já cobre `/api/v1/master/**`); o check da
  flag é no serviço (actor = principal). IP via `ClientIpResolver`.

### A7. Auditoria — `MasterAuditAction`

```java
MASTER_CREATED, MASTER_ACTIVATED, MASTER_DEACTIVATED
```

## Parte B — Relatório de atividades (`platform_activity_log`)

### B1. Migration V33 (parte 2)

```sql
CREATE TABLE platform_activity_log (
    id          UUID PRIMARY KEY,
    actor_type  VARCHAR(16) NOT NULL,   -- MASTER | MERCHANT | SYSTEM
    actor_id    UUID,                    -- master.id()/merchant.id() (null p/ SYSTEM)
    action      VARCHAR(64) NOT NULL,
    entity_type VARCHAR(32),             -- MERCHANT|TRANSACTION|WITHDRAWAL|INVOICE|SESSION|MASTER...
    entity_id   UUID,
    merchant_id UUID,                    -- lojista afetado (null p/ ações globais)
    details     TEXT,                   -- JSON serializado (mesmo padrão do master_audit_log)
    ip          VARCHAR(45),
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_platform_activity_created ON platform_activity_log(created_at);
CREATE INDEX ix_platform_activity_merchant ON platform_activity_log(merchant_id);
CREATE INDEX ix_platform_activity_action ON platform_activity_log(action);
```

### B2. Domínio

- `PlatformActorType { MASTER, MERCHANT, SYSTEM }`.
- `PlatformAction` (enum com os eventos): `MASTER_LOGIN, MASTER_LOGOUT,
  MASTER_CREATED, MASTER_ACTIVATED, MASTER_DEACTIVATED, MERCHANT_REGISTERED,
  MERCHANT_LOGIN, MERCHANT_LOGOUT, PASSWORD_RESET, CHARGE_CREATED, SALE_PAID,
  SALE_REFUNDED, CHARGEBACK_REGISTERED, WITHDRAWAL_REQUESTED,
  WITHDRAWAL_COMPLETED, WITHDRAWAL_FAILED, INVOICE_COLLECTED`.
- `PlatformActivity` (record imutável, como `MasterAuditEntry`).
- `PlatformActivityQuery` (filtros: from/to/actorType/action/merchantId/page/size).

### B3. `PlatformActivityRecorder` (application service)

```java
@Service
public class PlatformActivityRecorder {
    // record(actorType, actorId, action, entityType, entityId, merchantId, details, ip)
    // - chamado DENTRO do @Transactional da ação (mesma transação)
    // - details serializado com MÁSCARA de dados sensíveis (reusa padrão do
    //   MasterAuditRecorder: document/key/password/token/destinationKey...)
    // - ip: null quando não há request (jobs = SYSTEM)
}
```

- `ClientIpResolver` (já existe na infraestrutura) resolve o IP do request quando
  disponível; jobs passam `null`.

### B4. Pontos de instrumentação (mesma transação do evento)

| Ponto | Evento |
|---|---|
| `MasterAuthService.login` | `MASTER_LOGIN` (SYSTEM? não — MASTER, actor=masterId) |
| `MasterAuthService.logout` | `MASTER_LOGOUT` |
| `MasterProfileService` (Parte A) | `MASTER_CREATED/ACTIVATED/DEACTIVATED` |
| `RegisterMerchantService` | `MERCHANT_REGISTERED` (actor=MERCHANT) |
| `MerchantPortalAuthService.login/logout` | `MERCHANT_LOGIN` / `MERCHANT_LOGOUT` |
| `PasswordResetService.reset` | `PASSWORD_RESET` |
| `CreatePixChargeService` | `CHARGE_CREATED` |
| `ProcessPixWebhookService` (PAID/REFUNDED) | `SALE_PAID` / `SALE_REFUNDED` |
| `RegisterChargebackService` | `CHARGEBACK_REGISTERED` |
| `WithdrawalService`/`MasterWithdrawalService` | `WITHDRAWAL_REQUESTED` |
| `WithdrawalSettlementService` (COMPLETED/FAILED) | `WITHDRAWAL_COMPLETED` / `WITHDRAWAL_FAILED` |
| `InvoiceCollectionService` (job + manual) | `INVOICE_COLLECTED` (actor MASTER p/ manual, SYSTEM p/ job) |

- Para o login/logout do lojista, o `details` traz `{email}` (sem senha).
- Para `CHARGE_CREATED`: `{amount, transactionId}`. `SALE_PAID`: `{amount}`.
  `WITHDRAWAL_*`: `{amount}`. `INVOICE_COLLECTED`: `{invoiceId, amount}`.
  Nenhum dado sensível (chave Pix/documento/senha/token).

### B5. Consulta — `PlatformActivityUseCase` + `PlatformActivityService`

- `PageResult<PlatformActivity> query(PlatformActivityQuery)` via adapter JPA
  (Specifications — mesmo padrão do admin dashboard/audit).
- Somente admin (`can_manage_masters`) — mesmo guard da Parte A.

### B6. REST — `MasterActivityReportController` (não-documentado)

- `GET /api/v1/master/report/activity?from=&to=&actorType=&action=&merchantId=&page=&size=`
  → `PageResponse` (padrão do projeto) de `{id, occurredAt, actorType, actorId,
  action, entityType, entityId, merchantId, details, ip}`.
- 400 `invalid_argument` em `from/to` inválidos ou `actorType`/`action`
  desconhecidos (via `QueryParamParser`).

### B7. Config

`MasterProperties` ganha `maxProfiles` (default 4) — `foxcash.master.max-profiles`.

## Arquivos (resumo)

- `db/migration/V33__master_profiles_activity.sql` (partes 1 e 2)
- Parte A: `Master` (+flag), `MasterProfileUseCase/Service/Controller`,
  `MasterPasswordGenerator(+Impl)`, exceções novas, `MasterAuditAction` (+3),
  `GlobalExceptionHandler` (+5), `MasterRepository.findAll`, `MasterProperties.maxProfiles`
- Parte B: `PlatformActivity`/`PlatformActorType`/`PlatformAction`/
  `PlatformActivityQuery` (domain), `PlatformActivityUseCase/Service/Recorder`
  (application), `PlatformActivityRepository/Jpa/Entity/Mapper/Adapter` (infra),
  `MasterActivityReportController`, `ClientIpResolver` (reuso)
- Instrumentação nos services listados em B4
- Testes: `MasterProfileServiceTest`, `MasterPasswordGeneratorTest`,
  `PlatformActivityRecorderTest`, `PlatformActivityServiceTest`,
  `GlobalExceptionHandler` (novos códigos), E2E cenário 22; README (rota
  **não** documentada publicamente — apenas nota interna na spec)

## Fora de escopo

- Promoção de perfil a admin por API (flag fixa; decisão futura).
- Rota documentada publicamente (a gestão é intencionalmente oculta).
- Eventos adicionais do `platform_activity_log` além dos listados (evolutivo).
