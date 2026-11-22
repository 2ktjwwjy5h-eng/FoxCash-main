---
name: Dashboard/admin — endpoints de consulta
status: audited
references:
  - "management/vision.md#V6"
  - "management/specs/6-invoice-scheduler/requirements.md"
---

# Spec 8 — Dashboard/admin: endpoints de consulta

## O que entrega

Endpoints **somente-leitura** sob `/api/v1/admin` para alimentar uma tela de
dashboard/operação da plataforma white-label. Consultam os dados existentes
(lojistas, transações e faturas) com **paginação e filtros**, além de um
**resumo agregado** (contagens e somas) para o topo do dashboard.

Três endpoints:

1. `GET /api/v1/admin/merchants` — lista paginada de lojistas, filtrável por
   `status`, texto (`q` sobre o nome) e janela de criação
   (`from`/`to`, ISO-8601 instant).
2. `GET /api/v1/admin/invoices` — lista paginada de faturas de todos os
   lojistas, filtrável por `merchantId`, `status` e `billingPeriod` (`yyyy-MM`).
3. `GET /api/v1/admin/summary` — agregados: lojistas (total e por status),
   transações (total e por status + soma dos valores aprovados) e faturas
   (total e por status + soma dos valores não-canceladas).

Não altera o domínio existente (sem novas escritas). É evolução **funcional de
consulta** sobre os dados já produzidos pelas specs 1–7.

## Critérios de aceite

1. Migration `V5__admin_dashboard_indexes.sql` cria índices de apoio às
   consultas: `ix_merchants_status`, `ix_invoices_status` e
   `ix_invoices_billing_period`. A aplicação sobe com Flyway validando (sem
   erro).
2. `GET /api/v1/admin/merchants` retorna `{ items, page, size, totalElements,
   totalPages }` com `MerchantResponse` em `items`; `page` default `0`, `size`
   default `20` (máx. `100`, valores fora da faixa são ajustados); filtros
   `status`, `q` (substring em `name`, case-insensitive) e
   `from`/`to` (janela de `createdAt`) funcionam isolados e combinados.
3. `GET /api/v1/admin/invoices` retorna a mesma estrutura paginada com
   `InvoiceResponse` em `items`; filtros `merchantId`, `status` e
   `billingPeriod` funcionam isolados e combinados.
4. `GET /api/v1/admin/summary` retorna:
   - `merchants`: `total`, `active`, `suspended`, `inactive`;
   - `transactions`: `total`, `approved`, `pending`, `rejected`, `cancelled`,
     `failed`, `refunded` e `approvedVolume` (soma dos valores das transações
     APPROVED);
   - `invoices`: `total`, `pending`, `paid`, `overdue`, `cancelled` e
     `totalBilled` (soma de `total_amount` das faturas **não** canceladas).
   Os valores são consistentes com os dados persistidos (verificação via
   consulta SQL de referência no smoke test).
5. Valores inválidos de enum (`status` inexistente) ou de `billingPeriod`
   (formato fora de `yyyy-MM`) respondem `400` com `ErrorResponse` (sem 500).
6. `mvn test` verde, incluindo testes unitários novos
   (`AdminDashboardServiceTest`); suíte E2E continua 5/5 (sem regressão).

## Dependências

- Spec 6 (faturas mensais — dados consumidos pelo dashboard).
- Specs 1–5 (entidades lojista/transação, padrão de controllers/ports).

## Observações

- **Arquitetura**: segue o padrão hexagonal já usado — Controller → UseCase
  (`domain/port/in`) → Service (`application/service`) → Port
  (`domain/port/out`) → Adapter JPA (`infrastructure/outbound/persistence`).
- **Paginação no domínio**: um record genérico `PageResult<T>` no domínio
  evita vazar Spring Data (`Page`) para fora da infraestrutura.
- **Filtros dinâmicos**: o adapter usa `JpaSpecificationExecutor`
  (Specifications) para combinar filtros opcionais sem gerar N métodos
  derivados.
- **Soma no banco**: `approvedVolume` e `totalBilled` são calculados com
  `SUM`/`COALESCE` no PostgreSQL (não em memória) para escalar.
- Escopo: endpoints de consulta **apenas**; ações administrativas (ativar/
  suspender lojista, marcar fatura paga) ficam para spec futura.
