package com.foxcash.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao do provedor de pagamentos Pix (PSP).
 *
 * <p>Seleciona o adapter concreto via {@code foxcash.psp.provider}
 * ({@code mock} | {@code pagarme} | {@code asaas}) e controla a verificacao de
 * assinatura do webhook ({@code foxcash.psp.webhook.*}).
 *
 * <p>Em producao o segredo do webhook e obrigatorio (env var
 * {@code FOXCASH_PSP_WEBHOOK_SECRET}) — placeholder nao-resolvido (ex.:
 * {@code ${FOXCASH_PSP_WEBHOOK_SECRET}} literal) e rejeitado no boot (fail-fast,
 * mesmo padrao do {@code CryptoConverter} na Spec 9).
 */
@ConfigurationProperties("foxcash.psp")
public class PspProperties {

    /** Provedor selecionado: mock (default dev), pagarme, asaas. */
    private String provider = "mock";

    /** Provedor de PIX de saida (Spec 36): mock (default) | (futuro) pagarme/asaas. */
    private String payoutProvider = "mock";

    /** Spec 92 — roteamento de saque por prioridade (false = usa so o payout-provider). */
    private boolean payoutRoutingEnabled = false;

    /** Spec 92 — lista de provedores de saque em ordem de prioridade (ex.: "pagarme,asaas"). */
    private String payoutRoutingProviders = "";

    /** Simula falha no payout mock (dev/tests — estorno/rollback). */
    private boolean mockPayoutFailure = false;

    /** Simula timeout na cobranca mock (Spec 28, dev/tests): a PRIMEIRA tentativa
     * por transacao falha; o retry com o mesmo transactionId passa (como no PSP real). */
    private boolean mockTimeout = false;

    /** Saldo fake da conta master no PSP (Spec 92 — sweep em dev/test). */
    private java.math.BigDecimal mockBalance = java.math.BigDecimal.ZERO;

    /** Spec 98 — kill-switch do provedor trap de honeypot (default ON). Quando
     * desligado, o {@code HoneypotPixProviderClient} não é criado e um lojista
     * honeypot NÃO roteia para o trap (falha fechada: sem trap, sem honeypot —
     * o router trata como config inválida no boot se houver lojista marcado). */
    private boolean honeypotEnabled = true;

    private final Webhook webhook = new Webhook();

    /** Webhook de saida (Spec 36) — reusa o mesmo contrato do webhook do PSP. */
    private final Webhook payoutWebhook = new Webhook();

    private final PayoutReconciliation payoutReconciliation = new PayoutReconciliation();

    private final Pagarme pagarme = new Pagarme();

    /** Configuracao do adapter real Asaas de cobranca (Spec 92 — pull-forward da 91):
     * o saldo master do Asaas (GET /finance/balance) e o adapter de cobranca (Spec 91)
     * usam a MESMA credencial. Fail-fast quando provider=asaas. */
    private final Asaas asaas = new Asaas();

    /** Configuracao do PIX de saida no Pagar.me (Spec 38) — fail-fast quando payout-provider=pagarme. */
    private final PayoutPsp payoutPagarme = new PayoutPsp();

    /** Configuracao do PIX de saida no Asaas (Spec 38) — fail-fast quando payout-provider=asaas. */
    private final PayoutPsp payoutAsaas = new PayoutPsp();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getPayoutProvider() {
        return payoutProvider;
    }

    public void setPayoutProvider(String payoutProvider) {
        this.payoutProvider = payoutProvider;
    }

    public boolean isPayoutRoutingEnabled() {
        return payoutRoutingEnabled;
    }

    public void setPayoutRoutingEnabled(boolean payoutRoutingEnabled) {
        this.payoutRoutingEnabled = payoutRoutingEnabled;
    }

    public String getPayoutRoutingProviders() {
        return payoutRoutingProviders;
    }

    public void setPayoutRoutingProviders(String payoutRoutingProviders) {
        this.payoutRoutingProviders = payoutRoutingProviders;
    }

    public boolean isMockPayoutFailure() {
        return mockPayoutFailure;
    }

    public void setMockPayoutFailure(boolean mockPayoutFailure) {
        this.mockPayoutFailure = mockPayoutFailure;
    }

    public boolean isMockTimeout() {
        return mockTimeout;
    }

    public void setMockTimeout(boolean mockTimeout) {
        this.mockTimeout = mockTimeout;
    }

    public java.math.BigDecimal getMockBalance() {
        return mockBalance;
    }

    public void setMockBalance(java.math.BigDecimal mockBalance) {
        this.mockBalance = mockBalance;
    }

    /** Spec 98 — kill-switch do provedor trap de honeypot (default ON). */
    public boolean isHoneypotEnabled() {
        return honeypotEnabled;
    }

    public void setHoneypotEnabled(boolean honeypotEnabled) {
        this.honeypotEnabled = honeypotEnabled;
    }

    public Webhook getWebhook() {
        return webhook;
    }

    public Webhook getPayoutWebhook() {
        return payoutWebhook;
    }

    public PayoutReconciliation getPayoutReconciliation() {
        return payoutReconciliation;
    }

    public Pagarme getPagarme() {
        return pagarme;
    }

    public Asaas getAsaas() {
        return asaas;
    }

    public PayoutPsp getPayoutPagarme() {
        return payoutPagarme;
    }

    public PayoutPsp getPayoutAsaas() {
        return payoutAsaas;
    }

    @PostConstruct
    void validate() {
        if (webhook.secret == null || webhook.secret.isBlank() || webhook.secret.contains("${")) {
            throw new IllegalStateException(
                    "foxcash.psp.webhook.secret e obrigatorio (FOXCASH_PSP_WEBHOOK_SECRET em producao)");
        }
        if (payoutWebhook.secret == null || payoutWebhook.secret.isBlank()
                || payoutWebhook.secret.contains("${")) {
            throw new IllegalStateException(
                    "foxcash.psp.payout-webhook.secret e obrigatorio (FOXCASH_PSP_PAYOUT_WEBHOOK_SECRET em producao)");
        }
        if ("pagarme".equalsIgnoreCase(provider)
                && (pagarme.secretKey == null || pagarme.secretKey.isBlank() || pagarme.secretKey.contains("${"))) {
            throw new IllegalStateException(
                    "foxcash.psp.pagarme.secret-key e obrigatorio quando provider=pagarme (FOXCASH_PSP_PAGARME_SECRET_KEY)");
        }
        if ("asaas".equalsIgnoreCase(provider)
                && (asaas.secretKey == null || asaas.secretKey.isBlank() || asaas.secretKey.contains("${"))) {
            throw new IllegalStateException(
                    "foxcash.psp.asaas.secret-key e obrigatorio quando provider=asaas (FOXCASH_PSP_ASAAS_SECRET_KEY)");
        }
        // Spec 92 (refino do usuário): isolação de credenciais — a chave de
        // COBRANCA nunca pode ser a chave de SAQUE/sweep (se a de cobranca vazar,
        // o ofensor não move o saldo master). Fail-fast se iguais (qualquer perfil).
        requireDistinctSecrets("pagarme", pagarme.secretKey, payoutPagarme.secretKey);
        requireDistinctSecrets("asaas", asaas.secretKey, payoutAsaas.secretKey);
        // PIX de saida (Spec 38): credencial obrigatoria quando o adquirente for escolhido.
        if ("pagarme".equalsIgnoreCase(payoutProvider)
                && (payoutPagarme.secretKey == null || payoutPagarme.secretKey.isBlank()
                    || payoutPagarme.secretKey.contains("${"))) {
            throw new IllegalStateException(
                    "foxcash.psp.payout-pagarme.secret-key e obrigatorio quando payout-provider=pagarme (FOXCASH_PSP_PAYOUT_PAGARME_SECRET_KEY)");
        }
        if ("asaas".equalsIgnoreCase(payoutProvider)
                && (payoutAsaas.secretKey == null || payoutAsaas.secretKey.isBlank()
                    || payoutAsaas.secretKey.contains("${"))) {
            throw new IllegalStateException(
                    "foxcash.psp.payout-asaas.secret-key e obrigatorio quando payout-provider=asaas (FOXCASH_PSP_PAYOUT_ASAAS_SECRET_KEY)");
        }
    }

    /**
     * Spec 92 — isolação de credenciais: charge-secret e payout-secret do MESMO
     * adquirente nunca podem ser iguais (a chave de cobrança não pode mover saldo).
     * Ignora nulos e placeholder não-resolvido (contém "${").
     */
    private static void requireDistinctSecrets(String acquirer, String chargeSecret, String payoutSecret) {
        if (chargeSecret == null || payoutSecret == null
                || chargeSecret.isBlank() || payoutSecret.isBlank()
                || chargeSecret.contains("${") || payoutSecret.contains("${")) {
            return;
        }
        if (chargeSecret.equals(payoutSecret)) {
            throw new IllegalStateException(
                    "foxcash.psp." + acquirer + ".secret-key NAO pode ser igual a foxcash.psp.payout-" + acquirer
                            + ".secret-key (isolação de credenciais, Spec 92): a chave de COBRANCA nao pode ser a chave de"
                            + " SAQUE/sweep — configure credencial payout-scoped no adquirente");
        }
    }

    public static class Webhook {

        /** Nome do header que carrega a assinatura HMAC-SHA256 do payload. */
        private String headerName = "X-Psp-Signature";

        /** Segredo usado para computar/validar a assinatura (env var em prod). */
        private String secret;

        /** true (default) => /webhooks/pix exige assinatura valida. false so para dev/tests. */
        private boolean verifySignature = true;

        /**
         * Spec 59: throttle do WARN de assinatura invalida — no maximo 1 log por
         * intervalo (segundos) mesmo em flood de 401; evita spam de log.
         */
        private long logThrottleSeconds = 10;

        public String getHeaderName() {
            return headerName;
        }

        public void setHeaderName(String headerName) {
            this.headerName = headerName;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public boolean isVerifySignature() {
            return verifySignature;
        }

        public void setVerifySignature(boolean verifySignature) {
            this.verifySignature = verifySignature;
        }

        public long getLogThrottleSeconds() {
            return logThrottleSeconds;
        }

        public void setLogThrottleSeconds(long logThrottleSeconds) {
            this.logThrottleSeconds = logThrottleSeconds;
        }
    }

    /**
     * Configuracao especifica do adapter Pagar.me (API v5).
     */
    public static class Pagarme {

        /** Secret key da API v5 (env var FOXCASH_PSP_PAGARME_SECRET_KEY em prod). */
        private String secretKey;

        /** Base URL da API v5 (default: producao). */
        private String baseUrl = "https://api.pagar.me/core/v5";

        /** Validade da cobranca Pix em segundos. */
        private int expiresInSeconds = 3600;

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public int getExpiresInSeconds() {
            return expiresInSeconds;
        }

        public void setExpiresInSeconds(int expiresInSeconds) {
            this.expiresInSeconds = expiresInSeconds;
        }
    }

    /**
     * Configuracao do adapter Asaas de cobranca (Spec 92/91): secret-key (token
     * da API v3) + base-url. Usado pelo saldo master (Spec 92 — GET
     * /finance/balance) e pelo adapter de cobranca Pix (Spec 91).
     */
    public static class Asaas {

        /** Token da API v3 (env var FOXCASH_PSP_ASAAS_SECRET_KEY em prod). */
        private String secretKey;

        /** Base URL da API v3 (default: producao). */
        private String baseUrl = "https://www.asaas.com/api/v3";

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    /**
     * Configuracao do PIX de saida por adquirente (Spec 38): secret-key
     * obrigatoria (fail-fast no boot) quando {@code payout-provider} aponta para
     * o adquirente; base-url com default de producao. O adapter concreto e spec
     * futura — esta config prepara o slot.
     */
    public static class PayoutPsp {

        /** Secret key da API do adquirente (env var em prod, sem default). */
        private String secretKey;

        /** Base URL da API do adquirente (default: producao). */
        private String baseUrl;

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    /**
     * Reconciliação de saques PROCESSING órfãos (Spec 36): job consulta o PSP
     * e encerra saques presos além de {@code pending-since-hours}.
     */
    public static class PayoutReconciliation {

        /** Cron do job (UTC, default 04:30 — fora da janela de pico de saques). */
        private String cron = "0 30 4 * * *";

        /** PROCESSING com idade maior que este limite (horas) é reconciliado. */
        private long pendingSinceHours = 2;

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public long getPendingSinceHours() {
            return pendingSinceHours;
        }

        public void setPendingSinceHours(long pendingSinceHours) {
            this.pendingSinceHours = pendingSinceHours;
        }
    }
}
