# Spec 58 — Design: rate limit por usuário/chave e cobertura ampliada

## Contexto

`RateLimitFilter` (Spec 18/43) limita por `path|IP` apenas 5 rotas públicas
(cadastro, logins, forgot/reset, storefront GET, purchase). Rotas autenticadas
(`/nyx/pix/cob`, `/transactions`, `/portal/**`, `/master/**`, `/admin/**`) não
têm limite por usuário. Achado CRÍTICO: 1 lojista com chave pode gerar carga
ilimitada.

## Decisões

1. **Filtro novo** `AuthenticatedRateLimitFilter` (não modificar o `RateLimitFilter`
   — single responsibility, KISS): mesmo padrão (janela fixa, `RateLimitStore`,
   429 `too_many_requests`), mas a chave é `path|principal`.
2. **Principal** = `SecurityContext` resolvido pelos filtros de auth
   (`ApiKeyAuthFilter` → merchantId/admin, `MasterAuthFilter` → masterId,
   `MerchantPortalAuthFilter` → merchantId). **Ordem dos filtros (verificado no
   código):** a cadeia registra `requestId → rateLimit → masterAuth →
   merchantPortal → apiKey`, todos com `addFilterBefore(..., 
   UsernamePasswordAuthenticationFilter.class)` — o `rateLimitFilter` roda
   **antes** dos filtros de auth (não vê o principal). O `AuthenticatedRateLimitFilter`
   deve ser registrado com **`addFilterAfter(authenticatedRateLimitFilter, apiKeyAuthFilter.class)`**
   (o `apiKeyAuthFilter` é o último dos filtros de auth na ordem de execução) —
   assim o principal já está resolvido quando o limite é checado. Confirmar com
   teste de integração (request autenticado → contador por principal).
3. **Config** em `foxcash.security.rate-limit.authenticated`:
   `enabled`, `max-requests` (**baseline 120/min** por principal), `window-seconds`
   (60). Reusa `foxcash.security.rate-limit.store` para o store.
   **Valores são baseline — calibrar no Railway** (benchmark ramp progressivo;
   objetivo: 429 antes da saturação, não maximizar req/s).
4. **Cap de pendentes** no domínio (regra de negócio, não filtro): no
   `CreatePixChargeService`, **a checagem de idempotência vem PRIMEIRO** (Spec
   28 — replay devolve a MESMA transação, nunca 409), **depois** o cap: conta
   `PENDING` do lojista (`countByMerchantIdAndStatus`); acima de
   `foxcash.pricing.max-pending-charges` (**baseline 100**, configurável) →
   409 `too_many_pending_charges` (novo handler no `GlobalExceptionHandler`).
   Consulta com índice — adicionar `ix_pix_transactions_merchant_status` se não
   existir.
5. **Cobertura por IP ampliada**: no `RateLimitFilter`, adicionar prefixos
   transacionais com teto próprio (`foxcash.security.rate-limit.transactional.max-requests`,
   **baseline 300/min**) — `/api/v1/transactions/`, `/api/v1/billing/`,
   `/api/v1/foxcash/deliveries/`, `/api/v1/portal/dashboard/`. O bot polling usa
   `GET /api/v1/foxcash/deliveries/pending` — teto 300/min por IP cobre folga;
   calibrar conforme a frequência real do bot no benchmark.

## Arquivos

| Arquivo | Ação |
|---|---|
| `src/main/java/com/foxcash/infrastructure/security/AuthenticatedRateLimitFilter.java` | novo |
| `src/main/java/com/foxcash/infrastructure/security/RateLimitFilter.java` | ampliar paths transacionais |
| `src/main/java/com/foxcash/infrastructure/security/RateLimitProperties.java` | blocos `authenticated`/`transactional` |
| `src/main/java/com/foxcash/infrastructure/security/SecurityConfig.java` | registrar filtro novo (ordem pós-auth) |
| `src/main/java/com/foxcash/application/service/CreatePixChargeService.java` | cap de pendentes |
| `src/main/java/com/foxcash/domain/port/out/PixTransactionRepository.java` | `countByMerchantIdAndStatus` |
| `src/main/java/com/foxcash/infrastructure/inbound/rest/GlobalExceptionHandler.java` | handler 409 |
| `src/main/resources/application.yml` | config nova |
| migration (índice) | `ix_pix_transactions_merchant_status` se ausente |
| testes: filtro, service, controller | novos |
