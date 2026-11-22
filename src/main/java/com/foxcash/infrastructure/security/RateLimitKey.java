package com.foxcash.infrastructure.security;

/**
 * Normalização da chave do rate limit (SEC-02 — revisão de segurança
 * 2026-08-11).
 *
 * <p>A chave do bucket era {@code requestURI|IP} (ou {@code path|principal}):
 * rotas com {@code {id}} variável no path ({@code /transactions/{id}},
 * {@code /billing/invoices/{id}}, {@code /foxcash/deliveries/{id}/...},
 * {@code /foxcash/plans/{id}/purchase}, ...) criavam um bucket NOVO a cada valor de
 * {@code {id}} — um cliente alternando IDs distintos contornava o teto por
 * completo. Esta classe colapsa o path na rota registrada (prefixo fixo) para
 * que o bucket reflita a intenção do controle: limite POR IP / POR PRINCIPAL na
 * rota, não por instância de recurso.
 *
 * <p>Prefixos sem barra final (segmento limpo): o casamento exige igualdade ou
 * início de segmento ({@code prefixo + "/"}), evitando colisões com rotas
 * futuras de nome parecido.
 */
final class RateLimitKey {

    private RateLimitKey() {
    }

    /** Rotas com {@code {id}} variável no path — agrupar por prefixo no bucket. */
    static final String[] VARIABLE_PREFIXES = {
            "/api/v1/transactions",
            "/api/v1/billing",
            "/api/v1/foxcash/deliveries",
            "/api/v1/foxcash/plans",
            "/api/v1/portal/dashboard",
            // SEC-23 (revisão de segurança 2026-08-11): as rotas de RECURSOS do
            // lojista também têm {id} no path (/merchants/{id}, /portal/plans/{id},
            // /portal/webhooks/{id}, /portal/store/*/{id}, /portal/sales/{id}, ...).
            // Sem o colapso, um lojista criava N recursos → N buckets do limite
            // por PRINCIPAL (Spec 58) → vazão N × teto (bypass de cardinalidade,
            // mesma classe do SEC-02 — que tinha corrigido apenas as rotas
            // transacionais/dashboard).
            "/api/v1/merchants",
            "/api/v1/portal/plans",
            "/api/v1/portal/store",
            "/api/v1/portal/webhooks",
            "/api/v1/portal/sales",
            "/api/v1/portal/credentials",
            "/api/v1/portal/destinations",
            "/api/v1/portal/charges",
            // Spec 74: as rotas de gestão de bots também têm {id} no path
            // (/portal/bots/{id}, /pause, /resume) — mesma classe do SEC-23.
            "/api/v1/portal/bots",
            // Spec 81: perfis de tracking também têm {id} no path
            // (/portal/tracking/{id}, /activate, /sync, /events) — mesma classe.
            "/api/v1/portal/tracking",
            // Spec 122: perfis de operação também têm {id} no path
            // (/portal/profile/{id}) — mesma classe do SEC-23.
            "/api/v1/portal/profile",
            // Spec 75: interação do renderizador num bot (/api/v1/foxcash/bots/{id}/interaction)
            // — {id} variável no path, mesma classe do SEC-23.
            "/api/v1/foxcash/bots",
            "/api/v1/foxcash/scheduled-messages",
            // Spec 69 (critério 11 — fecha o bypass de cardinalidade do SEC-23
            // no lado MASTER): as rotas de recursos do master também têm {id} no
            // path (/master/merchants/{id}/..., /master/receivables/{id}/...,
            // /master/profiles/{id}/...). Sem o colapso, um master criava N
            // lojistas/recursos → N buckets do limite por PRINCIPAL (Spec 58) →
            // vazão N × teto (mesma classe do SEC-23, que cobriu o lojista e
            // deixou o master fora). O bucket reflete a ROTA, não o recurso.
            "/api/v1/master",
            "/api/v1/admin",
            // Spec 120: rotas do Pagamento via Saldo (pay/limits) — mesmo padrão
            // SEC-23 (rate limit por principal na família de rotas, não por recurso).
            "/api/v1/portal/pvs"
    };

    /**
     * Devolve a forma estável da rota para a chave do bucket: o prefixo fixo
     * quando o path pertence a uma rota com segmento variável; o path original
     * caso contrário (rotas exatas — sem bypass possível por cardinalidade).
     */
    static String normalize(String path) {
        if (path == null) {
            return path;
        }
        String trimmed = path.endsWith("/")
                ? path.substring(0, path.length() - 1)
                : path;
        for (String prefix : VARIABLE_PREFIXES) {
            if (trimmed.equals(prefix) || trimmed.startsWith(prefix + "/")) {
                return prefix;
            }
        }
        return path;
    }
}
