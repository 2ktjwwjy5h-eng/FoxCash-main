# Spec 64 — Design: Observabilidade de segurança, alerting e resposta a incidentes

## Contexto

- **O que já existe** (reuso, sem infra nova):
  - Outbox + entrega assinada: `notification_outbox` (Spec 35), dispatcher com
    batch/backoff/watchdog (Spec 53), evento de plataforma (merchant=null)
    entregue ao **webhook de operador** com fallback log (Spec 53/54).
  - `WebhookEventType` enum: `JOB_FAILED`, `OUTBOX_DEAD` (Specs 53/54) — novos
    tipos de segurança entram aqui.
  - Publicação de evento de plataforma: `eventPublisher.publish(null, type, data)`
    (padrão do `JobExecutionService.publish(...JOB_FAILED...)`).
  - Métricas: `/actuator/prometheus` (`nyx_*` — Spec 53), health/readiness
    (Spec 55).
  - Trilha: `platform_activity_log` (Spec 41) — registra `LOGIN_SUCCESS`,
    `LOGIN_FAILED`, `LOCKED`, `MFA_*`, etc.
  - Throttle de log: `AtomicReference<Instant>` + `Clock` no
    `WebhookSignatureFilter` (Spec 59) — mesmo padrão para throttle de alerta.
- **Gap**: sem alerta de segurança acionável e sem procedimento de resposta.

## Decisões

### 1. Tipos de alerta (`WebhookEventType` + payload)

Adicionar ao enum `WebhookEventType` (novos tipos de plataforma, merchant=null):

| Tipo | Disparo | Payload (mascarado) |
|---|---|---|
| `LOGIN_FAILURE_BURST` | ≥ N falhas de login em janela (default 10 em 60s) | `{windowSeconds, count, source}` (sem e-mail/documento) |
| `RATE_LIMIT_BURST` | ≥ N × teto do grupo em janela (429 contados por grupo/rota) | `{windowSeconds, count, group}` |
| `SIGNATURE_FAILURE_BURST` | ≥ N webhooks com HMAC inválido em janela (default 20 em 60s) | `{windowSeconds, count, path}` |
| `RATE_LIMIT_FAIL_OPEN` | fail-open do `RedisRateLimitStore` ativado (Redis down) | `{reason}` |

Limiares em `foxcash.security.alert.*` (env override, default documentado).
**Throttle**: 1 evento por tipo por janela maior (default 300s) — `AtomicReference`
+ `Clock` (padrão Spec 59); anti-cascata: um alerta que falha na entrega **não**
gera outro alerta (mesma regra do `OUTBOX_DEAD`, Spec 54).

### 2. Fontes dos contadores (onde medir)

- **Login**: `PlatformActivityRecorder` (Spec 41) já grava `LOGIN_FAILED`/
  `LOGIN_SUCCESS`/`LOCKED`. O contador de burst conta `LOGIN_FAILED`+`LOCKED` por
  janela — via um interceptor no recorder (sem mudar a trilha) ou consulta
  agregada (design da implementação decide; preferir contador em memória por
  instância com janela — aceitável, o alerta é indicativo, não autoritativo).
- **429**: `RateLimitFilter`/`WebhookSignatureFilter` (Specs 43/58/59) — ponto
  único onde o 429 é emitido; incrementar contador por grupo/rota.
- **Assinatura**: `WebhookSignatureFilter` — ponto do 401 por HMAC inválido.
- **Fail-open**: `RedisRateLimitStore` (Spec 43) — onde o WARN fail-open já sai.

### 3. Publicação (mesmo pipeline do JOB_FAILED)

```
contador (janela) → limiar → SecurityAlertPublisher.publish(type, data)
  → eventPublisher.publish(null, type, data)  [outbox, mesma tx]
  → dispatcher (Spec 53) → HttpMerchantNotifier (evento de plataforma)
  → webhook de operador (HMAC-SHA256, X-FoxCash-Signature) | fallback log
```

- O publisher é chamado de pontos **fora da transação da venda** (contadores em
  memória; publicação no outbox em tx própria se necessário — o padrão
  `REQUIRES_NEW` da Spec 28/42 para não afetar o caminho crítico).
- Sem webhook de operador configurado → **fallback log** (fail-open, Spec 53).

### 4. Alerting via Grafana Cloud (opcional — não bloqueia)

- Scrape autenticado do `/actuator/prometheus`: o endpoint exige ROLE_ADMIN
  (Spec 53). Opção: **token de scrape dedicado** (novo header/credencial
  configurável) OU usar o `X-Api-Key` admin no scrape job do Grafana Cloud
  (documentar em runbook — o scrape usa header customizado, sem expor a chave
  em URL).
- Regras sugeridas (Grafana): `rate(http_server_requests_seconds_count{status=~"5.."}[5m]) > 0.01`;
  readiness UP==0; `hikaricp_connections_pending > 0` por 5m;
  `jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes > 0.9`;
  outbox estagnado (via métrica `nyx_*` do dispatcher, Spec 53).
- Notificações → Slack/Discord/e-mail do Grafana Cloud (conta free).

### 5. Runbook `ops/security-incident.md`

Estrutura por classe de ataque (detectar → conter → mitigar → restaurar),
reusando runbooks existentes (`ops/backup-restore.md`, `deploy/railway.md`,
`deploy/edge.md` Spec 63):

| Classe | Detectar | Conter | Mitigar | Restaurar |
|---|---|---|---|---|
| Flood/DoS | alertas 429/edge, 5xx, Hikari | edge rate limit/WAF (Spec 63), escalar? | bloquear IP/ASN na borda | remover bloqueio, verificar health |
| Automação de conta / brute-force | `LOGIN_FAILURE_BURST`, lockout | Turnstile (Spec 63), suspender cadastro? | invalidar chaves suspeitas (Spec 24) | liberar legítimos (auditoria Spec 37/41) |
| Webhook replay/spoof | `SIGNATURE_FAILURE_BURST` | verificar segredo (Spec 35), revogar | investigar origem (IP/edge) | restaurar segredo, re-testar |
| Chave/segredo vazado | trilha, uso anômalo | rotacionar env vars, revogar API key | auditoria de acesso | rodar backup/restore se necessário |
| Abuso de lojista | trilha + dashboards | suspender (Spec 16), bloquear saque | investigar transações/ledger | decisão do operador, auditoria |

- **Teste de mesa**: cada classe simulada → operador executa os passos → gaps
  corrigidos no runbook (registrar na task).
- **Comunicação**: quem/onde notificar (operador via webhook; documento com
  contatos e `X-Request-Id` como chave de investigação).

### 6. Retenção/busca de logs

- Documentar a janela do Railway (drain/UI) e o caminho de investigação:
  `X-Request-Id` → logs do request → `platform_activity_log` → métricas.
- Log drain opcional (ex.: Axiom free tier) via Railway → documentado no
  runbook; não obrigatório.

## Arquivos

| Arquivo | Ação |
|---|---|
| `WebhookEventType` (domain/model/notification) | +4 tipos de segurança (LOGIN_FAILURE_BURST, RATE_LIMIT_BURST, SIGNATURE_FAILURE_BURST, RATE_LIMIT_FAIL_OPEN) |
| `SecurityAlertPublisher` (novo, application) | throttle por tipo + publicação no outbox (padrão Spec 53) |
| `SecurityAlertProperties` (novo, config) | limiares/janelas por tipo (`foxcash.security.alert.*`) |
| `RateLimitFilter` / `WebhookSignatureFilter` / `RedisRateLimitStore` | contadores + chamada do publisher nos pontos de disparo |
| `PlatformActivityRecorder` | contador de falhas de login (burst) |
| `application.yml` / prod | `foxcash.security.alert.*` (defaults) + webhook de operador já existente |
| `ops/security-incident.md` (novo) | runbook de resposta + teste de mesa |
| `deploy/railway.md` | nota: log drain opcional + investigação |
| `management/specs/64-sec-observability-incident-response/` | documentos da spec |

## Fora de escopo

- SIEM/comercial; autenticação adaptativa/step-up; gestão de segredos —
  propostas futuras (roadmap §Fase 17).
- Ação automática de mitigação (bloquear conta/IP sem operador) — alerta é
  indicador; decisão é humana/auditada (Specs 16/37/41).
- Mudança de contrato de API.
