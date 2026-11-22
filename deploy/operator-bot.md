# Operator Bot — runbook de deploy e operação (Spec 67)

Bot de comando e controle do operador via Discord (`operator-bot/`). Consulta o
banco (leitura), o Railway (status/deploy/logs) e o Cloudflare (WAF) — sem abrir
terminal/Railway para operação do dia a dia.

## Pré-requisitos externos

1. **Bot Discord** (portal.developers.discord.com → New Application → Bot):
   - Copie o **token** (`OPERATOR_DISCORD_TOKEN`)
   - Convide o bot para o servidor (OAuth2 URL Generator → scope `bot`,
     permissão `Send Messages` + `Read Message History`) — **sem o convite, o
     bot não vê mensagens de canal** (só DM)
   - `OPERATOR_CHANNELS` = IDs dos canais onde responde (separados por vírgula;
     ex.: saúde-sistema, ops, comando-controle…). Alternativa: `OPERATOR_CHANNEL_ID`
     (canal único). Sem nenhum, responde apenas por DM direto ao bot
2. **Webhooks por categoria** (servidor → Settings → Integrations → Webhooks —
   copie a URL de cada um dos 6): `WEBHOOK_CMD`, `WEBHOOK_OPS`,
   `WEBHOOK_FIN_PULSE`, `WEBHOOK_SUMMARIES`, `WEBHOOK_HEALTH`,
   `WEBHOOK_SECURITY`. Usados pelos proativos (ver "Alertas agendados");
   comandos continuam respondendo no canal (reply).
3. **DATABASE_URL** do Postgres do FoxCash (leitura) — em dev via túnel
   (`railway connect`); em produção, referência `${{Postgres.DATABASE_URL}}`
   (host interno — ver seção Produção).
4. **RAILWAY_TOKEN** (ou CLI autenticada local) p/ status/deploy/logs — hoje
   apenas via CLI local; container do Railway responde erro amigável (fase 2: API).
5. **FOXCASH_ADMIN_API_KEY** (mesma do backend) p/ `!latencia` (métricas Prometheus)
   e `!ip` (lookup de lojista por IP — Spec 67/Fase E).
6. **CF_API_TOKEN** (token escopado da zona — Spec 66/R1) p/ `!bloquear <ip>`.

**Instalação do bot (2026-08-10)** — Application ID `<DISCORD_APP_ID>`
(chave pública `<DISCORD_PUBLIC_KEY>`).
Link de convite com escopo e permissões corretos (68608 = View Channels + Send
Messages + Read Message History):

```
https://discord.com/oauth2/authorize?client_id=<DISCORD_APP_ID>&permissions=68608&scope=bot
```

> ⚠️ **A chave pública NÃO é o token de login.** O `OPERATOR_DISCORD_TOKEN`
> fica em portal.developers.discord.com → seu app → **Bot → Reset Token → Copy**
> (só aparece uma vez; se perder, reset de novo).
>
> ⚠️ **Intent Message Content (privilegiado) é OBRIGATÓRIO**: o bot lê comandos
> por mensagem. Em **Bot → Privileged Gateway Intents → Message Content** ative e
> salve. Sem isso o Discord recusa a conexão com `Used disallowed intents`.

## Local (dev)

```bash
cd operator-bot
npm install
cp .env.example .env   # preencha (ver .env.example)
npm run dev            # tsx (hot)  |  npm start  (build+node dist)
npm run typecheck      # tsc --noEmit
npm test               # vitest
```

> **Windows/git-bash**: o spawn do CLI `railway` via Node pode falhar (PATH do
> git-bash em formato POSIX + shim npm). Os comandos de Railway (`!deploy`,
> `!bloqueios`, `!ip`, `!log`) respondem com **erro amigável** onde não há CLI
> (Windows ou container do Railway); o parsing foi validado contra o output real
> do CLI. Fase 2: integrar via API do Railway com `RAILWAY_TOKEN`.

## Produção (Railway service)

O serviço usa `operator-bot/railway.json` — NIXPACKS (`npm ci` + `npm run build` +
`npm start`), **1 réplica** (2 réplicas duplicariam os resumos e o Discord derruba
a 2ª conexão de gateway), restart on failure. O bot não expõe porta HTTP, então
não há healthcheck de URL.

**Deploy — staging fora do git repo** (validado 2026-08-10): o `railway up`
executado dentro do repo sobe a **raiz do repositório**, não a pasta (Railpack
analisa `./` com `operator-bot/` dentro → falha). Deploy por staging sem git:

```bash
rm -rf /tmp/nyxbot-stage && mkdir -p /tmp/nyxbot-stage
cd operator-bot
cp -r package.json package-lock.json tsconfig.json railway.json .railwayignore src test /tmp/nyxbot-stage/
cd /tmp/nyxbot-stage
railway link -p <PROJECT_ID> -e production -s operator-bot   # 1ª vez
railway up --detach -s operator-bot
```

> **Alternativa durável (recomendada p/ monorepo)**: dashboard → service
> `operator-bot` → Settings → Source → conectar repo `<seu-usuario>/foxcash`
> (branch `main`) + **Root Directory: `operator-bot`** → auto-deploy no push.
> O CLI e a API GraphQL **não expõem** `rootDirectory` (só `image`/`repo`).
>
> ⚠️ **NÃO usar `railway service source connect`** neste serviço sem setar o
> Root Directory no dashboard: ele conecta o repo SEM rootDirectory e dispara
> deploys da **raiz** (backend Java no lugar do bot — 2026-08-10). Se isso
> acontecer: `railway service source disconnect -s operator-bot` + redeploy por
> staging.

**Env vars do serviço** (Railway → service `operator-bot` → Variables):

| Variável | Valor | Obs |
|---|---|---|
| `OPERATOR_DISCORD_TOKEN` | (token do bot) | obrigatório |
| `OPERATOR_CHANNELS` | (IDs dos canais, vírgula) | allowlist onde responde; sem ele, só DM (`OPERATOR_CHANNEL_ID` = 1 canal) |
| `WEBHOOK_FIN_PULSE` | (URL do canal **pulsação financeira**) | anomalia 🚨 (estrita — nunca outro canal) | 
| `WEBHOOK_VENDEDORES` | (URL do canal **Vendedores**) | comandos de vendedor (`!vendas`/`!chargebacks`/`!lojistas`/`!receita`/`!top5`/`!ip`/`!vigiar`) |
| `WEBHOOK_SUMMARIES` `WEBHOOK_HEALTH` `WEBHOOK_SECURITY` | (URLs das integrações) | resumos diário/semanal · saúde · segurança; `discordapp.com` é normalizado p/ `discord.com` |
| `WEBHOOK_CMD` `WEBHOOK_OPS` | — | futuros |
| `DATABASE_URL` | `${{Postgres.DATABASE_URL}}` | referência → host **interno** (sem exposição pública) |
| `TZ` | `America/Sao_Paulo` | node-cron usa a TZ do processo (Railway default UTC) |
| `FOXCASH_ADMIN_API_KEY` | (chave admin — deve ser a **mesma** do backend) | p/ `!latencia` e `!ip` (lookup de lojista por IP — Spec 67/Fase E); sem ela os comandos respondem "indisponível" |
| `FOXCASH_BASE_URL` | `http://foxcash.railway.internal:8080` (produção) / `http://localhost:8080` (dev) | **rede privada** do Railway (bypassa o Cloudflare — fix 2026-08-10: o edge bloqueava o IP do datacenter → 401 no `!ip`); default do código: `https://api.foxcash.example.com` (público) |
| `CF_API_TOKEN` `CF_ZONE_ID` | (escopado) · `<CF_ZONE_ID>` | opcional; p/ `!bloquear` (zona `foxcash.example.com` — rebrand Spec 140) |
| `RAILWAY_TOKEN` | (token de API do dashboard) | p/ `!deploy`/`!bloqueios`/`!ip`/`!log` (via API GraphQL) |
| `RAILWAY_OPS_SERVICE_ID` | (ID do serviço operado — backend FoxCash) | alvo de status/logs; default: próprio serviço do bot |
| `ANOMALY_THRESHOLD` | `0.5` | opcional |

> `DATABASE_URL=${{Postgres.DATABASE_URL}}` injeta o host interno
> automaticamente — o mesmo caminho seguro do dev via túnel, sem túnel.

**Comandos de Railway no container** (`!deploy`, `!bloqueios`, `!ip`, `!log`):
usam a **API GraphQL** do Railway com `RAILWAY_TOKEN` (token de API do dashboard
— validado 2026-08-10) e consultam o serviço de `RAILWAY_OPS_SERVICE_ID`
(backend FoxCash). Sem `RAILWAY_TOKEN`, respondem erro amigável. Sem
`CF_API_TOKEN`, `!bloquear` responde que o Cloudflare não está configurado.

> **Resiliência do `!ip`/`!vigiar` (2026-08-10)**: `fetchWithRetry` ganhou
> `timeoutMs` (lookup usa **30s**, sem retry em timeout). Diagnóstico real do
> erro "falha ao consultar o backend": a **rede interna**
> (`foxcash.railway.internal`) falha na conexão com o endpoint **admin**
> (`/api/v1/admin/**`) mesmo com `health=UP` no mesmo host — o `!ip` então faz
> **fallback p/ a URL pública** (`RAILWAY_SERVICE_FOXCASH_URL` ou
> `https://api.foxcash.example.com`, mesmo deploy). Falha deixa log
> `[!ip] <ip>: interno <erro com cause> | health=...` no console do serviço
> (distingue `TimeoutError`, DNS, conexão recusada/reset e status do health).

**Atualizações**: repetir o deploy por staging (comando acima) após cada mudança; alternativa durável: source GitHub com Root Directory `operator-bot` (dashboard) → auto-deploy no push.

## Comandos

Saúde: `!saude` · `!conexoes` · `!outbox` · `!jobs` · `!latencia` · `!deploy`
Negócio: `!vendas` · `!chargebacks` · `!lojistas` · `!receita` · `!top5`
Segurança: `!alertas` · `!bloqueios` · `!ip <ip> [id]` · `!reconciliacao` · `!log <requestId>`
Ações: `!bloquear <ip>` (regra WAF no Cloudflare) · `!ratelimit` · `!ajuda`

## Alertas agendados

Saem pelo **webhook da categoria** (integrações do servidor): diário e semanal →
`WEBHOOK_SUMMARIES` (resumos periódicos); anomalia → `WEBHOOK_FIN_PULSE`
(pulsação financeira). Webhook ausente → cai no primeiro canal da allowlist
(`OPERATOR_CHANNELS`); sem canal, vira log no console do bot.

- **Diário 09:00**: vendas de ontem, novos lojistas, alertas, saúde
  (`SCHED_DAILY_CRON`).
- **Semanal (seg 09:00)**: vendas da semana, chargebacks, top lojistas,
  reconciliação (`SCHED_WEEKLY_CRON`).
- **Anomalia** (a cada 15min): vendas da última hora < 50% da média
  (`ANOMALY_THRESHOLD`, throttle 1/h).

Mapeamento das categorias — **comandos interativos também roteiam** (2026-08-10):
quando a categoria do comando tem webhook configurado, a resposta vai para ele
(sem reply no canal; sem webhook → reply no canal):
| Categoria | Env | Comandos/destinos |
|---|---|---|
| vendedores | `WEBHOOK_VENDEDORES` | `!vendas` · `!chargebacks` · `!lojistas` · `!receita` · `!top5` · `!ip` · `!vigiar` (canal Vendedores) |
| pulsação financeira | `WEBHOOK_FIN_PULSE` | **anomalia 🚨** (estrita — nunca outro canal) |
| comando e controle | `WEBHOOK_CMD` | respostas de comando sem categoria (`!ajuda`/`!help`) — reply no canal |
| OPS | `WEBHOOK_OPS` | `!deploy` · `!bloquear` · `!ratelimit` · `!log` |
| resumos periódicos | `WEBHOOK_SUMMARIES` | **resumo diário** · **resumo semanal** |
| saúde e sistema | `WEBHOOK_HEALTH` | `!saude` · `!conexoes` · `!outbox` · `!jobs` · `!latencia` |
| segurança e auditoria | `WEBHOOK_SECURITY` | `!alertas` · `!bloqueios` · `!reconciliacao` |

## !bloquear <ip> — reversão

A regra WAF é criada com descrição `operator-bot block <ip> <data>` via **PUT** no
entrypoint do ruleset `http_request_firewall_custom` (expressão `(ip.src eq <ip>)`
**sem aspas** — validado contra a API real em 2026-08-10; `POST .../rules` e aspas
no IP falham com API Token). Para remover: Cloudflare Dashboard → Security → WAF →
Custom rules → apagar a regra (nome descrito acima) — ou via API reescrevendo o
entrypoint (PUT) sem a regra. Permissão do token: **Zone → WAF → Edit**.

## Segurança

- O bot é **read-only** no banco (SELECT); única escrita é `!bloquear` via API
  Cloudflare (reversível).
- ACL: responde apenas no canal configurado ou por DM ao bot.
- Segredos só por env; nenhum input de usuário vira SQL concatenado.
- Falha de qualquer fonte → resposta amigável, sem crash.
