---
name: Credenciais API do lojista — gestão de chaves (listar/revogar/regenerar, escopos, último uso)
status: audited
references:
  - "management/vision.md#V17"
  - "management/specs/20-portal-foundation/requirements.md"
  - "management/specs/12-api-authentication/requirements.md"
---

# Spec 24 — Credenciais API do lojista (backend-first)

## O que entrega

APIs de **gestão de chaves de API** do portal do lojista. Hoje a chave é única
e gerada no cadastro (`X-Api-Key`, Spec 12, devolvida uma única vez). Esta
spec evolui para um modelo de **chaves gerenciáveis** — a tela "Credenciais
API" do portal:

1. **Listar chaves:** `GET /api/v1/portal/credentials` → chaves do lojista com
   `{id, prefix (mch_…), status, scopes, lastUsedAt, createdAt}` — nunca o
   segredo completo.
2. **Criar chave:** `POST /api/v1/portal/credentials` `{label, scopes}` → 201
   devolvendo o segredo **uma única vez** (mesmo padrão do cadastro).
3. **Revogar:** `POST /api/v1/portal/credentials/{id}/revoke` → 204; chave
   revogada deixa de autenticar (401).
4. **Regenerar:** revogar + criar nova (fluxo explícito, sem rota mágica).
5. **Escopos:** enum inicial `{charges, transactions, withdrawals}` — a chave
   do cadastro permanece com escopo total; escopos novos restringem as rotas
   que a chave pode acessar (validação no filtro, Spec 12).
6. **Último uso:** `last_used_at` atualizado a cada autenticação bem-sucedida
   (tabela nova — migration V13 junto com a Spec 20).

**Painel "Uso no backend"** (doc/contrato, não endpoint): a spec entrega a
documentação de integração — exemplo de header `x-api-key`, checklist rápido e
**exemplo curl** para o lojista implementar no backend dele. O modo
**produção** valida que a chave tem escopo correto e não foi revogada.

## Critérios de aceite

1. Migration V13 cria `merchant_api_keys` (FK merchant, `label`, `key_hash`
   SHA-256 único, `status` ATIVO/REVOGADA, `scopes`, `last_used_at`,
   `created_at`) e migra a chave existente do cadastro para a nova tabela.
2. `GET /api/v1/portal/credentials` lista as chaves sem o segredo (prefix +
   status + escopos + último uso); exige `ROLE_MERCHANT_PORTAL`.
3. `POST /api/v1/portal/credentials` `{label, scopes}` → 201 com `{id, secret}`
   (secret devolvido **uma única vez**); escopo inválido → `400
   invalid_argument`; limite (ex.: 5 chaves ativas) → `409 too_many_keys`.
4. `POST /api/v1/portal/credentials/{id}/revoke` → 204 e chave revogada não
   autentica mais (401 em `X-Api-Key`); chave inexistente/de outro lojista →
   `404`/`403` (autorização por recurso, Spec 18).
5. `last_used_at` atualiza na autenticação válida; chave com escopo restrito
   não acessa rotas fora do escopo (403).
6. Docs: seção "Uso no backend" (header `x-api-key`, checklist, exemplo curl)
   no README; `mvn test` verde (**~160 testes: 153 + ~7 novos** —
   `ApiKeyManagementServiceTest`, filtro com escopo) e E2E sem regressão.

## Dependências

- Spec 20 (auth do portal).
- Spec 12 (filtro `X-Api-Key`, hash SHA-256 — evoluído para múltiplas chaves).
- Spec 18 (autorização por recurso).

## Observações

- **Segredo nunca persistido:** apenas `key_hash` SHA-256 (padrão do projeto).
- **A chave do cadastro continua válida** (migração sem quebrar integrações).
- **Frontend fica para spec futura** — o contrato REST + docs são o entregável.
- **Nota de implementação (2026-08-04):** a tabela foi criada na migration
  **V16** (não V13 — este número já foi usado pela Spec 20 em 2026-08-04).
  `mvn test` verde (**196 testes: 175 + 21**) e E2E **9/9** (cenário 9:
  credenciais).
