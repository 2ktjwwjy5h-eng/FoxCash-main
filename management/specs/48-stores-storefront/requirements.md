---
name: Stores e storefront (backend — loja do vendedor)
status: audited
references:
  - "management/vision.md#V31"
  - "management/specs/20-portal-foundation/requirements.md"
  - "management/specs/39-email-registration/requirements.md"
---

# Spec 48 — Stores e storefront (backend — loja do vendedor)

## O que entrega

Cada lojista (merchant) tem **uma loja** (`stores`, 1:1 — modelagem preparada
para 1:N no futuro sem redesenho: a tabela é por `merchant_id`; hoje o
storefront resolve a loja do merchant, amanhã resolve por id). A loja é a
**fonte de verdade de toda a experiência** que os bots renderizam (Spec 52):
configurável 100% pelo painel, sem deploy de bot.

A loja contém:
- **Identidade:** nome, logo (URL), banner (URL), descrição, redes sociais
  (lista de links), cor (hex), emoji, título e rodapé.
- **Botões do menu inicial** (Spec 50): `[🛒 Comprar] [📷 Ver prévia] [❓ Dúvidas]
  [🌐 Redes sociais] [🚪 Sair]` — o vendedor escolhe **quais e em que ordem**;
  nada é obrigatório; botões personalizados (Instagram, "Falar comigo", promoção,
  canal VIP → link).
- **Mensagens automáticas** (Spec 51): por etapa (boas-vindas, aguardando
  pagamento, pagamento recebido, entregue, promoção) + programadas — editáveis.
- **Referência aos planos** (Spec 49) e à **configuração de entrega**.

**Painel do vendedor** (portal, `ROLE_MERCHANT_PORTAL`): CRUD completo da loja —
visual (logo/banner/cor/emoji/título/rodapé), identidade, redes sociais,
mensagens e botões (Specs 50/51 têm seus endpoints próprios; a loja é a raiz).

**Storefront público** (consumido pelo bot): `GET /api/v1/foxcash/storefront`
(API key do lojista) devolve a **configuração completa** da loja do principal
(identidade, botões ordenados, planos ativos, mensagens) — o bot **não
hardcoda nada**; se não houver loja → 404 `store_not_found` (com orientação de
criar no painel).

## Critérios de aceite

- **1 loja por merchant:** criar loja (201), atualizar (200), buscar (200);
  segundo `POST` de loja para o mesmo merchant → **409 `store_already_exists`**
  (o modelo aceita N no futuro — a regra de 1:1 é de produto, não de schema).
- **Storefront:** `GET /api/v1/foxcash/storefront` retorna identidade + botões
  (ordem) + planos ativos (Spec 49) + mensagens (Spec 51) + config de entrega;
  loja ausente → 404 `store_not_found`; nunca expõe `delivery_actions` com
  segredo em claro ao público (o payload criptografado em repouso é devolvido
  em claro **apenas** via a API do próprio lojista).
- **Painel (portal):** CRUD privado por lojista (404 cross-loja); validações
  (nome 1–120, URLs até 500, cor no formato `#RRGGBB`, redes sociais JSON com
  máx. 10 links); 400 `invalid_argument`, 409/404 conforme o caso.
- **Regressão:** `mvn test` verde (novos testes) e E2E **22/22** + cenário novo
  (criar loja → storefront → 409 duplicada → 404 sem loja).
- **Cross-cutting (obrigatório):** validação rigorosa de entrada (Bean
  Validation + domínio), rate limit nas rotas públicas do storefront (Spec 43),
  dados sensíveis mascarados em logs (Spec 41), auditoria via
  `platform_activity_log` (`STORE_CREATED`/`STORE_UPDATED`), acesso por recurso
  (principal == merchantId).

## Dependências

- `management/specs/20-portal-foundation/requirements.md` (portal/ROLE_MERCHANT_PORTAL)
- `management/specs/43-rate-limit-redis/requirements.md` (rate limit)
- `management/specs/41-master-profiles-activity/requirements.md` (auditoria/logs)
- `management/specs/49-planos-entregas/requirements.md` (planos da loja)
- `management/specs/50-botoes-menu/requirements.md` (botões do menu)
- `management/specs/51-mensagens-automaticas/requirements.md` (mensagens)
