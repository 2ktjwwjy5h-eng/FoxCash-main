# Teste do fluxo Bot ↔ Gateway (Telegram + Discord) — 2026-08-20

Validação de ponta a ponta do fluxo de venda no chat (cobrança → pagamento →
notificação → entrega) nas duas plataformas suportadas, incluindo as correções
da Spec 150 e o refinamento do webhook de cobrança avulsa.

## Ambiente

| Serviço | Config | Obs |
|---|---|---|
| Backend FoxCash | `SPRING_PROFILES_ACTIVE=dev`, porta 8080 | mock PSP + mock Telegram (`FOXCASH_TELEGRAM_API_BASE_URL=http://localhost:9001`) |
| Bot Telegram | `@TesteFoxCash_bot`, webhook na 3000 | `FOXCASH_BOT_ID` configurado |
| Bot Discord | `Loja Teste#3758`, webhook na 3001 | `FOXCASH_BOT_ID_DISCORD` + `WEBHOOK_SECRET_DISCORD` configurados |
| Mock Telegram | porta 9001 | `scripts/telegram-mock.mjs` (aceita `setWebhook` sem HTTPS) |
| Loja de teste | Loja Teste Bot (`d59a4f1c-...`) | Bronze R$10 (send_message) + Prata R$25 (send_link + send_file) |

> O mock do Telegram é necessário em dev porque o Telegram real exige URL HTTPS
> para `setWebhook` — sem ele o registro do bot fica `ERROR`. Em produção o
> backend usa `https://api.telegram.org` e o bot registra `ACTIVE` com webhook
> real.

## Resultados

### 1. Portal do lojista
- Login `bot.teste@example.com` OK; wallet R$ 230,00; dashboard: 11 vendas,
  receita R$ 230,00, conversão 68,75%.
- Bot registrado `ACTIVE` com `getMe`/`setWebhook` validados.

### 2. Fluxo Telegram (chat real)
| Etapa | Resultado |
|---|---|
| `/comprar` → plano → QR + Copia e Cola | ✅ |
| `bot_id` atribuído na transação | ✅ `9930dbae-...` |
| `terms_accepted_at` gravado | ✅ (Spec 150) |
| Pagamento (webhook PAID) → `APPROVED` + wallet creditado | ✅ |
| `SALE_PAID` → entrega `SEND_MESSAGE` → `DELIVERED` | ✅ |

### 3. Fluxo Discord (chat real, servidor "Data Science")
| Etapa | Resultado |
|---|---|
| `/comprar` (registrado no guild — instantâneo) → QR + Copia e Cola | ✅ |
| `bot_id` atribuído | ✅ `6f939bc0-...` |
| `terms_accepted_at` gravado | ✅ |
| Entrega `SEND_MESSAGE` (Bronze) | ✅ DELIVERED |
| Entrega `SEND_LINK` + `SEND_FILE` (Prata) | ✅ DELIVERED |
| **Webhook em tempo real** (entrega ~2-3s após PAID, não 15s polling) | ✅ |

### 4. Cobrança avulsa + no-op silencioso
- `POST /api/v1/foxcash/pix/cob` (R$ 50) → QR/Copia-e-Cola; pagamento PAID →
  transação `APPROVED`.
- Bot recebe `SALE_PAID` de cobrança **sem delivery** → loga
  `SALE_PAID sem delivery (cobranca avulsa)` e responde **204** (no-op), sem
  retries (refinamento implementado nesta sessão).

### 5. Webhook v2 (Spec 148/150 — assinatura `t=,v1=`)
Testado contra os receivers (Telegram 3000 e Discord 3001):
- v2 válida → **204**
- v2 com body-tamper → **401**
- v1 legado (hex puro) → **204** (compatível na transição)

### 6. Refund/estorno
- Webhook PSP `REFUNDED` → transação `APPROVED` → `REFUNDED`; wallet debitado
  (R$ 280 → R$ 230). `SALE_REFUNDED` publicado.

### 7. Mensagens programadas (Spec 51)
- `POST /api/v1/portal/store/messages/schedule` → bot buscou via polling →
  tentou enviar aos destinatários → marcou `SENT`. (Falha de `chat not found`
  é esperada: destinatários são IDs fake de teste que não existem no chat real.)

## Correções e melhorias aplicadas nesta sessão

### Correções (commit `0550264`)
1. **QR code inválido** — mock PSP devolvia `pixQrCode` como base64 do TEXTO do
   BR Code (não PNG); Telegram rejeitava (`IMAGE_PROCESS_FAILED`) e o bot
   morria. Novo helper `qrPngFromPurchase` (`bots/core/src/qr.ts`): usa o PNG
   se válido, senão gera o QR real a partir do `pixCopyPaste`. Aplicado em
   Telegram e Discord.
2. **Startup do bot Telegram** — `await bot.start()` (long polling grammY)
   bloqueava scheduler/webhook server. Agora sobem antes do polling.
3. **Crash sem tratamento** — handler de compra com try/catch.
4. **No-op cobrança avulsa** — `SALE_PAID` sem delivery → 204 silencioso
   (antes 500 + retries).
5. **Bug `foxcash` vs `fox`** pré-existente no `/admin` (Telegram, Discord,
   smoke) — corrigido (commit `0d6895e`).

### Spec 150 implementada (commit `87a227e`)
- Porta `GatewayClient` + adapter `FoxCashGatewayClient` (`bots/core/src/gateway-client.ts`)
  — payload específico (botId/termsAccepted/buyerIp/buyerDevice) vive no adapter.
- `SalesMachine.openSale()` envia `termsAccepted`; adapter injeta `botId`.
- Config: `FOXCASH_BOT_ID`, `FOXCASH_GATEWAY_TYPE`.
- Assinatura v1/v2 no receiver com janela anti-replay (5 min).
- Docs em `bots/README.md`; task.md da Spec 150 marcado.

### Coexistência Telegram + Discord (commit `93582f2`)
- `WEBHOOK_PORT_DISCORD`, `WEBHOOK_SECRET_DISCORD`, `FOXCASH_BOT_ID_DISCORD`
  (com fallback p/ genéricos) — permite os dois bots no mesmo lojista.

## Ferramentas de dev criadas (não commitadas — uso local)

| Script | Uso |
|---|---|
| `scripts/telegram-mock.mjs` | Mock da Bot API do Telegram (porta 9001) para `setWebhook` em dev |
| `scripts/register-discord-command.mjs` | Registra `/comprar` na aplicação Discord |
| `scripts/query-bot-funnel.cjs` | Consulta o funil do bot no PostgreSQL |
| `bots/scripts/test-webhook-v2.ts` | Testa assinatura v1/v2 contra o receiver |

## Como reproduzir

```bash
# 1. Banco PostgreSQL (porta 5432, nyxcash/nyxcash) — já instalado local

# 2. Backend com mock do Telegram (dev)
cd bots && node ../scripts/telegram-mock.mjs &   # porta 9001
cd ..
SPRING_PROFILES_ACTIVE=dev FOXCASH_NOTIFICATION_ALLOW_LOOPBACK=true \
FOXCASH_SECURITY_RATE_LIMIT_ENABLED=false \
FOXCASH_TELEGRAM_API_BASE_URL=http://localhost:9001 \
java -Xmx768m -Xms128m -jar target/foxcash-backend-0.1.0-SNAPSHOT.jar

# 3. Bot Telegram (webhook 3000)
cd bots && npm run start:telegram

# 4. Bot Discord (webhook 3001) — token + APP_ID + guild no .env
cd bots && npm run start:discord
```

## Observações

- **Métricas por bot** (`GET /portal/bots/{id}/metrics`) retorna 400
  `conversao impossivel: vendas sem leads` quando há vendas sem lead — esperado
  no mock (lead só é criado com início de conversa real). Compras via chat têm
  `bot_id` na transação, mas o lead precisa do webhook de conversa real.
- **Slash command global do Discord** leva até 1h para propagar; o registro em
  **guild** (`PUT /applications/{id}/guilds/{guildId}/commands`) é instantâneo.
- **Webhook por plataforma:** ao rodar 2 bots no mesmo lojista, cada um precisa
  de webhook próprio (URL diferente) e segredo correspondente no `.env`.
