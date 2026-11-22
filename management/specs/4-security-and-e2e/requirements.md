---
name: Segurança (cripto, HTTPS, logs) + prévia de fatura + testes E2E
status: audited
references:
  - "management/lessons.md#L1"
  - "management/specs/2-scaffold-verification/requirements.md"
  - "management/vision.md#V2"
---

# Spec 4 — Segurança (cripto, HTTPS, logs) + prévia de fatura + E2E

## O que entrega

Endurece o backend FoxCash com os requisitos obrigatórios de segurança e
adiciona a prévia de fatura e a suíte E2E:

1. **Criptografia em repouso (AES-256/GCM)** via `CryptoConverter` (JPA
   `AttributeConverter`) aplicado a `merchants.document` e
   `pix_transactions.payer_document`. Chave vinda de `NYX_CRYPTO_SECRET`.
   Busca por documento via hash determinístico (`document_hash`, SHA-256) —
   AES-GCM com IV aleatório é não-determinístico e não permite busca por
   igualdade no ciphertext.
2. **HTTPS obrigatório em produção**: `application-prod.yml` com keystore SSL e
   `SecurityConfig` (`@EnableWebSecurity`) que exige canal seguro
   (`requiresSecure()`) apenas quando `foxcash.security.require-https=true`.
3. **Proteção de logs**: `SensitiveDataLoggingFilter` que sanitiza CPF/CNPJ dos
   payloads de request/response antes de logar.
4. **Prévia de fatura**: `GET /api/v1/billing/preview?merchantId&month` com
   cálculo transparente (`totalTransactions * 0,50 + 69,95`).
5. **Suíte E2E Playwright** em `e2e-tests/` (Node.js) cobrindo: cadastro de
   lojista (com verificação de criptografia no banco), cobrança Pix, webhook de
   pagamento, prévia da fatura e geração da fatura.

## Critérios de aceite

1. `merchants.document` e `pix_transactions.payer_document` armazenam
   ciphertext (`v1:<base64>`) no banco; a aplicação lê/escreve em texto puro.
2. `NYX_CRYPTO_SECRET` é a única fonte da chave em produção; o default em
   `application.yml` é apenas para dev.
3. Com `foxcash.security.require-https=true`, requisições HTTP são bloqueadas
   (redirect 302 para HTTPS); com `false` (dev), HTTP funciona.
4. Logs não contêm CPF/CNPJ em texto puro.
5. `GET /api/v1/billing/preview` retorna `totalTransactions`, `variableFee`,
   `fixedFee`, `totalDue` e `calculation`.
6. `npm test` (em `e2e-tests/`) passa os 5 cenários contra
   `http://localhost:8080` com o banco PostgreSQL limpo (globalSetup).

## Dependências

- Spec 2 (build + testes verdes) — pré-requisito para `implemented`.
- Schema existente adaptado via migration `V2__security_and_pix_charge_fields.sql`.

## Observações

- Decisões de segurança registradas em `management/adr.md` (ADR-003 criptografia,
  ADR-004 busca por documento hash).
- Fora de escopo nesta rodada (próximas specs): contador Redis, Kafka,
  ShedLock/scheduler, notificação ao lojista, dashboard/admin.
