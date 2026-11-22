---
name: Otimização do caminho do charge (auth) e drenagem do outbox
status: audited
references:
  - "management/vision.md#V45"
  - "docs/capacity-model.md"
  - "docs/capacity-report.md"
  - "management/specs/12-api-authentication/requirements.md"
  - "management/specs/24-api-credentials/requirements.md"
  - "management/specs/35-outbound-webhook/requirements.md"
  - "management/specs/53-job-robustez/requirements.md"
  - "management/specs/61-horizontal-scalability-ha/requirements.md"
  - "management/specs/62-capacity-engineering-high-throughput/requirements.md"
---

# Spec 65 — Otimização do caminho do charge (auth) e drenagem do outbox

## O que entrega

A Spec 62 provou que o teto real sustentado (~480 vendas/s com 2 réplicas) é de
**recurso compartilhado** (2→3 geradores não subiram o throughput) e apontou
dois gargalos com nome e número. Esta spec os ataca **com evidência
antes/depois**, sem re-arquitetura:

1. **Caminho do charge — autenticação por API key** (Specs 12/24). Medido:
   - `pg_stat_statements` (produção): `update merchant_api_keys` **98.710
     chamadas, média 41 ms** e `select merchants` **98.710 chamadas, média 23
     ms** — as duas queries mais caras do sistema;
   - `docs/capacity-model.md` (F1): auth carrega `merchants` 2× /
     `merchant_api_keys` 3× + reload no service (~5-6 SELECTs redundantes por
     venda; 2 requests/venda → ~10-12 queries de auth por venda);
   - `ApiKeyAuthFilter` (verificado no código): por request faz
     `findByKeyHash` (SELECT) + `findById` (SELECT) + **`recordUse` (UPDATE de
     `last_used_at` — o hot spot de escrita)**; o service do charge recarrega o
     lojista depois.
   Entregáveis:
   - **Consolidar as leituras**: 1-2 queries por request no caminho do charge
     (o filtro carrega chave+lojista uma vez e o service reusa/usa o
     principal em vez de recarregar; eliminar o reload redundante);
   - **Throttle do `last_used_at`**: o UPDATE de uso não roda em **todo**
     request — no máximo 1×/minuto por chave (configurável), mantendo o
     "último uso" útil do portal (Spec 24) sem o hot spot de escrita;
   - **Cache curto-TTL da resolução de API key** (Fase 2, gated por evidência
     — só se o re-benchmark mostrar que as leituras restantes ainda são
     gargalo): `keyHash → MerchantApiKey` com TTL curto (30-60s), com
     **invalidação explícita na revogação/regeneração** (Spec 24) e o **status
     do lojista sempre re-lido por request** (suspend/release — Spec 16 — nunca
     fica atrasado no cache). Documentado para N instâncias (Spec 61).
2. **Drenagem do outbox** (Specs 35/53/54). Medido: ~20-33 eventos/s (batch
   200 / poll 10s com ShedLock — o dispatcher processa 1 lote por lock).
   Entregáveis:
   - `foxcash.notification.max-batch-size` (200 → até 2000, configurável) e
     `poll-interval` (10s → 3s, configurável) — sem código estrutural;
   - **Múltiplos lotes por lock**: o dispatcher drena em loop até o backlog
     esvaziar ou um teto por run (mantendo o watchdog e o ShedLock da Spec 53;
     `SENT` idempotente — sem duplicação de entrega);
   - Sem DLQ física, sem worker externo (o outbox continua o "fio" — Spec 53).
3. **Prova (regra de ouro da Spec 62)**: re-benchmark dos estágios **600/s
   (2×300) e 750/s (3×250)** da F7 com o loadgen já pronto (`loadgen/`), e
   comparação antes/depois: vendas/s sustentadas, p95 do charge, queries/s no
   `pg_stat_statements`, drenagem do outbox.

**Fora de escopo:** PgBouncer/réplicas de leitura (só com evidência nova —
ADR-013; a F7 deu indício de contenção, mas a prova é este re-benchmark);
Redis Cluster; mudança de contrato de API; worker externo do outbox.

## Critérios de aceite

1. **Queries de auth reduzidas**: no caminho do charge (cobrança + webhook), o
   total de queries de autenticação por venda cai de ~5-6 SELECTs + 1 UPDATE
   para **≤ 3** (2 leituras + 0-1 escrita throttled) — evidência:
   `pg_stat_statements` antes/depois (as queries de auth saem do topo por
   chamadas/tempo) e contagem instrumentada por request.
2. **`last_used_at` throttled**: `recordUse` roda no máximo 1×/min por chave
   (configurável) — o `update merchant_api_keys` deixa de aparecer como hot
   spot; o portal continua mostrando o último uso (Spec 24) com granularidade
   de minutos.
3. **Semântica de auth inalterada** (segurança inegociável): chave revogada →
   **401 imediato** (cache invalidado na revogação/regeneração, ou TTL curto +
   verificação de status por request); lojista suspenso → **403/401 imediato**
   (status do lojista sempre re-lido — nunca servido de cache); escopos
   preservados (Spec 24); sem oráculo de enumeração (comportamento idêntico
   para chave inexistente/inválida).
4. **Outbox drena**: com os novos parâmetros (batch/poll/tetos), a drenagem
   medida ≥ **100 eventos/s** sustentados (antes ~20-33/s) com `SENT`
   idempotente (0 duplicação de entrega) e watchdog/ShedLock intactos (Spec 53).
5. **Re-benchmark (regra de ouro)**: re-executar 600/s (2×300) e 750/s (3×250)
   com a MESMA metodologia da F7 → **vendas/s sustentadas aumentam** vs ~480/s
   (meta conservadora: ≥ 600/s com p95 charge ≤ 3s; o número reportado é o
   medido) e **0 5xx, 0 duplicações, 0 divergência wallet×ledger, 0
   deadlocks** (integridade inalterada).
6. **Estado em memória auditado** (Spec 61): cache (se implementado) é
   per-instância, com TTL e tamanho máx, inventariado no design (tabela
   estado×N instâncias) — sem comprometer a topologia multi-instância.
7. `mvn test` sem regressão + E2E sem regressão (auth continua 401/403 idêntico;
   outbox continua idempotente; contrato de API inalterado).

## Filosofia

- **Medir → corrigir → medir (regra de ouro da Spec 62)**: nenhuma mudança
  entra sem o baseline instrumentado e sem o re-benchmark provando o ganho.
- **KISS**: Fase 1 = consolidação de leituras + throttle de escrita (sem
  cache); cache curto-TTL só na Fase 2 **se o benchmark mostrar que as
  leituras restantes ainda são o gargalo** — nunca otimizar o que a evidência
  não aponta.
- **Segurança antes de throughput**: revogação imediata e status do lojista
  sempre atual são inegociáveis — cache é de resolução imutável + TTL curto, e
  qualquer invalidação é testada (testes de regressão de auth).
- **Integridade financeira inegociável**: ledger/wallet/idempotência não mudam
  de caminho; o re-benchmark re-prova a zona sagrada (Spec 62 F4).

## Dependências

- `docs/capacity-model.md` (achado F1 — auth 5-6 SELECTs/venda)
- `docs/capacity-report.md` §11.9 (baseline Fase 2B: ~480/s, p95 5,5s, outbox ~20-33/s)
- `management/specs/12-api-authentication/requirements.md` e
  `management/specs/24-api-credentials/requirements.md` (auth por API key —
  base dos critérios 1-3)
- `management/specs/35-outbound-webhook/requirements.md`,
  `management/specs/53-job-robustez/requirements.md` (outbox/dispatcher —
  base do critério 4)
- `management/specs/61-horizontal-scalability-ha/requirements.md` (estado em
  memória × N instâncias — critério 6)
- `management/specs/62-capacity-engineering-high-throughput/requirements.md`
  (metodologia e regra de ouro do benchmark — critério 5)
- `loadgen/` (gerador distribuído pronto — Spec 62/commit e6ba405)
