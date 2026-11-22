---
name: Gestão de Links e Campanhas — UI do portal
status: verified
---

# Spec 110 — Design: Gestão de Links (UI)

## D1 — Conceito

`LinksPage.tsx` nova (rota `/links`) com seções: KPIs, tabela de links, modais
(Preparar Campanha, Cloaker, Domínios, Sale Codes).

```text
portal/src/pages/LinksPage.tsx
portal/src/components/LinksTable.tsx
portal/src/components/PrepareCampaignModal.tsx
portal/src/components/CloakerSettingsModal.tsx
portal/src/components/DomainsManager.tsx
portal/src/components/SaleCodesManager.tsx
```

## D2 — Layout

`[KPIs 3 cards] / [Busca + filtros] / [LinksTable] / tabs [Domínios] [Sale
Codes]`.

- **Tabela**: colunas Link/Status/URL camuflada (`{base}/l/{slug}` + `shk=`)/tag
  CLOAKER/Desempenho (cliques/vendas/receita/conversão)/Ações.
- **Copiar**: `copyToClipboard` + "Copiado!" (Spec 73).
- **Menu**: editar filtro de tráfego (modal), pausar/reativar (`POST activate/deactivate`),
  deletar (`ConfirmDialog` danger).

## D3 — Modais

- **PrepareCampaignModal**: formulário (destino, domínio, UTMs, filtro de tráfego,
  intermediate) → `POST /links/prepare-campaign` → mostra URL pronta.
- **CloakerSettingsModal**: toggle `filtro de tráfegoEnabled` + `intermediatePage` →
  `PATCH /links/{id}`.
- **DomainsManager**: lista + adicionar (`POST /links/domains`) + remover.
- **SaleCodesManager**: lista + adicionar/editar (`POST/PATCH
  /links/sale-codes`).

## D4 — Testes

- `npm run build` (tsc) limpo no portal.
- E2E `portal-links-ui.spec.js`: KPIs, criar link, copiar com feedback,
  preparar campanha, toggle filtro de tráfego, deletar com confirmação, domínios/sale
  codes, empty states.

## D5 — Wireframe aprofundado (registro de design 2026-08-16)

Visual de referência completo da página **Links** (`/links`).

```
┌────────────────────────────────────────────────────────────────────────┐
│  Links                                    [+ Novo link] [Preparar   │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐          campanha]      │
│  │ Links ativos│ │ Cliques    │ │ Com filtro de tráfego│                        │
│  │    12      │ │   8.492    │ │     3      │                        │
│  └────────────┘ └────────────┘ └────────────┘                        │
│  [Buscar nome/URL...]  [Status: Todos ▾]                           │
│  ┌──────────┬────────┬──────────────┬────────┬────────────────────┬──┐│
│  │ Link     │ Status │ URL camuflada│ Entrega│ Desempenho        │Aç ││
│  ├──────────┼────────┼──────────────┼────────┼────────────────────┼──┤│
│  │ Lanç.    │[ATIVO] │ {base}/l/ab12│ fluxo  │ 1.234 cliques      │││
│  │ Sneaker  │        │ shk=LANC2026 │ 32% ap.│ 42 vendas          │││
│  │ [CLOAKER]│        │              │        │ R$ 3.867 · 3,4% cnv│... ││
│  │ Bio Trevo│[ATIVO] │ {base}/l/xyz9│ —      │ 800 cliques         │││
│  │          │        │              │        │ 0 vendas            │││
│  └──────────┴────────┴──────────────┴────────┴────────────────────┴──┘│
│  ── Domínios ──                      ── Códigos de venda ──           │
│  [trevo.app] [add] [rem]            [LANC2026] [add] [ed] [rem]      │
└────────────────────────────────────────────────────────────────────────┘
```

**Modal Preparar Campanha** (UTMs + filtro de tráfego):
```
┌──────────────────────────────────────────────────────────────┐
│  Preparar campanha                                           │
│  Destino  [https://.../c/lancamento]                         │
│  Domínio  [trevo.app ▾]                                      │
│  Origem   [facebook]  Mídia [paid]  Campanha [lanc-ago]      │
│  Termo    [sneaker]   Conteúdo [video1]                      │
│  Cloaker  [x]  Página intermediária [https://.../seguro]     │
│  ────────────────────────────────                            │
│  URL pronta:  https://trevo.app/l/ab12?utm_source=facebook&… │
│  [Copiar]            [Cancelar]                           │
└──────────────────────────────────────────────────────────────┘
```

### D5.1 — Estados

- **Loading inicial**: `Spinner` central; filtros/busca disponíveis só após o
  primeiro load (evita busca sobre lista vazia).
- **Erro**: `ErrorBanner` com `reload()` no topo (KPIs + tabela recarregam
  juntos — `useAsync` único).
- **Empty global**: "Nenhum link ainda." + CTA `[+ Criar primeiro link]` e
  `[Preparar campanha]`.
- **Empty por filtro**: "Nenhum link para a busca/filtros." + botão "Limpar".
- **Tabela sem métricas**: Desempenho mostra `—` em cliques/vendas/receita
  quando o link não teve tráfego (não esconde a linha).

### D5.2 — Interações

- **Busca**: input com debounce (~300ms) em nome/URL camuflada; filtro Status
  (Todos/Ativos/Inativos/Cloaker) → query params; ordenação por data
  (recente primeiro).
- **Copiar**: `copyToClipboard` da URL camuflada completa
  (`{base}/l/{slug}?shk={saleCode}`) → botão vira " Copiado!" por 2s (Spec 73).
- **Menu (...)**: dropdown com "Editar filtro de tráfego" (modal), "Pausar"/"Reativar"
  (`POST activate/deactivate`), "Excluir" (`ConfirmDialog` danger — irreversível).
- **Linha clicável**: ao clicar no nome abre o "Preparar campanha" pré-preenchido
  com o destino daquele link (atalho).

### D5.3 — Modais (detalhe)

- **PrepareCampaignModal**: campos `utm_source/medium/campaign/term/content`,
  destino, domínio (select dos registrados), toggle filtro de tráfego + campo página
  intermediária (URL validada — reuso `WebhookUrlValidator`, SSRF-safe);
  submit → `POST /links/prepare-campaign` → devolve URL pronta → bloco com
  "Copiar" + abrir em nova aba.
- **CloakerSettingsModal**: toggle `filtro de tráfegoEnabled` + `intermediatePage` →
  `PATCH /links/{id}`; descrição do comportamento (robô da rede social → página
  limpa; humano → destino real — Spec 109).
- **DomainsManager**: lista (máx 5) + adicionar (`POST /links/domains`,
  validação de domínio próprio) + remover (ConfirmDialog; links existentes
  preservam a URL antiga).
- **SaleCodesManager**: lista de códigos únicos (label + valor) + adicionar/
  editar (`POST/PATCH /links/sale-codes`); validação de unicidade (409 → banner).

### D5.3.1 — Modal Preparar Campanha: wireframe completo e regras (aprofundamento)

```
┌────────────────────────────────────────────────────────────────────────┐
│   Preparar campanha                                  [x fechar]      │
│  ───────────────────────────────────────────────────────────────────── │
│  Link base │
│  (se aberto de uma linha da tabela, o destino vem pré-preenchido)       │
│  Destino  [https://trevo.app/c/lancamento]   ← URL de destino          │
│                                                                        │
│  ── Domínio ──                                                        │
│  Domínio de saída  [trevo.app ▾]  (select dos registrados — máx 5)     │
│                                                                        │
│  ── Parâmetros UTM ──                                                 │
│  Origem (source)   [facebook]      Mídia (medium)   [paid]             │
│  Campanha (campaign)[lanc-ago]     Termo (term)      [sneaker]         │
│  Conteúdo (content)[video1]        (todos opcionais, ≤ 100 chars)      │
│                                                                        │
│  ── Cloaker ──                                                        │
│  [x] Ativar filtro de tráfego                                                    │
│      Página intermediária  [https://trevo.app/seguro]                  │
│      (URL válida — reuso WebhookUrlValidator · vazia = página padrão)  │
│                                                                        │
│  ── Resumo ──                                                         │
│  URL pronta:                                                           │
│  https://trevo.app/l/ab12?utm_source=facebook&utm_medium=paid&         │
│  utm_campaign=lanc-ago&utm_term=sneaker&utm_content=video1             │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │ [Copiar URL]              [↗ Abrir em nova aba]                │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
```

**Regras e comportamentos:**
- **Pré-preenchimento**: aberto do botão " Preparar campanha" global →
  destino vazio (select do domínio default); aberto da **linha da tabela** →
  destino = link da linha (atalho). O resumo só aparece após o submit
  (`POST /links/prepare-campaign` → devolve a URL montada com o slug novo).
- **UTMs**: todos opcionais; `utm_source` recomendado (aviso visual se vazio —
  não bloqueia); validação ≤ 100 chars por campo; caracteres especiais
  percent-encoded na URL montada (server-side — não confiar no cliente).
- **Cloaker**: toggle desabilita/habilita o campo intermediária; sem
  intermediária → backend usa a página padrão configurada (Spec 109).
- **Copiar/Abrir**: `copyToClipboard` → " Copiado!" 2s; "Abrir em nova aba"
  navega para a URL montada (nova tab).
- **Reuso do modal**: o mesmo componente serve para editar a campanha de um
  link existente (pré-preenchido do `traffic_links` da Spec 109).
- **Segurança**: URLs sempre validadas com `WebhookUrlValidator` (SSRF-safe);
  sem execução de JS (construtor de dados — CSP mantido).

### D5.3.2 — Fluxo de criação de link: modal "Novo link" (aprofundamento)

Antes do link existir, o lojista cria o `traffic_link` (Spec 109). Wireframe:

```
┌────────────────────────────────────────────────────────────────────────┐
│   Novo link                                                [x]      │
│  ───────────────────────────────────────────────────────────────────── │
│  Nome do link   [Lançamento Sneaker]   (1–120, obrigatório)           │
│  Destino        [https://meusite.com/pagina]  (URL — SSRF-safe)       │
│                                                                        │
│  ── Opções ──                                                        │
│  [x] Usar domínio próprio          Domínio:  [trevo.app ▾]            │
│       (select dos registrados — sem domínio → link no domínio padrão)  │
│  [x] Ativar filtro de tráfego (gestão de tráfego)                                │
│  [ ] Vender com código            Código:  [LANC2026 ▾]               │
│       (vincula um sale_code — atribuição de comissão Spec 111)         │
│                                                                        │
│  ── Resultado ──                                                     │
│  URL camuflada gerada:  {base}/l/abc123                                │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │ [Criar link]  (cria → volta para a tabela com o novo link      │  │
│  │                   selecionado + SuccessBanner "Link criado")     │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
```

**Regras e comportamentos:**
- **Nome**: 1–120, obrigatório; **Destino**: URL válida (reuso
  `WebhookUrlValidator`); sem destino válido → criar desabilitado.
- **Domínio próprio**: toggle mostra o select dos domínios registrados (máx
  5 — Spec 110 D5.3); sem nenhum domínio → link cai no domínio padrão da
  plataforma (`{base}/l/...`) com hint "Adicione um domínio na aba Domínios".
- **Cloaker**: toggle simples neste modal (config completa fica no
  `CloakerSettingsModal` — página intermediária opcional).
- **Código de venda**: toggle + select dos sale codes ativos (Spec 110
  D5.3); sem código → atribuição por `shk={slug}` ainda funciona (Spec 109 —
  o slug vira código); vínculo explícito só quando o lojista quer.
- **Submit**: `POST /links` (Spec 109) → devolve `{slug, urlCamuflada}` →
  fecha o modal, recarrega a tabela, seleciona a linha nova e mostra
  `SuccessBanner` "Link criado — {url}" + botão `[Copiar]`.
- **409/validação**: erro de slug único/domínio → `ErrorBanner` no modal com
  a mensagem do campo (não fecha o formulário — estado preservado).
- **Acesso rápido**: botão " Preparar campanha" após criar (mesma sessão)
  para já estruturar as UTMs do link novo.

### D5.4 — Segurança/UX

- Tag **CLOAKER** (laranja) apenas quando `filtro de tráfegoEnabled`; badge de status
  (Ativo/Pausado).
- Rate limit padrão `/api/v1/portal/**` (Spec 58) — nada de novo.
- Sem PII no texto do link (sale_code é identificador, não dado pessoal).
