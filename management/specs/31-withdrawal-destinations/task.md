# Spec 31 — Task breakdown

Status: `audited` (2026-08-04). Ciclo SDD completo — implementada (commit `7edb7b4`) e auditada.

## Implementação

- [x] Migration `V20__withdrawal_destinations.sql`: tabela
      `merchant_withdrawal_destinations` + índices `(merchant_id)` e
      `(merchant_id, status)`; campos sensíveis como `VARCHAR(255)` (ciphertext
      AES-256/GCM).
- [x] `WithdrawalDestinationType` (enum: PIX_CPF/PIX_CNPJ/PIX_PHONE/PIX_EVP/
      BANK_ACCOUNT) + `WithdrawalDestination` (record de domínio com
      create/withLabel/activate/deactivate).
- [x] `WithdrawalDestinationValidator` (domínio): CPF/CNPJ com dígitos
      verificadores (comprimento 11/14 + `DocumentValidator.isValid`), celular
      BR (`^\+55[1-9]\d{10,11}$`), EVP (UUID), BANK_ACCOUNT (banco/agência/conta
      obrigatórios).
- [x] `WithdrawalDestinationRepository` (port out) + `WithdrawalDestinationJpaEntity`
      (campos sensíveis com `@Convert(CryptoConverter.class)`) + mapper +
      repository + adapter.
- [x] `WithdrawalDestinationUseCase` + `WithdrawalDestinationService`: create
      (valida + limite ativos 409 `too_many_destinations`), list, updateLabel,
      setActive (404 `destination_not_found` para destino de outro lojista).
- [x] `WithdrawalDestinationController` (`/api/v1/portal/destinations`,
      ROLE_MERCHANT_PORTAL): POST 201, GET mascarado, PATCH /{id}, POST
      /{id}/activate|deactivate 204.
- [x] `WithdrawalDestinationMasker` (CPF `529.***.***-25`, CNPJ, celular, EVP
      parcial, banco/agência/***-conta) — nunca expõe o valor em claro.
- [x] Propriedade `foxcash.portal.max-withdrawal-destinations` (default 5).

## Verificação

- [x] `WithdrawalDestinationValidatorTest`: formatos válidos/inválidos por tipo
      (CPF `123` → inválido; celular `123` → inválido; EVP não-UUID → inválido;
      BANK_ACCOUNT sem conta → inválido).
- [x] `WithdrawalDestinationServiceTest`: CRUD, limite ativos → 409,
      findByIdAndMerchantId de outro lojista → 404.
- [x] `WithdrawalDestinationControllerTest`: máscara na resposta, 400/401/403,
      404 cross-loja (sem vazar existência).
- [x] Verificação no banco: `value`/`account` persistidos com prefixo `v1:`
      (criptografados), nunca texto claro (E2E cenário 12).
- [x] E2E: cenário 12 (criar tipos válidos/inválidos, listar mascarado,
      ativar/desativar, 404 cross-loja, limite 409); suite **13/13** sem
      regressão.
- [x] `mvn test` verde (**246 testes, 0 falhas**).
- [x] Roadmap `implemented` → `audited`; requirements/design/task atualizados.
- [x] `/spec-audit` → `audited` (fim do ciclo SDD).
