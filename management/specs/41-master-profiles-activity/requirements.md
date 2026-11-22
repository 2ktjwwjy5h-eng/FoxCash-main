---
name: Gestão de perfis master + relatório unificado de atividades
status: audited
references:
  - "management/vision.md#V24"
  - "management/specs/13-master-auth/requirements.md"
  - "management/specs/37-master-audit/requirements.md"
---

# Spec 41 — Gestão de perfis master + relatório unificado de atividades

## O que entrega

Fechamento operacional da plataforma em **duas frentes** (decisões do usuário
2026-08-05):

### Parte A — Gestão de perfis master (autorizada e oculta, limite 4)

Hoje só existe o seed do primeiro master (Spec 13) e não há como criar/gerir
perfis. Esta spec adiciona:

- Migration **V33** (parte 1): `masters.can_manage_masters BOOLEAN NOT NULL
  DEFAULT FALSE` — o **primeiro master** (seed, ou o mais antigo em migração)
  vira **admin** (`true`); novos perfis nascem `false`.
- **`POST /api/v1/master/profiles`** `{username, name}` → 201 com
  `{id, username, name, active, temporaryPassword}` — **senha gerada pelo
  sistema** (formato `mst_<16 hex>`, devolvida **uma única vez**; apenas o hash
  BCrypt persistido). Exige `ROLE_MASTER` + `can_manage_masters`.
- **`GET /api/v1/master/profiles`** → lista perfis (username/name/active/
  createdAt/`canManageMasters` — **nunca o hash**).
- **`POST /api/v1/master/profiles/{id}/activate|deactivate`** → 204.
  Regras: **não desativa a si mesmo** (409 `cannot_deactivate_self`); **não
  desativa o último admin** (409 `cannot_deactivate_last_admin`).
- **Limite rígido de 4 perfis** (`foxcash.master.max-profiles`, default 4):
  5º → **409 `too_many_masters`**.
- **Auditoria (Spec 37):** `MASTER_CREATED`/`MASTER_ACTIVATED`/
  `MASTER_DEACTIVATED` no `master_audit_log` (actor, before/after, ip).
- **Rota não-documentada publicamente** (só na spec/docs internas). A
  segurança real é autorização (flag) + auditoria — não o path.

### Parte B — Relatório de atividades (trilha unificada)

Hoje as trilhas são separadas (`master_audit_log` = ações do master;
`wallet_ledger`/`master_ledger` = financeiro). Esta spec cria uma **fonte única
de eventos** para o admin enxergar **tudo em todas as contas** (vendedor +
master + sistema):

- Migration **V33** (parte 2): tabela **`platform_activity_log`** (append-only):
  `actor_type (MASTER|MERCHANT|SYSTEM)`, `actor_id`, `action`, `entity_type`,
  `entity_id`, `merchant_id`, `details TEXT` (JSON serializado, sem dados
  sensíveis — mesmo padrão do `master_audit_log`), `ip`,
  `created_at` + índices (created_at/merchant_id/action).
- **`PlatformActivityRecorder`** instrumenta os eventos-chave na mesma
  transação da ação (mesmo padrão do `MasterAuditRecorder`):
  - MASTER: `MASTER_LOGIN`, `MASTER_LOGOUT`, `MASTER_CREATED`,
    `MASTER_ACTIVATED`, `MASTER_DEACTIVATED`;
  - MERCHANT: `MERCHANT_REGISTERED`, `MERCHANT_LOGIN`, `MERCHANT_LOGOUT`,
    `PASSWORD_RESET`, `CHARGE_CREATED`, `SALE_PAID`, `SALE_REFUNDED`,
    `CHARGEBACK_REGISTERED`, `WITHDRAWAL_REQUESTED`, `WITHDRAWAL_COMPLETED`,
    `WITHDRAWAL_FAILED`, `INVOICE_COLLECTED`.
  - SYSTEM: (jobs — o IP é null; quem registrar pode incluir
    `INVOICE_COLLECTED` do scheduler como `SYSTEM`).
- **`GET /api/v1/master/report/activity`** (somente admin — `can_manage_masters`)
  → `PageResponse` de `{id, occurredAt, actorType, actorId, action,
  entityType, entityId, merchantId, details, ip}` com filtros
  (`from`/`to`/`actorType`/`action`/`merchantId`, 400 `invalid_argument`) e
  paginação (padrão `PageResponse`).

## Critérios de aceite

1. Seed cria o primeiro master com `can_manage_masters=true`; migração marca o
   master mais antigo existente como admin (backfill).
2. Perfil sem a flag não acessa `/master/profiles` nem `/master/report/**` →
   **403 `forbidden`** (o login/logout/rotas master normais continuam).
3. `POST /master/profiles` (admin) → **201** com `temporaryPassword` UMA vez
   (`mst_<16 hex>`), apenas hash BCrypt no banco; username duplicado → **409
   `master_already_exists`**; **4 perfis → 5º 409 `too_many_masters`**.
4. `GET /master/profiles` lista sem expor `password_hash`; `activate`/
   `deactivate` → 204; **deactivate de si mesmo → 409
   `cannot_deactivate_self`**; **deactivate do último admin → 409
   `cannot_deactivate_last_admin`**; desativado → login 401.
5. Toda ação de gestão de perfil gera entrada no `master_audit_log`
   (MASTER_CREATED/ACTIVATED/DEACTIVATED) com actor/before/after/ip.
6. `platform_activity_log` registra os eventos-chave listados (verificado via
   SQL/API após ações reais: login de lojista, cobrança criada, saque, reset de
   senha, criação de master); `details` sem dados sensíveis (documento/chave/
   senha/token mascarados); append-only (sem rota de mutação).
7. `GET /master/report/activity` (admin) paginado com filtros
   from/to/actorType/action/merchantId (combinações; 400 em parâmetro inválido);
   401 sem token; 403 sem a flag. `mvn test` verde e E2E sem regressão.

## Dependências

- Spec 13 (perfis master, sessão, seed — base da Parte A).
- Spec 37 (auditoria do master — ações novas entram no `MasterAuditAction`).
- Spec 20/39/40 (login/cadastro/reset do lojista — eventos da Parte B).
- Spec 12/14/19/33/35 (pontos de evento da Parte B).

## Observações

- **"Oculta"** = rota não documentada publicamente (README) + autorização por
  flag + auditoria. Não é segurança por obscuridade isolada — é a camada extra
  sobre a autorização real.
- **Senha gerada pelo sistema** (padrão do projeto, como o antigo
  `portalPassword`): o admin não conhece a senha final; o perfil é ativado com
  a senha entregue 1x ao operador.
- O `platform_activity_log` é **complementar** ao `master_audit_log` (que guarda
  before/after detalhado das ações master) e aos ledgers (financeiro detalhado):
  ele é o **índice cronológico unificado** de eventos para o relatório.
- A flag `can_manage_masters` **não muda por API** nesta spec (o admin é fixo —
  o seed; promoção de perfil é decisão futura).
