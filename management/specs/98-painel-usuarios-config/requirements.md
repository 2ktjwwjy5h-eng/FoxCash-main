---
name: Painel de usuários — backend escrita (configuração fina por lojista + controle de conta)
status: audited
references:
  - "management/vision.md#V74"
  - "management/specs/97-painel-usuarios-leitura/requirements.md"
  - "management/specs/69-rbac-master-stepup/requirements.md"
  - "management/specs/37-master-audit/requirements.md"
  - "management/specs/91-fallback-adquirente/requirements.md"
  - "management/specs/58-rate-limit-autenticado/requirements.md"
  - "management/specs/88-withdrawal-fee/requirements.md"
  - "management/specs/27-wallet-ledger/requirements.md"
  - "management/specs/34-master-ledger/requirements.md"
  - "management/specs/14-withdrawals/requirements.md"
  - "management/specs/32-withdrawal-destination-flow/requirements.md"
  - "management/specs/16-antifraud-compliance/requirements.md"
  - "management/specs/19-antifraud-real/requirements.md"
---

# Spec 98 — Painel de usuários: backend de escrita (configuração fina por lojista + controle de conta)

## O que entrega

Backend de **escrita** que dá ao operador controle **cirúrgico e individualizado**
sobre cada lojista (decisão do usuário 2026-08-15 — Painel Geral de Usuários).
Tudo sob `ROLE_MASTER` com **RBAC (Spec 69)** + **auditoria (Spec 37)** e
**step-up TOTP (Spec 69)** nas ações sensíveis.

1. **Configuração por lojista** (`GET/PUT /api/v1/master/merchants/{id}/config`):
   - **Regra de taxa** flexível: `pix_fee_type` = `FIXED` | `PERCENT`,
     `pix_fee_fixed`, `pix_fee_percent`, `pix_fee_threshold` — **Pix até o
     valor do threshold cobra taxa fixa; acima, cobra percentual** (regra da
     descrição: "Pix até determinado valor cobra taxa fixa, acima disso cobra
     percentual").
   - **Taxa de saque** (`withdrawal_fee`) — override do R$ 2,50 global da Spec 88.
   - **Limites operacionais**: `pix_min`/`pix_max`, `withdrawal_min`/
     `withdrawal_max` (valores mín/máx permitidos por operação).
   - **Gateway/rota** — **fora desta spec (coordenação 2026-08-15)**: a rota
     agora é **por perfil de operação** (`merchant_profiles`, Spec 122 — dona
     da entidade); o master console não gerencia rota nesta fase.
   - **Rate limit por lojista**: `rate_limit_rps` (req/s) e `rate_limit_rpm`
     (req/min) — override no `AuthenticatedRateLimitFilter` (blindar a API
     contra força bruta/robôs de teste de cartão/chave).
2. **Kill switch de conta**: Ativo/Inativo — `INACTIVE` (status existente da
   Spec 1; bloqueia login no portal, API key e saques).
3. **Debitar saldo** (`POST /api/v1/master/merchants/{id}/config/debit`
   `{amount, reason}`): débito manual do wallet com **ledger novo** (`ADMIN_DEBIT`
   no `WalletLedgerEntryType` e no `MasterLedgerEntryType` — Specs 27/34),
   `reason` obrigatório (400 `reason_required`, padrão write-off Spec 34),
   **auditoria** (Spec 37) e **step-up TOTP** (Spec 69 — ação sensível,
   padrão saque administrativo).
4. **Honeypot** (decisão do usuário — ambiente controlado de monitoramento antifraude):
   flag `honeypot` no lojista. A conta **continua operando** (o atacante não é
   alertado), mas: novas cobranças **roteiam para o provedor trap** (adapter
   mock `honeypot` no `PixProviderRouter` — aceita, nunca processa PAID),
   **saques bloqueados**, webhook `SALE_PAID` nunca chega; evento
   `HONEYPOT_TRIGGERED` no outbox (padrão Spec 64 — alerta ao operador com
   throttle) + auditoria. Reversível.
5. **Bloquear API** (`api_blocked`): API key do lojista → **403** no
   `ApiKeyAuthFilter` (portal segue normal).
6. **Banir** (`POST .../ban`): novo status **`BANNED`** (irreversível —
   bloqueia API, portal, saques e cobranças; mais forte que `INACTIVE`).
   **Desativar** = `INACTIVE` existente; **Rejeitar conta** = **fora de
   escopo** (não há estado PENDING — decisão do usuário).

## Contexto (por que agora)

Descrição de produto do usuário (2026-08-15): a tela de **configuração de
ajuste fino por lojista** — regra de taxa, limites e taxas de saque, gateway
(adquirente), rate limit integrado (req/s e req/min), kill switch e "Debitar
saldo" — do sistema de referência. Análise da lacuna contra o código real: as
taxas são **globais** (`pricing_fee_per_transaction`/`pricing_monthly_fee` e
`withdrawal-fee` da Spec 88); o roteamento é **global** por `risk_score`
(Spec 91 — sem override por lojista); o rate limit é **global**
(`RateLimitProperties` — sem override por principal); `MerchantStatus` só tem
ACTIVE/SUSPENDED/INACTIVE (sem BANNED); não há débito manual com ledger nem
honeypot. Decisões do usuário (2026-08-15): **4 specs** (esta é a 98);
**honeypot = ambiente controlado** (continua operando, fluxo trap, sem alertar
o atacante — resposta de produto detalhada); **banir = BANNED irreversível**;
**rejeitar conta fora de escopo**.

## Critérios de aceite

1. **API Master — config:** `GET /api/v1/master/merchants/{id}/config` →
   `{pixFeeType, pixFeeFixed, pixFeePercent, pixFeeThreshold, withdrawalFee,
   pixMin, pixMax, withdrawalMin, withdrawalMax, rateLimitRps,
   rateLimitRpm, apiBlocked, honeypot}` (valores efetivos: override ou default
   global). `PUT` (parcial — null = não alterar, padrão Spec 48/PATCH) →
   200 com a config atualizada; 404 `merchant_not_found`; 400
   `invalid_argument` em valores inválidos (ex.: `pix_fee_type` inválido,
   percentual fora de 0–100, threshold/limites negativos, `rate_limit_rps`
   ou `rate_limit_rpm` ≤ 0, gateway desconhecido).
2. **Regra de taxa:** `pix_fee_type=FIXED` → cobra `pix_fee_fixed` por
   transação (substitui `pricing_fee_per_transaction`); `PERCENT` →
   `pix_fee_percent`% do valor quando `amount > pix_fee_threshold`, senão
   `pix_fee_fixed` (regra da descrição). O `PricingCalculator` passa a
   considerar a config do lojista (fallback global). Aplicado nas faturas
   (Spec 8) e no `netAmount` das vendas (Spec 25) — mesma fonte.
3. **Gateway/rota — removido desta spec (coordenação 2026-08-15):** o override
   de rota passa a ser **por perfil de operação** (`merchant_profiles`, Spec
   122 — entidade dona da coluna). O master console desta spec não gerencia
   rota; o `RoutingPolicyResolver` (Spec 91) ganha o override por perfil na
   Spec 122. A cobrança continua gravando o `provider` na transação (V58).
4. **Rate limit por lojista:** `rate_limit_rps`/`rate_limit_rpm` → o
   `AuthenticatedRateLimitFilter` (Spec 58) consulta o override do lojista
   antes do teto global; ausente → teto global. Sempre **fail-closed** (sem
   override + sem config → global; nunca menos restritivo que o global por
   acidente — decisão de segurança).
5. **Kill switch:** `POST .../config/deactivate` → status `INACTIVE` (bloqueia
   login/API/saques — comportamento do status existente verificado);
   `POST .../config/activate` → `ACTIVE`. Auditoria `MerchantConfigUpdated`/
   `RISK_ADJUSTMENT` (before/after status — padrão suspend/release Spec 16).
6. **Debitar saldo:** `POST .../config/debit` `{amount (>0, 2 casas), reason
   (obrigatório)}` → 200 `{newBalance, debitedAmount}`; lança
   `WalletLedgerEntryType.ADMIN_DEBIT` (Spec 27) e `MasterLedgerEntryType.
   ADMIN_DEBIT` (Spec 34, balance_after) na mesma transação, sob lock
   pessimista (`findByIdForUpdate` — SEC-19); **step-up TOTP** (Spec 69 —
   `step_up_required` sem janela; reuso `MasterStepUpGuard`); auditoria
   `ADMIN_DEBIT` com before/after saldo e reason; saldo pode ficar negativo
   (decisão: débito administrativo permite negativo, padrão estorno Spec 26).
7. **Honeypot:** `POST .../config/honeypot` `{enabled}` → 200. Com `enabled`:
   `merchants.honeypot = true` → `PixProviderRouter` roteia as cobranças do
   lojista para o **`HoneypotPixProviderClient`** (mock: aceita o create,
   devolve QR, **nunca** dispara webhook PAID — nenhum crédito real);
   saques **bloqueados** (mesmo gate do `withdrawals_blocked`); cada tentativa
   de cobrança publica `HONEYPOT_TRIGGERED` no outbox (Spec 35 — transação
   própria REQUIRES_NEW, **throttle** por janela — padrão Spec 64, fail-safe);
   auditoria `HONEYPOT_MARKED`/`HONEYPOT_UNMARKED`. Reversível (desligar
   restaura o routing normal e os saques — saldo inalterado durante o período).
8. **Bloquear API:** `api_blocked = true` → `ApiKeyAuthFilter` (Spec 12/24)
   responde **403** `api_blocked` para a chave do lojista (login no portal e
   sessão do portal continuam funcionando — a flag é só da API key).
9. **Banir:** `POST .../ban` `{reason}` → status `BANNED` (irreversível; o
   `MerchantStatus` ganha `BANNED` — migration). Bloqueia API key, login no
   portal, saques e cobranças (todos os gates consultam o status). Auditoria
   `MERCHANT_BANNED` com reason. **Não existe** unbanned (irreversível —
   decisão do usuário).
10. **RBAC + step-up:** escrita exige `MasterPermission` (Spec 69): config
    (não sensível) = OPERATOR (bloco completo); **debitar saldo** e **banir** =
    **sensíveis com step-up TOTP** (reuso `MasterStepUpGuard` + lista de
    permissões `foxcash.security.master.step-up.required-permissions`).
    Sem permissão → 403 `forbidden`; sem step-up → 403 `step_up_required`.
11. **Auditoria:** todas as escritas registram `MasterAuditAction` na mesma
    transação (Spec 37 — actor, before/after, reason, ip). Novas actions:
    `MERCHANT_CONFIG_UPDATED`, `ADMIN_DEBIT`, `HONEYPOT_MARKED`/
    `HONEYPOT_UNMARKED`, `MERCHANT_BANNED`, `MERCHANT_ACTIVATED`/
    `MERCHANT_DEACTIVATED`. `PlatformAction` ganha os eventos de runtime
    (`HONEYPOT_TRIGGERED` — trilha da Spec 41 com actor SYSTEM, se aplicável).
12. **Migration V61:** `merchants` += `pix_fee_type VARCHAR(8) NULL`,
    `pix_fee_fixed NUMERIC(15,2) NULL`, `pix_fee_percent NUMERIC(5,2) NULL`,
    `pix_fee_threshold NUMERIC(15,2) NULL`, `withdrawal_fee NUMERIC(15,2) NULL`,
    `pix_min/pix_max/withdrawal_min/withdrawal_max NUMERIC(15,2) NULL`,
    `rate_limit_rps INT NULL`,
    `rate_limit_rpm INT NULL`, `api_blocked BOOLEAN NOT NULL DEFAULT FALSE`,
    `honeypot BOOLEAN NOT NULL DEFAULT FALSE`; `MerchantStatus` += `BANNED`;
    `WalletLedgerEntryType`/`MasterLedgerEntryType` += `ADMIN_DEBIT` (se o enum
    não for DB-backed — os ledgers usam VARCHAR, Spec 52 alargou para 30).
    V60 é da Spec 97 (phone); **V61** é desta spec (proximo livre).
13. **Testes:** unitários + integração (config get/put com validações, regra
    de taxa FIXED/PERCENT/threshold no `PricingCalculator`, override de
    gateway no router, rate limit por lojista no filtro, deactivate/activate,
    debit com step-up + ledger + saldo negativo, honeypot com trap + saques
    bloqueados + `HONEYPOT_TRIGGERED` throttled, api_blocked 403, ban 409/
    irreversível, RBAC 403, auditoria) e E2E Playwright; `mvn test` verde +
    E2E completo sem regressão.

## Dependências

- **Spec 97** (leitura) — painel exibe os overrides; esta spec cria as colunas
  e endpoints de escrita que a 97/99 leem.
- **Spec 69** (RBAC 2 níveis + step-up) — permissões, guards, step-up.
- **Spec 37** (auditoria master) — `MasterAuditRecorder`/`MasterAuditAction`.
- **Spec 91** (routing) — `PixProviderRouter`/`RoutingPolicyResolver` para o
  provedor trap do honeypot (override de rota = Spec 122, por perfil).
- **Spec 122** (perfil de operação) — a rota por lojista é substituída por
  rota por perfil (`merchant_profiles`); esta spec não cria mais
  `gateway_route` no `merchants`.
- **Spec 58** (rate limit autenticado) — `AuthenticatedRateLimitFilter` para o
  override por lojista.
- **Spec 88** (taxa de saque) — `withdrawal-fee` global + override.
- **Spec 27/34** (ledgers) — `ADMIN_DEBIT` nos dois ledgers + `balance_after`.
- **Spec 14/32** (saques) — bloqueio por status/`withdrawals_blocked` (honeypot).
- **Spec 64** (alertas) — padrão de alerta com throttle para `HONEYPOT_TRIGGERED`.
- **Spec 12/24** (API keys) — `ApiKeyAuthFilter` para `api_blocked`.

## Fora de escopo

- **UI** — telas de config no console: **Spec 100**.
- **Rejeitar conta / fluxo de aprovação manual** — não há estado PENDING;
  spec futura de aprovação de cadastro.
- **Edição de telefone** — `merchants.phone` nasce na Spec 97 (V60); a captura/
  edição via PUT da config pode incluir `phone` (aditivo, opcional — decisão na
  implementação; o cadastro Spec 39 não muda).
- **Unban** — BANNED é irreversível por decisão do usuário.
- **Debitar saldo com cobrança** — não; é só débito administrativo manual.
- **Honeypot com provedor real** — o trap é mock interno (não chama adquirente
  real); provedor trap real fica para evolução.
