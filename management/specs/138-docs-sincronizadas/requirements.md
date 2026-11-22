---
name: Documentação 100% sincronizada com o código
status: audited
references:
  - "management/roadmap.md#Fase 41"
  - "management/specs/62-capacity-engineering-high-throughput/requirements.md"
---

# Spec 138 — Documentação 100% sincronizada com o código

## O que entrega

O due diligence apontou que a documentação **não é fonte de verdade confiável**:

- **5 specs com status `verified` no requirements.md que JÁ têm código
  implementado** (13-master-auth, 75-leads, 78-criador-bot,
  97-painel-usuarios-leitura, 98-painel-usuarios-config — inventário real
  2026-08-16) — o status não foi atualizado quando o código foi entregue;
- **Tabela do roadmap desatualizada** vs. entradas detalhadas (ex.: Spec 62
  mostrava `planned` na tabela com texto `audited`; Spec 65 mostrava `planned`
  com detalhe `audited`);
- `arch.md`/`adr.md` com registros de design fora de sync.

Uma multinacional lê a documentação para avaliar o sistema — inconsistências
deste tipo são lidas como "não sabem o que têm".

## Critérios de aceite

**C1 — Todos os status corretos.** Todas as specs com código existente têm
status `implemented` ou `audited` (rodar `/spec-verify`/`/spec-audit` onde
faltar). Nenhuma spec `verified` tem código completo no `src/`.

**C2 — Tabela do roadmap == entradas detalhadas.** Para TODAS as specs da tabela,
a coluna de status bate com a entrada detalhada (regra automatizada: um script
valida a consistência e roda no CI — ver C5).

**C3 — requirements.md == código.** Para cada spec `audited`/`implemented`, os
critérios de aceite têm evidência verificável no código (classes/endpoints/
migrations existem). Validação: script que cruza referências de classe/rota dos
requirements com o `src/`.

**C4 — arch.md/adr.md/vision.md atualizados.** As decisões de arquitetura e as
jornadas refletem o estado real (ADR-020 etc.). Sem registros "futuros" que já
foram implementados.

**C5 — Regra automatizada no CI.** Um script de consistência (ex.:
`scripts/check-docs-consistency.sh`) roda no CI e **falha o build** se: status
da tabela ≠ status da entrada detalhada; spec `verified` com código existente;
requirements referenciando classe/rota inexistente. A documentação passa a ser
verificada mecanicamente, não por boa vontade.

**C6 — Sem regressão.** `mvn test` verde + E2E sem mudança de código de
comportamento (só docs + scripts de validação).

## Evidência-base (2026-08-16)

- 5 specs `verified` com código existente (13/75/78/97/98 — inventário real;
  specs 101–119 são `verified` legítimas, SEM código no `src/`/portal/console).
- 64 linhas da tabela do roadmap com status desatualizado vs frontmatter
  (ex.: Specs 1–15 mostravam `planned` com specs já `audited`; 62/65 idem).
- `arch.md`/`adr.md` com registros do Gap 4 (ADR-020) já aceitos — verificado
  consistente com o código real nesta implementação.

## Dependências

- **Spec 130** — rodar depois (o upgrade pode tocar versions/docs de deps).
- Nenhuma dependência de código — pode rodar em paralelo.

## Fora de escopo

- Reescrever a documentação (é sync, não reescrita).
- Rebranding FoxCash (Spec 139/pendência) — o sync de nomes fica com ele.
