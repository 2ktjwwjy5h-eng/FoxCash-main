---
name: Afiliado/Comissões — UI do portal
status: verified
---

# Spec 112 — Design: Afiliado/Comissões (UI)

## D1 — Conceito

`AffiliatePage.tsx` nova (rota `/affiliate`): resumo, tabela de coprodutores,
modal de criar/editar, histórico de comissões. Consumo da Spec 111.

```text
portal/src/pages/AffiliatePage.tsx
portal/src/components/AffiliateSummary.tsx
portal/src/components/AffiliatesTable.tsx
portal/src/components/AffiliateModal.tsx
portal/src/components/CommissionHistory.tsx
```

## D2 — Layout

`[AffiliateSummary 4 cards] / [AffiliatesTable + botão criar] / [CommissionHistory
(por coprodutor selecionado)]`.

- **Criar**: modal com campo de e-mail/código do lojista → resolve o lojista
  (backend busca por e-mail/código — aditivo `GET /portal/affiliates/resolve?q=`;
  ou o modal pede o `affiliate_id` — decisão: resolver por e-mail no backend).
- **Desativar**: `ConfirmDialog` danger.

## D3 — Testes

- `npm run build` (tsc) limpo no portal.
- E2E `portal-affiliate-ui.spec.js`: resumo, criar coprodutor, histórico,
  empty states.

## D4 — Wireframe aprofundado (registro de design 2026-08-16)

Visual de referência completo da página **Afiliado** (`/affiliate`).

```
┌────────────────────────────────────────────────────────────────────────┐
│  Afiliado                               Seu código: [af_TREVO2026]   │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐          │
│  │ Total pago │ │ Pendente   │ │ Vendas     │ │ Receita    │          │
│  │  R$ 850,00 │ │ R$ 210,00  │ │ atribuídas │ │ atribuída  │          │
│  │            │ │            │ │    42      │ │ R$ 1.060,00│          │
│  └────────────┘ └────────────┘ └────────────┘ └────────────┘          │
│  ── Coprodutores ──                                  [+ Adicionar]     │
│  ┌──────────────┬─────────┬─────┬─────────┬──────────────────────────┐ │
│  │ Nome         │ Código  │  %  │ Status  │ Ações                    │ │
│  ├──────────────┼─────────┼─────┼─────────┼──────────────────────────┤ │
│  │ Loja Parceira│ af_PART1│ 10% │[ATIVO]  │ [Editar] [Desativar]     │ │
│  │ Canal X      │ af_X    │  5% │[ATIVO]  │ [Editar] [Desativar]     │ │
│  └──────────────┴─────────┴─────┴─────────┴──────────────────────────┘ │
│  ── Histórico de comissões (Loja Parceira) ──                         │
│  ┌──────────────┬────────┬─────┬──────────┬────────────┐              │
│  │ Venda        │ Valor  │  %  │ Status   │ Data       │              │
│  ├──────────────┼────────┼─────┼──────────┼────────────┤              │
│  │ TREVO-...01  │ R$49,90│ 10% │ [PAGA]   │ 16/08 02:1 │              │
│  │ TREVO-...02  │ R$29,90│ 10% │ [PEND]   │ 16/08 01:4 │              │
│  └──────────────┴────────┴─────┴──────────┴────────────┘              │
│  [Retirar comissões → Saque]   (link para /withdraw — wallet)      │
└────────────────────────────────────────────────────────────────────────┘
```

**Modal Adicionar coprodutor**:
```
┌──────────────────────────────────────────────────────────────┐
│  Adicionar coprodutor                                        │
│  E-mail ou código  [parceiro@email.com]  ← resolve o lojista │
│                      (GET /portal/affiliates/resolve?q=)     │
│  Lojista encontrado:   Loja Parceira (af_PART1)            │
│  Percentual (%)      [ 10 ]   (1–50, validação)              │
│  ────────────────────────────────                            │
│  [Salvar]  [Cancelar]                                        │
└──────────────────────────────────────────────────────────────┘
```

### D4.1 — Estados

- **Loading inicial**: `Spinner` central; resumo + coprodutores carregam juntos
  (`useAsync` — dois fetches em paralelo).
- **Erro**: `ErrorBanner` com `reload()`.
- **Empty coprodutores**: "Nenhum coprodutor ainda." + CTA `[+ Adicionar
  coprodutor]` — o resumo continua visível.
- **Empty histórico**: "Sem comissões para este coprodutor." (ao selecionar um
  sem vendas); "Selecione um coprodutor" quando nenhum selecionado.
- **Sem código de afiliado**: caso `affiliate` ausente → banner de setup
  "Ative seu código de afiliado" + botão (cria o código `af_…`).

### D4.2 — Interações

- **Copiar código**: `copyToClipboard` → " Copiado!" por 2s (Spec 73).
- **Seleção de coprodutor**: clicar na linha seleciona → carrega o histórico
  de comissões dele (`GET /affiliates/{id}/commissions`, paginado); seleção
  destacada (borda/cor).
- **Editar**: modal pré-preenchido (e-mail/código readonly + percentual editável);
  **Desativar**: `ConfirmDialog` danger ("comissões futuras param de acumular —
  o histórico permanece").
- **Retirar**: link `→ /withdraw` (o afiliado é um lojista — wallet Spec 22).

### D4.3 — Formatos

- Código: `af_` + slug (ex.: `af_PART1`); percentual sempre exibido com `%`.
- Status da comissão: badge — `PAGA` (tag-success), `PENDENTE` (tag-warning).
- Data: `dd/mm HH:mm` local; paginação 20/histórico.
- Validação percentual 1–50 (400 `invalid_argument` → banner com mensagem do
  campo).

### D4.4 — Modal de coprodutor: fluxo de resolução e casos de borda (aprofundamento)

O ponto crítico do modal é o **resolve** do lojista coprodutor. Wireframe com
os estados:

```
┌────────────────────────────────────────────────────────────────────────┐
│  Adicionar coprodutor                                       [x]       │
│  ───────────────────────────────────────────────────────────────────── │
│  E-mail ou código do lojista                                          │
│  [parceiro@email.com             ]  [Buscar]                        │
│                                                                        │
│  ┌─ Estado: digitando (antes de buscar) ─────────────────────────────┐ │
│  │  Informe o e-mail cadastrado ou o código `af_…` do lojista.       │ │
│  └───────────────────────────────────────────────────────────────────┘ │
│                                                                        │
│  ┌─ Estado: resolvendo ──────────────────────────────────────────────┐ │
│  │  ⏳ Buscando…                                                    │ │
│  └───────────────────────────────────────────────────────────────────┘ │
│                                                                        │
│  ┌─ Estado: encontrado  ───────────────────────────────────────────┐ │
│  │  Lojista:   Loja Parceira  (af_PART1)                          │ │
│  │  (read-only — não editável)                                      │ │
│  └───────────────────────────────────────────────────────────────────┘ │
│                                                                        │
│  ┌─ Estado: não encontrado  ───────────────────────────────────────┐ │
│  │   Nenhum lojista encontrado com esse e-mail/código.            │ │
│  │  Verifique e tente novamente.                                    │ │
│  └───────────────────────────────────────────────────────────────────┘ │
│                                                                        │
│  Percentual (%)   [ 10 ]   (1–50 · erro inline: "Entre 1 e 50")        │
│  ────────────────────────────────────────────────────────────────      │
│  [Salvar]  [Cancelar]                                                   │
│  (Salvar desabilitado até o lojista ser resolvido e o % ser válido)    │
└────────────────────────────────────────────────────────────────────────┘
```

**Regras de resolução (`GET /portal/affiliates/resolve?q=`):**
- **Debounce** de ~400ms no campo + botão "Buscar" explícito (acessibilidade
  para quem não quer esperar); Enter também dispara.
- **Queries aceitas**: e-mail cadastrado do lojista OU código `af_…` exato.
  (Ambiguação: se o lojista coprodutor **for o próprio lojista logado** →
  erro "Você não pode se adicionar como coprodutor" — rejeitado no modal e no
  backend.)
- **Múltiplas correspondências** (raro — e-mail duplicado): lista de seleção
  (radio) antes de habilitar o Salvar.
- **Lojista já é coprodutor**: aviso "Este lojista já é coprodutor
  (10%)" e o modal fica no estado de edição do percentual (sem duplicar a
  linha — decisão: `PATCH` no registro existente; se o backend retornar 409
  `affiliate_already_exists`, a UI apenas avisa e pré-preenche a edição).
- **Erro de rede**: banner inline "Não foi possível buscar o lojista." +
  "Tentar novamente" (sem resetar o campo).
- **E-mail não cadastrado**: o backend NUNCA revela existência de conta não
  vinculada — o resolve devolve "não encontrado" tanto para e-mail inexistente
  quanto para lojista sem código ativo (anti-enumeração; o e-mail de
  terceiros é dado sensível — Spec 39).
- **Acesso**: busca limitada a lojistas `ACTIVE` com perfil de afiliado ativo
  (Spec 111); rate limit padrão `/api/v1/portal/**` (Spec 58).
