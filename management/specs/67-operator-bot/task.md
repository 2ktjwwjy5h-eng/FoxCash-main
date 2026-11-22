# Spec 67 — Task: Operator Bot (comando e controle via Discord)

Checklist de implementação. Princípios: **leitura primeiro, escrita mínima**;
**degradação graciosa** (cada fonte é independente); **segredos por env**.

## Fase A — Estrutura e fonte de dados

- [x] 1. `operator-bot/` (package.json, tsconfig, .env.example, .gitignore) com
      deps: discord.js, pg, node-cron, typescript, vitest. **Evidência:** `npm
      install` + `npm run typecheck` verde (exit 0) + build `tsc` OK.
- [x] 2. `src/db.ts` — pool pg de leitura + queries nomeadas (conexoes, outbox,
      jobs, vendas, chargebacks, lojistas, receita, top5, alertas, reconciliacao).
      **Evidência:** 12/12 queries validadas contra o Postgres real de produção
      (2026-08-10, túnel `railway connect`). Correção encontrada na validação:
      `SHOW max_connections` devolve a coluna `max_connections` (não `setting`) —
      `!conexoes` mostrava `max: null`/`NaN%`.
- [x] 3. `src/railway.ts` — wrapper: `railway status` (deploy/réplicas), `railway
      logs --lines N` (grep por requestId/padrão). **Evidência:** parsing
      validado contra output real do CLI (`online/replicas 2/2/deployment ID/url`
      conferidos em produção). Limitação Windows/git-bash documentada no runbook.
- [x] 4. `src/prometheus.ts` — parse do `/actuator/prometheus` (text format) p/
      p95/p99. **Evidência:** teste unitário com fixture (4/4 testes).

## Fase B — Comandos

- [x] 5. `src/index.ts` + dispatcher + ACL (canal do operador/DM). **Evidência:**
      typecheck; smoke manual do bot depende do pré-requisito externo (token do
      Discord — ver task 11).
- [x] 6. **Saúde**: `!saude` (app UP + conexões + Redis + outbox + deploy),
      `!conexoes`, `!outbox`, `!jobs`, `!latencia`, `!deploy`.
- [x] 7. **Negócio**: `!vendas`, `!chargebacks`, `!lojistas`, `!receita`, `!top5`.
- [x] 8. **Segurança**: `!alertas`, `!bloqueios` (logs), `!ip <ip>` (logs),
      `!reconciliacao`, `!log <requestId>` (busca por substring nos logs).
- [x] 9. **Ações**: `!bloquear <ip>` (CF WAF block — regra reversível por
      `ruleId`/descrição), `!ratelimit` (limites + estado do edge via CF).
      `!notificar` → fase 2. **Validado contra a API real (2026-08-10)** com IP de
      teste (192.0.2.1) + reversão: corrigido `POST .../rules` → **PUT no entrypoint**
      (POST retorna 405/10405 com API Token) e expression `(ip.src eq "IP")` →
      **sem aspas** (aspas falham no parse 20127). Permissão do token: Zone → WAF → Edit.

## Fase C — Agendados

- [x] 10. `src/scheduled.ts` (node-cron): daily (09:00), weekly (seg 09:00),
       anomalia (a cada 15min; < 50% da média → alerta com throttle 1/h).
       **Evidência:** `buildDaily`/`buildWeekly`/anomalia montados sobre os
       comandos testados; queries de janela (`salesWindow`) validadas ao vivo.

## Fase D — Verificação e deploy

- [x] 11. **Pré-requisitos externos documentados**: bot token do Discord
       (portal de desenvolvedor), `DATABASE_URL`, `RAILWAY_TOKEN`,
       `NYX_ADMIN_API_KEY`, `CF_API_TOKEN` (escopado) — em `.env.example`/README.
- [x] 12. **Testes**: vitest dos módulos puros (parse prometheus 4/4 ✅, `validIp`,
       `formatMs`) + queries contra o banco real (12/12 ✅). Smoke manual dos
       comandos no Discord fica para o primeiro deploy (pré-requisito: token).
- [x] 13. **Runbook** `deploy/operator-bot.md` (execução local / Railway service,
       reversão do `!bloquear`, limitação Windows documentada).
- [x] 15. **Roteamento por webhook (6 categorias do servidor Discord)**: `src/webhooks.ts`
       carrega `WEBHOOK_CMD/OPS/FIN_PULSE/SUMMARIES/HEALTH/SECURITY`; diário/semanal →
       `SUMMARIES` e anomalia → `FIN_PULSE` via `WebhookClient`; comandos seguem
       respondendo no canal (reply); fallback p/ `OPERATOR_CHANNEL_ID`/log.
       **Evidência:** teste unitário do parse de config (4/4) — total 8/8 testes.
       Correções no smoke real (2026-08-10): normaliza `discordapp.com`→`discord.com`
       (regex do discord.js só aceita `discord.com`) e URL de webhook inválida não
       derruba o bot (try/catch — degradação graciosa).
- [ ] 14. Roadmap: 67 `created` → `implemented` + commit. Fim do ciclo p/
       `/spec-audit`.

## Fase E — Lookup de IP (`!ip <endereco_ip>`) — **IMPLEMENTADA (2026-08-10)**

> Implementação retomada e concluída (2026-08-10). Requisitos: requirements.md §7.
> Design (queries SQL, estratégia do link, camadas): design.md §7.

**Backend (commitado):**
- [x] E1. Migração **V46** (`src/main/resources/db/migration/V46__ip_lookup_indexes.sql`):
      índices `ix_pix_transactions_buyer_ip` e `ix_platform_activity_ip` (IF NOT EXISTS).
      **Aplicada no boot (verificado no banco local: V46 presente).**
- [x] E2. `PlatformAction.IP_LOOKUP` adicionado ao enum (Spec 41 — trilha unificada).
- [x] E3. `domain/model/admin/IpLookupResult.java` criado (merchantId, name, email,
      status, totalSales, averageTicket, lastSaleAt, bot{storeId, storeName,
      activePlans, activeWebhooks, link}).
- [x] E4. Portas: `AdminDashboardUseCase.lookupByIp(String)` +
      `AdminDashboardPort.lookupByIp(String)`.
- [x] E5. `AdminDashboardService.lookupByIp` — chama o port + registra `IP_LOOKUP`
      na `platform_activity_log` (actor SYSTEM, ip=consultado, details `{count,
      merchants:[{id,name,status}]}` — sem e-mail/chaves; `@Transactional` próprio
      no método — o save não fica em transação readOnly).
- [x] E6. `AdminController` + `GET /api/v1/admin/lookup/ip/{ip}` (validação IPv4/IPv6
      → 400 `invalid_argument`) + DTO `IpLookupResponse` (mesmo padrão
      `MerchantResponse::from`).
- [x] E7. `AdminDashboardPersistenceAdapter.lookupByIp` — 3 queries nativas via
      `EntityManager` (merchants por IP / resumo vendas / store+bot) — SQL do
      design §7; link extraído de `stores.social_links` (Discord invite/Telegram);
      `MAX(paid_at)` de query nativa convertido Timestamp→Instant.
- [x] E8. Teste backend focado — `AdminDashboardServiceTest` +3 (delegação + registro
      `IP_LOOKUP`, matches vazio, sem dados sensíveis no details). `mvn test`
      **1159/0/0** sem regressão.

**Bot (`commands.ts`):**
- [x] E9. Handler `ip` substituído — valida `validIp` (reuso do `cloudflare.ts`),
      chama o endpoint com `X-Api-Key`, formata 1..N lojistas (vendas/ticket/última/
      status + bot com planos/webhooks/link), **mascara o IP** (`maskIp` exportado:
      IPv4 mantém último octeto; IPv6 grupos antes da compressão `::` + `…`). Sem
      admin key → mensagem clara. Testes vitest: **12/12** (maskip 4 novos),
      `tsc --noEmit` limpo.

**Validação (E10 — app dev local + banco real):**
- [x] E10a. Queries 1-3 validadas via psql no banco dev (schema V1..V46 real, dados
      reais — IPv6 `0:0:0:0:0:0:0:1` com 28 lojistas).
- [x] E10b. Endpoint validado ao vivo (app dev): com matches (resumo vendas +
      ticket `150.00`/`10.00`/`0.00` normalizado, lastSaleAt), sem matches
      (`matches:[]`), IP inválido → 400, sem chave → 401; **V46 aplicada no boot**;
      trilha `IP_LOOKUP` no banco (SYSTEM, ip consultado, details mascarado).
- [x] E10c. Link do bot validado: `social_links` com convite Discord → `bot.link`
      exposto; sem `social_links` → `bot` ativo sem link (honesto).
- [x] E10d. **Smoke de produção (2026-08-10, deploy `ce503a39` após push
      `8522b4d`)**: deploy novo confirmado (V46 no banco de prod `success=true`);
      endpoint real com chave admin de prod: `177.131.167.142` → **217 matches**
      (lojista com vendas/ticket/status; sem loja ativa → `bot:null`), sem matches
      → `{"ip":...,"matches":[]}`, IP inválido → **400**, sem chave → **401**;
      trilha `IP_LOOKUP` registrada em prod (`203.0.113.99` count 0 e
      `177.131.167.142` count 217 — actor SYSTEM, ip consultado, details
      mascarado). **Bot redeployado (deploy `02fa26c5`, status SUCCESS, online:
      "Operator Bot online: Trevopay Ops#1698")** — o serviço `operator-bot` usa
      deploy por staging (`railway up`, runbook) e rodava o código antigo; após o
      redeploy está com o handler `!ip` novo. Bot já tem `NYX_ADMIN_API_KEY` (48
      chars — igual ao backend) → `!ip` no Discord funcional; smoke manual do
      comando fica a critério do operador (comando de chat).

**Pendente:**
- [ ] E11. Fechamento: commit + push das docs finais (este task + runbook
      `deploy/operator-bot.md` com `NYX_ADMIN_API_KEY` para `!latencia`/`!ip`).
      **Fase E funcionalmente completa** — smoke de produção validado (E10d).

## Fase F — Mensagens conversacionais com classificação de estado (2026-08-10)

Melhoria de UX pedida pelo usuário: cada comando passou a dizer **como está** o
sistema (não só o número) e a sugerir o próximo passo.

- [x] F1. **`src/format.ts`** (novo, puro/testável): `Verdict {emoji,label,advice}` —
      classificadores com rótulo humano + dica contextual: `latencyVerdict`
      (p95: ótimo ≤200ms 🚀 / bom ≤500ms ✅ / regular ≤1s ⚠️ / ruim >1s 🚨),
      `usageVerdict` (conexões: tranquilo/ok/elevado/crítico), `outboxVerdict`
      (PENDING: liso/normal/acumulando/gargalo), `jobsVerdict`, `salesVerdict`
      (calmo/leve/bom/pico), `chargebackVerdict` e `reconciliationVerdict`.
- [x] F2. **`commands.ts`** reescrito com tom conversacional: emojis por item,
      rótulo de estado, dica (`💡`) nos níveis de atenção e sugestão de comandos
      relacionados. Exemplos: `!saude` agrega tudo e lista "pontos de atenção"
      com dicas; `!latencia` → "🟠 p95: 700ms — **regular ⚠️** + dica";
      `!conexoes`/`!outbox`/`!jobs`/`!reconciliacao` classificam; `!top5` com
      pódio 🥇🥈🥉; `!ip` com status por lojista; datas em horário de Brasília.
- [x] F3. Testes: `test/format.test.ts` (+8 casos — thresholds e conselhos).
      `tsc --noEmit` limpo e vitest **20/20** (4 arquivos). Build `tsc` OK.
- [x] F4. Redeploy do bot (staging `railway up`, deploy `88efef66` SUCCESS —
      "Operator Bot online: Trevopay Ops#1698") com as novas mensagens. Commit
      `9e18e7e`. Smoke manual dos comandos no Discord fica a critério do
      operador.

## Fase G — Roteamento de comandos por categoria via webhook (2026-08-10)

Pedido do usuário: as **informações dos vendedores** (comandos de negócio) vão
para um canal de texto específico via webhook (canal **"Vendedores"**,
webhook `WEBHOOK_FIN_PULSE`).

- [x] G1. `webhooks.ts`: `COMMAND_CATEGORY` — mapa comando→categoria (requirements
      §6): negócio (`!vendas`/`!chargebacks`/`!lojistas`/`!receita`/`!top5`) →
      `finPulse`; saúde → `health`; segurança → `security`; OPS → `ops`;
      `!ajuda`/`!help` → `cmd` (reply no canal).
- [x] G2. `index.ts`: dispatcher roteia a resposta para o webhook da categoria
      quando configurado (com aviso curto no canal do comando: "✅ respondido no
      canal de **pulsação financeira**"); sem webhook ou falha de envio → reply
      no canal (degradação graciosa). `WebhookRouter` compartilhado (hoisted,
      preenchido no `ClientReady`).
- [x] G3. Testes: `webhooks.test.ts` +1 (mapeamento dos 19 comandos por categoria).
      `tsc` limpo e vitest **21/21** (4 arquivos). Commit `2048b83`.
- [x] G4. **Config em produção**: `WEBHOOK_FIN_PULSE` setada no serviço
      `operator-bot` (Railway) com o webhook do canal "Vendedores"
      (validado: GET → webhook `Vendedores`, channel `1536393075964911657`;
      POST UTF-8 → 204). Redeploy do bot (deploy `106194c2` SUCCESS — online).
- [x] G5. **Smoke ponta a ponta**: `!vendas` com dados reais de prod (túnel +
      queries) → mensagem conversacional ("😴 nenhuma venda na última hora...")
      → `webhooks.send('finPulse', ...)` → **true** (publicada no canal
      Vendedores). Smoke manual dos comandos no Discord a critério do operador.

## Fase H — Monitorar vendedor por IP + anomalia estrita (2026-08-10)

Pedido do usuário: `!ip` com **faturamento da última semana** e links dos bots;
novo **`!vigiar <ip>`** (alerta único por chamada); **anomalia somente na
pulsação financeira** (nunca em outro canal de texto).

- [x] H1. **Backend — faturamento 7d**: query 2 do `lookupByIp` ganha
      `COUNT/SUM FILTER (paid_at >= now() - interval '7 days')` →
      `total7d`/`revenue7d` por lojista (mesmo padrão `now()-interval` das
      queries do bot). `IpLookupResult.MerchantMatch` + `sales7d`/`revenue7d`;
      DTO `IpLookupResponse` expõe. `mvn test` **1159/0/0**. Commit `2727428`
      (deploy automático do Railway).
- [x] H2. **`!ip` reformulado**: mostra IP mascarado, vendedor (status), **💰
      Última semana: R$ X (N vendas)**, ticket, última venda e 🤖 links dos
      bots (ou "ativo, sem link configurado"). Helper `ipLookup` +
      `formatIpReport` compartilhados.
- [x] H3. **`!vigiar <ip>`**: alerta **único por chamada** (sem persistência/
      loop) — mesma consulta do `!ip`, título "🚨 Vigilância de IP". Roteado
      para o canal Vendedores (`finPulse`).
- [x] H4. **Anomalia estrita**: `sender.send(..., 'finPulse', { channelFallback:
      false })` — sem webhook da finPulse vira **log**, nunca posta em outro
      canal de texto (demais resumos mantêm o fallback original).
- [x] H5. **Roteamento**: `!ip` e `!vigiar` → categoria `finPulse` (canal dos
      vendedores); `!log` permanece OPS. Testes 21/21, tsc limpo. Commit
      `9d99da5`.
- [x] H6. **Config prod**: canal Vendedores (`1536393075964911657`) adicionado à
      allowlist `OPERATOR_CHANNELS` (7 canais). Redeploy do bot (deploy
      `45664f6d` — SUCCESS/online). Endpoint validado ao vivo (app dev):
      `sales7d`/`revenue7d` presentes; `!ip`/`!vigiar` renderizados com dados
      reais. Smoke manual no Discord a critério do operador.
- [x] H7. **Fix 401 do `!ip` (2026-08-10)**: usuário reportou "!ip: chave admin
      inválida ou sem permissão" no canal Vendedores. Chaves `NYX_ADMIN_API_KEY`
      verificadas **idênticas** (hash) entre backend e bot; reprodução com node
      fetch da máquina local → **200**. Causa: **Cloudflare** (Bot Fight Mode ON,
      Spec 63) bloqueava/desafiava o IP de datacenter do Railway (o bot passava
      pelo edge público). Fix: `NYX_BASE_URL=http://nyxcash.railway.internal:8080`
      no serviço `operator-bot` — o bot fala com o backend pela **rede privada
      do Railway** (bypassa o edge; mesmo padrão do DATABASE_URL). + self-check
      no boot (`[api] baseUrl=... health=UP` no log). Redeploy `df7d7a47`
      SUCCESS: `[api] baseUrl=http://nyxcash.railway.internal:8080 health=UP`
      confirmado no log. Commit `85a9a88`. Re-teste manual do `!ip` no Discord a
      critério do operador.
- [x] H8. **Re-teste e limpeza do ack (2026-08-10)**: o 1º re-teste do usuário
      (13:00 BR) rodou **4 min antes** do deploy do fix (13:04 BR) — ainda no
      bot antigo (URL pública) → "falha ao consultar o backend". Fix já no ar
      (deploy `df7d7a47`, self-check `health=UP` na rede interna). Usuário
      também reclamou do ack "✅ respondido no canal de pulsação financeira." →
      **removido** (resposta vai só ao webhook da categoria; sem reply no canal;
      fallback p/ reply só se o webhook falhar). Redeploy `a7ab48a3` SUCCESS,
      self-check `[api] ... health=UP` confirmado. Commit `e553380`. Re-teste
      do `!ip` no Discord a critério do operador.
- [x] H9. **Fix "falha ao consultar o backend" (2026-08-10)**: o backend
      redeploya a cada push no `main` (Railway watch) — durante a troca de
      réplicas a conexão interna falha por segundos e o fetch lançava exceção.
      **`fetchWithRetry`** (commands.ts, exportado): 3 tentativas, backoff
      500ms→1s→1.5s, timeout 10s/tentativa, re-tenta SÓ erro de rede (status
      HTTP não re-tenta). Usado no `ipLookup`/`fetchPrometheus`/health do
      `!saude` e no self-check do boot. Testes `fetch.test.ts` (+3). vitest
      **24/24**, tsc limpo. Redeploy `c90c60a2` SUCCESS, self-check `health=UP`.
      Commit `01ca1c5`. Re-teste do `!ip` a critério do operador.
- [x] H10. **Fix timeout em lookup de IP grande (2026-08-10)**: o H9 cobriu
      falha de conexão (redeploy), mas o `!ip` de IPs com MUITOS lojistas
      (ex.: `177.131.167.142` = 217) estourava o **timeout de 10s** — o retry
      NÃO ajuda (servidor de pé, só lento) e multiplicava a espera (3×10s).
      `fetchWithRetry` agora aceita `timeoutMs` (default 10s) e **não re-tenta
      em timeout** (`isTimeoutError`), só em erro de conexão; `ipLookup`
      (`!ip`/`!vigiar`) usa **30s** + log `[!ip] <ip>: <tipo>: <msg>` no
      console do serviço + mensagem distinta p/ timeout vs backend inacessível.
      Testes `fetch.test.ts` (+2). vitest **26/26**, tsc limpo. Commit
      `f6ccc40`. Redeploy `42548745` SUCCESS, self-check `health=UP` (rede
      interna). Re-teste do `!ip` no Discord a critério do operador.
- [x] H11. **Causa real do "falha ao consultar o backend" (2026-08-10)**: o H10
      (timeout 30s) não resolveu — re-teste do usuário (13:52 BR) falhou igual,
      e o log novo mostrou `[!ip] ...: TypeError: fetch failed` (erro de
      conexão, não timeout). Diagnóstico: a **rede interna**
      (`nyxcash.railway.internal`) falha na conexão com o endpoint **admin**
      (`/api/v1/admin/**`) mesmo com `health=UP` no mesmo host (validado por
      probe com a chave real na URL pública → **HTTP 200 em 1,8s, 217 matches**
      — a query é rápida; o problema é só o caminho interno). Fix: `ipLookup`
      faz **fallback p/ a URL pública** (`RAILWAY_SERVICE_NYXCASH_URL` →
      `https://api.foxcash.example.com`) quando a interna lança erro de rede + log
      completo (`cause` TCP/DNS, status do health no momento, sucesso do
      fallback). `isTimeoutError` robusto p/ DOMException. Commit `ab1125f`.
      Redeploy `d41a14c8` SUCCESS, self-check `health=UP` (rede interna).
      Re-teste do `!ip` no Discord a critério do operador.
- [x] H12. **`!ip <ip> <id>` filtra vendedor + máscara sem markdown (2026-08-10)**:
      usuário pediu filtrar o vendedor pelo id depois do `!ip` (a lista de 217
      truncava a 1900 chars). `!ip` aceita um filtro opcional (id/nome) — filtra
      os `matches` **client-side** (sem nova chamada) por `name`/`merchantId`
      (case-insensitive); sem match → "nenhum vendedor com ...". Máscara do IP
      troca `***` → `•••` (o Discord comia os asteriscos como markdown — IP
      aparecia "..*.142"). Ajuda e runbook atualizados. Commit `f1c7771`,
      redeploy `bb54702d` SUCCESS, health=UP. Re-teste no Discord a critério do
      operador.
