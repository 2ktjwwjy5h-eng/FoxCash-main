# Homologação do PSP (Spec 10) — runbook

Este documento descreve o **contrato de integração** com um provedor Pix real
(Pagar.me primário / Asaas alternativa — ver ADR-005) e os passos de
homologação quando houver conta sandbox e credenciais disponíveis.

> **Estado atual:** a Spec 10 entrega a estrutura (seleção por config +
> verificação de assinatura do webhook), **sem** o adapter HTTP concreto. O
> adapter real (ex.: Pagar.me) será implementado em uma spec futura quando
> houver credenciais e acesso web.

---

## 1. Configuração (env vars)

O profile `prod` exige (fail-fast no boot):

| Variável | Obrigatória | Descrição |
|---|---|---|
| `FOXCASH_PSP_PROVIDER` | sim | `mock` (dev) / `pagarme` / `asaas` |
| `FOXCASH_PSP_WEBHOOK_SECRET` | sim (prod) | segredo HMAC-SHA256 da assinatura do webhook |
| `FOXCASH_SSL_KEYSTORE`, `FOXCASH_SSL_KEYSTORE_PASSWORD` | sim (prod) | TLS (já da Spec 9) |

No dev (`application.yml`) o default é `provider=mock` e
`verify-signature=false` — o E2E não assina o payload.

## 2. Verificação de assinatura do webhook

- **Header:** `X-Psp-Signature` (configurável via `foxcash.psp.webhook.header-name`).
- **Algoritmo:** `HMAC-SHA256(segredo, rawBody)` em **hex minúsculo**.
- **Comportamento:** `POST /webhooks/pix` sem header ou com assinatura inválida
  → `401 unauthorized`. Assinatura válida → processa e responde `204`.
- **Em prod:** `verify-signature` é sempre `true`.

Exemplo de assinatura correta (shell):

```bash
SECRET='seu-segredo'
BODY='{"externalReference":"E1234","status":"PAID"}'
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | awk '{print $2}')
curl -X POST https://localhost:8443/webhooks/pix \
  -H "Content-Type: application/json" \
  -H "X-Psp-Signature: $SIG" \
  -d "$BODY"
```

## 3. Passos de homologação (quando houver credenciais)

1. **Criar conta sandbox** no PSP escolhido (Pagar.me dashboard de testes /
   Asaas sandbox) e gerar as credenciais de API.
2. **Documentar o contrato da API** (endpoint de cobrança imediata, formato do
   payload de resposta — `externalReference`/`pixQrCode`/`pixCopyPaste`,
   formato do webhook e da assinatura). Sem web, este passo fica bloqueado.
3. **Implementar o adapter** — **✅ feito pela Spec 11**:
   `PagarMePixProviderClient` (API v5: `POST /charges`, auth Basic, contrato
   verificado no SDK oficial arquivado `pagarme/pagarme-core-api-java`),
   registrado com `@ConditionalOnProperty(prefix="foxcash.psp",
   name="provider", havingValue="pagarme")` — mesmo padrão do
   `MockPixProviderClient`.
4. **Ajustar o filtro de assinatura** se o PSP usar formato de assinatura
   diferente (ex.: payload assinado antes de serializar, timestamp replay).
5. **Validar a jornada completa** em sandbox: criar merchant → criar cobrança →
   pagar via QR/BR Code → webhook assinado → transação `APPROVED` → fatura.
   Com o adapter da Spec 11, basta apontar `FOXCASH_PSP_PROVIDER=pagarme` +
   `FOXCASH_PSP_PAGARME_SECRET_KEY` (credenciais reais de sandbox).

   **Script de homologação** (`load-tests/psp-homologation.py`) — roda a
   jornada completa + casos de erro (assinatura, referência desconhecida,
   amount divergente, replay idempotente de cobrança e saque, timeout):

   ```bash
   # 5.1. Subir a app com o adapter REAL (credenciais de sandbox):
   SPRING_PROFILES_ACTIVE=dev FOXCASH_PSP_PROVIDER=pagarme \
     FOXCASH_PSP_PAGARME_SECRET_KEY=sk_test_... \
     FOXCASH_PSP_PAYOUT_PROVIDER=mock \
     FOXCASH_PSP_WEBHOOK_VERIFY_SIGNATURE=true \
     FOXCASH_PSP_WEBHOOK_SECRET=dev-only-psp-webhook-secret \
     mvn spring-boot:run

   # 5.2. Executar a jornada (22 checks: criação → pagamento → aprovação →
   #      saldo → saque → conclusão + erros/timeout/replay/assinatura):
   python load-tests/psp-homologation.py \
     --base-url http://localhost:8080 \
     --provider pagarme \
     --webhook-secret dev-only-psp-webhook-secret \
     --payout-webhook-secret dev-only-psp-payout-webhook-secret
   ```

   O modo `--provider mock` (sem credenciais) valida o pipeline interno
   (22/22 OK — jornada fechada sem adquirente real); o modo `--provider
   pagarme` faz a criação da cobrança **através do Pagar.me real** (charge id
   `ch_...` como `externalReference`) — é o que prova a integração real.

   **Jornada validada ao vivo (mock, 2026-08-07):** 22/22 checks OK — cobrança
   201 com QR, assinatura inválida → 401, referência desconhecida → 404, PAID
   assinado → 204, transação APPROVED + SALE_CREDIT, replay → 204 sem efeitos,
   amount divergente → 204 no-op, saque PROCESSING + débito, payout PAID →
   COMPLETED, replay payout → 204, saldo inalterado.

6. **Rodar a suíte**: `mvn test` (39 testes) + E2E 5/5, depois validar em
   homologação real (passo 5.2 com `--provider pagarme` e credenciais
   sandbox).

## 4. Critérios de aceite da Spec 10 (já atendidos pela estrutura)

1. `foxcash.psp.provider` com default `mock` em dev; prod sem default.
2. Seleção por `@ConditionalOnProperty`; `pagarme`/`asaas` sem adapter → boot
   falha (bean ausente).
3. `/webhooks/pix` valida HMAC-SHA256 (sem header → 401; incorreta → 401;
   correta → processa).
4. `verify-signature` configurável (`true` default; `false` só dev/tests).
5. `mvn test` verde (35 testes) + E2E 5/5.
6. Este runbook + seção no README.
7. Boot `prod` sem `FOXCASH_PSP_WEBHOOK_SECRET` falha (fail-fast).
