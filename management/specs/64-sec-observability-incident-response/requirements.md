---
name: Observabilidade de segurança, alerting e resposta a incidentes
status: audited
references:
  - "management/vision.md#V44"
  - "management/adr.md#ADR-015"
  - "management/specs/35-outbound-webhook/requirements.md"
  - "management/specs/41-master-profiles-activity/requirements.md"
  - "management/specs/53-job-robustez/requirements.md"
  - "management/specs/54-outbox-dead-alert/requirements.md"
---

# Spec 64 — Observabilidade de segurança, alerting e resposta a incidentes

## O que entrega

O projeto já mede muito (métricas Prometheus — Spec 53; trilha de atividades —
Spec 41; tracing — Spec 57; `job_runs` — Spec 53) e já alerta falhas de job e
outbox morto via **webhook de operador** (Specs 53/54). O que falta é **segurança
observável e acionável**: sem alerta, picos de 401/403, 429 em massa, falha de
assinatura de webhook ou fail-open do rate limit (Spec 43/ADR-010) passam
despercebidos até virarem indisponibilidade — e, quando acontecem, não há um
**procedimento** de resposta. Esta spec entrega, **sem SIEM novo** (reuso do que
existe):

1. **Eventos de segurança no webhook de operador**: publicar eventos de
   `SecurityAlertType` no outbox (Spec 35/53) quando um **limiar de segurança**
   for atingido — mesmo pipeline e assinatura do `JOB_FAILED`/`OUTBOX_DEAD`
   (Specs 53/54). Alertas iniciais:
   - `LOGIN_FAILURE_BURST` — N falhas de login (lojista/master/portal) em
     janela curta (evidência: contador de falhas real, ex. `platform_activity_log`);
   - `RATE_LIMIT_BURST` — volume alto de 429 num grupo/rota em janela curta;
   - `SIGNATURE_FAILURE_BURST` — N webhooks rejeitados por assinatura inválida
     em janela curta (indício de replay/flood);
   - `RATE_LIMIT_FAIL_OPEN` — fail-open do `RedisRateLimitStore` ativado
     (Redis indisponível — proteção degradada, ADR-010).
   Cada alerta é **throttled** (1 por janela configurável — anti-spam, mesmo
   padrão do `WebhookSignatureFilter` Spec 59) e carrega contexto mascarado
   (nunca senha/token/segredo/chave Pix).
2. **Alerting nas métricas (opcional, sem infra nova obrigatória)**: canal via
   **Grafana Cloud (free tier)** com scrape autenticado do
   `/actuator/prometheus` (Spec 53) + regras de alerta para disponibilidade e
   saturação: 5xx rate, readiness DOWN, Hikari exhaustion, heap alto, outbox
   estagnado (PENDING não drena). Documentado e configurável — não bloqueia os
   critérios da spec se não for usado.
3. **Runbook de resposta a incidente de segurança**: procedimento
   **detectar → conter → mitigar → restaurar** para as classes de ataque que o
   projeto enfrenta (flood/DoS, automação de conta/brute-force, webhook replay/
   spoofing, vazamento de chave/secredo, abuso de lojista), reusando os runbooks
   validados (backup/restore `ops/`, HA/redeploy `deploy/railway.md`, edge
   `deploy/edge.md` Spec 63) — com **teste de mesa** e responsáveis/passos
   executáveis por um operador/agente.
4. **Retenção e busca de logs**: documentar a janela de retenção do Railway e a
   opção de **log drain** (ex.: Axiom/Loki, free tier) para investigação
   pós-incidente; mínimo exigido: saber onde buscar `X-Request-Id` e como cruzar
   com `platform_activity_log`/métricas.

**Fora de escopo:** SIEM/comercial (Splunk etc.), autenticação adaptativa/
step-up (proposta futura), gestão de segredos (proposta futura), mudança de
contrato de API. Os alertas são **indicadores** — a decisão/ação continua sendo
do operador (mesma filosofia da Spec 16).

## Critérios de aceite

1. **Alerta de login em massa**: gerar N falhas de login do portal em janela
   curta (abaixo do lockout, para não travar conta) → **1 evento
   `LOGIN_FAILURE_BURST`** no outbox com destino = webhook de operador
   (evidência: linha no outbox com `type=LOGIN_FAILURE_BURST`, entrega assinada
   ao webhook do operador ou fallback log quando não configurado).
2. **Alerta de 429 em massa**: rajada acima do teto de um grupo de rate limit →
   **1 `RATE_LIMIT_BURST`** throttled (repetição da rajada dentro da janela não
   gera outro evento).
3. **Alerta de assinatura inválida**: N webhooks rejeitados por HMAC inválido
   em janela curta → **1 `SIGNATURE_FAILURE_BURST`**; abaixo do limiar → nenhum
   evento (sem spam).
4. **Alerta de fail-open do rate limit**: com `store=redis` e Redis
   indisponível → **1 `RATE_LIMIT_FAIL_OPEN`** (e o log WARN já existente
   permanece); Redis de volta → opcionalmente 1 evento de recuperação ou
   silêncio (documentar a escolha).
5. **Contrato/privacidade**: payload dos alertas com dados mascarados (sem
   senha/token/segredo/chave/documento/email em claro — mesmas regras do
   `SensitiveDataLoggingFilter`); eventos do `platform_activity_log` intactos.
6. **Grafana Cloud (se usado)**: scrape autenticado do `/actuator/prometheus`
   funciona; regras de alerta (5xx, readiness, Hikari, outbox estagnado)
   disparam no limiar e resolvem — com o **acesso read-only** do scrape (não
   expor admin por scrape).
7. **Runbook**: `ops/security-incident.md` cobre detectar/conter/mitigar/
   restaurar para as 5 classes de ataque, referenciando os runbooks existentes;
   **teste de mesa** executado (ataque simulado → passos do runbook aplicados e
   registrados).
8. **Logs investigáveis**: documentada a janela de retenção + como buscar por
   `X-Request-Id` e cruzar com trilha/métricas (e opcionalmente log drain
   configurado).
9. `mvn test` sem regressão + E2E sem regressão (novos eventos/throttle não
   alteram contrato; alertas são fail-safe — sem webhook de operador → fallback
   log).

## Filosofia

- **Reuso antes de infra nova**: o webhook de operador (Specs 53/54) e as
  métricas (Spec 53) já existem — a spec os transforma em segurança acionável
  sem SIEM, sem agente, sem servidor novo. Grafana Cloud é **opcional** (não
  bloqueia).
- **Throttle e anti-spam**: alerta é exceção, não ruído — 1 por janela; abaixo
  do limiar não emite; o próprio ataque não gera cascata (padrão anti-cascata
  do `OUTBOX_DEAD` — Spec 54).
- **Fail-safe**: sem webhook de operador configurado → fallback log (mesmo do
  `HttpMerchantNotifier`); alerta nunca derruba nem degrada o caminho da venda.
- **Indicador, não ação automática**: alertar é informar o operador; decisões
  (bloquear lojista, invalidar chave, mitigar IP) continuam humanas/auditadas
  (Specs 16/37/41) — sem auto-ação agressiva em produção.

## Dependências

- `management/adr.md` (ADR-015 — decisão de observabilidade de segurança)
- `management/specs/35-outbound-webhook/requirements.md` (outbox + entrega
  assinada — base do pipeline de alerta)
- `management/specs/53-job-robustez/requirements.md` (métricas + webhook de
  operador — base dos critérios 1-4 e 6)
- `management/specs/54-outbox-dead-alert/requirements.md` (evento de plataforma
  + anti-cascata — padrão dos alertas)
- `management/specs/41-master-profiles-activity/requirements.md`
  (`platform_activity_log` — fonte dos contadores de falha de login)
- `management/specs/63-edge-waf-bot-mitigation/requirements.md` (edge — as
  classes de ataque do runbook incluem o que o edge bloqueia)
