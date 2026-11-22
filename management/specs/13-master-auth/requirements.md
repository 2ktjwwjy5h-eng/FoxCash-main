---
name: Perfil master — login usuário/senha + sessão (BCrypt, token opaco 8h, ROLE_MASTER)
status: audited
references:
  - "management/vision.md#V11"
  - "management/adr.md#ADR-007"
  - "management/specs/12-api-authentication/requirements.md"
  - "management/specs/9-production-foundation/requirements.md"
---

# Spec 13 — Perfil master: login + sessão

## O que entrega

Cria o **perfil master** (operador da plataforma) com autenticação própria por
**usuário + senha** e **sessão por token opaco** (ADR-007) — diferente do
lojista (API key, Spec 12) e do admin técnico (chave única):

1. **Tabelas `masters` e `master_sessions`** (migration **V7**): `masters` com
   usuário único (`username`) e senha com hash **BCrypt** (`password_hash`),
   `name`, `active`, `created_at`. `master_sessions` com `token_hash` (SHA-256
   do token opaco), `master_id`, `created_at`, `expires_at`, `revoked_at`.
2. **Login:** `POST /api/v1/auth/master/login` `{username, password}` →
   `200 {token, masterId, name, expiresAt}` com token opaco (`mst_<32 hex>`,
   expira em 8h, configurável). Credenciais inválidas ou master inativo →
   `401 unauthorized`. O token em claro **nunca é persistido** — apenas o hash.
3. **Logout:** `POST /api/v1/auth/master/logout` (header `Authorization:
   Bearer <token>`) revoga a sessão (`revoked_at`) → `204`.
4. **`MasterAuthFilter`** (Spring Security): resolve `Authorization: Bearer
   <token>` → sessão válida (não expirada, não revogada, master `ACTIVE`) →
   `ROLE_MASTER` com `masterId` no principal. Rota `/api/v1/master/**` exige
   `ROLE_MASTER` (401 sem token, 403 com outra role).
5. **Seed do primeiro master:** no boot, se a tabela `masters` estiver vazia, um
   `ApplicationRunner` cria o master inicial com
   `NYX_MASTER_SEED_USERNAME`/`NYX_MASTER_SEED_PASSWORD` (dev tem default fake;
   em prod sem env vars **não cria** — apenas documenta).
6. **Config:** `foxcash.master.*` — `seed.username`, `seed.password`,
   `session-ttl-seconds` (default 28800). Prod sem default para o seed.

## Critérios de aceite

1. Migration V7 cria `masters` (usuário único, senha BCrypt) e
   `master_sessions` (token_hash único, expiração, revogação).
2. `POST /api/v1/auth/master/login` com credenciais corretas → `200` com token
   opaco (`mst_<32 hex>`), `masterId`, `name`, `expiresAt` (~8h); senha errada
   ou usuário inexistente → `401 unauthorized`; master `active=false` → `401`.
3. `POST /api/v1/auth/master/logout` revoga a sessão; token revogado → `401`
   nas rotas `/api/v1/master/**`.
4. `MasterAuthFilter`: token válido → `ROLE_MASTER` (principal = masterId);
   token expirado/revogado/desconhecido → nenhuma autenticação. `/api/v1/master/**`
   sem token → 401; com token de lojista/admin → 403.
5. `SecurityConfig`: `/api/v1/master/**` exige `ROLE_MASTER`; `POST
   /api/v1/auth/master/login` é pública; as rotas existentes (lojista/admin,
   webhook, cadastro) permanecem inalteradas.
6. Seed: com a tabela vazia e env vars de seed preenchidas, o boot cria o
   master inicial (senha BCrypt); com a tabela populada ou sem env vars, não
   duplica nem falha. Boot `prod` sem `NYX_MASTER_SEED_*` **não falha** (não
   cria — documentado).
7. `mvn test` verde (**58 testes: 49 + 9 novos** — 6 do `MasterAuthServiceTest`,
   3 do `MasterAuthFilterTest`) e E2E **5/5** sem regressão.

## Dependências

- Spec 12 (padrão de filtro Spring Security, `ErrorResponse`, hash SHA-256).
- Spec 9 (padrão fail-fast de env vars e profile prod).
- ADR-007 (decisão do mecanismo master).

## Observações

- **Por que BCrypt?** Senhas de operadores humanos precisam de hash lento e
  salteado — BCrypt é o padrão do Spring Security (`BCryptPasswordEncoder`).
- **Por que token opaco com sessão no banco?** Revogação imediata no logout —
  JWT não revogaria (ADR-007).
- **Token em claro nunca persiste** — só o hash SHA-256 (`token_hash`),
  mesmo padrão de `api_key_hash` (Spec 12).
- **Criação de novos perfis master** (além do seed) fica fora de escopo —
  endpoint administrativo em spec futura.
- **Refresh/expiração renovável** fora de escopo (expiração fixa configurável).
