---
name: Automações avançadas — Meus Fluxos, Redirecionadores e Remarketing (UI)
status: verified
---

# Spec 118 — Design: Automações avançadas (UI)

## D1 — Conceito

Três páginas no portal sobre a Spec 117. `FlowsPage` com editor de nós
sequenciais; `RedirectsPage` reusa a `LinksPage` (Spec 110) filtrada;
`RemarketingPage` com CRUD + logs.

```text
portal/src/pages/FlowsPage.tsx        → lista + editor de fluxo (nós)
portal/src/pages/RedirectsPage.tsx    → reuso da LinksPage (filtro filtro de tráfego)
portal/src/pages/RemarketingPage.tsx  → CRUD campanhas + logs
portal/src/components/FlowNodeEditor.tsx → nós sequenciais (tipo/conteúdo/reorder)
```

## D2 — FlowsPage

- Lista de fluxos; "Novo fluxo" → editor: nome, trigger, nós (select tipo +
  conteúdo; botões ↑/↓; adicionar/remover nó); salvar → `POST/PATCH
  /portal/flows` + nós. Validação por tipo (ex.: SEND_LINK exige URL).

## D3 — RedirectsPage

- Reuso dos componentes da Spec 110 (`LinksTable`) com query
  `filtro de tráfego=true`; "Novo redirecionador" → modal de criar (Spec 109).

## D4 — RemarketingPage

- CRUD campanhas (`send_after_minutes ≥ 5`); tabela de logs por campanha
  (`GET /portal/remarketing/{id}/logs`).

## D5 — Testes

- `npm run build` (tsc) limpo no portal.
- E2E `portal-automacoes-ui.spec.js`: fluxo com 2 nós salvo; redirect criado;
  campanha + log; empty states.

## D6 — Wireframes aprofundados (registro de design 2026-08-16)

### D6.1 — Meus Fluxos (`/flows`)

```
┌────────────────────────────────────────────────────────────────────────┐
│  Meus Fluxos                                  [+ Novo fluxo]          │
│  ┌──────────────┬─────────┬────────┬───────────────────────────────┐  │
│  │ Nome         │ Status  │ Nós    │ Ações                         │  │
│  ├──────────────┼─────────┼────────┼───────────────────────────────┤  │
│  │ Bem-vindo    │[ATIVO]  │ 3      │ [Editar] [Pausar] [Excluir]   │  │
│  │ Upsell       │[PAUSADO]│ 2      │ [Editar] [Ativar] [Excluir]   │  │
│  └──────────────┴─────────┴────────┴───────────────────────────────┘  │
│                                                                        │
│  EDITOR (Editar: Bem-vindo)                                            │
│  Nome [Bem-vindo]  Trigger [após compra ▾]                             │
│  ┌────────────────────────────────────────────────────────────────┐   │
│  │ 1.  MENSAGEM    [Bem-vindo! Aqui está seu acesso…]  [↑][↓][del]│   │
│  │ 2.  SEND_LINK   [https://trevo.app/l/ab12]         [↑][↓][del]│   │
│  │ 3.  GRANT_ROLE  [vip]                              [↑][↓][del]│   │
│  │    [+ Adicionar nó]                                               │   │
│  └────────────────────────────────────────────────────────────────┘   │
│  [Salvar fluxo]                                                      │
└────────────────────────────────────────────────────────────────────────┘
```

**Estados e interações:**
- **Lista**: nome, status (badge ATIVO/PAUSADO), nº de nós, ações; empty
  state "Nenhum fluxo ainda." + CTA `[+ Novo fluxo]`.
- **Novo fluxo** → editor vazio (nome, trigger, 0 nós). **Editar** pré-carrega.
- **Nó**: select de tipo (`MESSAGE`/`SEND_LINK`/`ADD_TO_GROUP`/`GRANT_ROLE`)
  + campo conteúdo com **validação por tipo** (SEND_LINK exige URL — reuso
  `WebhookUrlValidator`; ADD_TO_GROUP exige id do grupo; GRANT_ROLE exige nome
  do papel). Botões ↑/↓ reordenam (persiste via `PUT /portal/flows/{id}/order`
  ou no save — decisão: salvar tudo de uma vez).
- **Remover nó**: `ConfirmDialog` leve (não destrutivo — pode adicionar de
  novo); **Pausar/Ativar** na lista: `POST /portal/flows/{id}/pause|resume`.
- **Salvar**: `POST/PATCH /portal/flows` + nós → `SuccessBanner` "Fluxo
  salvo."; erro → `ErrorBanner` (ex.: nó SEND_LINK sem URL → mensagem no campo).
- **KISS**: sem drag-and-drop visual (nós sequenciais — evolução documentada).

### D6.1.1 — Editor de nós: interações e regras por tipo (aprofundamento)

O editor é o coração da página — cada nó é uma **linha de formulário** que muda
conforme o tipo selecionado. Wireframe do nó expandido:

```
┌────────────────────────────────────────────────────────────────────────┐
│  Nó 2 de 3                                             [↑][↓][Excluir]│
│  Tipo:  [Enviar link ▾]                                              │
│         ┌─────────────────────────────────────────────────────────┐    │
│  Config:│  Destino        [https://trevo.app/c/lancamento]        │    │
│         │  (obrigatório · URL válida — reuso WebhookUrlValidator) │    │
│         │  Texto do botão [Comprar agora]  (opcional ≤ 60)     │    │
│         └─────────────────────────────────────────────────────────┘    │
│  Rótulo de fluxo:  [Enviar link de compra]  (exibido na lista, ≤ 80)   │
└────────────────────────────────────────────────────────────────────────┘
```

**Regras por tipo (o `select` de tipo troca a seção "Config"):**

| Tipo | Obrigatório | Opcional | Validação |
|---|---|---|---|
| `MESSAGE` | texto da mensagem | — | 1–2000 chars |
| `SEND_LINK` | URL destino | texto do botão | URL válida (SSRF-safe), botão ≤ 60 |
| `ADD_TO_GROUP` | id/nome do grupo | — | resolve o grupo na plataforma (404 → erro no campo) |
| `GRANT_ROLE` | nome do papel | — | 1–64 chars |

**Interações de edição:**
- **Trocar tipo** → preserva campos comuns (rótulo), zera a Config (sem perder
  confirmação? — decisão: **mantém os campos não aplicáveis em estado `null`**,
  não descarta o texto digitado até salvar; o tipo novo mostra a Config dele).
- **↑/↓**: desabilita ↑ no 1º nó e ↓ no último (`disabled`); reordena o array
  local — persistência só no **Salvar** (evita N chamadas).
- ** Excluir**: remove a linha imediatamente (sem `ConfirmDialog` — ação
  reversível via "Desfazer" no banner, padronizado na Spec 73? — decisão:
  sem desfazer; remove e mostra `SuccessBanner` "Nó removido" — KISS).
- **Erro por campo**: mensagem inline abaixo do campo (ex.: "Informe uma URL
  válida") — o `ErrorBanner` global só para falhas de rede/409.
- **Salvar com nós inválidos**: botão desabilitado + lista dos nós com erro
  (contador no rodapé: "2 nós com erro — corrija para salvar").
- **Rótulo de fluxo**: o lojista nomeia cada nó (ex.: "mensagem de boas-
  vindas") para a lista ficar legível; default = nome do tipo ("MENSAGEM").

**Contrato de payload (Spec 117 — referência para a UI):**
```json
POST /api/v1/portal/flows
{
  "name": "Bem-vindo",
  "trigger": "PURCHASE",
  "nodes": [
    { "type": "MESSAGE",    "content": "Bem-vindo! Aqui está seu acesso…" },
    { "type": "SEND_LINK",  "content": "https://trevo.app/c/lancamento",
      "buttonText": "Comprar agora" },
    { "type": "GRANT_ROLE", "content": "vip" }
  ]
}
```
(Nomes de campo definitivos na implementação — contrato da Spec 117.)

### D6.2 — Redirecionadores (`/redirects`)

```
┌────────────────────────────────────────────────────────────────────────┐
│  Redirecionadores                                  [+ Novo redirect]   │
│  KPIs:  Cliques totais  4.382 ·  Links com filtro de tráfego  3                 │
│  ┌──────────┬─────────┬──────────┬─────────┬────────────────────────┐ │
│  │ Link     │ URL     │ Cloaker  │ Cliques │ Ações                  │ │
│  ├──────────┼─────────┼──────────┼─────────┼────────────────────────┤ │
+  │ /l/ab12  │ trevo...│ [ON]     │ 1.234   │ [Copiar] [Editar][...]   │ │
│  │ /l/xyz9  │ trevo...│ [ON]     │   800   │ [Copiar] [Editar][...]   │ │
│  └──────────┴─────────┴──────────┴─────────┴────────────────────────┘ │
│                                                                        │
│  (Reuso da LinksPage da Spec 110 — tabela filtrada por filtro de tráfego=true)   │
└────────────────────────────────────────────────────────────────────────┘
```

- **Reuso máximo**: `LinksTable`/modais da Spec 110 com query
  `filtro de tráfego=true`; **Novo redirecionador** = modal de criar `traffic_link` da
  Spec 109 (com filtro de tráfego ativo por default).
- KPIs somados na UI (cliques totais, com filtro de tráfego); empty state "Nenhum
  redirecionador ainda."

### D6.3 — Remarketing (`/remarketing`)

```
┌────────────────────────────────────────────────────────────────────────┐
│  Remarketing                                      [+ Nova campanha]    │
│  ┌──────────────┬─────────┬─────────┬─────────┬──────────────────────┐ │
│  │ Nome         │ Status  │ Atraso  │ Envios  │ Ações                │ │
│  ├──────────────┼─────────┼─────────┼─────────┼──────────────────────┤ │
│  │ Recupera PIX │[ATIVO]  │ 30 min  │  128    │ [Editar][Pausar][...]  │ │
│  │ pendente     │         │         │         │                      │ │
│  │ 2º lembrete  │[ATIVO]  │  2 dias │   45    │ [Editar][Pausar][...]  │ │
│  └──────────────┴─────────┴─────────┴─────────┴──────────────────────┘ │
│                                                                        │
│  Logs — Recupera PIX pendente                                          │
│  ┌──────────────┬──────────────┬─────────┬────────────┐               │
│  │ Transação    │ Lead         │ Status  │ Data       │               │
│  ├──────────────┼──────────────┼─────────┼────────────┤               │
│  │ TREVO-...01  │ @fulano      │ [ENVIADO]│ 16/08 02:10│               │
│  │ TREVO-...02  │ +55 11 9…    │ [ENVIADO]│ 16/08 01:55│               │
│  └──────────────┴──────────────┴─────────┴────────────┘               │
└────────────────────────────────────────────────────────────────────────┘
```

**Estados e interações:**
- **CRUD campanhas**: nome, mensagem (reuso `renderMessage` do storefront? —
  decisão: campo texto com placeholders `{{nome}}`), `send_after_minutes ≥ 5`
  (validação 400 `invalid_argument`); `POST/PATCH /portal/remarketing`.
- **Logs por campanha**: `GET /portal/remarketing/{id}/logs` — tabela
  transação/lead/status/data; seleção de campanha carrega os logs.
- **Status do envio**: badge ENVIADO/FALHOU/AGENDADO; empty "Sem disparos
  ainda."
- **Pausar/Ativar**: `POST /portal/remarketing/{id}/pause|resume`; excluir com
  `ConfirmDialog` (logs preservados).
