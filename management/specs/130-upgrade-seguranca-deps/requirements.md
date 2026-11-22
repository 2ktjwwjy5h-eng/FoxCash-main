---
name: Upgrade Spring Boot 3.5.x + dependências + OWASP Dependency-Check bloqueante
status: audited
references:
  - "management/roadmap.md#Fase 41"
  - "management/lessons.md#L19"
  - "management/specs/18-security-hardening/requirements.md"
---

# Spec 130 — Upgrade Spring Boot 3.5.x + dependências + OWASP bloqueante

## O que entrega

Elimina o risco #1 apontado no due diligence (2026-08-16): o sistema roda em
**Spring Boot 3.3.5 (out/2024, ~1.5 ano desatualizado)** com **~1.403
dependências com update disponível** (logback 1.5.11→1.6.3, jackson
2.17.2→2.22.1, etc.) e **nenhuma varredura de CVE funcionando** — o workflow
`dependency-check.yml` falha sempre porque a NVD exige `NVD_API_KEY` desde 2024
e o plugin nem está no pom. Para um sistema que movimenta dinheiro, CVEs
conhecidos em bibliotecas de runtime são inaceitáveis.

## Decisões registradas (usuário 2026-08-16)

- Alvo: **Spring Boot 3.5.x** (linha LTS suportada, sem migração Jakarta —
  compatível com Java 21 atual). Spring Boot 4.x fica para depois (migração
  grande, sem valor imediato).
- OWASP Dependency-Check passa a ser **bloqueante no CI** (falha em CVE ≥ 7.0),
  com `NVD_API_KEY` configurada como secret do GitHub.

## Critérios de aceite

**C1 — Spring Boot atualizado.** `pom.xml` parent em 3.5.x estável (última
patch da linha 3.5). `mvn test` completo verde (1995+ testes) sem alteração de
lógica de negócio — só ajustes de compatibilidade documentados.

**C2 — Dependências de runtime sem CVE conhecido.** Após o upgrade, o OWASP
Dependency-Check reporta **0 vulnerabilidades de severidade ≥ 7.0** (HIGH/CRITICAL)
nas dependências de runtime (compiladas no jar). Dependências de teste podem ter
achados documentados e suprimidos com justificativa.

**C3 — OWASP Dependency-Check operacional e bloqueante.** Plugin configurado
no `pom.xml` (goal `check`, failOnCVSS=7.0) + workflow CI com `NVD_API_KEY`
lida do secret do GitHub + cache da base NVD entre runs (speed). O workflow
**roda e passa** (não só existe no repo).

**C4 — Logback/jackson/transitivas atualizadas.** As bibliotecas críticas que o
Boot gerencia chegam nas versões do Boot 3.5.x: logback ≥ 1.5.x última patch,
jackson-databind na versão do Boot 3.5.x. Verificação: `mvn versions:
display-dependency-updates` mostra só patches menores restantes (sem major/minor
em libs de runtime usadas).

**C5 — Sem regressão funcional.** As 1995+ specs passam; E2E completo verde;
gate de contexto (`mvn test -Pcontext-tests`) 2/2. Smoke em produção após o
deploy: health 200, venda completa (201+204), rate limit ativo (429 no abuso).

## Evidência-base (coletada em 2026-08-16)

- `Spring Boot 3.3.5` no pom (parent).
- `mvn versions:display-dependency-updates` → **~1.403 dependências com update**
  (logback-classic 1.5.11→1.6.3, jackson-databind 2.17.2→2.22.1, caffeine
  3.1.8→3.2.4, gson 2.10.1→2.14.0, json-path 2.9.0→3.0.0, h2 2.2.224→2.4.240).
- `dependency-check.yml` roda `mvn ...dependency-check:check` **sem** `NVD_API_KEY`
  → falha `Invalid API Key, length of 0` (NVD exige key desde 2024).
- Plugin `dependency-check-maven` **ausente** do pom (0 ocorrências).
- `mvn test` atual: **1995/0/0** (baseline pós-upgrade).

## Dependências

- **Spec 18** (security hardening) — base da camada de segurança.
- **L19** (lessons) — verificação de integridade pré-deploy obrigatória após
  mexer em dependências (`unzip -t` nos jars críticos + context-tests).

## Fora de escopo

- Spring Boot 4.x (migração Jakarta EE 11) — registrado como futuro.
- Hardening de runtime (senha logada, actuator, headers) — Spec 131.
- Migração para Java 23+ — não necessário (Java 21 é suportado pelo Boot 3.5.x).
