---
name: PVS UI portal — pagar Pix Copia e Cola com o saldo
status: audited
references:
  - "management/vision.md#V88"
  - "management/specs/120-pvs-backend/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
  - "management/specs/46-portal-frontend/requirements.md"
---

# Spec 121 — PVS UI portal (pagamento via saldo)

## O que entrega

A experiência de pagamento no portal do lojista, dentro da tela de Saque
(`portal/src/pages/WithdrawPage.tsx`, padrão UX da Spec 73):

1. **Formulário "Pagamento via Saldo"** — campo para **colar o Pix Copia e
   Cola**; valor **auto-preenchido** do próprio brcode (campo EMV 54) quando
   presente, ou digitado quando o código não carrega valor.
2. **Prévia antes de pagar** — chave Pix de destino **mascarada**, valor, taxa
   R$ 2,50 e **total a debitar**; confirmação explícita da ação financeira.
3. **Limites do dia** — card com **usado/restante** do teto diário (de
   `GET /api/v1/portal/pvs/limits`) e o teto por operação.
4. **Estados de erro claros** — código inválido, **Pix dinâmico não suportado**,
   teto diário/por operação excedido, saldo insuficiente (mensagem do backend).
5. **Sucesso** — pagamento em `PROCESSING` + saldo atualizado; o item aparece no
   **histórico de saques existente** (o backend não filtra por tipo — zero
   mudança na listagem).
6. **Consumo dos endpoints da Spec 120** — `POST /api/v1/portal/pvs/pay` e
   `GET /api/v1/portal/pvs/limits` (ROLE_MERCHANT_PORTAL).

## Contexto (por que agora)

A Spec 120 entrega o pipeline; falta a porta de entrada no portal. O
`WithdrawPage.tsx` já existe (Spec 73/70: formulário de saque com destino,
listagem e card de saque automático) — o PVS entra como **seção irmã** na mesma
tela (é o mesmo fluxo de "tirar dinheiro do saldo"), sem nova rota de menu.
Decisões do usuário (2026-08-15): só Pix Copia e Cola **estático** (o UI avisa
que Pix dinâmico/boleto não são suportados); teto diário + gates (o UI mostra os
limites para o lojista não estourar no susto).

## Critérios de aceite

1. **Formulário funcional** — colar o brcode preenche a prévia; com valor no
   código (54) o campo valor fica **readonly** (mostra o valor do código); sem
   valor, o campo é editável e **obrigatório**. `pixCode` validado no cliente
   (não-vazio; aviso se não começa com `000201`).
2. **Prévia + confirmação** — antes de pagar: chave **mascarada** (ex.:
   `529***725`), valor, taxa R$ 2,50, total; botão **Pagar** abre confirmação
   (padrão UX Spec 73 — `ConfirmDialog`/modal de ação financeira) com o total.
3. **Limites do dia** — card `GET /pvs/limits`: teto diário, usado hoje,
   restante, teto por operação; valor negativo de restante → 0.
4. **Erros explícitos** — mapeia os códigos da Spec 120: `pvs_invalid_code`,
   `pvs_dynamic_pix_not_supported` (texto: "Pix dinâmico não é suportado... use
   um Pix de chave/estático"), `pvs_unsupported_key`, `pvs_amount_required`/
   `pvs_amount_mismatch`, `pvs_max_per_payment_exceeded`,
   `pvs_daily_cap_exceeded`, saldo (reuso do erro do saque); após erro de teto,
   **recarrega os limites**.
5. **Sucesso** — mostra `PROCESSING` + novo saldo; limpa o formulário; o
   histórico de saques (listagem existente) atualiza e mostra o PVS.
6. **Estados de UI** — loading (submit), desabilitar durante a chamada, `ErrorBanner`
   e feedback "Copiado!" inexistentes aqui (nada a copiar) — padrão Spec 73.
7. **Acessibilidade/UX** — labels claros, foco no campo ao entrar na aba,
   resumo da taxa visível **antes** de pagar (sem surpresa de R$ 2,50).
8. **Testes E2E** — fluxo feliz (cola → prévia → confirma → PROCESSING → item no
   histórico) e erros (dinâmico, teto excedido).

## Dependências

- **Spec 120** (backend) — endpoints `pay`/`limits`, códigos de erro.
- **Spec 73** (UX) — `Card`, `ErrorBanner`, `Field`, `Spinner`, `formatMoney`,
  `useAsync`, `ConfirmDialog`, loading/estados vazios.
- **Spec 46** (portal foundation) — `apiClient`, `useAuth`.
- **Spec 70** (auto-withdraw) — estrutura atual do `WithdrawPage` (não quebrar o
  card de saque automático).

## Fora de escopo

- **Backend do PVS** — Spec 120.
- **Nova rota/menu** — PVS entra na tela de Saque existente (sem item novo no
  menu lateral).
- **Pix dinâmico/boleto na UI** — só o aviso de "não suportado" (o backend
  rejeita; o UI apenas exibe a mensagem clara).
- **Override de limites por lojista** — o card é read-only (global `foxcash.pvs.*`).
- **Master Console** — o operador não gerencia PVS nesta spec (painel de usuários
  é Specs 97–100).
