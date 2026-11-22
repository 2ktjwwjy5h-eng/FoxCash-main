# Spec 59 — Task: webhook flood (rate limit + log throttle)

- [x] 1. `WebhookSignatureFilter` ganhou rate limit por IP no TOPO do
      `doFilterInternal` (429 ANTES de ler o body/computar HMAC) usando o grupo
      `foxcash.security.rate-limit.webhook.*` (baseline 120/min/IP) + `clientIp`
      com trust-proxy (mesmo padrão do RateLimitFilter). **Decisão de
      implementação:** a checagem vive no `WebhookSignatureFilter` (e não no
      `RateLimitFilter`) — o design permitia as duas abordagens (KISS); colocar
      no próprio filtro garante 429-antes-do-HMAC independente da ordem de
      filtros do servlet, sem risco de dupla contagem no bucket.
- [x] 2. Ordem dos filtros validada por teste unitário
      (`webhookFloodIsCutWith429BeforeHmacCheck`): bucket esgotado → 429 mesmo
      SEM assinatura (que normalmente seria 401).
- [x] 3. `WebhookSignatureFilter`: throttle do WARN de assinatura inválida via
      `AtomicReference<Instant>` + `Clock` — no máximo 1 log a cada
      `foxcash.psp.webhook.log-throttle-seconds` (default 10s).
- [x] 4. `application.yml`: blocos `rate-limit.webhook` +
      `psp.webhook.log-throttle-seconds`.
- [x] 5. Testes (15 no `WebhookSignatureFilterTest`, +7 novos): flood mesmo IP →
      429; PSP legítimo abaixo do teto → 204; flood inválido → 401 com log
      throttled (10 inválidos → 1 linha; avança o intervalo → 2ª linha); 429
      antes do HMAC; bucket por IP e por rota; JSON `too_many_requests`.
- [x] 6. `mvn test` **1101/0/0** (sem regressão); E2E completo (regressão psp-flow).
- [x] 7. Smoke dev ao vivo (`NYXCASH_PSP_WEBHOOK_VERIFY_SIGNATURE=true`,
      teto webhook reduzido 5/min): **8 webhooks válidos → 5×204 + 3×429**;
      bucket esgotado → 429 mesmo sem assinatura (429 antes do HMAC).
- [x] 8. **Benchmark de calibração no Railway** (2026-08-08, k6
      `webhook-flood-calibration.js`, FLOOD=300 × 2 runs): **120×401 + 180×429**
      — split EXATO do teto **120/min/IP** com contador **agregado via Redis**
      (Spec 61/5c — se fosse por instância, ~60×429); **zero 5xx e zero erro de
      conexão**; p95 dos aceitos **522ms** (warm; 1º run 1.75s = JIT/cold). O
      **429 corta ANTES de ler o body/computar HMAC** — teto confirmado SEM
      bloquear reentrega legítima do PSP (reentrega em minutos). Integridade sob
      flood: **zero OOM**, health 200, sem restart loop. Valor final registrado
      no roadmap.
- [x] 9. Deploy Railway e verificação ao vivo (2026-08-08, junto com Spec 60):
      deploy SUCCESS com **2/2 réplicas**, healthcheck `[2/2] Healthcheck
      succeeded!` — health liveness/readiness 200 (fix 744c1f0: eximir
      `/actuator/health/**` do redirect HTTPS — o healthcheck do Railway sonda
      via HTTP puro e o `requiresChannel` respondia 302).
