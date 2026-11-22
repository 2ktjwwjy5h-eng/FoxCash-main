package com.foxcash.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Testes do fail-closed do step-up (Spec 69, F4 — critério T4.2): lista de
 * permissoes vazia ou com valor fora do catalogo {@code MasterPermission}
 * derruba o boot; defaults = as 4 acoes sensiveis; janela default 15 min.
 */
class MasterStepUpPropertiesTest {

    @Test
    void defaultsAreTheSixSensitivePermissionsAndFifteenMinutes() {
        MasterStepUpProperties properties = new MasterStepUpProperties();
        assertDoesNotThrow(properties::validate);

        assertThat(properties.getRequiredPermissions()).containsExactly(
                "withdrawal.administrative",
                "receivable.write_off",
                "invoice.collect",
                "master.profiles.manage",
                // Spec 98: débito manual e banimento também exigem step-up.
                "merchant.debit",
                "merchant.ban");
        assertThat(properties.getWindow()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void emptyListFailsClosed() {
        MasterStepUpProperties properties = new MasterStepUpProperties();
        properties.setRequiredPermissions(List.of());

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("required-permissions")
                .hasMessageContaining("vazia");
    }

    @Test
    void unknownPermissionFailsClosed() {
        MasterStepUpProperties properties = new MasterStepUpProperties();
        properties.setRequiredPermissions(List.of("withdrawal.administrative", "merchant.delete"));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalido")
                .hasMessageContaining("merchant.delete");
    }

    @Test
    void isRequiredReflectsConfiguredList() {
        MasterStepUpProperties properties = new MasterStepUpProperties();
        assertThat(properties.isRequired(com.foxcash.domain.model.master.MasterPermission.WITHDRAWAL_ADMINISTRATIVE))
                .isTrue();
        assertThat(properties.isRequired(com.foxcash.domain.model.master.MasterPermission.MERCHANT_SUSPEND))
                .isFalse();
    }
}
