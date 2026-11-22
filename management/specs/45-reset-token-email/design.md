# Spec 45 — Design: Entrega do token de reset por e-mail (Spec 40 follow-up)

## Contexto

A Spec 40 entregou o fluxo de token mas a **entrega em produção** ficou como
pendência (sem infra de e-mail). Esta spec implementa a entrega por e-mail
mantendo a resposta sempre 204 (anti-enumeração) e sem quebrar o boot por
falha de e-mail.

## Decisões de arquitetura

### 1. Porta `ResetTokenDeliverer` + adapters

- `domain/port/out/ResetTokenDeliverer`: `void deliver(String email, String
  resetToken, Instant expiresAt)` (lança em falha — o chamador decide).
- `LoggingResetTokenDeliverer` (default, dev): loga o token (nível INFO só em
  dev — o `expose-reset-token` de dev já cobre o teste; em prod com
  `deliverer=log` o token vai para o log sanitizado? — decisão: o adapter log
  é **só dev**; em prod exige SMTP).
- `SmtpResetTokenDeliverer` (`spring-boot-starter-mail`): monta e-mail
  (assunto "FoxCash — redefinição de senha", corpo com o link
  `https://<host>/reset?token=<rst_...>&email=<escaped>` — o link real do
  frontend fica configurado `foxcash.portal.base-url`), envia via
  `JavaMailSender`. Config: `foxcash.notification.reset-token.email.*`
  (host/port/username/password/from) via env `NYX_RESET_TOKEN_SMTP_*`.

### 2. Seleção por config — sem fail-hard

- `foxcash.notification.reset-token.deliverer` = `log` (default dev) |
  `smtp`. Em prod, o `application-prod.yml` define `smtp` com env vars; **sem
  env vars o boot não falha** — o adapter SMTP instancia com credenciais
  ausentes e a entrega loga erro (o gateway nunca cai por e-mail quebrado;
  decisão de disponibilidade, documentada no runbook).
- **Fail-fast de segurança:** `NotificationProperties.validate()` rejeita
  `deliverer=log` quando o profile ativo é `prod` (ou quando
  `foxcash.notification.reset-token.deliverer` for explicitamente `log` com
  `require-https=true`) — o token de reset é credencial de conta e nunca vai
  para logs de produção. O `log` deliverer fica restrito a dev/testes.

### 3. Anti-enumeração e timing

- `PasswordResetService.forgot`: e-mail existente → gera token + entrega;
  e-mail inexistente → executa `deliverer.deliver("dummy@foxcash.invalid",
  "rst_<dummy>", now+ttl)` com destino log (uniformiza o tempo — padrão do
  dummy BCrypt). A resposta é sempre 204 no controller (prod).

### 4. Confiabilidade da entrega

- Entrega **síncrona** no request (KISS — sem fila nesta versão): falha do
  SMTP é logada e o pedido segue 204 (o lojista tenta de novo; o token antigo
  é invalidado pelo novo pedido — Spec 40). Retry/backoff de entrega fica
  como evolução (estender o `notification_outbox` para o evento de reset —
  decisão adiada, registrada no task).

### 5. Segurança

- Token nunca em resposta HTTP (prod); só no e-mail.
- Corpo do e-mail: token em texto; recomendação de "ignore este e-mail se não
  foi você" (sem informação de existência de conta para terceiros — o e-mail
  só é enviado para o endereço da conta).
- Rate limit do `forgot-password` (Spec 40/B-02) limita spam por IP.

## Arquivos (visão geral)

- `domain/port/out/ResetTokenDeliverer` + `infrastructure/notification/
  LoggingResetTokenDeliverer`, `SmtpResetTokenDeliverer`
- `application/service/PasswordResetService` (chama o deliverer + dummy)
- `infrastructure/config/NotificationProperties` (bloco reset-token)
- `application-prod.yml`/`application-dev.yml` (deliverer + SMTP env)
- `pom.xml` (starter-mail)
- Testes: `PasswordResetServiceTest` (entrega chamada p/ existente; dummy p/
  inexistente; falha do SMTP não vaza 204), `SmtpResetTokenDelivererTest`
  (GreenMail ou mock do JavaMailSender)
