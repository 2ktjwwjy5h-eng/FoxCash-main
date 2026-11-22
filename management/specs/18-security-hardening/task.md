# Spec 18 — Task breakdown (Hardening de segurança)

## P0 — Crítico
- [ ] `MerchantAccessDeniedException` (domínio) + handler 403 no `GlobalExceptionHandler`
- [ ] `MerchantAccessGuard` (`@Component`)
- [ ] Aplicar guard nos controllers: Merchant, Transaction (POST/GET/GET-by-merchant),
      PixCharge (cob), Billing (preview), Invoice (POST/GET)
- [ ] `RateLimitFilter` + `RateLimitProperties` + config yml (dev/prod) + registro no SecurityConfig
- [ ] Testes: `MerchantAccessGuardTest`, `RateLimitFilterTest`

## P1 — Alto
- [ ] `DocumentValidator` (CPF/CNPJ dígitos verificadores) + `InvalidDocumentException`
- [ ] Validar documento no `RegisterMerchantService` + handler 400
- [ ] Handlers 400: MissingServletRequestParameter / MethodArgumentTypeMismatch /
      HttpMessageNotReadable / ConstraintViolation
- [ ] Webhook idempotente no `ProcessPixWebhookService`
- [ ] Testes: `DocumentValidatorTest`, `GlobalExceptionHandlerTest`,
      idempotência de webhook (unit)
- [ ] Dependabot (.github/dependabot.yml) + workflow OWASP dependency-check

## P2 — Rápido
- [ ] Cap `size` na paginação admin (máx 100)
- [ ] `MerchantAlreadyExistsException` sem documento na mensagem
- [ ] `RequestIdFilter` (X-Request-Id)
- [ ] E2E: cenário autorização A×B + documento inválido + webhook replay

## Verificação
- [ ] `mvn test` verde (~73 testes)
- [ ] E2E 6/6
- [ ] Validação manual curl (403/429/400/204 replay)
- [ ] Docs: vision V15, ADR-009, roadmap auditado; commit + push

## Follow-up (fora de escopo)
- [ ] Upgrade Spring Boot 3.3.5 → 3.5.x (sessão dedicada + E2E completo)
- [ ] `Idempotency-Key` nas criações (migration + tabela)
- [ ] Rate limit distribuído (Redis) para prod multi-instância

## `/spec-audit 18` (2026-08-17) — AUDITADA (alinhamento da baseline)

Spec da **baseline de segurança** (autorização por recurso/IDOR, rate limiting,
validação CPF/CNPJ, robustez 400, webhook idempotente, scan de dependências).
Entregue e verificada no ciclo de 2026-08-05 (resumo "Specs 1–41: todas
`audited`") e continuamente exercitada pelas specs posteriores (18 é referência
de base nas Fases 2/10). Este registro alinha o status do roadmap/frontmatter
(`implemented` → `audited`) ao estado já declarado da baseline. Obs.: este
task.md é da era original (checkboxes de época não re-marcados — os follow-ups
foram entregues por specs posteriores: Redis rate limit = Spec 43, upgrade
Spring Boot = Spec 130). `mvn test` atual **2069/0/0** cobre o hardening
(autorização/rate limit/validação/webhook) na suíte.
