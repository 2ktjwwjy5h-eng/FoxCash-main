---
name: Modal de termos de uso no cadastro do lojista (Spec 142 UI)
status: audited
---

# Spec 143 — Task: Modal de termos de uso no cadastro do lojista

Ordem: T0 (renderizador) → T1 (modal na RegisterPage) → T2 (vite) → T3 (build).

## T0 — Renderizador seguro (critério 3)

- [x] T0.1: `portal/src/terms.ts` — `escapeHtml` antes do markdown; subset do
      documento (títulos `#/##/###`, negrito, listas `-` com aninhamento e
      continuação recuada, `hr`, tabelas, parágrafos); sem dependência.

## T1 — Modal na RegisterPage (critérios 1, 2, 4, 5)

- [x] T1.1: `import termsMarkdown from '../../../docs/politicas-uso.md?raw'` +
      `useMemo(() => renderMarkdown(termsMarkdown), [])` (fonte única).
- [x] T1.2: Trigger — `<button className="terms-link">` com `preventDefault` +
      `stopPropagation` (não alterna o checkbox) → `setTermsOpen(true)`.
- [x] T1.3: Modal — `modal-backdrop` (`role="dialog"` `aria-modal`
      `aria-labelledby="terms-title"`) + `modal modal-wide` com
      `useDialogFocus`, `h2#terms-title`, `.terms-body` (scroll) e botão
      **Entendi**; aceite continua controlando o botão **Criar conta**.

## T2 — Vite (critério 6)

- [x] T2.1: `vite.config.ts` — `server.fs.allow: ['..']` (dev lê o `?raw` fora
      do root do portal).

## T3 — Build (critério 6)

- [x] T3.1: `npm run build` (tsc + vite) no portal resolve o `?raw` e compila
      limpo.

---

## Implementação (2026-08-17) — notas

Implementação concluída ANTES da formalização da spec (o modal foi feito na
sessão e o fluxo SDD documenta o entregue). Notas de execução:

- **T0.1 (`terms.ts`)** — renderizador caseiro de ~130 linhas. Decisão de
  segurança: `escapeHtml` em toda entrada; o `dangerouslySetInnerHTML` do
  `.terms-body` só recebe HTML **gerado pelo próprio renderizador** sobre texto
  escapado (o documento é estático e não confiável como HTML). Cobre os
  constructos reais de `docs/politicas-uso.md` (118 linhas: `#` título,
  negrito, listas `-` com continuação recuada, `hr`, tabela, parágrafos;
  itens `1.1.` viram parágrafo — não são sintaxe de lista markdown).
- **T1.x (RegisterPage)** — o botão `terms-link` fica DENTRO do `<label>` do
  checkbox; sem `preventDefault`/`stopPropagation` o clique alternaria o
  checkbox (comportamento nativo de label). O modal usa `useDialogFocus`
  (focus trap/Escape/restauração — padrão Spec 73) e `?raw` para embutir o
  mesmo `docs/politicas-uso.md` do repo (fonte única, sem cópia do texto).
- **T2.1 (vite)** — sem `fs.allow: ['..']` o import `?raw` de
  `../../../docs/...` (fora do root do portal) falha no dev server; o build
  (Rollup) resolve o `?raw` normalmente.
- **T3.1** — `npm run build` (tsc + vite) limpo; o bundle compilado contém o
  texto dos termos (verificado no asset novo).

**Âncoras validadas na spec:** `useDialogFocus` (Spec 73); `docs/politicas-uso.md`
existe; `.modal`/`.modal-backdrop`/`.modal-actions`/`.modal-wide`/
`.terms-body`/`.terms-link` em `styles.css`; Vite 5 suporta `?raw` nativo.

## Auditoria (2026-08-17) — 6/6 critérios OK, 0 achados abertos

Verificação 1:1 contra o código real (critérios do requirements):

- **C1 (modal na mesma tela)** — `RegisterPage.tsx` linhas 232–246:
  `{termsOpen && <div className="modal-backdrop" ...>` com `modal modal-wide`,
  `.terms-body` (scroll) e botão **Entendi**; nenhuma navegação — o trigger é
  um `<button className="terms-link">` (não `<a target=_blank>`).
- **C2 (fonte única)** — linha 20: `import termsMarkdown from
  '../../../docs/politicas-uso.md?raw'`; `vite.config.ts` com
  `server.fs.allow: ['..']` (comentário "Spec 143"). Nenhuma cópia do texto no
  código.
- **C3 (renderização segura)** — `terms.ts`: `escapeHtml` (linha 10) aplicado
  em `inline()` (linha 19) antes do `**negrito**`; blocos suportados (títulos,
  listas aninhadas/continuação, `hr`, tabelas, parágrafos); export único
  `renderMarkdown`; **sem dependência** (package.json do portal inalterado).
  O `dangerouslySetInnerHTML` recebe apenas HTML gerado pelo renderizador.
- **C4 (checkbox intacto)** — trigger (linhas 207–217): `e.preventDefault()` +
  `e.stopPropagation()` antes de `setTermsOpen(true)`; o aceite segue no
  `onChange` do checkbox e o botão **Criar conta** permanece
  `disabled={submitting || !acceptedTerms}` (linha 222).
- **C5 (acessibilidade)** — `useDialogFocus({ open: termsOpen, ... })` (linha
  66) + `role="dialog"` `aria-modal="true"` `aria-labelledby="terms-title"`
  (linha 233) + `h2 id="terms-title"` (linha 235).
- **C6 (build)** — `npm run build` (tsc + vite) limpo; o asset novo do portal
  contém o texto dos termos (verificado); `scripts/check-docs-consistency.sh`
  → **OK** (esta spec fecha o gap de numeração — antes as referências "Spec
  143" em `RegisterPage.tsx`/`terms.ts`/`styles.css`/`vite.config.ts` apontavam
  para uma spec inexistente no roadmap).

**Observação de fechamento:** a Spec 143 estava referenciada em 6 pontos do
código (comentários) mas não registrada no roadmap (gap do tipo que a Spec 138
audita); o registro fecha a numeração e o `docs-consistency` valida o status
`audited` na tabela vs frontmatter.

Fim do ciclo SDD (roadmap/requirements/design/task `audited`).
