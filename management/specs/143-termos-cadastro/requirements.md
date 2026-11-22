---
name: Modal de termos de uso no cadastro do lojista (Spec 142 UI)
status: audited
references:
  - "management/vision.md#V104"
  - "management/specs/142-cadastro-ui-portal/requirements.md"
  - "management/specs/46-portal-frontend/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
  - "docs/politicas-uso.md"
---

# Spec 143 — Modal de termos de uso no cadastro do lojista (Spec 142 UI)

## O que entrega

O aceite dos **termos de uso e condições** no cadastro do lojista
(`RegisterPage.tsx`, Spec 142) deixa de abrir o documento em outra aba e passa
a exibir o conteúdo **na própria tela**, num **modal** com scroll — sem sair do
fluxo de cadastro. O conteúdo é o **mesmo `docs/politicas-uso.md` do
repositório** (fonte única), embutido no bundle via import `?raw` e renderizado
por um **mini-renderizador de markdown seguro** (`portal/src/terms.ts`) que não
adiciona dependência de lib.

1. **Fonte única do documento** — `import termsMarkdown from
   '../../../docs/politicas-uso.md?raw'`: o mesmo arquivo versionado no repo é
   embutido no bundle (não há cópia do texto em código). No dev, o
   `vite.config.ts` ganhou `fs.allow: ['..']` para o Vite poder ler um arquivo
   fora do root do portal.
2. **Renderização leve e segura** (`terms.ts`) — cobre APENAS o subset de
   markdown usado no documento: títulos `#`/`##`/`###`, **negrito** (`**x**`),
   listas `-` com **aninhamento** (2 espaços) e **continuação recuada** (bullet
   quebrado em várias linhas), `hr` (`---`), tabelas (`| a | b |`) e parágrafos.
   O conteúdo é **escapado (`escapeHtml`) antes** de aplicar o markdown — o
   documento nunca renderiza HTML próprio (texto jurídico estático, sem
   confiança no input). Zero dependência (bundle pequeno).
3. **Modal na própria tela** — o link "termos de uso e condições"
   (`.terms-link`, botão estilizado como link dentro do label do checkbox) abre
   `modal modal-wide` com `.terms-body` (scroll) e botão **Entendi** para
   fechar. O botão usa `preventDefault`/`stopPropagation` para **não alternar o
   checkbox** do aceite ao abrir o modal.
4. **Acessibilidade** — `useDialogFocus` (focus trap, Escape, restauração de
   foco), `role="dialog"` `aria-modal="true"` `aria-labelledby="terms-title"`.
5. **Regressão do aceite** — o botão **Criar conta** continua `disabled` sem o
   aceite; marcar o checkbox continua funcionando normalmente.

## Contexto (por que agora)

A Spec 142 (audited 2026-08-17) entregou o cadastro com o link dos termos
aberto em **nova aba** (`target=_blank`). O usuário pediu para o documento
aparecer na **mesma tela** — UX de modal sem navegação. Como o portal é CSS
puro e sem libs (Fase 29), a renderização do markdown é **caseira e mínima**
(`terms.ts`), cobrindo só o que `docs/politicas-uso.md` usa, com escape de HTML
para não virar superfície de XSS (o documento é texto jurídico estático).

## Critérios de aceite

1. **Modal na mesma tela** — o link dos termos abre `modal modal-wide` com o
   conteúdo de `docs/politicas-uso.md` renderizado em `.terms-body` (scroll) e
   botão **Entendi**; nenhuma navegação para fora do site.
2. **Fonte única** — o conteúdo vem de `import ... from
   '../../../docs/politicas-uso.md?raw'` (sem cópia do texto no código);
   `vite.config.ts` com `fs.allow: ['..']` (dev server lê o arquivo fora do
   root).
3. **Renderização segura** — `terms.ts` escapa HTML antes do markdown (nunca
   `dangerouslySetInnerHTML` com HTML do documento); suporta os constructos do
   documento (títulos, negrito, listas aninhadas/continuação, `hr`, tabelas,
   parágrafos); sem dependência de lib.
4. **Checkbox intacto** — o clique no botão-link `terms-link` NÃO alterna o
   checkbox do aceite (`preventDefault` + `stopPropagation`); o aceite continua
   habilitando o botão **Criar conta**.
5. **Acessibilidade** — `useDialogFocus` (focus trap, Escape, restauração),
   `role="dialog"` `aria-modal="true"` `aria-labelledby="terms-title"`.
6. **Build limpo** — `npm run build` (tsc + vite) no portal resolve o `?raw` e
   compila sem erro.

## Dependências

- **Spec 142** (cadastro) — `RegisterPage.tsx`, checkbox de termos, botão
  Criar conta.
- **Spec 46** (portal foundation) — Vite/SPA, bundle.
- **Spec 73** (UX) — `useDialogFocus` (padrão de diálogos acessíveis),
  `.modal`/`.modal-backdrop`/`.modal-actions`.
- **`docs/politicas-uso.md`** — documento canônico (fonte única do conteúdo).

## Fora de escopo

- **Backend** — nada muda (nenhum endpoint/contrato).
- **Outras telas** — o modal é do cadastro; outros usos do documento seriam
  spec nova.
- **Lib de markdown** — decisão: renderizador caseiro mínimo (sem `marked`/
  `react-markdown`), consistente com a Fase 29 (sem libs de UI).
- **Edição do texto jurídico** — conteúdo é de `docs/politicas-uso.md` (não é
  spec de compliance).
