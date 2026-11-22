---
name: Testes de contexto (configuração e boot verificáveis)
status: audited
---

# Spec 68 — Task: Testes de contexto (configuração e boot verificáveis)

Checklist de implementação. Ordem: **F0 (infra de teste) → F1 (parse YAML) →
F2 (conditionals + binding) → F3 (smoke context) → F4 (CI) → F5 (prova)**.
Princípios: camadas 1–3 no `mvn test` default (rápidas, sem infra); camada 4
opt-in (`-Pcontext-tests`); fidelidade ao parser/binding do boot (D2/D4).

## F0 — Infra de teste

- [x] 1. **Package `com.foxcash.context`** em `src/test/java/com/foxcash/context/`.
- [x] 2. **`TestYaml`** (helper): `loadProperties(String resourceName)` via
      `YamlPropertiesFactoryBean` + `resolved(String key)` que resolve
      placeholders `${ENV:default}` (ex.: `${NYX_SECURITY_ALERT_STORE:memory}`
      → `memory`) com um `PropertyPlaceholderHelper`. Teste do helper incluído
      (resolução de default e de env).
- [x] 3. **Surefire** (pom.xml): `excludedGroups=context` no default +
      profile `context-tests` (`groups=context`, `excludedGroups` vazio).
      Verificação: `mvn test` roda sem o grupo; `mvn test -Pcontext-tests`
      seleciona só o grupo.

## F1 — Parse do YAML (camada 1)

- [x] 4. **`ApplicationConfigYamlTest`**:
      - [x] 4a. Carrega TODOS os `application*.yml` de
            `src/main/resources` (application, dev, prod, railway — fonte única
            de caminhos, sem hardcode além do prefixo) e asserta parse OK +
            properties não-vazias.
      - [x] 4b. Assert de chave crítica: `foxcash.security.alert.store`
            resolve para `memory` por default no yml base (via `TestYaml`).
      - [x] 4c. **Caso de regressão L16**: YAML montado em string com a chave
            `alert:` duplicada no mesmo mapeamento (reprodução literal do bug)
            → asserta que `YamlPropertiesFactoryBean.getObject()` lança
            `DuplicateKeyException` (a sentinela funciona se o bug voltar).
- [x] 5. `mvn test` verde (F1 isolada) — o parse do yml atual passa.

## F2 — Conditionals + binding (camadas 2 e 3)

- [x] 6. **`ConditionalStoreSelectionTest`** (`ApplicationContextRunner` com os
      4 stores + um bean consumidor para o caso inválido):
      - [x] 6a. `alert.store` e `rate-limit.store` = `memory` (explícito) → bean
            em memória presente; Redis ausente.
      - [x] 6b. `= redis` → Redis presente; em memória ausente.
      - [x] 6c. ausente → default `memory` (matchIfMissing).
      - [x] 6d. `= xxx` (inválido) → nenhum store; consumidor falha
            (`NoSuchBeanDefinitionException` no startup).
      - [x] 6e. `body-inspection.enabled=false` → `RequestBodyInspectionFilter`
            permanece no contexto.
- [x] 7. **`SecurityAlertPropertiesBindingTest`** (`Binder` com as properties do
      yml real via `TestYaml`): defaults (enabled=true, store=memory,
      throttleSeconds=300, login 10/60, rate-limit 20/60, signature 20/60,
      body 5/60) + override (`store=redis` refletido).
- [x] 8. `mvn test` verde (F1+F2 isoladas).

## F3 — Smoke de contexto (camada 4)

- [x] 9. **`FoxCashContextSmokeTest`** (`@Tag("context")`,
      `@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@ActiveProfiles("dev")`):
      - [x] 9a. Contexto carrega (app bean presente).
      - [x] 9b. Beans-chave: `SecurityAlertPublisher`, `AlertCounterStore`
            (instância `InMemoryAlertCounterStore` — default memory),
            `RateLimitStore` (instância `InMemoryRateLimitStore`),
            `RequestBodyInspectionFilter`, `WebhookSignatureFilter`,
            `ObjectMapper`.
      - [x] 9c. `GET /actuator/health` (TestRestTemplate, porta aleatória,
            sem auth) → 200 + `status UP`.
      - [x] 9d. Header do arquivo documenta o pré-requisito: Postgres local
            (Spec 1) — falha com mensagem clara se fora.
- [x] 10. `mvn test -Pcontext-tests` verde (com Postgres local).
- [x] 11. `mvn test` (default) **não** roda o grupo context (sem banco) e
       continua verde.

## F4 — CI

- [x] 12. **Job `test`** (ci.yml): instala PostgreSQL (passo copiado do job
       `e2e` — apt + CREATE USER/DATABASE nyxcash) e adiciona o passo
       `mvn -B test -Pcontext-tests` após o `mvn -B verify`.
- [x] 13. Verificação: workflow válido (lint YAML); `mvn -B verify` default e o
       passo do grupo context compatíveis (sem conflito de portas/banco com o
       job e2e — cada job tem runner próprio).

## F5 — Prova final

- [x] 14. `mvn test` default: **≥ 1169/0/0** + os novos testes das camadas 1–3
       (contagem do grupo default aumenta; nenhum teste existente quebrou).
- [x] 15. `mvn test -Pcontext-tests`: verde local (Postgres nativo) — smoke
       contexto + health UP.
- [x] 16. **Prova de captura**: (a) reverter temporariamente o yml (reintroduzir
       o 2º bloco `alert:`) → `ApplicationConfigYamlTest` FALHA com
       `DuplicateKeyException`; (b) restaurar → verde. Evidência anotada na
       task/roadmap (a sentinela pega a classe L16).
- [x] 17. Roadmap/requirements/task: status `implemented`; commit. Pronto para
       `/spec-audit`.

## Implementação (2026-08-11) — F0–F5 concluídas, status `implemented`

- **Camadas 1–3 (default `mvn test`):** 14 testes novos —
  `ApplicationConfigYamlTest` 3/0/0 (parse de TODOS os `application*.yml` +
  default do `alert.store` + regressão L16), `ConditionalStoreSelectionTest`
  9/0/0 (alert.store/rate-limit.store: memory explícito / redis / ausente→
  default / inválido→falha no consumidor + body-inspection) e
  `SecurityAlertPropertiesBindingTest` 2/0/0 (defaults reais do yml + override).
- **Camada 4 (opt-in):** `FoxCashContextSmokeTest` 2/0/0 em ~11s via
  `mvn test -Pcontext-tests` (app REAL + Postgres local: contexto sobe,
  beans-chave do tipo esperado, `/actuator/health` 200 UP).
- **`mvn test` default:** **1183/0/0** (1169 + 14) — o smoke NÃO roda no
  default (surefire `excludedGroups=context`), comprovado pela contagem e pela
  ausência do relatório do smoke.
- **Prova de captura (task 16):** reintroduzida a chave `alert:` duplicada no
  `application.yml` → `ApplicationConfigYamlTest` **FALHOU** (2 erros,
  `DuplicateKeyException`); restaurado → verde. A sentinela pega a classe L16.
- **Achado de implementação (profile Maven):** `<excludedGroups></excludedGroups>`
  vazio NO profile **não** sobrescreve o default — o Maven MESCLA os
  `<configuration>` (resultado: `groups=context` + `excludedGroups=context` →
  0 testes). Corrigido com **`combine.self="override"`** no `<configuration>`
  do profile (substitui o config do build em vez de mesclar) — verificado via
  `help:effective-pom` e execução real (2/2).
- **CI (F4):** job `test` ganhou a instalação do PostgreSQL (passo copiado do
  job `e2e`) + passo `mvn -B test -Pcontext-tests` com `DB_URL/DB_USERNAME/
  DB_PASSWORD` + `SPRING_PROFILES_ACTIVE=dev`.
- Pronto para `/spec-audit`.

## `/spec-audit` (2026-08-11) — 6/6 critérios OK, 0 achados abertos

Verificações: **critério 1** (parse) — `ApplicationConfigYamlTest` 3/0/0 com o
loader do boot + **prova de captura L16** (duplicata reintroduzida → FALHA;
restaurada → verde); **critério 2** (conditionals) — 9/0/0 (memory/redis/
ausente→default/inválido→falha no consumidor + body-inspection); **critério 3**
(binding) — 2/0/0, agora lendo os ints REAIS do yml (ver achado 1); **critério
4** (smoke) — `FoxCashContextSmokeTest` 2/0/0 via `-Pcontext-tests` (app real +
Postgres, health UP; fora do default — contagem 1189 prova); **critério 5**
(CI) — job `test` com Postgres + passo `-Pcontext-tests`; `mvn -B verify`
local = **BUILD SUCCESS + "All coverage checks have been met"** (o comando
exato do job test; o run do CI fica para conferência do usuário no browser);
**critério 6** (sem regressão) — default **1189/0/0** (1169 + 20) + context
2/0/0 = **22 testes novos**.

**Achados CORRIGIDOS na auditoria:**
1. **MÉDIO-CRÍTICO — `TestYaml` usava `YamlPropertiesFactoryBean`, que DESCARTA
   valores escalares não-String** (ints/bools/floats: `throttle-seconds: 300`,
   `threshold: 10`, `window-seconds: 60` somem do resultado — verificado
   empiricamente). O binding (camada 3) passava por causa dos defaults de
   CÓDIGO, não dos valores do yml. **Corrigido** para `YamlPropertySourceLoader`
   (o loader REAL do boot — `ConfigDataEnvironmentPostProcessor`): preserva
   todos os scalars, mantém a mesma `DuplicateKeyException` do boot. Design D2
   corrigido. O smoke (camada 4) nunca teve o problema (usa o contexto real).
2. **MENOR — teste do helper** (task item 2) não existia → criado
   `TestYamlTest` (6/0/0: parse, default, caminho ENV com lookup injetável,
   placeholder sem default fica literal, resolvedProperties, chave ausente).
3. **DOC — requirements critério 6** com contagens confusas → reescrito (22
   novos: 20 default + 2 context).

Pendência registrada: run do CI (o usuário confere no browser após o push).
Fim do ciclo SDD (roadmap/requirements/task `audited`).
