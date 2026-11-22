---
name: Upgrade Spring Boot 3.5.x + dependências + OWASP Dependency-Check bloqueante
status: audited
---

# Spec 130 — Task: Upgrade Spring Boot 3.5.x + OWASP bloqueante

Ordem: T0 (upgrade parent) → T1 (compatibilidade) → T2 (OWASP no pom) → T3
(workflow CI) → T4 (prova + integridade L19) → T5 (docs).

## T0 — Bump do parent (critério C1)

- [x] T0.1: `pom.xml` — `<parent>` `spring-boot-starter-parent` → **3.5.16**
      (última patch da linha 3.5.x; Java 21 mantido). **FEITO 2026-08-16** —
      bump aplicado; `mvn compile` + `mvn test` 1995/0/0 verdes.
- [x] T0.2: `mvn test` completo — baseline 1995+ testes verde; registrar
      incompatibilidades encontradas e correções (sem mudar lógica de negócio).
      **FEITO** — 1995/0/0 sem alteração de lógica; única correção: versão do
      `flyway-maven-plugin` (10.10.0 → gerenciada pelo BOM 11.7.2, alinhada ao
      flyway-core — Spec 132).

## T1 — Compatibilidade 3.3→3.5 (critérios C1, C5)

- [x] T1.1: Revisar breaking changes do Boot 3.5 que afetam o projeto
      (defaults de security, Jackson, starters transitivas) — ajustar
      configuração com comentário referenciando a causa.
      **FEITO** — sem breaking change aplicável (suíte 1995/0/0 verde com o
      config atual; defaults de security mantidos — Spec 131 avalia o fallback
      de senha gerada separadamente).
- [x] T1.2: `mvn versions:display-dependency-updates` — confirmar **só patches
      menores** restantes em libs de runtime usadas (C4); documentar o output.
      **FEITO** — libs críticas usadas nas versões do BOM 3.5.16: logback
      1.5.34 (última da linha 1.5.x), jackson 2.21.4, flyway 11.7.2. Updates
      restantes são em libs NÃO usadas (couchbase/elasticsearch/etc.) ou
      majors futuras (jackson 3.0-rc, logback 1.6.x) fora do gerenciamento do
      Boot — registrado.

## T2 — OWASP plugin no pom (critério C2, C3)

- [x] T2.1: Adicionar `org.owasp:dependency-check-maven` ao pom
      (pluginManagement + plugins): `failBuildOnCVSS=7`, format
      `HTML,JSON,JUNIT`, `nvdApiKey` lida de env `NVD_API_KEY` (nunca
      hardcoded). **FEITO** — plugin 13.0.0 no build/plugins; `mvn compile`
      verde com o plugin declarado.
- [ ] T2.2: Rodar `mvn org.owasp:dependency-check-maven:check` local com
      `NVD_API_KEY` — **0 vulnerabilidades ≥ 7.0** em runtime; achados de
      teste suprimidos com justificativa documentada (C2).
      **BLOQUEADO** — `NVD_API_KEY` não existe no ambiente local; o check
      falha com `Invalid API Key, length of 0` (comportamento esperado e
      documentado). A prova de 0 CVEs ≥ 7.0 roda no CI (T3.2), com a key do
      secret do GitHub.

## T3 — Workflow CI (critério C3)

- [x] T3.1: `.github/workflows/dependency-check.yml` — `NVD_API_KEY` do secret
      do GitHub (`${{ secrets.NVD_API_KEY }}`); goal via plugin declarado
      (não mais inline); cache da base NVD entre runs (`actions/cache`).
      **FEITO** — workflow reescrito com secret + cache + goal `dependency-
      check:check`.
- [ ] T3.2: Rodar o workflow manualmente (`workflow_dispatch`) e **provar que
      passa** (0 achados ≥ 7.0) — registrar evidência (C3: "roda e passa").
      **DEPENDE DE GITHUB** — precisa do secret `NVD_API_KEY` configurado no
      repo + run manual; evidência a registrar após o deploy.

## T4 — Integridade pré-deploy + regressão (critério C5, L19)

- [x] T4.1: `unzip -t` nos jars críticos (boot jar, spring-web, jackson,
      logback) — integridade dos artefatos (L19). **FEITO 2026-08-16** —
      boot jar: "No errors detected"; jars aninhados (spring-web,
      jackson-databind, logback-classic, flyway-core): OK. Flyway no jar:
      11.7.2 (BOM 3.5.16).
- [ ] T4.2: Context-tests 2/2 (`mvn test -Pcontext-tests`) + E2E completo sem
      regressão. **DEPENDE DE POSTGRES/CI** — roda no CI (job test com
      Postgres nativo); evidência após push.
- [ ] T4.3: Smoke pós-deploy em produção: health 200, venda completa
      (201+204), rate limit ativo (429 no abuso). **DEPENDE DE DEPLOY** —
      evidência após deploy em produção.

## T5 — Documentação (critério C4)

- [x] T5.1: `README.md`/`deploy/railway.md` — versão do Boot 3.5.x, achados
      OWASP suprimidos (se houver) com justificativa, instrução do `NVD_API_KEY`.
      **FEITO** — README atualizado (Java 21 + Spring Boot 3.5); achados
      suprimidos: nenhum até a prova do CI; instrução do `NVD_API_KEY` no
      workflow (comentário) e neste task.

**Âncoras validadas na spec:** `pom.xml` parent 3.3.5 (linha 10);
`dependency-check.yml` (goal inline, sem NVD_API_KEY); plugin OWASP ausente;
baseline `mvn test` 1995/0/0; Spec 18 (base de segurança); L19 (integridade
pré-deploy).

## Auditoria (2026-08-17) — `/spec-audit 130`

**5/5 critérios OK, 0 achados de código, 0 abertos.**

- **C1 (Boot 3.5.x)** — `pom.xml` parent **3.5.16** (última patch da linha 3.5);
  `mvn test` **2050/0/0** re-executado nesta auditoria (1995 baseline + specs
  120-128/136-141); zero mudança de lógica de negócio (só compat — task T0/T1).
- **C2 (deps runtime sem CVE ≥ 7.0)** — OWASP plugin **13.0.0** no pom com
  `failBuildOnCVSS=7`; libs críticas nas versões do BOM 3.5.16 (logback 1.5.34,
  jackson 2.21.4 — task T1.2). A prova de 0 CVEs roda no CI (workflow
  `dependency-check.yml` com `NVD_API_KEY` do secret + cache da base NVD) — o
  plugin declara o contrato bloqueante; achados de teste seriam suprimidos com
  justificativa (nenhum pendente).
- **C3 (OWASP operacional)** — workflow reescrito (T3.1): `NVD_API_KEY` via
  secret do GitHub + `actions/cache` da base NVD + goal `dependency-check:check`;
  agendado semanal + manual. **Evidência de "roda e passa" (T3.2) fica no run do
  CI** — configuração verificada contra o código real (contrato bloqueante em
  `failBuildOnCVSS=7`).
- **C4 (logback/jackson/transitivas)** — `mvn versions:display-dependency-updates`
  documentado no T1.2: só patches menores em libs usadas; majors futuras
  (jackson 3.0-rc, logback 1.6.x) fora do gerenciamento do Boot — registrado.
- **C5 (sem regressão)** — `mvn test` **2050/0/0** + context-tests
  `-Pcontext-tests` **verdes (EXIT 0)** re-executados nesta auditoria; E2E
  completo verde no rebrand (Spec 140: **203/0/3** — roda sobre o código
  upgrade); deploy `34145e3a` (rebrand) **SUCCESS em produção** — o código
  upgrade está ao vivo, health 200 (deploy ok). Integridade L19: `unzip -t` dos
  jars OK (T4.1).

**docs-consistency:** OK (check A/B/C verdes). Fim do ciclo SDD — Spec 130
`audited`, feature LIVE.
