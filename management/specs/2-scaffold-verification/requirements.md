---
name: Verificação do scaffold (build + testes)
status: audited
references:
  - "management/lessons.md#L1"
  - "management/specs/1-environment-setup/requirements.md"
  - "management/vision.md#V1"
---

# Spec 2 — Verificação do scaffold (build + testes)

## O que entrega

Prova de que o código já escrito no scaffold compila e funciona:

- Build Maven sem erros (`mvn -q clean compile`).
- Testes unitários de domínio passando (`mvn test`):
  `MoneyTest`, `PricingCalculatorTest`, `InvoiceCalculatorTest`.
- Aplicação sobe com schema criado pelo Flyway (`mvn spring-boot:run` com o
  PostgreSQL da Spec 1), sem falha na inicialização.

## Critérios de aceite

1. `mvn test` termina com `BUILD SUCCESS` e 100% dos testes verdes.
2. `mvn spring-boot:run` inicializa a aplicação na porta 8080 sem exceções de
   startup (Flyway aplica `V1__init.sql`; Hibernate valida o schema).
3. Smoke test opcional dos endpoints básicos via curl:
   cadastrar lojista → criar transação → webhook APPROVED → gerar fatura.
4. Se o build apontar erros, corrigi-los de forma cirúrgica (sem mudar
   regras de negócio) e repetir o ciclo até verde.

## Dependências

- Spec 1 (ambiente com JDK 21, Maven e PostgreSQL).

## Observações

- Se algum teste falhar ou a aplicação não subir, registrar a causa em
  `management/lessons.md` antes de corrigir.
