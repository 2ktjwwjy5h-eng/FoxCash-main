---
name: Documentação 100% sincronizada com o código
status: audited
---

# Spec 138 — Task: Documentação 100% sincronizada

Ordem: T0 (inventário) → T1 (status das specs) → T2 (roadmap) → T3 (script CI)
→ T4 (arch/adr/vision) → T5 (regressão).

## T0 — Inventário (critérios C1, C2)

- [x] T0.1: Listar todas as specs `verified` com código no `src/` (grep real —
      baseline 25) — planilha/lista com o status atual.
- [x] T0.2: Listar mismatches da tabela do roadmap vs entrada detalhada
      (ex.: 62/65).

## T1 — Status das specs (critério C1)

- [x] T1.1: Para cada spec `verified` com código completo — rodar
      `/spec-verify`/`/spec-audit` onde faltar e atualizar o frontmatter para
      `implemented`/`audited`.
- [x] T1.2: Nenhuma spec `verified` fica com código completo pendente
      (verificação final).

## T2 — Roadmap (critério C2)

- [x] T2.1: Corrigir 62/65 e qualquer outra linha cujo status da tabela não
      bate com a entrada detalhada.

## T3 — Script de consistência no CI (critério C5)

- [x] T3.1: `scripts/check-docs-consistency.sh` — (1) status da tabela ==
      frontmatter; (2) spec `verified` com código → falha; (3) classe/rota
      referenciada inexistente → falha; allowlist para exceções documentadas.
- [x] T3.2: `.github/workflows/ci.yml` — job/step roda o script e **falha o
      build** se inconsistente.

## T4 — arch/adr/vision (critério C4)

- [x] T4.1: `arch.md`/`adr.md`/`vision.md` — sync com o estado real (ADR-020
      etc.), sem registros "futuros" já implementados.

## T5 — Regressão (critério C6)

- [ ] T5.1: `mvn test` verde + E2E sem regressão (só docs + scripts — zero
      mudança de comportamento).

**Âncoras validadas na spec (inventário real 2026-08-16):** 5 specs `verified`
com código (13/75/78/97/98 — corrigido; specs 101–119 NÃO têm código);
64 linhas da tabela do roadmap desatualizadas (ex.: 62/65);
`arch.md`/`adr.md`/`vision.md`; `ci.yml`.

## Auditoria (2026-08-17) — `/spec-audit 138`

**6/6 critérios OK, 0 achados de código, 0 abertos.**

- **C1 (status corretos)** — frontmatter das 5 specs com código → `implemented`
  (13/75/78/97/98; +99/100 UI); specs 101–119 seguem `verified` legítimas (sem
  código); nenhuma spec `verified` com código completo no `src/` (check B do
  script passa).
- **C2 (tabela == entradas)** — 64 linhas da tabela sincronizadas; regra A do
  script (tabela == frontmatter) **verde** — verificado nesta auditoria após as
  Specs 139/141/130/131/132 (`audited`).
- **C3 (requirements == código)** — check C do script (classe/rota citada em
  spec implemented/audited existe) **verde** — verificado nesta auditoria.
- **C4 (arch/adr/vision)** — ADR-020 aceito + jornadas J1–J4 no arch.md; vision
  V98/V99/V100/V101 consistentes com o estado real (verificado nos audits
  139/141).
- **C5 (regra automatizada no CI)** — `scripts/check-docs-consistency.sh`
  presente com 3 regras (A tabela==frontmatter, B verified-com-código falha, C
  classe inexistente falha) + allowlist vazia; `ci.yml` job `docs` roda o script
  e **falha o build** — verificado no workflow.
- **C6 (sem regressão)** — `mvn test` **2050/0/0** re-executado nesta auditoria
  + docs-consistency **OK** (check A/B/C) — zero mudança de comportamento (só
  docs + script).

**docs-consistency:** OK (check A/B/C verdes). Fim do ciclo SDD — Spec 138
`audited`, feature LIVE.
