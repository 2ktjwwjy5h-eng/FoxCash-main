# Spec 58 — Task: rate limit por usuário/chave e cobertura ampliada

- [x] 1. `AuthenticatedRateLimitFilter`: janela fixa por `path|principal`, 429
      JSON, reuso do `RateLimitStore`, `shouldNotFilter` para anônimo/actuator.
- [x] 2. Registrar no `SecurityConfig` após os filtros de auth; teste de
      integração confirmando que o principal está resolvido quando o filtro roda.
- [x] 3. `RateLimitProperties`: blocos `authenticated` (120/min) e
      `transactional` (300/min) + `application.yml`.
- [x] 4. Ampliar `RateLimitFilter` com prefixos transacionais (IP, teto 300).
- [x] 5. Cap de pendentes: `countByMerchantIdAndStatus` + 409
      `too_many_pending_charges` + config `max-pending-charges` (100) + handler.
- [x] 6. Índice `(merchant_id, status)` em pix_transactions (migration) se ausente.
- [x] 7. Testes: filtro (429 por chave, isolamento entre lojas, bot polling OK),
      service (cap 409), controller, RateLimitFilter ampliado.
- [x] 8. `mvn test` verde; E2E sem regressão.
- [x] 9. Smoke dev: flood com chave → 429; loja B → 201; polling bot → 200.
      (Verificação AO VIVO em 2026-08-07 via `load-tests/verify-rate-limit-429.py`:
      3 camadas PASS — 429 por principal, 429 por IP transacional, 409 do cap.)
- [ ] 10. **Benchmark de calibração no Railway** (k6, ramp progressivo): medir o
       ponto de inflexão da latência p95; ajustar tetos (`authenticated`,
       `transactional`, `max-pending-charges`) para cortar com 429 **antes** da
       saturação. Verificar integridade sob pico: **idempotência** (replay →
       mesma transação, nunca 409), **ledger** (reconciliação Spec 30 passa),
       **outbox** (contagem coerente), **zero OOM/restart**. Registrar valores
       finais no roadmap. — *(deploy Railway: follow-up pós-deploy, fora do
       escopo local)*
- [ ] 11. Deploy Railway e verificação ao vivo (se o ambiente for trocado).
      — *(deploy Railway: follow-up pós-deploy, fora do escopo local)*
