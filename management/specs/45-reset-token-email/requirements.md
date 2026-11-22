---
name: Entrega do token de reset por e-mail (Spec 40 follow-up)
status: audited
references:
  - "management/vision.md#V28"
  - "management/specs/40-password-reset/requirements.md"
---

# Spec 45 — Entrega do token de reset por e-mail (Spec 40 follow-up)

## O que entrega

Fecha a recuperação de senha em **produção** (pendência documentada da Spec
40): hoje o token `rst_<32 hex>` é gerado mas **não é entregue** — em prod
`foxcash.auth.expose-reset-token=false` e o `forgot-password` responde sempre
**204** (anti-enumeração), o que torna a recuperação inutilizável para o
lojista real. Esta spec adiciona a **entrega do token por e-mail** ao endereço
da conta, **sem quebrar a anti-enumeração** nem expor o token em qualquer
resposta HTTP.

- **Porta de entrega:** `ResetTokenDeliverer` com adapter **SMTP** (JavaMail)
  e fallback **log** (dev/testes) — mesmo padrão do `MerchantNotifier`
  (Spec 7/35).
- **Fluxo:** `forgot-password` (público, rate limit por IP) → se o e-mail
  existe, gera o token (Spec 40) e **chama o deliverer** (mesmo processo;
  sem fila nesta versão — ver decisão no design); a resposta HTTP continua
  **sempre 204** (existente ou não).
- **Conteúdo do e-mail:** link/mensagem com o token one-time (`rst_<32 hex>`)
  e instruções; o token expira em `reset-token-ttl-seconds` (30min) e é
  single-use (Spec 40).
- **Configuração:** `foxcash.notification.reset-token.email` (SMTP host/port/
  auth/from) via env vars — **sem default em prod** (fail-fast? — decisão no
  design: recomenda-se não falhar o boot, mas logar erro na entrega, para o
  gateway não cair por e-mail quebrado); em dev, `deliverer=log` (o token
  continua exposto na resposta apenas em dev via `expose-reset-token`).

## Critérios de aceite

- `POST /forgot-password` com e-mail existente → **204** e e-mail entregue
  (SMTP real no E2E opcional; unit com mock do remetente); com e-mail
  inexistente → **204** e **nenhum** e-mail (anti-enumeração mantida — tempo
  de resposta uniforme: o caminho inexistente executa um passo "dummy" de
  entrega, padrão do dummy BCrypt).
- Token nunca aparece em resposta HTTP (prod); só no corpo do e-mail.
- **Em produção, `deliverer=log` é rejeitado no boot (fail-fast)** — o token
  de reset é credencial de conta e nunca pode ir para logs de produção; o
  default de prod é `smtp` (credenciais SMTP ausentes **não** derrubam o boot:
  a falha é logada na entrega, mantendo a disponibilidade do gateway).
- Falha do SMTP (indisponível/credencial) → **o pedido ainda responde 204**
  (não vaza existência nem derruba o fluxo) e o erro é logado; re-tentativa
  de entrega (job? — decisão no design) cobre falhas transitórias.
- Rate limit do `forgot-password` mantido (Spec 40/B-02) — sem spam de
  e-mails por IP.
- `mvn test` verde (service com mock do deliverer + anti-enumeração por
  timing) e E2E 22/22 sem regressão (dev continua expondo o token na resposta).

## Dependências

- `management/specs/40-password-reset/requirements.md` (fluxo do token)
- `management/specs/7-merchant-notification/requirements.md` (padrão de
  entrega/fallback log)
- JavaMail (`org.springframework.boot:spring-boot-starter-mail`)
