---
name: Conflito de cadastro — documento duplicado retorna 409 Conflict
status: audited
references:
  - "management/roadmap.md#observacoes-pos-auditoria"
  - "management/vision.md#V3"
  - "management/specs/4-security-and-e2e/requirements.md"
---

# Spec 5 — Conflito de cadastro (documento duplicado → 409 Conflict)

## O que entrega

Semântica correta na API de cadastro de lojistas: quando o CPF/CNPJ já está
cadastrado, `POST /api/v1/merchants` retorna **409 Conflict** com o código
`merchant_already_exists` e mensagem clara — em vez do `500 internal_error`
genérico (observação pós-auditoria da Spec 4).

## Critérios de aceite

1. `POST /api/v1/merchants` com documento já existente retorna HTTP **409** com
   corpo `{"code":"merchant_already_exists","message":"..."}`.
2. `POST /api/v1/merchants` com documento novo continua retornando **201** e
   persistindo o lojista normalmente (sem regressão).
3. A constraint `uq_merchants_document_hash` no banco permanece como backstop
   (não é removida nem relaxada).
4. Outras falhas (ex.: validação, erro inesperado) continuam com os códigos
   atuais (`400 validation_error`, `500 internal_error`).
5. `mvn test` verde (testes existentes + teste unitário do use case).

## Dependências

- Spec 4 (observação pós-auditoria que originou esta spec).

## Observações

- Implementação via exceção de domínio (`MerchantAlreadyExistsException`),
  pré-check no `RegisterMerchantService` (usa o port `MerchantRepository
  .findByDocument`, que já existe) e handler em `GlobalExceptionHandler`
  seguindo o padrão das exceções existentes.
- A arquitetura hexagonal é preservada: o domínio não conhece HTTP nem Spring
  Data; a infraestrutura mapeia a exceção para o status HTTP.
