# Spec 24 — Task breakdown

Implementação concluída em 2026-08-04. Verificação: `mvn test` **196/0/0** e
E2E Playwright **9/9**.

- [x] V16: tabela `merchant_api_keys` + migração da chave do cadastro.
- [x] Domínio: `ApiKeyScope`, `ApiKeyStatus`, `MerchantApiKey`, exceções
      (`TooManyApiKeysException`, `ApiKeyNotFoundException`), ports (in/out).
- [x] `ApiKeyManagementService` (list/create com limite 5/revoke).
- [x] Persistência: entity, jpa repository, mapper, adapter.
- [x] REST: `PortalCredentialsController` + DTOs (201 secret 1x, 400, 409, 404).
- [x] `ApiKeyAuthFilter`: escopos por rota + `recordUse` + fallback legado +
      fix de resposta 403 (flushBuffer + encerra a cadeia).
- [x] `RegisterMerchantService`: chave do cadastro também na tabela nova.
- [x] `GlobalExceptionHandler`: `too_many_keys` (409) e `api_key_not_found` (404).
- [x] Testes unitários: `ApiKeyManagementServiceTest`,
      `PortalCredentialsControllerTest`, `ApiKeyAuthFilterTest` (atualizado).
- [x] E2E: cenário 9 (credenciais do portal).
- [x] README: seção "Uso no backend".
- [x] `mvn test` verde (196) + E2E 9/9; roadmap/requirements `implemented`.
- [ ] `/spec-audit` (pendente de execução formal do fluxo).
