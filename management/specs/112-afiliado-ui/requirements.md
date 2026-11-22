---
name: Afiliado/Comissões — UI do portal
status: verified
references:
  - "management/vision.md#V82"
  - "management/specs/111-afiliado-backend/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
---

# Spec 112 — Afiliado/Comissões: UI do portal

## O que entrega

Página **Afiliado** no portal (rota `/affiliate`) com a descrição de produto do
usuário (2026-08-15). Puramente apresentação sobre os endpoints da Spec 111.
Padrão UX da Spec 73.

1. **Visão geral**: código do afiliado, coprodutores, resumo de comissões
   (pagas/pendentes + vendas atribuídas).
2. **CRUD de coprodutores**: adicionar lojista coprodutor (por e-mail/código)
   com percentual; ativar/desativar/excluir.
3. **Histórico de comissões**: tabela (venda, valor, %, status) por coprodutor.
4. **Retirada**: link para a tela de Saque (o afiliado é um lojista — retira
   pelo wallet).

## Contexto (por que agora)

Descrição de produto do usuário (2026-08-15) — Afiliado do portal de
referência. Backend: Spec 111 (criada junto).

## Critérios de aceite

1. **Resumo**: cards (total pago, pendente, vendas atribuídas, receita
   atribuída) do `GET /affiliate/summary`.
2. **Coprodutores**: tabela (nome, código `af_…`, %, status, ações) + modal de
   criar (e-mail/código do lojista + percentual 1–50) e editar/desativar.
3. **Histórico**: `GET /affiliates/{id}/commissions` paginado — colunas venda,
   valor, %, status (badge), data.
4. **UX**: Spinner, empty states, banners (Spec 73), `ConfirmDialog` para
   desativar.
5. **Rotas/SPA**: `/affiliate` no `PortalSpaController` + `spaPublicPaths()`
   (lição Specs 80/84).
6. **Testes**: `npm run build` (tsc) limpo no portal + E2E
   (`portal-affiliate-ui.spec.js`) — resumo, criar coprodutor, histórico,
   empty; suíte completa sem regressão.

## Dependências

- **Spec 111** (backend).
- **Spec 73** (UX).
- **Spec 102** (menu — Afiliado no grupo).
- **Spec 139** (escopo + lente, `in-progress` 2026-08-16) — tabela de
  comissões escopada pelo perfil ativo (Spec 124); respeitar a lente.

## Fora de escopo

- **Backend** — Spec 111.
- **Multi-nível** — evolução.
