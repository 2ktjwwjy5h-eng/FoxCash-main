---
name: Frontend do portal do lojista
status: audited
references:
  - "management/vision.md#V29"
  - "management/specs/20-portal-foundation/requirements.md"
  - "management/specs/21-merchant-dashboard/requirements.md"
  - "management/specs/23-pix-charge-generator/requirements.md"
---

# Spec 46 — Frontend do portal do lojista

## O que entrega

O **frontend (SPA) do portal do lojista** — hoje o portal existe só como API
(`/api/v1/portal/**`, Specs 20–25, 31, 35) e o "cliente" é o bot via API
(`X-Api-Key`). Esta spec entrega a interface web que consome essas APIs:

- **Telas (sobre as APIs existentes, sem novas rotas de backend):**
  1. **Login/recuperação:** login por e-mail+senha; "esqueci a senha"
     (forgot/reset — Spec 40/45).
  2. **Dashboard:** métricas de topo, série de faturamento, últimas vendas,
     pontos de atenção (Spec 21).
  3. **Carteira:** saldo, resumo do mês, fluxo de caixa (Spec 22).
  4. **Cobrança:** gerador avulso + Gift PIX (Spec 23), QR Code/Copia e Cola.
  5. **Vendas:** listagem/filtros/métricas/detalhe (Spec 25).
  6. **Destinos de saque:** CRUD mascarado (Spec 31).
  7. **Webhooks:** CRUD + teste (Spec 35).
  8. **Credenciais API:** chaves com escopos (Spec 24).
  9. **Saque:** solicitar com destino selecionado (Spec 32/36).
- **Auth no cliente:** token bearer opaco (Spec 20) armazenado com política
  de segurança (ver design — `sessionStorage` recomendado, com
  `X-Requested-With`/interceptor 401 → redirect ao login).
- **B-A8 (decisão de produto revivida):** com o frontend, os 401 uniformes
  de paths sem auth podem ser diferenciados para UX (ex.: 401 na rota de
  login vs. sessão expirada) **sem abrir oráculo** — decisão de contrato a
  ser refinada nesta spec.

## Critérios de aceite

- SPA servida estaticamente (build em `webapp/` ou `resources/static` — ou
  app separada; decisão no design), acessível sem auth apenas nas rotas
  públicas (login/reset).
- Todas as telas acima funcionam contra a API do portal (E2E Playwright
  **browser-based** — não mais `request` API — para as telas principais:
  login → dashboard → cobrança → venda paga → saque).
- Token: nunca em `localStorage` (preferência `sessionStorage` + header
  `Authorization`); logout limpa o estado; interceptor trata 401/403.
- Armazenamento do token sem vazamento em DevTools/Network? — **não há como
  ocultar o token de um atacante com acesso ao browser**; mitigação: TTL de
  sessão 8h (Spec 20), logout, e nunca logar o token no cliente.
- E2E browser: **cobrir login, logout, sessão expirada (401 → redirect),
  cobrança avulsa, destinos mascarados (nunca valor em claro no DOM)**.
- Sem regressão no backend (nenhuma rota muda; CORS configurado apenas para
  os origins permitidos, `foxcash.portal.cors-allowed-origins`).

## Dependências

- Specs 20–25, 31, 32, 35, 40 (APIs consumidas)
- `management/specs/45-reset-token-email/requirements.md` (fluxo de reset no
  frontend — link do e-mail aponta para esta SPA)
- Decisão de stack (design): React/Vite + TypeScript (padrão do e2e já é
  Playwright) ou alternativa leve
