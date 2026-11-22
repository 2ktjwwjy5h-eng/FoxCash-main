# Spec 141 — Task: Device Fingerprint Caseiro

## Fase 0 — Base
- [x] Registrar Spec 141 no SDD (requirements/design/task + roadmap + Visão V101)

## Fase 1 — Backend (infra + domain)
- [x] Migration `V77__device_fingerprint.sql` (device_fingerprints + device_fingerprint_merchants + índice)
- [x] Domain `DeviceFingerprint` (record) + port `DeviceFingerprintRepository`
- [x] JPA entities (hash PK natural + chave composta) + `DeviceFingerprintJpaRepository` (upsert `ON CONFLICT` nativo + refresh distinct)
- [x] `DeviceFingerprintPersistenceAdapter`

## Fase 2 — Backend (config + serviço)
- [x] `FingerprintProperties` (`foxcash.fingerprint.enabled`, env `FOXCASH_FINGERPRINT_ENABLED`) + registro no `ApplicationConfig` + bloco no `application.yml`
- [x] `DeviceFingerprintService` (valida 64-hex, normaliza minúsculas, best-effort)

## Fase 3 — Integração (cadastro + login)
- [x] `CreateMerchantRequest` + `fingerprintHash`; `RegisterMerchantUseCase.Command` + campo (compat 3-args)
- [x] `MerchantLoginRequest` + `fingerprintHash`; `MerchantPortalAuthUseCase.LoginCommand` + campo (compat 2/3-args)
- [x] `MerchantController` / `MerchantAuthController` propagam o campo
- [x] `RegisterMerchantService` grava fingerprint no cadastro (details `MERCHANT_REGISTERED`)
- [x] `MerchantPortalAuthService` grava fingerprint no login (details `MERCHANT_LOGIN`)

## Fase 4 — Frontend (portal)
- [x] `portal/src/fingerprint.ts` (sinais → SHA-256 via Web Crypto, best-effort, cache por página)
- [x] `LoginPage.tsx` envia `fingerprintHash`; `auth.tsx` `login(..., fingerprintHash?)`

## Fase 5 — Alerta automático (Telegram/Discord) ✅
- [x] `WebhookEventType.FINGERPRINT_DEVICE_SHARED`
- [x] `FingerprintProperties` + `alert-threshold` (default 2) + `alert-throttle-seconds` (default 300) no yml
- [x] `FingerprintAlertPublisher` (padrão HoneypotAlertPublisher: throttle por hash, REQUIRES_NEW, fail-safe)
- [x] `DeviceFingerprintService.record` chama `maybeAlert` após gravar (N contas → alerta)
- [x] `FingerprintAlertPublisherTest` (threshold/throttle/kill-switch/fail-safe)

## Fase 6 — Flag no Master Console ✅
- [x] `MerchantPanelDetail` + `fingerprintDistinctMerchants` (overload `of` compat)
- [x] `DeviceFingerprintRepository.maxDistinctMerchantsOf` + JPA query + adapter
- [x] `MerchantPanelQueryService.panelDetail` preenche o campo
- [x] Console: `types.ts` + badge "⚠ N contas no mesmo dispositivo" no `PanelHeader`

## Fase 7 — Testes e gates
- [x] Atualizar `DeviceFingerprintServiceTest` (novo construtor) + `MerchantPanelQueryServiceTest` (novo mock)
- [x] Gate: `mvn compile` OK
- [x] Gate: testes alvo verdes
- [x] Gate: `npm run build` console limpo (67 módulos)
- [x] Gate: `mvn clean test` completo verde (**2050/0/0** — jar hibernate-core corrompido no .m2 por builds paralelos; removido e rebaixado)
- [x] docs-consistency OK
- [x] Commit na branch

## Auditoria (2026-08-17) — `/spec-audit 141`
**5/5 critérios OK, 1 achado corrigido, 0 abertos.**

- **C1 (compile+teste):** `mvn test` **2050/0/0** re-executado — suíte completa verde.
- **C2 (frontend):** `npm run build` portal (70 módulos) + console (67 módulos) limpos — `fingerprint.ts` compila e `LoginPage` envia o campo.
- **C3 (validação do hash):** `DeviceFingerprintServiceTest` 6/6 — hash válido grava normalizado (minúsculas); null/blank/malformado → `Optional.empty()` sem gravação.
- **C4 (kill-switch):** `foxcash.fingerprint.enabled=false` → `recordWithFeatureDisabledReturnsEmptyWithoutSaving` (serviço não toca o banco); `FingerprintAlertPublisherTest.disabledDoesNotPublish` (alerta idem). Zero regressão em cadastro/login (testes de `RegisterMerchantServiceTest`/`MerchantPortalAuthServiceTest` com fingerprint ausente → `Optional.empty()`).
- **C5 (upsert atômico):** **achado da auditoria — sem teste automatizado** (o `DeviceFingerprintServiceTest` mocka o repositório). Corrigido com `DeviceFingerprintIntegrationTest` novo (padrão Spec 137, `@Tag("integration")`, fora do default): 2 cadastros + 1 login com o MESMO hash no caminho real HTTP → JPA → Postgres → `seen_count=3`, `distinct_merchants=2`, 2 associações (PK composta sem duplicar), alerta `FINGERPRINT_DEVICE_SHARED` no outbox com payload `{fingerprintHash, distinctMerchants:2}` e requests não bloqueados. **1/1 verde** (Postgres nativo, fallback sem Docker). Para isso, `DeviceFingerprintJpaRepository` ganhou `findByIdFingerprintHash` (`@Query` explícita — derived query com `@EmbeddedId` não resolve).
- **Verificação ao vivo adicional:** webhook do operador validado ponta a ponta pelo usuário (HTTP 204, mensagem entregue ao canal Discord — corpo idêntico ao `discordBody()` do `HttpMerchantNotifier`).
- **docs-consistency:** OK (check A/B/C verdes).

Fim do ciclo SDD — Spec 141 `audited`, feature LIVE.
