---
name: Antifraude e compliance — monitoramento de indicadores de risco + suspensão de contas (ADR-008)
status: audited
references:
  - "management/vision.md#V14"
  - "management/adr.md#adr-008-monitoramento-de-risco-chargebacks-e-suspensão-de-contas"
  - "docs/politicas-uso.md#4-o-que-o-foxcash-espera-da-operação-como-um-todo"
  - "management/specs/12-api-authentication/requirements.md"
  - "management/specs/13-master-auth/requirements.md"
  - "management/specs/15-master-dashboard/requirements.md"
---

# Spec 16 — Antifraude e compliance: indicadores de risco + suspensão

## O que entrega

Monitoramento **contínuo** dos indicadores de risco das [Políticas de Uso
§4](../docs/politicas-uso.md#4-o-que-o-foxcash-espera-da-operação-como-um-todo)
(ADR-008), com **decisão do operador (master)** — o sistema **computa** os
indicadores e expõe ao master, que **age** (suspensão/liberação):

1. **Monitoramento por métricas agregadas:** job agendado (ShedLock, padrão
   Specs 6/7) calcula, para **todos** os lojistas, os indicadores deriváveis
   dos dados existentes e grava um **snapshot** em `merchant_risk` (migration
   **V9**): volume aprovado, total/aprovadas/reembolsadas, **taxa de reembolso**
   (% REFUNDED/total), e as flags de alerta. Chargeback e denúncias **não têm
   dados modelados ainda** (ADR-008: disputa chega fora do fluxo Pix) — entram
   com taxa 0/contagem 0 nesta versão, prontos para alimentação futura.
2. **Limiares configuráveis:** `foxcash.risk.*` (`chargeback-alert-percent`
   default 1.0, `chargeback-suspend-percent` default 3.0,
   `refund-alert-percent` default 5.0, `complaints-alert-count` default 3) —
   flags calculadas na gravação do snapshot.
3. **Suspensão reusa o status existente:** `POST
   /api/v1/master/merchants/{id}/suspend` e `.../release` (role `ROLE_MASTER`)
   escrevem `MerchantStatus.SUSPENDED`/`ACTIVE` — o lojista `SUSPENDED` já não
   processa pagamentos (Spec 12) nem saca (Spec 14). **Decisão é do master**:
   nada de suspensão automática nesta versão.
4. **Consulta ao master:** `GET /api/v1/master/risk` (paginado, filtro
   `merchantId`) lista os snapshots; `GET /api/v1/master/merchants/{id}/risk`
   devolve o snapshot mais recente do lojista (404 se ainda não monitorado).

## Critérios de aceite

1. Migration V9 cria `merchant_risk` (FK merchant, `computed_at`, volume
   aprovado, contagens, `refund_rate_percent`, `chargeback_rate_percent`,
   `complaints_count`, flags de alerta) com índices em `merchant_id` e
   `computed_at`.
2. O job de risco (`foxcash.scheduler.risk-cron`, ShedLock
   `foxcash-risk-monitoring`) grava um snapshot por lojista com os indicadores
   corretos (verificado por teste de serviço e execução manual): taxa de
   reembolso = REFUNDED/total×100; flags de alerta conforme `foxcash.risk.*`.
3. `GET /api/v1/master/risk` lista snapshots paginados com filtro
   `merchantId` (parâmetro inválido → `400 invalid_argument`); exige
   `ROLE_MASTER`.
4. `GET /api/v1/master/merchants/{id}/risk` retorna o snapshot mais recente;
   lojista sem snapshot → `404 risk_snapshot_not_found`; exige `ROLE_MASTER`.
5. `POST /api/v1/master/merchants/{id}/suspend` → `204` e altera o status para
   `SUSPENDED` (verificado no banco); `.../release` → `204` e `ACTIVE`; lojista
   inexistente → `404 merchant_not_found`; sem token master → `401`, com token
   de lojista/admin → `403`.
6. `mvn test` verde (**~110 testes: 100 + ~10 novos** —
   `RiskMonitoringServiceTest`, `MerchantStatusControlServiceTest`,
   `QueryRiskServiceTest`) e E2E **6/6** sem regressão (jornada existente
   inalterada; sem E2E novo nesta spec).

## Dependências

- Spec 12 (lojista `SUSPENDED` não processa; `MerchantStatus`).
- Spec 13 (role `ROLE_MASTER` e rotas `/api/v1/master/**`).
- Spec 15 (dashboard master — agregações `SUM`/`COALESCE`; earnings).
- Specs 6/7 (padrão ShedLock para o job agendado).

## Observações

- **Decisão manual (ADR-008):** o job apenas **sinaliza** (flags/percentuais);
  suspender/liberar é ação do master. Automatização total de suspensão fica
  para spec futura.
- **Chargeback/denúncias:** sem fonte de dados nesta versão (taxa 0/contagem
  0). A modelagem da disputa/denúncia (endpoint administrativo) fica para spec
  futura — o schema já tem as colunas.
- **Reserva de valor** (§7) fora de escopo — documentada para spec futura.
- **Sem E2E novo:** a jornada existente continua intacta; validação por
  testes unitários + curl manual.
