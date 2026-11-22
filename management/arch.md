# Arquitetura — FoxCash

Backend **Java 21 + Spring Boot 3.3** em **arquitetura hexagonal (ports & adapters)**.

```
┌──────────────────────────────────────────────────────────────┐
│  INFRAESTRUTURA (adapters)                                    │
│  • REST controllers (inbound)    • JPA/persistence (outbound) │
│  • Webhook do PSP (inbound)      • MockPixProviderClient      │
├──────────────────────────────────────────────────────────────┤
│  APLICAÇÃO (use cases)                                        │
│  • CreatePixTransactionService • GenerateInvoiceService ...   │
├──────────────────────────────────────────────────────────────┤
│  DOMÍNIO (núcleo, sem dependências externas)                  │
│  • Modelos: PixTransaction, Merchant, Invoice, PricingPlan    │
│  • Ports: port.in (use cases), port.out (repositórios/PSP)    │
│  • Serviços: PricingCalculator, InvoiceCalculator             │
└──────────────────────────────────────────────────────────────┘
```

## Regra de dependência

O domínio não conhece Spring nem banco. A aplicação orquestra o domínio através
dos ports. A infraestrutura implementa os ports de saída e chama os ports de
entrada (use cases).

## Detalhamento

Mapa completo de diretórios, endpoints e regras de negócio: ver `README.md`
(documentação operacional de referência deste repositório).

## Jornadas ponta a ponta (registro de design 2026-08-16 — Gap 4)

As três superfícies de cliente (Bot, Gateway, Portal — ADR-020) compartilham o
mesmo backend. Jornadas de referência que amarram os wireframes das specs:

### J1 — Venda no Bot (Telegram/Discord)

```
Cliente chega no bot (link/QR/descoberta)
  │ /start → storefront (Spec 48/52 — botão plano)
  ▼
Cliente toca [Comprar] → POST /api/v1/foxcash/plans/{id}/purchase (Spec 49)
  │ → gera Pix (Spec 23) + persiste platform/platformUserId
  ▼
Bot envia QR + Copia e Cola no chat (Spec 52 — QR/copy-paste no chat)
  ▼
Cliente paga → webhook /webhooks/pix → SALE_PAID (Specs 10/28)
  │ → ledger/wallet (Specs 27/30) → outbox (Spec 35) → entrega (Spec 49/52)
  ▼
Bot entrega a chave no chat (mensagem "delivered" — Spec 51)
  ▼
[Tela no portal que reflete] → Dashboard/Financeiro (Specs 104/106),
  CRM com plano/fluxo/interação (Spec 119), métricas do bot (Spec 75)
```

### J2 — Venda no Gateway (página web `/c/{slug}`)

```
Cliente abre /c/{slug} (link de anúncio/bio link — Spec 115/116)
  │ GET /api/v1/public/checkout/{slug} (sem auth, rate limit por IP)
  ▼
Página renderiza produto + Sorteio (se ativa) → POST charge (Spec 115 D8)
  ▼
QR + Copia e Cola na página + countdown → cliente paga
  ▼
Webhook SALE_PAID → mesmo pipeline do bot (ledger → outbox → entrega)
  ▼
Página reflete PAID → chave de acesso na própria página (Spec 116 D7.1)
  ▼
[Tela no portal] → stats do checkout (views/vendas/receita — Spec 115),
  link de origem no CRM/afiliado (sale_code → Spec 109/111)
```

### J3 — Do link ao pagamento (Links/Redirecionamento — Spec 109/110)

```
Lojista cria traffic_link no portal (Spec 110 D5.3.2) → /l/{slug}?shk=…
  │ link distribuído (anúncio/bio/redirect — Specs 110/118)
  ▼
Clique → GET /l/{code} → filtro de tráfego server-side (Spec 109: robô de rede social
  → página limpa; humano → destino real) → conta clique + UTM
  ▼
Destino = checkout /c/{slug} (Spec 115) OU página do lojista
  ▼
Venda paga → atribuição por sale_code → comissão de afiliado (Spec 111)
  ▼
[Tela no portal] → métricas do link (cliques/vendas/receita/conversão —
  Spec 110), remarketing sobre PENDENTE (Spec 117/118)
```

### J4 — Remarketing e recuperação (Spec 117/118)

```
Cobrança PENDENTE expira ou é abandonada (qualquer superfície — bot/gateway)
  │ job ShedLock detecta (Spec 117 — padrão Spec 53)
  ▼
Campanha remarketing dispara mensagem de recuperação via bot (Spec 51/79)
  │ send_after_minutes ≥ 5 (Spec 118)
  ▼
Cliente volta e paga → SALE_PAID → fluxo normal (J1/J2)
  ▼
[Tela no portal] → logs da campanha (transação/lead/status/data — Spec 118 D6.3)
```

### Mapa consolidado (superfícies × specs de UI)

| Superfície | Rota | Specs de UI | Consome backend |
|---|---|---|---|
| Bot (conversa) | Telegram/Discord | 48–52 (audited) | `/api/v1/foxcash/**` |
| Gateway (página pública) | `/c/:slug`, `/bio/:slug` | 115/116 | `/api/v1/public/**` |
| Portal (lojista) | `/dashboard`, `/financial`, `/links`, `/affiliate`, `/ranking`, `/rewards`, `/flows`, `/redirects`, `/remarketing`, `/community`, `/crm`, `/webhooks` | 102/104/106/108/110/112/114/118/119 | `/api/v1/portal/**` |
| Shell global | todas (header + menu) | 102 | `/api/v1/portal/shell` |
| Master Console (operador) | `/console/**` | 71/72 | `/api/v1/master/**` |
