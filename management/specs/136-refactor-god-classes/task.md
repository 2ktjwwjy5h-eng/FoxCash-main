---
name: Refactor god classes — GlobalExceptionHandler (637) + PixTransaction (435)
status: audited
---

# Spec 136 — Task: Refactor de god classes

Ordem: T0 (congelar contrato) → T1 (dividir GlobalExceptionHandler) → T2
(avaliar PixTransaction) → T3 (regressão).

## T0 — Congelar o contrato ANTES (critério C3)

- [x] T0.1: `ExceptionMappingContractTest` — para cada um dos 76+
      `@ExceptionHandler`, assert de status + corpo (congela o comportamento
      atual). **Criado ANTES do refactor**: 87 cases (69 domínio + 18
      framework/multi-exceção) cobrindo TODAS as exceções mapeadas, incluindo
      os handlers com `code` dinâmico (`PixCodeParseException`/
      `PvsAmountException`/`PvsLimitExceededException`/`FraudRuleViolationException`).
- [x] T0.2: Rodar o teste de contrato contra o código atual — **verde** antes
      de tocar no refactor (baseline: 3/3 PASS).

## T1 — GlobalExceptionHandler dividido (critérios C1, C4)

- [x] T1.1: Criar os especialistas `@ControllerAdvice` por domínio com `@Order`
      explícito (genérico por último — `handler/*`):
      - `handler/TransactionExceptionHandler` (8) — transação, cobrança,
        chargeback, fraude, idempotência, PVS;
      - `handler/PixProviderExceptionHandler` (1) — `PspTimeoutException` (502);
      - `handler/MerchantExceptionHandler` (21) — lojista/conta, wallet, saque,
        billing, destinos, credenciais, webhook de saída;
      - `handler/StoreExceptionHandler` (24) — loja white-label (stores, botões,
        mensagens, planos, entregas, bots, CRM, broadcasts, tracking, perfis);
      - `handler/AuthExceptionHandler` (11) — auth, sessão, token de reset, MFA,
        Turnstile, gestão de perfis master;
      - `handler/SecurityExceptionHandler` (4) — acesso negado, permissão do
        master, step-up, documento inválido.
      O `GlobalExceptionHandler` fica com os 7 handlers GENÉRICOS/framework
      (400/404/405/409/500 + métrica 5xx) e `@Order(LOWEST_PRECEDENCE)` —
      **89 linhas (≤ 150, critério C1 OK)**. Nota de desvio: o design D6
      listava um `GenericExceptionHandler` separado; a responsabilidade
      "genérico/fallback" ficou no próprio `GlobalExceptionHandler`
      (orquestrador fino — opção explícita do C1 "ou vira orquestrador fino").
- [x] T1.2: Mover os 76 handlers para os especialistas; `GlobalExceptionHandler`
      fica **≤ 150 linhas** (89 — orquestrador fino + fallback genérico).
- [x] T1.3: Teste isolado por especialista (`*HandlerTest`, 6 classes — 69
      testes): exceção X → 4xx/5xx com corpo Y (C4).
- [x] T1.4: Teste de contrato (T0) **passa com a tabela byte-idêntica** — a
      lista `CONTRACT` (exceção → status → code) não mudou entre antes/depois;
      apenas o *roteamento* (`invoke`) aponta para os especialistas (mecânico,
      documentado no próprio teste). **Prova de refactor comportamentalmente
      idêntico (C3).**

## T2 — PixTransaction (critério C2 — avaliado e MANTIDO, com evidência)

- [x] T2.1: **Avaliação com evidência (2026-08-16):** `PixTransaction` (435
      linhas) foi analisado campo a campo. A "complexidade" da classe NÃO é
      coesão perdida — é a **escada de overloads** `create` (5) / `restore` (7)
      criada por evolução aditiva (cada spec acrescentou campos opcionais:
      `buyerIdHash`, `planId`, `botId`, `platform`, `platformUserId`, provas de
      entrega, `provider`, `profileId`). Os campos pertencem ao agregado
      (todos persistidos em colunas próprias, sem blobs). Extrair value objects
      (`PspChargeData`, `BuyerRef`) **reduziria a contagem de parâmetros mas**:
      (a) mudaria o construtor privado, os 12 overloads, o `PixTransactionMapper`
      e TODOS os call-sites (services de criação/restore); (b) adicionaria uma
      camada de abstração sem ganho comportamental; (c) arriscaria regressão na
      suíte de 1995+ testes por zero ganho de manutenibilidade real (o mapper
      continuaria espelhando campo a campo).
- [x] T2.2: **Decisão: MANTER** (não forçar — KISS, opção explícita do critério
      C2: "Se a divisão não reduzir complexidade real, documentar e manter").
      Registro: a escada de overloads é o ponto de atrito real; mitigação
      documentada em `management/lessons.md` (L20 — padrão de overloads dos
      aggregates: preservar `create`/`restore` aditivos, jamais reordenar
      parâmetros; novo campo opcional = overload novo no fim).

## T3 — Regressão (critérios C3, C5)

- [x] T3.1: `mvn test` completo verde (1995+ testes, 0 falhas) + testes novos
      da spec (contrato 3 + especialistas 69 + genérico reescrito). E2E sem
      regressão.
- [x] T3.2: Métricas `nyx_http_5xx_total{route}` (Spec 126) preservadas — o
      `handleUnexpected` (único handler com métrica) permanece no
      `GlobalExceptionHandler` com a MESMA semântica; teste dedicado
      (`unexpectedIncrements5xxMetricByRoute`) verde antes/depois.

**Âncoras validadas na spec:** `GlobalExceptionHandler.java` (637 linhas → 89);
`PixTransaction.java` (435 linhas — avaliado, mantido); `GlobalExceptionHandlerTest.java`
(existe — reescrito para os handlers genéricos; domínio nos `*HandlerTest`);
Spec 126 (métrica preservada); Spec 137 (roda em conjunto — mesma sessão).

## Auditoria (2026-08-16) — `/spec-audit 136`

**5/5 critérios OK, 0 achados abertos.** Verificações contra o código real (após
o commit `caace79`):

- **C1** — `GlobalExceptionHandler` **113 linhas** (≤ 150) — orquestrador fino com
  7 handlers genéricos/framework (400/404/405/409 + fallback 500 com métrica),
  `@Order(Ordered.LOWEST_PRECEDENCE)`; 6 especialistas em `handler/*` com
  `@Order(Ordered.HIGHEST_PRECEDENCE)`: Transaction 8, PixProvider 1, Merchant 21,
  Store 24, Auth 11, Security 4 (= 69 domínio + 7 genérico = 76). Cada um
  `@RestControllerAdvice` próprio (contagem de `@ExceptionHandler` conferida).
- **C2** — `PixTransaction` avaliado com evidência e **MANTIDO** (task T2 + L20 em
  `management/lessons.md`): a "complexidade" é a escada aditiva de overloads
  `create`/`restore`; extrair value objects mudaria construtor/mapper/call-sites
  sem ganho comportamental (KISS — opção "documentar e manter" do C2).
- **C3** — `ExceptionMappingContractTest` **3/3** (87 cases) verde após o refactor
  com a tabela `CONTRACT` byte-idêntica (só o roteamento aponta aos especialistas);
  suíte completa `mvn test` **EXIT 0** (2021+ testes).
- **C4** — 6 `*HandlerTest` isolados: Auth 11, Merchant 21, PixProvider 1,
  Security 4, Store 24, Transaction 8 = **69 testes, 0 falhas** (relatórios
  surefire conferidos).
- **C5** — `nyx_http_5xx_total{route}` (Spec 126) preservada no `handleUnexpected`
  (linha 107-109) com a MESMA semântica + teste dedicado
  `unexpectedIncrements5xxMetricByRoute` verde.

Fim do ciclo SDD (roadmap/requirements/design/task `audited`).
