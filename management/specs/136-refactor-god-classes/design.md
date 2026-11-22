---
name: Refactor god classes — GlobalExceptionHandler (637) + PixTransaction (435)
status: audited
---

# Spec 136 — Design: Refactor de god classes

## D1 — Conceito

O due diligence apontou 2 god classes: **`GlobalExceptionHandler` (637 linhas,
76 `@ExceptionHandler`)** e **`PixTransaction` (435 linhas)**. Objetivo:
dividir por responsabilidade **sem mudar comportamento** — refactor
comportamentalmente idêntico, **provado por testes de contrato criados ANTES**
(C3). Medição real confirmada: 637/435 linhas, 76 handlers, teste existente
`GlobalExceptionHandlerTest`.

## D2 — GlobalExceptionHandler dividido (C1, C4)

Os 76 `@ExceptionHandler` são agrupados por **domínio/categoria**, cada um um
`@ControllerAdvice` especialista (ou um `@RestControllerAdvice` que delega):

| Especialista (novo) | Domínio (exemplos de exceções) |
|---|---|
| `TransactionExceptionHandler` | transação Pix, pagamento, webhook de pagamento |
| `MerchantExceptionHandler` | merchant, conta, wallet, saque, billing |
| `AuthExceptionHandler` | auth, sessão, token, login |
| `SecurityExceptionHandler` | rate limit, CSRF, API key, acesso negado |
| `PixProviderExceptionHandler` | erros de PSP (pagar.me/asaas/mock) |
| `GenericExceptionHandler` | fallback (exceções não mapeadas → 500 padrão) |

- **Registro**: cada especialista é um bean `@ControllerAdvice` (ordem
  explícita via `@Order` para o fallback genérico) **ou** um orquestrador fino
  que delega para métodos de helpers. A classe original `GlobalExceptionHandler`
  fica **≤ 150 linhas** (vira orquestrador fino).
- **Contrato preservado**: os especialistas são chamados na MESMA ordem de
  precedência; resposta (status, corpo, headers) **idêntica** para cada
  exceção — provado pelos testes de contrato.

## D3 — PixTransaction dividido (C2 — se fizer sentido, com evidência)

- Avaliar com evidência: extrair **sub-objetos de valor** (estado da
  transação, valores monetários, campos do PSP) para classes próprias **se**
  reduzir complexidade real. **Sem mudar o modelo persistido** (colunas/
  tabelas intactas — o JPA mapeia o agregado como hoje).
- Se a divisão não reduzir complexidade mensurável (avaliação registrada no
  task), **documentar e manter** — não forçar (KISS).

## D4 — Testes de contrato ANTES do refactor (C3)

- **Criar ANTES**: `ExceptionMappingContractTest` — para cada exceção mapeada
  (76), assert de status + corpo + headers. Congela o comportamento atual.
- **Passar DEPOIS**: o refactor roda com os testes de contrato verdes SEM
  alteração — prova de refactor comportamentalmente idêntico.
- `GlobalExceptionHandlerTest` existente permanece (regressão).

## D5 — Métricas e logs preservados (C5)

- `nyx_http_5xx_total{route}` (Spec 126) e logs estruturados com a mesma
  semântica após a divisão — os handlers especialistas registram as métricas
  da mesma forma (mesmo util/medição).

## D6 — Arquivos

| Arquivo | Mudança |
|---|---|
| `.../inbound/rest/GlobalExceptionHandler.java` | ≤ 150 linhas (orquestrador fino) (D2) |
| `.../inbound/rest/handler/TransactionExceptionHandler.java` | novo (D2) |
| `.../inbound/rest/handler/MerchantExceptionHandler.java` | novo (D2) |
| `.../inbound/rest/handler/AuthExceptionHandler.java` | novo (D2) |
| `.../inbound/rest/handler/SecurityExceptionHandler.java` | novo (D2) |
| `.../inbound/rest/handler/PixProviderExceptionHandler.java` | novo (D2) |
| `.../inbound/rest/handler/GenericExceptionHandler.java` | novo (D2) |
| `.../domain/model/transaction/PixTransaction.java` | (se D3 aprovar) sub-objetos de valor |
| `src/test/.../ExceptionMappingContractTest.java` | novo (D4) |
| `src/test/.../handler/*HandlerTest.java` | novos (D4 — C4) |

## D7 — Testes (C3, C4, C5)

- Contrato dos 76 handlers (antes/depois idêntico); teste isolado por
  especialista; `mvn test` completo (1995+) verde; E2E sem regressão;
  métricas `nyx_http_5xx_total` com mesma semântica.

## D8 — Fora de escopo

Mudança de comportamento/API pública; outras classes grandes (avaliar por
evidência — só as 2 do DD).

**Âncoras validadas na spec:** `GlobalExceptionHandler.java` (637 linhas, 76
`@ExceptionHandler`); `PixTransaction.java` (435 linhas);
`GlobalExceptionHandlerTest.java` (existe); Spec 126 (métricas de erro);
Spec 137 (testes de integração — podem rodar em conjunto).
