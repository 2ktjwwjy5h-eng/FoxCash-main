---
name: Fundação de produção — CI/CD, profile prod, deploy sem Docker, ADR do PSP
status: audited
references:
  - "management/vision.md#V7"
  - "management/specs/8-admin-dashboard/requirements.md"
---

# Spec 9 — Fundação de produção (sem Docker)

## O que entrega

Prepara o FoxCash para operar de verdade **sem depender de Docker** (a máquina
de desenvolvimento não suporta WSL2; PostgreSQL é nativo). Entrega a base de
operação: CI/CD, profile de produção sem defaults inseguros, artefato
executável, deploy em VPS via `systemd` + nginx e a **decisão documentada do
PSP** (ADR) que desbloqueia a Spec 10 (integração real).

Escopo:

1. **CI/CD — GitHub Actions** (`.github/workflows/ci.yml`): a cada `push`/`PR`,
   roda `mvn -B verify` (32 testes) e a suíte E2E Playwright (5/5) contra um
   PostgreSQL **nativo no runner** (sem Docker na máquina do usuário).
2. **Profile `prod`** (`src/main/resources/application-prod.yml`): HTTPS
   obrigatório (`require-https: true`) e **sem valores default** para
   `NYX_CRYPTO_SECRET`/`DB_URL`/`DB_USERNAME`/`DB_PASSWORD` (env vars
   obrigatórias — boot falha se ausentes).
3. **JAR executável**: `mvn package` gera o fat-jar (via
   `spring-boot-maven-plugin`) verificável com `java -jar`.
4. **Deploy sem Docker** (`deploy/`): unit `systemd` (`trevopay.service`) +
   runbook VPS passo a passo (JDK 21, PostgreSQL nativo, env vars, nginx + TLS,
   backup `pg_dump`, migrations automáticas via Flyway).
5. **ADR do PSP** (`management/adr.md`): critérios objetivos de escolha do
   provedor Pix, shortlist (Pagar.me, Asaas, Efí/Gerencianet, Mercado Pago) e
   recomendação — decisão isolada atrás do port `PixProviderClient`, portanto
   trocável sem tocar no domínio.

## Critérios de aceite

1. `.github/workflows/ci.yml` existe, dispara em `push` e `pull_request`, e
   contém dois jobs: (a) `mvn -B verify`; (b) E2E com PostgreSQL nativo no
   runner (instalado via `apt`, sem containers), app iniciada via
   `spring-boot:run` e `npm test` em `e2e-tests/`.
2. `application-prod.yml` define `foxcash.security.require-https: true` e usa
   **apenas env vars sem default** para secrets/datasource
   (`${NYX_CRYPTO_SECRET}`, `${DB_URL}`, `${DB_USERNAME}`, `${DB_PASSWORD}`).
3. Boot com `--spring.profiles.active=prod` **sem** `NYX_CRYPTO_SECRET` falha
   no startup (nenhum default inseguro em prod — inclusive quando o placeholder
   fica como string literal, ex.: `${NYX_CRYPTO_SECRET}`).
4. Boot com profile `prod` e env vars válidas (apontando para o PostgreSQL
   local) sobe o app com **TLS no conector** (porta 8443): requisição **HTTPS**
   responde **200** e requisição **HTTP** ao canal TLS é **rejeitada (400 do
   Tomcat)** — a aplicação não atende canal inseguro. O `require-https` também
   cobre o cenário de proxy/offload (redirecionamento 302, verificado na
   auditoria da Spec 4 sem keystore).
5. `mvn -q clean package` produz `target/foxcash-*.jar` executável; `java -jar`
   sobe o app (smoke test no profile `dev`).
6. `deploy/trevopay.service` (unit systemd com `EnvironmentFile` e
   `Restart=on-failure`) e `deploy/README.md` (runbook VPS sem Docker: JDK,
   PostgreSQL, env vars, systemd, nginx+TLS, backup) existem.
7. `management/adr.md` ganha um ADR de decisão do PSP (critérios + shortlist +
   recomendação) referenciando o port `PixProviderClient`.
8. `mvn test` verde (32 testes) e E2E 5/5 sem regressão.

## Dependências

- Spec 8 (base funcional estável para servir de alvo do CI).
- Specs 1–3 (toolchain: JDK 21 + Maven + PostgreSQL nativo — sem Docker).

## Observações

- **Por que sem Docker:** a engine Docker exige WSL2 (indisponível nesta
  máquina). O runner do GitHub Actions tem ambiente próprio (Docker embutido ou
  Postgres nativo via `apt`), então o CI funciona independentemente da máquina
  local. O deploy usa VPS com `systemd` — caminho 100% livre de containers.
- **Segurança em prod:** o `application.yml` mantém defaults apenas para dev;
  o profile `prod` os sobrescreve para exigir env vars (fail-fast).
- **ADR com confirmação:** a escolha do PSP é registrada com base em critérios
  objetivos; a confirmação final (credenciais, SDK, webhook) acontece na Spec
  10, durante a homologação. Como o `PixProviderClient` é um port, a troca de
  PSP não toca no domínio.
- Escopo **excluído** desta spec: integração real com o PSP (Spec 10),
  notificação real ao lojista, autenticação/RBAC, observabilidade e demais
  fases de produção (specs futuras).
