---
name: Modal de termos de uso no cadastro do lojista (Spec 142 UI)
status: audited
---

# Spec 143 — Design: Modal de termos de uso no cadastro do lojista

## D1 — Conceito

No `RegisterPage.tsx` (Spec 142), o item dos termos passa de **link em nova
aba** para **modal na mesma tela**:

```
[ ✔ ] Li e concordo com os [termos de uso e condições]  ← .terms-link (button)
                    │  click → preventDefault + stopPropagation
                    ▼
┌───────────────────────────────────────────────────────────┐
│ modal-backdrop (role=dialog, aria-modal, aria-labelledby)  │
│  ┌ modal modal-wide (useDialogFocus)                    ┐  │
│  │ h2#terms-title "Termos de uso e condições"           │  │
│  │ div.terms-body (scroll)                              │  │
│  │   ↑ HTML de terms.ts(renderMarkdown(politicas-uso.md))│ │
│  │ [Entendi]                                            │  │
│  └──────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────┘
```

Conteúdo: `import termsMarkdown from '../../../docs/politicas-uso.md?raw'` —
**fonte única** (o arquivo do repo é embutido no bundle pelo Vite). Sem lib de
markdown (Fase 29).

## D2 — `terms.ts` (renderizador seguro)

```ts
export function renderMarkdown(src: string): string
```

- **`escapeHtml`** aplicado em TODA entrada antes de qualquer transformação
  (`& < > "` → entidades). O output só contém HTML gerado pelo próprio
  renderizador — o documento nunca injeta tags.
- **`inline`** — escapa e aplica `**negrito**` → `<strong>`.
- **Blocos** (por linha):
  - títulos `#{1,3}` → `<h1..3>`;
  - `hr` (`---`) → `<hr />`;
  - tabelas (`| a | b |` em sequência, pulando a linha separadora `:---`) →
    `<table><tr><th/td>…`;
  - listas `- ` → `<ul><li>`; `- ` com 2 espaços → `<ul>` aninhado; **linha
    recuada sem bullet** após um item → continuação do `<li>` atual
    (bullet quebrado em várias linhas);
  - linha vazia → fecha lista;
  - parágrafo → `<p>`.
- Estados locais: `table`, `inList`/`listNested`/`liText` com
  `flushListItem`/`closeList`/`flushTable`.
- Sem dependência — o bundle não cresce com lib de markdown.

## D3 — Integração na RegisterPage

```tsx
const [termsOpen, setTermsOpen] = useState(false);
const termsDialogRef = useDialogFocus({ open: termsOpen, onClose: () => setTermsOpen(false) });
const termsHtml = useMemo(() => renderMarkdown(termsMarkdown), []);
```

- **Trigger** — `<button className="terms-link" type="button"
  onClick={(e) => { e.preventDefault(); e.stopPropagation(); setTermsOpen(true); }}>`
  dentro do `<label className="checkbox-row">`. O `preventDefault` +
  `stopPropagation` impedem o toggle do checkbox (botão dentro de label
  alternaria o input).
- **Modal** — `{termsOpen && <div className="modal-backdrop" role="dialog"
  aria-modal="true" aria-labelledby="terms-title"><div className="modal
  modal-wide" ref={termsDialogRef}>…` com `h2#terms-title`, `.terms-body`
  (`dangerouslySetInnerHTML={{ __html: termsHtml }}` — seguro porque o HTML é
  100% gerado pelo renderizador sobre texto escapado) e botão **Entendi**
  (`onClick={() => setTermsOpen(false)}`).
- **`useDialogFocus`** — focus trap + Escape + restauração de foco (padrão
  Spec 73; usado nos modais do portal).

## D4 — CSS

- `.terms-link` (já existe) — botão com cara de link dentro do label do
  checkbox (Spec 142).
- `.modal-wide` (já existe) — `max-width: 640px` (leitura longa).
- `.terms-body` — `max-height: 60vh`, `overflow-y: auto`, estilos de leitura
  (títulos/p/h2-h3/ul/hr/tabela) para o texto jurídico.

## D5 — Vite (dev server)

```ts
// vite.config.ts
server: {
  fs: {
    allow: ['..'],   // Spec 143: import `?raw` de docs/politicas-uso.md (fora do root do portal)
  },
  ...
}
```

Sem isso, o `import ...md?raw` fora do root falha no dev (`fs.allow` bloqueia
por padrão). No build o `?raw` é resolvido pelo Rollup e embutido no bundle.

## D6 — Integração (arquivos tocados)

| Arquivo | Mudança |
|---|---|
| `portal/src/terms.ts` | (novo) renderizador markdown seguro (D2) |
| `portal/src/pages/RegisterPage.tsx` | modal dos termos (D3): estado `termsOpen`, `termsHtml`, trigger `.terms-link`, modal + "Entendi" |
| `portal/src/styles.css` | `.terms-link`/`.modal-wide`/`.terms-body` (já existiam — usados pelo modal) |
| `portal/vite.config.ts` | `fs.allow: ['..']` (dev lê `docs/politicas-uso.md?raw`) |
| `docs/politicas-uso.md` | documento canônico (fonte única — já existia) |

**Zero mudança de backend** — sem endpoints/contratos novos.

## D7 — Testes

- **Build** — `npm run build` (tsc + vite) no portal: o `?raw` resolve e o
  bundle compila (o `register-ui.spec.js` da Spec 142 continua 2/2).
- **Manual/visual** — cadastro: clicar em "termos de uso e condições" abre o
  modal com o conteúdo de `docs/politicas-uso.md` (títulos/negrito/listas/
  tabelas), rolagem, "Entendi" fecha, e o checkbox NÃO alterna; sem o aceite o
  botão "Criar conta" fica desabilitado.

**Âncoras validadas na spec:** `useDialogFocus` existe (`hooks/useDialogFocus`,
Spec 73); `docs/politicas-uso.md` existe (118 linhas — títulos `#`, negrito,
listas `-`, `1.1.` como parágrafo, `hr`, tabela); `.modal`/`.modal-backdrop`/
`.modal-actions`/`.modal-wide` existem em `styles.css`; Vite 5 com `?raw`
nativo.
