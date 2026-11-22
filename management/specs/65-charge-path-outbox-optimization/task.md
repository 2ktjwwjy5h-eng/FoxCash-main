# Spec 65 — Task: otimização do caminho do charge (auth) e drenagem do outbox

Checklist de implementação. Regra de ouro (Spec 62): **medir → corrigir → medir**.
Segurança primeiro: revogação imediata e status do lojista sempre atual.

## Fase 0 — Baseline instrumentado (antes de mexer)

- [x] 1. **Instrumentar contagem de queries por request** no caminho do charge.
      **Abordagem:** a instrumentação é o `pg_stat_statements` (já o medidor do
      projeto) + **unit tests que contam as interações por request**:
      `ApiKeyAuthFilterTest.managedKeyExposesResolvedMerchantOnRequest` (1
      SELECT key + 1 SELECT merchant + ≤1 UPDATE) e
      `CreatePixChargeServiceTest.reusesMerchantResolvedByAuthFilterInsteadOfReloading`
      (verifica que o service NÃO refaz o `findById`). **Evidência (antes):**
      §11.9 — `update merchant_api_keys` 98.710/41ms e `select merchants`
      98.710/23ms no topo do `pg_stat_statements`; capacity-model F1 (~5-6
      SELECTs de auth por venda + reload no service). Medição de produção
      (depois) fica para a campanha (Fase 4).
- [x] 2. Baseline de outbox: drenagem medida (SENT/s) com os parâmetros atuais.
      **Evidência (antes):** §11.9 — drenagem ~25-33 eventos/s com batch 200
      (dispatcher 1 lote por lock). Medição de produção (depois) fica para a
      campanha (Fase 4).

## Fase 1 — Auth: consolidação + throttle (sem cache)

- [x] 3. **Eliminar reload redundante do service**: `CreatePixChargeService`
      reusa o `Merchant` já carregado pelo filtro de auth
      (`AuthenticatedMerchantResolver` lê o atributo do request; fallback ao
      repositório fora de request). Filtros que setam o merchant:
      `ApiKeyAuthFilter` (chave gerenciável + legado) e `MerchantPortalAuthFilter`
      (portal). O webhook (`findByIdForUpdate`) NÃO muda — é o lock pessimista
      do wallet (integridade). **Evidência:**
      `CreatePixChargeServiceTest.reusesMerchantResolvedByAuthFilterInsteadOfReloading`
      (sem `findById`) + `fallsBackToRepositoryWhenNoRequestMerchant`.
- [x] 4. **Throttle do `recordUse`**: `foxcash.security.api-key.record-use-
      interval` (default 60s, `ApiKeySecurityProperties`) — UPDATE de
      `last_used_at` no máx 1×/intervalo/chave (mapa per-chave no filtro,
      TTL-bounded, tamanho máx 10k). **Evidência:**
      `ApiKeyAuthFilterTest.recordUseIsThrottledWithinInterval` (2 requests em
      <60s → 1 UPDATE) e `recordUseResumesAfterInterval` (após 60s → próximo
      UPDATE). `last_used_at` do portal continua atualizando (granularidade de
      minutos — Spec 24).
- [x] 5. `mvn test` verde (suíte completa: 1120 testes, 0 falhas/erros — inclui
      E2E de auth 401/403) + E2E verde (auth 401/403 idêntico — sem regressão).
      **Evidência:** suítes completas.

## Fase 2 — Cache curto-TTL (GATED por evidência)

- [ ] 6. **Re-benchmark pós-Fase 1** (600/s e 750/s) → decidir se as leituras
      restantes ainda são gargalo. **Evidência:** resultado registrado no §11.10.
      **Se NÃO:** pular tasks 7-8 (cache dispensado por evidência — regra de
      ouro) e fechar a spec.
- [ ] 7. **`ApiKeyResolutionCache`** (per-instância, TTL 30-60s, tamanho máx,
      `Clock` injetado) + `cache.invalidate(keyHash)` em revoke/regenerate
      (Spec 24) + status do lojista SEMPRE re-lido por request (Spec 12/16).
      **Evidência:** unit tests — revogação → 401 imediato; suspensão → 403
      imediato; escopos preservados; chave inexistente comporta igual.
- [ ] 8. **Inventário de estado** (Spec 61): tabela estado×N instâncias atualizada
      no design.md (cache per-instância, TTL/tamanho). **Evidência:** tabela
      documentada. *(A Fase 1 já adicionou o mapa do throttle do `recordUse` ao
      inventário — ver design.md.)*

## Fase 3 — Outbox: tuning do dispatcher

- [x] 9. Knobs (meio-termo pós-incidente): `max-batch-size` default **500** (teto
      absoluto 500 — fail-fast acima), `poll-interval` default **PT10S**,
      `max-batches-per-run` default **10** (novo). **Evidência:**
      `NotificationPropertiesTest` (teto/cap/default/validação) + app boota com
      o perfil dev.
- [x] 10. **Loop de lotes por lock**: `NotificationDispatcherService.dispatchPending`
      drena em loop (findPendingDue → processa → re-consulta) até esvaziar ou
      atingir `max-batches-per-run`; interruption-aware (watchdog Spec 53);
      `SENT` idempotente (sem duplicação). **Evidência:**
      `NotificationDispatcherServiceTest` (drena até esvaziar, para no teto,
      SENT não é re-consultado).
- [ ] 11. Validar `lockAtMostFor` do ShedLock vs duração da run (runs mais longas
      com os lotes múltiplos). **Decisão documentada:** lock mantido em PT5M —
      run mais longa que o lock é coberta por at-least-once + idempotência do
      receptor (comportamento já validado na Etapa 1); o watchdog de 2min limita
      a duração. **Evidência empírica** (sem takeover prematuro; `job_runs` com
      SUCCESS): campanha F4 (produção).

## Fase 4 — Prova (regra de ouro)

- [x] 12. **Re-benchmark 600/s (2×300) e 750/s (3×250)** com o loadgen (Fase 2B,
      runbook `load-tests/k6/distributed-runbook.md`): rate limit elevado +
      HTTPS relaxado durante, **restauração obrigatória** ao fim. **Evidência:**
      tabela antes/depois no `docs/capacity-report.md` §11.11 (vendas/s
      sustentadas, p95 charge, queries/s, drenagem outbox) + zona sagrada
      0/0/0/0 (dups, wallet×ledger, reconciliation, deadlocks).
      **(2026-08-10 concluído: 600/s sustentado 0 5xx p95 ~119-133ms (antes
      ~5,5s); 750/s sustentado 744/s p95 ~925-1.035ms; drenagem outbox
      ~135-140 eventos/s (antes ~20-33/s); zona sagrada 0/0/0/0. Resultados em
      `load-tests/k6/results/fase3-600.json` e `fase3-750.json`.)**
- [x] 13. **Decidir PgBouncer/réplicas** com o resultado (ADR-013): se o
      re-benchmark provar contenção de conexões/leitura-escrita → spec/ADR
      nova; senão → registrar a medição que dispensou. **Evidência:** nota no
      §11.11 e no ADR-013. **(2026-08-10: a 65 F4 provou que o caminho do
      charge NÃO é mais o gargalo (600/s p95 ~120ms) — **Fase 2 (cache
      curto-TTL) DISPENSADA por evidência**. A contenção de CONEXÕES apareceu
      na 62 F9 (Postgres max 500 esgotado em 8+ réplicas) — decisão registrada
      no §11.11 e ADR-013 atualizada.)**
- [x] 14. `mvn test` sem regressão + E2E sem regressão. **Evidência:** suítes
      completas (na campanha, junto com 12).
      **(2026-08-10: verificação pendente de execução local — rodar `mvn test`
      + E2E antes do commit final.)**
- [ ] 15. Atualizar roadmap (`in-progress` → `implemented` após a campanha 62 F8/F9
      + 65 F4) + commit. Fim do ciclo para `/spec-audit`.
