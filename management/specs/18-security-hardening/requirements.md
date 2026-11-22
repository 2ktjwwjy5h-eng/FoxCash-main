---
name: Hardening de segurança — autorização por recurso, rate limiting, validação de documento e robustez da API
status: audited
references:
  - "management/vision.md#V15"
  - "management/adr.md#ADR-009"
  - "management/specs/12-api-authentication/requirements.md"
  - "docs/politicas-uso.md"
---

# Spec 18 — Hardening de segurança

## O que entrega

Fecha as vulnerabilidades e lacunas de robustez identificadas na auditoria de
segurança (teste como vendedor + revisão de especialista):

1. **Autorização por recurso (correção de IDOR):** todo endpoint de lojista
   valida que o `merchantId` (path/query/body) pertence ao principal autenticado
   (`ROLE_MERCHANT`). Lojista acessando recurso de outro → **403 `forbidden`**.
   Aplica-se a: `GET /merchants/{id}`, `POST /transactions`,
   `GET /transactions/{id}`, `GET /transactions/merchant/{id}`,
   `POST /nyx/pix/cob`, `GET /billing/preview`,
   `POST/GET /merchants/{id}/invoices`.
2. **Rate limiting:** `POST /api/v1/merchants` (público) e
   `POST /api/v1/auth/master/login` limitados por IP (janela fixa em memória,
   configurável). Excedeu → **429 `too_many_requests`**. Em memória (single
   instance); limitação distribuída (Redis) fica documentada para prod multi-instância.
3. **Validação de CPF/CNPJ:** cadastro rejeita documento inválido (dígitos
   verificadores) → **400 `invalid_argument`**. Base do KYC (políticas §3.1).
4. **Robustez da API:** parâmetros ausentes/malformados e corpo ilegível →
   **400** (antes: 500).
5. **Webhook idempotente:** replay de evento já processado (ex.: `PAID` 2x) →
   **2xx silencioso** (antes: 409).
6. **Quick wins:** limite de `size` na paginação admin (máx. 100); mensagem de
   409 de documento duplicado **sem ecoar o documento**; header
   `X-Request-Id` em toda resposta.
7. **Scan de dependências:** Dependabot + OWASP dependency-check no CI
   (detecção contínua de CVEs). Upgrade do Spring Boot fica como follow-up
   documentado (risco de regressão exige sessão dedicada).

## Critérios de aceite

1. Lojista B (chave B) em recurso do lojista A → **403** em leitura (merchant,
   transação, fatura, preview) e em escrita (criação com `merchantId` de A).
   Lojista A nos próprios recursos → 200/201 inalterado. Admin/master seguem
   intactos.
2. `POST /api/v1/merchants` e `POST /api/v1/auth/master/login`: após N
   requisições (default 20/min) da mesma origem → **429 `too_many_requests`**;
   janela configurável (`foxcash.security.rate-limit.*`); demais rotas sem limite.
3. Cadastro com CPF/CNPJ de dígito verificador inválido → **400
   `invalid_argument`**; documento válido → 201.
4. `GET /billing/preview` sem `month`, `month=abc`, corpo ilegível → **400**
   (nunca 500).
5. Webhook `PAID` repetido na mesma transação → **204** nas duas chamadas
   (idempotente); transação permanece `APPROVED`.
6. `GET /api/v1/admin/merchants?size=10000` → devolve no máximo 100 itens;
   resposta inclui `X-Request-Id`; 409 de duplicado sem documento no body.
7. `.github/dependabot.yml` + workflow OWASP dependency-check presentes no CI.
8. `mvn test` verde (**+~15 testes novos**: guard, rate limit, validação de
   documento, handlers 400) e E2E **6/6** (novo cenário: autorização A×B +
   documento inválido + webhook replay).

## Dependências

- Spec 12 (filtro API key; principal já carrega o `merchantId`).
- ADR-009 (decisão: 403 para posse; rate limit em memória).
- Políticas de Uso §3.1 (KYC — validação de documento).

## Observações

- **403 vs 404:** optou-se por **403** explícito (recurso existe, mas sem
  permissão) — UUIDs não são enumeráveis e 403 facilita diagnóstico. Alternativa
  404 (anti-enumeração) documentada como possível evolução.
- **Rate limit em memória** (`ConcurrentHashMap`) é suficiente para 1 instância
  (dev/VPS único); prod multi-instância deve usar store distribuído (Redis) —
  fora de escopo.
- **Upgrade Spring Boot 3.3.5 → 3.5.x** e **Idempotency-Key** ficam como
  follow-up documentado no `task.md` (não bloqueiam o hardening).
