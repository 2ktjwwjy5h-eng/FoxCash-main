# FoxCash — Plataforma White-Label de Pagamentos Pix e Automação Conversacional

Backend em **Java 21 + Spring Boot 3.5** com **arquitetura hexagonal (ports & adapters)**, frontends em **React 18 + TypeScript + Vite** e bots em **Node.js + TypeScript** para processar transações Pix, faturar lojistas e automatizar entregas de produtos digitais no Telegram e Discord.

---

## 1. Dados dos Integrantes da Equipe

> **Instituição / Curso:** `[Nome da Faculdade / Universidade]` — `[Nome do Curso]`
> **Disciplina / Professor(a):** `[Nome da Disciplina]` — Prof.(a) `[Nome do Professor]`

| Nome Completo | Matrícula / RA | Papel no Projeto | GitHub / Contato |
| :--- | :--- | :--- | :--- |
| `Giulia Muniz da Costa Massafra` | `[06005334]` | Arquitetura Backend, Segurança & Banco de Dados, Bots Conversacionais (Telegram/Discord) & Integrações, QA, Testes E2E (Playwright), Carga (k6) & DevOps | `[@giuliamassafra]` |


---

## 2. Escolha da Situação-Problema

Vendedores digitais, criadores de conteúdo, comunidades pagas e infoprodutores utilizam cada vez mais plataformas conversacionais (**Telegram e Discord**) como canal principal de vendas e relacionamento. No entanto, o fluxo tradicional de comércio eletrônico apresenta quatro grandes problemas para esse cenário:

1. **Alta fricção e abandono de carrinho:** Redirecionar o comprador de dentro de um chat para páginas externas de checkout que exigem longos cadastros reduz drasticamente a conversão de vendas.
2. **Trabalho manual na entrega e gestão de acessos:** Após a confirmação de um pagamento Pix, muitos vendedores precisam verificar comprovantes manualmente para liberar cargos no Discord, aprovar entradas em grupos do Telegram ou enviar arquivos digitais, gerando atrasos, fraudes de comprovante falso e alto custo operacional.
3. **Riscos de integridade financeira e indisponibilidade de adquirentes:** Sistemas que dependem de um único Provedor de Serviços de Pagamento (PSP) ficam inoperantes quando o adquirente sofre instabilidade, além de sofrerem com condições de corrida (*race conditions*), cobranças duplicadas e falta de rastreabilidade contábil.
4. **Exposição desnecessária de dados pessoais (LGPD):** Gateways convencionais exigem e armazenam dados sensíveis do comprador final (CPF, telefone, endereço) mesmo em microtransações digitais onde o vendedor precisa apenas confirmar o pagamento atrelado ao identificador do usuário na plataforma de chat.

---

## 3. Descrição Sucinta do MVP

O **MVP do FoxCash** entrega uma infraestrutura B2B ponta a ponta que unifica **gateway de pagamento Pix multi-adquirente**, **carteira digital com razão contábil auditável** e **automação de vendas e entregas via bots**:

* **Núcleo Financeiro & Multi-PSP (Backend Java 21 / Spring Boot 3.5):**
  * Geração de cobranças Pix (QR Code + Pix Copia e Cola) com chave de idempotência obrigatória (`idempotencyKey`) e roteamento inteligente (*fallback* automático e *circuit breaker* entre Pagar.me, Asaas e provedor Mock).
  * Recebimento de confirmação via **Webhooks autenticados por HMAC-SHA256**, creditando automaticamente a carteira digital (*wallet*) do lojista.
  * **Razão Contábil Imutável (*Append-Only Wallet Ledger*):** Toda movimentação financeira (venda aprovada, estorno, chargeback, taxa, saque) gera escrituração contábil auditável com locks pessimistas (`SELECT ... FOR UPDATE`) e job diário de reconciliação.
  * **Faturamento e Saques Automatizados:** Modelo de precificação configurável (R$ 0,50 por transação aprovada + R$ 69,95 mensais + R$ 2,50 por saque), com saques Pix manuais ou automáticos por limiar de saldo.
* **Portal do Lojista (`portal/` — SPA React + TypeScript):**
  * Onboarding rápido por e-mail/senha, dashboard analítico com métricas de conversão, extrato de carteira, gerador de cobrança Pix avulsa/Gift, configuração de destinos de saque mascarados, gestão de chaves de API com escopos, configuração da loja white-label, CRM com chat em tempo real (SSE) e autenticação de dois fatores (MFA TOTP).
* **Master Console (`console/` — SPA React + TypeScript):**
  * Painel administrativo para operadores da plataforma com controle de risco/antifraude (MED%), auditoria de ações, bloqueio/liberação de saques, configuração fina de taxas/limites por lojista e *Step-Up Authentication* via TOTP para operações críticas.
* **Bots Renderizadores Stateless (`bots/` — Node.js + TypeScript):**
  * Bots para **Telegram** (`grammY`) e **Discord** (`discord.js`) 100% orientados pela configuração do Portal: exibem o catálogo no chat, geram o QR Code Pix no comando `/comprar` e, ao receberem o webhook assinado `SALE_PAID` (via padrão *Transactional Outbox*), executam automaticamente as ações de entrega (`grant_role`, `add_to_group`, `send_file`, `send_link`, `send_message`).
* **Privacidade e Segurança por Design:**
  * Nenhum dado pessoal (PII) do comprador é armazenado (apenas hash SHA-256 do `buyerId` pseudônimo para triagem antifraude). Dados sensíveis de lojistas e chaves Pix são criptografados em repouso com **AES-256/GCM**.

---

## 4. Instruções para Executar o MVP Localmente

### Pré-requisitos

| Ferramenta | Versão | Motivo |
|---|---|---|
| **JDK** | 21 (LTS) | Compilação e execução do backend Spring Boot |
| **Maven** | 3.9+ | Gerenciamento de dependências e build Java |
| **PostgreSQL** | 16+ ou 18.x | Banco de dados relacional (nativo ou via Docker) |
| **Node.js & npm** | 20+ (LTS) | Build dos frontends React (`portal/`, `console/`), bots e testes E2E |

> **Nota:** Os testes unitários (`mvn test`) rodam de forma isolada e **não dependem** de banco de dados em execução.

### Passo a Passo Rápido (Execução Completa)

```bash
# 1. Preparar o Banco de Dados PostgreSQL (Escolha a Opção A ou B):

# Opção A — Usando PostgreSQL instalado localmente (porta 5432):
psql -U postgres -h localhost -c "CREATE ROLE nyxcash LOGIN PASSWORD 'nyxcash';"
psql -U postgres -h localhost -c "CREATE DATABASE nyxcash OWNER nyxcash;"

# Opção B — Usando Docker Compose (sobe o PostgreSQL já configurado):
docker compose up -d postgres

# 2. (Opcional) Compilar os Frontends (Portal do Lojista + Master Console)
#    Nota: os builds estáticos já estão incluídos em src/main/resources/static/,
#    então você só precisa rodar este passo se alterar o código em portal/ ou console/.
cd portal && npm ci && npm run build && cd ..
cd console && npm ci && npm run build && cd ..

# 3. Rodar os testes unitários para validar o ambiente
mvn test

# 4. Subir o Backend + Portal do Lojista + Master Console (porta 8080, perfil 'dev')
#    O Flyway aplicará automaticamente todas as 80 migrations (V1..V80) no primeiro boot.
FOXCASH_PORTAL_SERVE_FRONTEND=true \
FOXCASH_CONSOLE_SERVE_FRONTEND=true \
SPRING_PROFILES_ACTIVE=dev \
mvn spring-boot:run
```

> **No Windows (PowerShell):**
> ```powershell
> $env:FOXCASH_PORTAL_SERVE_FRONTEND="true"
> $env:FOXCASH_CONSOLE_SERVE_FRONTEND="true"
> $env:SPRING_PROFILES_ACTIVE="dev"
> mvn spring-boot:run
> ```

### Acessando o MVP Localmente

Com a aplicação rodando em `http://localhost:8080`:

1. **Portal do Lojista (Web):** Acesse `http://localhost:8080/login` (ou `http://localhost:8080/register` para criar uma nova conta de lojista e receber sua `X-Api-Key`).
2. **Master Console (Painel Operador):** Acesse `http://localhost:8080/console/login`
   * **Usuário padrão (perfil `dev`):** `master`
   * **Senha padrão (perfil `dev`):** `dev-only-master-seed-password`
3. **Health Check da API:** `http://localhost:8080/actuator/health`
4. **Chave Admin da API (perfil `dev`):** Header `X-Api-Key: dev-only-admin-api-key`

---

## 5. Informações Adicionais Relevantes (Documentação Técnica Detalhada)

As seções a seguir detalham a arquitetura interna, variáveis de ambiente, catálogo completo de endpoints REST, mecanismos de segurança, suíte de testes automatizados (Unitários, Mutação PITest, Integração Testcontainers, E2E Playwright e Carga k6) e guias de deploy.

> **Documentação Complementar no Repositório:**
> - **Decisões de Arquitetura (ADRs 001 a 019):** [`management/adr.md`](management/adr.md)
> - **Visão Geral de Arquitetura e Jornadas:** [`management/arch.md`](management/arch.md)
> - **Relatório de Engenharia de Capacidade e Testes de Carga (k6):** [`docs/capacity-report.md`](docs/capacity-report.md)
> - **Auditoria e Revisão de Segurança (29 vetores analisados):** [`docs/revisao-seguranca-2026-08-11.md`](docs/revisao-seguranca-2026-08-11.md)
> - **Runbooks de Operação, Backup e Disaster Recovery:** [`ops/backup-restore.md`](ops/backup-restore.md) e [`deploy/README.md`](deploy/README.md)

### Detalhes de Execução e Variáveis de Ambiente Padrão (`dev`)

A migration Flyway (`src/main/resources/db/migration/V1__init.sql` até `V80`) cria o schema
automaticamente na primeira execução.

> **Flyway (Spec 132):** versão **12.11.0** — o BOM do Spring Boot 3.5.16 fixa
> `11.7.2` (suporte testado até PostgreSQL 17), então o `pom.xml` faz override
> `flyway.version=12.11.0`, a primeira linha estável com suporte oficial a
> **PostgreSQL 18**.

Credenciais padrão de banco no perfil `dev` (sobrescrevíveis por env vars):

```bash
DB_URL=jdbc:postgresql://localhost:5432/nyxcash
DB_USERNAME=nyxcash
DB_PASSWORD=nyxcash
```

### Portal do lojista (SPA — Spec 46)

O portal do lojista é uma **SPA (React + TypeScript + Vite)** em `portal/`,
servida pelo próprio Spring Boot a partir de `src/main/resources/static` (mesmo
origin — sem CORS para o próprio domínio).

```bash
# 1. Build da SPA (gera o estático em src/main/resources/static/)
cd portal
npm ci
npm run build
cd ..

# 2. Subir o backend COM o frontend servido (default: off — API pura)
FOXCASH_PORTAL_SERVE_FRONTEND=true SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run

# 3. Acessar http://localhost:8080/login
```

- **Rotas públicas:** `/login`, `/register`, `/forgot-password`, `/reset-password`. As demais
  rotas da SPA (`/dashboard`, `/wallet`, `/charges`, `/sales`, `/destinations`,
  `/withdraw`, `/webhooks`, `/credentials`) exigem autenticação no **cliente**
  (guard redireciona para `/login`); o backend mantém os 401 uniformes da API.
- **Token:** `sessionStorage` (nunca `localStorage`), header `Authorization:
  Bearer <token>`; logout revoga no servidor e limpa o storage; interceptor 401
  → logout local + redirect (B-A8 resolvido no cliente, sem oráculo no backend).
- **CSP/CORS:** com `serve-frontend=true` a CSP muda para `default-src 'self'`
  (SPA); CORS continua desabilitado por default — apenas para origins
  explicitamente configurados (`foxcash.portal.cors-allowed-origins`), ex. SPA
  hospedada em outro domínio.
- **Em dev (Vite):** `npm run dev` na porta 5173 com proxy para o backend
  (`/api` e `/webhooks` → `localhost:8080`).

### Master Console (SPA — Spec 71)

O Master Console é a **SPA do perfil master** (React + TypeScript + Vite) em
`console/`, servida pelo próprio Spring Boot a partir de
`src/main/resources/static/console` (mesmo origin — sem CORS). Fatia 1:
leitura + ações reversíveis de OPERATOR (suspender/liberar lojista e
bloquear/liberar saques); as ações sensíveis com step-up TOTP ficam na fatia 2
(Spec 72). Desde as Specs 99/100 o console também cobre o **Painel Geral de
Usuários** (tabela rica com MED%/conversão/Pix pagos, perfil com KPIs e
taxas/limites, abas Transações e Saques, modal Ver Shops) e a **Config fina por
lojista** (`/console/merchants/:id/config` — regra de taxa, taxas/limites de
saque, rate limit, kill switch, debitar saldo, honeypot, bloquear API e banir
com step-up TOTP).

```bash
# 1. Build da SPA (gera o estático em src/main/resources/static/console/)
cd console
npm ci
npm run build
cd ..

# 2. Subir o backend COM o console servido (default: off — API pura)
FOXCASH_CONSOLE_SERVE_FRONTEND=true SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run

# 3. Acessar http://localhost:8080/console/login
```

- **Ordem de build: o console SEMPRE é construído DEPOIS do portal.** O build do
  portal (`portal/vite.config.ts`) usa `emptyOutDir: true` no `static/` raiz e
  apagaria `static/console/` — reconstrua o console por último.
- **Rotas públicas:** `/console/login`. As demais rotas (`/console/dashboard`,
  `/console/merchants`, `/console/merchants/:id/config` (Spec 100),
  `/console/withdrawals`, ...) exigem autenticação no
  **cliente** (guard redireciona para `/console/login`); o backend mantém os 401
  uniformes da API.
- **RBAC-aware (Spec 69):** a navegação e os botões são filtrados por
  `permissions[]` do `GET /api/v1/master/me`; o backend continua a autoridade
  (403 `forbidden` → banner "sem permissão", a sessão permanece).
- **Em dev (Vite):** `npm run dev` na porta 5174 com proxy para o backend
  (`/api` e `/webhooks` → `localhost:8080`), `base '/console/'`.

---

## Arquitetura Hexagonal

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

**Regra de dependência:** o domínio não conhece Spring nem banco. A aplicação
orquestra o domínio através dos **ports**. A infraestrutura implementa os **ports de
saída** e chama os **ports de entrada** (use cases).

### Estrutura de diretórios

```
src/main/java/com/foxcash/
├── FoxCashApplication.java
├── domain/                        # Núcleo de negócio (100% independente)
│   ├── model/                     # Entidades e value objects
│   │   ├── Money.java             # Value object monetário (BRL, 2 casas)
│   │   ├── merchant/              # Merchant, MerchantStatus
│   │   ├── transaction/           # PixTransaction, TransactionStatus
│   │   ├── billing/               # Invoice, InvoiceStatus, BillingPeriod
│   │   └── pricing/               # PricingPlan (R$0,50 + R$69,95)
│   ├── port/
│   │   ├── in/                    # Use cases (interfaces de entrada)
│   │   └── out/                   # Repositórios e provedor Pix (saída)
│   ├── service/                   # PricingCalculator, InvoiceCalculator
│   └── exception/                 # Exceções de domínio
├── application/
│   └── service/                   # Implementações dos use cases (@Transactional)
└── infrastructure/
    ├── config/                    # Beans (PricingPlan, calculators, properties)
    ├── inbound/
    │   ├── rest/                  # Controllers REST + DTOs + GlobalExceptionHandler
    │   └── webhook/               # PixWebhookController (callback do PSP)
    └── outbound/
        ├── persistence/           # Entidades JPA, repositories, mappers, adapters
        └── pix/                   # MockPixProviderClient (substituir pelo PSP real)
```

---

## Endpoints

### Lojistas

| Método | Rota | Descrição |
|---|---|---|
| POST | `/api/v1/merchants` | Cadastra lojista com `{name, email, password}` (sem CPF/CNPJ — Spec 39); recebe o pricing global |
| GET | `/api/v1/merchants/{merchantId}` | Consulta lojista |

### Transações Pix

| Método | Rota | Descrição |
|---|---|---|
| POST | `/api/v1/transactions` | Cria transação Pix (gera `endToEndId` via PSP) |
| GET | `/api/v1/transactions/{transactionId}` | Consulta transação |
| GET | `/api/v1/transactions/merchant/{merchantId}` | Lista transações do lojista |

### Cobranças Pix

| Método | Rota | Descrição |
|---|---|---|
| POST | `/api/v1/foxcash/pix/cob` | Gera cobrança Pix: QR Code (base64) + Pix Copia e Cola |

### Faturamento

| Método | Rota | Descrição |
|---|---|---|
| POST | `/api/v1/merchants/{merchantId}/invoices` | Gera fatura do período (idempotente) |
| GET | `/api/v1/merchants/{merchantId}/invoices` | Lista faturas do lojista |
| GET | `/api/v1/billing/preview?merchantId={id}&month=2026-08` | Prévia da fatura (sem salvar) |

### Webhook (PSP)

| Método | Rota | Descrição |
|---|---|---|
| POST | `/webhooks/pix` | Callback do provedor (aprovado/rejeitado/estornado) |

### Exemplos

**Cadastrar lojista**

```bash
curl -X POST http://localhost:8080/api/v1/merchants \
  -H "Content-Type: application/json" \
  -d '{"name":"Loja A","email":"loja.a@example.com","password":"Senha-Segura-123"}'
```

**Criar transação Pix**

```bash
curl -X POST http://localhost:8080/api/v1/transactions \
  -H "Content-Type: application/json" \
  -d '{"merchantId":"<uuid>","amount":150.00,"description":"Venda #1","pixKey":"pix@loja.com.br"}'
```

**Criar cobrança Pix (QR Code + Copia e Cola)** — gateway somente para o
vendedor: nenhum dado pessoal do comprador é capturado (o contato com o cliente
é do bot do lojista). O campo `buyerId` é **opcional e pseudônimo** (sem PII;
apenas o hash SHA-256 é persistido) — o bot deve enviar um **ID estável e
opaco por comprador** (ex.: UUID) para o antifraude funcionar (Spec 19):

```bash
curl -X POST http://localhost:8080/api/v1/foxcash/pix/cob \
  -H "Content-Type: application/json" \
  -d '{"merchantId":"<uuid>","amount":150.00,"buyerId":"<id-opaco-do-bot>","expirationSeconds":3600}'
```

**Simular confirmação via webhook** (usar o `externalReference` retornado na cobrança)

```bash
curl -X POST http://localhost:8080/webhooks/pix \
  -H "Content-Type: application/json" \
  -d '{"eventId":"evt123","externalReference":"<externalReference>","status":"PAID","amount":150.00}'
```

> O status do PSP (`PAID`) é normalizado para o domínio (`APPROVED`).

**Gerar fatura de agosto/2025**

```bash
curl -X POST http://localhost:8080/api/v1/merchants/<uuid>/invoices \
  -H "Content-Type: application/json" \
  -d '{"period":"2025-08"}'
```

---

## Regras de negócio (domínio)

1. **Transação Pix** — nasce `PENDING`, recebe `endToEndId` do provedor.
   `APPROVED` | `REJECTED` via webhook; `APPROVED` → `REFUNDED`.
2. **Precificação fixa** — tarifa de `R$ 0,50` por transação **aprovada** +
   mensalidade de `R$ 69,95`. Valores configuráveis em `application.yml`
   (`foxcash.pricing.*`).
3. **Fatura mensal** — soma `transações aprovadas × 0,50` + `69,95`, com
   vencimento 5 dias após o fim do mês. Geração idempotente por `(merchant, período)`.
4. **Lojista** — precisa estar `ACTIVE` para criar transações.

---

## Segurança

### Criptografia em repouso (AES-256/GCM)

- `merchants.document` é armazenado **criptografado** no banco via
  `CryptoConverter` (JPA `AttributeConverter`, AES-256/GCM com IV aleatório).
  Formato: `v1:<base64>`. **Desde a Spec 39 o documento é opcional** (cadastro por
  nome/e-mail/senha) — novos lojistas têm `document` nulo; o dado criptografado
  permanece apenas nos cadastros legados.
- `pix_transactions.pix_key` também é **criptografado em repouso** (mesmo
  converter, B-03/auditoria 2026-08-05). Registros legados em claro são
  reescritos no boot pelo `PixKeyEncryptionRunner` (idempotente); a leitura pela
  API permanece em claro via o converter — nenhum fluxo busca por `pix_key`
  (IV aleatório inviabiliza igualdade no ciphertext).
- **Sem dados pessoais do comprador (decisão 2026-08-04):** o gateway não
  captura nem armazena dados do cliente — o contato com o comprador é do
  **bot do lojista** (minimização LGPD). As colunas
  `payer_document`/`payer_name`/`payer_phone` foram removidas (migration V11);
  o `buyerId` pseudônimo (Spec 19) é persistido **apenas como hash SHA-256**
  (`pix_transactions.buyer_id_hash`, migration V12) — sem PII.
- Chave mestra vem de **`FOXCASH_CRYPTO_SECRET`** (`foxcash.crypto.secret`). Em
  produção ela é obrigatória; o default em `application.yml` é só para dev.
- Busca por documento usa `document_hash` (SHA-256) — o ciphertext GCM não
  permite busca por igualdade. Desde a Spec 39 o login do lojista é por
  **e-mail** (identificador único, normalizado em minúsculas) — o documento não
  é mais usado como identificador de login.

### HTTPS em produção

- `application-prod.yml` configura keystore SSL (TLS 1.3) e
  `foxcash.security.require-https=true` — o `SecurityConfig` exige canal seguro
  (`requiresSecure()`). Em dev o HTTP continua permitido.

```bash
# Produção: SPRING_PROFILES_ACTIVE=prod FOXCASH_CRYPTO_SECRET=... \
#   FOXCASH_SSL_KEYSTORE=/caminho/keystore.p12 FOXCASH_SSL_KEYSTORE_PASSWORD=... \
#   DB_URL=... DB_USERNAME=... DB_PASSWORD=... mvn spring-boot:run
```

### Proteção de logs

- `SensitiveDataLoggingFilter` sanitiza CPF/CNPJ dos payloads de request/response
  antes de logar (level DEBUG; desligável com `foxcash.logging.sanitized=false`).

### Rate limit — store selecionável (Spec 18/43, ADR-010)

O `RateLimitFilter` limita por **IP+rota** as rotas públicas de abuso (`POST
/api/v1/merchants`, logins master/portal, `forgot-password`, `reset-password`);
excedeu o limite → **429 `too_many_requests`**. O contador vive em um store
selecionável (`foxcash.security.rate-limit.store`):

- **`memory` (default):** janela fixa em `ConcurrentHashMap` — topologia
  **single-instance**, sem Redis. Nenhuma env nova necessária.
- **`redis` (multi-instância):** contador agregado via **script Lua atômico**
  (`INCR`+`EXPIRE`, TTL = `window-seconds`) — N instâncias compartilham o MESMO
  limite. Se o Redis estiver indisponível, o comportamento é **fail-open**
  (permite a requisição, `log.warn` throttled) — o gateway não para por falha
  de infra auxiliar (ADR-010).

```bash
# Multi-instância: contador compartilhado
FOXCASH_RATE_LIMIT_STORE=redis \
SPRING_DATA_REDIS_HOST=... SPRING_DATA_REDIS_PORT=6379 SPRING_DATA_REDIS_PASSWORD=... \
mvn spring-boot:run
```

Produção **single-instance** continua sem Redis (`store=memory` default).

Os **webhooks públicos** (`POST /webhooks/pix` e `/webhooks/payouts`, Spec 59) têm
rate limit **por IP** próprio (`foxcash.security.rate-limit.webhook.*`, baseline
**120/min/IP**) aplicado no topo do `WebhookSignatureFilter` — o **429 corta o
flood antes do HMAC** (sem gastar CPU no excesso); a reentrega legítima do PSP
(minutos) fica muito abaixo do teto. O WARN de assinatura inválida é throttled
(`foxcash.psp.webhook.log-throttle-seconds`, default 10s). Teto configurável em
`foxcash.security.rate-limit.webhook.*` (env via relaxed binding, ex.:
`FOXCASH_SECURITY_RATE_LIMIT_WEBHOOK_MAX_REQUESTS`).

### Autenticação da API (API keys — Spec 12)

Todos os endpoints `/api/v1/**` exigem autenticação por **API key** no header
**`X-Api-Key`**, exceto:

- `POST /api/v1/merchants` — cadastro de lojista (é onde a chave é **emitida**);
- `/webhooks/pix` — autenticado pela assinatura HMAC do PSP (Spec 10).

**Chave do lojista:** gerada no cadastro (`POST /api/v1/merchants`), devolvida
**uma única vez** no body da resposta (campo `apiKey`, formato `mch_<32 hex>`).
Apenas o hash SHA-256 é persistido (`merchants.api_key_hash`) — o texto claro
nunca fica no banco.

**Chave admin:** separada, via env var **`FOXCASH_ADMIN_API_KEY`** (obrigatória em
produção — fail-fast; em dev há um default fake). Dá acesso a
`/api/v1/admin/**` (dashboard).

| Chave | Header | Acesso |
|---|---|---|
| `mch_...` (lojista) | `X-Api-Key: mch_...` | `/api/v1/**` (exceto `/admin/**`) |
| `FOXCASH_ADMIN_API_KEY` | `X-Api-Key: <admin-key>` | `/api/v1/**` incluindo `/admin/**` |
| ausente/inválida | — | **401 `unauthorized`** |

Exemplo:

```bash
# Cadastro devolve a apiKey UMA vez:
curl -s -X POST http://localhost:8080/api/v1/merchants \
  -H "Content-Type: application/json" \
  -d '{"name":"Loja A","email":"loja.a@example.com","password":"Senha-Segura-123"}'
# => {"id":"...","apiKey":"mch_...","..."}

# Chamadas subsequentes usam X-Api-Key:
curl -s http://localhost:8080/api/v1/merchants/<uuid> \
  -H "X-Api-Key: mch_..."
```

### Credenciais API do lojista (Spec 24) — Uso no backend

O lojista gerencia **chaves de API** pelo portal (`/api/v1/portal/credentials`,
sessão `ROLE_MERCHANT_PORTAL`): listar, criar (segredo devolvido **uma única
vez**) e revogar. Limite de **5 chaves ativas** por lojista (`409
too_many_keys`). Escopo inválido → `400 invalid_argument`.

| Endpoint | Ação | Resposta |
|---|---|---|
| `GET /api/v1/portal/credentials` | lista chaves (prefixo, status, escopos, último uso — **nunca o segredo**) | 200 |
| `POST /api/v1/portal/credentials` `{label, scopes}` | cria chave | **201** `{id, secret}` (secret UMA vez) |
| `POST /api/v1/portal/credentials/{id}/revoke` | revoga chave (deixa de autenticar) | 204 |
| — | regenerar = revogar + criar nova (fluxo explícito) | — |

**Escopos** (`scopes` no create; vazio = escopo total): `charges`
(cobrança `POST /foxcash/pix/cob`), `transactions` (leitura de transações),
`withdrawals` (saques). Chave com escopo restrito acessa **apenas** as rotas do
escopo — rota fora do escopo → **403 `forbidden`**. A chave do cadastro
(Spec 12) continua válida com escopo total.

**Integração no backend do lojista (checklist):**

1. Obtenha a chave no cadastro (`apiKey` do `POST /api/v1/merchants`) ou crie
   uma nova pelo portal (escopos restritos).
2. Envie o header **`X-Api-Key: mch_...`** em toda chamada (exceto cadastro e
   webhook).
3. Revogue imediatamente qualquer chave exposta/vazada (revogar + criar nova).

```bash
# Exemplo: consultar transações com chave de escopo "transactions"
curl -s http://localhost:8080/api/v1/transactions/merchant/<merchantId> \
  -H "X-Api-Key: mch_..."

# Exemplo: criar cobrança com chave de escopo "charges"
curl -s -X POST http://localhost:8080/api/v1/foxcash/pix/cob \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: mch_..." \
  -d '{"merchantId":"<uuid>","amount":150.00,"expirationSeconds":3600,"idempotencyKey":"cob_<chave_do_cliente>"}'
```

**`idempotencyKey` é OBRIGATÓRIA na criação de cobrança** (Spec 28) — em
`POST /api/v1/transactions`, `/api/v1/foxcash/pix/cob`, `/api/v1/portal/charges` e
`/api/v1/portal/charges/gift` (ausente/vazia → `400`). A chave é **única por
lojista**: reenviar o mesmo request com a mesma chave retorna a **mesma
cobrança** (mesmo `transactionId`), nunca duplicando no PSP — essencial sob
timeout do provedor (resposta incerta → `502 psp_timeout`; o retry com a mesma
chave conclui). Erro real do PSP → `FAILED` e retry com a mesma chave →
`409 idempotency_conflict` (troque a chave).

**Produção:** a chave precisa do escopo correto e **não revogada** — chave
revogada → `401`; escopo insuficiente → `403`.

### Webhook de saída para o bot do lojista (Spec 35)

O bot do lojista é **avisado** (em vez de consultar) quando algo relevante
acontece: o FoxCash faz um `POST` assinado para a URL configurada no portal.
A configuração é **privada por lojista** (404 cross-loja), o segredo é gerado
pelo servidor e devolvido **uma única vez** no `201`, e a entrega tem **retry
com backoff exponencial** (máx. `foxcash.notification.max-attempts`, default 5).

| Endpoint | Ação | Resposta |
|---|---|---|
| `GET /api/v1/portal/webhooks` | lista webhooks (secret **nunca** exposto; `hasSecret: true`) | 200 |
| `POST /api/v1/portal/webhooks` `{url, events}` | cria webhook (segredo gerado pelo servidor) | **201** `{id, secret, ...}` (secret UMA vez) |
| `PATCH /api/v1/portal/webhooks/{id}` `{url?, events?}` | atualiza URL/eventos | 200 |
| `POST /api/v1/portal/webhooks/{id}/activate` \| `/deactivate` | ativa/desativa (204) | 204 |
| `POST /api/v1/portal/webhooks/test` `{url, secret, eventType?}` | ping assinado (não persiste) | 200 `{delivered, statusCode?, error?}` |

**Eventos** (`events`, CSV): `CHARGE_CREATED`, `SALE_PAID`, `SALE_REFUNDED`,
`CHARGEBACK_REGISTERED`, `WITHDRAWAL_COMPLETED`, `INVOICE_GENERATED`,
`INVOICE_COLLECTED`. Evento inválido → `400 invalid_event`.

**Payload (envelope padrão de todos os eventos):**

```json
{
  "eventId": "uuid",          // único por evento — o receptor deduplica re-entregas
  "type": "SALE_PAID",
  "merchantId": "uuid",
  "createdAt": "2026-08-04T12:00:00Z",
  "data": { "transactionId": "uuid", "amount": 150.00, "paidAt": "..." }
}
```

**Assinatura:** o `POST` traz os headers `X-FoxCash-Signature`
(HMAC-SHA256 hex do **raw body** com o segredo do webhook),
`X-FoxCash-Event-Id` e `X-FoxCash-Event`. O lojista **deve verificar** a
assinatura antes de processar (ex.: Node.js `crypto.createHmac('sha256',
secret).update(rawBody).digest('hex')`). Entrega 2xx → `SENT`; falha/4xx/5xx →
retry com backoff; esgotado o máx. → `FAILED` (o backstop é a consulta do
lojista — a plataforma não re-entrega após `FAILED`). Sem webhook ativo para o
evento, a notificação é apenas logada (fallback).

```bash
# Login no portal (Spec 20) e criação de webhook:
curl -s -X POST http://localhost:8080/api/v1/portal/webhooks \
  -H "Authorization: Bearer <portal-token>" -H "Content-Type: application/json" \
  -d '{"url":"https://bot.example.com/hook","events":["SALE_PAID","CHARGE_CREATED"]}'
# => {"id":"...","secret":"<64 hex — UMA única vez>","active":true,"events":["SALE_PAID",...]}

# Teste de entrega (ping assinado, não persiste):
curl -s -X POST http://localhost:8080/api/v1/portal/webhooks/test \
  -H "Authorization: Bearer <portal-token>" -H "Content-Type: application/json" \
  -d '{"url":"https://bot.example.com/hook","secret":"<secret>","eventType":"SALE_PAID"}'
# => {"delivered":true,"statusCode":200}
```

### Perfil master (login usuário/senha + sessão — Spec 13)

O **master** é o operador da plataforma: autentica por **usuário + senha**
(BCrypt) e recebe uma **sessão por token opaco** (`mst_<32 hex>`, válida 8h,
configurável via `foxcash.master.session-ttl-seconds`). Diferente das API keys,
a sessão é **revogável** no logout (ADR-007).

| Endpoint | Acesso | Descrição |
|---|---|---|
| `POST /api/v1/auth/master/login` | público | `{username, password}` → `200 {token, masterId, name, expiresAt}`; inválido/inativo → `401` |
| `POST /api/v1/auth/master/logout` | público* | revoga a sessão do token `Authorization: Bearer <token>` → `204` |
| `/api/v1/master/**` | **`ROLE_MASTER`** | rotas do master (401 sem token; 403 com role lojista/admin) |

**Painel de usuários (Spec 97 — somente-leitura, `ROLE_MASTER`):**

| Endpoint | Descrição |
|---|---|
| `GET /api/v1/master/merchants/panel` | listagem com métricas: `{id, name, email, status, transactionFee, monthlyFee, withdrawalFee, conversionRate, paidPixCount, medPercent, medOccurrences, gateway, createdAt}`; filtros `status`, `q`, `from`/`to`, `risk` (baixo/medio/alto via MED%) + `page`/`size` (cap 100) |
| `GET /api/v1/master/merchants/{id}/panel` | perfil detalhado: `{kpis: {walletBalance, grossRevenue, paidPixCount, conversionRate, storesCount}, account: {name, email, phone, documentMasked, gateway, createdAt}, feesAndLimits: {transactionFee, fixedFee, withdrawalFee, medPercent, medOccurrences, pixMin, pixMax, withdrawalMin, withdrawalMax}}`; 404 `merchant_not_found` |
| `GET /api/v1/master/merchants/{id}/transactions` | transações com venda: `{transactionId, amount, status, buyerName, buyerIdHash, productName, externalReference, fee, netAmount, createdAt}` (comprador via lead do CRM; `netAmount = amount − fee`) |
| `GET /api/v1/master/merchants/{id}/stores` | stores do lojista (espelho read-only, **sem** `social_links`); 404 lojista, `[]` sem store |

**Config fina por lojista + controle de conta (Spec 98 — escrita, `ROLE_MASTER`;**
**RBAC Spec 69 + auditoria Spec 37 + step-up TOTP em debit/ban):**

| Endpoint | Descrição |
|---|---|
| `GET /api/v1/master/merchants/{id}/config` | config atual: `{pixFeeType, pixFeeFixed, pixFeePercent, pixFeeThreshold, withdrawalFee, pixMin, pixMax, withdrawalMin, withdrawalMax, rateLimitRps, rateLimitRpm, apiBlocked, honeypot}` (nulls = sem override → default global); 404 `merchant_not_found` |
| `PUT /api/v1/master/merchants/{id}/config` | atualização **parcial** (null = não alterar — PATCH): regra de taxa `FIXED`/`PERCENT` (acima do `pix_fee_threshold` cobra `pix_fee_percent`%; até lá, `pix_fee_fixed`), `withdrawal_fee` (override do global Spec 88), limites `pix_min/max`/`withdrawal_min/max`, `rate_limit_rps`/`rate_limit_rpm` (override no `AuthenticatedRateLimitFilter`, fail-closed), `apiBlocked`; 400 `invalid_argument` em valores inválidos |
| `POST /api/v1/master/merchants/{id}/config/debit` | débito administrativo do wallet `{amount (>0), reason (obrigatório)}` → 200 `{newBalance, debitedAmount}`; saldo pode ficar negativo; ledger duplo `ADMIN_DEBIT` (Specs 27/34) + auditoria na mesma transação; **step-up TOTP** (403 `step_up_required`) |
| `POST /api/v1/master/merchants/{id}/config/honeypot` | `{enabled}`: ambiente controlado de monitoramento antifraude — cobranças roteiam para o **provedor trap** (QR `HP<uuid>`, nunca PAID real), saques bloqueados (409 `honeypot_blocked`), alerta `HONEYPOT_TRIGGERED` throttled; reversível |
| `POST /api/v1/master/merchants/{id}/config/deactivate` | kill switch → status `INACTIVE` (bloqueia portal/API/saques); auditoria `MERCHANT_DEACTIVATED` |
| `POST /api/v1/master/merchants/{id}/config/activate` | kill switch → status `ACTIVE`; auditoria `MERCHANT_ACTIVATED` |
| `POST /api/v1/master/merchants/{id}/ban` | banimento **irreversível** `{reason (obrigatório)}` → status `BANNED` (bloqueia tudo); **step-up TOTP** + auditoria `MERCHANT_BANNED`; 409 `invalid_state` se já BANNED |

\* o logout é público de propósito: a revogação é feita pelo **próprio token
apresentado** (posse do token = credencial), sem depender de autenticação da
rota.

```bash
# Login — o token é devolvido UMA vez:
curl -s -X POST http://localhost:8080/api/v1/auth/master/login \
  -H "Content-Type: application/json" \
  -d '{"username":"master","password":"..."}'
# => {"token":"mst_...","masterId":"...","name":"Master","expiresAt":"..."}

# Chamadas master usam Authorization: Bearer:
curl -s http://localhost:8080/api/v1/master/... \
  -H "Authorization: Bearer mst_..."

# Logout revoga a sessão:
curl -s -X POST http://localhost:8080/api/v1/auth/master/logout \
  -H "Authorization: Bearer mst_..." -o /dev/null -w "%{http_code}"   # 204
```

**Primeiro master (admin):** criado no boot (seed) quando a tabela `masters`
está vazia e `FOXCASH_MASTER_SEED_USERNAME`/`FOXCASH_MASTER_SEED_PASSWORD` estão
preenchidas (em dev há defaults fake; em **prod sem as env vars o master não é
criado** — o boot não falha, apenas documenta em log). O primeiro master nasce
**admin** (`can_manage_masters=true`; migration V33 faz o backfill do mais
antigo em bancos existentes). A **gestão de perfis master** (criar com senha
gerada pelo sistema e devolvida 1x, listar, ativar/desativar — limite de 4) e o
**relatório de atividades** (`platform_activity_log`: eventos de
MASTER/MERCHANT/SYSTEM com filtros por período/ator/ação/lojista) existem via
endpoints **admin-only e não-documentados** (Spec 41 — a defesa é autorização
pela flag + auditoria, não o path).

### Autenticação do lojista no portal (Spec 20/39/40)

O lojista acessa o portal por **e-mail + senha** (Spec 39 — sem CPF/CNPJ no
cadastro). Login/logout em `/api/v1/merchant-auth` (públicos, mesmo padrão do
master); as rotas `/api/v1/portal/**` exigem o Bearer da sessão
(`ROLE_MERCHANT_PORTAL`).

| Endpoint | Acesso | Descrição |
|---|---|---|
| `POST /api/v1/merchant-auth/login` | público | `{email, password}` → `200 {token, merchantId, name, expiresAt}`; inválido → `401`; não-ACTIVE → `403 merchant_not_active` |
| `POST /api/v1/merchant-auth/logout` | público* | revoga a sessão do token `Authorization: Bearer <token>` → `204` |
| `POST /api/v1/merchant-auth/forgot-password` | público | `{email}` → **dev** (`foxcash.auth.expose-reset-token=true`): `200 {resetToken}` se o e-mail existe, `204` se não; **prod** (`false`): sempre `204` (anti-enumeração) |
| `POST /api/v1/merchant-auth/reset-password` | público | `{token, newPassword}` → `204`; token inválido/expirado/usado → `400 invalid_token`; senha < 8 → `400 validation_error` |

**Recuperação de senha (Spec 40 + 45):** o token é **one-time** (`rst_<32 hex>`,
apenas o hash SHA-256 é persistido, expira em ~30min — `foxcash.auth.reset-token-ttl-seconds`,
um único ativo por lojista). O `reset-password` troca a senha (BCrypt) e
**revoga todas as sessões ativas** do lojista na mesma transação. Em produção o
token **nunca** é devolvido na resposta (`expose-reset-token=false`) — a entrega
é por **e-mail** (Spec 45): o `forgot-password` envia o token via SMTP para o
endereço da conta (e-mail inexistente executa entrega dummy — anti-enumeração
por timing; a resposta é sempre `204`). Config: `FOXCASH_RESET_TOKEN_DELIVERER`
(`log` só em dev | `smtp` em prod — `log` em prod é **rejeitado no boot**,
fail-fast), `FOXCASH_RESET_TOKEN_SMTP_*` (host/port/username/password/from),
`FOXCASH_PORTAL_BASE_URL` (link de redefinição no e-mail); credenciais SMTP ausentes
não derrubam o boot (falha logada na entrega — disponibilidade primeiro).
`forgot-password` tem rate limit por IP.

```bash
# Login no portal:
curl -s -X POST http://localhost:8080/api/v1/merchant-auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"loja@example.com","password":"Senha-Segura-123"}'
# => {"token":"mst_...","merchantId":"...","name":"Loja","expiresAt":"..."}
#    (token opaco do portal — mesmo formato mst_<32 hex> do master; apenas o
#    hash SHA-256 é persistido; documentação corrigida na homologação 2026-08-05)

# Recuperação de senha (dev — devolve o token; prod responde 204):
curl -s -X POST http://localhost:8080/api/v1/merchant-auth/forgot-password \
  -H "Content-Type: application/json" -d '{"email":"loja@example.com"}'
# => {"resetToken":"rst_..."}   (dev)
curl -s -X POST http://localhost:8080/api/v1/merchant-auth/reset-password \
  -H "Content-Type: application/json" \
  -d '{"token":"rst_...","newPassword":"Nova-Senha-Segura-456"}' -o /dev/null -w "%{http_code}"  # 204
```

### Saques — fluxo automático + controle master (Spec 14/32/36)

O lojista saca do próprio **wallet** (`wallet_balance`) de forma **automática**
(sem aprovação por saque), desde que esteja `ACTIVE`, **desbloqueado** e com
saldo suficiente. **Fonte de saldo:** cada venda **APROVADA** (webhook `PAID`)
credita o valor no wallet do lojista (idempotente — replay não credita duas
vezes). O master pode **bloquear/liberar** novos saques por lojista (bloquear
**não** estorna saques já efetivados).

**Saque via PSP (Spec 36/38):** o débito é **imediato** na solicitação, mas o saque
nasce `PROCESSING` e só vira `COMPLETED` quando o PSP confirma (webhook de
saída `POST /webhooks/payouts` com `PAID`). Em `FAILED` o valor é **estornado
automaticamente** ao saldo (ledger `WITHDRAWAL_REVERSAL`). Um job de
**reconciliação** (`foxcash.psp.payout-reconciliation.*`, default diário 04:30
UTC) encerra `PROCESSING` órfãos — consulta `getPayout` e **não** liquida
enquanto o PSP responder `PENDING` (estado intermediário de adquirentes reais).
O adapter de saída é plugável (`foxcash.psp.payout-provider`, default `mock` em
dev; **obrigatório em prod**). O **adapter real Pagar.me** (API v5 — Spec 47,
`PagarMePayoutProviderClient`) está implementado: ative com
`FOXCASH_PSP_PAYOUT_PROVIDER=pagarme` + `FOXCASH_PSP_PAYOUT_PAGARME_SECRET_KEY`
(fail-fast no boot, Spec 38). O fluxo (débito → PROCESSING → webhook/reconciliação)
**não muda** — a porta isola o adapter. Veja o runbook
**[`deploy/payout-homologation.md`](deploy/payout-homologation.md)** para o
passo de homologação (sandbox, validação de transferência + webhook de saída).

| Endpoint | Acesso | Descrição |
|---|---|---|
| `POST /api/v1/merchants/{id}/withdrawals` | chave do lojista | `{amount, destinationId}` → `201` saída `PROCESSING` (`externalReference` do PSP) e saldo debitado; sem saldo → `409 insufficient_balance`; bloqueado → `409 withdrawals_blocked`; não-ACTIVE → `409 merchant_not_active` |
| `GET /api/v1/merchants/{id}/withdrawals` | chave do lojista | lista os próprios saques |
| `POST /api/v1/master/merchants/{id}/withdrawals/administrative` | `ROLE_MASTER` | recolhimento em nome do lojista `{amount, destinationKey, reason}` (reason obrigatório) |
| `POST /api/v1/master/merchants/{id}/withdrawals/block` | `ROLE_MASTER` | bloqueia novos saques → `204` |
| `POST /api/v1/master/merchants/{id}/withdrawals/release` | `ROLE_MASTER` | libera os saques → `204` |
| `GET /api/v1/master/withdrawals` | `ROLE_MASTER` | lista paginada com filtros `merchantId`/`status`/`from`/`to` |

```bash
# Saque do lojista (chave do próprio lojista):
curl -s -X POST http://localhost:8080/api/v1/merchants/<uuid>/withdrawals \
  -H "X-Api-Key: mch_..." -H "Content-Type: application/json" \
  -d '{"amount": 50.00, "destinationId": "<uuid>"}'
# => {"id":"...","merchantId":"...","amount":50.00,"status":"PROCESSING","externalReference":"PO...","walletBalance":150.00}

# Confirmação do PSP (Spec 36) — webhook de saída (PAID -> COMPLETED):
curl -s -X POST http://localhost:8080/webhooks/payouts \
  -H "Content-Type: application/json" \
  -d '{"externalReference":"PO...","status":"PAID"}'

# Master bloqueia os saques do lojista:
curl -s -X POST http://localhost:8080/api/v1/master/merchants/<uuid>/withdrawals/block \
  -H "Authorization: Bearer mst_..." -o /dev/null -w "%{http_code}"   # 204
```

### Saque automático por limiar de saldo (Spec 70)

O vendedor ativa no portal um **saque automático**: quando o **saldo disponível**
atingir (ou passar) um **limiar** (default R$ 1.000,00), um **job agendado**
(ShedLock, `foxcash-auto-withdraw`, cron default a cada 5 min UTC) saca **todo o
saldo** para o destino escolhido — reusando todo o pipeline de saque (bloqueio
do master, inadimplência, ledger, PSP de saída, webhook de conclusão). Condição
bloqueante → **skip silencioso** (o job nunca quebra nem gera saque indevido).

| Endpoint (portal, `ROLE_MERCHANT_PORTAL`) | Descrição |
|---|---|
| `GET /api/v1/portal/auto-withdraw` | config atual; nunca 404 — sem config → `{enabled: false, threshold: default, destinationId: null}` |
| `PUT /api/v1/portal/auto-withdraw` `{enabled, threshold, destinationId?}` | upsert → 200; `threshold > 0` e `>= min` (400); `enabled=true` exige destino ativo (400/404/409) |

Config de ambiente (`foxcash.auto-withdraw.*`):

| Variável | Default | Uso |
|---|---|---|
| `FOXCASH_AUTO_WITHDRAW_ENABLED` | `true` | kill-switch global do job |
| `FOXCASH_AUTO_WITHDRAW_CRON` | `0 */5 * * * *` | cron do job (UTC) |
| `FOXCASH_AUTO_WITHDRAW_DEFAULT_THRESHOLD` | `1000.00` | limiar default do GET não-configurado |
| `FOXCASH_AUTO_WITHDRAW_MIN_THRESHOLD` | `1.00` | piso de validação do PUT |
| `FOXCASH_AUTO_WITHDRAW_MAX_PER_RUN` | `100` | teto de saques criados por execução |

### Dashboard master — transações, ganhos e taxas (Spec 15)

Somente-leitura (a única escrita do master são os controles de saque da Spec
14). Reusa as agregações do dashboard/admin (Spec 8).

| Endpoint | Acesso | Descrição |
|---|---|---|
| `GET /api/v1/master/transactions` | `ROLE_MASTER` | transações paginadas com filtros `merchantId`/`status`/`from`/`to` |
| `GET /api/v1/master/merchants/{id}/earnings` | `ROLE_MASTER` | `{walletBalance, approvedVolume, totalWithdrawn, withdrawalsBlocked}`; lojista inexistente → `404` |
| `GET /api/v1/master/merchants/{id}/fees?month=yyyy-MM` | `ROLE_MASTER` | `{transactionCount, transactionFees, monthlyFee, totalFees}` derivado das faturas; mês inválido → `400` |

```bash
curl -s http://localhost:8080/api/v1/master/merchants/<uuid>/earnings \
  -H "Authorization: Bearer mst_..."
# => {"walletBalance":100.00,"approvedVolume":500.00,"totalWithdrawn":50.00,"withdrawalsBlocked":false}
```

### Antifraude e compliance — risco + suspensão (Spec 16, ADR-008)

Um job agendado (diário, `foxcash.scheduler.risk-cron`) calcula os **indicadores
de risco** de cada lojista (sobre os dados existentes) e grava um **snapshot**
em `merchant_risk` (migration V9): volume aprovado, total/aprovadas/reembolsadas
e **taxa de reembolso** (% REFUNDED/total), com flags de alerta conforme os
limiares configuráveis `foxcash.risk.*` (chargeback 1%/3%, reembolso 5%,
denúncias 3 — chargeback/denúncias sem fonte de dados nesta versão). A
**decisão** (suspender/liberar) é **do master** — nada é automático.

| Endpoint | Acesso | Descrição |
|---|---|---|
| `GET /api/v1/master/risk` | `ROLE_MASTER` | snapshots paginados, filtro `merchantId` |
| `GET /api/v1/master/merchants/{id}/risk` | `ROLE_MASTER` | snapshot mais recente do lojista; sem snapshot → `404 risk_snapshot_not_found` |
| `POST /api/v1/master/merchants/{id}/suspend` | `ROLE_MASTER` | suspende o lojista (`SUSPENDED` — não processa nem saca) → `204` |
| `POST /api/v1/master/merchants/{id}/release` | `ROLE_MASTER` | restaura `ACTIVE` → `204` |

```bash
curl -s "http://localhost:8080/api/v1/master/risk?merchantId=<uuid>" \
  -H "Authorization: Bearer mst_..."
# => {"items":[{"merchantId":"...","approvedVolume":150.00,"refundRatePercent":0.00,"refundAlert":false,...}],...}

curl -s -X POST http://localhost:8080/api/v1/master/merchants/<uuid>/suspend \
  -H "Authorization: Bearer mst_..." -o /dev/null -w "%{http_code}"   # 204
```

### Deploy em produção (VPS — sem Docker)

Runbook completo em [`deploy/README.md`](deploy/README.md): JDK 21, PostgreSQL
nativo, JAR executável, unit `systemd`, nginx (TLS) e backup `pg_dump`. O CI
roda no GitHub Actions (`.github/workflows/ci.yml`) — sem Docker na máquina
local (o runner da GitHub tem ambiente próprio).

---

## Substituir o provedor Pix (PSP)

`MockPixProviderClient` gera um `endToEndId` fake (default de dev via
`foxcash.psp.provider=mock`). A seleção do provedor é por configuração:
`mock` | `pagarme` | `asaas` (em prod, `FOXCASH_PSP_PROVIDER` obrigatória).

- O webhook `POST /webhooks/pix` valida **assinatura HMAC-SHA256** do payload
  (header `X-Psp-Signature`, segredo `FOXCASH_PSP_WEBHOOK_SECRET`) quando
  `foxcash.psp.webhook.verify-signature=true` (sempre em prod).
- Para um PSP real, crie um adapter que implementa `PixProviderClient` e
  registre-o com `@ConditionalOnProperty` (mesmo padrão do mock) — a porta de
  saída e o domínio não mudam.
- Runbook de homologação (contrato + passos com sandbox) em
  [`deploy/psp-homologation.md`](deploy/psp-homologation.md).

---

## Testes

### Testes unitários

```bash
mvn test
```

- `MoneyTest` — aritmética e validação monetária
- `PricingCalculatorTest` — tarifa fixa (0,50/transação + 69,95)
- `InvoiceCalculatorTest` — construção da fatura
- `MerchantTest` — saldo (wallet_balance) do lojista
- `CryptoConverterTest` — criptografia AES-256/GCM (roundtrip, não-determinismo, tamper)

### Testes End-to-End (Playwright)

Suíte em `e2e-tests/` (Node.js + Playwright) com **110 testes em 11 arquivos**
(`auth`, `merchant-journey`, `security`, `portal`, `portal-ui`, `bots`,
`outbox-flow`, `psp-flow`, `concurrency`, `reliability`, `stepup`). A jornada
do lojista (`merchant-journey.spec.js`) é **serial** (estado compartilhado, 27
cenários): cadastro por nome/e-mail/senha (sem CPF/CNPJ) → cobrança Pix
(idempotência) → webhook → fatura → segurança e antifraude → portal
(credenciais, vendas, wallet, destinos de saque) → saques (bloqueio, PSP,
estorno) → auditoria do master → recuperação de senha → gestão de perfis →
storefront → planos → botões de menu → mensagens automáticas → MFA do master.

```bash
# Pré-requisitos: app rodando em dev com AS MESMAS envs do job e2e do CI
# (ci.yml). O portal-ui.spec.js (Spec 46) exige a SPA servida pelo Spring Boot
# (FOXCASH_PORTAL_SERVE_FRONTEND=true) e a suíte de contratos faz MUITO mais de
# 20 logins/min — com o rate limit de login ativo (20/min/IP) a suíte local
# rápida esbarra no 429 (flakiness documentada; ver Spec 64). O rate limit tem
# cobertura própria (unitários Spec 43 + smoke Spec 66).
FOXCASH_PORTAL_SERVE_FRONTEND=true \
FOXCASH_SECURITY_RATE_LIMIT_ENABLED=false \
FOXCASH_NOTIFICATION_ALLOW_LOOPBACK=true \
SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run

cd e2e-tests
npm install
npx playwright install chromium   # primeira vez apenas
npm test
```

O `global-setup.js` trunca **todas as tabelas de domínio** (chargebacks,
withdrawals, invoices, pix_transactions, merchants, credenciais/sessões,
masters, ledgers, auditorias, `platform_activity_log`, tokens de reset etc.)
antes de cada execução. Configuração via env vars: `API_BASE_URL`, `DB_HOST`,
`DB_PORT`, `DB_USERNAME`, `DB_PASSWORD`, `DB_NAME`.
