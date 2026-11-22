package com.foxcash.infrastructure.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do fail-fast do PIX de saída (Spec 38): {@code PayoutPsp} por adquirente
 * — a secret-key é obrigatória quando {@code payout-provider} aponta para o
 * adquirente (mesmo padrão da cobrança Spec 10/11); e sem o provider definido em
 * produção o boot falha (sem default — validado no profile prod).
 */
class PspPropertiesTest {

    private PspProperties properties;

    @BeforeEach
    void setUp() {
        properties = new PspProperties();
        properties.getWebhook().setSecret("webhook-secret");
        properties.getPayoutWebhook().setSecret("payout-webhook-secret");
    }

    @Test
    void pagarmePayoutWithoutSecretKeyFailsFast() {
        properties.setPayoutProvider("pagarme");
        properties.getPayoutPagarme().setSecretKey(null);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FOXCASH_PSP_PAYOUT_PAGARME_SECRET_KEY");
    }

    @Test
    void pagarmePayoutWithSecretKeyPasses() {
        properties.setPayoutProvider("pagarme");
        properties.getPayoutPagarme().setSecretKey("sk_test_payout");

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void asaasPayoutWithoutSecretKeyFailsFast() {
        properties.setPayoutProvider("asaas");
        properties.getPayoutAsaas().setSecretKey(null);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FOXCASH_PSP_PAYOUT_ASAAS_SECRET_KEY");
    }

    @Test
    void placeholderSecretKeyFailsFast() {
        // Placeholder não-resolvido (${...} literal) também é rejeitado (Spec 9).
        properties.setPayoutProvider("pagarme");
        properties.getPayoutPagarme().setSecretKey("${FOXCASH_PSP_PAYOUT_PAGARME_SECRET_KEY}");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FOXCASH_PSP_PAYOUT_PAGARME_SECRET_KEY");
    }

    @Test
    void mockPayoutDoesNotRequireAdquirerSecrets() {
        // Default de dev: payout-provider=mock não exige secret de adquirente.
        properties.setPayoutProvider("mock");

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    // ---------- Cobrança (provider) e caminhos válidos do PIX de saída ----------

    @Test
    void defaultsProviderToMock() {
        assertThat(properties.getProvider()).isEqualTo("mock");
        assertThat(properties.getPayoutProvider()).isEqualTo("mock");
    }

    @Test
    void pagarmeProviderWithoutSecretKeyFailsFast() {
        properties.setProvider("pagarme");
        properties.getPagarme().setSecretKey(null);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FOXCASH_PSP_PAGARME_SECRET_KEY");
    }

    @Test
    void pagarmeProviderWithBlankSecretKeyFailsFast() {
        properties.setProvider("pagarme");
        properties.getPagarme().setSecretKey("   ");

        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pagarmeProviderWithPlaceholderSecretFailsFast() {
        properties.setProvider("pagarme");
        properties.getPagarme().setSecretKey("${FOXCASH_PSP_PAGARME_SECRET_KEY}");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FOXCASH_PSP_PAGARME_SECRET_KEY");
    }

    @Test
    void pagarmeProviderWithSecretKeyPasses() {
        properties.setProvider("pagarme");
        properties.getPagarme().setSecretKey("sk_test_pagarme");

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void asaasPayoutWithSecretKeyPasses() {
        properties.setPayoutProvider("asaas");
        properties.getPayoutAsaas().setSecretKey("sk_test_asaas");

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void asaasPayoutWithPlaceholderSecretFailsFast() {
        properties.setPayoutProvider("asaas");
        properties.getPayoutAsaas().setSecretKey("${FOXCASH_PSP_PAYOUT_ASAAS_SECRET_KEY}");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FOXCASH_PSP_PAYOUT_ASAAS_SECRET_KEY");
    }

    @Test
    void mockFlagsAndPayoutReconciliationDefaults() {
        // L66/L74: flags de simulação default false (mutantes booleanos).
        assertThat(properties.isMockPayoutFailure()).isFalse();
        assertThat(properties.isMockTimeout()).isFalse();
        // L90: reconcialiação de saques PROCESSING — default de produção.
        assertThat(properties.getPayoutReconciliation()).isNotNull();
        assertThat(properties.getPayoutReconciliation().getCron()).isEqualTo("0 30 4 * * *");
    }

    @Test
    void mockFlagsReflectSetValues() {
        properties.setMockPayoutFailure(true);
        properties.setMockTimeout(true);

        assertThat(properties.isMockPayoutFailure()).isTrue();
        assertThat(properties.isMockTimeout()).isTrue();
    }

    // ---------- Spec 92: isolação de credenciais (charge ≠ payout) ----------

    @Test
    void pagarmeChargeSecretEqualToPayoutSecretFailsFast() {
        // A chave de COBRANCA não pode ser a chave de SAQUE/sweep (isolação de
        // credenciais — se a de cobranca vazar, o ofensor não move o saldo master).
        properties.getPagarme().setSecretKey("sk_charge");
        properties.getPayoutPagarme().setSecretKey("sk_charge");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NAO pode ser igual")
                .hasMessageContaining("pagarme");
    }

    @Test
    void asaasChargeSecretEqualToPayoutSecretFailsFast() {
        properties.getAsaas().setSecretKey("sk_charge");
        properties.getPayoutAsaas().setSecretKey("sk_charge");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NAO pode ser igual")
                .hasMessageContaining("asaas");
    }

    @Test
    void distinctChargeAndPayoutSecretsPass() {
        properties.getPagarme().setSecretKey("sk_charge");
        properties.getPayoutPagarme().setSecretKey("sk_payout");

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void placeholderSecretsSkipIsolationCheck() {
        // Placeholder não-resolvido não dispara o teste de isolação (não há
        // credencial real ainda — o fail-fast de placeholder é outro caminho).
        properties.getPagarme().setSecretKey("${FOXCASH_PSP_PAGARME_SECRET_KEY}");
        properties.getPayoutPagarme().setSecretKey("${FOXCASH_PSP_PAYOUT_PAGARME_SECRET_KEY}");

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }
}
