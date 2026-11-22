---
name: Device fingerprint caseiro do portal (cadastro + login) — sinal de risco passivo
status: audited
references:
  - "management/vision.md#V101"
  - "management/roadmap.md"
---

# Spec 141 — Device Fingerprint Caseiro

## O que entrega

Fingerprint de **browser/device caseiro** (sem lib externa) no portal do lojista:
o frontend coleta sinais do navegador (canvas, WebGL, fontes instaladas, UA,
timezone, resolução, navigator), normaliza e reduz a um **hash SHA-256 (64
hex)** via Web Crypto. O hash é enviado como campo **opcional** no cadastro
(`POST /api/v1/merchants`) e no login (`POST /api/v1/merchant-auth/login`); o
backend valida o formato, grava em tabela própria (upsert atômico) e associa ao
lojista.

**Comportamento: sinal de risco PASSIVO** — registra (tabela + auditoria), **nunca
bloqueia**. A decisão (banir/liberar) é do master, usando os dados como
evidência: mesmo dispositivo em N contas (`distinct_merchants`), frequência de
uso (`seen_count`) e janelas (`first_seen_at`/`last_seen_at`).

**Notificação ao operador (decisão do usuário 2026-08-17 — alerta + flag):**

1. **Alerta automático em tempo real** — o MESMO hash em
   `foxcash.fingerprint.alert-threshold` contas distintas (default 2) publica
   `FINGERPRINT_DEVICE_SHARED` no outbox (Spec 35) → webhook do operador →
   Telegram/Discord. Throttle por hash (`alert-throttle-seconds`, default 300s)
   anti-spam; REQUIRES_NEW + fail-safe (nunca derruba cadastro/login).
2. **Flag no Master Console** — `GET /api/v1/master/merchants/{id}/panel`
   expõe `fingerprintDistinctMerchants` (0 = sem fingerprint, 1 = exclusivo,
   N>1 = risco); a página do lojista mostra badge "⚠ N contas no mesmo
   dispositivo".

1. **Frontend** — `portal/src/fingerprint.ts`: coleta de sinais com isolamento
   por bloco (canvas bloqueado/WebGL off/crypto.subtle indisponível → best-effort,
   continua com o que tem; nada → `''` que o backend ignora). Hash calculado 1x
   por carregamento da página (cache em módulo) para estabilidade na sessão.
   Integração no `LoginPage` (envia `fingerprintHash` no body do login).
2. **API** — campo `fingerprintHash` opcional (`@Size(max=64)`) no
   `CreateMerchantRequest` e `MerchantLoginRequest`; propagado via
   `RegisterMerchantUseCase.Command` / `MerchantPortalAuthUseCase.LoginCommand`
   (com construtores compat para requests sem o campo).
3. **Backend** — migration **V77**: `device_fingerprints` (hash PK natural,
   janelas, `seen_count`, `distinct_merchants`) + `device_fingerprint_merchants`
   (associação hash→lojista, chave composta). Domain `DeviceFingerprint` + port
   `DeviceFingerprintRepository`; adapter JPA com **upsert atômico `ON CONFLICT`**
   (sem lost update entre requests concorrentes). `DeviceFingerprintService`
   (app) valida o formato 64-hex, normaliza minúsculas e grava — best-effort:
   hash ausente/malformado/feature off **nunca quebra** o request.
4. **Config** — `foxcash.fingerprint.enabled` (`FingerprintProperties`,
   env `FOXCASH_FINGERPRINT_ENABLED`, default `true`), `alert-threshold`
   (default 2), `alert-throttle-seconds` (default 300).
5. **Auditoria** — o fingerprint entra nos `details` dos eventos existentes
   (`MERCHANT_REGISTERED` / `MERCHANT_LOGIN`) como `{fingerprint: {hash,
   distinctMerchants}}` — a trilha unificada (Spec 41) ganha o sinal sem ação nova.

**Não faz:** bloqueio automático (mesmo hash em N contas não bloqueia nada —
decisão manual do master), página de cadastro no portal (o cadastro é via API),
fingerprint no console do master (fora de escopo nesta versão).

## Critérios de aceite

1. **Gate compile+teste:** `mvn compile` e `mvn test` verdes (suíte completa,
   incluindo `DeviceFingerprintServiceTest` novo e os testes atualizados de
   cadastro/login).
2. **Gate frontend:** `npm run build` (portal) limpo — `fingerprint.ts` compila
   e o `LoginPage` envia o campo.
3. **Validação do hash:** hash válido é gravado (normalizado minúsculas); hash
   ausente/vazio/malformado (não-hex, tamanho ≠ 64) → `Optional.empty()`, sem
   gravação e sem erro ao chamador.
4. **Kill-switch:** `foxcash.fingerprint.enabled=false` → nada é gravado, login/
   cadastro seguem idênticos (zero regressão).
5. **Upsert atômico:** o mesmo hash em 2 requests (ex.: cadastro + login)
   incrementa `seen_count` e associa ao lojista sem duplicar/regredir contadores.

## Dependências

- Spec 41 (trilha unificada `platform_activity_log`) — detalhes do evento.
- Spec 20/39 (login/cadastro do portal) — pontos de integração.
- Spec 140 (rebrand) — código em `com.foxcash`, env `FOXCASH_*`, migration V77
  (a próxima após V76).

## Fora de escopo

- Bloqueio automático por fingerprint (decisão do usuário 2026-08-17: registrar
  como sinal de risco; sem bloqueio — mesmo padrão auto-suspend Spec 128).
- Página de cadastro no portal (cadastro hoje é via API direta).
- Fingerprint no master console / login do master.
- Detecção de bots por TLS/JA3 ou timing de requisição (é fingerprint de
  requisição, não de dispositivo).
