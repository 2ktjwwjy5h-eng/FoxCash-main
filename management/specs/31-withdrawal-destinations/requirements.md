---
name: Destinos de saque do lojista — chaves Pix e dados bancários privados e criptografados
status: audited
references:
  - "management/vision.md#V19"
  - "management/specs/20-portal-foundation/requirements.md"
  - "management/specs/4-security-and-e2e/requirements.md"
  - "management/specs/18-security-hardening/requirements.md"
---

# Spec 31 — Destinos de saque do lojista

## O que entrega

Hoje o saque (`POST /withdrawals`) recebe **apenas `{amount}`** — não há
destino: o dinheiro não sabe para onde vai e nenhuma informação de
endereçamento fica salva na conta do lojista (decisão de produto 2026-08-04).

Esta spec cria o **cadastro de destinos de saque do lojista** — privado,
criptografado em repouso e selecionável pelo próprio vendedor no ato do saque
(Spec 32):

1. **Tabela `merchant_withdrawal_destinations`** (migration V20): o lojista
   cadastra um ou mais destinos, cada um de **um dos 5 tipos**:
   - `PIX_CPF` (chave Pix = CPF), `PIX_CNPJ` (chave Pix = CNPJ),
     `PIX_PHONE` (chave Pix = celular), `PIX_EVP` (chave Pix aleatória) — os 4
     tipos de chave do Bacen;
   - `BANK_ACCOUNT` (dados bancários: banco + agência + conta, com chave Pix
     associada opcional).
2. **Validação por tipo** no domínio: CPF/CNPJ com dígitos verificadores
   (reuso `DocumentValidator` da Spec 18), celular no formato BR, chave
   aleatória como UUID válido, dados bancários com campos obrigatórios.
3. **Criptografia em repouso (AES-256/GCM)** via `CryptoConverter` (padrão do
   `document`, Spec 4): chave Pix e dados bancários **nunca** em texto claro no
   banco — mesmo em backups/dumps.
4. **Privacidade total por lojista:** só o próprio lojista (portal,
   `ROLE_MERCHANT_PORTAL`, principal = merchantId) gerencia e vê os **seus**
   destinos. Nenhum endpoint expõe o valor em claro — a listagem devolve
   **máscara** (ex.: CPF `529.***.***-25`, celular `+55 11 *****-9999`). O
   master **não** tem acesso aos destinos (dados privados do vendedor —
   decisão do usuário).
5. **Gestão:** criar, listar (mascarado), atualizar rótulo, ativar/desativar —
   limite de destinos ativos (configurável, default 5).

**Contrato:** `POST /api/v1/portal/destinations` (201), `GET
/api/v1/portal/destinations` (mascarados), `PATCH /api/v1/portal/destinations/{id}`
(rótulo), `POST /api/v1/portal/destinations/{id}/activate|deactivate` (204).

## Critérios de aceite

1. Migration V20 aplicada: tabela `merchant_withdrawal_destinations` com
   `merchant_id`, `type`, `value` (criptografado), campos de dados bancários
   (criptografados), `label`, `status`, timestamps e índices
   `(merchant_id)`/`(merchant_id, status)` (verificado no banco: `value` com
   prefixo `v1:` — nunca texto claro).
2. `POST /api/v1/portal/destinations` cria destino de cada tipo válido:
   `PIX_CPF`/`PIX_CNPJ` (dígitos verificadores), `PIX_PHONE` (formato BR),
   `PIX_EVP` (UUID), `BANK_ACCOUNT` (banco+agência+conta) → **201**.
3. Tipo inválido / formato inválido / campo obrigatório ausente → **400
   `invalid_argument`** (ex.: CPF `123` → 400; `PIX_PHONE` `123` → 400;
   `BANK_ACCOUNT` sem conta → 400).
4. Listagem `GET /api/v1/portal/destinations` retorna apenas os do lojista
   autenticado, **sempre mascarados** (nunca o valor em claro) e com
   `type`/`label`/`status`/`createdAt`.
5. `activate`/`deactivate` → 204; destino inativo é rejeitado no saque (Spec
   32); reativação → 204. Limite de ativos (default 5) → **409
   `too_many_destinations`**.
6. Privacidade: `GET/PATCH/activate` em destino de **outro** lojista → **404**
   (nunca 403/vazamento — não revela existência); 401 sem token; 403 com API
   key em `/portal/**`.
7. `mvn test` verde (204 + novos) e E2E sem regressão (**10/10** + cenário 11:
   criar tipos válidos/inválidos, listar mascarado, ativar/desativar, 404
   cross-loja, limite 409).

## Dependências

- Spec 20 (auth do portal + convenções de resposta).
- Spec 4/18 (criptografia AES-256/GCM via `CryptoConverter`; `DocumentValidator`
  para CPF/CNPJ).
- Spec 18 (autorização por recurso — privacidade por lojista).

## Observações

- **Escopo:** cadastro e exposição mascarada. O **uso** no saque (destino
  selecionado) é a Spec 32; a transferência real via PSP (Pix de saída) fica
  para spec futura (hoje o saque é simulado — debita e `COMPLETED`).
- **Máscara** é feita na resposta (o valor em claro só existe na memória no
  instante da criação/edição; nunca é devolvido).
- **`BANK_ACCOUNT`:** campo `value` (chave Pix associada) é opcional; sem
  chave, o destino serve para transferência bancária futura (TED) — fora de
  escopo nesta spec.
- **Master sem acesso** por decisão do usuário (dados privados do vendedor).
