# Spec 64 — Task: Observabilidade de segurança, alerting e resposta a incidentes

Checklist de implementação. Princípio: **reuso do que existe (outbox/webhook de
operador/métricas), throttle anti-spam, fail-safe (sem webhook → fallback log),
alerta é indicador (decisão humana).**

## Fase A — Eventos de segurança (código)

- [x] 1. `WebhookEventType` +4 tipos (LOGIN_FAILURE_BURST, RATE_LIMIT_BURST,
      SIGNATURE_FAILURE_BURST, RATE_LIMIT_FAIL_OPEN). **Evidência:** enum
      compila; testes do enum/contrato atualizados.
- [x] 2. `SecurityAlertProperties` (`foxcash.security.alert.*`): limiares e
      janelas por tipo + janela de throttle (default 300s), env override.
      **Evidência:** `@ConfigurationProperties` carrega; defaults documentados.
- [x] 3. `SecurityAlertPublisher`: throttle por tipo (`AtomicReference`+`Clock`,
      padrão Spec 59) + `eventPublisher.publish(null, type, data)` no outbox
      (payload mascarado). **Evidência:** unit tests — dispara no limiar, NÃO
      re-dispara na janela (throttle), dados sem segredo/email/documento.
- [x] 4. **Contadores nos pontos de disparo** (janela em memória por instância):
      - `RateLimitFilter` → contagem de 429 por grupo (RATE_LIMIT_BURST);
      - `WebhookSignatureFilter` → contagem de HMAC inválido
        (SIGNATURE_FAILURE_BURST);
      - `RedisRateLimitStore` → fail-open (RATE_LIMIT_FAIL_OPEN, 1 evento ao
        ativar — sem re-alertar a cada request);
      - `PlatformActivityRecorder` → falhas de login (`LOGIN_FAILED`+`LOCKED`)
        (LOGIN_FAILURE_BURST).
      **Evidência:** tests por ponto (janela fecha, limiar dispara 1x).
- [x] 5. Publicação **fora da transação da venda** (padrão `REQUIRES_NEW`/
      transação própria — Spec 28/42) — o alerta nunca afeta o caminho crítico.
      **Evidência:** smoke: fluxo de venda normal não emite alerta; simulação
      de rajada emite 1 evento (outbox type correto, merchant=null).
- [x] 6. **Smoke ao vivo (dev)**: rajada de logins inválidos → 1
      LOGIN_FAILURE_BURST no outbox → fallback log (sem webhook de operador em
      dev) com contexto mascarado. **Evidência:** log/banco.

## Fase B — Runbook e operação

- [x] 7. **Runbook `ops/security-incident.md`**: detectar→conter→mitigar→
      restaurar para as 5 classes (flood/DoS, automação/brute-force, webhook
      replay/spoof, chave vazada, abuso de lojista), reusando backup/restore +
      HA + edge (Spec 63) + trilha (Spec 41). **Evidência:** documento completo
      com passos executáveis e responsáveis.
- [x] 8. **Teste de mesa**: pelo menos 2 classes simuladas (flood e chave
      vazada) — operador executa os passos do runbook e registra gaps corrigidos.
      **Evidência:** seção "teste de mesa" no runbook.
- [x] 9. **Retenção/busca de logs documentada**: janela do Railway + caminho de
      investigação por `X-Request-Id` (logs → `platform_activity_log` →
      métricas) + opção de log drain (Axiom/Loki) anotada no runbook.
      **Evidência:** seção no runbook/README.

## Fase C — Alerting via métricas (opcional, não bloqueia)

- [x] 10. **Grafana Cloud (free)**: scrape autenticado do
       `/actuator/prometheus` (credencial read-only — header customizado, não
       na URL). **Evidência:** dashboard com as métricas `nyx_*` e do Tomcat/
       Hikari/JVM chegando.
- [x] 11. **Regras de alerta**: 5xx rate, readiness DOWN, Hikari pending,
       heap > 90%, outbox estagnado — com notificação (Slack/Discord/e-mail).
       **Evidência:** regras disparam no limiar e resolvem (teste manual).
- [x] 12. `mvn test` sem regressão + E2E sem regressão. **Evidência:** suíte
       completa verde (critério 9).
- [x] 13. Atualizar roadmap (`created` → `implemented`) + commit. Fim do ciclo
       para `/spec-audit`.
