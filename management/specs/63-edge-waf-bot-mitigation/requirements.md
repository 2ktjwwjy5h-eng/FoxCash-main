---
name: Edge/WAF e bot mitigation — Cloudflare na frente do Railway
status: audited
references:
  - "management/vision.md#V43"
  - "management/adr.md#ADR-014"
  - "management/specs/18-security-hardening/requirements.md"
  - "management/specs/46-portal-frontend/requirements.md"
  - "management/specs/58-rate-limit-autenticado/requirements.md"
  - "management/specs/61-horizontal-scalability-ha/requirements.md"
---

# Spec 63 — Edge/WAF e bot mitigation (Cloudflare na frente do Railway)

## O que entrega

O backend hoje está atrás do edge do Railway (TLS gerenciado, DDoS básico,
healthcheck) — **sem WAF, sem bot mitigation, sem rate limit na borda**: floods e
bots chegam inteiros no app, e o único controle é o rate limit da app (Specs
43/58/59 — distribuído via Redis, mas ainda assim custa CPU/DB/threads por
request processado). Esta spec coloca uma **edge de segurança de nível de
mercado** na frente, com o mínimo de mudança no app:

1. **Cloudflare como proxy DNS** na frente do domínio público da API (ex.:
   `api.foxcash.example.com` → CNAME para o domínio do Railway, proxy ON). O edge do
   Railway permanece como origin (o app não muda de endereço interno).
2. **DDoS protection** gerenciado (proteção L3/L4/L7 incluída no plano
   gratuito) — absorve floods volumétricos antes do app.
3. **WAF** com ruleset gerenciado do Cloudflare + regras customizadas para os
   endpoints sensíveis (`POST /api/v1/merchants`, `/merchant-auth/*`,
   `/webhooks/*`): bloqueio de payloads de ataque (SQLi/XSS/traversal) e de
   padrões de abuso.
4. **Rate limiting na borda** (regras por IP/rota no Cloudflare) — corta o
   flood **antes** de consumir recursos do app; o rate limit distribuído da app
   (Specs 43/58/59) permanece como controle final (defesa em profundidade).
5. **Bot mitigation**: **Cloudflare Turnstile** (gratuito, invisible/widget) nos
   formulários de **login e cadastro do portal** (Spec 46) com **validação
   server-side** (siteverify) nos endpoints de auth — automação de conta
   (criação em massa, brute-force de senha) deixa de ser viável via HTTP puro.
6. **TLS Full (strict)** — o Cloudflare valida o certificado do origin (domínio
   `up.railway.app` já tem cert válido do Railway); sem cert extra no app.
7. **`trust-proxy` validado** — o app já lê `X-Forwarded-For` com
   `NYX_TRUST_PROXY=true` (Spec 18); a spec valida que, com o Cloudflare no
   meio, o app continua vendo o **IP real do cliente** (não o do edge) no rate
   limit e nos logs (configurar `CF-Connecting-IP`/`True-Client-IP` se
   necessário).
8. **Verificação com ataques simulados** (flood, SQLi pattern, bot UA, força
   bruta no login) + **rollback documentado** (proxy OFF → DNS direto).

**Fora de escopo:** gestão de segredos profissional (Doppler/Vault — proposta
futura, roadmap §Fase 17), autenticação adaptativa/step-up (proposta futura),
mudança de contrato de API, e substituição do rate limit da app (o edge
**complementa**, não substitui).

## Critérios de aceite

1. **Domínio atrás do Cloudflare**: o domínio público da API resolve via
   Cloudflare com proxy ON (orange cloud); `curl -I https://api.foxcash.example.com` →
   `CF-Ray` no header e TLS válido (Full strict, sem erro de certificado).
2. **DDoS absorvido no edge**: flood volumétrico (ex.: 100 req/s de um IP por
   30s) → o Cloudflare absorve/rejeita e o app **não** processa o pico
   (verificado via métricas de requests/CPU do app — pico de requests do app
   ≈ 0 ou 429 do edge).
3. **WAF bloqueia payload de ataque**: request com SQLi pattern (ex.:
   `' OR 1=1 --` no body/query de `/merchant-auth/login`) → **403** (challenge/
   block do WAF) **sem chegar ao controller** (evidência: sem registro de
   `LOGIN_FAILED` no `platform_activity_log` para esse request).
4. **Edge rate limit**: N requests rápidos de um mesmo IP em rota sensível
   (`POST /api/v1/merchants`, `/merchant-auth/*`) acima do teto da regra →
   **429 na borda** (antes do app; evidência: sem processamento no app).
5. **Turnstile no portal**: login/cadastro via automação (curl, sem token
   Turnstile válido) → **400/403 `turnstile_failed`** (ou equivalente); via UI
   real com widget resolvido → **200/201**; Turnstile indisponível → **fail-open**
   (login legítimo não bloqueado, com WARN — disponibilidade primeiro, mesmo
   padrão da ADR-010).
6. **Webhook do PSP não quebra**: `POST /webhooks/pix|payouts` do IP/ASN do
   Pagar.me com assinatura válida → **204** (exceção de rate limit/WAF do
   webhook; reentrega do PSP não é bloqueada).
7. **IP real no app**: com Cloudflare no meio, o rate limit da app e os logs
   continuam vendo o **IP do cliente** (não `1.1.1.1`/IP do Cloudflare) —
   verificado por request de teste com header `CF-Connecting-IP` e leitura do
   log/IP resolvido.
8. **Contrato de API inalterado**: respostas 200/201/400/401/403/404/409/429/
   5xx mantêm o formato JSON `ErrorResponse` do projeto (o edge só adiciona
   cabeçalhos `cf-*`, não altera o body).
9. **Rollback testado**: com proxy OFF (DNS direto), o serviço volta a
   funcionar sem o Cloudflare (mesma URL pública, mesmo comportamento).
10. `mvn test` sem regressão + E2E sem regressão (mudanças de runtime/edge não
    alteram o contrato; o Turnstile tem fail-open para não quebrar o E2E
    existente — validar que os testes de auth do CI não exigem token).

## Filosofia

- **O edge reduz, o app controla**: Cloudflare/edge rate limit e WAF são a
  primeira camada (barata, absorve volume); o rate limit distribuído da app
  (Redis, Specs 43/58/59) é o controle final (autoritativo por chave/rota). O
  edge **nunca** substitui um controle de integridade/segurança da app.
- **Disponibilidade primeiro**: toda regra de bloqueio tem fail-open ou
  exceção documentada (Turnstile down não bloqueia login; webhook do PSP nunca
  é sacrificado por regra de rate limit). Mesma diretriz da ADR-010.
- **Baseline = plano gratuito**: DDoS, TLS, Bot Fight Mode e Turnstile são
  gratuitos; WAF ruleset completo e rate limiting em volume exigem plano Pro —
  a spec entrega o máximo no free e documenta o upgrade se a necessidade
  (evidência de ataque real) justificar. Regra de ouro: **não pagar por
  capacidade que a evidência não pede** (mesma diretriz da Spec 62).
- **Rollback sempre possível**: toda mudança de edge é config (DNS/proxy), não
  código — reversível em minutos.

## Dependências

- `management/adr.md` (ADR-014 — decisão do Cloudflare como edge)
- `management/specs/18-security-hardening/requirements.md` (trust-proxy/
  X-Forwarded-For — base do critério 7)
- `management/specs/46-portal-frontend/requirements.md` (SPA do portal — onde o
  Turnstile é embutido)
- `management/specs/58-rate-limit-autenticado/requirements.md` e
  `management/specs/59-webhook-rate-limit/requirements.md` (rate limit da app —
  camada final, base do critério 4)
- `management/specs/61-horizontal-scalability-ha/requirements.md` (edge Railway
  existente/origin; healthcheck)
- Domínio próprio do usuário (ex.: `api.foxcash.example.com`) — pré-requisito de DNS.
