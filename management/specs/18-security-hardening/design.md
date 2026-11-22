# Spec 18 — Design (Hardening de segurança)

## 1. Autorização por recurso (IDOR) — `MerchantAccessGuard`

**Problema:** o principal (`ROLE_MERCHANT`) carrega o `merchantId` (Spec 12) mas
nenhum endpoint valida posse.

**Decisão:** guard centralizado em `infrastructure/security/MerchantAccessGuard`
(`@Component`):

```java
void requireMerchantAccess(UUID resourceMerchantId) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    Object principal = auth == null ? null : auth.getPrincipal();
    if (principal instanceof UUID uuid && uuid.equals(resourceMerchantId)) return;
    throw new MerchantAccessDeniedException();
}
```

- Só `ROLE_MERCHANT` passa (principal = UUID do lojista). Admin (principal
  string) e master (UUID diferente) → negado nos endpoints de lojista
  (possuem rotas próprias: `/admin/**`, `/master/**`).
- Exceção `MerchantAccessDeniedException` (domínio) → handler 403 `forbidden`
  "Acesso negado a recurso de outro lojista".
- Chamada no início de cada controller de lojista (path/query/body
  `merchantId`), antes de qualquer operação.

**Arquivos:** `domain/exception/MerchantAccessDeniedException.java`,
`infrastructure/security/MerchantAccessGuard.java`, controllers
(Merchant/Transaction/PixCharge/Billing/Invoice), `GlobalExceptionHandler`.

## 2. Rate limiting — `RateLimitFilter`

**Decisão:** janela fixa em memória por IP + rota (`ConcurrentHashMap<String,
Window>`), aplicada só às rotas públicas de abuso (`POST /api/v1/merchants`,
`POST /api/v1/auth/master/login`).

- Config `foxcash.security.rate-limit.*`: `enabled` (true), `max-requests`
  (20), `window-seconds` (60). Ignora requisições sem header para IP
  (fallback `unknown`).
- Excedeu → 429 `too_many_requests` (JSON `ErrorResponse`) direto no filtro.
- Filtro `@Component` injetado no `SecurityConfig` (antes dos filtros de auth).

**Arquivos:** `infrastructure/security/RateLimitFilter.java`,
`RateLimitProperties` (ou dentro de SecurityProperties — novo),
`application.yml`/`application-prod.yml`.

## 3. Validação CPF/CNPJ — `DocumentValidator`

`domain/service/DocumentValidator` (estático puro): valida tamanho + dígitos
verificadores de CPF (11) e CNPJ (14). Chamado no `RegisterMerchantService`
antes do pré-check de duplicidade → `InvalidDocumentException` → 400
`invalid_argument` "Documento inválido".

**Arquivos:** `domain/service/DocumentValidator.java`,
`domain/exception/InvalidDocumentException.java`, `RegisterMerchantService`,
`GlobalExceptionHandler`.

## 4. Handlers 400 no `GlobalExceptionHandler`

Adicionar handlers (→ 400 `invalid_argument`, mensagem amigável):
`MissingServletRequestParameterException`, `MethodArgumentTypeMismatchException`,
`HttpMessageNotReadableException`, `ConstraintViolationException`.

## 5. Webhook idempotente — `ProcessPixWebhookService`

No `process()`: após mapear o status, **se a transação já está no estado
alvo, retornar sem operação** (replay tolerado). Ex.: transação `APPROVED` +
webhook `PAID` → no-op (não lança mais `IllegalStateException`). Transições
válidas seguem a máquina de estados do domínio.

## 6. Quick wins

- **AdminController:** `size = Math.min(size, 100)` (default 20).
- **MerchantAlreadyExistsException:** mensagem sem o documento ("Já existe um
  lojista com este documento").
- **RequestIdFilter:** `X-Request-Id` — usa o header se presente, senão gera
  UUID; define no response (e MDC para logs).

## 7. CI — Dependabot + OWASP

- `.github/dependabot.yml`: updates semanais (maven + npm).
- `.github/workflows/dependency-check.yml`: agendado semanal + manual
  (`workflow_dispatch`), roda `mvn org.owasp:dependency-check-maven:check
  -DfailBuildOnCVSS=7` (falha em CVE >= 7.0).

## Fora de escopo (follow-up)

- Upgrade Spring Boot 3.3.5 → 3.5.x (sessão dedicada).
- `Idempotency-Key` nas criações (migration + tabela).
- Rate limit distribuído (Redis) para multi-instância.
