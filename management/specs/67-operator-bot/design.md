# Spec 67 — Design: Operator Bot (comando e controle via Discord)

## Contexto

- O operador já recebe **alertas de segurança** no Discord (Spec 66/R3 — webhook
  de saída + formatador nativo). Para **consultar** (saúde, negócio, auditoria)
  hoje é preciso abrir Railway/terminal. Esta spec adiciona um **bot Discord**
  que responde comandos no mesmo canal do operador.
- Fontes de dados disponíveis e reutilizáveis: PostgreSQL do FoxCash (schema
  completo), Railway (status/deploy/logs), Cloudflare (WAF — token escopado),
  backend `/actuator/prometheus` (ROLE_ADMIN).

## Decisões

### 1. Localização e stack

Novo diretório **`operator-bot/`** na raiz (standalone, npm próprio — não usa o
workspace `bots/`, que é dos bots de loja com dependências diferentes):
- Node 20+, TypeScript, **discord.js** (client de mensagens — não webhook; o
  webhook só envia, não escuta) + **pg** (node-postgres) + **node-cron**
  (agendados). `discord.js` já é conhecido do projeto (bots/).
- O bot é **read-only** no banco (SELECT) exceto `!bloquear` (Cloudflare WAF).

### 2. Fontes de dados (env vars do bot)

| Env | Uso |
|---|---|
| `OPERATOR_DISCORD_TOKEN` | Token do bot Discord (portal de desenvolvedor — pré-requisito externo) |
| `OPERATOR_CHANNELS` | Allowlist de canais (IDs separados por vírgula) onde responde; `OPERATOR_CHANNEL_ID` = canal único (fallback) |
| `DATABASE_URL` | PostgreSQL do FoxCash (leitura) — `postgresql://user:pass@host:5432/railway` |
| `RAILWAY_TOKEN` | Token de API do Railway (dashboard → Account Settings → Tokens) p/ status/logs no container |
| `RAILWAY_OPS_SERVICE_ID` | Serviço operado (backend FoxCash) — alvo de status/logs; default: próprio serviço do bot |
| `WEBHOOK_CMD/OPS/FIN_PULSE/SUMMARIES/HEALTH/SECURITY` | 6 webhooks por categoria (integrações do servidor) p/ proativos |
| `NYX_ADMIN_API_KEY` | Chave admin p/ `/actuator/prometheus` (`!latencia`) |
| `CF_API_TOKEN` | Token escopado do Cloudflare (`!bloquear`) |
| `CF_ZONE_ID` | Zona `foxcash.example.com` |
| `NYX_BASE_URL` | URL pública da API (default `https://api.foxcash.example.com`) |
| `TZ` | Fuso p/ node-cron (Railway default UTC) |

### 3. Módulos

```
operator-bot/
  package.json  tsconfig.json  railway.json  .railwayignore  .env.example
  src/
    index.ts          — client Discord + dispatcher de comandos + ACL (allowlist) + cron
    db.ts             — pool pg (leitura) + queries nomeadas
    railway.ts        — API GraphQL do Railway (RAILWAY_TOKEN) ou CLI local (dev)
    cloudflare.ts     — WAF block (PUT no entrypoint do ruleset, expression sem aspas)
    prometheus.ts     — p95/p99 do /actuator/prometheus (parsing text format)
    commands.ts       — todos os handlers (saúde, negócio, segurança, ações) + help
    webhooks.ts       — roteamento por categoria (6 webhooks, WebhookClient do discord.js)
    scheduled.ts      — daily, weekly, anomalia (node-cron)
  test/               — vitest: prometheus, webhooks config, validIp
```

### 4. Queries principais (SQL de leitura — schema atual)

```sql
-- conexoes: SELECT count(*) FROM pg_stat_activity;  +  SHOW max_connections;
-- outbox:   SELECT status, count(*) FROM notification_outbox
--             WHERE created_at > now() - interval '1 hour' GROUP BY status;
-- jobs:     SELECT job, status, started_at FROM job_runs
--             ORDER BY started_at DESC LIMIT 5;
-- vendas:   SELECT count(*), COALESCE(avg(amount),0) FROM pix_transactions
--             WHERE status='APPROVED' AND paid_at > now() - interval '1 hour';
-- chargebacks: SELECT count(*) FROM chargebacks WHERE created_at > now() - interval '7 days';
-- lojistas: SELECT count(*) FILTER (WHERE status='ACTIVE'),
--                  count(*) FILTER (WHERE created_at > now() - interval '7 days')
--           FROM merchants;
-- receita:  SELECT COALESCE(sum(transaction_fees_total + monthly_fee),0) FROM invoices
--             WHERE billing_period = to_char(now(),'YYYY-MM');
-- top5:     SELECT m.name, sum(t.amount) FROM pix_transactions t
--             JOIN merchants m ON m.id = t.merchant_id
--             WHERE t.status='APPROVED' AND t.paid_at >= date_trunc('month', now())
--             GROUP BY m.id, m.name ORDER BY 2 DESC LIMIT 5;
-- alertas:  SELECT type, merchant_id, created_at FROM notification_outbox
--             WHERE type LIKE '%BURST%' OR type='BODY_INSPECTION_BLOCKED'
--             ORDER BY created_at DESC LIMIT 5;
-- reconciliacao: SELECT count(*) FROM reconciliation_issues WHERE status='OPEN';
--   + última execução: SELECT status, finished_at FROM job_runs
--     WHERE job LIKE '%reconcil%' ORDER BY started_at DESC LIMIT 1;
```

**`!latencia`** (Prometheus, text format): buscar
`/actuator/prometheus` com `X-Api-Key: $NYX_ADMIN_API_KEY`, parsear
`http_server_requests_seconds{...}` da última hora e calcular p95/p99 por
endpoint (ou agregado). Sem admin key → mensagem "métricas indisponíveis".

**`!bloqueios`/`!ip <ip>`/`!log <requestId>`**: via **API GraphQL** do Railway
(`deployments` + `deploymentLogs`, `RAILWAY_TOKEN`) consultando o serviço de
`RAILWAY_OPS_SERVICE_ID` e varrendo os **últimos 3 deployments** (o backend
redeploya a cada push — bloqueio/investigação pode estar em deployment
substituído); fallback: CLI local em dev. Grep por **substring** (`includes`) —
mais seguro que regex com input de usuário.

**`!bloquear <ip>`** (Cloudflare, validado contra a API real 2026-08-10): **PUT**
no entrypoint do ruleset `http_request_firewall_custom` com a lista de regras
atualizada + nova regra `(ip.src eq <ip>)` **sem aspas** — `POST .../rules` → 405
(10405) com API Token; aspas no IP → erro de parse (20127). Description
`operator-bot block <data>`, action `block`, `enabled: true`. Reversível:
reescrever o entrypoint sem a regra. Permissão do token: **Zone → WAF → Edit**.
Guarda contra IP inválido (regex).

**`!ratelimit`**: limites da config conhecida (app 20/120/300/120; edge 50/10s
consolidada) + estado do edge (consulta o ruleset via CF API — read).

### 5. Alertas agendados (node-cron, TZ configurável)

- `SCHED_DAILY_CRON` (default `0 9 * * *`) → vendas de ontem + novos lojistas +
  alertas do dia + saúde.
- `SCHED_WEEKLY_CRON` (default `0 9 * * 1`) → vendas da semana + chargebacks +
  taxa de aprovação + top lojistas.
- **Anomalia** (a cada 15min): vendas da última hora vs média das últimas 24h
  (excluindo a hora atual); se < `ANOMALY_THRESHOLD` (default 0.5) → alerta
  (throttle 1 por hora).

**Roteamento por webhook (6 categorias)** — decisão do usuário (integrações do
servidor Discord): resumos diário/semanal → `WEBHOOK_SUMMARIES`; anomalia →
`WEBHOOK_FIN_PULSE`. Comandos interativos continuam respondendo no canal
(reply). Webhook ausente → fallback para o primeiro canal da allowlist; sem
canal → log no console.

### 6. Segurança do bot

- Comandos respondidos **apenas nos canais da allowlist** (`OPERATOR_CHANNELS`,
  IDs separados por vírgula — ex.: saúde-sistema, ops, comando-controle…) ou por
  DM direto ao bot — nunca em canais públicos (ACL simples).
- Queries de leitura sem parâmetros concatenados (pool + valores literais fixos
  ou parametrizados — nenhuma string de usuário vai para SQL).
- `!log`/`!bloqueios`/`!ip` buscam por **substring** (`includes`) — sem regex
  com input de usuário.
- Segredos só por env; `!notificar` fica para fase 2 (endpoint no backend).

### 7. Lookup de IP (`!ip`) — design (implementação concluída em 2026-08-10 — task.md Fase E)

**Endpoint**: `GET /api/v1/admin/lookup/ip/{ip}` no `AdminController` (ROLE_ADMIN,
admin API key). 400 p/ IP inválido; 200 com `{ip, matches[]}` (matches vazio = IP
não encontrado — o bot formata a mensagem).

**Camadas (hexagonal, mesmo padrão do dashboard)**:
- `domain/model/admin/IpLookupResult.java` — merchantId, name, email, status,
  totalSales, averageTicket, lastSaleAt, bot{storeId, storeName, activePlans,
  activeWebhooks, link}.
- `AdminDashboardUseCase`/`AdminDashboardPort` → `lookupByIp(String)`.
- `AdminDashboardService.lookupByIp`: chama o port + registra
  `PlatformActivity.record(SYSTEM, null, IP_LOOKUP, "IP", null, null, details,
  ip, now)` via `PlatformActivityRepository.save` — `details` = JSON `{count,
  merchants:[{id,name,status}]}` (sem chaves/IPs/e-mails); `@Transactional`
  próprio no método (o save não fica em transação readOnly).
  `PlatformAction.IP_LOOKUP` adicionado ao enum.

```sql
-- 1. merchants por IP (união cobrança + trilha)
SELECT DISTINCT merchant_id FROM (
  SELECT pt.merchant_id FROM pix_transactions pt WHERE pt.buyer_ip = :ip
  UNION
  SELECT pal.merchant_id FROM platform_activity_log pal
    WHERE pal.ip = :ip AND pal.merchant_id IS NOT NULL
) t WHERE merchant_id IS NOT NULL;

-- 2. resumo de vendas (uma query p/ todos os merchants)
SELECT pt.merchant_id,
       COUNT(*) AS total,
       COALESCE(AVG(pt.amount), 0) AS ticket,
       MAX(pt.paid_at) AS ultima
FROM pix_transactions pt
WHERE pt.merchant_id IN (:ids) AND pt.status = 'APPROVED'
GROUP BY pt.merchant_id;

-- 3. store/bot (plans ativos + webhooks ativos + social_links p/ o link)
SELECT s.merchant_id, s.id AS store_id, s.name AS store_name, s.social_links,
       (SELECT COUNT(*) FROM plans p WHERE p.store_id = s.id AND p.active = TRUE) AS active_plans,
       (SELECT COUNT(*) FROM merchant_webhooks w
          WHERE w.merchant_id = s.merchant_id AND w.status = 'ACTIVE') AS active_webhooks
FROM stores s WHERE s.merchant_id IN (:ids);
```

- Detalhes do merchant: `merchantRepository.findAllById(ids)` (método existente).
- **Índices**: migração **V46** (`ix_pix_transactions_buyer_ip`,
  `ix_platform_activity_ip`) — **JÁ CRIADA**, será aplicada no próximo deploy
  (Flyway roda automático).

**Estratégia do link do bot de vendas** (deliverable 3): não há coluna dedicada
de convite. Regra:
1. **Bot ativo** = existe `stores` para o merchant E ≥1 `plans.active=true`
   (loja com planos à venda) e/ou ≥1 `merchant_webhooks.status='ACTIVE'` (entrega
   de eventos configurada).
2. **Link** = parse de `stores.social_links` (JSON `[{label,url}]`, ObjectMapper
   no adapter): primeira entrada cuja URL é invite do Discord
   (`discord.gg|discord.com/invite`) ou Telegram (`t.me|telegram.me`).
3. Sem link em `social_links` → resposta indica bot ativo mas **sem link
   configurado** (honesto). Futuro: coluna dedicada `stores.bot_invite_link`
   (migração nova) se o operador quiser convite explícito.

**Bot (`commands.ts`)**: substituir o handler `ip` — validar formato (`validIp` do
`cloudflare.ts`), chamar o endpoint com `X-Api-Key: $NYX_ADMIN_API_KEY`, formatar
(1 ou N lojistas), **mascarar o IP** na resposta (IPv4: mantém último octeto;
IPv6: primeiros grupos + `…`). Sem admin key → mensagem clara.

**Bot-link da resposta**: `link` só é exposto se veio de `social_links`; nunca
expor chaves/segredos do webhook (`merchant_webhooks.secret` jamais retornado).

## Arquivos

| Arquivo | Ação |
|---|---|
| `operator-bot/` (novo) | bot completo (estrutura acima) + `railway.json` (NIXPACKS, 1 réplica) + `.railwayignore` |
| `operator-bot/.env.example` | env vars documentadas |
| `deploy/operator-bot.md` (novo) | runbook de deploy/execução (Railway service ou local) — cobre setup e comandos (sem README próprio) |
| `management/roadmap.md` / `vision.md` | Spec 67 `audited` + V47 |

## Fora de escopo

`!notificar` (fase 2 — endpoint no backend), dashboard gráfico, escrita em dados
financeiros, integração Grafana nativa.
