# Spec 39 — Design: Cadastro com nome, e-mail e senha

## Contexto

O cadastro exige hoje `tradingName`+`legalName`+`document` (CPF/CNPJ validado,
Spec 18) e o login do portal usa o documento como username (Spec 20). O usuário
quer cadastro mínimo: **nome, e-mail e senha escolhida** — sem comprovação
documental. O e-mail vira o identificador único de login.

## Decisões de arquitetura

### 1. Migration V31 — `email`, `name`, documento opcional

```sql
ALTER TABLE merchants ADD COLUMN email VARCHAR(255);
ALTER TABLE merchants ADD COLUMN name VARCHAR(120) NOT NULL DEFAULT '';
UPDATE merchants SET name = COALESCE(legal_name, trading_name, '');
-- documento vira opcional (lojistas legados preservados)
ALTER TABLE merchants ALTER COLUMN document DROP NOT NULL;
ALTER TABLE merchants ALTER COLUMN document_hash DROP NOT NULL;
DROP INDEX uq_merchants_document_hash;
CREATE UNIQUE INDEX uq_merchants_document_hash
    ON merchants(document_hash) WHERE document_hash IS NOT NULL;
-- e-mail único, normalizado em minúsculas (busca exata)
CREATE UNIQUE INDEX uq_merchants_email ON merchants(lower(email))
    WHERE email IS NOT NULL;
-- nome único substitui trading_name/legal_name
ALTER TABLE merchants DROP COLUMN trading_name;
ALTER TABLE merchants DROP COLUMN legal_name;
```

- **E-mail em texto plano** (normalizado `lower(trim())` no domínio e na busca):
  é o login identifier — busca exata por `lower(email)`. Diferente do documento
  (dado fiscal criptografado): para e-mail, o custo/benefício de criptografar +
  coluna de hash não se justifica para um username. Mitigação: `email` entra na
  lista de campos mascarados do `SensitiveDataLoggingFilter`.
- `uq_merchants_document_hash` vira **parcial** (WHERE document_hash IS NOT
  NULL) — no Postgres índices únicos já tratam NULL como distinto, mas o parcial
  documenta a intenção.

### 2. Domínio `Merchant` — `name`/`email`

```java
// domain/model/merchant/Merchant.java
private final String name;      // substitui tradingName/legalName
private final String email;     // único, normalizado em minúsculas (novos)
private final String document;  // opcional (legado — nulo em novos cadastros)

public static Merchant register(String name, String email, PricingPlan plan, String apiKeyHash) {
    // normaliza email: lower(trim()); name não pode ser blank; email formato válido
}

// restore: (id, name, email, document, apiKeyHash, status, plan, walletBalance,
//           withdrawalsBlocked, createdAt) — os testes existentes que usavam a
//           sobrecarga legada (tradingName/legalName) foram atualizados para o
//           modelo novo (email = null no restore de legado).
public static Merchant restore(UUID id, String name, String email, String document,
                               String apiKeyHash, MerchantStatus status, PricingPlan plan,
                               Money walletBalance, boolean withdrawalsBlocked, Instant createdAt) {
    return new Merchant(id, name, email, document, apiKeyHash, status, plan,
            walletBalance, withdrawalsBlocked, createdAt);
}
```

- `register` valida: `name` non-blank (≤120), `email` formato válido
  (`EmailValidator`), normaliza `email = email.trim().toLowerCase()`.
- A senha NÃO entra no domínio (o serviço faz `passwordHasher.hash(password)` e
  cria a `MerchantCredential` — mesma fronteira de hoje).

### 3. Persistência

- `MerchantJpaEntity`: `name` (NOT NULL), `email` (nullable), `document`/
  `documentHash` (nullable); remove `tradingName`/`legalName`.
- `MerchantMapper`: `documentHash = document != null ? DocumentHasher.hash(document) : null`.
- `MerchantRepository`: `findByEmail(String email)` (normaliza em minúsculas);
  **remove `findByDocument`** (sem usuário no cadastro/login — código morto).
- `MerchantJpaRepository`: `findByEmail(String)`, remove `findByDocumentHash`.
- `MerchantPersistenceAdapter`: `findByEmail(email.trim().toLowerCase())`.

### 4. Cadastro (`RegisterMerchantService`)

```java
public Result register(Command command) {           // Command(name, email, password)
    validateName(command.name());                    // blank/>120 -> 400
    String email = normalizeEmail(command.email());  // valida formato -> 400
    if (merchantRepository.findByEmail(email).isPresent()) {
        throw new MerchantAlreadyExistsException();  // 409
    }
    requirePassword(command.password());             // <8 ou >72 -> 400
    ApiKeyGenerator.ApiKeyPair key = apiKeyGenerator.generate();
    Merchant merchant = Merchant.register(command.name(), email, pricingPlan, key.hash());
    Merchant saved = trySave(merchant);              // backstop DataIntegrityViolation -> 409
    // Senha ESCOLHIDA pelo usuário (não gerada): hash BCrypt na credencial.
    MerchantCredential credential = MerchantCredential.create(
            saved.id(), passwordHasher.hash(command.password()), clock.instant());
    credentialRepository.save(credential);
    merchantApiKeyRepository.save(MerchantApiKey.create(...));   // Spec 24 (igual)
    giftPresetRepository...                                     // Spec 23 (igual)
    return new Result(saved.id(), key.plaintext());              // SEM portalPassword
}
```

- `RegisterMerchantUseCase.Command(name, email, password)`; `Result(merchantId, apiKey)`.
- `PortalPasswordGenerator` deixa de ser injetado (código morto — remover).

### 5. Login (`MerchantPortalAuthService`)

```java
Merchant merchant = merchantRepository.findByEmail(normalizeEmail(command.email()))
        .orElseThrow(InvalidCredentialsException::new);
...
return new LoginResult(token.plaintext(), merchant.id(), merchant.name(), expiresAt);
```

- `MerchantPortalAuthUseCase.LoginCommand(email, password)`; `LoginResult(..., name, ...)`.
- `MerchantLoginRequest` passa a `{email, password}` (o controller repassa).

### 6. DTOs / contratos

- `CreateMerchantRequest` → `{name, email, password}` (`@NotBlank`; `@Email`-like
  via validação no serviço; `password @Size(min=8, max=72)`).
- `MerchantRegisteredResponse` → `{id, name, email, status, transactionFee,
  monthlyFee, createdAt, apiKey}` (sem `document`, sem `portalPassword`).
- `MerchantResponse` → `{id, name, email, status, transactionFee, monthlyFee,
  createdAt}`.
- `PortalMeResponse` → `{merchantId, name, email, status, walletBalance,
  withdrawalsBlocked, createdAt}`.
- `MerchantLoginResponse` → `{token, merchantId, name, expiresAt}`.
- `MerchantSearchQuery` (admin): busca por `name` (substitui tradingName/legalName).

### 7. Sanitização de logs

`SensitiveDataLoggingFilter.SENSITIVE_FIELD` ganha `email` na lista de chaves
mascaradas (B7) — o e-mail nunca aparece em claro nos logs DEBUG.

## Arquivos

- `db/migration/V31__merchant_email_registration.sql` (novo)
- `domain/model/merchant/Merchant.java` (name/email + register novo + restore legado)
- `domain/model/admin/MerchantSearchQuery.java` (javadoc)
- `domain/service/EmailValidator.java` (novo — formato simples)
- `domain/port/out/MerchantRepository.java` (findByEmail; remove findByDocument)
- `domain/port/in/RegisterMerchantUseCase.java` (Command/Result)
- `domain/port/in/MerchantPortalAuthUseCase.java` (LoginCommand/Result)
- `application/service/RegisterMerchantService.java` (email+password; sem portalPassword)
- `application/service/MerchantPortalAuthService.java` (findByEmail; name)
- `infrastructure/.../entity/MerchantJpaEntity.java`, `mapper/MerchantMapper.java`,
  `adapter/MerchantPersistenceAdapter.java`, `repository/MerchantJpaRepository.java`
- `infrastructure/.../adapter/AdminDashboardPersistenceAdapter.java` (busca por name)
- `infrastructure/inbound/rest/MerchantController.java`, `MerchantAuthController.java`
- `infrastructure/inbound/rest/dto/` (CreateMerchantRequest, MerchantRegisteredResponse,
  MerchantResponse, PortalMeResponse, MerchantLoginRequest, MerchantLoginResponse)
- `infrastructure/security/SensitiveDataLoggingFilter.java` (mascara email)
- Testes: `RegisterMerchantServiceTest`, `MerchantPortalAuthServiceTest` +
  ajustes em testes que usam `Merchant.restore` legado (compilam sem mudança)
- E2E: cadastro/login por e-mail; duplicado por e-mail; documento não exigido
- README (cadastro/login), roadmap, visão V22

## Fora de escopo

- KYC documental (não existe — decisão 2026-08-04; segue sem).
- Alteraçao da chave de API (Spec 12 mantida — gerada e devolvida 1x).
- Recuperação de senha/fluxo de redefinição (spec futura).
- `DocumentValidator` permanece (Spec 31 — destinos de saque PIX_CPF/CNPJ).
