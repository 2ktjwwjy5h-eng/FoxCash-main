# Spec 55 — Task: health sem falso-negativo

- [ ] 1. Adicionar `mail.enabled: ${NYX_MAIL_HEALTH_ENABLED:false}` ao bloco
      `management.endpoint.health` do `application.yml`.
- [ ] 2. Validar ao vivo (app dev sem SMTP) qual propriedade remove o
      componente `mail` do `/actuator/health` (`management.endpoint.health.mail.enabled`
      vs `management.health.mail.enabled`) e fixar a que funciona.
- [ ] 3. Teste de contexto: `/actuator/health` sem SMTP → 200 UP, sem `mail`
      DOWN (teste existente de health/actuator atualizado ou novo).
- [ ] 4. `mvn test` verde (sem regressão).
- [ ] 5. Deploy em Railway e verificação ao vivo: health 200 UP, sem `mail`;
      liveness/readiness 200.
