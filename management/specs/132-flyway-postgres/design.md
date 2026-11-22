---
name: Flyway compatível com PostgreSQL 18.x
status: implemented
---

# Spec 132 — Design: Flyway × PostgreSQL 18.x

## D1 — Conceito

O banco de produção roda **PostgreSQL 18.4** (Railway), mas o Flyway gerenciado
pelo Spring Boot 3.3.5 (`flyway-core`/`flyway-database-postgresql` no pom,
versão 10.10.0) declara suporte testado **até PostgreSQL 16** — o boot loga
`Flyway upgrade recommended: PostgreSQL 18.4 is newer...`. Migrations futuras
rodariam em banco não suportado pela ferramenta.

**Decisão (com evidência):** o upgrade do Spring Boot 3.5.x (Spec 130) gerencia
uma versão do Flyway que **suporta PostgreSQL 17/18** — a solução natural é
**atualizar o Flyway junto com a 130** (não pinar o Postgres em 16, que seria
regredir o provedor). Se o Boot 3.5.x trouxer Flyway < suporte a 18, avaliar
override de versão do Flyway (property `flyway.version`) para a última estável
com suporte a 18 — decisão registrada com a versão exata.

## D2 — Mudança (C1, C2)

- `pom.xml`: versão do Flyway passa a ser a **gerenciada pelo Boot 3.5.x**
  (ou override explícito `flyway.version` se necessário — documentar).
- `application.yml` (linha 37 `flyway:`): **sem mudança de contrato** — as
  migrations atuais (64+, V1..V75) rodam sem alteração.
- **Verificação**: boot sem o warning `Flyway upgrade recommended` + log
  `Successfully validated N migrations` contra o Postgres real (dev/produção).

## D3 — Validação contra o banco real (C2)

- `mvn flyway:validate` (config do pom linhas 334-335 já documenta o goal)
  contra o Postgres de dev/produção: `Successfully validated N migrations`,
  schema atual, 0 erros.
- As 64+ migrations existentes **sem alteração** (checksums intactos —
  alterar um arquivo aplicado quebra o validate).

## D4 — Suíte e docs (C3, C4)

- `mvn test` completo (1995+) + context-tests 2/2 + E2E sem regressão.
- `README.md`/`deploy/railway.md`: versão do Flyway + versão mínima/suportada
  do Postgres + a decisão (upgrade Flyway vs pin) com o porquê.

## D5 — Arquivos

| Arquivo | Mudança |
|---|---|
| `pom.xml` | (se necessário) `flyway.version` override p/ suporte a 18 (D2) |
| `README.md`/`deploy/railway.md` | versões + decisão documentadas (D4) |
| (nenhum código de app) | migrations intactas |

## D6 — Fora de escopo

PgBouncer (Spec 133), réplicas de leitura (Spec 62/ADR-013), multi-região
(Spec 135) — outras dimensões de banco.

**Âncoras validadas na spec:** `pom.xml` (flyway-core + flyway-database-postgresql
gerenciados pelo Boot; goal `mvn flyway:validate` documentado nas linhas
334-335); `application.yml` (linha 37 `flyway:`); 64+ migrations V1..V75 em
`src/main/resources/db/migration/`; Spec 130 (o Boot 3.5.x gerencia o Flyway
novo).
