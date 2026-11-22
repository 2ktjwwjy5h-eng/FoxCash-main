# Visão do Cliente — FoxCash

Visão **voltada ao lojista (vendedor)** — a experiência de uso da plataforma, o
onboarding e as obrigações de conformidade que o backend precisa suportar. O
gateway é **somente para o vendedor** (decisão de produto 2026-08-04): o contato
com o cliente é do **bot do lojista**, e o FoxCash **não captura nem armazena
dados do comprador**. Documento canônico do contrato técnico com o negócio; as
regras operacionais completas vivem em [Políticas de Uso e Compliance](../docs/politicas-uso.md).

---

## Onboarding do lojista (vendedor)

O cadastro do lojista é o primeiro contato do vendedor com a plataforma e é
**público** por design (`POST /api/v1/merchants` — é onde a chave de API é
emitida, Spec 12). O backend já entrega hoje:

- Cadastro com `tradingName`, `legalName` e `document` (CPF/CNPJ), documento
  **criptografado em repouso** (AES-256/GCM) e buscável por hash determinístico
  (Spec 4).
- **Chave de API por lojista** (`mch_<32 hex>`) devolvida **uma única vez**,
  persistida apenas como hash SHA-256 (Spec 12).
- Status do lojista (`ACTIVE` | `SUSPENDED` | etc.) já existe no domínio e
  bloqueia processamento quando não-ativo (Spec 12: só lojista `ACTIVE` é
  autenticado; Spec 14: bloqueio de saques por lojista).

### Sem KYC (decisão de produto — 2026-08-04)

O FoxCash **não exige KYC documental** (sem captura/upload de RG/CNH, selfie
etc.). O cadastro do lojista exige apenas o **CPF/CNPJ válido** (documento
fiscal, já validado na Spec 18) e a chave de API emitida na hora (Spec 12). O
que o backend entrega hoje:

- Cadastro com `tradingName`, `legalName` e `document` (CPF/CNPJ válido),
  documento **criptografado em repouso** (AES-256/GCM) e buscável por hash
  determinístico (Spec 4/18).
- Status do lojista (`ACTIVE` | `SUSPENDED`) bloqueia processamento quando
  não-ativo (Spec 12) — a suspensão por risco é decisão do master (Spec 16,
  ADR-008).
- **Sem dados do comprador:** o gateway **não captura nem armazena** dados do
  cliente — o contato com o comprador é do **bot do lojista** (decisão de
  produto 2026-08-04; migration V11 remove as colunas `payer_*`).

---

## Comprador

O gateway é **somente para o vendedor** (decisão de produto 2026-08-04): o
FoxCash **não captura nem armazena dados do comprador** — o contato com o
cliente é do **bot do lojista**, que coleta o que precisar e usa o gateway
apenas para gerar a cobrança Pix (QR Code / Copia e Cola) e receber a
confirmação de pagamento via webhook. A plataforma segue coibindo chargeback
de má-fé (§2.4) via monitoramento de risco (Spec 16, ADR-008) e exige que o
vendedor cumpra as obrigações com o comprador (políticas §3).

---

## Valores reforçados

- **Transparência radical:** descrição clara do produto, preço e condições
  (políticas §3.3) — o backend expõe faturamento transparente (Spec 4/8) e
  dados ao lojista (Spec 15).
- **Suporte humanizado:** SLA de 24h úteis (§3.5), canais de suporte e
  mediação de disputas — reforçados por políticas claras e pelo painel master
  (Spec 13/15).
