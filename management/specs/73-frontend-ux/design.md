---
name: Frontend UX — fluxos e usabilidade (console + portal)
status: audited
---

# Spec 73 — Design: Frontend UX (console + portal)

## D1 — Conceito

Padronizar a experiência de **fluxos** nos dois frontends. Frentes:

| Frente | Critérios | Onde |
|---|---|---|
| F1 — Feedback de ações | 1, 2, 11 | `ui.tsx` (ambos) + páginas de escrita do portal |
| F2 — Diálogos acessíveis | 3, 4, 5 | `ConfirmDialog`/`StepUpPrompt` (console) + `ConfirmDialog` novo (portal) + `CredentialsPage` |
| F3 — Loading/vazio | 6, 7, 8 | `ui.tsx` (`useAsync`/`Spinner`) + páginas |
| F4 — Navegação | 9 | `Layout` (ambos) + `MerchantDetailPage` |
| F5 — Formulários | 10 | `FinancialPage` + formulários de valor do portal |

Princípio: **espelhar, não abstrair** — os frontends são builds separados (Spec
46/71); o componente novo/ajustado é duplicado em cada `ui.tsx` (KISS, sem
pacote compartilhado).

## D2 — F1: Feedback de ações (critérios 1, 2, 11)

### D2.1 `ui.tsx` (ambos) — banners anunciáveis

```tsx
// console/src/components/ui.tsx  e  portal/src/components/ui.tsx
export function ErrorBanner({ error }: { error: unknown }) {
  if (!error) return null;
  const message = ...;
  return <div className="alert alert-error" role="alert">{message}</div>;
}

export function SuccessBanner({ message }: { message: string | null }) {
  if (!message) return null;
  return <div className="alert alert-success" role="status">{message}</div>;
}
```

- **Portal**: `SuccessBanner` é NOVO no `ui.tsx` (paridade com console).
- `role="alert"`/`role="status"` dão `aria-live` implícito (assertive/polite)
  → screen reader anuncia sucesso/erro.
- Nenhuma mudança de CSS (`.alert-*` já existe).

### D2.2 Portal — feedback em toda ação de escrita (critério 2)

Padrão por página (estado local `feedback: string | null` + `ErrorBanner`):

- **`WebhooksPage`**: `handleCreate` → `setFeedback('Webhook cadastrado.')`;
  `toggleActive` → `setFeedback(hook.active ? 'Webhook desativado.' : 'Webhook ativado.')`
  e **try/catch** → `setFormError(err)` (hoje sem catch → unhandled rejection).
- **`CredentialsPage`**: `revoke` → `setFeedback('Chave revogada.')` +
  try/catch → `setFormError(err)`.
- **`DestinationsPage`**: `handleCreate` → `setFeedback('Destino cadastrado.')`;
  `toggleActive` → `setFeedback('Destino ativado/desativado.')`; `updateLabel`
  → try/catch → `setFormError(err)` (hoje sem catch).

Banner de sucesso renderizado no topo da página (padrão console) via
`<SuccessBanner message={feedback} />`.

### D2.3 Copiar com feedback (critério 11)

Novo helper `copyToClipboard(text): Promise<boolean>` (em cada `ui.tsx`) que
usa `navigator.clipboard.writeText` com try/catch. Componente/atributo:

```tsx
const [copied, setCopied] = useState(false);
const handleCopy = async (text: string) => {
  const ok = await copyToClipboard(text);
  setCopied(ok);               // + setTimeout 2s → setCopied(false)
};
// botão: {copied ? 'Copiado!' : 'Copiar'}  (aria-live opcional no botão)
```

Aplicado em: `ChargePage` (copia-e-cola), `ProfilesPage` (senha temporária —
botão ao lado do `.secret-box`), `CredentialsPage` (chave gerada),
`SecurityPage` (recovery codes — botão "Copiar todos" ou por linha). Falha →
feedback de erro (`Copied` false → label "Falha ao copiar" ou `ErrorBanner`).

## D3 — F2: Diálogos acessíveis (critérios 3, 4, 5)

### D3.1 Hook `useDialogFocus` (console e portal, cada `ui.tsx` ou hook local)

Comportamento do modal acessível — reutilizado por `ConfirmDialog` e
`StepUpPrompt`:

```tsx
function useDialogFocus(opts: {
  open: boolean;
  onClose: () => void;
  /** Elemento a focar ao abrir: 'first' | 'last' | ref. Default 'first'. */
  initialFocus?: 'first' | 'last' | RefObject<HTMLElement | null>;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const restoreRef = useRef<HTMLElement | null>(null);
  useEffect(() => {
    if (!open) return;
    restoreRef.current = document.activeElement as HTMLElement | null; // salva foco
    const el = ref.current;
    if (el) {
      const focusables = Array.from(el.querySelectorAll<HTMLElement>(
        'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'));
      const target =
        opts.initialFocus === 'last' ? focusables[focusables.length - 1]
        : typeof opts.initialFocus === 'object' && opts.initialFocus?.current ? opts.initialFocus.current
        : focusables[0]; // 'first' (default)
      target?.focus();
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.preventDefault(); onClose(); return; }
      if (e.key !== 'Tab' || !el) return;
      const focusables = Array.from(el.querySelectorAll<HTMLElement>(
        'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'));
      if (!focusables.length) return;
      const first = focusables[0], last = focusables[focusables.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      restoreRef.current?.focus(); // restaura foco ao fechar
    };
  }, [open, onClose]);
  return ref;
}
```

- Foco preso dentro do modal (Tab cíclico), Escape fecha, foco restaurado no
  elemento que abriu ao desmontar.
- **Autofocus guiado pelo tom** (achado da verificação 2026-08-12): no
  `ConfirmDialog` o primeiro focável do DOM é o botão **Cancelar** (vem antes
  de Confirmar). `initialFocus='first'` (→ Cancelar) para tom `danger`
  (segurança: Enter acidental NÃO confirma ação destrutiva) e `initialFocus=
  'last'` (→ Confirmar) para tom `primary` (confirmações neutras). O
  `StepUpPrompt` usa `initialFocus={inputRef}` (campo TOTP — o usuário digita
  antes de confirmar; comportamento atual preservado).
- `modal-backdrop` já cobre a tela (clique no backdrop → `onClose`? manutenção
  do comportamento atual — hoje o backdrop não fecha; **mantém-se sem fechar
  por clique** para não mudar fluxos existentes; só teclado/Escape/✓).

### D3.2 `ConfirmDialog` (console) — tom + aria

```tsx
export interface ConfirmDialogProps {
  open: boolean; title: string; message: string; confirmLabel: string;
  busy: boolean; error: unknown; onConfirm: () => void; onCancel: () => void;
  children?: ReactNode;
  tone?: 'danger' | 'primary';   // default 'danger' — retrocompatível
}

// <div className="modal-backdrop" role="dialog" aria-modal="true"
//      aria-labelledby={titleId} aria-describedby={msgId}>
//   <h2 id={titleId}>…</h2>
//   <p id={msgId}>…</p>
//   ...
//   <button className={`btn ${tone === 'primary' ? 'btn-primary' : 'btn-danger'}`} …>
//     {busy ? 'Executando…' : confirmLabel}
//   </button>
```

- `useDialogFocus({ open, onClose: onCancel, initialFocus: tone === 'danger' ?
  'first' : 'last' })` — foco/Escape/restauração; danger foca Cancelar
  (segurança), primary foca Confirmar.
- `tone='primary'` nas confirmações neutras: "Já copiei" (ProfilesPage),
  "Confirmar cobrança" (MerchantInvoicesTab), "Liberar" (MerchantDetailPage),
  "Confirmar saque"/"Criar" etc. onde o critério 4 aplicar.

### D3.3 `StepUpPrompt` (console) — mesmo tratamento

Aplica `useDialogFocus` + `aria-labelledby`/`describedby` (título
"Reautenticação necessária" + texto de explicação). `initialFocus={inputRef}`
(campo TOTP — o usuário digita antes de confirmar; o Tab cíclico começa pelo
campo; comportamento atual preservado). Escape → cancela (mesmo comportamento
do botão Cancelar).

### D3.4 `ConfirmDialog` novo no portal + confirmação de ações destrutivas

- Copiar `ConfirmDialog` para `portal/src/components/ConfirmDialog.tsx`
  (mesma implementação acessível, sem `useStepUp` — o portal não tem step-up).
- CSS do modal já existe no portal? **Não** — o `styles.css` do portal não tem
  `.modal-backdrop`/`.modal`/`.modal-actions` (só o console tem). Copiar os
  estilos do console para o portal (`modal-backdrop`, `modal`, `modal-actions`
  — seção "Modal de confirmação").
- **`CredentialsPage` "Revogar"**: abre `ConfirmDialog` (tom danger) com
  mensagem "Revogar a chave {label}? As integrações que a usam deixarão de
  funcionar. Ação irreversível." → confirma → `revoke` com feedback (D2.2).
- `WebhooksPage`/`DestinationsPage` toggles: **reversíveis** (ativar/desativar)
  → NÃO exigem confirmação (decisão registrada: manter 1 clique, consistente
  com o console que só confirma ações irreversíveis/sensíveis).

## D4 — F3: Loading/vazio (critérios 6, 7, 8)

### D4.1 `useAsync` — manter dados durante reload (critério 8)

```tsx
export function useAsync<T>(fn: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);   // true apenas no 1º load
  ...
  useEffect(() => {
    let cancelled = false;
    setError(null);
    setLoading(data === null);   // reload com dados → não volta ao spinner
    run().then(r => { if (!cancelled) setData(r); })
      .catch(err => { if (!cancelled) setError(err); })
      .finally(() => { if (!cancelled) setLoading(false); });
    ...
  }, [run, tick]);
```

- Com dados anteriores, `loading` fica `false` durante o reload → a lista não
  some (sem blink). O `data` anterior permanece até o novo chegar.
- Primeiro carregamento (`data === null`): `loading=true` → `Spinner`.
- `reload()` continua idêntico.

### D4.2 Spinner uniforme (critério 6)

- Portal: trocar `<div className="muted">Carregando…</div>` por `<Spinner />`
  em `CredentialsPage` (91), `DestinationsPage` (87), `WebhooksPage` (84),
  `WithdrawPage` (186).
- `ChargePage`: `{presets.loading ? <ErrorBanner error={undefined} /> : …}` →
  `{presets.loading ? <Spinner /> : …}` (fix do bug).
- Console `FinancialPage`: select de lojistas — durante `merchants.loading`,
  desabilitar o select e mostrar opção "Carregando lojistas…" (ou manter a
  opção "Selecione o lojista" + `disabled`); após erro → `ErrorBanner` (já
  existe).

### D4.3 Empty states (critério 7)

Componente inline (não novo arquivo — manter KISS; padrão de linha/`<p>`):

```tsx
// tabela: {items.length === 0 && (
//   <tr><td colSpan={N} className="muted">Mensagem</td></tr>)}
// lista:  {items.length === 0 && <p className="muted">Mensagem</p>}
```

Adicionar em (mensagens específicas):

| Tela | Onde | Mensagem |
|---|---|---|
| Console `FinancialPage` | ledger | "Nenhum lançamento no ledger." |
| Console `ProfilesPage` | tabela de perfis | "Nenhum perfil criado ainda." |
| Console `MerchantDetailPage` | tabs ledger/receivables/withdrawals | "Nenhum lançamento.", "Nenhum receivable.", "Nenhum saque." |
| Portal `DashboardPage` | faturamento + últimas vendas | "Sem dados de faturamento." / "Nenhuma venda ainda." |
| Portal `ChargePage` | gift presets | "Nenhum preset disponível." |
| Portal `SalesPage` | tabela | "Nenhuma venda no período." (verificar colSpan) |
| Portal `WalletPage` | ledger | "Nenhum lançamento na carteira." (verificar) |

## D5 — F4: Navegação (critério 9)

### D5.1 Skip-link (console + portal `Layout.tsx`)

```tsx
<a href="#main-content" className="skip-link">Pular para o conteúdo</a>
...
<main id="main-content" className="content"><Outlet /></main>
```

CSS (ambos os `styles.css`): `.skip-link { position:absolute; left:-9999px; top:0;
z-index:100; } .skip-link:focus { left:8px; top:8px; padding:8px 12px;
background:var(--primary); color:#fff; border-radius:8px; }` — visível apenas
no foco (teclado).

### D5.2 Nome do lojista no detalhe (console)

`MerchantDetailPage`: carregar a lista de lojistas (já existe o endpoint
`GET /api/v1/master/merchants`, cap 100 — padrão do `FinancialPage`) e
resolver `name` por `id` para o `h1` ("Lojista {name}"), com fallback para
"{id}…" quando não encontrado. Sem endpoint novo (critério 9).

## D6 — F5: Formulários (critério 10)

### D6.1 `FinancialPage` (console) — paridade `Field`

Trocar os 4 `<label className="field">…</label>` do saque administrativo pelo
componente `Field` do `ui.tsx` com `required` + `autoComplete` onde couber —
mesma estrutura visual (`.field`/`.field-label`/`.input` idênticos), sem
mudança de CSS. O select de lojista fica como `<label className="field">`
(componente `Field` só cobre `<input>`) com `disabled` durante loading (D4.2).

### D6.2 Validação de valor com mensagem específica (portal)

Nos formulários de valor (`ChargePage` avulsa, `WithdrawPage` saque,
`WithdrawPage` auto-withdraw): mensagem "Informe um valor maior que zero" (em
vez de "Informe um valor válido") quando `value <= 0` ou não numérico; manter
a mensagem do backend para os demais erros. Se houver outro formulário de
valor no console (saque administrativo já valida via `formValid` + botão
desabilitado) — manter.

## D7 — Arquivos

```
console/src/
  components/ui.tsx              [SuccessBanner role=status; ErrorBanner role=alert;
                                  copyToClipboard; useAsync mantém dados (D4.1)]
  components/ConfirmDialog.tsx   [tone danger|primary; useDialogFocus; aria-* (D3.2)]
  components/StepUpPrompt.tsx    [useDialogFocus; aria-* (D3.3)]
  components/Layout.tsx          [skip-link (D5.1)]
  styles.css                     [.skip-link (D5.1)]
  pages/MerchantDetailPage.tsx   [h1 com nome do lojista (D5.2); tone primary em
                                  confirmações neutras; empty states tabs (D4.3)]
  pages/FinancialPage.tsx        [Field + required; select loading; empty ledger]
  pages/ProfilesPage.tsx         [tone primary "Já copiei"; copiar senha (D2.3);
                                  empty state (D4.3)]
  pages/SecurityPage.tsx         [copiar recovery codes (D2.3)]
portal/src/
  components/ui.tsx              [SuccessBanner novo; ErrorBanner role=alert;
                                  copyToClipboard; useAsync (D4.1)]
  components/ConfirmDialog.tsx   [NOVO — D3.4]
  styles.css                     [.skip-link; .modal-backdrop/.modal/.modal-actions
                                  (D3.4)]
  components/Layout.tsx          [skip-link (D5.1)]
  pages/WebhooksPage.tsx         [feedback + try/catch (D2.2); Spinner (D4.2)]
  pages/CredentialsPage.tsx      [ConfirmDialog revoke (D3.4); feedback (D2.2);
                                  copiar chave (D2.3); Spinner (D4.2)]
  pages/DestinationsPage.tsx     [feedback + try/catch (D2.2); Spinner (D4.2)]
  pages/ChargePage.tsx           [fix presets.loading (D4.2); copiar (D2.3);
                                  mensagem de valor (D6.2); empty presets (D4.3)]
  pages/WithdrawPage.tsx         [Spinner (D4.2); mensagem de valor (D6.2)]
  pages/DashboardPage.tsx        [empty states (D4.3)]
  pages/SalesPage.tsx            [empty state (D4.3)]
  pages/WalletPage.tsx           [empty state (D4.3)]
```

## D8 — Rastreabilidade (critérios ↔ design ↔ task)

| Critério (requirements) | Design | Task |
|---|---|---|
| 1 (banners anunciáveis) | D2.1 | F1 |
| 2 (feedback portal) | D2.2 | F1 |
| 3 (diálogos acessíveis) | D3.1–D3.3 | F2 |
| 4 (tom danger/primary) | D3.2 | F2 |
| 5 (confirmação portal) | D3.4 | F2 |
| 6 (loading uniforme) | D4.2 | F3 |
| 7 (empty states) | D4.3 | F3 |
| 8 (recarga não zera) | D4.1 | F3 |
| 9 (skip-link + nome) | D5 | F4 |
| 10 (formulários) | D6 | F5 |
| 11 (copiar feedback) | D2.3 | F1 |
| 12 (sem regressão) | — | F6 |

## D9 — Inventário de componentes compartilhados (registro 2026-08-16, referência do Gap 4)

Catálogo do que **reusa** entre as 11 specs de UI do Gap 4 (evita duplicação na
implementação). Padrão Spec 73: **espelhar, não abstrair** — cada frontend tem o
seu `ui.tsx`.

| Componente | Origem | Reusado em | Notas |
|---|---|---|---|
| `useAsync` (mantém dados no reload) | ui.tsx (ambos) | TODAS as páginas com fetch | D4.1 |
| `Spinner` | ui.tsx | TODAS (loading inicial) | D4.2 |
| `ErrorBanner` / `SuccessBanner` | ui.tsx | TODAS (feedback) | D2.1 |
| `Field` (input+label+erro) | ui.tsx | Formulários (construtor 116, coprodutor 112, novo link 110, fluxos 118) | D6.1 |
| `ConfirmDialog` (tone danger/primary) | components | Destrutivas: excluir bot/link/checkout/campanha; pausar bio link | D3.2/D3.4 |
| `copyToClipboard` + "Copiado!" | ui.tsx | Copiar URL de link/checkout/bio/código afiliado/JSON webhook | D2.3 |
| `PeriodTabs` / segments | novo (106) | 106 (Financeiro), 114 (Ranking) | estado `period` |
| `KpiCard` | novo (104) | 104 (dashboard), 106 (financeiro), 112 (afiliado), 110 (links) | `{title, value, sub}` |
| `ProgressBar` | novo (104) | 104 (premiações), 114 (RewardCard), barra de meta do shell (102) | `progressPercent` |
| `LeadAvatar` (iniciais) | novo (102) | 102 (header), 114 (pódio), 116 (bio link preview) | sem foto → iniciais |
| `LinksTable` (Spec 110) | novo (110) | 118 (Redirecionadores — filtro filtro de tráfego) | reuso direto |
| `useDialogFocus` | hook (ambos) | Todos os modais (ConfirmDialog, Connect/Edit, drawers) | D3.1 |
| `renderMessage` (storefront) | bots/core | 118 (Remarketing — placeholders `{{nome}}`) | reuso conceitual |
| `WebhookUrlValidator` | backend | 109/110/115/116/117/118 (validação de URL — SSRF-safe) | chamada server-side |

**Decisões de inventário (evitar retrabalho):**
- `PeriodTabs` e `KpiCard` nascem na spec que os usa primeiro (106/104) e são
  importados pelas demais — não criar componente "genérico" antes da primeira
  necessidade (KISS).
- A `LinksTable` da 110 vira o componente-base de listagem de links (reusado
  pela 118); a tabela de transações da 106 **não** vira componente genérico
  (colunas específicas demais — KISS).
- Modais de escrita (coprodutor 112, novo link 110, item bio 116, prêmio
  sorteio 116) seguem o mesmo padrão estrutural (`modal-backdrop` + `modal` +
  `Field` + `modal-actions`) **sem** componente único — espelhar o markup.

## D10 — Comportamento mobile/responsivo (registro 2026-08-16, referência)

Regras consolidadas para as páginas do Gap 4 (CSS puro, Spec 73). Evolução
completa de mobile permanece fora de escopo das specs 101–119 — estas regras
são o mínimo para não quebrar em telas estreitas.

| Padrão | Regra | Onde |
|---|---|---|
| Sidebar | colapsável (botão hamburger) em < 900px; menu vira drawer | Shell 102 |
| Header global | empilha: billing → saudação → carrossel → atalhos (wrap) | 102 |
| Tabelas | < 720px: linhas viram **cards** (label + valor por linha) ou scroll horizontal com `min-width` | 106, 110, 112, 118, 119 |
| Pódio | < 640px: empilha vertical (1º → 2º → 3º), escala normal | 114 |
| Construtor checkout/bio | < 860px: preview vai para baixo do formulário | 116 |
| KPIs | grid 4→2→1 colunas (breakpoints 1100/720/480) | 104, 106, 110, 112 |
| Botões grandes (bio link público) | full-width, altura ≥ 48px (touch) | 116 |
| Modais | < 480px: full-screen (sem borda, canto arredondado no topo) | todos |
| Abreviação de valor | `R$ 8,5k` apenas < 480px (senão `formatMoney` completo) | 114 |

**Princípio**: mobile = empilhar e preservar legibilidade; nunca esconder
informação essencial (status/valores) — scroll horizontal aceitável em tabelas
densas.

## D11 — Catálogo de empty states e erros (registro 2026-08-16, referência)

Padrão D4.3 aplicado às 11 specs do Gap 4 — mensagens específicas:

| Página | Empty state | Erro padrão |
|---|---|---|
| Dashboard (104) | "Sem dados no período." | ErrorBanner + reload |
| Financeiro (106) | "Nenhuma transação no período." / "para os filtros selecionados" + "Limpar filtros" | idem |
| Gateways admin (108) | "Nenhum adquirente configurado." | idem |
| Links (110) | "Nenhum link ainda." + CTA; "Nenhum link para a busca/filtros." + Limpar | idem |
| Afiliado (112) | "Nenhum coprodutor ainda." / "Sem comissões para este coprodutor." | idem |
| Ranking (114) | "Nenhum dado de faturamento no período." | idem |
| Premiações (114) | "Nenhuma premiação configurada ainda." | idem |
| Fluxos (118) | "Nenhum fluxo ainda." + Novo | idem |
| Redirecionadores (118) | "Nenhum redirecionador ainda." | idem |
| Remarketing (118) | "Nenhuma campanha ainda." / "Sem disparos ainda." | idem |
| CRM (119) | "Nenhum cliente encontrado." (busca) | idem |
| Comunidade (119) | "Nenhum aviso publicado ainda." | idem |
| Checkout público (116) | 404 → "Checkout não encontrado" + voltar (sem vazar info) | 429 neutro |
| Bio Link público (116) | 404 → "Página não encontrada" + voltar | idem |
| Sorteio (116) | "Nenhum prêmio configurado." + 1º prêmio | idem |

**Regras transversais** (todas as páginas):
- Erro nunca derruba o layout (banner + `reload()`); dados anteriores ficam
  visíveis durante reload (D4.1).
- Empty state com CTA quando a ação de criação é óbvia (links, fluxos,
  coprodutores, checkouts, campanhas); sem CTA em telas read-only (comunidade).
- Erros de rede/429/404 com mensagem neutra — nunca expor detalhe interno.
