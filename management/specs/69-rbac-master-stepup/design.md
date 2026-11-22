---
name: Autorização master em 2 níveis (ADMIN/OPERATOR) + step-up MFA em ações sensíveis
status: audited
---

# Spec 69 — Design: Autorização do master em 2 níveis + step-up MFA

## D1 — Catálogo de permissões e níveis (2 tiers)

### D1.1 Conceito

**Decisão do usuário (2026-08-11): autorização em 2 níveis.** Todo master
operacional nasce com o **bloco completo** de ações (OPERATOR+FINANCE+AUDITOR
juntos); a função **ADMIN** é separada (`can_manage_masters=true`) e soma a
gestão de perfis master. O **catálogo de permissões por ação** continua sendo o
**contrato** (guard, `/me`, config de step-up), mas a **concessão é por nível**, não
por escolha de papel no cadastro — sem `master_permissions`, sem least-privilege
granular (simplicidade deliberada do usuário).

### D1.2 Catálogo de permissões (fonte única)

Namespace por domínio — cola 1:1 com `MasterAuditAction` (Spec 37) e com as
rotas existentes:

| Permissão | Rota (existente) | MasterAuditAction | Nível | Sensível (step-up) |
|---|---|---|---|---|
| `merchant.suspend` | `POST /api/v1/master/merchants/{id}/suspend` | `RISK_ADJUSTMENT` (via `MerchantStatusControlService`) | OPERATOR | ❌ |
| `merchant.release` | `POST /api/v1/master/merchants/{id}/release` | `RISK_ADJUSTMENT` (via `MerchantStatusControlService`) | OPERATOR | ❌ |
| `withdrawal.block` | `POST /api/v1/master/merchants/{id}/withdrawals/block` | `WITHDRAWALS_BLOCK` | OPERATOR | ❌ |
| `withdrawal.release` | `POST /api/v1/master/merchants/{id}/withdrawals/release` | `WITHDRAWALS_RELEASE` | OPERATOR | ❌ |
| `withdrawal.administrative` | `POST /api/v1/master/merchants/{id}/withdrawals/administrative` | `ADMINISTRATIVE_WITHDRAWAL` | OPERATOR | ✅ |
| `receivable.write_off` | `POST /api/v1/master/receivables/{id}/write-off` | `RECEIVABLE_WRITE_OFF` | OPERATOR | ✅ |
| `invoice.collect` | `POST /api/v1/master/merchants/{id}/invoices/{id}/collect` | `INVOICE_COLLECT` | OPERATOR | ✅ |
| `master.profiles.manage` | `POST /api/v1/master/profiles` + `/{id}/activate|deactivate` | `MASTER_CREATED/ACTIVATED/DEACTIVATED` | **ADMIN** | ✅ |

> Auditoria confirmada (2026-08-11): suspend/release do lojista **já geram**
> `MasterAuditAction.RISK_ADJUSTMENT` no `MerchantStatusControlService` (Spec 37)
> — o catálogo acima reflete o código real. Sem pendência de auditoria.

### D1.3 Dois níveis (decisão do usuário 2026-08-11)

| Nível | Determinado por | Permissões (catálogo D1.2) | Uso típico |
|---|---|---|---|
| `OPERATOR` (default) | `can_manage_masters = false` | **Todas as 7 operacionais** (OPERATOR+FINANCE+AUDITOR **juntos**: suspend/release, block/release, administrative, write_off, collect) + leitura | Suporte + tesouraria + auditoria — um perfil operacional completo |
| `ADMIN` | `can_manage_masters = true` (seed) | 7 operacionais **+** `master.profiles.manage` | Função separada: gestão de perfis master (superuser) |

Não existem mais papéis mutuamente exclusivos por cadastro: `role` é **derivado**
da flag (`ADMIN` ↔ true, `OPERATOR` ↔ false). A leitura é neutra (ROLE_MASTER
basta) para os dois níveis.

## D2 — Persistência

### D2.1 Migration (Flyway)

Nova migration **`V47__master_step_up.sql`** (último V no backend = **V46**,
checado em 2026-08-11) — **aditiva única**:

```sql
ALTER TABLE master_sessions ADD COLUMN stepped_up_at TIMESTAMPTZ NULL;
```

**Nenhuma tabela nova** (sem `master_permissions` — decisão do usuário 2026-08-11:
concessão por nível, derivada de `can_manage_masters`). Nenhum backfill/UPDATE:
masters existentes preservam o acesso operacional atual; a única mudança
comportamental é o step-up (D4) nas ações sensíveis. `can_manage_masters`
continua sendo a flag de nível (compat + seed).

### D2.2 Catálogo (sem persistência nova)

- Enum de domínio **`MasterPermission`** com as 8 permissões (D1.2) — fonte
  única do catálogo, reutilizada por guard, `/me` e config de step-up.
- **Sem entidade/porta de permissões**: a concessão é por nível, derivada de
  `can_manage_masters` (lida via `MasterRepository` no guard — D3.1). Nada a
  persistir além do que já existe em `masters`.

## D3 — Guard por permissão (autorização na transação)

### D3.1 `MasterPermissionGuard`

Novo service (padrão do `MasterAdminGuard` existente):

```java
@Service
public class MasterPermissionGuard {
    // carrega master por id (findById) e valida isActive() — senão 403
    // resolve o NÍVEL: permissões operacionais → qualquer master ativo;
    // MASTER_PROFILES_MANAGE → master.canManageMasters() — senão
    // MasterPermissionDeniedException
    public Master requirePermission(UUID actorMasterId, MasterPermission permission);
    public Master requirePermission(UUID actorMasterId, String permission); // valida no catálogo
}
```

`MasterPermissionDeniedException` → `GlobalExceptionHandler` → **403**
`{ "code": "forbidden", ... }` (mesmo code do `MasterProfileAccessDeniedException`
— nenhuma distinção ao atacante).

### D3.2 Onde entra (services, não controllers)

| Service atual | Chamada adicionada |
|---|---|
| `MasterWithdrawalControlService.block/release` | `requirePermission(actor, WITHDRAWAL_BLOCK/RELEASE)` (OPERATOR — todo master ativo) |
| `MasterWithdrawalService.administrativeWithdrawal` | `requirePermission(actor, WITHDRAWAL_ADMINISTRATIVE)` + **step-up** |
| `ReceivableWriteOffService.writeOff` / `InvoiceCollectionService.collect` | `requirePermission(actor, RECEIVABLE_WRITE_OFF / INVOICE_COLLECT)` + **step-up** |
| `MerchantStatusControlService.suspend/release` (via `MasterRiskController`) | `requirePermission(actor, MERCHANT_SUSPEND / MERCHANT_RELEASE)` (OPERATOR) |
| `MasterProfileService.create/activate/deactivate` | `requirePermission(actor, MASTER_PROFILES_MANAGE)` (resolvida para `can_manage_masters`) + **step-up** — mantém `MasterAdminGuard` como implementação do nível ADMIN |

Decisão: **guard nos services** — protege HTTP **e** Operator Bot (Spec 67) e
futuro console na mesma transação; o `actor` vem do `MasterAuthFilter` (já
popula `Authentication` com `masterId`).

### D3.3 Leitura permanece ROLE_MASTER

Dashboards, revenue, risco, withdrawals list, receivables, audit, activity —
sem guard por permissão (nenhum efeito colateral). `MasterActivityReportController`
continua com `MasterAdminGuard` (é relatório de atividade, não leitura neutra —
decisão mantida).

## D4 — Step-up MFA

### D4.1 Modelo de sessão

`master_sessions` ganha `stepped_up_at TIMESTAMPTZ NULL` (coluna adicionada na
mesma **V47** da F0 — D2.1). Ação sensível exige
`stepped_up_at != null && now() - stepped_up_at <= janela` (default **15 min**,
configurável). Não é exigido TOTP a cada request sensível — padrão de indústria
(session claim de step-up), UX aceitável no bot/console, e o anti-replay do
`verifySecondFactor` já existente é reutilizado.

### D4.2 Fluxo

```text
POST /api/v1/master/receivables/{id}/write-off   (ROLE_MASTER, sessão ativa)
  → MasterPermissionGuard: tem "receivable.write_off"?  → 403 forbidden
  → ação é SENSÍVEL? → sessão.stepped_up_at válido (≤15min)?
       ├─ sim → executa → audit ✅
       └─ não → 403 { error: { code: "step_up_required" } }
              → cliente abre prompt TOTP
              → POST /api/v1/master/mfa/step-up { code }
                   → MasterMfaService.verifySecondFactor(masterId, code)
                        (TOTP lastCodeHash anti-replay + recovery mrc_ single-use
                         + lockout 5 falhas — reuso, nada novo)
                   → grava stepped_up_at = now() na sessão → 200
              → cliente REPETE a ação original → passa o gate → executa → audit
```

### D4.3 Semântica HTTP

- **`403 { code: "step_up_required" }`** — sessão é válida; falta requisito da
  ação. Distinto de `forbidden` (sem permissão) e `unauthorized` (sessão morta).
- **Não usar 401**: o interceptor do portal (`apiClient`) trata 401 como sessão
  expirada → logout; derrubaria o master em vez de pedir o TOTP.

### D4.4 Novos pontos de auditoria

- `MasterAuditAction.MASTER_STEP_UP` — registro do step-up em si (quem, quando,
  qual sessão). Hoje só login/logout/change-password são auditados.
- `change-password` já revoga todas as sessões (`revokeAllByMasterId`, SEC-25)
  → janelas de step-up abertas morrem junto. Nenhum passo extra.

### D4.5 Configuração

```yaml
foxcash:
  security:
    master:
      step-up:
        required-permissions:  # lista de permissões que exigem step-up
          - withdrawal.administrative
          - receivable.write_off
          - invoice.collect
          - master.profiles.manage
        window: 15m            # janela de validade do step-up por sessão
```

Fail-closed: se `required-permissions` for inválida/vazia em prod → boot falha
(defaults aplicados; decisão de segurança).

## D5 — Mudanças de API (aditivas)

| Rota | Mudança |
|---|---|
| `POST /api/v1/master/profiles` | **sem mudança** — permanece `{username, name}`; novos perfis nascem OPERATOR (`can_manage_masters=false`) |
| `GET /api/v1/master/profiles` | **sem mudança** (o `role` derivado é exposto via `GET /api/v1/master/me` da Spec 71, não aqui) |
| `POST /api/v1/master/mfa/step-up` | **NOVO** — body `{ "code": "123456" }`, exige ROLE_MASTER, 200 ou 401/403 do MFA |
| Demais rotas master | **sem mudança de shape** |

O contrato OpenAPI do `master-client` (spec futura) cobre esses campos sem
retrabalho — as rotas não mudam, só ganham corpo/erro.

## D6 — Rate limit das rotas master (fecha o bypass de cardinalidade do SEC-23)

### D6.1 Achado (2026-08-11)

`RateLimitKey.VARIABLE_PREFIXES` **não inclui** `/api/v1/master` nem
`/api/v1/admin`. O `AuthenticatedRateLimitFilter` (limite por **principal**,
Spec 58) usa `RateLimitKey.normalize` — portanto `POST
/api/v1/master/merchants/{id}/suspend` para N lojistas cria **N buckets** do
limite por principal → **bypass de cardinalidade** (vazão N × teto). É a MESMA
classe do SEC-23, que corrigiu as rotas de recursos do lojista
(`/api/v1/merchants`, `/api/v1/portal/**`) mas deixou as rotas master fora.

**Correção:** adicionar ao `VARIABLE_PREFIXES`:

```java
"/api/v1/master",
"/api/v1/admin",
```

O colapso por prefixo cobre `POST /master/merchants/{id}/suspend`, `POST
/master/receivables/{id}/write-off`, `POST /master/profiles/{id}/activate` etc.
— o bucket reflete a rota, não o recurso. Nota: `POST /api/v1/master/profiles`
(sem `{id}`) também casa com o prefixo `/api/v1/master` — aceitável (um bucket
para toda a gestão de perfis por principal).

### D6.2 Rate limit do `/api/v1/master/mfa/step-up`

O step-up é superfície de verificação de código — merece teto próprio por
principal **e** por IP (defesa em profundidade; o `verifySecondFactor` já tem
lockout de 5 falhas, o teto protege a superfície antes do lockout e cobre o
caso de múltiplos masters). O endpoint entra como rota de auth no
`RateLimitFilter.LIMITED_PATHS` (padrão das rotas de login — bucket por IP) e é
coberto pelo `AuthenticatedRateLimitFilter` (bucket por principal) — sem
mudança adicional, apenas garantir que o path não está excluído.

## D7 — Masters não-admin preservam acesso (decisão do usuário 2026-08-11)

O modelo de 2 níveis **não remove capacidade operacional**: todo master com
`can_manage_masters=false` continua com o bloco completo de escritas
operacionais — exatamente o acesso que já tem hoje via `ROLE_MASTER`. A única
mudança comportamental é:

1. **Step-up** nas 3 ações de FINANCE (`withdrawal.administrative`,
   `receivable.write_off`, `invoice.collect`) — exigência nova para **todos**
   os níveis (D4).
2. **Negação da gestão de perfis** (`master.profiles.manage`) — que já era
   negada aos não-admin via `MasterAdminGuard`.

**Consequência:** nenhuma migration de dados, nenhum `UPDATE` em `masters`,
nenhum relatório de "quem perdeu permissão" — ninguém perde. A decisão fica
registrada no requirements (critério 13).

## D8 — E2E dos fluxos master + default do Operator Bot

### D8.1 Cobertura E2E (além do unitário)

A suíte E2E existente (105 casos) ganha casos novos dos fluxos master:

- `403 forbidden` — master autenticado sem a permissão da ação;
- `403 step_up_required` — master com permissão mas sem step-up válido;
- fluxo completo — `step-up` → repetição da ação → executa (200/204);
- janela expirada — `stepped_up_at` vencido → `step_up_required` de novo;
- `change-password` → sessões revogadas → step-up morto.

Perfis de teste com permissões/step-up adequados (seed de teste), sem exigir o
`verifySecondFactor` de produção.

### D8.2 Default do Operator Bot (Spec 67) como critério

As ações sensíveis (4) exigem step-up **em qualquer cliente** — o guard no
service protege o bot. Default de produto: **o Operator Bot fica restrito a
ações NÃO sensíveis** (leituras + suspend/block/release) e nunca executa as 4
sensíveis via Discord — evita o problema de pedir TOTP em interação de chat. Se
um dia o operador quiser ações sensíveis pelo bot, é decisão de produto com
fluxo de step-up dedicado (fora desta spec). Este default vira critério de
aceite verificável (critério 15).

## D9 — Seqüência de implementação (Fases)

- **F0 — Catálogo + migration**: enum `MasterPermission` (fonte única) +
  migration `V47__master_step_up.sql` (só `stepped_up_at`). Testes: migration
  aplica limpa, catálogo == rotas.
- **F1 — Guard + wiring + rate limit**: `MasterPermissionGuard` (tier-based),
  exceção, handler 403, guard nas 8 escritas (operacionais → master ativo;
  `MASTER_PROFILES_MANAGE` → `can_manage_masters`), `VARIABLE_PREFIXES` ganha
  `/api/v1/master` + `/api/v1/admin`. Testes: 403 na gestão de perfis para
  OPERATOR, operacional OK para qualquer master ativo, bypass de cardinalidade
  fechado (N lojistas → 1 bucket).
- **F2 — Nível + seed**: seed mantém ADMIN (`can_manage_masters=true`);
  `POST/GET profiles` **inalterados** (novo perfil nasce OPERATOR); `role`
  derivado exposto no `/me` (Spec 71). Testes: seed ADMIN, perfil novo
  OPERATOR com bloco completo, `role` derivado.
- **F3 — Step-up**: coluna `stepped_up_at`, endpoint `/mfa/step-up` (com rate
  limit), gate nas 4 sensíveis, `403 step_up_required`, auditoria
  `MASTER_STEP_UP`. Testes: fluxo completo, janela expirada, reuso de recovery
  code, lockout, `change-password` limpa step-up, rate limit do step-up.
- **F4 — Config + docs + E2E**: `foxcash.security.master.step-up.*`,
  fail-closed, casos E2E master (D8.1), default do Operator Bot como critério,
  roadmap/requirements/task sync.

## D10 — Rastreabilidade critérios ↔ design ↔ task

| Critério (requirements) | Design | Task |
|---|---|---|
| 1–2 (catálogo + 2 níveis) | D1.2, D1.3, D2.1, D2.2 | F0, F2 |
| 3 (guard tier-based nos services) | D3.1, D3.2 | F1 |
| 4 (deny-by-default na função admin) | D1.3, D3.2 | F1 |
| 5 (papéis derivados ADMIN/OPERATOR) | D1.3, D5 | F2 |
| 6–8 (step-up sessão/endpoint/ações) | D4.1–D4.3, D4.5 | F3 |
| 9 (change-password limpa step-up) | D4.4 | F3 |
| 10 (sem quebra) | D5 | F0–F4 |
| 11 (rate limit cardinalidade master) | D6.1 | F1 |
| 12 (rate limit do step-up) | D6.2 | F3 |
| 13 (não-admin preserva acesso) | D7 | F2 |
| 14 (E2E dos fluxos master) | D8.1 | F4 |
| 15 (Operator Bot default) | D8.2 | F4 |
