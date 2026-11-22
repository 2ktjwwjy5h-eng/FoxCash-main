---
name: Segurança de runtime — senha logada, actuator, headers, secrets
status: verified
---

# Spec 131 — Design: Segurança de runtime (superfície mínima)

## D1 — Conceito

O due diligence apontou 4 achados de runtime. Dois já têm base no código
(`SecurityConfig` cobre actuator e headers); o foco é **provar por teste** e
fechar o gap da **senha gerada no boot** (fallback do Spring Security).

## D2 — Senha gerada no boot (C1)

**Causa raiz:** o Spring Security gera `Using generated security password: <uuid>`
quando um `UserDetailsService`/`AuthenticationProvider` default é detectado e
`spring.security.user` não é definido. O app usa filters customizados
(`ApiKeyAuthFilter`/`MasterAuthFilter`/`MerchantPortalAuthFilter` no
`SecurityConfig`), mas o fallback de user default do Boot **permanece ativo**.

**Correção (escolha uma, com evidência):**
- (a) **Desabilitar o fallback**: `spring.autoconfigure.exclude` do
  `UserDetailsServiceAutoConfiguration` (recomendado — nenhum user default
  existe no app) **ou** definir `spring.security.user.name/password` como
  credencial real de deploy (menos limpo — credencial estática em runtime).
- Verificação: log de boot sem `Using generated security password` (C1) +
  teste de contexto que asserta a ausência no log capturado.

## D3 — Actuator com exposição mínima (C2)

**Já existe** (`SecurityConfig` linhas 140-146 + `application.yml` 59-64):
- `/actuator/health` (+ `/health/**`) público (liveness/readiness p/
  orquestrador); `show-details: when_authorized`.
- `/actuator/**` demais → `hasRole("ADMIN")`; exposição web limitada a
  `health,info,prometheus,metrics`.

**Fechar por teste**: scan de rotas do actuator não autorizadas
(`/actuator/env`, `/actuator/beans`, `/actuator/prometheus` sem auth) →
**401/403, nunca 200**. `prometheus` só com ROLE_ADMIN (confirmar no teste).

## D4 — Headers de segurança (C3)

**Já existe** (`SecurityConfig` linhas 204-229): `nosniff`, frame `DENY`, HSTS,
CSP (`default-src 'none'`; com SPA servida, CSP permite `'self'` + estilos
inline do Vite), `Referrer-Policy`. **Fechar por teste**: curl/`MockMvc` nas
rotas principais (API + portal) asserta a presença dos headers (C3).

## D5 — Zero secrets em logs (C4)

**Já existe**: `SensitiveDataLoggingFilter` (máscara `***` em campos
sensíveis + CPF/CNPJ). **Fechar por teste**: teste de regressão do filter
(entrada com API key/token/senha → saída mascarada) e varredura manual dos
logs de produção (janela 24h) sem padrões de secret fora das máscaras.

## D6 — Testes (C5)

- `SecurityRuntimeSecurityTest` (novo): actuator não-autorizado → 403/401;
  headers presentes nas rotas principais; boot log sem senha gerada
  (captura de log no teste de contexto).
- `SensitiveDataLoggingFilterTest`: máscara de secret (regressão).
- `mvn test` verde + E2E sem regressão.

## D7 — Arquivos

| Arquivo | Mudança |
|---|---|
| `src/main/java/.../security/SecurityConfig.java` | (se necessário) exclusão/desativação do fallback de user (D2) |
| `src/main/resources/application.yml` | `spring.autoconfigure.exclude` do `UserDetailsServiceAutoConfiguration` (se (a)) |
| `src/test/java/.../SecurityRuntimeSecurityTest.java` | novo (D6) |
| `src/test/java/.../SensitiveDataLoggingFilterTest.java` | novo (D6) |
| `deploy/railway.md` | documentar a decisão (D2) |

## D8 — Escopo

Não muda regras de autorização por rota (Spec 18/46 intactas); não mexe em
WAF/edge (fora de escopo); não altera a política de CORS da SPA (Spec 46).

**Âncoras validadas na spec:** `SecurityConfig.java` (filters customizados +
actuator ROLE_ADMIN + headers nosniff/frame/HSTS/CSP);
`SensitiveDataLoggingFilter.java` (máscara `***` + CPF/CNPJ);
`application.yml` (management.endpoints + health probes + redis/mail health
desabilitados); Spec 18 (base); Spec 46 (CSP da SPA).
