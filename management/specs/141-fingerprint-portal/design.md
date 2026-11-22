# Spec 141 — Design: Device Fingerprint Caseiro

## Arquitetura

Sinal de risco passivo de ponta a ponta: o frontend coleta sinais do navegador
e envia um hash SHA-256; o backend valida, grava (upsert atômico) e associa ao
lojista; a trilha unificada (Spec 41) registra o fingerprint nos details dos
eventos existentes. **Nada bloqueia.**

```
portal/src/fingerprint.ts                  (coleta sinais → SHA-256 hex)
        │ fingerprintHash (opcional)
        ▼
POST /api/v1/merchants                    POST /api/v1/merchant-auth/login
        │ CreateMerchantRequest                 │ MerchantLoginRequest
        ▼                                       ▼
RegisterMerchantUseCase.Command          MerchantPortalAuthUseCase.LoginCommand
        │                                       │
        ▼                                       ▼
RegisterMerchantService                  MerchantPortalAuthService
        │                                       │
        └────────── DeviceFingerprintService.record(hash, merchantId)
                          │
                          ▼
              DeviceFingerprintRepository (port out)
                          │
                          ▼
        DeviceFingerprintPersistenceAdapter (JPA, upsert ON CONFLICT)
                          │
                          ▼
        device_fingerprints + device_fingerprint_merchants (V77)
```

## Decisões

1. **Hash no cliente, validação no servidor.** O frontend reduz os sinais a
   SHA-256 hex (64 chars) via Web Crypto; o backend valida o formato
   (`^[0-9a-f]{64}$`), normaliza minúsculas e **grava o hash, nunca os sinais**
   — zero PII extra no servidor. Best-effort: hash ausente/inválido → não grava
   e não quebra o request (o fingerprint é auditoria, não gate).
2. **Upsert atômico (`ON CONFLICT`).** O mesmo dispositivo pode enviar o hash
   em paralelo (cadastro + login, ou 2 abas). Queries nativas
   `INSERT ... ON CONFLICT DO UPDATE` incrementam `seen_count` e atualizam
   `last_seen_at` sem lost update; `distinct_merchants` é recalculado por
   subquery na associação após o upsert.
3. **Tabelas.**

   ```sql
   device_fingerprints (
     fingerprint_hash  VARCHAR(64) PK,   -- SHA-256 hex
     first_seen_at, last_seen_at,        -- janelas
     seen_count, distinct_merchants      -- contadores
   )
   device_fingerprint_merchants (
     fingerprint_hash → device_fingerprints (CASCADE),
     merchant_id      → merchants (CASCADE),
     first_seen_at, last_seen_at, seen_count,
     PK (fingerprint_hash, merchant_id)
   )
   ```

   `distinct_merchants > 1` = mesmo dispositivo em N contas (sinal clássico de
   fraude multi-conta) — a consulta de risco é um `count(*)` na associação.
4. **Sem PlatformAction novo.** O fingerprint entra nos `details` dos eventos
   `MERCHANT_REGISTERED` / `MERCHANT_LOGIN` como
   `{fingerprint: {hash, distinctMerchants}}` — a trilha da Spec 41 já mascara
   chaves sensíveis; o hash não é sensível (identificador pseudônimo).
5. **Config kill-switch.** `foxcash.fingerprint.enabled` (default `true`) —
   `false` → o serviço retorna vazio sem tocar no banco. Mesmo padrão
   fail-open/ADR-010 das features de segurança (Turnstile/body-inspection).

## Arquivos

| Arquivo | Papel |
|---|---|
| `src/main/resources/db/migration/V77__device_fingerprint.sql` | Tabelas + índices |
| `domain/model/fingerprint/DeviceFingerprint.java` | Modelo (record imutável) |
| `domain/port/out/DeviceFingerprintRepository.java` | Porta de saída |
| `application/service/DeviceFingerprintService.java` | Validação + gravação (best-effort) |
| `infrastructure/config/FingerprintProperties.java` | `foxcash.fingerprint.*` |
| `infrastructure/outbound/persistence/entity/DeviceFingerprintJpaEntity.java` | Entidade JPA (hash PK) |
| `infrastructure/outbound/persistence/entity/DeviceFingerprintMerchantJpaEntity.java` | Entidade JPA (chave composta) |
| `infrastructure/outbound/persistence/repository/DeviceFingerprintJpaRepository.java` | Queries upsert nativas |
| `infrastructure/outbound/persistence/adapter/DeviceFingerprintPersistenceAdapter.java` | Adapter do port |
| `infrastructure/inbound/rest/dto/CreateMerchantRequest.java` | + `fingerprintHash` |
| `infrastructure/inbound/rest/dto/MerchantLoginRequest.java` | + `fingerprintHash` |
| `domain/port/in/RegisterMerchantUseCase.java` | Command + campo (compat) |
| `domain/port/in/MerchantPortalAuthUseCase.java` | LoginCommand + campo (compat) |
| `application/service/RegisterMerchantService.java` | Grava no cadastro |
| `application/service/MerchantPortalAuthService.java` | Grava no login |
| `infrastructure/inbound/rest/MerchantController.java` | Propaga o campo |
| `infrastructure/inbound/rest/MerchantAuthController.java` | Propaga o campo |
| `infrastructure/config/ApplicationConfig.java` | Registra `FingerprintProperties` |
| `src/main/resources/application.yml` | Bloco `foxcash.fingerprint` |
| `portal/src/fingerprint.ts` | Coleta + SHA-256 (Web Crypto) |
| `portal/src/pages/LoginPage.tsx` | Envia o hash no login |
| `portal/src/auth.tsx` | `login(..., fingerprintHash?)` |
| `src/test/java/.../DeviceFingerprintServiceTest.java` | Testes do serviço |

## Riscos / mitigação

- **Falso-positivo de dispositivo compartilhado** (mesmo PC, 2 contas legítimas):
  mitigado por design — o fingerprint é **registro**, não ação; o master decide.
- **Canvas/WebGL bloqueados** (privacidade/headless): best-effort por bloco —
  os demais sinais seguem; sem sinais → `''` (backend ignora).
- **Hash muda entre sessões** (browser update/zoom/fontes): mitigado parcialmente
  pelos sinais estáveis (UA/tela/timezone); janelas `first/last_seen` permitem
  correlacionar mesmo com hash mudando.
- **Concorrência no upsert**: `ON CONFLICT` atômico por linha — sem lock
  aplicado no app.
