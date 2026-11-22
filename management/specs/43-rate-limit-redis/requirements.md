---
name: Rate limit distribuído com Redis (M-05)
status: audited
references:
  - "management/vision.md#V26"
  - "management/specs/18-security-hardening/requirements.md"
  - "management/adr.md#ADR-009"
---

# Spec 43 — Rate limit distribuído com Redis (M-05)

## O que entrega

Evolui o rate limit de **janela fixa em memória** (`RateLimitFilter`,
`ConcurrentHashMap` — Spec 18/ADR-009) para um **store distribuído (Redis)**
quando houver **múltiplas instâncias** do backend — sem mudar o contrato
externo (429 `too_many_requests` por IP+rota nas mesmas rotas públicas).

- **Seleção por config:** `foxcash.security.rate-limit.store` com valores
  `memory` (default — topologia atual single-instance, sem dependência nova)
  e `redis`. Em produção multi-instância o operador liga `redis`.
- **Backend Redis:** janela fixa por IP+rota, mesma semântica da implementação
  atual (limite por janela deslizante de `window-seconds`), implementada com
  `INCR` + `EXPIRE` (ou script Lua atômico) — consistente e sem corrida entre
  instâncias.
- **Fallback seguro:** se `store=redis` e o Redis estiver indisponível, a
  política é **fail-open ou fail-closed** — decidir no design (recomendado:
  fail-open com log, para não derrubar o gateway de pagamento por
  indisponibilidade de infra auxiliar; alternativa fail-closed com `allowlist`
  de health check).
- **Sem mudança nas rotas limitadas** (`/api/v1/merchants`,
  `/api/v1/auth/master/login`, `/api/v1/merchant-auth/login`,
  `/api/v1/merchant-auth/forgot-password` + as que a Spec 42 adicionar).

## Critérios de aceite

- `store=memory` (default): comportamento idêntico ao atual — nenhuma
  dependência nova no classpath em single-instance.
- `store=redis`: duas instâncias do app compartilham o mesmo contador — N
  requests distribuídos entre as instâncias (total > limite) → 429 na
  requisição que estourar o agregado.
- Janela correta: o contador expira após `window-seconds` (chave Redis com
  TTL); requisições na janela nova não são bloqueadas.
- Indisponibilidade do Redis: comportamento documentado e testado (fail-open
  por default com WARN — o gateway não para de aceitar tráfego por falha do
  rate limiter).
- Config documentada no README/runbook (env `NYX_RATE_LIMIT_STORE=redis` +
  `SPRING_DATA_REDIS_*`); produção single-instance continua sem Redis.
- `mvn test` verde (testes unitários do filtro com store mockado + teste de
  integração do script Lua contra Redis embutido, se viável) e E2E 22/22.

## Dependências

- `management/specs/18-security-hardening/requirements.md` (contrato do rate limit)
- `management/adr.md` (ADR-009 — nova ADR para o store distribuído)
- `management/specs/42-backlog-baixos-3a-auditoria/requirements.md` (B-02
  amplia as rotas limitadas — esta spec herda a lista atualizada)
