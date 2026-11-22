---
name: Segurança de runtime — senha logada, actuator, headers, secrets
status: audited
references:
  - "management/roadmap.md#Fase 41"
  - "management/specs/18-security-hardening/requirements.md"
  - "management/specs/130-upgrade-seguranca-deps/requirements.md"
---

# Spec 131 — Segurança de runtime: superfície mínima, sem vazamento

## O que entrega

Fecha os achados de runtime do due diligence (2026-08-16):

1. **Senha Spring Security logada no boot** — o log de produção mostra
   `Using generated security password: <uuid>` (2 ocorrências no deploy
   `94861317`, uma por réplica). Isso indica que a configuração de segurança do
   Spring Boot (actuator/console) está caindo no fallback padrão — superfície
   não mapeada que um pentester encontraria.
2. **Actuator exposto** — `/actuator/prometheus`, `/actuator/health` etc.
   precisam de proteção explícita (quem pode ver o quê).
3. **Headers de segurança HTTP** — verificar/garantir `X-Content-Type-Options`,
   `X-Frame-Options`, `Strict-Transport-Security`, `Content-Security-Policy`.
4. **Secrets** — garantir que nenhum secret de produção vaza em logs/env dumps
   (máscara já existe em `SensitiveDataLoggingFilter` — verificar cobertura).

## Critérios de aceite

**C1 — Sem senha gerada no boot.** Nenhum log de produção contém
`Using generated security password`. A superfície de segurança do Spring
(Basic auth, actuator) é **desabilitada ou protegida com credenciais reais**
via configuração, não via fallback.

**C2 — Actuator com exposição mínima.** `/actuator/health` (liveness/readiness)
público e OK; `/actuator/prometheus` protegido (ROLE_MASTER ou rede interna);
outros endpoints do actuator desabilitados ou autenticados. Verificação: scan
de rotas de actuator não autorizadas → 401/403 (nunca 200 sem auth).

**C3 — Headers de segurança presentes.** Resposta HTTP inclui:
`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY` (ou CSP
frame-ancestors), `Strict-Transport-Security` (HTTPS), `Cache-Control` para
respostas sensíveis. Verificação: curl dos headers nas rotas principais.

**C4 — Zero secrets em logs.** Scan dos logs de produção (janela 24h) sem
ocorrências de padrões de secret (API key, token, password em claro) fora das
máscaras esperadas (`***`). O `SensitiveDataLoggingFilter` cobre as rotas
transacionais; rotas novas seguem o mesmo padrão (teste de regressão).

**C5 — Testes.** Testes automatizados para: actuator não-autorizado → 403/401;
headers presentes nas rotas principais; nenhum log de boot com senha gerada
(assert em teste de contexto). `mvn test` verde + E2E sem regressão.

## Dependências

- **Spec 130** — o upgrade do Boot pode mudar defaults de segurança (rodar esta
  spec APÓS ou em paralelo com a 130).
- **Spec 18** (security hardening) — base existente de camadas de segurança.

## Fora de escopo

- Upgrade de dependências — Spec 130.
- WAF/edge (Cloudflare) — já coberto por Spec 66/deploy/edge.md.
