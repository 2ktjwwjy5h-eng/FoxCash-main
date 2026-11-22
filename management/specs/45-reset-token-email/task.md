# Spec 45 — Task breakdown

Status: `audited` (2026-08-06). Ciclo SDD completo.

## Implementação

- [x] Porta `ResetTokenDeliverer` (domain/port/out) + `LoggingResetTokenDeliverer`
      (dev) + `SmtpResetTokenDeliverer` (starter-mail, JavaMailSender).
- [x] `NotificationProperties` — bloco `reset-token` (`deliverer` default
      `log`; `email.*` host/port/username/password/from; `base-url` do link)
      + `validate()`: rejeita `deliverer=log` no profile `prod` (fail-fast de
      segurança — o token de reset nunca vai para logs de produção).
- [x] `PasswordResetService.forgot`: existente → gera token + entrega;
      inexistente → entrega dummy (uniformiza timing); resposta 204 sempre
      (prod); falha do SMTP → loga e segue 204 (disponibilidade).
- [x] `application-dev.yml`/`application-prod.yml` + README/runbook (env
      `NYX_RESET_TOKEN_SMTP_*`, `NYX_RESET_TOKEN_DELIVERER`,
      `foxcash.notification.reset-token.deliverer`).
- [x] Testes: `PasswordResetServiceTest` (+3: entrega existente/dummy
      inexistente/falha SMTP → token preservado), `SmtpResetTokenDelivererTest`
      (+4: link com token, URL-encode do e-mail, SMTP não configurado, falha do
      sender), `NotificationPropertiesTest` (+4: fail-fast log-em-prod).

## Verificação

- [x] `mvn test` verde (**686/0/0** — 11 novos); dev continua
      `expose-reset-token=true` + deliverer log.
- [x] Smoke ao vivo (dev, porta 8081): forgot-password existente → 200 com
      token (`rst_...`) + reset-password → 204; inexistente → 204 (dummy).
