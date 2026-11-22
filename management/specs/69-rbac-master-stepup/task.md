---
name: Autorização master em 2 níveis (ADMIN/OPERATOR) + step-up MFA em ações sensíveis
status: audited
---

# Spec 69 — Task: Autorização do master em 2 níveis + step-up MFA

## F0 — Catálogo + migration (critérios 1–2)

- [x] T0.1: Enum de domínio `MasterPermission` com o catálogo de 8 permissões
      (D1.2) — fonte única (guard, `/me`, config de step-up).
- [x] T0.2: Migration **`V47__master_step_up.sql`** (último V no backend =
      **V46**, checado 2026-08-11) — aditiva única:
      `ALTER TABLE master_sessions ADD COLUMN stepped_up_at TIMESTAMPTZ NULL`
      (D4.1). **Sem tabela `master_permissions`** (decisão do usuário 2026-08-11:
      concessão por nível) — sem backfill/UPDATE.
- [x] T0.3: Testes — migration aplica limpa (flyway), coluna nullable criada,
      catálogo do enum == rotas existentes.

## F1 — Guard tier-based + wiring + rate limit (critérios 3–4, 11)

- [x] T1.1: `MasterPermissionGuard.requirePermission(actorId, permission)` —
      resolve o **nível** do master: permissões operacionais → master **ativo**
      (via `MasterRepository.findById` + `isActive()`); `MASTER_PROFILES_MANAGE`
      → `canManageMasters()`. Negado → `MasterPermissionDeniedException`
      (403 `forbidden`, mesmo code do `MasterProfileAccessDeniedException`).
- [x] T1.2: `GlobalExceptionHandler` mapeia a exceção → 403.
- [x] T1.3: Guard nas 8 escritas (D3.2) — services:
      `MasterWithdrawalControlService` (block/release),
      `MasterWithdrawalService` (administrative),
      `ReceivableWriteOffService.writeOff`, `InvoiceCollectionService.collect`,
      `MerchantStatusControlService` (suspend/release — via `MasterRiskController`),
      `MasterProfileService` (create/activate/deactivate — guard
      `MASTER_PROFILES_MANAGE` = `MasterAdminGuard`, que vira a implementação
      do nível ADMIN).
- [x] T1.4: Testes — OPERATOR recebe **403 na gestão de perfis** (critério 4);
      OPERATOR executa as 7 operacionais normalmente (critério 2); ADMIN
      executa tudo; leituras seguem ROLE_MASTER sem guard.
- [x] T1.5: Confirmar que `MasterAdminGuard` é a implementação do nível ADMIN
      (`MASTER_PROFILES_MANAGE`) e documentar no javadoc.
- [x] T1.6: **Critério 11 (SEC-23 fechado no master)** — adicionar
      `"/api/v1/master"` e `"/api/v1/admin"` ao
      `RateLimitKey.VARIABLE_PREFIXES` (D6.1).
- [x] T1.7: Testes do T1.6 — bypass de cardinalidade fechado: master com N
      lojistas/recursos → **1 bucket** (N requisições em rotas distintas gastam
      o MESMO teto por principal); rotas master fora do colapso → bucket por
      rota exata.

## F2 — Nível + seed (critérios 5, 13)

- [x] T2.1: Nível derivado — helper `MasterLevel.of(master)` →
      `ADMIN` (canManageMasters) | `OPERATOR` (D1.3), reutilizado pelo guard e
      pelo `/me` (Spec 71).
- [x] T2.2: `MasterSeedRunner` — primeiro master permanece ADMIN
      (`can_manage_masters=true`; comportamento atual, critério 5).
- [x] T2.3: `POST /api/v1/master/profiles` **inalterado** — novo perfil nasce
      OPERATOR (`Master.create` já faz `canManageMasters=false`; sem mudança).
- [x] T2.4: `GET /api/v1/master/profiles` **inalterado** (o `role` derivado é
      exposto pelo `GET /api/v1/master/me` da Spec 71, não aqui).
- [x] T2.5: Testes — perfil novo OPERATOR executa as 7 operacionais e recebe
      403 na gestão de perfis; ADMIN executa tudo + perfis; `role` derivado
      correto para ambos.
- [x] T2.6: **Critério 13 (não-admin preserva acesso)** — sem migration de
      dados: `mvn test` verde com masters existentes mantendo o acesso
      operacional (nenhum teste espera 403 em operacional para OPERATOR).

## F3 — Step-up MFA (critérios 6–9, 12)

- [x] T3.1: Migration aditiva — coluna `stepped_up_at TIMESTAMPTZ NULL` em
      `master_sessions` (**V47 da F0**, D2.1 — migration única).
- [x] T3.2: `MasterSession`/entity ganham `steppedUpAt` + método
      `isSteppedUp(Instant now, Duration window)`.
- [x] T3.3: Porta de sessão ganha `markSteppedUp(UUID sessionId, Instant now)`
      (ou no fluxo existente do token).
- [x] T3.4: Endpoint novo `POST /api/v1/master/mfa/step-up` (body `{ code }`,
      ROLE_MASTER) → `MasterMfaService.verifySecondFactor` (reuso) → grava
      `stepped_up_at` na sessão → 200. Falha → 401/403 do MFA sem gravar.
- [x] T3.5: Gate de step-up nas 4 sensíveis (D4.5 default):
      `withdrawal.administrative`, `receivable.write_off`, `invoice.collect`,
      `master.profiles.manage` — `403 { code: "step_up_required" }` quando
      ausente/vencido.
- [x] T3.6: Auditoria — novo `MasterAuditAction.MASTER_STEP_UP` gravado no
      sucesso do step-up.
- [x] T3.7: `change-password` (`revokeAllByMasterId`, SEC-25) limpa
      `stepped_up_at` das sessões revogadas — janelas de step-up morrem junto.
- [x] T3.8: Testes — fluxo completo (403 → step-up → 200 → repete → executa);
      janela expirada; recovery code no step-up; lockout 5 falhas; falha não
      grava step-up; change-password limpa.
- [x] T3.9: **Critério 12 (rate limit do step-up)** — `POST
      /api/v1/master/mfa/step-up` entra em `RateLimitFilter.LIMITED_PATHS`
      (bucket por IP, D6.2) + coberto por principal. Teste: 429 ao estourar o
      teto no endpoint.

## F4 — Config + docs + E2E (critérios 10, 14–15)

- [x] T4.1: `foxcash.security.master.step-up.required-permissions` (default 4)
      + `window` (default 15m) + binding test contra o yml (padrão Spec 68).
- [x] T4.2: Fail-closed: lista inválida/vazia em prod → boot falha (defaults
      aplicados) — teste de contexto.
- [x] T4.3: `mvn -o clean test` verde (**1303/0/0**) + E2E **110/110** (105
      existentes mantidos + 5 novos; perfis de teste com permissões/step-up
      adequados — cenários 13/14/18/22 da journey com seed de step-up, D8.1).
      Observação: o cenário 22 exigiu ajuste de assertion pré-existente
      (temporaryPassword `mst_<16 hex>` → `<32 hex>`, SEC-25 2026-08-11).
- [x] T4.4: **Critério 14 (E2E dos fluxos master)** — casos novos (D8.1):
      403 `forbidden` (OPERATOR em gestão de perfis); 403 `step_up_required`
      (sem step-up em ação de FINANCE); fluxo completo (`step-up` → repete →
      executa); janela expirada; `change-password` mata o step-up. Perfis de
      teste OPERATOR e ADMIN com step-up próprio.
- [x] T4.5: **Critério 15 (default do Operator Bot)** — nota no design/roadmap
      + verificação: o bot (Spec 67) só executa ações NÃO sensíveis (leituras +
      suspend/block/release); as 4 sensíveis ficam fora do Discord (D8.2).
      Auditoria confirmada: suspend/release já geram `RISK_ADJUSTMENT` — sem
      pendência. Verificado: o bot só usa `GET /api/v1/admin/lookup/ip/{ip}`
      (leitura) + WAF do Cloudflare — nenhuma das 4 sensíveis.
- [x] T4.6: Sync final roadmap/requirements/task + `/spec-verify`.

## Verificação (`/spec-verify` 2026-08-11 — revisão do modelo)

**Mudança de modelo aplicada (decisão do usuário):** o RBAC granular (4 papéis +
tabela `master_permissions`) foi substituído por **autorização em 2 níveis**:

- **OPERATOR** (default, `can_manage_masters=false`): bloco operacional completo
  (OPERATOR+FINANCE+AUDITOR **juntos**) — as 7 permissões de ação;
- **ADMIN** (`can_manage_masters=true`): bloco completo + `master.profiles.manage`.

Consequências verificadas contra o código:

1. **Sem tabela nova** — a concessão é derivada de `can_manage_masters` (flag
   existente em `masters`, `Master.java`); a migration da spec fica só com
   `stepped_up_at` em `master_sessions` (V47, aditiva única).
2. **Sem mudança de payload** — `POST/GET /api/v1/master/profiles` permanecem
   como hoje (`MasterProfileRequest` = `{username, name}`; novos perfis nascem
   `canManageMasters=false` via `Master.create`).
3. **Masters existentes preservam acesso** — não-admin continuam com todas as
   escritas operacionais (ROLE_MASTER hoje); a única mudança comportamental é o
   **step-up** nas ações de FINANCE (mantido — decisão do usuário).
4. **Step-up mantido** para `withdrawal.administrative`, `receivable.write_off`,
   `invoice.collect` e `master.profiles.manage` (403 `step_up_required`, janela
   15 min) — mesmo com a permissão ampla.
5. **Catálogo `MasterPermission` permanece como contrato** (guard tier-based,
   `/me` da Spec 71, config de step-up).

Ajustes correlatos: Spec 71 (requirements critério 1/12 + design D2.1/D5.4),
V49/V51 e roadmap (Fase 23 linha 69 + Fase 24 princípios) atualizados para o
modelo de 2 níveis.

## Auditoria (pós-implementação)

- [x] Verificar os **15 critérios** do requirements.md um a um (padrão
      `/spec-audit`).

**`/spec-audit` (2026-08-11): 15/15 critérios OK — 0 achados de código.**
Verificações realizadas: (1) catálogo `MasterPermission` 8/8 (testes);
(2) níveis derivados OPERATOR/ADMIN sem campo novo; (3) `MasterPermissionGuard`
nos 8 services de escrita na mesma transação (403 `forbidden`); (4) deny-by-default
na gestão de perfis (OPERATOR → 403); (5) papéis derivados + seed ADMIN;
(6) `stepped_up_at` na V47 + `MasterSession.isSteppedUp`; (7) endpoint
`POST /master/mfa/step-up` reusa `verifySecondFactor` + `MASTER_STEP_UP`;
(8) 4 sensíveis configuráveis (`foxcash.security.master.step-up.required-permissions`);
(9) `change-password` limpa `stepped_up_at` via `revokeAllByMasterId`;
(10) contratos existentes intactos; (11) `VARIABLE_PREFIXES` + `/api/v1/master` +
`/api/v1/admin` (testes de cardinalidade); (12) step-up em `LIMITED_PATHS`
(429 testado); (13) sem migration de dados (V47 aditiva única); (14) E2E
`stepup.spec.js` 5/5 (S1 403 forbidden, S2 403 step_up_required, S3 fluxo
completo, S4 janela expirada, S5 change-password) + journeys 13/14/18/22 com
seed de step-up; (15) Operator Bot só usa `GET /api/v1/admin/lookup/ip/{ip}`
(leitura) — nenhuma das 4 sensíveis via Discord. `mvn test` **1303/0/0**
(re-executado) + E2E **110/110** (re-executado, app dev com
`NYXCASH_SECURITY_RATE_LIMIT_ENABLED=false` + `NYXCASH_PORTAL_SERVE_FRONTEND=true`
— padrão do CI; a suíte inclui o `portal-ui.spec.js` da Spec 46, que exige o
flag de frontend). Ajuste de doc nesta auditoria: design D3.2 corrigido para os
nomes reais das classes (`ReceivableWriteOffService`,
`MerchantStatusControlService` via `MasterRiskController`). Fim do ciclo SDD
(roadmap/requirements/design/task `audited`).
