# Roadmap — TrevoPay

Índice mestre de specs do projeto TrevoPay (backend white-label de pagamentos Pix).

Status possíveis: `proposed`, `planned`, `created`, `verified`, `implemented`,
`in-progress`, `audited`, `discarded`.

---

## Specs iniciais (fase 0 — fundação)

| # | Name | Status | References |
|---|---|---|---|
| 1 | Ambiente de desenvolvimento (toolchain) | audited | [requirements](specs/1-environment-setup/requirements.md) |
| 2 | Verificação do scaffold (build + testes) | audited | [requirements](specs/2-scaffold-verification/requirements.md) |
| 3 | Versionamento inicial (commit) | audited | [requirements](specs/3-initial-commit/requirements.md) |
| 4 | Segurança (cripto, HTTPS, logs) + prévia de fatura + E2E | audited | [requirements](specs/4-security-and-e2e/requirements.md) |
| 5 | Conflito de cadastro — documento duplicado → `409 Conflict` | audited | [requirements](specs/5-merchant-duplicate-conflict/requirements.md) |
| 6 | Scheduler — geração automática de faturas mensais (ShedLock) | audited | [requirements](specs/6-invoice-scheduler/requirements.md) |
| 7 | Notificação ao lojista (fatura gerada) | audited | [requirements](specs/7-merchant-notification/requirements.md) |
| 8 | Dashboard/admin — endpoints de consulta | audited | [requirements](specs/8-admin-dashboard/requirements.md) |

## Fase 1 — Produção

| # | Name | Status | References |
|---|---|---|---|
| 9 | Fundação de produção — CI/CD, profile prod, deploy sem Docker, ADR do PSP | audited | [requirements](specs/9-production-foundation/requirements.md) |
| 10 | Integração com PSP — estrutura + contrato (seleção por config, verificação de assinatura de webhook) | audited | [requirements](specs/10-psp-integration-foundation/requirements.md) |
| 11 | Adapter real Pagar.me — cobrança Pix via API v5 (REST, auth Basic, contrato verificado) | audited | [requirements](specs/11-pagarme-adapter/requirements.md) |
| 12 | Autenticação da API — API key por lojista + chave admin (X-Api-Key, hash SHA-256) | audited | [requirements](specs/12-api-authentication/requirements.md) |
| 13 | Perfil master — login usuário/senha + sessão (BCrypt, token opaco 8h, ROLE_MASTER) | audited | [requirements](specs/13-master-auth/requirements.md) |
| 14 | Saques — fluxo automático + bloqueio/liberação por lojista (master) | audited | [requirements](specs/14-withdrawals/requirements.md) |
| 15 | Dashboard master — transações, ganhos e taxas (leitura + execução) | audited | [requirements](specs/15-master-dashboard/requirements.md) |

## Fase 2 — Operação e compliance

| # | Name | Status | References |
|---|---|---|---|
| 16 | Antifraude e compliance — monitoramento de indicadores de risco (chargeback > 1%/3%, reembolso > 5%, fraude, denúncias) + suspensão de contas (ADR-008) | audited | [requirements](specs/16-antifraud-compliance/requirements.md) · [políticas](../docs/politicas-uso.md) · [ADR-008](adr.md#adr-008-monitoramento-de-risco-chargebacks-e-suspensão-de-contas) |
| 17 | ~~KYC obrigatório no onboarding~~ — **descartado** (decisão de produto: sem KYC; gateway somente para o vendedor — sem dados do comprador) | planned | [decisão](#observações-pós-auditoria-fora-de-escopo-para-spec-futura) |
| 18 | Hardening de segurança — autorização por recurso (IDOR), rate limiting, validação CPF/CNPJ, robustez da API (400), webhook idempotente, scan de dependências | audited | [requirements](specs/18-security-hardening/requirements.md) · [ADR-009](adr.md#adr-009-autorização-por-recurso-e-rate-limiting) |
| 19 | Antifraude real — triagem em tempo real na cobrança (buyerId opaco anônimo, velocidade, bloqueio por chargeback), registro de chargebacks, auto-suspensão e score de risco (0-100) | audited | [requirements](specs/19-antifraud-real/requirements.md) · migration V12 |

## Fase 3 — Portal do lojista (backend-first: APIs + docs)

| # | Name | Status | References |
|---|---|---|---|
| 20 | Portal do lojista — fundação (auth lojista no portal + convenções de resposta agregada) | audited | [requirements](specs/20-portal-foundation/requirements.md) |
| 21 | Dashboard do lojista — visão geral (métricas de topo, série de faturamento, últimas vendas, pontos de atenção) | audited | [requirements](specs/21-merchant-dashboard/requirements.md) |
| 22 | Carteira/financeiro do lojista — saldo, resumo do mês, entradas da semana, fluxo de caixa | audited | [requirements](specs/22-merchant-wallet/requirements.md) |
| 23 | Gerador de cobrança PIX — cobrança avulsa + Gift PIX (valores predefinidos) | audited | [requirements](specs/23-pix-charge-generator/requirements.md) |
| 24 | Credenciais API do lojista — gestão de chaves (listar/revogar/regenerar, escopos, último uso) | audited | [requirements](specs/24-api-credentials/requirements.md) · [design](specs/24-api-credentials/design.md) |
| 25 | Vendas/operação comercial — listagem com filtros e métricas de topo (volume 30d, concluídas) | audited | [requirements](specs/25-merchant-sales/requirements.md) · [design](specs/25-merchant-sales/design.md) |

## Fase 4 — Hardening (auditoria QA 2026-08-04)

| # | Name | Status | References |
|---|---|---|---|
| 26 | Hardening financeiro e de contrato — locks pessimistas no wallet, estorno (refund/chargeback debita o saldo, negativo permitido), `expirationSeconds` propagado, teto de valor em todos os fluxos, timeouts no Pagar.me, 409 no cadastro concorrente, chave admin constant-time, gift XOR, CI E2E consertado, cobertura REST da Spec 23 | audited | [requirements](specs/26-qa-hardening-financeiro/requirements.md) |

## Fase 5 — Hardening financeiro profundo (auditoria 2026-08-04)

| # | Name | Status | References |
|---|---|---|---|
| 27 | Wallet ledger — movimentações financeiras imutáveis e auditáveis (cada crédito/débito registrado na mesma transação; saldo validável contra o histórico) | audited | [requirements](specs/27-wallet-ledger/requirements.md) · [design](specs/27-wallet-ledger/design.md) · [task](specs/27-wallet-ledger/task.md) |
| 28 | Idempotência na criação de cobrança — `idempotencyKey` por lojista, replay retorna a mesma cobrança (elimina duplicidade em timeout do PSP) | audited | [requirements](specs/28-charge-idempotency/requirements.md) · [design](specs/28-charge-idempotency/design.md) · [task](specs/28-charge-idempotency/task.md) |
| 29 | Estorno consistente — fonte única de débito (chargeback marca a transação REFUNDED; webhook fora de ordem vira no-op idempotente; elimina o duplo débito) | audited | [requirements](specs/29-consistent-refund/requirements.md) · [design](specs/29-consistent-refund/design.md) · [task](specs/29-consistent-refund/task.md) |
| 30 | Reconciliação e integridade do saldo — job que valida `wallet_balance = Σ ledger` e alerta divergência; chargeback duplicado → 409; fatura via COUNT; escala normalizada no JSON | audited | [requirements](specs/30-wallet-reconciliation/requirements.md) · [design](specs/30-wallet-reconciliation/design.md) · [task](specs/30-wallet-reconciliation/task.md) |
| 31 | Destinos de saque do lojista — chaves Pix (CPF/CNPJ/celular/aleatória) e dados bancários, privados por lojista e criptografados em repouso; selecionáveis no saque | audited | [requirements](specs/31-withdrawal-destinations/requirements.md) · [design](specs/31-withdrawal-destinations/design.md) · [task](specs/31-withdrawal-destinations/task.md) |
| 32 | Saque com destino selecionado + bloqueio lacrado — saque exige `destinationId` (instantâneo, anti-duplo via lock); master saca em nome do lojista (administrativo, até o saldo, nunca negativo) | audited | [requirements](specs/32-withdrawal-destination-flow/requirements.md) · [design](specs/32-withdrawal-destination-flow/design.md) · [task](specs/32-withdrawal-destination-flow/task.md) |

## Fase 6 — Operação comercial, pagamentos reais e compliance (decisões 2026-08-04)

| # | Name | Status | References |
|---|---|---|---|
| 33 | Faturamento automático e contas a receber — cobrança da fatura no wallet no vencimento (total/parcial), dívida formal (`receivables`) e bloqueio de saque por inadimplência | audited | [requirements](specs/33-invoice-collection-receivables/requirements.md) · [design](specs/33-invoice-collection-receivables/design.md) · [task](specs/33-invoice-collection-receivables/task.md) |
| 34 | Ledger do master — receita/perda da plataforma registrada na mesma transação (taxas, recuperação de dívida, recolhimento administrativo, baixa auditada) | audited | [requirements](specs/34-master-ledger/requirements.md) · [design](specs/34-master-ledger/design.md) · [task](specs/34-master-ledger/task.md) |
| 35 | Webhook de saída para o bot — eventos de venda/estorno/saque/fatura entregues via POST assinado (HMAC-SHA256) com retry e backoff | audited | [requirements](specs/35-outbound-webhook/requirements.md) · [design](specs/35-outbound-webhook/design.md) · [task](specs/35-outbound-webhook/task.md) |
| 36 | Saque via PSP — contrato de Pix de saída (porta + mock + slot para adquirente real), estado real `PROCESSING/COMPLETED/FAILED`, webhook de saída e reconciliação | audited | [requirements](specs/36-payout-psp/requirements.md) · [design](specs/36-payout-psp/design.md) · [task](specs/36-payout-psp/task.md) |
| 37 | Auditoria de ações do master — trilha imutável (append-only) com actor, before/after e motivo em toda ação administrativa | audited | [requirements](specs/37-master-audit/requirements.md) · [design](specs/37-master-audit/design.md) · [task](specs/37-master-audit/task.md) |

## Fase 7 — Preparação para o adquirente real de PIX de saída (2026-08-05)

| # | Name | Status | References |
|---|---|---|---|
| 38 | PIX de saída pronto para o adquirente real (payout plug-and-play) — estado `PENDING` na reconciliação (não estorna indevidamente), config por adquirente com fail-fast (sem mock em prod), teste de contrato do adapter e runbook de homologação | audited | [requirements](specs/38-payout-acquirer-ready/requirements.md) · [design](specs/38-payout-acquirer-ready/design.md) · [task](specs/38-payout-acquirer-ready/task.md) |

## Fase 8 — Cadastro sem CPF (decisão do usuário 2026-08-05)

| # | Name | Status | References |
|---|---|---|---|
| 39 | Cadastro com nome, e-mail e senha — sem CPF/CNPJ (e-mail único como identificador de login; senha escolhida no cadastro; documento opcional/nulo para legado) | audited | [requirements](specs/39-email-registration/requirements.md) · [design](specs/39-email-registration/design.md) · [task](specs/39-email-registration/task.md) |

## Fase 9 — Recuperação de senha e gestão de perfis master (decisões 2026-08-05)

| # | Name | Status | References |
|---|---|---|---|
| 40 | Recuperação de senha do lojista — token self-service one-time (`POST /merchant-auth/forgot-password` gera token `rst_<32 hex>` com hash SHA-256 no banco, expira ~30min; `POST /merchant-auth/reset-password` redefine a senha e revoga as sessões; anti-enumeração em prod) | audited | [requirements](specs/40-password-reset/requirements.md) · [design](specs/40-password-reset/design.md) · [task](specs/40-password-reset/task.md) |
| 41 | Gestão de perfis master + relatório de atividades — CRUD de perfis master (flag `can_manage_masters` no admin, limite de 4, senha gerada 1x, auditoria Spec 37, rota não-documentada) e trilha unificada `platform_activity_log` (append-only: ações de MASTER/MERCHANT/SYSTEM) com endpoint de consulta do admin | audited | [requirements](specs/41-master-profiles-activity/requirements.md) · [design](specs/41-master-profiles-activity/design.md) · [task](specs/41-master-profiles-activity/task.md) |

## Fase 10 — Pendências pós-3ª auditoria (specs 42–47, `verified` 2026-08-05)

Pendências registradas nas auditorias de homologação (2026-08-05) e decisões
de produto — spec's verificadas (`/spec-verify` 2026-08-05), prontas para
`/spec-implement`.

| # | Name | Status | References |
|---|---|---|---|
| 42 | Backlog de baixos da 3ª auditoria — B-01 (timing no login master), B-02 (reset-password sem rate limit), B-03 (`pix_key` em repouso), B-04 (PSP de saque fora da transação), B-05 (validação de `amount` no webhook), B-06 (lockout do lojista) | audited | [requirements](specs/42-backlog-baixos-3a-auditoria/requirements.md) · [design](specs/42-backlog-baixos-3a-auditoria/design.md) · [task](specs/42-backlog-baixos-3a-auditoria/task.md) |
| 43 | Rate limit distribuído com Redis (M-05) — store selecionável `memory`/`redis`, script Lua atômico, fail-open documentado (ADR-010) | audited | [requirements](specs/43-rate-limit-redis/requirements.md) · [design](specs/43-rate-limit-redis/design.md) · [task](specs/43-rate-limit-redis/task.md) |
| 44 | MFA 2FA TOTP para o master (B-A5) — RFC 6238, ativação 2 passos, recovery codes, lockout de TOTP, auditoria | audited | [requirements](specs/44-mfa-totp-master/requirements.md) · [design](specs/44-mfa-totp-master/design.md) · [task](specs/44-mfa-totp-master/task.md) |
| 45 | Entrega do token de reset por e-mail (Spec 40 follow-up) — porta `ResetTokenDeliverer` (SMTP/log), anti-enumeração mantida, resposta 204 sempre | audited | [requirements](specs/45-reset-token-email/requirements.md) · [design](specs/45-reset-token-email/design.md) · [task](specs/45-reset-token-email/task.md) |
| 46 | Frontend do portal do lojista — SPA (React/Vite) sobre as APIs do portal; token em `sessionStorage`; B-A8 (UX de 401) resolvido no cliente | audited | [requirements](specs/46-portal-frontend/requirements.md) · [design](specs/46-portal-frontend/design.md) · [task](specs/46-portal-frontend/task.md) |
| 47 | Adapter real de PIX de saída (Pagar.me/Asaas) — implementa `PayoutProviderClient` (Spec 36/38) com `@ConditionalOnProperty`, herda o `PayoutProviderContractTest`, runbook de homologação | audited | [requirements](specs/47-payout-acquirer-adapter/requirements.md) · [design](specs/47-payout-acquirer-adapter/design.md) · [task](specs/47-payout-acquirer-adapter/task.md) |

## Fase 11 — Loja white-label multi-vendedora + bots renderizadores (decisão do usuário 2026-08-05)

Fluxo desejado: `/comprar` → escolher plano → criar cobrança → enviar QR Code
PIX → cliente paga → webhook recebe confirmação → banco atualiza pagamento →
bot recebe evento → **entrega acesso automaticamente** (ações por plano: cargo,
mensagem, link, grupo, arquivo). Decisões do usuário: **backend completo**
(planos + entregas idempotentes/auditáveis no TrevoPay; o bot só conversa com a
API), **cada vendedor tem sua loja** (`stores`, 1:1 hoje com modelagem 1:N),
**bot 100% orientado por configuração** (o bot apenas renderiza o storefront —
alterar a loja não exige reiniciar bots) e **bots Node.js + TypeScript**
(discord.js + grammY).

**Nota:** as specs 48/49 originais (deste mesmo dia) foram **descartadas**
(`discarded`) e **substituídas** por esta estrutura revisada.

**Decisão de arquitetura (2026-08-05) — bots stateless / Single Source of
Truth:** o **PostgreSQL do backend (Railway — decisão de infraestrutura
2026-08-05, ADR-011; antes "a definir") é a única fonte de verdade**. Os bots **não possuem banco próprio** (sem SQLite/JSON) e **nunca se
conectam ao banco** — toda comunicação é via API do TrevoPay. O backend
persiste, junto ao pedido/entrega, `platform` (`DISCORD`/`TELEGRAM`) +
`platform_user_id` (ID do comprador na plataforma, **criptografado em repouso**)
para que a entrega seja executada mesmo se o bot reiniciar/ser substituído —
recuperação de pendências exclusivamente via polling da API. Isso **ajusta** a
premissa de 2026-08-04 ("nenhum dado do comprador"): o backend segue **sem
nome/CPF/telefone** do comprador; guarda apenas o ID opaco da plataforma e o
`buyer_id_hash` pseudônimo (antifraude).

| # | Name | Status | References |
|---|---|---|---|
| 48 | Stores e storefront (backend) — loja do vendedor (1 loja/merchant, modelagem 1:N): identidade (nome/logo/banner/descrição/redes/cor/emoji/título/rodapé), painel no portal e `GET /nyx/storefront` (fonte de verdade que o bot renderiza) | audited | [requirements](specs/48-stores-storefront/requirements.md) · [design](specs/48-stores-storefront/design.md) · [task](specs/48-stores-storefront/task.md) |
| 49 | Planos visuais e ações de entrega (backend) — card visual (imagem/cor do embed/destaque), **ações de entrega por plano** (grant_role/send_message/send_link/add_to_group/send_file), compra vinculada (`plan_id`) e entrega com **progresso por ação** (idempotente, retry; **+lock pessimista `SELECT ... FOR UPDATE` 2026-08-07 — replay concorrente executa 1×, 1 auditoria** — E2E concurrency 7/7, k6 critical-endpoints 16.315 checks) | audited | [requirements](specs/49-planos-entregas/requirements.md) · [design](specs/49-planos-entregas/design.md) · [task](specs/49-planos-entregas/task.md) |
| 50 | Botões, menu e fluxo configurável (backend) — `store_buttons` (ações buy/preview/faq/social/link/close + ordem), o vendedor monta o menu no painel; o bot renderiza | audited | [requirements](specs/50-botoes-menu/requirements.md) · [design](specs/50-botoes-menu/design.md) · [task](specs/50-botoes-menu/task.md) |
| 51 | Mensagens automáticas e agendamento (backend) — mensagens por etapa (welcome/awaiting_payment/payment_received/delivered/promo/faq) + programadas (scheduled_at/cron) | audited | [requirements](specs/51-mensagens-automaticas/requirements.md) · [design](specs/51-mensagens-automaticas/design.md) · [task](specs/51-mensagens-automaticas/task.md) |
| 52 | Bots renderizadores da loja (Discord + Telegram) — monorepo `bots/` (core + discord.js + grammY) que renderiza o storefront (Specs 48–51), webhook assinado com dedup, entrega por ações, polling de pendências, ACL admin e fila de reprocessamento | audited | [requirements](specs/52-bots-renderizadores/requirements.md) · [design](specs/52-bots-renderizadores/design.md) · [task](specs/52-bots-renderizadores/task.md) |

**Correção (2026-08-12): statuses das specs 48–52 atualizados para `audited`**
(estavam `planned` na tabela, desatualizados — os frontmatter
`requirements.md` já indicavam `audited` e o código existe: domínio `stores`/
`planos`/`botões`/`mensagens` + monorepo `bots/`). O spec 49 real é
`49-planos-entregas` (`audited`); o dir `49-bots-venda-discord-telegram`
(`discarded`) é o original substituído, já documentado na nota da Fase 11.
**Também removidos 2 headers duplicados** da tabela (linhas 145–146) que
haviam sido inseridos numa edição anterior — a tabela agora tem 1 header +
separador.

## Fase 12 — Robustez da execução de jobs (decisão 2026-08-07)

Os 6 jobs do backend (faturas, cobrança/receivables, risco, reconciliação do
wallet, dispatcher de outbox/notificações — um único scheduler cobre os dois —
e reconciliação de saques) rodam in-process com
`@Scheduled` + ShedLock, mas hoje: (1) **pool único default** (1 thread) — cron
longo bloqueia o outbox (30s) e vice-versa; (2) **zero métricas** de
duração/resultado (Micrometer no classpath sem uso); (3) **falha de job é
silêncio operacional** (só ERROR solto no log, sem trilha); (4) sem tabela de
execuções. Esta fase entrega a infraestrutura de execução robusta: **pool de
threads**, **métricas por job**, tabela **`job_runs`** (trilha auditável) e
**alerta de falha via outbox** (evento `JOB_FAILED` → webhook de operador).
**Worker separado fica adiado** (spec futura, depende de escala real) — o outbox
já é o "fio" que permite separar o processamento de eventos sem tocar no domínio.

| # | Name | Status | References |
|---|---|---|---|
| 53 | Robustez da execução de jobs — pool de threads (`nyxcash.scheduler.pool-size`), métricas Micrometer por job (`nyx_job_*`), tabela `job_runs` (trilha auditável + retenção) e alerta de falha via outbox (`JOB_FAILED` → webhook de operador, reusando Spec 35) | audited | [requirements](specs/53-job-robustez/requirements.md) · [design](specs/53-job-robustez/design.md) · [task](specs/53-job-robustez/task.md) |
| 54 | Alerta de evento de outbox esgotado (`OUTBOX_DEAD`) — entrega de webhook de saída (Spec 35) que esgota as tentativas (max=5 → `FAILED`) publica um `OUTBOX_DEAD` (plataforma, sem merchant) NA MESMA transação → webhook de operador (infra da Spec 53); anti-cascata (OUTBOX_DEAD morto não gera outro); sem DLQ física (FAILED fica na tabela como trilha; o alerta é o mecanismo) | audited | [requirements](specs/54-outbox-dead-alert/requirements.md) · [design](specs/54-outbox-dead-alert/design.md) · [task](specs/54-outbox-dead-alert/task.md) |

## Fase 13 — Observabilidade de produção: health, compression e tracing (auditoria 2026-08-07)

Pendências encontradas na verificação de produção (Railway) em 2026-08-07 — a
verificação de observabilidade apontou 3 lacunas: (1) **health global DOWN**
por causa do indicador de e-mail (`MailHealthIndicator` tenta `localhost:587`
sem SMTP configurado → o health público responde 503, embora
liveness/readiness fiquem UP); (2) **compression ausente** (nenhuma resposta
com `content-encoding: gzip` mesmo com `Accept-Encoding: gzip` — JSON de
produção trafega sem compressão); (3) **tracing ausente** (só métricas
Prometheus; sem spans distribuídos). As 3 specs corrigem as lacunas
registradas na auditoria de produção.

| # | Name | Status | References |
|---|---|---|---|
| 55 | Health sem falso-negativo — indicador de mail desabilitado por default (`management.health.mail.enabled: ${NYX_MAIL_HEALTH_ENABLED:false}`): SMTP é infra opcional (Spec 45 — disponibilidade primeiro) e não deve derrubar o health agregado; liveness/readiness permanecem UP | audited | [requirements](specs/55-health-mail-indicator/requirements.md) · [design](specs/55-health-mail-indicator/design.md) · [task](specs/55-health-mail-indicator/task.md) |
| 56 | Compression de resposta — `server.compression.enabled=true` com mime-types JSON/XML/texto e tamanho mínimo 1KB: payloads de produção (storefront, vendas, relatórios) trafegam gzip | audited | [requirements](specs/56-response-compression/requirements.md) · [design](specs/56-response-compression/design.md) · [task](specs/56-response-compression/task.md) |
| 57 | Tracing distribuído — Micrometer Tracing (bridge OTel) + exporter OTLP: spans HTTP/JDBC/Redis com `traceId`/`spanId` em logs e métricas, sampling configurável (default 10%), endpoint via `NYX_OTLP_ENDPOINT`, sem derrubar a app sem collector | audited | [requirements](specs/57-distributed-tracing/requirements.md) · [design](specs/57-distributed-tracing/design.md) · [task](specs/57-distributed-tracing/task.md) |

## Fase 14 — Resiliência sob tráfego malicioso/pico (análise 2026-08-07)

Análise de resiliência (25 itens, auditoria 2026-08-07) identificou 2 CRÍTICOS
(sem rate limit por usuário/chave; webhook flood) e vários ALTOS (pool Hikari
10 vs 200 threads, memória sem teto, conexões sem tuning, endpoints caros sem
limite, log spam em flood). As 3 specs corrigem por camada:

- **Spec 58** — rate limit **por usuário/chave** (o gap central): filtro por
  principal autenticado em rotas transacionais, cap de cobranças pendentes por
  lojista, rate limit por token em admin/master, cobertura ampliada por IP em
  rotas autenticadas.
- **Spec 59** — **webhook flood**: rate limit por IP em `/webhooks/*` +
  throttle de log de assinatura inválida (anti-spam operacional).
- **Spec 60** — **infra**: tuning explícito de Tomcat (conexões/timeouts),
  pool Hikari dimensionado, teto de heap JVM coerente com a RAM do Railway.

| # | Name | Status | References |
|---|---|---|---|
| 58 | Rate limit por usuário/chave e cobertura ampliada — filtro por principal autenticado (API key/token) em rotas transacionais, cap de cobranças pendentes por lojista (409), rate limit por token em admin/master, cobertura por IP ampliada (**benchmark Railway 2026-08-07: teto 120/min com precisão cirúrgica até 5.000 VUs, 0 5xx, gargalo real = conexão ~1.000 simultâneas**) | audited | [requirements](specs/58-rate-limit-autenticado/requirements.md) |
| 59 | Webhook flood — rate limit por IP em `/webhooks/pix` e `/webhooks/payouts` (teto generoso, sem quebrar reentrega do PSP) + throttle de log de assinatura inválida (**benchmark Railway 2026-08-08: flood 300 → 120×401 + 180×429 agregado via Redis, 0 5xx, p95 aceitos 522ms**) | audited | [requirements](specs/59-webhook-rate-limit/requirements.md) · [design](specs/59-webhook-rate-limit/design.md) · [task](specs/59-webhook-rate-limit/task.md) |
| 60 | Hardening de infraestrutura — Tomcat (max-connections/accept-count/connection-timeout/keep-alive), pool Hikari dimensionado, teto de heap JVM (JAVA_OPTS) coerente com a RAM do Railway (**benchmark Railway 2026-08-08: 50→5000 VUs, 0 5xx, ponto de inflexão p95 ~500 VUs, gargalo de conexão ~1000 simultâneas, pool 20 zero timeouts, JAVA_OPTS -Xmx320m**) | audited | [requirements](specs/60-infra-hardening/requirements.md) · [task](specs/60-infra-hardening/task.md) |

## Fase 15 — Escalabilidade horizontal e alta disponibilidade (decisão do usuário 2026-08-08)

Auditoria de distributibilidade (2026-08-08): o backend **já é majoritariamente
stateless** — sessões DB-backed (`master_sessions`/`merchant_sessions`, token
opaco 8h, `SessionCreationPolicy.STATELESS`), scheduler com ShedLock no
Postgres (Spec 6/53), idempotência com locks pessimistas em banco (Spec 28) e
rate limit distribuído disponível via Redis (Spec 43). **Bloqueios para N
instâncias**: (a) rate limit default `memory` (contador por instância — quebra
com réplicas; solução: `NYX_RATE_LIMIT_STORE=redis`); (b) deploy
single-instance sem réplicas/healthcheck/graceful shutdown (railway.json sem
`replicas`/`healthcheckPath`; sem `server.shutdown=graceful`). Escopo completo
(app + deploy + verificação): statelessness garantido (shutdown graceful,
readiness/liveness p/ orquestração, Redis no rate limit), réplicas Railway +
healthcheck + zero-downtime, e verificação com 2 instâncias (docker compose
local e/ou Railway) + runbook de HA.

| # | Name | Status | References |
|---|---|---|---|
| 61 | Escalabilidade horizontal e alta disponibilidade — app stateless p/ N instâncias (rate limit Redis, shutdown graceful, readiness/liveness, auditoria de estado em memória) + deploy Railway multi-instância (réplicas, healthcheck, zero-downtime) + verificação com 2 instâncias e runbook de HA | audited | [requirements](specs/61-horizontal-scalability-ha/requirements.md) · [design](specs/61-horizontal-scalability-ha/design.md) · [task](specs/61-horizontal-scalability-ha/task.md) |

## Fase 16 — Capacity Engineering & High-Throughput Transaction Processing (decisão do usuário 2026-08-08)

**Agendamento:** `/spec-verify` concluído em 2026-08-08 (commit `357d88f`,
status `verified`). **Implementação (`/spec-implement`, F0 em diante) agendada
para 2026-08-09** (decisão do usuário).

A Spec 61 entregou HA funcionando (stateless, réplicas, zero-downtime). Esta fase
transforma o TrevoPay de "HA funcionando" em arquitetura com **capacidade mensurável**
e caminho de escala para **≥ 5.000 vendas/s sustentadas** e **1M+ usuários**, sem
sacrificar integridade financeira. Princípio: **Medir → localizar gargalo → corrigir
→ medir novamente → provar integridade → escalar.** Deve terminar com um **número real
de capacidade** (ex.: "TrevoPay Capacity v1: 6.240 vendas/s sustentadas, p95 180 ms,
0,00% 5xx, 0 duplicações, 0 perda de ledger, 0 perda de outbox, com 8 réplicas").

Escopo: (1) **capacity model** (1 venda = quantas reqs/queries/writes/Redis/outbox —
5.000 vendas/s × ~8 operações = ~40.000 operações/s muda o dimensionamento); (2)
**PostgreSQL como alvo principal** (queries/writes por venda, N+1, índices, locks,
duração de transações, deadlocks, pool, pg_stat_statements, slow queries, WAL/IOPs/CPU/
cache hit ratio, conexões); (3) **PgBouncer** (pooling — mais conexões pode piorar
throughput por contenção); (4) **separação leitura/escrita** com réplicas de leitura,
mantendo ledger/finanças no caminho consistente de escrita; (5) **ledger/wallet como
"zona sagrada"** — provar `wallet_balance == SUM(ledger_entries)` durante e depois de
carga pesada + idempotência sob throughput alto (10.000 reqs/mesma key → 1 transação);
(6) **Redis como infraestrutura de escala** (latência, throughput, conexões, pool,
memória, eviction, disponibilidade, failover, comportamento com Redis down, atomicidade
do rate limit; Redis Cluster se benchmark demonstrar necessidade); (7) **outbox/caminho
assíncrono** — caminho crítico curto (REQUEST → validate → idempotency → DB transaction →
ledger → outbox → COMMIT → RESPONSE) e workers secundários (webhook/bot/notification/
delivery/reconciliation) fora do caminho crítico; (8) **load test por estágio de
throughput** (100 → 250 → 500 → 1.000 → 2.000 → 3.000 → 4.000 → 5.000 → 6.000 → 8.000 →
10.000 vendas/s), cada estágio medindo throughput, vendas/s, p50/p95/p99, 4xx/5xx, DB
CPU/conexões, Hikari active/pending, Redis latency/ops/s, JVM heap/GC, CPU API, WAL,
lock contention, deadlocks, ledger reconciliation, duplicações, outbox backlog; (9)
**teste de explosão** (5k → 7.5k → 10k → 15k → 20k até quebrar — quebra bonita com 429/
backpressure, nunca Postgres/pool/JVM mortos ou saldo inconsistente); (10) **scale-out
linear** (2 → 4 → 8 → 16 instâncias; se 2→1.500/s mas 4→1.700/s, há gargalo central a
encontrar); (11) **banco não pode ser o gargalo escondido** — particionamento/
estratégias PostgreSQL avaliadas com evidência, sem trocar de banco prematuramente.

**Não faz parte da Spec 62:** trocar PostgreSQL por outro banco sem evidência;
microservices por moda; aumentar threads/connection pool arbitrariamente; remover rate
limiting; sacrificar consistência financeira por throughput.

| # | Name | Status | References |
|---|---|---|---|
| 62 | Capacity Engineering & High-Throughput Transaction Processing — capacity model (1 venda = ~8 operações), PostgreSQL como alvo principal (queries/writes por venda, índices, locks, deadlocks, pool, pg_stat_statements, slow queries), PgBouncer e réplicas de leitura **somente com evidência** (regra de ouro: não alterar arquitetura sem provar o gargalo), ledger/wallet como "zona sagrada" (`wallet_balance == SUM(ledger)` sob carga + idempotência em throughput alto), Redis como infraestrutura de escala, outbox com caminho crítico curto, load test por estágio de throughput (100→10.000 vendas/s com rate limit elevado para não mascarar o teto real), teste de explosão com quebra bonita (429/backpressure), scale-out linear (2→16 instâncias), terminando com relatório **reproduzível** com número real de capacidade (**TrevoPay Capacity v1 `audited` 2026-08-10: ~1.246 vendas/s sustentadas com 4 réplicas / teto ~1.554/s com 8 (Postgres max 500 é o gargalo central do scale-out), p95 charge ~120ms (600/s) após Spec 65, 0% 5xx, 0 duplicações, 0 perda de ledger/outbox — meta ≥5.000 não atingida, número medido reportado** — [capacity-report](docs/capacity-report.md)) | audited | [requirements](specs/62-capacity-engineering-high-throughput/requirements.md) · [design](specs/62-capacity-engineering-high-throughput/design.md) · [task](specs/62-capacity-engineering-high-throughput/task.md) · [capacity-model](docs/capacity-model.md) |

## Fase 17 — Segurança de infraestrutura e operação (diretriz do usuário 2026-08-09)

O código já é hardening (3 auditorias + specs 18/42/58/59/60), mas "segurança de
nível de mercado" exige elevar **infraestrutura e operação**: proteção de borda
(WAF/DDoS/bot), observabilidade de segurança (alerting sobre o que já é medido),
gestão de segredos profissional e operação preparada para ataques reais. As 2
specs atacam o topo da prioridade com **o mínimo de infra nova** (reuso do
edge/webhook/métricas existentes): **63** coloca o **Cloudflare na frente**
(WAF + DDoS + edge rate limit + Turnstile no portal — ADR-014) e **64** transforma
os indicadores existentes em **alerta acionável** (webhook de operador + métricas)
e entrega o **runbook de resposta a incidente** (ADR-015). Gestão de segredos
(Doppler) e autenticação adaptativa (step-up) ficam como **propostas futuras**
(depois destas, com evidência de necessidade). **`/spec-verify` (2026-08-09):
2/2 specs `verified`** — 1 achado corrigido na Spec 63 (exceção de benchmark:
com Turnstile ativo no `POST /api/v1/merchants`, o loadgen da Spec 62 exigiria
`NYX_TURNSTILE_ENABLED=false` ou exceção de IP — procedimento documentado no
design/runbook). Prontas para `/spec-implement`.

| # | Name | Status | References |
|---|---|---|---|
| 63 | Edge/WAF e bot mitigation — Cloudflare na frente do Railway (DDoS gerenciado, WAF ruleset, edge rate limiting por IP/rota, Turnstile no login/cadastro do portal com validação server-side, TLS Full-strict, `trust-proxy` validado, verificação com ataques simulados e rollback por DNS) (**`audited` 2026-08-10 — `/spec-audit` 10/10 critérios: 8 verificados ao vivo/evidência + 2 deferimentos documentados: critério 6 (webhook com assinatura REAL do PSP) fecha na Fase 2 Pagar.me — edge já validado: webhook passa o WAF e a app responde 401 em HMAC inválido; critério 9 (rollback proxy OFF) fecha na Spec 66 Fase A (custom domain do Railway) — ver nota do achado abaixo; `mvn test` 1159/0/0**) | audited | [requirements](specs/63-edge-waf-bot-mitigation/requirements.md) · [design](specs/63-edge-waf-bot-mitigation/design.md) · [task](specs/63-edge-waf-bot-mitigation/task.md) · [ADR-014](adr.md#adr-014-edgewaf-na-frente-do-railway--cloudflare-como-proxy-spec-63) |
| 64 | Observabilidade de segurança e resposta a incidentes — eventos de segurança no webhook de operador (burst 401/403, 429, falha de assinatura, fail-open do rate limit) + alerting opcional via Grafana Cloud nas métricas existentes + runbook de resposta a incidente (detectar→conter→mitigar→restaurar, reusando backup/HA/redeploy) + retenção/busca de logs (**`audited` 2026-08-10 — `/spec-audit` 9/9 critérios OK, `mvn test` 1159/0/0**) | audited | [requirements](specs/64-sec-observability-incident-response/requirements.md) · [design](specs/64-sec-observability-incident-response/design.md) · [task](specs/64-sec-observability-incident-response/task.md) · [ADR-015](adr.md#adr-015-observabilidade-de-segurança--alerting-via-operador-webhook--métricas-spec-64) |

- **Spec 63 (`audited`, 2026-08-10):** parte externa (Cloudflare) aplicada e
  verificada ao vivo. **Domínio**: `api.foxcash.example.com` (zona `foxcash.example.com` ativa
  no Cloudflare; CNAME → `nyxcash-production.up.railway.app`, proxy ON; o
  `api.foxcash.example.com` do design **não existe** — usado o domínio real do usuário).
  **SSL**: Full (strict) aplicado. **WAF custom**: 4 regras (exceção webhook PSP
  com `skip`, SQLi/XSS consolidado, path traversal, content-type em auth) —
  limites Free: 5 regras max, sem `matches`/`body.raw` (Business/WAF Advanced);
  regra "body > 16 KB" do design **não aplicável no Free** (campo inexistente).
  **Edge rate limit**: 1 regra consolidada (50 req/10s/IP em merchants/auth/cob/
  charges; webhooks fora da regra) — plano Free = 1 regra, período só 10s; tetos
  por rota exigem Pro. **Bot Fight Mode**: ON (modo verificação no Free). 
  **Turnstile**: widget "TrevoPay" (sitekey `<TURNSTILE_SITE_KEY>`) ativo em
  produção — `NYX_TURNSTILE_ENABLED=true` + keys reais no Railway (deploy feito);
  login sem token → **403 `turnstile_failed`** (antes 401); secret validada via
  siteverify. **Verificação ao vivo**: baseline 200/cf-ray ✅; SQLi em query → 403
  ✅ (no body → 401, app rejeita — edge Free não inspeciona body); content-type
  errado → 403 ✅; path traversal → 403 ✅; flood 30/s → 429 na borda ✅; contrato
  JSON preservado ✅. **Camada app (complemento ao edge)**: `RequestBodyInspectionFilter`
  — inspeção do RAW body de POST/PUT/PATCH **antes** da auth (403
  `body_inspection_failed` em SQLi/XSS/path traversal; padrões calibrados por
  combinação; webhooks do PSP excluídos) + alerta `BODY_INSPECTION_BLOCKED` no
  outbox (Spec 64; threshold 5/60s) — cobre o gap do WAF Free que não inspeciona
  body. Config: `nyxcash.security.body-inspection.*` (default ON, desligável por
  `NYX_BODY_INSPECTION_ENABLED=false`); testes unitários 12 (filtro) + 1
  (publisher). **Achado de rollback (critério 9)**: proxy OFF faz o DNS
  resolver para o Railway, mas o **HTTPS direto falha** (handshake reset — o
  Railway não serve cert de `api.foxcash.example.com` no IP do domínio default);
  rollback completo exige registrar o custom domain no Railway (ou manter proxy
  ON). Runbook atualizado: `deploy/edge.md`. **`/spec-audit` (2026-08-10):
  10/10 critérios — 8 verificados ao vivo/evidência; 2 deferimentos
  documentados**: critério 6 (webhook PSP com assinatura REAL) → fecha na
  Fase 2 Pagar.me (edge já validado ao vivo: POST /webhooks/pix passa o WAF e a
  app responde 401 em HMAC inválido — Bot Fight Mode bloqueando UA de bot
  confirmado, 403 code 1010); critério 9 (rollback proxy OFF) → **fechado pela
  Spec 66 Fase A em 2026-08-10** (CNAME → endpoint de custom domain + TXT
  `_railway-verify` + flip proxy OFF → HTTPS direto 200 validado → proxy ON
  restaurado — ver Spec 66). `mvn test` 1159/0/0. Status `audited`.


- **Spec 64 (`audited`, 2026-08-10):** observabilidade de segurança e resposta
  a incidentes — eventos de segurança no webhook de operador + runbook.
  **`WebhookEventType` +4 tipos** (`LOGIN_FAILURE_BURST`, `RATE_LIMIT_BURST`,
  `SIGNATURE_FAILURE_BURST`, `RATE_LIMIT_FAIL_OPEN` — eventos de PLATAFORMA, sem
  merchant); **`SecurityAlertProperties`** (`nyxcash.security.alert.*`: limiares e
  janelas por tipo — login 10/60s, 429 20/60s, assinatura 20/60s — + throttle
  global 300s, env `NYX_SECURITY_ALERT_*`); **`SecurityAlertPublisher`**
  (contador de rajada por (tipo, contexto) com janela em memória + throttle
  `AtomicReference`+`Clock` padrão Spec 59 + publicação no outbox com
  `publish(null, type, data)` em transação **PRÓPRIA `REQUIRES_NEW`** — o alerta
  nunca participa da transação da venda/login — e **fail-safe**: falha de
  publicação é capturada (WARN), nunca derruba o caminho crítico; payload
  mascarado — só source/group/path/reason, nunca IP/senha/token/segredo/email/
  documento). **Contadores nos 4 pontos de disparo**:
  `RateLimitFilter`/`AuthenticatedRateLimitFilter` (429 por grupo),
  `WebhookSignatureFilter` (429 no webhook + HMAC inválido), `RedisRateLimitStore`
  (fail-open, 1 evento ao ativar), `MasterAuthService`/`MerchantPortalAuthService`
  (falhas de login — e-mail inexistente, sem credencial, lockout, senha errada).
  **Entrega**: mesmo pipeline do `JOB_FAILED`/`OUTBOX_DEAD` (Specs 53/54) —
  `HttpMerchantNotifier.deliverToOperator` com HMAC-SHA256; sem operator webhook
  configurado → **fallback log** (fail-safe). **Runbook `ops/security-incident.md`**
  (detectar→conter→mitigar→restaurar para as 5 classes — flood/DoS,
  automação/brute-force, webhook replay/spoof, chave vazada, abuso de lojista —
  reusando backup/restore + HA + edge Spec 63 + trilha Spec 41) com **teste de
  mesa** (flood + chave vazada) e **retenção/busca de logs** (janela Railway +
  `X-Request-Id` → `platform_activity_log` → métricas + opção de log drain
  Axiom/Loki). Fase C (Grafana Cloud) **opcional documentada** (scrape autenticado
  read-only + regras 5xx/readiness/Hikari/heap/outbox). **Verificação**: `mvn
  test` **1139/0/0** (149 classes; `SecurityAlertPublisherTest` 8/8 — limiar,
  isolamento por fonte, throttle, fail-open, desligável, fail-safe) e **smoke ao
  vivo (dev)**: rajada de 13 logins inválidos no portal → **1 evento
  `LOGIN_FAILURE_BURST`** no outbox (`merchant_id` NULL, payload `{"source":
  "merchant"}` mascarado, criado 01:47:34Z → **SENT** 01:47:44Z via fallback log;
  throttle — rajada repetida na janela não duplica), 13×401 uniforme sem 429/500
  (o alerta não degrada o login). **E2E 105/105** (suíte completa, app dev com
  `NYXCASH_SECURITY_RATE_LIMIT_ENABLED=false` — mesmo padrão documentado do job
  `load` do CI: o rate limit de login 20/min/IP é proteção de abuso, não alvo da
  suíte de contratos; com rate limit ativo a suíte local rápida esbarra no 429 do
  login — flakiness local documentada e pré-existente, **auth 24/24 e security
  31/31 passam de qualquer forma**, e rajada abaixo do limiar não gera
  `RATE_LIMIT_BURST` — anti-spam correto). Fim do ciclo SDD — **`/spec-audit`
  2026-08-10: 9/9 critérios OK, 0 achados** (verificação em código dos 4
  contadores + runbook + `mvn test` 1159/0/0; smoke ao vivo do
  `LOGIN_FAILURE_BURST` documentado na implementação; Grafana Cloud opcional —
  critério 6 N/A sem uso). Status `audited`.

## Fase 18 — Otimização de desempenho pós-capacity (decisão do usuário 2026-08-09)

Derivada dos achados **medidos** da Spec 62 (F7/Fase 2B + `pg_stat_statements`):
a análise pós-benchmark concluiu que o teto real (~480 vendas/s com 2 réplicas)
é de **recurso compartilhado** (2→3 geradores não subiram o throughput), com dois
gargalos nomeados: **(a)** o caminho do charge gasta 5-6 queries redundantes de
autenticação por venda (`update merchant_api_keys` 41ms de média, `select
merchants` 23ms — top-2 do `pg_stat_statements`; achado F1 do `capacity-model`),
e **(b)** a drenagem do outbox é ~20-33 eventos/s (batch 200 / poll 10s — Specs
53). A Spec 65 ataca os dois com **evidência antes/depois** (re-benchmark dos
estágios 600/750 da F7), sem re-arquitetura (sem PgBouncer/réplicas — ADR-013;
sem DLQ/worker externo no outbox) e sem sacrificar integridade financeira nem a
semântica de auth (401 imediato na revogação).

| # | Name | Status | References |
|---|---|---|---|
| 65 | Otimização do caminho do charge (auth) e drenagem do outbox — consolidação das queries de autenticação (1-2 por request) + cache curto-TTL da resolução de API key com invalidação na revogação/regeneração e status do lojista sempre re-lido (segurança) + throttle do `last_used_at` (hot spot do `pg_stat_statements`) + tuning do dispatcher do outbox (batch/poll configuráveis, múltiplos lotes por lock) + re-benchmark 600/750 com comparação antes/depois (vendas/s, p95 charge, queries/s) (**`audited` 2026-08-10: auth consolidado + throttle + outbox com loop de lotes; re-benchmark 600/s 0 5xx p95 ~120ms (antes ~5,5s), drenagem outbox ~135-140/s (antes ~20-33/s); Fase 2 cache DISPENSADA por evidência; `/spec-audit` 7/7 critérios OK, `mvn test` 1159/0/0 — [capacity-report §11.11](docs/capacity-report.md)**) | audited | [requirements](specs/65-charge-path-outbox-optimization/requirements.md) · [design](specs/65-charge-path-outbox-optimization/design.md) · [task](specs/65-charge-path-outbox-optimization/task.md) |

**`/spec-verify` (2026-08-09): Spec 65 `verified`** — 1 achado corrigido: o
inventário de estado em memória (Spec 61) agora cobre também o mapa do throttle
do `recordUse` (Fase 1), não só o cache (Fase 2) — ambos per-instância, TTL e
tamanho máx, com o comportamento ×N instâncias documentado no design. Pronta
para `/spec-implement`.

**`/spec-implement` (2026-08-09): Spec 65 `in-progress`** — Fases 1 e 3
implementadas no código (main): auth consolidado (filtro reusa o `Merchant` no
request — `AuthenticatedMerchantResolver`; -1 SELECT/venda) + throttle do
`last_used_at` (`nyxcash.security.api-key.record-use-interval`, default 60s —
~1 UPDATE/min/chave) + outbox com knobs meio-termo (batch default 500 / teto 500,
poll default PT10S, `max-batches-per-run` 10) e loop de lotes por lock
(`NotificationDispatcherService`). `mvn test` verde (suíte completa). **Fase 2
(cache curto-TTL) GATED**: só com evidência do re-benchmark pós-Fase 1.
**Fase 4 (prova) CONCLUÍDA (2026-08-10)**: campanha 62 F8/F9 + 65 F4 (600/s e
750/s) executada e documentada em `docs/capacity-report.md` §11.11 (65 F4:
600/s p95 ~119-133ms, 750/s ~744/s sustentado; 62 F8: explosão 5k quebra
bonita; 62 F9: scale-out ~1.246/s em 4 réplicas). Spec 62 → `audited`
(TrevoPay Capacity v1), Spec 65 → `implemented` (Fase 2 cache dispensada por
evidência). Fechamento formal registrado em 2026-08-16.

**Interrupção por desligamento + restauração (2026-08-09, commit `d5d4865`):**
a campanha consolidada (62 F8/F9 + 65 F4) foi **iniciada e interrompida** por
desligamento inesperado do computador (rate limit elevado + HTTPS relaxado já
aplicados — passo 1 do runbook). **Segurança restaurada imediatamente**: rate
limit 20/120/300/120 + `require-https=true`, verificado ao vivo (429 após 20
requests anônimos; health UP 2/2; outbox drenado 127.858 SENT / 0 PENDING;
zona sagrada 0/0/0/0). Loadgen parado (0 réplicas). **Bloqueio para retomar a
campanha:** probe de conectividade da rede privada falhou
(`nyxcash.railway.internal:8080` → timeout; loadgen em `sfo` vs TrevoPay em
`us-west2` — investigar região do private network no Railway). Retomar em nova
sessão a partir do runbook passo 2 (`load-tests/k6/distributed-runbook.md`),
usando o `probe.js` (novo) para re-validar a conectividade **antes** de elevar
o rate limit de novo.

**Campanha concluída (2026-08-10):** a campanha consolidada (62 F8/F9 + 65 F4)
foi **retomada e concluída** — o bloqueio de conectividade (região do loadgen)
foi resolvido na sessão (loadgen re-deployado em us-west2, rede privada
funcionando). Resultados medidos (regra de ouro): **65 F4** — 600/s sustentado
0 5xx p95 charge ~119-133ms (antes ~5,5s), 750/s ~744/s p95 ~925-1.035ms;
**62 F8** — explosão 5k/s com rate limit ATIVO: quebra bonita (~100% 429, 0,00%
5xx, integridade intacta); **62 F9** — scale-out 2->600/s · 4->~1.246/s (linear)
· 8->~1.554/s (não linear: **Postgres max_connections=500 é o gargalo central**)
· 16->degrada (sale_ok 94-97%, p95 ~10-19s). **Restauração obrigatória
aplicada**: rate limit 20/120/300/120 + HTTPS true (429 verificado) + app 2/2
réplicas + **serviço loadgen deletado**. Gate final **0/0/0/0** (zona sagrada
intacta em todos os estágios), outbox 0 FAILED drenando ~135-140/s. `mvn test`
**1130/0/0**. Spec 62 -> `audited` (TrevoPay Capacity v1: ~1.246 vendas/s com 4
réplicas — meta ≥5.000 não atingida, número medido reportado); Spec 65 ->
`implemented` (Fase 2 cache dispensada por evidência). Evidência completa em
`docs/capacity-report.md` §11.11, `load-tests/k6/results/fase3-*`,
`fase4-f8-*`, `fase4-f9-*`. **Próximo bloco**: Spec 64 e Spec 63.

**`/spec-audit 62` (2026-08-16): Spec 62 `audited` — fechamento formal** —
11/11 critérios de aceite OK com evidência no `docs/capacity-report.md` v1:
(1) capacity model documentado (1 venda = ~8 operações); (2) PostgreSQL como
alvo principal com queries/writes/locks/índices medidos (postgresql-analysis.md
+ §4); (3) PgBouncer DISPENSADO por evidência (task 7, ADR-013 — pool 20 sem
contenção, acquire 0,6ms; F9: gargalo = max_connections=500 do Postgres, não
pooling); (4) réplicas de leitura DISPENSADAS por evidência (task 11, ADR-013);
(5) zona sagrada 0/0/0/0 em TODOS os estágios + idempotência 10k → 1 transação
(task 16); (6) Redis medido sob carga, fail-open confirmado (task 18), single
mantido por evidência (task 20); (7) outbox fora do caminho crítico, drenagem
~135-140/s pós-Spec 65, 0 FAILED (tasks 21-22); (8) load test por estágio com
rate limit elevado (tasks 23-26, §2); (9) explosão 5k com quebra bonita ~100%
429, 0,00% 5xx (task 27); (10) scale-out 2→4→8→16 com gargalo central
identificado (task 28); (11) relatório v1 reproduzível com número medido
(~1.246 vendas/s em 4 réplicas, meta ≥5.000 não atingida — reportado o medido)
(task 30). `mvn test` 1995/0/0 re-executado 2026-08-16 (task 31). Status do
requirements.md/task.md/roadmap alinhados. Fim do ciclo SDD.

## Fase 19 — Resiliência operacional pós-análise (2026-08-10)

Análise técnica completa de todos os subsistemas consolidada em
`docs/analise-tecnica-2026-08-10.md` (guardada como referência) com riscos
transformados em tarefas imediatas e notas por subsistema como roteiro de
melhorias (`docs/roteiro-melhorias-2026-08-10.md`). As **recomendações** da
análise viram a **Spec 66**: fechar o rollback do edge (achado do critério 9 da
63), agregar os alertas de segurança entre réplicas (hoje por-instância) e dar
monitoramento/calibração proativos ao body inspection (métrica + runbook).

| # | Name | Status | References |
|---|---|---|---|
| 66 | Resiliência operacional pós-análise — rollback de edge completo (custom domain do Railway como origem; fecha critério 9 da Spec 63) + alertas de segurança agregados entre réplicas (contadores de rajada via Redis, fail-open) + monitoramento proativo do body inspection (métrica `nyx.body_inspection.blocks`, runbook de calibração, cron do monitor) (**`implemented` 2026-08-10: Fases A (rollback), B (porta `AlertCounterStore` + `InMemoryAlertCounterStore` + `RedisAlertCounterStore` script Lua fail-open + `SecurityAlertPublisher` via porta + `NYX_SECURITY_ALERT_STORE`) e C (métrica `nyx.body_inspection.blocks` + runbook §7.6 + cron) concluídas — `mvn test` **1169/0/0** (+10 testes); Fase A **VALIDADA 2026-08-10**: token escopado válido → CNAME `api` → `custom-api.up.railway.app` (endpoint de custom domain que o Railway instrui) + TXT `_railway-verify.api` criado (`Verified: yes`) → flip proxy OFF → Railway emitiu o cert (`CN=api.foxcash.example.com`, Let's Encrypt, `VALID`) → **HTTPS direto 200** sem `CF-Ray` (rollback real OK) → proxy ON restaurado (200 via Cloudflare) — procedimento em `deploy/edge.md` §Rollback; **`/spec-audit` (2026-08-11) — 5/5 critérios OK, 0 achados abertos; 2 achados corrigidos: (1) CRÍTICO — chave `alert:` duplicada no `application.yml` (a Fase B `caa8b7d` inseriu um 2º bloco `alert:` colidindo com o do Spec 64) → `DuplicateKeyException` no boot (a app não subia); blocos fundidos (`store` dentro do bloco `alert:` existente); (2) MÉDIO — `security.spec.js` (SQLi-body/XXE, pré-data o body inspection da Spec 63) atualizado para aceitar o **403 `body_inspection_failed`**; `mvn test` 1169/0/0 + E2E 105/105 + métrica e fail-open do Redis validados ao vivo**) | audited | [requirements](specs/66-resiliencia-operacional/requirements.md) · [design](specs/66-resiliencia-operacional/design.md) · [task](specs/66-resiliencia-operacional/task.md) · Visão V46 |

## Fase 20 — Operação por chat: Operator Bot no Discord (2026-08-10)

O operador já recebe **alertas de segurança** no Discord (Spec 66/R3). Para
**consultar** (saúde, negócio, auditoria) sem abrir Railway/terminal, o usuário
pediu um **bot de comando e controle** no canal do operador (5 grupos: saúde,
métricas de negócio, segurança/auditoria, alertas agendados diário/semanal/
anomalia e ações leves). O bot consulta o banco (leitura), o Railway (status/
deploy/logs) e o Cloudflare (WAF — `!bloquear <ip>`, com o token escopado da
Spec 66/R1); `!notificar` fica para fase 2 (endpoint no backend).

| # | Name | Status | References |
|---|---|---|---|
| 67 | Operator Bot — comando e controle via Discord (`operator-bot/`): !saude/!conexoes/!outbox/!jobs/!latencia/!deploy · !vendas/!chargebacks/!lojistas/!receita/!top5 · !alertas/!bloqueios/!ip/!reconciliacao/!log · agendados daily/weekly/anomalia · !bloquear/!ratelimit · webhooks por categoria — 6 integrações Discord · **`!ip` = lookup de lojista por IP (Spec 67/Fase E `implemented` e validado em produção 2026-08-10, deploy `ce503a39`: endpoint `GET /api/v1/admin/lookup/ip/{ip}` com resumo de vendas + bot da loja, trilha `IP_LOOKUP`, V46, máscara no bot — smoke prod OK 217 matches/400/401)** (**`audited` 2026-08-10 — `/spec-audit` 7/7 critérios OK; 1 achado de doc corrigido (tabela de roteamento §6 → 7 categorias com `WEBHOOK_VENDEDORES` e anomalia estrita); fixes de produção do `!ip` nesta sessão: fallback p/ URL pública (rede interna ECONNREFUSED) + filtro por id + máscara `•••` — deploys `d41a14c8`/`bb54702d`; vitest 26/26, tsc limpo**) | audited | [requirements](specs/67-operator-bot/requirements.md) · [design](specs/67-operator-bot/design.md) · [task](specs/67-operator-bot/task.md) · Visão V47 |

**Ações imediatas — estado (2026-08-10, atualizado pós-Spec 66)**: R1 ✅ **FECHADO** (Roll da Global Key
feito pelo usuário; token escopado criado — ver roteiro); R3 ✅ **CONFIGURADO**
(operador webhook Discord via `NYX_NOTIFICATION_OPERATOR_WEBHOOK_URL`+`_SECRET`
+ formatador nativo no `HttpMerchantNotifier`, deploy `d2525272`; alertas
entregues sem erro); R4 ✅ **ENDEREÇADO** (Spec 66 Fase B — contadores Redis, fail-open);
R2 ✅ **FECHADO** (Spec 66 Fase A — rollback real validado 2026-08-10);
R6 ✅ **FECHADO** (`/spec-audit` das specs 63/64/65: `453ea45` 10/10, `60d4408` 9/9,
`e221266` 7/7). Pendente: R5 (agendar o `ops/monitor-body-inspection.sh` no cron —
Fase C já implementada). Checklist e detalhes no roteiro de melhorias.

- **Spec 66 (`audited`, 2026-08-11):** auditoria do ciclo SDD da resiliência
  operacional pós-análise (implementada em 2026-08-10). **`/spec-audit` — 5/5
  critérios de aceite OK, 0 achados abertos.** Verificações: **critério 1
  (rollback)** validado ao vivo (2026-08-10 — CNAME `api` →
  `custom-api.up.railway.app` + TXT `_railway-verify.api` → flip proxy OFF →
  HTTPS direto 200 sem `CF-Ray` → proxy ON restaurado; `deploy/edge.md`
  §Rollback); **critério 2 (agregação)** código verificado — porta
  `AlertCounterStore` + `InMemoryAlertCounterStore` (default) +
  `RedisAlertCounterStore` (script Lua atômico `INCR`+`EXPIRE`+`DEL` no
  disparo, limiar **global**, fail-open WARN throttled + fallback local) +
  `SecurityAlertPublisher` via porta + `nyxcash.security.alert.store`
  (`memory`|`redis`, env `NYX_SECURITY_ALERT_STORE`) — **fail-open validado AO
  VIVO**: instância com `store=redis` sem Redis → bloqueio 403 mantido + WARN
  `Alert counter Redis indisponivel` (o alerta nunca é perdido); **critério 3
  (métrica)** validada AO VIVO — POST com SQLi no body → **403
  `body_inspection_failed`** → `nyx_body_inspection_blocks_total{category=
  "sql_injection",pattern="union-select"} 1.0` no `/actuator/prometheus`
  (ROLE_ADMIN; 401 sem auth); **critério 4 (runbook)** `deploy/edge.md` §7.5/
  §7.6 com coleta de evidência (monitor `--json` + métrica + logs `X-Request-Id`),
  classificação ataque vs falso positivo, reversão imediata
  `NYX_BODY_INSPECTION_ENABLED=false` e cron do monitor (`*/15`); **critério 5**
  — `mvn test` **1169/0/0** (re-executado) + **E2E 105/105** (suíte completa,
  app dev com `NYXCASH_SECURITY_RATE_LIMIT_ENABLED=false` — padrão documentado).
  **2 achados CORRIGIDOS na auditoria: (1) CRÍTICO — `application.yml` com a
  chave `alert:` DUPLICADA** sob `nyxcash.security`: o commit `caa8b7d` (Fase B)
  inseriu um segundo bloco `alert:` (só com `store:`) colidindo com o bloco do
  Spec 64 → `DuplicateKeyException` no boot (**a app não subia** — local e
  qualquer deploy futuro; o CI e2e/flyway-coldstart teria falhado no push);
  `mvn test` **não pegou** porque não há `@SpringBootTest` que parseie o
  application.yml completo (gap de cobertura registrado em lessons L16);
  **corrigido fundindo os blocos** (`store: ${NYX_SECURITY_ALERT_STORE:memory}`
  movido para dentro do bloco `alert:` existente) — boot validado ao vivo
  (health UP) e perfis prod/railway sem duplicatas; **(2) MÉDIO — 2 testes E2E
  de segurança desatualizados** (`security.spec.js` — SQLi-body e XXE-JSON,
  criados em 2026-08-07, **pré-datavam o body inspection da Spec 63**):
  esperavam `[400,401,422]`/`[201,400,422]` mas o filtro (Spec 63, comportamento
  auditado) bloqueia com **403 `body_inspection_failed`** — falha latente desde
  `538eb25`, nunca vista porque o CI não rodou sobre esses commits (16 à frente
  do origin); corrigido adicionando 403 às listas esperadas (a garantia do teste
  — "4xx sem vazamento" — é mantida e até reforçada: o payload é barrado ANTES
  da auth). **Task 10 CONCLUÍDA AO VIVO (2026-08-16, deploy `94861317`)**:
  `NYX_SECURITY_ALERT_STORE=redis` ativado + smoke 6×403 → 1 alerta
  `BODY_INSPECTION_BLOCKED` (agregação Redis, 0 fail-open). **R5 pendente**
  (agendar o cron do `ops/monitor-body-inspection.sh` na máquina do operador —
  instruções Windows: Agendador de Tarefas, `schtasks /create /tn FoxBodyInspection
  /tr "bash /c /caminho/ops/monitor-body-inspection.sh" /sc minute /mo 15`).
  Fim do ciclo SDD (roadmap/requirements/task `audited`).

## Fase 21 — Testes de contexto (decisão do usuário 2026-08-11)

A auditoria da Spec 66 (2026-08-11) expôs uma lacuna de verificação medida: a
chave `alert:` duplicada no `application.yml` **quebrou o boot sem que `mvn
test` pegasse** — não há nenhum `@SpringBootTest`/teste de contexto que parseie
o `application.yml` completo (todos os 1169 testes são unitários com
mocks/slices); o bug só apareceria nos jobs de CI que sobem a app, que nunca
rodaram (commits à frente do origin). Além disso, a **seleção condicional** dos
stores (`alert.store`/`rate-limit.store` memory|redis) e o **binding** do
`SecurityAlertProperties` nunca são validados contra o yml real. Esta fase
entrega a infraestrutura de **testes de contexto**: (1) parse de TODOS os
`application*.yml` via o parser do Spring (pega chave duplicada/sintaxe no
`mvn test` local, sem banco), (2) seleção condicional de beans via
`ApplicationContextRunner` (memory/redis/default + valor inválido), (3) binding
de properties contra os defaults reais + overrides, e (4) um **smoke de contexto
completo** (`@Tag("context")`, fora do default — requer Postgres) que sobe a app
real e valida fiação/health (previne também a classe de bug do L10 — 2 beans
`Duration`). CI: o job `test` ganha Postgres e roda o grupo context.

| # | Name | Status | References |
|---|---|---|---|
| 68 | Testes de contexto — parse do YAML de config em TODOS os `application*.yml` (pega chave duplicada/sintaxe no `mvn test` local — regressão do caso L16), seleção condicional de beans (`alert.store`/`rate-limit.store` memory/redis/ausente→default/inválido→falha) e binding de properties contra o yml real; smoke de contexto completo (`@Tag("context")`, opt-in `-Pcontext-tests`, Postgres) (**`audited` 2026-08-11 — `/spec-audit` 6/6 critérios OK, 0 achados abertos; achados corrigidos: `TestYaml` migrado para `YamlPropertySourceLoader` — o loader REAL do boot preserva ints/bools (o `YamlPropertiesFactoryBean` descartava scalars não-String e o binding passava por default de código, não do yml), `TestYamlTest` criado (6/0/0), requirements critério 6 reescrito; `mvn test` default **1189/0/0** (+20 novos) + smoke **2/0/0** via `-Pcontext-tests`; CI: job `test` com Postgres + passo `-Pcontext-tests`**) | audited | [requirements](specs/68-context-tests/requirements.md) · [design](specs/68-context-tests/design.md) · [task](specs/68-context-tests/task.md) · Visão V48 |

**`/spec-verify` (2026-08-11): Spec 68 `verified`** — 1 achado corrigido: o smoke
de contexto (D5) agora **fixa** os stores (`nyxcash.security.alert.store=memory`/
`rate-limit.store=memory` via `@SpringBootTest(properties=...)`) para as
asserções de tipo serem determinísticas independente de env vars no runner
local/CI (a seleção condicional é responsabilidade da camada 2, não do smoke).
Referências validadas (V48, L10/L16, specs 43/64/66, `spring-boot-test` 3.3.5 no
classpath) e critérios ↔ design ↔ task com rastreabilidade 1:1. Pronta para
`/spec-implement`.

**`/spec-implement` (2026-08-11): Spec 68 `implemented`** — F0–F5 concluídas
(ver nota na row acima). Pronta para `/spec-audit`.

**`/spec-audit` (2026-08-11): Spec 68 `audited`** — 6/6 critérios OK, 0 achados
abertos (detalhe na row acima e no task.md §auditoria). **CI (1º run sobre os
commits recentes) — 1 achado corrigido: job `e2e` faltava o
`NYXCASH_SECURITY_RATE_LIMIT_ENABLED=false`** — a suíte de contratos (105
testes) estoura o teto de login (20/min/IP) e recebeu **429** em 3 testes
(merchant-journey 17, outbox-flow, portal-ui — o mesmo padrão de flakiness
documentado na Spec 64 para runs locais; o job `load` já usava a env). O 429
nunca é exigido pela suíte (só tolerado em brute-force/lockout, que continuam a
validar o lockout POR CONTA → 401). Flag adicionado ao job `e2e` (com comentário
explicativo) — validado localmente com a MESMA env: E2E 105/105. **Re-run do CI
confirmado VERDE (2026-08-11, conferência do usuário): todos os jobs passaram
(test + contexto, e2e 105/105, flyway×3, load).**

## Fase 22 — Autorização administrativa granular (RBAC + step-up MFA, 2026-08-11)

Gap identificado na discussão de arquitetura do frontend (2026-08-11) e
confirmado contra o código: a autorização master é **binária** — qualquer master
ativo (`ROLE_MASTER`) executa **todas** as escritas administrativas (suspender
lojista, bloquear saques, **saque administrativo**, **write-off de receivable**,
**cobrança manual de fatura**); só a gestão de perfis master tem guard extra
(`MasterAdminGuard` + `can_manage_masters`). Não há least-privilege entre papéis
e nenhuma ação sensível exige re-prova de posse (step-up MFA) na execução. A
revisão de segurança 2026-08-11 fechou integridade/autenticação (29 achados),
não autorização administrativa granular. Esta fase entrega: **(1) RBAC por ação**
— `master_permissions` (8 permissões), papéis (`ADMIN`/`OPERATOR`/`FINANCE`/
`AUDITOR`) como conveniência, guard por permissão **nos services** (protege HTTP
+ Operator Bot + futuro Master Console na mesma transação), deny-by-default; **(2)
step-up MFA** — as 4 ações sensíveis (saque administrativo, write-off, cobrança
manual, gestão de perfis) exigem TOTP válido numa janela de 15 min por sessão
(`stepped_up_at` em `master_sessions`), reutilizando `verifySecondFactor`
(anti-replay + recovery code + lockout), expresso com `403 { code:
"step_up_required" }` (não 401 — que derrubaria o cliente). Contrato HTTP das
rotas existentes **não muda** (camada de autorização) — não bloqueia o
`master-client` do OpenAPI futuro. **Ampliada em 2026-08-11 (15 critérios):**
também fecha o **bypass de cardinalidade do SEC-23 no lado master**
(`RateLimitKey.VARIABLE_PREFIXES` ganha `/api/v1/master` + `/api/v1/admin` —
N lojistas/recursos passam a gastar 1 bucket do limite por principal), rate
limit do `POST /api/v1/master/mfa/step-up` (superfície de verificação de
código), política de **migração dos masters não-admin existentes** (nascem sem
permissões — deny-by-default — com relatório de auditoria na migration), **E2E
dos fluxos master** (403 forbidden/step_up_required, janela expirada,
change-password mata step-up) e **default do Operator Bot** (restrito a ações
não sensíveis; suspend/release já auditam via `RISK_ADJUSTMENT` — auditoria
confirmada).

| # | Name | Status | References |
|---|---|---|---|
| 69 | Autorização do master em **2 níveis** + step-up MFA em ações sensíveis — catálogo `MasterPermission` (8 permissões) como contrato; concessão por nível (decisão do usuário 2026-08-11): **OPERATOR** (default, `can_manage_masters=false`) = bloco operacional completo (OPERATOR+FINANCE+AUDITOR juntos: suspend/release, block/release, administrative, write_off, collect), **ADMIN** (`can_manage_masters=true`) = bloco + `master.profiles.manage`; `MasterPermissionGuard` tier-based nos services (403 `forbidden`; deny-by-default só na função admin), **sem tabela `master_permissions`**; step-up TOTP nas 4 ações sensíveis (saque administrativo, write-off, cobrança manual, gestão de perfis) via `stepped_up_at` na sessão + `POST /api/v1/master/mfa/step-up` (reuso do `verifySecondFactor`: anti-replay + recovery code + lockout; janela 15 min), `403 { code: "step_up_required" }` (não 401), auditoria `MASTER_STEP_UP`, `change-password` limpa janelas de step-up (SEC-25), **fecha o bypass de cardinalidade do SEC-23 no master** (`VARIABLE_PREFIXES` ganha `/api/v1/master` + `/api/v1/admin`), **rate limit do step-up**, **masters não-admin preservam acesso** (sem migration de dados), **E2E dos fluxos master** e **default do Operator Bot** (não sensíveis) — contrato das rotas existentes inalterado (sem mudança de payload; `POST/GET profiles` como hoje) (**`audited` 2026-08-11 — `/spec-audit` 15/15 critérios OK, 0 achados de código; `mvn test` 1303/0/0 re-executado + E2E 110/110 re-executado com `NYXCASH_PORTAL_SERVE_FRONTEND=true`; ajuste de doc: design D3.2 com nomes reais das classes — `ReceivableWriteOffService`/`MerchantStatusControlService`**) | audited | [requirements](specs/69-rbac-master-stepup/requirements.md) · [design](specs/69-rbac-master-stepup/design.md) · [task](specs/69-rbac-master-stepup/task.md) · Visão V49 |

**`/spec-verify` (2026-08-11): Spec 69 `verified`** — 3 achados corrigidos: (1)
rastreabilidade D10 desalinhada (faltava a linha do critério 13 — migração
não-admin — e os rótulos 13/14 estavam trocados com E2E/Operator Bot; agora 15
critérios ↔ D ↔ F 1:1); (2) D7 referenciando "critério 4" quando a política de
migração é o **critério 13**; (3) ambiguidade no T3.1 — coluna `stepped_up_at`
fixada **na mesma V47 da F0** (aditivas, um ciclo flyway). Referência ao Spec 58
(rate limit autenticado) adicionada ao frontmatter (citada nos critérios
11/12). Demais referências validadas (V49, specs 13/37/41/44/67,
docs/revisao-seguranca-2026-08-11.md). Pronta para `/spec-implement`.

**Revisão do modelo (2026-08-11, decisão do usuário):** o RBAC granular (4 papéis
+ tabela `master_permissions`) foi substituído por **autorização em 2 níveis**
(OPERATOR com OPERATOR+FINANCE+AUDITOR juntos; ADMIN separado, só gestão de
perfis; step-up mantido). Requirements/design/task re-verificados e atualizados —
pronta para `/spec-implement` (F0 → F4). Nada da Spec 69 foi implementado —
apenas documentação (`verified`).

**Implementação adiada (decisão do usuário 2026-08-11):** documentação completa
(Spec 69 `verified` + ADR-016); `/spec-implement 69` fica agendado para depois.

**`/spec-implement` (2026-08-11): Spec 69 `implemented`** — F0→F4 concluídas:

- **F0 — Catálogo + migration**: `MasterPermission` (8 permissões, fonte única);
  `V47__master_step_up.sql` (aditiva única: `stepped_up_at TIMESTAMPTZ NULL` em
  `master_sessions`; **sem tabela `master_permissions`** — decisão do usuário
  2026-08-11). Flyway aplica limpa (validado ao vivo no boot local).
- **F1 — Guard + wiring + rate limit**: `MasterPermissionGuard` tier-based
  (`MasterLevel.of(master)`: 7 operacionais → master ativo; `master.profiles.manage`
  → `can_manage_masters`) nos 8 services de escrita (incl. `MerchantStatusControlService`
  = suspend/release); exceção + handler 403 `forbidden`; `VARIABLE_PREFIXES` ganha
  `/api/v1/master` + `/api/v1/admin` (SEC-23 fechado no master); `MasterAdminGuard`
  documentado como a implementação do nível ADMIN.
- **F2 — Nível + seed**: `MasterLevel` derivado (sem campo novo); seed mantém
  ADMIN; `POST/GET profiles` inalterados; masters não-admin preservam acesso.
- **F3 — Step-up MFA**: `stepped_up_at` no modelo/entity/porta/adapter;
  `POST /api/v1/master/mfa/step-up` (reuso `verifySecondFactor` — anti-replay +
  recovery + lockout; auditoria `MASTER_STEP_UP`); gate `MasterStepUpGuard` nas 4
  sensíveis (`403 step_up_required`, janela 15 min, fail-closed fora de request);
  `change-password` (SEC-25) limpa `stepped_up_at` das sessões revogadas;
  rate limit do step-up (`LIMITED_PATHS` por IP + principal).
- **F4 — Config + docs + E2E**: `nyxcash.security.master.step-up.*`
  (required-permissions default 4 + window 15m, fail-closed, binding test contra
  o yml); `mvn test` **1303/0/0** (+34 testes: guard, step-up, nível, sessão,
  properties, binding, rate limit); E2E **110/110** (105 existentes + 5 novos
  `stepup.spec.js`: 403 forbidden OPERATOR em perfis; 403 step_up_required;
  fluxo completo step-up→executa; janela expirada; change-password mata o
  step-up); cenários 13/14/18/22 da journey com seed de step-up (D8.1).
- **Critério 15 (Operator Bot)**: verificado — o bot (Spec 67) só usa
  `GET /api/v1/admin/lookup/ip/{ip}` (leitura) + WAF do Cloudflare; nenhuma das
  4 ações sensíveis via Discord (default documentado em D8.2).

**`/spec-audit` (2026-08-11): Spec 69 `audited`** — 15/15 critérios OK, 0 achados
de código (detalhe no task.md §auditoria). Verificações: catálogo 8/8, níveis
derivados sem campo novo, guard nos 8 services de escrita (403 `forbidden`),
deny-by-default na gestão de perfis, `stepped_up_at` (V47) + `isSteppedUp`,
endpoint `/master/mfa/step-up` reusando `verifySecondFactor` + auditoria
`MASTER_STEP_UP`, 4 sensíveis configuráveis, `change-password` limpa
`stepped_up_at`, `VARIABLE_PREFIXES` com `/api/v1/master` + `/api/v1/admin`
(cardinalidade fechada), step-up em `LIMITED_PATHS`, sem migration de dados,
E2E `stepup.spec.js` 5/5 + journeys com seed de step-up, Operator Bot restrito a
leituras. `mvn test` **1303/0/0** re-executado + E2E **110/110** re-executado
(app dev com `NYXCASH_SECURITY_RATE_LIMIT_ENABLED=false` +
`NYXCASH_PORTAL_SERVE_FRONTEND=true` — padrão do CI; o `portal-ui.spec.js` da
Spec 46 exige o flag de frontend). Ajuste de doc nesta auditoria: design D3.2
corrigido para os nomes reais das classes (`ReceivableWriteOffService`,
`MerchantStatusControlService` via `MasterRiskController`). Fim do ciclo SDD
(roadmap/requirements/design/task `audited`).

## Fase 23 — Saque automático por limiar de saldo (decisão do usuário 2026-08-11)

O vendedor pode ativar no portal um **saque automático**: quando o **saldo
disponível no wallet** atingir (ou passar) um **limiar** (default R$ 1.000,00),
o sistema saca **todo o saldo** automaticamente para um **destino específico
escolhido pelo vendedor**. Decisões do usuário (2026-08-11): gatilho = saldo ≥
limiar (não volume de vendas); valor = saldo total disponível; destino =
escolhido pelo vendedor (diferente do saque manual); ativação = configuração no
painel do portal (toggle + limiar). Implementação: job agendado (ShedLock +
`JobExecutionService`, Spec 53) que reusa **todo** o pipeline de saque existente
(Specs 14/31/32/36) — destinos ativos, bloqueio do master, inadimplência,
ledger, webhook de saída e reconciliação são respeitados; condição bloqueante →
**skip silencioso** (nunca quebra o job nem gera saque indevido).

| # | Name | Status | References |
|---|---|---|---|
| 70 | Saque automático por limiar de saldo — config no portal (`GET/PUT /api/v1/portal/auto-withdraw`: enabled + threshold default R$ 1.000 + destinationId do vendedor; auditoria `AUTO_WITHDRAW_SETTINGS_UPDATED`) + job agendado `nyxcash-auto-withdraw` (ShedLock + `JobExecutionService`, cron `0 */5 * * * *` UTC, kill-switch `nyxcash.auto-withdraw.enabled`) que saca **todo o saldo** quando `wallet_balance >= threshold` (reusa Fase A/B do saque; skips em não-ACTIVE/bloqueio/dívida/destino inativo; `AUTO_WITHDRAW_REQUESTED` actor SYSTEM) + card "Saque automático" na tela de Saque do portal (**`implemented` 2026-08-11 — F0–F5 concluídas: V48 + domínio + services + portal REST + scheduler + card; `mvn test` 1346/0/0 (1303 + 43 novos) + E2E **113/113** (run completo 112/113; a única falha — `portal-ui.spec.js` strict mode no select de destino duplicado pelo card novo — corrigida com locator escopado ao form e re-executada verde); ajustes pós-E2E: `enabled=false` mantém o destino anterior (coluna NOT NULL) e locator do `portal-ui.spec.js` escopado; **`audited` 2026-08-11 — `/spec-audit` 12/12 critérios OK, 0 achados abertos (detalhe no task.md §auditoria)**) | audited | [requirements](specs/70-auto-withdraw/requirements.md) · [design](specs/70-auto-withdraw/design.md) · [task](specs/70-auto-withdraw/task.md) · Visão V50 |

**`/spec-plan` + `/spec-new` + `/spec-verify` (2026-08-11): Spec 70 `verified`** —
spec planejada, criada e verificada na mesma sessão (4 achados corrigidos no
verify, ver task.md §verificação): (1) GET do portal devolve **200 com defaults**
(não 404) — settings com default da plataforma, o frontend não duplica o limiar;
(2) `max-withdrawals-per-run` conta **saques criados** (Fase A OK), não tentativas
(skips são baratos); (3) migração: V47 **reservado** pela Spec 69 (design) → esta
spec usa o **próximo número livre** na implementação (V48+ se a 69 for aplicada
antes); (4) `threshold > 0` validado explicitamente no serviço (`Money.of`
rejeita `> 2 casas`, mas `Money` permite negativo). Pronta para `/spec-implement`.

## Fase 24 — Master Console: frontend web do perfil master (decisão do usuário 2026-08-11)

Primeiro frontend para o perfil master. O backend master está **100% especificado
e auditado** (Specs 13/15/41/37/44 — RBAC + step-up da Spec 69 `verified`), e a
única interface administrativa hoje é o Operator Bot (Spec 67, Discord). Esta
fase entrega a **SPA do Master Console** (React/Vite, mesmo padrão e stack da
Spec 46), thin client sobre as APIs `ROLE_MASTER` existentes — **sem mudar
nenhum contrato HTTP existente** além de **3 endpoints novos mínimos, aditivos
e somente-leitura** (ROLE_MASTER):
1. `GET /api/v1/master/me` — perfil do master autenticado com `permissions[]`,
   `role`, `canManageMasters` e `mfaActive` (base da UI RBAC-aware — o
   **backend decide**; princípio consolidado na discussão 2026-08-11);
2. `GET /api/v1/master/merchants` — espelho read-only do `/api/v1/admin/merchants`
   (o master precisa navegar até o detalhe de um lojista);
3. `GET /api/v1/master/lookup/ip/{ip}` — espelho read-only do lookup do bot (67).
Os espelhos 2/3 existem porque `/api/v1/admin/**` exige `ROLE_ADMIN` (decisão do
usuário 2026-08-11: "Sim: espelhos read-only no master").

**Decisões do usuário (2026-08-11):** escopo em **2 fatias** — **Spec 71**
(leitura + ações reversíveis de OPERATOR, sem step-up) e **Spec 72** (ações
sensíveis de FINANCE/ADMIN com step-up TOTP + gestão de perfis master). As
permissões do master autenticado são expostas via **`GET /api/v1/master/me`**
(espelho do `/portal/me` do lojista — decisão do usuário; o `MasterLoginResponse`
não é alterado).

Princípios de design do console (aplicados às duas specs):

- **UI RBAC-aware por nível** (ADMIN/OPERATOR da Spec 69 — decisão do usuário
  2026-08-11: cada master tem OPERATOR+FINANCE+AUDITOR juntos; a função admin é
  separada, só gestão de perfis): esconde ações sem permissão; o backend
  continua a autoridade (403 `forbidden`). Navegação por permissões
  (`permissions[]` do perfil).
- **Step-up TOTP no cliente** (Spec 69, fatia 2): ação sensível sem janela →
  backend responde **403 `{ code: "step_up_required" }`** (não 401 — não derruba
  o cliente); a UI abre o prompt TOTP → `POST /api/v1/master/mfa/step-up` → repete
  a ação automaticamente; janela de 15 min por sessão respeitada no cliente
  (cache do `stepped_up_at` para UX, mas a janela real é do backend).
- **Login com MFA** (Spec 44): usuário/senha + TOTP quando ativo; sem MFA → senha
  apenas. Autogestão de MFA no console (activate/confirm/deactivate/recovery).

| # | Name | Status | References |
|---|---|---|---|
| 71 | Master Console — **fatia 1** (leitura + OPERATOR, sem step-up): SPA web (React/Vite, padrão Spec 46) + 3 endpoints novos ROLE_MASTER de leitura: `GET /api/v1/master/me` (perfil com `permissions[]`/`role`/`canManageMasters`/`mfaActive`), `GET /api/v1/master/merchants` e `GET /api/v1/master/lookup/ip/{ip}` (espelhos read-only dos admin — decisão do usuário); login+MFA (44); UI RBAC-aware (69 — esconde ações sem permissão, backend decide); telas de leitura: Dashboard master e transações (15), lojistas com earnings/fees/risco/ledger/receivables (15/16/19/27/33), saques e controles (14/32/36), financeiro/ledger do master (34), auditoria (37), relatório de atividades (41), reconciliação (30), lookup por IP (67); ações reversíveis de OPERATOR: suspender/liberar lojista (16) e bloquear/liberar saques (14) — sem step-up | audited | Visão V51 |
| 72 | Master Console — **fatia 2** (FINANCE + ADMIN com step-up): ações sensíveis com **step-up TOTP no cliente** (403 `step_up_required` → prompt → `POST /master/mfa/step-up` → repete ação; janela 15min; nunca 401): saque administrativo (32), write-off de receivable (34) e cobrança manual de fatura (33) — disponíveis a todo master (bloco completo, Spec 69) — e **gestão de perfis master (função admin**, `can_manage_masters`, com step-up; 41/69) | audited | Visão V51 |
| 73 | Frontend UX — **fluxos e usabilidade** (console + portal, puramente apresentação — sem mudança de contrato HTTP): feedback de ações consistente e anunciável (`SuccessBanner`/`ErrorBanner` com `role=status`/`role=alert`; toda ação de escrita com sucesso/erro visível — sem unhandled rejection), diálogos acessíveis (`ConfirmDialog`/`StepUpPrompt` com focus trap + Escape + autofocus + `aria-labelledby`/`describedby` + restauração de foco; tom `danger`/`primary`; `ConfirmDialog` novo no portal + confirmação para ações irreversíveis), estados de carregamento/vazio uniformes (Spinner padrão, empty state em toda lista/tabela, recarga sem zerar a lista), navegação (skip-link, nome real do lojista no detalhe) e formulários consistentes (`Field` + `required`, validação clara, "Copiar" com feedback) | audited | Visão V52 |

**`/spec-plan` (2026-08-11): Specs 71/72 `planned`** — proposta apresentada ao
usuário (telas por papel, fluxo de step-up, `GET /master/me` como dependência de
backend). **`/spec-new 71` (2026-08-11): Spec 71 `created`** —
requirements/design/task criados (`management/specs/71-master-console/`),
incluindo a decisão dos espelhos read-only no master; implementar após a
**Spec 69** (dependência explícita do `permissions[]`).
**`/spec-verify 71` (2026-08-11): Spec 71 `verified`** — 12 critérios checados
contra o código; 2 achados de precisão corrigidos nos documentos (design D2.1:
`mfaActive` via `MasterMfaService.hasActiveMfa`, método existente; task T6.4 +
design D6: env `NYXCASH_CONSOLE_SERVE_FRONTEND` no job e2e do `ci.yml`).

**`/spec-implement` (2026-08-11): Spec 71 `implemented`** — F0–F5 concluídas
(ver nota no task.md §Implementação): backend aditivo (`GET /master/me` com
`permissions[]`/`role`/`canManageMasters`/`mfaActive` — resolução por nível da
Spec 69; espelhos `/master/merchants` e `/master/lookup/ip/{ip}` reusando o
`AdminDashboardUseCase`; `QueryParamParser.isValidIp` extraído do admin — DRY),
config `nyxcash.console.serve-frontend` + `ConsoleSpaController` +
`SecurityConfig` (CSP `portal || console`, rotas públicas do console), SPA
`console/` (13 páginas, login+MFA, RBAC-aware, ações de OPERATOR com confirmação,
autogestão de MFA) e fechamento (helper TOTP extraído do auth.spec, E2E
`master-console-ui.spec.js` 4/4, env no job e2e do CI, README). **4 desvios
deliberados registrados** (paths explícitos do SPA controller — não
`/console/**`; rota `/console/merchants/:id/*`; `/console/` com barra; E2E 403
via profiles admin-only). **`mvn test` 1372/0/0** (26 novos) + E2E (ver nota).
Próximo: `/spec-audit 71`.

**`/spec-audit` (2026-08-12): Spec 71 `audited`** — **12/12 critérios OK, 0
achados de código** (detalhe no task.md §Auditoria). Verificações: `/master/me`
rota de leitura sem guard, `MasterMeResponse` sem hash/PII, resolução por nível
da Spec 69 (OPERATOR 7 / ADMIN + `master.profiles.manage`, nunca `[]` para
ativo) + `mfaActive` via `hasActiveMfa` (Spec 44); espelhos reusando
`AdminDashboardUseCase` + `QueryParamParser` (400/cap 100) + `isValidIp`
extraído (comportamento idêntico no `AdminController`); `ConsoleProperties`
default false + binding contra yml real (padrão Spec 68/L16) +
`ConsoleSpaController` condicional + `SecurityConfig` (CSP `portal || console`,
rotas públicas só com flag) + build `static/console` presente; login+MFA no
cliente (token `sessionStorage` `nyxcash.console.token`, 401→redirect exceto
pública, 403/409→ApiError sem logout); UI RBAC-aware (esconde, nunca autoriza);
13 telas de leitura; ações suspend/release + block/release com confirmação; MFA
autogestão; E2E `master-console-ui.spec.js` **4/4**; `mvn test` **1372/0/0**
re-executado + E2E completo **117/117** re-executado com banco limpo (envs do
CI); contratos `/api/v1/admin/**` e `/api/v1/master/**` existentes inalterados.
Observação fora de escopo registrada: o teste 23 da Spec 48 (race de `updatedAt`
da loja — CREATE/PATCH no mesmo microssegundo do Postgres) falhou 1× em
re-execução, pré-existente e sem relação com a Spec 71 (jornada isolada 27/27 e
suíte final 117/117 passaram). Fim do ciclo SDD
(roadmap/requirements/design/task `audited`).

**`/spec-new` + `/spec-verify` (2026-08-12): Spec 72 `verified`** — fatia 2 do
Master Console criada e verificada na mesma sessão
(`management/specs/72-master-console-stepup/`). Escopo: **backend aditivo
mínimo** (1 espelho read-only `GET /api/v1/master/invoices` — o master precisa
listar faturas para a cobrança manual, mas `/api/v1/admin/invoices` exige
ROLE_ADMIN; reusa `AdminDashboardUseCase.searchInvoices` — mesmo padrão dos
espelhos da 71) + **step-up TOTP no cliente** (hook `useStepUp` + `StepUpPrompt`
que intercepta 403 `step_up_required` → `POST /master/mfa/step-up` → repete a
ação; janela 15min; nunca 401) + **4 ações sensíveis** (saque administrativo 32,
write-off 34, cobrança manual 33, gestão de perfis 41/69). Backend das ações já
está pronto (Specs 69/32/33/34/41 `audited` — guards + step-up + endpoints
verificados). **`/spec-verify` 12 critérios checados; 2 achados de precisão
corrigidos** (sem mudança de contrato): (1) o `list` de perfis usa
`MasterAdminGuard.requireAdmin` **sem step-up** (leitura) — só
criar/ativar/desativar aplicam `stepUpGuard.requireSteppedUp` (linhas
79/107/130); requirements/design atualizados; (2) design D4.4 com o nome real
do guard. Pronta para `/spec-implement`.

**`/spec-implement` (2026-08-12): Spec 72 `implemented`** — backend aditivo
(espelho `GET /master/invoices` no `MasterReadController` + `MasterReadControllerTest`
+3) + step-up no cliente (`ApiError.stepUpRequired`, `useStepUp.tsx`,
`StepUpPrompt.tsx`) + 4 ações sensíveis (`FinancialPage` saque administrativo,
`ReceivablesPage` write-off, `MerchantDetailPage` aba Faturas + cobrança manual,
`ProfilesPage` nova com senha temporária 1×) + item de menu permissionado +
rota `/console/profiles` + tipos. **`/spec-audit` (2026-08-12): Spec 72
`audited`** — **12/12 critérios OK, 0 achados de código** (detalhe no task.md
§Auditoria). Verificações: espelho invoices (shape/validações iguais ao admin,
cap 100, 400 `invalid_argument`, contrato admin inalterado); step-up no cliente
(403 `step_up_required` → prompt → `POST /master/mfa/step-up` → repete a ação;
falha 401/400/429 mantém o modal; cancelar rejeita com o ApiError original;
cache 15min só UX fail-closed — E2E cenário 3 prova com `stepped_up_at`
expirado via SQL); saque administrativo (201 com saldo pós-débito, 409 →
banner); write-off (200 `{loss}`, linha vira PAID sem botão); cobrança manual
(`invoice.collect`, `{debit, remaining}`); perfis master (lista sem step-up;
escritas com step-up; senha temporária 1×; OPERATOR escondido + 403 com sessão
viva); UI RBAC-aware (esconde, nunca autoriza); E2E `master-console-stepup.spec.js`
**5/5** (2 execuções com banco limpo); `mvn test` **1376/0/0**; contratos
existentes inalterados (espelho aditivo). Ajustes de teste durante o E2E
(registrados no task.md §Auditoria): contrato do webhook PAID
(`eventId`/`externalReference`/`settledAt`), anti-replay do TOTP (step-up com
código de janela +30s + limpeza do `last_code_hash` no login entre testes —
marcador persiste na MESMA janela de 30s), contrato do `PixChargeRequest`
(`merchantId`/`expirationSeconds`/`idempotencyKey` única — Spec 28), `INSERT`
de receivable com `updated_at`. Fim do ciclo SDD
(roadmap/requirements/design/task `audited`).

**`/spec-plan` + `/spec-new` (2026-08-12): Spec 73 `created`** — Frontend UX
(fluxos e usabilidade) nos dois frontends, decisão do usuário
(`management/specs/73-frontend-ux/`). Escopo: **puramente apresentação** (sem
contrato HTTP novo, sem lib de UI — CSS puro): feedback de ações consistente e
anunciável (banners com `role=status`/`role=alert`; toda ação de escrita do
portal com sucesso/erro visível — hoje `WebhooksPage`/`CredentialsPage`/
`DestinationsPage` não mostram feedback e `toggleActive`/`revoke`/`updateLabel`
rodam sem try/catch → unhandled rejection silencioso), diálogos acessíveis
(`ConfirmDialog`/`StepUpPrompt` com focus trap/Escape/autofocus/aria/
restauração de foco; tom `danger`/`primary`; `ConfirmDialog` novo no portal +
confirmação para "Revogar chave" — hoje irreversível sem confirmação), estados
de carregamento/vazio uniformes (Spinner padrão — portal usa texto
"Carregando…" em 4 telas; bug no `ChargePage` que renderiza nada durante
loading; empty states ausentes em 9 telas/tabs; `useAsync` mantém dados em
reload), navegação (skip-link, nome real do lojista no detalhe) e formulários
consistentes (`Field` + `required` no `FinancialPage`, mensagens de valor
específicas, "Copiar" com feedback). Visão **V52**. Próximo: `/spec-verify 73`.

**`/spec-verify` (2026-08-12): Spec 73 `verified`** — 12 critérios checados
contra o código real (inventário fundamentado); 2 achados de precisão
corrigidos nos documentos: (1) autofocus dos diálogos guiado pelo tom — no
`ConfirmDialog` o 1º focável do DOM é o botão Cancelar; `danger` foca Cancelar
(Enter acidental NÃO confirma ação destrutiva), `primary` foca Confirmar
(D3.1/D3.2); (2) `role="status"`/`role="alert"` explicitados nos banners
(D2.1). **`/spec-implement` (2026-08-12): Spec 73 `implemented`** — F0–F5
entregues nos 2 frontends: banners anunciáveis + `SuccessBanner` no portal +
`useAsync` sem blink + `copyToClipboard`; diálogos acessíveis
(`useDialogFocus`: focus trap/Escape/autofocus por tom/restauração de foco;
`ConfirmDialog` com `tone` + aria; `ConfirmDialog` novo no portal + CSS do
modal; confirmação para "Revogar chave"); feedback completo no portal
(Webhooks/Credentials/Destinations com try/catch — antes unhandled
rejection); "Copiar" com "Copiado!" (4 telas); loading uniforme (Spinner no
portal, fix do bug do `ChargePage`, select de lojistas) + empty states em 9
telas/tabs; skip-link nos 2 layouts + nome real do lojista no detalhe;
`Field` + `required` no `FinancialPage` + mensagens de valor específicas.
**`/spec-audit` (2026-08-12): Spec 73 `audited`** — **12/12 critérios OK, 1
achado de código corrigido, 0 abertos** (detalhe no task.md §Auditoria):
estabilização do effect de foco dos diálogos (callbacks lidos via ref, deps
`[open]` — o effect re-rodava a cada render com o modal aberto). Validação:
`npm run build` limpo (tsc) nos 2 frontends + `mvn test` **1376/0/0** + E2E de
UI **10/10** (master-console-ui 4 + master-console-stepup 5 + portal-ui 1 —
re-executado 3×) + suíte completa **112/122** com envs do CI (2 falhas
ambientais registradas, sem relação com a spec — ver task.md). Achado
operacional: ordem de build dos frontends (portal antes do console —
`emptyOutDir` do portal apaga `static/console/`). Fim do ciclo SDD
(roadmap/requirements/design/task `audited`).

## Fase 25 — Vendas por bots: registro central, métricas e criadores (decisão do usuário 2026-08-12)

Análise externa (2026-08-12) do sistema de bots do irmão do usuário (painel
central para conectar/gerenciar múltiplos bots Telegram e Discord, vender via
gateway, e rastrear leads/vendas por bot — PRD fornecido pelo usuário). O
TrevoPay **já entrega o motor de venda por bots** (Specs 48/50/52 `audited`:
storefront white-label, fluxo configurável e bots renderizadores config-driven
`bots/`, stateless, sem banco próprio) — **o que falta é a camada de gestão
central**: registro de bots no banco, métricas de leads/vendas por bot, painel
e **criadores de bot** (wizard que guia a criação do bot na plataforma). Decisão
do usuário: implementar a mesma ideia **melhor** (token criptografado em
repouso, webhook com `secret_token`, idempotência, outbox/retry da Spec 35,
RBAC da Spec 69, auditoria da Spec 41, entrega por ações da Spec 52), com
**criador de bot próprio para Telegram e para Discord** (specs 77 e 78).

**Dependências:** Spec 74 (registro) → Specs 75 (métricas), 77 e 78
(criadores) → Spec 76 (painel). Reusa: Specs 48/50/52 (storefront/bots),
35 (outbox/webhooks), 41 (auditoria), 69 (RBAC master), 71–73 (console/UX),
46 (portal).

| # | Name | Status | References |
|---|---|---|---|
| 74 | Registro central de bots de venda (backend) — tabela `store_bots` (merchant_id, `platform` telegram/discord, token **criptografado em repouso**, status `REGISTERING/ACTIVE/PAUSED/ERROR`, webhook secret por bot, `last_health_check`, soft-delete); CRUD no portal (lojista gerencia os próprios bots) + espelho no Master Console (visão geral, RBAC Spec 69); integração Telegram (validação `getMe` + `setWebhook` com `secret_token` aleatório + verificação do update) e Discord (gerenciador de conexão com reconexão/backoff — decisão stateless Spec 52); auditoria Spec 41; métricas Spec 53 | audited | [requirements](specs/74-registro-bots/requirements.md) · [design](specs/74-registro-bots/design.md) · [task](specs/74-registro-bots/task.md) · Visão V53 |
| 75 | Leads e métricas por bot (backend) — `bot_leads` (bot_id + `platform_user_id`, constraint única — lead = usuário único que inicia conversa); eventos de funil (`lead → checkout_started → paid`) com atribuição `bot_id` na cobrança; endpoints de métricas (leads, vendas PAID, conversão por bot/loja/período) para portal e master; estorno/chargeback não conta como venda (Specs 23/29) (**`audited` 2026-08-17 — `/spec-audit` 10/10 critérios OK, 0 achados de código; achados de doc corrigidos (task.md desmarcado + status `verified` stale); `mvn test` 2061/0/0 + E2E `bot-funnel.spec.js` 6/6 com mock Telegram (porta 9001)**) | audited | [requirements](specs/75-leads-metricas-bots/requirements.md) · [design](specs/75-leads-metricas-bots/design.md) · [task](specs/75-leads-metricas-bots/task.md) · Visão V53 |
| 76 | Painel de gestão de bots (UI — console + portal) — tela no Master Console (todos os lojistas, RBAC-aware Spec 69) + tela no portal do lojista (os próprios bots): cards com foto/nome/@/status/leads/vendas (padrão Spec 52), filtro por plataforma, ações pausar/reativar/editar/excluir (ConfirmDialog Spec 73), modais de conectar/editar (avatar/descrição/comandos); consumo dos endpoints Specs 74/75 (**`audited` 2026-08-13 — `/spec-audit` 8/8 critérios OK, 0 achados abertos; `mvn test` 1481/0/0 + E2E completo 135/135 + `bots-panel.spec.js` 2/2 com mock Telegram; achado de doc D4.2 corrigido (edição = só displayName — contrato real da Spec 74; descrição/comandos nos criadores 77/78); fix de flakiness cross-spec pré-existente no teste 18.7 do merchant-journey (`withdrawalFees` absoluto 7.5 → `>= 7.5`, o summary master é GLOBAL e auto-withdraw roda antes na suíte completa)**) | audited | [requirements](specs/76-painel-bots/requirements.md) · [design](specs/76-painel-bots/design.md) · [task](specs/76-painel-bots/task.md) · Visão V54 |
| 77 | Criador de Bot Telegram (wizard) — guia a criação do bot via BotFather (token), valida com `getMe`, registra webhook com `secret_token`, testa foto/descrição/comandos via API do Telegram (`setMyPhoto`/`setMyDescription`/`setMyShortDescription`) e finaliza criando o registro da Spec 74; passo a passo no painel (portal) com verificação em tempo real (`POST /validate` + `POST /setup` aditivos mínimos documentados) | audited | [requirements](specs/77-criador-bot-telegram/requirements.md) · [design](specs/77-criador-bot-telegram/design.md) · [task](specs/77-criador-bot-telegram/task.md) · Visão V55 |
| 78 | Criador de Bot Discord (wizard) — guia a criação do bot via Discord Developer Portal (token + Application ID), valida a conexão (gateway com reconexão/backoff, decisão Spec 52), testa presença + comandos slash (`PUT /applications/{id}/commands`) e finaliza criando o registro da Spec 74; passo a passo no painel (portal) com verificação em tempo real (**`audited` 2026-08-13 — `/spec-audit` 8/8 critérios OK, 0 achados abertos; `mvn test` 1504/0/0 + E2E completo 139/139 + `bots-creator-discord.spec.js` 2/2 com mock Discord (porta 9002, `FOXCASH_DISCORD_API_BASE_URL` no CI); wizard `intro → Dev Portal → credenciais → validar → setup → register → done` com idempotência (voltar não duplica registro)**) | audited | [requirements](specs/78-criador-bot-discord/requirements.md) · [design](specs/78-criador-bot-discord/design.md) · [task](specs/78-criador-bot-discord/task.md) · Visão V55 |

**`/spec-plan` (2026-08-12): Specs 74–78 `planned`** — proposta apresentada ao
usuário após a análise do sistema externo: (1) o motor de venda por bots já
existe e é `audited` (Specs 48/50/52), então as specs novas **não reimplementam
o renderizador** — apenas a camada de gestão; (2) registro central `store_bots`
(74) como dependência raiz; (3) métricas de leads/vendas por bot (75); (4)
painel de gestão no console + portal (76); (5) criadores de bot Telegram (77) e
Discord (78) — decisão do usuário: "vou fazer um criador de bot em cada um".
Visões V53–V55. Próximo: `/spec-new 74`.

**`/spec-new 74` (2026-08-12): Spec 74 `created`** —
requirements/design/task criados (`management/specs/74-registro-bots/`):
tabela `store_bots` (V49, token criptografado AES-256/GCM via `CryptoConverter`
+ `token_hash`/`webhook_secret_hash` para lookup), máquina de estados
`REGISTERING/ACTIVE/PAUSED/ERROR` com soft-delete, CRUD no portal +
espelho read-only no Master Console (RBAC Spec 69), integração Telegram real
(`getMe` + `setWebhook` com `secret_token` de 32 bytes — URL sem `{bot_id}`
adivinhável) e Discord (gerenciador de conexão com reconexão/backoff,
decisão stateless Spec 52), auditoria `PlatformAction` nova + job de
reconciliação (Spec 53). Próximo: `/spec-verify 74` e depois `/spec-new 75`.

**`/spec-new 75–78` (2026-08-12): Specs 75–78 `created`** —
requirements/design/task criados em `management/specs/{75-leads-metricas-bots,
76-painel-bots,77-criador-bot-telegram,78-criador-bot-discord}/`:
- **75 (leads e métricas)**: `bot_leads` (UNIQUE bot_id+platform_user_id) +
  funil `lead → checkout_started → paid` (V50), `botId` opcional no `purchase`
  (zero regressão sem ele), métricas por período (venda = PAID não estornado).
- **76 (painel)**: `BotsPage` no portal (CRUD + cards com leads/vendas) e no
  console (espelho read-only, RBAC Spec 69); modais de conectar/editar;
  padrão UX da Spec 73.
- **77 (criador Telegram)**: wizard `intro → BotFather → validar (getMe) →
  setup → register → done`; validação em tempo real; registro 1× via Spec 74.
- **78 (criador Discord)**: wizard `intro → Dev Portal → credenciais →
  validar (conexão) → setup → register → done(convite OAuth2)`; reconexão/
  backoff da Spec 74.
Todas: **sem rota de backend nova silenciosa** (gaps = aditivo mínimo
documentado no task); reuso das Specs 74/75/73. Próximo: `/spec-verify 74`
(iniciar implementação) ou revisão dos documentos.

## Fase 26 — Broadcast: transmissão e disparo de mensagens em massa (decisão do usuário 2026-08-12)

Análise externa (2026-08-12) do módulo de **broadcast** do sistema do irmão
do usuário (disparo em massa para bots Telegram/Discord: fila Redis+BullMQ,
rate limits, editor rich-text com preview, mídia via CDN/file_id, botões
inline, modos Agora/Agendada/Bem-vindos/Repost e log por destinatário — PRD
fornecido pelo usuário). O TrevoPay **já cobre o modo Agendada** (Spec 51
`audited`: `scheduled_at`/`cron`, bot busca vencidas e envia) e **tem as
tags dinâmicas** no bot (`renderTemplate`, Spec 52). **Faltam**: destino
canal/grupo (`target_chat`), modo **Agora (NOW)**, modo **Bem-vindos**
(evento chat_member/guildMemberAdd — hoje `welcome` é do menu da loja), modo
**Repost** (intervalo), **mídia CDN** (file_id), **botões inline** no corpo
do broadcast, **broadcast_logs** por destinatário e a **fila com rate limit**.

**Decisão de arquitetura (diferente do BullMQ do irmão):** stack Java/Spring
— **sem fila BullMQ**. O envio continua **no bot** (Spec 52, grammY/
discord.js com rate limit nativo das libs: 30 msg/s Telegram, buckets do
Discord); o backend vira o **orquestrador** (tabelas `broadcasts`/
`broadcast_logs`, resolução de destino via registro da Spec 74, modos,
mídia, botões) e o bot **busca pendências e reporta log por destinatário**
via API (padrão que a Spec 51/52 já usa).

**Dependências:** Spec 74 (registro de bots — destino) e Specs 51/52
(agendamento/envio) como base; Spec 35 (outbox) para eventos; Spec 41
(auditoria).

| # | Name | Status | References |
|---|---|---|---|
| 79 | Broadcast — **motor de disparo em massa (backend)**: tabelas `broadcasts` (modo `NOW/SCHEDULED/WELCOME/REPOST`, `target_chat`, content, media, `buttons_json`, status) e `broadcast_logs` (por destinatário `SUCCESS/BLOCKED/FAILED`); destino canal/grupo resolvido via registro da Spec 74 (chats onde o bot é admin); modos: Agora, Agendada (reuso Spec 51), Bem-vindos (evento chat_member/guildMemberAdd) e Repost (intervalo 12h/24h); **mídia CDN** (upload → file_id/URL, imagem ≤10MB, vídeo/áudio ≤50MB); **botões inline** (link/callback) e rich-text (negrito/itálico/etc. no formato da plataforma) + tags dinâmicas; dispatch: bot busca pendências e **reporta log por destinatário** via API (rate limit nativo das libs da Spec 52); auditoria Spec 41 (**`audited` 2026-08-13 — `/spec-audit` 12/12 critérios OK, 2 achados de doc corrigidos: requirements critério 12 alinhada ao design D10 — sem job de due (pull do bot) e métricas `nyx.job.*`; observação critério 10: content sem whitelist/limite, mitigado por Markdown + WAF Spec 63; detalhe no task.md §auditoria**) | audited | [requirements](specs/79-broadcast-motor/requirements.md) · [design](specs/79-broadcast-motor/design.md) · [task](specs/79-broadcast-motor/task.md) · Visão V56 |
| 80 | Broadcast — **editor e preview (UI portal)**: editor de mensagem com toolbar de formatação (negrito/itálico/sublinhado/riscado/código/citação/spoiler no formato Telegram/Discord), inserção de tags dinâmicas (`{primeiro_nome}`, `{username}`, `{data}`), upload de mídia (imagem/vídeo/áudio com limites), editor de grade de botões inline (link/callback), **preview em tempo real** (bolha simulada da plataforma) e barra de modos (Enviar agora/Agendada/Bem-vindos/Repost) com validação de formulário (bot/destino obrigatórios; mensagem ou mídia pelo menos um); consumo dos endpoints da Spec 79; padrão UX da Spec 73 (**`implemented` 2026-08-14 — F0–F2 concluídas; `npm run build` (tsc) limpo nos 2 frontends + `mvn test` 1589/0/0 + E2E `broadcast-editor.spec.js` 3/3 + suíte completa 142/142; 5 bugs REAIS corrigidos no E2E — multipart quebrado pelos filtros de body (Spec 63), CHECK `media_type` lowercase vs enum (V55), rotas `/broadcasts` fora do SPA fallback/whitelist, Content-Type global do Playwright quebrando FormData, preview de botão link mostrando URL; detalhe no task.md §Implementação** · **`audited` 2026-08-14 — `/spec-audit` 10/10 critérios OK, 1 achado corrigido (aria-live flood no preview — design D5 "sem flood"; movido para o rodapé discreto), 0 abertos; `mvn test` 1589/0/0 + E2E 142/142 re-executados pós-fix; detalhe no task.md §auditoria**) | audited | [requirements](specs/80-broadcast-editor/requirements.md) · [design](specs/80-broadcast-editor/design.md) · [task](specs/80-broadcast-editor/task.md) · Visão V57 |

**`/spec-implement 80` (2026-08-14): Spec 80 `implemented`** — editor e preview
de broadcast no portal (F0→F2, retomada pós-crash do computador). **`npm run
build` (tsc) limpo nos 2 frontends** (portal + console — ordem portal → console)
+ **`mvn test` 1589/0/0** + **E2E `broadcast-editor.spec.js` 3/3** + suíte E2E
completa **142/142** (sem regressão). **5 bugs REAIS corrigidos** (o E2E do
editor expôs bugs latentes que nem o `mvn test` nem o E2E da Spec 79 pegavam):

1. **Upload de mídia quebrado pelos filtros de body (Spec 63/M-01)** — o
   `RequestBodySizeFilter` (teto 1 MiB) e o `RequestBodyInspectionFilter`
   liam o corpo e re-embrulhavam via `CachedBodyHttpServletRequest`, que NÃO
   reimplementa `getParts()` → o parse de multipart do Spring falhava (400
   `invalid_argument` em `POST /api/v1/portal/broadcasts/media`); e o teto de
   1 MiB contradizia o contrato da Spec 79 (imagem ≤10MB, vídeo/áudio ≤50MB).
   Corrigido: ambos os filtros **pulam `multipart/form-data`** (o parse é do
   container; o tamanho é controlado pelo `spring.servlet.multipart.*` novo no
   `application.yml` — max-file 60MB/max-request 64MB).
2. **CHECK `media_type` em lowercase vs enum UPPERCASE (V54)** — `CHECK
   (media_type IN ('image','video','audio'))` rejeitava o INSERT com
   `IMAGE`/`VIDEO`/`AUDIO` (`@Enumerated(EnumType.STRING)` grava o nome do
   enum — mesmo padrão do achado da Spec 74) → 500 no create com mídia.
   Corrigido pela migration **V55** (drop + add da constraint com UPPERCASE).
3. **Rotas `/broadcasts` fora do fallback/whitelist da SPA** — o
   `PortalSpaController` e o `spaPublicPaths()` do `SecurityConfig` não
   incluíam `/broadcasts`/`/broadcasts/*` → full reload (F5/goto) retornava
   401 JSON em vez do `index.html` (o guard client-side redireciona para
   /login). Corrigido nos dois.
4. **Content-Type global do Playwright quebrava o FormData do browser** —
   `extraHTTPHeaders: {'Content-Type': 'application/json'}` no
   `playwright.config.js` vazava para o `fetch` do browser (MediaUploader) →
   o navegador não montava o `multipart/form-data; boundary=` → 400. Removido
   o header global (o fixture `request` define o header correto por `data`/
   `form`/`multipart`); headers explícitos adicionados aos testes que usavam
   `data: JSON.stringify(...)`/Buffer no `security.spec.js`.
5. **Preview de botão link mostrava a URL em vez do rótulo** — o
   `BroadcastPreview` exibia `b.url || b.label` (o Telegram/Discord mostram o
   label, não a URL) → corrigido para `b.label || ...`.

**Desvios registrados**: (1) o `global-setup.js` já truncava `store_bots` com
CASCADE (limpa `broadcasts` via FK — sem mudança); (2) o job e2e do CI ganhou
`NYX_BOTS_TELEGRAM_CDN_CHANNEL_ID` (o upload do E2E usa o mock → `sendPhoto`
no canal CDN). Próximo: `/spec-audit 80`.

**`/spec-audit 80` (2026-08-14): Spec 80 `audited`** — **10/10 critérios OK, 1
achado corrigido, 0 abertos** (detalhe no task.md §auditoria). Verificações:
rota/menu/lista (C1), toolbar/markup por plataforma (C2), tags (C3), mídia
com limites (C4), botões com limites por plataforma (C5), modos (C6), preview
bolha Telegram/Discord com tags exemplo (C7), validação/envio (C8),
cancelar PENDING + duplicar pré-preenchido (C9), testes E2E (C10). **Achado
corrigido (UX/acessibilidade — design D5 "aria-live polida — sem flood")**: o
`BroadcastPreview` tinha `aria-live="polite"` no `<section>` inteiro (container
que re-renderiza a cada tecla/upload/edição) — um leitor de tela anunciaria o
texto completo do preview a cada digitação (flood, contradizendo D5); corrigido
movendo o `aria-live` para o rodapé discreto (`Modo: X` — muda raramente), com
o container mutável sem live region. `npm run build` (tsc) limpo (portal +
console, ordem portal → console) + `mvn test` **1589/0/0** + E2E
`broadcast-editor.spec.js` **3/3** + suíte completa **142/142** re-executados
pós-fix. Observação registrada (fora de escopo): race pré-existente do teste
23 (Spec 48 — `updatedAt` da loja) documentado na auditoria da Spec 71.
Fim do ciclo SDD (roadmap/requirements/design/task `audited`).

**`/spec-plan` (2026-08-12): Specs 79–80 `planned`** — proposta apresentada
ao usuário após a análise do módulo externo: (1) o agendamento já existe
(Spec 51) — as specs novas **não reimplementam** a busca de vencidas/envio;
(2) Spec 79 = motor (tabelas, modos, mídia, botões, log); (3) Spec 80 =
editor/preview no portal; (4) decisão: **sem BullMQ** — envio no bot (Spec
52) com rate limit nativo, backend orquestrador. Visões V56–V57. Próximo:
`/spec-new 79`.

**`/spec-new 79–80` (2026-08-12): Specs 79–80 `created`** —
requirements/design/task criados em
`management/specs/{79-broadcast-motor,80-broadcast-editor}/`:
- **79 (motor)**: `broadcasts`/`broadcast_logs` (+ `broadcast_welcome_sent`,
  V51), modos NOW/SCHEDULED (reuso Spec 51)/WELCOME (chat_member/
  guildMemberAdd)/REPOST (intervalo ≥1h, job ShedLock), destino canal/grupo
  via Spec 74 (`GET /portal/bots/{id}/chats`), mídia CDN (file_id/URL, 10MB/
  50MB), botões inline (link/callback com limites por plataforma), rich-text
  validado (whitelist HTML Telegram / Markdown Discord) + tags resolvidas no
  bot; dispatch por **pull do bot** (`due`/`sent`/`logs`/`welcome-trigger`,
  padrão Spec 51) — **sem BullMQ**.
- **80 (editor)**: `BroadcastsPage` + `BroadcastEditorPage` no portal com
  toolbar de formatação por plataforma, inserção de tags, upload de mídia,
  grade de botões inline, **preview em tempo real** (bolha simulada
  Telegram/Discord), barra de modos e validação de formulário (bot/destino/
  conteúdo-ou-mídia); reuso UX da Spec 73.
Todas: sem rota de backend nova silenciosa; reuso Specs 74/51/52/73.
Próximo: `/spec-verify 79` (iniciar implementação) ou revisão dos documentos.

**`/spec-verify 74–80` (2026-08-12): Specs 74–80 `verified`** — critérios
checados contra o código real; **4 achados de precisão corrigidos nos
documentos**: (1) Spec 75 frontmatter — `29-refund-consistency` não existe;
o dir real é `29-consistent-refund` (link corrigido); (2) Spec 75 task T1.4 —
coluna `pix_transactions.bot_id` fixada **na V50** (antes "V50 ou migration
própria"); (3) Spec 74 design D5.3 — ancorado nos controllers reais
(`PixWebhookController`/`PayoutWebhookController` em
`infrastructure/inbound/webhook/`, `WebhookSignatureFilter`); (4) Specs 74/79
design D9/D10 — seção do `PlatformAction` precisa: nova seção `// Bots
(Spec 74)` (o enum usa seções por comentário — `// Master`, `// Lojista /
portal`, `// Operações financeiras`, `// Operacional / auditoria`).
**Verificações sem achado**: migrations V49/V50/V51 livres (última real é a
V48); classes referenciadas existem (`CryptoConverter`, `DocumentHasher`,
`ScheduledMessage`, `FoxScheduledMessageController`,
`PayoutProviderContractTest`); `renderTemplate` existe no bot (Spec 52);
rotas `/webhooks/pix`/`/webhooks/payouts` reais; specs referenciadas nos
frontmatters existem (após fix 1); nomeação `BOT_*`/`BROADCAST_*` do
`PlatformAction` consistente (`BUTTON_*`/`MESSAGE_*`/`PLAN_*`); decisão
verificada: `store_bots.merchant_id` (em vez de `store_id`) é válida — `stores`
é 1:1 com merchant (V36 `merchant_id UNIQUE`), registro como decisão.
Próximo: `/spec-implement 74` (iniciar implementação pela dependência raiz).

**`/spec-implement 74` (2026-08-12): Spec 74 `implemented`** — F0→F5 concluídas:

- **F0 — Domínio + migration**: `V49__store_bots.sql` (token `CryptoConverter` +
  `token_hash`/`webhook_secret_hash` SHA-256 p/ lookup, `CHECK` de
  platform/status, `ix_store_bots_merchant` parcial); `StoreBot` (aggregate por
  `merchant_id`) + enums `BotPlatform`/`BotStatus` com máquina de estados
  (`activate`/`pause`/`resume`/`markError`/`remove` — `InvalidBotStateException`
  → 409 `invalid_bot_state`), `tokenMask()` (últimos 4 — token nunca em resposta
  HTTP) + JPA/repo/mapper/adapter.
- **F1 — Services**: `StoreBotCommandService` (register com integração →
  ACTIVE/ERROR + auditoria; token duplicado → 409 `bot_already_exists`),
  `StoreBotQueryService` (portal + espelho master com nome do lojista);
  `PlatformAction` += 6 (`BOT_REGISTERED/UPDATED/PAUSED/RESUMED/REMOVED/
  INTEGRATION_ERROR`, seção `// Bots (Spec 74)`).
- **F2 — Telegram**: porta `TelegramBotApi` + `HttpTelegramBotApiClient`
  (getMe/setWebhook/deleteWebhook/setMyDescription/setMyShortDescription/
  setMyCommands/getWebhookInfo; timeouts 5s/10s; base URL configurável p/
  mock E2E); `integrate` (getMe → identidade + setWebhook com URL
  `/webhooks/telegram/{id}/{secret}` — secret 32 bytes, nunca o id — +
  `secret_token`); `disintegrate` (deleteWebhook best-effort); endpoint público
  `POST /webhooks/telegram/{id}/{secret}` (lookup por hash constant-time, header
  `X-Telegram-Bot-Api-Secret-Token` validado, descarte em PAUSED/ERROR, publica
  `TELEGRAM_UPDATE` no outbox Spec 35, rate limit por IP normalizado, body cap
  Spec 63 herdado + exclusão do body inspection — payload tem texto arbitrário
  do usuário).
- **F3 — Discord**: `DiscordBotApi` + `HttpDiscordBotApiClient` (GET /users/@me,
  PUT /applications/{id}/commands) + `DiscordBotManager` (estado em memória por
  instância, kill-switch `nyxcash.bots.discord-gateway-enabled` off em dev/test,
  decisão stateless Spec 52).
- **F4 — REST**: `PortalBotController` (CRUD + pause/resume, escopo por lojista
  — cross-lojista 404, respostas sem token) + `MasterBotController` (espelho
  read-only); `SecurityConfig` (webhook público permitAll); `RateLimitKey`
  ganha `/api/v1/portal/bots` (cardinalidade SEC-23).
- **F5 — Job + config**: `BotReconcileScheduler` (ShedLock + `JobExecutionService`
  Spec 53; REGISTERING preso → ERROR; ACTIVE sem health → re-verifica; ERROR →
  re-integra; métrica `nyx.bot.reconcile`) + `BotProperties` (binding contra o
  yml real — Spec 68/L16) + job e2e do CI com `NYX_TELEGRAM_API_BASE_URL`.
- **Testes**: `mvn test` **verde** (suíte completa; +86 testes Spec 74: domínio,
  services, integration, controllers, adapters, HTTP clients, properties +
  binding) e E2E **`bots-registro.spec.js` 5/5** (mock do Telegram: registro →
  ACTIVE com getMe+setWebhook, update → `TELEGRAM_UPDATE` no outbox, pause →
  descarta, resume, espelho master, soft-delete) + `global-setup` ganhou
  `store_bots` (L6). **Smoke ao vivo**: boot com migration V49 limpa + Hibernate
  validate; bot com token inválido → ERROR com auditoria; webhook segredo
  inválido → 404; pause em ERROR → 409; espelho master com nome do lojista;
  token `v1:` no banco + `token_hash`. **Desvios registrados**: (1) `CHECK` de
  platform em UPPERCASE (`('TELEGRAM','DISCORD')`) — o design dizia lowercase,
  mas `@Enumerated(EnumType.STRING)` grava o nome do enum (padrão V43); (2) o
  retry do `deleteWebhook` é coberto pelo job de reconciliação (o outbox Spec 35
  entrega webhooks de saída ao lojista — publicar tarefa interna nele seria
  uso indevido; o endpoint público descarta updates em PAUSED/ERROR de qualquer
  forma); (3) listagem do portal sem paginação (poucos bots por lojista — array
  plano, padrão dos demais lists do portal). Próximo: `/spec-audit 74`.

**`/spec-audit` (2026-08-12): Spec 74 `audited`** — **12/12 critérios OK, 2
achados corrigidos** (detalhe no task.md §Auditoria): **(1) CRÍTICO — replay
concorrente de registro** devolvia **500** em vez de 409: o `register` checava
`findByTokenHash` mas não convertia a violação da UNIQUE de `token_hash`
(`DataIntegrityViolationException`) em `StoreBotAlreadyExistsException`; o
`SELECT ... FOR UPDATE` da Spec 49 não se aplica a INSERT de linha nova (não há
linha a travar) — o mecanismo real é UNIQUE + catch → 409 (replay executa 1×);
corrigido + teste `registerWithConcurrentReplayThrows409InsteadOf500`; **(2)
DOC — requirements critério 1 e design D2.1** especificavam CHECK de platform em
lowercase e `token_hash TEXT`; a implementação usa UPPERCASE (exigido pelo
`@Enumerated(EnumType.STRING)`) e `VARCHAR(64)` (padrão `document_hash`) —
documentos alinhados ao código (design estava errado). `mvn test` **verde**
(suíte completa, re-executado pós-fix) + E2E `bots-registro.spec.js` 5/5 (na
implementação). **Ferramenta `src/tools/spec-tracer.mjs` inexistente** no repo —
rastreabilidade/aceite verificados manualmente nesta auditoria. Fim do ciclo
SDD (roadmap/requirements/design/task `audited`).

- **Spec 76 (`audited`, 2026-08-13):** auditoria do ciclo SDD do painel de
  gestão de bots (implementada com a Spec 74/75 `audited`). **`/spec-audit` —
  8/8 critérios OK, 0 achados abertos.** Implementação: `BotsPage` no portal
  (`/bots`, "Meus Bots" — cards com ícone/display_name/@/status
  ONLINE-OFFLINE-ERRO + leads/vendas/conversão, filtro por plataforma, ações
  pausar/reativar/editar/excluir com `ConfirmDialog` `danger`, modais
  `ConnectBotModal`/`EditBotModal`) e no console (`/console/bots`, espelho
  **read-only** com lojista/plataforma/status/métricas + filtros — RBAC Spec
  69, nenhum botão de mutação); rotas/SPA/CSP wired (portal + console,
  `ConsoleSpaControllerTest` +1); **`useDialogFocus` novo no portal** (espelho
  do console — focus trap/Escape/autofocus/restauração nos modais, critério 7);
  CSS dos cards/badges. **Validação:** `npm run build` (tsc) limpo nos 2
  frontends (ordem portal → console) + `mvn test` **1481/0/0** + E2E
  `bots-panel.spec.js` **2/2** (portal: empty → conectar com mock Telegram
  getMe/setWebhook → pausar → reativar → editar → filtrar → excluir; console:
  espelho + RBAC sem ações) + E2E completo **135/135** sem regressão.
  **Achados:** (1) DOC — design D4.2 previa "descrição/bio e comandos" no
  modal de edição; o contrato real da Spec 74 só expõe `displayName` no PUT
  (`StoreBotUpdateRequest`) — descrição/comandos ficam nos criadores (Specs
  77/78); D4.2 alinhado; (2) TESTE — fix de flakiness cross-spec
  **pré-existente** no `merchant-journey.spec.js` 18.7: asseriava
  `withdrawalFees = 7.5` absoluto no summary GLOBAL do master, mas a suíte
  completa roda `auto-withdraw.spec.js` (Spec 70) **antes** (ordem alfabética)
  gerando +2.50 → summary 10.00 (a auditoria da Spec 88 validou com a journey
  isolada 27/27, por isso não apareceu); corrigido para `>= 7.5` (a jornada
  contribui 7,50 líquido ao summary global — mesmo padrão de robustez já usado
  para `losses` no 18.4b). Fim do ciclo SDD
  (roadmap/requirements/design/task `audited`).

**`/spec-implement 79` (2026-08-13): Spec 79 `implemented`** — motor de
broadcast (backend) F0→F4 concluídas, **`mvn test` 1589/0/0** sem regressão:

- **F0 — Domínio + migration**: `V54__broadcast.sql` (número livre — a última
  real era a V53) com `broadcasts` (+ `error_message` exigido pelo critério 4),
  `broadcast_logs` (UNIQUE `broadcast_id+recipient_id` — idempotência) e
  `broadcast_welcome_sent` (idempotência do WELCOME por `chat_id+user_id`);
  `Broadcast` (aggregate: validações por modo — target obrigatório p/
  NOW/SCHEDULED/REPOST, `scheduled_at` futuro, `interval_seconds >= 3600`,
  conteúdo-ou-mídia; máquina de estados PENDING→PROCESSING→COMPLETED/FAILED,
  `reschedule` do REPOST, `isDue` — WELCOME nunca é "vencida") + enums
  `BroadcastMode/Status/LogStatus/MediaType` + value object `BroadcastButtons`
  (parse/validação do `buttons_json` por plataforma — Telegram ≤100/≤8 por
  linha, Discord ≤5/≤5 — com URL SSRF-safe injetada via `WebhookUrlValidator`,
  preservando a fronteira hexagonal); JPA/repos/mappers/adapters + portas
  `BroadcastRepository`/`BroadcastLogRepository`/`BroadcastWelcomeSentRepository`
  (consultas sempre escopadas por `merchantId` — IDOR-safe); `PlatformAction` +=
  `BROADCAST_CREATED/SCHEDULED/CANCELLED` (seção `// Bots`).
- **F1 — Services + REST portal**: `BroadcastCommandService` (create/cancel com
  auditoria na mesma transação; DELETE físico de PENDING — 409 se PROCESSING),
  `BroadcastQueryService` (lista com filtro modo/status + detalhe com logs),
  `BroadcastChatService` (destino dinâmico — Telegram `getUpdates`/chat, Discord
  `GET /users/@me/guilds`; cache TTL 60s); `PortalBroadcastController`
  (POST/GET/GET{id}/DELETE `/api/v1/portal/broadcasts` + `POST /media`
  multipart) e `GET /api/v1/portal/bots/{id}/chats` no `PortalBotController`;
  `GlobalExceptionHandler` += 404 `broadcast_not_found`.
- **F2 — Dispatch + API do bot**: `BroadcastDispatchService` (`due` → marca
  PROCESSING; `markSent` → COMPLETED se ≥1 SUCCESS / FAILED se nenhum —
  idempotente; `reportLog` idempotente por `(broadcast_id, recipient_id)` — uma
  falha por destinatário nunca derruba os demais; `welcomeTrigger` 1× por
  `(chatId, userId)` via `broadcast_welcome_sent`); `FoxBroadcastController`
  (`GET /due`, `POST /{id}/sent`, `POST /{id}/logs`, `POST /welcome-trigger` —
  API key, padrão Spec 51).
- **F3 — Mídia CDN**: `BroadcastMediaService.upload` (valida tipo/tamanho —
  imagem ≤10MB, vídeo/áudio ≤50MB; Telegram envia ao canal CDN configurável
  `nyxcash.bots.telegram-cdn-channel-id` e reusa o `file_id` — sem re-upload por
  destinatário; Discord: URL do attachment); `TelegramBotApi` += `listChats`/
  `sendMedia` (mock de contrato com MockWebServer).
- **F4 — REPOST + fechamento**: `BroadcastRepostScheduler`
  (`nyxcash-broadcast-repost`, ShedLock + `JobExecutionService` — re-agenda
  `scheduled_at = now + interval` → PENDING; kill-switch
  `nyxcash.broadcast.repost-enabled`); `BroadcastProperties` + binding contra o
  yml real; E2E do fluxo due→logs→sent; **1591 testes verdes (0 falhas) +
  contexto smoke (Flyway V54 aplicada)**.

**`/spec-audit 79` (2026-08-13): Spec 79 `audited`** — `/spec-audit` 12/12
critérios OK, **1591/0/0**; 2 achados de documentação (nenhum bloqueante)
corrigidos: (1) requirements critério 12 alinhada ao design D10 — sem job de
due (NOW/SCHEDULED viram PROCESSING via pull do bot), métricas `nyx.job.*`
via `JobExecutionService`; (2) observação critério 10 — `content` sem
whitelist/limite de tags HTML (mitigado por `parse_mode: Markdown` no bot +
WAF Spec 63); detalhe no task.md §auditoria.
Próximo: Spec 80 (editor/UI do broadcast).

## Fase 27 — Tracking: perfis e disparo server-side de eventos (decisão do usuário 2026-08-12)

Análise externa (2026-08-12) do módulo de **trackeamento/eventos** do sistema
do irmão do usuário (perfis de rastreamento reutilizáveis que disparam eventos
**server-to-server** — Lead/InitiateCheckout/Purchase — para Facebook CAPI,
TikTok Ads, UTMify e Otimizey, com captura de UTM no início da conversa, fila
de envio, log de eventos e painel — PRD fornecido pelo usuário). O TrevoPay
**já tem a fonte dos eventos**: a Spec 75 projeta o funil `bot_leads` +
`bot_funnel_events` (`lead → checkout_started → paid` por bot, com
`transaction_id`). **Faltam**: captura de **UTM** (não existe em lugar
nenhum), os **perfis de tracking** (tokens dos provedores), os **clientes**
(CAPI v19, TikTok Events API, UTMify/Otimizey webhook), o **log de eventos**
e o **painel**.

**Decisões (melhores que o PRD externo):** tokens **criptografados em
repouso** (`CryptoConverter`, padrão Spec 74 — o PRD guarda em texto puro);
disparo via **outbox da Spec 35** (retry/backoff, sem fila nova — mesma
decisão do broadcast); `user_data` **hasheado** (email/phone SHA-256) + `fbp`/
`fbc` repassados; `event_logs` idempotente por `(profile_id, event_id)`.

**Dependências:** Spec 75 (funil — fonte dos eventos), Spec 74 (bots —
vínculo perfil↔bot), Spec 35 (outbox — dispatch), Spec 41 (auditoria),
Spec 46/73 (portal/UX).

| # | Name | Status | References |
|---|---|---|---|
| 81 | Tracking — **perfis e disparo server-side (backend)**: tabelas `tracking_profiles` (merchant_id, name, status, configs Facebook CAPI/TikTok/UTMify/Otimizey com **tokens criptografados em repouso**), `tracking_profile_bots` (vínculo perfil↔bot) e `event_logs` (ok/erros, idempotente); **captura de UTM** no início da conversa (o bot parseia `?utm_*` do link e persiste vinculado ao lead da Spec 75 — colunas novas em `bot_leads`); **disparo server-to-server** dos eventos do funil da Spec 75 (`lead`→Lead/PageView, `checkout_started`→InitiateCheckout, `paid`→Purchase com valor/currency) para **Facebook CAPI v19** (`POST /{pixel_id}/events`, `user_data` SHA-256 + fbp/fbc), **TikTok Events API** (`/open_api/v1.3/pixel/track/`) e **UTMify/Otimizey** (webhook com dados do pedido + UTMs), via **outbox da Spec 35** (retry/backoff, sem fila nova); ação de **sincronizar/teste**; métricas `nyx_tracking_*` (Spec 53); auditoria Spec 41 (**`implemented` 2026-08-14 — F0–F4 concluídas: migration V56 (tokens `CryptoConverter` + UTM em `bot_leads`), JPA/portas/adapters, domínio + `PlatformAction` `TRACKING_PROFILE_*`, CRUD portal (`PortalTrackingController` + command/query services + sync por provedor, 404 cross-lojista, token mascarado), captura de UTM no `/start` do Telegram (primeiro valor vence) com hook de tracking no `recordLead`, hooks `onLead/onCheckout/onPurchase` no funil (Spec 75) publicando `TRACKING_DISPATCH` no outbox por (perfil, evento), `TrackingDispatchHandler` interceptado no `NotificationProcessor` (ERROR em `REQUIRES_NEW` + retry/backoff/OUTBOX_DEAD), 4 adapters (`FacebookCapiClient` com `external_id` SHA-256, `TikTokEventsClient`, `UtmifyClient`/`OtimizeyClient` webhook genérico), métricas `nyx_tracking_*{provider}`, `TrackingProperties` + binding; decisões/desvios no task.md §Implementação — migration V56 (não V52), `event_id` derivado (estável e distinto por tipo — sem colisão no UNIQUE), Discord sem UTM (endpoint de interação não carrega link); **`mvn test` 1606/0/0** (+15); E2E (T4.3) pendente — agendado com o painel da Spec 82** · **`audited` 2026-08-14 — `/spec-audit` 12/12 critérios OK, 3 achados de código corrigidos, 0 abertos (detalhe no task.md §Auditoria): A1 — faltava o timer de latência `nyx_tracking_latency_seconds{provider}` no `TrackingDispatchHandler` (critério 11); A2 — base URLs dos provedores sem validação → `TrackingProperties.validate()` fail-fast (`@PostConstruct`: http(s), host, sem credenciais — SSRF-safe, critério 8) + 2 testes; A3 — PUT não permitia **desconfigurar** provedor de pixel (pixel null + token mantido → 400) → pixel null agora limpa o provedor inteiro, token null com pixel presente mantém (critério 3). `mvn test` **1608/0/0** re-executado (1591 → 1608; +17). Pendência registrada: E2E T4.3 (com o painel da Spec 82)**) | audited | [requirements](specs/81-tracking-eventos/requirements.md) · [design](specs/81-tracking-eventos/design.md) · [task](specs/81-tracking-eventos/task.md) · Visão V58 |
| 82 | Tracking — **painel (UI portal)**: página `TrackingPage` com **cards superiores de resumo** (perfis ativos, fluxos rastreados, total de eventos com sucesso), **card do perfil** com badges dos serviços conectados (Pixel/TikTok/UTMify/Otimizey), chips dos bots vinculados e **linha de status do log** (ok/erros em tempo real), ações **editar/duplicar/ativar/desativar/sincronizar/excluir** (ConfirmDialog Spec 73) e **modal de configuração** (pixel IDs, tokens mascarados, vínculo de bots); consumo dos endpoints da Spec 81; padrão da WebhooksPage (Spec 35) + UX da Spec 73 (**`implemented` 2026-08-14 — F0–F3: `TrackingPage` com 3 `SummaryCard` (perfis ativos/fluxos/eventos ok), cards de perfil com badges de serviço + chips de bots (Spec 74) + linha "X ok · Y erros", ações editar/duplicar/ativar/desativar/sincronizar/excluir (ConfirmDialog Spec 73, banners Spec 73), `TrackingProfileModal` com tokens mascarados (placeholder "•••• manter atual"; vazio = mantém, pixel vazio = desativa), `SyncResultModal` e `EventLogModal` paginado; rota `/tracking` + menu + SPA paths (`spaPublicPaths()`/`PortalSpaController` ganharam `/tracking`, `/tracking/*`); CSS novo em `styles.css`; `npm run build` (tsc) limpo no portal (65 módulos) + console rebuildado (ordem portal→console, achado Spec 73) + backend `mvn test` 1608/0/0; **`audited` 2026-08-14 — `/spec-audit` 9/9 critérios OK, 2 achados corrigidos, 0 abertos (detalhe no task.md §Auditoria): A1 — E2E não cobria Duplicar e Ativar/Desativar → teste 6 adicionado (duplicar com confirmação, toggle, badges/chips herdados; 3 ajustes de strict mode no locator); A2 — `POST /duplicate` quebrava em runtime (`Unresolved compilation problem` embutido no `.class` — build incremental mascarava `Set<UUID>` → `List<UUID>`; `mvn clean` expôs; fix: `replaceBotLinks` aceita `Collection<UUID>`). E2E `tracking.spec.js` **6/6 verde** + `npm run build` limpo + `mvn clean test` tracking 38/38**) | audited | [requirements](specs/82-tracking-painel/requirements.md) · [design](specs/82-tracking-painel/design.md) · [task](specs/82-tracking-painel/task.md) · Visão V59 |

**`/spec-plan` (2026-08-12): Specs 81–82 `planned`** — proposta apresentada
ao usuário após a análise do módulo externo: (1) a fonte dos eventos **já
existe** (Spec 75 — funil por bot); (2) Spec 81 = backend (perfis, UTM,
clientes, dispatch via outbox, log); (3) Spec 82 = painel no portal (cards,
badges, ações); (4) decisões: tokens criptografados, dispatch via outbox da
Spec 35 (sem fila nova), `user_data` hasheado. Visões V58–V59. Próximo:
`/spec-new 81`.

**`/spec-new 81–82` (2026-08-12): Specs 81–82 `created`** —
requirements/design/task criados em
`management/specs/{81-tracking-eventos,82-tracking-painel}/`:
- **81 (backend)**: `tracking_profiles` (tokens `CryptoConverter`),
  `tracking_profile_bots`, `event_logs` (UNIQUE profile_id+event_id) +
  `ALTER bot_leads` (UTM — V52); captura de UTM na integração (Spec 74 →
  `recordLead` da Spec 75, primeiro valor vence); dispatch do funil
  (`lead`→Lead, `checkout_started`→InitiateCheckout, `paid`→Purchase com
  valor/currency) via **outbox da Spec 35** (retry/backoff, sem fila nova);
  adapters com mock de contrato: Facebook CAPI v19 (`user_data` SHA-256 +
  fbp/fbc), TikTok Events API, UTMify/Otimizey (webhook SSRF-safe); `POST
  /{id}/sync` (teste por provedor); métricas `nyx_tracking_*`.
- **82 (painel)**: `TrackingPage` (resumo + cards de perfil com badges de
  serviço, chips de bots, linha "X ok · Y erros", ações
  editar/duplicar/ativar/desativar/sincronizar/excluir) + modal de
  configuração com **tokens mascarados** + log de eventos paginado; padrão
  WebhooksPage (Spec 35) + UX Spec 73.
Todas: sem rota de backend nova silenciosa; reuso Specs 75/74/35/73.
Próximo: `/spec-verify 81–82` (somente verificação, como nas Fases 25–26).

**`/spec-verify 81–82` (2026-08-12): Specs 81–82 `verified`** — critérios
checados contra o código real; **0 achados de precisão** (nenhuma correção
necessária). Verificações: migration **V52 livre** (não existem V49–V52 —
última real é a V48; V49/50/51 reservadas pelas Specs 74/75/79); **sem
conflito de tabelas** (`event_logs`/`tracking_profiles`/`tracking_profile_bots`
não existem); refs dos frontmatters existem (75/74/35/41/53/73/46); classes
ancoradas existem (`CryptoConverter`, `NotificationDispatcherService`,
`NotificationProcessor`); `WebhooksPage` do portal existe (padrão da Spec 82);
outbox real é `notification_outbox` (Spec 35 ✓); seção `// Bots (Spec 74)` do
`PlatformAction` ainda não existe (será criada pela Spec 74 — referência
consistente com o design verificado da 74); consistência interna
requirements↔design↔task (migration V52, mapeamento dos 3 eventos, adapters
com mock de contrato, sync por serviço, tokens mascarados na UI).
Próximo: `/spec-implement 74` (iniciar implementação pela dependência raiz —
aguardando decisão do usuário).

## Fase 28 — CRM: base de clientes e Live Chat em tempo real (decisão do usuário 2026-08-12)

Análise externa (2026-08-12) do módulo de **base de clientes (CRM) + chat em
tempo real** do sistema do irmão do usuário (central de leads/clientes com
nome/username/email/phone e status NOVO/PENDENTE/PAGO/BLOQUEADO, cards de
métricas, filtros, tabela com "Abrir Chat" e **Live Chat bidirecional** via
WebSocket — Socket.io — como ponte painel↔backend↔Telegram/Discord, com
persistência de conversa e detecção de bloqueio por 403 — PRD fornecido pelo
usuário). O TrevoPay **já tem a base**: `bot_leads` (Spec 75, com funil
`lead → checkout_started → paid`) e a integração real por plataforma
(Spec 74: webhook Telegram + gerenciador Discord + `TelegramBotApi`; Spec 52:
`sendMessage`/`user.send`). **Faltam**: campos de CRM no lead (nome/username/
email/phone), o **status derivado** (NOVO/PENDENTE/PAGO/BLOQUEADO), a
**ponte de chat** (enviar via REST + receber via webhook/manager), o **push
em tempo real**, `chat_messages`, a **detecção de bloqueio** e o **painel**.

**Decisão de arquitetura (melhor que Socket.io do irmão):** **SSE
(`SseEmitter`, built-in do spring-web — zero dependência nova) + Redis
pub/sub** para fan-out entre instâncias (padrão `RedisAlertCounterStore` com
fail-open in-memory, ADR-010) — **sem sticky sessions**, consistente com a
HA stateless (Spec 62). Enviar do painel = **REST** (auditável, Spec 41);
receber = webhook/Discord manager → persiste → SSE.

**Dependências:** Spec 75 (funil — derivação de status), Spec 74 (integração
de envio/recebimento), Spec 52 (bots), Spec 41 (auditoria), Spec 46/73
(portal/UX), Redis (Spec 43/66).

| # | Name | Status | References |
|---|---|---|---|
| 83 | CRM — **base de clientes e chat em tempo real (backend)**: `bot_leads` ganha nome/username/email/phone e `status` (**derivado do funil da Spec 75**: NOVO = só lead, PENDENTE = checkout sem paid, PAGO = paid, BLOQUEADO = 403 do Telegram/ban); tabela **`chat_messages`** (lead_id, sender CLIENT/ADMIN/BOT_AUTO, text, media, `is_read`); **ponte de chat** — enviar via REST (reusa `TelegramBotApi`/gerenciador Discord da Spec 74, `sendMessage`/DM) e receber via webhook Telegram (Spec 74 D5.3)/listener Discord → persiste + **push SSE**; **SSE `SseEmitter` + Redis pub/sub** (fan-out multi-instância, fail-open ADR-010 — sem Socket.io/sticky sessions); **detecção de bloqueio** (Telegram `403 bot was blocked` → lead `BLOQUEADO` automático + auditoria); endpoints de lista com busca/filtros/paginação e histórico do chat; métricas `nyx_crm_*` (Spec 53); auditoria Spec 41 (**`implemented` 2026-08-14** — migration `V57__crm.sql` fundida com a Spec 90, `BotLead`/`BotLeadJpaEntity` + `chat_messages`/`ChatMessageJpaEntity` + mappers/adapters, `CrmLeadStatus`/`ChatSenderType`/`LeadStatusDerivationService` (derivação na mesma transação do funil), `CrmLeadService` (lista/busca/summary/envio Telegram/read) + `PortalCrmController` (leads/messages/read/summary/stream SSE), `ChatEventPublisher` (SSE in-memory + Redis pub/sub fail-open, `CrmRedisConfig` condicional), `TelegramBotApi.sendMessage` + detecção 403 "bot was blocked" → `LeadBlockedException`/`LEAD_BLOCKED`; filtros dinâmicos via Specification — achado Spec 89 §1 (`IS NULL OR` quebrava no Postgres); `mvn test` **1640/0/0**; E2E `crm.spec.js` **3/3** + suíte completa **157/157**) (**`audited` 2026-08-14 — `/spec-audit` 12/12 critérios OK, 4 achados corrigidos, 0 abertos: (1) MÉDIO — métricas `nyx_crm_*` ausentes (C12) implementadas no `CrmLeadService` (`nyx_crm_messages_sent_total`/`_received_total`/`lead_blocked_total`) + `ChatEventPublisher` (`nyx_crm_sse_connected_total`) + teste novo `CrmLeadServiceTest` 4/4; (2) BUG — rota `/crm` fora do `SecurityConfig.spaPublicPaths()` + `PortalSpaController` (mesma classe do achado `/broadcasts` da Spec 80) corrigida nos dois; (3) MÉDIO — contagem/marcação de não lidas sem filtro de remetente (C11): `countByLeadIdAndReadFalse`/`markAllRead` contavam/marcavam TODAS (inclusive ADMIN — o indicador é CLIENT-only), exposto pelo E2E `crm.spec.js` teste 2; corrigido no `ChatMessageJpaRepository` (`countUnreadClient` + `AND senderType='CLIENT'`); (4) DOC — requirements C6/design D7 alinhados ao comportamento real (persistência CLIENT só em handoff HUMAN; BOT → `TELEGRAM_UPDATE` — decisão Spec 90 C3); `mvn test` **1644/0/0** re-executado + E2E completo verde — detalhe no task.md §Auditoria**) | audited | [requirements](specs/83-crm-chat/requirements.md) · [design](specs/83-crm-chat/design.md) · [task](specs/83-crm-chat/task.md) · Visão V60 |
| 84 | CRM — **painel (UI portal)**: página `CrmPage` com **cards superiores** (Novos, Pendentes, Pagos, Bloqueados, Total), **busca** (nome/@username/e-mail/ID) e **filtros** (bot, status, período Hoje/Ontem/Semana/Mês/Tudo), **tabela de clientes** (nome/username/ID, bot & fluxo, badge de status) e botão **"Abrir Chat"** → modal de **Live Chat** (histórico persistido, conexão **SSE** para mensagens em tempo real, campo de envio, indicador de **não lida** + sinal sonoro leve ao receber); consumo dos endpoints da Spec 83; padrão UX da Spec 73 (**`implemented` 2026-08-14** — `CrmPage` + rota `/crm` + menu "Clientes": `SummaryCard`×5, filtros (busca debounce/bot/status/período), tabela com badges + não lidas + "Abrir Chat"; `LiveChatModal` com histórico, envio REST, marcar lida e botão **Pausar/Retomar bot** (Spec 90); SSE via **fetch + ReadableStream** (o `EventSource` não envia o Bearer) — hook `useCrmStream` com reconexão; modal acessível (`useDialogFocus`); `npm run build` limpo; E2E `crm.spec.js` **3/3**; decisões no task.md §Implementação — beep do requirements 84 C6 deixado como pendência da auditoria) (**`audited` 2026-08-14 — `/spec-audit` 9/9 critérios OK, 2 achados corrigidos, 0 abertos: (1) C6 — sinal sonoro (beep) IMPLEMENTADO (pendência do task.md): `playBeep` Web Audio (oscilador 880Hz ~500ms, `AudioContext` lazy — sem autoplay problemático) no append de mensagem CLIENT do lead aberto + mute toggle no cabeçalho do modal (`aria-pressed`, `localStorage`); (2) MÉDIO — rota `/crm` fora do `SecurityConfig.spaPublicPaths()` + `PortalSpaController` (F5 de `/crm` → 401/404; mesma classe do bug `/broadcasts` da Spec 80) corrigida nos dois; `npm run build` (tsc) limpo no portal + console (ordem portal → console) + E2E `crm.spec.js` 3/3 — detalhe no task.md §Auditoria**) | audited | [requirements](specs/84-crm-painel/requirements.md) · [design](specs/84-crm-painel/design.md) · [task](specs/84-crm-painel/task.md) · Visão V61 |

**`/spec-plan` (2026-08-12): Specs 83–84 `planned`** — proposta apresentada
ao usuário após a análise do módulo externo: (1) a base (leads + funil +
integração por plataforma) **já existe** (Specs 74/75); (2) Spec 83 =
backend (status derivado, chat_messages, ponte de chat, SSE+Redis pub/sub,
detecção de bloqueio); (3) Spec 84 = painel no portal (cards, filtros,
tabela, Live Chat); (4) decisão: **SSE em vez de Socket.io** (zero
dependência, sem sticky sessions, fan-out via Redis). Visões V60–V61.
Próximo: `/spec-new 83`.

**`/spec-new 83–84` (2026-08-12): Specs 83–84 `created`** —
requirements/design/task criados em
`management/specs/{83-crm-chat,84-crm-painel}/`:
- **83 (backend)**: `ALTER bot_leads` (name/username/email/phone/status/
  updated_at) + `chat_messages` (V53); **status derivado** do funil da
  Spec 75 (NOVO→PENDENTE→PAGO; BLOQUEADO por 403/ban — detecção automática
  no envio + `LEAD_BLOCKED`); **ponte de chat** — enviar via REST (reusa
  Spec 74), receber via webhook Telegram (D5.3)/listener Discord → persiste
  → **push SSE**; **SSE `SseEmitter` (zero dependência) + Redis pub/sub**
  (fan-out multi-instância, fail-open ADR-010 — sem Socket.io/sticky
  sessions); endpoints lista/summary/histórico/read/stream.
- **84 (painel)**: `CrmPage` (cards Novos/Pendentes/Pagos/Bloqueados/Total,
  busca + filtros bot/status/período, tabela com badges + não lidas +
  "Abrir Chat") + `LiveChatModal` (histórico, **SSE** via `EventSource`,
  envio REST, beep com mute, marcar lida); padrão UX Spec 73.
Todas: sem rota de backend nova silenciosa; reuso Specs 75/74/52/73; Redis
existente (Spec 43/66). Próximo: `/spec-verify 83–84` (somente verificação).

## Fase 29 — ~~Frontend: Shadcn UI + responsividade~~ **DESCARTADA (decisão do usuário 2026-08-14)**

> **Descarte:** o usuário **desistiu do Shadcn UI** em 2026-08-14. Os dois
> frontends seguem com o padrão atual (CSS puro + `ui.tsx` — Spec 73), que já
> entrega todos os fluxos (Specs 46/71/72/73/76/80/82/84/89 `audited`). As
> specs 85/86/87 **nunca foram implementadas** (status `verified` →
> `discarded`; **nenhum código Tailwind/Radix existe** nos apps — verificado).
> Responsividade fica como **evolução futura** (spec nova, se necessário) sobre
> o padrão existente. Visões V62–V64 **não são entregues**.

Decisão original (2026-08-12, **revogada**): **adotar o estilo Shadcn UI em todo o
frontend** (console + portal) e **exigir responsividade**. Hoje os dois
frontends são CSS puro sem framework (console `styles.css` 538 linhas, portal
459 linhas; componentes custom em `ui.tsx` — Card/Field/Banners/Spinner/
Pagination — `ConfirmDialog`/`StepUpPrompt`/`Layout`; **sem Tailwind, sem lib
de UI** — terreno limpo para montar a fundação). A adoção envolvia: **Tailwind
CSS v4** + **componentes shadcn/ui** (Radix por baixo, copy-paste por app:
button/card/dialog/alert-dialog/input/label/select/table/badge/tabs/sheet/
dropdown-menu/sonner/skeleton), **tema claro + escuro** (CSS variables,
toggle persistido), **Layout responsivo** (sidebar → `Sheet` no mobile) e
**migração das 25 páginas** preservando a acessibilidade/UX da Spec 73
(banners `role=status/alert` → Sonner, `ConfirmDialog` → `AlertDialog`,
`StepUpPrompt` → `Dialog`) e os contratos HTTP (Spec 73: camada de
apresentação, sem mudança de contrato).

**Decisões do usuário (2026-08-12, revogadas pelo descarte):** (1) escopo = **os
dois frontends, execução com o portal primeiro** (é onde cairiam as telas novas
das Specs 76/80/82/84 — evitar retrabalho); (2) **dark mode sim** (claro +
escuro); (3) divisão em **3 specs**: 85 = fundação, 86 = migração console, 87 =
migração portal. **Ordem de execução: 85 → 87 → 86** (portal antes do
console, apesar da numeração). Riscos documentados: CSP do console/portal
pode precisar de ajuste para estilos inline do Radix (popper); seletor dos
E2E existentes (Specs 71/73) pode precisar de atualização com o novo markup —
ambos verificados na implementação (que não ocorreu).

**Dependências (do plano original, revogado):** nenhuma nova de backend; reuso
das Specs 46/71/73 (padrão de tela/UX) — a fase **precederia** a implementação
das Specs 76/80/82/84 (páginas novas nasceriam no padrão shadcn). Nota: as
Specs 76/80/82/84 foram implementadas no padrão existente (CSS puro) e estão
`audited`.

| # | Name | Status | References |
|---|---|---|---|
| 85 | Frontend — **fundação Shadcn UI**: Tailwind CSS v4 (`@tailwindcss/vite`) + componentes shadcn/ui base (button/card/dialog/alert-dialog/input/label/select/table/badge/tabs/sheet/dropdown-menu/sonner/skeleton) nos DOIS frontends; `globals.css` com CSS variables + **tema claro/escuro** (toggle + persistência + preferência do sistema); **Layout responsivo** (sidebar → `Sheet` no mobile, topbar com theme toggle, skip-link preservado); migração dos primitivos do `ui.tsx` (Card/Field/banners→Sonner/Spinner→Loader2-Skeleton/Pagination) e `ConfirmDialog`→`AlertDialog`/`StepUpPrompt`→`Dialog` reusando os mesmos componentes nos 2 apps; **zero mudança de contrato HTTP**; CSP validado p/ estilos inline do Radix; `npm run build` (tsc) limpo nos 2 frontends | discarded | [requirements](specs/85-frontend-shadcn-foundation/requirements.md) · [design](specs/85-frontend-shadcn-foundation/design.md) · [task](specs/85-frontend-shadcn-foundation/task.md) · Visão V62 |
| 86 | Frontend — **migração Master Console (14 páginas) para Shadcn UI**: Dashboard/Transactions/Merchants/MerchantDetail/Withdrawals/Financial/Receivables/Audit/Activity/Reconciliation/Lookup/Security/Profiles/Login migradas para os componentes da Spec 85; step-up TOTP e RBAC-aware preservados (Specs 69/71/72); **responsividade** (grids de métricas colapsam, tabelas com scroll horizontal/card no mobile, formulários empilham); acessibilidade da Spec 73 preservada (AlertDialog/Dialog nativos); E2E `master-console-ui`/`master-console-stepup` verdes (seletores atualizados se necessário, documentado) | discarded | [requirements](specs/86-frontend-console-shadcn/requirements.md) · [design](specs/86-frontend-console-shadcn/design.md) · [task](specs/86-frontend-console-shadcn/task.md) · Visão V63 |
| 87 | Frontend — **migração Portal (11 páginas) para Shadcn UI**: Dashboard/Charge/Sales/Wallet/Withdraw/Credentials/Destinations/Webhooks/Login/ForgotPassword/ResetPassword migradas; **execução antes da 86** (portal primeiro — decisão do usuário); **responsividade** (páginas de cobrança/formulários empilham no mobile, tabelas adaptam); banners→Sonner preservando feedback (Spec 73); E2E `portal-ui` verde (seletores atualizados se necessário) | discarded | [requirements](specs/87-frontend-portal-shadcn/requirements.md) · [design](specs/87-frontend-portal-shadcn/design.md) · [task](specs/87-frontend-portal-shadcn/task.md) · Visão V64 |

**`/spec-plan` (2026-08-12): Specs 85–87 `planned`** — proposta apresentada
ao usuário (estado atual = CSS puro, terreno limpo; adoção = Tailwind v4 +
shadcn/ui + tema claro/escuro + Layout responsivo; ordem estratégica: antes
das Specs 76/80/82/84 para as telas novas nascerem no padrão). Decisões:
escopo os dois (portal primeiro na execução), dark mode sim, 3 specs.
Visões V62–V64. Próximo: `/spec-new 85`.

**`/spec-new 85–87` (2026-08-12): Specs 85–87 `created`** —
requirements/design/task criados em
`management/specs/{85-frontend-shadcn-foundation,86-frontend-console-shadcn,
87-frontend-portal-shadcn}/`:
- **85 (fundação)**: Tailwind v4 + shadcn/ui base nos 2 apps; tema claro +
  escuro (CSS variables, toggle + localStorage + preferência do sistema,
  anti-FOUC); Layout responsivo (sidebar → `Sheet` em < lg); primitivos
  migrados com API compatível (`Card`/`Field`/banners→Sonner/`Spinner`→
  Loader2-Skeleton/`ConfirmDialog`→AlertDialog/`StepUpPrompt`→Dialog);
  CSP validado p/ Radix inline; **zero contrato HTTP**; aditiva (não quebra
  o CSS antigo até as migrações).
- **86 (console)**: 14 páginas migradas preservando step-up TOTP (Spec 72),
  RBAC-aware (Spec 69) e acessibilidade (Spec 73); responsividade (grids
  colapsam, tabelas com scroll/cards, forms empilham); E2E
  `master-console-ui`/`-stepup` verdes.
- **87 (portal)**: 11 páginas migradas; **execução antes da 86** (portal
  primeiro — as telas novas das Specs 76/80/82/84 nascem no padrão);
  ChargePage responsiva; credenciais com AlertDialog + Sonner (Spec 73);
  E2E `portal-ui` verde.
Todas: camada de apresentação (Spec 73) — sem mudança de contrato HTTP.
Próximo: `/spec-verify 85–87` (somente verificação, como nas fases
anteriores).

**`/spec-verify 83–87` (2026-08-12): Specs 83–87 `verified`** — critérios
checados contra o código real; **2 achados de precisão corrigidos nos
documentos** e **1 risco já resolvido**:
- **Achado 1 (Spec 83 design D2.1)**: `CREATE INDEX ix_bot_leads_status ON
  bot_leads (merchant_id?, status)` estava mal escrito (coluna `merchant_id?`
  inexistente — `bot_leads` não tem merchant_id, o vínculo é via
  `store_bots`, Spec 74) → corrigido para `ix_bot_leads_status ON bot_leads
  (status)` com nota explicando o join com `store_bots.merchant_id` nas
  queries do portal.
- **Achado 2 (Spec 85 design D6)**: o risco de CSP (estilos inline do Radix
  popper) **já está resolvido de fábrica** — o `SecurityConfig` (linha ~211)
  já inclui `style-src 'self' 'unsafe-inline'` (console e portal) → D6
  atualizado: **nenhum ajuste de CSP é necessário**; só validar o popper em
  E2E.
- **Verificações sem achado**: migration **V53 livre** (última real V48;
  V49–52 reservadas); refs dos frontmatters 83–87 existem (75/74/52/41/53/
  73/46/71/72/69/85); sem conflito de tabelas (`chat_messages`/`bot_leads`
  não existem — `bot_leads` virá da V50 da Spec 75); **`spring-boot-starter-
  web` presente** (SseEmitter disponível — zero dependência nova); E2E
  `master-console-ui`/`master-console-stepup`/`portal-ui` existem
  (`e2e-tests/tests/`); console 14 páginas / portal 11 páginas (bate com as
  specs 86/87); `ui.tsx` exports confirmados (Card/Field/SuccessBanner/
  ErrorBanner/Spinner/useAsync/Pagination) + `ConfirmDialog`/`StepUpPrompt`
  presentes; `styles.css` 538/459 linhas; `vite.config.ts` nos 2 apps.
Próximo: `/spec-implement 74` (iniciar implementação pela dependência raiz —
aguardando decisão do usuário).

**Descarte (2026-08-14, decisão do usuário):** as Specs **85–87 foram
`discarded`** — o usuário **desistiu do Shadcn UI**. Nenhum código
Tailwind/Radix foi criado (specs nunca implementadas); os frontends seguem com
o padrão atual da Spec 73 (CSS puro + `ui.tsx`). O achado de verificação da
Spec 83 (índice `ix_bot_leads_status`) permanece válido (spec 83 `audited`).
Responsividade continua como evolução futura fora do escopo atual.

## Fase 30 — Taxa de saque R$ 2,50 (decisão do usuário 2026-08-13)

Nova taxa fixa de **R$ 2,50 por saque do lojista** (Spec 88). Decisões do
usuário (2026-08-13): (1) incide em saques **manuais** (Specs 14/32/36) **e
automáticos** (Spec 70); (2) em saque FAILED no PSP o estorno devolve **valor +
taxa** (receita revertida — padrão do `ADMIN_RECOVERY_REVERSAL`, SEC-29); (3)
saque **administrativo do master** (recolhimento, Spec 34) **não paga** taxa.
A taxa entra como receita da plataforma no resumo financeiro do master (novo
bucket `withdrawalFees`, aditivo).

| # | Name | Status | References |
|---|---|---|---|
| 88 | Taxa de saque R$ 2,50 — débito de valor + taxa no create (manual e automático), reversão total no FAILED (`WITHDRAWAL_FEE_DEBIT`/`WITHDRAWAL_FEE_REVERSAL` no wallet + `WITHDRAWAL_FEE`/`WITHDRAWAL_FEE_REVERSAL` no ledger do master), saque administrativo isento, campo `fee` na resposta, `withdrawalFees` no summary do master | audited | [requirements](specs/88-withdrawal-fee/requirements.md) · [design](specs/88-withdrawal-fee/design.md) · [task](specs/88-withdrawal-fee/task.md) · Visão V65 |

**`/spec-plan` (2026-08-13): Spec 88 `planned`** — decisões do usuário definidas
acima (manuais + automáticos; devolve tudo na falha; admin isento). Próximo:
`/spec-new 88`.

**`/spec-new` + `/spec-verify` (2026-08-13): Spec 88 `verified`** —
requirements/design/task criados em `management/specs/88-withdrawal-fee/`
(decisões do usuário registradas: manuais + automáticos pagam 2,50; estorno
devolve valor + taxa com reversão de receita; admin isento). Verificação contra
o código real: `PricingProperties`/`application.yml` (padrão das taxas
existentes), `Money.add/subtract/of`, migration **V51** livre (V49/V50 usadas),
chamadores identificados (`WithdrawalService` manual, `AutoWithdrawRunnerService`
automático, `MasterWithdrawalService` admin), enums/ledgers/`RevenueSummary`/
`WithdrawalResponse` confirmados. Achados de precisão: nenhum nos docs. Próximo:
`/spec-implement 88`.

**`/spec-implement` + `/spec-audit` (2026-08-13): Spec 88 `audited`** —
**10/10 critérios OK, 1 achado de código corrigido, 0 abertos** (detalhe no
task.md §Auditoria). Implementação: config `withdrawal-fee: 2.50`,
migrations **V51** (`withdrawals.fee_amount`) e **V52** (alarga `entry_type`
para VARCHAR(30) — ver achado), `Withdrawal.fee` (domínio + entity + mapper),
ledgers (`WITHDRAWAL_FEE_DEBIT`/`WITHDRAWAL_FEE_REVERSAL` no wallet e
`WITHDRAWAL_FEE`/`WITHDRAWAL_FEE_REVERSAL` no master), persister (débito
valor+taxa no create manual/auto; auto saca `saldo − 2,50`; admin isento),
settlement (FAILED devolve valor+taxa e reverte a receita), revenue
(`withdrawalFees` no summary — Σ fee − Σ reversão — incluído em `total`/`net`),
`WithdrawalResponse.fee` (aditivo). **Achado (bug latente pré-existente):**
`wallet_ledger.entry_type`/`master_ledger.entry_type` eram VARCHAR(20) e
`WITHDRAWAL_FEE_REVERSAL` (23 chars) estourava no estorno FAILED — e
`ADMIN_RECOVERY_REVERSAL` (SEC-29) era o MESMO bug latente no master (qualquer
saque administrativo FAILED real daria 500; nunca exercitado em E2E); corrigido
pela V52 (VARCHAR(30) nas duas tabelas). **Validação:** `mvn test` **1465/0/0**
BUILD SUCCESS + E2E: journey **27/27** (13: 147,50/117,50; 14: 46,55/53,45/4,05;
16/17 com taxa; 18: `withdrawalFees` 7,50), psp-flow+reliability+auto-withdraw
**5/5** (117,50/617,50; auto 247,50+fee), master-console-stepup **5/5** (admin
isento). Fim do ciclo SDD (roadmap/requirements/design/task `audited`).

## Fase 31 — Lacunas do sistema do irmão: auditoria de broadcasts e handoff humano (decisão do usuário 2026-08-14)

Comparação (2026-08-14) com as specs **82** (auditoria de broadcasts no Master
Console) e **83** (transbordo humano / chat inbox) do sistema do irmão do
usuário. O TrevoPay já entrega o motor de broadcast (Spec 79 `audited`), o
editor (Spec 80 `audited`), o Master Console (Specs 71/72 `audited`) e o CRM
com Live Chat SSE (Specs 83/84 `verified`). A análise revelou **2 lacunas**:

1. **Auditoria de broadcasts no Master Console** — o console não tem tela
   `/broadcasts`; não existe endpoint de cancelamento forçado com trilha de
   operador (o `DELETE` do portal da Spec 79 só apaga fisicamente `PENDING`);
   não existe a ação `BROADCAST_FORCE_CANCELLED` na auditoria (Spec 41).
2. **Handoff humano no CRM** — não existe estado `BOT`/`HUMAN` por lead: o bot
   (Spec 52) continua respondendo ao funil/comandos mesmo quando o lojista está
   atendendo manualmente pelo Live Chat (Specs 83/84); o webhook (Spec 74 D5.3)
   não aborta o encaminhamento do update para o bot em atendimento humano.

Decisão do usuário: implementar as 2 capacidades como Specs 89 e 90 (mesmo
processo SDD — `planned` → `created` → `verified` → `implemented` → `audited`).

| # | Name | Status | References |
|---|---|---|---|
| 89 | Auditoria de Broadcasts no Master Console — leitura global paginada de todos os broadcasts da plataforma (filtros Lojista/Bot/Status/Modo/Data), inspeção de conteúdo (rich-text, mídia, botões, `broadcast_logs` por destinatário `SUCCESS/BLOCKED/FAILED`) e **force-cancel** (`POST /api/v1/master/broadcasts/{id}/force-cancel` → status `FAILED` com `error_message = 'Cancelled by Master Operator'`, apenas `PENDING` — 409 caso contrário; trilha `BROADCAST_FORCE_CANCELLED` com ID do operador — Spec 37 + nova permissão `broadcast.force_cancel` no catálogo da Spec 69 (9ª); RBAC nível OPERATOR sem step-up, padrão suspend/release da Spec 71); UI `/console/broadcasts` (tabela padrão Spec 73, filtros e drawer de detalhe); dependências: Specs 79/71/72/69/41/73 (**`implemented` 2026-08-14 — F0–F2: `MasterBroadcastQueryService` (list cross-merchant via Specification/subquery em `store_bots` + detail com logs agrupados), `MasterBroadcastCommandService.forceCancel` (RBAC OPERATOR + auditoria `BROADCAST_FORCE_CANCELLED` na mesma transação), `Broadcast.forceCancel` no domínio (`PENDING → FAILED`), 9ª permissão `broadcast.force_cancel`, controller `/api/v1/master/broadcasts` (list/detail/force-cancel, 404/409), `BroadcastsPage` + rota `/console/broadcasts` (menu, `spaPublicPaths`, `ConsoleSpaController`); `mvn test` **1619/0/0**; E2E `master-console-broadcast.spec.js` **3/3 verde** (lista cross-merchant, drawer com conteúdo+logs, cancelar PENDING → FAILED no banco + trilha do operador, botão ausente em PROCESSING/COMPLETED, 409 via API) + master-console-ui/stepup sem regressão (9/9); decisões no task.md §Implementação — Specification em vez de JPQL `IS NULL OR` (erro de inferência de tipo do Postgres) e seeds E2E via SQL + mock Telegram**) | audited | [requirements](specs/89-master-broadcast-audit/requirements.md) · [design](specs/89-master-broadcast-audit/design.md) · [task](specs/89-master-broadcast-audit/task.md) · Visão V66 |
| 90 | Handoff humano no CRM — estado `BOT`/`HUMAN` por lead (`bot_leads.handoff_status`, default `BOT`); webhook Telegram (Spec 74 D5.3) e listener Discord: lead em `HUMAN` → persiste `chat_messages` (sender `CLIENT`) + push SSE e **aborta o encaminhamento do update para o funil do bot** (não publica `TELEGRAM_UPDATE`); endpoints do portal (status + `PUT /api/v1/portal/crm/leads/{id}/handoff`) + botão "Pausar bot"/"Retomar bot" no Live Chat (Spec 84); auditoria `LEAD_HANDOFF` (Spec 41); dependências: Specs 83/84/74/75/52 (**`implemented` 2026-08-14** — migration `V57__crm.sql` fundida com a 83 (coluna `handoff_status` na mesma tabela); `LeadHandoffStatus` + `BotLead.pauseBot/resumeBot` idempotentes + `id` no domínio; interceptação no `TelegramWebhookController` (HUMAN → persiste `CLIENT` + SSE e **não publica** `TELEGRAM_UPDATE`; callbacks suprimidos) e no `FoxBotInteractionController` (interação Discord, `text` opcional); `LeadHandoffService` (toggle + auditoria `LEAD_HANDOFF` com before/after na mesma transação) + `PUT /api/v1/portal/crm/leads/{id}/handoff`; botão Pausar/Retomar no Live Chat (Spec 84); `mvn test` **1640/0/0**; E2E `crm-handoff.spec.js` **3/3** + suíte completa **157/157**; decisões no task.md §Implementação) (**`audited` 2026-08-14 — `/spec-audit` 9/9 critérios OK, 0 achados de código: verificação de todos os critérios no código real (migration V57 fundida, domínio idempotente, interceptação Telegram/Discord, `BOT_AUTO` não afetado, API com 404/400, auditoria `LEAD_HANDOFF`, UI Pausar/Retomar, testes E2E 3/3); `mvn test` **1644/0/0** re-executado + E2E completo sem regressão; observação: ordem de build portal → console revalidada (achado da Spec 73) — detalhe no task.md §Auditoria**) | audited | [requirements](specs/90-crm-handoff/requirements.md) · [design](specs/90-crm-handoff/design.md) · [task](specs/90-crm-handoff/task.md) · Visão V67 |

**`/spec-plan` + `/spec-new` + `/spec-verify` (2026-08-14): Specs 89–90
`verified`** — requirements/design/task criados em
`management/specs/{89-master-broadcast-audit,90-crm-handoff}/` (após a
comparação com o sistema do irmão) e verificados contra o código real. **Spec
89 — 5 achados de precisão corrigidos nos documentos**: (1) `Broadcast.fail()`
só aceita `PROCESSING` → force-cancel usa **novo método de domínio
`forceCancel()`** (`PENDING → FAILED`); (2) ação de MASTER → trilha da **Spec
37** (`MasterAuditAction.BROADCAST_FORCE_CANCELLED` via `MasterAuditRecorder`),
não `PlatformAction`/Spec 41 (essa é de MERCHANT/SYSTEM); (3) catálogo
`MasterPermission` ganha a **9ª permissão `broadcast.force_cancel`** (guard
tier-based → todo master ativo; `permissions[]` do `/master/me` inclui
automaticamente via `MasterPermission.values()`; touch-points de doc: javadoc
"7 operacionais" → 8 e Spec 69 "8 permissões" → 9); (4) `/broadcasts` do portal
**já** está no `spaPublicPaths()` (Spec 80) — falta só `/console/broadcasts` no
console; (5) `BroadcastRepository` é merchant-scoped → porta ganha `findById`
sem escopo + `searchMaster`. **Spec 90 — 1 achado corrigido**: o ponto real de
intercepção Discord é `POST /nyx/bots/{id}/interaction` →
`DiscordBotManager.recordInteraction` (Spec 75), não um listener interno.
Verificações sem achado: migration **V56 livre** (última real V55), refs dos
frontmatters existem (79/71/72/69/41/73 e 83/84/74/75/52), classes ancoradas
existem (`Broadcast`, `BroadcastRepository`, `BroadcastQueryService`,
`MasterReadController`, `MasterPermissionGuard`, `ConsoleSpaController`,
`SecurityConfig.spaPublicPaths`, `BotLead`/`BotLeadService`/
`BotLeadJpaEntity`, `DiscordBotManager`, `TelegramWebhookController`,
`PlatformAction`, `MasterAuditRecorder`), `invalid_broadcast_state`/
`broadcast_not_found` já tratados no `GlobalExceptionHandler`.
Próximo: `/spec-implement` (Spec 89 primeiro — sem dependência; Spec 90 junto
com o CRM 83/84).

**`/spec-audit 89` (2026-08-14): Spec 89 `audited`** — **10/10 critérios OK, 2
achados corrigidos, 0 abertos** (detalhe no task.md §Implementação — itens 7-8).
Verificações: listagem cross-merchant paginada com filtros e nomes resolvidos
(C1), detalhe com conteúdo/mídia/botões + logs agrupados (C2), force-cancel
PENDING → FAILED + 409/404 (C3), auditoria `BROADCAST_FORCE_CANCELLED` na mesma
transação (C4), RBAC leitura + force-cancel OPERATOR + 403 (C5), segurança/rate
limit/sem token do bot (C6), UI `/console/broadcasts` (tabela, filtros,
paginação server-side) (C7), drawer de detalhe com métricas e logs (C8), botão
"Cancelar Campanha" só em PENDING com ConfirmDialog danger + banners (C9),
testes unitários/integração + E2E (C10). **Achados corrigidos**: (1)
**acessibilidade** — o `BroadcastDetailModal` não tinha focus trap/Escape/
restauração de foco (padrão dos demais modais do console, Spec 73); aplicado
`useDialogFocus` e tsc revalidado; (2) **filtro de lojista por ID (não por
nome)** — o requirements C7/D5.2 diziam "busca por texto", mas o contrato da API
(C1/D2.1) só expõe `merchantId` (UUID); placeholder corrigido para "Lojista
(id)…" (busca por nome exigiria LIKE em `merchants.name` — desvio do contrato,
fora de escopo). Decisão mantida (task.md item 6): N+1 por item na listagem —
página de auditoria de baixa frequência, mesmo padrão do espelho master de bots.
`npm run build` (tsc) limpo no console (portal intacto) + `mvn test`
**1621/0/0** (+2 dos novos testes de filtro/403) + E2E
`master-console-broadcast.spec.js` **3/3** + suíte completa **151/151**
re-executados pós-fix. Observação de ambiente: um run intermediário da suíte
falhou 5 testes de webhook/Discord/CDN porque a app estava sem
`NYX_NOTIFICATION_ALLOW_LOOPBACK`/`NYX_DISCORD_API_BASE_URL`/
`NYX_BOTS_TELEGRAM_CDN_CHANNEL_ID` (env do CI) — reiniciada com o ambiente do CI,
suíte verde. Fim do ciclo SDD (roadmap/requirements/design/task `audited`).

**`/spec-implement` (2026-08-14): Specs 83/84/90 `implemented`** — CRM
(backend + painel) e handoff humano implementados juntos (dependência mútua;
migration **V57 fundida**: enriquecimento do `bot_leads` + `chat_messages` +
`handoff_status`). Backend 83: derivação de status na mesma transação do funil
(Spec 75), ponte de chat (Telegram `sendMessage` + 403 → `BLOQUEADO`), SSE
in-memory + Redis pub/sub (fail-open), endpoints leads/messages/read/summary/
stream. Painel 84: `CrmPage` (cards, filtros, tabela com badges/não lidas) +
`LiveChatModal` (histórico, envio, SSE via fetch+ReadableStream, marcar lida).
Handoff 90: `bot_leads.handoff_status` + interceptação no webhook Telegram
(HUMAN → CLIENT + SSE, sem `TELEGRAM_UPDATE`) e na interação Discord +
`PUT .../handoff` + botão Pausar/Retomar + auditoria `LEAD_HANDOFF`. Verificações:
`mvn test` **1640/0/0** (+19 novos) · E2E `crm-handoff.spec.js` **3/3** +
`crm.spec.js` **3/3** · suíte E2E completa **157/157** (zero regressão; um run
intermediário falhou 12 testes por estado transiente pós-reinício da app —
re-executado verde em foreground). Decisões registradas nos task.md
§Implementação (Specification para filtros dinâmicos — padrão Spec 89 §1;
envio do painel Telegram-only; SSE via fetch — EventSource não envia Bearer).
Próximo: `/spec-audit 83` e `/spec-audit 84` e `/spec-audit 90` — **executados
em 2026-08-14 (todas `audited`**: 83 12/12, 84 9/9, 90 9/9 — detalhe nas rows
acima e nos task.md §Auditoria). Fase 31 concluída — CRM + handoff LIVE.

## Plano de implementação (2026-08-10) — ordem das specs pendentes

Ordem definida com o usuário em 2026-08-09 para implementar tudo em uma sessão.
Princípios: **(1) otimizar antes de medir de novo** — a Spec 65 muda o baseline,
então a Fase 4 (re-benchmark) da 65 e a F8/F9 da 62 rodam na **MESMA campanha**
(1 única elevação + restauração de rate limit, economizando tempo operacional);
**(2) código antes de infra externa** — 65 e 64 não dependem de conta externa; a
63 depende de domínio/Cloudflare; **(3) specs que tocam os mesmos arquivos em
sequência** (65 → 63: Turnstile toca endpoints de auth já mexidos pela 65).

### Pré-requisitos externos (preparar antes de começar)

| Spec | Pré-requisito | Ação |
|---|---|---|---|
| 63 | Domínio próprio (ex.: `api.foxcash.example.com`) + conta Cloudflare (free) | planned |  |
| 64 | Destino do webhook de operador (Slack/Discord/e-mail) | planned |  |
| 62 F8/F9 e 65 F4 | Serviço `loadgen` (deletado ao fim da Fase 2B) | recriar: `railway up ./loadgen --path-as-root -s loadgen` + env (`BASE_URL=http://nyxcash.railway.internal:8080`, `STAGE`, `POOL`, `MAX_VUS`, `WEBHOOK_SECRET`, `DURATION=45s`) — runbook `load-tests/k6/distributed-runbook.md` |

### Ordem

1. **Spec 65 — F0→F1→F3 (código, sem dependência externa)** — manhã:
   baseline instrumentado → auth consolidado (1-2 queries/request) + throttle do
   `recordUse` → tuning do outbox (batch/poll/lotes por lock). `mvn test` + E2E
   verde. (F2 cache fica **GATED** — decide no item 2 com o re-benchmark.)
2. **Campanha de benchmark consolidada (62 F8/F9 + 65 F4)** — tarde, operacional:
   1× elevação de rate limit + HTTPS relaxado → **65 F4** (600/s e 750/s provam a
   otimização) → **62 F8** (teste de explosão 5k→20k com rate limit ATIVO) →
   **62 F9** (scale-out 2→4→8→16) → **restauração obrigatória**. Se a F4 mostrar
   que as leituras de auth ainda são gargalo → **65 F2** (cache curto-TTL).
3. **Spec 62 F10 + fechamento 65** — relatório final "TrevoPay Capacity v1" com o
   número **otimizado** (regra de ouro: o reportado é o medido); 62 → `audited`;
   65 → `implemented`.
4. **Spec 64 (código + runbook)** — eventos de segurança no webhook de operador
   (LOGIN_FAILURE_BURST/RATE_LIMIT_BURST/SIGNATURE_FAILURE_BURST/
   RATE_LIMIT_FAIL_OPEN) + runbook de incidente (referencia o edge da 63) +
   teste de mesa. `mvn test` + E2E.
5. **Spec 63 (edge/Cloudflare)** — DNS/WAF/edge rate limit/Turnstile no portal —
   **depois da 65** (Turnstile toca `merchant-auth`/`merchants`, mexidos pela
   65). Requer domínio/Cloudflare. Verificação com ataques simulados + rollback.

> Se o tempo não fechar: a **ordem mínima de valor** é 1 → 2 → 3 (65 + 62 F8/F9/
> F10 fecha a Spec 62 com o número otimizado). 64 e 63 ficam como bloco seguinte.

## Grafo de dependências

```
Spec 1 (ambiente) ──► Spec 2 (build + testes) ──► Spec 3 (commit inicial)
Spec 2 ──► Spec 4 (segurança + preview + E2E)
Spec 4 ──► Spec 5 (409 em documento duplicado — observação pós-auditoria)
Spec 5 ──► Spec 6 (scheduler de faturas)
Spec 6 ──► Spec 7 (notificação de fatura gerada)
Spec 6 ──► Spec 8 (dashboard/admin — usa faturas/lojistas)
Spec 8 ──► Spec 9 (fundação de produção — CI/CD, profile prod, deploy sem Docker)
Spec 9 ──► Spec 10 (integração com PSP real — em homologação)
Spec 10 ──► Spec 11 (adapter real Pagar.me — cobrança via API v5)
Spec 8/9 ──► Spec 12 (autenticação da API — protege o dashboard admin e os dados de lojistas)
Spec 12 ──► Spec 13 (perfil master — login usuário/senha + sessão)
Spec 13 ──► Spec 14 (saques — fluxo automático + bloqueio master)
Spec 13 ──► Spec 15 (dashboard master — transações, ganhos, taxas)
Spec 12/13/15 ──► Spec 16 (antifraude/compliance — indicadores de risco + suspensão; ADR-008)
Spec 12 ──► Spec 17 (~~KYC no onboarding~~ — DESCARTADA: sem KYC; gateway só vendedor, sem dados do comprador)
Spec 12 ──► Spec 18 (hardening de segurança — autorização por recurso, rate limit, validação de doc)
Spec 12 ──► Spec 20 (fundação do portal — auth do lojista no portal + convenções de resposta agregada)
Spec 20 ──► Spec 21 (dashboard do lojista — visão geral)
Spec 20 ──► Spec 22 (carteira/financeiro do lojista)
Spec 12/20 ──► Spec 23 (gerador de cobrança PIX + Gift PIX)
Spec 12 ──► Spec 24 (credenciais API — gestão de chaves do lojista)
Spec 12/20 ──► Spec 25 (vendas/operação comercial)
Spec 14/19/20/23 ──► Spec 26 (hardening financeiro — locks, estorno, contrato de cobrança, CI; auditoria QA 2026-08-04)
Spec 26 ──► Spec 27 (wallet ledger — movimentações imutáveis; base da Fase 5, diagnostico 2026-08-04)
Spec 27 ──► Spec 29 (estorno consistente — débito idempotente via ledger)
Spec 27 ──► Spec 30 (reconciliação — valida saldo vs ledger)
Spec 27 ──► Spec 32 (saque com destino — débito registra ledger)
Spec 31 ──► Spec 32 (saque exige destino cadastrado pelo lojista)
Spec 28 — independente (idempotência de cobrança; não depende do ledger)
Spec 27/29/32 ──► Spec 33 (fatura cobrada do wallet via ledger; estorno cria receivable; saque bloqueado por dívida)
Spec 27/33 ──► Spec 34 (fatura paga → receita da plataforma; receivable pago → RECEIVABLE_RECOVERY)
Spec 32 ──► Spec 34 (saque administrativo → ADMIN_RECOVERY)
Spec 7/29/32/33 ──► Spec 35 (outbox evolui com retry; eventos publicados nas origens)
Spec 10/11/32 ──► Spec 36 (contrato de Pix de saída; estado real PROCESSING/FAILED + estorno)
Spec 36 ──► Spec 38 (adquirente real pronto: PENDING na reconciliação, config fail-fast, teste de contrato)
Spec 5/12/18/20 ──► Spec 39 (cadastro sem CPF: e-mail único + senha escolhida; documento opcional/nulo para legado)
Spec 20/39 ──► Spec 40 (recuperação de senha: token self-service one-time via merchant-auth; revoga sessões do portal)
Spec 13/37 ──► Spec 41 (gestão de perfis master: flag can_manage_masters, limite 4, senha gerada 1x, auditoria)
Spec 37/40/20/12/19/14/33/35 ──► Spec 41 (relatório de atividades: trilha unificada platform_activity_log de MASTER/MERCHANT/SYSTEM)
Spec 13/14/19/32/33/34 ──► Spec 37 (auditoria nos pontos de ação do master)
Spec 19/23/28/35/41 ──► Spec 48 (stores + storefront — fonte de verdade da loja)
Spec 48 ──► Spec 49 (planos visuais + ações de entrega)
Spec 48/49 ──► Spec 50 (botões/menu configurável)
Spec 48/50 ──► Spec 51 (mensagens automáticas + agendamento)
Spec 35/48/49/50/51 ──► Spec 52 (bots renderizadores Discord + Telegram)
Spec 6/35 ──► Spec 53 (robustez de jobs: ShedLock como base + reuso do outbox p/ alertas)
Spec 35/53 ──► Spec 54 (alerta OUTBOX_DEAD: reusa o pipeline do outbox + webhook de operador)
Spec 43/53/60 ──► Spec 61 (escalabilidade horizontal e HA: rate limit Redis, shutdown graceful, readiness, réplicas Railway + healthcheck)
```

As specs 1 → 2 → 3 formam a sequência básica de fundação. A spec 4 depende do
build verde (spec 2) e da adaptação do schema via migration V2. A spec 5 nasce
da observação pós-auditoria da spec 4. As specs 6–8 são a evolução funcional:
scheduler (6), notificação (7, depende do evento de fatura da 6) e admin (8,
consulta sobre dados existentes).

## Estado atual (2026-08-05) — ciclo SDD completo

- **Specs 1–41: todas `audited`** (Spec 17 KYC `discarded` por decisão de
  produto). Backend TrevoPay **completo** na direção aprovada: fundação,
  produção, portal do lojista, hardening financeiro (ledger, idempotência,
  estorno, reconciliação), operação comercial (destinos de saque, saque via
  PSP, webhook de saída, faturamento, receitas do master), cadastro sem CPF,
  recuperação de senha e gestão de perfis master + relatório de atividades.
- **Verificação final (2026-08-05):** `mvn test` **418/0/0** e E2E **22/22**
  (cenário 22 auto-suficiente); migration **V33** aplicada via Flyway
  (`platform_activity_log` com eventos reais); rotas novas (perfis master e
  relatório de atividades) **não-documentadas** no README público (autorização
  por flag + auditoria — não obscuridade).
- **3ª auditoria de homologação (2026-08-05):** ✅ APROVADO (condicional) — 0
  Críticos/Altos, M-01 (DoS de memória por body sem teto) corrigido em
  `86af4ff`, backlog de baixos B-01..B-06 registrado.
- **Specs 42–47 (`verified`, 2026-08-05):** pendências pós-auditoria e decisões
  de produto spec'd e verificadas (`/spec-verify`) — 42 (baixos B-01..B-06),
  43 (Redis/M-05), 44 (MFA TOTP master/B-A5), 45 (e-mail do token de reset),
  46 (frontend do portal, inclui B-A8), 47 (adapter real de PIX de saída).
  Prontas para `/spec-implement`.
- **Spec 42 (`audited`, 2026-08-05):** backlog de baixos B-01..B-06 remediado —
  **B-01** `DummyCredentials` compartilhado: login do master executa BCrypt
  dummy em usuário inexistente (tempo uniforme, sem oráculo); **B-02**
  `reset-password` entra no `RateLimitFilter` (429 após flood, verificado ao
  vivo); **B-03** `pix_key` com `@Convert(CryptoConverter)` + backfill
  `PixKeyEncryptionRunner` (no boot, via SQL nativo — `v1:` no banco verificado
  ao vivo, leitura em claro pela API); **B-04** PSP de saque **fora da
  transação** — Fase A (`WithdrawalProcessingPersister`: débito + PROCESSING sem
  `external_reference` + ledger) e Fase B (`WithdrawalSubmissionPersister`
  REQUIRES_NEW grava a referência; falha síncrona → estorno via
  `settleFailedByWithdrawalId`), reconciliação re-tenta PROCESSING sem referência
  com o MESMO id; **B-05** webhook PAID com `amount` divergente → no-op + issue
  de reconciliação (sem creditar valor divergente); **B-06** lockout por conta no
  login do lojista (migration **V35**, `MerchantLoginLockoutPersister`
  REQUIRES_NEW, 5 falhas → 401 uniforme, verificado ao vivo). Migration **V35**
  aplicada no boot. **Auditoria do ciclo SDD: 6/6 critérios OK** — 1 achado
  corrigido no B-04 (o estorno passou a cobrir APENAS a falha síncrona do
  `createPayout`; antes, uma falha pós-aceite do PSP — ex.: no relatório de
  atividades — estornaria um saque já pago no adquirente → duplo pagamento) +
  teste `noReversalWhenPspAlreadyAcceptedAndLaterStepFails`. Aceitos por design:
  caminho de conta bloqueada (B-01/B-06) não executa BCrypt (espelha o B-08; não
  é oráculo de enumeração — o lockout só é atingível após a existência da conta
  ser conhecida); issue de reconciliação do B-05 reusa a OPEN existente do
  lojista (idempotência da Spec 30; o log WARN sempre registra a divergência).
  `mvn test` **479/0/0** e E2E **22/22** sem regressão.
- **Spec 43 (`audited`, 2026-08-05):** rate limit distribuído (M-05) —
  porta `RateLimitStore` (`tryAcquire`) com **2 implementações selecionáveis**
  via `nyxcash.security.rate-limit.store` (env `NYX_RATE_LIMIT_STORE`):
  `InMemoryRateLimitStore` (default `memory`, lógica extraída do filtro —
  single-instance sem Redis) e `RedisRateLimitStore` (multi-instância —
  **script Lua atômico** `INCR`+`EXPIRE` com TTL = `window-seconds`, chave
  `rl:{rota}|{ip}`; **fail-open** com `log.warn` throttled se o Redis cair —
  ADR-010). `RateLimitFilter` passou a injetar a porta (mesmo contrato 429).
  `pom.xml` ganhou `spring-boot-starter-data-redis`; placeholder
  `${NYX_RATE_LIMIT_STORE:memory}` no `application.yml` base (dev e prod);
  ADR-010 registrada; README documenta `NYX_RATE_LIMIT_STORE=redis` +
  `SPRING_DATA_REDIS_*`. `mvn test` **490/0/0** (11 novos) e E2E **22/22** sem
  regressão (default memory). **Smoke fail-open ao vivo:** `store=redis` sem
  Redis → 30×201 (nenhum 429/500) + 1 WARN throttled. Smoke multi-instância
  real (2 instâncias + Redis compartilhando contador) **adiado** — requer Redis
  ao vivo (WSL2/Docker indisponível nesta máquina); a atomicidade do `INCR` é
  coberta pelo teste do script Lua. **Auditoria do ciclo SDD: 6/6 critérios
  OK** — 1 achado de higiene corrigido (`spring.data.redis.repositories.enabled=false`
  no `application.yml`: sem repositórios Redis, o scanning era desnecessário e
  logava ruído no boot — verificado 0 ocorrências ao vivo). Aceitos por design:
  fail-open em qualquer `RuntimeException` (o rate limiter nunca derruba o
  gateway — ADR-010); smoke multi-instância real e teste com Redis embutido
  adiados (critérios "se viável"; sem Redis ao vivo nesta máquina, cobertos por
  mock do script Lua). `mvn test` **490/0/0** e E2E **22/22** sem regressão.
- **Specs 48–52 (`verified`, 2026-08-05):** **loja white-label multi-vendedora +
  bots renderizadores** (decisão do usuário): cada vendedor tem **sua loja**
  (1:1, modelagem 1:N) configurável no painel (identidade, logo/banner, redes
  sociais, cor/emoji, botões do menu, planos com imagem/cor do embed, mensagens
  automáticas e ações de entrega por plano); o backend é a **fonte de verdade**
  (`GET /nyx/storefront`) e os bots (Node.js/discord.js+grammY) apenas
  **renderizam** o fluxo — alterar a loja não reinicia bots. Fluxo: `/comprar` →
  escolher plano → cobrança → QR → paga → webhook confirma → bot entrega acesso
  automaticamente (**ações por plano**: cargo, mensagem, link, grupo, arquivo —
  com progresso por ação e retry). As specs 48/49 originais deste dia foram
  **descartadas** (`discarded`) e substituídas por 48 (stores/storefront), 49
  (planos + ações de entrega), 50 (botões/menu), 51 (mensagens) e 52 (bots).
  Visão V31/V32. **`/spec-verify` (2026-08-05): 5/5 specs `verified`** — 4
  achados corrigidos: (1) Spec 50 — payload do botão **sempre criptografado**
  (estava ambíguo); (2) Spec 51 — `audience` fechado em **BUYERS** (o bot é
  stateless e não tem lista de usuários; `ALL` = evolução), o endpoint devolve
  `recipients` (platform_user_id dos compradores); (3) Spec 49 — endpoint
  `POST /deliveries/{id}/retry` adicionado aos critérios de aceite; (4) Spec 52
  — dedup de `eventId` documentado como best-effort (a idempotência real é do
  backend). Prontas para `/spec-implement`.
- **Spec 48 (`audited`, 2026-08-05):** loja do vendedor + storefront —
  migration **V36** `stores` (id/merchant_id UNIQUE — 1:1 hoje, modelagem 1:N,
  name/logo_url/banner_url/description/social_links JSON/color/emoji/title/
  footer/created_at/updated_at) aplicada no boot. **Domínio** `Store`
  (create/restore/update com validações: nome 1–120, URLs ≤500, cor `#RRGGBB`,
  `social_links` ≤10 itens label 1–40/url http(s) ≤500) + `StoreRepository`.
  **Portal** (`ROLE_MERCHANT_PORTAL`): `GET/POST/PATCH /api/v1/portal/store`
  — merchantId sempre do principal (nunca do body); sem loja → **404
  `store_not_found`**; segundo POST → **409 `store_already_exists`**; PATCH
  parcial (null = não alterar) com 400 `invalid_argument`. **Storefront**
  (`GET /api/v1/nyx/storefront`, API key do lojista): DTO agregado `{store,
  buttons[], plans[], messages{}}` — seções das Specs 49/50/51 vazias nesta
  versão (contrato estável para o bot); principal não-UUID (admin/master) →
  403; sem loja → 404. **Auditoria `STORE_CREATED`/`STORE_UPDATED`** na mesma
  transação (Spec 41). **Cross-cutting fechado:** `SocialLink` inválido (item
  sem label/url) → 400 `invalid_argument` (era NPE/500); **rate limit no
  storefront** (Spec 43 — GET por IP+rota, 20/min default, 429 verificado ao
  vivo). `mvn test` **520/0/0** (30 novos: Store 1+8, StoreService 6,
  StorefrontService 2, PortalStoreController 6, FoxStorefrontController 3,
  RateLimitFilter +1) e E2E **23/23** (cenário 23 novo: criar/atualizar no
  portal, storefront via API key, 409 duplicada, 404 sem loja, 401/403,
  auditoria no banco) sem regressão.
- **Spec 49 (`audited`, 2026-08-05):** planos + ações de entrega — migration
  **V37** `plans`/`plan_delivery_actions`/`plan_deliveries`/
  `plan_delivery_actions_exec` + `pix_transactions.plan_id/platform/
  platform_user_id` (destinatário **criptografado em repouso** — backend =
  única fonte de verdade; bots stateless). **Planos (portal)**: CRUD +
  activate/deactivate com card visual (preço ≤ teto); listagem nunca devolve o
  payload (só `hasDeliveryActions`). **Ações por tipo** (GRANT_ROLE/
  SEND_MESSAGE/SEND_LINK/ADD_TO_GROUP/SEND_FILE): payload validado por tipo e
  **criptografado em repouso** (ex.: `v1:` verificado no banco pelo E2E); seq
  reordenável 0..N. **Compra** `POST /api/v1/nyx/plans/{id}/purchase`
  (API key): 201 com contrato do pix/cob + `planId`, `platform`/
  `platformUserId` obrigatórios (400), plano inativo 409 `plan_inactive`,
  inexistente 404, idempotência Spec 28, rate limit (Spec 43). **Entrega**:
  webhook PAID com plan_id → `plan_deliveries` PENDING + snapshot das ações em
  `plan_delivery_actions_exec` (MESMA transação; replay não duplica; plano sem
  ação → DELIVERED direto); `GET /nyx/deliveries/pending` + `by-transaction`
  (404 cross-loja; destinatário + payload **em claro apenas ao dono**);
  `POST .../actions/{seq}/complete` (gate só PENDING, replay 204; todas DONE →
  DELIVERED; qualquer FAILED → FAILED) e `POST .../retry` (reabre FAILED →
  PENDING, idempotente). **Storefront** agora devolve os planos ATIVOS
  (card — sem payload). **Atividade** PLAN_CREATED/PLAN_PURCHASED/
  DELIVERY_ACTION_COMPLETED/DELIVERY_COMPLETED (Spec 41); payloads/
  platformUserId mascarados nos logs. `mvn test` **578/0/0** (58 novos) e E2E
  **24/24** (cenário 24: plano 3 ações → purchase → PAID → 3 PENDING → 1,2
  DONE → 3 FAILED → entrega FAILED → retry → DONE → DELIVERED; replay 204;
  404 cross-loja; 409 plano inativo; `v1:` no banco) sem regressão.
- **Spec 50 (`implemented`, 2026-08-06):** botões, menu e fluxo configurável —
  migration **V39** `store_buttons` (id/store_id FK/label ≤80/action ≤30/payload
  TEXT **criptografado**/position/active/created_at + unique `(store_id,
  position)` + índice) aplicada no boot. **Domínio** `StoreButton`
  (create/restore/update/setActive/moveTo) + enum `StoreButtonAction`
  (BUY/PREVIEW/FAQ/SOCIAL/LINK/CLOSE) + `StoreButtonRepository`.
  **`StoreButtonPayloadCodec`**: payload validado por tipo (BUY/CLOSE sem
  payload — null; PREVIEW `mediaUrls[]` http(s) ≤500 ≤10 itens; FAQ `text`
  ≤1000; SOCIAL/LINK `url` http(s) ≤500 + `label` ≤80) e serializado em JSON
  **sempre criptografado em repouso** (CryptoConverter — `v1:` no banco,
  verificado pelo E2E). **Portal** (`ROLE_MERCHANT_PORTAL`):
  `GET/POST /api/v1/portal/store/buttons` (cria no fim — position = max+1;
  **máx. 20 → 409 `too_many_buttons`**), `PATCH/DELETE /{id}` (DELETE reindexa
  posições 0..N), `PUT /order` `{ids[]}` (valida pertencimento à loja — 404
  `button_not_found` cross-loja sem revelar existência —, tamanho exato e sem
  duplicados; recalcula positions 0..N); 400 `invalid_argument` em action/payload
  inválidos. **Storefront** (`GET /api/v1/nyx/storefront`, Spec 48) agora
  devolve `buttons[]` **ATIVOS ordenados** `{id, label, action, payload,
  position}` (payload em claro ao dono via API key — o bot renderiza o menu).
  **Auditoria** `BUTTON_CREATED`/`BUTTON_UPDATED`/`BUTTON_DELETED` na mesma
  transação (Spec 41) — trilha **sem payload** (só label/action, nunca em
  claro); handlers 404 `button_not_found`/409 `too_many_buttons` no
  `GlobalExceptionHandler`. Rate limit do storefront já coberto (Spec 43, GET
  por IP). `mvn test` verde (**623/0/0** — 45 novos/atualizados: StoreButtonTest
  9, StoreButtonPayloadCodecTest 6, StoreButtonServiceTest 13,
  PortalStoreButtonControllerTest 9, storefront 2+3 atualizados) e E2E
  **25/25** (cenário 25 novo: menu customizado — criar BUY/SOCIAL/FAQ, 400 em
  action/payload inválidos, PATCH, desativar → fora do storefront, reordenação
  204 com positions 0..N, 404 `button_not_found` cross-loja, DELETE 204, `v1:`
  no banco, auditoria BUTTON_CREATED/UPDATED/DELETED) sem regressão. **Fix
  encontrado no E2E:** a reordenação (`PUT /order`) violava a unique
  `(store_id, position)` com o `save` simples (o Hibernate agrupa os UPDATEs no
  flush final) → 500; corrigido com `saveAndFlush` (flush imediato) em **duas
  passadas** (desloca todos para posições temporárias `i + n`, depois move para
  a ordem final).
- **Spec 51 (`implemented`, 2026-08-06):** mensagens automáticas + agendamento
  (fecha o backend da loja) — migration **V40** `store_messages` (unique
  `(store_id, event)` — 1 mensagem por etapa) + `store_scheduled_messages`
  (índice `(store_id, status, scheduled_at)`). **Domínio** `StoreMessage`
  (create/restore/update — event enum, text 1–1000) + `ScheduledMessage`
  (create/restore/markSent/isDue — XOR scheduledAt/cron, cron 6 campos
  **Quartz validado** via `CronExpression`, audience BUYERS nesta versão).
  **Portal** (`ROLE_MERCHANT_PORTAL`): `GET/POST /api/v1/portal/store/messages`
  — **upsert por event** (201 cria / 200 atualiza, nunca duplica a etapa) —
  `PATCH/DELETE /{id}` (404 `message_not_found` cross-loja) e
  `POST .../messages/schedule` `{text, scheduledAt | cron, audience: BUYERS}`
  (400 `invalid_argument` em XOR/cron/audience inválidos). **Bot/API key**
  (`ROLE_MERCHANT`): `GET /api/v1/nyx/scheduled-messages?dueBefore=<ISO>`
  devolve as **vencidas** (one-shot `scheduled_at ≤ dueBefore`; cron pelo
  **próximo disparo** após a criação — `CronExpression.next` em UTC) **com
  `recipients`** = `platform_user_id` dos compradores com entrega **DELIVERED**
  da loja (Spec 49 — deduplicados, em claro ao dono via API key; criptografados
  em repouso); `POST /{id}/sent` → 204 **idempotente** (SENT com `sent_at`;
  replay não re-entrega). **Storefront** (Spec 48) agora devolve `messages{}`
  `{event: text}` das etapas **ATIVAS** (o bot renderiza welcome/
  awaiting_payment/payment_received/delivered no fluxo). **Auditoria**
  `MESSAGE_CREATED/UPDATED/DELETED/SCHEDULED` (Spec 41) na mesma transação — o
  **text nunca vai em claro** para a trilha/logs (a atividade registra só o
  event/tipo; logs já mascaravam a chave "text"). `mvn test` verde
  (**675/0/0** — 52 novos: StoreMessageTest 8, ScheduledMessageTest 9,
  StoreMessageServiceTest 12, ScheduledMessageQueryServiceTest 6, controllers
  +18, storefront/atualizados) e E2E **26/26** (cenário 26 novo: upsert
  WELCOME/FAQ 201+200, 400 event/text, PATCH/DELETE 404 cross-loja, storefront
  com ativas, schedule one-shot+cron+400s, bot busca vencidas com
  `recipients` contendo o comprador entregue do cenário 24, sent 204+replay,
  some das vencidas, 404 cross-loja, banco SENT/sent_at + auditoria
  MESSAGE_*) sem regressão. **Fix de teste:** `CronExpression.next` exige
  `ZonedDateTime` (Instant não suporta DAY_OF_WEEK) — `isDue` avalia o cron em
  UTC.
- **Spec 52 (`audited`, 2026-08-06):** bots renderizadores (Discord +
  Telegram) — monorepo `bots/` (Node.js + TypeScript, workspaces core/
  discord/telegram) **100% stateless** (sem SQLite/JSON — PostgreSQL do TrevoPay
  é a única fonte de verdade; recuperação pós-falha via polling da API, Spec
  49). **Core** (`@nyxcash/bot-core`, sem dependência de plataforma):
  `config.ts` (dotenv tipado, fail-fast; `DISCORD_TOKEN`/`GUILD_ID`/
  `TELEGRAM_BOT_TOKEN`/`WEBHOOK_SECRET`/`NYX_API_KEY` etc. só por env),
  `types.ts` (contrato Specs 48–51), `logger.ts` (JSON estruturado — payload/
  secret/platformUserId nunca logados), `nyxcash-client.ts` (`X-Api-Key` em
  todas as chamadas; `FoxCashApiError` 404/409/429; **429 re-tenta com backoff
  exponencial 500ms→1s**), `storefront.ts` (cache 30s), `signature.ts`
  (HMAC-SHA256 do raw body + `timingSafeEqual`, Spec 35), `dedup.ts` (`eventId`
  TTL 10min best-effort — idempotência real é do backend), `sales.ts`
  (`buyerId = sha256(platform:userId)` + `idempotencyKey` `compra:{userId}:{ts}`,
  Spec 28), `payload.ts` (espelha os codecs do backend: botões Spec 50
  `{mediaUrls[]}/{text}/{url,label?}` e ações Spec 49
  `{roleId}/{text}/{url,label?}/{groupId}/{fileUrl}` + variáveis `{nome}`/
  `{plano}`), `delivery-actions.ts` (executa ação → `completeAction` DONE/
  FAILED+note; falha não interrompe as demais; fila de retry em memória com
  backoff 1s→2s→5s→10s, máx. 5 — via `retryDelivery` Spec 49; **`onDelivered`
  dispara a mensagem `delivered` do storefront ao comprador quando nenhuma ação
  falha**), `scheduler.ts` (polling `deliveries/pending` **filtrado por
  plataforma** + `scheduled-messages?dueBefore=` com `markScheduledSent`
  idempotente — Spec 51) e `webhook-server.ts` (express, **raw body
  preservado**, HMAC tempo constante → 401, dedup por `X-FoxCash-Event-Id` →
  replay 204, dispatcher `SALE_PAID` → entrega por transação; 500 → retry com
  backoff do backend). **Bot Discord** (discord.js): `/comprar` com embeds dos
  planos (cor/imagem/preço) + botões `buy:{planId}`, QR (PNG) + Copia e Cola +
  `awaiting_payment`, entrega `grant_role`/`send_message`/`send_link`/
  `send_file` via DM (Design §3 — `add_to_group` → FAILED `unsupported`),
  `/admin status` com ACL (`ADMIN_DISCORD_IDS`). **Bot Telegram** (grammY):
  `/start`/`/comprar` com inline keyboard, foto QR + Copia e Cola, entrega
  `add_to_group` (convite)/`send_message`/`send_link`/`send_file` via DM
  (`grant_role` → FAILED `unsupported`), `/admin` com ACL. **Testes vitest
  48/48** (signature, dedup, client — incl. backoff 429 —, storefront, sales,
  payload, delivery-actions — incl. `onDelivered` — com timers fake) e
  `tsc --noEmit` limpo nos 3 workspaces. **Smoke E2E** (`bots/scripts/smoke.ts`,
  contra app dev real — loja/plano com ação SEND_MESSAGE/webhook criados via
  API do portal): storefront → comprar → QR → PSP webhook PAID (delivery
  PENDING) → `SALE_PAID` assinado → HMAC + dedup → ação DONE → **DELIVERED**;
  replay do mesmo `eventId` → 204 no-op; assinatura divergente → **401**;
  polling com scheduler novo (simula reinício) → recupera pendência →
  **DELIVERED** — tudo verde. **README `bots/`** com setup, tokens, URL pública
  do webhook, registro no portal (Spec 35) e contrato de assinatura. `mvn test`
  backend **675/0/0** sem regressão (Specs 48–51 intactas). **Auditoria do
  ciclo SDD: 8/8 critérios de aceite OK** — 2 achados corrigidos: (1) design §4
  exigia "backoff no client" em 429 e o `FoxCashClient` só lançava o erro tipado
  → retry com backoff exponencial (500ms→1s, 2 retries, `sleep` injetável) + 2
  testes; (2) o requirements exige "quando todas DONE → `delivered` (mensagem)
  → DELIVERED" e o `DeliveryActions` não enviava a mensagem da etapa → hook
  `onDelivered` (dispara só com 0 falhas; bots Discord/Telegram enviam a
  mensagem `delivered` do storefront com `{nome}`/`{plano}`) + 2 testes. Fim do
  ciclo SDD (roadmap/requirements/task `audited`).
- **Spec 50 (`audited`, 2026-08-06):** auditoria do ciclo SDD de botões/menu
  (implementada em 2026-08-06). Verificação **ao vivo** contra o app dev (loja
  smoke com API key real): **16/16 checks OK** — POST 201 (BUY/SOCIAL com
  `position` no fim), GET ordenado com positions numéricas, PATCH label 200,
  `PUT /order` reordena 0..N (204), validações 400 (label vazio, PREVIEW sem
  `mediaUrls`), **limite de 20 botões → 409 `too_many_buttons`** (21º), 404 em
  PATCH/DELETE de botão inexistente/cross-loja, storefront devolve `buttons[]`
  ativos ordenados (Spec 48) e botão removido some dele. Auditoria Spec 41
  (`BUTTON_CREATED/UPDATED/DELETED` na mesma transação) confirmada nos testes
  unitários (`StoreButtonServiceTest`). `mvn test` **675/0/0** e E2E **25/25**
  (documentados na implementação) sem regressão. **0 achados de auditoria.** Fim
  do ciclo SDD (roadmap/requirements/task `audited`).
- **Spec 51 (`audited`, 2026-08-06):** auditoria do ciclo SDD de mensagens
  automáticas + agendamento (implementada em 2026-08-06). Verificação **ao vivo**
  contra o app dev: **20/20 checks OK** — upsert por event (201 cria / 200
  atualiza, **nunca duplica** — 1 WELCOME na lista), validações 400 (event
  inválido, text vazio), agendamento one-shot + cron válidos (201), cron inválido
  400, `scheduledAt`+`cron` (XOR) 400, bot via API key `GET
  /nyx/scheduled-messages?dueBefore=` devolve a vencida **com `recipients[]`**
  (10 — platform_user_id dos entregues), `POST /{id}/sent` 204 + replay
  idempotente e a mensagem **some das vencidas**, storefront `messages{}` com
  `welcome` renderizável (`{nome}`), PATCH 200 / DELETE 204. Auditoria Spec 41
  (`MESSAGE_CREATED/UPDATED/DELETED/SCHEDULED` na mesma transação, texto nunca
  em claro) confirmada nos testes unitários (`StoreMessageServiceTest`). `mvn
  test` **675/0/0** e E2E **26/26** (documentados na implementação) sem
  regressão. **0 achados de auditoria.** Fim do ciclo SDD
  (roadmap/requirements/task `audited`). **Com isso, toda a Fase 11 (loja
  white-label multi-vendedora + bots renderizadores, Specs 48–52) está
  `audited` — backend e bots LIVE.**
- **Spec 45 (`implemented`, 2026-08-06):** entrega do token de reset por
  e-mail (fecha a pendência da Spec 40 — em prod a recuperação de senha estava
  inutilizável: token gerado mas nunca entregue). **Porta**
  `domain/port/out/ResetTokenDeliverer` (`deliver(email, resetToken, expiresAt)`)
  com 2 adapters selecionáveis por `nyxcash.notification.reset-token.deliverer`:
  `LoggingResetTokenDeliverer` (dev, `log`) e `SmtpResetTokenDeliverer` (prod,
  `smtp` — `spring-boot-starter-mail` + JavaMailSender; e-mail com link
  `{base-url}/reset?token=...&email=<url-encoded>` e instruções; token nunca no
  assunto). **`NotificationProperties`** ganhou o bloco `reset-token`
  (`deliverer` default `log`, `base-url`, `email.*` host/port/username/password/
  from) + **`validate()` fail-fast de segurança: `deliverer=log` no profile
  `prod` → boot falha** (o token de reset é credencial de conta e nunca vai
  para logs de produção). **`PasswordResetService.forgot`**: e-mail existente →
  gera token (Spec 40) + **entrega**; e-mail inexistente → **entrega dummy**
  (`nao-existe@nyxcash.invalid`, padrão do dummy BCrypt — uniformiza o timing,
  anti-enumeração); **falha do SMTP é logada e o pedido segue 204** (o gateway
  nunca cai por e-mail quebrado — decisão de disponibilidade). Config:
  `application-dev.yml` (deliverer=log) e `application-prod.yml` (deliverer=smtp
  + `spring.mail` de `NYX_RESET_TOKEN_SMTP_*` + `NYX_PORTAL_BASE_URL`); README +
  runbook Railway com as env vars. `mvn test` **686/0/0** (11 novos:
  `PasswordResetServiceTest` +3 — entrega existente/dummy/falha SMTP preserva o
  token —, `SmtpResetTokenDelivererTest` +4 — link com token, URL-encode do
  e-mail, SMTP não configurado, falha do sender —, `NotificationPropertiesTest`
  +4 — fail-fast) sem regressão. **Smoke ao vivo** (dev, porta 8081):
  forgot-password existente → 200 + token `rst_...` → reset-password → **204**;
  e-mail inexistente → **204** (dummy). Fim do ciclo SDD
  (roadmap/requirements/task `implemented` — pronto para `/spec-audit`).
- **Spec 45 (`audited`, 2026-08-06):** auditoria do ciclo SDD da entrega do
  token de reset por e-mail (implementada em 2026-08-06). Verificação **ao
  vivo** contra o app dev (porta 8081): **8/8 checks OK** — (1) forgot-password
  e-mail existente → **200 com token** `rst_...` (dev, `expose-reset-token=true`)
  **e o token entregue no log** (`LoggingResetTokenDeliverer`); (2)
  reset-password com o token → **204** e login com a senha nova → **200**
  (senha realmente trocada); (3) **token reusado → 400 `invalid_token`**
  (single-use, Spec 40); (4) e-mail inexistente → **204 + entrega dummy**
  (`nao-existe@nyxcash.invalid` com `rst_0000...` no log — timing uniforme,
  anti-enumeração mantida); (5) **rate limit do forgot-password**: flood → 429
  (Spec 40/B-02, sem spam de e-mails); (6) **fail-fast de segurança ao vivo**:
  boot `prod` com `NYX_RESET_TOKEN_DELIVERER=log` → **APPLICATION FAILED** com
  a mensagem exata (`nyxcash.notification.reset-token.deliverer=log e proibido
  em producao... Configure deliverer=smtp` — `NotificationProperties.validate`); 
  (7) **disponibilidade**: boot `prod` com `deliverer=smtp` (default) e **sem
  credenciais SMTP → app SOBE** (HTTPS 8443, 401 em rota protegida — e-mail
  quebrado não derruba o gateway); (8) **token nunca em resposta HTTP em prod**
  (`expose-reset-token: false` + controller devolve 204 quando
  `!exposeResetToken || !emailFound`). `mvn test` **728/0/0** (Spec 45:
  `PasswordResetServiceTest` 13 — entrega existente/dummy/falha SMTP preserva o
  token —, `SmtpResetTokenDelivererTest` 4 — link, URL-encode, SMTP não
  configurado, falha do sender —, `NotificationPropertiesTest` 4 — fail-fast)
  e E2E **27/27** sem regressão (cenário 21 = forgot/reset one-time; suite
  completa contra o app dev). **0 achados de auditoria.** Fim do ciclo SDD
  (roadmap/requirements/task `audited`).
- **Spec 46 (`audited`, 2026-08-08):** frontend do portal do lojista — ciclo
  SDD fechado (implementado em `0f20745`; auditoria concluída em 2026-08-08).
  **SPA React 18 + TypeScript + Vite** em `portal/` (thin client sobre as APIs
  do portal, Specs 20–25/31/32/35/40): build → `src/main/resources/static`
  servido pelo próprio Spring Boot com `nyxcash.portal.serve-frontend=true`
  (`PortalSpaController` faz fallback das rotas client-side → `/index.html`).
  **Telas:** login/forgot/reset (públicas) + dashboard (Spec 21), carteira
  (22), cobrança avulsa + Gift PIX com QR/Copia e Cola (23, com
  `idempotencyKey` via `crypto.randomUUID` — Spec 28), vendas (25), destinos
  mascarados (31 — **o valor em claro nunca chega ao DOM**, só `mask`), saque
  com destino (32/36), webhooks (35) e credenciais (24). **Auth no cliente:**
  token opaco em `sessionStorage` (nunca `localStorage`), `Authorization:
  Bearer`, interceptor 401 → logout local + redirect `/login` (B-A8: 401
  uniforme mantido no backend; a UX distingue contexto no cliente — rota
  pública = credenciais inválidas, rota protegida = sessão expirada).
  **`SecurityConfig`:** CSP da SPA (`default-src 'self'; script-src 'self';
  style-src 'self' 'unsafe-inline'; img-src 'self' data:`) **condicional** ao
  flag (off = API pura com CSP `default-src 'none'`); CORS apenas para
  `nyxcash.portal.cors-allowed-origins` (vazio = desabilitado); rotas da SPA e
  assets públicos só com `serve-frontend=true`. **E2E browser**
  `portal-ui.spec.js` ampliado na auditoria para cobrir o critério 2 do
  requirements — fluxo completo **login → dashboard → cobrança (QR/Copia e
  Cola) → venda paga (webhook PAID → APPROVED na listagem de vendas) →
  destinos (máscara no DOM, sem valor em claro) → saque com destino →
  logout → redirect → 401**. **Verificação:** `npm run build` do portal OK;
  `mvn test` **1101/0/0** sem regressão; E2E **105/105** (app dev com
  `NYXCASH_PORTAL_SERVE_FRONTEND=true` + `NYX_NOTIFICATION_ALLOW_LOOPBACK=true`
  — envs do CI; suite completa). README ganhou a seção "Portal do lojista
  (SPA — Spec 46)" (build/run). **Achados de auditoria corrigidos:** (1) E2E
  browser não cobria "venda paga → saque" exigido no requirements (critério 2)
  → fluxo ampliado no spec; (2) README sem docs de build/run do portal (task
  pendente) → seção adicionada. Fim do ciclo SDD (roadmap/requirements/task
  `audited`).
- **Spec 47 (`implemented`, 2026-08-06):** adapter real de PIX de saída
  **Pagar.me** (API v5 — fecha a pendência da Spec 38 "implementar o adapter
  quando o adquirente for definido"). **Porta** `PayoutProviderClient` ganhou
  exceções tipadas (`PayoutProviderException` + `PayoutProviderTimeoutException`).
  **`PagarMePayoutProviderClient`** (infrastructure/outbound/pix,
  `@ConditionalOnProperty(nyxcash.psp.payout-provider=pagarme)`, auth Basic com
  `payout-pagarme.secret-key` — fail-fast da Spec 38, timeouts 5s/10s):
  `createPayout` → `POST {base-url}/transfers` com `code` = withdrawal.id
  (idempotente no adquirente, padrão Spec 28) e `pix: {pix_key, pix_key_type}`;
  `getPayout` → `GET /transfers/{id}` mapeia `processed`→PAID,
  `failed`/`canceled`→FAILED, demais→PENDING (Spec 38: PENDING não liquida).
  **`pix_key_type` inferido do formato canônico da chave** (CPF 11 dígitos →
  `cpf`, CNPJ 14 → `cnpj`, `+55...` → `phone`, UUID → `evp` — a porta não
  carrega o tipo; **zero mudança** em `WithdrawalService`/
  `WithdrawalSettlementService`/`WithdrawalReconciliationService`, critério de
  aceite). **BANK_ACCOUNT/TED → erro explícito** (`PayoutProviderException` —
  exigiria `document_number` que o domínio não captura). **Testes**:
  `PagarMePayoutProviderClientTest` **herda o `PayoutProviderContractTest`**
  (3 invariantes do harness passam) + 9 testes MockWebServer (auth Basic, body
  code/amount/pix_key_type, 200 com id, 422/404 → exceção, mapeamento de
  estados, BANK_ACCOUNT, inferência, centavos em long sem estouro B1) — **12/12**.
  `mvn test` **740/0/0** (+12) e E2E **27/27** sem regressão (mock segue default
  em dev). **Smoke ao vivo** (dev com `NYX_PSP_PAYOUT_PROVIDER=pagarme`):
  boot OK com o bean real; saque com secret fake → chamada REAL ao Pagar.me
  (`/transfers` → 401 Unauthorized) → `PayoutProviderException` → saque **FAILED**
  ("Falha no PSP: Pagar.me: falha ao solicitar PIX de saida") + **estorno
  automático** (`WITHDRAWAL_REVERSAL` de 50 no ledger, saldo restaurado de
  50→100) — o fluxo do adapter real pluga sem tocar no núcleo. Runbook
  `deploy/payout-homologation.md` atualizado (passos 1–5 feitos; falta só o
  passo 7: sandbox com credenciais reais) + README. Fim do ciclo SDD
  (roadmap/requirements/task `implemented` — pronto para `/spec-audit`).
- **Spec 47 (`audited`, 2026-08-06):** auditoria do ciclo SDD do adapter real de
  PIX de saída Pagar.me (implementada em 2026-08-06). Verificação **ao vivo**:
  `mvn test` **740/0/0** BUILD SUCCESS (re-executado; `PagarMePayoutProviderClientTest`
  **12/12** — 3 invariantes do harness `PayoutProviderContractTest` herdado + 9
  MockWebServer: auth Basic, body code/amount/pix_key_type, 422/404 → exceção,
  mapeamento processed→PAID / failed/canceled→FAILED / demais→PENDING, rejeição
  de BANK_ACCOUNT, inferência do tipo da chave, centavos em long sem estouro B1)
  e E2E **27/27** (re-executado contra o app dev, mock segue default — sem
  regressão). **Auditoria do ciclo SDD: 5/5 critérios de aceite OK — 0 achados
  que exijam correção.** Verificações adicionais: (1) commit `d4fde7f` não tocou
  o núcleo — `WithdrawalService`/`WithdrawalSettlementService`/
  `WithdrawalReconciliationService` intactos (a porta isola o adapter); (2)
  `pix_key_type` inferido do formato canônico da Spec 31 (CPF 11 dígitos → `cpf`,
  CNPJ 14 → `cnpj`, `+55...` → `phone` — regex `^\+55[1-9]\d{10,11}$` tem 12–13
  dígitos, sem colisão com 11/14 na ordem de checagem —, UUID → `evp`); (3)
  `BANK_ACCOUNT`/TED → erro explícito (`PayoutProviderException`, documentado —
  exigiria `document_number` que o domínio não captura); (4) timeouts 5s/10s,
  `ResourceAccessException` → `PayoutProviderTimeoutException` (resposta incerta,
  retry idempotente com o mesmo `code` — B-04); (5) chave Pix nunca em claro nos
  logs (mask); (6) fail-fast `PspProperties` (secret-key do Pagar.me obrigatória
  com `payout-provider=pagarme`, `PspPropertiesTest`) + `application-prod.yml`
  sem default para `NYX_PSP_PAYOUT_PROVIDER`. Única pendência (fora do escopo):
  passo 7 do runbook — homologação em sandbox com credenciais reais do usuário.
  Fim do ciclo SDD (roadmap/requirements/task `audited`).
- **Spec 44 (`audited`, 2026-08-06):** MFA 2FA TOTP para o master (B-A5) —
  implementada em 2026-08-06 (`implemented`). Migration **V41** `master_totp`
  (números 36–40 consumidos pelas Specs 48–51): `master_mfa_methods` (registro
  multi-método — type/status/PENDING TTL 10min; futuro WebAuthn = novo type,
  sem refatorar o fluxo), `master_totp` (`secret_encrypted` via
  **CryptoConverter AES-256/GCM — `v1:` no banco, NUNCA hash**: o servidor
  precisa do valor original para recalcular o código esperado; hash puro
  tornaria a verificação impossível), `master_recovery_codes` (SHA-256
  single-use) + **V42** (FKs com `ON DELETE CASCADE` — a desativação apaga o
  método e os detalhes sem violar FK). **TOTP próprio (RFC 6238)** —
  `domain/service/Totp` (`generateSecret` base32 160 bits, `code`,
  `verify(±1 step)` com `MessageDigest.isEqual`, vetores oficiais do RFC 6238
  em `TotpTest`). **`MasterMfaService`**: activate (devolve `otpauthUri` +
  `manualEntryKey` 1x; pendente TTL 10min; re-activate descarta pendente e
  com ativo → **409 `invalid_state`**), confirm (prova de posse do app → ACTIVE
  + 8 recovery codes 1x), deactivate (senha atual + TOTP/recovery → 204),
  regenerate-recovery (TOTP válido → revoga e gera 8 novos). **Login 2FA**
  (`MasterAuthService.login` + `LoginCommand.totpCode`): com MFA ativo, senha
  NÃO basta — exige TOTP (janela ±1, anti-replay via `last_code_hash`) ou
  recovery (1x); 401 uniforme sem oráculo (código errado/ausente/lockout);
  sem MFA ativo → comportamento idêntico ao atual. **Lockout de TOTP**
  (contador separado, 5 falhas → 15min, expira sozinho — sem unlock manual).
  **Rotas** `POST /api/v1/master/mfa/{activate,confirm,deactivate,
  recovery-codes}` (ROLE_MASTER, sempre o próprio perfil — admin não gerencia
  TOTP de terceiros). **Auditoria**: 7 eventos no `PlatformAction`
  (`MFA_ACTIVATED/DEACTIVATED/LOGIN_SUCCESS/LOGIN_FAILED/LOCKED/RECOVERY_USED/
  RECOVERY_REGENERATED`; sem `MFA_UNLOCKED`) no `platform_activity_log`
  (Spec 41). `mvn test` **725/0/0** (+50 MFA) e E2E **27/27** (cenário 27:
  ativação 2 passos com TOTP calculado no teste, login 2FA, 401 sem código,
  recovery 1x, `v1:` no banco, desativação com cascata). **Auditoria do ciclo
  SDD: 22/22 checks ao vivo OK — 1 achado CRÍTICO corrigido:** o lockout de
  TOTP era **inócuo** — `verifySecondFactor` (REQUIRED, mesma transação do
  login `@Transactional`) lançava `InvalidMfaCodeException` DEPOIS de
  `totpRepository.save(failed)` + `activityRecorder.record(MFA_LOGIN_FAILED)`;
  o rollback revertia o contador/evento (testes unitários com mocks não pegam
  transação). **Correção:** `MasterTotpFailurePersister` (transação PRÓPRIA
  `REQUIRES_NEW`, mesmo padrão do `MasterLoginLockoutPersister` B-08) persiste
  a falha + evento ANTES do throw; verificado ao vivo (5 falhas → banco
  `failed_attempts=5`/`locked_until`, código correto → 401 na janela,
  `MFA_LOGIN_FAILED`+`MFA_LOCKED` na trilha) + 3 testes novos
  (`MasterTotpFailurePersisterTest`). `mvn test` **728/0/0** e E2E **27/27**
  sem regressão. Fim do ciclo SDD (roadmap/requirements/task `audited`).
- **Decisão de infraestrutura (2026-08-05) — Railway (ADR-011):** o usuário
  escolheu o **Railway** para hospedar o backend e o **PostgreSQL remoto**
  (fecha a "decisão de infra em aberto" da Fase 11). Preparação Railway-ready:
  perfil `application-railway.yml` (`SPRING_PROFILES_ACTIVE=prod,railway` —
  TLS no edge, `server.port=${PORT}`, `forward-headers-strategy=framework` que
  anula o keystore do prod), `railway.json` (Nixpacks + startCommand do
  fat-jar), `nixpacks.toml` (JDK 21), runbook `deploy/railway.md` (env vars
  obrigatórias, banco, domínio, backups, troubleshooting) e
  `NYX_TRUST_PROXY=true` para o rate limit ver o IP real. O fluxo VPS/systemd
  (`deploy/README.md`) permanece como alternativa (`prod` puro, TLS na 8443).

## Campanha de robustez e operações (2026-08-07) — além do ciclo SDD

Ações diretas de engenharia/qualidade/ops fora do ciclo de specs (commits de
2026-08-07), na sequência das auditorias SDD de 2026-08-06:

- **Mutation testing (PIT) — campanha encerrada (`8a9413c`):** global final
  **95% killed (1908/2013)**, 38 survivors, 67 NO_COVERAGE, test strength 98% —
  categorias A–D a 0 survivors (round 12 `dfe3d25`: categoria D — domínio/
  validação — a **211/212 = 99% com 0 NO_COVERAGE**, `StoreButton` L98 provado
  equivalente). Campanha encerrada com condições de retomada documentadas
  (reabrir apenas em mudança de código nas categorias críticas).
- **JaCoCo com metas de cobertura (`193da27` + `6d2f17d`):** `prepare-agent` +
  `report` + `check` no `mvn verify` — metas no código de negócio (dominio +
  aplicacao + infra de lógica pura): **INSTRUCTION/LINE ≥ 85%, BRANCH ≥ 80%,
  CLASS ≥ 90%**. Estado real pós-R12: **92.8/92.8/85.8/93.5**. Thin adapters
  sem teste unitário excluídos com justificativa **tabelada no pom** (controllers
  REST — cobertos por E2E —, scheduler, seed, config, persistence JPA, DTOs).
- **CI de migrations (`c2360e9` + `ed8a825`):** job Flyway valida a integridade
  **sem subir a app** (`mvn flyway:validate` — falha se checksum de migration
  aplicada mudou — + comparação classpath vs `flyway_schema_history`);
  **`flyway-coldstart`**: banco vazio → boot aplica V1..V42 → Hibernate validate
  → smoke (detecta migration que depende de estado pré-existente);
  **`flyway-upgrade`**: migrate com target na penúltima (V41) → boot aplica só a
  última (V42) + smoke — valida exatamente o deploy incremental de produção.
- **Carga e concorrência com k6 (`bb66598`):** `load-tests/k6/` (`load.js`:
  smoke+load+stress; `concurrency.js`: burst de N VUs) + README + job `load` no
  CI (50 VUs contra a app real, rate limit desligado via
  `NYXCASH_SECURITY_RATE_LIMIT_ENABLED`). Baseline real: concorrência 50 VUs
  **100% sucesso p95=422ms**; load 12.854 reqs **0% falhas p95=710ms** — gargalo
  identificado: login BCrypt (~50-100ms CPU/hash); meta agressiva p95<400
  documentada para produção.
- **Observabilidade (`4253639`):** actuator + health + métricas Prometheus
  (micrometer) + log estruturado (`logback-spring.xml`: `ts/level/logger/
  requestId/msg` no prod, texto legível em dev). `/actuator/health` público
  (liveness p/ orquestrador), demais endpoints exigem **ROLE_ADMIN**; indicador
  Redis **desabilitado** (opcional — ADR-010; sem Redis o health NÃO pode ficar
  DOWN); probes liveness/readiness no CI. Validado: health/liveness/readiness UP,
  prometheus 200 com admin/401 sem, `mvn test` **1060/0/0**.
- **DR do PostgreSQL (`1a0955b` + `48c4244`):** runbook `ops/backup-restore.md`
  **VALIDADO com teste real** (backup → DROP DATABASE → recriar do zero Flyway
  V1..V42 → boot + Hibernate validate → smoke → pg_restore — 8 merchants/5
  masters de volta). `ops/backup.sh` (pg_dump -Fc + lock `flock` anti-
  sobreposição/fallback mkdir + validação de tamanho mínimo + retenção 14d +
  detecção de binário PATH/PG_BIN/Windows) e `ops/restore.sh` (DROP+CREATE+
  pg_restore com confirmação + validação de contagens e última migration;
  `RESTORE_MODE=data` p/ só dados). Cron diário **02:30 UTC** documentado no
  runbook. 4 lições do teste: (1) NUNCA substituir pg_hba.conf mudando ACL
  (incidente real — recuperado via icacls; editar in-place); (2) DROP exige dono,
  CREATE exige CREATEDB; (3) restore `--clean` substitui schema migrado —
  validar `flyway_schema_history`; (4) seed recria master admin no boot.
- **Suíte E2E de autenticação (auth.spec.js):** `e2e-tests/tests/auth.spec.js` —
  **24 testes** do contrato de auth: merchant (login, logout, sessão expirada,
  senha errada, 5 erros → lockout, Dummy BCrypt anti-enumeração, reset de senha
  one-time expirado/reutilizado, API key com escopos e revogação) e master
  (login, logout, sessão expirada, lockout, Dummy BCrypt, MFA TOTP: ativação em
  2 passos, recovery codes, TOTP válido/inválido, replay, clock drift ±1/±3,
  lockout de TOTP e desativação). Requer o perfil dev (`expose-reset-token`) e
  gap de 65s entre as partes master/MFA por causa do rate limit do
  `/auth/master/login` (20 req/60s). NOTA (execução local): o rate limit é em
  memória — re-rodar em <60s após a execução anterior pode estourar o teto (429)
  nos testes de lockout; no CI a app sobe fresca (sem carryover).
- **Suíte E2E de segurança (`59cf98a`):** `e2e-tests/tests/security.spec.js` —
  **31 testes** de ataques contra a API real (Nível 6): input malformado (JSON
  inválido/UTF-8/payload >1MiB/XML-XXE/SQLi body+query/path traversal/open
  redirect/CRLF/desync), authN/authZ (IDOR, brute-force com lockout,
  anti-enumeração, replay por `idempotencyKey`), headers/infra (Host header,
  JWT/HMAC tampering, smuggling CL/TE, XSS refletido, race condition) e
  path/método (404/405 genéricos sem stack, fuzzing). CI roda **AS TRÊS suítes**
  (auth 24 + merchant-journey 27 + security 31 = **82 testes**) sem filtro por
  arquivo — spec novo em `tests/` entra no CI automaticamente. Validado
  localmente: **PASSED, 0 falhas**.

## Estado atual (2026-08-03)

- **Spec 1 (`audited`):** JDK 21.0.12 (Temurin) e Maven 3.9.11 instalados
  (`~/tools/apache-maven-3.9.11`, env em `~/.bashrc`). **PostgreSQL 18 nativo**
  em uso (serviço `postgresql-x64-18`, porta 5432) — a engine Docker exige WSL2
  (terminal elevado + reinício), indisponível nesta máquina. Banco `nyxcash` +
  usuário `nyxcash` criados localmente (credenciais padrão do `application.yml`).
  Auditoria 2026-08-03: 4/4 critérios OK (`java -version` 21, `mvn -version`
  3.9.11/JDK 21, PostgreSQL acessível, README atualizado).
- **Spec 2 (`audited`):** `mvn clean test` verde — 16 testes, 0 falhas. `mvn
  spring-boot:run` sobe na porta 8080 (Flyway aplica V1+V2) e o smoke test via
  curl passou: lojista → transação → cobrança (QR Code) → webhook PAID → prévia
  → fatura. Fixes de build: `pom.xml` `java.version` 25→21 (máquina tem JDK 21)
  e `MerchantTest` movido para o package `domain.model.merchant` (limpeza de
  `.class` obsoleto em `target/`). Auditoria 2026-08-03: 4/4 critérios OK.
- **Spec 3 (`audited`):** commit inicial criado — `2a07f54` `feat: scaffold do
  backend TrevoPay (hexagonal, Spring Boot 3.3)` na branch `main` (103 arquivos;
  `git status` limpo; identidade local `Operador <operador@foxcash.example.com>`). Projeto
  publicado em https://github.com/usuario/foxcash. Auditoria 2026-08-03:
  3/3 critérios OK.
- **Spec 4 (`audited`):** código implementado e compilando; testes unitários
  verdes (incl. `CryptoConverterTest`); **suíte E2E Playwright 5/5 passando**
  (`npm test` em `e2e-tests/` contra app + PostgreSQL nativo). Auditoria
  2026-08-03: 6/6 critérios OK — (1) `merchants.document` e
  `pix_transactions.payer_document` como `v1:<base64>` no banco (app lê/escreve
  em claro); (2) chave via `NYX_CRYPTO_SECRET` (prod sem default; dev-only no
  `application.yml`); (3) `require-https=true` → HTTP 302 p/ HTTPS, `false` →
  HTTP OK; (4) logs sanitizados (`***`, 0 CPF/CNPJ em claro); (5)
  `/billing/preview` retorna `totalTransactions`/`variableFee`/`fixedFee`/
  `totalDue`/`calculation`; (6) E2E 5/5.
- **Spec 5 (`audited`):** `POST /api/v1/merchants` com documento duplicado retorna
  **409 Conflict** `merchant_already_exists` (antes: 500). Implementação:
  `MerchantAlreadyExistsException` (domínio), pré-check no `RegisterMerchantService`
  via `MerchantRepository.findByDocument`, handler 409 no `GlobalExceptionHandler`.
  Auditoria 2026-08-03: 5/5 critérios OK — 409 duplicado, 201 novo, índice único
  `uq_merchants_document_hash` mantido (backstop), 400/500 sem regressão, `mvn
  test` verde (18 testes: 16 + 2 novos) e E2E 5/5.
- **Spec 6 (`audited`):** job mensal gera a fatura do mês anterior para todos os
  lojistas ACTIVE (`@EnableScheduling` + **ShedLock** via tabela `shedlock`,
  migration V3; cron configurável `nyxcash.scheduler.invoice-cron`). Novos:
  `GenerateInvoicesForPeriodUseCase` + service idempotente,
  `MerchantRepository.findAllActive()`, `InvoiceGenerationScheduler` (lock
  `nyxcash-invoice-generation`), `SchedulingConfig`. Auditoria 2026-08-03:
  5/5 critérios OK — V3 aplicada, findAllActive só ACTIVE, job gerou fatura
  2026-07 (R$ 69,95) e ShedLock bloqueou re-execução (lock ativo), `mvn test`
  verde (21 testes: 18 + 3 novos) e E2E 5/5.
- **Spec 7 (`audited`):** outbox de notificações (`notification_outbox`, migration
  V4) registra `INVOICE_GENERATED` na mesma transação da fatura nova; dispatcher
  agendado (`NotificationDispatchScheduler`, lock `nyxcash-notification-dispatch`)
  entrega via port `MerchantNotifier` (adapter mock `LoggingMerchantNotifier`) e
  marca `SENT`. Auditoria 2026-08-03: 5/5 critérios OK — V4 aplicada, evento
  criado só em fatura nova (idempotência: 2 gerações → 1 evento), entrega logada
  e status SENT com `sent_at`, `mvn test` verde (26 testes: 21 + 5 novos) e E2E
  5/5.
- **Spec 8 (`audited`):** endpoints **somente-leitura** de consulta em
  `/api/v1/admin`: `GET /merchants` (paginado + filtros `status`/`q`/`from`/
  `to`), `GET /invoices` (paginado + filtros `merchantId`/`status`/
  `billingPeriod`) e `GET /summary` (agregados de lojistas, transações e
  faturas). Migration **V5** cria `ix_merchants_status`, `ix_invoices_status` e
  `ix_invoices_billing_period`. Arquitetura hexagonal:
  `AdminDashboardUseCase` → `AdminDashboardService` →
  `AdminDashboardPort` → `AdminDashboardPersistenceAdapter` (Specifications +
  `SUM`/`COALESCE` no PostgreSQL); `PageResult<T>` no domínio evita vazar
  Spring Data. Auditoria 2026-08-03: 6/6 critérios OK — V5 aplicada e
  validada, merchants/invoices paginados com filtros isolados e combinados,
  summary consistente com SQL de referência (1 lojista ACTIVE, 1 transação
  APPROVED R$ 150,00, 1 fatura PENDING R$ 70,45), parâmetros inválidos → 400
  `invalid_argument`, `mvn test` verde (30 testes: 26 + 4 novos) e E2E 5/5.
- **Spec 9 (`audited`):** fundação de produção **sem Docker**. CI/CD no GitHub
  Actions (`.github/workflows/ci.yml`): job `mvn -B verify` (32 testes) + job
  E2E Playwright (5/5) com PostgreSQL **nativo no runner** (`apt`, sem
  containers) e instalação do Chromium do Playwright. Profile `prod`
  (`application-prod.yml`): TLS no conector (8443), `require-https: true` e
  env vars **sem default** (`DB_URL`/`DB_USERNAME`/`DB_PASSWORD`/
  `NYX_CRYPTO_SECRET`/`NYX_SSL_KEYSTORE_PASSWORD`) — boot falha se ausentes.
  `CryptoConverter` rejeita placeholder não-resolvido (`${NYX_CRYPTO_SECRET}`
  literal) como chave pública. Deploy `deploy/`: unit systemd
  (`trevopay.service` com `EnvironmentFile` + `Restart=on-failure`) e runbook
  VPS passo a passo (JDK 21, PostgreSQL, nginx+TLS, backup `pg_dump`). ADR-005
  registra a decisão do PSP (Pagar.me primário, Asaas alternativa) isolada
  atrás do port `PixProviderClient`. Auditoria 2026-08-03: **8/8 critérios OK**
  — (1) CI com 2 jobs e PG nativo no runner; (2) prod sem defaults inseguros;
  (3) boot `prod` sem `NYX_CRYPTO_SECRET` falha (fail-fast, mensagem clara);
  (4) boot `prod` com env vars válidas → HTTPS 200 / HTTP 400 no canal TLS
  (8443); (5) `mvn package` gera fat-jar executável (`java -jar` sobe em dev
  e prod); (6) `deploy/trevopay.service` + runbook presentes; (7) ADR-005 do
  PSP; (8) `mvn test` verde (32 testes: 30 + 2 novos do CryptoConverter) e
  E2E 5/5 sem regressão.
- **Spec 10 (`audited`):** estrutura de integração com PSP real **sem adapter
  HTTP concreto** (fica para spec futura, quando houver credenciais/web).
  Seleção por config `nyxcash.psp.provider` (`mock` | `pagarme` | `asaas`) com
  `@ConditionalOnProperty` no `MockPixProviderClient` (default dev);
  `pagarme`/`asaas` sem adapter → boot **falha** com bean `PixProviderClient`
  ausente (fail-fast). `PspProperties` (`@ConfigurationProperties`) com segredo
  do webhook obrigatório em prod (`NYX_PSP_WEBHOOK_SECRET` sem default).
  `WebhookSignatureFilter` valida **HMAC-SHA256** do raw body em
  `POST /webhooks/pix` (header `X-Psp-Signature` configurável): sem assinatura
  ou inválida → **401**; correta → processa (`CachedBodyHttpServletRequest`
  preserva o body). `verify-signature` `true` default; `false` só dev/tests;
  prod sempre `true`. `deploy/psp-homologation.md` documenta o contrato e os
  passos de homologação (sandbox, credenciais, env vars, validação de cobrança
  e webhook). Auditoria 2026-08-03: **7/7 critérios OK** — (1) provider default
  `mock` em dev / sem default em prod; (2) seleção por `@ConditionalOnProperty`
  e fail-fast sem adapter; (3) webhook 401 sem header, 401 incorreta, processa
  correta (verificado via HTTPS real: 401/401/404-de-transação-inexistente);
  (4) `verify-signature` configurável (dev `false`, prod `true`); (5) `mvn
  test` verde (35 testes: 32 + 3 novos do filtro) e E2E 5/5; (6) runbook
  `deploy/psp-homologation.md` + README atualizado; (7) boot `prod` sem
  `NYX_PSP_WEBHOOK_SECRET` falha (fail-fast).
- **Spec 11 (`audited`):** primeiro adapter HTTP real do PSP — `PagarMePixProviderClient`
  (API v5: `POST {base-url}/charges` com `Authorization: Basic base64(secretKey:)`,
  payload `CreateChargeRequest` — `code`=transactionId, `amount` em centavos,
  `payment_method="pix"`, `pix.expires_in` — e resposta `GetChargeResponse`
  `last_transaction` → `pixCopyPaste`/`pixQrCode`/`externalReference`). Registrado
  apenas com `nyxcash.psp.provider=pagarme` (`@ConditionalOnProperty`); mock
  continua default em dev. Config `nyxcash.psp.pagarme.*`: `secret-key`
  (`NYX_PSP_PAGARME_SECRET_KEY`), `base-url`, `expires-in-seconds` — prod exige a
  secret sem default (fail-fast no `PspProperties.validate()`). Testes com
  **MockWebServer** (okhttp3) reproduzem o contrato v5 sem credenciais reais
  (auth Basic, body, 200 com QR, 422, QR ausente). Auditoria 2026-08-03: **7/7
  critérios OK** — (1) `@ConditionalOnProperty` + mock default; (2) request
  `POST /charges` correto (testado); (3) mapeamento da resposta (testado); (4)
  4xx/5xx → exceção com contexto (testado); (5) prod fail-fast sem
  `NYX_PSP_PAGARME_SECRET_KEY` (boot falha com mensagem clara) e dev com default
  fake; (6) `mvn test` verde (39 testes: 35 + 4 novos) e E2E 5/5; (7) runbook
  `deploy/psp-homologation.md` com passo 3 marcado feito.
- **Spec 12 (`audited`):** autenticação da API por **API key** (header `X-Api-Key`,
  ADR-006). Chave por lojista gerada no cadastro (`mch_<32 hex>`, devolvida 1x no
  `POST /api/v1/merchants`) e persistida **apenas como hash SHA-256**
  (`merchants.api_key_hash`, migration V6, índice único). Chave admin via env
  `NYX_ADMIN_API_KEY` (obrigatória em prod — fail-fast; default fake em dev)
  protege `/api/v1/admin/**`. `ApiKeyAuthFilter` (Spring Security) resolve
  `X-Api-Key` → `ROLE_ADMIN`/`ROLE_MERCHANT` (lojista ACTIVE, merchantId no
  principal); rotas protegidas sem chave/inválida → **401**, lojista em admin →
  **403** (JSON `ErrorResponse`). Públicas: `POST /api/v1/merchants` e
  `/webhooks/pix` (HMAC da Spec 10). Auditoria 2026-08-03: **7/7 critérios OK**
  — (1) cadastro emite chave e persiste só o hash (E2E verifica no banco); (2)
  filtro autentica admin/lojista-ACTIVE, ignora inválida/não-ACTIVE (5 testes);
  (3) 401 sem chave, 401 inválida, 403 lojista em `/admin`, 200 admin (curl
  real); (4) `SecurityConfig` protege `/api/v1/**`, webhook segue funcional; (5)
  prod fail-fast sem `NYX_ADMIN_API_KEY` (boot falha) e dev com default fake;
  (6) `mvn test` verde (49 testes: 39 + 10 novos) e E2E 5/5; (7) README
  documenta autenticação + ADR-006.
- **Spec 13 (`audited`):** perfil master com login usuário/senha e sessão por
  token opaco (ADR-007). Migration **V7** cria `masters` (usuário único, senha
  **BCrypt**) e `master_sessions` (só o **hash SHA-256** do token, expiração,
  revogação). `POST /api/v1/auth/master/login` → `200 {token: mst_<32 hex>,
  masterId, name, expiresAt}` (~8h); credenciais inválidas ou master inativo →
  **401** (`InvalidCredentialsException` → `unauthorized`). `POST
  /api/v1/auth/master/logout` revoga a sessão → **204**. `MasterAuthFilter`
  resolve `Authorization: Bearer <token>` → `ROLE_MASTER` (principal =
  masterId); `SecurityConfig` exige `ROLE_MASTER` em `/api/v1/master/**`
  (login/logout públicos; rotas existentes inalteradas). Seed do primeiro
  master no boot (`MasterSeedRunner`): tabela vazia + `NYX_MASTER_SEED_*` →
  cria; sem env vars → não cria nem falha (documentado em log); prod sem
  default (boot não falha). Beans: `Clock`, `Duration` (TTL configurável
  `nyxcash.master.session-ttl-seconds`). Auditoria 2026-08-03: **7/7 critérios
  OK** — (1) V7 aplicada (verificado no banco); (2) login 200 com token
  `mst_<32 hex>` + expiresAt ~8h, senha errada 401, usuário inexistente 401,
  master inativo 401 (teste); (3) logout revoga (`revoked_at` no banco) e token
  revogado → 401; (4) filtro: token válido → ROLE_MASTER, expirado/revogado/
  desconhecido → sem autenticação; `/api/v1/master/**` sem token → 401, com
  lojista → 403 (curl real com chave de lojista); (5) login/logout públicos e
  rotas existentes intactas (admin 200, cadastro 201); (6) seed criou o master
  inicial (verificado no banco) e não duplica em re-boot; (7) `mvn test` verde
  (**58 testes: 49 + 9 novos** — 6 do `MasterAuthServiceTest`, 3 do
  `MasterAuthFilterTest`) e E2E **5/5** sem regressão.
- **Spec 18 (`audited`):** hardening de segurança fechando as vulnerabilidades
  da auditoria de especialista (IDOR, abuso, validação, robustez) — ADR-009.
  **Autorização por recurso:** `MerchantAccessGuard` valida
  `principal == merchantId` em todos os endpoints de lojista (merchant,
  transactions, pix/cob, billing, invoices) → lojista B em recurso do A →
  **403 `forbidden`** (antes: 200/vazava dados e permitia escrita em nome de
  terceiros). **Rate limiting** (`RateLimitFilter`): `POST /api/v1/merchants` e
  `POST /api/v1/auth/master/login` por IP+rota (janela fixa configurável
  `nyxcash.security.rate-limit.*`, default 20/min) → **429
  `too_many_requests`**. **Validação CPF/CNPJ** (`DocumentValidator`, dígitos
  verificadores) no cadastro → 400 (base KYC). **Robustez:** parâmetros
  ausentes/malformados e corpo ilegível → **400** (antes: 500);
  **webhook idempotente** (replay `PAID` → 204, não 409); **`X-Request-Id`**
  em toda resposta; **409 de duplicado sem ecoar o documento**. **CI:**
  Dependabot + OWASP dependency-check (falha em CVE ≥ 7.0). Auditoria
  2026-08-03: **8/8 critérios OK** — (1) IDOR 403 em leitura/escrita (E2E 6.x +
  curl real); (2) 429 após limite e 201 normal abaixo (curl real, 25 reqs); (3)
  documento "123" → 400, válido → 201 (unit + E2E); (4) preview sem month /
  month=abc → 400 JSON (curl real); (5) replay webhook → 204 e transação
  mantém APPROVED (E2E); (6) `size` admin com cap 100 (já existia), `X-Request-Id`
  presente e honra header do cliente, 409 sem documento (curl real); (7)
  `.github/dependabot.yml` + workflow OWASP presentes; (8) `mvn test` verde
  (**83 testes: 58 + 25 novos**) e E2E **6/6** (novo cenário de segurança).
- **Spec 14 (`audited`):** saques **automáticos** do wallet + controle
  master por lojista. Migration **V8** cria `withdrawals` (FK merchant, amount,
  status `COMPLETED`/`REJECTED`, timestamps; índices em merchant/status/
  created_at) e adiciona `merchants.withdrawals_blocked BOOLEAN NOT NULL
  DEFAULT FALSE`. Domínio: `Withdrawal` + `WithdrawalStatus`,
  `Merchant.withdraw(Money)` (valida ACTIVE → bloqueio → saldo; debita),
  `Merchant.blockWithdrawals()`/`releaseWithdrawals()`, `Money.subtract`.
  **Fonte de saldo:** `ProcessPixWebhookService` agora **credita o wallet** do
  lojista na aprovação da transação (PAID → APPROVED) — o `Merchant.credit()`
  existia mas nunca era chamado; sem isso o saque nunca teria saldo
  suficiente (idempotente: replay de PAID não credita de novo). Portas
  `WithdrawalUseCase`/`MasterWithdrawalControlUseCase`/
  `QueryWithdrawalUseCase` + adapter JPA com Specifications. REST:
  `POST|GET /api/v1/merchants/{id}/withdrawals` (chave do lojista +
  `MerchantAccessGuard`), `POST /api/v1/master/merchants/{id}/withdrawals/
  block|release` → 204 (ROLE_MASTER) e `GET /api/v1/master/withdrawals`
  (filtros `merchantId`/`status`/`from`/`to`). Erros: **409
  `insufficient_balance`** (saldo), **409 `withdrawals_blocked`** (bloqueio),
  **409 `merchant_not_active`** e 404 `withdrawal_not_found`. Testes: `mvn
  test` verde (**100 testes: 99 + 1 novo do webhook** — 5
  `WithdrawalServiceTest`, 3 `MasterWithdrawalControlServiceTest`, 3 no
  `MerchantTest`, 5 `MasterDashboardServiceTest` da Spec 15, 1 crédito no
  `ProcessPixWebhookServiceTest`). E2E **6/6** sem regressão + validação
  manual curl (saque 201/409, bloqueio 204/409, IDOR 403, earnings/fees).
- **Spec 15 (`audited`):** dashboard master **somente-leitura**
  (ROLE_MASTER). `GET /api/v1/master/transactions` (paginado + filtros
  `merchantId`/`status`/`from`/`to`, reusa padrão Spec 8);
  `GET /api/v1/master/merchants/{id}/earnings` → `{walletBalance,
  approvedVolume, totalWithdrawn, withdrawalsBlocked}` (SUM APPROVED +
  SUM saídas COMPLETED via COALESCE no PostgreSQL; lojista inexistente →
  404 `merchant_not_found`); `GET /api/v1/master/merchants/{id}/fees?month=
  yyyy-MM` → `{transactionCount, transactionFees, monthlyFee, totalFees}`
  derivado das faturas (sem fatura → zeros; mês inválido → 400
  `invalid_argument`). `MasterDashboardService` + `MasterDashboardPort` +
  `MasterDashboardPersistenceAdapter` (Specifications + SUM/COALESCE).
  Testes: `MasterDashboardServiceTest` (5 novos) — ver contagem na Spec 14.
- **Spec 16 (`audited`):** antifraude/compliance (ADR-008) — o sistema
  **computa** os indicadores e o **master decide** (nada automático). Job
  agendado `RiskMonitoringScheduler` (ShedLock `nyxcash-risk-monitoring`,
  cron `nyxcash.scheduler.risk-cron`, default diário 03:00 UTC) calcula para
  **todos** os lojistas (`MerchantRepository.findAll()`) um **snapshot** em
  `merchant_risk` (migration **V9**): volume aprovado, total/aprovadas/
  reembolsadas e **taxa de reembolso** (% REFUNDED/total) com flags de alerta
  pelos limiares configuráveis `nyxcash.risk.*` (chargeback 1%/3%, reembolso
  5%, denúncias 3 — chargeback/denúncias sem fonte de dados nesta versão,
  colunas prontas p/ alimentação futura). `RiskMonitoringUseCase` +
  `QueryRiskUseCase` + `RiskRepository` + adapter (Specifications);
  `TransactionRepository` ganhou `countByMerchantId(AndStatus)`/
  `sumAmountByMerchantIdAndStatus`. REST (ROLE_MASTER): `GET
  /api/v1/master/risk` (paginado + filtro `merchantId`), `GET
  /api/v1/master/merchants/{id}/risk` (404 `risk_snapshot_not_found` sem
  snapshot) e **suspensão reusando o status**: `POST
  /api/v1/master/merchants/{id}/suspend|release` → 204 (SUSPENDED bloqueia
  processamento, Spec 12, e saques, Spec 14; 404 `merchant_not_found`).
  Testes: `mvn test` verde (**111 testes: 100 + 11 novos** — 6
  `RiskMonitoringServiceTest`, 3 `MerchantStatusControlServiceTest`, 2
  `QueryRiskServiceTest`). E2E **6/6** sem regressão + validação manual (job
  gravou snapshots, earnings refletem, suspend/release 204/401/200, 404/401/
  403, 400 em parâmetro inválido).
- **Spec 20 (`audited`, 2026-08-04):** fundação do portal do lojista —
  auth usuário/senha + sessão bearer (role `ROLE_MERCHANT_PORTAL`, V13:
  `merchant_credentials` BCrypt + `merchant_sessions` hash SHA-256, TTL 8h),
  senha inicial devolvida 1x no cadastro, `/portal/me`, convenções de métrica
  (Spec 21–25) e rate limit no login. Auditoria 2026-08-04: **7/7 critérios OK**
  — cadastro devolve `portalPassword` 1x (só hash BCrypt no banco);
  **`last_used_at` registrado no login** (V14, `MerchantCredential.recordUse()`);
  login 200 com expiração exata 8h, senha errada/usuário inexistente 401, lojista
  não-ACTIVE **403 `merchant_not_active`** (nova `MerchantLoginDeniedException`;
  saques/cobranças mantêm 409); logout 204 → token revogado 401; `/portal/me`
  com perfil completo; 401 sem token e 403 com API key em `/portal/**`; `mvn
  test` verde (144) e E2E **7/7** sem regressão. Fixes: boot `@Qualifier` (L10),
  `global-setup.js` com `merchant_credentials`/`merchant_sessions` (L6).
- **Spec 21 (`audited`, 2026-08-04):** dashboard do lojista no portal
  (**somente-leitura**, `ROLE_MERCHANT_PORTAL`). `GET
  /api/v1/portal/dashboard/summary?from=&to=` → `{grossRevenue, sales,
  pixConversionRate, journeyProgress}` (conversão = aprovadas/criadas×100,
  jornada = volume ÷ meta `nyxcash.portal.journey-goal-amount` × 100, cap
  100; janela padrão = mês corrente UTC); `GET .../revenue-series?from=&to=`
  → pontos `{label, value}` por dia UTC (dias sem venda = 0); `GET
  .../recent-sales?limit=` → últimas vendas com `buyerIdHash` (limit clamp
  1–50, default 5); `GET .../attention-points` → alertas `{type, severity,
  message}` derivados do estado do lojista (`merchant_status` se ≠ ACTIVE,
  `withdrawals_blocked`) e do snapshot de risco (`refund_alert`, Spec 16/19).
  `MerchantDashboardService` + `MerchantDashboardUseCase` +
  `PortalDashboardController` (400 `invalid_argument` via `QueryParamParser`,
  mesmas convenções da Spec 20). Testes: `mvn test` verde (**144 testes: 138
  + 6 novos** — `MerchantDashboardServiceTest`: summary com conversão/jornada,
  jornada cap 100, série contínua, recent-sales ordenado, pontos de atenção
  (estado+saque+risco) e vazio quando saudável). **Auditoria 2026-08-04: 5/5
  critérios OK** — validação via API real (app no ar): summary `{grossRevenue:
  200.00, sales: 2, pixConversionRate: 66.67, journeyProgress: 20}` (2/3
  aprovadas, 200/1000); revenue-series contínua com dia sem venda = 0;
  recent-sales mais recentes primeiro com `buyerIdHash` opaco; attention-points
  vazio (saudável) e `[withdrawals_blocked MEDIUM]` com saques bloqueados
  (SUSPENDED não autentica no portal — `merchant_status` validado no teste de
  serviço, inalcançável via API por design); 401 sem token, 403 com API key,
  400 `invalid_argument` em from/to; `mvn test` verde (144) e E2E **7/7** sem
  regressão. **Fix de boot (regressão da Spec 20, L10):** `@Qualifier`
  explícito nos `Duration` de `MasterAuthService`/`MerchantPortalAuthService`
  (2 beans Duration → app não subia).
- **Spec 22 (`audited`, 2026-08-04):** carteira/financeiro do lojista no
  portal (**somente-leitura**, `ROLE_MERCHANT_PORTAL`). `GET
  /api/v1/portal/wallet/balance` → `{walletBalance, withdrawalsBlocked,
  saldoCalculadoPelaApi: true}` (fonte oficial da Spec 14 — nunca estimativa
  local); `GET .../monthly-summary?month=yyyy-MM` → `{grossRevenue,
  netRevenue, transactionCount}` (líquida = bruta − taxas de transação via
  `PricingCalculator`; `netRevenue` pode ser negativo — `BigDecimal`, pois
  `Money` não aceita negativo); `GET .../weekly-inflows` → 7 pontos
  `{label, value}` (dia × volume aprovado, UTC, zerado sem vendas, `Clock`
  injetado); `GET .../cash-flow?from=&to=` → `{inflows, outflows, net}`
  (saídas = saques `COMPLETED` via novo
  `WithdrawalRepository.sumCompletedAmountByMerchantIdBetween`; janela padrão
  mês corrente). `MerchantWalletService` + `MerchantWalletUseCase` +
  `PortalWalletController` (400 `invalid_argument` via `QueryParamParser`).
  Testes: `mvn test` verde (**150 testes: 144 + 6 novos** —
  `MerchantWalletServiceTest`: saldo com flag, resumo bruta−taxas, net
  negativo, semana 7 pontos zero-fill, fluxo de caixa com net positivo e
  negativo). **Auditoria 2026-08-04: 5/5 critérios OK** — validação via API
  real (2 vendas 150+50 aprovadas, saque 80 COMPLETED): balance
  `{walletBalance: 120.00, withdrawalsBlocked: false, saldoCalculadoPelaApi:
  true}`; monthly-summary 2026-08 `{grossRevenue: 200.00, netRevenue:
  199.00, transactionCount: 2}`; cash-flow `{inflows: 200.00, outflows:
  80.00, net: 120.00}`; weekly-inflows 7 pontos de 2026-07-29 a 08-04 com
  200.00 no dia atual e 0 nos demais; 400 `invalid_argument` em month/from
  inválidos, 401 sem token, 403 com API key; `mvn test` verde (150) e E2E
  **7/7** sem regressão.
- **Spec 23 (`audited`, 2026-08-04):** gerador de cobrança PIX do portal —
  `POST /api/v1/portal/charges` (cobrança avulsa, mesmo contrato do
  `/nyx/pix/cob` com triagem antifraude), catálogo `GET
  /api/v1/portal/charges/gift-presets` (defaults R$ 5/10/20/50 seedados no
  cadastro — migration **V15** `gift_presets`) e `PUT .../gift-presets/{id}`
  `{active}` → 204 (inativo → 409 `gift_preset_inactive`; inexistente → 404
  `gift_preset_not_found`); `POST .../charges/gift` `{presetId | amount}` (XOR
  validado — ambos → 400). Reuso do `CreatePixChargeService` (Spec 19) com o
  principal do portal; `description` opcional propagada ao PSP. Auditoria
  2026-08-04: **5/5 critérios OK** — 201 com contrato completo (E2E cenário
  8), catálogo 4 defaults ativos, 204/409/404 no preset, XOR → 400, valor
  inválido/≥ teto → 400; `mvn test` verde (175) e E2E **8/8** sem regressão.
  Código entregue junto com a Spec 26 (commit `047fa62` — a QA da 26 incluiu
  a cobertura REST M4).
- **Spec 26 (`audited`, 2026-08-04):** auditoria QA completa sobre specs
  concluídas (14/16/19/20/23) corrigiu **C1** (concorrência: locks pessimistas
  `SELECT … FOR UPDATE` no webhook/saque/chargeback — antes webhooks `PAID`
  concorrentes podiam creditar 2× e saques concorrentes podiam estourar o
  saldo), **H1** (estorno: `REFUNDED`/chargeback agora **debitam o wallet** —
  antes o lojista podia sacar dinheiro devolvido; saldo pode ficar negativo,
  decisão de produto), **H2** (CI: health check autenticado — job E2E estava
  sempre 401), **H3** (`expirationSeconds` propagado até o `expires_in` do
  Pagar.me — antes aceito e ignorado), **M1/M6** (teto de valor em todos os
  fluxos de cobrança), **M2** (timeouts 5s/10s no Pagar.me), **M4**
  (`MerchantChargeControllerTest`, 8 testes — camada REST da Spec 23 sem
  cobertura), **M5** (cadastro concorrente → 409), **L1** (chave admin em tempo
  constante), **L4** (gift `presetId`+`amount` → 400). Auditoria 2026-08-04:
  **10/10 critérios OK** — `mvn test` verde (**175 testes: 157 + 18**); E2E
  **8/8** (cenário 8 do portal: login, `/portal/me`, cobrança avulsa, gift
  presets, logout); REFUNDED debita wallet (saldo -150,00) e replay
  idempotente; chargeback não-APPROVED → 409 e debita wallet; `/nyx/pix/cob`
  acima do teto → 400 (curl real); `expirationSeconds ≤ 0` → 400 e propagado
  ao PSP (MockWebServer); CI health check autenticado; timeouts 5s/10s; chave
  admin constant-time; gift XOR → 400. Commit `047fa62`.
- **Spec 24 (`audited`, 2026-08-04):** credenciais API do lojista no
  portal — gestão de chaves gerenciáveis com escopos e último uso. Migration
  **V16** `merchant_api_keys` (id, merchant_id, label, prefix, key_hash
  SHA-256 único, status ACTIVE/REVOKED, scopes CSV, last_used_at, created_at)
  **migra a chave do cadastro** (Spec 12) com escopo total — integrações
  existentes não quebram. `GET /api/v1/portal/credentials` (prefixo/status/
  escopos/último uso — nunca o segredo), `POST` `{label, scopes}` → 201 com
  `secret` UMA vez (limite 5 ativas → 409 `too_many_keys`; escopo inválido →
  400), `POST /{id}/revoke` → 204 (404 `api_key_not_found` fora do lojista).
  `ApiKeyAuthFilter` evoluído: busca na tabela nova, valida **escopos por
  rota** (`charges` → `/nyx/pix/**`, `transactions`, `withdrawals`; rota sem
  escopo → exige total; insuficiente → **403**), registra `last_used_at` e
  mantém fallback legado (`merchants.api_key_hash`). Cadastro grava a chave
  também na tabela nova. Verificação: `mvn test` verde (**196 testes: 175 +
  21 novos** — `ApiKeyManagementServiceTest`, `PortalCredentialsControllerTest`,
  `ApiKeyAuthFilterTest` ampliado) e E2E **9/9** (novo cenário 9: listar/criar
  chave, escopo transactions 200/charges 403, escopo inválido 400, revogar →
  401, limite 409). README: seção "Uso no backend". Commit `03ff30a`.
  **Auditoria via API real: 6/6 critérios OK (22/22 checks)** — (1) V16 com
  `key_hash` SHA-256 (64 hex) UNIQUE + colunas; chave do cadastro migrada
  ACTIVE com escopo **total explícito** (`charges,transactions,withdrawals`);
  (2) `GET /credentials` lista sem o segredo (prefix `mch_`, label "Chave do
  cadastro", status, `lastUsedAt`); (3) `POST` → 201 com `secret` UMA vez
  (`mch_<32 hex>`), escopo inválido → 400 `invalid_argument`, limite → 409
  `too_many_keys` (exatamente 5 ACTIVE no banco); (4) revoke → 204 e chave
  revogada → 401; lojista B revoga chave do A → 404 `api_key_not_found`
  (autorização por recurso); (5) escopo por rota (transactions → 200,
  `/nyx/pix` → 403 `forbidden`) e `last_used_at` atualizado na autenticação
  válida (chave scoped + cadastro); (6) docs README "Uso no backend" + `mvn
  test` 313/0/0 + E2E 18/18 (cenário 9) sem regressão.
- **Spec 25 (`audited`, 2026-08-04):** vendas/operação comercial do
  lojista no portal (**somente-leitura**, `ROLE_MERCHANT_PORTAL`). `GET
  /api/v1/portal/sales?status=&from=&to=&page=&size=` → `PageResponse` de
  `{transactionId, buyerIdHash, method: "PIX", grossAmount, netAmount, status,
  createdAt}` (líquido = bruto − taxa do plano via `PricingCalculator`, mesma
  fonte das faturas Spec 8; page default 0 / size default 20 / max 100);
  `GET .../sales/metrics?days=30` → `{volume30d, completedSales, pendingSales,
  conversionRate}` (janela últimos N dias via `Clock`, clamp 1–90, conversão =
  concluídas ÷ criadas × 100); `GET .../sales/{transactionId}` → detalhe com
  payload Pix **somente quando `PENDING`**, 404 `transaction_not_found` e 403
  `forbidden` para transação de outro lojista (Spec 18/ADR-009). Sem novas
  tabelas — leitura sobre `pix_transactions`. `MerchantSalesUseCase` +
  `MerchantSalesService` + `PortalSalesController` (400 `invalid_argument` via
  `QueryParamParser`); `TransactionRepository` ganhou `searchSales`
  (Specification paginada) e contagens/soma por intervalo (SUM/COALESCE).
  Testes: `mvn test` verde (**204 testes: 196 + 8 novos** —
  `MerchantSalesServiceTest`: líquido, filtros, métricas/conversão, clamp de
  dias, detalhe PENDING/APPROVED, 403, 404) e E2E **10/10** (cenário 10 novo:
  listagem, filtros Concluídas/Pendentes, métricas 30d, detalhe PENDING com
  payload Pix, 400 `invalid_argument`, 404). **Auditoria 2026-08-04: 5/5
  critérios OK** — validação via API real (2 lojistas): listagem paginada com
  contrato completo e `netAmount = grossAmount − 0,50` (77,77 → 77,27); filtros
  status isolados; 403 `forbidden` ao lojista B ler transação do A (autorização
  por recurso); detalhe PENDING com QR Code/Copia e Cola, 404 `transaction_not_found`;
  metrics `{volume30d: 0, completedSales: 0, pendingSales: 1, conversionRate:
  0.00}` (e 150.0/1/≥1/>0 no cenário E2E com aprovada); 400 `invalid_argument`
  em `status`/`from`; 401 sem token e 403 com API key em `/portal/**`; `mvn
  test` verde (204) e E2E **10/10** sem regressão. Commit `76b6bdd`.
- **Fase 5 — Hardening financeiro profundo (specs 27–32 `verified`, 2026-08-04):**
  diagnóstico profundo do subsistema financeiro (wallet, saques, webhook,
  chargeback, faturas) identificou 3 críticos — **C1** (duplo débito:
  chargeback debita sem mudar status e webhook `REFUNDED` debita de novo),
  **C2** (cobrança duplicada em timeout do PSP — sem idempotency key),
  **C3** (saldo é campo solto, sem ledger/histórico) — e 4 médios (**M1**
  webhook fora de ordem → 409; **M2** fatura conta estornadas sem decisão
  explícita; **M3** chargeback duplicado → 500; **M4** venda com chargeback
  aparece como concluída). Specs criadas e **verificadas** (`/spec-verify`):
  **27** wallet ledger (C3), **28** idempotência de cobrança
  (C2), **29** estorno consistente (C1/M1/M3/M4), **30** reconciliação +
  fatura COUNT + escala (L2/L3 + backstop). Visão V18. Em seguida, specs de
  saque (decisões do usuário 2026-08-04): **31** destinos de saque (chaves Pix
  dos 4 tipos do Bacen ou dados bancários, privados e criptografados) e **32**
  saque com destino selecionado (instantâneo, anti-duplo via lock) + bloqueio
  lacrado com saque administrativo do master (até o saldo, nunca negativo).
  Visão V19.
- **Fase 6 — Operação comercial, pagamentos reais e compliance (specs 33–37
  `verified`, 2026-08-04):** o usuário aprovou a direção "backend completo,
  estruturado e seguro para mercado" e adiou o frontend para rodada futura.
  Ideias priorizadas viraram 5 specs (status `verified` — `/spec-verify`
  concluído): **33** faturamento automático e contas a receber (cobrança da
  fatura no wallet no vencimento — total/parcial —, dívida formal `receivables`
  e bloqueio de saque por inadimplência), **34** ledger do master (receita/perda
  da plataforma na mesma transação + write-off auditado), **35** webhook de
  saída para o bot (POST assinado HMAC com retry/backoff, outbox evoluído),
  **36** saque via PSP (contrato de Pix de saída — porta + mock + slot para
  adquirente real, que o usuário escolherá depois —, estado real
  PROCESSING/FAILED com estorno automático e reconciliação) e **37** auditoria
  de ações do master (trilha append-only com actor/before/after/motivo). Migrations
  planejadas: V22 (`receivables`), V23 (`master_ledger`), V24 (`merchant_webhooks`),
  V25 (`withdrawals` payout state), V26 (`master_audit_log`). Visão V20.
- **Fixes aplicados no `/spec-verify` das Specs 27–37 (2026-08-04):** (1) Spec
  27 e 34 — a unique do ledger passa a incluir `entry_type`
  (`(merchant_id, entry_type, reference_type, reference_id)`): sem isso, uma
  transação paga+estornada colidiria em `SALE_CREDIT`/`REFUND_DEBIT` (27) e uma
  fatura paga colidiria em `TRANSACTION_FEE`/`MONTHLY_FEE` (34); (2) Spec 29 —
  ordem corrigida no `RegisterChargebackService` (`chargebackRepository.save`
  antes do `ledgerRecorder.record` com `saved.id()`); (3) Spec 28 —
  `IdempotencyPersister.persistPending` retorna o tx com a chave atribuída (o
  fluxo reassina `tx = persistPending(tx, key)` — evita apagar a coluna
  `idempotency_key` no save posterior); (4) Spec 31 — validação CPF/CNPJ por
  comprimento + `DocumentValidator.isValid` (métodos `isValidCpf/Cnpj` são
  privados); (5) Spec 33 — ciclo da dívida fechado: `collect` re-cobra faturas
  `OVERDUE` e o job quita receivables de estorno com saldo disponível; Spec 34
  explicitou que `RECEIVABLE_RECOVERY` é só para receivables de estorno (sem
  dupla contagem com as fees da fatura).
- **Spec 27 (`audited`, 2026-08-04):** wallet ledger — auditoria do ciclo SDD.
  Verificação: migration **V17** `wallet_ledger` aplicada (tabela + índices
  `ix_wallet_ledger_merchant_created`/`ix_wallet_ledger_ref` + unique
  `uq_wallet_ledger_ref` com `entry_type`); recorder integrado nos **4 fluxos**
  de mutação do saldo na mesma transação (`ProcessPixWebhookService`
  SALE_CREDIT/REFUND_DEBIT, `RegisterChargebackService` CHARGEBACK_DEBIT,
  `WithdrawalService` WITHDRAWAL_DEBIT) com `balance_after` = saldo pós-mutação
  sob lock pessimista; backfill `OpeningBalanceSeedRunner` idempotente
  (`OPENING_BALANCE` = saldo atual, re-boot não duplica); consultas
  `GET /api/v1/master/merchants/{id}/ledger` (ROLE_MASTER) e
  `GET /api/v1/portal/wallet/ledger` (ROLE_MERCHANT_PORTAL, só o próprio — sem
  parâmetro de lojista, impossível IDOR) paginadas com filtros type/from/to e
  400 `invalid_argument`. `mvn test` verde (**246 testes, 0 falhas**) e E2E
  **13/13** sem regressão (cenário 11 novo: SALE_CREDIT 150 → balance 150,
  CHARGEBACK_DEBIT 150 → balance 0, filtro por tipo, 400/401).
- **Spec 31 (`audited`, 2026-08-04):** destinos de saque do lojista — auditoria
  do ciclo SDD. Verificação: migration **V20**
  `merchant_withdrawal_destinations` aplicada (5 tipos: PIX_CPF/PIX_CNPJ/
  PIX_PHONE/PIX_EVP/BANK_ACCOUNT; campos sensíveis criptografados AES-256/GCM
  via `CryptoConverter` — `v1:` no banco, verificado via SQL no E2E); validação
  por tipo (`WithdrawalDestinationValidator`: CPF/CNPJ dígitos verificadores,
  celular BR, EVP UUID, banco/agência/conta obrigatórios); privacidade total por
  lojista (merchantId do principal, `findByIdAndMerchantId` → 404 cross-loja sem
  revelar existência; master sem acesso); resposta **sempre mascarada**
  (`WithdrawalDestinationMasker`: CPF `529.***.***-25`, banco `Banco 341 - ag
  0001 - conta ***-9`, etc. — valor em claro nunca exposto); gestão completa
  (criar/listar/PATCH rótulo/ativar/desativar 204) com limite de ativos default
  5 → 409 `too_many_destinations`. `mvn test` verde (**246 testes, 0 falhas**) e
  E2E **13/13** sem regressão (cenário 12 novo: tipos válidos/inválidos, máscara,
  ativar/desativar, 404 cross-loja, limite 409, 401/403).
- **Spec 32 (`audited`, 2026-08-04):** saque com destino + bloqueio lacrado —
  auditoria do ciclo SDD. Verificação: migration **V21** (`withdrawals` ganha
  `destination_id`, `type` default `MERCHANT`, `reason`,
  `destination_key_snapshot` criptografado AES-256/GCM + índice
  `ix_withdrawals_type`); saque do vendedor exige `destinationId` (400 sem ele,
  404 de outro lojista sem revelar existência, 409 inativo) e grava snapshot +
  `WITHDRAWAL_DEBIT` no ledger (Spec 27) sob o lock pessimista; **bloqueio
  lacrado**: `Merchant.withdraw` (vendedor) valida ACTIVE+desbloqueio+saldo →
  409 `withdrawals_blocked` sem exceção, e `Merchant.adminWithdraw` (master)
  valida **apenas** saldo — recolhe de lojista bloqueado/suspenso (201),
  nunca negativo (409 `insufficient_balance` se `amount > saldo`), exige
  `destinationKey`+`reason` (400); `type=ADMINISTRATIVE` visível em
  `GET /api/v1/master/withdrawals`; resposta do saque sempre com destino
  **mascarado** (Spec 31) e `walletBalance` pós-débito no POST; sem overdraft em
  saques sequenciais. `mvn test` verde (**246 testes, 0 falhas**) e E2E **13/13**
  sem regressão (cenário 13 novo: saque com destino, bloqueio lacrado, saque
  administrativo, 400/404/409).
- **Spec 33 (`audited`, 2026-08-04):** faturamento automático e contas a
  receber — auditoria do ciclo SDD. Verificação: migration **V22**: tabela
  `receivables` (origem
  `INVOICE`/`CHARGEBACK`/`REFUND`/`ADJUSTMENT`, `paid_amount`, unique
  `(merchant_id, origin, reference_id)`) + `invoices.paid_amount`/`collected_at`.
  **Job diário** `InvoiceCollectionScheduler` (ShedLock `nyxcash-invoice-collection`,
  cron 02:30 UTC configurável) cobra faturas `PENDING`/`OVERDUE` com saldo
  devedor: saldo suficiente → debita o total + invoice `PAID` + entry
  `INVOICE_FEE_DEBIT` (Spec 27) **cumulativa** na mesma transação; saldo
  insuficiente → debita o disponível, invoice `OVERDUE` e receivable `OPEN` com
  o saldo devedor (`upsertOpen`); re-cobra `OVERDUE` quando o saldo volta e
  **quita receivables de estorno** (`CHARGEBACK`/`REFUND`) com o saldo disponível
  (`RECEIVABLE_DEBIT` cumulativa — a receita fica para a Spec 34). **Estorno não
  coberto** (Spec 29): saldo negativo no chargeback/refund → receivable
  `CHARGEBACK`/`REFUND` na mesma transação (`ReceivableRecorder`). **Bloqueio por
  inadimplência**: lojista com receivable `OPEN`/`PARTIAL` → saque do vendedor
  **409 `outstanding_debt`** (`Merchant.ensureNoOutstandingDebt`, configurável
  `nyxcash.billing.block-withdrawals-on-debt` default true); saque administrativo
  do master **não** valida. REST (ROLE_MASTER): `POST
  /merchants/{id}/invoices/{invoiceId}/collect` → `{debit, remaining}` (manual,
  idempotente), `GET /receivables` e `GET /merchants/{id}/receivables` paginados
  com filtros (`merchantId`/`status`/`origin`/`from`/`to`, 400
  `invalid_argument`). `mvn test` verde (**267 testes: 246 + 21 novos** —
  `InvoiceCollectionServiceTest` 9, `ReceivableTest` 7, `WalletLedgerRecorderTest`
  +2, `WithdrawalServiceTest` +3) e E2E **14/14** sem regressão (cenário 14 novo:
  cobrança total/parcial, receivable OPEN→PAID, bloqueio por dívida 409, saque
  administrativo do master com dívida, re-cobrança de OVERDUE, idempotência,
  ledger `INVOICE_FEE_DEBIT`). Fixes do audit: `markPaidIfOpen` grava
  `paid_amount = amount` (receivable INVOICE quitado com valor total rastreável)
  e `upsertOpen` na re-cobrança parcial acumula `paid_amount` + `PARTIAL` (a
  dívida formal reflete o pago contra ela); E2E 14.12 cobre o critério 6
  (`GET /api/v1/master/receivables` global paginado/filtros, 400
  `invalid_argument`, 401 sem auth).
- **Spec 34 (`audited`, 2026-08-04):** ledger do master — receita/perda da
  plataforma na mesma transação. Migration **V23** `master_ledger` (unique
  `(merchant_id, entry_type, reference_type, reference_id)` + índices),
  `MasterLedgerEntryType` (TRANSACTION_FEE/MONTHLY_FEE/RECEIVABLE_RECOVERY/
  ADMIN_RECOVERY/CHARGEBACK_LOSS), `MasterLedgerRecorder` com `balance_after`
  integrado nos fluxos origem (fatura paga → TRANSACTION_FEE+MONTHLY_FEE,
  receivable pago → RECEIVABLE_RECOVERY, saque administrativo → ADMIN_RECOVERY,
  write-off → CHARGEBACK_LOSS). **Write-off** `POST
  /api/v1/master/receivables/{id}/write-off` (ROLE_MASTER, `reason` obrigatório
  → 400 `reason_required`, receivable → PAID, CHARGEBACK_LOSS, auditado Spec 37);
  **receita** `GET /api/v1/master/revenue/summary` (SUM/COALESCE; net = entradas
  − perdas) e `GET /api/v1/master/revenue/ledger?type=` paginado. Fix de
  contrato: `WriteOffRequest` **sem** `@NotBlank` (a validação de `reason` é do
  domínio → `reason_required`; Bean Validation duplicado devolvia
  `validation_error`). `mvn test` verde (**284 testes**) e E2E **15/15** sem
  regressão (cenário 18: write-off sem/com reason, receivable PAID,
  CHARGEBACK_LOSS no ledger, summary com losses 80.00). **Auditoria via API
  real: 7/7 critérios OK** — (1) V23 com unique por origem (entry_type na chave,
  fatura paga → 2 entries) + índices; (2) fatura paga → `TRANSACTION_FEE` +
  `MONTHLY_FEE` na mesma transação (2 entries, mesma ref; re-collect idempotente
  debit 0 sem duplicar) e `balance_after` encadeado (Σ = total); (3)
  `RECEIVABLE_RECOVERY` validado em unit (fluxo via job agendado, sem endpoint
  manual); (4) saque administrativo → `ADMIN_RECOVERY` (250,00); (5) write-off →
  receivable PAID + `CHARGEBACK_LOSS` (20,95) + auditoria, sem `reason` → 400
  `reason_required`, re-write-off → 409; (6) `summary` `{total: 341.9, net: 300}`
  consistente com Σ entries, `ledger` paginado/filtrado, 400 `invalid_argument`,
  401; (7) `mvn test` 299/0/0 + E2E 16/16.
- **Spec 37 (`audited`, 2026-08-04):** auditoria de ações do master —
  trilha imutável. Migration **V26** `master_audit_log` (append-only + índices
  merchant/actor/action×created_at), `MasterAuditRecorder` grava na mesma
  transação da ação (actorId do principal, before/after, reason, ip —
  serializado com máscara de dados sensíveis), `MasterAuditController`
  (`GET /api/v1/master/audit` com filtros merchantId/action/actorId/from/to +
  paginação, **sem** rotas de mutação). Pontos integrados: block/release
  (WITHDRAWALS_BLOCK/RELEASE, Spec 14), saque administrativo
  (ADMINISTRATIVE_WITHDRAWAL, Spec 32 — reason obrigatório), write-off
  (RECEIVABLE_WRITE_OFF, Spec 34), collect manual (INVOICE_COLLECT, Spec 33);
  RISK_ADJUSTMENT reservado no enum (Spec 19). Fix de contrato:
  `GlobalExceptionHandler` ganhou handler para
  `HttpRequestMethodNotSupportedException` → **405 method_not_allowed** (antes
  caía no handler genérico → 500) — POST /audit retorna 405 (imutável). `mvn
  test` verde (**284 testes**) e E2E **15/15** sem regressão (cenário 18:
  block gera trilha com before/after, write-off auditado com reason, 401/403,
  POST /audit → 405). **Auditoria via API real: 5/5 critérios OK** — (1) V26
  com colunas (actor_id/action/merchant_id/before_json/after_json/reason/ip) +
  índices; (2) cada ação gera entrada na mesma transação — block before
  `false→true`, release `true→false`, write-off/saque administrativo com reason,
  collect manual `PENDING→PAID` (e o re-collect idempotente `PAID→PAID` audita
  também — toda ação administrativa deixa trilha), actor = master em todas; (3)
  saque administrativo e write-off sem `reason` → 400 `reason_required`; (4)
  `GET /audit` com filtros merchantId/action/actorId/período + paginação, POST
  → 405 (imutável), 401 sem token; (5) `mvn test` 299/0/0 + E2E 16/16.
- **Spec 35 (`audited`, 2026-08-04):** webhook de saída para o bot —
  auditoria do ciclo SDD. Verificação: migration **V27** `merchant_webhooks`
  aplicada (URL + segredo criptografado AES-256/GCM com prefixo `v1:` no banco,
  verificado via SQL — events CSV, status ACTIVE/INACTIVE) e `notification_outbox`
  com `attempts`/`last_error`/`next_attempt_at` (+ índice
  `(status, next_attempt_at)`). **Domínio:** `WebhookEventType` (7 eventos) +
  `MerchantWebhook` imutável (create/activate/deactivate/isSubscribedTo).
  **Publicação na mesma transação** (`WebhookEventPublisher`, envelope
  `{eventId, type, merchantId, createdAt, data}` — `eventId` único para o
  receptor deduplicar): `CHARGE_CREATED` (cobrança), `SALE_PAID`/`SALE_REFUNDED`
  (webhook de pagamento), `CHARGEBACK_REGISTERED`, `WITHDRAWAL_COMPLETED`
  (lojista e administrativo), `INVOICE_GENERATED` (Spec 7, agora envelope) e
  `INVOICE_COLLECTED`. **Entrega assinada:** `HttpMerchantNotifier` (`@Primary`,
  fallback log da Spec 7 sem webhook ativo) faz `POST` com
  `X-FoxCash-Signature` (HMAC-SHA256 hex do raw body com o segredo do lojista)
  + `X-FoxCash-Event-Id`/`X-FoxCash-Event`. **Retry/backoff:** dispatcher usa
  `findPendingDue(now)`, falha → `incrementAttempts` + `markFailed` com
  `next_attempt_at = now + base × 2^attempt` (`nyxcash.notification.max-attempts`
  default 5 → `FAILED` com `last_error`; re-execução não re-entrega FAILED).
  **REST no portal** (`ROLE_MERCHANT_PORTAL`, privado por lojista — 404
  cross-loja sem revelar existência): GET (secret nunca exposto, `hasSecret`),
  POST 201 (segredo gerado no servidor, devolvido **uma única vez**), PATCH,
  activate/deactivate 204, `POST /test` → `{delivered, statusCode?, error?}`
  (ping assinado, não persiste); evento inválido → 400 `invalid_event`;
  404 `webhook_not_found`. Contrato de assinatura documentado no README. `mvn
  test` verde (**299 testes, 0 falhas**) e E2E **16/16** sem regressão (cenário
  15 novo: configurar webhook, receber `SALE_PAID` assinado com assinatura
  reproduzível, retry com 500 → PENDING attempts=1 + backoff, re-entrega após
  forçar `next_attempt_at` → SENT com 2 entregas do mesmo evento). **Auditoria
  via API real: 7/7 critérios OK** — (1) secret `v1:` no banco + colunas de
  retry no outbox; (2) CRUD privado (201 com secret 64hex UMA vez, list com
  `hasSecret` sem secret, PATCH 200, activate/deactivate 204, evento inválido
  400 `invalid_event`, 401 sem token, 403 com API key, 404 `webhook_not_found`
  cross-loja); (3) os **7 eventos** publicados no outbox nos pontos de origem
  (cobrança/saque/estorno/chargeback/fatura gerada e cobrada — verificado no
  banco); (4) entrega `POST` assinado com assinatura HMAC reproduzível em todas
  as entregas e `X-FoxCash-Event-Id` = id do outbox (PING do /test não persiste,
  por design); (5) `eventId` único (11 únicos / 11 eventos); (6) `/test` →
  `{delivered: true, statusCode: 200}`; (7) `mvn test` 299/0/0 + E2E 16/16.
- **Spec 36 (`audited`, 2026-08-04):** saque via PSP — PIX de saída com
  estado real. Migration **V28** `withdrawals` ganha `external_reference`/
  `payout_provider`/`failure_reason`/`failed_at` + índice `(status, created_at)`
  (o `status` passa a usar PROCESSING/COMPLETED/FAILED — default COMPLETED
  preserva histórico; V25 ficou planejada, V26/V27 tomaram os números). **Porta**
  `PayoutProviderClient` (`createPayout` + `getPayout` p/ reconciliação) +
  `MockPayoutProviderClient` (`@ConditionalOnProperty payout-provider=mock`,
  default; `mock-payout-failure` simula falha; provider desconhecido → bean
  ausente = fail-fast). **Saque nasce PROCESSING:** `WithdrawalService`/
  `MasterWithdrawalService` debitam (imediato), chamam o provider na mesma
  transação e gravam `external_reference`+`payout_provider`; falha síncrona →
  rollback. **Webhook de saída** `POST /webhooks/payouts` (segredo próprio
  `nyxcash.psp.payout-webhook.*`; `WebhookSignatureFilter` generalizado p/ os
  2 paths): `WithdrawalSettlementService` — `PAID` → `COMPLETED` (publica
  `WITHDRAWAL_COMPLETED` — agora na conclusão, não na solicitação), `FAILED` →
  `FAILED` + estorno automático (`credit` + ledger `WITHDRAWAL_REVERSAL` na
  mesma transação), idempotente via gate de status sob lock
  `findByExternalReferenceForUpdate`; externalReference desconhecido → 404.
  **Reconciliação:** `WithdrawalReconciliationService` + scheduler ShedLock
  (`nyxcash-payout-reconciliation`, cron 04:30 UTC) encerra PROCESSING órfãos
  consultando o PSP e reusa o settlement (sem duplicar estorno). `mvn test`
  verde (**313 testes: 299 + 14 novos** — settlement 4, reconciliation 4, mock
  payout 3, filtro payout 2, rollback do provider 1) e E2E **18/18** sem
  regressão (cenários 16/17 novos: PROCESSING → COMPLETED via webhook PAID e
  FAILED → estorno com WITHDRAWAL_REVERSAL no ledger; cenário 13 atualizado p/
  PROCESSING + conclusão via webhook). **Auditoria via API real: 6/6 critérios
  OK (25/25 checks)** — (1) V28 com colunas + índice `(status, created_at)`
  aplicada; (2) fail-fast real: boot com `payout-provider=inexistente` falha
  ("No qualifying bean of type 'PayoutProviderClient'... APPLICATION FAILED TO
  START"); (3) saque do vendedor e administrativo → 201 `PROCESSING` +
  `PO<32 hex>` + débito imediato + `payout_provider=MOCK` no banco; (4) webhook
  `PAID` → `COMPLETED` (replay 204 no-op, saldo inalterado), `FAILED` → estorno
  (saldo devolvido + `WITHDRAWAL_REVERSAL` no ledger + motivo no banco, replay
  no-op sem duplo estorno), externalReference desconhecido → 404
  `withdrawal_not_found`; (5) reconciliação via unit tests + config ShedLock
  verificada (cron 04:30 UTC — job não executado ainda, esperado); (6) `mvn
  test` 313/0/0 + E2E 18/18.
- **Auditoria de segurança ofensiva (2026-08-04) — 1 vulnerabilidade real
  corrigida:** suíte de ataques autorizados contra o app local (authN/authZ +
  IDOR, injeção/validação, lógica de negócio, rate limiting) encontrou um
  **broken access control**: o token de sessão do portal (`ROLE_MERCHANT_PORTAL`)
  era aceito em **rotas de API** (`GET /merchants/{id}`, `GET /transactions`,
  `POST /nyx/pix/cob`, `GET /invoices` → 200/201) porque
  `anyRequest().authenticated()` aceita qualquer autenticação. **Fix no
  `SecurityConfig`:** as rotas `/api/v1/**` (não-portal/admin/master) passam a
  exigir role de API (`hasAnyRole(ADMIN, MASTER, MERCHANT)`); o saque do lojista
  (`/api/v1/merchants/*/withdrawals/**`) mantém `MERCHANT_PORTAL` como credencial
  válida (tela de saque do portal — Spec 14/32). E2E cenário 9 ganhou cobertura
  do fix (portal → 403 em API, mas autorizado no saque). **Demais vetores
  testados e bloqueados (42/42 checks):** 401/403 por role, IDOR cross-loja
  (404/403 sem vazar existência), SQLi parametrizado (JPQL/Specifications — sem
  stack/erro), mass assignment ignorado (banco ACTIVE/saldo 0), valores
  inválidos (negativo/zero/teto/expiração → 400), gift XOR → 400, replay de
  webhook PAID/REFUNDED/payout sem duplo crédito/débito/estorno, chargeback em
  PENDING/duplicado → 409, saque concorrente sem overdraft (201+409), rate
  limit 429 em flood de cadastro/login. XSS em label: dado bruto em API JSON
  (informativo — sanitizar no renderizador). `mvn test` 313/0/0 e E2E **18/18**
  sem regressão.
- **Auditoria completa do backend (2026-08-05) — 3 ALTOS + 13 MÉDIOS/BAIXOS
  corrigidos (commit `8fa06a7`):** **A1 SSRF** (webhooks de saída validados por
  esquema + IP público no create/update/entrega/test; flag
  `nyxcash.notification.allow-loopback`), **A2 contábil** (`upsertOpen` mantém o
  `amount` original do receivable — `remaining()` nunca negativo), **A3
  dispatcher** (transação por notificação + lote 50 + ShedLock 10min;
  `incrementAttempts` retorna o contador real). MÉDIOS: **M1a** PSP chamado após
  o commit nos fluxos de cobrança (`PixChargePersistence`: PENDING → PSP →
  attach; falha → REJECTED), **M4** chargeback duplicado serializado por lock →
  409 (não 500), **M6** suspend/release do master auditados
  (`RISK_ADJUSTMENT`, Spec 37), **M7** listagens por lojista limitadas (200) e
  recentSales via query, **M8** counts/somas via SQL (sem carregar entidades),
  **M9** `Money` rejeita >2 casas decimais, **M13** webhook tolera eventos fora
  de ordem (no-op, sem 500/retry), **M10** `X-Forwarded-For` sob
  `nyxcash.security.trust-proxy` (default false), **M2** `@Valid` + limites de
  tamanho nos DTOs do portal, **M3** `MerchantDashboard.summary` respeita a
  janela from/to, **M5** write-off adquire lock do lojista. BAIXOS: B1
  `PagarMe.toCents` em long (sem overflow > R$ 21,4M), B2 código morto removido,
  B3 deploy/README migrações V1→V28, B4 webhook payload limitado a 1 MiB (413),
  B7 logs DEBUG mascaram apiKey/password/token/secret/document, B9
  `handleIllegalState` loga WARN. `mvn test` **352/0/0**; E2E com
  `NYX_NOTIFICATION_ALLOW_LOOPBACK=true` (receptor local no cenário 15).

## Specs 28, 29 e 30 implementadas e auditadas (2026-08-05)

- **Spec 28 (`audited`, 2026-08-05):** idempotência na criação de cobrança.
  Migration **V29** `pix_transactions.idempotency_key VARCHAR(64)` + índice único
  parcial `(merchant_id, idempotency_key)` (por lojista — lojistas diferentes
  reusam a mesma chave). `idempotencyKey` **OBRIGATÓRIA** (`@NotBlank` → 400
  `validation_error`) em `/transactions`, `/nyx/pix/cob`, `/portal/charges` e
  `/portal/charges/gift`. `PixTransaction` ganhou `fail()` (PENDING → FAILED) e
  o campo persistido `idempotencyKey`; `IdempotencyPersister` (bean, transação
  própria `REQUIRES_NEW` via `TransactionTemplate`) persiste a PENDING com a
  chave ANTES do PSP — um timeout não apaga o registro. Retry idempotente
  (`findByMerchantIdAndIdempotencyKey`): replay → mesma transação sem nova
  chamada ao PSP; PENDING sem QR (timeout anterior) → re-tenta com o mesmo
  `code` (= transactionId, idempotente no Pagar.me); FAILED → 409
  `idempotency_conflict`. `PixProviderClient` ganhou exceções tipadas:
  `PixProviderException` (erro real → FAILED + rethrow) e
  `PixProviderTimeoutException` (→ **502 `psp_timeout`**, PENDING persiste);
  `PagarMe` mapeia `ResourceAccessException` → timeout e demais
  `RestClientException` → erro real; mock com `nyxcash.psp.mock-timeout`
  (1ª tentativa por transactionId falha) + cache idempotente por code. `mvn
  test` **373/0/0** (6 testes novos de idempotência) e E2E **20/20** (2b novo:
  replay mesma chave → mesmo transactionId, chave ausente → 400, mesma chave
  em outro lojista permitida). Validação via API real com `NYX_MOCK_TIMEOUT=true`:
  **502 `psp_timeout` → retry 201 (mesmo transactionId, QR anexado) → replay
  201 (mesmo tx) → 400 sem chave; 1 única transação no banco para a chave**
  (sem duplicidade).
- **Spec 29 (`audited`, 2026-08-05):** estorno consistente — fonte única de
  débito. `RegisterChargebackService.register` agora chama **`transaction.refund()`**
  (APPROVED → REFUNDED, persistida) ao registrar o chargeback — o webhook
  `REFUNDED` posterior vira **no-op** (status já REFUNDED) e o débito acontece
  UMA única vez (1 entry `CHARGEBACK_DEBIT`). Duplicado (sequencial ou corrida
  na unique `uq_chargebacks_transaction`) → **409 `chargeback_already_exists`**
  (nova `ChargebackAlreadyExistsException`; backstop catch de
  `DataIntegrityViolationException` → rollback do refund + 409). Bônus M4: a
  venda estornada deixa de aparecer como "concluída" no portal (Spec 25 filtra
  por APPROVED) — E2E 10/13/14 ajustados. O webhook fora de ordem já era no-op
  (M13). `mvn test` **373/0/0** (1 teste novo: corrida na unique → 409) e E2E
  **20/20** (7b novo: chargeback → transação REFUNDED, webhook REFUNDED
  pós-chargeback não debita de novo — saldo estável, duplicado → 409
  `chargeback_already_exists`).
- **Spec 30 (`audited`, 2026-08-05):** reconciliação e integridade do saldo.
  Migration **V30** `reconciliation_issues` (expected/actual balance, status
  OPEN|RESOLVED, índice `(merchant_id, status)` + unique parcial
  `uq_reconciliation_open`). `WalletReconciliationService` valida, por lojista,
  `wallet_balance == balance_after` da última entry do ledger (Spec 27);
  divergência → issue OPEN idempotente (re-execução não duplica — inclusive em
  corrida via `DataIntegrityViolationException` → reaproveita a OPEN); sem
  ledger → consistente por definição; `orphanCharges` (PENDING sem
  externalReference, timeout da Spec 28) no resumo. `WalletReconciliationScheduler`
  (ShedLock `nyxcash-wallet-reconciliation`, cron `nyxcash.scheduler.reconciliation-cron`
  default 04:00 UTC). REST (ROLE_MASTER): `POST /api/v1/master/reconciliation/run`
  → `{checked, ok, issues, orphanCharges}` e `GET .../issues` paginado com
  filtros `merchantId`/`status` (400 `invalid_argument` via `QueryParamParser`).
  Fatura via COUNT já existia (`countByMerchantIdAndStatusAndCreatedAtBetween`);
  escala 2 reforçada com `Money.normalize(BigDecimal)` aplicado no volume das
  métricas de vendas (ex.: `150.00`, não `150.0`). `mvn test` **373/0/0** (9
  novos: service 5, controller 5, adapter 3, Money normalize 1) e E2E **20/20**
  (18.11 novo: run manual → resumo, UPDATE direto no saldo → issue OPEN com
  expected/actual, re-execução não duplica, 400 em parâmetro inválido, 401 sem
  auth). **Nota:** migrations usaram **V29/V30** (não V18/V19) porque o banco já
  tinha V20–V28 aplicados — mesmo padrão documentado ("V25 ficou planejada,
  V26/V27 tomaram os números"); Flyway sem `out-of-order`.

**Plano de retomada executado:** Spec 28 (independente, base pronta) → Spec 29
(fecha o duplo débito do chargeback) → Spec 30 (backstop de integridade), cada
uma com `mvn test` + E2E + validação via API real.

## Fase 7 — PIX de saída pronto para o adquirente real (spec 38, 2026-08-05)

- **Spec 38 (`audited`, 2026-08-05):** preparação plug-and-play do adquirente
  real de PIX de saída (Pagar.me/Asaas — ADR-005). **Robustez:** `PayoutStatus`
  ganhou **`PENDING`** (estado intermediário de adquirentes reais) e a
  reconciliação (`WithdrawalReconciliationService`) **não liquida** saque
  `PROCESSING` quando o PSP responde `PENDING` (aguarda o próximo run) — antes,
  qualquer estado ≠ PAID virava `FAILED` e **estornava o débito indevidamente**.
  **Config por adquirente:** `PspProperties` ganhou blocos `payout-pagarme`/
  `payout-asaas` (secret-key/base-url) com **fail-fast no boot** quando o
  provider apontar para o adquirente (mesmo padrão da cobrança Spec 10/11);
  `application-prod.yml` agora exige **`NYX_PSP_PAYOUT_PROVIDER` sem default**
  (mock nunca em prod por acidente — verificado: boot `prod` sem a env var falha
  com `Could not resolve placeholder`). **Teste de contrato:**
  `PayoutProviderContractTest` (harness abstrato: aceite devolve
  externalReference não-vazia, `getPayout` devolve estado canônico, falha
  síncrona lança) — o `MockPayoutProviderClient` herda e passa; o adapter real
  do adquirente herda o teste e ganha a garantia de contrato automaticamente.
  **Runbook:** `deploy/payout-homologation.md` documenta o contrato da porta, o
  mapeamento de estados, o webhook de saída, a config por adquirente e o
  **checklist** para plugar o adquirente. README atualizado. `mvn test`
  **382/0/0** (9 novos: 3 contrato, 1 reconciliação PENDING, 5 fail-fast do
  `PspProperties`) e E2E **20/20** sem regressão (mock segue default em dev).
  **Próximo passo (quando o adquirente for definido):** implementar o adapter
  `@ConditionalOnProperty(payout-provider=<adquirente>)` + herdar o contrato +
  preencher as credenciais — sem mexer em reconciliação/webhook/estorno.

## Fase 8 — Cadastro sem CPF (spec 39, 2026-08-05)

- **Spec 39 (`audited`, 2026-08-05):** cadastro com **nome, e-mail e senha**
  (sem CPF/CNPJ). Migration **V31**: `merchants.email` (único, normalizado em
  minúsculas — `uq_merchants_email` sobre `lower(email)`) + `merchants.name`
  (backfill de `legal_name`); `document`/`document_hash` **anuláveis**
  (`uq_merchants_document_hash` vira **parcial** — documento nulo não colide);
  `trading_name`/`legal_name` removidos. **Domínio:** `Merchant.register(name,
  email, plan, apiKeyHash)` normaliza o e-mail; `Merchant.restore` novo com
  `name`/`email`/`document`; `EmailValidator` (formato simples).
  **Cadastro** (`RegisterMerchantService`): `Command(name, email, password)`
  — nome 1–120, e-mail válido/único (duplicado → **409 `merchant_already_exists`**
  com backstop do índice único em corrida), senha **escolhida pelo usuário**
  8–72 (hash **BCrypt** em `merchant_credentials`, nunca em claro); **não
  devolve mais `portalPassword`**; `PortalPasswordGenerator` removido (código
  morto). **Login** (`MerchantPortalAuthService`): `LoginCommand(email,
  password)` via `findByEmail` — o documento não funciona mais como
  identificador; `LoginResult` com `name`. **DTOs/contratos**:
  `CreateMerchantRequest {name,email,password}`, `MerchantRegisteredResponse`
  (name/email/apiKey), `MerchantResponse`, `PortalMeResponse`,
  `MerchantLoginRequest {email,password}`, `MerchantLoginResponse (name)`;
  admin busca por `name`. **Logs:** `email` entra na lista de campos mascarados
  do `SensitiveDataLoggingFilter` (B7). `mvn test` **387/0/0** (4 novos:
  `EmailValidatorTest` 2, cadastro 2) e E2E **20/20** (cenário 1 novo:
  cadastro por e-mail/senha → 201 com apiKey, `document` nulo e `password_hash`
  BCrypt no banco, duplicado por e-mail → 409; login por e-mail; sem regressão).
  Fim do ciclo SDD (requirement status `audited`).

## Fase 9 — Recuperação de senha e gestão de perfis master (2026-08-05)

- **Spec 40 (`audited`, 2026-08-05):** recuperação de senha do lojista —
  **token self-service one-time** via `/api/v1/merchant-auth` (públicos).
  Migration **V32** `password_reset_tokens` (token_hash SHA-256 único,
  expires_at, used_at single-use, created_at; índice único parcial
  `(merchant_id) WHERE used_at IS NULL` — um token ativo por lojista).
  **Domínio:** `PasswordResetToken.issue/restore/isValid/use` +
  `InvalidResetTokenException` (400 `invalid_token`) +
  `ResetTokenAlreadyOpenException` (409, backstop de corrida no índice) +
  portas `ResetTokenGenerator` (`rst_<32 hex>`, hash via `DocumentHasher`),
  `PasswordResetTokenRepository` (save/findByTokenHash/deleteOpenByMerchantId),
  `PasswordResetUseCase`. **Serviço:** `forgot` normaliza e-mail, apaga token
  aberto, gera/salva (hash) e devolve o token **só quando
  `nyxcash.auth.expose-reset-token=true`** (dev; prod `false` → resposta sempre
  **204** — anti-enumeração); `reset` valida senha 8–72, consome o token
  (expirado/usado/desconhecido → 400), troca o `password_hash` (**BCrypt**) via
  `MerchantCredential.changePassword`, marca `used` e **revoga todas as sessões
  ativas** (`MerchantSessionRepository.revokeAllByMerchantId`, bulk UPDATE) na
  mesma transação. **REST:** `POST /forgot-password {email}` (200 `{resetToken}`
  dev / 204 prod-e-inexistente) e `POST /reset-password {token, newPassword}`
  (204); `SecurityConfig` permitAll (posse do token = credencial);
  `RateLimitFilter` inclui forgot-password; `resetToken`/`reset_token`
  mascarados nos logs. Config `nyxcash.auth.reset-token-ttl-seconds` (1800) +
  `AuthProperties` + bean `resetTokenTtl`. `mvn test` **404/0/0** (17 novos:
  `PasswordResetServiceTest` 10, `MerchantCredentialTest` 2,
  `MerchantAuthControllerTest` 5 — prod/expose=false → 204) e E2E **21/21**
  (cenário 21 novo: forgot → 200 com token (só hash no banco), reset → 204,
  login antigo 401 / novo 200, token reusado 400 `invalid_token`, sessão antiga
  revogada 401, `password_hash` BCrypt novo, e-mail inexistente 204, senha curta
  400; sem regressão). Fim do ciclo SDD (requirement status `audited`).

- **Spec 41 (`audited`, 2026-08-05):** gestão de perfis master + relatório
  unificado de atividades. Migration **V33** — parte 1:
  `masters.can_manage_masters` (backfill do master mais antigo → admin) e
  parte 2: tabela **`platform_activity_log`** (append-only, índices
  created_at/merchant_id/action). **Parte A — perfis master (admin-only, rotas
  não-documentadas):** `POST /api/v1/master/profiles` cria perfil com **senha
  gerada pelo sistema** (`mst_<16 hex>` via `MasterPasswordGenerator`, devolvida
  1x — só o hash BCrypt persiste), `GET /profiles` (nunca expõe o hash) e
  `POST /profiles/{id}/activate|deactivate` (204); sem a flag → **403
  `forbidden`**; limite **4 perfis** (`nyxcash.master.max-profiles`) → 5º **409
  `too_many_masters`**; username duplicado → 409 `master_already_exists`;
  deactivate de si mesmo → 409 `cannot_deactivate_self`; último admin → 409
  `cannot_deactivate_last_admin`; auditoria Spec 37
  (`MASTER_CREATED/ACTIVATED/DEACTIVATED`). **Parte B — trilha unificada:**
  `PlatformActivityRecorder` instrumenta **17 eventos** de MASTER/MERCHANT/
  SYSTEM na mesma transação (login/logout, cadastro, reset de senha, cobrança,
  venda, chargeback, saque, fatura, gestão de perfil) com dados sensíveis
  mascarados e IP via `ClientIpResolver` (null em jobs); `GET
  /api/v1/master/report/activity` (admin-only) consulta paginado com filtros
  `from/to/actorType/action/merchantId` (400 em inválido; 401/403). `mvn test`
  **418/0/0** (novos `MasterProfileServiceTest` 9, `MasterPasswordGeneratorTest`,
  `PlatformActivityRecorderTest`, `PlatformActivityServiceTest` + instrumentação
  por mock nos serviços) e E2E **22/22** (cenário 22 **auto-suficiente**: cria
  perfil 201 com `temporaryPassword` 1x, login do novo perfil 200, 403 sem
  flag, 409 self/5º, relatório com eventos reais filtrados por action+lojista,
  400/401/403, auditoria; sem regressão). Fim do ciclo SDD
  (roadmap/requirements/task/vision V24 `audited`).

**Ciclo SDD completo (2026-08-05):** specs 1–41 `audited` — backend TrevoPay
finalizado; verificação final em "Estado atual (2026-08-05)".

## Observações pós-auditoria (fora de escopo, para spec futura)

- (resolvida pela Spec 5) Cadastro de lojista com documento duplicado retornava
  `500 internal_error`; agora responde `409 Conflict` com `merchant_already_exists`.
- **Políticas de Uso e Compliance (v1.0, Ago/2026)** criadas em
  [`docs/politicas-uso.md`](../docs/politicas-uso.md): descrevem a operação,
  monitoramento de risco (§4 — chargeback > 1%/3%, reembolso > 5%, fraude,
  denúncias 3+/30 dias) e consequências gradativas (§7 — advertência →
  suspensão → bloqueio → reserva). Decisão técnica em **ADR-008**; execução na
  **Spec 16** (antifraude/compliance, `audited`).
- **Decisão de produto (2026-08-04): sem KYC; gateway somente para o vendedor.**
  A Spec 17 (KYC documental do lojista no onboarding — RG/CNH, selfie etc.) foi
  **descartada** (`discarded`). O cadastro do lojista mantém o CPF/CNPJ válido
  (documento fiscal, Spec 18). O gateway **não captura dados pessoais do
  comprador**: o contato com o cliente é do **bot do lojista** —
  `POST /api/v1/nyx/pix/cob` recebe `merchantId`, `amount`, `expirationSeconds`
  e um `buyerId` **opcional e pseudônimo** (sem PII; apenas o hash SHA-256 é
  persistido) para antifraude; as colunas `payer_name`/`payer_phone`/
  `payer_document` foram removidas (migration **V11**).
- **Antifraude real (Spec 19, 2026-08-04):** o problema é chargeback (o
  comprador usa o produto e contesta no banco). Com o `buyerId` pseudônimo do
  bot, a triagem em tempo real bloqueia **apenas** o comprador com histórico de
  chargeback (`fraud_buyer_blocked`, 409 na hora). **Velocidade foi removida**
  (ajuste 2026-08-04: compra rápida em sequência é normal — não se bloqueia
  venda legítima). Chargebacks são registrados pelo lojista/bot ou master
  (tabela `chargebacks`, migration **V12**), alimentando o indicador real da
  Spec 16; chargeback ≥ 3% (limiar duro) **suspende o lojista automaticamente**
  (desligável via `nyxcash.risk.auto-suspend-enabled`); snapshot de risco
  ganhou **score 0-100** exposto no dashboard master. Docs atualizadas: README,
  visão-cliente,
  políticas e visão V16.
- **Spec 20 auditada (2026-08-04) — 3 achados da auditoria da Spec 21 resolvidos:**
  (a) boot com 2 beans `Duration` → `@Qualifier` explícito (L10); (b) login de
  lojista não-ACTIVE agora retorna **403 `merchant_not_active`** (contrato da
  Spec 20) via nova `MerchantLoginDeniedException` — saques/cobranças mantêm 409
  (`MerchantNotActiveException`); (c) roadmap `verified` → `audited` (L9). A
  auditoria da Spec 20 ainda achou e corrigiu: **`last_used_at` ausente** na
  credencial (critério 1 — "registra o último uso para auditoria") — migration
  **V14** + `MerchantCredential.recordUse()` no login; `global-setup.js` ganhou
  `merchant_credentials`/`merchant_sessions` no TRUNCATE (L6).

## Legenda

- `verified` = documentos da spec existem (requirements.md), aguardando `/spec-verify`.
- `audited` = spec concluída e recurso em produção (fim do ciclo SDD).

---

## Specs 55–57 implementadas e auditadas (2026-08-07) — observabilidade de produção

- **Spec 55 (`audited`, 2026-08-07):** health sem falso-negativo. `management.health.mail.enabled: ${NYX_MAIL_HEALTH_ENABLED:false}` no `application.yml` (mesma mecânica do `management.health.redis.enabled` da ADR-010) — o `MailHealthIndicator` (Spec 45, auto-config do starter-mail) deixou de derrubar o health agregado com 503 quando o SMTP não está configurado. **Verificação ao vivo (produção Railway):** `/actuator/health` sem auth → **200 UP** (antes 503 DOWN); com admin → componentes `db`/`diskSpace`/`ping`/`livenessState`/`readinessState` UP, **sem `mail`**; liveness/readiness **200**. `mvn test` **1078/0/0** sem regressão. Fim do ciclo SDD.
- **Spec 56 (`audited`, 2026-08-07):** compression de resposta. `server.compression.enabled: true` + mime-types JSON/XML/texto + `min-response-size: 1024` no `application.yml` (compressão nativa do Tomcat, sem dependência nova). **Verificação ao vivo:** `/actuator/prometheus` (text/plain, >1KB) com `Accept-Encoding: gzip` → **`content-encoding: gzip` + `vary: accept-encoding`**; `/api/v1/admin/merchants` (JSON 7.444 bytes) → **gzip**; resposta <1KB e cliente sem `Accept-Encoding` → sem compressão (comportamento correto). `mvn test` **1078/0/0** sem regressão. Fim do ciclo SDD.
- **Spec 57 (`audited`, 2026-08-07):** tracing distribuído. `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` no `pom.xml`; `management.tracing.enabled: true` com sampling `NYX_TRACING_SAMPLING_PROBABILITY` (default 0.1) e `management.otlp.tracing.endpoint` via `NYX_OTLP_ENDPOINT` (**default `http://localhost:4318/v1/traces`** — achado de implementação: o exporter OTLP **rejeita endpoint vazio no boot** `Invalid endpoint, must start with http:// or https://`; o default local garante **fail-open** — sem collector a falha é assíncrona em background com retry, nunca derruba nem bloqueia); `traceId=%X{traceId:-} spanId=%X{spanId:-}` no pattern estruturado do prod (`logback-spring.xml`). **Verificação ao vivo (produção Railway):** logs estruturados com **35 linhas contendo `traceId=<32 hex> spanId=<16 hex>`** (amostradas); health 200 UP sem collector; `mvn test` **1078/0/0** sem regressão (tracing não altera contrato). Runbook `deploy/railway.md` documenta `NYX_OTLP_ENDPOINT`/`NYX_TRACING_SAMPLING_PROBABILITY`/`NYX_MAIL_HEALTH_ENABLED`. Fim do ciclo SDD.

## Specs 59 e 60 implementadas (2026-08-08) — webhook flood + infra hardening

Implementação das pendências CRÍTICO/ALTAS da análise de resiliência (2026-08-07).
Status `verified` → `implemented` (verificação local completa; benchmark de
calibração no Railway pendente — tasks abertas nos task.md).

- **Spec 59 (`implemented`, 2026-08-08):** webhook flood — **rate limit por IP
  em `/webhooks/pix` e `/webhooks/payouts`** (grupo
  `nyxcash.security.rate-limit.webhook.*`, baseline **120/min/IP**, janela 60s,
  configurável) no **TOPO do `WebhookSignatureFilter.doFilterInternal`** — o
  **429 `too_many_requests` corta o flood ANTES de ler o body/computar HMAC**
  (não gasta CPU/DB/threads no flood); chave `path|IP` com `clientIp` +
  trust-proxy (mesmo padrão do `RateLimitFilter`, reusa o `RateLimitStore` da
  Spec 43 — com `store=redis` o contador é agregado entre instâncias, critério
  6). **Decisão de implementação (KISS):** a checagem vive no próprio
  `WebhookSignatureFilter` (e não no `RateLimitFilter`) — garante 429-antes-do-
  HMAC independente da ordem de filtros do servlet, sem dupla contagem no
  bucket. **Throttle do WARN de assinatura inválida**: `AtomicReference<Instant>`
  + `Clock`, no máximo **1 log a cada `nyxcash.psp.webhook.log-throttle-seconds`**
  (default 10s) mesmo em flood de 401 (o 401 permanece inalterado — só o log é
  cortado). **Verificação:** `mvn test` **1101/0/0** (15 testes no
  `WebhookSignatureFilterTest`, +7 novos: flood mesmo IP → 429 mesmo com
  assinatura VÁLIDA; 429 ANTES do HMAC — bucket esgotado → 429 sem assinatura;
  bucket por IP e por rota; desligável; JSON `too_many_requests`; throttle 10
  inválidos → 1 linha + avanço de intervalo → 2ª; 401 mantido) e E2E **104/104**
  (6.0m, sem regressão — psp-flow com ~6 webhooks, bem abaixo do teto). **Smoke
  dev ao vivo** (`NYXCASH_PSP_WEBHOOK_VERIFY_SIGNATURE=true`, teto reduzido
  5/min): **8 webhooks → 5×204 + 3×429**; bucket esgotado → 429 mesmo sem
  assinatura. **Benchmark de calibração no Railway (task 8, 2026-08-08):**
  flood k6 **300 webhooks → 120×401 + 180×429** — split EXATO do teto
  **120/min/IP** com contador **agregado via Redis** (re-rodado 2×, resultado
  idêntico), **zero 5xx e zero erro de conexão** em todos os runs, p95 dos
  aceitos **522ms** (warm; 1º run 1.75s = JIT/cold); o **429 corta ANTES de
  ler o body/computar HMAC** — teto confirmado SEM bloquear reentrega legítima
  do PSP (reentrega em minutos). Integridade sob flood: **zero OOM**, health
  200 durante todo o teste, sem restart loop. **Deploy Railway + verificação
  ao vivo (task 9, 2026-08-08, junto com Spec 60):** deploy SUCCESS com 2
  réplicas, health liveness/readiness 200, `[2/2] Healthcheck succeeded!`.
  Fim do ciclo SDD.
- **Spec 60 (`implemented`, 2026-08-08):** hardening de infraestrutura —
  **Tomcat explícito** no `application.yml` (`server.tomcat`: max-connections
  **1000** — default 8192, anti-DoS de sockets; accept-count 200;
  connection-timeout 10000ms — descarta slow-connectors; keep-alive-timeout
  30000ms; max-keep-alive-requests 100) **com env override**
  (`NYX_TOMCAT_*`, sem rebuild para calibrar). **Hikari**:
  `maximum-pool-size: ${NYX_DB_POOL_SIZE:20}` (10 → 20; query ~5ms → ~4000
  req/s de DB, coerente com threads e Postgres Railway). **Heap JVM**:
  `Dockerfile` passa a `ENTRYPOINT ["sh","-c","java ${JAVA_OPTS:--XX:MaxRAMPercentage=75} -jar app.jar"]`
  — teto via env sem rebuild (default seguro preserva o comportamento);
  runbook `deploy/railway.md` documenta **`JAVA_OPTS=-Xmx320m`**
  (instância ~512MB) + tabela de tuning + alertas Prometheus (Hikari
  exhaustion, `jvm.memory.used`, 500s, conexões ativas). **Verificação:** `mvn
  test` **1101/0/0** e E2E **104/104** sem regressão (config de runtime não
  muda contrato de API); smoke dev: app sobe com os novos limites (health 200,
  Tomcat initialized). **Benchmark de calibração no Railway (tasks 6/7,
  2026-08-08, k6 ramp 50→100→500→1000→2000→5000 VUs — burst por run com
  lojista fresco, `rate-limit-calibration.js`):** 50 e 100 VUs → **100% 200**
  (p95 686ms warm); 500 VUs → 120×200 + 380×429 (teto do principal 120/min),
  p95 aceitos **1.53s = ponto de inflexão**; 1000 VUs → +58 erros de conexão;
  2000 VUs → +1055 (53%); 5000 VUs → 120×200 + 815×429 + **4065 erros de
  conexão (81%, iterações ~21s = fila de conexão do edge/accept saturada)**.
  **Zero 5xx em TODOS os runs** (o 429 corta antes da saturação da app —
  degradação controlada confirmada). **Pool Hikari 20/réplica: zero timeouts**
  (7990 aquisições, acquire máx 1.41s). **Zero OOM**, zero restart loop,
  health 200 durante todos os testes. **Valores finais (task 8):**
  `JAVA_OPTS=-Xmx320m` (heap observado ~100MB —
  folga), `NYX_DB_POOL_SIZE=20` por réplica (40 total < max_connections do
  Postgres), Tomcat `max-connections=1000`/`accept-count=200` mantidos (teto
  de conexões identificado: a camada de edge/accept satura a partir de ~1000
  concorrentes — manter para degradação controlada). Fim do ciclo SDD.

## Spec 61 implementada (2026-08-08) — escalabilidade horizontal e alta disponibilidade

Status `verified` → `implemented` (verificação local completa; benchmark/
deploy no Railway pendente — tasks 9/10 abertas nos task.md).

- **Spec 61 (`implemented`, 2026-08-08):** app stateless p/ **N instâncias** +
  deploy Railway multi-instância. **App:** `server.shutdown: graceful` +
  `spring.lifecycle.timeout-per-shutdown-phase: ${NYX_SHUTDOWN_TIMEOUT:30s}`
  (dreno de requisições no rollout, complementa o
  `waitForTasksToCompleteOnShutdown` do scheduler — Spec 53); rate limit
  distribuído pronto via `NYX_RATE_LIMIT_STORE=redis` (Spec 43/ADR-010 —
  obrigatório em multi-instância, fail-open); **auditoria de estado em
  memória** (evidência no design.md): sem `HttpSession`/`@Cacheable`/`static`
  mutável; únicos estados locais: `InMemoryRateLimitStore` (→ `redis`),
  throttle de log de webhook (Spec 59 — por instância, aceitável) e o
  `MockPixProviderClient` (dev/testes apenas). **Deploy:** `railway.json` com
  **`numReplicas: 2`** (baseline; chave validada contra o schema oficial do
  Railway — `replicas` não existe no schema, fix 23d7b70) +
  **`healthcheckPath: /actuator/health/readiness`**
  (sem auth — `/actuator/health/**` é `permitAll`; a instância só entra no
  balanço quando pronta) — rollout sem downtime com `numReplicas > 1`;
  `docker-compose.yml` ganhou `redis` + `api-a`/`api-b` para a verificação
  multi-instância; runbook `deploy/railway.md` com seção **HA** (env vars
  obrigatórias, pool de DB por réplica, rollout/rollback, verificação
  pós-deploy). **Verificação multi-instância (2 instâncias JVM locais, mesmo
  Postgres — Docker daemon indisponível no ambiente, WSL sem distros):** 5a
  sessão cross-instância (token criado na A → **200** na B; controle sem token
  → 401); 5b scheduler 1× (`job_runs`: **1 execução/ciclo de 30s, 0 overlaps**,
  lock distribuído A/B — ShedLock no Postgres); 5d idempotência
  cross-instância (cobrança com chave K na A → 201; replay na B → **mesma
  transação**, DB com **1 PENDING** — sem duplicidade); 5e failover (A
  derrubada → **B 200** health + auth 200). **`mvn test` 1101/0/0** e **E2E
  104/104 (5.8m)** sem regressão. **Deploy Railway + verificação ao vivo (task
  9, 2026-08-08):** deploy SUCCESS com **2/2 réplicas** e healthcheck `[2/2]
  Healthcheck succeeded!` (achado de implementação: o healthcheck do Railway
  sonda via HTTP puro e o `requiresChannel(anyRequest().requiresSecure())`
  respondia 302 quebrando o deploy — corrigido eximindo `/actuator/health/**`
  com `ChannelDecisionManagerImpl.ANY_CHANNEL`, fix 744c1f0); **rollout sem
  downtime** (3 redeploys sob tráfego: 516 req contínuas + burst de 30
  concorrentes ~1800 req — **zero falhas**); **dreno do graceful shutdown**
  confirmado na prática (requisições in-flight completam durante o rollout; os
  logs "Commencing graceful shutdown" não são capturados pelo Railway para
  deploys drenados — limitação de captura, config `server.shutdown: graceful`
  validada); **rate limit agregado via Redis (5c)**: flood webhook 300 →
  120×401 + 180×429 (contador COMPARTILHADO entre réplicas; achado: Reference
  do Railway exige sintaxe `${{...}}` — `${Redis.REDIS_PASSWORD}` não resolvia
  e o store caía em fail-open; corrigido via env); **valores finais (task
  10):** `numReplicas: 2`, `NYX_SHUTDOWN_TIMEOUT=30s` (default),
  `NYX_DB_POOL_SIZE=20` por réplica (calibrado no benchmark da Spec 60),
  `JAVA_OPTS=-Xmx320m`. Fim do ciclo SDD.

## Auditoria das Specs 59 e 60 (2026-08-08) — 0 issues

Auditoria pós-implementação + pós-benchmark/pós-deploy Railway. Status
`implemented` → `audited` (fim do ciclo SDD).

- **Spec 59 (`audited`, 2026-08-08):** todos os 10 critérios de aceite
  verificados. Ao vivo (produção Railway): flood 300 webhooks → **120×401 +
  180×429** (429 antes do HMAC, teto 120/min/IP **agregado via Redis** — Spec
  61/5c; por instância seria ~60×429), **zero 5xx/conn-error**, p95 aceitos
  **522ms** (warm); 401 inalterado + WARN throttled (testes 15); 204 abaixo do
  teto (smoke dev + E2E 104/104); integridade sob flood: **zero OOM**, sem
  restart loop; sem perda/duplicidade e outbox íntegro cobertos pelas Specs
  26/27/28/30/35/36/53 (locks + idempotência, E2E sem regressão).
- **Spec 60 (`audited`, 2026-08-08):** todos os 10 critérios de aceite
  verificados. Benchmark k6 50→5000 VUs na infra real: **zero 5xx em todos os
  runs** (degradação controlada — 429 antes da saturação), ponto de inflexão
  p95 aceitos **~500 VUs (1.53s)**, gargalo de conexão a partir de **~1000
  concorrentes** (fila de edge/accept; erros de conexão 6%→53%→81% em
  1000/2000/5000 VUs — comportamento calibrado, não prematuro); pool Hikari
  **20/réplica zero timeouts** (7990 aquisições, acquire máx 1.41s);
  `jvm.memory.used` ~100MB com folga do teto; **zero OOM**, zero restart loop;
  slow-connectors/keep-alive configurados (connection-timeout 10s, keep-alive
  30s); ledger/outbox/idempotência cobertos pelas Specs 26/28/30/35/36/53 (E2E
  104/104 sem regressão). Valores finais aplicados no Railway:
  `JAVA_OPTS=-Xmx320m`, `NYX_DB_POOL_SIZE=20`. **Achado de auditoria
  (corrigido):** o `JAVA_OPTS=-Xmx320m -XX:MaxMetaspaceSize=96m` documentado
  originalmente no runbook causa **`OutOfMemoryError: Metaspace`** no boot
  (Spring Boot/Hibernate/Lettuce precisam de mais metaspace) — validado em
  deploy ao vivo (deploy 50cc6f7d FAILED, healthcheck nunca saudável);
  corrigido para `JAVA_OPTS=-Xmx320m` (sem cap de metaspace) e re-verificado
  (deploy 646d671d SUCCESS, Tenured max 223MB, usado 62MB, zero OOM).
  Instância Railway observada ~1GB (não ~512MB como assumido).

## Revisão de Segurança transversal (2026-08-11) — 29 achados corrigidos

Auditoria de segurança exigindo raciocínio sobre **vetores sutis, race
conditions e timing attacks** em todos os subsistemas (documento completo:
[docs/revisao-seguranca-2026-08-11.md](../docs/revisao-seguranca-2026-08-11.md)).
**Rodada 1** (10 achados): SEC-01 lost update no lockout (master/lojista/TOTP —
persisters FOR UPDATE REQUIRES_NEW), SEC-02 bypass de rate limit por
cardinalidade de `{id}` no path (`RateLimitKey`), SEC-04 oráculo de timing no
lockout (BCrypt antes do check), SEC-05 bypass de escopo de API key por `..`
(`cleanPath`), SEC-09 amplificação de memória no logging de resposta (wrapper só
com DEBUG), SEC-10 TOCTOU no single-use do token de reset (`consumeIfOpen`
atômico), SEC-11 lost update no incremento de attempts do outbox (UPDATE
atômico), SEC-12 I/O do PSP dentro de transação (reconcile sem `@Transactional`),
SEC-13 IDOR latente no create charge (resolver vs merchantId do comando → 403),
SEC-14 lost update na fatura sob coleta concorrente (re-leitura fresca pós-lock).
**Rodada 2** (4 achados — auditoria da rodada 1, que os testes verdes não
cobriam): SEC-15 race sucesso × lockout (login válido salva snapshot stale por
cima do lockout — re-leitura FOR UPDATE no sucesso), SEC-16 bypass de rate
limit por path traversal `..` (cleanPath nos rate limit filters — SEC-05 era só
no escopo), SEC-04 incompleto (short-circuit `||` de conta inativa pulava o
BCrypt — oráculo de ativação), SEC-13 estendido (mesmo IDOR em
`CreatePixTransactionService`/`WithdrawalService`).
**Rodada 3** (2 achados — áreas não cobertas): SEC-17 (**CRÍTICO**) bypass da
verificação HMAC do webhook por path traversal — `WebhookSignatureFilter`
comparava o path cru com `/webhooks/pix`; `/webhooks/../webhooks/pix`
(normalizado pelo container) pularia a verificação de assinatura e permitiria
crédito de PAID falso (cleanPath em todas as decisões do filtro), SEC-18 body
inspection inspecionando webhook excluído (path cru no casamento de exclusão —
falso positivo de SQLi em reentrega legítima; cleanPath).
**Rodada 4** (1 achado — fluxos de administração): SEC-19 (**ALTO**) save de
merchant sem lock sobrescreve movimentação — `MasterWithdrawalControlService`
(block/release), `MerchantStatusControlService` (suspend/release) e
`RiskMonitoringService` (auto-suspend) carregavam o merchant com `findById` sem
FOR UPDATE e salvavam; o save JPA grava todos os campos (sem `@DynamicUpdate`),
incluindo `wallet_balance` — um débito/crédito concorrente
(saque/webhook/chargeback) era sobrescrito pela snapshot stale (correção:
`findByIdForUpdate` antes de salvar; auto-suspend relê sob lock apenas quando
vai suspender).
**Rodada 5** (4 achados — MFA/recovery codes, saída de webhooks e rate limit
por principal): SEC-20 (**ALTO**) TOCTOU no single-use do recovery code do MFA
(leitura + saveAll — dois logins concorrentes com o MESMO código passavam;
consume ATÔMICO `consumeIfOpen` com UPDATE condicional, padrão do SEC-10),
SEC-21 (**ALTO**) SSRF por DNS rebinding na entrega de webhooks de saída
(`WebhookUrlValidator` resolvia e o `java.net.http` re-resolvia na conexão —
cliente trocado para Apache HttpClient 5 com `WebhookDnsResolver` PINADO e
redirects desabilitados), SEC-22 (**ALTO**) estorno de falha síncrona do saque
sem lock (`settleFailedByWithdrawalId` com `findById` — webhook PAID paralelo
podia ser sobrescrito por FAILED+estorno; `findByIdForUpdate`), SEC-23
(**MÉDIO**) bypass do rate limit por cardinalidade nas rotas de RECURSOS
(`/merchants/{id}`, `/portal/plans/{id}`, `/portal/webhooks/{id}`, ... sem
colapso — lojista com N recursos ganhava N × teto do limite por principal;
prefixos adicionados ao `RateLimitKey`).
**Rodada 6** (3 achados — ciclo de vida de credenciais, cripto/entropia e
fluxos de cobrança/chargeback): SEC-24 (**ALTO**) revogação de API key não
revoga a credencial legada — `merchants.api_key_hash` (fallback do
`ApiKeyAuthFilter`, escopo total implícito) nunca era limpo no `revoke`: se a
linha gerenciável fosse removida, a chave "revogada" ressuscitava com escopo
total (correção: `clearLegacyApiKeyHashIfMatches` — UPDATE condicional no
revoke), SEC-25 (**ALTO**) ciclo de vida da credencial do master quebrado —
senha gerada com 64 bits, exibida UMA vez e **imutável** (sem troca/reset/sem
revogação de sessões): gerador em 128 bits + `POST
/api/v1/auth/master/change-password` (senha atual via BCrypt com timing
uniforme + `revokeAllByMasterId` — tokens roubados morrem na rotação), SEC-26
(**ALTO**) TOCTOU no encerramento da PENDING do charge —
`failPending`/`cancelPending` salvavam a snapshot stale por cima de um
APPROVED concorrente (webhook PAID pós-erro do PSP marcava venda paga como
FAILED; correção: re-leitura `findByIdForUpdate` + gate `PENDING` sem
`external_reference` — padrão do SEC-22 no lado da cobrança).
**Rodada 7** (3 achados — lógica de negócio e fronteiras entre subsistemas):
os quatro vetores pedidos foram verificados e considerados OK (IDOR entre
lojistas — escopo em todos os services; plano sem pagamento — entrega só no
webhook PAID; entrega 2× e estorno 2× — gates de status sob lock); achados nos
fluxos ADJACENTES: SEC-27 (**MÉDIO**) note da conclusão de entrega sem `@Size`
(DataException/500 — coluna VARCHAR(255); `@Size(max=255)`), SEC-28 (**MÉDIO**)
motivo de falha do saque sem teto quebra o estorno (`failure_reason`
VARCHAR(255) recebia `"Falha no PSP: " + e.getMessage()` — DataException dentro
do estorno deixava o saque PROCESSING com débito pendurado; truncamento no
domínio `Withdrawal.fail`), SEC-29 (**MÉDIO-ALTO**) estorno do saque
administrativo não revertia o `ADMIN_RECOVERY` do ledger do master (receita
fantasma na Spec 34; novo tipo `ADMIN_RECOVERY_REVERSAL` gravado no FAILED de
saques administrativos + summary subtrai). **Validação:** `mvn -o clean test`
**1252/0/0 BUILD SUCCESS** (1243 → 1252, +9 testes novos — SEC-27/28/29).
**Commits:** `35050a0` (núcleo de autenticação, 26 arquivos) + `ddf6905` (edge
e serviços transacionais, 20 arquivos) + `6e72a46` (rodada 3 SEC-17/18, 4
arquivos) + `027462d` (rodada 4 SEC-19, 6 arquivos) + `3357c8f` (rodada 5
SEC-20/22, 10 arquivos) + `a49a7dd` (rodada 5 SEC-23, 2 arquivos) + `bd0083c`
(rodada 5 SEC-21, 5 arquivos) + rodada 6: `0cdd168` (SEC-24, 5 arquivos) +
`cbca457` (SEC-25, 14 arquivos) + `35c86a9` (SEC-26, 3 arquivos) + rodada 7:
`df36c2d` (SEC-27, 2 arquivos) + `1fee6b4` (SEC-28/29, 7 arquivos) — divisão por
coesão temática (arquivos com as
duas rodadas num hunk único não são separáveis sem quebrar compilação).
**Rodada 8** (verificação dos canais de interação — **sem achados de código**):
outbox de SAÍDA (publicação na transação, at-least-once com dedup, retry/
backoff, OUTBOX_DEAD com anti-cascata, ShedLock+watchdog, HMAC por webhook,
SSRF fechado SEC-21), webhook de payouts (HMAC próprio + idempotente), bootstrap
(seed via env, Turnstile fail-open ADR-010, admin key fail-fast), 7 jobs com
ShedLock, frontend (token em sessionStorage + CSP), sanitização de logs,
token de reset via SMTP com fail-fast contra log. **Veredito de produção:**
código pronto (1252/0/0 + E2E 105/105 + load 600/s p95 ~120ms); 4 gaps de
CONFIGURAÇÃO por env documentados (Turnstile OFF default, rate limit
store=memory em multi-instância, SMTP sem fail-fast, master seed sem
fail-fast) — [docs §9](docs/revisao-seguranca-2026-08-11.md).

**Fix de flakiness cross-spec no E2E step-up + verificação (2026-08-13):** o
`master-console-stepup.spec.js` (Spec 72) falhava só na suíte completa/combo auth
— o `beforeAll` não limpava os masters deixados por specs anteriores (ex.:
`auth.spec` ativa MFA) → teste 4 estourava o limite de 4 perfis (Spec 41) com
`409 too_many_masters`; round-trip step-up + repetição da ação excedia os 5s
default. Correção **somente em testes** (sem mudança de backend): limpeza de
`master_mfa_methods`/`master_sessions`/`master_audit_log`/`masters` no
`beforeAll` + timeout 15s no teste 4 + ajustes correlatos no `merchant-journey`
(cenário 18 com baseline de `losses` — DELTA; cenário 22 com `DELETE FROM
master_mfa_methods` antes de `masters` — FK do step-up/MFA). **Verificação:
suíte E2E completa 122 testes, 0 falhas de step-up** (Stepup 4 CORRIGIDO);
única falha do run foi o journey teste 23 (Spec 48 — race de `updatedAt`,
flake pré-existente documentado na auditoria da Spec 71), re-executado isolado
**27/27** e recorrência registrada no follow-up da Spec 48 (detalhe no task.md
da Spec 72 §Follow-up).

## Fase 32 — Fallback automático de adquirente + resiliência de saldo no PSP (decisão do usuário 2026-08-14)

Análise de operação de alta performance (2026-08-14): hoje o PSP é selecionado
**estaticamente no boot** (`nyxcash.psp.provider=mock|pagarme|asaas` — UM adapter
via `@ConditionalOnProperty`); se o adquirente cair, a cobrança falha (502/
PENDING, idempotência Spec 28) sem tentar outro. A decisão do usuário é uma
arquitetura **preventiva** (não reativa) em 3 pilares: **(1) Circuit Breaker
preventivo** — monitora a saúde do adquirente (janela de falhas/timeouts +
input do chargeback da Spec 16/19) e desvia volume antes do gateway virar
risco; **(2) Sweep Zero-Balance** — job que esvazia o saldo "Disponível" do PSP
para a conta blindada da empresa, retendo só um buffer de saques; **(3)
Split/sub-contas** — **adiado** para spec futura (Spec 93) por decisão do
usuário (Conta Master hoje).

**Refinamentos do usuário (2026-08-14) incorporados:**
- **Anti-dupla-cobrança**: no fallback, erro incerto (timeout/5xx) do adquirente
  A → o router **consulta A via `getChargeByCode`** (novo método no `PixProviderClient`)
  antes de tentar B — se A criou a cobrança (QR), usa A; só cai para B se A
  confirmar que **não** criou. `PixProviderClient` ganha `getChargeByCode` (hoje só
  existe `createCharge`).
- **Asaas real na Spec 91** (não plug-and-play): adapter real
  `AsaasPixProviderClient` + harness **`PixProviderContractTest`** herdado pelos
  DOIS adapters (Pagar.me + Asaas) — força interface verdadeiramente agnóstica
  e habilita smart routing por custo no futuro.
- **Isolamento de credenciais no sweep**: o `JobExecutionService` do sweep usa
  **somente** a credencial payout-scoped (`nyxcash.psp.payout-*.secret-key`,
  bloco já existente no `PspProperties`) — **nunca** a chave de cobrança; se a
  chave de cobrança vazar, o ofensor não move o saldo master. IP whitelisting
  no adquirente (IP de egresso do Railway) + **fail-fast no boot** se
  charge-secret == payout-secret em prod.

| # | Name | Status | References |
|---|---|---|---|
| 91 | Fallback automático de adquirente (cobrança Pix) — `PixProviderRouter` com prioridade configurável (`nyxcash.psp.routing.*`, config/env, sem UI): em erro incerto do primário (**timeout/5xx**) → **poll `getChargeByCode` no primário** (anti-dupla-cobrança: só cai para o secundário se A confirmar que não criou; refino do usuário 2026-08-14); **circuit breaker preventivo** por janela de falhas/timeouts + input de chargeback (Spec 16/19) → desvia volume antes do risco; **política de roteamento por risco do lojista** (`RoutingPolicyResolver` — `merchant_risk.risk_score` da Spec 19 → lista de prioridade `routing.policies.*`: `default` vs `high-risk`, isolamento por nicho, plug-and-play p/ novas policies; decisão do usuário 2026-08-14); **adapter real `AsaasPixProviderClient`** (refino do usuário — Asaas já na 91, não plug-and-play) + harness **`PixProviderContractTest`** herdado pelos 2 adapters (Pagar.me + Asaas); **`PixProviderClient.getChargeByCode`** novo no port; **webhook `/webhooks/pix` por adquirente** (valida o segredo de cada um configurado + coluna `provider` em `pix_transactions` p/ rotear reconciliação); métricas `nyx_psp_*`/`nyx_psp_route_total{provider,policy}` por adquirente + **alerta ao operador** `PROVIDER_CIRCUIT_OPEN` (padrão Spec 64); idempotência Spec 28 preservada (mesmo `code`/`idempotencyKey`; `external_reference` com namespace por adquirente) | audited | [requirements](specs/91-fallback-adquirente/requirements.md) · [design](specs/91-fallback-adquirente/design.md) · [task](specs/91-fallback-adquirente/task.md) · Visão V68 · ADR-017 |
| 92 | Resiliência de saldo no PSP — **job de sweep** (ShedLock + `JobExecutionService` Spec 53, cron diário configurável) que transfere o saldo **Disponível** do PSP para a conta blindada da empresa, **retendo buffer** configurável (`nyxcash.psp.sweep.*`) para os saques dos lojistas (exposição máxima a um freeze = o que entrou desde o último sweep + buffer); **credencial isolada** para o sweep (refino do usuário: usa **só** `nyxcash.psp.payout-*.secret-key`, nunca a chave de cobrança; IP whitelist no adquirente + fail-fast se iguais em prod); **payout fallback** na solicitação do saque (timeout/5xx de A → tenta B; **freeze não é roteável** — a defesa é o sweep); métricas `nyx_psp_balance_*` (exposição, última varredura) + alerta de exposição alta/freeze | audited | [requirements](specs/92-resiliencia-saldo-psp/requirements.md) · [design](specs/92-resiliencia-saldo-psp/design.md) · [task](specs/92-resiliencia-saldo-psp/task.md) · Visão V69 · ADR-017 |
| 94 | MFA TOTP no portal do lojista — **autenticação em 2 fatores (TOTP, RFC 6238)** fechando a lacuna #1 da auditoria de segurança (2026-08-14): o master já tem MFA (Spec 44), o lojista não. **Espelho do padrão master**: mesma lib `Totp`, mesma criptografia em repouso (`CryptoConverter` AES-256/GCM), mesmo lockout de TOTP (5 falhas → 15min, expira sozinho), mesmos recovery codes (`mrc_<32hex>`, SHA-256 single-use, consume atômico SEC-20) e mesmos eventos de auditoria `MFA_*` (Spec 41, actor `MERCHANT`). Login **single-step** (request aceita `totpCode` opcional; MFA ativo com código ausente/inválido → **401 uniforme**, sem oráculo). Portal ganha campo TOTP no login (sempre visível) + página **Segurança** (ativar em 2 passos / desativar com posse: senha + TOTP/recovery / regenerar recovery codes). Migration **V59** (`merchant_mfa_methods` / `merchant_totp` / `merchant_recovery_codes` — CASCADE nativo, lição V42). `GET /portal/me` ganha `mfaActive` (aditivo). E2E `merchant-mfa.spec.js`. **Sem step-up** (fora de escopo) e **sem dependência externa** (sem Clerk — stack Java, decisão da auditoria). **Número 94: o roadmap reserva "Spec 93" para Split/sub-contas (adiado — decisão 2026-08-14)** | audited | [requirements](specs/94-mfa-merchant-portal/requirements.md) · [design](specs/94-mfa-merchant-portal/design.md) · [task](specs/94-mfa-merchant-portal/task.md) · Visão V70 |

**`/spec-plan` (2026-08-14): Specs 91–92 `planned`** — proposta apresentada ao
usuário após análise de operação de alta performance: (1) o PSP hoje é seleção
estática no boot (ADR-005: Pagar.me primário, Asaas alternativa — sem fallback
em runtime); (2) a spec 91 entrega o `PixProviderRouter` (fallback na criação +
poll anti-dupla-cobrança + circuit breaker preventivo + Asaas real + webhook
por adquirente); (3) a spec 92 entrega o sweep zero-balance com buffer +
credencial isolada + payout fallback; (4) **Split/sub-contas adiado** (Spec 93
futura — Conta Master hoje, decisão do usuário). Visões V68–V69, ADR-017.

**`/spec-new 91` e `/spec-new 92` (2026-08-14): Specs 91–92 `created`** —
requirements/design/task criados em `management/specs/91-fallback-adquirente/` e
`management/specs/92-resiliencia-saldo-psp/`, incorporando os 3 refinamentos do
usuário: (1) **anti-dupla-cobrança** — `PixProviderClient.getChargeByCode` + poll no
router antes do fallback em erro incerto; (2) **Asaas real na 91** —
`AsaasPixProviderClient` + harness `PixProviderContractTest` (força a interface
agnóstica); (3) **credencial isolada no sweep** — payout-scoped key exclusiva,
fail-fast se == chave de cobrança em prod, IP whitelist no runbook. Migration
V58 (coluna `provider` em `pix_transactions`). **Ajuste pós-new (2026-08-14):
política de roteamento por risco do lojista adicionada à 91** —
`RoutingPolicyResolver` (`merchant_risk.risk_score` da Spec 19 → lista de prioridade
`routing.policies.*`: `default` vs `high-risk`, isolamento por nicho,
plug-and-play p/ novas policies; critério 11/D4.1/T2.5) — fecha o pitch de
"Smart Routing por risco calculado". **`/spec-verify 91` (2026-08-14): Spec 91 `verified`** — 2 achados de precisão corrigidos nos docs (sem mudança de escopo): (1) coluna do score é **`risk_score`** (não `score`) — MerchantRiskJpaEntity; (2) `getCharge` → **`getChargeByCode(code)`** — no timeout o `external_reference` é desconhecido, a busca é pelo `code` (= transactionId); Pagar.me `GET /charges?code=`, Asaas `?externalReference=`, Mock por cache de transactionId. Âncoras validadas (portas, adapters, harness `PayoutProviderContractTest`, `RiskRepository.latestByMerchant`, `WebhookSignatureFilter` 1 segredo/rota, injetores do port, V58 livre — detalhe no task.md §Verificação). **`/spec-verify 92` (2026-08-14): Spec 92 `verified`** — 2 achados de precisão corrigidos (sem mudança de escopo): (1) idempotência do sweep por **UUID determinístico por dia** `UUID.nameUUIDFromBytes("sweep:"+LocalDate)` (o `PayoutCommand.withdrawalId` é UUID e o code do PSP é derivado dele — Pagar.me code=withdrawal.id, Spec 47); (2) **roteamento pós-fallback** da reconciliação/webhook pela coluna `withdrawals.payout_provider` (já existe — V28). Sem migration nova. **Ordem de implementação corrigida (decisão do usuário 2026-08-14): 92 → 91** — a Spec 92 é **auto-contida** (sweep protege o caixa já, baixo risco, estabelece a credencial payout-scoped; fallback de saque simples por prioridade, sem o registry da 91) e o racional original "91 raiz" estava **invertido** (é a 92 que opcionalmente reusaria o registry da 91, não o contrário). Próximo: `/spec-implement 92`, depois `/spec-implement 91`.

**`/spec-implement 92` (2026-08-14): Spec 92 `implemented`** — F0–F4 entregues
conforme o task.md: porta `PspBalanceProvider` + adapters Mock/Pagar.me/Asaas
(harness `PspBalanceContractTest`); `PspSweepScheduler`/`PspSweepService` (UUID
determinístico por dia `UUID.nameUUIDFromBytes("sweep:"+LocalDate)` + buffer +
skip se ≤ 0); `SweepAlertPublisher` (`SWEEP_EXPOSURE_HIGH`/`SWEEP_FAILED`);
fail-fast de credencial isolada em qualquer perfil (`requireDistinctSecrets`);
`PayoutProviderRouter` `@Primary` (timeout/5xx → B; provedor aceito gravado em
`withdrawals.payout_provider`); `SweepProperties` + binding. **`mvn test` verde
(1681/0/0)** + **E2E completo sem regressão (159 testes — psp-sweep 2/2)**.
Achados do E2E: (1) **poll por contagem** no `psp-sweep.spec.js` — o poll por
`started_at > max(...)` era intrinsecamente frágil (o `job_runs.started_at` tem
precisão de microssegundos do `Instant.now()` Java e o JS Date do node-postgres
trunca para ms → o `max` truncado casava com a própria linha e o poll quebrava
na 1ª iteração, falso negativo); agora aguarda `count(*)` AUMENTAR (inteiro,
sem sensibilidade a precisão); (2) **locator específico** no
`master-console-ui.spec.js` — `text=Saldo do wallet` casava 2 elementos (heading
do card + item da lista de métricas, strict mode violation); trocado por
`getByRole('heading', ...)`. Próximo: `/spec-implement 91` e `/spec-audit 92`.

**`/spec-implement 91` (2026-08-14): Spec 91 `implemented`** — F0–F5 entregues
conforme o task.md: `PixProviderClient.getChargeByCode` + `GetChargeResult`
(poll anti-dupla-cobrança — busca pelo code/transactionId, achado da
verificação) + `CreateChargeResult.provider` + `CreateChargeCommand.merchantId`;
harness `PixProviderContractTest` herdado por Mock/Pagar.me/Asaas;
`AsaasPixProviderClient` (API v3, auth access_token, 5xx=incerto/4xx=definitivo);
`PixProviderRouter` @Primary (fallback definitivo → imediato; incerto → poll
até 2 tentativas; poll-achou → retry idempotente Spec 28; circuito/kill-switch/
por-lojista → pula); `RoutingPolicyResolver` (`merchant_risk.risk_score` →
`routing.policies.*`: default vs high-risk, isolamento por nicho);
`PixProviderHealthRegistry` (janela→OPEN→cooldown→HALF-OPEN) + alerta
`PROVIDER_CIRCUIT_OPEN` (outbox REQUIRES_NEW, fail-safe) + métricas
`nyx_psp_route_total{provider,policy,outcome}`; `WebhookSignatureFilter` por
adquirente (HMAC Pagar.me / token Asaas, tempo constante, fail-closed);
migration V58 (`pix_transactions.provider`); `RoutingProperties` + binding;
E2E `psp-fallback.spec.js` (4/4 — fallback 5xx→B, poll-achou→A sem dupla,
webhook do B processa, high-risk→suitpay/default→mock) via endpoint dev
`/internal/psp-mock` (controle de simulação dos mocks, @Profile("dev")). **`mvn
test` verde (1734/0/0)** + **E2E completo sem regressão (163 testes)**.
Desvios registrados no task.md §Implementação (adapters sempre beans — padrão
Spec 92; poll-achou → re-createCharge idempotente; 5xx = incerto nos adapters;
2º mock "suitpay" + endpoint dev de simulação; E2E do circuito coberto nos
unitários — efeito observável indistinguível sem telemetria). Próximo:
`/spec-audit 92` e `/spec-audit 91`.

**`/spec-audit 92` (2026-08-14): Spec 92 `audited`** — C1–C8 verificados contra
o código real/testes/E2E; **1 achado de código corrigido + 1 ajuste de doc**
(detalhe no task.md §Auditoria): (1) `AsaasBalanceProvider` usava
`Authorization: Bearer` enquanto o `AsaasPixProviderClient` usa `access_token` —
inconsistência de auth do mesmo adquirente; alinhado ao `access_token` (teste
atualizado); (2) requirements C4 dizia "fail-fast em prod" — a implementação
roda em QUALQUER perfil (mais forte); requirements.md ajustado. Demais critérios
verificados sem achado (porta/adapters/harness, scheduler+UUID determinístico,
buffer, credencial payout-scoped + runbook, router por prioridade +
`payout_provider` pós-fallback, métricas/alertas, binding, `mvn test` 1734/0 +
E2E 2/2). **Spec DONE (live).**

**`/spec-audit 91` (2026-08-14): Spec 91 `audited`** — C1–C11 verificados contra
o código real/testes/E2E; **1 achado de código corrigido + 1 desvio justificado
documentado** (detalhe no task.md §Auditoria): (1) **C8 — fail-fast de
credencial do adquirente listado no routing**: o `PspProperties.validate()` só
cobria o provider CONFIGURADO; adquirente real listado (`pagarme`/`asaas`) sem
secret-key válida só falhava em RUNTIME (401) — corrigido no `PixProviderRouter`
(`validateSecret` na construção; mocks isentos) + 2 testes; (2) **C5 — desvio
justificado**: `openForMerchant` (circuito por lojista por chargeback) existe e é
testado, mas nenhum chamador em produção o alimenta — a defesa real do lojista
de risco é a auto-suspensão (Spec 19) + o roteamento por policy (C11); wiring é
follow-up. Demais critérios verificados sem achado (getChargeByCode no port +
3 adapters, harness `PixProviderContractTest`, `AsaasPixProviderClient` real,
router com poll anti-dupla-cobrança, registry/alerta `PROVIDER_CIRCUIT_OPEN`,
webhook por adquirente fail-closed, V58 `provider`, binding + fail-fast sem
adapter, idempotência Spec 28 intacta, `mvn test` **1736/0/0** + E2E
`psp-fallback` 4/4). **Spec DONE (live).**

## Auditoria de segurança 2026-08-14/15 — lacunas registradas para execução futura

Após a **Spec 94 (MFA do lojista — `audited`)**, o checklist de segurança da
auditoria está **coberto em 7/10**. A re-verificação concreta (2026-08-15)
confirmou: repositório limpo (sem `.env` commitado, chave privada, token
GitHub/AWS/Slack ou `sk_live_`; únicos `sk_*` são fixtures `sk_test_*` em
testes; `.env` no `.gitignore` L22–23); WAF/Cloudflare + rate limit por IP
cobrem Gobuster/Nmap contra a API. As **3 lacunas restantes foram registradas
como specs futuras** (decisão do usuário 2026-08-15 — organizar e registrar,
não implementar agora):

| # | Name | Status | References |
|---|---|---|---|
| 95 | **Log drain / agregação de logs** — fecha a **Fase C opcional da Spec 64** (observabilidade): Logtail N/A na stack Java/Spring + Railway (decisão da auditoria). O trilho `platform_activity_log` (Spec 41) e os app logs já existem; falta o **log drain do Railway para Axiom/Loki** (config operacional) + runbook de consulta; candidato: job de retenção/expurgo (ShedLock, padrão Spec 53). **Nº livre — 93 reservado (Split), 94 usado (MFA)** | audited | [requirements](specs/95-log-drain/requirements.md) · [design](specs/95-log-drain/design.md) · [task](specs/95-log-drain/task.md) · [Spec 64](specs/64-sec-observability-incident-response/requirements.md) · Visão V71 · ADR-015 · ADR-018 |
| 96 | **Backup automático — instalar/verificar o cron** — o `ops/backup.sh`/`restore.sh`/`backup-restore.md` existem, mas o **agendamento diário 02:30 UTC depende de instalação manual do operador** (cron do host do Railway). Registrado como spec operacional: checklist de instalação/verificação (restore testado) + automação do agendamento + runbook de recuperação | audited | [requirements](specs/96-backup-cron/requirements.md) · [design](specs/96-backup-cron/design.md) · [task](specs/96-backup-cron/task.md) · [backup-restore.md](ops/backup-restore.md) · Visão V72 · ADR-018 |

**Decisão formal da lacuna #9 (rotação de senha 90d):** registrada no
**ADR-018** — **NIST 800-63B desaconselha rotação forçada quando há MFA**; com o
MFA agora no master (Spec 44) e no lojista (Spec 94), a rotação 90d fica
**descartada**; mantém-se `PasswordPolicy` (8–72, maiúsc+minúsc+dígito) + MFA +
alerta de login (Spec 64). Nenhuma spec de código nasce dessa lacuna.

**`/spec-plan` + `/spec-new 95` + `/spec-implement` + `/spec-audit` (2026-08-15):**
**Spec 95 `audited`** — lacuna #7 fechada: (1) runbook **`ops/log-drain.md`**
(onde os logs vivem no Railway, janela de retenção, habilitação do **log drain**
Axiom/Loki free tier com credencial fora do repo, **busca por `X-Request-Id`** e
correlação com `platform_activity_log` + métricas); (2) seção **"7. Log drain
(opcional)"** no `deploy/railway.md`; (3) **job de retenção opt-in**
`PlatformActivityCleanupScheduler` (ShedLock + `JobExecutionService`, padrão
Spec 53) — `PlatformActivityRepository.deleteOlderThan(cutoff)` + JPA
`@Modifying` (índice `ix_platform_activity_created` existente, sem migration);
**default DESLIGADO** (`activity-log-cleanup-enabled=false`) preservando o
contrato append-only da Spec 41; config `activity-log-cleanup-cron` (04:15 UTC)
+ `activity-log.retention-days` (180). Visão V71. `mvn test` verde (novo teste
do scheduler 2/2). A configuração efetiva do drain no Railway permanece passo
operacional documentado (acesso do operador).

**Aditivo pós-auditoria (commit `b9cf789`, 2026-08-15) — appender Axiom
direto:** além do drain da plataforma, o backend ganhou **`AxiomHttpAppender`**
(`infrastructure/logging/` — POST assíncrono, fila 2000 + batch 50,
`LogstashEncoder` com MDC `requestId`/`traceId`/`spanId`, **fail-open**: sem
token/url a app sobe desligada; Axiom fora/fila cheia → descarta com WARN via
StatusManager, nunca realimenta o próprio appender). Habilitado no profile
`prod` (`logback-spring.xml` — url dataset `TrevoPay`, token `${AXIOM_API_TOKEN:-}`,
**env var no Railway, nunca no repo**). `pom.xml` + dependência
`net.logstash.logback:logstash-logback-encoder`. Teste `AxiomHttpAppenderTest`
(lote JSON com Bearer+MDC via MockWebServer; fail-open sem token). `mvn test`
verde. `ops/log-drain.md` §2 agora documenta as **2 opções** (drain da
plataforma × appender direto — escolher uma para não duplicar eventos).

**`/spec-plan` + `/spec-new 96` + `/spec-implement` + `/spec-audit` (2026-08-15):**
**Spec 96 `audited`** — lacuna #10 fechada (spec 100% operacional, sem código
Java): (1) **`ops/install-backup-cron.sh`** — instalador/verificador do cron
com modos `install` (idempotente — `grep -F` da linha `30 2 * * *` do
`backup.sh`; preserva entradas; sem `crontab` → instruções WSL/Task Scheduler e
exit 1), `check` (cron presente + `backup.sh` executável + **frescura do dump <
48h** + dir legível; exit 0/1 com diagnóstico), `uninstall` (idempotente);
(2) **`ops/logrotate-nyxcash-backup.conf`** — rotativo diário do
`/var/log/nyxcash-backup.log` (60 dias — fecha a promessa do runbook);
(3) **`ops/backup-restore.md`** — usa o instalador + **checklist de
verificação** (semanal: `check`; mensal: teste de restore em banco
descartável; pós-falha: restaurar o mais recente). Visão V72. **Validado:**
`bash -n` limpo; `install`/`check`/`uninstall` testados com stub de `crontab`
(idempotência + remoção) e com backup real do dev DB (`check` → exit 0, dump
fresco 0h). A instalação efetiva no host do Railway permanece o passo
operacional final do operador.

**Aditivo pós-auditoria (commit `e6863d6`, 2026-08-15) — serviço
`backup-cron` no Railway:** alternativa ao cron do host para produção Railway —
`ops/Dockerfile` (`postgres:18-alpine` — pg_dump 18 = versão do servidor
Railway, pg_dump antigo não despeja servidor mais novo) + `ops/backup-cron-entrypoint.sh`
(interpreta a `DATABASE_URL` injetada pelo Postgres → `DB_*` do `backup.sh`,
roda o backup a cada **24h** na rede privada em loop `while true` + `sleep
86400`; falha de um ciclo não mata o loop; log no stdout/Railway Logs).
Deploy/volume `/backups` documentados no `ops/backup-restore.md` +
`deploy/railway.md` §6.

## Fase 33 — Painel Geral de Usuários: operação e controle do lojista no Master Console (decisão do usuário 2026-08-15)

Descrição de produto do usuário (painel de usuários do sistema do irmão —
tabela rica com métricas de risco, perfil detalhado, transações em tempo real,
histórico de saques e configuração fina por lojista). Análise da lacuna contra o
código real (2026-08-15): o Master Console (Specs 71/72/73 `audited`) já tem a
listagem básica de lojistas, o detalhe com tabs, transações e saques — **mas
faltam**: (1) **métricas de risco na tabela** — coluna **MED%** (termômetro de
risco, ex.: 8.73% / 62 ocorrências — derivável ao vivo de `refund_rate` +
`chargeback_rate` + contagens do `merchant_risk`/`chargebacks`), **taxa de
conversão** (pix pagos ÷ criados), **nº exato de cobranças/Pix pagos** e
**gateway utilizado** (PADRAO ou rota específica); (2) **dados de conta**
(telefone — **não existe** em `merchants`, só em `bot_leads`; CPF/CNPJ
criptografado; titular; gateway; data de cadastro) e **painel de taxas e
limites** (Taxa Pix, Taxa Fixa R$ 0,40, Taxa de Saque R$ 1,00, MED acumulado,
limites operacionais de Pix e Saque — hoje as taxas/limites são **globais**
`pricing_fee_per_transaction`/`pricing_monthly_fee` e `withdrawal-fee`); (3)
**transações com nome do comprador** (decisão do usuário: **via lead do CRM** —
`bot_leads.name/username` vinculado por `bot_id`+`platform_user_id` da Spec 75,
sem PII nova; produto/serviço via `plan_id` → nome do plano da Spec 49; e
**líquido** descontando a taxa) e **saques** (já têm `fee` da Spec 88 +
destino mascarado — integrar à tela nova); (4) **configuração fina por lojista**
— **regra de taxa** (fixa até X / percentual acima — não existe), **taxa e
limites de saque**, **gateway/rota por lojista** (override no `PixProviderRouter`
da Spec 91 — hoje o roteamento é global por `risk_score`), **rate limit por
lojista** (req/s e req/min — hoje global `RateLimitProperties`),
**kill switch Ativo/Inativo**, **Debitar saldo** (débito manual com ledger +
auditoria + step-up — não existe entry type), **honeypot**, e ações
**rejeitar/desativar/bloquear API/banir** (hoje só `suspend/release` e
`withdrawals block/release`).

**Decisões do usuário (2026-08-15):**
- **Divisão em 4 specs** (padrão backend→frontend das fases anteriores): 97 =
  backend leitura; 98 = backend escrita (config/controle); 99 = UI Painel
  Geral; 100 = UI Config fina.
- **Honeypot = ambiente controlado de monitoramento antifraude** (resposta do
  usuário à pergunta de produto): a conta **continua operando** (o atacante não
  é alertado — banir faria o golpista criar conta nova imediatamente), mas todo
  o tráfego/faturamento do lojista é **direcionado a um fluxo controlado** onde
  ele **não causa prejuízo real nem saca fundos**, e a equipe/antifraude
  **colhe inteligência** (IPs, scripts, tentativa de desvio) sem o atacante
  saber que foi descoberto. Implementação: flag `honeypot` no lojista → novas
  cobranças roteiam para o **provedor trap** (adapter mock `honeypot` no
  `PixProviderRouter` — aceita, nunca processa PAID), **saques bloqueados**,
  webhook `SALE_PAID` nunca chega, evento `HONEYPOT_TRIGGERED` no outbox
  (alerta ao operador, padrão Spec 64 — com throttle) + auditoria.
- **Banir = novo status `BANNED`** (irreversível — bloqueia API, portal, saques
  e cobranças; o `MerchantStatus` hoje só tem ACTIVE/SUSPENDED/INACTIVE);
  **Desativar = `INACTIVE` existente**; **Bloquear API = flag `api_blocked`**
  (API key → 403, portal segue); **Rejeitar conta fica FORA de escopo** (não há
  estado PENDING — cadastro é instantâneo; aprovação manual é spec futura).
- **Nome do comprador via lead do CRM** (`bot_leads.name/username` por
  `bot_id`+`platform_user_id`) — sem PII nova no backend; transação sem lead →
  `buyerIdHash` opaco (decisão da pergunta de produto).
- **Debitar saldo** = débito manual do master com `reason` obrigatório, entry
  novo nos ledgers (`ADMIN_DEBIT`/`ADJUSTMENT`), auditoria (Spec 37) e
  **step-up TOTP** (Spec 69 — ação sensível, padrão saque administrativo);
  novo tipo `ADMIN_DEBIT` em `WalletLedgerEntryType`/`MasterLedgerEntryType`.

| # | Name | Status | References |
|---|---|---|---|
| 97 | Painel de usuários — **backend leitura** (métricas e dados enriquecidos): tabela de lojistas com **MED%** (derivado ao vivo: refund+chargeback ÷ pagos × 100 + contagem), **taxa de conversão** (pagos ÷ criados × 100), **nº de Pix pagos**, **gateway/rota efetiva** (da última cobrança — `pix_transactions.provider`, Spec 91; PADRAO = routing global); **perfil do lojista** com dados de conta (e-mail, telefone — novo, opcional, documento mascarado, titular = name, gateway, createdAt) e **painel de taxas e limites** (Taxa Pix, Taxa Fixa, Taxa de Saque, MED, limites Pix/Saque); **transações** com nome do comprador (lead do CRM, Spec 75/83), produto/serviço (plano Spec 49) e **líquido** (bruto − taxa); **saques** integrados; espelho read-only **stores** (`GET /api/v1/master/merchants/{id}/stores` — "Ver Shops"); sem mudança de contrato das rotas existentes — endpoints master aditivos read-only | audited | [requirements](specs/97-painel-usuarios-leitura/requirements.md) · [design](specs/97-painel-usuarios-leitura/design.md) · [task](specs/97-painel-usuarios-leitura/task.md) · Visão V73 |
| 98 | Painel de usuários — **backend escrita** (config fina por lojista + controle de conta): **config** (`GET/PUT /api/v1/master/merchants/{id}/config` — ROLE_MASTER, PATCH parcial): regra de taxa (`pix_fee_type` FIXED/PERCENT, `pix_fee_fixed`, `pix_fee_percent`, `pix_fee_threshold` — fixa até X, % acima; aplicada no netAmount Spec 25/97 e na fatura Spec 8), `withdrawal_fee` (override do R$ 2,50 global — Spec 88), limites operacionais (`pix_min`/`pix_max`, `withdrawal_min`/`withdrawal_max`), **rate limit por lojista** (`rate_limit_rps`/`rate_limit_rpm` — override no `AuthenticatedRateLimitFilter`, fail-closed); **rota de gateway = fora de escopo** (por perfil de operação — Spec 122, `merchant_profiles`; coordenação 2026-08-15); **kill switch** Ativo/Inativo (`INACTIVE`); **Debitar saldo** (`POST .../config/debit` `{amount, reason}` — ledger duplo `ADMIN_DEBIT` Specs 27/34 + auditoria + step-up; saldo negativo permitido); **honeypot** (flag + provedor trap + `HONEYPOT_TRIGGERED` no outbox com throttle + saques 409); **bloquear API** (`api_blocked` → 403 no `ApiKeyAuthFilter`); **banir** (status `BANNED` irreversível); migration **V61** (colunas em `merchants` + enum BANNED + entry types); RBAC (Spec 69 — catálogo 12) + auditoria (Spec 37) + step-up (Spec 69) | audited | [requirements](specs/98-painel-usuarios-config/requirements.md) · [design](specs/98-painel-usuarios-config/design.md) · [task](specs/98-painel-usuarios-config/task.md) · Visão V74 |
| 99 | Painel Geral de Usuários — **UI Master Console**: tabela rica de lojistas (nome/e-mail, status com badge, taxas aplicadas, gateway, **conversão %**, **Pix pagos**, **MED%** colorido com contagem) + **menu de ações** (Ver detalhes, Ver shops, Config, Sacar, Rejeitar/Desativar/Bloquear API/Banir, Marcar honeypot — ConfirmDialog Spec 73); **perfil do lojista** (KPIs de topo: saldo, faturamento com Pix pagos, conversão, nº de shops; dados da conta; painel de taxas e limites); **aba Transações** (valor, status, comprador, produto, TXID, líquido); **aba Saques** (valor, status, chave Pix mascarada, timestamp, taxa); consumo dos endpoints da Spec 97; padrão UX da Spec 73 | audited | [requirements](specs/99-painel-usuarios-ui/requirements.md) · [design](specs/99-painel-usuarios-ui/design.md) · [task](specs/99-painel-usuarios-ui/task.md) · Visão V75 |
| 100 | Configuração fina por lojista — **UI Master Console**: tela `/console/merchants/:id/config` com formulário de regra de taxa (fixa até X / % acima), taxas e limites de saque (seção gateway NÃO implementada — sem endpoint, coordenação 2026-08-15), **rate limit** (req/s e req/min), **kill switch** Ativo/Inativo, **Debitar saldo** (modal com valor + motivo + step-up TOTP no cliente — Spec 72/73), **honeypot** (toggle + confirmação), **bloquear API**; consumo dos endpoints da Spec 98; padrão UX da Spec 73 | audited | [requirements](specs/100-painel-usuarios-config-ui/requirements.md) · [design](specs/100-painel-usuarios-config-ui/design.md) · [task](specs/100-painel-usuarios-config-ui/task.md) · Visão V76 |

**`/spec-plan` (2026-08-15): Specs 97–100 `planned`** — proposta apresentada ao
usuário após análise da lacuna vs código real (inventário no topo desta fase) e
3 decisões de produto fechadas via pergunta (4 specs; honeypot = ambiente
controlado de monitoramento antifraude — conta segue operando, tráfego vai para o
provedor trap, saques bloqueados, alerta com throttle, sem alertar o atacante;
banir = BANNED irreversível + rejeitar conta fora de escopo). Visões V73–V76.

**`/spec-new` + `/spec-verify` (2026-08-15): Specs 97–100 `verified`** —
requirements/design/task criados em
`management/specs/{97-painel-usuarios-leitura,98-painel-usuarios-config,
99-painel-usuarios-ui,100-painel-usuarios-config-ui}/` e verificados contra o
código real. **Âncoras validadas (0 achados de escopo):** `MerchantStatus`
= ACTIVE/SUSPENDED/INACTIVE (Spec 98 adiciona BANNED — VARCHAR sem CHECK, V1);
`MasterPermission` 9 permissões (Spec 98 → 12: `merchant.config`,
`merchant.debit`, `merchant.ban`); `MasterStepUpGuard`/`MasterStepUpProperties`
(`required-permissions` — Spec 98 adiciona `merchant.debit`/`merchant.ban`);
`PricingCalculator.transactionFee(PricingPlan)` (Spec 98 D4.1 muda a assinatura
para `merchantId`+`amount` — call-sites registrados no task);
`BotLeadRepository.findByBotIdAndPlatformUserId` (Spec 97 D5 — nome do comprador
via lead do CRM, decisão do usuário); `ChargebackJpaRepository.countByMerchantId`
+ `PixTransactionJpaRepository.countByMerchantIdAndStatus` (Spec 97 C2 — MED ao
vivo); `AuthenticatedRateLimitFilter` + `AuthenticatedMerchantResolver` (Spec 98
D4.3 — rate limit por lojista sem query extra); `withdrawal-fee: 2.50` no yml
(Spec 88); migrations **V60/V61 livres** (última real: V59 — V60 phone da 97,
V61 config da 98); `WalletLedgerEntryType`/`MasterLedgerEntryType` existem
(`ADMIN_DEBIT` novo); `ConsoleSpaController`/`spaPublicPaths`/`useStepUp`/
`useDialogFocus` existem (Specs 99/100). **Achados de precisão corrigidos nos
documentos (durante o verify):** (1) Spec 97 — `ChargebackJpaRepository.
countByMerchantId` e `PixTransactionJpaRepository.countByMerchantIdAndStatus`
**já existem** (Confirmado no código — o task T1.2 foi escrito com
"verificar existência" e confirmou existência; ajustado); (2) Spec 98 — o
`MasterStepUpGuard` fica em `application/service` (não `infrastructure/
security`); (3) Spec 99 — o `MerchantDetailPage` já tem `TABS` com
earnings/fees/risk/ledger/receivables/withdrawals/invoices — a nova aba
Transações entra após "Saques" e o perfil (KPIs/Dados/Taxas) entra no topo sem
quebrar as tabs existentes; (4) Spec 100 — rota `/console/merchants/:id/config`
precisa ficar **antes** de `:id/*` no React Router (ordem). **Ordem de
implementação:** 97 (leitura — raiz) → 98 (escrita) → 99 (UI painel, depende de
97/98) → 100 (UI config, depende de 98). Pronto para `/spec-implement 97`.

## Fase 34 — Portal rico do lojista: shell, dashboard, financeiro, gateways, tráfego, gamificação, conversão e automações (decisão do usuário 2026-08-15)

Descrição de produto do usuário (2026-08-15) do **portal do lojista** do sistema
de referência (header global, menu lateral expandido, dashboard rico, módulo
financeiro, base de clientes, gateways, checkouts, webhooks, gestão de links com
filtro de tráfego, postagens, ranking/premiações, afiliado, automações). Análise da
lacuna contra o código real (2026-08-15): o portal do lojista (Specs 46/73
`audited`) **já tem** um menu básico (Dashboard/Carteira/Cobrança/Vendas/
Destinos/Saque/Webhooks/Bots/Transmissões/Clientes/Tracking/Credenciais/
Segurança), dashboard com KPIs básicos (Spec 21), CRM completo com Live Chat
SSE + handoff (Specs 83/84/90 `audited`), Bots (Specs 74/76), **Postagens/
Broadcast (Specs 79/80 `audited` — destino, preview, mídia, editor Markdown,
botões, agendadas, bem-vindos, repost)** e Tracking de pixels/UTM (Specs 81/82).
**Faltam** (decisão do usuário — marcou TODOS os blocos): header global,
menu expandido, dashboard rico (gráfico/log em tempo real/Top 5/premiações),
financeiro rico, gateways UI, links/redirecionamento, ranking/premiações,
afiliado/comissões, checkout builder + bio link + sorteio, automações avançadas
(fluxos/redirecionadores/remarketing), CRM enriquecido, webhooks melhorado e
comunidade.

**Decisões do usuário (2026-08-15):**
- **Divisão em 19 specs com Backend + UI SEPARADOS** (padrão estrito das fases
  74–90): cada bloco vira 2 specs (backend + frontend), exceto os leves (CRM
  enriquecido, Webhooks melhorado, Comunidade) que ficam numa spec única de
  fechamento.
- **Reuso máximo do que já é `audited`**: Broadcast/Postagens (79/80) **não é
  replanejado** (já entrega destino/preview/mídia/editor/botões/agendadas/
  bem-vindos/repost); o CRM (83/84/90) já entrega contadores/busca/Abrir Chat
  com SSE + handoff — o "CRM enriquecido" só adiciona colunas (plano/fluxo/data
  última interação) e busca por telefone; o Tracking (81/82) já é pixels/UTM
  para ads (o novo bloco "Links/Redirecionamento" é **outra coisa**: links rastreáveis
  com domínio próprio, UTM por campanha, códigos de venda e filtro de tráfego server-side).
- **Gateways UI** é visual sobre o backend da Spec 91 (fallback inteligente já
  existe: `PixProviderRouter`/`RoutingPolicyResolver`/`PixProviderHealthRegistry`);
  o portal ganha a tela de visão/ordem/catálogo (read-only + config aditiva).
- **Filtro de Tráfego/Redirecionadores** = decisão de produto sensível (proteção de
  contas de anúncio Meta/TikTok/Kwai) — implementado como **links rastreáveis
  com página intermediária + filtro de filtro de tráfego server-side** (user-agent/JS/
  IP) que mostra página limpa para o robô da rede social e o destino real para
  o humano; **não é** evasão de revisão da Meta — é controle de tráfego
  documentado (a política de uso da Spec 16 permanece).
- **Ordem de implementação:** 101→102 (shell), 103→104 (dashboard), 105→106
  (financeiro), 107→108 (gateways), 109→110 (links/redirecionamento), 111→112
  (afiliado), 113→114 (ranking/premiações), 115→116 (checkout/bio/sorteio),
  117→118 (automações), 119 (leves). Backend antes do frontend por bloco.

| # | Name | Status | References |
|---|---|---|---|
| 101 | **Shell do portal (backend)** — header global: `GET /api/v1/portal/shell` (saudação com hora, data, nome do lojista, **faturamento do período + meta + barra de progresso**, **leads recentes** com avatar/iniciais do CRM, atalhos: ajuda/notificações/perfil/config); `GET /api/v1/portal/shell/recent-leads` (carrossel); notificações (contador + lista leve); aditivo, read-only, reuso Specs 21/83 (**`implemented` 2026-08-17 — `PortalShellService` + `PortalShellController` + `BotLeadRepository.findRecentByMerchantId` (JPQL COALESCE updated/created); desvios: `unreadCount` = nº de itens (sem estado de lida), fuso via `Instant.atOffset`; `mvn test` 2082/0/0**) (**`audited` 2026-08-17 — `/spec-audit`: endpoint verificado (greeting.date dentro do objeto — design D2.1), `SensitiveDataLoggingFilter` sem PII, JPQL merchant-scoped + COALESCE + cap no adapter; `mvn test` 2082/0/0 + E2E shell 2/2 — detalhe no task.md §Audit**) | audited | [requirements](specs/101-portal-shell-backend/requirements.md) · [design](specs/101-portal-shell-backend/design.md) · [task](specs/101-portal-shell-backend/task.md) · Visão V77 |
| 102 | **Shell do portal (UI)** — `Layout.tsx` com **header global** (faturamento + barra de meta, saudação/data, carrossel de avatares de leads recentes → abre Live Chat do CRM, atalhos) + **menu lateral expandido em seções** (Visão Geral: Dashboard/Ranking/Premiações/Analises; Financeiro; Clientes; Automações: Meus Robos/Meus Fluxos/Redirecionadores/Remarketing/Postagens; Ferramentas: Gateways/Trackeamento/Checkout/Bio Link/Webhooks/Cupons & Sorteios; Comunidade/Afiliado) com navegação para as rotas existentes + placeholders das novas specs; padrão UX Spec 73 (**`implemented` 2026-08-17 — `HeaderGlobal` + `LeadAvatar` + `NotificationsMenu` + `Sidebar` (NAV_GROUPS) + `Layout` reescrito (ProfileSelector Spec 139 preservado) + `HelpModal` + `CrmPage` com `?lead=`; desvios: ajuda = modal (não rota), itens futuros = links desabilitados "em breve"; 4 E2E atualizados p/ labels novos; builds limpos + E2E shell 2/2 + specs afetados 21/21 + suíte completa verde**) (**`audited` 2026-08-17 — `/spec-audit`: header (saudação/data/billing/barra/avatares), chat via avatar (C2), menu em seções (C3), badge (C4) + 401 cobertos no E2E; `npm run build` limpo + E2E shell 2/2 + `mvn test` 2082/0/0 — detalhe no task.md §Audit**) | audited | [requirements](specs/102-portal-shell-ui/requirements.md) · [design](specs/102-portal-shell-ui/design.md) · [task](specs/102-portal-shell-ui/task.md) · Visão V77 |
| 103 | **Dashboard rico (backend)** — KPIs: Vendas Aprovadas (volume + count), Taxa de Conversão, **Total Starts**, **Ticket Médio**; **gráfico de desempenho temporal** (série por dia da semana — reuso `/revenue-series` + agregado); **log de atividades em tempo real** (`GET /api/v1/portal/dashboard/activity-log` — eventos de lead/checkout/PAID recentes, polling curto); **Top 5 Players** (`GET /api/v1/portal/dashboard/top-players` — ranking por faturamento, visível no portal); premiações/metas (Dados); aditivo, reuso Specs 21/75/83 | verified | [requirements](specs/103-dashboard-rico-backend/requirements.md) · [design](specs/103-dashboard-rico-backend/design.md) · [task](specs/103-dashboard-rico-backend/task.md) · Visão V78 |
| 104 | **Dashboard rico (UI)** — `DashboardPage.tsx` reescrita: cards de KPI (Vendas Aprovadas, Conversão, Total Starts, Ticket Médio), gráfico temporal (SVG/CSS puro, sem lib — padrão do console), **log de atividades em tempo real** (polling/SSE), **Top 5 Players** lateral, **seção Premiações/Metas** com progresso; padrão UX Spec 73 | verified | [requirements](specs/104-dashboard-rico-ui/requirements.md) · [design](specs/104-dashboard-rico-ui/design.md) · [task](specs/104-dashboard-rico-ui/task.md) · Visão V78 |
| 105 | **Financeiro rico (backend)** — `GET /api/v1/portal/financial/summary?period=hoje\| verified |semana\|mes\|todo`: **resumo de Pix** (gerados/pagos/pendentes), gráfico de métodos (Pix %), **ticket médio**, **expirados**, **reembolsos**, **leads únicos**; `GET /api/v1/portal/transactions` com **gateway** (`provider` Spec 91), **status**, **fluxo**, valores **bruto/líquido**, nome do cliente + produto (reuso Spec 97 D5 lógica do lead); filtros temporais; aditivo | verified | [requirements](specs/105-financeiro-rico-backend/requirements.md) · [design](specs/105-financeiro-rico-backend/design.md) · [task](specs/105-financeiro-rico-backend/task.md) · Visão V79 |
| 106 | **Financeiro rico (UI)** — página Financeiro (rota `/financial`): filtros temporais (Hoje/Ontem/Semana/Mes/Todo), contadores de Pix (gerados/pagos/pendentes), gráfico de métodos de pagamento, tabela de transações (cliente/produto/adquirente/data/bruto/liquido) com filtros por gateway/status/fluxo, resumo do período lateral; padrão UX Spec 73 | verified | [requirements](specs/106-financeiro-rico-ui/requirements.md) · [design](specs/106-financeiro-rico-ui/design.md) · [task](specs/106-financeiro-rico-ui/task.md) · Visão V79 |
| 107 | **Gateways (backend)** — `GET /api/v1/portal/gateways` (visão read-only dos adquirentes: configurados, **ordem de fallback** da Spec 91, estado do circuito, extratos por adquirente via `pix_transactions.provider`); `GET /api/v1/portal/gateways/catalog` (catálogo disponível: Pix/Cartao/Cripto — aditivo read-only); config de ordem por lojista (aditiva, se o router aceitar — reuso Spec 91) (**`audited` 2026-08-17 — `/spec-audit`: endpoint verificado (displayName/order/circuitState/volume+count; escopo por perfil Spec 139 via `X-Profile-ID`, BOT_ONLY sem override), DTOs sem segredo, Spec 91 intacta; 1 achado de doc corrigido (design D4: nome real do E2E `portal-gateways-ui.spec.js`); `mvn test` 2082/0/0 + E2E 2/2 — detalhe no task.md §Audit**) | audited | [requirements](specs/107-gateways-backend/requirements.md) · [design](specs/107-gateways-backend/design.md) · [task](specs/107-gateways-backend/task.md) · Visão V80 |
| 108 | **Gateways (UI)** — página Gateways (rota `/gateways`): cards dos adquirentes conectados (Shark Wallet/Atenas Pay/etc. → providers reais pagarme/asaas) com extratos, **sistema de fallback inteligente** visual (Principal/Fallback 1/Fallback 2 — ordem da Spec 91), catálogo de gateways disponíveis (Pix/Cartao/Cripto) com ativação (config); padrão UX Spec 73 (**`audited` 2026-08-17 — `/spec-audit`: `FallbackChain` (setas + rotação automática), `GatewayCard` (badges ordem/circuito + extrato), `GatewayCatalog` (seções + badges available/configured, read-only), empty state; rota wired (App/SPA/spaPublicPaths); `npm run build` limpo + E2E 2/2 + `mvn test` 2082/0/0; 0 achados de código — detalhe no task.md §Audit**) | audited | [requirements](specs/108-gateways-ui/requirements.md) · [design](specs/108-gateways-ui/design.md) · [task](specs/108-gateways-ui/task.md) · Visão V80 |
| 109 | **Links/Redirecionamento (backend)** — **Gestão de Tráfego**: tabela `traffic_links` (merchant, nome, domínio próprio, URL camuflada `/{code}`, `sale_code`, `utm_*`, `filtro de tráfego` flag, página intermediária, status); `traffic_domains` (domínios do lojista p/ camuflagem); `sale_codes` (códigos de venda p/ atribuição de comissão/parceiro — base do Afiliado 111); `traffic_clicks` (clique + contexto: IP/UA/ref, decisão filtro de tráfego); **`GET /l/{code}`** (redirect público com **filtro de tráfego server-side**: robô da rede social → página limpa; humano → destino real/landing; conta clique, válida `sale_code`); métricas por link (cliques, vendas, receita, conversão); endpoints CRUD no portal; auditoria Spec 41 | verified | [requirements](specs/109-links-filtro de tráfego-backend/requirements.md) · [design](specs/109-links-filtro de tráfego-backend/design.md) · [task](specs/109-links-filtro de tráfego-backend/task.md) · Visão V81 |
| 110 | **Links/Redirecionamento (UI)** — página Links (rota `/links`): KPIs (links ativos, cliques, filtro de tráfego), tabela de links (nome/status/URL camuflada/código `shk=`/tag CLOAKER/Entrega/Desempenho: cliques-vendas-receita-conversao), busca/filtros, ações (copiar, preparar campanha, menu — editar filtro de tráfego/pausar/deletar), gerenciador de **domínios** e **página intermediária**; modal Preparar Campanha (UTM: origem/mídia/campanha); padrão UX Spec 73 | verified | [requirements](specs/110-links-filtro de tráfego-ui/requirements.md) · [design](specs/110-links-filtro de tráfego-ui/design.md) · [task](specs/110-links-filtro de tráfego-ui/task.md) · Visão V81 |
| 111 | **Afiliado/Comissões (backend)** — `affiliates` (código do afiliado/coprodutor, % comissão, status), `affiliate_commissions` (atribuição por `sale_code`/link da Spec 109 na cobrança PAID, cálculo de comissão na mesma transação, ledger novo `AFFILIATE_COMMISSION`, payout via wallet do afiliado); endpoints portal (CRUD afiliados, resumo de comissões, retirada); auditoria Spec 41; sem dupla contagem (Spec 29/30) | verified | [requirements](specs/111-afiliado-backend/requirements.md) · [design](specs/111-afiliado-backend/design.md) · [task](specs/111-afiliado-backend/task.md) · Visão V82 |
| 112 | **Afiliado/Comissões (UI)** — página Afiliado (rota `/affiliate`): código de afiliado, coprodutores, tabela de comissões (venda/valor/%) e retirada; padrão UX Spec 73 | verified | [requirements](specs/112-afiliado-ui/requirements.md) · [design](specs/112-afiliado-ui/design.md) · [task](specs/112-afiliado-ui/task.md) · Visão V82 |
| 113 | **Ranking + Premiações (backend)** — concurso de faturamento: `GET /api/v1/portal/ranking` (posição do lojista + Top 10 por período); `GET /api/v1/portal/rewards` (metas de faturamento configuráveis `nyxcash.rewards.*` — ex.: meta 50k libera "Shark Blood"; progresso do lojista, `reward_unlocked`); job de verificação de metas (ShedLock, padrão Spec 53); reuso das somas APPROVED (Spec 30); aditivo | verified | [requirements](specs/113-ranking-premiacoes-backend/requirements.md) · [design](specs/113-ranking-premiacoes-backend/design.md) · [task](specs/113-ranking-premiacoes-backend/task.md) · Visão V83 |
| 114 | **Ranking + Premiações (UI)** — seções no portal: Ranking (Top 5 Players + posição do lojista, rota `/ranking`) e Premiações (placas/metas com progresso, rota `/rewards`); padrão UX Spec 73 | verified | [requirements](specs/114-ranking-premiacoes-ui/requirements.md) · [design](specs/114-ranking-premiacoes-ui/design.md) · [task](specs/114-ranking-premiacoes-ui/task.md) · Visão V83 |
| 115 | **Checkout + Bio Link + Sorteio (backend)** — `checkout_pages` (construtor de páginas de pagamento customizadas: identidade, produto, cor, botão, sorteio config), `bio_link_pages` (árvore de links) + `bio_link_items`; **página pública** `GET /c/{slug}` e `GET /bio/{slug}` (render server-side dos dados — reuso `PixProviderClient` para gerar o Pix); `sorteio_config` (prêmios, probabilidades, exibição no checkout); CRUD portal; aditivo | verified | [requirements](specs/115-checkout-biolink-sorteio-backend/requirements.md) · [design](specs/115-checkout-biolink-sorteio-backend/design.md) · [task](specs/115-checkout-biolink-sorteio-backend/task.md) · Visão V84 |
| 116 | **Checkout + Bio Link + Sorteio (UI)** — páginas: `/checkouts` (listagem + **construtor** de checkout customizado), `/biolink` (editor de árvore de links), Cupons & Sorteios (config de prêmios); visual público (rota `/c/:slug`, `/bio/:slug` servida pelo backend — esta spec é o **editor** no portal); padrão UX Spec 73 | verified | [requirements](specs/116-checkout-biolink-sorteio-ui/requirements.md) · [design](specs/116-checkout-biolink-sorteio-ui/design.md) · [task](specs/116-checkout-biolink-sorteio-ui/task.md) · Visão V84 |
| 117 | **Automações avançadas (backend)** — **Meus Fluxos**: `bot_flows` (funis de conversação configuráveis: nós de mensagem/ação, reuso Specs 50/51) + `bot_flow_nodes`; **Redirecionadores**: reuso da Spec 109 (links com filtro de tráfego) com UI própria de "redirect" (agrupados — sem backend novo, só endpoints de listagem); **Remarketing**: job ShedLock que detecta cobrança PENDENTE expirada/abandonada → dispara mensagem de recuperação via bot (reuso Spec 51/79); aditivo | verified | [requirements](specs/117-automacoes-backend/requirements.md) · [design](specs/117-automacoes-backend/design.md) · [task](specs/117-automacoes-backend/task.md) · Visão V85 |
| 118 | **Automações avançadas (UI)** — páginas: `/flows` (Meus Fluxos — editor visual de nós), `/redirects` (Redirecionadores — lista + criar, reuso Spec 110), `/remarketing` (campanhas de recuperação com histórico); padrão UX Spec 73 | verified | [requirements](specs/118-automacoes-ui/requirements.md) · [design](specs/118-automacoes-ui/design.md) · [task](specs/118-automacoes-ui/task.md) · Visão V85 |
| 119 | **Leves: CRM enriquecido + Webhooks melhorado + Comunidade (backend+UI)** — CRM: colunas **plano/fluxo/data última interação** na tabela de clientes + busca por **telefone** (reuso `bot_leads` Specs 75/83); Webhooks: **validador de payload JSON** na tela (gaveta com o JSON real do evento) + **aviso de segurança** HMAC/HTTPS/desativação após 5 falhas (dados reais da Spec 35); Comunidade: `community_posts` (avisos/novidades da plataforma — CRUD portal read + seed master) e `community_comments`? (decisão: só posts read + reação, sem chat — KISS); padrão UX Spec 73 (**`implemented` 2026-08-17 — F0–F3: `q` com telefone + DTO aditivo `lastPlanName`/`lastInteractionAt`/`flowName` (via `bot_funnel_events` — coluna criptografada não casa), `WebhookResponse` status/attempts/lastError + `/{id}/sample?type=` (mock sem PII, `buyerIdHash` mascarado), migration **V78** `community_posts` + `GET /portal/community` + `MasterCommunityController` (RBAC 13ª permissão `community.manage` + auditoria `COMMUNITY_POST_*`) + seed "Bem-vindo"; UI: `CrmPage` colunas + `WebhooksPage` drawer/aviso + `CommunityPage` (rota + menu + SPA paths); `mvn test` 2097/0/0 + E2E `portal-leves.spec.js` 3/3 + suíte completa 213/213 (0 falhas, 3 skips honestos) — detalhe no task.md §Implementação**) | implemented | [requirements](specs/119-leves-crm-webhooks-comunidade/requirements.md) · [design](specs/119-leves-crm-webhooks-comunidade/design.md) · [task](specs/119-leves-crm-webhooks-comunidade/task.md) · Visão V86 |

**`/spec-plan` (2026-08-15): Specs 101–119 `planned`** — proposta apresentada
ao usuário após análise da lacuna vs código real (inventário: portal já tem
menu básico, dashboard básico, CRM completo, Bots, Broadcast/Postagens e
Tracking; faltam os 11 blocos marcados). Decisão do usuário (2026-08-15):
**Backend + UI separados** (padrão estrito 74–90) → 19 specs; Broadcast/
Postagens (79/80) e Tracking (81/82) **não replanejados** (já `audited`);
Filtro de Tráfego = links rastreáveis + página intermediária + filtro server-side
(controle de tráfego documentado, não evasão). Visões V77–V86.

**`/spec-new` + `/spec-verify` (2026-08-15): Specs 101–119 `verified`** —
requirements/design/task criados em
`management/specs/{101-portal-shell-backend,102-portal-shell-ui,
103-dashboard-rico-backend,104-dashboard-rico-ui,105-financeiro-rico-backend,
106-financeiro-rico-ui,107-gateways-backend,108-gateways-ui,
109-links-filtro de tráfego-backend,110-links-filtro de tráfego-ui,111-afiliado-backend,
112-afiliado-ui,113-ranking-premiacoes-backend,114-ranking-premiacoes-ui,
115-checkout-biolink-sorteio-backend,116-checkout-biolink-sorteio-ui,
117-automacoes-backend,118-automacoes-ui,119-leves-crm-webhooks-comunidade}/`
e verificados contra o código real. **Âncoras validadas (0 achados de escopo):**
`MerchantDashboardUseCase`, `BotLeadRepository`, `PlatformActivityRepository`,
`BotFunnelEventRepository`, `StoreButtonRepository`/`StoreMessageRepository`
(Specs 50/51 — nós de fluxo da 117), `ProcessPixWebhookService` (hook de
comissão da 111), `PixProviderHealthRegistry`/`RoutingPolicyResolver`
(gateways/fallback da 107), `WebhookUrlValidator` (SSRF-safe — links 109,
checkout 115, fluxos 117), `JobExecutionService`/`QueryParamParser`/
`PortalSpaController`/`ConsoleSpaController`/`spaPublicPaths`/`permitAll` do
`SecurityConfig`, `TransactionStatus` (financeiro 105), migrations **V60–V67
livres** (última real: V59), `VARIABLE_PREFIXES` do `RateLimitKey` (Spec 69).
**1 achado de precisão registrado (não bloqueante, tratado nas tasks):** as
**rotas novas com `{id}`** (`/portal/links/{id}`, `/portal/flows/{id}`,
`/portal/affiliates/{id}`, `/portal/checkouts/{id}`, `/portal/biolinks/{id}`,
`/portal/sorteios/{id}`, `/portal/remarketing/{id}`, `/portal/bots/...`,
`/master/community/posts/{id}`) precisam entrar no `VARIABLE_PREFIXES` do
`RateLimitKey` (padrão SEC-23/Spec 69 — cardinalidade por principal) — citado
nas tasks das specs que criam rotas com `{id}`. **Ordem de implementação:**
101→102 (shell), 103→104 (dashboard), 105→106 (financeiro), 107→108 (gateways),
109→110 (links/redirecionamento), 111→112 (afiliado), 113→114 (ranking/premiações),
115→116 (checkout/bio/sorteio), 117→118 (automações), 119 (leves). Pronto para
`/spec-implement 101`.

**Design registrado (2026-08-16 — detalhamento do Gap 4 com o usuário):**
decisão de arquitetura **ADR-020** (Bot × Gateway = superfícies de cliente
distintas sobre **UM backend** — sem split de serviço; isolamento por
prefixo/auth; divisão futura só da fatia read dos bots) e **wireframes de
referência** gravados nos `design.md`: Spec 102 D7 (shell — header global +
menu em seções), Spec 104 D5 (dashboard rico — KPIs/gráfico/Top 5/log/
premiações), Spec 108 D4 (página admin de gateways — fallback + catálogo),
Spec 115 D8 (fluxo de dados do checkout público `/c/{slug}` — do GET ao
webhook PAID) e Spec 116 D7 (wireframes do Gateway `/c/:slug` em 4 estados,
Bio Link `/bio/:slug`, construtor 2 colunas e sorteio Σ100). Os wireframes são
referência visual — o template visual final do frontend virá do usuário.

**Aprofundamento dos wireframes (2026-08-16, mesmo dia):** as 6 páginas
restantes do Gap 4 ganharam design aprofundado nos `design.md` — Spec 106 D5
(Financeiro rico — estados/empty/filtros/paginação/formatos), Spec 110 D5
(Links/Redirecionamento — KPIs, modais Preparar Campanha/Cloaker/Domínios/Sale Codes,
empty por filtro), Spec 112 D4 (Afiliado — modal coprodutor resolve lojista,
histórico por seleção, retirada via wallet), Spec 114 D5 (Ranking com pódio +
Premiações com RewardCard em 3 estados), Spec 118 D6 (Meus Fluxos com editor
de nós/validação por tipo, Redirecionadores por reuso, Remarketing com logs) e
Spec 119 D7 (CRM com colunas plano/fluxo/interação + busca por telefone,
Webhooks com drawer de payload sem PII + aviso de segurança, Comunidade
read-only + CRUD master).

**Aprofundamento dos pontos complexos (2026-08-16, em seguida):** Spec 118
D6.1.1 (editor de nós — regras por tipo MESSAGE/SEND_LINK/ADD_TO_GROUP/
GRANT_ROLE, troca de tipo preserva campos, reorder local, payload de
referência), Spec 119 D7.2.1 (drawer de payload — select de evento, copiar
JSON, mascaramento `buyerIdHash` sem PII), Spec 114 D5.1.1 (pódio — composição
3 colunas 2º/1º/3º, casos de borda: menos de 3, empate espelha backend, selo
"você", acessibilidade), Spec 110 D5.3.1 (modal Preparar Campanha — wireframe
completo com UTMs/filtro de tráfego/resumo, pré-preenchimento por contexto, validação
SSRF-safe).

**Aprofundamento dos formulários (2026-08-16, em seguida):** Spec 112 D4.4
(modal de coprodutor — fluxo de resolução por e-mail/código com debounce,
estados digitando/resolvendo/encontrado/não-encontrado, anti-enumeração,
coprodutor duplicado → PATCH, auto-exclusão rejeitada), Spec 116 D7.3.1
(construtor de checkout — preview ao vivo por keystroke, tabela de validações
por campo, slug sugerido do título + 409 com sufixo `-2`, preservação de views
na edição), Spec 110 D5.3.2 (modal "Novo link" — domínio próprio, filtro de tráfego,
vínculo de sale_code, criação → linha selecionada + acesso rápido ao Preparar
Campanha).

**Fechamento do design do Gap 4 (2026-08-16, último):** Spec 116 D7.2.1
(editor do Bio Link `/biolink` — identidade + preview + itens com reorder +
modal de item) e D7.4.1 (página da Sorteio `/sorteio` — linhas dinâmicas,
Σ=100 com válido/inválido, Testar giro server-side, vínculo global/por-checkout); arch.md
(jornadas ponta a ponta J1–J4: venda no Bot, venda no Gateway, link→pagamento
com filtro de tráfego/UTM, remarketing — + mapa consolidado superfícies×specs);
Spec 73 D9/D10/D11 (inventário de componentes compartilhados com decisões de
reuso, regras mobile/responsivo por página, catálogo de empty states/erros).
**Gap 4 com design completo e rastreável; aguardando template visual do
usuário para aplicar a identidade antes do `/spec-implement 101`.**

**`/spec-plan` (2026-08-16): Spec 129 `planned` — Landing page + cadastro
público.** Buraco real identificado: não existe landing page nem tela de
cadastro no sistema (rotas públicas do portal são só `/login`,
`/forgot-password`, `/reset-password`; o backend já tem `POST /api/v1/merchants`
`permitAll` — Specs 4/12 — com contrato `{name, email, password,
turnstileToken}`, mas **sem UI**). **Estrutura aprovada pelo usuário
(2026-08-16):** mensagem principal = **centralização operacional + facilidade
de uso** ("tudo em um só lugar"); seções = hero (mensagem central + CTAs),
"Por que centralizar?" (operação unificada + facilidade), grade "O que inclui"
(8 cards: velocidade de processamento, segurança ponta a ponta, carteira
centralizada, relatórios de saldo em tempo real, integração via código/API,
bots Telegram/Discord, checkout personalizado, um login só) + rodapé. Página de
cadastro `/signup` mostra a **API key 1x** no sucesso (padrão Spec 12). **Visual
PENDENTE — o usuário vai fornecer um template/desenho que define a identidade;
esta spec fica `planned` até o template chegar.**

**`/spec-new` + `/spec-implement` (2026-08-19): Spec 129 `implemented` — Landing
page + cadastro público.** O usuário entregou o **template visual** (protótipo
"aaaaa" — identidade escura + laranja-fox: hero, métricas, recursos, como
funciona, integrações, preços, rodapé), que **substitui a estrutura original de
2026-08-16** (o conteúdo/copy final é o do template, que já estava no ar).
Entrega **frontend-only** em `portal/src/pages/LandingPage.tsx` (rota `/`
pública no `App.tsx`; redirect antigo `/` → `/dashboard` removido; o `LoginPage`
segue navegando a `/dashboard`) + bloco CSS `.landing-*` (tema escuro +
laranja-fox escopado em vars `--fox-*` — não afeta o tema claro do portal) +
ícones SVG inline (sem lib de UI — Fase 29) + header fixo com **estado logado**
(`useAuth`: deslogado → Login/Criar conta; logado → Painel/Sair) + menu mobile.
Todos os CTAs → `/register` (cadastro com API key 1x — Spec 142; `/` já era
público no `SecurityConfig.spaPublicPaths()` — **zero mudança de backend**).
**Gates**: `npm run build` portal (84 módulos) + console (67, ordem portal →
console — achado Spec 73) limpos; E2E `e2e-tests/tests/landing.spec.js` (3/3:
render das seções, header deslogado + CTA → `/register`, nav + Preços). Visão
**V105**. **`/spec-audit 129` (2026-08-19): Spec 129 `audited`** — **8/8
critérios OK, 0 achados** (rota `/` 200 sem auth + `spaPublicPaths`; seções do
template; header com `useAuth` + menu mobile; CTAs → `/register`; identidade
escopada em `.landing`; zero dependência; builds portal 83 + console 67 limpos;
E2E `landing.spec.js` 3/3 + regressão `portal-shell-ui.spec.js` 2/2). O WIP de
divisão do CRM (`LeadsPage`/`/crm/chat`) que estava no working tree foi
**preservado** em `portal/.tmp-template/wip-crm-split/` (fora do commit —
decisão do usuário 2026-08-19). Fim do ciclo SDD — feature LIVE.

**`Redesign visual` (2026-08-19): Spec 129 refinada — landing fiel ao protótipo
"aaaaa".** Trabalho **pós-auditoria** aprovado pelo usuário (addendum na
requirements.md — mesma feature, sem nova spec): `LandingPage.tsx` +
`.landing-*` em `styles.css` reescritos para aproximar o protótipo — **header
em pill flutuante** (encolhe ao rolar), **hero com mockup 3D** do dashboard
(`DashboardMockup` + `fox-float` + glow aurora + grid lines), **métricas em
painel único**, **Recursos em bento grid** (card grande + 2 médios + 3
pequenos), **Como funciona** com numeração outline + raposa ao fundo,
**Integrações bento 6-col + marquee**, **Preços com borda em gradiente**,
**CTA final gradiente com a raposa** (`fox-forest-close.png`, luminosity) e
animações `Reveal` (IntersectionObserver, `prefers-reduced-motion`).
**Textos/seções idênticos** — os 11 seletores do E2E foram preservados.
**Assets**: `portal/public/fox-forest-close.png` (usada) + `fox-snow-close.png`
(copiada, não usada) — o Vite copia `public/` para o estático do Spring Boot.
**Gates**: builds portal 83 + console 67 limpos (ordem portal → console);
E2E `landing.spec.js` **3/3 sem ajuste de seletor**. Zero mudança de backend/
rotas (`/` pública, `spaPublicPaths`, CTAs → `/register` intactos).

### Plano de implementação — Fase 34 (registrado 2026-08-17)

Execução **adiada** (decisão do usuário 2026-08-17 — plano registrado, código
**não iniciado**; specs 101–119 permanecem `verified`). Ordem por lotes
(backend antes do frontend por bloco — decisão 2026-08-15):

| Lote | Specs | Bloco | Reuso do audited |
|---|---|---|---|
| 1 | 101+102 | Shell — header global (faturamento+meta, leads, notificações) + menu expandido em seções | Specs 21/83 |
| 2 | 103+104 | Dashboard rico — KPIs, gráfico temporal, log em tempo real, Top 5, premiações | Specs 21/75/83 |
| 3 | 105+106 | Financeiro rico — resumo Pix, métodos, transações com gateway/cliente/produto/bruto-líquido | Specs 91/97 |
| 4 | 107+108 | Gateways UI — visão dos adquirentes, ordem de fallback, catálogo | Spec 91 |
| 5 | 109+110 | Links/Redirecionamento — links rastreáveis + domínio próprio + filtro de tráfego server-side (UA/JS/IP) | novo (o mais sensível) |
| 6 | 111+112 | Afiliado/Comissões — `affiliates`, código do afiliado/coprodutor | novo |
| 7 | 113+114 | Ranking + Premiações — concurso de faturamento, Top 5, prêmios | novo |
| 8 | 115+116 | Checkout builder + Bio Link + Sorteio — `checkout_pages` construtor | novo |
| 9 | 117+118 | Automações avançadas — `bot_flows` (funis, redirecionadores, remarketing) | novo |
| 10 | 119 | Leves — CRM enriquecido + Webhooks melhorado + Comunidade | Specs 83/84/90/35 |

Cada lote = ciclo SDD completo (`/spec-verify` já feito → `/spec-implement` →
`/spec-audit`). **Não afeta a operação atual em produção** (features novas sobre
o portal `audited`).

## Fase 35 — PVS (Pagamento via Saldo): pagar Pix Copia e Cola com o saldo do wallet (decisão do usuário 2026-08-15)

Descrição de produto: o lojista **paga um Pix Copia e Cola estático** (chave Pix
embutida no brcode) usando o **saldo do wallet** — pagamento a fornecedor/loja
sem precisar sacar e sem cadastrar destino. Análise da lacuna contra o código
real (2026-08-15): o PVS é **uma variação do pipeline de saque que já existe** —
reuso direto de `PayoutProviderClient.createPayout` (Specs 36/38/47/92, com
fallback via `PayoutProviderRouter`), `WithdrawalProcessingPersister` Fase A
(débito + PROCESSING + ledger sob lock — SEC-19), `WithdrawalSubmissionPersister`
Fase B (referência do PSP fora da transação), `WithdrawalSettlementService`
(webhook PAID/FAILED + estorno automático de **valor + taxa**, Spec 88) e
`WithdrawalReconciliationService` (encerra PROCESSING órfãos); taxa de saque R$
2,50 (`nyxcash.pricing.withdrawal-fee`) e ledger `WITHDRAWAL_DEBIT`/
`WITHDRAWAL_FEE_DEBIT`. **Faltam**: (1) **destino arbitrário** — o saque exige
`destinationId` cadastrado (Spec 31); o PVS aceita a chave livre do Copia e
Cola → `destination_id = null` + snapshot da chave no `destination_key_snapshot`
(coluna já existe, V21); (2) **parser do Pix Copia e Cola** —
`PayoutDestination` só aceita chave (CPF/CNPJ/phone/EVP); a string EMV
`000201...` precisa ser **parseada** (novo `PixCodeParser`, TLV); (3)
**anti-abuso** — destino arbitrário = risco maior (dinheiro saindo para chave
qualquer) → **teto diário + gates**; (4) **auditoria/ledger** — reuso
`WITHDRAWAL_DEBIT`/`WITHDRAWAL_FEE_DEBIT` (sem entry novo — estorno/
reconciliação intactos), semântica nova via `withdrawals.type = 'BILL_PAYMENT'`.

**Decisões do usuário (2026-08-15):**
- **Escopo do destino** = **só Pix Copia e Cola**; fechado no detalhe técnico:
  **só Pix estático** (chave embutida no TLV 26.00.01) — o `createPayout` só
  envia para **chave Pix**; **Pix dinâmico** (brcode de conta/boleto com URL
  `br.gov.bcb.pix` + txid) e **boleto de barras** NÃO passam pelo pipeline atual
  → rejeitados com mensagem clara (fora de escopo).
- **Modelo de dados** = **reusar `withdrawals`**: novo `WithdrawalType.BILL_PAYMENT`
  (a coluna `type` da V21 é `VARCHAR(20)` **sem CHECK no banco** — só o enum
  restringe → mudança de enum apenas, `@Enumerated(STRING)` length 20);
  `destination_id = null`, `destination_key_snapshot` = chave extraída
  (criptografada em repouso, mesmo padrão do saque).
- **Taxa** = **igual ao saque** (R$ 2,50 — Spec 88); débito de valor + taxa;
  estorno devolve valor + taxa.
- **Anti-abuso** = **sim, teto diário + gates**: teto diário configurável
  (`nyxcash.pvs.daily-cap`, default R$ 5.000,00) somando os PVS criados no dia
  (todos os status — tentativa contabiliza) + **teto por operação**
  (`nyxcash.pvs.max-per-payment`, default R$ 1.000,00); ambos avaliados DENTRO
  da Fase A sob o lock do lojista (`findByIdForUpdate`, SEC-19 — sem corrida);
  gates de conta reusados (ACTIVE + desbloqueio + inadimplência + saldo).
- **Estrutura** = **2 specs** (padrão Backend+UI das fases recentes): 120 =
  backend (pipeline + parser + anti-abuso + V68 + endpoints); 121 = UI portal
  (formulário Copia e Cola + limites do dia no `WithdrawPage`).

| # | Name | Status | References |
|---|---|---|---|
| 120 | **PVS backend** — pagamento via saldo: novo `WithdrawalType.BILL_PAYMENT` (enum apenas — V21 sem CHECK, `@Enumerated(STRING)`), `destination_id = null` + chave no `destination_key_snapshot`; **`PixCodeParser`** (EMV TLV → chave; só estático; valida CRC-16; dinâmico/boleto → 400 com mensagem clara); `PvsPaymentService` espelhando o `WithdrawalService` (Fase A nova `createBillPaymentWithdrawal` no `WithdrawalProcessingPersister` — ACTIVE + saldo + **teto diário + teto por operação** sob lock SEC-19 → Fase B `createPayout` + estorno via `WithdrawalSettlementService` + `WithdrawalSubmissionPersister`); ledger reuso `WITHDRAWAL_DEBIT`/`WITHDRAWAL_FEE_DEBIT`; taxa R$ 2,50 (Spec 88); **migration V71** (desvio de numeração: design previa V68, mas V69/V70 já aplicadas pelas Specs 122/124 — Flyway sem `out-of-order`; CHECK de `withdrawals.type` + índice `ix_withdrawals_merchant_type_created` p/ teto diário); endpoints `POST /api/v1/portal/pvs/pay` + `GET /api/v1/portal/pvs/limits` (ROLE_MERCHANT_PORTAL); `PlatformAction.BILL_PAYMENT_REQUESTED`; histórico do portal inclui BILL_PAYMENT **sem mudança** (`findByMerchantIdOrderByCreatedAtDesc` não filtra por tipo); webhook/reconciliação reuso total (**`implemented` 2026-08-15 — F0–F5 entregues: `PixCodeParser` + `ParsedPix` (CRC-16 verificado contra check-value 0x29B1 e vetor cross-verificado), `PvsAmountException`/`PixCodeParseException`/`PvsLimitExceededException` (400/409 com código), Fase A com `blockOnDebt` (desvio D3), sum diário no repo/adapter (índice V71), serviço 2 fases, controller + DTOs, `PvsProperties` fail-fast + yml, `RateLimitKey` += `/portal/pvs`; `mvn test` **1865/0/0** (53 PVS novos); E2E `pvs.spec.js` **3/3** (pay→COMPLETED+limits, erros 400/409, FAILED→estorno valor+taxa); V71 aplicada no banco dev**; **`audited` 2026-08-15 — `/spec-audit` 9/9 critérios OK, 0 achados de código**: verificação no código real (parser com todas as rejeições + `pixCode` nunca logado — grep; Fase A com tetos sob `findByIdForUpdate` SEC-19; `limits` com floor 0 read-only; settlement/estorno intactos — E2E 31 devolve valor+taxa; histórico sem filtro de tipo — E2E 29; V71 aplicada + CHECK/índice; trilha `BILL_PAYMENT_REQUESTED` só `{amount}`; rate limit por principal cobre `/portal/pvs` via `AuthenticatedRateLimitFilter` + `RateLimitKey`) | audited | [requirements](specs/120-pvs-backend/requirements.md) · [design](specs/120-pvs-backend/design.md) · [task](specs/120-pvs-backend/task.md) · Visão V87 |
| 121 | **PVS UI portal** — aba/sessão no `WithdrawPage.tsx`: campo de colar o Copia e Cola, valor auto-preenchido do EMV (campo 54) ou digitado, **prévia do destino** (chave mascarada), valor + taxa R$ 2,50 + total, **limites do dia** (usado/restante de `GET /pvs/limits`), botão pagar com confirmação (padrão UX Spec 73), estados de erro (código inválido, Pix dinâmico não suportado, teto excedido, saldo insuficiente), sucesso mostra PROCESSING + saldo atualizado; histórico de saques já mostra os PVS (backend não filtra tipo) (**`implemented` 2026-08-15 — T0–T4 entregues: seção "Pagamento via Saldo" + card "Limites do dia" + card "Prévia" no `WithdrawPage.tsx` (sem reestruturar o saque/auto-saque), prévia best-effort com TLV walk (chave mascarada + valor do EMV 54 readonly quando presente), mapa de erros (Spec 120 → mensagem, D4) + detecção client-side de Pix dinâmico (26.25/80/81), `ConfirmDialog` de confirmação (danger), reload de saldo/limites/histórico no sucesso; `npm run build` limpo (tsc + vite, 69 módulos); E2E UI `portal-ui.spec.js` (bloco "Pagamento via Saldo") **3/3** (fluxo feliz cola→prévia→confirma→PROCESSING→histórico, Pix dinâmico → aviso, teto por operação → banner) + regressão portal-ui/auto-withdraw/pvs **10/10**; **`audited` 2026-08-15 — `/spec-audit` 8/8 critérios OK, 1 achado de código corrigido, 0 abertos**: verificação no código real (formulário com readonly/editável + aviso 000201; prévia mascarada + `ConfirmDialog` danger com total; card de limites com barra e floor 0; mapa de erros D4 + reload de limites em erro de teto; sucesso limpa form e recarrega saldo/limites/histórico; loading/disabled/ErrorBanner; **achado corrigido — critério 7 (foco no campo ao entrar na aba):** o textarea não tinha foco inicial → `autoFocus` adicionado + asserção E2E `toBeFocused` no fluxo feliz; `npm run build` re-validado + E2E UI 3/3 pós-fix) | audited | [requirements](specs/121-pvs-ui/requirements.md) · [design](specs/121-pvs-ui/design.md) · [task](specs/121-pvs-ui/task.md) · Visão V88 |

**`/spec-plan` (2026-08-15): Specs 120/121 `planned`** — proposta apresentada ao
usuário após análise da lacuna vs código real (PVS = variação do pipeline de
saque; reuso direto de `PayoutProviderClient`/persisters Fase A+B/settlement/
reconciliação; faltam destino arbitrário + parser EMV + anti-abuso) e 4 decisões
de produto fechadas via pergunta (só Pix Copia e Cola; reusar `withdrawals`;
taxa R$ 2,50; teto diário + gates) + 2 decisões estruturais (só Pix **estático**
— `createPayout` só paga chave, dinâmico/boleto fora; 2 specs Backend+UI).
Visões V87–V88.

**`/spec-new` + `/spec-verify` (2026-08-15): Specs 120/121 `verified`** —
requirements/design/task criados em `management/specs/{120-pvs-backend,
121-pvs-ui}/` e verificados contra o código real. **Âncoras validadas (0 achados
de escopo):** `withdrawals.type` = `VARCHAR(20) NOT NULL DEFAULT 'MERCHANT'`
**sem CHECK no banco** (V21 — só o enum `WithdrawalType` restringe;
`BILL_PAYMENT` = mudança de enum; `WithdrawalJpaEntity.type` é
`@Enumerated(EnumType.STRING)` length 20); `PayoutProviderClient.
PayoutDestination(pixKey, bankCode, agency, account, accountType)` +
`PagarMePayoutProviderClient` (infere `cpf`/`cnpj`/`phone`/`evp` pelo formato —
chave parseável obrigatória; sem parser EMV hoje — o código só **gera** brcode
`pixCopyPaste`, nunca parseia); `WithdrawalService` (fluxo 2 fases + estorno
B-04) + `WithdrawalProcessingPersister` (Fase A sob `findByIdForUpdate`, SEC-19)
+ `WithdrawalSubmissionPersister` + `WithdrawalSettlementService`/
`WithdrawalReconciliationService` (estorno valor+taxa Spec 88 — **reuso total,
nenhuma mudança**); `WithdrawalJpaRepository.findByMerchantIdOrderByCreatedAtDesc`
**sem filtro de tipo** (histórico do portal já mostra BILL_PAYMENT);
`PlatformAction` (valor novo `BILL_PAYMENT_REQUESTED`);
`PricingProperties.getWithdrawalFee()` (R$ 2,50); migrations **V60–V67
reservadas** (Specs 97/98 e 101–119) → PVS usa **V68**; `WithdrawPage.tsx`
existe (Spec 73/70 — formulário de saque + card auto-saque). **Ordem de
implementação:** 120 (backend) → 121 (UI, depende de 120). Pronto para
`/spec-implement 120`.

## Fase 36 — Perfil de operação: seletor de contexto por perfil no portal (decisões do usuário 2026-08-15, revisadas)

Descrição de produto (revisada 2026-08-15): o portal do lojista ganha **N perfis
de operação** — entidade `merchant_profiles` — e um **seletor de contexto** no
**HeaderGlobal** (`Layout.tsx`, Spec 102) que alterna o **perfil ativo** (ex.:
"Padrão BR" ↔ "Global/Stripe"). Cada perfil carrega a **rota de gateway**
(cobrança Pix) e a **frota de bots** (escopo de dados). **Fase 1 (Specs 122/123)
= fundação** (entidade + seletor + header `X-Profile-ID` + override por perfil
no resolver); **fase 2 (Spec 124, planejada) = filtro rígido** — o switch
"reseta" o contexto nos ~13 módulos (dashboard, ranking, financeiro, CRM, bots,
redirectores, remarketing, tracking), mostrando **apenas os dados do perfil
ativo**. Análise da lacuna vs código real (2026-08-15): o roteamento de cobrança
**já é dinâmico por lojista** no `PixProviderRouter` (Spec 91 `audited`) — ordem
por **risco** (`RoutingPolicyResolver` sobre `merchant_risk.risk_score`) com
circuit breaker por lojista; `pix_transactions.provider` já grava o provedor que
criou (V58); `store_bots` (V49) tem `merchant_id` (base para o FK `profile_id`).
**Falta**: (1) **perfil de operação** — não existe entidade de perfil nem
override de rota por perfil (a Spec 98 planejava `merchants.gateway_route` no
V61 — **removida**: a rota vive no perfil, dona: Spec 122); (2) **o mecanismo
no portal** — o lojista poder criar/alternar perfis e escolher a rota de cada
um; (3) **contexto no frontend** — hoje o portal não tem estado de perfil além
do auth (`auth.tsx`/`apiClient.ts`); o `Layout.tsx` tem o HeaderGlobal (Spec
102) — ponto do seletor; o `BroadcastEditorPage` já tem seletor de bot
(`draft.botId` de `/api/v1/portal/bots`). Só existem adapters
**pagarme/asaas/mock** (`PixProviderClient`) — **Stripe/Global NÃO existe**:
decisão do usuário = **só a estrutura** (mecanismo completo, seletor inerte até
existir um 2º adquirente real).

**Decisões do usuário (2026-08-15, revisadas nesta sessão):**
- **Entidade `merchant_profiles` (N por lojista)** — cada perfil: nome, rota de
  gateway (`gateway_route`, PADRAO = null), frota de bots (`store_bots.profile_id`),
  default + ordem. Substitui a coluna `merchants.gateway_route` (Spec 98: coluna
  removida do V61).
- **Fase 1 = fundação (Specs 122/123)** — entidade + seletor no HeaderGlobal +
  header `X-Profile-ID` + override por perfil no `RoutingPolicyResolver` +
  endpoints `GET/POST/PATCH /api/v1/portal/profile`. Nada é escondido nesta fase.
- **Filtro rígido na fase 2 (Spec 124, planejada)** — o switch **esconde** dados
  fora do perfil ativo (dashboard, ranking, extrato, CRM, bots, redirectores,
  remarketing, tracking) e as cobranças passam a rotear com o `profileId` do
  perfil (rota por perfil viva).
- **"Stripe/Global" = só a estrutura** — sem adapter novo; o catálogo lista os
  adquirentes **configurados** + `PADRAO` (risco). O mecanismo fica pronto; com
  um 2º adquirente real, o switch passa a valer sem mudança de arquitetura.
- **Fonte da verdade = banco** — a rota vem da linha do perfil no banco; o header
  `X-Profile-ID` **seleciona o perfil do próprio lojista (server-validado)**,
  **nunca decide rota** — à prova de fraude: o lojista não se auto-roteia para
  adquirente fora da lista configurada.
- **Bot fleet por perfil** — FK `store_bots.profile_id` (NULL = default do
  lojista); na fase 2 o bot ativo filtra o escopo de dados.
- **Só cobrança** — `PixProviderRouter`; o payout (`PayoutProviderRouter`, Spec
  92) segue a config atual (lista estática).
- **Estrutura** = 3 specs: 122 = backend (entidade + resolver por perfil +
  endpoints + header); 123 = UI (`ProfileContext` + `ProfileSelector` no
  HeaderGlobal + `X-Profile-ID`); 124 = **fase 2, filtro rígido por perfil nos
  módulos (agora `verified` — Spec 124)**.

| # | Name | Status | References |
|---|---|---|---|
| 122 | **Perfil de operação (backend, fase 1)** — **entidade `merchant_profiles`** (migration **V69** — dona; `id UUID`, `merchant_id`, `name`, `gateway_route` NULL/PADRAO, `is_default`, `sort_order`, soft-delete; **backfill** 1 perfil "Padrão BR" por lojista) + `store_bots.profile_id` (FK NULL; bot NULL = default); `RoutingPolicyResolver.resolveProviders(merchantId, profileId)` — **override do perfil vence risco** (profileId null → política atual); endpoints `GET /api/v1/portal/profile` (profiles com gatewayRoute/availableRoutes/bots + activeProfileId), `POST /api/v1/portal/profile` (cria perfil; valida catálogo → 400 `unknown_gateway_route`/`invalid_name`), `PATCH /api/v1/portal/profile/{id}` (rota/nome; 404 IDOR; lock SEC-19); `PlatformAction` += `PROFILE_CREATED`/`PROFILE_UPDATED`/`GATEWAY_ROUTE_CHANGED` (Spec 41, sem PII); header `X-Profile-ID` = **seleção de perfil validada, nunca decide rota**; Spec 98: `gateway_route` **removida** do V61; payout/policy de risco intactas; **sem adapter novo** (Stripe fora) | audited | [requirements](specs/122-operation-profile-backend/requirements.md) · [design](specs/122-operation-profile-backend/design.md) · [task](specs/122-operation-profile-backend/task.md) · Visão V89 |
| 123 | **Perfil de operação (UI, fase 1)** — `ProfileContext` (profiles + activeProfileId) persistido em `localStorage` (`{activeProfileId}`); `apiClient` anexa `X-Profile-ID` quando ativo; **`ProfileSelector`** no **HeaderGlobal** do `Layout.tsx` (Spec 102) — dropdown de perfis + "criar perfil" (POST) + dropdown de rota do perfil (PATCH); **pré-seleção do bot do perfil ativo** no `BroadcastEditorPage` (novo draft) e destaque no `BotsPage`; Segurança/Config expõe a rota atual (read + PATCH); estados loading/erro/saving (padrão UX Spec 73); **fase 1 não esconde dados** (filtro rígido = Spec 124) | audited | [requirements](specs/123-operation-profile-ui/requirements.md) · [design](specs/123-operation-profile-ui/design.md) · [task](specs/123-operation-profile-ui/task.md) · Visão V90 |
| 124 | **Perfil de operação (fase 2) — filtro rígido de dados por perfil** — o switch de perfil **escopa** os módulos do portal: dashboard/KPIs e vendas/financeiro por `pix_transactions.profile_id` (**V70**, nova; backfill bot→perfil / sem bot→default); CRM/leads, broadcasts, tracking e métricas por **bot-set** (`store_bots.profile_id`, derivado via bot_id); bots listam só a **frota do perfil**; **rota viva** — cobranças (bot flow, portal charge, API legada) resolvem `profileId` e chamam `RoutingPolicyResolver.resolveProviders(merchantId, profileId)` (Spec 122), gravando `profile_id` + `provider`; `ProfileScopeResolver` valida o header (válido → escopa; inválido → **vazio**, nunca vaza; ausente → lojista inteiro — regressão); módulos ainda não implementados (redirectores/ranking/checkout/automações — Specs 109–119) adotam o padrão (D9) | audited | [requirements](specs/124-profile-data-scope/requirements.md) · [design](specs/124-profile-data-scope/design.md) · [task](specs/124-profile-data-scope/task.md) · Visão V91 |

**`/spec-plan` (2026-08-15): Specs 122/123 `planned`** — proposta apresentada ao
usuário após análise da lacuna vs código real (router já é dinâmico por lojista
— falta o **override**; adapters só pagarme/asaas/mock — **Stripe não existe**;
bot é canal de venda, não adquirente; portal sem estado de perfil) e decisões
fechadas via pergunta (só a estrutura; persistido por lojista; bot = escopo de
dados; só cobrança). **Correção de arquitetura aplicada:** a fonte da verdade da
rota é o **banco**, nunca o header — um header cego deixaria qualquer lojista se
auto-rotear (fraude/fuga de risco). Visões V89–V90.

**`/spec-new` + `/spec-verify` (2026-08-15): Specs 122/123 `verified`** —
requirements/design/task criados em
`management/specs/{122-operation-profile-backend,123-operation-profile-ui}/` e
verificados contra o código real. **Âncoras validadas (0 achados de escopo):**
`PixProviderRouter` (já recebe `merchantId` no `CreateChargeCommand`;
`routingKeys(merchantId)` → `RoutingPolicyResolver.resolveProviders(merchantId)`;
fail-fast de credencial; circuit breaker por lojista
`PixProviderHealthRegistry.shouldSkipForMerchant`); `RoutingPolicyResolver`
(políticas por `risk_score` — ponto exato do override); adapters
`PixProviderClient` (pagarme/asaas/mock — catálogo de rotas do profile);
`pix_transactions.provider` (V58 — rota efetiva já gravada); `store_bots` (V49 —
id/merchant_id UUID, soft-delete; `StoreBotManagementUseCase`/
`/api/v1/portal/bots` — base do FK `profile_id`); `gen_random_uuid()` (já usado
no V16); `findByIdForUpdate` (SEC-19); `AuthenticatedMerchantResolver` (Merchant
já no request); portal `Layout.tsx` (HeaderGlobal — Spec 102, ponto do seletor),
`auth.tsx` (`AuthContext`/`PortalMe`), `apiClient.ts` (header global trivial),
`BroadcastEditorPage` (já tem `draft.botId` + `GET /portal/bots` — pré-seleção);
Spec 98 T0.1 (V61 — **`gateway_route` removida**: a rota vive em
`merchant_profiles`, dona: Spec 122); migration **V69 livre** (última real: V59;
V60–V67 reservadas; V68 da Spec 120).

**Revisão do escopo (2026-08-15, decisões do usuário via pergunta):** a visão do
usuário é maior que "rota + bot ativo" — **cada contexto tem funcionalidade
própria** e o seletor de perfil faz **reset contextual nos módulos** (o lojista
tem 1 conta que se comporta como N empresas). Decisões fechadas: **(1)** modelo =
**entidade `merchant_profiles`** (N por lojista, rota + frota de bots + dados
escopados), substituindo a coluna única; **(2)** comportamento = **filtro rígido**
(esconde o que não pertence ao perfil — fase 2); **(3)** faseamento = **fase 1
agora (fundação: entidade + seletor no HeaderGlobal + `X-Profile-ID` + override
por perfil)**, fase 2 depois (**Spec 124** planejada: filtro por módulo +
`profileId` vivo nas cobranças). Specs 122/123 **reescritas** e **re-verificadas**
(âncoras revalidadas: `store_bots` UUID, `gen_random_uuid` no V16, V69 livre);
Spec 98 **coordenada** (`gateway_route` removida do V61 — nota nos 3 docs).
**Ordem de implementação:** 122 (backend) → 123 (UI, depende de 122). Pronto
para `/spec-implement 122`.

**`/spec-new` + `/spec-verify` (2026-08-15): Spec 124 `verified`** —
requirements/design/task criados em `management/specs/124-profile-data-scope/`
e verificados contra o código real. **Âncoras validadas (0 achados de escopo):**
`pix_transactions` (V1 + `bot_id` V50 nullable + `provider` V58 — base da coluna
`profile_id` V70); `bot_leads`/`bot_funnel_events` (V50 — sem merchant_id, join
via `store_bots`); `broadcasts.store_bot_id` (V54); `tracking_profiles`/
`tracking_profile_bots` (V56); `chat_messages` (V57); `CreatePixChargeService`
(`CreatePixChargeUseCase.Command` tem `botId` — Spec 75) vs
`CreatePixTransactionService` (legado, sem bot → perfil default);
`PortalChargeController` (cobrança do portal — perfil do header);
`RoutingPolicyResolver.resolveProviders(merchantId, profileId)` (Spec 122);
serviços do portal `MerchantDashboardService`/`MerchantSalesService`/
`CrmLeadService`/`StoreBotManagementUseCase`/`BotMetricsService`/
`BroadcastQueryService`/`TrackingProfileQueryService` (todos filtram por
`merchantId` hoje — ponto exato do escopo); `apiClient` (Spec 123 já envia
`X-Profile-ID` — a UI não muda para o filtro entrar); migration **V70 livre**
(V69 = Spec 122). Módulos Specs 109–119 (redirectores/ranking/checkout/
automações/afiliado) **sem tabelas no banco** → fora do escopo desta fase, com
padrão documentado (D9). **Ordem de implementação:** 122 → 123 → **124**
(depende de 122 implementada).

**`/spec-implement 122` (2026-08-15): Spec 122 `implemented`** — código entregue e
**`mvn test` verde (1799 testes, 0 falhas)**. Entregue: **V69__merchant_profiles.sql**
(tabela `merchant_profiles` + backfill 1 default "Padrão BR" por lojista +
`store_bots.profile_id` + UPDATE bots → default); `MerchantProfile` (domínio) +
entity/mapper/repository/adapter (`findByIdForUpdate` SEC-19, `gatewayRouteOf`
projection); `store_bots.profile_id` com round-trip no domínio (`assignProfile` +
mapper); **`RoutingPolicyResolver.resolveProviders/resolvePolicyName(merchantId,
profileId)`** — override do perfil vence risco, com **fallback para risco + WARN**
quando a rota está fora do catálogo (defesa em profundidade; sobrecarga
single-arg preservada — regressão Spec 91); **`ProviderCatalog`** (PADRAO +
adquirentes configurados); **`OperationProfileController`**
(`GET /api/v1/portal/profile` com `X-Profile-ID` validado → activeProfileId;
`POST` cria, 1º vira default; `PATCH /{id}` parcial sob lock) + `OperationProfileService`
(default lazy, auditoria `PROFILE_CREATED`/`PROFILE_UPDATED`/`GATEWAY_ROUTE_CHANGED`
Spec 41, IDOR 404); `PlatformAction` += 3 valores; handlers
(`profile_not_found`/`unknown_gateway_route`/`invalid_name`); RateLimitKey +=
`/portal/profile`. **Header `X-Profile-ID` nunca decide rota** (fonte da verdade =
banco); perfil de outro lojista → default (nunca vaza). Testes novos:
`RoutingPolicyResolverTest` (+3 override), `OperationProfileControllerTest`,
`OperationProfileServiceTest`. Próxima: **123 (UI)**.

**`/spec-implement 123` (2026-08-15): Spec 123 `implemented`** — UI entregue e
**`npm run build` do portal verde** (`tsc -b && vite build`, 0 erros) +
**regressão `mvn test` verde (1801 testes, 0 falhas)**. Entregue:
**`portal/src/profile.tsx`** — `ProfileProvider`/`useProfile` (`profiles`,
`activeProfileId`, `activeProfile`, `setActiveProfileId` local,
`setGatewayRoute` PATCH, `createProfile` POST, `refreshProfile`) com persistência
em `localStorage` (`nyxcash.portal.profile` = `{activeProfileId}`), sanidade no
load (perfil removido → default) e limpeza no logout; provider montado dentro do
`AuthProvider` (main.tsx) e **`refreshProfile` no `Layout` junto do `refreshMe`**
(design D2 — sem GET duplicado no mount); **`apiClient.ts`** — `PROFILE_KEY` +
`X-Profile-ID` anexado em toda chamada autenticada quando há perfil ativo
(seleção de contexto documentada no código; rotas públicas sem header);
**`ProfileSelector`** no topo do shell (`Layout.tsx`) — dropdown de perfis
(default primeiro), "criar perfil" (POST), dropdown de rota do perfil (PATCH,
só quando >1 rota), estados loading/saving/erro (padrão UX Spec 73,
`aria-label`/`aria-live`); **`SecurityPage`** — seção "Perfil de operação" com
rota atual (read) + troca por PATCH; **pré-seleção do bot do perfil ativo** no
`BroadcastEditorPage` (novo draft sem `botId` → `activeProfile.bots[0]`;
edição de existente inalterada) e **destaque `is-active` + badge "perfil ativo"**
no `BotsPage` (fase 1 não esconde nada); CSS mínimo no `styles.css`. Ajustes do
build: short-circuit `{error && …}` com estado `unknown` → `ErrorBanner` direto
(padrão do código). **Sem infra de testes no portal** (sem vitest/testing-library
— T1.2/T4.1/T4.2 dependem de setup futuro; T4.3 coberto pelo build + regressão).
Próxima: **124 (fase 2 — filtro rígido de dados por perfil)**.

**`/spec-implement 124` (2026-08-15): Spec 124 `implemented`** — fase 2 (filtro
rígido de dados por perfil) entregue e **`mvn test` verde (1825 testes, 0
falhas)** + **E2E `profile-scope.spec.js` 6/6** + **suíte E2E completa sem
regressão**. Entregue: **V70__pix_transactions_profile.sql** (`profile_id` FK +
índice parcial `(merchant_id, profile_id)` + backfill bot→perfil/sem bot→default);
`PixTransaction.profileId` + `attachProfile` + variantes `...AndProfileId` no
`TransactionRepository` + bot-set (`countByBotIdIn`/`sumAmountByBotIdIn`);
**`ProfileScopeResolver`** (`resolveActiveProfileId` valida pertinência /
`scopeProfileId` para leitura / `botIdsOf` merchant-scoped / `defaultProfileId`
/ `scopeBotIds` — sem header → null = lojista inteiro; header inválido → vazio,
nunca vaza); **cobranças (rota viva)** — `CreatePixChargeService` (header
validado → bot → default) + `CreatePixTransactionService` (legado → default) +
`MerchantChargeController` (portal — header), gravando `profile_id`; **filtros
por módulo** — `MerchantDashboardService`/`MerchantSalesService` (perfil),
`CrmLeadService` (bot-set), `StoreBotManagementUseCase` (frota do perfil),
`BotMetricsService` (bot-set), `BroadcastQueryService` + `findByMerchantIdAndBotIds`,
`TrackingProfileQueryService` + `findByMerchantIdAndBotIds`, e os controllers do
portal passando o escopo; **`PixTransaction.withIdempotencyKey` preserva o
`profileId`** (bug REAL encontrado no E2E — a cópia reconstruía a transação sem o
perfil e o `persistPending` salvava `profile_id = NULL`; corrigido + teste de
regressão); testes novos (`ProfileScopeResolverTest` 11, `BotMetricsServiceTest`
+2, `StoreBotCommandServiceTest` +2, `BroadcastQueryServiceTest` 4,
`TrackingProfileQueryServiceTest` 4, `PixTransactionTest` +1) e atualizações dos
testes existentes para as assinaturas com `profileId`/`botIds`. E2E: 2 perfis
com bots/transações/leads/broadcasts distintos (seeds SQL) → trocar perfil →
dashboard/extrato/bots/CRM/broadcasts mostram só o perfil; `X-Profile-ID`
inválido → vazio; sem header → lojista inteiro. Próxima: `/spec-audit 122`,
`/spec-audit 123`, `/spec-audit 124`.

**`/spec-audit 122` (2026-08-15): Spec 122 `audited`** — **7/7 critérios OK, 0
achados abertos** (detalhe no task.md §Auditoria). Verificações: migration V69
(tabela + backfill "Padrão BR" + `store_bots.profile_id` + UPDATE → default),
resolver com override por perfil (vence risco, fallback+WARN fora do catálogo,
`resolvePolicyName`="override"), `GET/POST/PATCH /api/v1/portal/profile`
(activeProfileId do header validado, 400 invalid_name/unknown_gateway_route,
404 IDOR, lock SEC-19, auditoria `PROFILE_*`/`GATEWAY_ROUTE_CHANGED`), header
`X-Profile-ID` **nunca decide rota** (fonte da verdade = banco; `PixProviderRouter`
lê profileId do comando), testes verdes. **1 bug real corrigido na auditoria**
(frota/bot-set excluía bots NULL com o default ativo — corrigido no
`StoreBotPersistenceAdapter` + JPA query + 3 testes; detalhe na Spec 124).

**`/spec-audit 123` (2026-08-15): Spec 123 `audited`** — **8/8 critérios OK, 0
achados abertos** (detalhe no task.md §Auditoria). Verificações: seletor no
HeaderGlobal (acessível, aria-label/aria-live), persistência em localStorage com
sanidade, troca local + `X-Profile-ID` em toda chamada autenticada, rota do
perfil PATCH no seletor + SecurityPage, criar perfil POST, pré-seleção do bot do
perfil no BroadcastEditorPage + destaque no BotsPage, regressão (E2E completo
verde), build portal limpo. **1 bug real corrigido na auditoria** (sidebar fixa
sem overflow — o ProfileSelector empurrou o rodapé/"Sair" para fora da viewport;
`overflow-y: auto` na `.sidebar`).

**`/spec-audit 124` (2026-08-15): Spec 124 `audited`** — **7/7 critérios OK, 1
achado de código corrigido, 0 abertos** (detalhe no task.md §Auditoria).
Verificações: migration V70 (profile_id + índice parcial + backfill), resolução
de perfil nas cobranças (header→bot→default + rota viva), filtro por perfil em
8 controllers (`ProfileScopeResolver`), header inválido → vazio / ausente →
lojista inteiro, não vazamento (testes de segurança), módulos escopados, `mvn
test` **1824/0/0** + E2E `profile-scope.spec.js` **6/6** + suíte E2E completa
**171/0/0**. **Achado corrigido (bug REAL):** o `StoreBotJpaRepository` filtrava
estritamente `profile_id = :profileId`; bots com `profile_id` NULL (registro da
Spec 74 não atribui perfil; dados legados) pertencem **logicamente** ao perfil
default (Spec 122) e sumiam da UI com o default ativo (o `apiClient` da Spec 123
persiste o default e envia `X-Profile-ID`) — 7 E2E falhando (bots-panel,
bots-creator×2, broadcast-editor, crm, portal-ui, tracking). Corrigido no
`StoreBotPersistenceAdapter.includeUnassigned` (TRUE quando o perfil é o default
do lojista) + `StoreBotJpaRepository.findFleetByMerchantIdAndProfile`/
`findIdsByMerchantIdAndProfile` com `OR profile_id IS NULL` condicional; **3
testes novos** no `StoreBotPersistenceAdapterTest`. E2E `tracking.spec.js`
atualizado (vínculo do bot no modal — filtro rígido exige bot-set; selectors
`exact:true` colidindo com o ProfileSelector da Spec 123). Regressão de
segurança preservada (perfil de outro lojista → vazio; não-default → só bots
atribuídos). Fim do ciclo SDD (roadmap/requirements/design/task `audited`).

## Fase 37 — Trace de transação: rastreio ponta a ponta (decisão do usuário 2026-08-16)

| # | Name | Status | References |
|---|---|---|---|
| 125 | **Trace de transação — rastreio ponta a ponta** (bot → gateway → PSP → ledger → webhook): endpoint read-only `GET /api/v1/master/trace/transactions/{ref}` (`ref` = UUID interno \| audited | `idempotencyKey`) que agrega por `transaction.id` — `pix_transactions` (cabeçalho), `platform_activity_log` (timeline; **índice V73** por entity + filtro `entityId`), `wallet_ledger`/`master_ledger` (movimento de dinheiro), `bot_leads`/`bot_funnel_events` (funil Spec 75) e `notification_outbox` (**coluna de referência + backfill V73**) para a entrega do webhook (`attempts`/`lastError`/`nextAttemptAt`, Spec 35) — e devolve uma **timeline** com `stage` (BOT/GATEWAY/PSP/LEDGER/WEBHOOK). Fases: 1 trace funcional (núcleo), 2 entrega no trace, 3 traceId de negócio (futuro), 4 UI console (futuro). ROLE_MASTER + máscara (reusa `PlatformActivityRecorder`). Fio condutor já existe (`transaction.id` em activity/ledger/outbox/funil); base de observabilidade já existe (`RequestIdFilter`/MDC, Spec 18); `BotFunnelService` já consome SALE_PAID (Specs 75/81/83) | audited | [requirements](specs/125-trace-transacao/requirements.md) · [design](specs/125-trace-transacao/design.md) · [task](specs/125-trace-transacao/task.md) · Visão V92 |

**`/spec-new 125` (2026-08-16): Spec 125 `created`** — proposta aprovada pelo
usuário após mapeamento do que já existe (o fio condutor `transaction.id` em
activity/ledger/outbox/funil; `RequestIdFilter` + MDC; `BotFunnelService` já
consome SALE_PAID). Mudanças estruturais dimensionadas: índice de activity por
`(entity_type, entity_id, created_at)` + filtro no `PlatformActivityQuery`
(critério C4); coluna de referência no outbox + backfill do payload (critério
C5, fase 2). Fases 3–4 (traceId propagado no MDC do worker, UI no console)
registradas como futuras no task.md §Fases futuras.

**`/spec-verify 125` (2026-08-16): Spec 125 `verified`** — documentos validados
contra o código real (âncoras). **Verificados:** `PlatformActivityQuery` sem
`entityId` hoje (C4 correto); `NotificationOutboxPort`
(`savePending`/`findPendingDue`/`markSent`/`incrementAttempts`/`markFailed`) e
`NotificationOutboxJpaEntity` (coluna `status` já existe — C5 usa no trace);
`TransactionRepository` (`findById`/`findByExternalReference`/
`findByMerchantIdAndIdempotencyKey` — D2 lookup 100% suportado);
`PixChargePersistence` (payload `CHARGE_CREATED` com `transactionId` — backfill
do outbox viável); V73 livre (V72 último); rotas master de leitura = ROLE_MASTER
via `SecurityConfig` (padrão `MasterPanelController`). **3 ajustes aplicados:**
(1) **D7/C3/T3.1** — trace é read-only → guard é `hasRole("MASTER")`, NÃO
`MasterPermissionGuard` (permissões finas da Spec 69 são de escrita; corrigido o
design que citava o guard); (2) **T2.1a** — queries novas de repositório:
`findByReferenceTypeAndReferenceId` (wallet/master ledger — o `findByReference`
exige merchantId+type) e `findByTransactionId` (funil — hoje só
`findByBotIdAndTransactionId`); (3) **D5/T4.3** — item `WEBHOOK_DELIVERED`
inclui `status` real do outbox (PENDING/SENT/FAILED) além de attempts/lastError.

**`/spec-implement 125` (2026-08-16): Spec 125 `implemented`** — fases 1–2
entregues (núcleo + entrega no trace): migration **V73** (`ix_platform_activity_entity`
+ outbox `entity_type`/`entity_id` + backfill do payload); `PlatformActivityQuery`
com `entityType`/`entityId` (construtor de conveniência preserva os callers);
`TransactionTraceService` (lookup UUID → externalReference → idempotencyKey; merge
da timeline com stage BOT/GATEWAY/PSP/LEDGER/WEBHOOK); `MasterTraceController` +
`TransactionTraceResponse` (ROLE_MASTER via `/api/v1/master/**`, privacidade — sem
pixKey/QR/buyerIp no payload); queries novas (`findByReferenceTypeAndReferenceId`
nos ledgers, `findByTransactionId` no funil, `findByIdempotencyKey` na transação);
outbox com referência (`OutboxNotification` + `status`, `WebhookEventPublisher`
com overload, `CHARGE_CREATED`/`SALE_PAID`/`SALE_REFUNDED` gravam a referência).
`mvn test` **1965/0/0** (+11: `TransactionTraceServiceTest` 7, `MasterTraceControllerTest`
4; 3 verifies atualizados para a assinatura com referência). Fases 3–4 (traceId
propagado no MDC, UI no console) permanecem como futuras (task.md §Fases futuras).
Próxima: `/spec-audit 125`.

**`/spec-audit 125` (2026-08-16): Spec 125 `audited`** — **6/6 critérios OK, 0
achados de código, 0 abertos** (detalhe no task.md §T6): C1 lookup 3 formas
(`resolve()` UUID → externalReference → idempotencyKey) + 404
`TransactionNotFound`; C2 timeline de fontes reais (activity/ledger×2/funil/
outbox) com merge ordenado e BOT só se funil existir; C3 rota sob
`/api/v1/master/**` → `hasRole("MASTER")` (SecurityConfig L161) + details
mascarado na origem (sem pixKey/QR/buyerIp); C4 `PlatformActivityQuery`
entityType/entityId + índice V73 `(entity_type, entity_id, created_at)`; C5
outbox `entity_type`/`entity_id` + `attempts`/`lastError`/`nextAttemptAt` na
timeline; C6 testes `MasterTraceControllerTest` 5 + `TransactionTraceServiceTest`
6 = **11/0/0** re-executados (suíte completa **1995/0/0**). Fases 3–4 entregues
pela Spec 127 (trace_id V74 + TracePage) — núcleo + incrementais completos.
Fim do ciclo SDD.

## Fase 38 — Alertas proativos: observabilidade operacional (decisão do usuário 2026-08-16)

| # | Name | Status | References |
|---|---|---|---|
| 126 | **Alertas proativos — observabilidade operacional**: fecha o gap de operação (17 métricas `nyx_*` de PSP/CRM/tracking, **zero de operação**). Instrumenta os pontos de falha que importam para o dinheiro — `nyx_job_failure_total{job}`/`nyx_job_duration_seconds{job}` (JobExecutionService, ponto único dos jobs), `nyx_outbox_delivered_total`/`nyx_outbox_failed_total` + `nyx_outbox_backlog_age_seconds` (gauge, outbox Spec 35), `nyx_reconciliation_open_issues` (Spec 30), `nyx_http_5xx_total{route}` (GlobalExceptionHandler), `nyx_http_429_total{group}` (Spec 64), `nyx_webhook_amount_divergence_total` (B-05) — e entrega a **config de alerta** (`docs/observability/`: scrape Grafana Cloud, regras PromQL — outbox parado > 5min, divergência > 0, fallback de adquirente, job falhou, 5xx, 429 —, dashboard de exemplo e runbook). Sem mudança de domínio financeiro; operação passa de reativa a proativa | audited | [requirements](specs/126-alertas-proativos/requirements.md) · [design](specs/126-alertas-proativos/design.md) · [task](specs/126-alertas-proativos/task.md) · Visão V93 |

**`/spec-new 126` (2026-08-16): Spec 126 `created`** — proposta aprovada pelo
usuário (prioridade 1 do gap operacional). Reconhecimento: `/actuator/prometheus`
JÁ exposto (Spec 53); 17 métricas `nyx_*` existentes (PSP/CRM/tracking); falta a
camada de OPERAÇÃO. Instrumentação nos pontos únicos (JobExecutionService,
NotificationProcessor, GlobalExceptionHandler, filtros de rate limit,
WalletReconciliationScheduler, ProcessPixWebhookService B-05) + config
Grafana/regras/runbook em `docs/observability/`.

**`/spec-implement 126` (2026-08-16): Spec 126 `implemented`** — implementação
concluída: T0–T5 entregues (job metrics já existiam — verificação; outbox:
`findOldestPendingCreatedAt` port/adapter/JPA + counters delivered/failed + gauge
backlog; HTTP: 5xx por rota + 429 por grupo; financeiro: gauge de issues OPEN +
counter de divergência B-05; docs `docs/observability/` com 4 arquivos). Testes:
`mvn clean test` **1967/1967 verde** (asserts de métricas nos testes existentes
dos 6 componentes tocados).

**`/spec-audit 126` (2026-08-16): Spec 126 `audited`** — auditoria critério a
critério (C1–C6): C1 job metrics verificado + testado (JobExecutionServiceTest);
C2–C3 outbox/HTTP testados; C4 ganhou `WalletReconciliationSchedulerTest` (gauge
de issues OPEN — achado da auditoria, coberto); C5 docs validados (JSON do
dashboard válido, nomes de métricas conferidos contra o código — tag do PSP é
`outcome`, não `result`; design.md corrigido); C6 `mvn test` **1969/1969 verde**.
Zero achados restantes. Spec DONE — feature LIVE.

## Fase 39 — Trace de transação: traceId propagado + UI no console (fases 3–4 da Spec 125, decisão do usuário 2026-08-16)

| # | Name | Status | References |
|---|---|---|---|
| 127 | **Trace UI + traceId** — fases 3–4 da Spec 125. **Fase 3 (backend, pequeno)**: `trace_id` em `pix_transactions` (V74 — backfill `= id` para legadas, NOT NULL; o traceId de negócio É o id da cobrança, estável do nascimento à morte) + `traceId` no `TransactionTraceResponse` + `data.traceId` no payload do outbox (SALE_PAID/CHARGE_CREATED/SALE_REFUNDED) + **MDC `traceId` no `NotificationProcessor`** (o worker do outbox hoje loga sem requestId — o log estruturado já emite `%X{traceId:-}` e o Axiom inclui MDC, correlação do request do bot → webhook do PSP → worker do outbox por traceId). **Fase 4 (UI)**: página "Trace de transação" no Master Console (`/console/trace`) — colar txid/`idempotencyKey`/`externalReference` → timeline com stage colorido (BOT/GATEWAY/PSP/LEDGER/WEBHOOK), reusa `TransactionTraceResponse`. Sem mudança de fluxo de dinheiro. Fase 5 (lojista vê o próprio trace) registrada como futura — fora de escopo | audited | [requirements](specs/127-trace-ui/requirements.md) · [design](specs/127-trace-ui/design.md) · [task](specs/127-trace-ui/task.md) · Visão V94 |

**`/spec-plan` (2026-08-16): Spec 127 `planned`** — Gap 3 da priorização do usuário
(trace de transação: correlação de logs + tela de suporte). Reconhecimento: o
endpoint `GET /api/v1/master/trace/transactions/{ref}` (Spec 125, fases 1–2) já
monta a jornada; o `RequestIdFilter` põe `requestId` no MDC dos logs HTTP, mas o
worker do outbox roda em thread própria sem correlação; o log estruturado já
emite `traceId=%X{traceId:-}` (basta o worker setar o MDC). Última migration:
V73 → **V74 livre**. Console master: React 18 + Vite, sem lib de testes (a
verificação da UI é typecheck + build). Sem mudança de domínio financeiro;
nenhuma dependência bloqueia.

**`/spec-new 127` (2026-08-16): Spec 127 `created`** — requirements/design/task
criados + Visão V94. Design: traceId de negócio = `pix_transactions.id` (coluna
V74 explícita com backfill `= id`; getter `traceId()` no model sem campo novo;
setter no mapper — padrão provider/profileId); `data.traceId` em 2 pontos do
outbox (CHARGE_CREATED/SALE_PAID/SALE_REFUNDED); MDC no `NotificationProcessor`
via `entityType == "TRANSACTION"` (referência da Spec 125) com `finally` remove;
UI `TracePage.tsx` reusa `TransactionTraceResponse`. Próximos: `/spec-verify 127`
→ `/spec-implement 127`.

**`/spec-implement 127` (2026-08-16): Spec 127 `implemented`** — fases 3–4
entregues (detalhe no task.md §Implementação):

- **Fase 3 (backend)**: migration **V74** (`pix_transactions.trace_id UUID NOT
  NULL` com backfill `= id`); `PixTransaction.traceId()` (getter derivado do `id`,
  sem campo novo — overloads de create/restore intactos);
  `PixTransactionJpaEntity.trace_id` + `PixTransactionMapper.toEntity` seta o
  traceId; `TransactionTraceResponse.traceId` (record + `from(...)`); `data.traceId`
  explícito no payload do outbox (`PixChargePersistence.attachAndPublish`
  CHARGE_CREATED + `ProcessPixWebhookService.publishSaleEvent` SALE_PAID/
  SALE_REFUNDED — contrato auto-documentado); **MDC `traceId` no
  `NotificationProcessor.process`** (`traceIdOf`: `entityType == "TRANSACTION"` →
  `entityId`; `MDC.put` + `MDC.remove` no `finally` — thread do pool sem
  vazamento; OUTBOX_DEAD/JOB_FAILED sem referência não setam). Logback/Axiom já
  emitem `traceId=%X{traceId:-}` — correlação request do bot → webhook do PSP →
  worker pelo MESMO traceId, zero mudança de config.
- **Fase 4 (UI console)**: `TracePage.tsx` (`/console/trace` — input "colar txid/
  idempotencyKey/externalReference" + Buscar via `useAsync`; cabeçalho com
  traceId + botão copiar; timeline vertical com badge de stage colorido
  BOT/GATEWAY/PSP/LEDGER/WEBHOOK + detail chave-valor; loading/404/erro);
  tipos `TransactionTraceResponse`/`TraceItem`/`BotRef`; rota + nav ("Trace" —
  seção Operação); CSS `.trace-timeline`/`.trace-stage-*`; **SPA fallback**
  `/console/trace` no `ConsoleSpaController` + `SecurityConfig.spaPublicPaths()`
  (+ teste — lição Spec 80/84).
- **Verificação**: `mvn test` **1973/0/0** (1969 + 4 novos: `PixTransactionMapperTest`
  1, `NotificationProcessorTest` +3 MDC; asserts de `traceId` no response/payload
  nos testes das Specs 125/35) + `npx tsc --noEmit` e `npm run build` limpos no
  console (67 módulos). Fase 5 (autoatendimento do lojista) fora de escopo.
  Próximo: `/spec-audit 127`.

**`/spec-audit 127` (2026-08-16): Spec 127 `audited`** — **6/6 critérios OK, 0
achados de código** (detalhe no task.md §Auditoria). Verificações: **C1**
`trace_id == id` ao vivo no banco (V74 aplicada; `PixTransaction.traceId()` +
mapper); **C2** trace por transactionId/externalReference/idempotencyKey →
`traceId == transactionId`; **C3** payloads reais no `notification_outbox` de
CHARGE_CREATED e SALE_PAID com `"traceId"` em `data`; **C4** MDC no
`NotificationProcessor` (3 testes: setado durante/ausente após/sem vazamento) +
logback prod `traceId=%X{traceId:-}` + Axiom `includeMdc=true`; **C5** página
`/console/trace` — smoke ao vivo: flag OFF → 401 (sem SPA exposta, invariante de
segurança), flag ON → 200 text/html com o bundle contendo a TracePage;
`ConsoleSpaController`/`SecurityConfig`/teste; **C6** `mvn test` **1973/0/0**
re-executado + `tsc`/`vite build` limpos. **Smoke ponta a ponta ao vivo**:
lojista → cobrança PENDING → trace (3 refs) → webhook PAID → APPROVED com
jornada completa (GATEWAY CHARGE_CREATED → LEDGER SALE_CREDIT → PSP SALE_PAID →
WEBHOOK SALE_PAID com status/attempts). Fim do ciclo SDD — **Gap 3 (Trace UI +
traceId) LIVE.** Fase 5 (autoatendimento do lojista) registrada como futura.

## Fase 40 — Automação de risco: Risk Scoring Engine (decisão do usuário 2026-08-16)

| # | Name | Status | References |
|---|---|---|---|
| 128 | **Risk Scoring Engine — automação de risco por MED (Gap 2)**: o risco hoje é calculado (`merchant_risk`, Spec 16) e exibido (painel, Spec 97 — `medPercent` com faixas LOW/MEDIUM/HIGH) mas **nunca age** além do auto-suspend (Spec 19, chargeback ≥ 3% → SUSPENDED). Esta spec entrega a **única ação automática aprovada**: MED 30d alto → **reduzir `rate_limit_rps`/`rate_limit_rpm`** do lojista automaticamente (reversível quando o MED normaliza — histerese). **Sem bloqueio de conta automático** (decisão do usuário: chargeback alto é inerente à vertical de produtos digitais — nem `deactivate` nem `suspend`; `nyxcash.risk.auto-suspend-enabled` default → `false`). Roda no MESMO job diário (`nyxcash-risk-monitoring`, ShedLock + `JobExecutionService` — ponto de extensão natural): após o snapshot, `RiskScoringEngine.evaluate(merchant, snapshot)` com regra declarativa em config (`nyxcash.risk.auto.*` — limiar de MED default 10%, histerese de restauração 5%, redução 50%, pisos rps/rpm), fail-safes (kill-switch global, nunca age com 0 pagos na janela, piso mínimo), **override manual vence** (coluna `merchants.risk_auto_managed` sticky — humano tocou config/status → engine não age, sem guerra de edição), auditoria na trilha da plataforma (actor SYSTEM, Spec 41 — `PlatformAction` novas com before/after/motivo) + métrica `nyx_risk_auto_actions_total{action}`. **Regra de falha PSP adiada** (sem fonte por lojista — métrica `nyx_psp_route_total` é global; fase futura com contador por lojista no router). Dependências: Specs 16/97/98/41/53 | audited | Visão V95 |

**`/spec-plan` (2026-08-16): Spec 128 `planned`** — Gap 2 da priorização do
usuário (automação de risco). Reconhecimento: o `RiskMonitoringScheduler` já
roda diário (cron `nyxcash.scheduler.risk-cron`, ShedLock + `JobExecutionService`),
`RiskMonitoringService.computeForAllMerchants()` já varre em batch e já tem um
auto-suspend (Spec 19 — **sem trilha de auditoria**, gap coberto por esta spec);
o `medPercent` do painel (Spec 97) é calculado ao vivo (não persistido) com
thresholds `nyxcash.master-panel.risk-thresholds.low-percent/mid-percent`; o
`MerchantConfigService` (Spec 98) já expõe `rateLimitRps`/`rateLimitRpm` no
`MerchantConfig` e o `AuthenticatedRateLimitFilter` lê o override por lojista em
runtime; a auditoria SYSTEM usa `PlatformActivityRecorder` (Spec 41 —
`PlatformActorType.SYSTEM`, `actorId=null`); `MasterAuditRecorder` exige
`actorId` de master (não serve para SYSTEM — trilha via `platform_activity_log`).
**Decisões do usuário**: (1) sem bloqueio automático por chargeback — o
auto-suspend da Spec 19 fica desligado por default (`auto-suspend-enabled=false`)
e o engine NÃO faz `deactivate`/`suspend`; o chargeback alimenta o MED e a única
ação é reduzir rate limit; (2) regra de falha PSP adiada (v1 = MED → rate limit
apenas). Próximo: `/spec-new 128`.

**`/spec-new` + `/spec-verify` (2026-08-16): Spec 128 `verified`** —
requirements/design/task criados em `management/specs/128-risk-scoring-engine/`
e verificados contra o código real. Verificações sem achado bloqueante: `V75`
livre (V74 usado pela Spec 127); `TransactionRepository.countByMerchantIdAndStatusAndCreatedAtBetween`
já existe (janela 30d — APPROVED/REFUNDED); `ChargebackJpaEntity.created_at`
existe + `countByBuyerIdHashAndCreatedAtAfter` como precedente do novo
`countByMerchantIdAndCreatedAtAfter`; `MerchantConfigProvider.findWithConfigForUpdate`
+ `save` (lock SEC-19); `MerchantConfig.rateLimitRps/Rpm` +
`AuthenticatedRateLimitFilter` lendo o override em runtime (rps → senão rpm →
senão teto global); `PlatformActivityRecorder` com `PlatformActorType.SYSTEM`
(actorId null — padrão do AutoWithdrawRunnerService/InvoiceCollectionService);
`MasterAuditRecorder` exige actorId de master (trilha SYSTEM via
`platform_activity_log` — decisão do design D5 confirmada); `Merchant` com
construtor/restore + config (overload novo para `riskAutoManaged`);
`nyxcash.risk.*` no application.yml (bloco `auto` será adicionado);
`auto-suspend-enabled` default mora no código (`RiskProperties`, hoje `true` →
`false` por decisão do usuário). **Achados de precisão aplicados aos
documentos**: (1) design D4 — `riskAutoManaged` entra via **overload novo de
`Merchant.restore`** (com config + flag, usado pelo mapper); `register` default
`false`; (2) design D6 — `auto-suspend-enabled` não existe no yml (default no
código) — T2.3 muda o default do campo + adiciona a chave explícita no yml.
Próxima: `/spec-implement 128`.

**`/spec-implement 128` (2026-08-16): Spec 128 `implemented`** — T0–T6
entregues (detalhe no task.md §Implementação):

- **T0/T1 (domínio/infra)**: migration **V75** (`merchants.risk_auto_managed
  BOOLEAN NOT NULL DEFAULT false`); `Merchant.riskAutoManaged()`/
  `markRiskAutoManaged()` (overload novo de `restore`; mapper mapeia);
  `MerchantJpaEntity` coluna; `MerchantRiskStats` (MED 30d, `paid==0 → 0`).
- **T2 (config)**: `RiskProperties.auto` (`nyxcash.risk.auto.*` — enabled,
  med-threshold 10%, med-recover 5%, reduction 50%, default-rps 5, default-rpm
  300, floor-rps 1, floor-rpm 60; env `NYX_RISK_AUTO_*`) +
  `auto-suspend-enabled` default **`false`** (decisão do usuário — sem bloqueio
  automático; key explícita no yml).
- **T3 (engine)**: `RiskScoringEngine.evaluate(merchant)` — gates (enabled,
  `!riskAutoManaged`, ACTIVE), MED 30d (`countByMerchantIdAndStatusAndCreatedAtBetween`
  + novo `countByMerchantIdAndCreatedAtAfter` no chargeback), `paid==0 → sem
  ação`; `reduce`/`restore` com lock SEC-19 (`findWithConfigForUpdate`) e
  re-checagem sob lock; redução `max(floor, floor(ref × (1−redução%)))`
  (arredonda para BAIXO — fail-safe); idempotente (sem catraca); restaura
  quando MED ≤ recover (histerese).
- **T4 (job + manual vence)**: `RiskMonitoringService` chama
  `engine.evaluate(merchant)` no loop do batch (mesmo job/lock);
  `MerchantConfigService` (updateConfig/honeypot/deactivate/activate/ban) +
  `MerchantStatusControlService` (suspend/release) marcam `risk_auto_managed`
  (debit NÃO marca) — humano tocou → automático não age.
- **T5 (auditoria/métrica)**: `PlatformAction` += `RISK_AUTO_RATE_LIMIT_REDUCED`/
  `RISK_AUTO_RATE_LIMIT_RESTORED`; auditoria SYSTEM (Spec 41, before/after/
  motivo); métrica `nyx_risk_auto_actions_total{action=reduced|restored|
  skipped_manual}`.
- **Verificação**: `mvn test` **1993/0/0** (1973 + 20: `RiskScoringEngineTest`
  11, `MerchantRiskStatsTest` 4, `RiskPropertiesTest` 3,
  `RiskPropertiesBindingTest` 3; `RiskMonitoringServiceTest` atualizado).
  Próximo: `/spec-audit 128`.

**`/spec-audit 128` (2026-08-16): Spec 128 `audited`** — **6/6 critérios OK, 1
achado de código corrigido, 0 abertos** (detalhe no task.md §Auditoria).
Verificações: **C1** `nyxcash.risk.auto.*` binding contra o yml real
(`RiskPropertiesBindingTest`, padrão Spec 68/L16); **C2** engine
(`RiskScoringEngineTest` 12 — dispara/restaura com histerese/idempotente/piso/
lock; redução `floor`); **C3** fail-safes (kill-switch, 0 vendas pagas,
não-ACTIVE, piso; sem bloqueio de status — decisão do usuário); **C4** override
manual vence — **ao vivo**: `PUT /master/merchants/{id}/config` →
`risk_auto_managed = t` no banco + auditoria `MERCHANT_CONFIG_UPDATED` + 429
após 2 req/s com o override (prova do efeito em runtime da ação do engine);
**C5** auditoria SYSTEM (Spec 41) + métrica
`nyx_risk_auto_actions_total{action=reduced|restored|skipped_manual}`; **C6**
`RiskMonitoringService` invoca o engine no job diário (mesmo lock) + `mvn test`
**1995/0/0** (1973 + 22). **Achado corrigido (MÉDIO, do smoke)**: o denominador
do MED era `paid = só APPROVED` — como estornar MOVE a transação para REFUNDED,
um lojista com vendas estornadas zerava o denominador e o fail-safe mascarava o
caso mais arriscado; corrigido `MerchantRiskStats.paidCount() = approved +
refunded` (estornada JÁ foi paga) + testes `allRefundedMerchantStillActs`/
`allRefundedIsHighMedNotZero`. **Gap 2 (Risk Scoring Engine) LIVE.** Fases
futuras: regra de falha PSP (contador por lojista no router), UI para desmarcar
`risk_auto_managed`, alerta de operador da ação (webhook Spec 64).


## Pendência agendada — Rebranding TrevoPay → FoxCash (Spec 140, **em execução** 2026-08-16)

Registrada como **Spec 140** (status `in-progress`) — ver tabela acima.
Planejamento completo em `management/rebranding-foxcash.md` — aprovado pelo
usuário. Execução na branch `rebrand-foxcash` (baseline `f76f772` no main).
Escopo: package Java
(`com.nyxcash` → `com.foxcash`, 1.210 arquivos), classes `Nyx*` → `Fox*`,
env vars `NYX_*` → `FOXCASH_*` (112 código / 22 Railway), métricas
`nyx_*` → `foxcash_*`, Redis `nyx:*` → `foxcash:*`, jobs `nyxcash-*` →
`foxcash-*`, docs (70), frontend/E2E (46), infra Railway (serviço/domínio/rede —
**Fase 6 adiada**). Banco de dados NÃO muda (nenhuma tabela com prefixo `nyx_`).
**Pendência do usuário (Fase 6):** domínio novo registrado; aí renomear serviço
Railway + 22 env vars + trocar domínio público. **Risco alto (Fase 6):**
webhooks PSP (pagar.me/asaas) + setWebhook Telegram/Discord precisam ser
reconfigurados com o novo domínio.

**Fases 1–5 concluídas (retomada 2026-08-16, após crash do computador):** todo o
código renomeado (package `com.foxcash`, classes `Fox*`, `FoxCashApplication`,
pom/Dockerfile/CI, config `foxcash:` + env `FOXCASH_*`, métricas `foxcash_*`,
Redis `foxcash:*`, jobs `foxcash-*`, headers `X-FoxCash-*`, rotas públicas
`/api/v1/foxcash/*` + `/webhooks/foxcash`), docs management/specs/frontend
atualizadas (refs de contrato/config/classe; históricos datados e `.bak.*`
preservados) e assets estáticos regenerados. **Gates verdes**: `mvn test`
**2038/0/0**, context-tests **7/0/0** (boot real com `foxcash:*` + Postgres),
`npm run build` portal (69 módulos) + console (67 módulos) tsc limpo, bots
vitest **48/48**, operator-bot vitest **26/26**, E2E completo **203 passed / 0
failed / 3 skipped** (app dev com `FOXCASH_*` + roteamento PSP
`mock,suitpay`). **Correções do rename** (over-replaces que quebrariam CI/banco):
defaults de banco nos specs E2E e `global-setup.js` (28), ops backup (3),
deploy/README, javadoc do smoke test, arquivo `FoxCashContextSmokeTest.java`,
docs da própria Spec 140 reescritos com o mapeamento correto
(`com.nyxcash` → `com.foxcash`), roadmap/vision V100. **Pendente**: commit na
branch, `/spec-audit` e Fase 6 (Railway — domínio novo).

**Fase 6 concluída + Spec 140 audited (2026-08-17):** domínio novo
`foxcash.example.com` configurado no Cloudflare (zona ativa, SSL Full strict, WAF
espelho da zona antiga, rate limit, Turnstile), 27 env vars `FOXCASH_*`
adicionadas e `NYX_*` removidas (0 restantes), deploy do rebrand `34145e3a`
SUCCESS, rede interna `foxcash.railway.internal`, TRUNCATE das 51 tabelas +
Flyway V76 aplicada, domínio antigo `api.foxcash.example.com` removido (NXDOMAIN),
webhooks PSP/Telegram/Discord verificados — **nada a fazer**
(`FOXCASH_PSP_PROVIDER=mock` + gateway Discord). Detalhes em `deploy/edge.md`
§Zona foxcash.example.com e `management/rebranding-foxcash.md`. **`/spec-audit`
140**: critérios de aceite **5/5 OK, 0 achados** — status `audited`.

## Fase 41 — Programa Nota 10 (decisão do usuário 2026-08-16): specs de maturidade pós-due-diligence

Diagnóstico real (2026-08-16): Spring Boot 3.3.5 (out/2024) com ~1.403
dependências com update disponível (logback 1.5.11→1.6.3, jackson 2.17.2→2.22.1);
OWASP dependency-check NUNCA funcionou (workflow sem NVD_API_KEY obrigatória
desde 2024 + plugin ausente no pom); PostgreSQL 18.4 com Flyway que suporta
até 16; 1 único @SpringBootTest (0 integração real); sem fila dedicada (outbox
DB); single-node Postgres (teto ~1.550 vendas/s).

| # | Spec (proposta) | Status | Referência |
|---|---|---|---|
| 130 | Upgrade Spring Boot 3.5.x + dependências + OWASP bloqueante (CVEs) — **`audited` 2026-08-17 — 5/5 critérios OK, 0 achados abertos** (parent 3.5.16; `mvn test` 2050/0/0 + context-tests verdes re-executados; flyway 12.11.0; OWASP plugin 13.0.0 no pom com `failBuildOnCVSS=7`; workflow `dependency-check.yml` com NVD_API_KEY secret + cache NVD (prova de 0 CVEs roda no CI); integridade L19 OK; deploy `34145e3a` roda o código upgrade em produção) | audited | [requirements](specs/130-upgrade-seguranca-deps/requirements.md) · [design](specs/130-upgrade-seguranca-deps/design.md) · [task](specs/130-upgrade-seguranca-deps/task.md) · Visão V96 |
| 131 | Segurança de runtime: senha logada, actuator, headers, secrets — **`audited` 2026-08-17 — 5/5 critérios OK, 0 achados abertos** (exclusão `UserDetailsServiceAutoConfiguration` — sem senha default no boot; actuator: health público + demais ROLE_ADMIN; headers de segurança; `SecurityRuntimeSecurityTest` 5/5 + `SensitiveDataLoggingFilterTest` 9/9 re-executados verdes + `mvn test` 2050/0/0; achado resolvido: prometheus 404 em `@SpringBootTest` = default novo do Boot 3.5.16 — re-enable do export no teste; deploy `34145e3a` roda o código em produção) | audited | [requirements](specs/131-seguranca-runtime/requirements.md) · [design](specs/131-seguranca-runtime/design.md) · [task](specs/131-seguranca-runtime/task.md) · Visão V96 |
| 132 | Flyway compatível com PostgreSQL 18.x (ou pin versão suportada) — **`audited` 2026-08-17 — 4/4 critérios OK, 0 achados abertos** (override `flyway.version=12.11.0` — o 11.7.2 do BOM 3.5.16 só testa até PG 17; boot real contra PG 18.4: `Successfully validated 64 migrations` **sem** `Flyway upgrade recommended`; `mvn flyway:validate` 64 migrations 0 erros; `mvn test` 2050/0/0 + context-tests verdes re-executados; migrations V1..V77 intactas; obs: JaCoCo 0.73<0.80 pré-existente no HEAD — specs 120-128, não é regressão) | audited | [requirements](specs/132-flyway-postgres/requirements.md) · [design](specs/132-flyway-postgres/design.md) · [task](specs/132-flyway-postgres/task.md) · Visão V97 |
| 133 | PgBouncer + pool tuning (destravar >1.550 vendas/s, ADR-013) — **pendente de infra**: benchmark de capacidade exige loadgen + réplicas Railway (pool por réplica dimensionado + docs T4.1 já feitos; benchmark 8/16 réplicas + PgBouncer = operação de produção) | verified | [requirements](specs/133-pgbouncer-escala/requirements.md) · [design](specs/133-pgbouncer-escala/design.md) · [task](specs/133-pgbouncer-escala/task.md) · Visão V97 |
| 134 | Fila dedicada RabbitMQ (outbox em tempo real) — **implementado 2026-08-17** (`spring-boot-starter-amqp`; `RabbitProperties`/`RabbitConfig` opt-in com DLQ; `OutboxRelay` AFTER_COMMIT + fail-safe ADR-010; `OutboxConsumer` retry→DLQ; modo dreno `FOXCASH_NOTIFICATION_WORKER_ENABLED`; métricas published/consumed/dlq; `mvn test` **2069/0/0** — 11 testes novos; **T5.1 implementado 2026-08-17** (Testcontainers RabbitMQ no job `integration` do CI — Docker do runner: publish→consume→entrega, retry, DLQ, fallback broker down, zero perda sob carga + p95<5s; achados corrigidos: `@EnableRabbit` + `SimpleRabbitListenerContainerFactory` + trusted packages no converter — a app não bootava com `enabled=true`; **`audited` 2026-08-17 — `/spec-audit` 7/7 critérios OK, 0 achados abertos (C1–C6 verificados no código; C7 confirmado: job `integration` do CI verde — Testcontainers RabbitMQ real: publish→consume→entrega, retry, DLQ, fallback broker down, zero perda sob carga p95<5s; `mvn test` **2069/0/0** unitários sem regressão)**) | audited | [requirements](specs/134-fila-rabbitmq/requirements.md) · [design](specs/134-fila-rabbitmq/design.md) · [task](specs/134-fila-rabbitmq/task.md) · Visão V97 |
| 135 | Multi-região / DR / backup restaurado provado — **`audited` 2026-08-17 — 5/5 critérios OK, 0 achados abertos** (DR drill real: backup 477 KB → `pg_restore` exit 0 em 0.646s → 54 tabelas / 66 migrations até V77 / dados idênticos / `flyway:validate` OK; **RPO ≤ 4h** 6/6h — cron `30 2,8,14,20` + dump com hora; **RTO 0.646s**; **backup fora da região ATIVO**: Cloudflare R2 `foxcash-backups` + 6 envs `R2_*` no `backup-cron` + deploy `a4119bf2` validado ao vivo (`[backup-upload] OK: ... replicado no bucket`); **multi-região avaliada/decidida/medida**: Railway Postgres single-region → sem active-active, T3.1 medido (us-east → sfo warm ~61 ms vs ~2 ms, conexão nova ~443 ms) — `deploy/railway.md` §6.1; validação do artefato `pg_restore --list` no `backup.sh` (C5); runbook com failover/contatos; 2 achados de DOC corrigidos na auditoria (T4.1 RTO + contatos C4); incidente CRLF corrigido (`.gitattributes`) | audited | [requirements](specs/135-dr-backup/requirements.md) · [design](specs/135-dr-backup/design.md) · [task](specs/135-dr-backup/task.md) · Visão V97 |
| 136 | Refactor god classes (GlobalExceptionHandler 637, PixTransaction 435) | audited | [requirements](specs/136-refactor-god-classes/requirements.md) · [design](specs/136-refactor-god-classes/design.md) · [task](specs/136-refactor-god-classes/task.md) · Visão V98 |
| 137 | Testes de integração reais (@SpringBootTest + Testcontainers) | audited | [requirements](specs/137-testes-integracao/requirements.md) · [design](specs/137-testes-integracao/design.md) · [task](specs/137-testes-integracao/task.md) · Visão V98 |
| 138 | Docs 100% sincronizada com código (5 specs desatualizadas) — **`audited` 2026-08-17 — 6/6 critérios OK, 0 achados abertos** (frontmatter das 5 specs com código → `implemented`; 64 linhas da tabela sincronizadas; `scripts/check-docs-consistency.sh` com 3 regras + job `docs` no CI que falha o build; allowlist vazia; `mvn test` 2050/0/0 + docs-consistency OK) | audited | [requirements](specs/138-docs-sincronizadas/requirements.md) · [design](specs/138-docs-sincronizadas/design.md) · [task](specs/138-docs-sincronizadas/task.md) · Visão V98 |

**`/spec-audit 130/131/132/138` (2026-08-17): Specs 130–132/138 `audited`** —
fechamento do Programa Nota 10 (detalhe no §Auditoria de cada task.md):
**130** 5/5 OK (Boot 3.5.16, `mvn test` 2050/0/0 + context-tests verdes, OWASP
13.0.0 `failBuildOnCVSS=7` + workflow com NVD_API_KEY/cache — prova de 0 CVEs
roda no CI, deploy `34145e3a` roda o upgrade em produção); **131** 5/5 OK
(exclusão `UserDetailsServiceAutoConfiguration` — sem senha no boot;
`SecurityRuntimeSecurityTest` 5/5 + `SensitiveDataLoggingFilterTest` 9/9
re-executados; actuator/headers verificados); **132** 4/4 OK (Flyway 12.11.0 —
PG 18.4 sem `Flyway upgrade recommended`; `flyway:validate` 64 migrations 0
erros; migrations V1..V77 intactas); **138** 6/6 OK (5 specs com código →
`implemented`; 64 linhas da tabela sincronizadas; `check-docs-consistency.sh` 3
regras + job `docs` no CI; allowlist vazia). Todas: `mvn test` **2050/0/0**
re-executado + docs-consistency OK. Fim do ciclo SDD — Programa Nota 10
completo (133/134/135 permanecem `verified` — infra).

**`/spec-implement 134` + `/spec-implement 135 (T0)` (2026-08-17): Specs 134–135
implementadas (135 parcial)** — detalhe nos task.md:
**134** (RabbitMQ) — pom `spring-boot-starter-amqp`; `RabbitProperties`/`RabbitConfig`
(opt-in `FOXCASH_RABBIT_ENABLED=false`; exchange direct + fila com DLQ +
bindings); `OutboxRelay` (`@TransactionalEventListener AFTER_COMMIT` no
`OutboxRecordedEvent` emitido pelo `WebhookEventPublisher` — publica após o
commit; **fail-safe ADR-010**: broker down → mensagem fica no outbox DB e o
worker entrega); `OutboxConsumer` (`@RabbitListener` — busca no DB por
`findById` novo no port, entrega via `NotificationProcessor`, falha → retry e
após `max-attempts` → DLQ); modo dreno (`FOXCASH_NOTIFICATION_WORKER_ENABLED`);
métricas `foxcash_outbox_published_total`/`_consumed_total`/`_dlq_total`; `mvn
test` **2069/0/0** (11 testes novos); T5.1 (Testcontainers) + benchmark p95<5s
pendentes de Docker/CI (sem broker local).
**135** (DR) — **DR drill real**: backup 477 KB via `ops/backup.sh` (artefato
validado C5) → staging vazio → `pg_restore` exit 0 (0.646s) → **54 tabelas / 66
migrations até V77 / dados idênticos ao primário** (61 merchants, 123 outbox,
113 ledger) → `flyway:validate` no staging `Successfully validated 66
migrations` (app aceita o banco restaurado — C1 provado); T1/T4 já feitos
(2026-08-16); **RPO decidido 2026-08-17: ≤ 4h (backup 6/6h — cron
`30 2,8,14,20 * * *` + dump com hora, scripts validados); RTO medido 0.646s**;
**pendentes (infra)**: backup fora da região (T2.2), multi-região
Railway/Cloudflare (T3).
**133** (PgBouncer): **pendente de infra** — benchmark exige loadgen + réplicas
Railway (pool + docs feitos; benchmark 8/16 + PgBouncer = operação de
produção). Programa Nota 10: 130/131/132/136/137/138 `audited`, 134
`implemented`, 135 `in-progress`, 133 `verified` (infra).

**`/spec-implement 136` (2026-08-16): Spec 136 `implemented`** — refactor de god
classes. **T0**: contrato congelado ANTES (`ExceptionMappingContractTest`, 87
cases — status + code por exceção; baseline 3/3 verde). **T1**: `GlobalExceptionHandler`
637 → **89 linhas** (orquestrador fino + fallback genérico, `@Order(LOWEST_PRECEDENCE)`);
6 especialistas `handler/*` (Transaction 8, PixProvider 1, Merchant 21, Store 24,
Auth 11, Security 4) com `@Order(HIGHEST_PRECEDENCE)`; 6 testes isolados
(`*HandlerTest`, 69 testes — C4); contrato verde DEPOIS com a tabela byte-idêntica
(só o roteamento aponta aos especialistas — prova comportamental C3). **T2**:
`PixTransaction` avaliado com evidência e **MANTIDO** (overloads aditivos =
evolução, não coesão perdida; extrair value objects não reduziria complexidade
real — KISS, opção "documentar e manter" do C2; L20 registrada). **T3**: `mvn
test` **2021/0/0** (1995 + 26 novos) + contexto smoke 2/2 (boot com os advices
novos) + métrica `nyx_http_5xx_total` preservada (teste dedicado). Detalhe no
task.md §T0–T3.

**`/spec-implement 137` (2026-08-16): Spec 137 `implemented`** — camada de
testes de integração reais. **T0**: Testcontainers no pom (junit-jupiter +
postgresql — BOM 3.5.16; **nota**: não existe módulo `org.testcontainers:redis`
— Redis via `GenericContainer`); `IntegrationTestBase` (`@SpringBootTest(RANDOM_PORT)`
+ perfil dev + Postgres 18 real via Testcontainers com fallback para Postgres
nativo — padrão Spec 68; `spring.task.scheduling.enabled=false` p/ determinismo;
grupo `@Tag("integration")` excluído do default + profile `integration-tests`).
**T1**: 5 fluxos críticos — `PixChargeFlowIntegrationTest` (cobrança → idempotência
→ webhook PAID → wallet 150.00), `AuthRbacIntegrationTest` (login/token/401/403
master + ROLE_ADMIN positiva — determinística, sem depender do seed de master),
`WebhookHmacIntegrationTest` (HMAC ausente/inválido → 401, válido → passa o gate),
`RateLimitRedisIntegrationTest` (429 com Redis real) e `RedisFailOpenIntegrationTest`
(fail-open ADR-010 com Redis derrubado — container próprio, contexto separado).
**T2**: job **`integration`** no CI (ubuntu-latest com Docker → Testcontainers
reais; falha se quebrar). **T3**: sem regressão de cobertura (integração fora do
default; especialistas da Spec 136 100% cobertos — 573 instruções 0 missed);
tempo documentado (unit ~22s; integração seletiva ~15s local / boot+1º pull no
CI). **Validação local (sem Docker): 5/5 não-Redis verdes**; Redis validado no CI.
Nota pré-existente (Spec 132): gate BRANCH do JaCoCo (mín 80%) já falha no HEAD
(0.73) — INSTRUCTION/LINE ≥ 85% passam; follow-up registrado. Detalhe no task.md
§T0–T3.

**`/spec-audit 136` (2026-08-16): Spec 136 `audited`** — **5/5 critérios OK, 0
achados abertos** (detalhe no task.md §Auditoria). Verificações contra o código
real: `GlobalExceptionHandler` **113 linhas** (≤ 150 — orquestrador fino) com 7
handlers genéricos/framework + fallback 500 com métrica, `@Order(LOWEST_PRECEDENCE)`;
6 especialistas `handler/*` com `@Order(HIGHEST_PRECEDENCE)` (Transaction 8,
PixProvider 1, Merchant 21, Store 24, Auth 11, Security 4 = 69 domínio + 7
genérico = 76); `PixTransaction` avaliado e MANTIDO (C2 — L20); contrato
`ExceptionMappingContractTest` **3/3** (87 cases) byte-idêntico pós-refactor
(C3); 6 `*HandlerTest` = **69 testes, 0 falhas** (C4); métrica
`nyx_http_5xx_total{route}` preservada no `handleUnexpected` + teste dedicado
(C5). Suíte completa `mvn test` EXIT 0. Fim do ciclo SDD.

**`/spec-audit 137` (2026-08-16): Spec 137 `audited`** — **5/5 critérios OK, 0
achados abertos** (detalhe no task.md §Auditoria). Verificações: Testcontainers
no pom + `IntegrationTestBase` (fallback nativo, C1); fluxos críticos no caminho
real — local sem Docker `PixChargeFlow` 1/1, `AuthRbac` 1/1, `WebhookHmac` 3/3
(boot real: Flyway 64 migrations, V75), Redis com assumption → CI (C2); grupo
separado `excludedGroups=context,integration` — cobertura inalterada (C3); job
`integration` no CI com Docker (C4); seletiva 5 testes, tempo documentado (C5).
Fim do ciclo SDD.

**`/spec-implement 131` (2026-08-16): Spec 131 `implemented`** — segurança de
runtime. **T0** (commit `caace79`): exclusão do `UserDetailsServiceAutoConfiguration`
no `application.yml` — elimina o fallback que logava `Using generated security
password: <uuid>` no boot de produção (deploy `94861317`, 2 ocorrências);
`bootLogDoesNotContainGeneratedSecurityPassword` captura o log do boot e asserta
a ausência (T0.3). **T1/T2** (já no código): `SecurityConfig` — `/actuator/health`
público, `/actuator/**` ROLE_ADMIN, headers (nosniff, frame DENY, CSP
`default-src 'none'`, Referrer-Policy, HSTS condicional). **T3**: `SensitiveDataLoggingFilterTest`
9/9 (máscara apiKey/password/token/secret/CPF/CNPJ). **T4.1**: `mvn test` completo
EXIT 0. **T4.2**: `deploy/railway.md` §2d documenta a decisão (por que a senha
não aparece mais no boot). **Achado resolvido nesta sessão** (commit `b30ec9f`):
`SecurityRuntimeSecurityTest` 5/5 — o `actuatorPrometheusAllowedForAdminRole`
falhava (404 em vez de 200) porque o **spring-boot-test-autoconfigure 3.5.16**
desliga os exporters de métrica por default em `@SpringBootTest`
(`DisableObservabilityContextCustomizer` → `management.defaults.metrics.export.enabled=false`);
fix: re-enable do export do prometheus no contexto do teste
(`management.prometheus.metrics.export.enabled=true` — teste de segurança, não
de métricas). Pendente para o /spec-audit: varredura dos logs de produção (T3.2,
janela 24h) após o deploy.

**`/spec-implement 138` (2026-08-16): Spec 138 `implemented`** — documentação
sincronizada com o código + verificação mecânica no CI. **T0 (inventário
real):** das 30 specs `verified`, apenas **5 têm código** (13/75/78/97/98) —
as specs 101–119 do Gap 4 são `verified` legítimas, **sem código** no
`src/`/`portal/`/`console/` (a evidência-base da spec, "24 specs com código",
estava incorreta e foi corrigida nos docs). **T1:** frontmatter das 5 specs →
`implemented` (+131, que ficara pendente). **T2:** **64 linhas** da tabela do
roadmap com status desatualizado vs frontmatter sincronizadas (ex.: Specs
1–15 `planned` com specs já `audited`; 62/65 idem) — as tabelas passam a
refletir o status atual, as entradas detalhadas continuam o histórico. **T3:**
`scripts/check-docs-consistency.sh` novo com 3 regras — (A) tabela do roadmap
== frontmatter (resolvido pelo DIR linkado, casos 48/49); (B) spec `verified`
com código → falha; (C) requirements.md de spec implemented/audited citando
classe `.java` inexistente → falha (design/task não validados — são planos);
allowlist `scripts/check-docs-consistency.allowlist` (vazia — zero exceções).
Job `docs` no `ci.yml` roda o script e **falha o build**. **T4:** arch.md
(jornadas = registro de design Gap 4), adr.md (ADR-020 aceito) e vision.md
(V98) verificados consistentes. **T5:** `mvn test` verde, zero mudança de
comportamento (só docs + script de validação). Fim do ciclo SDD — pronto para
`/spec-audit 138`.

## Fase 42 — Escopo do perfil de operação + lente de contexto Bot/Gateway (decisão do usuário 2026-08-16)

Descrição de produto (decisão do usuário 2026-08-16): o perfil de operação
**nasce com escopo definido** — **Geral** (rota + frota de bots), **Somente Bot**
(frota de bots, rota sempre por risco) ou **Somente Gateway** (rota de gateway,
sem frota) — e o painel deixa de ser "tamanho único": um **seletor de contexto
de lente** (Bot | Gateway) aparece no shell **antes** do seletor de perfil, e
cada lente expõe **apenas os perfis do escopo relacionado** (lente Bot →
GENERAL + BOT_ONLY; lente Gateway → GENERAL + GATEWAY_ONLY). O "criar perfil"
passa a ser um **modal limpo**: nome + **Tipo de Operação** (radio: 🤖 Somente
Bot / 🌐 Somente Gateway / ⚡ Completo, cada um com descrição de uma linha),
**sem campos condicionais** — rota e frota são atribuídas depois, no painel do
contexto da lente — com **nomes amigáveis de rota** (PADRAO → "Padrão (por
risco)", pagarme → "Pagar.me", asaas → "Asaas") e **feedback visual** na troca
de perfil. Contexto do usuário: os specs 122/123/124 (`audited`) entregaram o
mecanismo de perfis e o filtro por `profile_id`, mas na fase 1 o seletor é
"invisível" (troca de perfil não muda nada na tela, a rota mostra chaves
internas `pagarme`/`PADRAO`, o elo com a frota de bots não aparece no seletor,
e não existe endpoint para atribuir bots a um perfil — a Spec 122 deixou isso
explicitamente para "fase 2"). O escopo + lente resolvem a clareza do painel
**sem quebrar o modelo auditado**: o perfil continua sendo UMA entidade (rota +
frota), o que muda é (a) o campo `scope` (migration **V76** livre; V75 é a
última) que classifica o perfil, (b) a **lente** como estado de UI (localStorage)
que filtra os perfis exibidos e o que cada perfil mostra, (c) o endpoint novo
`PUT /api/v1/portal/profile/{id}/bots` para atribuir a frota. Fases futuras
registradas: o escopo enriquece o filtro rígido da Spec 124 (perfis BOT_ONLY só
escoçam módulos de bot; GATEWAY_ONLY os de cobrança).

Decisões do usuário (2026-08-16, via pergunta no DsCode):
- **Escopo no nascimento** — "o perfil nasça com um escopo definido (Geral /
  Bot-Only / Gateway-Only)"; o painel passa a vestir a operação como o lojista
  precisa.
- **Lente baseada no escopo** — seletor de contexto (Bot/Gateway) ANTES do
  seletor de perfil; perfis da lente = escopos relacionados.
- **Criar perfil com especificações** — modal limpo: nome + **Tipo de Operação**
  (radio: 🤖 Somente Bot / 🌐 Somente Gateway / ⚡ Completo, com descrição);
  rota/frota atribuídas depois no painel da lente (refinado pelo usuário em
  2026-08-16, mock do modal).
- **Execução via spec SDD nova** — este item (Spec 139), seguindo o fluxo.

| # | Spec (proposta) | Status | Referência |
|---|---|---|---|
| 139 | Escopo do perfil de operação (Geral/Bot-Only/Gateway-Only) + lente de contexto Bot/Gateway no portal — migration **V76** (`merchant_profiles.scope`, CHECK + backfill GENERAL); `POST/PATCH /api/v1/portal/profile` aceitam `scope` (400 `invalid_scope`; BOT_ONLY + rota → 400 `route_not_allowed_for_scope`; GATEWAY_ONLY → frota desanexada); endpoint novo `PUT /api/v1/portal/profile/{id}/bots {botIds}` (atribui frota; GATEWAY_ONLY → 400 `bots_not_allowed_for_scope`); `RoutingPolicyResolver` com defesa BOT_ONLY (nunca override); auditoria += `PROFILE_BOTS_CHANGED`; **lente Bot/Gateway** no `ProfileSelector` (2 níveis: contexto → perfil), `lens` persistido (`foxcash.portal.lens`), perfis filtrados por escopo, criar perfil (modal limpo: nome + Tipo de Operação 🤖/🌐/⚡), nomes amigáveis de rota, **dica flutuante no dropdown de perfis** (hover/foco → resumo do perfil: escopo + bots + rota), feedback visual na troca; `SecurityPage`/`BotsPage` escopo-aware (**`audited` 2026-08-17 — 12/12 critérios OK, 0 achados abertos**: `ProfileScope`/`MerchantProfile.scope` + service `create/update` com escopo + `replaceBots`, controller `PUT /{id}/bots`, resolver BOT_ONLY, 3 exceções novas, auditoria `PROFILE_BOTS_CHANGED`; portal: lente + `visibleProfiles` (localStorage), seletor 2 níveis + tooltip, modal limpo, dialog gerenciar bots, banner; `mvn test` **2050/0/0** + builds portal/console limpos + docs-consistency OK; E2E `profile-scope-lens.spec.js` **1/1 verde** (executado 2026-08-16 — §E2E no task.md) com 1 bug de a11y corrigido; desvios registrados no task.md) | audited | [requirements](specs/139-profile-scope-lens/requirements.md) · [design](specs/139-profile-scope-lens/design.md) · [task](specs/139-profile-scope-lens/task.md) · Visão V99 |
| 140 | **Rebranding TrevoPay/FoxCash → FoxCash** — package Java `com.nyxcash` → `com.foxcash` (1.210 arquivos), classes `Nyx*` → `Fox*`, `TrevoPayApplication` → `FoxCashApplication`, pom/Dockerfile/CI, config `nyxcash:` → `foxcash:` + env `NYX_*` → `FOXCASH_*` (112), métricas `nyx_*` → `foxcash_*`, Redis `nyx:*` → `foxcash:*`, jobs `nyxcash-*` → `foxcash-*`, headers `X-NyxCash-*` → `X-FoxCash-*`, docs (70) + frontend/E2E (46); banco **intocado** (nenhuma tabela com prefixo `nyx_` — zero migration); branch `rebrand-foxcash`; **`audited` 2026-08-17 — Fases 0-6 concluídas + audit 5/5 OK, 0 achados**: `mvn test` 2038/0/0, context-tests 7/0/0, builds portal+console limpos, bots 48/48, operator-bot 26/26, **E2E 203/0/3**; **Fase 6 (Railway) concluída**: domínio `foxcash.example.com` (Cloudflare: zona ativa, SSL Full strict, WAF, rate limit, Turnstile) + `api.foxcash.example.com` cert VALID, 27 vars `FOXCASH_*` (0 `NYX_*`), deploy `34145e3a` SUCCESS, rede interna `foxcash.railway.internal`, TRUNCATE + Flyway V76, webhooks nada a fazer, domínio antigo `api.foxcash.example.com` removido (NXDOMAIN); detalhe na nota acima | audited | [requirements](specs/140-rebranding-foxcash/requirements.md) · [design](specs/140-rebranding-foxcash/design.md) · [task](specs/140-rebranding-foxcash/task.md) · [plano](rebranding-foxcash.md) · Visão V100 |
| 141 | **Device fingerprint caseiro no portal (cadastro + login)** — sinal de risco PASSIVO (nunca bloqueia): `portal/src/fingerprint.ts` coleta sinais do navegador (canvas, WebGL, fontes, UA, timezone, resolução, navigator) e reduz a **SHA-256 hex (64)** via Web Crypto; campo `fingerprintHash` **opcional** em `POST /api/v1/merchants` (`CreateMerchantRequest`) e `POST /api/v1/merchant-auth/login` (`MerchantLoginRequest`), propagado via `RegisterMerchantUseCase.Command`/`MerchantPortalAuthUseCase.LoginCommand` (compat 2/3-args); `DeviceFingerprintService` valida 64-hex e grava (best-effort — hash ausente/malformado/off nunca quebra o request); migration **V77**: `device_fingerprints` (hash PK, janelas, `seen_count`, `distinct_merchants`) + `device_fingerprint_merchants` (associação, chave composta), **upsert atômico `ON CONFLICT`** (sem lost update); auditoria Spec 41 ganha `{fingerprint:{hash,distinctMerchants}}` nos details de `MERCHANT_REGISTERED`/`MERCHANT_LOGIN`; config `foxcash.fingerprint.enabled` (`FingerprintProperties`, env `FOXCASH_FINGERPRINT_ENABLED`); **alerta `FINGERPRINT_DEVICE_SHARED`** no outbox → webhook do operador (Telegram/Discord) quando o mesmo hash atinge `alert-threshold` contas (default 2, throttle 300s, REQUIRES_NEW, fail-safe — padrão HoneypotAlertPublisher); **flag no Master Console** `fingerprintDistinctMerchants` no `GET /api/v1/master/merchants/{id}/panel` + badge "⚠ N contas no mesmo dispositivo" (**`audited` 2026-08-17 — 5/5 critérios OK, 1 achado corrigido (C5: teste de integração do upsert atômico — `DeviceFingerprintIntegrationTest` 1/1, padrão Spec 137; `findByIdFingerprintHash` no JpaRepository), 0 abertos; `mvn test` 2050/0/0 + integração 1/1 + builds portal/console limpos; webhook do operador validado ao vivo HTTP 204**) | audited | [requirements](specs/141-fingerprint-portal/requirements.md) · [design](specs/141-fingerprint-portal/design.md) · [task](specs/141-fingerprint-portal/task.md) · Visão V101 |
| 142 | **Tela de cadastro do lojista no portal** — `RegisterPage.tsx` (novo) em `/register`: formulário **nome** (≤120) / **e-mail** (≤255) / **senha** (8–72) + checkbox "Li e concordo com os termos de uso e condições" (botão **Criar conta** desabilitado sem o aceite; link para `docs/politicas-uso.md`); consome `POST /api/v1/merchants` (Spec 39 — rota pública, `{auth:false}`) + Turnstile (Spec 63, widget invisível via site key) + fingerprint (Spec 141); sucesso exibe a **API key UMA única vez** (Spec 12) com botão copiar + navega para `/login`; rota `/register` no `App.tsx` + link "Criar conta" no `LoginPage`; **fix**: `/register` nas rotas públicas da SPA (`SecurityConfig.spaPublicPaths` + `PortalSpaController.forwardToIndex` — acesso direto/F5 servia 401 JSON); E2E `register-ui.spec.js` (2 cenários: cadastro completo + link no login) (**`audited` 2026-08-17 — 8/8 critérios OK, 0 achados abertos**: C1 formulário com os limites da Spec 39; C2 termos obrigatórios (botão disabled + link politicas-uso.md); C3 submit `{auth:false}` + Turnstile + fingerprint + 409→ErrorBanner; C4 sucesso com API key 1x (copiar + Fazer login); C5 rota + link no login; C6 fix de rota pública verificado AO VIVO (`GET /register` → 200 HTML, antes 401); C7 a11y/UX; C8 E2E `register-ui.spec.js` **2/2 passaram** + import do `@playwright/test` corrigido; docs: referência da Spec 63 corrigida (`63-edge-waf-bot-mitigation`) + README rotas públicas += `/register`; `docs-consistency` OK) | audited | [requirements](specs/142-cadastro-ui-portal/requirements.md) · [design](specs/142-cadastro-ui-portal/design.md) · [task](specs/142-cadastro-ui-portal/task.md) · Visão V102 |

**`/spec-audit 142` (2026-08-17): Spec 142 `audited`** — **8/8 critérios OK, 0 achados abertos** (detalhe no task.md §Auditoria): tela de cadastro do lojista no portal (Spec 39 UI) — `RegisterPage.tsx` + rota `/register` + link no login; fix de rota pública da SPA (`/register` em `SecurityConfig.spaPublicPaths` + `PortalSpaController` — acesso direto/F5 servia 401); E2E 2/2 contra a app real; docs-consistency OK; commit `5c18e4f` publicado.

**`/spec-verify` + `/spec-implement` (2026-08-16): Specs 139 backend + frontend
`implemented`** — migration **V76** (`merchant_profiles.scope` NOT NULL DEFAULT
'GENERAL' + CHECK); `ProfileScope`/`MerchantProfile.scope` + `scopeOf`
projection; `OperationProfileService` `create`/`update` com escopo +
`replaceBots` (PUT /{id}/bots); controller; resolver BOT_ONLY (nunca override);
3 exceções novas no `ExceptionMappingContractTest`; auditoria
`PROFILE_BOTS_CHANGED`; portal: lente `lens`/`setLens` + `visibleProfiles`
(localStorage `foxcash.portal.lens`), seletor 2 níveis (segmented + menu
customizado + tooltip D5.2), modal limpo de criar perfil, dialog gerenciar bots
(PUT), banner transitório, `SecurityPage` escopo-aware; `mvn test` **2038/0/0**;
desvios no task.md. E2E `profile-scope-lens.spec.js` **1/1 verde** executado
(§E2E do task.md — banco frio validou V76 em cold-start; 1 bug real de a11y
corrigido no `ProfileSelector`).

**`/spec-audit 139` (2026-08-17): Spec 139 `audited`** — **12/12 critérios OK, 0
achados abertos** (detalhe no task.md §Auditoria): C1 migration V76 validada em
cold-start; C2–C5 service/controller (14 testes de escopo/frota + 3 exceções no
contract test); C6 resolver BOT_ONLY (2 testes, regressão Spec 91 intacta);
C7–C11 frontend (lens, seletor 2 níveis, tooltip aria-describedby, modal limpo,
banner, SecurityPage escopo-aware — confirmado no código + E2E 1/1); C12 `mvn
test` **2050/0/0** re-executado + builds portal (70) e console (67) limpos +
docs-consistency OK. Fim do ciclo SDD — feature LIVE.

**`/spec-verify` + `/spec-implement` + `/spec-audit 141` (2026-08-17): Spec 141
`audited`** — **5/5 critérios OK, 1 achado corrigido, 0 abertos** (detalhe no
task.md §Auditoria). Verificações contra o código real: **C1** `mvn test`
**2050/0/0** re-executado; **C2** `npm run build` portal (70 módulos) + console
(67) limpos — `fingerprint.ts` compila, `LoginPage` envia o campo; **C3**
`DeviceFingerprintServiceTest` 6/6 (hash válido normaliza minúsculas;
null/blank/malformado → `Optional.empty()` sem gravação); **C4** kill-switch
(`enabled=false` → serviço e alerta não tocam o banco; zero regressão em
cadastro/login — testes com fingerprint ausente → `Optional.empty()`); **C5**
**achado da auditoria — sem teste automatizado do upsert atômico** (o teste de
serviço mocka o repositório): corrigido com `DeviceFingerprintIntegrationTest`
novo (padrão Spec 137, `@Tag("integration")`, fora do default) — 2 cadastros +
1 login com o MESMO hash no caminho real HTTP → JPA → Postgres → `seen_count=3`,
`distinct_merchants=2`, 2 associações (PK composta sem duplicar), alerta
`FINGERPRINT_DEVICE_SHARED` no outbox (`{fingerprintHash, distinctMerchants:2}`),
requests não bloqueados — **1/1 verde** (Postgres nativo, fallback sem Docker);
`DeviceFingerprintJpaRepository` ganhou `findByIdFingerprintHash` (`@Query`
explícita — derived query com `@EmbeddedId` não resolve). Verificação ao vivo
adicional: webhook do operador validado ponta a ponta (HTTP 204, corpo idêntico
ao `discordBody()` do `HttpMerchantNotifier`). docs-consistency OK. Fim do ciclo
SDD — feature LIVE.

**Adaptação às specs pendentes de back/front (2026-08-16):** as specs que ainda
serão implementadas (101–119, todas `verified`) foram **adaptadas para não
ignorar a Spec 139** — cada `requirements.md` ganhou referência explícita no
bloco `## Dependências` (escopo `merchant_profiles` V76, lente Bot/Gateway
`nyxcash.portal.lens`, `PUT /api/v1/portal/profile/{id}/bots`, `X-Profile-ID`,
defesa `BOT_ONLY` do `RoutingPolicyResolver`), e os `design.md`/`task.md` das
specs com interação profunda (102 shell UI, 103/105 dashboard/financeiro,
107/108 gateways, 115/116 checkout, 117 automações) ganharam seções/itens
próprios ("Não ignorar a Spec 139"). A **Spec 129** (landing/cadastro,
`planned`) deve notar que o cadastro cria o perfil default `GENERAL` (Specs
122/139 — V69/V76). **Próxima migration livre: V77.**

## Fase 32 — Modal de termos de uso no cadastro (decisão do usuário 2026-08-17)

| # | Name | Status | References |
|---|---|---|---|
| 143 | **Modal de termos de uso no cadastro do lojista** — o aceite dos termos no `RegisterPage.tsx` (Spec 142) passa de link em nova aba para **modal na MESMA tela**: `portal/src/terms.ts` (novo) renderiza `docs/politicas-uso.md` (**fonte única**, import `?raw` embutido no bundle) com um **mini-renderizador de markdown seguro** — `escapeHtml` antes do markdown (o documento nunca renderiza HTML próprio), subset dos constructos usados (títulos/negrito/listas aninhadas com continuação recuada/hr/tabelas/parágrafos), **sem lib de markdown** (Fase 29); trigger `.terms-link` com `preventDefault`+`stopPropagation` (não alterna o checkbox); modal `modal-wide` com `.terms-body` (scroll), `useDialogFocus` (focus trap/Escape/restauração — Spec 73), `role="dialog"` `aria-modal` `aria-labelledby` e botão **Entendi**; `vite.config.ts` com `server.fs.allow: ['..']` (dev lê o `?raw` fora do root); aceite continua controlando o botão **Criar conta**; zero mudança de backend (**`audited` 2026-08-17 — 6/6 critérios OK, 0 achados abertos** — detalhe no task.md §Auditoria; fecha o gap de numeração: a Spec 143 estava referenciada em 6 comentários do código sem registro no roadmap — padrão Spec 138) | audited | [requirements](specs/143-termos-cadastro/requirements.md) · [design](specs/143-termos-cadastro/design.md) · [task](specs/143-termos-cadastro/task.md) · Visão V104 |

**`/spec-plan` + `/spec-new 143` + `/spec-verify` + `/spec-implement` +
`/spec-audit 143` (2026-08-17): Spec 143 `audited`** — modal de termos de uso
no cadastro (Spec 142 UI). Implementação já existia no código (referenciada
como "Spec 143" em `RegisterPage.tsx`, `terms.ts`, `styles.css`,
`vite.config.ts`) mas a spec **não estava registrada** no roadmap — o fluxo
SDD formaliza o entregue e **fecha o gap de numeração** apontado na auditoria
da Spec 144. Verificação 1:1: renderizador seguro (`escapeHtml`), fonte única
via `?raw`, modal acessível (`useDialogFocus` + `role="dialog"`), checkbox
intacto (`preventDefault`/`stopPropagation`), `fs.allow` no vite e build limpo
— 6/6 critérios OK, 0 achados abertos (detalhe no task.md §Auditoria).
`scripts/check-docs-consistency.sh` → OK. Fim do ciclo SDD — feature LIVE.

## Fase 33 — Central de atendimento estilo Telegram no CRM (decisão do usuário 2026-08-17)

| # | Name | Status | References |
|---|---|---|---|
| 144 | **Central de atendimento estilo Telegram no CRM do portal** — a tela `/crm` troca a **tabela + modal de Live Chat** por uma **central de 2 painéis**: **sidebar de conversas** (header "Chats" + total, busca, item com avatar/iniciais, nome, @username/telefone/ID, bot 🤖, data da última interação, não lida, badge de status, dot laranja de handoff HUMAN, barra azul de seleção, paginação server-side) + **painel direito** (cabeçalho com avatar, `ID · via bot`, **Plano** e **Última interação** (Spec 119), status, **Pausar/Retomar bot** (Spec 90), **som** com beep/mute, navegação `‹ pos/total ›`, **Fechar**; histórico em bolhas por remetente com mídia e **✓/✓✓ de leitura**; rodapé de envio com aviso para lead **BLOQUEADO**); **CSS puro** no tema claro (sem Tailwind/lib de ícones — Fase 29), **zero contrato HTTP novo**; SSE no nível da página com **correção da não lida do lead certo** (bug pré-existente incrementava o lead aberto) + lista local sincronizada em tempo real; `?lead={id}` (Spec 102) e handoff/read/beep preservados; painel usa classes `crm-panel-*` (não colide com `.crm-chat-header`/`.crm-chat-actions`/`.crm-chat-sub` compartilhadas por Comunidade/Webhooks); E2E `crm.spec.js` (3 cenários), `portal-shell-ui.spec.js` e `portal-leves.spec.js` atualizados; builds portal + console limpos (estáticos restaurados — ordem portal → console, achado Spec 73) (**`audited` 2026-08-17 — 9/9 critérios OK, 0 achados abertos** — detalhe no task.md §Auditoria) | audited | [requirements](specs/144-crm-central-telegram/requirements.md) · [design](specs/144-crm-central-telegram/design.md) · [task](specs/144-crm-central-telegram/task.md) · Visão V103 |

**`/spec-plan` + `/spec-new 144` + `/spec-verify` + `/spec-implement` +
`/spec-audit 144` (2026-08-17): Spec 144 `audited`** — central de atendimento
estilo Telegram no CRM do portal (Spec 84 UI). Implementação concluída na
sessão (CrmPage reescrita + CSS + E2E + builds) e formalizada pelo ciclo SDD
completo: **9/9 critérios OK, 0 achados abertos** (detalhe no task.md
§Auditoria). Correções de valor no caminho: (1) o SSE de não lidas
incrementava o lead **ABERTO** em vez do lead certo (bug pré-existente —
corrigido); (2) a lista local passou a refletir não lidas/handoff/status em
tempo real (antes `upsertLead` mapeava uma lista nunca populada); (3) o painel
usa `crm-panel-*` para não alterar o visual de Comunidade/Webhooks.
**Observação de numeração:** a Spec **143** (modal de termos do cadastro) está
referenciada em comentários do código (`RegisterPage.tsx`, `terms.ts`,
`vite.config.ts`) mas **não registrada** no roadmap — gap de sincronia do tipo
que a Spec 138 audita; registrar em ciclo próprio. Fim do ciclo SDD — feature
LIVE.

## Fase 34 — Diferenciação competitiva: subcontas/splits, saque e webhooks (gap NyxCash 2026-08-20)

| # | Name | Status | References |
|---|---|---|---|
| 145 | **Subcontas e splits (backend)** — domínio `Shop` (subconta com saldo próprio, `external_ref` único por lojista, auto-criação no 1º uso), regras de split na cobrança (destino `shop_id`/`parent` + `amount`/`percent`, máx. 10; cálculo fixos → percentuais → remanescente), creditação idempotente dos destinos no webhook de pagamento com ledger por subconta (Spec 27) e leitura interna de saldo; **exposição REST/portal = spec futura** | verified | [requirements](specs/145-subcontas-splits/requirements.md) · [design](specs/145-subcontas-splits/design.md) · [task](specs/145-subcontas-splits/task.md) · Visão V106 |
| 146 | **Saque: cooldown por usuário final** — trava anti-abuso de N s (default 5 min) scoped por (lojista, usuário final = `sub_shop_id` ou hash da chave Pix), `429 too_many_requests` com `cooldown_seconds`, config `foxcash.withdrawal.cooldown.*` (memory/redis, kill-switch), fail-open no store; replay idempotente (Spec 147) isento | verified | [requirements](specs/146-saque-cooldown-usuario/requirements.md) · [design](specs/146-saque-cooldown-usuario/design.md) · [task](specs/146-saque-cooldown-usuario/task.md) · Visão V107 |
| 147 | **Saque: correlation_id + gateway_e2eid** — chave de idempotência explícita no contrato (replay retorna o saque existente `idempotent: true`; `409` por chave em uso; índice único por lojista — migration V78), persistência/exposição do End-to-End ID do PIX e do `failure_reason` na listagem | verified | [requirements](specs/147-saque-idempotencia-rastreio/requirements.md) · [design](specs/147-saque-idempotencia-rastreio/design.md) · [task](specs/147-saque-idempotencia-rastreio/task.md) · Visão V108 |
| 148 | **Webhooks outbound v2** — header `X-FoxCash-Event` (tipo do evento) + assinatura com timestamp `t=<unix>,v1=<hex>` (HMAC sobre `"<t>.<body>"`, janela anti-replay documentada) + retry policy pública (tentativas/backoff/4xx encerra) fiel ao outbox (Specs 35/138); versionamento v1/v2 por lojista com janela de transição (**`implemented` 2026-08-21 — `WebhookSignatureV2` (`t=,v1=`, HMAC de `"<t>.<rawBody>"`), `X-FoxCash-Event` com `headerValue()` (`sale.paid`/`sale.refunded`), `signature_version` no `MerchantWebhook` + migration V80 (default `v2`), v1 legado por lojista, 4xx → `PermanentWebhookDeliveryException` → FAILED sem backoff no `NotificationProcessor`, `docs/webhooks.md` com retry policy fiel (Spec 138); `mvn test` **2123/0/0**; **`audited` 2026-08-21 — `/spec-audit` 5/5 critérios OK, 0 achados abertos; achados da auditoria corrigidos: F1 (teste explícito de body-tamper no `WebhookSignatureV2Test` — critério 2d) e F2 (whitelist `v1`/`v2` no `WebhookManagementService.update` — 400 para valor inválido, antes persistiria como v2 silencioso); `mvn test` **2125/0/0** + `check-docs-consistency.sh` OK**) | audited | [requirements](specs/148-webhooks-outbound-v2/requirements.md) · [design](specs/148-webhooks-outbound-v2/design.md) · [task](specs/148-webhooks-outbound-v2/task.md) · Visão V109 |
| 149 | **Preview financeiro da cobrança** — resposta do create passa a incluir `fee`/`feePercent`/`netAmount` (fonte única `PricingCalculator`, paridade com a fatura Specs 8/22) e `splitsPreview` opcional (hook da Spec 145) — transparência antes do pagamento | verified | [requirements](specs/149-preview-financeiro-cobranca/requirements.md) · [design](specs/149-preview-financeiro-cobranca/design.md) · [task](specs/149-preview-financeiro-cobranca/task.md) · Visão V110 |

**Origem (2026-08-20):** gap analysis contra a API pública do concorrente
NyxCash. **Fora de escopo por decisão registrada:** KYC (Spec 17 `discarded` —
sem dados do comprador) e dashboard agregado (já coberto pelas Specs 21/22/105).

## Fase 35 — Integração bot ↔ gateway (auditoria 2026-08-20)

| # | Name | Status | References |
|---|---|---|---|
| 150 | **Bot ↔ Gateway — atribuição, provas e webhook v2** — o bot vende/recebe pagamento corretamente, mas o payload do `purchase` omite campos que o gateway já aceita: corrige `SalesMachine.openSale()` para enviar `botId` (restaura funil/métricas por bot — Spec 75/113 — e o perfil de operação via resolução por `botId` — Spec 124), `termsAccepted` (grava `termsAcceptedAt` — prova de aceite, Spec 125) e `buyerIp`/`buyerDevice` opcionais; prepara o receiver do bot para o webhook v2 (Spec 148 — validar `t=,v1=` com janela anti-replay, mantendo o legado na transição); documenta os dois fluxos de criação (portal vs bot) para o link de pagamento futuro. **Sem mudança no backend**; **bot gateway-agnóstico** (decisão 2026-08-20: vende via FoxCash ou concorrente — integração por adapter `GatewayClient`, payload/assinatura por gateway, D7) (**`implemented` 2026-08-20 — `commit 87a227e`: `GatewayClient`/`FoxCashGatewayClient`, `botId`+`termsAccepted` no purchase (env `FOXCASH_BOT_ID`), assinatura v1/v2 no receiver (janela anti-replay 5 min), docs + task.md; typecheck limpo, vitest 61/61, smoke E2E OK, `termsAcceptedAt` gravado confirmado de ponta a ponta; **validado no chat real 2026-08-20**: compra via @TesteFoxCash_bot → transação `APPROVED` com `bot_id` preenchido + `terms_accepted_at` + entrega `DELIVERED` (`SEND_MESSAGE` DONE); **`audited` 2026-08-20 — `/spec-audit` 5/5 critérios OK, 0 achados abertos; evidências: payload (`sales.ts`/`gateway-client`/`foxcash-client`) + funil real no banco (`bot_funnel_events` CHECKOUT_STARTED→PAID por bot) + `resolveProfileId`/`store_bots.profile_id` (Spec 124) + signature v1/v2 (14 casos, janela 5min) + docs README; vitest 61/61, typecheck limpo, validado Telegram + Discord**) | audited | [requirements](specs/150-bot-gateway-integracao/requirements.md) · [design](specs/150-bot-gateway-integracao/design.md) · [task](specs/150-bot-gateway-integracao/task.md) · Visão V111 |

**Origem (2026-08-20):** auditoria da integração bot↔gateway — fluxo de venda
robusto (webhook assinado + dedup + polling 15s + retry), mas com lacunas de
**atribuição e provas** no payload (achados em `bots/core/src/sales.ts` vs
contrato do e2e). **Fora de escopo:** cartão/boleto (PIX-only) e link de
pagamento genérico (item futuro — D5 registra o terreno).

## Fase 36 — Produto: exportação CSV, reembolso pelo lojista e link de pagamento (decisão do usuário 2026-08-20)

| # | Name | Status | References |
|---|---|---|---|
| 151 | **Exportação CSV (vendas e saques)** — endpoints `GET /api/v1/portal/sales/export` e `/withdrawals/export`: CSV em streaming (todas as linhas do filtro, sem paginação), formato planilha BR (UTF-8 BOM, `;`, valores `12,34`), colunas de vendas com taxa e de saques com `gateway_e2eid` (Spec 147) + motivo de falha, chave Pix mascarada; teto `csv-max-rows` (default 100k) → `400 csv_limit_exceeded` | verified | [requirements](specs/151-exportacao-csv/requirements.md) · [design](specs/151-exportacao-csv/design.md) · [task](specs/151-exportacao-csv/task.md) · Visão V112 |
| 152 | **Reembolso pelo lojista (self-service)** — `POST /api/v1/portal/sales/{id}/refund`: estorno de venda `APPROVED` dentro de janela configurável (default 30 dias), confirmação explícita `confirm: true`, débito consistente (Spec 29 — ledger `REFUND_DEBIT`, recebível se saldo negativo Spec 33), guard de chargeback (Spec 16), webhook `SALE_REFUNDED` (Spec 35), auditoria (Spec 41) + step-up opcional (Spec 94); **sem PII do comprador** | verified | [requirements](specs/152-reembolso-lojista/requirements.md) · [design](specs/152-reembolso-lojista/design.md) · [task](specs/152-reembolso-lojista/task.md) · Visão V113 |
| 153 | **Link de pagamento genérico (sem NF)** — `POST /api/v1/portal/payment-links` → URL pública `/pay/{token}` (token aleatório 128 bits): página de pagamento com QR/copia-e-cola/status, reusa o fluxo de cobrança avulsa (Spec 23) + preview financeiro (Spec 149), lista/cancela no portal; **sem nota fiscal** (decisão) e sem PII do comprador; migration V79 | verified | [requirements](specs/153-link-pagamento/requirements.md) · [design](specs/153-link-pagamento/design.md) · [task](specs/153-link-pagamento/task.md) · Visão V114 |

**Origem (2026-08-20):** itens de produto escolhidos do gap "perante gateways" —
② exportação CSV, ③ reembolso pelo lojista, ④ link de pagamento (**sem NF**).
**Fora de escopo:** cartão/boleto e recorrência (PIX-only); OpenAPI/SDK público
e nota fiscal (não escolhidos).

## Fase 37 — Bot assistente do lojista (avaliação de experiência 2026-08-20)

| # | Name | Status | References |
|---|---|---|---|
| 154 | **Bot assistente do lojista (Telegram/Discord)** — assistente de bolso (não replica o portal): canal vinculado por API key + allowlist de chat (migration V80), **notificações push de leitura em 2s** (`✅ R$ 47,50 recebido — Plano Pro (#id)`) com dedup por eventId, prefs por tipo (pago/refund/saque default ON; gerado/expirado OFF) e botão "Ver no portal"; **consultas rápidas** `!saldo`/`!vendas hoje`/`!saque status` via agregados (Specs 21/22/25/14, < 2s) + menus de botões; **ações críticas com confirmação** — saque com 1 botão + TOTP opcional (Spec 94) + cooldown claro (Spec 146), cobrança manual via **link de pagamento** (Spec 153); **controle de ruído** — coalescência de rajada, resumo diário opcional, kill-switch por tipo, nunca notificar erros de integração | verified | [requirements](specs/154-bot-assistente-lojista/requirements.md) · [design](specs/154-bot-assistente-lojista/design.md) · [task](specs/154-bot-assistente-lojista/task.md) · Visão V115 |

**Origem (2026-08-20):** avaliação da experiência de bot como assistente do
lojista (4 dimensões: notificações/consultas/ações/ruído + 9 irritações) —
resolve os pontos levantados reusando outbound (Spec 35/148), agregados
(21/22/25/14), MFA (94), cooldown (146) e link (153). **Não replica o portal**
(decisão de produto); relação com gateway concorrente documentada (o assistente
reporta o que a Fox sabe).

**`/spec-verify` (2026-08-20): Spec 154 `verified`** — critérios checados contra
o código real; **2 achados de precisão corrigidos nos documentos**:
(1) **`WITHDRAWAL_FAILED` não existe no outbound** — o `WebhookEventType` só tem
`WITHDRAWAL_COMPLETED` e o `WithdrawalSettlementService` no caso FAILED publica
apenas auditoria `PlatformAction.WITHDRAWAL_FAILED` (sem webhook); a spec
assumia o evento como existente → registrado como **aditivo** (novo valor no
enum + publicação na mesma transação do FAILED, padrão do COMPLETED — Spec 35),
com task T2.1 expandida e observação no requirements; (2) **prefixo de config
`foxcash.bot.*` → `foxcash.bots.*`** (plural, padrão real do projeto —
`@ConfigurationProperties("foxcash.bots")` no `BotProperties`/yml `foxcash:
bots:`), corrigido em requirements/design/task (`saque-step-up-enabled`,
`coalesce-window`, `BotChannelProperties`). Verificações sem achado: migration
**V80 livre** (última real V79), Visão V115 existe, refs do frontmatter existem
(67/52/35/148/21/22/25/14/94/146/153/150), Spec 94 `audited` com
`MerchantMfaService.verifySecondFactor` reutilizável para o TOTP do saque,
`bots/core`/`bots/discord`/`bots/telegram` presentes (módulo `bots/merchant/`
novo), `X-FoxCash-Event`/`X-FoxCash-Signature` reais no `HttpMerchantNotifier`
(Spec 148 é v2/`t=,v1=` — dependência futura registrada). Dependências
146/148/150/153 estão `verified` (não implementadas) — registradas nas
Dependências; implementação da 154 deve aguardá-las. Pronta para
`/spec-implement`.
