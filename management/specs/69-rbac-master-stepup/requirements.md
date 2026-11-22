---
name: Autorização master em 2 níveis (ADMIN/OPERATOR) + step-up MFA em ações sensíveis
status: audited
references:
  - "management/vision.md#V49"
  - "management/specs/13-master-auth/requirements.md"
  - "management/specs/37-master-audit/requirements.md"
  - "management/specs/41-master-profiles-activity/requirements.md"
  - "management/specs/44-mfa-totp-master/requirements.md"
  - "management/specs/58-rate-limit-autenticado/requirements.md"
  - "management/specs/67-operator-bot/requirements.md"
  - "docs/revisao-seguranca-2026-08-11.md"
---

# Spec 69 — Autorização do master em 2 níveis + step-up MFA em ações sensíveis

## O que entrega

Fecha o gap de autorização administrativa identificado na discussão de
arquitetura do frontend (2026-08-11) e confirmado contra o código: hoje a
autorização master é **binária** — qualquer master ativo (`ROLE_MASTER`,
SecurityConfig) pode executar **todas** as escritas administrativas (suspender
lojista, bloquear saques, **saque administrativo**, **write-off de receivable**,
**cobrança manual de fatura**), e apenas a gestão de perfis master tem guard
extra (`MasterAdminGuard` + `can_manage_masters`). Não há distinção entre níveis
de operador e nenhuma ação sensível exige re-prova de posse (step-up MFA) no
momento da execução.

Esta spec entrega **duas camadas de autorização** que completam a cadeia
`authenticated → ROLE_MASTER → permissão da ação → step-up (se sensível) →
executa → audit log`:

1. **Autorização em 2 níveis** — catálogo de permissões por ação como
   contrato (fonte única); concessão **por nível**: todo master operacional
   (`can_manage_masters=false`) nasce com o bloco completo
   (OPERATOR+FINANCE+AUDITOR **juntos** — decisão do usuário 2026-08-11), e o
   ADMIN (`can_manage_masters=true`) soma a gestão de perfis master. Guard por
   permissão nos **services** (protege HTTP + Operator Bot + futuro Master
   Console na mesma transação); deny-by-default apenas na função admin.
2. **Step-up MFA em ações sensíveis** — as 4 ações de alto impacto
   (saque administrativo, write-off, cobrança manual, gestão de perfis master)
   exigem re-autenticação TOTP válida numa janela curta (15 min) por sessão,
   reutilizando o `MasterMfaService.verifySecondFactor` existente (anti-replay,
   recovery code single-use, lockout de 5 falhas). A API expressa a exigência
   com `403 { error: { code: "step_up_required" } }` — **não** 401 (o 401
   dispararia logout no cliente).

O **contrato HTTP** das rotas existentes **não muda** (a autorização por nível
e o step-up são camadas de autorização) — o `master-client` gerado do OpenAPI
(Spec futura de contrato) não é bloqueado por esta spec. **Nenhuma mudança de
payload**: o `POST /api/v1/master/profiles` permanece `{username, name}`
(novos perfis nascem OPERATOR) e o `GET /profiles` permanece como hoje.

## Contexto (por que agora)

A revisão de segurança 2026-08-11 (29 achados em 8 rodadas, docs/
revisao-seguranca-2026-08-11.md) fechou vetores de **integridade e
autenticação**, mas **não** endereçou autorização administrativa granular:
SEC-19 corrigiu lost update no save de merchant (bloqueio por lock), não quem
pode bloquear. Na discussão de evolução do frontend, o usuário e o irmão
consolidaram o princípio: "o frontend nunca decide se o master pode executar uma
operação — o backend verifica `autenticado → é master? → permissão da ação →
escopo do recurso → step-up → executa → audit log`". A identidade/MFA/auditoria
do master **já existem e estão auditadas** (Specs 13/44/37/41 + SEC-01/04/15/20/25);
o que falta é exatamente a camada do meio: **permissão da ação** (RBAC) e
**step-up** — que é o escopo desta spec.

## Critérios de aceite

1. **Catálogo de permissões (fonte única)**: enum `MasterPermission` com as 8
   permissões (namespace por domínio, cola com `MasterAuditAction`):
   `merchant.suspend`, `merchant.release`, `withdrawal.block`,
   `withdrawal.release`, `withdrawal.administrative`, `receivable.write_off`,
   `invoice.collect`, `master.profiles.manage`. **Sem tabela nova** — a
   concessão é por nível (critério 2); o catálogo é o contrato (guard, `/me`,
   config de step-up).
2. **Dois níveis (decisão do usuário 2026-08-11)**: nível derivado de
   `can_manage_masters` (flag existente) — `false` (default) → **OPERATOR**
   com o bloco completo `merchant.suspend/release`, `withdrawal.block/release`,
   `withdrawal.administrative`, `receivable.write_off`, `invoice.collect`
   (OPERATOR+FINANCE+AUDITOR **juntos**); `true` → **ADMIN** com o bloco
   completo **+** `master.profiles.manage`. **Sem migration de permissões**: os
   masters existentes preservam exatamente o acesso operacional de hoje — a
   única mudança comportamental é o step-up nas ações de FINANCE (critério 8).
3. **Guard por permissão nos services (tier-based)**: novo
   `MasterPermissionGuard.requirePermission(UUID actorMasterId, String
   permission)` — resolve o **nível** do master (`can_manage_masters`):
   permissões operacionais do catálogo → todo master **ativo** tem (o guard
   valida `isActive`); `master.profiles.manage` → só `can_manage_masters=true`.
   Sem a condição → `MasterPermissionDeniedException` → `403 { code:
   "forbidden" }`. Todas as rotas de **escrita** de `/api/v1/master/**` chamam
   o guard correspondente **na mesma transação** (protege HTTP + Operator Bot +
   console). Rotas de leitura permanecem `ROLE_MASTER`.
4. **Deny-by-default na função admin**: a gestão de perfis master é a única
   escrita negada por padrão — master operacional (sem `can_manage_masters`)
   recebe **403 `forbidden`** em `POST /profiles` + `/{id}/activate|deactivate`
   e em qualquer leitura de perfis. As escritas operacionais são **concedidas
   por nível** (todo master ativo as tem — decisão do usuário).
5. **Papéis exibidos (ADMIN|OPERATOR)**: `role` é **derivado** de
   `can_manage_masters` (ADMIN ↔ true, OPERATOR ↔ false) — sem campo novo, sem
   atribuição. Seed cria o primeiro master como ADMIN (flag `true`, mesmo
   comportamento atual). `POST /api/v1/master/profiles` **permanece**
   `{username, name}` (novo perfil nasce OPERATOR); `GET /profiles` permanece
   como hoje. Sem `MASTER_PERMISSIONS_CHANGED` (não há concessão granular a
   auditar).
6. **Step-up: modelo de sessão**: coluna `stepped_up_at` (nullable) em
   `master_sessions`. Ação sensível exige `stepped_up_at != null` dentro de
   janela configurável (default **15 min**); fora da janela ou ausente → `403 {
   error: { code: "step_up_required", message: "..." } }`.
7. **Step-up: endpoint e verificação**: novo `POST
   /api/v1/master/mfa/step-up` (body `{ code }`, exige ROLE_MASTER) valida via
   `MasterMfaService.verifySecondFactor` (TOTP anti-replay `lastCodeHash` +
   recovery code `mrc_` single-use + lockout 5 falhas), grava `stepped_up_at`
   na sessão e responde 200. Falha → 401/403 padrão do MFA (sem gravar
   step-up). O step-up em si gera entrada de auditoria (`MASTER_STEP_UP`).
8. **Ações sensíveis**: exatamente `withdrawal.administrative`,
   `receivable.write_off`, `invoice.collect` e `master.profiles.manage`
   exigem step-up. `merchant.suspend/release` e `withdrawal.block/release`
   **não** exigem (reversíveis — suporte ágil). A lista de sensíveis é
   configurável (`foxcash.security.master.step-up.required-permissions`,
   default = as 4 acima).
9. **`change-password` continua revogando tudo**: `revokeAllByMasterId`
   (SEC-25) também limpa `stepped_up_at` (sessões revogadas não carregam
   step-up) — troca de senha mata tokens roubados **e** janelas de step-up
   abertas.
10. **Sem quebra de contrato**: nenhuma rota existente de `/api/v1/master/**`
    muda de shape. Respostas novas apenas quando a ação **não** executa
    (403 `forbidden` / `step_up_required`). `mvn -o clean test` permanece verde
    (testes novos sem regressão); E2E 105/105 mantido (fluxos do Operator Bot
    com permissões/step-up dos perfis de teste).
11. **Rate limit por cardinalidade nas rotas master (fecha o SEC-23)**: adicionar
    `/api/v1/master` e `/api/v1/admin` ao `RateLimitKey.VARIABLE_PREFIXES` —
    o `AuthenticatedRateLimitFilter` (limite por principal, Spec 58) passa a
    colapsar `POST /master/merchants/{id}/...`, `/master/receivables/{id}/...`,
    `/master/profiles/{id}/...` num bucket por rota (N lojistas/recursos →
    **1 bucket**). Hoje o bypass de cardinalidade existe: N recursos = N × teto
    por principal (mesma classe do SEC-23, que cobriu as rotas do lojista e
    deixou as do master fora).
12. **Rate limit do `/api/v1/master/mfa/step-up`**: o endpoint entra em
    `RateLimitFilter.LIMITED_PATHS` (bucket por IP, padrão das rotas de login) e
    é coberto pelo limite por principal — protege a superfície de verificação de
    código antes do lockout do `verifySecondFactor` (5 falhas).
13. **Masters não-admin preservam acesso (decisão do usuário 2026-08-11)**:
    o modelo de 2 níveis **não remove capacidade** — masters com
    `can_manage_masters=false` continuam com todo o bloco operacional (como
    hoje). A única mudança comportamental é o **step-up** nas 3 ações de
    FINANCE (saque administrativo, write-off, cobrança) e a negação da gestão
    de perfis (que já era negada). **Sem migration de dados**: nenhum `UPDATE`
    em `masters`, nenhuma tabela nova.
14. **E2E dos fluxos master**: a suíte ganha casos de integração —
    `403 forbidden` (master OPERATOR tentando gestão de perfis), `403
    step_up_required` (sem step-up em ação de FINANCE), fluxo completo
    (`step-up` → repete ação → executa), janela expirada → `step_up_required`
    de novo, e `change-password` matando o step-up. Perfis de teste OPERATOR e
    ADMIN com step-up próprio.
15. **Default do Operator Bot (Spec 67) como critério verificável**: o bot fica
    restrito a ações **não sensíveis** (leituras + suspend/block/release) e
    nunca executa as 4 sensíveis via Discord — o guard no service protege
    qualquer cliente; o fluxo de step-up por chat é decisão de produto futura,
    fora desta spec.

## Fora de escopo

- **Master Console (UI)** — consome esta fundação quando existir (P2 do
  roadmap consolidado); a UI esconde ações sem permissão, o backend decide.
- **OpenAPI/contratos gerados** — o `master-client` vem de spec futura; esta
  spec não bloqueia e não depende dela.
- **Mudança do modelo de sessão** (cookie/HttpOnly) — fora; sessão Bearer opaca
  permanece.
- **RBAC de lojista** (portal) — o portal já tem escopo por principal
  (`findByIdAndMerchantId`); o escopo é o perfil master.
