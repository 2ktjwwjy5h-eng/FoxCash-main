---
name: Upgrade Spring Boot 3.5.x + dependências + OWASP Dependency-Check bloqueante
status: verified
---

# Spec 130 — Design: Upgrade Spring Boot 3.5.x + OWASP bloqueante

## D1 — Conceito

O sistema roda em Spring Boot **3.3.5** (parent no `pom.xml` linha 10) com
~1.403 dependências com update disponível e OWASP que **nunca passou** (o
workflow `.github/workflows/dependency-check.yml` roda `org.owasp:dependency-
check-maven:check` **inline, sem `NVD_API_KEY`** e sem o plugin declarado no
`pom.xml` — falha sempre desde que a NVD passou a exigir key em 2024). O
upgrade para **3.5.x** (linha LTS, compatível com Java 21) + OWASP operacional
e **bloqueante** (failBuildOnCVSS=7) é o risco #1 do due diligence.

Regra de ouro (L19): depois de mexer em dependências, **verificar integridade
pré-deploy** (unzip -t nos jars críticos + context-tests 2/2) — `mvn test`
verde não prova boot íntegro.

## D2 — Upgrade do parent Spring Boot (C1)

`pom.xml`:

- `<parent>` `spring-boot-starter-parent` → última patch da linha **3.5.x**
  (3.5.x estável no Maven Central). Java 21 mantido (Boot 3.5 suporta).
- **Sem alteração de lógica de negócio** — só ajustes de compatibilidade
  documentados (breaking changes 3.3→3.5 conhecidos: mudanças de defaults de
  security, `spring-boot-starter-*` transitivas, mapeamentos do Jackson).
- `mvn test` completo verde (baseline 1995+ testes) como gate.

## D3 — Dependências críticas (C2, C4)

Versões que o Boot 3.5.x gerencia sobem junto:

| Lib | 3.3.5 (atual) | Esperada no 3.5.x |
|---|---|---|
| logback-classic | 1.5.11 | ≥ 1.5.x última patch (1.6.x se o Boot gerenciar) |
| jackson-databind | 2.17.2 | versão do Boot 3.5.x |
| caffeine / gson / json-path / h2 | 3.1.8 / 2.10.1 / 2.9.0 / 2.2.224 | gerenciadas pelo Boot 3.5.x |

- **Verificação**: `mvn versions:display-dependency-updates` mostra **só
  patches menores** restantes em libs de runtime usadas (sem major/minor).
- **Meta OWASP**: 0 vulnerabilidades ≥ 7.0 nas dependências de runtime
  (compiladas no jar). Achados em dependências de teste → suprimidos com
  justificativa (explicitação no pom/README).

## D4 — OWASP Dependency-Check operacional e bloqueante (C3)

1. **Plugin declarado no `pom.xml`** (pluginManagement + build/plugins):
   `org.owasp:dependency-check-maven` versão estável, com:
   - `<failBuildOnCVSS>7</failBuildOnCVSS>` (falha em HIGH/CRITICAL);
   - `<format>HTML,JSON,JUNIT</format>` (relatório + integração CI);
   - `nvdApiKey` lida de `env` (`NVD_API_KEY`) — **nunca** hardcoded.
2. **Workflow `.github/workflows/dependency-check.yml` atualizado**:
   - passo `Set up JDK 21` (já existe);
   - `NVD_API_KEY` lida do **secret do GitHub** (`${{ secrets.NVD_API_KEY }}`);
   - **cache da base NVD entre runs** (`actions/cache` no diretório do
     data-directory do OWASP) — sem isso cada run rebaixa a NVD inteira;
   - goal `check` via plugin declarado (não mais inline `org.owasp:...:check`);
   - **bloqueante**: `if: always()` não usado para passar por cima da falha;
     o job falha quando o check falha.
3. **Prova de operacionalidade**: rodar o workflow manualmente
   (`workflow_dispatch`) e registrar que **passa** (0 achados ≥ 7.0) — o
   requisito é "roda e passa", não "existe no repo".

## D5 — Ordem de execução e verificação (L19)

1. Bump parent → `mvn test` (1995+) — corrige incompatibilidades uma a uma.
2. OWASP plugin + workflow + NVD_API_KEY → run manual → 0 achados ≥ 7.0.
3. `mvn versions:display-dependency-updates` → só patches menores.
4. **Integridade pré-deploy (L19)**: `unzip -t` nos jars críticos (boot jar,
   spring-web, jackson, logback) + context-tests 2/2 + E2E completo.
5. Smoke em produção pós-deploy: health 200, venda 201+204, rate limit 429.

## D6 — Arquivos

| Arquivo | Mudança |
|---|---|
| `pom.xml` | parent 3.5.x + plugin OWASP no build + `NVD_API_KEY` env (D2/D4) |
| `.github/workflows/dependency-check.yml` | NVD_API_KEY secret + cache NVD + goal via plugin (D4) |
| `README.md`/`deploy/railway.md` | versões do Boot/Flyway/documentação de achados suprimidos (se houver) |

## D7 — Testes (C5)

- `mvn test` completo verde (baseline 1995+).
- E2E completo (suíte existente) sem regressão.
- Context-tests 2/2 (`mvn test -Pcontext-tests`).
- OWASP run manual → 0 achados ≥ 7.0 (registrar evidência).
- Smoke pós-deploy (health/venda/rate limit).

**Âncoras validadas na spec:** `pom.xml` parent 3.3.5 (linha 10);
`dependency-check.yml` com goal inline sem NVD_API_KEY (2 ocorrências no
workflow); plugin OWASP ausente do pom; `mvn test` baseline 1995/0/0;
`spring-boot-maven-plugin` (pom linha 145); Spec 18 (security hardening) como
base; L19 (integridade pré-deploy).
