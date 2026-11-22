---
name: Health sem falso-negativo — indicador de mail desabilitado por default
status: audited
references:
  - "management/vision.md#V35"
  - "management/specs/45-reset-token-email/requirements.md"
  - "management/specs/53-job-robustez/requirements.md"
---

# Spec 55 — Health sem falso-negativo (indicador de mail)

## O que entrega

Corrige o **falso-negativo no health agregado** observado na verificação de
produção (Railway, 2026-08-07): `/actuator/health` respondia **DOWN (503)**
porque o `MailHealthIndicator` (auto-configurado pelo `spring-boot-starter-mail`
da Spec 45) tentava conectar em `localhost:587` sem SMTP configurado — embora
liveness/readiness permanecessem UP e o serviço estivesse saudável.

A correção desabilita o indicador de mail **por default**
(`management.health.mail.enabled: ${NYX_MAIL_HEALTH_ENABLED:false}` — mesma
mecânica do `management.health.redis.enabled: false` já existente para o Redis
opcional, ADR-010): SMTP é infra **opcional** (Spec 45 — decisão de
disponibilidade: e-mail quebrado não derruba o gateway) e, portanto, não deve
derrubar o health agregado nem produzir 503 no probe público. O indicador pode
ser religado explicitamente via env `NYX_MAIL_HEALTH_ENABLED=true` quando o
SMTP estiver realmente configurado.

## Critérios de aceite

1. `GET /actuator/health` (sem auth) → **200 UP** em produção sem SMTP
   configurado — nenhum componente DOWN.
2. `GET /actuator/health` (com `X-Api-Key` admin) → `components` **sem `mail`**
   (ou UP), `db`/`diskSpace`/`ping`/`livenessState`/`readinessState` UP.
3. `GET /actuator/health/liveness` e `/actuator/health/readiness` → **200 UP**
   (inalterado).
4. Boot com `NYX_MAIL_HEALTH_ENABLED=true` → indicador de mail presente no
   health (comportamento opt-in).
5. `mvn test` sem regressão; E2E sem regressão.
