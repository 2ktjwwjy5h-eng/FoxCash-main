---
name: Scheduler — geração automática de faturas mensais (ShedLock)
status: audited
references:
  - "management/vision.md#V4"
  - "management/specs/5-merchant-duplicate-conflict/requirements.md"
---

# Spec 6 — Scheduler: geração automática de faturas mensais (ShedLock)

## O que entrega

Geração automática da fatura mensal dos lojistas, sem depender do
`POST /api/v1/merchants/{id}/invoices` manual:

- Job agendado que roda no 1º dia de cada mês (cron configurável) e gera a fatura
  do **mês anterior** para todos os lojistas **ACTIVE**.
- **ShedLock** (lock via tabela `shedlock` no PostgreSQL) para garantir execução
  única mesmo com múltiplas instâncias da aplicação.
- Geração idempotente: a constraint `uq_invoices_merchant_period` e o
  `GenerateInvoiceService` já garantem que não há duplicação (o job usa o mesmo
  use case do POST manual).

## Critérios de aceite

1. App inicia com `@EnableScheduling` + `@EnableSchedulerLock` e a migration
   `V3__shedlock.sql` cria a tabela `shedlock` (via Flyway).
2. `MerchantRepository.findAllActive()` lista apenas lojistas com status
   `ACTIVE`.
3. O use case `GenerateInvoicesForPeriodUseCase.generateForPeriod(YearMonth)`
   gera fatura do período para cada lojista ACTIVE que ainda não a possui e
   retorna a quantidade criada (ignora lojistas com fatura já existente).
4. O job agendado usa `@SchedulerLock(name = "foxcash-invoice-generation", ...)`
   com cron configurável `foxcash.scheduler.invoice-cron` (default
   `0 5 0 1 * *`, UTC).
5. `mvn test` verde, incluindo teste unitário do novo use case.

## Dependências

- Spec 5 (fase de evolução iniciada após o fechamento da fundação).

## Observações

- O scheduler vive na **infraestrutura** (`@Scheduled` + ShedLock); a regra de
  negócio (quais lojistas, qual período, idempotência) fica no **domínio/aplicação**
  via port `GenerateInvoicesForPeriodUseCase`.
- Período gerado: `YearMonth.now(UTC).minusMonths(1)` (fatura do mês anterior).
- A notificação de fatura gerada será tratada na Spec 7.
