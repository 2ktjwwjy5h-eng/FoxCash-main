# Spec 66 — Task: Resiliência operacional pós-análise

Checklist de implementação. Princípios: **disponibilidade primeiro (fail-open —
ADR-010)**, **evidência antes de mudança** (Fase A só altera topologia com
achado medido) e **reversível** (Fase A = config; Fase B = env store; Fase C =
métrica + docs).

## Fase A — Rollback de edge completo (config + runbook)

- [x] 1. **Verificar o target de custom domain** do Railway — `railway domain`
      (2026-08-10): `api.foxcash.example.com` **custom, ACTIVE** (id `<DOMAIN_ID>`);
      endpoints `.up.railway.app` também ACTIVE. Anotado no runbook (§Rollback).
      **Endpoint de custom domain distinto descoberto**: `custom-api.up.railway.app`
      (`dnsRecords[].requiredValue` do `railway domain status --json`) — o CNAME
      antigo apontava para o domínio default (não serve o cert do domínio custom).
- [x] 2. **Ajustar o CNAME** no Cloudflare — **FEITO (2026-08-10, token escopado
      `<TOKEN_ID>` válido)**: CNAME `api` → `custom-api.up.railway.app` + **TXT
      `_railway-verify.api`** (`railway-verify=<VERIFY_TOKEN>` — sem ele o Railway fica
      `Verified: no` e o cert `VALIDATING_OWNERSHIP`). Resultado: `Verified: yes`,
      cert emitido (`CN=api.foxcash.example.com`, Let's Encrypt, `VALID`).
- [x] 3. **Testar rollback real** — **VALIDADO (2026-08-10)**: proxy OFF →
      `curl -I https://api.foxcash.example.com` → **200 direto** (`Server:
      railway-hikari`, sem `CF-Ray`, health UP, TLS verify 0) → **proxy ON
      restaurado** (200 via Cloudflare, `CF-Ray` presente). Procedimento em
      `deploy/edge.md` §Rollback.
- [x] 4. **Atualizar `deploy/edge.md` §Rollback**: procedimento correto
      (CNAME/TXT/flip na ordem certa — proxy OFF expõe o CNAME e dispara a
      emissão do cert), resultado validado, tabela do critério 9 atualizada.
      Commitado.

## Fase B — Alertas de segurança agregados (Redis)

- [x] 5. **Porta `AlertCounterStore`** (`domain/port/out`) com
      `incrementAndFire(counterKey, threshold, windowSeconds) → long` (0/1).
      Interface + javadoc. Testes do contrato nos 2 stores.
- [x] 6. **`InMemoryAlertCounterStore`** (default) — janela + reset no disparo
      (extraído do publisher). `InMemoryAlertCounterStoreTest` 3/3.
- [x] 7. **`RedisAlertCounterStore`** — script Lua (`INCR`+`EXPIRE`; `DEL` no
      disparo) + **fail-open** (WARN throttled + fallback local).
      `RedisAlertCounterStoreTest` 6/6 (fire/0, prefixo `alert:`, fail-open).
- [x] 8. **`SecurityAlertProperties.store`** (`memory`|`redis`, env
      `NYX_SECURITY_ALERT_STORE`) + beans condicionais `@ConditionalOnProperty`
      (mesmo padrão do `RateLimitStore`) + `application.yml`. Boot com os 2
      valores coberto pela suíte (contexto).
- [x] 9. **`SecurityAlertPublisher`** usa a porta (`incrementAndFire`); mantém
      throttle por tipo + payload mascarado. `SecurityAlertPublisherTest` 8/8
      adaptado (store real in-memory).
- [x] 10. **Verificação multi-instância** — **CONCLUÍDA AO VIVO (2026-08-16)**:
       `NYX_SECURITY_ALERT_STORE=redis` ativado no Railway (deploy `94861317`,
       2/2 réplicas, health 200) + smoke de agregação: 6 payloads maliciosos
       (SQLi boolean-tautology) contra `/api/v1/merchants` → **6×403**
       `body_inspection_failed`, **1 alerta `BODY_INSPECTION_BLOCKED` publicado**
       (limiar 5/60s agregado via Redis — script Lua atômico), **0 WARN
       fail-open** em todo o ciclo (Redis operante). Evidência: logs do deploy
       `94861317` (07:04 UTC). **Coberto até
       lá**: script Lua atômico testado (`RedisAlertCounterStoreTest` 6/6) +
       fail-open verificado AO VIVO na auditoria (instância 8082 com
       `store=redis` sem Redis → 403 mantido + WARN fail-open) — política Spec 43.

## Fase C — Métrica e calibração do body inspection

- [x] 11. **Métrica `nyx.body_inspection.blocks`** no `RequestBodyInspectionFilter`
       (injeta `MeterRegistry`; `Counter` tags `pattern`/`category`). Teste com
       `SimpleMeterRegistry` (2 bloqueios → count 2.0).
- [x] 12. **Runbook de calibração** em `deploy/edge.md` §7.6 (evidência:
       monitor + métrica + logs; classificação ataque vs falso positivo; afinar
       padrão ou `NYX_BODY_INSPECTION_ENABLED=false`; registrar em lessons).
       Commitado.
- [x] 13. **Cron do monitor** documentado no runbook (§7.6) e já no header do
       `ops/monitor-body-inspection.sh` (*/15; exit 2 = alerta).

## Fase D — Verificação final

- [x] 14. **Critérios 1-5** da requirements: **critério 1 (rollback) VALIDADO
       (2026-08-10)** — CNAME/TXT + flip proxy OFF → HTTPS direto 200 + restauração
       proxy ON; critérios 2 (agregação via script Lua + testes), 3 (métrica —
       teste) e 4 (runbook) OK; critério 5 `mvn test` **1169/0/0** OK.
       E2E via CI (mudanças de runtime não alteram contrato).
- [x] 15. **Roteiro de melhorias atualizado** — R2 (Fase A feita), R4 (Fase B
       feito), R5 (Fase C feito; cron pendente) e R6 (audits 63/64/65 fechados)
       marcados com estado; checklist D atualizado. Commitado.
- [x] 16. Roadmap: 66 `created` → `implemented` (Fases B/C) + commit. Fase A
       bloqueada no token CF; `/spec-audit` após o rollback. **Fase A concluída
       (2026-08-10) — rollback validado; pronto para `/spec-audit`.**
- [x] 17. **`/spec-audit` (2026-08-11) — 5/5 critérios OK, 0 achados abertos.** 2
       achados CORRIGIDOS: (1) **CRÍTICO** — `application.yml` com chave `alert:`
       duplicada (Spec 66 Fase B `caa8b7d` inseriu 2º bloco `alert:` colidindo com
       o do Spec 64) → `DuplicateKeyException` no boot (**a app não subia**;
       `mvn test` não pegou — sem `@SpringBootTest`); corrigido fundindo os blocos
       (`store` dentro do bloco `alert:` existente) + boot validado + gap
       registrado em lessons (L16); (2) **MÉDIO** — `security.spec.js` (SQLi-body
       e XXE-JSON, pré-data o body inspection da Spec 63) esperava
       `[400,401,422]`/`[201,400,422]` mas o filtro bloqueia com **403
       `body_inspection_failed`** → listas atualizadas (+403); falha latente desde
       `538eb25`, nunca vista (CI não rodou sobre esses commits). Verificações:
       `mvn test` **1169/0/0**, **E2E 105/105** (app dev com
       `NYXCASH_SECURITY_RATE_LIMIT_ENABLED=false`), métrica validada ao vivo,
       fail-open do Redis validado ao vivo (instância 8082). Pendências
       registradas: task 10 (multi-instância) + R5 (cron do monitor). Fim do ciclo
       SDD (roadmap/requirements/task `audited`).
