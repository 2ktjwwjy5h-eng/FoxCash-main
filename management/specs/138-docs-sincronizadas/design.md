---
name: Documentação 100% sincronizada com o código
status: implemented
---

# Spec 138 — Design: Documentação 100% sincronizada (verificação mecânica)

## D1 — Conceito

O due diligence apontou que a documentação **não é fonte de verdade
confiável**: 5 specs com `status: verified` no requirements.md que **já têm
código implementado** (13-master-auth, 75-leads, 78-criador-bot,
97-painel-usuarios-leitura, 98-painel-usuarios-config — confirmado no
inventário real), tabela do roadmap desatualizada vs frontmatter (64 linhas,
ex.: Specs 62/65), e arch.md/adr.md fora de sync. O objetivo é **sync +
verificação mecânica no CI** (C5) — não reescrever a documentação.

## D2 — Estado real medido (2026-08-16)

- **5 specs** com `status: verified` no requirements.md mas com código no
  `src/` (inventário real: 13-master-auth, 75-leads-metricas-bots,
  78-criador-bot-discord, 97-painel-usuarios-leitura,
  98-painel-usuarios-config) — precisam virar `implemented`.
- Specs 101–119 (`verified`) são legítimas: **sem código** no `src/`, `portal/`
  ou `console/` (as rotas/páginas "ricas" do Gap 4 não existem ainda).
- 64 linhas da tabela do roadmap com status desatualizado vs frontmatter
  (ex.: Specs 62/65 — tabela `planned` × frontmatter/entrada `audited`).

## D3 — Estratégia: 3 passadas + regra mecânica (C1, C2, C3)

**Passada 1 — status das specs (C1):** para cada spec `verified` com código no
`src/`, rodar `/spec-verify`/`/spec-audit` onde faltar e atualizar o status
para `implemented`/`audited` (o audit valida critérios com evidência no
código). Nenhuma spec `verified` com código completo fica pendente.

**Passada 2 — tabela do roadmap == entrada detalhada (C2):** para TODAS as
specs da tabela, o status da coluna bate com o frontmatter/entrada detalhada
(62/65 corrigidas). A regra vira script automatizado (D4).

**Passada 3 — requirements == código (C3):** para cada spec
`audited`/`implemented`, os critérios referenciam classes/rotas/migrations que
**existem** no `src/` — script cruza as referências.

## D4 — Script de consistência no CI (C5)

`scripts/check-docs-consistency.sh` (novo, bash):

1. **Status da tabela vs entrada detalhada** — para cada linha `| N | ... |
   status |` do roadmap, o status bate com o frontmatter do requirements.md
   (`status:`). Falha = mismatch.
2. **Spec `verified` com código** — spec com `status: verified` cujas classes/
   rotas referenciadas existem no `src/` → falha (deve estar `implemented`).
3. **Referências inexistentes** — classe/rota citada no requirements (padrão
   `Xxx.java`/`/api/...`) sem correspondência no `src/` → falha.

- Roda no `ci.yml` (job novo ou step do job principal) — **falha o build**.
- Arquivo de **allowlist** para exceções documentadas (ex.: specs `planned`
  130-138 que referenciam código futuro) — sem allowlist, sem passagem.

## D5 — arch.md/adr.md/vision.md (C4)

- Atualizar `arch.md`/`adr.md`/`vision.md` com o estado real: decisões já
  implementadas (ADR-020 etc.) sem registro "futuro"; jornadas refletem o
  código. Sem reescrita — sync.

## D6 — Testes/regressão (C6)

- `mvn test` verde + E2E sem regressão (só docs + scripts de validação — zero
  mudança de código de comportamento).

## D7 — Arquivos

| Arquivo | Mudança |
|---|---|
| `scripts/check-docs-consistency.sh` | novo (D4) |
| `.github/workflows/ci.yml` | roda o script de consistência (D4) |
| `management/roadmap.md` | status 62/65 + tabela == entradas (D3) |
| `management/specs/*/requirements.md` | status → implemented/audited (D3) |
| `management/arch.md`/`adr.md`/`vision.md` | sync com o estado real (D5) |

## D8 — Fora de escopo

Reescrever a documentação (é sync); Rebranding FoxCash (a Spec 139/pendência
cuida do sync de nomes).

**Âncoras validadas na spec:** 25 specs `verified` (grep real); exemplos
13/75/78/101/117/118/119 `verified` com código; Specs 62/65 tabela × detalhe;
`arch.md`/`adr.md`/`vision.md` existem; `ci.yml` existe.
