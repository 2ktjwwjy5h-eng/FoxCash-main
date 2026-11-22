# Spec 55 — Design: health sem falso-negativo

## Diagnóstico (produção Railway, 2026-08-07)

`spring-boot-starter-mail` (Spec 45) coloca `MailHealthIndicator` no classpath.
O auto-config do Spring Boot cria o `JavaMailSender` mesmo sem SMTP configurado
(o `spring.mail.host` default é `localhost`). O `MailHealthIndicator` tenta
`localhost:587` → `MailConnectException` → componente `mail` DOWN → health
agregado DOWN → `/actuator/health` sem auth responde **503** (status do grupo
DOWN). Liveness/readiness não incluem `mail` → seguem UP.

## Decisão

SMTP é infra opcional (Spec 45: "e-mail quebrado não derruba o gateway"). O
indicador de mail **não deve derrubar o health agregado**. Desligar por
default, com opt-in explícito.

## Mudanças

### `src/main/resources/application.yml` (bloco `management`)

```yaml
management:
  health:
    # Spec 55: SMTP é infra opcional — o indicador de mail não derruba o
    # health agregado (falso-negativo em produção sem SMTP). Opt-in via
    # NYX_MAIL_HEALTH_ENABLED=true quando o SMTP estiver configurado. Mesma
    # mecânica do management.health.redis.enabled (ADR-010).
    mail:
      enabled: ${NYX_MAIL_HEALTH_ENABLED:false}
```

Nota: no Spring Boot 3.x os indicadores individuais são controlados por
`management.health.<nome>.enabled` (`HealthContributorAutoConfiguration`) — o
mesmo padrão já usado no projeto para o Redis
(`management.health.redis.enabled: false`). O `MailHealthIndicator` é um bean
condicional que respeita essa propriedade.

### Testes

- Teste de contexto (ou ajuste em teste existente): boot do app (profile dev,
  sem SMTP) → `GET /actuator/health` (via MockMvc ou contexto) → status UP e
  componentes sem `mail` DOWN.
- Teste opt-in: com a propriedade `true`, o indicador `mail` aparece no health
  (pode usar um teste de propriedade/contexto com `@SpringBootTest(properties=...)`).

## Arquivos

| Arquivo | Ação |
|---|---|
| `src/main/resources/application.yml` | editar bloco `management.health` |
| `src/test/java/...` (teste de health) | novo/ajustado |
