# Spec 41 — Task breakdown

Status: `audited` (2026-08-05). Implementação concluída e auditada — todos os itens verificados.

## Implementação — Parte A (perfis master)

- [x] Migration `V33__master_profiles_activity.sql` (parte 1): `masters.can_manage_masters`
      BOOLEAN NOT NULL DEFAULT FALSE + backfill do master mais antigo → TRUE.
- [x] `Master` (domain): campo `canManageMasters` (final); `create(username,
      passwordHash, name, canManageMasters)` + overload `create(..., false)`;
      `restore` com o campo; accessor.
- [x] `MasterSeedRunner`: cria o primeiro master com `can_manage_masters=true`.
- [x] `MasterRepository`: `List<Master> findAll()` (já tem `count`).
- [x] `MasterPasswordGenerator` (port out) + `MasterPasswordGeneratorImpl`
      (`mst_<16 hex>` SecureRandom).
- [x] Exceções novas (domain/exception): `MasterProfileAccessDeniedException`
      (403 `forbidden`), `TooManyMastersException` (409 `too_many_masters`),
      `MasterAlreadyExistsException` (409 `master_already_exists`),
      `CannotDeactivateSelfException` (409 `cannot_deactivate_self`),
      `CannotDeactivateLastAdminException` (409 `cannot_deactivate_last_admin`)
      + handlers no `GlobalExceptionHandler`.
- [x] `MasterProfileUseCase` (port in): `CreatedProfile create(CreateCommand,
      actorMasterId, ip)`, `deactivate/activate(targetId, actorMasterId, ip)`,
      `List<Profile> list()`.
- [x] `MasterProfileService`: requireAdmin (flag) em todas; create valida limite
      `foxcash.master.max-profiles` (default 4) → 409; username duplicado → 409;
      senha gerada 1x (hash BCrypt); deactivate não-é-a-si-mesmo nem-último-admin
      → 409; auditoria `MASTER_CREATED/ACTIVATED/DEACTIVATED` (Spec 37) na mesma
      transação; `list` sem passwordHash.
- [x] `MasterAuditAction`: `MASTER_CREATED`, `MASTER_ACTIVATED`,
      `MASTER_DEACTIVATED`.
- [x] `MasterProperties`: `maxProfiles` (default 4).
- [x] `MasterProfileController` (rota **não-documentada**): `POST /master/profiles`
      (201 com `temporaryPassword` 1x), `GET /master/profiles`, `POST
      /master/profiles/{id}/activate|deactivate` (204). IP via `ClientIpResolver`.

## Implementação — Parte B (relatório de atividades)

- [x] Migration `V33` (parte 2): tabela `platform_activity_log` (actor_type,
      actor_id, action, entity_type, entity_id, merchant_id, details JSONB, ip,
      created_at) + índices (created_at/merchant_id/action).
- [x] Domain: `PlatformActorType {MASTER, MERCHANT, SYSTEM}`; `PlatformAction`
      (17 eventos); `PlatformActivity` (record imutável); `PlatformActivityQuery`
      (filtros from/to/actorType/action/merchantId/page/size).
- [x] `PlatformActivityRecorder` (application service): `record(...)` com máscara
      de dados sensíveis no details (padrão `MasterAuditRecorder`); IP via
      `ClientIpResolver` (null em jobs/SYSTEM).
- [x] `PlatformActivityRepository` (port out) + entidade/mapper/adapter JPA
      (Specifications paginada).
- [x] `PlatformActivityUseCase`/`PlatformActivityService`: `PageResult query(...)`.
- [x] `MasterActivityReportController` (não-documentado): `GET
      /master/report/activity` (admin-only) paginado + filtros; 400 em inválido.
- [x] Instrumentar nos pontos (mesma transação): `MasterAuthService`
      (MASTER_LOGIN/LOGOUT), `MasterProfileService` (MASTER_CREATED/ACTIVATED/
      DEACTIVATED), `RegisterMerchantService` (MERCHANT_REGISTERED),
      `MerchantPortalAuthService` (MERCHANT_LOGIN/LOGOUT), `PasswordResetService`
      (PASSWORD_RESET), `CreatePixChargeService` (CHARGE_CREATED),
      `ProcessPixWebhookService` (SALE_PAID/SALE_REFUNDED),
      `RegisterChargebackService` (CHARGEBACK_REGISTERED),
      `WithdrawalService`/`MasterWithdrawalService` (WITHDRAWAL_REQUESTED),
      `WithdrawalSettlementService` (WITHDRAWAL_COMPLETED/FAILED),
      `InvoiceCollectionService` (INVOICE_COLLECTED — MASTER manual / SYSTEM job).

## Verificação

- [x] `MasterProfileServiceTest`: create 201 com senha 1x (só hash no banco);
      limite 4 → 409; username duplicado → 409; sem flag → 403; deactivate self
      → 409; deactivate último admin → 409; activate/deactivate 204; auditoria
      registrada; list sem hash.
- [x] `MasterPasswordGeneratorTest`: formato `mst_<16 hex>`.
- [x] `PlatformActivityRecorderTest`: details sem dados sensíveis (máscara);
      ip null p/ SYSTEM.
- [x] `PlatformActivityServiceTest`: consulta paginada + filtros combinados
      (from/to/actorType/action/merchantId), 400 em inválido.
- [x] `MasterAuthServiceTest`/`RegisterMerchantServiceTest`/etc.: eventos
      registrados no recorder (verificação de integração por mock).
- [x] `mvn test` verde (404 + novos).
- [x] E2E (cenário 22): admin cria perfil → 201 com `temporaryPassword`; login
      com o novo perfil → 200; perfil sem flag em `/master/profiles` → 403;
      deactivate self → 409; 5º perfil → 409; relatório de atividades com
      eventos reais (cadastro, login, cobrança, saque) filtrados por
      action/merchantId/período; 401 sem token; suite sem regressão.
- [x] Boot com V33 aplicada (Flyway) sem erro.
- [x] Roadmap/requirements/task/vision V24; rota **não** documentada no README
      público; `/spec-audit` → `audited` (fim do ciclo SDD).
