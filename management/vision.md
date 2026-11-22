# Visão — FoxCash

## V1 — Fundação do backend (fase 0)

**O que entrega:** um backend Java 21 + Spring Boot 3.3 em arquitetura hexagonal,
capaz de cadastrar lojistas, criar transações Pix, processar callbacks do PSP e
gerar faturas mensais com precificação fixa (R$ 0,50/transação + R$ 69,95/mês).

**Problema que resolve:** dar à plataforma white-label uma base de código
verificável e versionada — ambiente instalado, build verde, testes passando e
commit inicial — antes de qualquer evolução funcional.

**Implementado por:**
- Spec 1 (Ambiente de desenvolvimento)
- Spec 2 (Verificação do scaffold — build + testes)
- Spec 3 (Versionamento inicial — commit)

O escopo funcional completo do backend (endpoints, domínio, persistência) já
está escrito no scaffold; a fase 0 garante que ele compila, testa e é versionado.

### V2: Segurança ponta a ponta + faturamento transparente + verificação E2E
Endurece o backend FoxCash com os requisitos obrigatórios de segurança e validação:

- **Criptografia em repouso** AES-256/GCM para `merchants.document` (chave via `NYX_CRYPTO_SECRET`); busca por documento via hash determinístico (SHA-256) em `document_hash`.
- **HTTPS obrigatório em produção** (TLS via keystore) com `SecurityConfig` exigindo canal seguro quando `foxcash.security.require-https=true`.
- **Proteção de logs**: `SensitiveDataLoggingFilter` sanitiza CPF/CNPJ nos payloads logados.
- **Prévia de fatura** `GET /api/v1/billing/preview` com cálculo transparente (`totalTransactions * 0,50 + 69,95`).
- **Suíte E2E Playwright** (`e2e-tests/`) validando a jornada completa: cadastro (com verificação de criptografia no banco), cobrança Pix, webhook, prévia e geração de fatura.

**Delivered by:** Spec 4 (segurança + preview + E2E)

### V3: Conflito de cadastro tratado (documento duplicado)
O cadastro de lojista com CPF/CNPJ já existente passa a retornar **409 Conflict** com código `merchant_already_exists` e mensagem clara, em vez de um `500 internal_error` genérico. A regra de unicidade continua garantida pela constraint `uq_merchants_document_hash` no banco (backstop), e o pré-check no use case melhora a semântica da API sem quebrar a arquitetura hexagonal.

**Delivered by:** Spec 5 (merchant-duplicate-conflict)

### V4: Faturamento automático mensal (scheduler + ShedLock)
Job agendado mensal que gera automaticamente a fatura de cada lojista **ACTIVE** para o mês anterior, sem depender do `POST /merchants/{id}/invoices` manual. Usa `@EnableScheduling` + **ShedLock** (lock via tabela `shedlock` no PostgreSQL) para garantir execução única mesmo com múltiplas instâncias, e a geração permanece idempotente pela constraint `uq_invoices_merchant_period`.

**Delivered by:** Spec 6 (invoice-scheduler)

### V5: Notificação de fatura gerada (outbox + port de entrega)
Toda fatura **nova** gerada (manual ou pelo scheduler da Spec 6) registra um evento na tabela **`notification_outbox`** (padrão outbox, mesma transação). Um dispatcher agendado (ShedLock) entrega as notificações pendentes via um port `MerchantNotifier` — com adapter de exemplo que loga o evento (placeholder para e-mail/webhook reais). O outbox garante rastreabilidade e retry sem perder eventos.

**Delivered by:** Spec 7 (merchant-notification)

### V6: Dashboard administrativo — endpoints de consulta
Endpoints **somente-leitura** sob `/api/v1/admin` para a tela de dashboard/operação:

- `GET /api/v1/admin/merchants` — lojistas paginados, filtráveis por `status`, texto (`q`) e janela de criação (`from`/`to`).
- `GET /api/v1/admin/invoices` — faturas paginadas de todos os lojistas, filtráveis por `merchantId`, `status` e `billingPeriod`.
- `GET /api/v1/admin/summary` — resumo agregado: lojistas (total/por status), transações (total/por status + `approvedVolume`) e faturas (total/por status + `totalBilled`).

Segue a arquitetura hexagonal (Controller → UseCase → Service → Port → Adapter JPA), usa `PageResult<T>` no domínio (sem vazar Spring Data) e calcula as somas com `SUM`/`COALESCE` no PostgreSQL. Nenhuma escrita é adicionada — é consulta sobre os dados existentes.

**Delivered by:** Spec 8 (admin-dashboard)

### V7: Fundação de produção (sem Docker)
Prepara o FoxCash para operar de verdade, **sem depender de Docker** (a máquina não suporta WSL2):

- **CI/CD (GitHub Actions):** pipeline build → testes unitários → E2E Playwright a cada push/PR — roda no runner da GitHub (Docker embutido ou Postgres nativo), independente da máquina local.
- **Profile `prod`:** `application-prod.yml` sem defaults inseguros — HTTPS obrigatório, `NYX_CRYPTO_SECRET`/`DB_*` vindos obrigatoriamente de variáveis de ambiente.
- **Deploy sem Docker:** `mvn package` gera JAR executável; unit `systemd` + nginx (TLS) + PostgreSQL nativo + backups `pg_dump` num VPS; runbook passo a passo.
- **ADR do PSP:** decisão documentada do provedor Pix (Pagar.me/Asaas/Efí…) com critérios objetivos — isolada atrás do port `PixProviderClient`, permitindo troca sem tocar no domínio.

**Delivered by:** Spec 9 (production-foundation)

### V8: Integração com PSP — estrutura + contrato (preparação para homologação)
Prepara o FoxCash para operar com um provedor Pix **real** (Pagar.me primário, Asaas alternativa — ver ADR-005), sem acoplar o domínio ao provedor:

- **Seleção de PSP por configuração:** propriedade `foxcash.psp.provider` (`mock` | `pagarme` | `asaas`) + propriedades `foxcash.psp.*` vindas de env vars (`NYX_PSP_*`) sem default em produção — o adapter concreto (mock) continua default em dev, e a troca para um PSP real não toca o domínio (port `PixProviderClient`).
- **Verificação de assinatura de webhook:** o endpoint `/webhooks/pix` passa a validar a assinatura HMAC-SHA256 do payload (header de assinatura + segredo configurado) antes de processar — rejeita 401 quando ausente/incorreta. Sem isso, qualquer chamada falsa poderia aprovar transações.
- **Contrato de homologação documentado:** runbook passo a passo (criar conta sandbox, obter credenciais, apontar `NYX_PSP_*`, validar cobrança + webhook) — a integração HTTP real fica para uma spec futura quando houver credenciais e web disponível.

**Delivered by:** Spec 10 (psp-integration-foundation)

**Delivered by:** Spec 10 (psp-integration-foundation)

### V9: Adapter real Pagar.me — cobrança Pix via API v5
Implementa o **primeiro adapter HTTP real** do provedor Pix (Pagar.me, API v5), atrás do port `PixProviderClient` (ADR-001) — a estrutura da Spec 10 vira operação real:

- **Adapter `PagarMePixProviderClient`:** `POST /charges` na base `https://api.pagar.me/core/v5` com **auth HTTP Basic** (secret key), payload `CreateChargeRequest` (`code`, `amount` em centavos, `payment.payment_method="pix"`, `payment.pix.expires_in`) e resposta `GetChargeResponse` (`last_transaction.qr_code` → Pix Copia e Cola, `qr_code_url` → QR Code). Contrato verificado no SDK oficial arquivado `pagarme/pagarme-core-api-java`.
- **Config por env vars:** `foxcash.psp.pagarme.secret-key` (`NYX_PSP_PAGARME_SECRET_KEY`), `base-url`, `expires-in-seconds` — seleção via `@ConditionalOnProperty(provider=pagarme)` (mesmo padrão do mock).
- **Testes com MockWebServer:** reproduzem o contrato da API v5 (200 OK com QR, 4xx de erro) sem credenciais reais — a homologação final com credenciais de sandbox fica documentada no runbook da Spec 10.

**Delivered by:** Spec 11 (pagarme-adapter)

### V10: Autenticação da API — API key por lojista + chave admin
Protege os endpoints `/api/v1/**` com **autenticação por API key** (header `X-Api-Key`), seguindo o padrão do próprio Pagar.me:

- **Chave por lojista:** gerada no cadastro (`POST /api/v1/merchants`, único endpoint público de escrita), devolvida **uma única vez** na resposta e armazenada apenas como **hash SHA-256** (`merchants.api_key_hash`, migration V6, índice único) — o texto claro nunca é persistido.
- **Chave admin:** separada, via env var `NYX_ADMIN_API_KEY` (obrigatória em prod, fail-fast) — protege `/api/v1/admin/**` (dashboard da Spec 8).
- **Filtro Spring Security** (`ApiKeyAuthFilter`): resolve o header → `ROLE_ADMIN` (chave admin) ou `ROLE_MERCHANT` (hash → lojista `ACTIVE`, com o `merchantId` no principal para futuras checagens de posse). Rota sem chave/inválida → **401**; lojista tentando `/admin` → **403**.
- **Rotas públicas:** `POST /api/v1/merchants` (cadastro) e `/webhooks/pix` (assinatura HMAC da Spec 10 continua protegendo o webhook). Todo o resto exige chave.
- **E2E atualizado:** a jornada captura a chave no cadastro e envia `X-Api-Key` nas chamadas seguintes (webhook continua sem chave).

Fecha o principal gap de segurança para produção: hoje **todos** os endpoints — incluindo o dashboard admin — estão `permitAll`.

**Delivered by:** Spec 12 (api-authentication)

### V11: Perfil master — login usuário/senha + sessão (ROLE_MASTER)
Cria o **perfil master** — operador da plataforma com login próprio (usuário + senha), separado do admin/dashboard da Spec 8:

- **Tabelas `masters` e `master_sessions`** (migration V7): senha com hash **BCrypt**, sessão por **token opaco** (armazenado como hash SHA-256, expira em **8h**, revogável no logout).
- **`POST /api/v1/auth/master/login`** `{username, password}` → token (bearer) + id/nome; senha errada/usuário inativo → **401**. **`POST /api/v1/auth/master/logout`** revoga a sessão.
- **`MasterAuthFilter`** (Spring Security): `Authorization: Bearer <token>` → **`ROLE_MASTER`** (principal = masterId); rota `/api/v1/master/**` exige essa role (401 sem token, 403 com role errada).
- **Primeiro master via seed no boot** (`NYX_MASTER_SEED_USERNAME`/`NYX_MASTER_SEED_PASSWORD`, dev com default fake; em prod sem env vars não cria).

Permite múltiplos operadores com identidade própria e revogação de acesso — base para os controles de saques (Spec 14) e o dashboard master (Spec 15).

**Delivered by:** Spec 13 (master-auth)

### V12: Saques automáticos com controle master (bloquear/liberar por lojista)
Fluxo de **saques** no domínio financeiro (sobre o wallet já existente), **automático** — sem aprovação intermediária — porém **controlável pelo master por lojista**:

- **Saque automático:** `POST /api/v1/merchants/{merchantId}/withdrawals` `{amount}` debita o saldo do lojista e cria a saída (`withdrawals` table, migration V8, status `COMPLETED`/`REJECTED`). Lojista **sem saldo** → 409 `insufficient_balance`.
- **Controle master:** flag `merchants.withdrawals_blocked` (migration V8). `POST /api/v1/master/merchants/{id}/withdrawals/block` e `/release` — enquanto **bloqueado**, a tentativa de saque do lojista responde **409 `withdrawals_blocked`**; ao **liberar**, o fluxo automático volta a funcionar. Sem dependência de aprovação manual de cada saque.
- **Consulta:** lojista vê os próprios saques (`GET /api/v1/merchants/{id}/withdrawals`); master lista todos (`GET /api/v1/master/withdrawals`, filtrável por lojista/status/período).
- O saldo (`wallet_balance`) reflete apenas saques **efetivados** — o bloqueio impede novos, não estorna os já feitos.

**Delivered by:** Spec 14 (withdrawals) — auditada 2026-08-04 (saque 201/409,
block/release 204/409 com saldo intacto, filtros + 400, 401/403).

### V13: Dashboard master — transações, ganhos e taxas (leitura + execução)
**Dashboard do master** — leitura e execução sob `/api/v1/master/**` (role `ROLE_MASTER` da Spec 13):

- **Transações:** `GET /api/v1/master/transactions` — lista paginada com filtros por lojista, status e período (reusa o domínio da Spec 8, agora exposta ao master).
- **Ganhos:** `GET /api/v1/master/merchants/{merchantId}/earnings` — saldo disponível (`wallet_balance`), volume aprovado (`SUM` de `APPROVED`), total de taxas pagas (faturas) e saques realizados.
- **Taxas:** breakdown das tarifas (0,50/transação + 69,95/mês) por período — transparência total para o operador.
- **Execução:** os controles de bloqueio/liberação de saques (Spec 14) vivem aqui; leitura **e** ação num único perfil.
- Tudo somente-leitura exceto os controles explícitos — nenhuma escrita adicional no domínio financeiro.

**Delivered by:** Spec 15 (master-dashboard) — auditada 2026-08-04
(transactions/earnings/fees, 400/404, ROLE_MASTER).

### V14: Políticas de Uso e Compliance — transparência e monitoramento de risco
As **[Políticas de Uso e Compliance](../docs/politicas-uso.md)** (v1.0, Ago/2026)
transformam os valores da plataforma em **contrato verificável** com lojistas e
compradores, e dão direção técnica ao backend:

- **Transparência radical** e **suporte humanizado** (valores centrais da
  plataforma) são reforçados por políticas claras: descrição obrigatória do
  produto/preço/condições (§3.3), SLA de atendimento de 24h úteis (§3.5) e
  faturamento transparente já entregue (Specs 4/8/15).
- **Sem KYC documental e sem dados do comprador (decisão de produto,
  2026-08-04):** o cadastro do lojista exige apenas CPF/CNPJ válido (fiscal,
  Spec 18) — sem upload de RG/CNH/selfie (Spec 17 descartada). O gateway é
  **somente para o vendedor**: `POST /api/v1/foxcash/pix/cob` não recebe dados do
  comprador — o contato com o cliente é do **bot do lojista** (colunas
  `payer_*` removidas na migration V11).
- **Operação de baixo risco** (§4) com indicadores monitorados continuamente:
  chargeback > 1% (alerta) / > 3% (suspensão), reembolso > 5%, fraude (crítico),
  denúncias 3+/30 dias — decisão em [ADR-008](adr.md#adr-008-monitoramento-de-risco-chargebacks-e-suspensão-de-contas);
  executado na Spec 16 (auditada), reutilizando o status `SUSPENDED`
  existente e o painel master (Spec 13/15).
- **Consequências gradativas** (§7): advertência → suspensão temporária →
  bloqueio permanente → reserva de valor → autoridades — a suspensão já é
  representável no domínio e acionável pelo master (Spec 16); reserva de valor
  fica documentada para spec futura.

**Delivered by:** Políticas de Uso (docs) + Spec 16 (antifraude/compliance,
auditada) + decisão de produto "sem KYC" (2026-08-04).

### V15: Hardening de segurança — autorização por recurso, rate limiting e robustez
Fecha as vulnerabilidades encontradas na auditoria de segurança (teste como
vendedor + revisão de especialista), garantindo que **nenhum lojista acesse ou
escreva em nome de outro** e que as rotas públicas não sejam alvo de abuso:

- **Autorização por recurso (IDOR fechado — ADR-009):** `MerchantAccessGuard`
  valida `principal == merchantId` em todo endpoint de lojista (leitura e
  escrita). Lojista B tentando ler/criar dados do lojista A → **403
  `forbidden`** (antes: 200 — vazava CPF/CNPJ e permitia criar transações em
  nome de terceiros).
- **Rate limiting (ADR-009):** `POST /api/v1/merchants` e
  `POST /api/v1/auth/master/login` limitados por IP (janela fixa configurável,
  default 20/min) → **429 `too_many_requests`** — bloqueia brute-force no
  login master e criação massiva de contas.
- **Validação de documento (KYC):** cadastro rejeita CPF/CNPJ com dígitos
  verificadores inválidos → **400** (`DocumentValidator`).
- **Robustez da API:** parâmetros ausentes/malformados e corpo ilegível →
  **400** (antes: 500); **webhook idempotente** (replay → 2xx, não 409);
  header **`X-Request-Id`** em toda resposta; 409 de duplicado sem ecoar o
  documento.
- **Detecção contínua de CVEs:** Dependabot + OWASP dependency-check no CI.

**Delivered by:** Spec 18 (security-hardening) — implementada e auditada.

### V16: Antifraude real — chargeback, triagem em tempo real e auto-suspensão
Ataca o problema que custa dinheiro: **chargeback** (o comprador usa o produto e contesta no banco). Como o comprador não se identifica (nome aleatório), o sinal é o **buyerId pseudônimo** enviado pelo bot do lojista — um identificador opaco, **sem PII**, persistido apenas como hash SHA-256 (mesmo padrão do projeto):

- **Triagem em tempo real** ao criar cobrança (`POST /transactions` e `/pix/cob`): **única regra de bloqueio** — comprador com histórico de chargeback (`fraud_buyer_blocked`) → **409 na hora**, antes de gerar o Pix. **Velocidade nunca bloqueia** (decisão 2026-08-04: compra rápida em sequência é normal; não se bloqueia venda legítima).
- **Registro de chargeback** (lojista/bot ou master): tabela `chargebacks` (migration V12) alimenta o indicador de chargeback que antes era sempre 0 (Spec 16).
- **Auto-suspensão de lojista**: chargeback ≥ 3% (limiar duro) suspende automaticamente (`foxcash.risk.auto-suspend-enabled` para desligar); o master continua podendo liberar/suspender manualmente.
- **Score de risco 0-100** por lojista no snapshot (`/api/v1/master/risk`), derivado das regras (reembolso, chargeback, denúncias).

**Delivered by:** Spec 19 (antifraud-real) — auditada 2026-08-04 (E2E de
chargeback + teste unitário de triagem/hash + global-setup com todas as
tabelas de domínio).

### V17: Portal do lojista — backend-first (APIs + docs)
**Portal do lojista** — APIs + contratos (backend-first) que cada tela do futuro frontend consome. Decisão (2026-08-04): entregar o backend antes do frontend, com contrato documentado.

- **Fundação (Spec 20):** autenticação do lojista no portal (login usuário/senha + sessão, mesmo padrão do master na Spec 13) + convenções de resposta agregada (métricas, séries, paginação) consumíveis por um SPA.
- **Dashboard (Spec 21):** visão geral com receita bruta, vendas, conversão PIX, série de faturamento (gráfico de linha), últimas vendas e painel de pontos de atenção.
- **Carteira (Spec 22):** saldo disponível, resumo do mês (bruta/líquida/transações), entradas da semana (barras), fluxo de caixa (entradas vs saídas) — saldo sempre o calculado pela API (fluxo oficial).
- **Gerador de cobrança (Spec 23):** cobrança avulsa (valor, descrição, opções) + Gift PIX (valores predefinidos e personalizados).
- **Credenciais API (Spec 24):** gestão de chaves — ativação/revogação, escopos, último uso — com painel de uso no backend, checklist rápido e exemplo curl.
- **Vendas (Spec 25):** tabela de vendas com ID, pagador, método, bruto/líquido, status e ações; filtros e métricas de topo (volume 30d, concluídas).

**Hipóteses a confirmar pelo usuário:** o que é "Jornada do player" (gamificação do lojista vs. etapas de venda) e o que é "Gift PIX" (valores predefinidos para o bot vs. PIX presente).

**Delivered by:** Spec 20 (portal-foundation), Spec 21 (merchant-dashboard), Spec 22 (merchant-wallet), Spec 23 (pix-charge-generator), Spec 24 (api-credentials), Spec 25 (merchant-sales)

### V18: Hardening financeiro profundo — ledger, idempotência, estorno consistente e reconciliação (Fase 5)
Programa de **Fase 5** focado em eliminar qualquer possibilidade de erro financeiro no núcleo do gateway (diagnóstico profundo 2026-08-04). Quatro frentes:

- **Wallet ledger (Spec 27):** toda movimentação do saldo (crédito de venda, débito de estorno/chargeback/saque) vira entry **imutável** gravada na mesma transação, com `balance_after` e referência de origem; o saldo deixa de ser um campo solto e passa a ser **auditável** (master e lojista consultam o histórico; backfill `OPENING_BALANCE` ancora a reconciliação).
- **Idempotência de cobrança (Spec 28):** `idempotencyKey` obrigatória por lojista nas rotas de cobrança — replay retorna a **mesma** cobrança; persistência antecipada faz a transação sobreviver a timeout do PSP e o retry reusa a mesma cobrança (nunca duas no provedor); erro real → `FAILED` + 409 `idempotency_conflict`; timeout → 502 `psp_timeout` recuperável.
- **Estorno consistente (Spec 29):** fonte única de débito — chargeback **marca a transação `REFUNDED`** + debita 1× (webhook `REFUNDED` posterior vira no-op; elimina o duplo débito); webhook **tolerante a ordem** (evento fora de sequência → 204 no-op com alerta, nunca 409/500 para o PSP); chargeback duplicado → 409 `chargeback_already_exists`.
- **Reconciliação (Spec 30):** job (ShedLock) que valida `wallet_balance = balance_after` do ledger e registra issue por divergência (auditável pelo master); fatura via COUNT (sem carregar transações); escala 2 normalizada no JSON; decisão explícita de fatura-snapshot.

**Entregável:** o saldo do lojista passa a ser **imutável em origem, auditável em histórico e verificável por job** — a base de confiança financeira da plataforma antes de escalar volume.

**Delivered by:** Spec 27 (wallet-ledger), Spec 28 (charge-idempotency), Spec 29 (consistent-refund), Spec 30 (wallet-reconciliation)

### V19: Saque com destino do vendedor + bloqueio lacrado com saque administrativo do master
Requisitos do usuário (2026-08-04) sobre o fluxo de saque:

- **Destinos de saque privados e criptografados (Spec 31):** o vendedor cadastra na **sua conta** os endereços de saque — chaves Pix dos **4 tipos do Bacen** (CPF, CNPJ, celular, aleatória/EVP) **ou** chave + dados bancários (banco/agência/conta) — com validação por tipo, **criptografia em repouso AES-256/GCM** (padrão do `document`) e **privacidade total**: só o próprio lojista vê os seus (listagem sempre **mascarada**), o master não acessa.
- **Saque com destino selecionado (Spec 32):** o saque passa a exigir o `destinationId` escolhido pelo vendedor (instantâneo, mantido pelo lock pessimista — anti saque duplo/múltiplo, sem overdraft); cada saque preserva um **snapshot criptografado** do destino e registra `WITHDRAWAL_DEBIT` no ledger (Spec 27).
- **Bloqueio lacrado + saque administrativo do master (Spec 32):** com os saques bloqueados, o vendedor fica **totalmente impedido** (409 `withdrawals_blocked`, sem exceção), mas o **master pode sacar em nome do lojista** (recolhimento de taxas/chargebacks/encerramento) via endpoint administrativo — **apenas até o saldo disponível** (resultado nunca negativo), com `type=ADMINISTRATIVE`, motivo e auditoria completa.

**Entregável:** o saque sai de `{amount}` solto para um fluxo com **destino explícito, privado, criptografado e auditável**, e o master ganha controle operacional real sobre o bloqueio — sem buracos no caixa.

**Delivered by:** Spec 31 (withdrawal-destinations), Spec 32 (withdrawal-destination-flow)

### V20: Operação comercial, pagamentos reais e compliance
O backend fecha o ciclo financeiro de ponta a ponta, com qualidade de mercado, antes do frontend (adiado pelo usuário para rodada futura, 2026-08-04):

- **Faturamento que efetivamente cobra:** a fatura mensal (V15) deixa de ser só um registro — no vencimento o sistema debita o wallet (total ou parcial), formaliza a dívida restante em `receivables` e **bloqueia o saque do lojista inadimplente** (`outstanding_debt`), sem travar o recolhimento do master (Spec 33).
- **Receita da plataforma auditável:** `master_ledger` registra na mesma transação o que a plataforma ganha (taxas, mensalidade, recuperação de dívida, recolhimento administrativo) e perde (baixa de dívida) — o dono da plataforma tem a mesma blindagem do lojista (Spec 34).
- **Bot avisado, não consultando:** webhooks de saída assinados (HMAC-SHA256) entregam venda paga, estorno, saque e fatura via POST com retry e backoff — o bot do lojista conclui a entrega do produto sem polling (Spec 35).
- **Saque que movimenta dinheiro de verdade:** contrato de Pix de saída (porta + mock + slot pronto para o adquirente que o usuário escolher), estado real `PROCESSING/COMPLETED/FAILED` com **estorno automático** em falha e reconciliação de saques órfãos (Spec 36).
- **Responsabilidade administrativa:** toda ação do master (bloqueio, saque administrativo, baixa, cobrança manual, ajuste de risco) deixa trilha imutável com quem/quando/antes/depois/motivo (Spec 37).

Com isso, o backend fica pronto para mercado: ninguém saca o que não tem, a plataforma recebe o que fatura, o lojista é avisado em tempo real e o master responde por cada ação.

**Delivered by:** Spec 33 (invoice-collection-receivables), Spec 34 (master-ledger), Spec 35 (outbound-webhook), Spec 36 (payout-psp), Spec 37 (master-audit)

### V21: PIX de saída pronto para o adquirente real (payout plug-and-play)
O PIX de saída da Spec 36 (porta `PayoutProviderClient` + mock + webhook + reconciliação) fica **pronto para receber o adquirente real** que o usuário escolher (Pagar.me/Asaas — ADR-005): plugar o adapter passa a ser só "identificar o adquirente".

- **Reconciliação tolerante a estados reais:** o status da porta ganha `PENDING` (estado intermediário de adquirentes reais — ex.: payout em análise). A reconciliação **não liquida** saque `PROCESSING` quando o PSP responde PENDING (aguarda o próximo run) — antes, qualquer estado ≠ PAID era tratado como FAILED e **estornaria indevidamente** o débito.
- **Config por adquirente com fail-fast:** blocos `foxcash.psp.payout-pagarme`/`payout-asaas` (secret-key, base-url) validados no boot quando o provider for escolhido; em **produção `NYX_PSP_PAYOUT_PROVIDER` é obrigatório** (sem default — mock nunca em prod por acidente).
- **Teste de contrato do adapter:** `PayoutProviderContractTest` (harness abstrato) valida os invariantes que todo adapter real deve cumprir (externalReference no aceite, `getPayout` em PAID/FAILED/PENDING, falha síncrona lança) — o adapter do adquirente herda o teste e ganha a garantia de contrato automaticamente.
- **Runbook de homologação:** `deploy/payout-homologation.md` documenta o contrato da porta, o mapeamento de estados, o webhook de saída e o checklist para plugar o adquirente (adapter + teste herdado + credenciais + validação em sandbox).

**Entregável:** com o adquirente definido, o trabalho é só implementar o adapter (`@ConditionalOnProperty(payout-provider=<adquirente>)` + herdar o teste de contrato) e preencher as credenciais — todo o resto (reconciliação, webhook, estorno, fail-fast, docs) já está preparado.

**Delivered by:** Spec 38 (payout-acquirer-ready)

**Delivered by:** Spec 38 (payout-acquirer-ready)

### V22: Cadastro sem CPF — nome, e-mail e senha
O cadastro de conta passa a exigir **apenas nome, e-mail e senha** — sem CPF/CNPJ nem comprovação documental (decisão do usuário 2026-08-05).

- **Cadastro mínimo:** `POST /api/v1/merchants` com `{name, email, password}` — e-mail **único** (normalizado em minúsculas; duplicado → 409), senha **escolhida pelo usuário** no cadastro (mín. 8 caracteres). Não há mais senha inicial gerada nem devolução de `portalPassword`.
- **Login por e-mail:** `POST /api/v1/merchant-auth/login` com `{email, password}` (substitui o documento como identificador).
- **Documento vira opcional (legado):** `merchants.document`/`document_hash` ficam anuláveis — lojistas existentes preservados; novos cadastros não têm documento. `trading_name`/`legal_name` são substituídos por um único `name` (backfill de `legal_name`).
- **API key mantida:** a chave `mch_<32 hex>` continua gerada e devolvida **uma única vez** no cadastro (auth da API via `X-Api-Key` — Spec 12) — não é "comprovação", é credencial de integração.

**Entregável:** abrir uma conta é instantâneo — nome, e-mail e senha — sem barreira documental; o e-mail vira a identidade de login da plataforma.

**Delivered by:** Spec 39 (email-registration)

### V23: Recuperação de senha do lojista (self-service via token one-time)
O lojista que esqueceu a senha do portal se recupera **sem depender do master**: `POST /api/v1/merchant-auth/forgot-password` `{email}` gera um token one-time `rst_<32 hex>` (apenas o hash SHA-256 é persistido, expira em ~30min, um único ativo por lojista); `POST /api/v1/merchant-auth/reset-password` `{token, newPassword}` redefine a senha (BCrypt), **marca o token como usado** e **revoga todas as sessões ativas** do lojista (troca de senha invalida sessões existentes).

- **Sem infra de e-mail:** o token é devolvido na resposta em dev (`foxcash.auth.expose-reset-token=true`); em **produção** o flag é `false` e o endpoint responde sempre `204` (anti-enumeração — mesmo comportamento com e-mail existente ou não) — a entrega fica para canal futuro (e-mail/webhook do bot).
- **Segurança:** token single-use com hash SHA-256 em `password_reset_tokens`, expiração configurável, índice único parcial (um token ativo por lojista), `forgot-password` com rate limit por IP, token mascarado nos logs.

**Entregável:** o ciclo de auth do lojista fecha — cadastro (nome/e-mail/senha) → login por e-mail → recuperação self-service de senha, sem intervenção humana e sem vazar se um e-mail existe ou não.

**Delivered by:** Spec 40 (password-reset)

### V24: Gestão de perfis master + relatório unificado de atividades
O **admin da plataforma** (master com `can_manage_masters=true` — o primeiro master criado pelo seed) passa a **gerenciar os perfis master** (hoje só existe o seed da Spec 13): `POST /api/v1/master/profiles` cria um novo perfil com **senha gerada pelo sistema e devolvida uma única vez** (apenas o hash BCrypt persistido), `GET /api/v1/master/profiles` lista (nunca expõe o hash), `activate`/`deactivate` reativa/desativa. Limite rígido de **4 perfis** (`foxcash.master.max-profiles`, 5º → 409 `too_many_masters`); **não é possível desativar a si mesmo nem o último admin**; toda ação vai para o `master_audit_log` (Spec 37). A rota é **autorizada e não-documentada** (exige a flag; a defesa real é autorização + auditoria, não o path).

E o **relatório de tudo que acontece na plataforma**: nova trilha unificada `platform_activity_log` (append-only) registra eventos-chave de **MASTER** (login/logout, criação/ativação/desativação de perfil), **MERCHANT** (cadastro, login/logout, reset de senha, cobrança criada/paga/estornada, chargeback, saque solicitado/completado/falhou, fatura cobrada) e **SYSTEM** (jobs). `GET /api/v1/master/report/activity` (somente admin) consulta com filtros (período, ator, ação, lojista) e paginação — o admin enxerga **tudo em todas as contas**, vendedor e master, numa única fonte.

**Entregável:** operação segura da plataforma por até 4 perfis master, com o admin auditando qualquer ação em qualquer conta — um "log de auditoria unificado" consultável.

**Delivered by:** Spec 41 (master-profiles-activity)

### V25: Hardening de baixos da 3ª auditoria (B-01..B-06)
Corrige os 6 achados Baixos da 3ª homologação de segurança (2026-08-05): **B-01** timing side-channel no login do master (dummy BCrypt, mesmo padrão do login do lojista — elimina enumeração de username por tempo); **B-02** `reset-password` passa a ter rate limit por IP (todo endpoint público de auth limitado); **B-03** `pix_key` criptografada em repouso (AES-256/GCM via `CryptoConverter`, padrão de `document`/destinos); **B-04** o PSP de saque sai de dentro da `@Transactional` (conexão do pool não fica retida no I/O de rede, preservando a atomicidade débito+registro com retry idempotente); **B-05** o webhook de `PAID` valida o `amount` do PSP (divergência vira issue de reconciliação, sem creditar às cegas); **B-06** lockout por conta no login do lojista (mesmo padrão do master — 5 falhas → 15min, 401 uniforme sem oráculo).

**Delivered by:** Spec 42 (backlog-baixos-3a-auditoria)

### V26: Rate limit distribuído com Redis (M-05)
O rate limit por IP+rota (Spec 18) sai da memória da instância (`ConcurrentHashMap`) e ganha um **store Redis** selecionável (`foxcash.security.rate-limit.store` = `memory` | `redis`) — janela fixa com script Lua atômico (`INCR`+`EXPIRE`), consistente entre instâncias. Single-instance (VPS atual) continua sem dependência nova (default `memory`); multi-instância liga `redis` e compartilha o contador. Indisponibilidade do Redis → **fail-open** documentado (o gateway não para por falha de infra auxiliar), com log. Contrato externo inalterado (429 `too_many_requests` nas mesmas rotas públicas).

**Delivered by:** Spec 43 (rate-limit-redis)

### V27: MFA 2FA TOTP para o master (B-A5)
O operador da plataforma (perfil master — Spec 13/41) ganha **autenticação de dois fatores por TOTP** (RFC 6238, padrão Google Authenticator/Aegis): o master ativa no próprio perfil (secret base32 160 bits, `otpauth://` para o QR, **secret devolvido uma única vez** e ativação confirmada com o primeiro código do app); com 2FA ativo o login exige **senha + código TOTP** (verificação ±30s de skew, **anti-replay**, lockout de TOTP 5 falhas → 15min sem oráculo). **8 códigos de recuperação** one-time (hashed, single-use, regeneráveis) para perda do aparelho; desativação exige TOTP/recovery + senha. Tudo auditado no `master_audit_log` (Spec 37) e no `platform_activity_log` (Spec 41). Sem 2FA ativo, o login comporta-se exatamente como hoje (opcional por master — exigência global fica como evolução).

**Delivered by:** Spec 44 (mfa-totp-master)

### V28: Entrega do token de reset por e-mail
A recuperação de senha (Spec 40) fica **utilizável em produção**: o token `rst_<32 hex>` one-time é **entregue por e-mail** ao endereço da conta (porta `ResetTokenDeliverer` — adapter SMTP com fallback log em dev), sem expor o token em nenhuma resposta HTTP e **mantendo a anti-enumeração** (o `forgot-password` continua respondendo sempre 204; e-mail inexistente executa uma entrega "dummy" para uniformizar o tempo). Falha do SMTP não derruba o fluxo (pedido segue 204, erro logado) — o gateway não cai por e-mail quebrado. Rate limit do `forgot-password` evita spam. O lojista recupera a senha sozinho, em qualquer ambiente.

**Delivered by:** Spec 45 (reset-token-email)

### V29: Frontend do portal do lojista
O portal do lojista (hoje só API — Specs 20–25, 31, 32, 35, 40) ganha o **frontend web (SPA React + TypeScript + Vite)**: login/recuperação de senha, dashboard (métricas, série, últimas vendas, pontos de atenção), carteira (saldo, resumo do mês, fluxo de caixa), gerador de cobrança PIX avulsa + Gift (QR Code/Copia e Cola), vendas com filtros, destinos de saque (sempre mascarados — nunca valor em claro no DOM), saque com destino, webhooks e credenciais de API. Thin client sobre as APIs existentes (zero regra de negócio no front). **Token em `sessionStorage`** (não `localStorage`), interceptor 401 → logout/redirect; **B-A8** resolvido no cliente (backend mantém 401 uniforme; o frontend distingue contexto para UX, sem oráculo). E2E Playwright **browser-based** para as telas principais. SPA servida pelo próprio backend (mesmo origin, sem CORS para o próprio domínio).

**Delivered by:** Spec 46 (portal-frontend)

### V30: Adapter real de PIX de saída (Pagar.me/Asaas)
O saque via PSP (Spec 36/38) conecta o **adquirente real de PIX de saída** — adapter `PagarMePayoutProviderClient` (ADR-005; Asaas como alternativa) implementando `PayoutProviderClient`: `createPayout` (POST /transfers, `code` = id do saque — idempotente) e `getPayout` (GET /transfers/{id} → PAID/FAILED/PENDING), auth Basic, timeouts 5s/10s. Registrado por `@ConditionalOnProperty(payout-provider=pagarme)` (config e fail-fast já prontos da Spec 38). **Zero mudança** no núcleo (reconciliação/webhook/estorno dependem só da porta); o adapter real **herda o `PayoutProviderContractTest`** (garantia de contrato automática) e o runbook `deploy/payout-homologation.md` documenta o passo de homologação. Saques reais do lojista e administrativos do master saem do mock para o provedor de produção.

**Delivered by:** Spec 47 (payout-acquirer-adapter)

### V31: Loja white-label multi-vendedora (backend)
Cada vendedor tem **sua loja** (`stores`, 1:1 com modelagem 1:N) configurável
**100% pelo painel** e servida por um **storefront** (`GET /nyx/storefront`) que
é a **fonte de verdade** para os bots renderizarem — identidade (nome, logo,
banner, descrição, redes sociais, cor, emoji, título, rodapé), **botões do
menu** (Spec 50: comprar/prévia/dúvidas/redes/link/close, na ordem escolhida),
**planos visuais** (Spec 49: imagem, cor do embed, destaque) e **mensagens
automáticas** (Spec 51: por etapa + programadas). **Ações de entrega por plano**
— o vendedor define exatamente o que acontece quando o pagamento aprova:
`grant_role`, `send_message`, `send_link`, `add_to_group`, `send_file` — com
**entrega idempotente e progresso por ação** (`plan_deliveries` +
`plan_delivery_actions_exec`, unique por transação, snapshot, retry, e **lock
pessimista `SELECT ... FOR UPDATE` na conclusão** — completes concorrentes da
mesma ação viram replay idempotente: 1 execução, 1 auditoria).
Compra vinculada (`pix_transactions.plan_id`), antifraude e idempotência
(Spec 19/28) intactas; rastreabilidade no `platform_activity_log` (Spec 41).

**Delivered by:** Specs 48 (stores-storefront), 49 (planos-entregas),
50 (botoes-menu), 51 (mensagens-automaticas)

### V32: Bots renderizadores da loja (Discord + Telegram)
Dois **bots renderizadores** (Node.js + TypeScript: discord.js no Discord,
grammY no Telegram) que são **100% orientados por configuração**: baixam o
storefront (`GET /nyx/storefront`, Specs 48–51) e renderizam o fluxo da loja —
boas-vindas + menu (botões na ordem), planos (embed com imagem/cor), `/comprar`
cria a cobrança vinculada (`POST /plans/{id}/purchase`) e envia o **QR + Pix
Copia e Cola**; o cliente paga → o FoxCash aprova e publica `SALE_PAID` (webhook
de saída assinado, Spec 35) → o bot valida o HMAC, deduplica por `eventId`,
busca a entrega e executa **ação por ação** (cargo, mensagem, link, grupo,
arquivo) confirmando cada uma; **entrega automática** com recuperação por
**polling** (bot offline) e fila de reprocessamento em memória. Alterar a loja
no painel não exige reiniciar bots. **Bots 100% stateless** (sem SQLite/JSON —
decisão 2026-08-05): o backend (PostgreSQL) é a única fonte de verdade e guarda
`platform` + `platform_user_id` do destinatário (Spec 49), então a entrega
sobrevive a reinício/substituição do bot consultando a API. ACL de comandos
admin.

**Delivered by:** Spec 52 (bots-renderizadores)

### V33: Robustez da execução de jobs (threads, métricas e alertas)
Infraestrutura de execução robusta para os 6 jobs do backend (faturas, cobrança/receivables, risco, reconciliação do wallet, dispatcher de outbox/notificações — um único scheduler cobre os dois — e reconciliação de saques), que hoje rodam in-process com `@Scheduled` + ShedLock mas sem controle:

- **Pool de threads dedicado** (`ThreadPoolTaskScheduler`, `foxcash.scheduler.pool-size`) — o outbox (poll 30s) deixa de ficar refém de cron longo e vice-versa.
- **Métricas Micrometer por job** (`nyx_job_duration_seconds`, `nyx_job_success_total`, `nyx_job_failure_total`, tag `job`) expostas em `/actuator/prometheus` — duração e resultado de cada execução.
- **Trilha auditável `job_runs`** (job, started_at, duration_ms, status, error) com retenção configurável — base para consulta e auditoria operacional.
- **Alerta de falha via outbox** — evento `JOB_FAILED` (plataforma, sem merchant) publicado no `notification_outbox` (agora com `merchant_id` nullable) e entregue a um **webhook de operador** com retry/backoff assinado (reuso do pipeline da Spec 35).
- **Abstração única de execução** (`JobExecutionService`) — cronometra, registra métrica, grava `job_runs` e captura falha (log estruturado + métrica + outbox); os 6 schedulers delegam a ela.

**Worker separado fica adiado** (spec futura, depende de escala real) — o outbox já é o "fio" que permite separar o processamento de eventos sem tocar no domínio; o ShedLock continua garantindo execução única mesmo com worker + réplicas.

**Delivered by:** Spec 53 (job-robustez)

### V34: Alerta de evento de outbox esgotado (OUTBOX_DEAD)
Alerta operacional quando uma entrega de webhook de saída (Spec 35) **esgota as tentativas** (max=5 → `FAILED`): o `NotificationProcessor` publica um evento **`OUTBOX_DEAD`** (plataforma, sem merchant) na **mesma transação** do FAILED, com `{eventId, originalType, merchantId, error, attempts}` do evento morto no payload → entregue ao **webhook de operador** (infra da Spec 53) com retry/backoff assinado.

- **Sem DLQ física:** o evento `FAILED` permanece na própria tabela como trilha auditável (status + last_error + attempts + payload); o **alerta é o mecanismo operacional** de visibilidade.
- **Anti-cascata:** um `OUTBOX_DEAD` que também esgota NÃO gera outro — evita cadeia infinita de alertas sobre alertas.
- **Fail-open:** sem webhook de operador configurado, o alerta é métrica + log + trilha (o evento `OUTBOX_DEAD` vai a SENT por fallback log, não acumula).
- **Fechamento do ciclo dos 5 fluxos do outbox** coberto pelo E2E `outbox-flow.spec.js` (cenário 5 agora valida a publicação do alerta).

**Delivered by:** Spec 54 (outbox-dead-alert)

### V35: Health de produção sem falso-negativo
O health agregado do actuator deixa de reportar **DOWN** por infra opcional: o indicador de e-mail (`MailHealthIndicator`, auto-configurado pelo `spring-boot-starter-mail` da Spec 45) é desabilitado por default — SMTP não configurado não derruba o health público (503) nem o agregado. Liveness/readiness permanecem UP e o indicador pode ser religado via `NYX_MAIL_HEALTH_ENABLED=true` quando o SMTP estiver realmente configurado.

**Delivered by:** Spec 55 (health-mail-indicator)

### V36: Compressão de resposta (gzip)
Respostas HTTP JSON passam a trafegar **comprimidas** (`content-encoding: gzip`) quando o cliente envia `Accept-Encoding: gzip` — storefront, vendas, relatórios e listagens paginadas gastam menos banda e carregam mais rápido. Compressão nativa do Tomcat (`server.compression.enabled=true`, mime-types JSON/XML/texto, mínimo 1KB), sem dependência nova e com compatibilidade total para clientes sem o header.

**Delivered by:** Spec 56 (response-compression)

### V37: Tracing distribuído (OTLP)
O backend ganha **tracing distribuído** via Micrometer Tracing (bridge OpenTelemetry) + exporter OTLP: spans de requisições HTTP/JDBC com `traceId`/`spanId` propagados nos logs estruturados e exportados a um collector (Tempo/Grafana/Jaeger). Sampling configurável (default 10%) e **fail-open**: sem collector configurado a app não derruba nem bloqueia — o tracing é observabilidade, não dependência de disponibilidade.

**Delivered by:** Spec 57 (distributed-tracing)

### V38: Rate limit por usuário/chave
O rate limit deixa de ser só por IP nas rotas públicas e passa a limitar **por usuário/chave autenticado** nas rotas transacionais: um lojista (ou chave vazada) não consegue mais gerar carga ilimitada no core (cobrança, purchases, portal). Cap de cobranças pendentes por lojista (409) evita acúmulo de custo PSP; admin/master têm limite por token; rotas autenticadas ganham rede de segurança por IP.

**Delivered by:** Spec 58 (rate-limit-autenticado)

### V39: Proteção contra webhook flood
Os endpoints públicos de webhook (`/webhooks/pix`, `/webhooks/payouts`) ganham rate limit por IP (teto generoso que não quebra a reentrega legítima do PSP) e o log de assinatura inválida passa a ser throttled — um flood de webhooks (com ou sem segredo válido) deixa de saturar processamento/DB e de explodir o volume de logs.

**Delivered by:** Spec 59 (webhook-rate-limit)

### V40: Hardening de infraestrutura (Tomcat/Hikari/JVM)
A infraestrutura deixa de rodar com defaults que não resistem a pico: Tomcat com limites explícitos (conexões, fila de aceite, timeouts de conexão/keep-alive — anti slow-connectors e DoS de sockets), pool Hikari dimensionado ao throughput e ao Postgres, e teto de heap JVM explícito via `JAVA_OPTS` coerente com a RAM da instância Railway — sem OOM/restart loop sob carga real.

**Delivered by:** Spec 60 (infra-hardening)

### V41: Escalabilidade horizontal e alta disponibilidade (N instâncias)
O backend passa a rodar **N instâncias** com alta disponibilidade. O app já é majoritariamente stateless (sessões DB-backed, ShedLock no Postgres, idempotência com locks pessimistas em banco); a spec remove os bloqueios restantes: rate limit distribuído via Redis (`NYX_RATE_LIMIT_STORE=redis`, Spec 43), `server.shutdown=graceful`, readiness/liveness para orquestração, auditoria de estado em memória. No deploy, o Railway ganha réplicas + healthcheck + zero-downtime (railway.json). A verificação com 2 instâncias (docker compose local e/ou Railway) prova que o sistema escala horizontalmente sem perda/duplicidade e que uma instância pode falhar sem derrubar o serviço (HA) — com runbook de HA documentado.

**Delivered by:** Spec 61 (horizontal-scalability-ha)

### V42: Capacity Engineering & High-Throughput Transaction Processing
Transforma o FoxCash de "HA funcionando" (Spec 61) em arquitetura com **capacidade mensurável** e caminho de escala para **≥ 5.000 vendas/s sustentadas** e 1M+ usuários, sem sacrificar integridade financeira.

**Problema que resolve:** a Spec 61 provou HA (stateless, réplicas, zero-downtime), mas não entrega um **número real de capacidade**. Sem capacity model, não se sabe quantas operações uma venda gera (5.000 vendas/s × ~8 operações = ~40.000 operações/s), e o PostgreSQL pode virar o gargalo escondido — pool, locks, deadlocks, WAL e cache hit ratio não são dimensionados com evidência.

**O que entrega:**
- **Capacity model** — 1 venda = quantas requests/queries/writes/Redis/outbox/webhooks; dimensionamento baseado em operações/s, não req/s.
- **PostgreSQL como alvo principal** — queries/writes por venda, N+1, índices, locks, duração de transações, deadlocks, pg_stat_statements, slow queries, WAL/IOPs/CPU/cache hit ratio, conexões; **PgBouncer** para pooling (mais conexões pode piorar throughput por contenção).
- **Separação leitura/escrita** — réplicas de leitura; ledger e finanças permanecem no caminho consistente de escrita (saldo nunca depende de réplica atrasada).
- **Ledger/wallet como "zona sagrada"** — provar `wallet_balance == SUM(ledger_entries)` durante e depois de carga pesada; idempotência sob throughput alto (10.000 reqs/mesma key → 1 transação, 0 duplicações).
- **Redis como infraestrutura de escala** — latência, throughput, pool, eviction, failover, comportamento com Redis down, atomicidade do rate limit; Redis Cluster se benchmark demonstrar necessidade.
- **Outbox com caminho crítico curto** — REQUEST → validate → idempotency → DB transaction → ledger → outbox → COMMIT → RESPONSE; webhook/bot/notification/delivery/reconciliation fora do caminho crítico.
- **Load test por estágio** (100 → 10.000 vendas/s) com métricas completas (throughput, p50/p95/p99, 4xx/5xx, DB CPU/conexões, Hikari active/pending, Redis latency/ops, JVM heap/GC, WAL, lock contention, deadlocks, ledger reconciliation, duplicações, outbox backlog).
- **Teste de explosão** — 5k → 20k até quebrar; quebra bonita com 429/backpressure, nunca Postgres/pool/JVM morto ou saldo inconsistente.
- **Scale-out linear** — 2 → 4 → 8 → 16 instâncias; se o throughput não escalar, a Spec encontra o gargalo central.
- **Número real de capacidade** — termina com um "FoxCash Capacity v1" medido (ex.: vendas/s sustentadas, p95, 0,00% 5xx, 0 duplicações, 0 perda de ledger/outbox, nº de réplicas).

Princípio: **Medir → localizar gargalo → corrigir → medir novamente → provar integridade → escalar.**

**Não faz parte:** trocar PostgreSQL por outro banco sem evidência; microservices por moda; aumentar threads/connection pool arbitrariamente; remover rate limiting; sacrificar consistência financeira por throughput.

**Delivered by:** Spec 62 (capacity-engineering-high-throughput)

### V43: Edge/WAF e bot mitigation (Cloudflare na frente do Railway)
Proteção de borda de nível de mercado na frente do edge do Railway: **Cloudflare como proxy/DNS** (DDoS protection gerenciado, TLS, WAF com regras gerenciadas e customizadas, **rate limiting na borda** por IP/rota e **bot mitigation** com Turnstile no login/cadastro do portal). O rate limit distribuído da app (Specs 43/58/59/61) continua como controle final — o edge reduz a carga e absorve floods ANTES de chegar no app, sem alterar o contrato de API nem o `trust-proxy` (X-Forwarded-For já tratado). Verificação com ataques simulados (flood, SQLi pattern, bot) e rollback por DNS (proxy OFF).

**Delivered by:** Spec 63 (edge-waf-bot-mitigation)

### V44: Observabilidade de segurança, alerting e resposta a incidentes
Operação preparada para ataques reais: **alerting** sobre os indicadores que já existem (métricas Prometheus `/actuator/prometheus` — 5xx, readiness, pool Hikari, heap — e eventos de segurança do `platform_activity_log`/outbox: pico de 401/403, 429 em massa, falha de assinatura de webhook, fail-open do rate limit), entregues ao **webhook de operador** (infra das Specs 53/54) e/ou canal de métricas (ex.: Grafana Cloud). **Runbook de resposta a incidente de segurança** (detectar → conter → mitigar → restaurar) reusando os runbooks validados (backup/restore, HA, redeploy) + teste de mesa, e **retenção/busca de logs** para investigação pós-incidente.

**Delivered by:** Spec 64 (sec-observability-incident-response)

### V45: Otimização do caminho do charge e drenagem do outbox
Otimização de desempenho dirigida por evidência, derivada da campanha de capacity (Spec 62): **(1) caminho do charge** — eliminar as 5-6 queries redundantes de autenticação por venda (join/consolidação + cache curto-TTL da resolução de API key com invalidação na revogação/regeneração e re-leitura do status do lojista; throttle do `last_used_at` que aparece como `update merchant_api_keys` 41ms de média no `pg_stat_statements`); **(2) drenagem do outbox** — tuning do dispatcher (batch maior, poll menor, múltiplos lotes por lock) sem re-arquitetura; **(3) prova** — re-benchmark dos estágios 600/750/s da F7 com comparação antes/depois (vendas/s sustentadas, p95 charge, queries/s). Integridade financeira e semântica de auth (401 imediato na revogação) inegociáveis; cache auditado para N instâncias (Spec 61).

**Delivered by:** Spec 65 (charge-path-outbox-optimization)

### V46: Resiliência operacional pós-análise
Fechamento operacional derivado da análise técnica completa de 2026-08-10 (todos os subsistemas auditados e documentados em `docs/analise-tecnica-2026-08-10.md`, com riscos → tarefas imediatas e notas por subsistema → roteiro de melhorias em `docs/roteiro-melhorias-2026-08-10.md`). Três entregas: **(1) rollback de edge completo** — registra `api.foxcash.example.com` como custom domain de origem no Railway para que proxy OFF restaure HTTPS (fecha o critério 9 da Spec 63, que falhava com handshake reset); **(2) alertas de segurança agregados entre réplicas** — contadores de rajada do `SecurityAlertPublisher` (Spec 64) passam a ser agregados via Redis (script Lua atômico, **fail-open** com fallback local — ADR-010), tornando o limiar global (N réplicas somam) para `LOGIN_FAILURE_BURST`/`RATE_LIMIT_BURST`/`SIGNATURE_FAILURE_BURST`/`BODY_INSPECTION_BLOCKED`; **(3) monitoramento proativo do body inspection** — métrica Micrometer `nyx.body_inspection.blocks` (tags pattern/category) no Prometheus + runbook de calibração (evidência → classificar ataque vs falso positivo → afinar padrão ou `NYX_BODY_INSPECTION_ENABLED=false`) + cron do `ops/monitor-body-inspection.sh`.

**Delivered by:** Spec 66 (resiliencia-operacional)

### V47: Operator Bot — comando e controle via Discord
Bot de operação no Discord (canal do operador) para **comando e controle sem abrir Railway/terminal**, complementando os alertas automáticos da Spec 66. **Saúde**: `!saude`/`!conexoes`/`!outbox`/`!jobs`/`!latencia`/`!deploy` (healthcheck, `pg_stat_activity`, fila de notificações, `job_runs`, p95/p99 via Prometheus, status Railway). **Negócio**: `!vendas`/`!chargebacks`/`!lojistas`/`!receita`/`!top5` (transações aprovadas, chargebacks, cadastros, faturamento do mês, ranking de lojistas). **Segurança/auditoria**: `!alertas`/`!bloqueios`/`!ip`/`!reconciliacao`/`!log <requestId>` (outbox de segurança, bloqueios do body inspection, investigação por requestId, divergências wallet×ledger). **Agendados**: resumo diário (09:00), semanal (seg 09:00) e alerta de anomalia (vendas/hora < 50% da média). **Ações leves**: `!bloquear <ip>` (regra WAF no Cloudflare com o token escopado) e `!ratelimit`. O bot é **read-only** no banco (exceto bloquear via API); segredos por env.

**Delivered by:** Spec 67 (operator-bot)

### V48: Testes de contexto — configuração e boot verificáveis no `mvn test`
Fechamento da lacuna de verificação exposta pela auditoria da Spec 66 (2026-08-11): a chave `alert:` duplicada no `application.yml` quebrou o boot sem que `mvn test` pegasse (não havia nenhum `@SpringBootTest`/teste de contexto que parseie o YAML completo — só os jobs de CI que sobem a app pegariam, e nunca rodaram). Entrega **4 camadas de teste de contexto**: (1) **parse do YAML de config** — carrega TODOS os `application*.yml` (base/dev/prod/railway) com o MESMO parser do boot (`YamlPropertiesFactoryBean`/SnakeYAML) e falha em chave duplicada/sintaxe no `mvn test` local, sem banco (regressão direta do caso L16); (2) **seleção condicional de beans** — `ApplicationContextRunner` valida `alert.store`/`rate-limit.store` `memory`/`redis`/default (`matchIfMissing`)/valor inválido (nenhum bean → contexto falha); (3) **binding de properties** — `SecurityAlertProperties` via `Binder` contra os defaults reais do yml + overrides; (4) **smoke de contexto completo** (`@Tag("context")`, excluído do `mvn test` default por não exigir banco no job de teste) — `@SpringBootTest` com perfil dev + Postgres local/CI sobe a app real e valida fiação + `/actuator/health` UP (pega também a classe de bug do L10 — 2 beans `Duration`). CI: o job `test` ganha Postgres e roda o grupo (`mvn -B test -Pcontext-tests`). Resultado: a classe de erro "config que só aparece em produção" passa a falhar no `mvn test` local, antes do push.

**Delivered by:** Spec 68 (context-tests)

### V49: Autorização do master em 2 níveis + step-up MFA em ações sensíveis
Fecha o gap de autorização administrativa: hoje qualquer master ativo (ROLE_MASTER) executa todas as escritas administrativas (suspender lojista, bloquear saques, saque administrativo, write-off de receivable, cobrança manual de fatura) — autorização binária sem distinção de nível. Entrega **duas camadas** que completam a cadeia `autenticado → ROLE_MASTER → permissão da ação → step-up (se sensível) → executa → audit log`:

1. **Autorização em 2 níveis** — catálogo `MasterPermission` (8 permissões, namespace por domínio colado ao `MasterAuditAction`) como contrato e concessão **por nível** (decisão do usuário 2026-08-11): todo master operacional (`can_manage_masters=false`) nasce com o **bloco completo** (OPERATOR+FINANCE+AUDITOR juntos — suspender/liberar lojista, bloquear/liberar saques, saque administrativo, write-off, cobrança manual), e o **ADMIN** (`can_manage_masters=true`, função separada) soma a **gestão de perfis master**. Guard por permissão **nos services** (protege HTTP + Operator Bot + futuro Master Console na mesma transação); deny-by-default apenas na função admin.
2. **Step-up MFA em ações sensíveis** — saque administrativo, write-off, cobrança manual e gestão de perfis master exigem re-autenticação TOTP numa janela curta (15 min) por sessão (`stepped_up_at` em `master_sessions`), reutilizando o `verifySecondFactor` existente (anti-replay, recovery code single-use, lockout de 5 falhas). A API expressa com `403 { code: "step_up_required" }` (não 401 — que derrubaria o cliente). `change-password` (SEC-25) limpa as janelas de step-up junto com as sessões.

O contrato HTTP das rotas existentes **não muda** (autorização por nível + step-up são camadas de autorização) — **sem mudança de payload** (`POST/GET /profiles` permanecem como hoje; novos perfis nascem OPERATOR). A identidade/MFA/auditoria do master já existiam e estavam auditadas (Specs 13/44/37/41); esta spec entrega exatamente a "camada do meio" que faltava: permissão da ação + step-up.

**Ampliação (2026-08-11):** a spec também fecha o **bypass de cardinalidade do SEC-23 no lado master** (`RateLimitKey.VARIABLE_PREFIXES` ganha `/api/v1/master` + `/api/v1/admin` — N lojistas/recursos passam a gastar 1 bucket do limite por principal, mesmo padrão já aplicado às rotas do lojista), adiciona **rate limit ao endpoint de step-up** (superfície de verificação de código), garante que **masters não-admin preservam acesso** (o modelo de 2 níveis não remove capacidade; sem migration de dados), cobertura **E2E dos fluxos master** (403 forbidden na gestão de perfis para OPERATOR, 403 step_up_required, janela expirada, change-password mata step-up) e o **default do Operator Bot** (restrito a ações não sensíveis; suspend/release já auditam via `RISK_ADJUSTMENT`).

**Delivered by:** Spec 69 (rbac-master-stepup)

### V50: Saque automático por limiar de saldo (auto-withdraw)
O vendedor ativa no portal um **saque automático**: quando o **saldo disponível no wallet** atingir (ou passar) um **limiar configurável** (default R$ 1.000,00), o sistema saca **todo o saldo** automaticamente para um **destino específico escolhido pelo vendedor** (decisões do usuário 2026-08-11: gatilho = saldo, valor = saldo total, destino = próprio, ativação = painel do portal).

- **Config no portal** (`GET/PUT /api/v1/portal/auto-withdraw`, `ROLE_MERCHANT_PORTAL`): `enabled` + `threshold` (default da plataforma) + `destinationId` (destino do vendedor, ativo; 404/409 conforme o saque manual); auditoria `AUTO_WITHDRAW_SETTINGS_UPDATED` (Spec 41).
- **Job agendado** `foxcash-auto-withdraw` (ShedLock + `JobExecutionService`, Spec 53 — `job_runs` + métricas `nyx_job_*`; cron default `0 */5 * * * *` UTC; kill-switch `foxcash.auto-withdraw.enabled`): para cada config ativa, se `wallet_balance >= threshold` sob o lock pessimista do lojista → cria saque MERCHANT `PROCESSING` **no valor do saldo total**, **reusando** Fase A (débito + ledger `WITHDRAWAL_DEBIT` na mesma transação) e Fase B (PSP fora da transação + `WithdrawalSubmissionPersister`) do saque existente (Specs 14/32/36).
- **Respeita as regras existentes**: ACTIVE, desbloqueio do master, inadimplência (receivable) e destino ativo — condição bloqueante → **skip silencioso** (nunca quebra o job nem gera saque indevido); falha síncrona do PSP → estorno automático (mesmo fluxo do saque manual); conclusão via webhook de saída/reconciliação (evento `WITHDRAWAL_COMPLETED` → webhook do bot); atividade `AUTO_WITHDRAW_REQUESTED` (actor SYSTEM). Falha do payout → saldo restaurado → próxima execução re-tenta (auto-retry natural).
- **Frontend**: card "Saque automático" na tela de Saque do portal (toggle + limiar + destino).

O vendedor não precisa mais acompanhar o saldo para sacar — o dinheiro das vendas vai para o destino escolhido assim que cruza o limiar, sem ação manual.

**Delivered by:** Spec 70 (auto-withdraw)

### V51: Master Console — frontend web do perfil master
Primeiro frontend para o perfil master — o backend master está **100% implementado e auditado** (auth/MFA Specs 13/44, dashboard 15, perfis 41, auditoria 37, saques 14/32/36, financeiro 33/34, risco 16/19, reconciliação 30, RBAC+step-up 69), e hoje a única interface administrativa é o Operator Bot (Spec 67). O console é uma **SPA React + TypeScript + Vite** (mesmo padrão da Spec 46 — thin client, servida pelo próprio Spring Boot) que consome exclusivamente as APIs `ROLE_MASTER` existentes, **sem mudar contrato HTTP existente** além de **3 endpoints novos, aditivos e somente-leitura** (decisões do usuário 2026-08-11): `GET /api/v1/master/me` (perfil do master autenticado com `permissions[]`/`role`/`canManageMasters`/`mfaActive` — espelho do `/portal/me` do lojista; o `MasterLoginResponse` não é alterado), `GET /api/v1/master/merchants` e `GET /api/v1/master/lookup/ip/{ip}` (espelhos read-only dos `/api/v1/admin/**`, que exigem `ROLE_ADMIN` e ficam fora do alcance do master).

Escopo entregue em **2 fatias** (decisão do usuário 2026-08-11):
- **Spec 71 (fatia 1 — leitura + OPERATOR, sem step-up):** login+MFA, UI RBAC-aware, e as telas de leitura (Dashboard master e transações, lojistas com earnings/fees/risco/ledger/receivables, saques, financeiro/ledger do master, auditoria, relatório de atividades, reconciliação, lookup por IP) + as ações reversíveis de OPERATOR (suspender/liberar lojista, bloquear/liberar saques).
- **Spec 72 (fatia 2 — FINANCE/ADMIN + step-up):** ações sensíveis com **step-up TOTP no cliente** (403 `step_up_required` → prompt → `POST /api/v1/master/mfa/step-up` → repete a ação; janela 15 min; nunca 401): saque administrativo, write-off de receivable, cobrança manual de fatura e gestão de perfis master com RBAC.

Princípios (aplicados às duas fatias):
- **UI RBAC-aware por papel** (ADMIN/OPERATOR/FINANCE/AUDITOR da Spec 69): a UI **esconde ações sem permissão**, mas o **backend decide** (403 `forbidden`, deny-by-default) — princípio consolidado na discussão de arquitetura 2026-08-11.
- **Login com MFA** (Spec 44): usuário/senha + TOTP quando ativo; sem MFA, senha apenas; autogestão de MFA no console (activate/confirm/deactivate/recovery codes).

Resultado: o operador da plataforma ganha uma interface web completa e segura — as ações aparecem conforme a permissão, as sensíveis exigem re-prova de posse (TOTP) e toda decisão de autorização permanece no backend.

**Delivered by:** Spec 71 (master-console-fatia-1) · Spec 72 (master-console-fatia-2)

### V52: Frontend UX — fluxos e usabilidade (console + portal)
Camada de usabilidade sobre os dois frontends (Master Console Specs 71/72 e Portal do Lojista Spec 46+): feedback de ações consistente e anunciável (SuccessBanner/ErrorBanner com `role=status`/`role=alert`; toda ação de escrita com sucesso/erro visível — sem unhandled rejection), diálogos acessíveis (focus trap, Escape, autofocus, `aria-labelledby`/`aria-describedby`, restauração de foco; `ConfirmDialog` com tom `danger`/`primary`; confirmação para ações destrutivas irreversíveis no portal), estados de carregamento/vazio uniformes (Spinner em todo loading; empty state em toda lista/tabela; recarga não zera a lista visível), navegação (skip-link \"Pular para o conteúdo\", nome real do lojista no detalhe) e formulários consistentes (componente `Field` com `required`, validação com mensagem clara, botões \"Copiar\" com feedback). Sem mudança de contrato HTTP — camada puramente de apresentação (CSS puro, sem lib de UI).

**Delivered by:** Spec 73 (frontend-ux)

### V53: Registro central de bots de venda e métricas
Camada de gestão central dos bots de venda (Discord + Telegram), que hoje são renderizadores config-driven (Specs 48/50/52) sem registro próprio. A **Spec 74** cria a tabela `store_bots` — bot pertence a um lojista, `platform` telegram/discord, token **criptografado em repouso**, máquina de estados `REGISTERING/ACTIVE/PAUSED/ERROR`, `webhook_secret` aleatório por bot e soft-delete — com CRUD no portal (o lojista gerencia os próprios bots) e espelho no Master Console com RBAC (Spec 69). Integração real por plataforma: Telegram valida via `getMe` e registra `setWebhook` com `secret_token` (URL com segredo, não `{bot_id}` adivinhável); Discord usa gerenciador de conexão com reconexão/backoff preservando a decisão stateless da Spec 52. Tudo auditado (Spec 41) e com métricas de job (Spec 53).

A **Spec 75** adiciona rastreamento de **leads** (`bot_leads` com constraint única `bot_id + platform_user_id` — lead = usuário único que inicia conversa) e **eventos de funil** (`lead → checkout_started → paid`) com atribuição `bot_id` na cobrança: dá para medir conversão por bot, por loja e por período, contando como venda apenas pagamento confirmado (estorno/chargeback não conta — Specs 23/29).

**Delivered by:** Spec 74 (registro-bots), Spec 75 (leads-metricas-bots)

### V54: Painel de gestão de bots (console + portal)
Interface para o lojista e para o operador verem e operarem os bots de venda. No **portal do lojista**, uma tela lista os bots do lojista em cards (foto, nome, @, status ONLINE/OFFLINE, total de leads e vendas — padrão visual da Spec 52), com filtro por plataforma (Telegram/Discord) e ações de pausar/reativar, editar (avatar, descrição/bio, comandos) e excluir com `ConfirmDialog` (Spec 73). No **Master Console**, espelho de leitura com visão geral de todos os lojistas, RBAC-aware (Spec 69). Modais de conectar e editar consomem os endpoints das Specs 74/75; toda ação de escrita tem feedback anunciável e segue o padrão UX do console (Spec 73).

**Delivered by:** Spec 76 (painel-bots)

### V55: Criadores de bot Telegram e Discord
Wizards passo a passo que guiam a criação do bot dentro da própria plataforma — um para **Telegram** (Spec 77) e um para **Discord** (Spec 78).

- **Criador Telegram:** o painel orienta a criação do bot no BotFather (obter token), valida em tempo real via `getMe`, registra o webhook com `secret_token` aleatório, testa foto/descrição/comandos (`setMyDescription`/`setMyCommands`) e finaliza criando o registro na Spec 74.
- **Criador Discord:** o painel orienta a criação no Discord Developer Portal (token + Application ID), valida a conexão via gateway com reconexão/backoff (decisão stateless da Spec 52), testa presença e comandos slash (`PUT /applications/{id}/commands`) e finaliza criando o registro na Spec 74.

O objetivo é o lojista sair do zero até o bot vendendo no FoxCash sem sair do painel — cada etapa com verificação em tempo real e mensagens claras.

**Delivered by:** Spec 77 (criador-bot-telegram), Spec 78 (criador-bot-discord)

### V56: Broadcast — motor de disparo em massa
Motor de disparo de mensagens em massa para os bots Telegram/Discord do lojista (registrados na Spec 74). O backend vira o **orquestrador**: tabela `broadcasts` (modo `NOW/SCHEDULED/WELCOME/REPOST`, `target_chat`, content, mídia, `buttons_json`, máquina de estados `PENDING/PROCESSING/COMPLETED/FAILED`) e `broadcast_logs` (log por destinatário `SUCCESS/BLOCKED/FAILED` — histórico auditável).

- **Destino canal/grupo**: resolvido via registro da Spec 74 (chats onde o bot é admin), carregado dinamicamente.
- **Modos**: Agora (NOW), Agendada (reusa a busca de vencidas da Spec 51), Bem-vindos (evento `chat_member`/`guildMemberAdd` — nova entrada dispara a mensagem) e Repost (reenvio em intervalo configurável, ex. 12h/24h).
- **Mídia CDN**: upload de imagem (≤10MB) e vídeo/áudio (≤50MB) reaproveitando o `file_id` do Telegram / URL da CDN — o envio não re-uploada o binário a cada destinatário.
- **Botões inline**: grade de botões `link` (URL — checkout/suporte/grupo) e `callback` (ação interna) no corpo da mensagem.
- **Rich-text + tags**: formatação no formato nativo de cada plataforma e tags dinâmicas (`{primeiro_nome}`, `{username}`, `{data}`).
- **Disparo sem BullMQ**: o envio continua no bot (Spec 52, grammY/discord.js com rate limit nativo — 30 msg/s Telegram, buckets Discord); o bot **busca pendências via API** e **reporta o log por destinatário** de volta (padrão Spec 51/52).

O lojista passa a transmitir promoções, avisos e boas-vindas em massa — com histórico por destinatário e respeito aos limites das plataformas, sem travar a API.

**Delivered by:** Spec 79 (broadcast-motor)

### V57: Broadcast — editor com preview em tempo real
Editor de broadcast no portal do lojista, com **preview em tempo real**: conforme o lojista digita ou anexa mídia, uma bolha de chat simulada da plataforma (Telegram/Discord) renderiza exatamente o que será enviado.

- **Toolbar de formatação**: negrito, itálico, sublinhado, riscado, código (inline/bloco), citação e spoiler — convertidos para o formato nativo da plataforma (HTML/Markdown do Telegram, Markdown do Discord).
- **Tags dinâmicas**: inserção de `{primeiro_nome}`, `{username}`, `{data}` com dica de preview.
- **Mídia**: upload de imagem/vídeo/áudio (limites da Spec 79) com preview do anexo.
- **Botões inline**: editor de grade (linhas × botões) com tipo link (URL) ou callback (ação), preview dos botões na bolha.
- **Barra de modos**: Enviar agora / Agendada (data-hora ou cron) / Bem-vindos / Repost (intervalo) — o formulário valida antes de habilitar o envio (bot e destino obrigatórios; mensagem ou mídia pelo menos um).

Tudo no padrão UX do console/portal (Spec 73): banners, campos consistentes, estados de carregamento/vazio. O lojista compõe transmissões com confiança — vê o resultado antes de disparar.

**Delivered by:** Spec 80 (broadcast-editor)

### V58: Tracking — perfis e disparo server-side de eventos
Disparo **server-to-server** de eventos de conversão para os serviços de mídia do lojista (Facebook CAPI, TikTok Ads, UTMify, Otimizey), consumindo o funil da Spec 75 (`bot_leads` + `bot_funnel_events`: `lead → checkout_started → paid` por bot).

- **Perfis de tracking** (`tracking_profiles`): configuração por lojista com Pixel ID + Access Token (Facebook), Pixel Code + Token (TikTok) e tokens/webhooks (UTMify/Otimizey) — **tokens criptografados em repouso** (padrão Spec 74, não texto puro como no sistema externo).
- **Vínculo perfil↔bot** (`tracking_profile_bots`): quais bots da loja disparam para aquele perfil.
- **Captura de UTM**: o bot parseia `?utm_source=...&utm_campaign=...` do link de entrada e persiste vinculado ao lead (colunas novas em `bot_leads` — hoje não existe UTM em lugar nenhum).
- **Mapeamento de eventos**: `lead` → Lead/PageView, `checkout_started` → InitiateCheckout, `paid` → Purchase (com valor e moeda BRL) — enviados como: Facebook CAPI v19 (`POST /{pixel_id}/events` com `user_data` hasheado SHA-256 + `fbp`/`fbc`), TikTok Events API (`/open_api/v1.3/pixel/track/`), UTMify/Otimizey (webhook com os dados do pedido + UTMs).
- **Dispatch via outbox da Spec 35** (retry/backoff, sem fila nova) e **`event_logs`** idempotente por `(profile, event)` — dá para ver X ok / Y erros no painel.

O lojista mede as campanhas (Meta/TikTok/atribuição) com dados 100% precisos — sem adblocker, sem pixel no cliente — e o operador vê a saúde dos disparos.

**Delivered by:** Spec 81 (tracking-eventos)

### V59: Tracking — painel (UI portal)
Painel de tracking no portal do lojista — padrão da WebhooksPage (Spec 35) + UX da Spec 73:

- **Cards superiores de resumo**: perfis ativos, fluxos (bots) rastreados e total de eventos disparados com sucesso.
- **Card do perfil de tracking**: badges dos serviços conectados (Pixel/TikTok/UTMify/Otimizey), chips dos bots vinculados, **linha de status do log** com contagem em tempo real (X ok / Y erros).
- **Ações**: editar, duplicar, ativar/desativar, **sincronizar** (teste de conexão com o provedor) e excluir (com `ConfirmDialog` — Spec 73).
- **Modal de configuração**: pixel IDs, tokens mascarados (nunca em claro — criptografia da Spec 81), campos por serviço e vínculo dos bots.

O lojista cria perfis reutilizáveis ("Pixel Modelo", "TikTok Promo"), vincula aos bots e acompanha a saúde dos disparos sem sair do painel.

**Delivered by:** Spec 82 (tracking-painel)

### V60: CRM — base de clientes e Live Chat (backend)
Base de clientes (CRM) + **Live Chat bidirecional** entre o painel e os clientes no Telegram/Discord, sobre os leads da Spec 75 e a integração da Spec 74.

- **Lead enriquecido**: `bot_leads` ganha nome, username, e-mail, phone e **status derivado do funil** (Spec 75): `NOVO` (só lead), `PENDENTE` (checkout sem pagamento), `PAGO` (venda aprovada), `BLOQUEADO` (Telegram `403 bot was blocked` ou ban — **detecção automática** no envio + auditoria).
- **`chat_messages`**: histórico persistido da conversa (sender `CLIENT`/`ADMIN`/`BOT_AUTO`, texto/mídia, `is_read` para o indicador de não lida).
- **Ponte de chat**: enviar do painel via **REST** (reusa `TelegramBotApi`/gerenciador Discord da Spec 74 — `sendMessage`/DM, respeitando rate limits); receber via webhook Telegram (Spec 74 D5.3) e listener Discord → persiste + **push SSE** para a janela aberta no painel.
- **Push em tempo real — SSE (`SseEmitter`, zero dependência nova) + Redis pub/sub** (fan-out entre instâncias, fail-open ADR-010 — padrão `RedisAlertCounterStore`): **sem Socket.io, sem sticky sessions**, consistente com a HA stateless (Spec 62).
- **Endpoints**: lista de clientes com busca (nome/@/e-mail/ID) e filtros (bot, status, período) + histórico do chat paginado; métricas `nyx_crm_*` (Spec 53); auditoria (Spec 41).

O lojista acompanha todos os clientes e **atende em tempo real pela própria loja** — sem sair do painel para responder no Telegram/Discord.

**Delivered by:** Spec 83 (crm-chat)

### V61: CRM — painel (UI portal)
Painel de CRM no portal do lojista — padrão UX da Spec 73:

- **Cards superiores**: Novos, Pendentes, Pagos, Bloqueados e Total de leads.
- **Busca e filtros**: busca por nome/@username/e-mail/ID; filtros por bot, status (NOVO/PENDENTE/PAGO/BLOQUEADO) e período (Hoje/Ontem/Semana/Mês/Tudo).
- **Tabela de clientes**: nome/username/ID, bot & fluxo (Spec 74), badge de status (cores por estado) e botão **"Abrir Chat"**.
- **Live Chat (modal)**: histórico persistido (Spec 83), conexão **SSE** para receber mensagens do cliente em tempo real, campo de envio (REST — Spec 83), indicador de **não lida** na tabela e **sinal sonoro leve** ao receber mensagem com a janela aberta.

O lojista vê a base toda (com status automático derivado do funil) e **atende o cliente em tempo real** dentro do painel — visual de conversa familiar (bolha do cliente à esquerda, resposta à direita), com feedback de envio/erro (Spec 73).

**Delivered by:** Spec 84 (crm-painel)

### V62: Frontend — fundação Shadcn UI
> **DESCARTADA (2026-08-14):** decisão do usuário — **não será entregue**
> (Shadcn UI abandonado; os frontends seguem com o padrão atual da Spec 73 —
> CSS puro + `ui.tsx`). A responsividade fica como evolução futura fora de
> escopo. Spec 85 `discarded`.

Fundação do **design system Shadcn UI** nos dois frontends (console + portal), que hoje são CSS puro sem framework.

- **Tailwind CSS v4** (`@tailwindcss/vite`) + **componentes shadcn/ui** (Radix por baixo, copy-paste por app): button, card, dialog, alert-dialog, input, label, select, table, badge, tabs, sheet, dropdown-menu, sonner, skeleton.
- **Tema claro + escuro** via CSS variables (padrão shadcn) — toggle na topbar com persistência (localStorage) e preferência do sistema.
- **Layout responsivo**: sidebar de navegação que vira **`Sheet` (drawer)** no mobile, topbar com theme toggle, skip-link preservado (Spec 73).
- **Primitivos migrados**: `Card`/`Field` → componentes shadcn, banners → **Sonner** (preservando `role=status/alert`), `Spinner` → `Loader2`/`Skeleton`, `ConfirmDialog` → `AlertDialog`, `StepUpPrompt` → `Dialog`.
- **Zero mudança de contrato HTTP** (camada de apresentação — Spec 73); CSP validado para estilos inline do Radix (popper).

É a base sobre a qual todas as telas (existentes e novas — Specs 76/80/82/84) passam a ser construídas com um visual consistente e responsivo.

**Delivered by:** Spec 85 (frontend-shadcn-foundation)

### V63: Frontend — migração do Master Console
> **DESCARTADA (2026-08-14):** decisão do usuário — **não será entregue**
> (Shadcn UI abandonado; console segue no padrão atual da Spec 73). Spec 86
> `discarded`.

Migração das **14 páginas do Master Console** para os componentes da Spec 85 (fundação Shadcn UI): Dashboard, Transactions, Merchants, MerchantDetail, Withdrawals, Financial, Receivables, Audit, Activity, Reconciliation, Lookup, Security, Profiles e Login.

- **Funcionalidade preservada**: step-up TOTP (Specs 69/71/72), UI RBAC-aware (backend decide), autogestão de MFA — nada de contrato HTTP muda.
- **Acessibilidade da Spec 73 preservada**: `AlertDialog`/`Dialog` nativos do Radix substituem o `ConfirmDialog`/`StepUpPrompt` custom (focus trap/Escape/aria nativos).
- **Responsividade obrigatória**: grids de métricas colapsam (1 coluna no mobile), tabelas ganham scroll horizontal (ou viram cards), formulários empilham, nav vira drawer.
- **Verificação**: E2E `master-console-ui` e `master-console-stepup` verdes (seletores atualizados se o novo markup exigir, documentado).

O operador ganha um console com visual moderno e consistente, usável de qualquer tela.

**Delivered by:** Spec 86 (frontend-console-shadcn)

### V64: Frontend — migração do Portal
> **DESCARTADA (2026-08-14):** decisão do usuário — **não será entregue**
> (Shadcn UI abandonado; portal segue no padrão atual da Spec 73). Spec 87
> `discarded`.

Migração das **11 páginas do Portal do lojista** para os componentes da Spec 85 (fundação Shadcn UI): Dashboard, Charge, Sales, Wallet, Withdraw, Credentials, Destinations, Webhooks, Login, ForgotPassword e ResetPassword.

- **Execução antes da migração do console** (decisão do usuário — portal primeiro): é onde cairão as telas novas das Specs 76/80/82/84, que já nascem no padrão shadcn.
- **Funcionalidade preservada**: cobrança PIX/Gift, credenciais, webhooks, destinos, saque — zero mudança de contrato HTTP.
- **Responsividade obrigatória**: páginas de cobrança/formulários empilham no mobile, tabelas adaptam (scroll/cards), nav vira drawer.
- **Feedback preservado** (Spec 73): banners → Sonner com `role=status/alert`; `ConfirmDialog` → `AlertDialog`.
- **Verificação**: E2E `portal-ui` verde (seletores atualizados se o novo markup exigir, documentado).

O lojista ganha um painel moderno, consistente e **usável no celular** — essencial para quem acompanha vendas fora do computador.

**Delivered by:** Spec 87 (frontend-portal-shadcn)

### V65: Taxa de saque R$ 2,50

Nova taxa fixa de **R$ 2,50 por saque do lojista** (Spec 88), aditiva à precificação existente (R$ 0,50/transação + R$ 69,95 mensais). Incide em saques manuais (Specs 14/32/36) e automáticos (Spec 70). Em saque FAILED no PSP, o estorno devolve **valor + taxa** — a receita da taxa é revertida (padrão `ADMIN_RECOVERY_REVERSAL`, SEC-29). Saque administrativo do master (recolhimento, Spec 34) é isento. A taxa vira receita da plataforma no resumo financeiro do master (bucket `withdrawalFees`, aditivo) e aparece no ledger do wallet como `WITHDRAWAL_FEE_DEBIT`/`WITHDRAWAL_FEE_REVERSAL`.

**Delivered by:** Spec 88 (withdrawal-fee)

### V66: Auditoria de Broadcasts no Master Console

Interface no Master Console (Specs 71/72) para operadores monitorarem, auditarem e intervirem nas campanhas de disparo em massa criadas pelos lojistas (motor da Spec 79):

- **Visão global (read-only)**: lista paginada de todos os broadcasts da plataforma, com filtros cruzados (Lojista, Bot, Status, Modo, Data) e dados do `merchant` + `store_bot`.
- **Inspeção de conteúdo**: ao clicar em um broadcast, o operador vê exatamente o que está sendo/foi enviado — rich-text formatado, mídia, botões inline e os logs por destinatário (`SUCCESS`/`BLOCKED`/`FAILED`).
- **Intervenção de emergência — Force Cancel**: `POST /api/v1/master/broadcasts/{id}/force-cancel` coloca a campanha em `FAILED` com `error_message = 'Cancelled by Master Operator'` (apenas `PENDING`; 409 caso contrário) — protege a reputação dos bots e coíbe spam.
- **RBAC + auditoria**: leitura para todo master; o force-cancel é ação de OPERATOR (Spec 69, sem step-up — padrão suspend/release); a ação gera trilha `BROADCAST_FORCE_CANCELLED` com o ID do operador (Spec 41).
- **UI**: rota `/console/broadcasts` com tabela padrão Spec 73 e drawer de detalhe.

O operador consegue **ver o que cada lojista está disparando em massa e parar uma campanha abusiva imediatamente**, com registro auditável de quem interveio.

**Delivered by:** Spec 89 (master-broadcast-audit)

**Delivered by:** Spec 89 (master-broadcast-audit)

### V67: Handoff humano no CRM (pausar o bot e atender manualmente)

Capacidade de **transbordo humano** no Live Chat do CRM (Specs 83/84): pausar as respostas automáticas do bot para um cliente específico e assumir a conversa manualmente.

- **Estado por lead**: `bot_leads.handoff_status` (`BOT`/`HUMAN`, default `BOT`) — o lojista alterna por cliente, não para o bot inteiro.
- **Interceptação no webhook**: ao receber mensagem de um lead em `HUMAN`, o webhook do Telegram (Spec 74 D5.3) e o listener do Discord **persistem a mensagem em `chat_messages` (sender `CLIENT`) + push SSE** e **abortam o encaminhamento do update para o funil do bot** — o bot para de responder comandos/automação daquele cliente enquanto estiver em atendimento humano.
- **Controles no painel**: endpoints do portal (status + `PUT /api/v1/portal/crm/leads/{id}/handoff`) e botão **"Pausar bot"/"Retomar bot"** no cabeçalho do Live Chat (Spec 84), com trilha `LEAD_HANDOFF` na auditoria (Spec 41).

O lojista **assume a conversa sem que o bot "atropele" o cliente com o funil automático** — essencial para recuperar vendas e resolver dúvidas de suporte (Customer Success), mantendo o histórico persistido e o atendimento em tempo real via SSE.

**Delivered by:** Spec 90 (crm-handoff)

**Delivered by:** Spec 90 (crm-handoff)

### V68: Fallback automático de adquirente (cobrança Pix)

O FoxCash deixa de depender de um único adquirente selecionado estático no boot (`foxcash.psp.provider`). Um **`PixProviderRouter`** tenta os adquirentes em ordem de prioridade configurável (`foxcash.psp.routing.*`, config/env): em erro incerto do primário (**timeout/5xx**) ele **consulta o primário via `getChargeByCode`** (busca pelo code=transactionId) antes de cair para o secundário — **anti-dupla-cobrança** (se A criou a cobrança e só a resposta se perdeu, usa A; só cai para B se A confirmar que não criou). Um **circuit breaker preventivo** monitora a saúde de cada adquirente (janela de falhas/timeouts + input de chargeback da Spec 16/19) e **desvia o volume antes** do gateway virar risco, com métricas `nyx_psp_*` por adquirente e **alerta ao operador** (`PROVIDER_CIRCUIT_OPEN`, padrão Spec 64). Inclui o **adapter real do Asaas de cobrança** (não plug-and-play) herdando um harness `PixProviderContractTest` junto com o Pagar.me — forçando uma interface verdadeiramente agnóstica e habilitando smart routing por custo no futuro. Webhook `/webhooks/pix` valida o segredo de cada adquirente configurado + coluna `provider` na transação para rotear a reconciliação. Idempotência da Spec 28 preservada (mesmo `code`/`idempotencyKey`).

**Delivered by:** Spec 91 (fallback-adquirente)

### V69: Resiliência de saldo no PSP (sweep + credencial isolada)

Resiliência do caixa do FoxCash contra freeze/indisponibilidade do PSP. Um **job de sweep** (ShedLock + `JobExecutionService`, Spec 53) transfere diariamente o saldo **Disponível** da conta master do PSP para a **conta blindada corporativa**, **retendo um buffer** configurável (`foxcash.psp.sweep.*`) para os saques dos lojistas — a exposição máxima a um freeze é só o que entrou desde o último sweep + o buffer, nunca meses acumulados. **Credencial isolada** para o sweep: usa **somente** a chave payout-scoped (`foxcash.psp.payout-*.secret-key`), nunca a chave de cobrança — se a chave de cobrança vazar, o ofensor não move o saldo master (IP whitelist no adquirente + fail-fast se as chaves forem iguais em prod). **Payout fallback** na solicitação do saque (timeout/5xx de A → tenta B), com a defesa real contra freeze sendo o próprio sweep (freeze não é roteável). Métricas `nyx_psp_balance_*` (exposição, última varredura) + alerta de exposição alta.

**Delivered by:** Spec 92 (resiliencia-saldo-psp)

### V70: MFA TOTP no portal do lojista

O lojista pode **ativar autenticação em 2 fatores (TOTP, RFC 6238)** no portal — fechando a lacuna #1 da auditoria de segurança (2026-08-14): o master já tem MFA (Spec 44), o lojista não. Com o MFA ativo, a senha **não basta** para o login no portal: exige o 2º fator (código TOTP do app autenticador **ou** recovery code single-use). Implementação **espelho do padrão master (Spec 44)**: mesma lib custom (`Totp` RFC 6238), mesma criptografia em repouso (`CryptoConverter` AES-256/GCM), mesmo lockout de TOTP (5 falhas → 15 min, expira sozinho), mesmos recovery codes (SHA-256 single-use, consume atômico SEC-20) e mesmos eventos de auditoria (Spec 41, actor `MERCHANT`). Login **single-step** (request aceita `totpCode` opcional; MFA ativo com código ausente/inválido → 401 uniforme, sem oráculo). Portal ganha campo TOTP no login (sempre visível) e página **Segurança** (ativar em 2 passos / desativar com posse / regenerar recovery codes).

**Delivered by:** Spec 94 (mfa-merchant-portal)

### V71: Log drain e agregação de logs (observabilidade pós-incidente)

Fecha a **lacuna #7 da auditoria de segurança** (2026-08-14): agregação de logs. O operador ganha um **runbook de consulta de logs** (`ops/log-drain.md`) — onde os logs vivem no Railway, janela de retenção da plataforma, como habilitar o **log drain** (Axiom/Loki free tier, credencial fora do repo) e como **buscar por `X-Request-Id`** cruzando com `platform_activity_log` (SQL) e métricas (`/actuator/prometheus`) — fechando o critério 8 da Spec 64 ("logs investigáveis"). Além disso, um **job de retenção opt-in** (`PlatformActivityCleanupScheduler`, padrão Spec 53/ShedLock) permite ao operador expurgar a trilha `platform_activity_log` por janela configurável — **default desligado**, preservando o contrato append-only da Spec 41 (sem rota de mutação na API). Reusa o que já existe: log estruturado em prod (`logback-spring.xml` com `requestId/traceId/spanId`), `RequestIdFilter`, Micrometer Tracing (Spec 57) e o runbook de incidente (Spec 64). A configuração efetiva do drain no Railway é passo operacional documentado, não código.

**Delivered by:** Spec 95 (log-drain)

### V72: Backup automático — cron instalável e verificável (DR garantido)

Fecha a **lacuna #10 da auditoria de segurança** (2026-08-14): o backup diário do PostgreSQL (dump via `ops/backup.sh`, já validado em 2026-08-07) passava por **instalação manual do cron** — sem automação e sem detecção de falha silenciosa. A Spec 96 entrega o **instalador/verificador `ops/install-backup-cron.sh`** (modos `install` idempotente, `check` e `uninstall`): agenda `30 2 * * *` com caminho absoluto, e o modo `check` valida a linha do cron, a executabilidade do `backup.sh` e a **frescura do último dump** (< 48h — pega falha silenciosa antes de precisar do backup). Inclui o rotativo do log de backup (`ops/logrotate-nyxcash-backup.conf`, 60 dias — antes prometido sem implementação) e o **checklist de verificação** no runbook `ops/backup-restore.md` (semanal: `check`; mensal: teste de restore em banco descartável; pós-falha: restaurar o mais recente). Spec 100% operacional — sem código Java; a instalação efetiva no host do Railway é o passo operacional final do operador.

**Delivered by:** Spec 96 (backup-cron)

### V73: Painel de usuários — backend leitura (métricas e dados enriquecidos do lojista)

Backend de **leitura** que transforma a listagem básica de lojistas do Master Console (Specs 71/72) em um painel rico de operação e risco. Entrega: **tabela de lojistas com métricas** — coluna **MED%** (termômetro de risco derivado ao vivo: (refunds + chargebacks) ÷ pix pagos × 100, com a contagem de ocorrências — ex.: 8.73% / 62), **taxa de conversão** (pix pagos ÷ criados × 100), **nº exato de cobranças/Pix pagos** e **gateway/rota efetiva** (da última cobrança — `pix_transactions.provider` da Spec 91; PADRAO = routing global); **perfil detalhado do lojista** — KPIs de topo (saldo na carteira, faturamento histórico com Pix pagos, conversão global, nº de shops), **dados de conta** (e-mail, telefone — coluna nova opcional, documento mascarado, titular, gateway, data de cadastro) e **painel de taxas e limites** (Taxa Pix, Taxa Fixa, Taxa de Saque, MED acumulado, limites operacionais de Pix e Saque); **transações** com **nome do comprador** (via lead do CRM — `bot_leads.name/username` por `bot_id`+`platform_user_id`, decisão do usuário: sem PII nova; sem lead → `buyerIdHash` opaco), **produto/serviço** (nome do plano via `plan_id`, Spec 49) e **líquido** (bruto − taxa); **saques** com valor, status, chave Pix mascarada, timestamp e taxa (Spec 88); e **espelho read-only de stores** (`GET /api/v1/master/merchants/{id}/stores` — "Ver Shops", hoje só existe no portal). Nenhuma mudança de contrato nas rotas existentes — endpoints master **aditivos read-only**.

**Delivered by:** Spec 97 (painel-usuarios-leitura)

### V74: Painel de usuários — backend escrita (configuração fina por lojista + controle de conta)

Backend de **escrita** que dá ao operador controle **cirúrgico e individualizado** sobre cada lojista. Entrega: **configuração por lojista** (`GET/PUT /api/v1/master/merchants/{id}/config`) com **regra de taxa** flexível (`pix_fee_type` FIXED/PERCENT, `pix_fee_fixed`, `pix_fee_percent`, `pix_fee_threshold` — Pix até um valor paga taxa fixa, acima paga percentual), **taxa de saque** (override do R$ 2,50 global da Spec 88), **limites operacionais** (pix_min/pix_max, withdrawal_min/withdrawal_max), **gateway/rota por lojista** (`gateway_route`: PADRAO ou provedor específico — override no `PixProviderRouter`/`RoutingPolicyResolver` da Spec 91) e **rate limit por lojista** (`rate_limit_rps`/`rate_limit_rpm` — override no `AuthenticatedRateLimitFilter`, blindando a API contra força bruta/robôs de teste de cartão/chave); **kill switch** Ativo/Inativo; **Debitar saldo** (`POST .../config/debit` `{amount, reason}` — débito manual com entry novo `ADMIN_DEBIT` nos ledgers, auditoria Spec 37 e **step-up TOTP** Spec 69); **honeypot** — flag que **não alerta o atacante** (conta continua operando, mas cobranças roteiam para o **provedor trap** que nunca processa PAID, saques bloqueados, `HONEYPOT_TRIGGERED` no outbox com throttle — ambiente controlado de monitoramento antifraude); **bloquear API** (`api_blocked` → 403 no filtro, portal segue); **banir** (novo status `BANNED` irreversível — bloqueia API, portal, saques e cobranças). Migration V60+ (colunas em `merchants`, enum `BANNED`, entry types novos). RBAC (Spec 69) + auditoria (Spec 37) + step-up (Spec 69) em todas as ações de escrita.

**Delivered by:** Spec 98 (painel-usuarios-config)

### V75: Painel Geral de Usuários — UI do Master Console

A **central de comando** da operação no Master Console: uma tabela rica de todos os lojistas com **métricas de risco** — nome/e-mail, status da conta com badge, taxas aplicadas, **gateway utilizado** (PADRAO ou rota específica), **taxa de conversão** (%) e **nº exato de cobranças/Pix pagos** — e a coluna vital **MED%** (termômetro de risco colorido com a contagem de ocorrências — ex.: 8.73% / 62 acende alerta vermelho). Cada linha tem um **menu de ações administrativas diretas**: Ver detalhes, Ver shops, Config, Sacar, e as ações de controle (Rejeitar/Desativar/Bloquear API/Banir, Marcar honeypot) com `ConfirmDialog` (Spec 73). O **perfil detalhado do lojista** mostra o raio-x financeiro e operacional: **KPIs de topo** (saldo na carteira principal, faturamento histórico com Pix pagos, conversão global, nº de shops), **dados da conta** (e-mail, telefone, CPF/CNPJ, titular, gateway, data de cadastro) e **painel de taxas e limites** (Taxa Pix, Taxa Fixa, Taxa de Saque, MED acumulado, limites operacionais). A **aba Transações** funciona como extrato vivo (valor, status PENDENTE/PAGO, nome do comprador, produto/serviço — ex.: "VIP Semanal", TXID e líquido já com a taxa descontada). A **aba Saques** mostra o fluxo de caixa de saída (valor, status CONCLUIDO/FALHOU, chave Pix mascarada, carimbo de data/hora e taxa da operação). Consumo dos endpoints da Spec 97; padrão UX da Spec 73.

**Delivered by:** Spec 99 (painel-usuarios-ui)

### V76: Configuração fina por lojista — UI do Master Console

A tela onde o administrador **molda as regras de negócio individualmente** para cada lojista no Master Console: formulário de **regra de taxa** (Pix até determinado valor cobra taxa fixa; acima disso, percentual), **taxas e limites de saque** (valor da taxa, limites operacionais), **gateway/adquirente** (PADRAO — roda na cascata global da Spec 91 — ou injetado diretamente em um adquirente específico), **rate limit integrado** (controle de requisições por segundo e por minuto para blindar a API contra força bruta ou robôs de teste), **controle de conta (kill switch)** com botões Ativo/Inativo (Bloqueado), **Debitar saldo** (modal com valor + motivo, com **step-up TOTP no cliente** — 403 `step_up_required` → prompt → repete a ação, padrão Spec 72/73), **honeypot** (toggle com confirmação — marca o lojista para o fluxo de monitoramento antifraude sem alertar o atacante) e **bloquear API**. Consumo dos endpoints da Spec 98; padrão UX da Spec 73 (banners, `ConfirmDialog`, estados de carregamento/vazio, formulários consistentes).

**Delivered by:** Spec 100 (painel-usuarios-config-ui)

### V77: Shell do portal do lojista — header global + menu lateral expandido

Header global e navegação do portal do lojista (Specs 101/102): **faturamento em tempo real** do período com **barra de progresso de meta**, **saudação personalizada** com hora ("Boa madrugada, Gansa") e data corrente, **carrossel de leads/contatos recentes** (avatares circulares com iniciais que abrem o Live Chat do CRM) e **atalhos rápidos** (ajuda, notificações, perfil, configurações). O **menu lateral é expandido em seções** (Visão Geral: Dashboard/Ranking/Premiações/Análises; Financeiro; Clientes; Automações: Meus Robôs/Meus Fluxos/Redirecionadores/Remarketing/Postagens; Ferramentas: Gateways/Trackeamento/Checkout/Bio Link/Webhooks/Cupons & Sorteios; Comunidade/Afiliado), com navegação para as rotas existentes e placeholders das specs novas. Backend aditivo read-only (`GET /api/v1/portal/shell` + recent-leads) e frontend sobre o Layout existente (Spec 73).

**Delivered by:** Spec 101 (portal-shell-backend), Spec 102 (portal-shell-ui)

### V78: Dashboard rico do lojista — KPIs, gráfico, log em tempo real, premiações e Top 5

Dashboard principal do portal (Specs 103/104): **cards de KPI** — Vendas Aprovadas (volume financeiro convertido + nº absoluto de aprovações), Taxa de Conversão (% entre inícios e Pix pagos), **Total Starts** (clientes que iniciaram checkout/conversa) e **Ticket Médio** (valor médio por comprador); **gráfico de desempenho temporal** ("Seu Desempenho" — curva do fluxo de vendas ao longo dos dias da semana); **log de atividades em tempo real** (feed cronológico com eventos como início de conversa e pagamento aprovado); **seção de Premiações/Metas** (progresso visual para desbloqueio de recompensas) e **Top 5 Players** (tabela lateral dos maiores faturadores da plataforma em tempo real). Backend aditivo read-only (KPIs estendidos + activity-log + top-players) e frontend reescrito do DashboardPage (padrão UX Spec 73).

**Delivered by:** Spec 103 (dashboard-rico-backend), Spec 104 (dashboard-rico-ui)

### V79: Financeiro rico do lojista — filtros, resumo de Pix, transações com gateway e resumo do período

Módulo Financeiro do portal (Specs 105/106): **filtros temporais** rápidos (Hoje/Ontem/Semana/Mês/Todo Período), **resumo de Pix** (total gerado, pago e pendente), **gráfico de métodos de pagamento** (participação do Pix no faturamento), **tabela de transações recentes** com filtros por Gateway/Status/Fluxo detalhando nome do cliente, produto/plano ("VIP Semanal"), adquirente responsável, data/hora e valores **bruto/líquido**; e **resumo do período lateral** com métricas complementares (Ticket Médio, Expirados, Reembolsos, Leads Únicos). Backend aditivo read-only (`/portal/financial/summary` + `/portal/transactions` enriquecido, reuso da lógica de lead da Spec 97) e frontend com a nova página Financeiro.

**Delivered by:** Spec 105 (financeiro-rico-backend), Spec 106 (financeiro-rico-ui)

### V80: Gateways de pagamento — visão dos adquirentes, fallback inteligente e catálogo

Gestão de Gateways no portal (Specs 107/108): **visão geral dos adquirentes** conectados (cards com o gateway real — Pagar.me/Asaas, exibidos com os nomes de referência "Shark Wallet/Atenas Pay" — e seus respectivos extratos), **sistema de fallback inteligente** com a organização visual da ordem de prioridade (Principal e Fallbacks 1/2, detalhando a rotação automática em caso de falhas — visual sobre o backend da Spec 91) e o **catálogo de gateways disponíveis** (grade de integrações prontas para ativação, divididas por Pix/Cartão/Cripto). Backend aditivo read-only (visão dos providers + ordem + extrato por adquirente) e frontend com a página Gateways.

**Delivered by:** Spec 107 (gateways-backend), Spec 108 (gateways-ui)

### V81: Gestão de Links e Campanhas (Distribuição) — links rastreáveis, UTMs, domínios e filtro de tráfego

Gestão de Links e Campanhas no portal (Specs 109/110) — **Distribuição de tráfego**: **meus links** (painel principal para criar/gerenciar URLs rastreáveis com domínio próprio e URL camuflada `/{code}`), **códigos de venda** (identificadores para atribuição de comissão/afiliados), **preparar campanha** (estruturar links com parâmetros de UTM — origem/mídia/campanha — para medir desempenho exato em cada canal de anúncio), **domínios** (gestão dos endereços usados para camuflar o destino final) e **página intermediária** (landers de pré-venda que qualificam o clique). A **tabela de links** mostra Link (nome/status/URL camuflada/código `shk=`/tag CLOAKER), Entrega (fluxo de venda + taxa de aprovação) e **Desempenho** (cliques, vendas, receita, conversão). O **filtro de tráfego server-side** (filtro de bot da rede social vs humano — página limpa vs conteúdo real) protege as contas de anúncio (Meta/TikTok/Kwai) — decisão do usuário: controle de tráfego documentado, não evasão. Backend novo (`traffic_links`/`traffic_domains`/`sale_codes`/`traffic_clicks` + `GET /l/{code}`) e frontend com a página Links.

**Delivered by:** Spec 109 (links-filtro de tráfego-backend), Spec 110 (links-filtro de tráfego-ui)

### V82: Afiliado/Comissões — co-produtores e divisão de lucros

Afiliado/Comissões no portal (Specs 111/112): **gestão de co-produtores e divisão de lucros** — o lojista cria códigos de afiliado/coprodutor com percentual de comissão; a atribuição da venda acontece via **`sale_code`/link** da Gestão de Tráfego (Spec 109); a **comissão é calculada na mesma transação do PAID** (sem dupla contagem — Specs 29/30), registrada em **ledger novo `AFFILIATE_COMMISSION`** e paga ao wallet do afiliado. Endpoints do portal (CRUD de afiliados, resumo de comissões, retirada) e frontend com a página Afiliado.

**Delivered by:** Spec 111 (afiliado-backend), Spec 112 (afiliado-ui)

### V83: Ranking + Premiações — concurso de faturamento e metas com recompensas

Gamificação no portal (Specs 113/114): **Concurso de Faturamento** — competição interna de vendas entre lojistas com **Ranking** (posição do lojista + Top 10 por período) e **Top 5 Players** no dashboard; e **Premiações/Placas** — metas de faturamento configuráveis (`foxcash.rewards.*`) que liberam recompensas físicas ao atingir o objetivo (ex.: meta de 50k libera o card "Shark Blood"), com progresso visual. Backend aditivo (ranking por somas APPROVED — Spec 30, metas + job de verificação ShedLock Spec 53) e frontend com as seções Ranking/Premiações.

**Delivered by:** Spec 113 (ranking-premiacoes-backend), Spec 114 (ranking-premiacoes-ui)

### V84: Checkout builder + Bio Link + Cupons & Sorteios — conversão customizada

Conversão no portal (Specs 115/116): **Checkout** — construtor de páginas de pagamento customizadas de alta conversão (identidade, produto, cor, botão) com página pública `GET /c/{slug}` (reuso do `PixProviderClient` para gerar o Pix); **Bio Link** — páginas de árvore de links (`/bio/{slug}`) para redes sociais; e **Cupons & Sorteios** — sorteio gamificada de incentivo à conversão na página de pagamento (prêmios configuráveis com probabilidades). Backend novo (`checkout_pages`/`bio_link_pages`/`sorteio_config` + páginas públicas) e frontend com os editores no portal.

**Delivered by:** Spec 115 (checkout-biolink-sorteio-backend), Spec 116 (checkout-biolink-sorteio-ui)

### V85: Automações avançadas — Meus Fluxos, Redirecionadores e Remarketing

Automações avançadas no portal (Specs 117/118): **Meus Fluxos** — desenho e controle dos funis de conversação (nós de mensagem/ação, reuso das Specs 50/51); **Redirecionadores** — links inteligentes de tráfego (reuso da infra da Spec 109 com UI própria); e **Remarketing** — campanhas automáticas de recuperação de carrinhos (job ShedLock detecta cobrança PENDENTE expirada/abandonada → dispara mensagem de recuperação via bot, reuso Specs 51/79). Backend aditivo (`bot_flows`/`bot_flow_nodes` + remarketing job) e frontend com as páginas Meus Fluxos/Redirecionadores/Remarketing.

**Delivered by:** Spec 117 (automacoes-backend), Spec 118 (automacoes-ui)

### V86: Leves — CRM enriquecido, Webhooks melhorado e Comunidade

Fechamento de melhorias no portal (Spec 119): **CRM enriquecido** — colunas **plano/fluxo/data última interação** na tabela de clientes e **busca por telefone** (reuso do `bot_leads` das Specs 75/83 — sem PII nova); **Webhooks melhorado** — **validador de payload JSON** na tela (gaveta com o JSON real do evento) + **aviso de segurança** (HMAC-SHA256, HTTPS obrigatório, desativação automática após 5 falhas — dados reais da Spec 35); e **Comunidade** — canal de avisos/novidades da plataforma (posts read-only no portal com seed do master, KISS sem chat). Padrão UX da Spec 73.

**Delivered by:** Spec 119 (leves-crm-webhooks-comunidade)

### V87: PVS backend — pagamento via saldo (Pix Copia e Cola estático)

O lojista **paga um Pix Copia e Cola estático** (chave Pix embutida no brcode) usando o **saldo do wallet** — sem precisar cadastrar destino nem sacar. É uma **variação do pipeline de saque**: o pagamento nasce como um `withdrawal` de novo tipo `BILL_PAYMENT` e passa pelo **mesmo fluxo em 2 fases** (Fase A: débito valor + taxa R$ 2,50 + PROCESSING + ledger `WITHDRAWAL_DEBIT`/`WITHDRAWAL_FEE_DEBIT` sob lock — SEC-19; Fase B: `PayoutProviderClient.createPayout` fora da transação + `WithdrawalSubmissionPersister`), com desfecho (COMPLETED/FAILED) via **webhook `/webhooks/payouts`, estorno automático de valor + taxa e reconciliação — reuso total, zero mudança**. Entrega: **`PixCodeParser`** (EMV TLV → chave; **só estático**; valida CRC-16; **Pix dinâmico** — brcode de conta/boleto com URL `br.gov.bcb.pix` — e boleto são **rejeitados** com mensagem clara, fora de escopo); **`PvsPaymentService`** espelhando o `WithdrawalService` com destino arbitrário (`destination_id = null` + chave no `destination_key_snapshot` criptografado); **anti-abuso com teto diário + gates** (`foxcash.pvs.daily-cap`/`max-per-payment`, avaliados DENTRO da Fase A sob o lock — sem corrida); endpoints `POST /api/v1/portal/pvs/pay` + `GET /api/v1/portal/pvs/limits` (ROLE_MERCHANT_PORTAL); migration **V68** (CHECK documentando `withdrawals.type` — a V21 criou sem CHECK — + índice para o teto diário); o histórico de saques do portal já mostra os PVS (o repositório não filtra por tipo).

**Delivered by:** Spec 120 (pvs-backend)

### V88: PVS UI portal — pagar Pix Copia e Cola com o saldo

A experiência de pagamento no portal do lojista: dentro da tela de Saque (`WithdrawPage.tsx`, padrão UX Spec 73), o lojista **cola o Pix Copia e Cola** num campo dedicado — o valor é **auto-preenchido do próprio brcode** (campo EMV 54) ou digitado quando o código não carrega valor — e vê uma **prévia clara antes de pagar**: chave Pix de destino **mascarada**, valor, taxa de saque R$ 2,50 e total a debitar, além dos **limites do dia** (usado/restante — de `GET /api/v1/portal/pvs/limits`) para nunca estourar o teto no susto. O botão de pagar passa por **confirmação** (ação financeira). Estados de erro explícitos: código inválido, **Pix dinâmico não suportado**, teto diário/por operação excedido e saldo insuficiente; sucesso mostra o pagamento em `PROCESSING` com o saldo atualizado. O **histórico de saques já inclui os pagamentos via saldo** (o backend não filtra por tipo) — o lojista acompanha PVS e saque no mesmo extrato.

**Delivered by:** Spec 121 (pvs-ui)

### V89: Perfil de operação backend — entidade merchant_profiles (fase 1: fundação)

A fundação backend do **seletor de contexto de perfil**: cada lojista ganha **N perfis de operação** — entidade nova `merchant_profiles` (migration V69, dona desta spec; `id`, `merchant_id`, `name`, `gateway_route`, `is_default`, `sort_order`, soft-delete) com **backfill** de 1 perfil "Padrão BR" por lojista e **frota de bots por perfil** (`store_bots.profile_id`). O `RoutingPolicyResolver` passa a resolver **override do perfil vence a política de risco**: `resolveProviders(merchantId, profileId)` — com `gateway_route` do perfil fixado, a cobrança Pix roteia para aquele adquirente (`PixProviderRouter`, Spec 91); sem perfil (profileId null), segue o roteamento por `risk_score` atual. Endpoints do portal: `GET /api/v1/portal/profile` (perfis com rota + **catálogo de adquirentes configurados** — PADRAO/pagarme/asaas — + bots do perfil + activeProfileId), `POST /api/v1/portal/profile` (cria perfil) e `PATCH /api/v1/portal/profile/{id}` (rota/nome; valida catálogo → 400 `unknown_gateway_route`; escrita sob lock SEC-19; auditoria `PROFILE_*`/`GATEWAY_ROUTE_CHANGED` Spec 41). **Segurança:** a fonte da verdade é o **banco** — o header `X-Profile-ID` **seleciona o perfil do próprio lojista (server-validado)**, nunca decide rota; um lojista jamais se auto-roteia para adquirente fora da lista configurada. **Sem adapter novo** (decisão: só a estrutura — o seletor fica pronto; com um 2º adquirente real, o switch passa a valer sem mudança de arquitetura); payout inalterado; **Spec 98 coordenada** (`gateway_route` removida do V61 — a rota vive no perfil). O **filtro rígido por perfil** nos módulos é a fase 2 (Spec 124).

**Delivered by:** Spec 122 (operation-profile-backend)

### V90: Perfil de operação UI — seletor de contexto por perfil (fase 1: fundação)

O **seletor de contexto de perfil** no **HeaderGlobal** do portal (`Layout.tsx`, Spec 102): um `ProfileContext` global guarda a lista de **perfis de operação** (Spec 122) e o **perfil ativo**, persistido em `localStorage` (`{activeProfileId}`); o `apiClient` anexa o header **`X-Profile-ID`** (seleção de contexto validada no backend — nunca decide rota). Trocar o perfil é local; **criar perfil** (`POST`) e **trocar a rota do perfil** (`PATCH /api/v1/portal/profile/{id}`) chamam o backend. O **bot do perfil ativo** vira **pré-seleção** nos fluxos (novo broadcast usa o bot do perfil; `BotsPage` destaca os bots do perfil) — escopo de dados; a página Segurança/Config expõe a rota atual (leitura + troca). Estados de loading/erro/saving no padrão UX Spec 73. **Fase 1 não esconde dados** — o **filtro rígido por perfil** (dashboard, ranking, extrato, CRM, bots, redirectores, remarketing, tracking) é a fase 2 (Spec 124). Com um 2º adquirente real configurado, o switch de rota passa a alternar a operação de verdade — o mecanismo (UI + contexto + header + endpoint) já está no lugar.

**Delivered by:** Spec 123 (operation-profile-ui)

### V91: Perfil de operação (fase 2) — filtro rígido de dados por perfil

A fase 2 do **seletor de contexto de perfil** (Specs 122/123): o switch de perfil deixa de ser "pré-seleção" e passa a fazer **reset contextual real** — cada módulo do portal mostra **apenas os dados do perfil ativo**: o **dashboard** filtra KPIs, gráficos e logs; o **ranking/premiação** recalcula pela base do perfil; o **financeiro** mostra só transações que passaram pelo gateway do perfil; o **CRM** lista apenas leads dos bots do perfil; a **frota de bots/automações** exibe os robôs do perfil; **redirectores/filtro de tráfego** carregam as regras e domínios do contexto; o **remarketing** roda sobre os leads do perfil; o **gateway** roteia a cobrança para o PSP do perfil (`PixProviderRouter` com `profileId` — override do perfil vence risco, Spec 122); o **tracking** troca pixels/UTMs. Requer marcar dados com perfil (bots já têm FK na fase 1; links, campanhas e transações derivam de bot/checkout → perfil). O lojista tem **1 conta que se comporta como N empresas** — alternando o botão, sem vazamento de dados entre operações.

**Delivered by:** Spec 124 (profile-data-scope)

### V92: Trace de transação — rastreio ponta a ponta (suporte escalável)

Ferramenta de suporte que responde "meu Pix de R$ 50 não caiu" sem juntar tabelas na mão: um endpoint read-only do Master Console (`GET /api/v1/master/trace/transactions/{ref}`) resolve o txid/`externalReference`/`idempotencyKey` para o UUID interno e agrega por `transaction.id` as fontes que já existem — `platform_activity_log` (Spec 41, timeline com details mascarados), `wallet_ledger`/`master_ledger` (Specs 27/34, movimento de dinheiro com `balanceAfter`), funil do bot (Spec 75, prova de que o bot viu a venda) e `notification_outbox` (Spec 35, entrega do webhook com `attempts`/`lastError`) — devolvendo uma **linha do tempo** com `stage` (BOT → GATEWAY → PSP → LEDGER → WEBHOOK). O fio condutor já existe por acidente (`transaction.id` é a chave em todas as fontes); a base de observabilidade já existe (`RequestIdFilter`/MDC, Spec 18). Requer duas mudanças estruturais dimensionadas: índice de activity por `(entity_type, entity_id, created_at)` + filtro no `PlatformActivityQuery`, e coluna de referência no outbox com backfill do payload. Fases futuras registradas: traceId de negócio propagado no MDC do worker e tela no console.

**Delivered by:** Spec 125 (trace-transacao)

### V93: Alertas proativos — observabilidade operacional (prevenção de incidente)

Fecha o gap operacional mais perigoso do FoxCash: hoje o sistema expõe /actuator/prometheus (micrometer, Spec 53) e tem 17 métricas nyx_* de PSP/CRM/tracking, mas **nenhuma métrica de operação** — "o outbox parou de drenar", "5xx disparou" ou "a reconciliação achou divergência" só aparecem quando o cliente liga. Esta spec instrumenta os pontos de falha que importam para o dinheiro (`nyx_job_failure_total`/`nyx_job_duration_seconds` no JobExecutionService — ponto único de todos os jobs; `nyx_outbox_delivered/failed_total` + `nyx_outbox_backlog_age_seconds` no pipeline do outbox Spec 35; `nyx_reconciliation_open_issues` na reconciliação Spec 30; `nyx_http_5xx_total{route}` e `nyx_http_429_total{group}`; `nyx_webhook_amount_divergence_total` no B-05) e entrega a **configuração de alerta** (`docs/observability/`): scrape do Grafana Cloud, regras PromQL (outbox parado > 5min, divergência > 0, fallback de adquirente, job falhou, 5xx, 429), dashboard de exemplo e runbook ("o que fazer" para cada alerta). Operação passa de reativa a proativa — o operador é avisado antes do cliente.

**Delivered by:** Spec 126 (alertas-proativos)

### V94: Trace de transação — traceId propagado + UI no console (suporte sem curl)

Fecha as fases 3–4 do trace de transação (Spec 125). **Fase 3 — correlação de logs**: o traceId de negócio de uma cobrança É o `pix_transactions.id` (estável do nascimento à morte — a idempotência da Spec 28 garante que o retry reusa a MESMA transação); a migration V74 cria `trace_id` explícito (backfill `= id` para legadas, NOT NULL), o `TransactionTraceResponse` expõe o traceId, o payload do outbox (CHARGE_CREATED/SALE_PAID/SALE_REFUNDED) carrega `data.traceId` e o worker do outbox (`NotificationProcessor`) seta **MDC `traceId`** — os logs do request do bot, do webhook do PSP e do worker passam a ser correlacionáveis pelo MESMO traceId no log estruturado e no Axiom (Spec 95), sem mudança de logback (`%X{traceId:-}` já emite). **Fase 4 — tela de suporte**: página "Trace de transação" no Master Console (`/console/trace`) — o suporte cola o txid/`idempotencyKey`/`externalReference` e vê a jornada (Bot → Gateway → PSP → Ledger → Webhook) com timeline de stage colorido, reusando `TransactionTraceResponse`. Sem mudança de fluxo de dinheiro. Fase 5 (lojista vê o próprio trace — autoatendimento) registrada como futura.

**Delivered by:** Spec 127 (trace-ui)

### V95: Automação de risco — Risk Scoring Engine (o risco age, sem banir por engano)

Fecha o gap 2 da priorização operacional: hoje o risco é calculado (`merchant_risk`, Spec 16), exibido no painel (Spec 97, `medPercent` com faixas LOW/MEDIUM/HIGH) e vira decisão **manual** do master (ban/deactivate/honeypot/rate_limit da Spec 98, com RBAC + step-up). Com dezenas de lojistas, alguém precisa olhar o painel e agir. A **única ação automática aprovada pelo usuário** é a redução de rate limit: quando o MED (refunded + chargebacks ÷ paid) na janela de 30 dias ultrapassa o limiar configurado, o engine reduz `rate_limit_rps`/`rate_limit_rpm` do lojista automaticamente — e **restaura** quando o MED normaliza (histerese). **Sem bloqueio de conta automático** (nem `deactivate` nem `suspend`): chargeback alto é inerente à vertical de produtos digitais que o produto vai rodar, então o auto-suspend da Spec 19 passa a default `false`. O engine roda no MESMO job diário (`foxcash-risk-monitoring`, ShedLock — ponto de extensão natural), com regra declarativa em config (`foxcash.risk.auto.*`), fail-safes (kill-switch global, nunca age com 0 pagos na janela, pisos de rate limit), **override manual vence** (coluna `risk_auto_managed` sticky — humano tocou config/status → o automático não age, evitando guerra de edição) e auditoria na trilha da plataforma (actor SYSTEM, Spec 41) com before/after/motivo + métrica `nyx_risk_auto_actions_total`. Tudo reversível — nenhuma ação destrutiva irreversível (ban continua manual). A regra de falha PSP (bloquear API por falha de adquirente) fica adiada: a métrica `nyx_psp_route_total` (Spec 91) é global, sem fonte por lojista.

**Delivered by:** Spec 128 (risk-scoring-engine)

### V96: Maturidade de segurança — dependências sem CVE e scan bloqueante

Spring Boot atualizado para a linha suportada 3.5.x, dependências de runtime sem CVE conhecido (HIGH/CRITICAL), e OWASP Dependency-Check **bloqueante** no CI (com NVD_API_KEY). O sistema deixa de rodar com CVEs conhecidos em bibliotecas de dinheiro.

**Delivered by:** Spec 130 (upgrade-seguranca-deps), Spec 131 (seguranca-runtime)

### V97: Escala e disponibilidade — PgBouncer, fila em tempo real, DR provado

Escala destravada além de 1.550 vendas/s (PgBouncer/pool, ADR-013 executado com benchmark), eventos fluindo em tempo real via RabbitMQ (p95 < 5s em vez de horas de backlog), e disaster recovery **provado** (DR drill com restore real, RPO/RTO definidos, backup validado e fora da região).

**Delivered by:** Spec 132 (flyway-postgres), Spec 133 (pgbouncer-escala), Spec 134 (fila-rabbitmq), Spec 135 (dr-backup)

### V98: Qualidade e confiabilidade da documentação — código limpo, testes reais, docs sincronizadas

God classes divididas com comportamento idêntico (provado por testes de contrato), camada de testes de integração real com Testcontainers (@SpringBootTest + Postgres + Redis nos fluxos financeiros críticos), e documentação verificada mecanicamente no CI (status da tabela = entradas, requirements == código).

**Delivered by:** Spec 136 (refactor-god-classes), Spec 137 (testes-integracao), Spec 138 (docs-sincronizadas)

### V99: Escopo do perfil de operação + lente de contexto Bot/Gateway no portal

O perfil de operação deixa de ser "tamanho único" e **nasce com escopo definido** — **⚡ Completo** (rota de gateway + frota de bots), **🤖 Somente Bot** (frota, rota sempre por risco) ou **🌐 Somente Gateway** (rota, sem frota) — decidido pelo lojista no momento da criação. O portal ganha um **seletor de contexto de lente** (Bot | Gateway) no shell, **antes** do seletor de perfil: a lente **Bot** mostra os perfis Completo e Somente Bot (com a frota de cada um, editável por `PUT /profile/{id}/bots`), e a lente **Gateway** mostra os perfis Completo e Somente Gateway (com a rota em **nome amigável** — "Padrão (por risco)", "Pagar.me", "Asaas" — trocável por PATCH). O "criar perfil" é um **modal limpo** — nome + **Tipo de Operação** (radio: 🤖 Somente Bot / 🌐 Somente Gateway / ⚡ Completo, com descrição de uma linha), sem campos condicionais: rota e frota são atribuídas depois, no painel do contexto da lente. No dropdown de perfis, **hover/foco numa opção exibe uma dica flutuante** com o resumo do perfil (escopo, bots vinculados e rota em nome amigável). A troca de perfil dá **feedback visual** imediato, e o `RoutingPolicyResolver` ganha defesa para perfis Somente Bot (nunca override de rota). Sem quebrar o modelo auditado das Specs 122/123/124: o perfil continua UMA entidade (rota + frota + escopo), a lente é estado de UI persistido, e o backend valida escopo/rota/frota (400 `invalid_scope`, `route_not_allowed_for_scope`, `bots_not_allowed_for_scope`). Fases futuras: o escopo enriquece o filtro rígido da Spec 124 (perfis Somente Bot escopam só módulos de bot; Somente Gateway os de cobrança).

**Delivered by:** Spec 139 (profile-scope-lens)

### V100: Rebranding TrevoPay → FoxCash — identidade, package e runtime renomeados

Renomeia todo o projeto de TrevoPay/NyxCash para **FoxCash**: package Java `com.nyxcash` → `com.foxcash` (1.210 arquivos), classes `Nyx*` → `Fox*`, `TrevoPayApplication` → `FoxCashApplication`, pom/Dockerfile/CI, config `nyxcash:` → `foxcash:` + env `NYX_*` → `FOXCASH_*` (112), métricas `nyx_*` → `foxcash_*` (25), Redis `nyx:*` → `foxcash:*`, jobs ShedLock `nyxcash-*` → `foxcash-*` (12), headers de webhook `X-NyxCash-*` → `X-FoxCash-*`, docs (70) e frontend/E2E (46). **Banco intocado** — nenhuma tabela usa prefixo `nyx_`; zero migration. Execução na branch `rebrand-foxcash` com gates de build/teste; Fase 6 (Railway/domínio) fica para quando o domínio novo for registrado.

**Delivered by:** Spec 140 (rebranding-foxcash)

### V101: Device fingerprint caseiro — sinal de risco passivo no cadastro/login

O portal do lojista passa a enviar um **fingerprint de dispositivo caseiro** (sem lib externa): o frontend coleta sinais do navegador (canvas, WebGL, fontes instaladas, UA, timezone, resolução, navigator) e reduz a um hash SHA-256 (64 hex) via Web Crypto. O hash é enviado como campo opcional no cadastro (`POST /api/v1/merchants`) e no login (`POST /api/v1/merchant-auth/login`); o backend valida o formato, grava em tabela própria com upsert atômico e associa ao lojista. **Sinal de risco PASSIVO**: registra (tabela + trilha unificada da Spec 41 nos details de `MERCHANT_REGISTERED`/`MERCHANT_LOGIN`), **nunca bloqueia** — a decisão é do master, que pode usar o fingerprint como evidência de mesmo dispositivo em N contas (`distinct_merchants`), frequência de uso (`seen_count`) e janelas de uso. Best-effort por design: hash ausente/malformado/feature off (`foxcash.fingerprint.enabled`, env `FOXCASH_FINGERPRINT_ENABLED`) nunca quebra o request. **Operação proativa (decisão do usuário 2026-08-17):** quando o MESMO hash atinge `alert-threshold` contas distintas (default 2), um alerta `FINGERPRINT_DEVICE_SHARED` é publicado no outbox → webhook do operador (Telegram/Discord), com throttle por hash (300s) e fail-safe (REQUIRES_NEW — nunca derruba cadastro/login); o Master Console expõe `fingerprintDistinctMerchants` no painel do lojista com badge "⚠ N contas no mesmo dispositivo".

**Delivered by:** Spec 141 (fingerprint-portal)

### V102: Tela de cadastro do lojista no portal — autosserviço de onboarding

O portal do lojista ganha a tela de **cadastro** (`/register`), fechando o autosserviço de onboarding: qualquer pessoa cria a conta com **nome, e-mail e senha** (Spec 39 — sem CPF/CNPJ, decisão 2026-08-05), aceita os **termos de uso e condições** (`docs/politicas-uso.md`) e recebe a **API key UMA única vez** (Spec 12) com botão de copiar — a mesma chave que o checkout e os bots usam. A tela herda as proteções já existentes do login: **Turnstile** invisível (Spec 63, quando a site key está configurada) e **fingerprint de dispositivo** (Spec 141) como sinal de risco passivo. O link "Criar conta" aparece na tela de login, e a rota `/register` é pública na SPA (fix de `SecurityConfig.spaPublicPaths` + `PortalSpaController.forwardToIndex` — acesso direto/F5 servia 401 JSON antes). Coberto por **E2E** (`register-ui.spec.js`: cadastro completo com exibição da chave + link no login).

**Delivered by:** Spec 142 (cadastro-ui-portal)

### V103: Central de atendimento estilo Telegram (CRM)

A tela **Clientes** (`/crm`) do portal vira uma central de atendimento no padrão visual do Telegram Desktop/Web: **sidebar de conversas** (lista com avatar, nome, @username/ID, bot, data da última interação, não lidas e badge de status) + **painel direito** da conversa ativa (cabeçalho com avatar, `ID · via bot`, Plano e Última interação, status, botões Pausar/Retomar bot, som, navegação `‹ pos/total ›` e fechar; histórico em bolhas por remetente com mídia e confirmação de leitura ✓/✓✓; rodapé de envio com aviso para lead bloqueado). Tudo isso **sem Tailwind e sem dependência nova** — CSS puro no tema claro do portal (Spec 73), preservando os dados reais da API (Specs 83/84/90): cards de resumo e filtros no topo, SSE em tempo real, envio via REST, marcar lida ao abrir, handoff humano e abertura direta por `?lead={id}` (avatar do header, Spec 102). Aproveita para corrigir o SSE de não lidas (incrementava o lead aberto em vez do lead certo) e refletir atualizações locais na lista em tempo real.

**Delivered by:** Spec 144 (crm-central-telegram)

### V104: Modal de termos de uso no cadastro (Spec 142 UI)

O cadastro do lojista exibe os **termos de uso e condições** na própria tela (sem navegar para fora do site): o link dentro do checkbox abre um **modal** com o conteúdo de `docs/politicas-uso.md` — a **fonte única** do documento, embutida no bundle via import `?raw` (mesmo arquivo do repositório, sem duplicação). A renderização é **leve e segura**: `terms.ts` cobre apenas o subset de markdown usado no documento (títulos, negrito, listas aninhadas com continuação recuada, `---`, tabelas, parágrafos), **escapa o HTML antes** de aplicar o markdown — o texto jurídico nunca renderiza HTML próprio — e **sem dependência de lib** (bundle pequeno). O modal segue o padrão de acessibilidade da Spec 73 (`useDialogFocus` com focus trap/Escape/restauração, `role="dialog"` `aria-modal` `aria-labelledby`, corpo com scroll e botão "Entendi"); o botão do link usa `preventDefault`/`stopPropagation` para não alternar o checkbox do aceite, e o botão "Criar conta" continua desabilitado sem o aceite.

**Delivered by:** Spec 143 (termos-cadastro)

### V105: Landing page pública + cadastro público — vitrine de conversão

A **landing page pública** do FoxCash na raiz do portal (`/`, `portal/src/pages/LandingPage.tsx`) — a vitrine de marketing que converte visitante em lojista — seguindo o **template visual aprovado pelo usuário** (protótipo "aaaaa": identidade escura + laranja-fox) dentro da stack atual do portal (React Router + CSS puro, sem lib de UI — Fase 29). Estrutura fiel ao template: header fixo com blur e estado logado (`useAuth`: deslogado → Login + Criar conta; logado → Painel + Sair), hero com badge "Gateway + Bot em um só lugar" e CTA, 4 cards de métricas (R$ 0,50 / R$ 2,50 / Gateway nativo / PIX instantâneo), seção Recursos (4 cards), Como funciona (4 passos numerados), Integrações (4 grupos), Preços (card "Modelo transparente") e rodapé. A raiz `/` passa a ser a landing pública (o redirect antigo para `/dashboard` foi removido — o login segue levando ao dashboard); todos os CTAs apontam para `/register` (cadastro com API key 1x — Spec 142). **Frontend-only**: zero mudança de contrato HTTP; o backend `/` já era público via `spaPublicPaths()`; a identidade nova é escopada em `.landing` (vars `--fox-*`) e não afeta o tema claro do restante do portal.

**Delivered by:** Spec 129 (landing-cadastro)
