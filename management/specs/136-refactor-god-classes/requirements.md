---
name: Refactor god classes — GlobalExceptionHandler (637) + PixTransaction (435)
status: audited
references:
  - "management/roadmap.md#Fase 41"
  - "management/arch.md"
---

# Spec 136 — Refactor de god classes: GlobalExceptionHandler + PixTransaction

**Implementada (2026-08-16):** `GlobalExceptionHandler` 637 → 89 linhas
(especialistas em `handler/*`: Transaction 8, PixProvider 1, Merchant 21,
Store 24, Auth 11, Security 4; genérico/fallback no próprio). Contrato congelado
ANTES (`ExceptionMappingContractTest`, 87 cases) e verde DEPOIS com a tabela
byte-idêntica. `PixTransaction` **avaliado e MANTIDO** (L20 — overloads aditivos,
KISS; critério C2 "documentar e manter"). `mvn test` **2021/0/0** + contexto
smoke 2/2 (boot com os advices novos). Métrica `nyx_http_5xx_total` preservada
(teste dedicado). Detalhe no task.md.

## O que entrega

O due diligence apontou 2 god classes no limite de manutenibilidade:

- **`GlobalExceptionHandler` (637 linhas, 76 `@ExceptionHandler`)** — concentra
  TODA a tradução de exceção→HTTP do sistema. Qualquer mudança de um endpoint
  arrisca os outros 75; é impossível testar isoladamente.
- **`PixTransaction` (435 linhas)** — agregado de domínio com toda a lógica da
  transação Pix (estados, valores, campos). Cresce a cada spec nova.

Objetivo: dividir por responsabilidade sem mudar comportamento (refactor
comportamentalmente idêntico, provado por testes).

## Critérios de aceite

**C1 — GlobalExceptionHandler dividido.** Os 76 handlers organizados em
especialistas por domínio/categoria (ex.: `TransactionExceptionHandler`,
`MerchantExceptionHandler`, `AuthExceptionHandler`, `SecurityExceptionHandler`,
`GenericExceptionHandler`), registrados como `@ControllerAdvice` separados ou
delegados. A classe original fica ≤ 150 linhas (ou vira orquestrador fino).

**C2 — PixTransaction dividido (se fizer sentido).** Avaliar com evidência:
extrair sub-objetos de valor (estado, valores monetários, campos do PSP) para
classes próprias se reduzir complexidade — sem mudar o modelo persistido
(colunas/tabelas intactas). Se a divisão não reduzir complexidade real,
documentar e manter (não forçar).

**C3 — Comportamento idêntico.** As 1995+ specs passam; testes de contrato dos
handlers (status code, corpo, headers para cada exceção) são **criados ANTES**
do refactor e passam DEPOIS sem alteração. E2E completo verde.

**C4 — Testes isolados dos handlers.** Cada handler especialista tem teste
próprio (ex.: exceção X → 4xx/5xx com corpo Y) — cobertura do mapeamento de
erro deixa de depender de teste do controller completo.

**C5 — Métricas de erro preservadas.** `nyx_http_5xx_total{route}` (Spec 126) e
logs estruturados continuam funcionando com a mesma semântica após a divisão.

## Evidência-base (2026-08-16)

- `GlobalExceptionHandler.java`: 637 linhas, 76 `@ExceptionHandler`.
- `PixTransaction.java`: 435 linhas.
- Testes existentes: `GlobalExceptionHandlerTest` (cobre os mapeamentos).

## Dependências

- **Spec 126** (métricas de erro) — preservar semântica.
- **Spec 137** (testes de integração) — pode rodar em conjunto (os testes de
  contrato dos handlers são pré-requisito do C3).

## Fora de escopo

- Mudança de comportamento/API pública.
- Outras classes grandes (avaliar por evidência, só as 2 apontadas no DD).
