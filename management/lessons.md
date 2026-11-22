# Lições Aprendidas — FoxCash

Padrões que funcionaram e armadilhas a evitar. Referenciadas por specs como
`L1`, `L2`, etc.

## L1 — Ambiente sem JDK/Maven bloqueia a validação

**Problema:** a máquina de desenvolvimento não possui JDK 21 nem Maven. O
scaffold completo foi escrito, mas nunca compilado/testado — o que impede a
verificação da Spec 2.

**Ação:** antes de qualquer evolução funcional, instalar o toolchain
(Spec 1) e rodar `mvn test` (Spec 2). Não dar um spec como `implemented` sem
build verde.

**Resolvido (2026-08-03):** JDK 21.0.12 (Temurin, via winget) e Maven 3.9.11
(extraído em `~/tools/apache-maven-3.9.11`; env persistido em `~/.bashrc`).
`mvn test` verde: 16 testes, 0 falhas. **Resolvido (2026-08-03):** PostgreSQL 18
nativo em uso (serviço `postgresql-x64-18`, porta 5432); smoke test de startup e
suíte E2E passando.

## L2 — Git sem commit inicial

**Problema:** repositório `git init` existe, mas não há nenhum commit — todo o
código está `untracked`. Risco de perda de trabalho.

**Ação:** versionar o scaffold logo após a verificação do build (Spec 3).

## L3 — Docker indisponível → PostgreSQL nativo

**Problema:** a engine do Docker não sobe nesta máquina (exige WSL2 + terminal
elevado + reinício). O `docker-compose.yml` (PostgreSQL) bloqueava o smoke test
e a suíte E2E.

**Ação:** usar o PostgreSQL nativo do Windows (instalador oficial; serviço
`postgresql-x64-18` na porta 5432). Criar banco/usuário via `psql` como
superusuário `postgres`. O `docker-compose.yml` permanece apenas como alternativa
opcional para máquinas com engine disponível.

## L4 — `java.version` do pom divergindo do JDK instalado

**Problema:** `pom.xml` definia `<java.version>25</java.version>`, mas a máquina
tem JDK 21 — `mvn clean test` falhava com "release version 25 not supported"
(o build incremental passava por classes já compiladas, mascarando o erro).

**Ação:** alinhar `java.version` ao JDK instalado (`21`) e rodar `mvn clean test`
(não apenas `mvn test`) após mudanças no pom.

## L5 — Teste em diretório errado gera `Unresolved compilation problems`

**Problema:** após mover `Merchant` para `domain/model/merchant/`, o
`MerchantTest.java` permaneceu em `domain/model/` (package declarado
`...merchant`, caminho antigo). O `.class` obsoleto em `target/` quebrava a
suíte com "Unresolved compilation problems".

**Ação:** mover o teste para o diretório correspondente ao package declarado e
rodar `mvn clean test` para eliminar classes obsoletas.

## L6 — global-setup E2E precisa limpar TODAS as tabelas de domínio (não só as "principais")

**Problema:** o `e2e-tests/global-setup.js` truncava apenas
`invoices, pix_transactions, merchants`. Quando novas tabelas com FK chegaram
(`chargebacks`, `withdrawals`, `merchant_risk`, `notification_outbox`,
`master_sessions`, `masters`), uma segunda execução da suíte E2E quebraria por
violação de FK (dados residuais apontando para merchants apagados).

**Ação (Spec 19, 2026-08-04):** o TRUNCATE deve listar **todas** as tabelas de
domínio, filhas antes dos pais (`chargebacks, withdrawals, merchant_risk,
notification_outbox, invoices, pix_transactions, merchants, master_sessions,
masters`), com `RESTART IDENTITY CASCADE`. **Padrão a seguir:** ao adicionar
qualquer tabela nova, atualizar o `global-setup.js` na mesma mudança — o E2E
repetido é parte do contrato.

## L7 — Auditoria de spec = validar contra a API real, não só testes unitários

**Problema:** o risco de marcar uma spec como `audited` confiando só em
`mvn test` é real — o teste unitário valida o serviço com mocks, não o
endpoint exposto.

**Ação (Specs 14/15/16, 2026-08-04):** a auditoria subiu o app (`mvn
spring-boot:run`) e validou por **curl real**: saque 201/409, block/release
204 com **saldo intacto** após bloqueio (prova de que o débito não acontece),
suspend/release refletido no banco (`SELECT status FROM merchants`), 400 em
parâmetro inválido, 401 sem token nas rotas master, 403 com token de lojista.
**Padrão a seguir:** auditoria = testes unitários + E2E + pelo menos um smoke
manual dos endpoints novos.

## L8 — `master` seed pode sumir com o global-setup; re-seed é no boot

**Problema:** durante a auditoria, o login master falhou com "credenciais
desconhecidas" — o `global-setup.js` (L6) também trunca `masters`, apagando o
perfil master seedado em sessões anteriores.

**Ação:** com `masters` vazio, o boot re-seeda com as credenciais default de
dev (`master` / `dev-only-master-seed-password`, config `foxcash.master.*`).
**Padrão a seguir:** após rodar a suíte E2E, se precisar de credenciais master
no curl manual, reiniciar o app para re-seed (ou setar env vars explícitas).

## L9 — Documentação de status: frontmatter do requirements + roadmap juntos

**Problema:** o roadmap marcava Specs 14/15/16 como `implemented`, mas o
frontmatter de `requirements.md` ainda dizia `verified` — os dois divergiam.

**Ação:** ao mudar o status de uma spec, atualizar **ambos** (tabela do
roadmap **e** frontmatter) no mesmo commit; auditoria → `audited` nos dois.
Na revisão de hoje (2026-08-04), os frontmatters foram alinhados de
`verified` para `audited` junto com o roadmap.

## L10 — Duas beans do mesmo tipo exigem `@Qualifier`; teste unitário não pega falha de boot

**Problema:** a auditoria da Spec 21 (2026-08-04) encontrou que **a app não
subia** — `MasterAuthService` e `MerchantPortalAuthService` injetavam
`Duration` por tipo, e a Spec 20 criou uma segunda bean `Duration`
(`merchantSessionTtl` ao lado de `masterSessionTtl`) → `No qualifying bean`
no boot. `mvn test` passava (144 testes) porque os testes unitários mockam a
dependência — só o **boot real** revela o erro de autowiring. Sem
`-parameters` no pom, o match por nome de parâmetro não funciona no Spring 6.

**Ação:** `@Qualifier("masterSessionTtl")` / `@Qualifier("merchantSessionTtl")`
explícito nos construtores. **Padrão a seguir:** sempre que um serviço injetar
um tipo que exista em mais de uma bean (Duration, Clock, etc.), usar
`@Qualifier` explícito; e auditoria = subir a app (`mvn spring-boot:run`) antes
de marcar `audited` (reforça a L7).

## L11 — Persistir estado de falha dentro de `@Transactional` exige `REQUIRES_NEW`

**Problema:** o lockout de login do master (B-08, 2026-08-05) incrementava o
contador de falhas com `masterRepository.save(master)` **dentro** do
`@Transactional` do `login()` e depois lançava `InvalidCredentialsException` —
o throw **revertia o save** (rollback da transação) e o lockout nunca persistia
no banco. Os testes unitários passavam porque o mock do repositório não tem
transação (a mutação fica só na instância em memória) — só a validação no banco
real (5 falhas → `locked_until` nulo) revelou o bug.

**Ação:** criar um bean dedicado (`MasterLoginLockoutPersister`) que salva o
estado em transação PRÓPRIA via `TransactionTemplate` com
`PROPAGATION_REQUIRES_NEW` — mesmo padrão do `IdempotencyPersister` (Spec 28).
**Padrão a seguir:** (1) sempre que uma exceção for lançada dentro de
`@Transactional` e algo precisar **sobreviver** ao rollback, persistir em
`REQUIRES_NEW`; (2) comportamento dependente de transação deve ser validado
contra o banco real (teste manual/E2E), não só com mocks.

## L12 — Mutation testing (PITest): como matar mutantes e provar equivalência

Campanha 2026-08-06/07 (rounds 2–11, 80.3% → 91.2%+ global — ver
`management/mutation-testing-campaign.md`). Lições reutilizáveis:

**1. Scripts node de análise do XML do PIT:** usar **regex literal**, nunca
`new RegExp(string)`. Em string JS, `\s` vira `s` (escape não reconhecido) —
a regex `[\s\S]` passa a `[sS]` e o parse de `<mutatedClass>` falha
silenciosamente (classes=1 vazio). `summary-pit.js`/`no-coverage.js`/`pit-status.js`
em `target/` são o modelo correto.

**2. Mutantes de `log.*` (VoidMethodCall/NegateConditionals em linhas de log)**
não morrem por asserção normal — capturar o log com `ListAppender` do logback:
`(Logger) LoggerFactory.getLogger(Classe.class)`, `setLevel(DEBUG)`, `addAppender`,
assert na contagem de mensagens (`appender.list`). Modelos:
`SensitiveDataLoggingFilterTest`, `OpeningBalanceSeedRunnerTest`,
`RedisRateLimitStoreTest`.

**3. `ConditionalsBoundary` morre com o valor EXATAMENTE no limite:** 0.00
(`signum() <= 0` → `< 0`), valor == max (`> max` → `>= max`), 10 itens
(`> 10` → `>= 10`), 1000/500 chars, label 80. Modelo: `StoreButtonPayloadCodecTest.acceptsExactBoundarySizes`.

**4. Equivalência se prova lendo o código, não adivinhando:**
- Regra **duplicada no domínio** mascara o mutante do service (PlanService L169
  `signum() <= 0` — `Plan.validate` tem a MESMA regra → preço 0 lança IAE de
  qualquer forma).
- `Math.max(x, 1000)` neutraliza mutante de divisão no cálculo do throttle
  (RedisRateLimitStore L72: `window*1000 → window/1000` = 0 → max devolve 1000).
- O JDK desembrulha IPv4-mapped automaticamente (`getAllByName("::ffff:x")` →
  `Inet4Address`) → código de unwrap manual é dead code (WebhookUrlValidator).
- Retorno descartado (método de validação por efeito colateral) → NullReturnVals
  é equivalente (DeliveryQueryService `requireDelivery`).

**5. Config de infra sem getter (Spring 6.1):** timeouts do
`SimpleClientHttpRequestFactory` são campos `int` privados e o `RestClient` é
interface — verificar via reflexão em cascata (`PagarMePayoutProviderClient.restClient`
→ `DefaultRestClient.clientRequestFactory` → `connectTimeout`/`readTimeout`).
Modelo: `PagarMePayoutProviderClientTest.appliesConnectAndReadTimeouts`.

**6. Código time-dependent (rate limit/expiração): injetar `Clock`** (padrão do
projeto — bean em `ApplicationConfig`) e avançar **exatamente** `windowMs` para
matar `ConditionalsBoundary` de `>=`/`>` em `now - start >= windowMs`. Modelo:
`InMemoryRateLimitStoreTest.cleanupRemovesWindowExactlyAtBoundary` e
`windowResetsExactlyAtBoundary` (advance de 1000ms == 1s de janela).

**7. Equivalência por simetria do loop:** mutante `+ window → - window` num
laço que varre `{-WINDOW_STEPS..+WINDOW_STEPS}` produz o MESMO conjunto de
steps, só em outra ordem (Totp L89 — `verify` é agnóstico à ordem). E
`bits >= 5 → bits > 5` cujo trailing `if (bits > 0)` emite o MESMO caractere
(`(buffer >>> 0) & 0x1F` == `(buffer << 0) & 0x1F`) quando bits == 5
(Totp L117). Ambos são equivalentes — provar antes de tentar matar.

**8. Boundary inalcançável em enum = equivalente:** validação de tamanho sobre
`enum.name()` (ex.: `action.name().length() > 30` — StoreButton L98) é
INALCANÇÁVEL quando o maior nome do enum é menor que o limite (o maior é
"PREVIEW", 7 chars). `>` e `>=` são idênticos — não escrever teste, provar lendo
o enum.

**9. Dígito verificador com remainder exato:** para matar `ConditionalsBoundary`
de `remainder < 2 ? 0 : 11 - remainder` (DocumentValidator L61), usar um
documento cujo DV tenha `soma % 11 == 2` (ex.: CPF 00000013099 — 1º DV = 9).
Encontrar via brute-force numérico antes de escrever o teste.

## L13 — Gate de status em memória NÃO serializa concorrência; replay concorrente exige lock no banco

**Problema:** a conclusão de ação de entrega (Spec 49) validava o gate (só
`PENDING` → `DONE`) lendo o estado **na instância carregada** — 2 threads
concorrentes liam a ação `PENDING` e a executavam **2×** (2 registros de
auditoria `DELIVERY_ACTION_COMPLETED`). O bug foi exposto pelo E2E
`concurrency.spec.js` cenário 4 (8 completes simultâneos → asserção de
**exatamente 1** auditoria no banco): testes unitários com mock do repositório
não pegam corrida real — só o banco verdadeiro mostra a dupla execução.

**Ação:** `findByDeliveryIdOrderBySeqForUpdate` (`SELECT ... FOR UPDATE` no
`PlanDeliveryActionExecJpaRepository`) — o 2º request **bloqueia** na linha,
relê a ação já concluída e vira **replay idempotente** (204): 1 execução, 1
auditoria. Aplicado também no retry. **Padrão a seguir:** (1) qualquer
transição de estado que precise ser **exatamente-uma-vez** sob concorrência
usa lock pessimista no banco (mesma família do `FOR UPDATE` da Spec 26 no
wallet/refund); (2) invariantes de corrida são provados com **E2E paralelo +
checagem no banco**, nunca só com teste unitário de mock.

## L14 — Balanço de engenharia: sem otimização prematura E sem downgrade de qualidade/segurança/integridade/observabilidade

**Diretriz do usuário (2026-08-08), vinculante para TODO o projeto e para a
Spec 62 (capacity engineering).**

1. **Sem otimização prematura:** não adicionar complexidade sem evidência.
   PgBouncer, réplicas de leitura, Redis Cluster, particionamento etc. entram
   somente se o benchmark provar o gargalo (regra de ouro da Spec 62).
2. **Sem downgrade de qualidade, segurança, integridade ou observabilidade
   para economizar recursos:** nenhuma solução é simplificada, desligada ou
   degradada apenas para reduzir custo, número de componentes ou esforço de
   implementação. Qualidade, segurança, integridade financeira (zero
   duplicação/perda, degradação controlada) e observabilidade são
   **requisitos**, não otimização — não se troca nenhum deles por economia de
   qualquer tipo.

**Padrão a seguir:** ao comparar duas soluções — a mais simples vence **se e
somente se** mantiver a mesma qualidade/segurança/integridade/observabilidade;
a mais robusta vence **se e somente se** a complexidade adicional for
justificada por evidência. Custo/componentes/esforço **nunca** são motivo para
enfraquecer esses atributos; evidência é o **único** motivo para adicionar
complexidade. Anti-exemplos: pular instrumentação (`pg_stat_statements`/
EXPLAIN/tracing) para "economizar tempo"; desligar rate limit/outbox/
reconciliação num estágio só para "passar" mais rápido; escolher a solução com
menos componentes sem provar que mantém a robustez exigida.

## L15 — Não declarar meta de capacidade sem evidência experimental

**Diretriz do usuário (2026-08-08), vinculante na Spec 62:** **não declarar
que a meta de 5.000 vendas/s foi atingida até existir evidência experimental**
— benchmark reproduzível (F7/F10) com métricas completas por estágio
(throughput, vendas/s, p50/p95/p99, %5xx, integridade, duplicações, outbox
backlog) arquivadas em `docs/capacity-report.md`. Sem evidência, o número
reportado é o que foi **medido**, nunca o estimado/planejado.

**Padrão a seguir:** a meta de engenharia é um **alvo**, não um resultado — o
resultado final da Spec 62 é o número real que o benchmark provar (critério 11
do requirements). Nenhuma planilha, estimativa ou "deveria aguentar" substitui
a medição; o relatório é reproduzível (metodologia + ambiente + scripts k6 +
config do rate limit + janela temporal documentados) para um segundo agente
re-executar e comparar.

## L16 — Chave YAML duplicada quebra o boot sem que `mvn test` pegue

**Problema (auditoria da Spec 66, 2026-08-11):** o commit `caa8b7d` (Fase B)
inseriu um **segundo bloco `alert:`** sob `foxcash.security` no
`application.yml` (um com `store:`, outro com `enabled/thresholds` do Spec 64)
→ `org.yaml.snakeyaml.constructor.DuplicateKeyException: found duplicate key
alert` no **boot real** (a app não subia). `mvn test` passou 1169/0/0 mesmo
assim — **não há nenhum `@SpringBootTest`/teste de contexto que parseie o
`application.yml` completo** (todos os testes são unitários com mocks/slices).
O CI só pegaria no job `e2e`/`flyway-coldstart` (que sobem a app) — e não
rodou porque os commits estavam 16 à frente do origin.

**Ação:** ao adicionar uma propriedade nova num bloco existente do YAML,
**confirmar que a chave não existe no mesmo nível** (grep do nome da chave no
arquivo antes de editar). Quando possível, manter **um teste de contexto que
carregue o `application.yml` completo** (ex.: `@SpringBootTest` mínimo) para o
build local falhar cedo — hoje o boot é a única validação do YAML.

**Resolvido (2026-08-11):** blocos fundidos (`store` movido para dentro do
bloco `alert:` existente); boot validado ao vivo (health UP; instância com
`store=redis` também sobe); perfis prod/railway checados sem duplicatas. A
auditoria também corrigiu 2 testes E2E de segurança que **pré-datavam o body
inspection** (Spec 63): esperavam `[400,401,422]`/`[201,400,422]` mas o filtro
bloqueia com **403** — regra: quando um filtro de segurança muda o status de
resposta de um vetor de ataque, a suíte E2E de segurança precisa ser revalidada
(ela é a única que cobre o comportamento HTTP real de borda).

## L17 — JVM stale segurando a porta mascara o fix; verificar PID/início antes de culpar o código

**Problema (E2E do Spec 81, 2026-08-14):** após o fix do event_id compartilhado
(checkout/paid da mesma transação colidiam no `UNIQUE (profile_id, event_id)`),
o E2E de tracking continuava falhando: 3 jobs `TRACKING_DISPATCH` SENT no outbox
mas só 2 `event_logs` (PURCHASE sumia). Os payloads dos jobs mostravam o `eventId`
**antigo** (v4 aleatório, idêntico para INITIATE_CHECKOUT e PURCHASE) — o fix não
"tinha funcionado". Causa real: o `mvn spring-boot:run` da subida **falhou** (exit
1 — porta 8080 já ocupada) e o health check encontrou a **JVM antiga** de pé. A
JVM stale (iniciada às 00:00:24, **antes** do fix compilar às 00:10) continuava
servindo o build pré-fix — o E2E das 03:13 rodou contra ela. `netstat`/boot log
(horários antigos) confirmaram: PID 476 com `CreationDate` anterior à recompilação.

**Ação:** matar a JVM stale (`Stop-Process -Id <pid> -Force`), subir a app com o
build recompilado e re-rodar o E2E — 5/5 verde, 3 `event_logs` OK com event_ids
v3 **distintos por tipo**. De quebra, o E2E expôs 2 bugs latentes de strict mode
no `tracking.spec.js` (locator `.tag-primary` com 4 matches; `Excluir` ambíguo
entre cards e modal) — corrigidos ao escopar os locators. **Padrão a seguir:**
(1) antes de debugar "o fix não funcionou", **confirmar qual processo está na
porta** e se ele é anterior à recompilação (`netstat -ano | grep :8080` +
`Get-CimInstance Win32_Process` para `CreationDate`; comparar com o mtime do
`.class` em `target/`); (2) um `spring-boot:run` que **falha** (exit != 0) não é
"subiu a app" — o health check seguinte pode estar vendo a instância antiga; (3)
ao desconfiar de build stale, validar o artefato real: payloads no banco com
assinatura do código novo (ex.: UUID v3 determinístico vs v4 aleatório).

## L18 — Build incremental verde não prova que o source compila; `.class` com erro embutido sobrevive

**Problema (auditoria da Spec 82, 2026-08-14):** o E2E do painel expôs 500 no
`POST /duplicate` — o método `duplicate` de `TrackingProfileCommandService`
lançava `java.lang.Error: Unresolved compilation problem` em runtime, embora o
`mvn test` (38/38 tracking) passasse e o `.class` tivesse mtime ≥ source. Causa
raiz: o `.class` em `target/` era bytecode de um compilador que **falhou
parcialmente** (o ECJ/Eclipse embute `new Error("Unresolved compilation
problem")` no corpo do método que não compilou — assinatura distinta do javac,
que falha o build). Como o mtime do `.class` era mais novo que o do source, o
**build incremental do Maven não recompilou** e o `.class` corrompido
sobreviveu. Só o `mvn clean test` revelou o erro real: a linha 129 passava
`Set<UUID>` (do `findBotIdsByProfileId`) para `replaceBotLinks(List<UUID>)`.

**Ação:** `mvn clean` (elimina `target/` inteiro) + fix de tipo no source
(`replaceBotLinks` do service passou a aceitar `Collection<UUID>`, alinhado com
a porta — o `Set` flui sem wrapper). **Padrão a seguir (reforça L4/L5):** (1)
um **build incremental verde não prova que o source compila** — suspeita de
comportamento estranho de endpoint (500 fora do contrato, método que nunca
executa) → `mvn clean test` antes de culpar a lógica; (2) `Unresolved
compilation problem` em runtime = `.class` gerado por compilador que falhou
parcialmente — nunca é bug de lógica, é classe obsoleta/corrompida no
classpath; (3) o E2E é a única camada que exercita os endpoints reais e expõe
esse tipo de artefato (L7).

## L19 — Corrupção de artefato no `.m2` é silenciosa para `mvn test` e explode no boot; verificar integridade antes de produção

**Problema (Specs 120/121, 2026-08-15):** no boot da app (perfil dev, após o
`/spec-implement`), o Flyway/JPA falhou ao conectar no Postgres com
`java.lang.ClassFormatError: Invalid constant pool index 733 in class file
org/postgresql/jdbc/PgPreparedStatement`. O `mvn test` (suíte completa
**1865/0/0**) tinha acabado de passar, então parecia bug de código novo. Causa
raiz: o jar `postgresql-42.7.4` no `~/.m2/repository` estava **corrompido**
(`unzip -t` → "At least one error was detected") — download interrompido do
Maven em algum momento anterior (mtime do jar era de semanas antes). Os testes
unitários **não carregam o driver JDBC real** (mocks/slices) → o `mvn test`
verde não provou a integridade do runtime; só o boot (Flyway/JPA abre conexão)
expôs a corrupção. Efeito colateral de debug: `mvn clean` sozinho NÃO resolve
(problema no `.m2`, não no `target/`), e um `spring-boot:run` que falha deixa a
porta livre — qualquer JVM stale anterior mascararia (L17).

**Ação:** `rm -rf ~/.m2/repository/org/postgresql/postgresql/42.7.4` +
`mvn dependency:get -Dartifact=org.postgresql:postgresql:42.7.4` (re-baixa o
jar; roda SEM `-o`) + `unzip -t <jar>` → "No errors detected" → boot OK e E2E
verdes. O commit `0097974` registra o incidente.

**Padrão a seguir (reforça L4/L5/L17/L18):**
1. **`mvn test` não prova o runtime real** — ele não sobe a app nem abre conexão
   JDBC de verdade. Antes de declarar "pronto para produção" (ou sempre que o
   boot falhar com `ClassFormatError`/`Invalid constant pool`/`ZipException`/
   `NoClassDefFoundError` estranho), validar a integridade com pelo menos um
   gate de runtime: `mvn -Pcontext-tests` (smoke `@SpringBootTest` que sobe a
   app e conecta — Spec 68), `mvn flyway:validate`/`flyway-coldstart`
   (conectam no Postgres e carregam o driver) ou o E2E real.
2. **Verificar o `.m2` antes de mexer no código**: jar suspeito →
   `unzip -t ~/.m2/repository/<group>/<artifact>/<ver>/<jar>`; corrompido →
   `rm -rf` do diretório do artefato + re-baixar (`mvn dependency:get` ou
   `mvn -U` sem `-o`). Não assumir que o `.m2` é sagrado — downloads
   interrompidos corrompem jars sem aviso e o erro aparece só no primeiro uso
   real (muitas vezes dias/semanas depois).
3. **Distinguir as 2 fontes de corrupção de bytecode**: jar do `.m2`
   (driver/lib — erro em classe de pacote `org.postgresql`, `org.springframework`,
   etc.) vs `.class` do `target/` (L4/L5/L18 — erro em classe do PRÓPRIO
   projeto, `Unresolved compilation problem`). O sintoma e o fix são
   diferentes: `.m2` → re-baixar; `target/` → `mvn clean`.
4. **Local vs CI**: o CI baixa de um cache/registry limpo e tende a re-provar
   (os jobs `flyway*` e `context-tests` pegariam), então corrupção de `.m2` é
   quase sempre LOCAL — documentar a recuperação no runbook para o operador
   não perder tempo nem atribuir a bug de código.

**Checklist aplicado (2026-08-16, pré-deploy das specs 126/127/128):** 1) gate
de runtime executado — `mvn test -Pcontext-tests` (`FoxCashContextSmokeTest`
2/2, app sobe com Tomcat + JDBC + Flyway no boot); 2) `.m2` verificado —
`unzip -t` OK em `postgresql-42.7.4.jar`, `spring-boot-3.3.5.jar`,
`flyway-core-10.10.0.jar`, `micrometer-core-1.13.6.jar` (0 corrompidos);
3) deploy de produção (deploy `94861317`) validou o runtime real — Flyway
"Successfully validated 64 migrations", schema V75, health 200, 2/2 réplicas.

## L20 — Overloads aditivos de aggregates: preservar `create`/`restore`, jamais reordenar

**Contexto (Spec 136, 2026-08-16):** a avaliação do `PixTransaction` (435 linhas)
concluiu que sua "complexidade" é a **escada de overloads** `create` (5) /
`restore` (7) criada por evolução aditiva — cada spec acrescenta campos
opcionais (`buyerIdHash`, `planId`, `botId`, `platform`, `platformUserId`,
provas de entrega, `provider`, `profileId`) como novo overload no fim. A
decisão foi **MANTER** (extrair value objects mudaria construtor/overloads/
mapper/call-sites sem ganho comportamental — KISS, critério C2 da Spec 136).

**Padrão a seguir para os aggregates (reflexo do padrão já usado no `Merchant`,
`Store`, etc.):**
1. Novo campo opcional = **novo overload `create`/`restore` no FIM da lista de
   assinaturas** — os overloads anteriores chamam o novo com `null` no campo;
   call-sites antigos nunca recompilam quebrados.
2. **Jamais reordenar/renomear parâmetros** de um overload existente (quebra
   silenciosa de call-site que usa args posicionais).
3. Campo mutável (ex.: `profileId`, `provider`) = setter/aditivo com
   `copy`/`attach` preservando os demais campos (ver o bug REAL da Spec 124:
   `withIdempotencyKey` reconstruía a cópia sem o `profileId` e o
   `persistPending` salvava `profile_id = NULL`).
4. Se a escada passar de ~8 overloads num aggregate, o ponto de atrito NÃO é o
   aggregate — é a persistência: avaliar um **builder/constructor padrão**
   (não value-object forçado) com teste de contrato antes.
