---
name: Flyway compatível com PostgreSQL 18.x
status: audited
references:
  - "management/roadmap.md#Fase 41"
  - "management/specs/130-upgrade-seguranca-deps/requirements.md"
---

# Spec 132 — Flyway compatível com PostgreSQL 18.x

## O que entrega

Elimina o aviso do boot em produção: `Flyway upgrade recommended: PostgreSQL
18.4 is newer than this version of Flyway and support has not been tested. The
latest supported version of PostgreSQL is 16.`

O banco de produção roda **PostgreSQL 18.4** (Railway), mas o Flyway do projeto
(10.10.0) declara suporte testado **até 16**. Isso significa que as migrations
futuras rodam em versão de banco **não suportada** pela ferramenta — risco
silencioso de comportamento incorreto em DDL/DML.

## Critérios de aceite

**C1 — Sem aviso de versão no boot.** O log de produção NÃO contém
`Flyway upgrade recommended` nem `is newer than this version`. Duas opções
aceitáveis: (a) atualizar Flyway para versão que suporta PostgreSQL 18.x, ou
(b) pinar o Postgres do Railway em versão suportada (≤16.x) — decidir com
evidência (o que o Railway permite; o que o upgrade do Flyway custa).

**C2 — Migrations validam contra o banco real.** `flyway:validate` (ou o boot)
contra o Postgres de produção/dev: "Successfully validated N migrations", schema
atual, 0 erros. As 64+ migrations existentes rodam sem alteração.

**C3 — Suíte verde.** `mvn test` completo (1995+) + context-tests 2/2 + E2E sem
regressão após a mudança de versão.

**C4 — Documentado.** A versão do Flyway e a versão mínima/suportada do Postgres
ficam registradas no README/deploy (decisão: upgrade Flyway vs pin Postgres —
com a escolha e o porquê).

## Evidência-base (2026-08-16)

- Log de boot do deploy `94861317`: `WARN o.f.c.i.database.base.Database msg=
  Flyway upgrade recommended: PostgreSQL 18.4 is newer than this version of
  Flyway and support has not been tested. The latest supported version of
  PostgreSQL is 16.`
- `flyway-core` 10.10.0 no pom (gerenciado pelo Spring Boot 3.3.5).

## Dependências

- **Spec 130** — o upgrade do Spring Boot 3.5.x sobe o Flyway gerenciado;
  rodar esta spec em conjunto (verificar a versão que o Boot 3.5.x traz).

## Fora de escopo

- PgBouncer/pool (Spec 133), réplicas de leitura (Spec 62/ADR-013), multi-região
  (Spec 135) — outras dimensões de banco.
