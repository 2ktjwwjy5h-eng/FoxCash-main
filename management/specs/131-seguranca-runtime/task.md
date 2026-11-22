---
name: Segurança de runtime — senha logada, actuator, headers, secrets
status: audited
---

# Spec 131 — Task: Segurança de runtime

Ordem: T0 (senha no boot) → T1 (actuator) → T2 (headers) → T3 (secrets) → T4
(testes/docs).

## T0 — Senha gerada no boot (critério C1)

- [x] T0.1: Investigar o fallback do Spring Security — **confirmado**: o auto-config
      `UserDetailsServiceAutoConfiguration` detecta o fallback default e loga
      `Using generated security password: <uuid>` (evidência: deploy `94861317`,
      2 ocorrências, uma por réplica).
- [x] T0.2: Corrigir — **exclusão do `UserDetailsServiceAutoConfiguration`** no
      `application.yml` (`spring.autoconfigure.exclude`). Decisão registrada em
      `deploy/railway.md` §2d: o app NÃO usa `UserDetailsService` (auth por
      filters customizados); não existe user default — definir
      `spring.security.user.*` criaria credencial que não existe no app.
      Commit `caace79`.
- [x] T0.3: Provar — `bootLogDoesNotContainGeneratedSecurityPassword` (no
      `SecurityRuntimeSecurityTest`): captura o log do boot via appender no ROOT
      e asserta a AUSÊNCIA de `Using generated security password`. Verde.

## T1 — Actuator com exposição mínima (critério C2)

- [x] T1.1: Revisar `SecurityConfig` (140-146) + `application.yml` (59-64) —
      confirmado: `/actuator/health` e `/actuator/health/**` públicos; demais
      `/actuator/**` → ROLE_ADMIN; exposição web limitada a
      `health,info,prometheus,metrics`.
- [x] T1.2: Novo teste `SecurityRuntimeSecurityTest` — `/actuator/env`,
      `/actuator/beans`, `/actuator/prometheus`, `/actuator/metrics` sem auth →
      401/403 (nunca 200); `/actuator/health` público → 200; `/actuator/prometheus`
      com ROLE_ADMIN → 200. **5/5 verde.**

## T2 — Headers de segurança (critério C3)

- [x] T2.1: Confirmar headers no `SecurityConfig` — nosniff, frame DENY, CSP
      (`default-src 'none'`; com SPA servida `'self'` + estilos inline do Vite),
      Referrer-Policy `no-referrer`, Permissions-Policy; HSTS condicional
      (`require-https=true`).
- [x] T2.2: Teste — `securityHeadersPresentOnMainRoutes`: assert dos headers em
      rota pública (`/actuator/health`) e autenticada (`/api/v1/admin/merchants`).
      Verde (dentro do 5/5).

## T3 — Zero secrets em logs (critério C4)

- [x] T3.1: Teste de regressão do `SensitiveDataLoggingFilter` — entrada com
      API key/token/senha/CPF/CNPJ → saída mascarada (`***`).
      `SensitiveDataLoggingFilterTest` **9/9 verde**.
- [ ] T3.2: Varredura dos logs de produção (janela 24h) — pendente até o deploy
      da Spec 131 subir (evidência para o /spec-audit).

## T4 — Testes e docs (critério C5)

- [x] T4.1: `mvn test` completo verde — EXIT 0 (2026-08-16, após fix b30ec9f).
- [x] T4.2: `deploy/railway.md` §2d — decisão do T0.2 documentada (por que a
      senha não aparece mais no boot) + superfície mínima do actuator/headers.

**Âncoras validadas na spec:** `SecurityConfig.java` (filters customizados,
actuator ROLE_ADMIN, headers); `SensitiveDataLoggingFilter.java` (máscara);
`application.yml` (management.endpoints); Spec 18/46 (base); Spec 130
(pré-requisito — defaults de segurança podem mudar no Boot 3.5).

## Auditoria (2026-08-17) — `/spec-audit 131`

**5/5 critérios OK, 0 achados de código, 0 abertos.**

- **C1 (sem senha gerada no boot)** — `spring.autoconfigure.exclude` contém
  `UserDetailsServiceAutoConfiguration` (application.yml L17, commit `caace79`);
  teste `bootLogDoesNotContainGeneratedSecurityPassword` captura o log do boot
  via appender e asserta a AUSÊNCIA de `Using generated security password` —
  **verde** (dentro do `SecurityRuntimeSecurityTest` 5/5 re-executado nesta
  auditoria). Decisão documentada em `deploy/railway.md` §2d (app não usa
  `UserDetailsService` — auth por filters customizados).
- **C2 (actuator mínimo)** — `SecurityConfig` L140-146: `/actuator/health` +
  `/**` público; demais `/actuator/**` → `hasRole("ADMIN")`; exposição web
  limitada a `health,info,prometheus,metrics` (yml L66-71). Testes
  `SecurityRuntimeSecurityTest` 5/5 (actuator não-autorizado → 401/403, nunca
  200; prometheus com ROLE_ADMIN → 200) — **re-executados verdes**.
- **C3 (headers)** — `SecurityConfig` L204-222: nosniff, frame DENY, CSP
  (default-src 'none'; 'self' + estilos inline do Vite quando SPA servida),
  Referrer-Policy `no-referrer`, Permissions-Policy; HSTS condicional
  (`require-https=true`). Teste `securityHeadersPresentOnMainRoutes` verde.
- **C4 (zero secrets em logs)** — `SensitiveDataLoggingFilterTest` **9/9
  re-executado verde** (API key/token/senha/CPF/CNPJ → `***`). Varredura dos
  logs de produção (T3.2, janela 24h) fica como evidência pós-deploy — o deploy
  `34145e3a` (rebrand, 2026-08-17) já roda o código com a exclusão + máscara em
  produção.
- **C5 (testes)** — `mvn test` **2050/0/0** (suíte completa) + `SecurityRuntimeSecurityTest`
  5/5 + `SensitiveDataLoggingFilterTest` 9/9 re-executados nesta auditoria; E2E
  sem regressão (rebrand 203/0/3).

**docs-consistency:** OK (check A/B/C verdes). Fim do ciclo SDD — Spec 131
`audited`, feature LIVE.
