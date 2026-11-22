package com.foxcash.domain.model.withdrawal;

import com.foxcash.domain.model.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do dominio {@link AutoWithdrawConfig} (Spec 70): fabricas
 * enable/update/disable/restore e validacoes de threshold
 * ({@code <= 0}, mais de 2 casas decimais — regra M9) e {@code destinationId}
 * obrigatorio quando habilitado.
 */
class AutoWithdrawConfigTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID DESTINATION_ID = UUID.randomUUID();

    @Test
    void enableCreatesEnabledConfigWithThresholdAndDestination() {
        AutoWithdrawConfig config = AutoWithdrawConfig.enable(
                MERCHANT_ID, Money.of("1000.00"), DESTINATION_ID);

        assertThat(config.enabled()).isTrue();
        assertThat(config.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(config.threshold().amount()).isEqualByComparingTo("1000.00");
        assertThat(config.destinationId()).isEqualTo(DESTINATION_ID);
        assertThat(config.id()).isNotNull();
        assertThat(config.createdAt()).isNotNull();
        assertThat(config.updatedAt()).isNotNull();
    }

    @Test
    void enableWithoutDestinationThrows() {
        assertThatThrownBy(() -> AutoWithdrawConfig.enable(MERCHANT_ID, Money.of("100.00"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destinationId");
    }

    @Test
    void enableWithNonPositiveThresholdThrows() {
        assertThatThrownBy(() -> AutoWithdrawConfig.enable(
                MERCHANT_ID, Money.of("0.00"), DESTINATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maior que zero");
        assertThatThrownBy(() -> AutoWithdrawConfig.enable(
                MERCHANT_ID, Money.of("-5.00"), DESTINATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maior que zero");
    }

    @Test
    void updateChangesStateAndKeepsIdentity() {
        AutoWithdrawConfig original = AutoWithdrawConfig.enable(
                MERCHANT_ID, Money.of("1000.00"), DESTINATION_ID);

        AutoWithdrawConfig updated = original.update(false, Money.of("500.00"), DESTINATION_ID);

        assertThat(updated.id()).isEqualTo(original.id());
        assertThat(updated.merchantId()).isEqualTo(original.merchantId());
        assertThat(updated.enabled()).isFalse();
        assertThat(updated.threshold().amount()).isEqualByComparingTo("500.00");
        assertThat(updated.destinationId()).isEqualTo(DESTINATION_ID);
        assertThat(updated.createdAt()).isEqualTo(original.createdAt());
        assertThat(updated.updatedAt()).isAfterOrEqualTo(original.updatedAt());
    }

    @Test
    void updateEnabledWithoutDestinationThrows() {
        AutoWithdrawConfig original = AutoWithdrawConfig.enable(
                MERCHANT_ID, Money.of("1000.00"), DESTINATION_ID);

        assertThatThrownBy(() -> original.update(true, Money.of("500.00"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destinationId");
    }

    @Test
    void updateDisabledKeepsDestinationForOneClickReEnable() {
        AutoWithdrawConfig original = AutoWithdrawConfig.enable(
                MERCHANT_ID, Money.of("1000.00"), DESTINATION_ID);

        // disable() mantém o destinationId anterior — o próximo enable é 1-clique.
        AutoWithdrawConfig disabled = original.disable();
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.destinationId()).isEqualTo(DESTINATION_ID);

        AutoWithdrawConfig reEnabled = disabled.update(true, Money.of("1000.00"), disabled.destinationId());
        assertThat(reEnabled.enabled()).isTrue();
        assertThat(reEnabled.destinationId()).isEqualTo(DESTINATION_ID);
    }

    @Test
    void restoreRecreatesPersistedState() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-11T12:00:00Z");
        Instant updatedAt = Instant.parse("2026-08-11T13:00:00Z");

        AutoWithdrawConfig config = AutoWithdrawConfig.restore(
                id, MERCHANT_ID, false, Money.of("750.50"), DESTINATION_ID, createdAt, updatedAt);

        assertThat(config.id()).isEqualTo(id);
        assertThat(config.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(config.enabled()).isFalse();
        assertThat(config.threshold().amount()).isEqualByComparingTo("750.50");
        assertThat(config.destinationId()).isEqualTo(DESTINATION_ID);
        assertThat(config.createdAt()).isEqualTo(createdAt);
        assertThat(config.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void thresholdWithMoreThanTwoDecimalsIsRejectedByMoney() {
        // M9 (Spec 26): mais de 2 casas decimais é rejeitado por Money.of — o
        // domínio recebe Money já validado; aqui confirma-se o contrato do piso.
        assertThatThrownBy(() -> Money.of("10.009"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mais de 2 casas");
    }
}
