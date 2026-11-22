# Spec 59 — Design: webhook flood (rate limit + log throttle)

## Contexto

`/webhooks/pix` e `/webhooks/payouts` são públicos (permitAll) — o
`WebhookSignatureFilter` valida HMAC (401 sem/válida) mas **não há rate limit**.
O `RateLimitFilter` (Spec 43) não cobre esses paths. Flood com segredo válido =
processamento em série no lock `FOR UPDATE` (custo DB/threads); flood inválido =
WARN por request (log spam).

## Decisões

1. **Rate limit por IP nos webhooks**: estender o `RateLimitFilter` com um
   terceiro grupo `webhook` (`foxcash.security.rate-limit.webhook.*`):
   `max-requests` **baseline 120/min por IP** (reentrega do Pagar.me é em
   minutos — folga grande), `window-seconds` 60. A chave segue `path|IP`
   (mesmo contrato 429). **Baseline — calibrar no Railway** (benchmark: medir
   o throughput sustentável de webhooks válidos/inválidos e ajustar para cortar
   com 429 antes da saturação de CPU/DB).
2. **Ordem vs HMAC**: o `RateLimitFilter` já é registrado antes dos filtros de
   auth na cadeia (`addFilterBefore`, verificado no código). O
   `WebhookSignatureFilter` é um `@Component` `OncePerRequestFilter` — registrado
   automaticamente no container e **executado antes da cadeia de segurança**
   (filtros de servlet rodam antes do `DelegatingFilterProxy`). Para que o rate
   limit do webhook rode **antes** do HMAC (não gastar CPU de HMAC no flood
   além do teto), a checagem de rate limit dos paths `/webhooks/*` deve ser
   feita no próprio `WebhookSignatureFilter` (topo do `doFilterInternal`, antes
   do `readNBytes`/HMAC) OU via `@Order` menor no `RateLimitFilter` — validar no
   teste de integração e escolher a abordagem mais simples (KISS). O importante:
   **429 antes do HMAC** para flood, **204 após HMAC** para legítimo.
3. **Throttle de log**: no `WebhookSignatureFilter.doFilterInternal`, o WARN de
   assinatura inválida passa a usar um `AtomicReference<Instant>` de último
   log — loga no máximo 1 WARN a cada `webhook.log-throttle-seconds` (default
   10s); os demais são descartados (contador em memória opcional). Mensagem
   mantém path/header para diagnóstico.
4. **Config** (`application.yml`):
   ```yaml
   foxcash.security.rate-limit.webhook:
     enabled: true
     max-requests: 120
     window-seconds: 60
   foxcash.psp.webhook.log-throttle-seconds: 10
   ```

## Arquivos

| Arquivo | Ação |
|---|---|
| `src/main/java/com/foxcash/infrastructure/security/RateLimitFilter.java` | paths webhook + grupo config |
| `src/main/java/com/foxcash/infrastructure/security/RateLimitProperties.java` | bloco `webhook` |
| `src/main/java/com/foxcash/infrastructure/security/WebhookSignatureFilter.java` | throttle de log |
| `src/main/resources/application.yml` | config nova |
| testes: `RateLimitFilterTest` (webhook 429), `WebhookSignatureFilterTest` (throttle) | novos |
