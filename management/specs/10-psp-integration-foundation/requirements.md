---
name: Integração com PSP — estrutura + contrato (seleção por config, verificação de assinatura de webhook)
status: audited
references:
  - "management/vision.md#V8"
  - "management/adr.md#ADR-005"
  - "management/specs/9-production-foundation/requirements.md"
---

# Spec 10 — Integração com PSP: estrutura + contrato

## O que entrega

Prepara o FoxCash para operar com um provedor Pix **real** sem acoplar o
domínio ao provedor (ADR-005: Pagar.me primário, Asaas alternativa; ADR-001:
troca de PSP = novo adapter do port `PixProviderClient`). Esta spec entrega a
**estrutura** e o **contrato** de integração — a chamada HTTP real fica para
uma spec futura (quando houver credenciais de sandbox e web disponível).

Escopo:

1. **Seleção de PSP por configuração**: propriedade `foxcash.psp.provider`
   (`mock` | `pagarme` | `asaas`) + propriedades `foxcash.psp.*` vindas de env
   vars (`NYX_PSP_*`). O adapter mock continua default em dev; o profile `prod`
   exige a configuração explícita (sem default inseguro). A seleção é feita via
   condicional Spring (`@ConditionalOnProperty`) — a troca de PSP não toca o
   domínio nem os serviços de aplicação.
2. **Verificação de assinatura de webhook**: o endpoint `/webhooks/pix` passa a
   validar a assinatura HMAC-SHA256 do payload antes de processar. Header de
   assinatura (configurável) + segredo (`NYX_PSP_WEBHOOK_SECRET`). Payload sem
   assinatura ou com assinatura inválida → `401 unauthorized`. Sem isso, uma
   chamada falsa ao webhook poderia aprovar transações indevidamente.
3. **Contrato de homologação documentado**: runbook passo a passo em
   `deploy/psp-homologation.md` (criar conta sandbox, obter credenciais,
   apontar `NYX_PSP_*`, validar cobrança + webhook com assinatura). A integração
   HTTP real (adapter Pagar.me/Asaas) é declarada fora de escopo desta spec.

## Critérios de aceite

1. `application.yml` define `foxcash.psp.provider: mock` como default de dev e
   o bloco `foxcash.psp.webhook.*` (header de assinatura e segredo) com default
   apenas para dev; `application-prod.yml` **não** define default para
   `foxcash.psp.provider` nem para o segredo do webhook (env var obrigatória).
2. Existe um mecanismo de seleção de adapter (ex.: `@ConditionalOnProperty`)
   que registra o `MockPixProviderClient` quando `provider=mock`. Escolher
   `pagarme`/`asaas` (sem adapter concreto ainda) faz o boot **falhar** com
   erro claro de bean ausente para `PixProviderClient` — fail-fast, nunca
   operar silenciosamente sem provedor.
3. `/webhooks/pix` valida assinatura HMAC-SHA256: (a) request **sem** header de
   assinatura → `401`; (b) request com assinatura **incorreta** → `401`; (c)
   request com assinatura **correta** → processado (204) e transação atualizada
   como antes.
4. A verificação de assinatura é **configurável**: `foxcash.psp.webhook.verify-signature`
   (`true` default; `false` apenas para dev/tests) — em prod é sempre `true`.
5. `mvn test` verde (35 testes: 32 + 3 novos — webhook sem header de
   assinatura, com assinatura incorreta e com assinatura correta) e E2E 5/5 sem
   regressão (o E2E usa `verify-signature=false` no profile dev).
6. `deploy/psp-homologation.md` existe com o runbook de homologação (conta
   sandbox, credenciais, env vars, validação de cobrança e webhook) e o README
   ganha uma seção curta apontando para ele.
7. Boot com `--spring.profiles.active=prod` **sem** `NYX_PSP_WEBHOOK_SECRET`
   falha no startup (fail-fast, mesmo padrão da Spec 9).

## Dependências

- Spec 9 (profile `prod`, env vars obrigatórias, CI/CD).
- ADR-005 (decisão do PSP: Pagar.me primário / Asaas alternativa).
- Spec 4 (segurança: HTTPS, criptografia, webhook existente).

## Observações

- **Por que sem adapter HTTP real:** a web está indisponível nesta máquina e
  não há credenciais de sandbox do PSP. Implementar a chamada HTTP agora seria
  escrever contra uma especificação não verificável. Esta spec fixa o contrato
  (config, assinatura, runbook); a spec futura (ex.: 11-psp-pagarme) implementa
  o adapter concreto quando houver credenciais e web.
- **Segurança do webhook:** sem verificação de assinatura, qualquer requester
  poderia enviar `PAID` para uma `externalReference` e aprovar uma transação
  não paga. A verificação HMAC-SHA256 mitiga isso; em prod `verify-signature`
  é sempre `true`.
- **Seleção de PSP:** `@ConditionalOnProperty` mantém o domínio e os use cases
  intocados (ADR-001). O `MockPixProviderClient` vira o adapter default de dev;
  `pagarme`/`asaas` são valores aceitos pela config, mas sem bean concreto —
  escolhê-los faz o boot falhar (fail-fast) até o adapter real existir.
- Escopo **excluído** desta spec: adapter HTTP real (Pagar.me/Asaas), refund e
  split no PSP, retry/outbox de webhooks, observabilidade do PSP.
