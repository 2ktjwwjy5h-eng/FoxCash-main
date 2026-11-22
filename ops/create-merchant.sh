#!/usr/bin/env bash
#
# create-merchant.sh — cria uma conta de lojista (Spec 39) e confere o login
# no portal (Spec 20). Bootstrap rápido para dev/homolog/prod.
#
# Uso:
#   ./ops/create-merchant.sh                             # email/senha gerados
#   BASE_URL=http://localhost:8080 ./ops/create-merchant.sh
#   MERCHANT_EMAIL=loja@meu.com MERCHANT_PASSWORD='Senha-Segura-123' \
#     ./ops/create-merchant.sh
#
# Comportamento:
#   - POST /api/v1/merchants {name, email, password} -> 201 {id, ..., apiKey}
#     (a apiKey e devolvida UMA unica vez — nunca persistida: guarde-a)
#   - POST /api/v1/merchant-auth/login {email, password} -> 200 {token, ...}
#     (verifica que as credenciais funcionam; VERIFY_LOGIN=0 pula)
#   - Senha validada no cliente contra a PasswordPolicy (8-72 chars, com
#     minuscula + maiuscula + numero — mesmo criterio do backend)
#   - Turnstile (Spec 63): se a env de destino tiver o Turnstile ligado, o
#     cadastro via API retorna 403 turnstile_failed — o script avisa (no
#     navegador o portal resolve o widget sozinho)
#   - Sem dependencia de jq (usa jq quando disponivel; senao grep/sed)
#
# Config via env:
#   BASE_URL          (default https://foxcash.example.com)
#   MERCHANT_NAME     (default "Loja <timestamp>")
#   MERCHANT_EMAIL    (default "loja-<timestamp>@example.com")
#   MERCHANT_PASSWORD (default gerada: "Loja-<ts>-<rand>A1" — valida a politica)
#   VERIFY_LOGIN      (default 1; 0 pula a verificacao de login)
#   TURNSTILE_TOKEN   (opcional — token do widget Turnstile quando a env de
#                      destino tem o Turnstile ligado; expira em ~5 min)
#
# Exit codes:
#   0 sucesso | 1 falha (rede/validacao/erro da API) | 2 e-mail ja cadastrado

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuração
# ---------------------------------------------------------------------------

BASE_URL="${BASE_URL:-https://foxcash.example.com}"
VERIFY_LOGIN="${VERIFY_LOGIN:-1}"
TURNSTILE_TOKEN="${TURNSTILE_TOKEN:-}"

MERCHANT_NAME="${MERCHANT_NAME:-Loja $(date +%s)}"
MERCHANT_EMAIL="${MERCHANT_EMAIL:-loja-$(date +%s)-$RANDOM@example.com}"
MERCHANT_PASSWORD="${MERCHANT_PASSWORD:-Loja-$(date +%s)-$RANDOM-A1}"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

fail() { echo "[create-merchant] $*" >&2; exit 1; }

# Valida a senha com os MESMOS criterios da PasswordPolicy do backend.
validate_password() {
    local p="$1"
    if [ "${#p}" -lt 8 ] || [ "${#p}" -gt 72 ]; then
        fail "A senha deve ter entre 8 e 72 caracteres (PasswordPolicy)."
    fi
    case "$p" in *[a-z]*) ;; *) fail "A senha deve conter letra minuscula." ;; esac
    case "$p" in *[A-Z]*) ;; *) fail "A senha deve conter letra maiuscula." ;; esac
    case "$p" in *[0-9]*) ;; *) fail "A senha deve conter numero." ;; esac
}

# Escapa aspas/barra para montar o JSON inline sem quebrar.
json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }

# Extrai um campo string de um JSON: prefere jq, cai para grep/sed.
json_val() { # $1 = json, $2 = chave
    if command -v jq >/dev/null 2>&1; then
        printf '%s' "$1" | jq -r --arg k "$2" '.[$k] // empty' 2>/dev/null
    else
        printf '%s' "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 \
            | sed "s/\"$2\":\"//; s/\"$//" || true
    fi
}

# ---------------------------------------------------------------------------
# Preparação
# ---------------------------------------------------------------------------

validate_password "$MERCHANT_PASSWORD"

echo "[create-merchant] Base URL: $BASE_URL"
echo "[create-merchant] Cadastrando lojista: $MERCHANT_NAME <$MERCHANT_EMAIL>"
echo "[create-merchant] Validando a senha no cliente (8-72, min+Mai+num)... ok"

# Turnstile (Spec 63): quando TURNSTILE_TOKEN for fornecido, entra no payload.
TURNSTILE_FIELD=""
[ -n "$TURNSTILE_TOKEN" ] && TURNSTILE_FIELD=",\"turnstileToken\":\"$(json_escape "$TURNSTILE_TOKEN")\""

REGISTER_JSON=$(printf '{"name":"%s","email":"%s","password":"%s"%s}' \
        "$(json_escape "$MERCHANT_NAME")" \
        "$(json_escape "$MERCHANT_EMAIL")" \
        "$(json_escape "$MERCHANT_PASSWORD")" \
        "$TURNSTILE_FIELD")

# ---------------------------------------------------------------------------
# 1) Cadastro
# ---------------------------------------------------------------------------

RESPONSE=$(curl -sS -X POST "$BASE_URL/api/v1/merchants" \
    -H 'Content-Type: application/json' \
    -w '\n%{http_code}' \
    --data "$REGISTER_JSON") || fail "Falha de rede ao acessar $BASE_URL"

HTTP_CODE=$(printf '%s' "$RESPONSE" | tail -1)
BODY=$(printf '%s' "$RESPONSE" | sed '$d')

case "$HTTP_CODE" in
    201)
        MERCHANT_ID=$(json_val "$BODY" id)
        API_KEY=$(json_val "$BODY" apiKey)
        echo "[create-merchant] Conta criada: id=$MERCHANT_ID status=$(json_val "$BODY" status)"
        ;;
    409)
        echo "[create-merchant] E-mail ja cadastrado: $MERCHANT_EMAIL (tente outro e-mail ou faca login)." >&2
        exit 2
        ;;
    403)
        echo "[create-merchant] 403 — $BODY" >&2
        case "$BODY" in *turnstile_failed*)
            echo "[create-merchant] O Turnstile esta LIGADO nesta env (Spec 63): o cadastro via API precisa do" >&2
            echo "[create-merchant] token do widget. Crie a conta pelo navegador (o portal resolve o CAPTCHA) ou" >&2
            echo "[create-merchant] desligue foxcash.security.turnstile.* na env." >&2
            ;;
        esac
        exit 1
        ;;
    *)
        echo "[create-merchant] Falha no cadastro (HTTP $HTTP_CODE): $BODY" >&2
        exit 1
        ;;
esac

# ---------------------------------------------------------------------------
# 2) Verificação de login
# ---------------------------------------------------------------------------

if [ "$VERIFY_LOGIN" = "0" ]; then
    echo "[create-merchant] VERIFY_LOGIN=0 — pulando a verificacao de login."
else
    LOGIN_RESPONSE=$(curl -sS -X POST "$BASE_URL/api/v1/merchant-auth/login" \
        -H 'Content-Type: application/json' \
        -w '\n%{http_code}' \
        --data "{\"email\":\"$(json_escape "$MERCHANT_EMAIL")\",\"password\":\"$(json_escape "$MERCHANT_PASSWORD")\"$TURNSTILE_FIELD}") \
        || fail "Falha de rede ao verificar login em $BASE_URL"

    LOGIN_CODE=$(printf '%s' "$LOGIN_RESPONSE" | tail -1)
    LOGIN_BODY=$(printf '%s' "$LOGIN_RESPONSE" | sed '$d')

    case "$LOGIN_CODE" in
        200)
            echo "[create-merchant] Login verificado: token=$(json_val "$LOGIN_BODY" token | cut -c1-12)… expira em $(json_val "$LOGIN_BODY" expiresAt)"
            ;;
        401)
            echo "[create-merchant] AVISO: login retornou 401 logo apos o cadastro (inesperado)." >&2
            ;;
        403)
            echo "[create-merchant] AVISO: login 403 — Turnstile ligado nesta env; no navegador o portal resolve o widget." >&2
            ;;
        *)
            echo "[create-merchant] AVISO: login retornou HTTP $LOGIN_CODE: $LOGIN_BODY" >&2
            ;;
    esac
fi

# ---------------------------------------------------------------------------
# 3) Resumo
# ---------------------------------------------------------------------------

echo
echo "=== FoxCash — conta de lojista criada ==="
echo "URL de login : $BASE_URL/login"
echo "E-mail       : $MERCHANT_EMAIL"
echo "Senha        : $MERCHANT_PASSWORD"
echo "API key      : $API_KEY   # devolvida UMA vez — guarde-a (Spec 12)"
echo "=========================================="
