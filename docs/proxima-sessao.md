# Próxima sessão — ponto de retomada

> Última atualização: 2026-08-20. **Sessão de testes Bot ↔ Gateway concluída**
> (ver `docs/teste-bot-gateway-2026-08-20.md`): fluxo de venda no chat validado
> em **Telegram e Discord** reais (cobrança → pagamento → SALE_PAID → entrega;
> cobrança avulsa com no-op; webhook v2; refund; mensagens programadas).
> **Spec 150 `audited`** (commit `87a227e` — botId/termsAccepted no
> purchase + GatewayClient + webhook v2; validado no chat real Telegram e
> Discord; `/spec-audit` 5/5 critérios OK, 0 achados). **Fila de implementação:
> Specs 97–121 `verified`** — ver seção
> "Fila de implementação".

## Estado atual

- **Specs 1–61: todas `audited`** (spec 17 `discarded`). O backend TrevoPay está
  completo e auditado na direção aprovada: fundação, produção, portal do
  lojista (incl. frontend SPA — Spec 46), hardening financeiro (ledger,
  idempotência, estorno, reconciliação), operação comercial, cadastro sem CPF,
  recuperação de senha, perfis master, loja white-label multi-vendedora
  (stores/storefront + planos/entregas + botões/menu + mensagens automáticas),
  rate limit distribuído com Redis (Spec 43), observabilidade (Specs 55–57),
  resiliência (Specs 58–60) e HA multi-instância (Spec 61).
- **Spec 62 (Capacity Engineering) `verified`** — **próxima a implementar**
  (agendada para 2026-08-09): capacity model, PostgreSQL como alvo principal,
  PgBouncer/réplicas de leitura/Redis Cluster **somente com evidência** (regra
  de ouro), ledger/wallet como "zona sagrada", load test por estágio
  (100→10.000 vendas/s, rate limit elevado), teste de explosão (quebra bonita
  429) e scale-out linear (2→16), terminando em relatório reproduzível
  (`docs/capacity-report.md`). F0 (tasks 1–2) precisa de `pg_stat_statements`
  ativo no Postgres do Railway — ver "Próximo passo".
- **Spec 47 (adapter real de PIX de saída) `audited`:** implementada com
  **Pagar.me** (API v5) — pendência da Spec 38 fechada (decisão do usuário
  2026-08-05).
- **Git corrigido (2026-08-06):** a instalação de `C:\Program Files\Git` estava
  corrompida (`libexec/git-core` vazio → HTTPS quebrado). Baixado e extraído o
  **MinGit 2.55.0.3** em `~/Apps/mingit` (funcional via HTTPS) e o
  `~/.bashrc` agora prepende o MinGit no PATH (o Git Bash prependia o
  Git quebrado). Repo sincronizado com o origin em `cfbe95a` (2026-08-08).
- **App em `localhost:8080`** é parada após cada ciclo de verificação; suba com
  o perfil dev para E2E:
  `SPRING_PROFILES_ACTIVE=dev NYX_NOTIFICATION_ALLOW_LOOPBACK=true
  mvn spring-boot:run`.

## O que foi feito nesta sessão (2026-08-06)

1. **Git corrigido (ambiental):** a instalação do Git em `C:\Program Files\Git`
   estava corrompida (`mingw64/libexec/git-core/` vazio → qualquer operação HTTPS
   falhava com `git: 'remote-https' is not a git command`). O `winget` abortou
   (EAbort — arquivos em uso + UAC em background). Solução sem admin: baixado e
   extraído o **MinGit 2.55.0.3** (portátil completo) em
   `~/Apps/mingit` + `setx` no PATH do usuário. O Git Bash
   **prependia** `/mingw64/bin` (o quebrado) antes do PATH do Windows —
   corrigido adicionando o prepend do MinGit ao `~/.bashrc` (carregado por
   último, vence a ordem). **Push dos 9 commits pendentes** concluído
   (`edc001e..bc6ac07 main -> main`) e arquivo acidental `…` removido. Repo
   sincronizado.
2. **Spec 50 implementada (`/spec-implement`):** botões, menu e fluxo
   configurável da loja (backend) — migration `V39` `store_buttons`, domínio
   `StoreButton`/`StoreButtonAction`, `StoreButtonPayloadCodec` (payload por
   tipo, criptografado em repouso), `StoreButtonService` +
   `PortalStoreButtonController` (`GET/POST /portal/store/buttons`,
   `PATCH/DELETE /{id}`, `PUT /order`), storefront inclui `buttons[]` ativos
   ordenados, auditoria `BUTTON_CREATED/UPDATED/DELETED`. `mvn test`
   **623/0/0** (+45) e E2E **25/25** (cenário 25 novo) sem regressão.
   **Fix no E2E:** a reordenação violava a unique `(store_id, position)` (o
   Hibernate agrupa os UPDATEs no flush final → 500); corrigido com
   `saveAndFlush` em duas passadas (desloca para posições temporárias `i + n`,
   depois move para a ordem final).
3. **Documentos SDD atualizados:** `management/roadmap.md` (Spec 50 →
   `implemented`, seção de estado detalhada), `task.md` (checklist completo) e
   `requirements.md` (status `implemented`).
4. **App reiniciada** em `localhost:8080` com perfil dev + loopback (E2E) —
   migração `V39` aplicada no boot (verificada no `flyway_schema_history`).
5. **Spec 51 implementada (`/spec-implement`, 2026-08-06):** mensagens
   automáticas + agendamento (backend) — migration `V40` (`store_messages`
   unique `(store_id, event)` + `store_scheduled_messages`), domínio
   `StoreMessage`/`ScheduledMessage` (XOR scheduledAt/cron, cron 6 campos
   Quartz via `CronExpression`, audience BUYERS), `StoreMessageService` +
   `PortalStoreMessageController` (GET/POST upsert 201/200, PATCH/DELETE,
   POST /schedule), `ScheduledMessageQueryService` + `FoxScheduledMessageController`
   (GET vencidas com `recipients` = compradores entregues da Spec 49; POST
   /{id}/sent 204 idempotente), storefront inclui `messages{}` ativas,
   auditoria `MESSAGE_CREATED/UPDATED/DELETED/SCHEDULED` (text nunca em claro).
   `mvn test` **675/0/0** (+52) e E2E **26/26** (cenário 26 novo) sem
   regressão. **Fix de teste:** `CronExpression.next` exige `ZonedDateTime`
   (Instant não suporta DAY_OF_WEEK) — `isDue` avalia o cron em UTC.
6. **Documentos SDD atualizados:** `management/roadmap.md` (Spec 51 →
   `implemented` + seção de estado), `task.md` (checklist completo) e
   `requirements.md` (status `implemented`).

## O que foi feito nesta sessão (2026-08-05)

1. **Auditoria completa de homologação** (como empresa contratada): backend,
   rotas (31 controllers), autenticação, autorização/IDOR, webhooks, fluxo
   financeiro (ledger/saques/chargeback), OWASP Top 10, performance,
   escalabilidade e arquitetura — + 17 testes adversariais dinâmicos.
2. **Achados:** 1 **Alto** (A-01), 6 **Médios** (M-01..M-06), 8 **Baixos**
   (B-01..B-08). Relatório completo na conversa.
3. **Correções aplicadas e commitadas (`87af19f`):**
   - **A-01** — credenciais/segredos padrão conhecidos + webhook sem assinatura
     no perfil default: `application.yml` seguro-por-default (fail-fast sem
     segredos; `verify-signature: true`; `expose-reset-token: false`; sem seed
     de master com senha pública) + **novo `application-dev.yml`** com os
     defaults fake isolados no perfil explícito `dev`; CI e docs atualizados.
   - **M-01** — enumeração de contas no login do portal: senha (BCrypt)
     verificada antes do status → 401 uniforme; 403 `merchant_not_active` só com
     credencial válida; BCrypt dummy contra timing. +3 testes unitários.
   - **M-02** — teto de valor na rota legada `POST /api/v1/transactions`
     (aplicado `charge-max-amount`; 15000 → 400).
   - **M-03** — `temporaryPassword`/`temporary_password` mascarados nos logs.
   - **M-04** — HSTS (1 ano + subdomínios) quando `require-https=true`.
   - **B-02** — README: token do portal corrigido (`nyx_` → `mst_`).
4. **Falsos positivos/limitações:** M-06 era **falso positivo** (segredo do
   webhook de saída já é criptografado em repouso via `CryptoConverter` no
   `MerchantWebhookJpaEntity`); **M-05** (rate limit distribuído/Redis) **não
   implementado** — limitação documentada da topologia single-instance VPS.
5. **Verificação:** `mvn test` **421/0/0** (3 novos) + **E2E 22/22** na
   instância corrigida + fail-fast provado (boot sem perfil → exit 1) + testes
   adversariais das correções PASS.
6. **App reiniciada** em `localhost:8080` com perfil dev (processo antigo
   parado; correções confirmadas via smoke test 3/3).

## Backlog de achados baixos (2026-08-05)

Sem spec engatilhada, resolvi o backlog de achados baixos da homologação
(remediação direta, mesmo padrão das correções `87af19f`). Detalhe:

- **B-01 — `GlobalExceptionHandler` não ecoa mais `ex.getMessage()` em 400/409:**
  os handlers genéricos de `IllegalArgumentException`/`IllegalStateException`
  logam a mensagem no servidor e devolvem 400 `invalid_argument`/409
  `invalid_state` genéricos. `MethodArgumentNotValidException` (Bean Validation)
  continua devolvendo o campo específico. +2 testes.
- **B-03 — `X-Request-Id` sanitizado no `RequestIdFilter`:** apenas
  `[A-Za-z0-9._:-]` (máx. 128); valor fora do padrão (CR/LF, scripts, tamanho
  excessivo) é substituído por UUID — sem ecoar payload que polua headers/logs.
  +1 teste (`RequestIdFilterTest`).
- **B-04 — teto superior em `expirationSeconds`:** 86.400s (24h) — mesmo limite
  do Pagar.me v5. Validado no `CreatePixChargeService` (cobre gateway, portal e
  gift) + `@Max(86_400)` nos DTOs (`PixChargeRequest`, `PortalChargeRequest`,
  `PortalGiftChargeRequest`). +1 teste.
- **B-05 — SSRF parcial no `POST /api/v1/portal/webhooks/test`:** o teste agora
  é por conta do lojista (principal da sessão) e **só aceita URLs de webhooks
  cadastrados dele** (`WebhookManagementService.requireOwnedUrl`) + re-validação
  da URL contra SSRF antes do POST. O servidor não vira relay de requisições a
  hosts arbitrários públicos. +3 testes.
- **B-07 — política de senha (`PasswordPolicy`):** 8–72 caracteres **+ pelo
  menos uma minúscula, uma maiúscula e um número**, aplicada no cadastro (Spec
  39) e no reset (Spec 40). Senhas geradas pelo sistema (master, Spec 41) não
  passam por aqui. +7 testes.
- **B-08 — lockout de login do master:** após `nyxcash.auth.max-login-attempts`
  (5) falhas seguidas, o master fica bloqueado por `lockout-minutes` (15) —
  401 uniforme sem oráculo. Persistido via **migração `V34__master_login_lockout.sql`**
  (colunas `failed_login_attempts`/`locked_until` em `masters`) e gravado em
  transação PRÓPRIA (`MasterLoginLockoutPersister`, padrão `REQUIRES_NEW` do
  `IdempotencyPersister` — o throw dentro do `@Transactional` do login faria
  rollback de um save comum). Usuário inexistente segue 401 sem contador
  (coberto pelo rate limit por IP). **MFA continua como decisão de produto**
  (requer spec nova — 2FA TOTP). +4 testes.
- **Verificação:** `mvn test` **442/0/0** (+21) + **E2E 22/22** na instância
  corrigida + lockout provado no banco (5 falhas → `locked_until` setado) +
  smoke tests B-01/B-03/B-04/B-05 PASS.

## 2ª auditoria de homologação (2026-08-05) — nova rodada completa

Nova auditoria como empresa contratada (backend, rotas, auth, IDOR, webhooks,
fluxo financeiro, OWASP Top 10, performance, escalabilidade): **✅ APROVADO
(condicional)** — zero vulnerabilidades **Críticas/Altas**, fluxos essenciais
funcionando. Revalidou as correções anteriores (todas firmes) e encontrou
**3 Médios + 9 Baixos**. Correções aplicadas nesta sessão:

- **M-A1 — `NoResourceFoundException` → 500 + stack trace em ERROR:** rota não
  mapeada com auth válida (ex.: `GET /api/v1/merchants/`) retornava 500 e
  logava stack trace completo (flood de log por paths aleatórios — cadastro
  público dá API key a qualquer um). Agora **404 `not_found`** com log WARN sem
  stack (`GlobalExceptionHandler` + teste). Confirmado dinamicamente.
- **M-A2 — corrida de idempotência na criação de cobrança:** duas requisições
  com a MESMA `idempotencyKey` podiam ambas passar no `findBy` e a perdedora
  do INSERT caía em `DataIntegrityViolationException` → 500 (o índice único
  `uq_pix_transactions_idempotency` é o backstop — nunca duplica no PSP; o
  impacto era o código de resposta). Agora → **409 `idempotency_conflict`**
  (`CreatePixChargeService` + teste; mesmo contrato do RegisterMerchant/PasswordReset).
- **M-A3 — schedulers com full-scan + N+1:** `WalletReconciliationService.run()`
  e `RiskMonitoringService.computeForAllMerchants()` carregavam **todos** os
  lojistas em memória (`findAll`) + 1 query por lojista. Agora varrem em
  **páginas de 200** (`MerchantRepository.findAll(page,size)` → `PageResult`).
  Testes atualizados.

### Achados baixos B-A1..B-A9 — tratados

Corrigidos nesta rodada (auditoria 2026-08-05, com testes):

- **B-A1 — HMAC-pepper no hash de documento:** `DocumentHasher.hmac` (prefixo
  `h1:`) para o `document_hash` do lojista (CPF/CNPJ — identificador de baixa
  entropia, antes SHA-256 puro brute-forcável offline). O hash é write-only
  (nenhum lookup) — migração segura. `buyerId` (pseudônimo do bot) e tokens de
  alta entropia (chaves/sessões/reset) seguem SHA-256 por construção e pelo
  lookup histórico do antifraude. +2 testes (`DocumentHasherTest`).
- **B-A2 — entropia mínima do `NYX_CRYPTO_SECRET`:** fail-fast no boot
  (`CryptoProperties.validate`): segredo obrigatório, sem placeholder
  não-resolvido e com **≥ 32 caracteres** — a derivação da chave AES via
  SHA-256 é adequada com entropia suficiente (migração de dados evitada). +4 testes.
- **B-A3 — headers de segurança:** `Content-Security-Policy: default-src 'none'`,
  `Referrer-Policy: no-referrer` e `Permissions-Policy` adicionados no
  `SecurityConfig` (HSTS segue condicionado ao `require-https`). Confirmado
  dinamicamente.
- **B-A4 — sanitização de logs robusta a aspas:** `SensitiveDataLoggingFilter`
  agora **parseia o JSON com Jackson** e mascara chaves sensíveis
  recursivamente (fallback regex para não-JSON). Senha com `"` não vaza mais o
  restante. +2 testes.
- **B-A7 — escopo de API key por prefixo de path:** `ApiKeyAuthFilter`
  substituiu `path.contains(...)` por casamento **por segmento**
  (`/api/v1/nyx/pix/**`, `/api/v1/transactions[/**]`, `/api/v1/merchants/*/withdrawals…`)
  — uma rota futura `/transactions-report` não herda o escopo. +2 testes.
- **B-A9 — 400 sem eco de parâmetro:** já mitigado pelo B-01 (handler genérico)
  — confirmado dinamicamente com master real: `?action=hacker` e
  `?status=INVALIDO` → `invalid_argument` genérico, sem ecoar o valor.

Mantidos como decisão documentada (não corrigíveis sem produto/infra):

- **B-A5 — admin por chave estática única:** MFA/identidade por operador é
  decisão de produto (spec nova — 2FA TOTP para o master, mesmo trilho do B-08).
- **B-A6 — rate limit em memória:** Redis somente com multi-instância (limitação
  da topologia single-instance VPS).
- **B-A8 — 401 uniforme para paths sem auth:** escolha deliberada (anti-
  enumeração); revisto quando o frontend existir.

## 3ª auditoria de homologação (2026-08-05) — M-01 corrigido + backlog de baixos

Nova rodada completa (backend, 30 controllers, rotas, authN/authZ, webhooks,
fluxo financeiro, OWASP Top 10, performance, erros, escalabilidade, arquitetura
— revisão estática + verificação dinâmica): **✅ APROVADO (condicional)** — 0
**Críticos**, 0 **Altos**; **1 Médio (M-01) corrigido** e **6 Baixos** no
backlog. Fluxos essenciais validados ao vivo (IDOR 403, cross-tenant 403,
roles 403, rate limit 429, 404/400 sem eco, headers CSP/Referrer/Permissions).
*Numeração local desta rodada (M-01/B-01..B-06) — difere da 1ª rodada
(M-01..M-06/B-01..B-08) e da 2ª (M-A1..M-A3/B-A1..B-A9).*

- **M-01 — DoS de memória por corpo sem teto + cache ilimitado de log
  (CORRIGIDO, `86af4ff`):** o `ContentCachingRequestWrapper` default cacheia o
  body inteiro em memória (ilimitado) e não havia teto global de corpo — um
  cliente com qualquer API key (cadastro público) podia enviar um corpo de
  centenas de MB e derrubar o heap da instância single-VPS. Novo
  `RequestBodySizeFilter` lê no máximo 1 MiB (`readNBytes` — nunca aloca o
  corpo inteiro) e responde **413 `payload_too_large`** (fast path por
  `Content-Length`; corpo válido reexposto via `CachedBodyHttpServletRequest`);
  `SensitiveDataLoggingFilter` agora cacheia no máximo 64 KiB
  (`contentCacheLimit`). +6 testes. Confirmado ao vivo: POST 1.5 MiB → 413;
  cadastro 201; webhooks intactos.
- **B-01 — timing side-channel no login do master** (enumeração de username):
  usuário inexistente responde 401 imediato, existente executa BCrypt — falta o
  `DUMMY_BCRYPT_HASH` que o login do lojista já usa. Mitigado por rate
  limit/lockout.
- **B-02 — `reset-password` público sem rate limit** (fora do `RateLimitFilter`).
- **B-03 — `pix_key` em repouso sem criptografia** (`PixTransactionJpaEntity` —
  aplicar `CryptoConverter`, padrão de `document`/destinos).
- **B-04 — saque chama o PSP dentro da `@Transactional`** (pool retido durante
  o I/O de rede; padrão inconsistente com o fluxo de cobrança já corrigido) —
  reavaliar com multi-instância.
- **B-05 — webhook de PAID não valida o `amount` do PSP** (divergência
  silenciosa de valor entre PSP e plataforma não detectada pela reconciliação).
- **B-06 — login do lojista sem lockout por conta** (só rate limit por IP).
- **Verificação:** `mvn test` **460/0/0** (+6) + E2E 22/22 + smoke adversariais
  ao vivo (headers/404/400/403/429/413) + app reiniciada em dev com o fix.

## Fila de implementação (2026-08-15, atualizada pós-auditoria 122/123/124) — ordem natural

As specs **97–121** estão `verified` (Fases 33–35). **Specs 122/123/124
`audited`** (Fase 36 concluída — Perfil de operação fase 1+2). **Ordem natural =
numérica** (cada fase já está em ordem de dependência — backend antes do
frontend por bloco). Fluxo: `/spec-implement N` por spec, `mvn test` + E2E após
cada uma (nunca pular a verificação).

| Ordem | Spec | Fase | Depends on |
|---|---|---|---|
| 1 | 97 Painel usuários — backend leitura | 33 | — (migration V60) |
| 2 | 98 Painel usuários — backend escrita | 33 | 97 (V61) |
| 3 | 99 Painel usuários — UI | 33 | 97 + 98 |
| 4 | 100 Config fina por lojista — UI | 33 | 98 |
| 5–22 | 101→119 Portal rico (pares backend→UI) | 34 | por bloco (V62–V67) |
| 23 | 120 **PVS backend** | 35 | — (V68) |
| 24 | 121 **PVS UI** | 35 | 120 |

**Fase 34 (101–119) por bloco:** 101→102 (shell), 103→104 (dashboard),
105→106 (financeiro), 107→108 (gateways), 109→110 (links/redirecionamento), 111→112
(afiliado), 113→114 (ranking/premiações), 115→116 (checkout/bio/sorteio),
117→118 (automações), 119 (leves).

**Mapa de migrations:** V60=97 · V61=98 · V62–V67=101–119 · V68=120.
(Últimas reais no repo: **V69/V70** — Specs 122/124, já aplicadas.)

**Coordenações:**
- `merchants.gateway_route` era dona da Spec 122 (V69); a Spec 98 (V61) usa
  `ADD COLUMN IF NOT EXISTS` (nota adicionada na task da 98) → **qualquer ordem
  de implementação entre 98 e 122 funciona** (e a 122 já foi — V69 no banco).
- 121 é UI que consome o backend 120 — implementar nessa ordem.
- Fases 33/34 não bloqueiam 35/36 (backends auto-contidos).

**Sugestão para a próxima sessão:**
- **120 → 121** (PVS — backends auto-contidos, destrava a feature mais recente)
  ou seguir a ordem numérica a partir de **97**.
- **Não fazer as 27 numa tacada** — 3–4 specs/dia com verificação é o ritmo dos
  ciclos anteriores.

**Já implementadas e auditadas (não re-fazer):** Specs 1–96 `audited` (spec 17
`discarded`) + **Specs 122/123/124 `audited`** (Perfil de operação — V69/V70
aplicadas, seletor + filtro rígido no ar); a fila 97–121 abaixo é o que falta.

## Como rodar a verificação (comandos usados)

```bash
# 1. Testes unitários
mvn test               # 1101 testes, 0 falhas (2026-08-08)

# 2. E2E Playwright (requer app no ar + PostgreSQL nativo 18, serviço postgresql-x64-18)
#    Perfil dev obrigatório (homologação 2026-08-05): o application.yml base
#    agora é seguro-por-default (fail-fast sem segredos). O cenário 15 (webhook
#    de saída) exige NYX_NOTIFICATION_ALLOW_LOOPBACK=true na APP. O portal-ui
#    (Spec 46) exige NYXCASH_PORTAL_SERVE_FRONTEND=true.
SPRING_PROFILES_ACTIVE=dev NYX_NOTIFICATION_ALLOW_LOOPBACK=true NYXCASH_PORTAL_SERVE_FRONTEND=true mvn spring-boot:run  # app em localhost:8080
cd e2e-tests && npx playwright test  # 105/105 (2026-08-08)
```

## Observações

- **Git corrigido (2026-08-06):** MinGit 2.55.0.3 em `~/Apps/mingit`
  (instalação de `C:\Program Files\Git` corrompida — `libexec/git-core` vazio).
  O `~/.bashrc` prepende o MinGit no PATH (o Git Bash prependia o
  quebrado); o `setx` no PATH do Windows vale para cmd/PowerShell. Para parar a
  app: `powershell -Command "Stop-Process -Id <PID 8080> -Force"` (`taskkill`
  via Git Bash falha silenciosamente).
- **Sem o perfil `dev`, o boot falha de propósito** (fail-fast da correção
  A-01) — o `application.yml` base exige `NYX_CRYPTO_SECRET`,
  `NYX_ADMIN_API_KEY`, `NYX_PSP_WEBHOOK_SECRET` e
  `NYX_PSP_PAYOUT_WEBHOOK_SECRET` (produção = `application-prod.yml`).
- Rotas novas da Spec 41 (perfis master e relatório de atividades) são
  **deliberadamente não-documentadas** no README público — autorização por flag
  (`can_manage_masters`) + auditoria (Spec 37), não obscuridade.
- **Lockout do master (B-08):** contador/lockout persistem em `masters`
  (`failed_login_attempts`, `locked_until` — migração `V34`). O save da falha
  roda em transação própria (`MasterLoginLockoutPersister`, REQUIRES_NEW) — um
  save comum seria revertido pelo throw dentro do `@Transactional` do login
  (validado no banco: 5 falhas → `locked_until` setado). Senhas de teste com a
  política nova (B-07) precisam de minúscula + maiúscula + número (ex.:
  `Senha-Segura-123` — já é o padrão do README/E2E).
- `DocumentValidator` continua (Spec 31 — destinos de saque PIX_CPF/PIX_CNPJ).
- Para evitar OOM do DsCode ao gerar muitas specs: `NODE_OPTIONS=--max-old-space-size=4096`.
