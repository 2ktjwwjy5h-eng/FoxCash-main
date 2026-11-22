---
name: Operator Bot — comando e controle via Discord
status: audited
references:
  - "management/vision.md#V47"
  - "management/specs/64-sec-observability-incident-response/requirements.md"
  - "management/adr.md#ADR-011"
  - "management/adr.md#ADR-014"
  - "docs/analise-tecnica-2026-08-10.md"
---

# Spec 67 — Operator Bot (comando e controle via Discord)

## O que entrega

Um **bot de operação no Discord** (canal do operador) que permite consultar a
saúde do sistema, métricas de negócio, segurança/auditoria e executar ações
leves **sem abrir o Railway ou o terminal** — usando a mesma infraestrutura de
alerta já configurada (operador webhook do Discord, Spec 66/R3) como ponto de
entrada. Comandos por mensagem no formato `!comando [args]`.

### 1. Monitoramento de Saúde (resumo em tempo real)

| Comando | Responde |
|---|---|
| `!saude` | App UP (healthcheck), conexões DB (atual/máx), Redis, outbox drenando, último deploy |
| `!conexoes` | Conexões ativas no banco (ex.: 201/500) via `pg_stat_activity` |
| `!outbox` | Status da fila (PENDING/SENT/FAILED) + taxa de drenagem (última hora) |
| `!jobs` | Últimos 5 jobs (`job_runs`) com status SUCCESS/FAILED |
| `!latencia` | p95/p99 da última hora (via métricas Prometheus do app) |
| `!deploy` | Status do último deploy (data, commit, réplicas) via Railway |

### 2. Métricas de Negócio (pulsação financeira)

| Comando | Responde |
|---|---|
| `!vendas` | Vendas aprovadas na última hora + ticket médio |
| `!chargebacks` | Chargebacks na última semana |
| `!lojistas` | Lojistas ativos + novos nos últimos 7 dias |
| `!receita` | Estimativa de receita do mês (transações + mensalidades) |
| `!top5` | Top 5 lojistas por volume no mês |

### 3. Segurança e Auditoria (centro de vigilância)

| Comando | Responde |
|---|---|
| `!alertas` | Últimos 5 alertas de segurança (outbox: `*_BURST`, `BODY_INSPECTION_BLOCKED`...) |
| `!bloqueios` | Últimos 5 bloqueios do body inspection (path/padrão/categoria — dos logs) |
| `!ip <ip>` | O IP aparece nos logs de ataque recentes? |
| `!reconciliacao` | Divergências wallet×ledger abertas + última execução |
| `!log <requestId>` | Busca o requestId nos logs do Railway |

### 4. Alertas Agendados (resumos periódicos automáticos)

| Agendado | Conteúdo |
|---|---|
| Diário 09:00 | Vendas do dia anterior, novos lojistas, alertas, saúde |
| Semanal (seg 09:00) | Vendas da semana, chargebacks, taxa de aprovação, top lojistas |
| Anomalia (proativo) | Vendas da última hora < 50% da média → alerta |

### 5. Interação (comando e controle)

| Comando | Ação |
|---|---|
| `!bloquear <ip>` | Adiciona IP à blacklist do Cloudflare (regra WAF block) — usa o token escopado da Spec 66/R1 |
| `!ratelimit` | Limites atuais (app: 20/120/300/120; edge: 50/10s) + estado |
| `!notificar <lojista> <mensagem>` | Notifica um lojista (fase 2 — requer endpoint dedicado no backend) |

**Fora de escopo (fase 2 / documentado):** `!notificar` (requer endpoint
`POST /api/v1/operator/notify` no backend — insert direto no outbox é frágil),
dashboard gráfico, integração com Grafana, comandos de escrita em dados
financeiros (só leitura + bloquear IP).

### 6. Roteamento por webhook (7 categorias)

O operador mantém **7 webhooks** nas integrações do servidor Discord (um por
categoria). Os **proativos** (resumos e anomalia) saem pelo webhook da categoria;
comandos interativos também roteiam para a categoria (sem reply no canal);
sem webhook da categoria → reply no canal:

| Categoria | Env | Destinos |
|---|---|---|
| comando e controle | `WEBHOOK_CMD` | respostas de comando sem categoria (`!ajuda`/`!help`) — reply no canal |
| OPS | `WEBHOOK_OPS` | `!deploy` · `!bloquear` · `!ratelimit` · `!log` |
| pulsação financeira | `WEBHOOK_FIN_PULSE` | **anomalia 🚨** (estrita — nunca outro canal) |
| vendedores | `WEBHOOK_VENDEDORES` | `!vendas`/`!chargebacks`/`!lojistas`/`!receita`/`!top5`/`!ip`/`!vigiar` (canal Vendedores) |
| resumos periódicos | `WEBHOOK_SUMMARIES` | **resumo diário** · **resumo semanal** |
| saúde e sistema | `WEBHOOK_HEALTH` | saúde (`!saude`/`!conexoes`/`!outbox`/`!jobs`/`!latencia`) |
| segurança e auditoria | `WEBHOOK_SECURITY` | segurança (`!alertas`/`!bloqueios`/`!reconciliacao`) |

> **Ajuste do usuário (2026-08-10)**: canais separados — negócio/vendedores → canal
> Vendedores (`WEBHOOK_VENDEDORES`, novo); a anomalia 🚨 sai **somente** na pulsação
> financeira (`WEBHOOK_FIN_PULSE`, `channelFallback: false` — sem webhook, vira log,
> nunca outro canal). Antes o `!ip` roteava para OPS e a anomalia caía no canal da
> allowlist.

Webhook ausente → fallback para `OPERATOR_CHANNEL_ID`; sem canal → log no
console do bot. Mapeamento detalhado no runbook `deploy/operator-bot.md`.

### 7. Lookup de IP (`!ip <endereco_ip>`) — operação/auditoria

> **Estado (2026-08-10): IMPLEMENTADO e validado em produção** — backend + bot
> concluídos; smoke de produção OK (deploy `ce503a39`: V46 aplicada, endpoint real
> com 217 matches, 400/401, trilha `IP_LOOKUP`). Ver task.md Fase E.

Substitui o `!ip` antigo (grep em logs do Railway) por um **lookup de lojista por
IP** via endpoint admin no backend (`GET /api/v1/admin/lookup/ip/{ip}`):

1. Encontra o(s) lojista(s) associado(s) ao IP consultando
   `pix_transactions.buyer_ip` (IP do comprador na cobrança) **ou**
   `platform_activity_log.ip` (IP de origem do ator na trilha).
2. Exibe por lojista: total de vendas aprovadas, ticket médio, última venda e
   status da conta (`merchants.status`).
3. Se o lojista tem loja (`stores`) com plano(s) ativo(s) (`plans.active`) e/ou
   webhook ativo (`merchant_webhooks.status='ACTIVE'`), indica o bot de vendas e
   inclui um **link** quando disponível em `stores.social_links` (entrada com URL
   de Discord invite ou Telegram).
4. IP sem associação → "IP não encontrado".
5. Múltiplos lojistas → lista todos com resumo curto de cada um.

**Segurança**:
- Endpoint sob `/api/v1/admin/**` → `ROLE_ADMIN` (admin API key — a mesma
  `NYX_ADMIN_API_KEY` que o bot já usa).
- O bot mascara o IP na resposta (nunca exibe IP completo/chaves no chat); o
  backend não retorna IPs além do consultado.
- Cada consulta é registrada na `platform_activity_log` com ação **`IP_LOOKUP`**
  (actor `SYSTEM`, `ip` = IP consultado, `details` = contagem + merchants
  mascarados — sem chaves/IPs).

**Verificação de schema (2026-08-10)** — divergências do descrito pelo usuário:
- `pix_transactions.buyer_ip` **EXISTE** (V38, VARCHAR(45)) ✅ — e `buyer_id_hash`
  (V12, hash opaco — sem PII).
- `merchant_webhooks` usa **`status`** ('ACTIVE'/'INACTIVE'), **não** `active`.
- `stores.social_links` (TEXT, JSON `[{label,url}]`) — única fonte de link de bot
  (Discord/Telegram) hoje; **não há coluna dedicada de convite** (ver design §7).
- `platform_activity_log.ip` (VARCHAR(45)) + `merchant_id` + `action` ✅ (V33).
- Faltam índices p/ o lookup → migração **V46** (já criada, não aplicada).

## Critérios de aceite

1. Bot conecta ao Discord (token) e responde os comandos de leitura (grupos 1-3)
   no canal do operador, com dados **reais** (banco/API), não mockados.
2. `!saude`/`!conexoes`/`!outbox`/`!jobs`/`!vendas`/`!chargebacks`/`!lojistas`/
   `!receita`/`!top5`/`!alertas`/`!reconciliacao` retornam valores consistentes
   com o banco (verificável por SQL manual).
3. `!bloquear <ip>` cria a regra WAF block no Cloudflare (token escopado) e
   responde o resultado; regra removível manualmente (documentado).
4. `!log <requestId>`/`!bloqueios`/`!ip <ip>` consultam os logs do Railway e
   retornam as linhas relevantes.
5. Alertas agendados: daily/weekly enviados no horário configurado pelo webhook
   da categoria `SUMMARIES`; anomalia dispara quando vendas/hora < 50% da média
   (janela configurável) e sai em `FIN_PULSE`; webhook ausente → fallback para o
   canal (`OPERATOR_CHANNEL_ID`).
6. Falhas de qualquer fonte (banco/API/CLI) → resposta de erro amigável, nunca
   crash do bot; comandos desconhecidos → ajuda.
7. `npm run typecheck` + testes dos módulos puros verdes; sem tocar no backend
   (o bot é read-only exceto `!bloquear` via API Cloudflare).

## Filosofia

- **Leitura primeiro, escrita mínima**: o bot é uma janela de leitura para
  operação; a única ação é `!bloquear <ip>` (reversível, via WAF) — nada de
  escrever em dados financeiros pelo chat.
- **Fail-open / degradação graciosa**: banco fora → mensagem clara, não crash;
  cada fonte (DB/Railway/CF/Prometheus) é independente.
- **Reusa o que existe**: infra de alerta (Discord), métricas Prometheus,
  outbox/trilhas/jobs já persistidos — o bot só **consulta**.
- **Segredos por env**: bot token, DATABASE_URL, admin key, CF token via env;
  nunca em repo.

## Dependências

- Banco PostgreSQL do FoxCash (leitura) — mesmo schema do prod
- Railway (status/deploy/logs — CLI autenticado ou `RAILWAY_TOKEN`+API)
- Cloudflare (regra WAF p/ `!bloquear`) — token escopado `foxcash-ops-scoped` (Spec 66/R1)
- Backend `/actuator/prometheus` (ROLE_ADMIN) p/ `!latencia`
- `docs/analise-tecnica-2026-08-10.md`, `docs/roteiro-melhorias-2026-08-10.md`
