---
name: Flyway compatível com PostgreSQL 18.x
status: audited
---

# Spec 132 — Task: Flyway × PostgreSQL 18.x

Ordem: T0 (decisão com evidência) → T1 (mudança de versão) → T2 (validate real)
→ T3 (suíte/docs).

## T0 — Decisão com evidência (critério C1)

- [x] T0.1: Confirmar a versão do Flyway gerenciada pelo Boot 3.5.x (após a
      Spec 130) — suporta PostgreSQL 18? (checar release notes do Flyway).
- [x] T0.2: Registrar a decisão: upgrade do Flyway junto com a 130 **ou**
      override `flyway.version` — com a versão exata e o porquê (pinar o
      Postgres em 16 é a alternativa descartada).

## T1 — Mudança de versão (critérios C1, C2)

- [x] T1.1: `pom.xml` — versão do Flyway = gerenciada pelo Boot 3.5.x (ou
      `flyway.version` override); `flyway-core` +
      `flyway-database-postgresql` mantidos.
- [x] T1.2: Boot contra o Postgres real — **sem** `Flyway upgrade recommended`
      e com `Successfully validated N migrations`; 64+ migrations intactas.

## T2 — Validação contra o banco real (critério C2)

- [x] T2.1: `mvn flyway:validate` contra dev/produção — schema atual, 0 erros,
      checksums intactos (nenhuma migration alterada).

## T3 — Suíte e docs (critérios C3, C4)

- [x] T3.1: `mvn test` completo (1995+) + context-tests 2/2 + E2E sem
      regressão.
- [x] T3.2: `README.md`/`deploy/railway.md` — versão do Flyway, versão mínima/
      suportada do Postgres e a decisão do T0.2 (com o porquê).

**Âncoras validadas na spec:** `pom.xml` (flyway-core/flyway-database-postgresql
gerenciados; goal `mvn flyway:validate` documentado linhas 334-335);
`application.yml` (linha 37 `flyway:`); migrations V1..V75;
Spec 130 (Boot 3.5.x gerencia o Flyway).

## Implementação (2026-08-16) — `/spec-implement 132`

**Decisão T0 (com evidência):** o BOM do Boot 3.5.16 fixa
`flyway.version=11.7.2`, que declara suporte testado **até PostgreSQL 17** — o
bytecode do `PostgreSQLDatabase.ensureSupported` chama
`recommendFlywayUpgradeIfNecessaryForMajorVersion("17")` (o warning continuaria
com o PG 18.4 do Railway, agora citando "latest supported is 17"). O source do
`PostgreSQLDatabase.java` nas tags do GitHub mostra o limite subindo para
`"18"` a partir do **Flyway 12.0.0** (12.0.0/12.4.0/12.8.0/12.11.0/13.x todos
com `"18"`). A API usada pelo `FlywayAutoConfiguration` do Boot 3.5.16
(builder `FluentConfiguration`) foi verificada contra o jar do 12.11.0 — **55/55
métodos presentes** (compatibilidade). Artefatos `flyway-core`,
`flyway-database-postgresql` e `flyway-maven-plugin` 12.11.0 existem no Maven
Central. **Escolha: override `flyway.version=12.11.0`** (última estável da linha
12 — 13.x é muito recente) — pinar o Postgres em 16 descartado (regrediria o
provedor).

**Mudanças aplicadas:**
- `pom.xml`: `<flyway.version>12.11.0</flyway.version>` nas properties (com
  comentário explicativo) + comentário do `flyway-maven-plugin` corrigido (o
  antigo afirmava incorretamente que o 11.7.2 suporta PG 18).
- `README.md`: tabela de pré-requisitos `PostgreSQL 16+` → `18.x` + nota
  "Flyway (Spec 132)" com versão/decisão.
- `deploy/railway.md`: diagrama `PostgreSQL 15/16/17` → `18`; seção de boot
  `V1→V37` → `V1→V75` + nota de versões; verificação espera
  `Successfully validated 64 migrations` sem warning.

**Validação (evidências):**
- `mvn flyway:validate -Dflyway.url=jdbc:postgresql://localhost:5432/nyxcash
  ...` → `flyway:12.11.0:validate` → **`Successfully validated 64 migrations`**
  (execution 00:00.052s), BUILD SUCCESS, 0 erros (C2).
- Boot real (perfil dev, PG nativo 18.4 local): `Database:
  jdbc:postgresql://localhost:5432/nyxcash (PostgreSQL 18.4)` +
  `Successfully validated 64 migrations` + `Current version of schema
  "public": 75` + `Started FoxCashApplication in 7.538 seconds` — **sem
  `Flyway upgrade recommended`** (C1).
- `mvn test`: **1995/0/0** (0 falhas, 0 erros) + context-tests `-Pcontext-tests`
  contra PG 18.4: **2/2 PASS** (smoke `@SpringBootTest` sobe a app + Hibernate
  validate) (C3).
- Migrations V1..V75 **intactas** (nenhuma alterada — checksums válidos).

**Observação registrada (fora de escopo):** o `mvn verify` falha na regra
JaCoCo `branches >= 0.80` (medido 0.73) — **pré-existente no HEAD**
(comprovado: idêntico com Boot 3.3.5/3.5.16 e Flyway 11.7.2/12.11.0; classes
novas das specs 120-128 — tracking/CRM/Discord/broadcast — sem cobertura
unitária). Não é regressão desta spec; tratar em spec de testes futura.

## Auditoria (2026-08-17) — `/spec-audit 132`

**4/4 critérios OK, 0 achados de código, 0 abertos.**

- **C1 (sem aviso no boot)** — `pom.xml` `<flyway.version>12.11.0</flyway.version>`
  (override do BOM 11.7.2 que só suporta até PG 17; 12.0.0+ suporta PG 18 — task
  T0 com evidência de source/release notes); boot real contra PG 18.4
  documentado no task: `Successfully validated 64 migrations` **sem**
  `Flyway upgrade recommended`. Deploy `34145e3a` (rebrand, 2026-08-17) já roda
  o Flyway 12.11.0 em produção contra o PG 18.4 do Railway.
- **C2 (validate real)** — `mvn flyway:validate` contra o banco: **64 migrations,
  0 erros, BUILD SUCCESS** (documentado no task; evidência de execução com o
  Postgres nativo). Re-executável com `mvn flyway:validate` (goal 12.11.0).
- **C3 (suíte verde)** — `mvn test` **2050/0/0** re-executado nesta auditoria +
  context-tests `-Pcontext-tests` **verdes (EXIT 0)**; migrations V1..V77
  intactas (nenhuma alterada — checksums válidos; V76/V77 adicionadas pelas
  Specs 139/141 após a validação original, sem conflito).
- **C4 (documentado)** — `README.md` (PostgreSQL 18.x + nota Flyway Spec 132) +
  `deploy/railway.md` (diagrama PG 18, verificação espera `Successfully
  validated` sem warning) — verificado consistente.

**docs-consistency:** OK (check A/B/C verdes). Fim do ciclo SDD — Spec 132
`audited`, feature LIVE.
