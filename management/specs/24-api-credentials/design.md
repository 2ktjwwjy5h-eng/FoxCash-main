# Spec 24 — Design: Credenciais API do lojista

## Contexto

Hoje a chave de API é única, gerada no cadastro (Spec 12) e armazenada apenas
como hash em `merchants.api_key_hash`. Esta spec evolui para **múltiplas chaves
gerenciáveis** pelo portal do lojista, com escopos e último uso.

## Decisões de arquitetura

### Tabela nova `merchant_api_keys` (migration **V16** — não V13, já usada pela Spec 20)

```
merchant_api_keys
  id           UUID PK
  merchant_id  UUID FK merchants
  label        VARCHAR(80)   — nome da chave (ex.: "Bot de produção")
  prefix       VARCHAR(16)   — "mch_" + 6 hex (identificação sem o segredo)
  key_hash     VARCHAR(64)   — SHA-256 do segredo, único (padrão do projeto)
  status       VARCHAR(20)   — ACTIVE | REVOKED
  scopes       VARCHAR(255)  — CSV: charges,transactions,withdrawals
  last_used_at TIMESTAMP     — atualizado a cada autenticação válida
  created_at   TIMESTAMP
  UNIQUE (key_hash)
```

A migration **migra as chaves existentes** do cadastro
(`INSERT ... SELECT FROM merchants WHERE api_key_hash IS NOT NULL`) com escopo
total e label `"Chave do cadastro"` — integrações existentes não quebram
(prefixo `mch_` mantido, mesmo hash).

### Domínio (hexagonal)

- `ApiKeyScope` enum: `charges`, `transactions`, `withdrawals` (escopo total =
  os três).
- `MerchantApiKey` (domínio): id, merchantId, label, prefix, keyHash, status,
  scopes (Set), lastUsedAt, createdAt — com `revoke()`, `recordUse(Instant)`,
  `hasScope(...)`, `isTotal()`.
- Port out `MerchantApiKeyRepository`: `save`, `findByKeyHash` (filtro),
  `findByMerchantId`, `findByIdAndMerchantId` (posse — 404 se de outro lojista),
  `countActiveByMerchantId` (limite), `recordUse`.
- Port in `ApiKeyManagementUseCase`: `list(merchantId)`, `create(Command)`,
  `revoke(merchantId, keyId)`.
- Exceções: `TooManyApiKeysException` (409 `too_many_keys`),
  `ApiKeyNotFoundException` (404 `api_key_not_found`).

### Filtro `ApiKeyAuthFilter` (Spec 12 evoluído)

1. Chave admin → `ROLE_ADMIN` (inalterado, tempo constante).
2. `merchantApiKeyRepository.findByKeyHash(hash)`:
   - Chave **ACTIVE** + lojista **ACTIVE** → `ROLE_MERCHANT` (principal =
     merchantId) e `recordUse` (last_used_at).
   - Chave válida mas **escopo insuficiente** para a rota → **403 `forbidden`**
     (o filtro responde direto, exceção documentada — o SecurityConfig não
     distingue escopo).
   - Chave **REVOKED**/inexistente → sem autenticação (401 pelo SecurityConfig).
3. Fallback (legado): `merchants.api_key_hash` com escopo total — cobre
   cadastros anteriores à migração.

**Mapeamento rota → escopo** (rota sem escopo → exige escopo total):

| Rota (API key) | Escopo |
|---|---|
| `/api/v1/foxcash/pix/**` | `charges` |
| `/api/v1/transactions/**`, `/api/v1/merchants/{id}/transactions` | `transactions` |
| `/api/v1/merchants/{id}/withdrawals/**` | `withdrawals` |
| demais `/api/v1/**` (merchants, billing, invoices…) | total |

### REST (portal, `ROLE_MERCHANT_PORTAL`)

- `GET /api/v1/portal/credentials` → `[{id, prefix, status, scopes,
  lastUsedAt, createdAt}]` — **nunca o segredo/hash**.
- `POST /api/v1/portal/credentials` `{label, scopes}` → 201 `{id, secret}` —
  segredo devolvido **uma única vez**; escopo inválido → 400
  `invalid_argument`; > 5 chaves ativas → 409 `too_many_keys`.
- `POST /api/v1/portal/credentials/{id}/revoke` → 204; chave de outro lojista
  ou inexistente → 404 `api_key_not_found` (mesmo padrão GiftPreset).
- Regenerar = revogar + criar nova (fluxo explícito, sem rota mágica).

### Cadastro (RegisterMerchantService)

Além do `merchants.api_key_hash` (Spec 12, mantido para compatibilidade), a
chave do cadastro agora também é persistida em `merchant_api_keys` com escopo
total e label `"Chave do cadastro"` — consistente com a migration.

## Arquivos

- `db/migration/V16__merchant_api_keys.sql` (nova)
- `domain/model/apikey/ApiKeyScope.java`, `MerchantApiKey.java` (novos)
- `domain/exception/TooManyApiKeysException.java`, `ApiKeyNotFoundException.java`
- `domain/port/out/MerchantApiKeyRepository.java`, `domain/port/in/ApiKeyManagementUseCase.java`
- `application/service/ApiKeyManagementService.java`
- `infrastructure/outbound/persistence/{entity,repository,mapper,adapter}/MerchantApiKey*`
- `infrastructure/inbound/rest/PortalCredentialsController.java` + DTOs
- `infrastructure/security/ApiKeyAuthFilter.java` (escopos + recordUse + fallback)
- `infrastructure/security/SecurityConfig.java` (injeta MerchantApiKeyRepository + ObjectMapper no filtro)
- `application/service/RegisterMerchantService.java` (chave na tabela nova)
- `infrastructure/inbound/rest/GlobalExceptionHandler.java` (2 handlers)
- Testes: `ApiKeyManagementServiceTest`, `PortalCredentialsControllerTest`,
  `ApiKeyAuthFilterTest` (atualizado), `RegisterMerchantServiceTest` (mock novo)
- E2E: cenário 9 (credenciais do portal)
- README: seção "Uso no backend"

## Fora de escopo

- Frontend (spec futura).
- Regeneração automática (revogar + criar é o fluxo explícito).
- Rate limit específico por escopo.
