# Decisões de Arquitetura — FoxCash (ADRs)

## ADR-001: Arquitetura hexagonal (ports & adapters)

**Status:** Aceito (implementado no scaffold).

**Contexto:** o domínio de pagamentos Pix precisa evoluir sem acoplamento a
framework (Spring), banco ou provedor externo (PSP), para permitir troca de
provedor e testes unitários sem infraestrutura.

**Decisão:** separar o código em 3 camadas com dependência sempre apontando para
dentro: `domain` (núcleo puro), `application` (use cases) e `infrastructure`
(adapters REST/JPA/PSP). Ports de entrada e saída definidos em `domain/port`.

**Consequências:**
- (+) Domínio testável sem Spring (testes unitários existentes).
- (+) Troca de PSP = novo adapter que implementa `PixProviderClient`.
- (+) Troca de banco = novo adapter JPA; domínio intocado.
- (−) Mais arquivos/mappers (custo de boilerplate aceito).

## ADR-002: Precificação fixa configurável

**Status:** Aceito (implementado no scaffold).

**Contexto:** modelo de negócio FoxCash = R$ 0,50 por transação aprovada +
R$ 69,95 de mensalidade.

**Decisão:** `PricingPlan` (value object) no domínio; valores configuráveis em
`application.yml` (`foxcash.pricing.*`) injetados via `PricingProperties` no
`ApplicationConfig`. O lojista recebe o plano global da plataforma.

**Consequências:**
- (+) Mudança de tarifa = editar YAML/env var, sem tocar no domínio.
- (+) `PricingCalculator` e `InvoiceCalculator` testáveis.
- (−) Sem suporte a planos por-lojista na versão inicial (fora de escopo).

## ADR-003: Criptografia em repouso AES-256/GCM via JPA AttributeConverter

**Status:** Aceito (Spec 4).

**Contexto:** CPF/CNPJ (documentos) são dados sensíveis e devem ficar
criptografados no banco. A aplicação trabalha com o valor em texto puro.

**Decisão:** `CryptoConverter` (JPA `AttributeConverter`) aplicado a
`merchants.document` (até 2026-08-04 também a `pix_transactions.payer_document`,
removido — o gateway não captura dados do comprador). Algoritmo AES-256/GCM
com IV aleatório de 12 bytes; saída versionada `v1:<base64(iv+ciphertext+tag)>`.
Chave derivada via SHA-256 de `NYX_CRYPTO_SECRET` (qualquer tamanho de input,
saída sempre 32 bytes). Cipher por `ThreadLocal` (não é thread-safe).

**Consequências:**
- (+) Confidencialidade + integridade (GCM autentica o ciphertext).
- (+) Ciphertext não-determinístico (IV aleatório) — mais seguro.
- (−) Busca por igualdade no ciphertext é impossível → coluna auxiliar
  `document_hash` (SHA-256) para lookups (ver ADR-004).
- (−) Aumento de tamanho do dado armazenado (~2x) → colunas `VARCHAR(255)`.

## ADR-004: Busca por documento via hash determinístico

**Status:** Aceito (Spec 4).

**Contexto:** o domínio precisa de `findByDocument` (ex.: evitar duplicidade de
cadastro), mas o ciphertext AES-GCM é não-determinístico.

**Decisão:** armazenar `merchants.document_hash` = SHA-256 (hex) do documento
normalizado, com índice único. O adapter/persistência calcula o hash na escrita
e busca por ele na leitura. O hash é uma função unidirecional — não revela o
documento.

**Consequências:**
- (+) Lookup por documento continua exato e indexado.
- (+) Sem risco de vazamento do documento via hash (SHA-256 unidirecional).
- (−) Coluna extra no schema; documento com mesmos dígitos → mesmo hash
  (esperado, é o comportamento desejado para unicidade).

## ADR-005: Provedor de pagamentos (PSP) para o Pix

**Status:** Aceito (Spec 9) — recomendação a **confirmar na homologação** (Spec 10).

**Contexto:** o FoxCash usa o port `PixProviderClient` com um adapter mock
(`MockPixProviderClient`). Para operar de verdade, precisa de um PSP com API
Pix (cobrança imediata, webhook com verificação de assinatura, refund e split —
para o modelo white-label). A escolha impacta diretamente a Spec 10.

**Critérios de decisão (peso):**
1. Documentação/SDK oficial (alta).
2. Suporte a marketplace/white-label com split de pagamento (alta).
3. Verificação de assinatura de webhook documentada (alta).
4. Sandbox/homologação ágil (alta).
5. Custo por transação (média).
6. SLA e suporte (média).

**Shortlist (conhecimento de mercado; preços/condições a confirmar na
homologação — web indisponível na data da ADR):**
- **Pagar.me (Stone)** — API v5 robusta, webhook com verificação, split e
  marketplace maduros, SDKs oficiais. Forte para white-label.
- **Asaas** — API completa (Pix, cobranças, assinaturas), white-label, custo
  competitivo, boa documentação.
- **Efí/Gerencianet** — API Pix madura e simples, custo baixo; verificar o
  estado atual da plataforma e o suporte a split.
- **Mercado Pago** — grande, porém menos orientado a white-label/marketplace.

**Decisão:** **Pagar.me como candidato primário** e **Asaas como alternativa**,
avaliados por um mesmo conjunto de testes de integração implementados no adapter
`PixProviderClient` (Spec 10). A troca final não toca o domínio — o port isola
o provedor (ver ADR-001).

**Consequências:**
- (+) Decisão registrada e reversível; risco de homologação mitigado por
  candidato alternativo.
- (+) Critérios objetivos permitem comparação justa na Spec 10.
- (−) Preços/condições não verificados na data desta ADR — confirmar na
  homologação.
- (−) Compliance: operar como PSP credenciado exige camada regulatória
  (credenciamento BACEN); o modelo recomendado é **white-label/subadquirente**
  sobre o PSP escolhido.

## ADR-006: Autenticação da API por API keys (por lojista + admin)

**Status:** Aceito (Spec 12).

**Contexto:** todos os endpoints `/api/v1/**` (incluindo o dashboard admin da
Spec 8) são `permitAll` no `SecurityConfig` — qualquer pessoa na internet pode
criar lojistas, consultar transações/faturas e ler o resumo admin. Para operar em
produção é preciso autenticar as chamadas sem acoplar o domínio a um mecanismo
específico.

**Alternativas consideradas:**
1. **API key por lojista + chave admin** — header `X-Api-Key`; cada lojista
   recebe uma chave no cadastro (armazenada apenas como hash SHA-256, devolvida
   1x); chave admin separada via env. Stateless, simples, é o padrão do próprio
   Pagar.me.
2. **Chave única compartilhada** — mais simples ainda, porém todos os lojistas
   compartilham a mesma credencial (sem identidade por lojista) — rejeitada.
3. **JWT com login** — exige senha armazenada, endpoint de login e gestão de
   refresh; escopo muito maior para o estágio atual — postergado.

**Decisão:** API key por lojista + chave admin (opção 1). O `ApiKeyAuthFilter`
(Spring Security) resolve o header `X-Api-Key` → `ROLE_ADMIN` (chave admin de
`NYX_ADMIN_API_KEY`) ou `ROLE_MERCHANT` (hash SHA-256 → lojista `ACTIVE`, com o
`merchantId` no principal para futuras checagens de posse). Rotas públicas:
`POST /api/v1/merchants` (cadastro — onde a chave é emitida) e `/webhooks/pix`
(protegido pelo HMAC da Spec 10).

**Consequências:**
- (+) Fecha o gap de segurança mais crítico (dashboard admin e dados de lojistas
  expostos).
- (+) Sem senha armazenada: a chave em claro nunca persiste — só o hash
  SHA-256 (coluna `api_key_hash`, índice único, migration V6).
- (+) Troca de mecanismo (ex.: JWT) não toca o domínio — é só trocar o filtro.
- (−) Chaves não expiram nesta versão (rotação/expiração = spec futura).
- (−) Autorização de posse (`merchantId` só acessa os próprios dados) fica para
  spec futura — o principal já carrega o `merchantId` para isso.

## ADR-007: Perfil master — autenticação por login usuário/senha + sessão opaca

**Status:** Aceito (Spec 13).

**Contexto:** o operador da plataforma precisa de um perfil próprio
(`ROLE_MASTER`) capaz de controlar saques (Spec 14) e visualizar dados
financeiros (Spec 15). Diferente do lojista (API key por lojista, Spec 12) e do
admin técnico (chave única `NYX_ADMIN_API_KEY`), o master é um **operador
humano**: múltiplos perfis com identidade, revogação de acesso e sem
compartilhamento de credencial.

**Alternativas consideradas:**
1. **API key por master** — estende a Spec 12; porém exige distribuir/revogar
   chaves manualmente, sem logout e sem identidade de sessão — rejeitada para
   operadores humanos.
2. **JWT sem sessão** — sem sessão no banco; revogação e logout são difíceis
   (só expiração) — rejeitada (precisamos de bloqueio imediato de acesso).
3. **Login usuário/senha + token opaco com sessão no banco** — escolhida.

**Decisão:** tabelas `masters` (usuário único, senha **BCrypt**) e
`master_sessions` (token opaco armazenado como **hash SHA-256**, expira em 8h,
revogável). `POST /api/v1/auth/master/login` valida credenciais e devolve o
token; `POST /api/v1/auth/master/logout` revoga. `MasterAuthFilter` resolve
`Authorization: Bearer <token>` → `ROLE_MASTER` (principal = masterId). O
primeiro master é criado por seed no boot via `NYX_MASTER_SEED_USERNAME/PASSWORD`
(dev tem default fake; prod sem env vars não cria). Mesmo padrão de hash do
projeto (document_hash/api_key_hash).

**Consequências:**
- (+) Múltiplos operadores com identidade própria; logout/revogação imediata.
- (+) Senha nunca em claro (BCrypt); token nunca em claro (SHA-256).
- (+) Troca para JWT seria só trocar o filtro — domínio intocado.
- (−) Sessão no banco exige consulta por request (indexada por token_hash).
- (−) Expiração fixa de 8h (configurável); sem refresh nesta versão.
- (−) Seed cria apenas o 1º master; criação de novos perfis fica para spec
  futura (endpoint administrativo de masters).

## ADR-008: Monitoramento de risco, chargebacks e suspensão de contas

**Status:** Aceito (Políticas de Uso e Compliance v1.0 — `docs/politicas-uso.md`).

**Contexto:** as [Políticas de Uso e Compliance](../docs/politicas-uso.md) (v1.0,
Ago/2026, §4) definem que o FoxCash opera com **operações de baixo risco** e
monitora continuamente indicadores de risco. A seção §7 prevê consequências
gradativas por descumprimento: **advertência → suspensão temporária → bloqueio
permanente → reserva de valor → autoridades**. Hoje o backend tem o status de
lojista (`ACTIVE`/`SUSPENDED`, Spec 12 bloqueia processamento de não-ACTIVE) e o
bloqueio de saques por lojista (Spec 14), mas **nenhum monitoramento de
indicadores** nem um fluxo explícito de suspensão por risco.

**Indicadores da política (§4):**

| Indicador | Alerta | Ação prevista |
|---|---|---|
| Índice de chargeback | > 1% (alerta) / > 3% (suspensão) | Alerta / suspensão |
| Taxa de reembolso | > 5% (alerta) | Alerta |
| Fraudes confirmadas | Qualquer | Crítico (bloqueio) |
| Denúncias/reclamações | 3+ em 30 dias | Alerta |
| Autenticidade das vendas / comportamento de compra | Padrões suspeitos | Alerta antifraude |

**Decisão:**

1. **Monitoramento por métricas agregadas, não por regra hardcoded:** os
   indicadores (chargeback %, reembolso %, volume aprovado, denúncias) são
   calculados sobre os dados já existentes (transações, faturas, saques) em jobs
   agendados (padrão ShedLock das Specs 6/7) e expostos ao master
   (`/api/v1/master/**`, Spec 13). Limiares configuráveis
   (`foxcash.risk.*`: `chargeback-alert-percent`, `chargeback-suspend-percent`,
   `refund-alert-percent`, `complaints-alert-count`).
2. **Suspensão reusa o status de lojista existente:** lojista em
   `SUSPENDED` já não processa pagamentos (Spec 12). O fluxo de suspensão por
   risco escreve esse status (endpoint master `POST
   /api/v1/master/merchants/{id}/suspend` e `release`), sem novo conceito no
   domínio. **Reserva de valor** (retenção de saldo) entra como flag nova em
   spec futura de compliance, se necessário.
3. **Sinalização no domínio:** a decisão de alerta é **do operador** (master),
   não automática de escrita — o sistema computa os indicadores e o master age.
   Automatização total de suspensão fica para spec futura (antifraude).
4. **Antifraude desenhado para os indicadores:** o módulo antifraude (spec
   futura) captura os sinais da §4 — horários atípicos, múltiplas compras de
   curto prazo, meios de pagamento não autorizados — alimentando os mesmos
   agregados de risco.

**Consequências:**
- (+) Indicadores objetivos (1%/3%/5%) viram config e relatório para o master.
- (+) Suspensão reusa `MerchantStatus.SUSPENDED` — sem modelo novo.
- (+) Política documentada vira contrato verificável (testes sobre os cálculos).
- (−) Cálculo de chargeback/reembolso exige dados de disputa ainda não
  modelados (a contestação chega fora do fluxo Pix; será alimentada por
  endpoint/processo administrativo em spec futura).
- (−) Decisão de suspensão permanece manual (operador) nesta versão — sem
  auto-suspensão.
- (−) Reserva de valor e comunicação a autoridades ficam como procedimento
  operacional/documental, fora do código por ora.


## ADR-009: Autorização por recurso (IDOR) e rate limiting

**Status:** Aceito (Spec 18).

**Contexto:** a auditoria de segurança (teste como vendedor) confirmou que o
`merchantId` no principal (Spec 12) nunca era validado contra o recurso
acessado — **IDOR de leitura e escrita** (lojista B lia dados e criava
transações em nome do lojista A). Além disso, `POST /api/v1/merchants` (público)
e `POST /api/v1/auth/master/login` não tinham proteção contra abuso
(brute-force / criação massiva).

**Decisão:**

1. **Autorização por recurso** com `MerchantAccessGuard`: antes de qualquer
   operação, os endpoints de lojista validam
   `principal (UUID) == resourceMerchantId`; violação → **403 `forbidden`**
   (`MerchantAccessDeniedException`). Admin/master possuem rotas próprias e são
   negados aqui. Escolheu-se **403 explícito** (recurso existe, sem permissão)
   em vez de 404: UUIDs não são enumeráveis e 403 facilita diagnóstico.
2. **Rate limiting** com `RateLimitFilter`: janela fixa **em memória** por
   IP+rota (`ConcurrentHashMap`), aplicada só às rotas públicas de abuso;
   excesso → **429 `too_many_requests`**. Limiares configuráveis
   (`foxcash.security.rate-limit.*`). Detecta IP via `X-Forwarded-For`
   (primeiro valor) com fallback `remoteAddr`.

**Consequências:**
- (+) IDOR fechado em leitura e escrita; testes A×B no unit e no E2E.
- (+) Proteção contra brute-force/abuso no cadastro e no login master.
- (−) **403 vs 404:** resposta revela que o recurso existe (UUIDs não
  adivinháveis; trade-off aceito). Evolução possível: 404 anti-enumeração.
- (−) Rate limit **em memória**: não serve para multi-instância — produção
  com N pods precisa de store distribuído (Redis) — follow-up documentado.
- (−) `X-Forwarded-For` pode ser falsificado por cliente direto; em prod o
  proxy reverso deve sobrescrever o header com o IP real.

## ADR-010: Store distribuído do rate limit (Redis, fail-open)

**Status:** Aceito (Spec 43 — M-05).

**Contexto:** o rate limit da Spec 18 (ADR-009) usa janela fixa em memória
(`ConcurrentHashMap`) por IP+rota — correto para instância única (VPS), mas em
**multi-instância cada pod teria o seu próprio contador** (N× o limite efetivo
para o mesmo cliente). A homologação registrou o achado M-05: "rate limit por
instância". Produção com N pods precisa de um store compartilhado.

**Decisão:**

1. **Porta `RateLimitStore`** (`boolean tryAcquire(key, maxRequests,
   windowSeconds)`) com semântica de janela fixa — o `RateLimitFilter` injeta a
   porta e ignora o store concreto (mesmo contrato externo: 429
   `too_many_requests`).
2. **`InMemoryRateLimitStore`** (default, `store=memory`): extrai a lógica atual
   do filtro — nenhuma mudança de comportamento em single-instance, sem
   necessidade de Redis.
3. **`RedisRateLimitStore`** (ativo com `store=redis`): script Lua **atômico**
   (`INCR` + `EXPIRE`, TTL = `window-seconds` setado na 1ª requisição da janela)
   — consistente e sem corrida entre instâncias; N requests distribuídos entre
   pods compartilham o MESMO contador.
4. **Fail-open** com log throttled (1 warn por janela): se o Redis estiver
   indisponível, `tryAcquire` permite a requisição. Justificativa: o rate limit
   é proteção **secundária** — o gateway de pagamento não pode ficar
   indisponível por falha de infra auxiliar.

**Consequências:**

- (+) Rate limit efetivo em multi-instância (contador agregado via Redis).
- (+) Seleção por config (`foxcash.security.rate-limit.store=memory|redis`);
  single-instance continua sem Redis (nenhuma env nova obrigatória).
- (+) Fail-open documentado e testado: indisponibilidade do Redis degrada a
  proteção, mas não derruba o serviço.
- (−) Dependência do `spring-boot-starter-data-redis` no classpath (beans de
  conexão existem mas não são usados/contactados com `store=memory`).
- (−) Durante a indisponibilidade do Redis, a limitação deixa de valer
  (fail-open) — aceito por decisão de disponibilidade; a alternativa fail-closed
  derrubaria o gateway inteiro.
- (−) Janela fixa (não deslizante) — mesmo contrato da Spec 18; burst no limite
  da janela é permitido.

## ADR-011: Infraestrutura de produção no Railway (app + banco PostgreSQL)

**Status:** Aceito (decisão do usuário 2026-08-05).

**Contexto:** o projeto estava com a "decisão de infra em aberto" (roadmap —
"hospedagem a definir — Render, VPS própria, Neon/Supabase etc."). O deploy de
referência documentado (Spec 9) era VPS própria + systemd + keystore TLS na
8443 (`deploy/README.md`). O usuário decidiu usar o **Railway** tanto para
hospedar o backend quanto para o **PostgreSQL remoto**.

**Decisão:**

1. **Railway** é a plataforma de produção (app + banco). O fluxo VPS/systemd
   permanece documentado como alternativa (`deploy/README.md`), mas não é mais
   o caminho padrão.
2. **TLS no edge**: o Railway termina o HTTPS no proxy; o app fala **HTTP puro**
   na porta injetada `$PORT`. Novo perfil **`railway`** (ativado com
   `SPRING_PROFILES_ACTIVE=prod,railway`) que anula `server.ssl.*` (keystore
   vazio → connector HTTP), usa `server.port=${PORT}` e ativa
   `server.forward-headers-strategy=framework` para o `require-https` honrar o
   `X-Forwarded-Proto` do edge (sem loop de redirect).
3. **Banco**: Railway Postgres (`DATABASE_URL`), mapeado para
   `DB_URL=jdbc:postgresql://...`/`DB_USERNAME`/`DB_PASSWORD` (o prod já exige
   essas env vars — fail-fast).
4. **Build/deploy**: Nixpacks detecta o Maven (`nixpacks.toml` fixa JDK 21);
   `railway.json` define o startCommand (`java -jar target/foxcash-backend-0.1.0-SNAPSHOT.jar`)
   e política de restart. Segredos (cripto, admin key, webhooks) como variáveis
   do Railway. Runbook em `deploy/railway.md`.
5. **IP do cliente**: `NYX_TRUST_PROXY=true` no Railway (o edge SOBRESCREVE o
   `X-Forwarded-For`) para o rate limit (Spec 18/43) ver o IP real.

**Consequências:**

- (+) Deploy simplificado (sem provisionar VPS, sem keystore, sem nginx).
- (+) TLS automático no edge (domínio `.up.railway.app` ou custom).
- (+) ShedLock continua garantindo execução única dos jobs com N réplicas
  (lock no PostgreSQL).
- (−) Rate limit default `memory` é por instância (ADR-010) — multi-replica com
  limite agregado exige `NYX_RATE_LIMIT_STORE=redis`.
- (−) O perfil `prod` puro (sem `railway`) mantém o comportamento VPS (TLS na
  8443) — duas topologias suportadas; a escolha é por `SPRING_PROFILES_ACTIVE`.
- (−) Custo por uso do Railway (Hobby/Pro) em vez de custo fixo de VPS.

## ADR-012: Execução de jobs — in-process com métricas, trilha e alerta via outbox

**Status:** Aceito (decisão 2026-08-07, Spec 53).

**Contexto:** os 6 jobs do backend (faturas, cobrança/receivables, risco,
reconciliação do wallet, dispatcher de outbox/notificações e reconciliação de
saques) rodam in-process com `@Scheduled` + ShedLock (Spec 6), mas: (1) o pool
default do Spring tem **1 thread** — um cron longo bloqueia o poll do outbox
(30s) e vice-versa; (2) nenhum job registra métrica (Micrometer no classpath
sem uso); (3) falha de job é **silêncio operacional** (ERROR solto no log, sem
métrica, sem trilha, sem alerta).

**Decisão:**

1. **Pool de threads dedicado** (`ThreadPoolTaskScheduler`, `nyx-job-*`,
   `foxcash.scheduler.pool-size`, default 4) — substitui o scheduler default.
2. **`JobExecutionService`** — abstração única pela qual TODOS os jobs passam:
   cronometra, registra métricas (`nyx_job_duration_seconds`,
   `nyx_job_success_total`, `nyx_job_failure_total`, tag `job`), grava a trilha
   `job_runs` (SUCCESS/FAILED + duração + erro) e captura falha. **Nunca
   re-propaga** — job agendado não derruba a thread do pool; falha da própria
   infra de trilha/alerta vira WARN e não mascara a execução.
3. **Trilha auditável `job_runs`** (migration V43) com retenção configurável
   (`foxcash.scheduler.job-runs.retention-days`, default 30) e job de limpeza
   diário.
4. **Alerta via outbox**: evento **`JOB_FAILED`** (plataforma, sem merchant —
   `notification_outbox.merchant_id` torna-se nullable) entregue a um
   **webhook de operador** (`foxcash.notification.operator-webhook-url` +
   `operator-webhook-secret`), reusando o pipeline da Spec 35 (dispatcher 30s,
   retry/backoff, HMAC-SHA256, dedup por `eventId`). Sem URL configurada →
   fail-open: o alerta fica em métrica + log estruturado + `job_runs`.
5. **Worker separado fica ADIADO** (spec futura): o outbox já é o "fio" que
   permite separar o processamento de eventos sem tocar no domínio; o ShedLock
   continua garantindo execução única mesmo com worker + réplicas.

**Consequências:**

- (+) Duração e resultado de cada execução visíveis em `/actuator/prometheus` e
  auditáveis em `job_runs`; falha de job não é mais silenciosa.
- (+) Cron longo não atrasa mais o poll do outbox (pool ≥ 2).
- (−) Jobs só rodam com o backend de pé (sem orquestrador externo) — decisão
  mantida; a separação de worker é evolutiva via outbox.
- (−) Alerta ativo (push) exige configurar o webhook do operador (URL + segredo
  juntos, fail-fast no boot); sem isso, o monitoramento é pull (métrica/log/DB).
- (−) `notification_outbox.merchant_id` nullable — eventos de merchant
  continuam com FK intacta (sem regressão na Spec 35).

## ADR-013: Escala de dados sem PgBouncer/réplicas — dispensados por evidência (Spec 62)

**Status:** Aceito (Spec 62, F1 — 2026-08-09).

**Contexto:** a Spec 62 (capacity engineering) exige ≥ 5.000 vendas/s com
integridade financeira. PgBouncer (pooling) e réplicas de leitura (separação
leitura/escrita) são intervenções candidatas **gated por evidência**: só entram
se o benchmark demonstrar que o componente é o gargalo (regra de ouro).

**Evidência que dispensou as mudanças (F1 local, 2026-08-09):**

- **PgBouncer:** pool Hikari 20/réplica sem contenção (acquire máx 0,6 ms, sem
  fila, 21 conexões no Postgres; capacidade ~4.000 req/s de DB no baseline da
  Spec 60). O caminho da venda usa transações curtas (~11-16 ms) e não retém
  conexão durante I/O de rede (PSP fora da transação — Spec 28). Sem fila/limite
  de conexões, o pooling atual não é gargalo.
- **Réplicas de leitura:** o caminho da venda é **write-heavy** (10 escritas/
  venda vs 18 leituras single-row indexadas); os endpoints de leitura pesados
  (storefront, dashboard, relatórios, admin) estão **fora do caminho crítico** e
  têm volume baixo. Sem evidência de competição leitura×escrita no primary, a
  complexidade de datasource routing não se justifica.

**Decisão:** manter o pool atual (Hikari 20, Spec 60) e leituras no primary. As
medições que dispensaram as mudanças estão em `docs/postgresql-analysis.md`
(F1). Se o benchmark da F7 (Railway, `pg_stat_statements`) demonstrar contenção
de conexões ou competição leitura×escrita, PgBouncer/replicas voltam como
intervenções priorizadas — com a **regra de ouro** de que ledger, wallet,
idempotência e outbox permanecem no caminho consistente de escrita.

**Consequências:**

- (+) Zero complexidade nova (sem PgBouncer, sem routing datasource, sem
  atraso de réplica).
- (+) Decisão reversível com evidência: o critério de aceite 3/4 da Spec 62 é
  atendido pela medição registrada.
- (−) Se o volume real exigir, a implementação de PgBouncer/réplicas será
  retrabalho — mitigado pelo runbook (design.md) e pela regra de ouro.

**Atualização 2026-08-10 (campanha 62 F8/F9 + 65 F4):** a F7/F2B (2 réplicas)
confirmou o pool sem contenção, mas a **F9 (scale-out) mediu o limite real**:
com pool Hikari 100/réplica, **8 réplicas desejam 800 conexões > Postgres
max_connections=500** (erro `too many clients already`) — o scale-out útil
para em ~4-8 réplicas (~1.250-1.550/s) e **degrada em 16 réplicas** (sale_ok
94-97%, p95 charge ~10-19s). Evidência em `docs/capacity-report.md` §11.11.
**Decisão revisada:** o gargalo de conexões é REAL e medido. Para scale-out
além de ~4 réplicas, a recomendação é **dimensionar o pool Hikari por réplica**
(ex.: pool 60 × 8 = 480 < 500) — sem infra nova e reversível. **PgBouncer**
(transaction pooling) permanece como candidato se o scale-out além de ~8
réplicas for necessário (desacopla as conexões da app do limite do Postgres);
ledger/wallet/idempotência/outbox seguem no caminho consistente de escrita
(regra de ouro).

## ADR-014: Edge/WAF na frente do Railway — Cloudflare como proxy (Spec 63)

**Status:** Aceito (Spec 63 — 2026-08-09).

**Contexto:** o app roda atrás do edge do Railway (TLS gerenciado, mitigação
básica de DDoS, healthcheck). O código já é hardening (auditorias 1-3, Specs
18/42/58/59/60), mas **não há proteção de borda dedicada**: floods e bots
chegam inteiros no app (o rate limit da app é o único controle; o benchmark da
Spec 62 provou que o edge do Railway recusa conexões ~1000 concorrentes, mas
sem WAF/bot mitigation). Para "segurança de nível de mercado" (diretriz do
usuário 2026-08-09), a camada de maior ganho é uma edge/WAF + bot mitigation na
frente.

**Decisão:** colocar o **Cloudflare como proxy DNS** na frente do domínio
público da API (ex.: `api.foxcash.example.com` → CNAME para o domínio do Railway,
proxy ON), com:

- **DDoS protection** gerenciado (ilimitado no plano gratuito);
- **WAF** com ruleset gerenciado + regras customizadas (endpoints sensíveis:
  `/api/v1/merchants`, `/merchant-auth/*`, `/webhooks/*`);
- **Rate limiting na borda** (regras por IP/rota) — camada que reduz a carga
  ANTES do app; o rate limit distribuído da app (Specs 43/58/59) permanece como
  controle final (defesa em profundidade);
- **Bot mitigation**: Turnstile (gratuito, invisible) no login/cadastro do
  portal (Spec 46) com validação server-side;
- **TLS**: Cloudflare Full (strict) com o certificado válido do Railway no
  origin; o app continua lendo `X-Forwarded-For`/`CF-Connecting-IP` via
  `NYX_TRUST_PROXY=true` (Spec 18) — validar que o app confia apenas nos IPs do
  Cloudflare.

O edge do Railway permanece (origin). Rollback = proxy OFF (DNS direto).

**Consequências:**

- (+) Absorção de DDoS/flood/bot antes do app (menor custo de CPU/DB/threads);
- (+) WAF e edge rate limit sem alterar o contrato de API;
- (+) Turnstile reduz automação no cadastro/login do portal;
- (−) Dependência de um provedor externo (Cloudflare) e latência adicional de
  ~5-10 ms por request na borda;
- (−) Limitações de plano (WAF/rate limit completos exigem plano Pro; free tem
  DDoS, TLS, Bot Fight Mode e Turnstile);
- (−) Se mal configurado, pode bloquear tráfego legítimo (webhook do PSP —
  configurar exceção de IP/ASN do Pagar.me) — mitigado por regras específicas e
  testes antes do go-live.

## ADR-015: Observabilidade de segurança — alerting via operador webhook + métricas (Spec 64)

**Status:** Aceito (Spec 64 — 2026-08-09).

**Contexto:** o projeto já tem métricas Prometheus (`/actuator/prometheus`,
Spec 53), tracing (Spec 57), trilha de atividades (`platform_activity_log`,
Spec 41) e o **webhook de operador** via outbox (`JOB_FAILED` → Spec 53;
`OUTBOX_DEAD` → Spec 54). O que falta é **segurança observável**: sem alerta,
pico de 401/403, 429 em massa, falha de assinatura de webhook ou fail-open do
rate limit (Spec 43/ADR-010) passam despercebidos até o incidente virar
indisponibilidade.

**Decisão:** sem SIEM novo — reutilizar o que existe:

1. **Eventos de segurança no webhook de operador**: publicar eventos de
   `SecurityEventType` (ex.: `LOGIN_FAILURE_BURST`, `RATE_LIMIT_BURST`,
   `SIGNATURE_FAILURE_BURST`, `RATE_LIMIT_FAIL_OPEN`) no outbox (Spec 35/53)
   quando um limiar for atingido — mesmo pipeline do `JOB_FAILED`/`OUTBOX_DEAD`;
2. **Alerting nas métricas**: canal opcional via Grafana Cloud (gratuito) com
   scrape autenticado do `/actuator/prometheus` + regras de alerta (5xx,
   readiness DOWN, Hikari exhaustion, heap alto) — documentado, não obrigatório;
3. **Runbook de resposta a incidente de segurança** (detectar → conter →
   mitigar → restaurar) reusando backup/restore + HA + redeploy + teste de mesa;
4. **Retenção/busca de logs**: documentar a janela do Railway + opção de
   log drain (Axiom/Loki) para investigação.

**Consequências:**

- (+) Sem infraestrutura nova obrigatória (o webhook de operador já existe);
- (+) Segurança vira um evento operacional acionável (não só log);
- (+) Runbook torna a resposta a ataque executável por um operador/agente;
- (−) Eventos de limiar exigem contadores/estado (implementação de código na
  Spec 64);
- (−) Depende de o operador ter o webhook configurado (Spec 35);
- (−) Métricas externas (Grafana Cloud) adicionam uma conta/dependência — opcional.

## ADR-016: Autorização do master em 2 níveis (ADMIN/OPERATOR) + step-up MFA (Spec 69)

**Status:** Aceito (2026-08-11 — decisão do usuário; implementada e `audited`
na mesma data, Spec 69).

**Contexto:** a Spec 69 foi originalmente desenhada com **RBAC granular por
ação** — tabela `master_permissions` (8 permissões), papéis
`ADMIN/OPERATOR/FINANCE/AUDITOR` como agrupamentos convenientes, atribuição de
`role`/`permissions[]` no `POST /profiles` e deny-by-default para toda escrita.
Ao detalhar o Master Console (Spec 71), o usuário decidiu simplificar o modelo:
**todo master operacional nasce com OPERATOR+FINANCE+AUDITOR juntos** (um único
perfil de operação completo), e a função **admin é separada** — determinada pela
flag `can_manage_masters` já existente, responsável apenas pela gestão de perfis
master. O step-up MFA nas ações sensíveis (saque administrativo, write-off,
cobrança manual, gestão de perfis) é mantido como a barreira de segurança.

**Decisão:**

1. **Autorização em 2 níveis** (derivada de `can_manage_masters`, sem campo novo):
   - `OPERATOR` (default, `can_manage_masters=false`) → bloco operacional
     completo: `merchant.suspend/release`, `withdrawal.block/release`,
     `withdrawal.administrative`, `receivable.write_off`, `invoice.collect`
     (7 permissões) + leitura (`ROLE_MASTER`);
   - `ADMIN` (`can_manage_masters=true`, seed) → bloco completo **+**
     `master.profiles.manage`.
2. **Sem tabela `master_permissions`** — o catálogo `MasterPermission` (8 nomes)
   permanece como **contrato** (guard, `/me`, config de step-up), mas a concessão
   é por nível; nada a persistir além do que já existe.
3. **Guard tier-based nos services** (`MasterPermissionGuard.requirePermission`):
   permissões operacionais → master ativo; `master.profiles.manage` →
   `can_manage_masters`; negado → 403 `forbidden`.
4. **Sem mudança de payload** — `POST/GET /api/v1/master/profiles` permanecem
   como hoje; novos perfis nascem OPERATOR.
5. **Sem migration de dados** — masters existentes preservam o acesso operacional
   atual; a única mudança comportamental é o **step-up** nas 3 ações de FINANCE.
6. **Step-up MFA mantido** nas 4 ações sensíveis (403 `step_up_required`, janela
   15 min, reuso do `verifySecondFactor`) — mesmo com permissão ampla, a ação de
   valor exige re-prova de posse.

**Consequências:**

- (+) Modelo muito mais simples que o RBAC granular: zero tabela nova, zero
  payload novo, zero migration de dados; o delta da Spec 69 fica majoritariamente
  no step-up + catálogo + guard tier-based;
- (+) Masters existentes não perdem capacidade (sem relatório de "quem ficou sem
  permissão");
- (+) UI do Master Console (Specs 71/72) fica uniforme: todo master operacional
  vê o console completo; só a gestão de perfis é oculta para OPERATOR;
- (−) Sem least-privilege granular: não dá para restringir um operador específico
  de write-off sem torná-lo não-operacional (se um dia precisar, o catálogo já
  permite voltar a uma concessão fina sem mudar o guard);
- (−) O "auditor" como papel somente-leitura deixa de existir como entidade
  separada (todo master lê tudo — a separação de funções passa a ser
  produto/organizacional, não técnica);
- (−) Re-verificação da Spec 69 necessária após a mudança (feita:
  requirements/design/task + V49/V51 + roadmap atualizados).

## ADR-017: Fallback de adquirente + resiliência de saldo no PSP (Specs 91/92)

**Status:** Aceito (2026-08-14 — decisão do usuário; specs 91/92 `planned`).

**Contexto:** o PSP é selecionado estaticamente no boot
(`foxcash.psp.provider=mock|pagarme|asaas` — UM adapter via
`@ConditionalOnProperty`; ADR-005: Pagar.me primário, Asaas alternativa). Se o
adquirente cair (timeout/indisponibilidade), a cobrança falha (502/PENDING,
Spec 28) sem tentar outro — e um **freeze** (bloqueio jurídico da conta no
gateway por fraude/chargeback/quebra de termos) não é contornável por fallback
reativo: as chamadas de saque retornam 403/400 e o saldo está juridicamente
travado. A decisão é uma arquitetura **preventiva**, não reativa.

**Decisão:**

1. **Fallback na criação da cobrança** (Spec 91) — `PixProviderRouter` com lista
   de prioridade configurável (`foxcash.psp.routing.*`, config/env, sem UI).
   O Pix só permite fallback **na criação** (o QR nasce atrelado ao adquirente
   que o gerou; cobrança já criada não migra). Erro incerto (timeout/5xx) →
   **`getCharge` no primário** (novo método no `PixProviderClient`): se A criou,
   usa A; só cai para B se A confirmar que **não** criou (**anti-dupla-cobrança**).
   Erro definitivo (rejeição de negócio) → não consulta, fallback imediato.
2. **Circuit breaker preventivo** (Spec 91) — saúde por adquirente: janela de
   falhas/timeouts (padrão Spec 64) + input de chargeback (Spec 16/19). Acima do
   limiar → abre o circuito → desvia volume ao secundário **antes** do risco;
   métricas `nyx_psp_*` por adquirente + alerta `PROVIDER_CIRCUIT_OPEN` ao
   operador (Spec 64). Kill-switch manual por adquirente.
3. **Sweep Zero-Balance com buffer** (Spec 92) — job diário (ShedLock +
   `JobExecutionService`, Spec 53) transfere o saldo **Disponível** do PSP para a
   conta blindada corporativa, retendo um **buffer** configurável
   (`foxcash.psp.sweep.*`) para os saques dos lojistas. Exposição máxima a um
   freeze = o que entrou desde o último sweep + buffer.
4. **Isolamento de credenciais** (Spec 92) — o sweep usa **somente** a credencial
   payout-scoped (`foxcash.psp.payout-*.secret-key`), **nunca** a chave de
   cobrança. Se a chave de cobrança vazar, o ofensor não move o saldo master.
   IP whitelist no adquirente (egresso do Railway) + **fail-fast no boot** se
   charge-secret == payout-secret em prod.
5. **Payout fallback** (Spec 92) — na solicitação do saque (timeout/5xx de A →
   tenta B). **Freeze não é roteável** — a defesa contra freeze é o sweep, não o
   fallback de saque.
6. **Split/sub-contas adiado** (Spec 93 futura) — Conta Master hoje; o split
   muda cobrança (`split_rules`), wallet, saque e reconciliação — spec separada
   quando o volume justificar.

**Consequências:**

- (+) Cobrança resiliente a indisponibilidade de adquirente sem dupla cobrança
  (poll antes do fallback) e sem sacrificar a idempotência da Spec 28;
- (+) Caixa protegido contra freeze: exposição máxima = 1 janela de sweep +
  buffer; o dinheiro dos lojistas e as taxas da plataforma ficam fora do risco
  de meses acumulados;
- (+) Credencial de cobrança comprometida não dá acesso ao saldo (princípio do
  menor privilégio entre funções);
- (+) Dois adapters reais (Pagar.me + Asaas) com harness de contrato forçam a
  porta `PixProviderClient` agnóstica de verdade e habilitam smart routing por
  custo no futuro;
- (−) Mais um ponto de configuração operacional (lista de prioridade, circuit
  breaker, sweep) que exige monitoramento; fail-fast e alertas mitigam;
- (−) O `getCharge` novo na porta exige implementação nos adapters (Pagar.me já
  tem o GET; Asaas precisa do endpoint de consulta);
- (−) Sweep exige credencial de transferência no adquirente (payout-scoped) e
  conta blindada corporativa — setup externo do usuário.

## ADR-018: Decisões da auditoria de segurança 2026-08-14/15 — lacunas #7/#9/#10 (Spec 94)

**Status:** Aceito (2026-08-15 — auditoria de segurança; Spec 94 `audited`).

**Contexto:** o checklist de segurança (origem JS/Next.js: Clerk, Prisma, Vercel,
Logtail) foi mapeado contra a stack real (Java/Spring + Railway). 7/10 itens já
cobertos; a Spec 94 (MFA TOTP do lojista) fechou a lacuna #1. Restam 3 lacunas
com componentes de **decisão/operação**, não apenas código: **(#7)** log drain/
agregação; **(#9)** rotação de senha a cada 90d; **(#10)** instalação do cron do
backup. A verificação concreta do repositório (2026-08-15) confirmou que a
narrativa de ataque (`.env` exposto, chave GitHub/AWS/Slack vazada, `sk_live_`,
recon/fuzzing via Gobuster/Nmap) **não se materializa**: repo limpo, WAF/
Cloudflare (Spec 63) + rate limit por IP (Spec 43) cobrem a superfície pública.

**Decisão #9 — rotação de senha 90d: DESCARTADA (NIST 800-63B).** O NIST
desaconselha rotação forçada quando há autenticação multifator — a rotação
forçada reduz a segurança (usuários escolhem senhas previsíveis) sem ganho real
quando o 2º fator existe. Com o MFA agora no master (Spec 44) e no lojista
(Spec 94), a política vigente permanece: `PasswordPolicy` (8–72 caracteres,
maiúscula + minúscula + dígito, Spec 42) + MFA obrigatório no login + alerta de
login suspeito (Spec 64) + lockout de senha (Spec 20/B-06) e de TOTP (Spec 94).
**Nenhuma spec de código nasce dessa lacuna** — fica registrada como decisão
formal para não reaparecer como "todo".

**Decisão #7 — logs/auditoria: Logtail N/A, Railway log drain.** A trilha
de auditoria existe (`platform_activity_log`, Spec 41, incluindo os eventos
`MFA_*` do lojista) e os app logs existem; o **log drain do Railway para
Axiom/Loki** era a Fase C **opcional** da Spec 64. **Entregue pela Spec 95
(`audited`, 2026-08-15)**: runbook `ops/log-drain.md` (consulta por
`X-Request-Id` + correlação com trilha/métricas), seção no `deploy/railway.md`
e job de retenção opt-in (`PlatformActivityCleanupScheduler`, default OFF —
append-only preservado). Resta apenas o **passo operacional** de configurar o
drain no Railway (acesso do operador).

**Decisão #10 — backup: cron instalável e verificável.** `ops/backup.sh`/
`restore.sh`/`backup-restore.md` existiam; o agendamento diário 02:30 UTC
dependia de instalação manual no host do Railway. **Entregue pela Spec 96
(`audited`, 2026-08-15)**: instalador/verificador `ops/install-backup-cron.sh`
(install idempotente, check com frescura do dump < 48h, uninstall) +
`ops/logrotate-nyxcash-backup.conf` (rotativo 60 dias) + checklist de
verificação no runbook (semanal `check`, mensal teste de restore). Resta o
**passo operacional** de rodar o `install` no host do Railway (acesso do
operador).

**Consequências:**

- (+) Checklist da auditoria fechado formalmente: 7/10 implementados + 2
  registrados como specs futuras (95/96) + 1 decisão arquivada (rotação);
- (+) A decisão #9 evita trabalho futuro desnecessário e potencialmente
  prejudicial (rotação forçada com MFA);
- (−) Logs agregados (Spec 95) e backup agendado (Spec 96) continuam pendentes
  de execução operacional — risco residual de observabilidade centralizada e de
  RPO/RTO não garantido por cron até lá;
- (−) A execução de 95/96 depende de acesso operacional ao Railway (drain config,
  host do cron) — setup externo do usuário.

## ADR-019: Perfil de operação como entidade — `merchant_profiles` (Specs 122/123/124)

**Status:** Aceito (2026-08-15 — decisão do usuário; Specs 122/123 `verified`,
Spec 124 `planned`).

**Contexto:** o seletor de contexto do portal (Specs 122/123) nasceu com um modelo
"1 rota por lojista" (`merchants.gateway_route`). O usuário ampliou a visão: cada
**contexto** (Gateway × Bot) tem funcionalidade própria e o seletor deve fazer um
**reset contextual** nos ~13 módulos do portal — o lojista tem **1 conta que se
comporta como N empresas** (ex.: "Padrão BR" ↔ "Global/Stripe"), cada uma com
rota de gateway, frota de bots e dados escopados. O modelo de coluna única não
comporta N operações por lojista nem o filtro rígido de dados por contexto.

**Decisão:** modelar o perfil de operação como **entidade de primeira classe**
`merchant_profiles` (N por lojista), com `gateway_route` (rota de cobrança Pix),
frota de bots (`store_bots.profile_id`), default/ordem e soft-delete. A coluna
`merchants.gateway_route` prevista na Spec 98 (V61) é **removida** — a rota vive
no perfil (dona: Spec 122). **Faseamento:** fase 1 (Specs 122/123) = fundação
(entidade + seletor no HeaderGlobal + header `X-Profile-ID` + override por perfil
no `RoutingPolicyResolver`; nada é escondido); fase 2 (Spec 124) = **filtro
rígido** por perfil nos módulos e cobranças roteando com `profileId`. **Anti-
fraude preservado:** o header `X-Profile-ID` **seleciona o perfil do próprio
lojista** (server-validado) e **nunca decide rota** — a rota vem da linha do
perfil no banco, validada contra o catálogo configurado.

**Consequências:**

- (+) Modelo comporta N operações por lojista e o multi-tenancy dentro da conta;
- (+) O filtro rígido (fase 2) isola dados entre operações (sem vazamento de
  lead/traffic entre perfis);
- (+) Mecanismo "plug and play": um 3º perfil (ex.: whitelabel) = nova linha em
  `merchant_profiles` + grupo de bots, sem novo banco;
- (−) Retrofit de dados: links, campanhas e transações precisam derivar o perfil
  (via bot/checkout → perfil) na fase 2 — trabalho de marcação;
- (−) Spec 98 perde o `gateway_route` do V61 (coordenação feita nos 3 docs);
- (−) Fase 1 entrega o mecanismo, mas o efeito de rota só fica vivo quando os
  call-sites de cobrança passarem `profileId` (fase 2).

## ADR-020: Bot e Gateway = superfícies de cliente distintas sobre UM backend (sem split de serviço)

**Status:** Aceito (decisão do usuário 2026-08-16 — análise pré-implementação do Gap 4).

**Contexto:** ao detalhar o frontend do Gap 4 (portal rico), o usuário reforçou
que **Bot** e **Gateway** são **duas superfícies de cliente completamente
diferentes**: o Bot é a **conversa** dentro do Telegram/Discord onde o cliente
compra, paga (QR + copia-e-cola no chat) e recebe a entrega (Specs 48–52,
`audited`); o Gateway é a **página web pública de pagamento** (`/c/{slug}`,
Specs 115/116) onde o cliente paga no navegador, com identidade customizável e
Cupons & Sorteios. A pergunta de arquitetura: a API precisa ser **separada** (dois
serviços) entre "API do bot" e "API do gateway"?

**Evidência do código (2026-08-16):**
- **Um único backend** (`com.foxcash`, Spring Boot hexagonal) serve tudo com
  **separação lógica por prefixo + auth** no `SecurityConfig`: `/api/v1/foxcash/**`
  (API key do lojista — bots Node consomem), `/api/v1/portal/**`
  (MERCHANT_PORTAL), `/api/v1/master/**` (MASTER), `/api/v1/admin/**` (ADMIN),
  `/webhooks/pix|payouts|telegram/**` (público + verificação HMAC/secret),
  `/api/v1/merchant-auth/**` (login público).
- **Os bots já são processos Node separados** (`bots/telegram`, `bots/discord`,
  `bots/core`) que **consomem** a API via `/api/v1/foxcash/*` (`foxcash-client.ts`).
  A separação física bot×backend **já existe** — não há bot dentro do backend.
- **O fluxo do dinheiro é UMA transação** (gerar Pix → webhook PAID → crédito no
  ledger → wallet → saque → payout) com `REQUIRES_NEW`, ShedLock e outbox
  (Specs 42/47/92/30). Splitting físico no caminho do dinheiro = transação
  distribuída (duplo pagamento/crédito perdido).

**Decisão:**
1. **Manter UM backend** — as superfícies Bot × Gateway compartilham o mesmo
   domínio financeiro (transação, ledger, wallet, PSP routing). **Não** dividir
   em dois serviços agora.
2. **Isolamento por prefixo + auth** (já existente) é a separação correta:
   `/api/v1/foxcash/**` (bots, API key), `/api/v1/public/**` (checkout/bio link,
   `permitAll` + rate limit por IP — Specs 115/116), `/api/v1/portal/**`
   (lojista), `/api/v1/master/**` (operador).
3. **Divisão futura (condicional, ADR futura):** SE um dia os perfis de escala
   divergirem (muitas requisições leves de renderizador de bot × picos de
   webhook Pix), extrair **somente a fatia read/leve** dos bots
   (`/api/v1/foxcash/**` storefront/renderizador) para instância separada. **Nunca**
   o núcleo financeiro. Não é requisito do Gap 4.
4. **Frontends distintos por superfície:** Bot = conversa (grammy/discord.js);
   Gateway = SPA do portal com rotas públicas `/c/:slug`, `/bio/:slug`
   (Specs 115/116); Portal administrativo = mesmo SPA com guard de auth.

**Consequências:**
- (+) Zero transação distribuída no caminho do dinheiro (atomicidade preservada);
- (+) Sem custo operacional de dois deploys/sincronização de schema;
- (+) A distinção Bot × Gateway fica no **produto/frontend** (superfícies), não
  na infraestrutura — o ADR-019 modela a distinção no perfil de operação;
- (−) Um processo único compartilha recursos (CPU/threads) entre bots e gateway
  — mitigado por rate limit por prefixo e (se necessário) escala horizontal do
  mesmo deploy;
- (−) Mudar para split físico no futuro exigiria SAGA/eventual consistency no
  fluxo financeiro — decisão reversível, porém cara (mitigada pela fatia
  read/leve dos bots, se um dia precisar).

