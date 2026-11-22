---
name: Tela de cadastro do lojista no portal (Spec 39 UI)
status: audited
---

# Spec 142 — Design: Tela de cadastro do lojista no portal

## D1 — Conceito

Nova **rota pública** `/register` no portal (React Router), mesma família visual
das telas de auth existentes (`LoginPage`, `ForgotPasswordPage`,
`ResetPasswordPage` — `auth-screen`/`auth-card`/`Field`/`ErrorBanner` da Spec
73/46). A página é **novo arquivo** (`RegisterPage.tsx`) — não toca em nenhuma
tela existente (cirurgia mínima, Spec 3); as únicas mudanças em arquivos
existentes são: 1 linha de rota no `App.tsx`, 1 link no `LoginPage.tsx` e o fix
de rota pública no backend (`SecurityConfig` + `PortalSpaController`).

## D2 — Estrutura da RegisterPage

```tsx
// Estado
const [name, setName] = useState('');
const [email, setEmail] = useState('');
const [password, setPassword] = useState('');
const [acceptedTerms, setAcceptedTerms] = useState(false);
const [error, setError] = useState<unknown>(null);
const [submitting, setSubmitting] = useState(false);
const [copied, setCopied] = useState(false);
const [registered, setRegistered] = useState<MerchantRegistered | null>(null);
const [turnstileToken, setTurnstileToken] = useState('');
```

1. **Form** (`onSubmit={handleSubmit}`, `className="auth-card"`): título FoxCash +
   subtítulo "Crie sua conta de lojista" + `ErrorBanner` + três `Field`
   (Nome/E-mail/Senha com os `maxLength`/`minLength`/`autoComplete` do critério
   1) + checkbox de termos (D3) + div ref para o Turnstile + botão submit.
2. **handleSubmit** — `e.preventDefault()`, limpa o erro, re-valida
   `acceptedTerms`, calcula `getFingerprintHash()` (Spec 141), chama
   `api.post<MerchantRegistered>('/api/v1/merchants', {...}, { auth: false })`
   (Spec 46 — rota pública sem Bearer); 201 → `setRegistered(created)`; erro →
   `setError` (409/400 exibidos via `ErrorBanner`).
3. **Turnstile (Spec 63, Fase C)** — `useEffect` busca
   `/api/v1/portal/turnstile-config`; se expuser `siteKey`, carrega o script do
   Cloudflare (`loadTurnstileScript`, mesmo padrão do `LoginPage`) e renderiza o
   widget **invisível** no `div ref` com `callback → setTurnstileToken`. Sem
   site key → widget ausente (feature off, cadastro funciona sem token).
4. **Sucesso (`registered`)** — troca para a tela "Conta criada — guarde sua
   chave de API": boas-vindas com o nome, aviso de que a chave é exibida UMA
   única vez (Spec 12), `div.key-value` com a chave, botão **Copiar chave**
   (`copyToClipboard` da Spec 73, feedback "Copiada ✓") e botão **Fazer login**
   (`navigate('/login')`).

## D3 — Termos de uso e condições

```tsx
<label className="checkbox-row">
  <input type="checkbox" checked={acceptedTerms}
         onChange={(e) => setAcceptedTerms(e.target.checked)} required />
  <span className="checkbox-label">
    Li e concordo com os{' '}
    <a href={TERMS_URL} target="_blank" rel="noopener noreferrer">
      termos de uso e condições
    </a>
  </span>
</label>
```

- `TERMS_URL` → `docs/politicas-uso.md` no repositório
  (`https://github.com/operador/FoxCash/blob/main/docs/politicas-uso.md`).
- O botão **Criar conta** tem `disabled={submitting || !acceptedTerms}` — a
  validação no handler é defensiva (o botão já bloqueia).

## D4 — Contrato com o backend (Spec 39)

**Request** (`CreateMerchantRequest`): `name` (1–120, `@NotBlank @Size(max=120)`),
`email` (≤255, único, normalizado minúsculas), `password` (8–72),
`turnstileToken` (opcional), `fingerprintHash` (opcional, ≤64).

**Response** (`MerchantRegisteredResponse`): `{id, name, email, status,
transactionFee, monthlyFee, createdAt, apiKey}` — a UI consome `{id, name,
email, apiKey}` (o resto é ignorado pelo TypeScript).

**Erros**: 409 `merchant_already_exists` (e-mail duplicado — Spec 5/39), 400
`validation_error`/`invalid_argument`, 403 `turnstile_failed` (apenas com a
feature Turnstile ativa — Spec 63). Todos chegam como `ApiError`
(`message`/`code`) e são exibidos pelo `ErrorBanner`.

## D5 — Integração (arquivos tocados)

| Arquivo | Mudança |
|---|---|
| `portal/src/pages/RegisterPage.tsx` | (novo) página completa (D2/D3/D4) |
| `portal/src/App.tsx` | `+1` rota pública: `<Route path="/register" element={<RegisterPage />} />` |
| `portal/src/pages/LoginPage.tsx` | `+1` link no `auth-links`: `<Link to="/register">Criar conta</Link>` |
| `src/main/java/.../security/SecurityConfig.java` | `+"/register"` em `spaPublicPaths()` (rotas públicas da SPA) |
| `src/main/java/.../inbound/rest/PortalSpaController.java` | `+"/register"` no `@RequestMapping` (fallback → index.html) |
| `e2e-tests/tests/register-ui.spec.js` | (novo) 2 cenários E2E |
| `src/main/resources/static/` | build do portal (novo asset `index-B30SWor6.js`); console reconstruído por último (ordem do README) |

**Por que o fix D5 é necessário**: com `FOXCASH_PORTAL_SERVE_FRONTEND=true`, o
`SecurityConfig` libera as rotas da SPA (`spaPublicPaths`) e o
`PortalSpaController` encaminha para o `index.html`. Sem `/register` nas duas
listas, `GET /register` cai no `anyRequest().authenticated()` → **401 JSON** no
acesso direto (F5, deep-link, `page.goto('/register')` do E2E). O `/login` já
estava nas listas — o cadastro foi esquecido por ser rota nova.

## D6 — Testes

- **E2E** (`e2e-tests/tests/register-ui.spec.js`, Playwright, padrão Spec 73):
  1. Cadastro com nome/e-mail/senha + termos → conta criada, tela de sucesso com
     a API key no `.key-value` e botão "Fazer login"; antes do aceite, o botão
     "Criar conta" está desabilitado.
  2. Tela de login tem o link "Criar conta".
- **Build** — `tsc -b` (portal) limpo; `mvn package` com o fix Java.
- **Verificação ao vivo** — app em dev com `serve-frontend=true`:
  `GET /register` → 200 `text/html` (antes do fix: 401).

**Âncoras validadas na spec:** `CreateMerchantRequest`/`MerchantRegisteredResponse`
existem (Spec 39); `LoginPage`/`ForgotPasswordPage` usam `auth-card`/`Field`/
`ErrorBanner` (Spec 73/46); `api.post` suporta `{auth:false}` (Spec 46);
`getFingerprintHash` existe (`fingerprint.ts`, Spec 141); `/api/v1/portal/
turnstile-config` existe (Spec 63); `spaPublicPaths`/`PortalSpaController` são
os pontos únicos de rota pública da SPA (Spec 46).
