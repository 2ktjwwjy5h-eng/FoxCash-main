---
name: Testes de contexto (configuração e boot verificáveis)
status: audited
---

# Spec 68 — Design: Testes de contexto (configuração e boot verificáveis)

## Contexto

- **L16 (2026-08-11):** a chave `alert:` duplicada no `application.yml` (Spec 66
  Fase B `caa8b7d` colidindo com o Spec 64) quebrou o boot com
  `DuplicateKeyException` e `mvn test` passou 1169/0/0 — **nenhum teste carrega
  o YAML completo** (todos unitários com mocks/slices; sem `@SpringBootTest`,
  sem `src/test/resources/application.yml`). O CI (e2e/flyway-coldstart, que
  sobem a app) pegaria — mas nunca rodou (16 commits à frente do origin).
- **L10 (2026-08-04):** 2 beans `Duration` no contexto → boot falhava apenas no
  arranque real (`@Qualifier` ausente). A mesma classe de erro (fiação do
  contexto) não é coberta por nenhum teste.
- Os stores condicionais já existem (`@ConditionalOnProperty`):
  `InMemoryAlertCounterStore` (`alert.store=memory`, `matchIfMissing=true`),
  `RedisAlertCounterStore` (`alert.store=redis`, `matchIfMissing=false`);
  `InMemoryRateLimitStore`/`RedisRateLimitStore` idênticos para
  `rate-limit.store`. Nenhum teste valida a seleção nem o valor inválido.
- `SecurityAlertProperties` (`@ConfigurationProperties("foxcash.security.alert")`)
  tem defaults no código (enabled=true, store=memory, throttleSeconds=300,
  thresholds 10/60·20/60·20/60·5/60) **espelhados** no `application.yml` — sem
  teste que prove que os dois não divergiram.

## Decisões

### D1 — Camadas 1–3 no `mvn test` default; camada 4 opt-in (`@Tag("context")`)

As camadas 1–3 não exigem banco nem Spring Boot completo (milissegundos) →
rodam em todo `mvn test` (e `mvn verify`). A camada 4 (`@SpringBootTest` +
Postgres) fica **fora do default** via surefire `excludedGroups=context`, porque
o job `test` do CI não tem Postgres e `mvn test` local não deve exigir banco
para quem não tem (KISS: o default continua rápido e sem infra; o completo é
opt-in explícito).

### D2 — Camada 1: parse com o MESMO loader do boot (`YamlPropertySourceLoader`)

O Spring Boot carrega `application*.yml` no arranque via
`YamlPropertySourceLoader` (SnakeYAML) — a chave duplicada gera
`org.yaml.snakeyaml.constructor.DuplicateKeyException` **na hora do parse**. O
teste usa **exatamente esse loader** (do `spring-boot`, já no classpath):
fidelidade total ao erro de produção E preservação de TODOS os valores
escalares (String/int/boolean).

> **Achado da auditoria (2026-08-11):** o `YamlPropertiesFactoryBean` (do
> `spring-beans`) **NÃO serve** — embora pareça o mesmo parser, ele **descarta
> valores escalares não-String** (ex.: `throttle-seconds: 300`, `threshold: 10`,
> `window-seconds: 60` somem do resultado; verificado empiricamente). O boot NÃO
> usa esse bean — usa o `YamlPropertySourceLoader` — e o binding de properties
> (camada 3) depende dos ints do yml. O `TestYaml` foi corrigido para o loader
> do boot (a camada 3 agora lê os ints reais; antes validava só os defaults de
> CÓDIGO por acidente).

Alternativas descartadas: PyYAML/Jackson/jq (parser diferente → falso
positivo/negativo) e um `@SpringBootTest` completo (lento, exige banco, não
isola o arquivo).

```java
// ApplicationConfigYamlTest (esqueleto)
class ApplicationConfigYamlTest {
    @Test
    void allApplicationYamlFilesParseWithoutDuplicateKeysOrSyntaxErrors() {
        // resources: application.yml, application-dev.yml, application-prod.yml,
        // application-railway.yml (fonte única: ClassPathResource + caminho)
        for (Resource r : yamlResources()) {
            Properties props = TestYaml.loadProperties(r); // YamlPropertySourceLoader
            assertThat(props).isNotEmpty();
        }
    }

    @Test
    void securityAlertStorePropertyResolvesToMemoryByDefault() {
        // lê foxcash.security.alert.store do application.yml agregado → "memory"
    }
}
```

**Regressão L16 (prova de que o teste pega o bug):** além do parse do yml atual
(passa), o teste inclui um caso que **monta um YAML com a chave `alert:` duas
vezes no mesmo mapeamento** (reprodução literal do bug) e asserta que o parse
LANÇA `DuplicateKeyException` — garantindo que a sentinela funciona se o bug for
reintroduzido (a flag é o comportamento do próprio parser, verificado).

### D3 — Camada 2: `ApplicationContextRunner` para os conditionals

`ApplicationContextRunner` (do `spring-boot-test`, JUnit 5) registra os beans
condicionais e valida a seleção **sem** subir o app nem o banco:

```java
// ConditionalStoreSelectionTest (esqueleto)
class ConditionalStoreSelectionTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StoresConfig.class); // os 4 stores @Component

    @Test void defaultSelectsInMemoryAlertStore() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(InMemoryAlertCounterStore.class);
            assertThat(ctx).doesNotHaveBean(RedisAlertCounterStore.class);
        });
    }
    @Test void redisSelectsRedisAlertStore() {
        runner.withPropertyValues("foxcash.security.alert.store=redis").run(ctx -> {
            assertThat(ctx).hasSingleBean(RedisAlertCounterStore.class);
            assertThat(ctx).doesNotHaveBean(InMemoryAlertCounterStore.class);
        });
    }
    @Test void invalidAlertStoreFailsWhenConsumed() {
        runner.withPropertyValues("foxcash.security.alert.store=xxx").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(AlertCounterStore.class);
            // o consumidor (SecurityAlertPublisher) exige AlertCounterStore → a
            // criação do bean falha com NoSuchBeanDefinitionException
        });
    }
    // mesmos 4 casos para rate-limit.store
    @Test void bodyInspectionDisabledKeepsFilterBean() {
        runner.withPropertyValues("foxcash.security.body-inspection.enabled=false")
              .run(ctx -> assertThat(ctx).hasSingleBean(RequestBodyInspectionFilter.class));
    }
}
```

Para o caso "valor inválido", registra-se também um bean consumidor que injeta
`AlertCounterStore` (ex.: um `@Configuration` com `@Bean Consumer(AlertCounterStore)`)
→ o contexto falha com `NoSuchBeanDefinitionException` (assert do
`assertFailure`/`ctx.getStartupFailure()`), espelhando o comportamento real de
fail-fast por bean ausente.

### D4 — Camada 3: binding via `Binder` (mesmo mecanismo do `@ConfigurationProperties`)

```java
// SecurityAlertPropertiesBindingTest (esqueleto)
class SecurityAlertPropertiesBindingTest {
    @Test void bindsRealYamlDefaults() {
        Binder binder = new Binder(ConfigurationPropertySources.from(yamlProperties()));
        SecurityAlertProperties props = binder.bind("foxcash.security.alert",
                Bindable.of(SecurityAlertProperties.class)).get();
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getStore()).isEqualTo("memory");
        assertThat(props.getThrottleSeconds()).isEqualTo(300);
        assertThat(props.getLoginFailure().getThreshold()).isEqualTo(10);
        assertThat(props.getLoginFailure().getWindowSeconds()).isEqualTo(60);
        // rate-limit 20/60, signature-failure 20/60, body-inspection 5/60
    }

    @Test void overrideIsRespected() {
        // yamlProperties() + property foxcash.security.alert.store=redis → getStore()=="redis"
    }
}
```

Os **defaults esperados vêm do `application.yml` real** (lido na camada 1) — o
teste não duplica constantes: se o yml mudar (ex.: limiar 10→15), o teste falha
e o diff fica visível (sentinela de alinhamento código↔yml).

### D5 — Camada 4: smoke de contexto completo (`@Tag("context")`)

```java
// FoxCashContextSmokeTest (esqueleto)
@Tag("context")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // FIXA os stores para asserções determinísticas (independe de env vars
        // no runner local/CI — o smoke valida FIAÇÃO + health, não a seleção
        // condicional, que é coberta na camada 2):
        properties = {
                "foxcash.security.alert.store=memory",
                "foxcash.security.rate-limit.store=memory" })
@ActiveProfiles("dev") // defaults fake locais (Spec 45/20); Postgres local (Spec 1)
class FoxCashContextSmokeTest {
    @Autowired FoxCashApplication app; // contexto carregado = critério principal
    @Autowired SecurityAlertPublisher publisher;
    @Autowired AlertCounterStore alertStore;          // InMemory (default memory)
    @Autowired RateLimitStore rateLimitStore;         // InMemory (default memory)
    @Autowired RequestBodyInspectionFilter bodyFilter;
    @Autowired WebhookSignatureFilter webhookFilter;
    @Autowired ObjectMapper objectMapper;
    @LocalServerPort int port;

    @Test void contextLoadsAndHealthIsUp() {
        assertThat(publisher).isNotNull();
        assertThat(alertStore).isInstanceOf(InMemoryAlertCounterStore.class);
        assertThat(rateLimitStore).isInstanceOf(InMemoryRateLimitStore.class);
        // health público (liveness/readiness): GET /actuator/health → 200 UP
        // via TestRestTemplate (sem auth — /actuator/health/** é permitAll)
    }
}
```

- O boot roda Flyway (no-op se o banco já está na versão atual — idempotente),
  Hibernate `ddl-auto=validate` e o seed do master (idempotente) — **não
  destrutivo** (só lê/valida; não trunca nada).
- `RANDOM_PORT` + `TestRestTemplate` valida o stack web completo (inclui o
  `requiresChannel`/CSP/compression) com `require-https=false` do perfil dev.
- Requer Postgres local (Spec 1: serviço `postgresql-x64-18`, porta 5432,
  banco `nyxcash`) — documentado no header; falha com mensagem clara se o
  banco estiver fora (a falha é a sentinela, não um erro silencioso).

### D6 — Config do surefire (pom.xml) + profile Maven

```xml
<!-- pom.xml → <build><plugins> (junto ao spring-boot-maven-plugin) -->
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <excludedGroups>context</excludedGroups>  <!-- default: smoke fora (sem DB) -->
  </configuration>
</plugin>
<!-- profile explícito para rodar só o grupo context -->
<profiles>
  <profile>
    <id>context-tests</id>
    <build><plugins><plugin>
      <artifactId>maven-surefire-plugin</artifactId>
      <configuration>
        <groups>context</groups>
        <excludedGroups></excludedGroups>  <!-- anula o default -->
      </configuration>
    </plugin></plugins></build>
  </profile>
</profiles>
```

- `mvn test` → roda tudo **menos** o grupo `context` (rápido, sem banco).
- `mvn test -Pcontext-tests` → roda **apenas** o grupo `context` (com Postgres).
- CI job `test`: instala Postgres (passo copiado do job `e2e`), roda
  `mvn -B verify` (default) e depois `mvn -B test -Pcontext-tests`.

### D7 — Localização e nomenclatura

- Package: `com.foxcash.context` (test) — `src/test/java/com/foxcash/context/`.
- Nomes: `ApplicationConfigYamlTest`, `ConditionalStoreSelectionTest`,
  `SecurityAlertPropertiesBindingTest`, `FoxCashContextSmokeTest`.
- Helper compartilhado: `TestYaml` (carrega `Properties` de um `application*.yml`
  via `YamlPropertiesFactoryBean` e resolve placeholders `${...}` com os
  defaults do próprio arquivo — para ler `foxcash.security.alert.store` do yml
  real, o placeholder `${NYX_SECURITY_ALERT_STORE:memory}` resolve para
  `memory`). Reusado pelas camadas 1 e 3.

## Arquivos

| Arquivo | Ação |
|---|---|
| `src/test/java/com/foxcash/context/TestYaml.java` | novo — helper: `loadProperties(String resourceName)`, `resolved(String key)` (resolve `${ENV:default}` com um `PropertyPlaceholderHelper` simples) |
| `src/test/java/com/foxcash/context/ApplicationConfigYamlTest.java` | novo — parse de todos os `application*.yml` (falha em duplicata/sintaxe) + caso de regressão (YAML duplicado montado → `DuplicateKeyException`) + asserts de chaves críticas (alert.store default memory) |
| `src/test/java/com/foxcash/context/ConditionalStoreSelectionTest.java` | novo — `ApplicationContextRunner`: alert.store e rate-limit.store (memory/redis/ausente/inválido) + body-inspection.enabled |
| `src/test/java/com/foxcash/context/SecurityAlertPropertiesBindingTest.java` | novo — `Binder` com properties reais (defaults) + override |
| `src/test/java/com/foxcash/context/FoxCashContextSmokeTest.java` | novo — `@Tag("context")` `@SpringBootTest(RANDOM_PORT)` perfil dev: contexto + beans + health UP |
| `pom.xml` | mod — surefire `excludedGroups=context` + profile `context-tests` |
| `.github/workflows/ci.yml` | mod — job `test`: instala Postgres + passo `mvn -B test -Pcontext-tests` |

## Fora de escopo

- Testcontainers/H2 para o smoke (Docker indisponível — Spec 1; H2 incompatível
  com migrations Postgres). O smoke usa o Postgres real (local/CI).
- Testes de contexto para TODOS os `@ConfigurationProperties` (apenas
  `SecurityAlertProperties` como piloto; a camada 1 (parse) + 3 (binding)
  provam o padrão, estendível).
- Converter testes unitários existentes para `@SpringBootTest`.
- Cobertura de integração de banco (já existe: CI e2e + flyway-coldstart/upgrade).
