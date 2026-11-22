---
name: Adapter real Pagar.me — cobrança Pix via API v5 (REST, auth Basic, contrato verificado)
status: audited
references:
  - "management/vision.md#V9"
  - "management/adr.md#ADR-005"
  - "management/specs/10-psp-integration-foundation/requirements.md"
---

# Spec 11 — Adapter real Pagar.me (cobrança Pix)

## O que entrega

Implementa o **primeiro adapter HTTP real** do provedor Pix atrás do port
`PixProviderClient` (ADR-001): o **Pagar.me** (candidato primário da ADR-005),
via **API v5** (`https://api.pagar.me/core/v5`). O contrato foi **verificado no
SDK oficial arquivado** `pagarme/pagarme-core-api-java` (APIMATIC, v5.7.5) —
que documenta `CreateChargeRequest`/`GetChargeResponse`/`GetPixTransactionResponse`
e a autenticação HTTP Basic com a secret key.

Escopo:

1. **Adapter `PagarMePixProviderClient`** (`infrastructure/outbound/pix/`):
   implementa `PixProviderClient.createCharge(...)` fazendo
   `POST {base-url}/charges` com `Authorization: Basic base64(secretKey:)`,
   payload `CreateChargeRequest` (code = transactionId, amount em centavos,
   `payment.payment_method="pix"`, `payment.pix.expires_in`) e mapeando a
   resposta `GetChargeResponse.last_transaction` (`qr_code` → pixCopyPaste,
   `qr_code_url` → pixQrCode, `id`/`code` → externalReference).
2. **Configuração**: bloco `foxcash.psp.pagarme.*` — `secret-key`
   (`NYX_PSP_PAGARME_SECRET_KEY`, obrigatória), `base-url` (default
   `https://api.pagar.me/core/v5`) e `expires-in-seconds` (default 3600).
   Seleção via `@ConditionalOnProperty(provider=pagarme)` — o mock continua
   default em dev; em prod, `NYX_PSP_PROVIDER=pagarme` ativa o adapter real.
3. **Testes com MockWebServer** (okhttp3): reproduzem o contrato real da API v5
   (200 com QR Code/URL, 4xx de erro) **sem credenciais reais**. Incluem
   verificação do header `Authorization` e do corpo da requisição.
4. **Homologação real documentada**: credenciais de sandbox + validação
   ponta-a-ponta continuam no runbook da Spec 10
   (`deploy/psp-homologation.md`) — o adapter real é o passo 3 do runbook.

## Critérios de aceite

1. `PagarMePixProviderClient` implementa `PixProviderClient` e é registrado
   apenas quando `foxcash.psp.provider=pagarme` (via `@ConditionalOnProperty`);
   com `mock` (dev default) o adapter real **não** é criado.
2. O adapter monta `POST {base-url}/charges` com header
   `Authorization: Basic base64(secretKey:)` e corpo `CreateChargeRequest`
   correto: `code` = transactionId (UUID), `amount` = centavos, `payment_method`
   = `"pix"`, `pix.expires_in` = valor configurado.
3. O adapter mapeia a resposta v5: `last_transaction.qr_code` →
   `pixCopyPaste`, `last_transaction.qr_code_url` → `pixQrCode`, e
   `id` → `externalReference`.
4. Erro HTTP (4xx/5xx) da API → exceção clara de integração (sem NPE, sem
   mapear resposta inválida); timeout/erro de rede → exceção com contexto.
5. `application.yml` (dev) e `application-prod.yml` (prod) definem o bloco
   `foxcash.psp.pagarme.*`: prod exige `NYX_PSP_PAGARME_SECRET_KEY` sem default
   (fail-fast); dev tem default fake para testes locais.
6. `mvn test` verde (39 testes: 35 + 4 novos do adapter Pagar.me) e E2E 5/5
   sem regressão (o E2E continua no provider `mock`).
7. `deploy/psp-homologation.md` é atualizado: passo "Implementar o adapter"
   marcado como feito (Spec 11) e credenciais/validação real explicitadas como
   pendentes (requer conta sandbox).

## Dependências

- Spec 10 (seleção por config `@ConditionalOnProperty`, filtro de assinatura
  de webhook, runbook de homologação).
- ADR-005 (Pagar.me como candidato primário).
- ADR-001 (port `PixProviderClient` isola o provedor — o domínio não muda).

## Observações

- **Contrato verificado:** o SDK oficial `pagarme/pagarme-core-api-java`
  (arquivado, APIMATIC v5.7.5) confirma base URL, auth Basic, `POST /charges` e
  os campos `CreateChargeRequest`/`GetPixTransactionResponse` usados. Não há
  SDK Java mantido para a v5 — o padrão é REST direto (como este adapter).
- **Sem credenciais:** os testes usam MockWebServer com o contrato da API; a
  validação com credenciais reais de sandbox fica para o runbook (passo de
  homologação) — não é pré-requisito desta spec.
- **Fluxo do webhook:** o evento `charge.paid` do Pagar.me (status `paid`,
  assinatura sha256) já é coberto pela Spec 10 (filtro + mapeamento
  `PAID → APPROVED`); o adapter desta spec é a **cobrança** (saída). Se o
  payload do webhook v5 exigir tradução de campos, isso fica numa spec futura
  (observação no runbook).
- **Valores monetários:** a API v5 usa `amount` em **centavos** (int); o domínio
  usa `Money` (BigDecimal reais) — o adapter converte (×100).
- Escopo **excluído** desta spec: refund/split no Pagar.me, tradução do payload
  do webhook v5, retry/outbox de webhooks, outros PSPs (Asaas e demais).
