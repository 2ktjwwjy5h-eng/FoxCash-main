---
name: Testes de contexto (configuração e boot verificáveis)
status: audited
references:
  - "management/vision.md#V48"
  - "management/lessons.md#L16"
  - "management/lessons.md#L10"
  - "management/specs/66-resiliencia-operacional/requirements.md"
  - "management/specs/43-rate-limit-redis/requirements.md"
  - "management/specs/64-sec-observability-incident-response/requirements.md"
---

# Spec 68 — Testes de contexto (configuração e boot verificáveis)

## O que entrega

Fecha a **lacuna de verificação de configuração** exposta e medida na auditoria
da Spec 66 (2026-08-11): a chave `alert:` **duplicada** no `application.yml`
(Spec 66 Fase B colidindo com o Spec 64) quebrou o **boot real** com
`DuplicateKeyException` — mas **`mvn test` passou 1169/0/0** porque **não existe
nenhum teste de contexto** que carregue o `application.yml` completo (todos os
testes são unitários com mocks/slices; o YAML só é parseado no boot real, que só
acontece nos jobs de CI que sobem a app — e o CI nunca rodou sobre esses commits,
16 à frente do origin). O mesmo gap cobre a classe de bug do **L10** (2 beans
`Duration` no contexto → boot falha só no arranque real).

Esta spec entrega **4 camadas de teste de contexto** que rodam com o **mesmo
parser/loader do Spring** usado no boot:

1. **Parse do YAML de configuração** (`ApplicationConfigYamlTest`) — carrega
   TODOS os `application*.yml` (base, dev, prod, railway) via
   `YamlPropertiesFactoryBean` (mesmo parser do Spring Boot — SnakeYAML) e
   **falha em chave duplicada/erro de sintaxe** no `mvn test` local, **sem
   banco** e sem subir a app. É a regressão direta do caso L16: se a chave
   duplicada for reintroduzida, o `mvn test` quebra antes do push.
2. **Seleção condicional de beans** (`ConditionalStoreSelectionTest`) — usa
   `ApplicationContextRunner` para validar as 4 combinações de
   `foxcash.security.alert.store` e `foxcash.security.rate-limit.store`
   (`memory` explícito → só o store em memória; `redis` → só o Redis; ausente →
   default `memory` via `matchIfMissing`; valor inválido → **nenhum** bean →
   falha de contexto, o comportamento real). Também valida o filtro
   `foxcash.security.body-inspection.enabled`.
3. **Binding de properties** (`SecurityAlertPropertiesBindingTest`) — vincula o
   `SecurityAlertProperties` via `Binder` (o mesmo mecanismo do
   `@ConfigurationProperties`) contra os **defaults reais do yml** (enabled,
   store, throttle-seconds, limiares/janelas dos 4 tipos) + override respeitado.
4. **Smoke de contexto completo** (`FoxCashContextSmokeTest`,
   `@Tag("context")`) — `@SpringBootTest` com perfil `dev` + Postgres que **sobe
   a app real** (Flyway no-op, seed idempotente, web server em porta aleatória),
   valida beans-chave presentes e `/actuator/health` **UP**. **Excluído do
   `mvn test` default** (surefire `excludedGroups=context` — o job de teste do
   CI não tem Postgres); roda com `mvn test -Pcontext-tests` local (Postgres
   nativo, Spec 1) e no CI (o job `test` ganha Postgres e um passo dedicado).

O resultado: a classe de erro "config que só aparece em produção" passa a falhar
**no `mvn test` local, antes do push** — e a fiação do contexto (beans
condicionais, binding, health) fica coberta em todo commit.

## Critérios de aceite

1. **Parse do YAML no `mvn test` local**: `ApplicationConfigYamlTest` carrega
   TODOS os `application*.yml` do classpath (`src/main/resources`) com o
   `YamlPropertiesFactoryBean` do Spring e **falha com `DuplicateKeyException`
   (ou erro de sintaxe) quando o arquivo contém chave duplicada ou YAML
   inválido**. Caso de regressão: com o yml ATUAL (corrigido na auditoria da
   Spec 66) o teste passa; se um segundo bloco `alert:` for reintroduzido, o
   teste falha. Sem banco, sem Spring Boot completo (rápido, roda em todo
   `mvn test`).
2. **Seleção condicional correta** (`ApplicationContextRunner`, sem infra):
   - `alert.store=memory` e `rate-limit.store=memory` (explícito) → bean do
     store em memória presente; bean Redis ausente;
   - `alert.store=redis` e `rate-limit.store=redis` → bean Redis presente; em
     memória ausente;
   - propriedade **ausente** → default `memory` (o `matchIfMissing=true` ativa
     o store em memória; o Redis fica ausente);
   - valor **inválido** (ex.: `foxcash.security.alert.store=xxx`) → **nenhum**
     `AlertCounterStore`/`RateLimitStore` registrado → contexto falha ao
     injetar o consumidor (comportamento real, fail-fast por bean ausente).
   - `foxcash.security.body-inspection.enabled=false` → o
     `RequestBodyInspectionFilter` permanece no contexto (o desligamento é por
     `shouldNotFilter`, não por bean ausente).
3. **Binding de properties**: `SecurityAlertProperties` vinculado via `Binder`
   com as properties do `application.yml` real produz os defaults documentados
   (enabled=true, store=memory, throttleSeconds=300, login-failure 10/60,
   rate-limit 20/60, signature-failure 20/60, body-inspection 5/60); um
   override (ex.: `foxcash.security.alert.store=redis`) é refletido.
4. **Smoke de contexto completo** (`FoxCashContextSmokeTest`,
   `@Tag("context")`, `@SpringBootTest(webEnvironment = RANDOM_PORT)`, perfil
   `dev`): o contexto sobe (Flyway/Hibernate validate OK no Postgres local),
   beans-chave presentes (`SecurityAlertPublisher`, o store ativo conforme
   config, `RequestBodyInspectionFilter`, `WebhookSignatureFilter`,
   `ObjectMapper`) e `GET /actuator/health` responde `{"status":"UP",...}`
   (sem auth). **Fora do `mvn test` default**: surefire `excludedGroups=context`
   (o job `test` do CI não tem Postgres); roda com `-Pcontext-tests` local e no
   CI.
5. **CI**: o job `test` ganha a instalação do PostgreSQL (mesmo passo do job
   `e2e`) e um passo `mvn -B test -Pcontext-tests` (ou `verify` + grupo) que
   roda o smoke; o job default continua `mvn -B verify` (sem grupo context,
   sem Postgres) e passa.
6. **Sem regressão**: `mvn test` default (sem o grupo context) verde com
   **≥ 1169 testes** e o grupo context (`-Pcontext-tests`) verde com Postgres
   local. Total de **22 testes novos** — 20 no default (`TestYamlTest` 6 +
   `ApplicationConfigYamlTest` 3 + `ConditionalStoreSelectionTest` 9 +
   `SecurityAlertPropertiesBindingTest` 2) + 2 no grupo context
   (`FoxCashContextSmokeTest`). Não altera contrato de API nem comportamento
   de runtime.

## Filosofia

- **Mesmo parser do boot**: o parse do YAML usa `YamlPropertiesFactoryBean` do
  Spring (SnakeYAML) — não PyYAML/Jackson/jq — para que a falha de teste seja
  **exatamente** a falha de produção (`DuplicateKeyException`), sem falso
  positivo/negativo por parser divergente.
- **Rápido por padrão, completo por opt-in**: as camadas 1–3 não exigem banco
  nem contexto (milissegundos) e rodam em todo `mvn test`; o smoke completo
  (camada 4) exige Postgres e fica fora do default — coberto localmente (o
  ambiente dev tem Postgres nativo, Spec 1) e no CI (job `test` com Postgres).
- **Fidelidade à configuração real**: os testes de conditional/binding leem os
  MESMOS valores/estruturas do `application.yml` real (e não duplicam defaults
  em código de teste) — o teste é a sentinela de que o yml e o código continuam
  alinhados.
- **Capturar a classe de erro, não só o caso**: o parse pega QUALQUER chave
  duplicada/sintaxe (o caso `alert:` de hoje e os de amanhã); o smoke pega
  QUALQUER fiação quebrada (o caso `Duration` do L10 e afins).

## Dependências

- `management/specs/66-resiliencia-operacional/` (achado L16 que origina a spec;
  stores `alert.store`)
- `management/specs/43-rate-limit-redis/` (stores `rate-limit.store`)
- `management/specs/64-sec-observability-incident-response/`
  (`SecurityAlertProperties`, `SecurityAlertPublisher`)
- `management/lessons.md` (L10 — 2 beans `Duration`; L16 — chave YAML duplicada)
- `pom.xml` (surefire: `excludedGroups` + profile `context-tests`)
- `.github/workflows/ci.yml` (job `test`: Postgres + passo do grupo context)
- `src/main/resources/application*.yml` (objeto do teste de parse)
